(ns waymark10.server.webhooks
  "Webhooks (phase 9b): the outbox is a product. The transition log is
  already an outbox (phase 6); this exposes it. A :subscription is an
  engine-served resource — url, kind filter, optional secret — and
  delivery is at-least-once off the log: the deliverer keeps ONE
  cursor per subscription in waymark10_cursors, drains everything
  past it on every wake, and persists the cursor per delivered event,
  so a restart replays instead of dropping. The events dispatcher is
  only the wake signal (plus its poll backstop); the log carries
  truth, exactly the phase-6 discipline.

  The wire: POST each matching transition to the subscription's url —
  the body is the SSE frame's data (waymark10.server.events/
  transition-payload, snake keys), the event id rides
  X-Waymark-Event-Id, and when the subscription declares a secret the
  body is signed: X-Waymark-Signature: hex hmac-sha256(secret, body).
  Third parties verify with the shared secret and never learn the
  envelope format.

  THE SECRET MAY BE A REFERENCE. `secret` takes a literal key, or the
  bare id of a `secret` row (waymark10.server.secrets). The field keeps
  the id and never the value; the deliverer reads the row's value at
  each delivery, so a replaced value signs the next one. While the row
  holds no value the deliveries WAIT: the cursor stays, the
  subscription stays active, and `failure_reason` says what it waits
  for. Nothing goes out unsigned and nothing is marked failed.

  A LITERAL KEY IS NEVER SHOWN. At create, a `secret` that is not the
  id of a secret row moves to `signing_key`, a :secret field that no
  projection carries, and `secret` keeps only the mark `set`. So get,
  query, history and the event frames show that a key is there and
  never the key; the deliverer still signs with it. A row from before
  this, with its literal still in `secret`, signs as it did.

  Failure discipline, deliberately NOT waymark9's: subscriptions.py
  skipped a refusing event after its attempts and advanced the cursor
  (liveness over completeness); v10 marks the SUBSCRIPTION failed —
  bounded retries with backoff, then :mark_failed as the system actor,
  logged, cursor parked at the refusing event — so a resume (after
  fixing the endpoint) continues from exactly where delivery stopped.
  Nothing is silently dropped; the trade is that one broken endpoint
  stops its own stream (never anyone else's).

  Batch F makes that trade PER SUBSCRIPTION (:delivery_policy,
  declaration-driven): \"fail\" (the default, exactly the discipline
  above) or \"skip\" (waymark9's liveness posture — a delivery that
  exhausts its retries logs to *err*, the cursor advances past the
  refusing event, and the subscription stays active). And waymark9's
  revoked terminal state is ported after all: :revoke is owner-gated
  (the subscription's creator, never another principal) and terminal —
  paused and failed still both resume; revoked does not.

  WHO MADE THE MOVE. Each delivery names its actor: `actor` is the
  principal as the log holds it (its `id` is the address, `seat:…`),
  and `actor_name` is the name of the row that address names, or the
  principal's own display. A subscription's `skip_actors` lists
  addresses whose moves it does not want: such a transition is passed
  over at the drain, the cursor advancing, and is never POSTed.

  Recorded deviations and scope, each a sentence:
  - One deliverer thread drains every subscription's cursor in turn —
    the v10 spelling of a worker per active subscription; delivery is
    sequential per subscription either way, and the cursor rows are
    the real per-subscription state.
  - A new subscription hears the world from its own creation
    transition, never before (waymark9's discipline): the first drain
    seeds the cursor at the newest transition of the subscription row
    itself.
  - :subscription transitions are never delivered (waymark9 excluded
    them too) — a webhook narrating its own bookkeeping is feedback,
    not signal.
  - The active-subscription set re-reads from storage on every drain —
    no cache to invalidate; the drain is already IO-bound on delivery."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.secrets :as secrets]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpRequest$Builder
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 webhooks: " parts))))

(def deliverer-actor
  "The system actor that records delivery failure on a subscription."
  (t/principal {:id "waymark10-webhooks" :type :system
                :display "Webhook deliverer"}))

;; ── the resource ────────────────────────────────────────────────────

(defn- system? [ctx]
  (= :system (get-in ctx [:principal :type])))

(g/defguard ^:private deliverer-only
  {:name :deliverer-marks-failure
   :explain "Failure is the deliverer's record, never a client's claim — pause instead."
   :reads [:principal]}
  [_ _ ctx]
  (if (system? ctx) (t/allow) (t/deny)))

(defhandler record-failure [row inp _ctx]
  (assoc-in row [:data :failure_reason] (:reason inp)))

(g/defguard ^:private owner-only
  {:name :owner-revokes
   :explain "Only the subscription's owner may revoke it — pause it instead."
   :reads [:principal]}
  [row _ ctx]
  (if (and (some? (:owner row))
           (= (:owner row) (get-in ctx [:principal :id])))
    (t/allow) (t/deny)))

(def ^:private row-id-form
  #"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

(def literal-mark
  "What `secret` reads as when a literal key was typed. The key itself
  is in `signing_key`."
  "set")

(defn- conceal-literal
  "At create: a `secret` that is not the id of a secret row is a
  literal key. It moves to `signing_key` and `secret` keeps the mark.
  A caller's own `signing_key` is dropped: the field is the engine's."
  [row ctx]
  (let [s (get-in row [:data :secret])
        ref? (and (string? s)
                  (re-matches row-id-form s)
                  (if-some [rd (:read ctx)]
                    (some? (rd :secret s))
                    true))
        row (update row :data dissoc :signing_key)]
    (if (or (not (string? s)) (str/blank? s) ref?)
      row
      (update row :data assoc :secret literal-mark :signing_key s))))

(def ^:private skip-actors-field
  "The `skip_actors` field, one spelling for the row and for restate."
  [:skip_actors {:optional true
                 :examples [["seat:a7006f1e-175b-4b82-9d78-afc71af2d55b"]]
                 :x-display
                 {:raw true
                  :label "Whose moves to leave out"
                  :help "The addresses of the principals whose own moves this endpoint does not want to hear — seat:… for a seat, as a delivery's actor spells it. Left blank it hears everyone's."}}
   [:maybe [:vector {:max 20} [:string {:min 1 :max 120}]]]])

(defhandler restate-subscription [row inp _ctx]
  (if (contains? inp :skip_actors)
    (assoc-in row [:data :skip_actors] (:skip_actors inp))
    row))

(defresource subscription
  {:kind :subscription
   :plural "subscriptions"
   :states [:active :paused :failed :revoked]
   :initial :active
   :terminal #{:revoked}               ; failed and paused both resume
   :nav :system
   :summary "{data.url} · {state}"
   :schema [:map
            [:url {:x-display
                   {:label "Where to POST"
                    :help "The endpoint that will receive each event as a JSON POST — https, and reachable from this engine, or nothing arrives."}}
             [:string {:min 1 :max 250}]]
            [:kinds {:optional true
                     :examples [["task" "chore"]]
                     :x-display
                     {:label "Which kinds to hear about"
                      :help "The resource kinds whose transitions this endpoint wants — task, meal, chore. Left blank it hears about all of them."}}
             [:maybe [:vector [:string {:min 1 :max 64}]]]]
            skip-actors-field
            [:description {:optional true
                           :x-display
                           {:label "What this endpoint is for"
                            :help "A line for whoever finds this subscription later — whose integration it feeds, and who to ask when it starts failing."}}
             [:maybe [:string {:max 200}]]]
            ;; the HMAC key for X-Waymark-Signature; absent = unsigned
            [:secret {:optional true
                      :x-display
                      {:raw true
                       :label "Signing secret"
                       :help "The shared key each delivery is HMAC-signed with, so the receiver can prove the POST came from here. Give the id of a secret row, bare, and the engine signs with the value that row holds and never shows it; the deliveries wait while the row holds no value. A key typed here signs too, and is shown afterwards only as set. Leave it blank and the deliveries go unsigned."}}
             [:maybe [:string {:min 8 :max 120}]]]
            ;; a literal key typed into `secret`, moved here at create:
            ;; never rendered, never filterable
            [:signing_key {:optional true
                           :secret true
                           :x-display
                           {:hidden true
                            :label "The literal key"
                            :spelled-by-hand "Moved here by the engine from a key typed into the signing secret; never shown."}}
             [:maybe [:string {:min 8 :max 120}]]]
            ;; what an exhausted delivery does (batch F): "fail" (the
            ;; default — mark the subscription failed, park the cursor)
            ;; or "skip" (log to *err*, advance the cursor, stay active)
            [:delivery_policy {:optional true
                               :x-display
                               {:label "When an endpoint stops answering"
                                :choices
                                {"fail" "Stop and wait — the subscription goes failed and the cursor parks where it was, so nothing is lost when someone resumes it"
                                 "skip" "Keep going — log the miss, advance past it, stay active; the event is gone but the stream is not"}}}
             [:maybe [:enum "fail" "skip"]]]
            [:failure_reason {:optional true
                              :x-display
                              {:label "Why deliveries stopped"
                               :help "Written by the deliverer when it gives up — the last error it saw. Not yours to fill in."}}
             [:maybe [:string {:max 200}]]]]
   :filterable {:state #{:eq :in}}
   :on-create conceal-literal
   :actions
   {:pause {:from #{:active} :to :paused
            :safety {:idempotent true :reversible true :confirm false}
            :display {:label "Pause" :order 1}}
    :resume {:from #{:paused :failed} :to :active
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Resume" :order 1}}
    ;; whose moves it leaves out can change while it runs; the cursor
    ;; stays where it is. An omitted field keeps its value.
    :restate {:from #{:active} :to :active
              :input [:map skip-actors-field]
              :record true
              :edit {:prefill [:skip_actors]}
              :handler restate-subscription
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Restate" :order 2
                        :description "Change whose moves this endpoint leaves out; deliveries carry on from where they were"}}
    :revoke {:from #{:active :paused :failed} :to :revoked
             :guards [owner-only]
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The endpoint never hears another event; a new subscription starts from its own creation, not from here."}
             :display {:label "Revoke" :style :danger :order 8}}
    :mark_failed {:from #{:active} :to :failed
                  :input [:map [:reason {:optional true
                                         :x-display
                                         {:label "What went wrong"
                                          :help "The last error the deliverer saw before it gave up — a status code, a timeout, a refused connection."}}
                                [:maybe [:string {:max 200}]]]]
                  :record true
                  :guards [deliverer-only]
                  :handler record-failure
                  :safety {:idempotent true :reversible true :confirm false}
                  :display {:label "Mark failed" :order 9}}
    ;; the deliverer's note that deliveries wait for a secret row's
    ;; value, and its removal when the value is there
    :await_secret {:from #{:active} :to :active
                   :input [:map [:reason {:optional true
                                          :x-display
                                          {:label "What it waits for"
                                           :help "The secret row whose value the deliverer needs before it can sign. Empty when the wait is over."}}
                                 [:maybe [:string {:max 200}]]]]
                   :record true
                   :replay false
                   :guards [deliverer-only]
                   :handler record-failure
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "Bookkeeping the deliverer writes while it waits for a secret's value; it removes the note when the value is there."}
                   :display {:label "Wait for the secret" :order 10}}}})

;; ── the signature ───────────────────────────────────────────────────

(defn sign
  "hex hmac-sha256(secret, body) — the X-Waymark-Signature value."
  ^String [^String secret ^String body]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec.
                      (.getBytes secret StandardCharsets/UTF_8)
                      "HmacSHA256")))]
    (str/join (map #(format "%02x" %)
                   (.doFinal mac (.getBytes body StandardCharsets/UTF_8))))))

