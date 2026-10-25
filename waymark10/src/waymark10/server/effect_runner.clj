(ns waymark10.server.effect-runner
  "The effect runner (docs/spec-integrations.md § 4, build step 6): it
  takes each `pending` effect_call and makes the call it names, after
  the door's transaction committed and never inside it.

  THE CALL IS THE INTEGRATION'S. The runner finds the live integration
  whose `server` holds the power's row, and calls through
  `gate-proxy/invoke-for` under that integration's grant, as
  `integration:<id>`, with the door's actor as `on_behalf_of`
  (X-Waymark-Acts-For) and the call's key as Idempotency-Key. So the
  power entry's filter, `allow` globs and `approval` judge it as they
  judge any caller.

  IT ENDS HONESTLY. An answer is `done` and the row lands through
  `<door>_landed`; a definite refusal is `failed` with the server's
  sentence; a call held for a person is `held`, and the held call's
  own ending settles it later. A call that did not answer is retried
  with the same key only when the power entry says `idempotent: true`,
  three tries with a backoff; otherwise, or after the third, it is
  `unknown`, and the row lands `:fails-to` saying it may or may not
  have been sent. Each try is written on the call before the wire
  (`note_try`), so a call a crash left `pending` after a try is not
  sent again unless its server dedupes it."
  (:require [waymark10.effect :as effect]
            [waymark10.server.clock :as clock]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.integrations :as integrations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(def max-tries
  "How many times an idempotent call is tried before it is `unknown`."
  3)

(def maybe-sent
  "R-4.4's sentence for a call that did not answer."
  "It may or may not have been sent.")

(defonce ^:private in-flight (atom #{}))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 effects: " parts))))

(defn- cut [s n]
  (let [s (str s)] (if (> (count s) (long n)) (subs s 0 n) s)))

(defn- load-one [eng kind id]
  (let [st (:storage eng)]
    (some->> (store/with-tx st #(store/load-row st % kind (str id) {}))
             (inv/decode-row (get (inv/resources eng) kind)))))

(defn- rows-of [eng kind filt]
  (let [rd (get (inv/resources eng) kind)
        st (:storage eng)]
    (if rd
      (mapv #(inv/decode-row rd %)
            (store/with-tx st #(store/query-rows st % kind filt {:limit 500})))
      [])))

;; ── the endings ──────────────────────────────────────────────────────

(defn- end!
  "Walk one of the call's own doors, as the engine. A call another pass
  ended first refuses the door, and that is no news. → true when it
  moved."
  [eng cid door inp]
  (try (inv/invoke! eng :effect_call (str cid) door inp {:principal inv/effects-actor})
       true
       (catch clojure.lang.ExceptionInfo e
         (warn! "call " cid " did not take " (name door) " (" (ex-message e) ")")
         false)))

(defn- land-row!
  "Move the row that asked through its hidden door, naming the call. A
  row that has left the state that means asked refuses it, and the
  call's own ending stands."
  [eng c door-of inp]
  (let [{:keys [target_kind target door]} (:data c)
        hidden (door-of (str door))]
    (try (inv/invoke! eng (keyword (str target_kind)) (str target) hidden
                      (assoc inp :effect_call (str (:id c)))
                      {:principal inv/effects-actor})
         (catch clojure.lang.ExceptionInfo e
           (warn! target_kind " " target " did not take " (name hidden)
                  " (" (ex-message e) ")")))))

(defn- done! [eng c answer]
  ;; an undo (§ 5) lands nothing: its row left with the door that unwound
  (when (and (end! eng (:id c) :mark_done
                   {:answer (cut (wire/write-json (or answer {})) integrations/answer-cap)})
             (nil? (effect/undo-of c)))
    (land-row! eng c effect/landed-door (if (map? answer) {:answer answer} {}))))

(defn- failed! [eng c door reason]
  (let [reason (cut (or (not-empty (str reason)) "The call failed.") 480)]
    (when (end! eng (:id c) door {:reason reason})
      (land-row! eng c effect/failed-door {:reason reason}))))

;; ── the call ─────────────────────────────────────────────────────────

(defn- json-object [t]
  (try (let [v (wire/read-json (str t))] (when (map? v) v))
       (catch Exception _ nil)))

(defn- unwrapped
  "A tool that returns a JSON string comes back from FastMCP as
  structuredContent {result: \"{…}\"}: the object that string holds,
  else nil."
  [sc]
  (when (= 1 (count sc))
    (let [v (or (get sc :result) (get sc "result"))]
      (when (string? v) (json-object v)))))

(defn answer-of
  "What `(result …)` reads of a CallToolResult: its structuredContent
  (the JSON object inside a lone `result` string, when it wraps one),
  or the JSON object in a text part, else {}."
  [payload]
  (let [sc (:structuredContent payload)]
    (if (map? sc)
      (or (unwrapped sc) sc)
      (or (some #(some-> (:text %) json-object) (:content payload))
          {}))))

(defn- words-of [payload]
  (or (some #(some-> (:text %) str not-empty) (:content payload))
      "The server refused the call."))

(defn- sentence-of [e]
  (str (or (:sentence (client/said e)) (:detail (ex-data e)) (ex-message e))))

(defn- integration-of
  "The integration whose `server` holds the row `power` lives on:
  {:row} for a live one, {:wait true} when one is there and not live
  yet, nil when none is."
  [eng power]
  (when-some [sid (some-> (servers/resolve-tool eng power) :row :id str)]
    (let [rows (filter #(= sid (some-> (get-in % [:data :server]) str))
                       (rows-of eng :integration {}))]
      (if-some [live (first (filter #(= :live (:state %)) rows))]
        {:row live}
        (when (some #(contains? #{:proposed :paused} (:state %)) rows)
          {:wait true})))))

(defn- visibility-of [eng irow]
  (when-some [gid (some-> (get-in irow [:data :grant]) str not-empty)]
    (when (= :active (:standing (grants/grant-standing eng gid)))
      (grants/visibility eng gid (integrations/principal irow)))))

(defn- attempt
  "One call through the power door as the integration. → [:answered
  payload], [:held held-call-id], [:refused sentence] or [:unknown
  sentence]."
  [eng vis irow c]
  (let [{:keys [power idempotency_key why on_behalf_of input]} (:data c)
        args (cond-> (or (some-> input str not-empty wire/read-json) {})
               (some? why) (update :why #(or % why)))]
    (try
      (let [res (gate/invoke-for eng vis (str power) args
                                 {:caller (integrations/principal-id irow)
                                  :principal (assoc (integrations/principal irow)
                                                    :acts-for (str on_behalf_of))
                                  :idempotency-key (str idempotency_key)})]
        (cond
          (true? (get-in res [:structuredContent :held]))
          [:held (str (get-in res [:structuredContent :held_call]))]

          (true? (:isError res)) [:refused (words-of res)]

          :else [:answered res]))
      (catch clojure.lang.ExceptionInfo e
        (let [status (:status (ex-data e))]
          (if (and (p/problem? e) (number? status) (< (long status) 500))
            [:refused (sentence-of e)]
            [:unknown (sentence-of e)])))
      (catch Exception e
        [:unknown (str (ex-message e))]))))

(defn- backoff-ms [eng n]
  (* (long (get-in eng [:services :integrations :effect-backoff-ms] 1000))
     (bit-shift-left 1 (dec (long n)))))

(defn- call!
  [eng c]
  (let [cid (str (:id c))
        power (str (get-in c [:data :power]))
        idem? (true? (:idempotent (servers/resolve-tool eng power)))
        tries (long (or (get-in c [:data :tries]) 0))
        {irow :row wait :wait} (integration-of eng power)
        vis (when irow (visibility-of eng irow))]
    (cond
      wait nil

      (nil? irow)
      (failed! eng c :mark_failed
               (str "No live integration calls the server " power
                    " lives on, so it was not sent."))

      (and (pos? tries) (or (not idem?) (>= tries (long max-tries))))
      (failed! eng c :mark_unknown
               (str "A try of " power " was under way when the runner stopped. "
                    maybe-sent))

      (nil? vis)
      (failed! eng c :mark_failed
               (str "The grant integration " (get-in irow [:data :name])
                    " wears is not active, so " power " was not sent."))

      :else
      (loop [n (inc tries)]
        (when (end! eng cid :note_try {:tries n})
          (let [[how x] (attempt eng vis irow c)]
            (case how
              :answered (done! eng c (answer-of x))
              :held (end! eng cid :mark_held {:held_call x})
              :refused (failed! eng c :mark_failed x)
              :unknown (if (and idem? (< n (long max-tries)))
                         (do (Thread/sleep (long (backoff-ms eng n)))
                             (recur (inc n)))
                         (failed! eng c :mark_unknown
                                  (str power " did not answer (" (cut x 300) "). "
                                       maybe-sent))))))))))

(defn run-call!
  "Make the call of one `pending` effect_call, once: a call another pass
  is making in this process is left to it, and any other state is left
  as it is."
  [eng cid]
  (let [cid (str cid)
        [before] (swap-vals! in-flight conj cid)]
    (when-not (contains? before cid)
      (try
        (when-some [c (load-one eng :effect_call cid)]
          (when (= :pending (:state c))
            (call! eng c)))
        (finally (swap! in-flight disj cid))))))

(defn settle!
  "A `held` call whose held_call ended: `done` lands its answer, and
  `refused`, `failed` or `expired` is the call's failure."
  [eng cid]
  (when-some [c (load-one eng :effect_call cid)]
    (when (= :held (:state c))
      (when-some [h (some->> (get-in c [:data :held_call]) str not-empty
                             (load-one eng :held_call))]
        (case (:state h)
          :done
          (done! eng c (answer-of (try (wire/read-json (str (get-in h [:data :answer])))
                                       (catch Exception _ nil))))

          (:refused :failed :expired :withdrawn)
          (let [r (some-> (get-in h [:data :reason]) str not-empty)]
            (failed! eng c :mark_failed
                     (str "The held call ended " (name (:state h))
                          (if r (str ": " r) "."))))
          nil)))))

;; ── the consumer and the sweep ───────────────────────────────────────

(defn- effect-door? [eng kind action]
  (let [a (get-in (inv/resources eng) [kind :actions action])]
    (some? (or (:effect a) (:unwinds a)))))

(defn- run-order
  "The order calls are made in: each effect's own call, then the undos,
  newest effect first (§ 5) — by when the call each undoes was kept."
  [eng cs]
  (let [born (fn [c]
               (some-> (rows-of eng :effect_call {:idempotency_key (effect/undo-of c)
                                                  :target (str (get-in c [:data :target]))})
                       first :created-at))]
    (concat (remove effect/undo-of cs)
            (sort-by born #(compare %2 %1) (filter effect/undo-of cs)))))

(defn handle-transition!
  "One committed transition: an effect door's makes its row's pending
  calls, and a held call's ending settles the calls it held. Never
  throws: a parked cursor would stop every later call."
  [eng t]
  (try
    (let [kind (some-> (:kind t) keyword)
          action (some-> (:action t) name keyword)
          rid (str (:resource-id t))]
      (cond
        (= :held_call kind)
        (doseq [c (rows-of eng :effect_call {:held_call rid :state :held})]
          (settle! eng (:id c)))

        (and kind action (effect-door? eng kind action))
        (doseq [c (run-order eng (rows-of eng :effect_call {:target rid :state :pending}))]
          (run-call! eng (:id c)))))
    (catch Exception e
      (warn! "transition " (:id t) " could not be run — " (ex-message e))))
  nil)

(defn sweep!
  "Make every call still `pending`, and settle every `held` one whose
  held call ended: what a crash, a pause or a missed transition left."
  [eng]
  (doseq [c (run-order eng (rows-of eng :effect_call {:state :pending}))]
    (try (run-call! eng (:id c))
         (catch Exception e
           (warn! "call " (:id c) " could not be made — " (ex-message e)))))
  (doseq [c (rows-of eng :effect_call {:state :held})]
    (try (settle! eng (:id c))
         (catch Exception e
           (warn! "call " (:id c) " could not be settled — " (ex-message e))))))

(defn start-runner!
  "The runner: a durable log consumer (consumer:effects) and a sweep
  every `:sweep-ms`. The integrations module elects it beside the
  applier, so one process per database makes the calls. opts:
  :dispatcher, :poll-ms, :sweep-ms."
  [eng {:keys [sweep-ms] :or {sweep-ms 30000} :as opts}]
  (let [stop (clock/every! {:name "waymark10-effects-sweep" :period-ms sweep-ms
                            :f #(sweep! eng)})]
    {:consumer (consumers/register-consumer!
                eng :effects (fn [tr] (handle-transition! eng tr))
                (select-keys opts [:dispatcher :poll-ms]))
     :stop stop}))

(defn stop-runner! [{:keys [consumer stop]}]
  (when stop (stop))
  (some-> consumer consumers/stop-consumer!)
  nil)
