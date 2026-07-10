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
     before anyone is asked to confirm anything.
  6. Warning 409s (the E1 acknowledge protocol) surface as data:
     {:warnings … :acknowledge!} — calling (acknowledge!) retries
     with the Waymark-Acknowledge header naming every warning, and
     nothing is acknowledged that a caller did not see.
  7. Plans route over effect.to edges learned from every document
     seen (plan), and every act! verifies the returned state
     against the declared prediction — a divergence is attached to
     the result (:waymark10.client/diverged), surfaced, never
     improvised around.

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
  [base-url {:keys [principal bearer grant handler key-store]}]
  {:base-url base-url
   :headers (cond-> {"content-type" "application/json"}
              principal (merge (principal-headers principal))
              bearer (assoc "authorization" (str "Bearer " bearer))
              grant (assoc "x-waymark-grant" grant))
   :request-fn (if handler
                 (ring-request-fn handler)
                 (http-request-fn base-url))
   :key-store (or key-store (atom {}))
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

(defn get-doc
  "GET an advertised href (a doc's :self, a well-known collection
  href, a links entry) → the envelope, learning its effect.to edges.
  Accepts a doc in place of an href (re-reads its self)."
  [session href]
  (let [href (if (map? href) (:self href) href)
        res (request session {:method :get :path href})]
    (learn! session res)
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

(defn- consequence-of
  "The confirm gate's text: the declaration's :consequence rides the
  wire as display.description."
  [entry]
  (or (get-in entry [:display :description])
      (get-in entry [:display :label])
      "This action requires confirmation."))

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

(declare act!)

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
    :confirm!     (fn [{:keys [action effect consequence summary]}]
                  → truthy) — the human-approval seam a confirm-gated
                  action requires; absent or falsey → local refusal
                  with the consequence text (rule 2)
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

       ;; rule 2: the confirm gate — a hard local stop
       (and (get-in entry [:safety :confirm])
            (not (::confirmed opts))
            (not (and confirm!
                      (confirm! {:action (name aname)
                                 :effect (:effect entry)
                                 :consequence (consequence-of entry)
                                 :summary (:summary doc)}))))
       {:refused {:code (if confirm! :confirm-declined :confirm-required)
                  :action (name aname)
                  :consequence (consequence-of entry)
                  :reason (str "safety.confirm=true — a human must approve: "
                               (consequence-of entry))}}

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
  true (:warnings […])} or {:problem …}; refuses locally on an
  undeclared action exactly like act!."
  [session doc action input]
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
                  "dry_run=1")
             input
             (act-headers doc entry nil nil)
             true))))

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
