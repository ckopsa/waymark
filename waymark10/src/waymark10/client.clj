(ns waymark10.client
  "The affordance-following client — Part IV of the spec, ENFORCED
  rather than remembered. This namespace is the reference
  implementation of the agent client rules; each rule lives in
  exactly one place, named below:

  1. Act only on declared actions (act!): the action's href and
     method come from the envelope or the call refuses LOCALLY —
     this client contains no URL constructor for writes, so a
     prompt-injected \"POST /api/plans/{id}/-/delete_everything\"
     has nothing to hold on to. Reads follow self and links only
     (get-doc, follow).
  2. safety.confirm=true is a hard stop (act!): the :confirm!
     callback — the seam where a human says yes — must approve, or
     the call refuses locally carrying the consequence text
     (display.description, where the declaration's :consequence
     rides the wire). No callback means no approval means no call.
  3. safety.idempotent=false → an Idempotency-Key is generated and
     PERSISTED (the session's key-store) before the first attempt,
     keyed by the logical attempt (href + input digest); a retry —
     ambiguous network failure or a deliberate re-call — reuses the
     same key and replays instead of duplicating.
  4. Fenced actions (safety.fence) auto-send If-Match from the
     document's meta.etag: the write is against the row you READ,
     or it is a 412, never a lost update.
  5. dry-run pre-validates input server-side (schema AND guards)
     before anyone is asked to confirm anything — ENFORCED at the
     confirm gate (act!): when a :confirm! seam exists, the input is
     dry-run first, a refusal comes back as the problem without the
     human ever being asked, and pending warnings ride the confirm
     payload so the yes is an informed one.
  6. Warning 409s (the E1 acknowledge protocol) surface as data:
     {:warnings … :acknowledge!} — calling (acknowledge!) retries
     with the Waymark-Acknowledge header naming every warning, and
     nothing is acknowledged that a caller did not see.
  7. Plans route over effect.to edges learned from every document
     seen (plan), and every act! verifies the returned state
     against the declared prediction — a divergence is attached to
     the result (:waymark10.client/diverged), surfaced, never
     improvised around.
  8. A refusal's remedies are a route too (pursue!): the goal door is
     tried, each remedy a refusing guard names is pursued in turn, and
     the door it unblocked is retried — rehearsed by dry-run first,
     bounded, cycle-checked, and only genuine choices come back.

  Refusals are DATA, never exceptions swallowed into nil:
  - {:problem … :status …}   the server refused (RFC 9457 body)
  - {:refused {:code …}}     this client refused locally
  - {:transport {…}}         the wire itself failed
  - {:warnings … :acknowledge! f}  the acknowledge protocol
  and a plain envelope map means the call landed. Predicates:
  problem?, refused?, transport?, warnings?, doc?.

  Transport: java.net.http (no new deps). connect's :handler opt
  swaps in a ring handler as the transport — the tests drive the
  full contract against a real engine without a socket."
  (:require [clojure.string :as str]
            [waymark10.confirm :as confirm]
            [waymark10.holds :as holds]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$Builder
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(set! *warn-on-reflection* true)

;; ── result predicates ───────────────────────────────────────────────

(defn problem?
  "The server refused: r carries the RFC 9457 body under :problem."
  [r] (boolean (and (map? r) (:problem r))))

(defn refused?
  "This client refused locally — the request never left the process."
  [r] (boolean (and (map? r) (:refused r))))

(defn transport?
  "The wire failed (connection refused, timeout, broken stream)."
  [r] (boolean (and (map? r) (:transport r))))

(defn warnings?
  "The acknowledge protocol: advisory guards warned; call the
  result's :acknowledge! to accept them and retry."
  [r] (boolean (and (map? r) (:warnings r) (:acknowledge! r))))

(defn doc?
  "A resource (or collection) envelope — the call landed."
  [r] (boolean (and (map? r) (:self r) (:kind r))))

;; ── the session ─────────────────────────────────────────────────────

(defn- principal-headers [p]
  (if (string? p)
    {"x-waymark-principal" p}
    (cond-> {"x-waymark-principal" (:id p)}
      (seq (:roles p)) (assoc "x-waymark-roles" (str/join "," (:roles p)))
      (:type p) (assoc "x-waymark-actor-type" (name (:type p))))))

(defn- ring-request-fn
  "A transport over a ring handler: the same request/response maps the
  router serves, no socket. SSE (watch!) needs a real server."
  [handler]
  (fn [{:keys [method path headers body]}]
    (let [[uri query] (str/split path #"\?" 2)
          resp (handler (cond-> {:request-method method
                                 :uri uri
                                 :headers (or headers {})}
                          query (assoc :query-string query)
                          body (assoc :body body)))]
      {:status (:status resp)
       :headers (into {} (map (fn [[k v]] [(str/lower-case (str k)) v]))
                      (:headers resp))
       :body (let [b (:body resp)]
               (cond (nil? b) nil (string? b) b :else (slurp b)))})))

(defn- encode-path
  "Server-advertised hrefs arrive URL-encoded already; a hand-typed
  one may carry the raw brackets of page[size] — encode just enough
  for URI/create to accept it."
  ^String [^String p]
  (-> p (str/replace " " "%20") (str/replace "[" "%5B") (str/replace "]" "%5D")))

(defn- http-request-fn
  "The real transport: java.net.http against base-url."
  [^String base-url]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))]
    (fn [{:keys [method path headers body]}]
      (let [builder (HttpRequest/newBuilder
                     (URI/create (str base-url (encode-path path))))
            builder (reduce-kv (fn [^HttpRequest$Builder b k v]
                                 (.header b (str k) (str v)))
                               builder (or headers {}))
            builder (case method
                      :get (.GET ^HttpRequest$Builder builder)
                      :delete (.DELETE ^HttpRequest$Builder builder)
                      (:post :put)
                      (.method ^HttpRequest$Builder builder
                               (str/upper-case (name method))
                               (if body
                                 (HttpRequest$BodyPublishers/ofString body)
                                 (HttpRequest$BodyPublishers/noBody))))
            req (.build ^HttpRequest$Builder builder)
            resp (.send client req (HttpResponse$BodyHandlers/ofString))]
        {:status (.statusCode resp)
         :headers (into {} (map (fn [[k vs]] [(str/lower-case (str k))
                                              (first vs)]))
                        (.map (.headers resp)))
         :body (.body resp)}))))

