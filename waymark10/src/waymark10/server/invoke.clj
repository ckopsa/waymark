(ns waymark10.server.invoke
  "THE transition algorithm — every write in every application is one
  call of invoke! (or create!). The step order is waymark9
  invoke.py's, reproduced exactly; each numbered step below matches
  the Python original:

   1. resolve the action                            (404)
   2. idempotency-key requirement + stored replay   (428 / replay)
   3. load the row FOR UPDATE — one lock per invocation
   4. the row's law resolves the guards (judgment; resident stub
      until phase 5)
   5. out of state → natural replay, else concealment (404), else
      wrong-state with becomes-available              (409)
   6. the fence: If-Match vs the current etag         (412)
   7. input validation, field-keyed errors            (422)
   8. NATURAL REPLAY BEFORE GUARDS (2.0 ordering): if the latest
      transition is this action with this digest and the state is its
      outcome, the first execution's guards already passed — and
      re-running them can honestly deny. Replay is the byte-honest
      answer.
   9. the guard loop: warnings collect (acknowledged names pass and
      are recorded), hard denies refuse               (409)
  10. dry-run exits: {:valid true} + warnings
  11. the handler (pure: row → row), tamper refusal
  12. derived materialization, in the same commit
  13. state advance, version+1, summary
  14. transition append (defined_by = the row's law) + save
  15. idempotency store — replay is byte-identical

  Returns {:row … :transition … :replayed? … :valid? …}; rendering to
  the envelope is phase 3's job."
  (:require [clojure.string :as str]
            [waymark10.derived :as derived]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn etag [kind id version]
  (str "W/\"" (name kind) "-" id "-v" version "\""))

(defn- body-digest [body]
  (wire/digest (or body {})))

(defn- rdef-of [engine kind]
  (or (get-in engine [:resources kind])
      (throw (p/not-found kind "?"))))

(defn- make-ctx [engine tx mode principal]
  (t/ctx {:principal principal
          :now ((:now-fn engine))
          :services (:services engine)
          :mode mode
          :actor-of (fn [row transition]
                      ;; the newest matching transition's actor id
                      (some (fn [rec]
                              (when (= (:action rec) transition)
                                (get-in rec [:actor :id])))
                            (store/transitions
                             (:storage engine) tx
                             {:kind (:kind row) :resource-id (:id row)}
                             {:newest-first true})))}))

(defn- decode-row
  "The load boundary owns coercion: stored JSON becomes schema types
  (ISO strings → LocalDate) so laws compare real values."
  [rdef row]
  (update row :data #(schema/decode (:schema rdef) %)))

(defn- encode-row [rdef row]
  (update row :data #(schema/encode (:schema rdef) %)))

(defn- summary-of [rdef row]
  (summary/render (:summary rdef) (assoc row :kind (:kind rdef))))

(defn- natural-replay
  "The latest transition, when it is this same action with this same
  input digest and the row already sits at its outcome. Guards do NOT
  re-run — the first execution's guards already passed."
  [engine tx rdef row defn digest]
  (let [[latest] (store/transitions (:storage engine) tx
                                    {:kind (:kind rdef) :resource-id (:id row)}
                                    {:newest-first true :limit 1})]
    (when (and latest
               (= (:action latest) (:name defn))
               (= (:input-digest latest) digest)
               (= (:state row) (:to defn)))
      {:row row :transition latest :replayed? :natural})))

(defn- probe-hidden-only?
  "Concealment holds out-of-state too: when a hide-flagged guard
  denies on probe, the wire must 404, not narrate."
  [defn row ctx]
  (boolean
   (some (fn [guard]
           (let [[v d] (g/evaluate guard row nil (assoc ctx :mode :probe))]
             (and (t/deny? v) (:hide d))))
         (:guards defn))))

(defn- run-guards
  "→ {:warned [{:name :reason :remedies}] :overridden [names]} or
  throws guard-refused on the first hard deny."
  [defn row inp ctx acknowledged rdef]
  (reduce
   (fn [acc guard]
     (let [[v d] (g/evaluate guard row inp ctx)]
       (if-not (t/deny? v)
         acc
         (if (= :warning (:severity d))
           (if (contains? acknowledged (:name d))
             (update acc :overridden conj (:name d))
             (update acc :warned conj
                     {:name (:name d)
                      :reason (g/render-reason d v row)
                      :remedies (:remedies d)}))
           (throw (p/guard-refused
                   (:name defn) (:state row)
                   (g/render-reason d v row)
                   {:guard (:name d)
                    :remedies (:remedies d)
                    :becomes-available (g/becomes-available d v row)}
                   {:kind (:kind rdef) :id (:id row)
                    :summary (summary-of rdef row)}))))))
   {:warned [] :overridden []}
   (:guards defn)))

(defn- finish!
  "Steps 11–15: handler, tamper refusal, materialize, advance,
  append, save, idempotency store."
  [engine tx rdef row defn inp ctx
   {:keys [digest overridden idempotency-key principal correlation-id]}]
  (let [now (:now ctx)
        handled (if-some [h (:handler defn)]
                  (let [out (h row inp ctx)]
                    (when-not (map? out)
                      (throw (t/definition-error
                              (str "handler for " (name (:name defn))
                                   " must return the row"))))
                    out)
                  row)
        _ (when-some [facts (seq (derived/tampered rdef row handled now))]
            (throw (p/derived-tampered (:name defn) (vec facts))))
        materialized (derived/materialize rdef handled now)
        advanced (-> materialized
                     (assoc :state (:to defn))
                     (update :version inc))
        advanced (assoc advanced :summary (summary-of rdef advanced))
        record (store/append-transition!
                (:storage engine) tx
                {:kind (:kind rdef)
                 :resource-id (:id row)
                 :action (:name defn)
                 :from-state (:state row)
                 :to-state (:to defn)
                 :actor {:type (name (:type principal))
                         :id (:id principal)
                         :display (:display principal)}
                 :law-revision (:law-revision row)
                 :input-digest digest
                 :inputs (when (:record defn) inp)
                 :acknowledged (not-empty (vec overridden))
                 :correlation-id correlation-id
                 :idempotency-key idempotency-key
                 :summary (:summary advanced)})
        saved (store/save-row! (:storage engine) tx (:kind rdef)
                               (encode-row rdef (dissoc advanced :summary))
                               (:version row))]
    (when idempotency-key
      (store/idempotency-store!
       (:storage engine) tx idempotency-key (:kind rdef) (:name defn) digest
       200 (wire/write-json {:id (:id saved) :state (name (:state advanced))
                             :version (:version advanced)
                             :summary (:summary advanced)})
       "application/waymark+json"))
    {:row (decode-row rdef saved) :transition record}))

(defn invoke!
  "One write. opts: :principal (required), :if-match, :idempotency-key,
  :dry-run, :acknowledged (set of guard names), :correlation-id."
  [engine kind id action-name body
   {:keys [principal if-match idempotency-key dry-run acknowledged
           correlation-id]
    :or {acknowledged #{}}}]
  (let [rdef (rdef-of engine kind)
        defn (or (some-> (get-in rdef [:actions action-name])
                         (assoc :name action-name))
                 (throw (p/no-such-action kind action-name)))
        digest (body-digest body)]
    (store/with-tx (:storage engine)
      (fn [tx]
        ;; 2. idempotency: requirement, then stored replay
        (when (and (not dry-run)
                   (not (get-in defn [:safety :idempotent]))
                   (nil? idempotency-key))
          (throw (p/idempotency-key-required action-name)))
        (if-some [hit (when (and (not dry-run) idempotency-key)
                        (store/idempotency-lookup (:storage engine) tx
                                                  idempotency-key kind))]
          ;; a key replays only its own action + body; anything else
          ;; is reuse, refused before touching the row
          (if (and (= (:action hit) action-name)
                   (= (:request-digest hit) digest))
            {:replayed? :idempotency :response hit}
            (throw (p/idempotency-key-reuse action-name)))
          ;; 3. the row lock
          (let [raw (or (store/load-row (:storage engine) tx kind id
                                        {:for-update (not dry-run)})
                        (throw (p/not-found kind id)))
                row (decode-row rdef raw)
                ;; 4. the row's law judges the row — resident stub until
                ;; the definitions machinery (phase 5) serves stored trees
                ctx (make-ctx engine tx (if dry-run :dry-run :invoke) principal)]
            (if-not (contains? (:from defn) (:state row))
              ;; 5. out of state: replay, conceal, or narrate
              (or (natural-replay engine tx rdef row defn digest)
                  (when (probe-hidden-only? defn row ctx)
                    (throw (p/not-found kind id)))
                  (throw (p/wrong-state action-name (:state row) (:from defn)
                                        {:kind kind :id id
                                         :summary (summary-of rdef row)})))
              (do
                ;; 6. the fence
                (when (get-in defn [:safety :fence])
                  (let [current (etag kind id (:version row))]
                    (when (not= (some-> if-match str/trim) current)
                      (throw (p/version-conflict action-name
                                                 {:kind kind :id id
                                                  :etag current})))))
                ;; 7. input validation — decode first (validation
                ;; speaks schema types), closed maps refuse unknowns
                (let [inp (if (:input defn)
                            (let [decoded (schema/decode (:input defn) (or body {}))]
                              (when-some [errors (schema/closed-errors (:input defn) decoded)]
                                (throw (p/schema-invalid action-name errors)))
                              decoded)
                            (if (seq body)
                              (throw (p/schema-invalid
                                      action-name
                                      (into {} (map (fn [[k _]] [k ["unexpected field"]])) body)))
                              nil))]
                  ;; 8. natural replay before guards
                  (or (when (and (not dry-run) (get-in defn [:safety :idempotent]))
                        (natural-replay engine tx rdef row defn digest))
                      ;; 9. the guard loop
                      (let [{:keys [warned overridden]}
                            (run-guards defn row inp ctx acknowledged rdef)]
                        (cond
                          ;; 10. dry-run exits before any effect
                          dry-run {:valid? true :warnings (not-empty warned)}
                          (seq warned) (throw (p/warning-refused action-name warned))
                          :else (finish! engine tx rdef row defn inp ctx
                                         {:digest digest
                                          :overridden overridden
                                          :idempotency-key idempotency-key
                                          :principal principal
                                          :correlation-id correlation-id})))))))))))))

(defn create!
  "The initial-state transition. Validates against :create-schema (or
  :schema), runs create guards with row nil, runs :on-create, then
  materializes, inserts, and logs under the kind's create action name."
  [engine kind body {:keys [principal acknowledged correlation-id id]
                     :or {acknowledged #{}}}]
  (let [rdef (rdef-of engine kind)
        model (or (:create-schema rdef) (:schema rdef))
        digest (body-digest body)]
    (store/with-tx (:storage engine)
      (fn [tx]
        (let [inp (schema/decode model (or body {}))
              _ (when-some [errors (schema/closed-errors model inp)]
                  (throw (p/schema-invalid :create errors)))
              ctx (make-ctx engine tx :invoke principal)
              {:keys [warned overridden]}
              (reduce
               (fn [acc guard]
                 (let [[v d] (g/evaluate guard nil inp ctx)]
                   (if-not (t/deny? v)
                     acc
                     (if (= :warning (:severity d))
                       (if (contains? acknowledged (:name d))
                         (update acc :overridden conj (:name d))
                         (update acc :warned conj {:name (:name d)
                                                   :reason (g/render-reason d v nil)
                                                   :remedies (:remedies d)}))
                       (throw (p/guard-refused :create nil
                                               (g/render-reason d v nil)
                                               {:guard (:name d)
                                                :remedies (:remedies d)}
                                               nil))))))
               {:warned [] :overridden []}
               (:create-guards rdef))]
          (when (seq warned)
            (throw (p/warning-refused :create warned)))
          (let [now (:now ctx)
                row {:id (or id (str (random-uuid)))
                     :state (:initial rdef)
                     :version 1
                     :data inp
                     :shape (:shape rdef 1)
                     :owner (:id principal)
                     :law-revision (get-in engine [:current-law kind] 1)}
                row (if-some [oc (:on-create rdef)] (oc row ctx) row)
                row (derived/materialize rdef row now)
                row (assoc row :summary (summary-of rdef row))
                _ (store/insert-row! (:storage engine) tx kind
                                     (encode-row rdef (dissoc row :summary)))
                record (store/append-transition!
                        (:storage engine) tx
                        {:kind kind
                         :resource-id (:id row)
                         :action (first (:create-action-names rdef))
                         :from-state nil
                         :to-state (:initial rdef)
                         :actor {:type (name (:type principal))
                                 :id (:id principal)
                                 :display (:display principal)}
                         :law-revision (:law-revision row)
                         :input-digest digest
                         :acknowledged (not-empty (vec overridden))
                         :correlation-id correlation-id
                         :summary (:summary row)})]
            {:row row :transition record}))))))

(defn engine
  "Phase-2 wiring: storage + resources, kinds ensured. Grows into
  server.engine in phase 3."
  [{:keys [storage resources services now-fn]}]
  (let [by-kind (into {} (map (fn [r] [(:kind r) r])) resources)]
    (doseq [r resources]
      (store/ensure-kind! storage r))
    {:storage storage
     :resources by-kind
     :services services
     :now-fn (or now-fn (fn [] (java.time.Instant/now)))}))