;; ── delivery ────────────────────────────────────────────────────────

(defn- http-client ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- post!
  "One POST attempt; true on a 2xx/3xx answer."
  [^HttpClient client ^String url headers ^String body timeout-ms]
  (try
    (let [builder (-> (HttpRequest/newBuilder (URI. url))
                      (.timeout (Duration/ofMillis (long timeout-ms)))
                      (.POST (HttpRequest$BodyPublishers/ofString body)))
          builder (reduce-kv (fn [^HttpRequest$Builder b k v]
                               (.header b ^String k ^String v))
                             builder headers)
          resp ^HttpResponse (.send client
                                    (.build ^HttpRequest$Builder builder)
                                    (HttpResponse$BodyHandlers/discarding))]
      (< (.statusCode resp) 400))
    (catch Exception _ false)))

(defn signing-key
  "The key one delivery is signed with: nil for an unsigned
  subscription, the literal key in `signing_key` (or, on a row from
  before that field, in `secret`), or, when `secret` is the id of a
  secret row, the value that row holds now. → ::waiting when the row
  holds no value yet. The seat key check (`routes.seats/service-of`)
  knows a calling service by this same key."
  [eng sub]
  (let [s (get-in sub [:data :secret])
        k (get-in sub [:data :signing_key])]
    (if (some? k)
      k
      (if-some [held (when (and (string? s) (re-matches row-id-form s))
                       (secrets/value-of eng s))]
        (or (:value held) ::waiting)
        s))))

