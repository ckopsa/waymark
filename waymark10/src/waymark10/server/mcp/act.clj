(ns waymark10.server.mcp.act
  "The invoke family's bodies, carved out of waymark10.server.mcp
  (ticket 066b9c29): the staging a recording self walk shows
  (docs/spec-agent-demo-walks.md § 2), the confirm gate, the MCP origin
  key and the counts read off it, `waymark_invoke` with its create,
  bulk and scheduled calls, `waymark_pursue` and `rehearse`, and the
  bodies of `waymark_history`, `waymark_in_flight` and
  `waymark_resolve`. A pure move — mcp keeps a stub for each var it
  still calls, and this namespace requires mcp.wire's plumbing rather
  than mcp back."
  (:require [clojure.string :as str]
            [waymark10.client :as client]
            [waymark10.confirm :as confirm]
            [waymark10.server.collections :as coll]
            [waymark10.server.grants :as grants]
            [waymark10.server.grants.visibility :as grant-vis]
            [waymark10.server.in-flight :as in-flight]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp.wire :as mcp-wire]
            [waymark10.server.members :as members]
            [waymark10.server.presence :as presence]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.walks :as walks]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URLDecoder URLEncoder)))

(set! *warn-on-reflection* true)

;; mcp.wire's plumbing, one stub for each var this namespace calls

(def ^:private bound-sitting mcp-wire/bound-sitting)
(def ^:private door mcp-wire/door)
(def ^:private request mcp-wire/request)
(def ^:private body-text mcp-wire/body-text)
(def ^:private body-json mcp-wire/body-json)
(def ^:private query-string mcp-wire/query-string)

(def ^:private result mcp-wire/result)
(def ^:private value-result mcp-wire/value-result)
(def ^:private pass-through mcp-wire/pass-through)
(def ^:private refusal mcp-wire/refusal)

(def ^:private rdef-of mcp-wire/rdef-of)
(def ^:private declared-action mcp-wire/declared-action)
(def ^:private collection-door mcp-wire/collection-door)
(def ^:private wire-action mcp-wire/wire-action)

(def ^:private verbatim-json mcp-wire/verbatim-json)
(def ^:private return-of mcp-wire/return-of)
(def ^:private id-of-self mcp-wire/id-of-self)
(def ^:private row-doc? mcp-wire/row-doc?)
(def ^:private row-summary mcp-wire/row-summary)
(def ^:private invoke-summary mcp-wire/invoke-summary)
(def ^:private answer mcp-wire/answer)

;; ── staging (docs/spec-agent-demo-walks.md § 2) ─────────────────────
;;
;; While, and only while, the session's principal has a self walk in
;; `recording`, the calls it already makes are shown: each reports to
;; its own presence in process, the call `presence-report` makes for a
;; browser, with `walks/self-recorder`'s tap built from the session's
;; visibility. Nothing is posted over HTTP, no stream is opened, and
;; nothing sleeps: the recording keeps the beats' true times and replay
;; paces them (`REPLAY_BURST_GAP`). There is no tool for it and no
;; argument that turns it on. Creating the self walk is the opt-in.

(def ^:private typed-beats
  "The most beats one staged dialog types. A call with more arguments
  types all but the last beat one by one, and the rest in the last."
  12)

(defn- stage*
  "What a staged beat needs, {:reg :principal :tap :visible? :summary-of}, or nil
  when this call makes no beat: the engine's presence registry is not
  running, the session is anonymous, the kind is the recording's own
  (`walks/record-own!`'s rule), or the principal is recording no self
  walk. It never throws."
  [eng session rdef]
  (try
    (let [principal (:principal session)
          reg (some-> (:runtime eng) deref :presence)]
      (when (and reg principal
                 (not= (:id t/anonymous) (:id principal))
                 (not (contains? #{"walk" "walk_frame"}
                                 (some-> (:kind rdef) name)))
                 (walks/recording-own? eng principal))
        (when-some [rec (walks/self-recorder eng principal (:visibility session))]
          {:reg reg
           :principal principal
           :tap (:presence rec)
           :visible? (presence/self-visible? eng (:visibility session))
           ;; a ref's row by its summary line, as the session may read it
           :summary-of (let [read (walks/ref-summaries eng (:visibility session))]
                         (fn [k id] (:summary (read k id))))})))
    (catch Exception _ nil)))

(defn beat!
  "One staged beat: `self`, and a `ui` part when the call shows one. A
  self the session could not GET makes none: the gate `presence-report`
  keeps for a browser on a private kind, kept for every kind here. It
  never throws: a walk that could not take a frame does not fail the
  call it shows. `since`, {:from self}, is where the gaze was before
  the call (`gaze-before`), for a call whose own read moves it."
  ([st self ui] (beat! st self ui nil))
  ([{:keys [reg principal tap visible?]} self ui since]
  (try
    (when (visible? self)
      (presence/report! reg principal self ui tap since))
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 mcp staging: a beat was not reported -"
                 (ex-message e)))))
  nil))

(defn gaze-before
  "Where the stage's principal is looking now, as {:from self}, read
  before a call whose route marks the gaze itself: a grant-scoped GET
  is its caller's gaze already (`presence/read!`), and that door has no
  tap, so the beat after it would find the gaze on the row and the walk
  would take no `move`. It never throws."
  [{:keys [reg principal]}]
  (try
    {:from (presence/gaze reg (:id principal))}
    (catch Exception _ nil)))

(defn- sheet-close!
  "The beat that closes the quest's sheet, when the stage's principal
  has one open: a `ui` beat with no `quest` part, on the row the gaze is
  on. With no sheet open it makes none. It never throws."
  [{:keys [reg principal] :as st}]
  (try
    (when (presence/sheet reg (:id principal))
      (when-some [self (presence/gaze reg (:id principal))]
        (beat! st self {:dialog nil})))
    (catch Exception _ nil))
  nil)

(defn stage
  "`stage*` for a call that is not the sheet's own: a quest's sheet a
  rehearsal left open closes first, as Not now does on a person's
  screen."
  [eng session rdef]
  (when-some [st (stage* eng session rdef)]
    (sheet-close! st)
    st))

