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

(def ^:private deliverer-only
  (g/guard {:name :deliverer-marks-failure
            :explain "Failure is the deliverer's record, never a client's claim — pause instead."
            :reads [:principal]
            :check (fn [_ _ ctx] (if (system? ctx) (t/allow) (t/deny)))}))

(defhandler record-failure [row inp _ctx]
  (assoc-in row [:data :failure_reason] (:reason inp)))

(def ^:private owner-only
  (g/guard {:name :owner-revokes
            :explain "Only the subscription's owner may revoke it — pause it instead."
            :reads [:principal]
            :check (fn [row _ ctx]
                     (if (and (some? (:owner row))
                              (= (:owner row) (get-in ctx [:principal :id])))
                       (t/allow) (t/deny)))}))

(defresource subscription
  {:kind :subscription
   :plural "subscriptions"
   :states [:active :paused :failed :revoked]
   :initial :active
   :terminal #{:revoked}               ; failed and paused both resume
   :nav :system
   :summary "{data.url} · {state}"
   :schema [:map
            [:url [:string {:min 1 :max 250}]]
            [:kinds {:optional true}
             [:maybe [:vector [:string {:min 1 :max 64}]]]]
            [:description {:optional true} [:maybe [:string {:max 200}]]]
            ;; the HMAC key for X-Waymark-Signature; absent = unsigned
            [:secret {:optional true :x-display {:raw true}}
             [:maybe [:string {:min 8 :max 120}]]]
            ;; what an exhausted delivery does (batch F): "fail" (the
            ;; default — mark the subscription failed, park the cursor)
            ;; or "skip" (log to *err*, advance the cursor, stay active)
            [:delivery_policy {:optional true}
             [:maybe [:enum "fail" "skip"]]]
            [:failure_reason {:optional true} [:maybe [:string {:max 200}]]]]
   :filterable {:state #{:eq :in}}
   :actions
   {:pause {:from #{:active} :to :paused
            :safety {:idempotent true :reversible true :confirm false}
            :display {:label "Pause" :order 1}}
    :resume {:from #{:paused :failed} :to :active
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Resume" :order 1}}
    :revoke {:from #{:active :paused :failed} :to :revoked
             :guards [owner-only]
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The endpoint never hears another event; a new subscription starts from its own creation, not from here."}
             :display {:label "Revoke" :style :danger :order 8}}
    :mark_failed {:from #{:active} :to :failed
                  :input [:map [:reason {:optional true}
                                [:maybe [:string {:max 200}]]]]
                  :record true
                  :guards [deliverer-only]
                  :handler record-failure
                  :safety {:idempotent true :reversible true :confirm false}
                  :display {:label "Mark failed" :order 9}}}})

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

(defn- deliver-with-retries!
  "Attempt one event's delivery up to attempts times with exponential
  backoff; → true when the endpoint accepted it."
  [client sub t-id body {:keys [attempts backoff-ms timeout-ms]}]
  (let [url (get-in sub [:data :url])
        headers (cond-> {"Content-Type" "application/json"
                         "X-Waymark-Event-Id" (str t-id)}
                  (get-in sub [:data :secret])
                  (assoc "X-Waymark-Signature"
                         (sign (get-in sub [:data :secret]) body)))]
    (loop [n 0]
      (cond
        (post! client url headers body timeout-ms) true
        (<= attempts (inc n)) false
        :else (do (Thread/sleep (long (* backoff-ms (bit-shift-left 1 n))))
                  (recur (inc n)))))))

(defn- wants? [sub t]
  (let [kinds (get-in sub [:data :kinds])]
    (or (empty? kinds)
        (boolean (some #(= (name (:kind t)) %) kinds)))))

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

(defn- wire-body
  "The delivery body: the SSE frame's data, verbatim — one shape for
  the stream and the hook."
  ^String [eng t]
  (wire/write-json (events/transition-payload eng t)))

(defn- drain-subscription!
  "Deliver everything past one active subscription's cursor, advancing
  it per delivered event. A delivery that exhausts its retries follows
  the subscription's :delivery_policy: \"fail\" (the default) marks
  the subscription failed and PARKS the cursor at the refusing event,
  so a resume continues from exactly there; \"skip\" logs the loss to
  *err*, advances the cursor past the refusing event, and the
  subscription stays active — liveness over completeness, chosen per
  subscription."
  [eng client sub opts]
  (let [st (:storage eng)
        consumer (consumer-of sub)
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
                 (let [body (wire-body eng t)]
                   (cond
                     (deliver-with-retries! client sub (:id t) body opts)
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