(def ^:private waiting-note "Waiting: the secret ")

(defn- waits? [sub]
  (str/starts-with? (str (get-in sub [:data :failure_reason])) waiting-note))

(defn- note-wait!
  "Writes `reason` as the subscription's `failure_reason` through the
  deliverer's own door. The subscription stays active."
  [eng sub reason]
  (try
    (inv/invoke! eng :subscription (:id sub) :await_secret
                 {:reason reason}
                 {:principal deliverer-actor})
    (catch Exception e
      (warn! "could not note the wait on subscription " (:id sub) ": "
             (ex-message e)))))

(defn- hold!
  "Says once, on the subscription, which secret row it waits for."
  [eng sub]
  (when-not (waits? sub)
    (note-wait! eng sub
                (str waiting-note (get-in sub [:data :secret])
                     " holds no value yet. Deliveries start when its owner enters the value."))))

(defn- settle-wait!
  "Removes the waiting note when the secret row holds a value now."
  [eng sub]
  (when (and (waits? sub) (not= ::waiting (signing-key eng sub)))
    (note-wait! eng sub nil)))

(defn- deliver-with-retries!
  "Attempt one event's delivery up to attempts times with exponential
  backoff; → true when the endpoint accepted it. `secret` is the
  resolved signing key, or nil for an unsigned delivery."
  [client sub secret t-id body {:keys [attempts backoff-ms timeout-ms]}]
  (let [url (get-in sub [:data :url])
        headers (cond-> {"Content-Type" "application/json"
                         "X-Waymark-Event-Id" (str t-id)}
                  secret
                  (assoc "X-Waymark-Signature" (sign secret body)))]
    (loop [n 0]
      (cond
        (post! client url headers body timeout-ms) true
        (<= attempts (inc n)) false
        :else (do (Thread/sleep (long (* backoff-ms (bit-shift-left 1 n))))
                  (recur (inc n)))))))

