(ns workqueue10.onboarding-trial
  "The onboarding trial's count (ticket b5518453, docs/onboarding-trial.md).

  On a clone seeded `onboarding-trial`, one document says what it took
  to go from a fresh house to a live org: each move made as the owner
  (door, row), each held call and who allowed it, each refusal an agent
  met (guard, remedy, from the sittings' `refusal_log`), each approval
  request, and the totals.

  It reads the log after the seed's last write, the completion
  marker's, so the seed's own steps, which walk as the owner too, are
  not counted: only what the trial did."
  (:require [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def seed-name "onboarding-trial")

(def ^:private read-cap
  "The most log rows one count reads."
  200000)

(defn- text [v]
  (some-> v (as-> x (if (keyword? x) (name x) (str x))) not-empty))

(defn- field
  "`k` of a row's data, however it was spelled."
  [m k]
  (if (contains? m k) (get m k) (get m (name k))))

(defn- actor-id [actor]
  (text (if (map? actor) (field actor :id) actor)))

(defn owner-of
  "The trial's owner: the `owner` param the boot gave, else the seed's."
  []
  (text (or (:owner (seed/read-params (System/getenv "WAYMARK10_SEED_PARAMS")))
            (get-in (seed/read-seed seed-name) [:params :owner]))))

(defn after-the-seed
  "The log rows after the last one on the seed's marker; none when the
  seed's marker is not there."
  [log]
  (let [marker (str "seed-" seed-name)
        v (vec log)
        i (last (keep-indexed #(when (= marker (str (:resource-id %2))) %1) v))]
    (if i (subvec v (inc i)) [])))

(defn- call-of
  "The call a log row was made in (ticket 09053f55): the request's
  correlation id, which every inner write of one call shares, else the
  row's moment, else its place in the log."
  [t i]
  (or (text (:correlation-id t)) (text (:at t)) (str "move-" i)))

(defn- by-engine
  "Each move flagged `:by_engine` when it was a consequence of another
  door in its call rather than the owner's own (ticket 09053f55). The
  call's own door is its first move that is not a birth (a birth has
  no from state), else its first move; a move on any other kind or
  door is the engine's. The log does not promise an inner write lands
  after the door that opened it, so the order alone cannot say."
  [moves]
  (let [own (reduce (fn [m mv]
                      (let [c (:call mv)
                            seen (get m c)]
                        (if (or (nil? seen) (and (:birth? seen) (some? (:from mv))))
                          (assoc m c {:pair [(:kind mv) (:door mv)]
                                      :birth? (nil? (:from mv))})
                          m)))
                    {} moves)]
    (mapv #(assoc % :by_engine (not= [(:kind %) (:door %)]
                                     (:pair (get own (:call %)))))
          moves)))

(defn tally
  "The count, from what was read: `owner` the trial owner's member id,
  `log` the log rows after the seed, `held` the held_call rows,
  `sittings` the sitting rows and `asks` the approval_request rows."
  [{:keys [owner log held sittings asks]}]
  (let [moves (by-engine
               (vec (for [[i t] (map-indexed vector log)
                          :when (= owner (actor-id (:actor t)))]
                      {:kind (text (:kind t)) :row (text (:resource-id t))
                       :door (text (:action t))
                       :from (text (:from-state t)) :to (text (:to-state t))
                       :at (text (:at t))
                       :call (call-of t i)})))
        held (vec (for [r held :let [d (:data r)]]
                    {:id (text (:id r)) :tool (text (field d :tool))
                     :state (text (:state r))
                     :allowed_by (text (field d :decided_by))}))
        refusals (vec (for [s sittings
                            :let [d (:data s)]
                            e (field d :refusal_log)]
                        {:sitting (text (:id s)) :seat (text (field d :seat))
                         :guard (text (field e :guard))
                         :remedies (mapv text (field e :remedies))
                         :door (text (field e :door)) :kind (text (field e :kind))
                         :type (text (field e :type)) :at (text (field e :at))}))
        ;; the calls turned away before any guard judged them
        ;; (ticket 09053f55): schema-invalid, not-found
        rejections (vec (for [s sittings
                              :let [d (:data s)]
                              e (field d :rejection_log)]
                          {:sitting (text (:id s)) :seat (text (field d :seat))
                           :tool (text (field e :tool))
                           :kind (text (field e :kind)) :door (text (field e :door))
                           :type (text (field e :type)) :status (field e :status)
                           :errors (mapv text (field e :errors))
                           :at (text (field e :at))}))
        agent-calls (reduce + 0 (for [s sittings
                                      [_ line] (field (:data s) :served)]
                                  (long (or (field line :calls) 0))))
        asks (vec (for [r asks]
                    {:id (text (:id r)) :state (text (:state r))}))]
    {:seed seed-name
     :owner owner
     :owner_moves moves
     :held_calls held
     :refusals refusals
     :rejections rejections
     :approval_requests asks
     :totals {:owner_steps (count (distinct (map :call moves)))
              :owner_row_moves (count (remove :by_engine moves))
              :held_calls (count held)
              :held_calls_allowed_by_owner (count (filter #(= owner (:allowed_by %)) held))
              :refusals (count refusals)
              :rejections (count rejections)
              :agent_calls agent-calls
              :approval_requests (count asks)}}))

(defn- rows
  "Every row of `kind`; none when this engine serves no such kind."
  [eng kind]
  (let [st (:storage eng)]
    (try (store/with-tx st #(store/query-rows st % kind {} {:all true}))
         (catch Exception _ []))))

(defn count-of
  "The count for the trial on `eng`, its owner `owner`."
  [eng owner]
  (let [st (:storage eng)
        log (store/with-tx st #(store/transitions st % {:since 0} {:limit read-cap}))]
    (tally {:owner (text owner)
            :log (after-the-seed log)
            :held (rows eng :held_call)
            :sittings (rows eng :sitting)
            :asks (rows eng :approval_request)})))