(defn connect
  "Open a session against a waymark10 server. Auth (dev-header world
  unless the server configures OIDC):
    :principal  \"id\" or {:id … :roles [\"r\"] :type :agent}
    :bearer     an OIDC access token → Authorization: Bearer
    :grant      a grant id → X-Waymark-Grant (the scope selector;
                the principal must be the grant's audience)
  Seams:
    :handler    a ring handler used as the transport (tests)
    :key-store  an atom of {logical-attempt-key → Idempotency-Key},
                pass a persisted one so retries survive the process
                (the CLI's session file does exactly this)
  The session also carries :graph — effect.to edges learned from
  every document seen, the basis for plan."
  [base-url {:keys [principal bearer grant handler key-store presence]}]
  {:base-url base-url
   :headers (cond-> {"content-type" "application/json"}
              principal (merge (principal-headers principal))
              bearer (assoc "authorization" (str "Bearer " bearer))
              grant (assoc "x-waymark-grant" grant))
   :request-fn (if handler
                 (ring-request-fn handler)
                 (http-request-fn base-url))
   :key-store (or key-store (atom {}))
   ;; presence: the session marks where it is looking as it reads
   ;; (hand-in-hand beat 2 — a following human's screen breathes with
   ;; the agent's attention). :presence false opts out.
   :presence? (not (false? presence))
   :presence-at (atom [nil 0])
   :graph (atom {})})

;; ── the state graph (Part IV rule 7's memory) ───────────────────────

(defn- learn!
  "Accumulate effect.to edges from a resource envelope: kind → state
  → {action → to}. Collections and problems teach nothing."
  [session doc]
  (when (and (doc? doc) (:state doc)
             (not (str/ends-with? (str (:kind doc)) "_collection")))
    (swap! (:graph session) update-in [(:kind doc) (:state doc)]
           merge
           (into {}
                 (keep (fn [[a entry]]
                         (when-some [to (get-in entry [:effect :to])]
                           [(name a) to])))
                 (:actions doc)))))

;; ── requests and responses ──────────────────────────────────────────

(defn- parse-response
  "Wire bytes → data. 2xx parses to the body (an envelope, a report,
  a {:valid true}); 4xx/5xx parses to {:problem body :status n} —
  the problem is hypermedia too, so it comes back whole."
  [{:keys [status body] :as resp}]
  (let [parsed (when-not (str/blank? body)
                 (try (wire/read-json body)
                      (catch Exception _ {:unparsed body})))]
    (if (< status 400)
      (with-meta (or parsed {}) {::status status ::headers (:headers resp)})
      {:problem (or parsed {}) :status status})))

(defn- request
  "One exchange as data; a transport failure is {:transport …}."
  [session req]
  (try
    (parse-response ((:request-fn session)
                     (update req :headers #(merge (:headers session) %))))
    (catch java.io.IOException e
      {:transport {:message (or (ex-message e) (str (class e)))
                   :path (:path req)}})
    (catch java.net.http.HttpTimeoutException e
      {:transport {:message (or (ex-message e) "timeout")
                   :path (:path req)}})))

(defn index
  "GET /api/.well-known/waymark — the discovery document: kinds,
  collection hrefs, declared surfaces. The only path this client
  knows a priori; everything after it is followed."
  [session]
  (request session {:method :get :path "/api/.well-known/waymark"}))

(def ^:private presence-beat-ms
  "Same-self beats are throttled to one per this window — the
  registry only keeps the latest gaze anyway."
  5000)

(defn- presence-beat!
  "Fire-and-forget: mark where this session is looking, so a
  following human's screen breathes with the agent's attention
  (hand-in-hand beat 2). Presence is ephemeral, never law: every
  outcome — 204, a 503 from an unstarted engine, a scoped 404 — is
  ignored, and a read never fails because its gaze went unseen."
  [session self]
  (when (and (:presence? session) (string? self))
    (let [now (System/currentTimeMillis)
          [prev at] @(:presence-at session)]
      (when (or (not= prev self) (< (+ ^long at presence-beat-ms) now))
        (reset! (:presence-at session) [self now])
        (try
          (request session {:method :post :path "/api/-/presence"
                            :body (wire/write-json {:self self})})
          (catch Exception _ nil))))))

(defn get-doc
  "GET an advertised href (a doc's :self, a well-known collection
  href, a links entry) → the envelope, learning its effect.to edges
  and marking presence on what was actually seen (a refused read
  marks nothing — probing must not paint gaze on concealed rows).
  Accepts a doc in place of an href (re-reads its self)."
  [session href]
  (let [href (if (map? href) (:self href) href)
        res (request session {:method :get :path href})]
    (learn! session res)
    (when (:self res)
      (presence-beat! session (:self res)))
    res))

(defn follow
  "Follow a document's link rel (Part IV: links are the read surface
  beyond self — never a constructed URL). Unknown rel refuses
  locally."
  [session doc rel]
  (if-some [link (get-in doc [:links (keyword rel)])]
    (get-doc session (:href link))
    {:refused {:code :no-such-link
               :rel (name rel)
               :reason (str (:kind doc) " " (:self doc)
                            " declares no link " (name rel) ".")}}))

;; ── act! (rules 1–4, 6, 7) ──────────────────────────────────────────

(defn- why-not
  "The server's own narration for an action this doc does not afford."
  [doc action]
  (get-in doc [:unavailable (keyword action) :reason]))

(def ^:private consequence-of
  "The confirm gate's text: the declaration's :consequence rides the
  wire as display.description. The one reading, shared with the server."
  confirm/consequence-of)

(defn- attempt-key
  "The logical attempt: same action href + same input = same attempt,
  so its persisted Idempotency-Key replays instead of duplicating.
  Hashes the JSON spelling (not the canonical digest — inputs may
  carry decimals, which the canonical encoding refuses raw)."
  [href input]
  (str href "#" (wire/sha256-hex (wire/write-json (or input {})))))

(defn- idempotency-key!
  "Generate-and-persist BEFORE the first attempt (rule 3); reuse ever
  after."
  [session href input]
  (let [k (attempt-key href input)]
    (or (get @(:key-store session) k)
        (get (swap! (:key-store session)
                    (fn [m] (if (contains? m k) m (assoc m k (str (random-uuid))))))
             k))))

(defn- act-headers [doc entry idem-key acknowledge]
  (cond-> {}
    (and (get-in entry [:safety :fence]) (get-in doc [:meta :etag]))
    (assoc "if-match" (get-in doc [:meta :etag]))
    idem-key (assoc "idempotency-key" idem-key)
    (seq acknowledge) (assoc "waymark-acknowledge"
                             (str/join "," (map name acknowledge)))))

(defn- post!
  "POST with the ambiguous-failure discipline: a transport error
  retries ONCE iff the action is idempotent or the same persisted
  key rides the retry — otherwise the failure surfaces as data."
  [session path body headers retriable?]
  (let [req {:method :post :path path
             :headers headers
             :body (when body (wire/write-json body))}
        res (request session req)]
    (if (and (transport? res) retriable?)
      (request session req)
      res)))

(declare act! dry-run)

(defn- warning-result
  "The E1 protocol as data: the 409's warnings plus an :acknowledge!
  that retries naming exactly what the caller saw."
  [session doc action input opts res]
  (let [names (get-in res [:problem :acknowledge :names])]
    (assoc res
           :warnings (get-in res [:problem :warnings])
           :acknowledge!
           (fn []
             (act! session doc action input
                   (-> opts
                       (assoc ::confirmed true)
                       (update :acknowledge (fnil into #{}) names)))))))

(defn act!
  "Invoke a DECLARED action on doc. opts:
    :confirm!     (fn [{:keys [action effect consequence summary
                  warnings]} → truthy) — the human-approval seam a
                  confirm-gated action requires; absent or falsey →
                  local refusal with the consequence text (rule 2).
                  Before the seam fires, the input is dry-run
                  server-side (rule 5): a refusal returns as the
                  problem without a prompt, and pending warnings ride
                  the payload's :warnings.
    :acknowledge  guard names to acknowledge up front (normally you
                  let the {:warnings … :acknowledge!} result drive)
  Returns the new envelope (with :waymark10.client/diverged attached
  when the landed state contradicts the declared effect.to — rule 7),
  or {:problem …} / {:refused …} / {:transport …} /
  {:warnings … :acknowledge! f}."
  ([session doc action input] (act! session doc action input {}))
  ([session doc action input {:keys [confirm! acknowledge] :as opts}]
   (let [aname (keyword action)
         entry (get-in doc [:actions aname])]
     (cond
       ;; rule 1: unknown action → local refusal, never a constructed URL
       (nil? entry)
       {:refused {:code :unknown-action
                  :action (name aname)
                  :reason (or (why-not doc aname)
                              (str (:kind doc) " " (:self doc)
                                   " does not afford " (name aname)
                                   " in state " (:state doc) "."))}}

       ;; rule 2: the confirm gate — a hard local stop. No callback
       ;; refuses locally without a wire call, exactly as ever; a
       ;; PRESENT callback earns rule 5 first: the server judges the
       ;; input (schema AND guards, ?dry_run=1) before the human is
       ;; asked — a doomed input is never worth a consequence prompt
       (and (get-in entry [:safety :confirm])
            (not (::confirmed opts)))
       (if-not confirm!
         {:refused {:code :confirm-required
                    :action (name aname)
                    :consequence (consequence-of entry)
                    :reason (str "safety.confirm=true — a human must approve: "
                                 (consequence-of entry))}}
         (let [pre (dry-run session doc aname input)]
           (cond
             (problem? pre) pre
             (transport? pre) pre
             (confirm! {:action (name aname)
                        :effect (:effect entry)
                        :consequence (consequence-of entry)
                        :summary (:summary doc)
                        :warnings (:warnings pre)})
             (act! session doc aname input (assoc opts ::confirmed true))
             :else
             {:refused {:code :confirm-declined
                        :action (name aname)
                        :consequence (consequence-of entry)
                        :reason (str "safety.confirm=true — a human must approve: "
                                     (consequence-of entry))}})))

       :else
       (let [idempotent? (get-in entry [:safety :idempotent])
             ;; rule 3: key persisted before the first attempt
             idem-key (when-not idempotent?
                        (idempotency-key! session (:href entry) input))
             headers (act-headers doc entry idem-key acknowledge)
             res (post! session (:href entry) input headers
                        (boolean (or idempotent? idem-key)))]
         (cond
           (transport? res) res

           ;; rule 6: the acknowledge protocol, surfaced as data
           (and (problem? res)
                (= 409 (:status res))
                (get-in res [:problem :acknowledge :names]))
           (warning-result session doc aname input opts res)

           (problem? res) res

           :else
           (do (learn! session res)
               ;; rule 7: verify the landing against the prediction
               (let [predicted (get-in entry [:effect :to])]
                 (if (and predicted (:state res)
                          (not= predicted (:state res)))
                   (assoc res ::diverged {:action (name aname)
                                          :predicted predicted
                                          :actual (:state res)})
                   res)))))))))

(defn diverged
  "The divergence act! attached, when the server landed somewhere the
  declared effect.to did not predict — surface it, don't improvise."
  [res]
  (get res ::diverged))

(defn create!
  "Invoke a collection envelope's create action — the same act!
  discipline (a create is never idempotent, so the key-store makes
  an identical retried create replay instead of duplicating)."
  ([session collection-doc input] (create! session collection-doc input {}))
  ([session collection-doc input opts]
   (act! session collection-doc :create input opts)))

(defn dry-run
  "Rule 5: pre-validate input server-side — schema AND guards, no
  transition — before asking a human to confirm anything. → {:valid
  true (:warnings […])} (a bulk/batch door answers {:valid …
  :verdicts […]}, per item) or {:problem …}; refuses locally on an
  undeclared action exactly like act!. act!'s confirm gate calls
  this itself; the fn stays public for callers pre-validating
  outside a confirm flow. `mode` :partial asks the door to judge only
  what the input gives: a missing field is no error, and the guards
  that read one are named under :awaiting, not judged."
  ([session doc action input] (dry-run session doc action input nil))
  ([session doc action input mode]
   (let [aname (keyword action)
         entry (get-in doc [:actions aname])]
     (if (nil? entry)
       {:refused {:code :unknown-action
                  :action (name aname)
                  :reason (or (why-not doc aname)
                              (str (:kind doc) " does not afford "
                                   (name aname) "."))}}
       (post! session
              (str (:href entry)
                   (if (str/includes? (:href entry) "?") "&" "?")
                   (if (= :partial mode) "dry_run=partial" "dry_run=1"))
              input
              (act-headers doc entry nil nil)
              true)))))

;; ── plan (rule 7) ───────────────────────────────────────────────────

(defn plan
  "A route from doc's state to goal-state over the effect.to edges
  this session has learned (every get-doc/act! feeds the graph; the
  doc itself is learned here too). BFS, shortest in actions. →
  {:route [\"action\" …] :from … :goal …} or {:refused {:code
  :no-route}} when the states seen so far offer none — fetch more
  documents (rows in other states) to widen the graph."
  [session doc goal-state]
  (learn! session doc)
  (let [goal (name goal-state)
        kind (:kind doc)
        edges (get @(:graph session) kind {})
        start (:state doc)]
    (if (= start goal)
      {:route [] :from start :goal goal}
      (loop [frontier (conj clojure.lang.PersistentQueue/EMPTY [start []])
             visited #{start}]
        (if-some [[state path] (peek frontier)]
          (let [steps (get edges state {})
                hit (some (fn [[a to]] (when (= to goal) (conj path a))) steps)]
            (if hit
              {:route hit :from start :goal goal}
              (let [next-steps (remove (comp visited val) steps)]
                (recur (into (pop frontier)
                             (map (fn [[a to]] [to (conj path a)]))
                             next-steps)
                       (into visited (map val) next-steps)))))
          {:refused {:code :no-route :from start :goal goal
                     :reason (str "No route from " start " to " goal
                                  " in the states seen so far — fetch more "
                                  "documents to widen the graph.")}})))))

(defn follow-plan!
  "Execute a planned route step by step, re-reading between steps and
  verifying each landing (act!'s divergence check). Stops at the
  first refusal/problem/divergence and returns it with :at naming
  the step; a clean run returns the final envelope."
  ([session doc goal-state] (follow-plan! session doc goal-state {}))
  ([session doc goal-state opts]
   (let [{:keys [route] :as planned} (plan session doc goal-state)]
     (if-not route
       planned
       (reduce (fn [current step]
                 (let [res (act! session current step nil opts)]
                   (cond
                     (diverged res) (reduced (assoc res :at step))
                     (doc? res) res
                     :else (reduced (assoc res :at step)))))
               doc
               route)))))

;; ── pursue (rule 8: GRAIL — a refusal's remedies are the route) ─────

(defn- door-of
  "kind.action, the remedy token's own wire spelling; a collection's
  create is its kind's door."
  [doc action]
  (str (str/replace (str (:kind doc)) #"_collection$" "") "." (name action)))

(defn- door-kind [door] (first (str/split (str door) #"\." 2)))

(defn- door-action [door] (second (str/split (str door) #"\." 2)))

(defn- trail
  "The stack as a person reads it: which door waits on which row. A
  goal rehearsed partially carries its form: :needs and :awaiting."
  [stack]
  (mapv (fn [f] (merge {:door (:door f) :row (get-in f [:doc :self])} (:form f))) stack))

(defn- missing-inputs
  "The required fields of a door's declared input this input leaves
  unset — a choice only a person (or :resolve) can make."
  [entry input]
  (into []
        (comp (map keyword) (remove #(some? (get input %))))
        (get-in entry [:input :required])))

(defn- remedy-doc
  "The row a :resolve pick names — a doc, an href, or an id read
  through the index's collection href; a create acts on the collection."
  [session kind action {:keys [doc href id]}]
  (let [coll (delay (get-in (index session) [:resources (keyword kind) :href]))]
    (cond
      doc doc
      href (get-doc session href)
      (and id @coll) (get-doc session (str @coll "/" id))
      (and (= "create" action) @coll) (get-doc session @coll))))

(defn- remedy-call
  "Where remedy `door` acts for the `refused` call: the row the refusal
  itself bound (its resolved remedies), with the :input :choices gives
  that door beneath the bound input; else the :choices pick, :resolve's
  pick, or, when neither names one, the refused call's own row when the
  remedy is on its kind. nil means nobody said — a choice for a person;
  {:unseen reason} means the pick did not read back (gone, or outside
  the caller's grant), so the remedy is blocked, never attempted. A
  bound remedy that names :fields carries them on the call, and asks
  for a patch where its door takes one, so only those fields are owed."
  [session door refused resolve choices]
  (let [same-kind? (= (door-kind door) (door-kind (:door refused)))
        bound (some #(when (= door (:door %)) %) (:bound refused))
        chosen (or (get choices door) (get choices (keyword door)))
        pick (if (some? (:id bound))
               (cond-> (select-keys bound [:id :input])
                 (seq (:input chosen)) (update :input #(merge (:input chosen) %)))
               (or chosen
                   (when resolve (resolve door refused))
                   (when same-kind? (select-keys bound [:input]))))
        target (when pick
                 (let [d (remedy-doc session (door-kind door) (door-action door) pick)]
                   (if (and (nil? d) same-kind?) (:doc refused) d)))]
    (cond
      (doc? target)
      (let [fields (mapv keyword (:fields bound))
            patch? (and (seq fields)
                        (some? (get-in target [:actions (keyword (door-action door))
                                               :input :properties :patch])))]
        (cond-> {:door door :doc target :input (:input pick)}
          (seq fields) (assoc :fields fields)
          patch? (assoc-in [:input :patch] true)))
      (problem? target)
      {:door door :unseen (or (get-in target [:problem :detail])
                              (get-in target [:problem :title])
                              "That row did not answer.")})))

(defn- input-at
  "What `input` holds at a remedy's `field`: a name, or a dotted path
  into a nested field (showcase.evidence.film_url)."
  [input field]
  (reduce (fn [m k]
            (when (map? m)
              (if-some [v (get m (keyword k))] v (get m k))))
          input
          (str/split (name field) #"\.")))

(defn- attempt
  "Try one call once — rehearsed (dry-run) or real (act!) — on a fresh
  read of its row. → {:landed doc :to state} (the state it landed the
  row in; a rehearsal's is the door's effect.to) · {:refused [remedy …]
  :bound [resolved remedy …] :doc :reason}
  · {:blocked entry} · {:stop res} (a wire failure or a divergence)."
  [session {:keys [door doc input retry fields] :as call} rehearse? opts]
  (let [doc (if (:self doc) (get-doc session (:self doc)) doc)
        action (keyword (door-action door))
        entry (get-in doc [:actions action])
        blocked (fn [m] {:blocked (merge {:door door :row (:self doc) :needs []
                                          :or (:or call [])}
                                         m)})
        ;; a remedy that names its fields owes those: what the refusal
        ;; is missing. A patch leaves the door's other fields as stored
        needs (when entry
                (if (seq fields)
                  (into []
                        (distinct)
                        (concat (remove #(some? (input-at input %)) fields)
                                (when-not (true? (:patch input))
                                  (missing-inputs entry input))))
                  (missing-inputs entry input)))]
    (cond
      (not (doc? doc)) {:stop doc}

      ;; not afforded: a guard the render probe could judge says why,
      ;; and its remedies ride the unavailable entry
      (nil? entry)
      (if-some [remedies (not-empty (vec (get-in doc [:unavailable action :remedies])))]
        {:refused remedies :doc doc :reason (why-not doc action)
         :bound (vec (get-in doc [:unavailable action :resolved_remedies]))}
        (blocked {:reason (or (why-not doc action)
                              (str door " is not afforded on " (:self doc) "."))}))

      ;; a confirm door is a person's to open: with no :confirm! seam
      ;; the pursuit stops here, rehearsed or real, and names the
      ;; sentence — it is never acknowledged on anyone's behalf
      (and (get-in entry [:safety :confirm]) (not (:confirm! opts)))
      (blocked (cond-> {:confirm true
                        :consequence (consequence-of entry)
                        :reason (str "safety.confirm is true — a person must approve: "
                                     (consequence-of entry))}
                 ;; a partial rehearsal's goal names the form it owes
                 (and rehearse? (:partial call) (seq needs)) (assoc :needs needs)))

      ;; the goal of a partial rehearsal: its form is filled last, so
      ;; the guards that read no missing field are judged now, and the
      ;; door counts as landing with its :needs still owed
      (and (seq needs) rehearse? (:partial call))
      (let [res (dry-run session doc action input :partial)
            ;; a refusal names the waiting guards on its problem
            form {:needs needs :awaiting (vec (or (:awaiting res)
                                                  (get-in res [:problem :awaiting])))}]
        (cond
          (or (transport? res) (diverged res)) {:stop res}
          (seq (get-in res [:problem :remedies]))
          {:refused (vec (get-in res [:problem :remedies])) :doc doc
           :bound (vec (get-in res [:problem :resolved_remedies]))
           :reason (get-in res [:problem :detail])
           :form form}
          (warnings? res) (blocked {:needs needs :warnings (:warnings res)})
          (or (problem? res) (refused? res))
          (blocked {:needs needs
                    :reason (or (get-in res [:problem :detail])
                                (get-in res [:refused :reason])
                                (get-in res [:problem :title]))})
          :else {:landed doc :to (get-in entry [:effect :to]) :form form}))

      (seq needs) (blocked {:needs needs})

      :else
      (let [_ (when (and retry (not rehearse?))
                ;; the refused attempt wrote nothing; once its remedy
                ;; landed, the retry is a new logical attempt (rule 3)
                (swap! (:key-store session) dissoc (attempt-key (:href entry) input)))
            res (if rehearse?
                  (dry-run session doc action input)
                  (act! session doc action input opts))]
        (cond
          (or (transport? res) (diverged res)) {:stop res}
          ;; a hold: the call now waits on a person's tap, and waiting
          ;; is not landing — the pursuit stops here and never retries
          (:held res)
          (blocked {:held true :held_call (:held_call res) :reason (:why res)})
          ;; a rehearsal cannot mint the held call: it counts the door
          ;; as landing, marked, so the real run reaches it and holds
          (and rehearse? (holds/hold? (get-in res [:problem :guard])))
          {:landed doc :hold true :to (get-in entry [:effect :to])}
          (warnings? res) (blocked {:warnings (:warnings res)})
          (seq (get-in res [:problem :remedies]))
          {:refused (vec (get-in res [:problem :remedies])) :doc doc
           :bound (vec (get-in res [:problem :resolved_remedies]))
           :reason (get-in res [:problem :detail])}
          (or (problem? res) (refused? res))
          (blocked {:reason (or (get-in res [:problem :detail])
                                (get-in res [:refused :reason])
                                (get-in res [:problem :title]))})
          :else {:landed (if rehearse? doc res)
                 :to (if rehearse? (get-in entry [:effect :to]) (:state res))})))))

(defn- door-to
  "The state a door's declared effect lands its row in, read off the row
  it was tried on — afforded there or not."
  [{:keys [door doc]}]
  (let [action (keyword (door-action door))]
    (or (get-in doc [:actions action :effect :to])
        (get-in doc [:unavailable action :effect :to]))))

(defn- stands-in?
  "A landing that put `row` in `to` stands in for `below`, the refused
  door waiting on it, when that door would land the same row in the same
  state — so it is popped, not retried. Judged on the state the landing
  made (the one a rehearsal simulates), never on door names."
  [row to below]
  (boolean
   (and to (contains? below :remedies)
        (= row (get-in below [:doc :self]))
        (= (name to) (some-> (door-to below) name)))))

(defn- retried
  "The doors a landing on `row` in `to` leaves to retry, top first: each
  door waiting below it, less any one the landing beneath stood in for."
  [row to stack]
  (loop [row row to to stack stack out []]
    (if-some [f (peek stack)]
      (if (stands-in? row to f)
        (recur row to (pop stack) out)
        (recur (get-in f [:doc :self]) (door-to f) (pop stack) (conj out f)))
      out)))

(defn- walk
  "One pass of the pursuit over an explicit stack. rehearse? dry-runs
  every door, and a remedy that would land counts every door waiting
  above it as landing too — a rehearsal cannot see an effect it did not
  write, so its writes are an estimate. A real pass (:expect, the
  rehearsal's writes) re-checks each landing against the latest
  rehearsal, then re-rehearses what is left of the stack from the state
  that landing made: it goes on while the new rehearsal still reaches
  the goal, and answers its :blocked-on, with the writes already made,
  when it does not. The stack, and with it the depth bound and the
  cycle check, carries across re-rehearsals; :max-steps (default 4 ×
  :max-depth) bounds the attempts one real pass makes."
  [session stack rehearse? {:keys [resolve choices max-depth max-steps expect] :or {max-depth 8} :as opts}]
  (let [act-opts (select-keys opts [:confirm! :acknowledge])
        max-steps (or max-steps (* 4 max-depth))
        write-of (fn [f] (merge {:door (:door f) :row (get-in f [:doc :self]) :input (:input f)}
                                (:form f)))]
    (loop [stack stack writes [] blocked [] at nil expect expect steps 0]
      (let [top (peek stack)]
        (cond
          ;; the step bound: a real pass that keeps landing without
          ;; reaching the goal stops here
          (and (not rehearse?) (not (contains? top :remedies)) (>= steps max-steps))
          {:stopped {:step-bound max-steps} :writes writes :stack (trail stack)}

          ;; a fresh (or retried) door: try it
          (not (contains? top :remedies))
          (let [out (attempt session top rehearse? act-opts)
                steps (inc steps)]
            (cond
              (:stop out)
              {:stopped (:stop out) :writes writes :stack (trail stack)}

              (:refused out)
              (recur (conj (pop stack)
                           (assoc top :doc (:doc out) :reason (:reason out)
                                  :remedies (seq (:refused out)) :all (:refused out)
                                  :bound (:bound out) :form (:form out)))
                     writes blocked at expect steps)

              (:blocked out)
              (let [stack' (pop stack)
                    blocked' (conj blocked (:blocked out))
                    at' (or at (trail stack'))]
                (if (empty? stack')
                  {:blocked-on blocked' :stack at' :writes writes}
                  (recur stack' writes blocked' at' expect steps)))

              :else
              (let [w (cond-> (merge (write-of top) (:form out)) (:hold out) (assoc :hold true))
                    writes' (conj writes w)
                    expected (first expect)
                    row (get-in top [:doc :self])
                    ;; an alternative that already landed the row where
                    ;; the refused door below would stands in for it
                    stack' (cond-> (pop stack)
                             (stands-in? row (:to out) (peek (pop stack))) pop)]
                (cond
                  (and expect (not= (select-keys expected [:door :row])
                                    (select-keys w [:door :row])))
                  {:stopped {:diverged {:expected expected :actual w}}
                   :writes writes' :stack (trail stack)}

                  (empty? stack') {:done (:landed out) :writes writes'}

                  rehearse? {:done nil :writes (into writes' (map write-of)
                                                     (retried row (:to out) stack'))}

                  :else
                  (let [stack'' (conj (pop stack') (-> (peek stack')
                                                       (dissoc :remedies :all :reason :bound)
                                                       (assoc :retry true)))
                        again (walk session stack'' true (dissoc opts :expect))]
                    (if (contains? again :done)
                      (recur stack'' writes' [] nil (:writes again) steps)
                      (-> again (dissoc :done) (assoc :writes writes'))))))))

          ;; a refused door with a remedy left to try
          (seq (:remedies top))
          (let [door (first (:remedies top))
                stack (conj (pop stack) (update top :remedies next))
                others (vec (remove #{door} (:all top)))
                call' (remedy-call session door
                                   (select-keys top [:door :doc :input :reason :bound])
                                   resolve choices)
                block (fn [m] (merge {:door door :row (get-in call' [:doc :self])
                                      :needs [] :or others}
                                     m))
                entry (cond
                        (nil? call')
                        (block {:reason "No row was chosen for this remedy."})
                        (contains? call' :unseen)
                        (block {:reason (:unseen call')})
                        (some #(and (= door (:door %))
                                    (= (get-in call' [:doc :self]) (get-in % [:doc :self])))
                              stack)
                        (block {:reason :cycle})
                        (>= (count stack) max-depth)
                        (block {:reason :depth}))]
            (if entry
              (recur stack writes (conj blocked entry) (or at (trail stack)) expect steps)
              (recur (conj stack (assoc call' :or others)) writes blocked at expect steps)))

          ;; every remedy refused or blocked: this door fails, and the
          ;; choices that stopped it are already in `blocked`
          :else
          (let [stack' (pop stack)]
            (if (empty? stack')
              {:blocked-on blocked :stack (or at []) :writes writes}
              (recur stack' writes blocked at expect steps))))))))

(defn pursue!
  "Reach a goal by following refusal remedies (Amundsen's GRAIL). Try
  the goal door; on a refusal whose guard names :remedies, push it and
  try each remedy in order — a refused remedy's own remedies in turn —
  and when one lands, pop and retry the door below it — unless it landed
  that door's row in that door's effect.to, which stands in for the
  door: both pop and it is not retried. The whole chain
  is rehearsed by dry-run first — a first estimate, since a dry-run
  cannot see its own effects — and the real run re-checks each landing
  against the latest rehearsal, stops on divergence, and re-rehearses
  the rest of the stack after each landing, so a door that needs the
  same remedy several times lands in one call. opts:
    :resolve    (fn [remedy-door refused-call] → {:id row-id :input {…}}
                or nil) — which row a remedy acts on (also :href or
                :doc), asked only where the refusal's resolved remedies
                bound no row. With neither, a remedy on the refused
                call's own kind acts on its row; any other is a choice.
                When the rows a guard waits on are outside the caller's
                sight (unseen children), that fallback tries the refused
                row itself, the same guard refuses it, and the rehearsal
                ends in a cycle block, by design.
    :choices    {remedy-door {:id row-id :input {…}}} — as :resolve, and
                asked first; where the refusal bound the row, its
                :input still fills what the binding left unset (the
                meal for the day a refusal named)
    :max-depth  stack bound (default 8); the same door on the same row
                twice in the stack stops that branch (the cycle check)
    :max-steps  attempts one real run may make across its re-rehearsals
                (default 4 × :max-depth); past it the run answers
                {:stopped {:step-bound n} …}
    :dry-run    rehearse only: the writes it would make, every choice.
                :partial rehearses a goal whose input lacks a required
                field all the same: the door is asked to judge only
                what was given, its remedies are followed, and the
                goal's write (or its :stack frame, when a remedy
                blocks) carries :needs, the fields still owed, and
                :awaiting, the guards that wait on them
    :confirm! :acknowledge  ride every act! (rules 2 and 6)
  → {:done doc :writes […]}, or {:blocked-on [{:door :row :needs
  [input …] :or [alternative remedy …] (:reason)}] :stack […] :writes
  […]} — only genuine choices come back — or {:stopped res …} on a wire
  failure or a divergence. A rehearsal answers :rehearsal true and
  :first-estimate true: its writes are what it could see before any
  write, not a promise. A confirm door with no :confirm! blocks with
  :confirm true and its :consequence; a hold guard's door is a
  rehearsal write marked :hold, and in the real run blocks with :held
  true and the :held_call id."
  ([session doc action input] (pursue! session doc action input {}))
  ([session doc action input opts]
   (let [call (cond-> {:door (door-of doc action) :doc doc :input input}
                (= :partial (:dry-run opts)) (assoc :partial true))
         rehearsal (walk session [call] true opts)]
     (if (or (:dry-run opts) (not (contains? rehearsal :done)))
       (-> rehearsal (dissoc :done) (assoc :rehearsal true :first-estimate true))
       (walk session [call] false (assoc opts :expect (:writes rehearsal)))))))

;; ── the MCP tool projection ─────────────────────────────────────────

(defn tools
  "Project a document's CURRENT actions onto an MCP-style tool list:
  \"whatever this resource affords right now\" as an agent tool
  surface — derived from the envelope, never hand-maintained.
  Folded acceptance sets arrive as enums in input_schema for free;
  confirm gates annotate the description so a harness knows to route
  through a human."
  [_session doc]
  (vec
   (for [[aname entry] (sort-by (comp name key) (:actions doc))]
     (let [effect (:effect entry)
           desc (or (get-in entry [:display :description])
                    (get-in entry [:display :label])
                    (str "Transition this " (:kind doc)
                         " to state '" (:to effect) "'"))]
       {:name (str (:kind doc) "." (name aname))
        :description (cond-> desc
                       (get-in entry [:safety :confirm])
                       (str " (requires human confirmation before invoking)")
                       (:terminal effect)
                       (str " (terminal: no actions afterwards)"))
        :input_schema (or (:input entry)
                          {:type "object" :properties {}})}))))

;; ── the firehose (SSE) ──────────────────────────────────────────────

(defn watch!
  "Tail the transition firehose (GET /api/-/events), calling
  (on-event {:id n :data {…}}) per frame. Blocks until the stream
  ends or (stop?) goes truthy (checked between frames). opts:
  :kinds [\"plan\" …], :since last-event-id, :stop? (fn [] boolean).
  Real HTTP only — a ring-handler session has no stream to tail.
  Returns {:transport …} on connection failure, nil on a clean end."
  [session {:keys [kinds since on-event stop?]}]
  (let [q (cond-> []
            (seq kinds) (conj (str "kinds=" (str/join "," (map name kinds))))
            since (conj (str "last_event_id=" since)))
        path (str "/api/-/events" (when (seq q) (str "?" (str/join "&" q))))]
    (try
      (let [client (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofSeconds 10))
                       (.build))
            builder (HttpRequest/newBuilder
                     (URI/create (str (:base-url session) path)))
            builder (reduce-kv (fn [^HttpRequest$Builder b k v]
                                 (.header b (str k) (str v)))
                               builder (dissoc (:headers session)
                                               "content-type"))
            resp (.send client (.build ^HttpRequest$Builder builder)
                        (HttpResponse$BodyHandlers/ofInputStream))]
        (if (>= (.statusCode resp) 400)
          (parse-response {:status (.statusCode resp)
                           :body (slurp ^java.io.InputStream (.body resp))})
          (with-open [rdr (java.io.BufferedReader.
                           (java.io.InputStreamReader.
                            ^java.io.InputStream (.body resp)))]
            (loop [frame {}]
              (if (and stop? (stop?))
                nil
                (if-some [line (.readLine rdr)]
                  (cond
                    (str/blank? line)
                    (do (when (:data frame)
                          (on-event {:id (some-> (:id frame) parse-long)
                                     :data (wire/read-json (:data frame))}))
                        (recur {}))

                    (str/starts-with? line ":") (recur frame)

                    :else
                    (let [[k v] (str/split line #":" 2)]
                      (recur (assoc frame (keyword (str/trim k))
                                    (str/trim (or v ""))))))
                  nil))))))
      (catch java.io.IOException e
        {:transport {:message (or (ex-message e) (str (class e)))
                     :path path}}))))