(defn- quest-goal
  "The goal a quest's create names in `input`, {:self :action} as the
  page's tap spells it (the row's href with no query), or nil when the
  input names no row's door."
  [input]
  (let [given (when (map? input)
                (into {} (map (fn [[k v]] [(keyword (name k)) v])) input))
        self (presence/normalize-self (:self given))
        action (:action given)]
    (when (and (string? self) (str/starts-with? self "/api/")
               (or (string? action) (keyword? action))
               (seq (name action)))
      {:self (first (str/split self #"\?")) :action (name action)})))

(defn- stage-sheet!
  "A rehearsed quest create's beat: the quest's sheet, as the page's tap
  on a shut door reports it (questSheet, ui/140-links-access.js). The
  gaze moves to the goal's row and the `ui` beat carries `quest`
  {goal, label, seen {ok, body}}: the label is the goal door's own on
  its row, and `body` is the rehearsal's `preview` or the refusal's
  title and detail. An answer with neither makes no beat, and so does a
  goal row the session could not GET (`beat!`). → `resp`, its body read
  to text so the answer reads it again. It never throws."
  [eng call session st goal since resp]
  (let [resp (assoc resp :body (body-text resp))]
    (try
      (let [ok? (<= 200 (:status resp 500) 299)
            body (body-json resp)
            p (when ok? (:preview body))]
        (when (or (map? p) (and (not ok?) (map? body)))
          (let [env-resp (call (request session :get (:self goal) {}))
                entry (when (<= 200 (:status env-resp 500) 299)
                        (get-in (body-json env-resp)
                                [:unavailable (wire-action (keyword (:action goal)))]))
                label (or (get-in entry [:display :label])
                          (presence/door-label eng (:self goal) (:action goal)))
                shut (or (:shut_reason p) (:reason entry))
                seen (if ok?
                       {:ok true
                        :body {:preview
                               (cond-> (assoc (select-keys p [:goal :blocked_reason
                                                              :plan_is_estimate])
                                              :plan (mapv presence/sheet-step
                                                          (filter map? (:plan p))))
                                 shut (assoc :shut_reason shut))}}
                       {:ok false :body (select-keys body [:title :detail])})]
            (beat! st (:self goal)
                   {:quest (cond-> {:goal goal :seen seen}
                             (string? label) (assoc :label label))}
                   since))))
      (catch Exception _ nil))
    resp))

(defn- typed-steps
  "The `fields` of each typing beat, in order: each adds one value, and
  past `typed-beats` arguments the last adds all that are left."
  [given ks]
  (let [n (count ks)]
    (map #(select-keys given (take % ks))
         (if (> n (long typed-beats))
           (concat (range 1 typed-beats) [n])
           (range 1 (inc n))))))

(defn- stage-dialog!
  "An invoke's beats before its write: the gaze moves to `self`, the
  form opens with no value, and each argument is typed in the order of
  the action's input schema (`presence/typed-keys`, which leaves a
  secret one out). A create's `self` is the collection, and its form is
  the kind's create. When the last beat already shows this form with
  these values (a rehearsal typed them, or a refused call left them),
  nothing is typed again. Each typing beat names in `focus` the
  argument it adds (the last of them, when it adds several), so a
  replay lights that field. A typing beat also carries `labels`: the
  summary line of the row each ref argument it shows names, as the
  session may read it (`presence/ref-labels`), so a replay names the
  row and not its id. A row the session may not see has no label. The
  labels are read from the beat's own fields, so a `kind-from`
  argument has its label from the beat that also shows its sibling,
  and not before. An action the kind does not declare opens no form,
  and only the gaze moves."
  [eng st self aname input]
  (let [dialog {:self self :action (name aname)}
        ks (presence/typed-keys eng self (name aname) input)
        given (into {} (map (fn [[k v]] [(keyword (name k)) v])) input)
        ;; one read for each row, however many beats show it
        summary-of (memoize (fn [kind id] ((:summary-of st) kind id)))
        typed (fn [fields]
                (let [named (try (presence/ref-labels eng self (name aname)
                                                      fields summary-of)
                                 (catch Exception _ nil))]
                  (cond-> {:dialog dialog :fields fields}
                    named (assoc :labels named))))
        shown? (and ks
                    (try (presence/shows? (:reg st) (:id (:principal st))
                                          (assoc (typed (select-keys given ks))
                                                 :focus (some-> (last ks) name)))
                         (catch Exception _ false)))]
    (if (or (nil? ks) shown?)
      (beat! st self nil)
      (do (beat! st self {:dialog dialog :fields {}})
          (doseq [fields (typed-steps given ks)]
            (beat! st self (assoc (typed fields)
                                  :focus (name (nth ks (dec (count fields)))))))))))

(defn- stage-close!
  "The beat after a write that landed: the form closes. A refused call
  and a rehearsal make none, so the form stays open with what was
  typed, as it would on a person's screen."
  [st self]
  (beat! st self {:dialog nil}))

(defn- lift-caption
  "An invoke's `caption` and `caption_field` may also ride inside `input`
  (docs/spec-agent-demo-walks.md § 3): a client holding a tool list from
  before the two arguments shipped cannot send them beside it. → `args`
  with each one moved out of `input`; the one beside `input` wins when
  the call carries both. A key the door's own input declares is the
  door's argument and stays where it is."
  [rdef aname {:keys [input] :as args}]
  (let [form (or (get-in rdef [:actions aname :input]) (:schema rdef))
        declared (when (vector? form)
                   (into #{} (comp (filter vector?) (map first) (filter keyword?))
                         (rest form)))
        lifted (when (map? input)
                 (into [] (comp (filter #(#{"caption" "caption_field"} (name %)))
                                (remove #(contains? declared (keyword (name %)))))
                       (keys input)))]
    (reduce (fn [a k]
              (let [top (keyword (name k))]
                (cond-> (update a :input dissoc k)
                  (not (contains? a top)) (assoc top (get input k)))))
            args lifted)))

(defn stage-caption!
  "A call's `caption` (docs/spec-agent-demo-walks.md § 3): one `caption`
  frame in the caller's recording self walk, written before the call's
  beats and anchored to the step's own `self`: the screen for a query,
  the row for a get, the form for an invoke, and beside `caption_field`
  when the call names one. → nil, or the refusal when the caption
  cannot be shown (`walks/caption-problem`). With no recording self
  walk the arguments are accepted and do nothing."
  [eng session rdef self aname {:keys [caption caption_field]}]
  (when (and (some? caption) (stage* eng session rdef))
    (let [c {:self self :action (some-> aname name) :field caption_field
             :text caption}]
      (if-some [why (walks/caption-problem eng c)]
        (refusal (p/problem :invalid-arguments 422 "That caption cannot be shown"
                            {:detail why}))
        (do (walks/caption! eng (:principal session) (:visibility session) c)
            nil)))))

;; the confirm gate — the one refusal this namespace issues in its own
;; voice, and the reason the spec calls MCP a safety surface rather
;; than a convenience: the engine already computes the sentence, and
;; echoing it is the price of a dangerous verb.

(def consequence-of
  "The confirm gate's text, read off the row's own rendered entry — the
  declaration's `:consequence` rides the wire as display.description,
  a per-origin map already resolved against this row's state. Same
  accessor waymark10.client uses, and it must stay the same one: two
  readings of one sentence is a gate that can be walked around. It is
  `confirm/consequence-of`, which a namespace this one requires may
  call without requiring this one."
  confirm/consequence-of)

(defn confirm-refusal [aname sentence given]
  (p/problem
   :confirm-required 409 "Confirmation required"
   {:detail (str "safety.confirm is true for " (name aname)
                 " — echo its consequence sentence back as `acknowledge`,"
                 " exactly as written.")
    :action (name aname)
    :consequence sentence
    :acknowledge {:argument "acknowledge" :value sentence}
    :given (when given given)
    :remedies [(str "Call waymark_invoke again with acknowledge: "
                    (pr-str sentence))]}))

(def origin-prefix
  "The `Idempotency-Key` prefix an invoke from this door rides under —
  the MCP sibling of `feed/origin-prefix`. One string, named once,
  because `origin-of` reads exactly what `origin-key` writes."
  "mcp")

(defn origin-key
  "The `Idempotency-Key` this door stamps on every invoke it forwards:

      mcp/waymark10-connector-claude%3Acolton/9f3c1a

  Three slash-separated segments — the prefix, the principal's id
  percent-encoded (a delegate's id is `<client>:<sub>`, and a member
  id is whatever the registrar minted, so the encoding is what keeps
  the key's own separators honest), and a nonce.

  NO NEW COLUMN, and the same reason as the feed's: `invoke/finish!`
  and `create-in-tx!` stamp a present key into the transition row
  whether or not the action is idempotent, so actions-from-this-door
  is one prefix away, per principal, retroactive to the day the
  convention landed. This is the experiment docs/spec-connector-door.md
  exists for — actions taken FROM THE CONVERSATION, counted off the
  transition log — and the principal segment is what tells the
  connector's delegates (`waymark10-connector-claude:<sub>`) from
  every other caller at the same door, which the spec's bare `claude/`
  spelling could not.

  THE NONCE IS LOAD-BEARING, exactly as `feed/origin-key` says: the
  idempotency store is scoped (key, kind) and a key returning with a
  different digest is a 409, so two invokes of one verb on one row by
  one principal must not collide — and a retry that SHOULD replay is
  the client's own job, done with the header it already has."
  ^String [principal-id nonce]
  (str origin-prefix "/"
       (URLEncoder/encode (str principal-id) "UTF-8") "/" nonce))

(defn origin-of
  "The MCP origin a key names, or nil for every key that is not one —
  `{:principal :nonce}`. A key of any other shape (the feed's, a
  client's own) is somebody else's and this reader says so by
  answering nil rather than by guessing."
  [k]
  (when (string? k)
    (let [segs (str/split k #"/")]
      (when (and (= 3 (count segs)) (= origin-prefix (first segs))
                 (not-empty (nth segs 1)) (not-empty (nth segs 2)))
        {:principal (URLDecoder/decode ^String (nth segs 1) "UTF-8")
         :nonce (nth segs 2)}))))

(def log-scan-cap
  "Transitions `actions-from-mcp` folds for one read — the feed's own
  bound, at the feed's own number, for the feed's own reason:
  truncation announced beats totality implied."
  500)

(defn actions-from-mcp
  "The connector experiment's number, made queryable: how many writes
  arrived through this door, by principal, kind and action.

  It folds the newest `:limit` transitions (`log-scan-cap` by default)
  and keeps the ones whose `idempotency_key` `origin-of` recognizes,
  optionally narrowed to principals whose id starts with
  `:principal-prefix` — `\"waymark10-connector-claude:\"` is every
  delegate the claude.ai connector minted, which is the count
  spec-connector-door § The experiment asks for. → `{:total
  :by-principal :by-kind :by-action :scanned :reached-cap}`.

  Same trade as `feed/actions-from-feed`, recorded there: a bounded
  newest-first window scanned in memory rather than a LIKE pushed into
  four stores, with `:since` for a caller walking further back and
  `:reached-cap` saying when the window filled."
  ([eng] (actions-from-mcp eng {}))
  ([eng {:keys [principal-prefix limit since]}]
   (let [st (:storage eng)
         n (long (or limit log-scan-cap))
         log (store/with-tx st
               (fn [tx] (store/transitions st tx (cond-> {} since (assoc :since since))
                                           {:limit n :newest-first true})))
         hits (into []
                    (keep (fn [tr]
                            (when-some [o (origin-of (:idempotency-key tr))]
                              (when (or (nil? principal-prefix)
                                        (str/starts-with? (:principal o) principal-prefix))
                                (assoc o :action (name (:action tr))
                                       :kind (name (:kind tr)))))))
                    log)]
     {:principal-prefix principal-prefix
      :total (count hits)
      :by-principal (frequencies (map :principal hits))
      :by-kind (frequencies (map :kind hits))
      :by-action (frequencies (map (fn [h] (str (:kind h) "." (:action h)))
                                   hits))
      :scanned (count log)
      :reached-cap (= (count log) n)})))

(def app-origin-prefix
  "The `Idempotency-Key` prefix of a person's tap. It is not `mcp/`, so
  `actions-from-mcp` keeps counting the model's writes alone."
  "mcp-app")

(defn taps-from-app
  "The MCP Apps experiment's number (docs/spec-mcp-apps.md, The count):
  how many writes a person tapped in Claude, by principal, kind and
  action.

  `actions-from-mcp`'s sibling, over the same newest-first window of
  `:limit` transitions (`log-scan-cap` by default, `:since` to walk
  further back). It keeps the ones whose `idempotency_key` starts with
  `app-origin-prefix` and a slash, the key `waymark_app_act` builds:
  `mcp-app/<url-encoded bearer id>/<nonce>`. A replayed act lands no
  second transition, so it is counted once. → `{:total :by-principal
  :by-kind :by-action :scanned :reached-cap}`."
  ([eng] (taps-from-app eng {}))
  ([eng {:keys [limit since]}]
   (let [st (:storage eng)
         n (long (or limit log-scan-cap))
         log (store/with-tx st
               (fn [tx] (store/transitions st tx (cond-> {} since (assoc :since since))
                                           {:limit n :newest-first true})))
         lead (str app-origin-prefix "/")
         hits (into []
                    (keep (fn [tr]
                            (let [k (:idempotency-key tr)]
                              (when (and (string? k) (str/starts-with? k lead))
                                {:principal (URLDecoder/decode
                                             (str (second (str/split k #"/")))
                                             "UTF-8")
                                 :action (name (:action tr))
                                 :kind (name (:kind tr))}))))
                    log)]
     {:total (count hits)
      :by-principal (frequencies (map :principal hits))
      :by-kind (frequencies (map :kind hits))
      :by-action (frequencies (map (fn [h] (str (:kind h) "." (:action h)))
                                   hits))
      :scanned (count log)
      :reached-cap (= (count log) n)})))

(defn- invoke-headers
  "Rules 3 and 4 of the affordance-following client (waymark10.client),
  which this tool is one of — with one thing the generic client does
  not do: EVERY invoke carries an Idempotency-Key, `origin-key`'s,
  idempotent or not. For a non-idempotent action it is the client
  rule (an ambiguous retry replays instead of doubling); for every
  action it is the door signing its work, so `actions-from-mcp` can
  count what came through here. A fenced action carries the If-Match
  of the row we READ, so the write lands on the row the agent saw or
  not at all — and `fenced?` is the caller's answer to 'is this door
  fenced', read off the envelope's entry when it has one and off the
  DECLARATION when a guard shut the door (ticket ec814cac)."
  [session fenced? etag warnings]
  (cond-> {"idempotency-key" (origin-key (get-in session [:principal :id])
                                         (random-uuid))}
    (and fenced? etag)
    (assoc "if-match" etag)
    (seq warnings)
    (assoc "waymark-acknowledge" (str/join "," (map name warnings)))))

(defn- create-row
  "The create door: POST the collection. An agent with no grant still
  reaches this one for approval_request, which is how it asks for
  everything else — so the create verb had to be reachable, and
  `waymark_invoke` with no id is where it went rather than a seventh
  tool. `seen`, when given, is shown the route's answer before it is
  read and answers the one to read (`stage-sheet!`)."
  [call session rdef aname input dry-run warnings acknowledge return as-id & [seen]]
  (let [door (inv/create-door rdef aname)
        verbs (into (mapv name (:create-action-names rdef))
                    (map (comp name :action))
                    (:creates rdef))
        names (set (map p/wire-key verbs))
        confirm? (confirm/create-confirm? rdef)
        sentence (when confirm? (confirm/create-consequence rdef input))]
    (cond
      (not (contains? names (p/wire-key aname)))
      (refusal (p/problem :no-such-action 404 "Not found"
                          {:detail (str "No id was given, so this is a create — "
                                        "but " (name (:kind rdef)) " creates with "
                                        (pr-str verbs)
                                        ", not " (pr-str (name aname)) ".")}))

      ;; a create's confirm gate (ticket 13e3b323): the sentence is
      ;; filled from THIS input, as the row door's is read off its row
      (and confirm? (not= acknowledge sentence))
      (refusal (confirm-refusal aname sentence acknowledge))

      :else
      (answer
       ((or seen identity)
        (call (request session :post (str "/api/" (:plural rdef)
                                          ;; an extra create door's own route
                                          (when door
                                            (str "/-/" (name (:action door)))))
                       {:body (or input {})
                        :query (when dry-run "dry_run=1")
                        :headers (cond-> {"idempotency-key"
                                          (origin-key (get-in session [:principal :id])
                                                      (random-uuid))}
                                   (seq warnings)
                                   (assoc "waymark-acknowledge"
                                          (str/join "," (map name warnings)))
                                   ;; the router's create gate reads the echo
                                   confirm? (assoc "waymark-confirm" acknowledge)
                                   ;; the cast member a clone's seat acts as
                                   as-id (assoc "x-waymark-as" as-id))})))
       return
       #(when (row-doc? %) (invoke-summary aname nil %))))))

(defn- bulk-confirm-refusal
  "The confirm gate at the bulk door, in the same voice as the single
  one. `ids` shares one input and would share one acknowledgement,
  which is exactly the blanket the gate exists to refuse: a confirm
  action over ids is told to send items. Over items, every item that
  did not echo its own sentence is named with the sentence it owes,
  and nothing ran — one retry fixes all of them."
  [aname sentence missing]
  (p/problem
   :confirm-required 409 "Confirmation required"
   (if (nil? missing)
     {:detail (str "safety.confirm is true for " (name aname)
                   " — acknowledge is per item, and ids would share one. "
                   "Send items: [{id, acknowledge}] with each row's "
                   "consequence sentence echoed exactly as written.")
      :action (name aname)
      :consequence sentence
      :acknowledge {:argument "items[].acknowledge" :value sentence}
      :remedies [(str "Call waymark_invoke again with items, each carrying "
                      "acknowledge: " (pr-str sentence))]}
     {:detail (str "safety.confirm is true for " (name aname) " — "
                   (count missing) " item(s) did not echo their consequence "
                   "sentence back as their own `acknowledge`, exactly as "
                   "written. Nothing ran.")
      :action (name aname)
      :consequence sentence
      :acknowledge {:argument "items[].acknowledge" :value sentence}
      :items (mapv (fn [{:keys [id given consequence]}]
                     (cond-> {:id id :consequence consequence}
                       given (assoc :given given)))
                   missing)
      :remedies [(str "Call waymark_invoke again with every item carrying "
                      "acknowledge: " (pr-str sentence))]})))

(defn- bulk-item-sentence
  "The sentence one item owes. A string consequence rides the
  collection entry's own display.description and is every row's; a
  per-origin map resolves by the row's state, which only the row can
  say — so that one case reads the row through the real route (its
  404 is the engine's concealment, and the item will draw it again
  at the door)."
  [call session rdef aname col-sentence id]
  (or col-sentence
      (let [consequence (get-in rdef [:actions aname :safety :consequence])]
        (when (map? consequence)
          (let [resp (call (request session :get
                                    (str "/api/" (:plural rdef) "/" id) {}))]
            (when (<= 200 (:status resp 500) 299)
              (get consequence (keyword (:state (body-json resp))))))))
      "This action requires confirmation."))

(defn- bulk-rows
  "Many rows through the bulk door (waymark-pywy.4): `ids` is the
  phase-7 shape — one shared input, one acknowledged-warnings set —
  and `items` gives each row its own input, its own acknowledge and
  its own acknowledge_warnings. Both read the COLLECTION envelope
  first, as the single door reads the row: it is where the bulk
  entry's href and consequence sentence come from, and an ungranted
  kind 404s there before any verb is composed. The engine's report —
  per-item ok/refusal in the guard's own words, on_error honored,
  dry_run's per-item verdicts — passes through byte for byte; the
  confirm gate is the one refusal issued here, and it is per item."
  [call session rdef aname {:keys [ids items where input dry_run on_error
                                   acknowledge_warnings]}]
  (let [col-self (str "/api/" (:plural rdef))
        col-resp (call (request session :get col-self {}))]
    (if-not (<= 200 (:status col-resp 500) 299)
      (pass-through col-resp)
      (let [col (body-json col-resp)
            entry (get-in col [:actions (wire-action aname)])
            confirm? (boolean (get-in rdef [:actions aname :safety :confirm]))
            col-sentence (get-in entry [:display :description])
            missing (when (and confirm? items)
                      (into []
                            (keep (fn [it]
                                    (let [s (bulk-item-sentence call session rdef aname
                                                                col-sentence (:id it))]
                                      (when (not= (:acknowledge it) s)
                                        {:id (:id it) :consequence s
                                         :given (:acknowledge it)}))))
                            items))]
        (cond
          (and confirm? (or ids where))
          (refusal (bulk-confirm-refusal aname (or col-sentence
                                                    "This action requires confirmation.")
                                         nil))

          (seq missing)
          (refusal (bulk-confirm-refusal aname (or col-sentence
                                                    (:consequence (first missing)))
                                         missing))

          :else
          (pass-through
           (call (request session :post
                          (or (:href entry) (str col-self "/-/" (name aname)))
                          {:body (cond-> (if items
                                           {:items (mapv (fn [it]
                                                           (cond-> {:id (:id it)}
                                                             (:input it)
                                                             (assoc :input (:input it))
                                                             (seq (:acknowledge_warnings it))
                                                             (assoc :acknowledge
                                                                    (vec (:acknowledge_warnings it)))))
                                                         items)}
                                           (cond-> (or input {})
                                             ids (assoc :ids ids)
                                             where (assoc :where where)))
                                   on_error (assoc :on_error on_error))
                           :query (when dry_run "dry_run=1")
                           ;; the call's key stores the report, as any
                           ;; bulk call's does, and every item's
                           ;; transition carries it as a stamp
                           ;; (invoke.clj bulk!, waymark-pywy.5) — so
                           ;; `actions-from-mcp` counts N for N rows
                           :headers (invoke-headers session nil nil
                                                    acknowledge_warnings)}))))))))

(def schedule-keys
  "The scheduler's fields `waymark_invoke` carries beside `at` (R-7.2),
  spelled as the scheduled_action create spells them."
  [:zone :validity :conditions :expect_state :expect_refusals :grace_seconds
   :tell :acknowledge :acknowledge_warnings])

(defn- in-zone
  "An instant as the wire spells it, written on the clock of `zone`: a
  scheduler reads its own time and not UTC (R-7.2). What this cannot
  read is answered as it came."
  [run-at zone]
  (try
    (.format java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME
             (.atZone (java.time.Instant/parse (str run-at))
                      (java.time.ZoneId/of (str zone))))
    (catch Exception _ run-at)))

(defn schedule-call
  "`at` on waymark_invoke, and on waymark_power with `{tool}` for its
  target (docs/spec-scheduled-actions.md R-7.2, R-4.4): the
  call is not made. It is written as a scheduled_action create — the
  kind, the action and the id become its `target`, `at` its `run_at` —
  and POSTed to that kind's own collection, so the scheduling check,
  the zone rules, the limits and the grant are judged by the one door
  that judges them, and its refusal comes back as it wrote it.

  The answer to a stored call is an ANSWER, not a refusal: the row's
  id, `run_at` on the scheduler's clock, the rule and the summary line.
  `dry_run` rehearses the same create and passes its verdict through.
  Many rows at once are refused here: a scheduled bulk call is a
  scheduled job, and that is its own design.

  The create carries the sitting this session is bound to, as an
  invoke does, so the stored call is that sitting's act and its answer
  is one served answer on it. The run at `run_at` counts on no sitting
  (R-4.2)."
  [eng call session target {:keys [ids items where at input dry_run] :as args}]
  (if (or ids items where)
    (refusal (p/problem :invalid-arguments 422 "One call at a time"
                        {:detail (str "`at` schedules one call: one row's action, or one create. "
                                      "A scheduled bulk call is a scheduled job, which is not "
                                      "built. Give `id` in place of ids or items, one call for "
                                      "each row.")}))
    (let [sdef (rdef-of eng "scheduled_action")
          resp (call (assoc (request session :post (str "/api/" (:plural sdef))
                                     {:body (cond-> (into {:target target
                                                           :run_at at}
                                                          (filter (comp some? val))
                                                          (select-keys args schedule-keys))
                                              input (assoc :input input))
                                      :query (when dry_run "dry_run=1")
                                      :headers {"idempotency-key"
                                                (origin-key (get-in session [:principal :id])
                                                            (random-uuid))}})
                            :waymark10/sitting
                            (bound-sitting eng (:mcp-session-id session))))
          row (when (and (not dry_run) (<= 200 (:status resp 500) 299))
                (body-json resp))
          ;; the envelope carries the address, not the id
          sid (id-of-self (:self row))]
      (if sid
        (value-result {:scheduled true
                       :scheduled_action sid
                       :run_at (in-zone (get-in row [:data :run_at])
                                        (get-in row [:data :zone]))
                       :zone (get-in row [:data :zone])
                       :validity (get-in row [:data :validity])
                       :summary (:summary row)})
        ;; a refusal, or a rehearsal's verdict: the door's own words
        (pass-through resp)))))

(defn invoke
  "Read the row, then move it.

  The read is not overhead: it is what makes concealment honest (a row
  outside the grant 404s here, before any verb is composed), it is
  where the consequence sentence and the ETag come from, and it is
  what lets this tool follow the envelope's OWN href rather than
  building an address out of string parts — rule 1 of the client
  rules, and the reason a prompt-injected \"POST
  /api/plans/{id}/-/delete_everything\" has nothing to hold on to.

  An action the row does not advertise is still SENT: the engine's
  refusal (404 for a concealed door, 409 with the guard's own sentence
  for an unavailable one) is more honest than this namespace
  re-narrating what render already said."
  [eng call session {:keys [kind id ids items where action dry_run acknowledge
                            acknowledge_warnings if_version at] :as args}]
  (let [return (return-of args)
        ;; the move's one sentence of why (ticket 1d8f5f1c) rides every
        ;; request this call makes (`request`)
        session (if-some [why (some-> (:why args) str str/trim not-empty)]
                  (assoc session :why why)
                  session)
        rdef (rdef-of eng kind)
        aname (or (declared-action rdef action)
                  (inv/door-name rdef (keyword action)))
        ;; an aliased name is read as its new door here, and posted
        ;; as written so the engine logs `via_alias`
        via (when (contains? (:aliases rdef) (keyword action)) (keyword action))
        ;; a caption inside `input` is the call's own (§ 3): it leaves
        ;; `input` here, before the form is typed and the door judges it
        args (lift-caption rdef aname args)
        input (:input args)
        ;; captioned (§ 3): the line is written before the call's beats,
        ;; on the row's form, or on the collection for a create and a
        ;; bulk call. The two arguments go no further than here
        refused (stage-caption! eng session rdef
                                (str "/api/" (:plural rdef)
                                     (when (and id (not (or ids items where)))
                                       (str "/" id)))
                                aname args)
        args (dissoc args :caption :caption_field)
        ;; `as` (clone guard 2/3) rides one row's or one create's
        ;; request as X-Waymark-As, and the router judges it
        as-id (some-> (:as args) str not-empty)]
    (cond
      refused refused

      (and as-id (or (some? at) ids items where))
      (refusal (p/problem :invalid-arguments 422 "Not a call to act as"
                          {:detail (str "`as` acts as a cast member on one row or "
                                        "one create, now. Leave out `at`, `ids` "
                                        "and `items`, or leave out `as`.")}))

      ;; a safe collection door answers now and writes nothing, so there
      ;; is no call to store for a later time: `at` is refused here, in
      ;; this door's own sentence, before the scheduling door is asked
      (and (some? at) (nil? id) (not (or ids items where))
           (collection-door rdef aname))
      (refusal (p/problem :invalid-arguments 422 "Not a call to schedule"
                          {:detail (str "`" (name aname) "` on " (name (:kind rdef))
                                        " answers now and writes nothing, so it "
                                        "cannot be scheduled with `at`. Call it "
                                        "without `at`, at the time you want the "
                                        "answer.")}))

      ;; `at`: the call is stored for its time, not made (R-7.2)
      (some? at)
      (let [self (when-not (or ids items where)
                   (str "/api/" (:plural rdef) (when id (str "/" id))))
            st (when self (stage eng session rdef))
            _ (when st (stage-dialog! eng st self aname input))
            res (schedule-call eng call session
                               (cond-> {:kind (name (:kind rdef)) :action (name aname)}
                                 id (assoc :id (str id)))
                               args)]
        ;; staged (§ 2): the scheduled_action it made is the write
        (when (and st (not dry_run) (not (:isError res)))
          (stage-close! st self))
        res)

      (and (or ids items where) id)
      (refusal (p/problem :invalid-arguments 422 "One target, please"
                          {:detail (str "id names one row; ids, items and where name many. "
                                        "Give one of the four.")}))

      (< 1 (count (filter some? [ids items where])))
      (refusal (p/problem :invalid-arguments 422 "One target, please"
                          {:detail (str "Give ids (one shared input), items (each row its "
                                        "own) or where (the rows a filter matches): one, "
                                        "not both.")}))

      ;; the bulk door answers a report, not a row: `return` has
      ;; nothing to project there and the report passes through as
      ;; it is under both spellings
      (or ids items where)
      (do
        ;; staged (§ 2): the gaze goes to the collection, then the writes
        (when-some [st (stage eng session rdef)]
          (beat! st (str "/api/" (:plural rdef)) nil))
        (bulk-rows call session rdef aname args))

      ;; a safe collection door (:collection-doors): no row is read and
      ;; none is moved, so the route's answer passes through as it is.
      ;; `dry_run` changes nothing: the call writes nothing, so it is
      ;; its own rehearsal
      (and (nil? id) (collection-door rdef aname))
      (pass-through
       (call (request session :post (:href (collection-door rdef aname))
                      {:body (or input {})})))

      (nil? id)
      (let [self (str "/api/" (:plural rdef))
            raw (stage* eng session rdef)
            ;; staged (§ 2): a quest's create is the quest's sheet, not
            ;; the quests create form. Rehearsed, it is the tap on the
            ;; goal's shut door, and the sheet opens with what the
            ;; rehearsal answered; made while that sheet is open, it is
            ;; Accept, and the sheet closes after the write
            goal (when (and raw (= "quest" (name (:kind rdef)))
                            (contains? (set (map p/wire-key (:create-action-names rdef)))
                                       (p/wire-key aname)))
                   (quest-goal input))
            preview? (boolean (and goal dry_run))
            accept? (boolean
                     (and goal (not dry_run)
                          (= goal (try (:goal (presence/sheet (:reg raw)
                                                              (:id (:principal raw))))
                                       (catch Exception _ nil)))))
            sheet? (or preview? accept?)
            ;; any other create closes a sheet left open (Not now)
            st (when raw (when-not sheet? (sheet-close! raw)) raw)
            since (when preview? (gaze-before st))
            ;; staged (§ 2): the form opens on the collection and is
            ;; typed before the write, so a refusal leaves it open
            _ (when (and st (not sheet?)) (stage-dialog! eng st self aname input))
            res (create-row call session rdef aname input dry_run
                            acknowledge_warnings acknowledge return as-id
                            (when preview?
                              #(stage-sheet! eng call session st goal since %)))]
        ;; staged (§ 2): the create landed, so the form closes
        (when (and st (not dry_run) (not (:isError res)))
          (if accept? (sheet-close! st) (stage-close! st self)))
        res)

      :else
      (let [self (str "/api/" (:plural rdef) "/" id)
            env-resp (call (request session :get self {}))]
        (if-not (<= 200 (:status env-resp 500) 299)
          ;; concealed, gone, or never here — the engine's own 404
          (pass-through env-resp)
          (let [env (body-json env-resp)
                entry (get-in env [:actions (wire-action aname)])
                ;; A door a GUARD shut is not in `actions` at all — it
                ;; is in `unavailable`, which carries a reason and its
                ;; remedies and no `safety` — so the fence flag falls
                ;; back to the DECLARATION here. Read off `entry`
                ;; alone, a guard-refused fenced door went out with no
                ;; If-Match, and invoke's fence (step 6, ahead of the
                ;; guard loop) answered 412 "Version conflict" on a row
                ;; that had not changed, where the guard's own 409
                ;; belonged (ticket ec814cac).
                fenced? (boolean
                         (or (get-in entry [:safety :fence])
                             (get-in rdef [:actions aname :safety :fence])))
                sentence (consequence-of entry)
                st (stage eng session rdef)]
            ;; staged (§ 2): the form opens and is typed before the
            ;; confirm gate and the write, so a refusal leaves it open
            (when st (stage-dialog! eng st self aname input))
            (if (and (get-in entry [:safety :confirm])
                     (not= acknowledge sentence))
              (refusal (confirm-refusal aname sentence acknowledge))
              ;; the sitting this session is bound to rides the request,
              ;; so a wall judges THIS sitting and not the newest under
              ;; the seat's shared grant (ticket 51dfd10b)
              (let [resp (call (assoc (request session :post
                                               (if via
                                                 (str self "/-/" (name via))
                                                 (or (:href entry) (str self "/-/" (name aname))))
                                               {:body (or input {})
                                                :query (when dry_run "dry_run=1")
                                                :headers (cond-> (invoke-headers
                                                                  session fenced?
                                                                  (get-in env-resp [:headers "ETag"])
                                                                  acknowledge_warnings)
                                                           ;; the version the CALLER read, not
                                                           ;; this read's: an edit door holds
                                                           ;; the write to it (ticket 5120da15)
                                                           (some? if_version)
                                                           (assoc "if-match" (str if_version))
                                                           as-id
                                                           (assoc "x-waymark-as" as-id))})
                                      :waymark10/sitting
                                      (bound-sitting eng (:mcp-session-id session))))]
                ;; staged (§ 2): the write landed, so the form closes
                (when (and st (not dry_run) (<= 200 (:status resp 500) 299))
                  (stage-close! st self))
                (answer
                 resp
                 return
                 ;; `from` and the changed set come off the row as READ —
                 ;; the same read the gate and the ETag came from
                 #(when (row-doc? %)
                    (invoke-summary aname (verbatim-json env-resp) %)))))))))))

;; ── waymark_pursue (GRAIL 3/3): a goal in one call ──────────────────

(defn- door-session
  "A waymark10.client session whose transport is this door, wearing
  `ident` — the resolved `:waymark10/principal`, and the
  `:waymark10/visibility` and `:waymark10/sitting` when there are any —
  on every request, and `origin-key`'s signature on every POST. The one
  builder under `pursue-session` (a request's caller) and `rehearse` (a
  caller rebuilt from ids), so the two cannot come to differ."
  [call ident]
  (let [pid (get-in ident [:waymark10/principal :id])]
    (client/connect
     "mcp:"
     {:presence false
      :handler (fn [req]
                 (call (cond-> (merge req ident)
                         (= :post (:request-method req))
                         (update :headers assoc "idempotency-key"
                                 (origin-key pid (or (get-in req [:headers "idempotency-key"])
                                                     (random-uuid)))))))})))

(defn- pursue-session
  "The door's session wearing the caller's identity exactly as `invoke`
  does: the resolved principal and visibility, and the bound sitting on
  every request. So each step the pursuit takes is an ordinary invoke —
  judged by the caller's grant, counted on the caller's sitting — and
  nothing the caller could not do by hand."
  [eng call session]
  (door-session
   call
   (-> (request session :get "/" {})
       (assoc :waymark10/sitting
              (bound-sitting eng (:mcp-session-id session)))
       (select-keys [:waymark10/principal :waymark10/visibility
                     :waymark10/sitting]))))

(defn rehearse
  "GRAIL's rehearsal for a caller named by ids, outside a request: what
  a durable consumer calls, since it hears a principal id and holds no
  session. `self` is the href the goal starts from (a row's, or a
  collection's for a create) and `action` a door it advertises. Answers
  `client/pursue!`'s `:dry-run true` answer — `{:writes :blocked-on
  :stack :rehearsal :first-estimate}` — and writes nothing; a start the
  caller cannot read answers `{:stopped res}` in the same shape.

  The caller is rebuilt as the router would resolve it. The principal
  is `members/principal-for`'s, so its roles are the member's own, read
  now. With no grant it wears `grants/unscoped-visibility`, which is
  `wrap-identity`'s own else-branch: nothing for a person, the worn
  grant or the bootstrap surface for an agent. With a grant id it wears
  `grants/visibility` for that grant, as the model its seat is held for
  when the grant cites one (`grants/as-the-seats-sitter`): no session is
  here to declare it. The router lets a dead grant
  through as a scope of nothing; here it is refused by name, because a
  plan of nothing would read as a goal with no way to it. No sitting
  rides: a rehearsal counts on nobody's.

  `opts` may carry `:dry-run :partial`, which is `client/pursue!`'s: a
  goal whose input lacks a required field is rehearsed all the same,
  and answers the fields it still needs.

  Throws a problem: 404 when nobody named `principal` is a member, 403
  when the grant is gone, another's, unaccepted, expired or revoked, and
  the gate's own 403 for a suspended member."
  [eng {:keys [principal grant]} self action input & [{:keys [dry-run]}]]
  (let [who (or (members/principal-for eng principal)
                (throw (p/problem :rehearse-no-such-member 404 "No such member"
                                  {:detail (str "Nobody named " (pr-str principal)
                                                " is a member here, so there is "
                                                "nobody to rehearse as.")})))
        gid (some-> grant str not-empty)
        who (if gid (grants/as-the-seats-sitter eng gid who) who)
        vis (if gid
              (let [v (grant-vis/visibility eng gid who)]
                (when-not (:grant v)
                  (throw (p/problem :rehearse-grant-not-live 403 "The grant confers nothing"
                                    {:detail (str "Grant " (pr-str gid) " is gone, is "
                                                  "not " (:id who) "'s, or is not "
                                                  "accepted and unexpired now, so "
                                                  "nothing can be rehearsed under it.")})))
                v)
              (grant-vis/unscoped-visibility eng who))
        cs (door-session (door eng)
                         (cond-> {:waymark10/principal who}
                           vis (assoc :waymark10/visibility vis)))
        start (client/get-doc cs self)]
    (if-not (client/doc? start)
      ;; concealed, gone, or never here — the engine's own refusal
      {:stopped start :writes [] :rehearsal true :first-estimate true}
      (client/pursue! cs start (wire-action (keyword (name action))) input
                      {:dry-run (if (= :partial dry-run) :partial true)}))))

(defn pursue
  "waymark_pursue: waymark10.client/pursue! over this door. A remedy
  the refusal binds (its resolved_remedies) is followed first; `choices`
  (remedy door → {id, input}) is its :choices for the rest, and fills
  the input a binding left unset; a door it does not name falls back to
  the refused row when the remedy is on that row's kind.
  dry_run defaults TRUE — a pursuit writes only when asked to. The
  answer is written without `wire-value`: `done` is the route's own
  envelope and its keys pass through as the route spelled them."
  [eng call session {:keys [kind id action input choices max_depth dry_run]}]
  (let [rdef (rdef-of eng kind)
        aname (or (declared-action rdef action) (keyword action))
        cs (pursue-session eng call session)
        start (client/get-doc cs (cond-> (str "/api/" (:plural rdef))
                                   id (str "/" id)))]
    (if-not (client/doc? start)
      ;; concealed, gone, or never here — the engine's own refusal
      (result (wire/write-json (:problem start)) true (:status start))
      (let [picks (when (seq choices)
                    (into {}
                          (map (fn [[door c]]
                                 [(name door)
                                  (cond-> {}
                                    (some? (:id c)) (assoc :id (str (:id c)))
                                    (some? (:input c)) (assoc :input (:input c)))]))
                          choices))
            res (client/pursue! cs start (wire-action aname) input
                                (cond-> {:dry-run (not (false? dry_run))}
                                  max_depth (assoc :max-depth max_depth)
                                  picks (assoc :choices picks)))
            rehearsal? (boolean (:rehearsal res))]
        (result
         (wire/write-json
          (cond-> {:steps_taken (if rehearsal? [] (:writes res))}
            rehearsal? (assoc :plan (:writes res))
            (some? (:done res)) (assoc :done (:done res))
            (contains? res :blocked-on) (assoc :blocked_on (:blocked-on res)
                                               :stack (:stack res))
            (:stopped res) (assoc :stopped (:stopped res))))
         (boolean (:stopped res)))))))

(defn history
  "The row's transitions, newest first — now a call onto the route,
  like the other five (waymark-zp5, closed).

  This namespace used to read `store/transitions` directly and choose
  its own projection, because `GET /api/{plural}/{id}/-/history` was
  docs/spec-time-travel.md's and unbuilt. That projection is DELETED
  rather than widened: a transition's meaning had two homes, and the
  bead existed to record that adding a third would be the wrong fix.
  Concealment, the field projection over the decision record, the
  honesty notes and the derived basis all now arrive from the one
  place that owes them."
  [eng call session {:keys [kind id limit]}]
  (let [rdef (rdef-of eng kind)]
    (pass-through
     (call (request session :get
                    (str "/api/" (:plural rdef) "/" id "/-/history")
                    (when limit {:query (str "limit=" (long limit))}))))))

;; ── waymark_in_flight: the body ─────────────────────────────────────

(defn in-flight-read
  "The in-flight document as GET /api/-/in-flight answers it. That
  route is the seats module's, and `door` mounts core's routes only —
  a call onto it read as collection \"-\" (ticket 334e4dac) — so this
  is `in-flight/read-all`, the function the route answers with: the
  session's grant is its visibility, blank filters left out, and
  `holder=me` read as the session principal's own token, as the route
  reads it (`in-flight/with-me`, spec-first-run R-3.1)."
  [eng _call session args]
  (let [filters (into {}
                      (keep (fn [k]
                              (when-some [v (some-> (get args k) str str/trim not-empty)]
                                [k v])))
                      in-flight/filter-keys)]
    (value-result (in-flight/read-all eng (:visibility session)
                                      (in-flight/with-me eng (:principal session) filters)))))

;; ── waymark_resolve: the body ───────────────────────────────────────

(def resolve-chunk
  "Values per collection call. The route splits the comma list into
  one IN cond, so the chunk bounds the query string and the IN list;
  the rows come back paged at the route's own maximum regardless,
  and `resolve-pages` walks every page."
  50)

(defn resolvable-fields
  "The fields an agent may resolve BY, as wire param names: the
  declaration's :filterable entries carrying :eq or :in — the ones
  the collection grammar answers `field=a,b` for — minus :state (a
  state is not a key) and minus any field a summary item does not
  carry in `fields` (a vector, or prose: `render/grid-fields`' rule),
  because the match is read back off those fields. This is the
  vocabulary the refusal names, read off the declaration."
  [rdef]
  (let [carried (render/grid-fields rdef)]
    (->> (:filterable rdef)
         (filter (fn [[f ops]]
                   (and (not= :state f)
                        (contains? carried f)
                        (some #{:eq :in} ops))))
         (map (comp name key))
         sort
         vec)))

(defn- not-resolvable
  "The refusal in this namespace's own voice, beside the confirm gate:
  a field the declaration did not make a key. It names the fields
  that ARE — the whole vocabulary, so the next call needs no guess."
  [rdef by]
  (let [names (resolvable-fields rdef)]
    (p/problem
     :not-resolvable 422 "Not a resolvable field"
     {:detail (str (name (:kind rdef)) " cannot be resolved by " (pr-str by)
                   " — it is not declared filterable by :eq or :in. "
                   (if (seq names)
                     (str "Resolvable fields: " (pr-str names) ".")
                     "This kind declares no resolvable field."))
      :kind (name (:kind rdef))
      :by by
      :resolvable names
      :remedies (if (seq names)
                  [(str "Call waymark_resolve again with by: one of "
                        (pr-str names) ".")]
                  ["Ask the kind's author to declare the key field :filter #{:eq :in}."])})))

(defn- resolved-row
  "One collection item through `row-summary` — the same projection
  `return: summary` answers everywhere else, so a resolved row and a
  summarized row are one shape. The caller's `fields` pick was the
  ROUTE's (fields=, waymark-pywy.2), so the item already carries
  exactly what was asked plus the key field this tool added in
  order to read the match back; `strip` is that key, dropped again
  when the caller did not name it."
  [item strip]
  (let [row (row-summary item)]
    (if (and strip (contains? row "fields"))
      (update row "fields" dissoc strip)
      row)))

(defn- resolve-pages
  "Every item the collection route answers for one chunk of values:
  `by=a,b,…` plus the caller's filter and, when the caller picked
  fields, the route's own fields= (the key field always among them),
  paged at the route's maximum and walked to the end — a key nobody promised unique may answer
  more rows than values. → {:items […]} (string-keyed, the route's
  own spelling) or {:refusal resp}, the route's own refusal (an
  unknown filter, a field the grant does not admit) standing as the
  whole answer."
  [call session rdef by values filter picked]
  (let [params (cond-> (into {} (map (fn [[k v]] [(name k) (str v)])) filter)
                 true (assoc by (str/join "," values)
                             "page[size]" (str coll/page-size-max))
                 (seq picked) (assoc "fields" (str/join "," picked)))]
    (loop [n 1 acc []]
      (let [resp (call (request session :get (str "/api/" (:plural rdef))
                                {:query (query-string
                                         (assoc params "page[number]" (str n)))}))]
        (if-not (<= 200 (:status resp 500) 299)
          {:refusal resp}
          (let [env (verbatim-json resp)
                items (get-in env ["data" "items"])
                total (long (or (get-in env ["data" "total"]) 0))
                acc (into acc items)]
            (if (or (empty? items) (>= (* n coll/page-size-max) total))
              {:items acc}
              (recur (inc n) acc))))))))

(defn resolve-rows
  "The batch lookup. Values are trimmed, de-duplicated and chunked —
  `resolve-chunk` per call on an :in field, ONE per call on a field
  declared :eq alone, because the grammar splits a comma list only
  where :in was declared and would otherwise read `a,b` as one value.
  A value carrying a comma can never be asked through that grammar
  and lands in unmatched without a call. Each chunk is one or more
  collection reads through the door — the grant's projection
  inherited like every other tool's — and the answer is the set
  difference: a value one row carries is matched to that row's
  summary, a value several carry is ambiguous, the rest unmatched.
  Concealed rows are unmatched rows; the description says so."
  [eng call session {:keys [kind by values filter fields]}]
  (let [rdef (rdef-of eng kind)
        by (str by)]
    (if-not (contains? (set (resolvable-fields rdef)) by)
      (refusal (not-resolvable rdef by))
      (let [wanted (into [] (comp (map str) (map str/trim)
                                  (remove str/blank?) (distinct))
                         (if (sequential? values) values [values]))
            in? (contains? (set (get (:filterable rdef) (keyword by))) :in)
            askable (if in? (remove #(str/includes? % ",") wanted) wanted)
            filter (dissoc (or filter {}) (keyword by) by)
            wire-by (p/wire-key by)
            ;; the caller's pick rides the route's fields= (pywy.2) —
            ;; the route judges it against the grant's vocabulary and
            ;; its refusal is the whole answer. The key field goes
            ;; along whether or not it was named, because the match
            ;; is read back off it; `strip` drops it again after
            named (when (seq fields)
                    (into [] (comp (map str) (map str/trim) (remove str/blank?)
                                   (map p/wire-key) (distinct))
                          (if (sequential? fields) fields [fields])))
            picked (when (seq named) (distinct (cons wire-by named)))
            strip (when (and (seq named) (not (some #{wire-by} named)))
                    wire-by)
            fetched (reduce (fn [acc chunk]
                              (let [r (resolve-pages call session rdef by
                                                     chunk filter picked)]
                                (if (:refusal r)
                                  (reduced r)
                                  (into acc (:items r)))))
                            []
                            (partition-all (if in? resolve-chunk 1) askable))]
        (if (map? fetched)
          (pass-through (:refusal fetched))
          (let [index (group-by #(str (get-in % ["fields" wire-by])) fetched)
                matched (into {}
                              (keep (fn [v]
                                      (let [rows (get index v)]
                                        (when (= 1 (count rows))
                                          [v (resolved-row (first rows) strip)]))))
                              wanted)
                ambiguous (into {}
                                (keep (fn [v]
                                        (let [rows (get index v)]
                                          (when (< 1 (count rows))
                                            [v (mapv #(resolved-row % strip) rows)]))))
                                wanted)
                unmatched (into [] (remove #(or (contains? matched %)
                                                (contains? ambiguous %)))
                                wanted)]
            (result
             (wire/write-json
              (cond-> {"kind" (name (:kind rdef))
                       "by" by
                       "matched" matched
                       "unmatched" unmatched}
                (seq ambiguous) (assoc "ambiguous" ambiguous))))))))))
