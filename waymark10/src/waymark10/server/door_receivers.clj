(ns waymark10.server.door-receivers
  "A subscription whose receiver is a seat's fire door (docs/spec-events.md
  §4.2, E-3.3 to E-3.8), drained as the `door` family and holding no key.
  A match re-checks the owner's seat.fire and only marks the seat pending
  (`wakes/mark-door-wake!`); a lost grant or a gone seat is a skip."
  (:require [waymark10.server.consumers :as consumers]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.listen :as listen]
            [waymark10.server.store :as store]
            [waymark10.server.wakes :as wakes]
            [waymark10.server.webhooks :as webhooks]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def family
  "The door subscriptions' family: cursors `door:<id>`."
  :door)

(def ^:private unheard
  "The engine's own writing about a wake, which `wakes` passes over too."
  #{:seat :sitting :schedule :subscription})

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 door receivers: " parts))))

(def ^:private stored-row webhooks/stored-row)

(defn- door-rows
  "The active subscriptions whose receiver is a door."
  [eng]
  (if (get (inv/resources eng) :subscription)
    (let [st (:storage eng)]
      (filterv webhooks/door-receiver?
               (store/with-tx st
                 (fn [tx] (store/query-rows st tx :subscription
                                            {:state :active} {:limit 500})))))
    []))

(defn- owner-holds-fire?
  "Does the owner hold `seat.fire` on the seat as its grant stands NOW
  (E-3.4)? An owner with no grant is a person or the engine."
  [eng sub seat-id]
  (let [{:keys [owner owner_grant]} (:data sub)]
    (if-some [gid (some-> owner_grant str not-empty)]
      (let [who (t/principal {:id (str owner) :type :agent :display (str owner)})]
        (boolean
         (when (= :active (:standing (grants/grant-standing eng gid)))
           (webhooks/holds-seat-fire? who (:grant (grants/visibility eng gid who))
                                      seat-id))))
      true)))

(defn- wake-of
  "What this match asks of the seat, or nil: the transition, or for a
  COUNT receiver (E-3.6) the moved kind's count under `count_filter`,
  at its size. The settle and its cap read as a `wake_on` entry's."
  [eng {:keys [at_least at_most count_filter settle_seconds] :as receiver} t]
  (let [base {:settle (some-> settle_seconds long)
              :max-wait (wakes/door-max-wait receiver)}
        kind (keyword (name (:kind t)))]
    (if (and (nil? at_least) (nil? at_most))
      (assoc base :text (wakes/wake-text t))
      (when-some [n (wakes/count-under eng kind count_filter)]
        (let [n (long n)]
          (if (some? at_least)
            (when (>= n (long at_least))
              (assoc base :text (wakes/count-text kind n :at_least (long at_least))))
            (when (<= n (long at_most))
              (assoc base :text (wakes/count-text kind n :at_most (long at_most))))))))))

(defn- smallest-wait
  "The SMALLEST max_wait among this receiver and those pending (E-3.5)."
  [eng sub seat-id mine]
  (let [waits (->> (wakes/door-matched eng seat-id)
                   (remove #(= (str (:id %)) (str (:id sub))))
                   (keep #(wakes/door-max-wait (get-in % [:data :receiver])))
                   (cons mine)
                   (remove nil?))]
    (when (seq waits) (reduce min waits))))

(defn- note-match!
  "The match's `last_delivered_at`, which `wakes/door-matched` reads."
  [eng sub at]
  (try
    (inv/invoke! eng :subscription (str (:id sub)) :note_deliveries
                 {:last_delivered_at (str at)}
                 {:principal webhooks/deliverer-actor})
    (catch Exception x
      (warn! "could not note the match on subscription " (:id sub) ": "
             (ex-message x)))))

(defn- deliver!
  "One match: the seat's pending marks, or a skip, thrown."
  [eng sub t]
  (let [seat-id (str (get-in sub [:data :receiver :id]))
        seat (stored-row eng :seat seat-id)
        owner (some-> (get-in sub [:data :owner]) str)]
    (cond
      (or (nil? seat) (contains? #{:retired :merged} (:state seat)))
      (throw (ex-info (str "seat " seat-id " is gone or retired, so this"
                           " subscription fires nothing")
                      {::skip :gone}))

      (not (owner-holds-fire? eng sub seat-id))
      (throw (ex-info (str "its owner " owner " no longer holds seat.fire on seat "
                           seat-id)
                      {::skip :not-granted}))

      :else
      (when-some [wake (wake-of eng (get-in sub [:data :receiver]) t)]
        (let [at ((:now-fn eng))]
          (wakes/mark-door-wake! eng seat-id t
                                 (assoc wake :at at
                                        :max-wait (smallest-wait eng sub seat-id
                                                                 (:max-wait wake))))
          (note-match! eng sub at))))))

(defn- record-skip!
  "A skip, written on the subscription by the deliverer's door (E-3.8)."
  [eng sub e]
  (let [fresh (or (stored-row eng :subscription (:id sub)) sub)
        why (str (ex-message e))]
    (warn! "subscription " (:id sub) " skipped an event: " why)
    (try
      (inv/invoke! eng :subscription (str (:id sub)) :note_deliveries
                   {:skipped_count (inc (long (or (get-in fresh [:data :skipped_count]) 0)))
                    :last_skipped_at (str ((:now-fn eng)))
                    :last_skip_error (subs why 0 (min 200 (count why)))}
                   {:principal webhooks/deliverer-actor})
      (catch Exception x
        (warn! "could not note the skip on subscription " (:id sub) ": "
               (ex-message x))))))

(defn- spec-of [eng]
  {:kind :subscription
   :members #(door-rows eng)
   :entry #(listen/from-subscription (:data %))
   :ignore #(contains? unheard (keyword (name (:kind %))))
   :deliver (fn [sub t] (deliver! eng sub t))
   :policy (constantly :skip)
   :on-skip (fn [sub _ e] (record-skip! eng sub e))
   :reload (fn [sub]
             (let [fresh (stored-row eng :subscription (:id sub))]
               (when (and (= :active (:state fresh))
                          (webhooks/door-receiver? fresh))
                 fresh)))})

(defn drain!
  "One pass of every door subscription, in the caller's thread."
  [eng]
  (consumers/drain-family! eng family (spec-of eng)))

(defn start!
  "The `door` family on the running dispatcher, for `stop!`."
  [eng dispatcher {:keys [poll-ms] :or {poll-ms 2000}}]
  (consumers/register-family! eng family (spec-of eng)
                              {:dispatcher dispatcher :poll-ms poll-ms}))

(defn stop! [running]
  (consumers/stop-family! running))