(defn- actor-addresses
  "The addresses a transition's actor answers to: its id as the log
  holds it, and `type:id` when the id names no kind of its own."
  [t]
  (let [a (:actor t)
        id (some-> (if (map? a) (:id a) a) str not-empty)
        type (when (map? a) (some-> (:type a) name))]
    (cond-> #{}
      id (conj id)
      (and id type (not (str/includes? id ":"))) (conj (str type ":" id)))))

(defn- wants? [sub t]
  (let [kinds (get-in sub [:data :kinds])
        skip (set (get-in sub [:data :skip_actors]))]
    (and (or (empty? kinds)
             (boolean (some #(= (name (:kind t)) %) kinds)))
         (not-any? skip (actor-addresses t)))))

(defn- consumer-of [sub] (str "webhook:" (:id sub)))

(defn- seed-cursor!
  "A subscription with no cursor hears the world from its own creation
  transition — the newest transition of the subscription row itself
  (its create when fresh, its resume after an outage)."
  [eng sub]
  (let [st (:storage eng)
        pos (or (:id (first (store/with-tx st
                              (fn [tx]
                                (store/transitions
                                 st tx {:kind :subscription
                                        :resource-id (:id sub)}
                                 {:newest-first true :limit 1})))))
                0)]
    (store/with-tx st #(store/cursor-set! st % (consumer-of sub) pos))
    pos))

(defn- mark-failed! [eng sub reason]
  (try
    (inv/invoke! eng :subscription (:id sub) :mark_failed
                 {:reason (subs reason 0 (min (count reason) 200))}
                 {:principal deliverer-actor})
    (catch Exception e
      (warn! "could not mark subscription " (:id sub) " failed: "
             (ex-message e)))))

(defn- actor-name
  "What a person calls the actor of this transition: the head of the
  summary line of the row its address names (`seat:<id>` is the seat's
  name), as render names a summary's ref; else the principal's own
  display. Nil when neither is there."
  [eng t]
  (let [a (:actor t)
        a (if (map? a) a {:id a})
        [k id] (some-> (:id a) str (str/split #":" 2))
        rdef (when (and id (not (str/blank? k)))
               (get (inv/resources eng) (keyword k)))
        line (when (and rdef (re-matches row-id-form id))
               (try
                 (let [st (:storage eng)]
                   (when-some [raw (store/with-tx st
                                     #(store/load-row st % (:kind rdef) id {}))]
                     (render/target-summary rdef (inv/decode-row rdef raw) nil)))
                 (catch Exception _ nil)))]
    (or (when (string? line)
          (not-empty (first (str/split line #" · " 2))))
        (some-> (:display a) str not-empty))))

(defn- wire-body
  "The delivery body: the SSE frame's data — one shape for the stream
  and the hook — and beside its `actor`, `actor_name` when the actor
  has a name."
  ^String [eng t]
  (let [who (actor-name eng t)]
    (wire/write-json (cond-> (events/transition-payload eng t)
                       who (assoc :actor_name who)))))

(defn- drain-subscription!
  "Deliver everything past one active subscription's cursor, advancing
  it per delivered event. A delivery that exhausts its retries follows
  the subscription's :delivery_policy: \"fail\" (the default) marks
  the subscription failed and PARKS the cursor at the refusing event,
  so a resume continues from exactly there; \"skip\" logs the loss to
  *err*, advances the cursor past the refusing event, and the
  subscription stays active — liveness over completeness, chosen per
  subscription. A delivery whose signing secret is a secret row with no
  value yet is not attempted: the drain stops there, the cursor stays,
  and the subscription says what it waits for."
  [eng client sub opts]
  (let [st (:storage eng)
        consumer (consumer-of sub)
        _ (settle-wait! eng sub)
        skip? (= "skip" (get-in sub [:data :delivery_policy]))
        cursor (or (store/with-tx st #(store/cursor-get st % consumer))
                   (seed-cursor! eng sub))]
    (loop [cursor cursor]
      (let [rows (store/with-tx st
                   (fn [tx] (store/transitions st tx {:since cursor}
                                               {:limit 200})))
            advance! (fn [t]
                       (store/with-tx st
                         #(store/cursor-set! st % consumer (:id t)))
                       (:id t))
            outcome
            (reduce
             (fn [_cursor t]
               (if (or (= :subscription (:kind t)) (not (wants? sub t)))
                 (advance! t)
                 (let [body (wire-body eng t)
                       secret (signing-key eng sub)]
                   (cond
                     (= ::waiting secret)
                     (do (hold! eng sub)
                         (reduced ::failed))

                     (deliver-with-retries! client sub secret (:id t) body opts)
                     (advance! t)

                     skip?
                     (do (warn! "delivery to " (get-in sub [:data :url])
                                " failed after " (:attempts opts)
                                " attempts at event " (:id t)
                                "; skipping it (delivery policy: skip) — "
                                "the subscription stays active")
                         (advance! t))

                     :else
                     (do (warn! "delivery to " (get-in sub [:data :url])
                                " failed after " (:attempts opts)
                                " attempts at event " (:id t)
                                "; marking the subscription failed — "
                                "resume replays from here")
                         (mark-failed! eng sub
                                       (str "delivery failed after "
                                            (:attempts opts)
                                            " attempts at event " (:id t)))
                         (reduced ::failed))))))
             cursor rows)]
        (when (and (not= ::failed outcome) (= 200 (count rows)))
          (recur outcome))))))

(defn drain!
  "One delivery pass: every active subscription drains past its
  cursor. The deliverer thread calls this on every wake; tests call
  it directly for determinism. opts {:attempts 3 :backoff-ms 250
  :timeout-ms 10000} (engine opts :webhook-attempts /
  :webhook-backoff-ms override)."
  ([eng] (drain! eng (http-client) {}))
  ([eng client opts]
   (let [opts (merge {:attempts (:webhook-attempts eng 3)
                      :backoff-ms (:webhook-backoff-ms eng 250)
                      :timeout-ms (:webhook-timeout-ms eng 10000)}
                     opts)
         subs (store/with-tx (:storage eng)
                (fn [tx] (store/query-rows (:storage eng) tx :subscription
                                           {:state :active} {:limit 500})))]
     (doseq [sub subs]
       (try
         (drain-subscription! eng client sub opts)
         (catch Exception e
           (warn! "drain of subscription " (:id sub) " failed: "
                  (ex-message e))))))))

;; ── the deliverer lifecycle (engine start!/stop!) ───────────────────

(defn start-deliverer!
  "The delivery worker: subscribe to the running dispatcher as a wake
  signal (take-event's timeout is the poll backstop) and drain on
  every wake. Returns the running deliverer; stop-deliverer! ends it."
  [eng dispatcher {:keys [poll-ms] :or {poll-ms 2000}}]
  (let [sub (events/subscribe dispatcher {})
        client (http-client)
        running (atom true)
        t (Thread.
           ^Runnable
           (fn []
             ;; drain once at startup: an outage replays, never drops
             (try (drain! eng client {})
                  (catch Exception e
                    (warn! "startup drain failed: " (ex-message e))))
             (while @running
               (try
                 (let [evt (events/take-event sub poll-ms)]
                   (when-not (= ::events/closed evt)
                     (drain! eng client {})))
                 (catch InterruptedException _ nil)
                 (catch Exception e
                   (when @running
                     (warn! "deliverer loop: " (ex-message e)))))))
           "waymark10-webhooks")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :running running :dispatcher dispatcher :sub sub}))

(defn stop-deliverer! [{:keys [running dispatcher sub ^Thread thread]}]
  (reset! running false)
  (events/unsubscribe dispatcher sub)
  (some-> thread .interrupt)
  nil)
