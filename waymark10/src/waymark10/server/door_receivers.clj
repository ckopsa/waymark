(ns waymark10.server.door-receivers
  "A subscription whose receiver is a door (docs/spec-events.md §4.2,
  E-3.3 to E-3.8), drained as the `door` family and holding no key.
  A seat's fire: a match re-checks the owner's seat.fire and only marks
  the seat pending (`wakes/mark-door-wake!`); a lost grant or a gone
  seat is a skip. A walk (docs/spec-change.md R-C.11, R-C.13): a match
  walks the named door on each row `id_from` names, under the owner's
  grant; no row, or a row already moved, is a skip, and any other
  refusal is kept on the subscription."
  (:require [clojure.string :as str]
            [waymark10.server.consumers :as consumers]
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

(defn- grant-active? [eng gid]
  (= :active (:standing (grants/grant-standing eng gid))))

(defn- seat-grant-now
  "The grant a `seat:` owner wears NOW: its authority is the seat's, not
  one sitting's (R-5.3), so a re-sit's fresh grant stands in for the
  lapsed one the row was written under. It is the pick the sit makes
  (`seats/standing-seat-grant`): the newest standing grant whose
  audience is the seat's sitter and which cites that seat. nil for any
  other owner (ticket 8084e5b7)."
  [eng owner]
  (let [owner (str owner)]
    (when (str/starts-with? owner "seat:")
      (let [seat-id (subs owner (count "seat:"))]
        (some-> (grants/standing-grant-for
                 eng owner #(= seat-id (str (get-in % [:data :seat]))))
                :id str)))))

(defn- owner-grant
  "The id of the grant a delivery judges the owner under, or nil when
  none stands: the stored `owner_grant` while it is active, else, for a
  seat owner, the seat's live grant."
  [eng sub]
  (let [{:keys [owner owner_grant]} (:data sub)
        stored (some-> owner_grant str not-empty)
        gid (if (and stored (grant-active? eng stored)) stored (seat-grant-now eng owner))]
    (when (and gid (grant-active? eng gid)) gid)))

(defn- lapse-note
  "Where the stored grant lapsed, the words a skip adds to say so."
  [eng sub]
  (when-some [gid (some-> (get-in sub [:data :owner_grant]) str not-empty)]
    (let [s (:standing (grants/grant-standing eng gid))]
      (when (not= :active s)
        (str ": its stored grant " gid " lapsed (" (some-> s name) ")"
             " and no live grant of its own stands in for it")))))

(defn- owner-holds-fire?
  "Does the owner hold `seat.fire` on the seat as its grant stands NOW
  (E-3.4)? An owner with no grant is a person or the engine."
  [eng sub seat-id]
  (let [{:keys [owner owner_grant]} (:data sub)]
    (if (some-> owner_grant str not-empty)
      (let [who (t/principal {:id (str owner) :type :agent :display (str owner)})]
        (boolean
         (when-some [gid (owner-grant eng sub)]
           (webhooks/holds-seat-fire? who (:grant (grants/visibility eng gid who))
                                      seat-id))))
      true)))

(defn- wake-of
  "What this match asks of the seat, or nil: the transition, or for a
  COUNT receiver (E-3.6) the moved kind's count under `count_filter`,
  at its size: over the seat's own walk, the rows its sit would hand
  (`wakes/door-count`). The settle and its cap read as a wake
  entry's."
  [eng seat {:keys [at_least at_most count_filter settle_seconds] :as receiver} t]
  (let [base {:settle (some-> settle_seconds long)
              :max-wait (wakes/door-max-wait receiver)}
        kind (keyword (name (:kind t)))]
    (if (and (nil? at_least) (nil? at_most))
      (assoc base :text (wakes/wake-text t))
      (when-some [n (wakes/door-count eng seat kind count_filter)]
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

(declare record-skip!)

(defn- field-at
  "The value at `path`, a vector of keys, in a row's data."
  [row path]
  (get-in (:data row) path))

(defn- id-of
  "The row id a field holds (R-C.11): an id, or an address
  `<kind>:<id>` (`born_from`) when its kind is the door's; else nil."
  [kind v]
  (when-some [s (some-> v str str/trim not-empty)]
    (if-some [[_ k id] (re-matches #"([a-z_][a-z0-9_]*):(.+)" s)]
      (when (= k (name kind)) id)
      s)))

(defn- walk-targets
  "The ids of the rows `id_from` names: the one a field of the moved row
  holds, or each row of the door's kind a match names."
  [eng kind self moved id-from]
  (let [{:keys [path match has?]} (webhooks/id-from id-from)]
    (cond
      path (some-> (id-of kind (field-at moved path)) vector)
      match (let [st (:storage eng)]
              (mapv #(str (:id %))
                    (store/with-tx st
                      (fn [tx] (store/query-rows st tx kind
                                                 {match (if has? {:has self} self)}
                                                 {:limit 500})))))
      :else [])))

(defn- walk-input
  "The door's input (R-C.11): each field from a field of the moved row,
  or (self), (action), (from) or (to) of its move. The door's own schema
  judges it."
  [moved t mapping]
  (not-empty
   (into {}
         (keep (fn [[k src]]
                 (let [src (str src)
                       v (case src
                           "(self)" (str (:resource-id t))
                           "(action)" (some-> (:action t) name)
                           "(from)" (some-> (:from-state t) name)
                           "(to)" (some-> (:to-state t) name)
                           (field-at moved (mapv keyword (str/split src #"\."))))]
                   (when (some? v) [(keyword (name k)) v]))))
         mapping)))

(defn- walker
  "The invoke opts a walk on row `id` runs under (E-3.4): the owner,
  wearing its grant as it stands NOW, and carrying the subscription;
  nil when an agent's grant no longer admits that door on that row. An
  owner with no grant is a person, or, on a kind's own subscription
  (E-5.4, which only the boot writes), the system principal the kind's
  declaration names, so a wall such as a mirror's admits its walk."
  [eng sub t kind door id]
  ;; the why names the move it heard, so the walked row's history says
  ;; which row's move walked it (ticket 082ec682)
  (let [{:keys [owner owner_grant managed_by]} (:data sub)
        hand #(assoc % :subscription (str (:id sub))
                       :why (str "Subscription " (:id sub) " heard "
                                 (some-> (:action t) name) " on "
                                 (some-> (:kind t) name) " "
                                 (:resource-id t) "."))]
    (if (some-> owner_grant str not-empty)
      (let [who (t/principal {:id (str owner) :type :agent :display (str owner)})
            g (when-some [gid (owner-grant eng sub)]
                (:grant (grants/visibility eng gid who)))]
        (when (webhooks/holds-door? who g kind door id)
          {:principal (hand who) :grant g}))
      {:principal (hand (t/principal
                         (cond-> {:id (str owner) :display (str owner)}
                           (= "kind" (some-> managed_by name)) (assoc :type :system))))})))

(defn- record-refusal!
  "A walk's refusal other than `wrong_state`, kept on the subscription
  (R-C.13): its last refusal and when, for a seat or a person to read."
  [eng sub kind door id e]
  (let [why (str (name kind) "." (name door) " on " id " was refused: "
                 (inv/problem-reason e))]
    (warn! "subscription " (:id sub) " kept a refusal: " why)
    (try
      (inv/invoke! eng :subscription (str (:id sub)) :note_deliveries
                   {:last_refusal (subs why 0 (min 480 (count why)))
                    :last_refused_at (str ((:now-fn eng)))}
                   {:principal webhooks/deliverer-actor})
      (catch Exception x
        (warn! "could not keep the refusal on subscription " (:id sub) ": "
               (ex-message x))))))

(defn- walk!
  "One match on a walk receiver (R-C.11): the door on each row `id_from`
  names, each its own write after the source's. No row is a skip,
  thrown (E-3.8); a row already moved (`wrong_state`, R-C.13) or a lost
  grant is a skip, noted; any other refusal is kept (`record-refusal!`)."
  [eng sub t]
  (let [{:keys [kind door id_from input]} (get-in sub [:data :receiver])
        kind (keyword (name kind))
        door (keyword (name door))
        self (str (:resource-id t))
        moved (stored-row eng (keyword (name (:kind t))) self)
        ids (walk-targets eng kind self moved id_from)]
    (when (empty? ids)
      (throw (ex-info (str "no " (name kind) " row is named by this move, so it"
                           " walks no " (name door))
                      {::skip :no-target})))
    (let [walked (reduce
                  (fn [n id]
                    (if-some [opts (walker eng sub t kind door id)]
                      (try
                        (inv/invoke! eng kind id door (walk-input moved t input) opts)
                        (inc (long n))
                        (catch clojure.lang.ExceptionInfo e
                          (cond
                            (= :wrong-state (:waymark10/problem (ex-data e)))
                            (do (record-skip! eng sub e) n)

                            (inv/refusal? e)
                            (do (record-refusal! eng sub kind door id e) n)

                            :else (throw e))))
                      (do (record-skip! eng sub (ex-info (str "its owner no longer holds "
                                                              (name kind) "." (name door)
                                                              " on " id (lapse-note eng sub))
                                                         {::skip :not-granted}))
                          n)))
                  0 ids)]
      (when (pos? (long walked))
        (note-match! eng sub ((:now-fn eng)))))))

(defn- deliver!
  "One match: the seat's pending marks, or a skip, thrown."
  [eng sub t]
  (let [seat-id (str (get-in sub [:data :receiver :id]))
        seat (stored-row eng :seat seat-id)
        owner (some-> (get-in sub [:data :owner]) str)]
    (cond
      ;; a seat's own wake marks (`mark_wake`) never wake it (leases
      ;; 5a): `unheard` passes every schedule move over, and this holds
      ;; it where the wake is made
      (= :schedule (keyword (name (:kind t))))
      (throw (ex-info (str "a schedule's move is the engine's own writing"
                           " about a wake, so it wakes no seat")
                      {::skip :own-mark}))

      (or (nil? seat) (contains? #{:retired :merged} (:state seat)))
      (throw (ex-info (str "seat " seat-id " is gone or retired, so this"
                           " subscription fires nothing")
                      {::skip :gone}))

      (not (owner-holds-fire? eng sub seat-id))
      (throw (ex-info (str "its owner " owner " no longer holds seat.fire on seat "
                           seat-id (lapse-note eng sub))
                      {::skip :not-granted}))

      :else
      (when-some [wake (wake-of eng seat (get-in sub [:data :receiver]) t)]
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
   :deliver (fn [sub t] (if (webhooks/walk-receiver? sub)
                          (walk! eng sub t)
                          (deliver! eng sub t)))
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
