(ns waymark10.occupies-test
  "`:occupies` (docs/spec-calendar-time.md §5, build step 7): the
  landscaping fixture pair of waymark10.landscaping-test with its
  hand-written the-crew-is-free retired. The booking declares that it
  holds its crew for its window, the engine derives the guard, and the
  Hendersons' patio is refused in the same sentence. A time_off kind
  holds the same crew, so the derived tree is one none per occupier."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.guards :as g]
            [waymark10.occupies :as occupies]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

;; ── the fixture pair, and time off ───────────────────────────────────

(def ^:private safety
  {:idempotent true :reversible false :confirm false :one-way "Booked is booked."})

(def ^:private slot
  [:map
   [:crew_id {:kind :crew} :waymark/ref]
   [:starts_at :waymark/instant]
   [:ends_at :waymark/instant]])

;; reschedule takes only the times: the crew is the row's
(def ^:private times
  [:map
   [:starts_at :waymark/instant]
   [:ends_at :waymark/instant]])

(def ^:private window
  [[:crew_id {:optional true :kind :crew} [:maybe :waymark/ref]]
   [:starts_at {:optional true} [:maybe :waymark/instant]]
   [:ends_at {:optional true} [:maybe :waymark/instant]]])

(def ^:private window-filters
  {:state #{:eq :in} :crew_id #{:eq} :starts_at #{:eq} :ends_at #{:eq}})

(def ^:private crew
  {:kind :crew
   :plural "crews"
   :states [:active :resting]
   :initial :active
   :terminal #{:resting}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:calendar_id {:optional true} [:maybe :string]]]
   :filterable {:state #{:eq :in}}
   :actions {:rest {:from #{:active} :to :resting :safety safety}}})

(def ^:private booking
  {:kind :booking
   :plural "bookings"
   :states [:draft :scheduled :in_progress :done]
   :initial :draft
   :terminal #{:done}
   :summary "{data.customer_name} · {state}"
   :schema (into [:map [:customer_name [:string {:min 1 :max 80}]]] window)
   :filterable window-filters
   :occupies [{:resource :crew_id :from :starts_at :to :ends_at
               :in #{:scheduled :in_progress} :doors #{:schedule :reschedule}}]
   :explain {:crew-is-free (str "That crew is already at {b.customer_name} from {b.starts_at} "
                                "to {b.ends_at}. {free.name} is free.")}
   :actions {:schedule {:from #{:draft} :to :scheduled :input slot :safety safety}
             :reschedule {:from #{:scheduled} :to :scheduled :input times :safety safety}
             :start {:from #{:scheduled} :to :in_progress :safety safety}
             :finish {:from #{:scheduled :in_progress} :to :done :safety safety}}})

(def ^:private time-off
  {:kind :time_off
   :plural "time_offs"
   :states [:requested :approved :withdrawn]
   :initial :requested
   :terminal #{:withdrawn}
   :summary "Time off · {state}"
   :schema (into [:map] window)
   :filterable window-filters
   :occupies [{:resource :crew_id :from :starts_at :to :ends_at
               :in #{:approved} :doors #{:create :approve}}]
   :actions {:approve {:from #{:requested} :to :approved :safety safety}
             :withdraw {:from #{:requested :approved} :to :withdrawn :safety safety}}})

(defn- world
  "The three kinds through the registry's derivation; `over` merges
  into a kind's declaration."
  [& {:as over}]
  (occupies/derive-kinds
   {:crew (r/resource (merge crew (:crew over)))
    :booking (r/resource (merge booking (:booking over)))
    :time_off (r/resource (merge time-off (:time_off over)))}))

(defn- guard-on [w kind door gname]
  (some #(when (= gname (:name %)) %)
        (if (= :create door)
          (get-in w [kind :create-guards])
          (get-in w [kind :actions door :guards]))))

;; The Ash Court crew is free but resting; the Birch Road crew is the
;; oldest active one, but at the Diazes'; the Oak Lane crew is at the
;; Parks'. The Elm Street crew is the first active crew with no booking.
(def ^:private rows
  [{:id "crew-ash" :kind :crew :state :resting :created_at "2026-08-01T09:00:00Z"
    :data {:name "The Ash Court crew"}}
   {:id "crew-birch" :kind :crew :state :active :created_at "2026-08-15T09:00:00Z"
    :data {:name "The Birch Road crew"}}
   {:id "crew-oak" :kind :crew :state :active :created_at "2026-09-01T09:00:00Z"
    :data {:name "The Oak Lane crew"}}
   {:id "crew-elm" :kind :crew :state :active :created_at "2026-09-02T09:00:00Z"
    :data {:name "The Elm Street crew"}}
   {:id "bk-parks" :kind :booking :state :scheduled :created_at "2026-10-10T09:00:00Z"
    :data {:customer_name "the Parks' yard" :crew_id "crew-oak"
           :starts_at "2026-10-22T08:00:00Z" :ends_at "2026-10-22T12:00:00Z"}}
   {:id "bk-diaz" :kind :booking :state :in_progress :created_at "2026-10-11T09:00:00Z"
    :data {:customer_name "the Diazes' beds" :crew_id "crew-birch"
           :starts_at "2026-10-22T09:00:00Z" :ends_at "2026-10-22T11:00:00Z"}}])

;; the Elm Street crew's approved day off, Thursday morning
(def ^:private elm-off
  {:id "to-elm" :kind :time_off :state :approved :created_at "2026-10-12T09:00:00Z"
   :data {:crew_id "crew-elm" :starts_at "2026-10-22T07:00:00Z" :ends_at "2026-10-22T13:00:00Z"}})

(defn- field-of [row f]
  (case f
    :state (some-> (:state row) name)
    :id (:id row)
    (get-in row [:data f])))

(defn- holds? [row {:keys [field op value values]}]
  (let [v (str (field-of row field))
        c (compare v (str value))]
    (case op
      := (zero? c)
      :not= (not (zero? c))
      :< (neg? c)
      :<= (not (pos? c))
      :> (pos? c)
      :>= (not (neg? c))
      :in (contains? (set (map str values)) v))))

(defn- ctx-over
  "The write ctx's hooks over `rows`: ids id-ordered, as ids-matching
  answers them."
  [rows]
  (let [hits (fn [kind conds]
               (filter (fn [row] (and (= kind (:kind row)) (every? #(holds? row %) conds)))
                       rows))]
    {:rows-matching (fn [kind conds limit]
                      (vec (take limit (sort (map :id (hits kind conds))))))
     :count-matching (fn [kind conds] (count (hits kind conds)))
     :read (fn [kind id]
             (some #(when (and (= kind (:kind %)) (= (str id) (:id %))) %) rows))}))

(def ^:private hendersons {:id "bk-hendersons" :data {:customer_name "the Hendersons' patio"}})

(defn- thursday [crew]
  {:crew_id crew :starts_at "2026-10-22T09:00:00Z" :ends_at "2026-10-22T11:00:00Z"})

;; ── the scene, migrated ─────────────────────────────────────────────────

(deftest the-scene-is-refused-in-the-same-sentence
  (let [free (guard-on (world) :booking :schedule :crew-is-free)
        [v d] (g/evaluate free hendersons (thursday "crew-oak") (ctx-over rows))]
    (is (t/deny? v))
    (is (= {:b "bk-parks" :free "crew-elm"} (:evidence v))
        "the Parks' job, and the first active crew with no overlapping booking")
    (is (= (str "That crew is already at the Parks' yard from "
                "08:00 to 12:00. "
                "The Elm Street crew is free.")
           (g/render-reason d v hendersons)))
    (is (= [{:door :booking/reschedule :id "bk-parks"}]
           (g/resolve-remedies d hendersons (thursday "crew-oak") (:evidence v)))
        "the remedy is the door that moves a holding booking's window")))

(deftest the-free-crew-is-booked
  (let [free (guard-on (world) :booking :schedule :crew-is-free)]
    (is (t/allow? (first (g/evaluate free hendersons (thursday "crew-elm") (ctx-over rows)))))))

(deftest reschedule-reads-the-crew-from-the-row
  (let [free (guard-on (world) :booking :reschedule :crew-is-free)
        at (fn [crew] (assoc-in hendersons [:data :crew_id] crew))
        times (dissoc (thursday nil) :crew_id)]
    (testing "the door takes only the times, so the crew is (data :crew_id)"
      (let [[v] (g/evaluate free (at "crew-oak") times (ctx-over rows))]
        (is (t/deny? v))
        (is (= "bk-parks" (get-in v [:evidence :b]))))
      (is (t/allow? (first (g/evaluate free (at "crew-elm") times (ctx-over rows))))))))

(deftest one-none-per-kind-that-holds-the-crew
  (let [w (world)]
    (testing "a booking is refused by the crew's approved time off"
      (let [[v] (g/evaluate (guard-on w :booking :schedule :crew-is-free)
                            hendersons (thursday "crew-elm") (ctx-over (conj rows elm-off)))]
        (is (t/deny? v))
        (is (= "to-elm" (get-in v [:evidence :t])))))
    (testing "time off is refused by a booking, in the derived default sentence"
      (let [row {:data {}}
            [v d] (g/evaluate (guard-on w :time_off :create :crew-is-free)
                              row (thursday "crew-oak") (ctx-over rows))]
        (is (t/deny? v))
        (is (= {:b "bk-parks" :free "crew-elm"} (:evidence v)))
        (is (= "That crew is held from 08:00 to 12:00." (g/render-reason d v row))
            "the part that reads the unbound time off drops")))
    (testing "approve takes no input: every side is the row's"
      (let [[v] (g/evaluate (guard-on w :time_off :approve :crew-is-free)
                            {:id "to-oak" :data (thursday "crew-oak")} {} (ctx-over rows))]
        (is (t/deny? v))))))

;; ── -is-working ─────────────────────────────────────────────────────────

(defn- tree [form]
  (:when (g/expr {:name :tree :explain "The tree." :when form})))

(deftest the-working-guard-reads-the-resource-calendar
  (testing "no :calendar on the crew: the default work_calendar"
    (is (= (tree '(working? (input :starts_at) (input :ends_at)))
           (:when (guard-on (world) :booking :schedule :crew-is-working)))))
  (testing "the crew's :calendar, followed from the side the door carries"
    (let [w (world :crew {:calendar :calendar_id})]
      (is (= (tree '(working? (input :starts_at) (input :ends_at)
                              (follow :crew (input :crew_id) :calendar_id)))
             (:when (guard-on w :booking :schedule :crew-is-working))))
      (is (= (tree '(working? (input :starts_at) (input :ends_at)
                              (follow :crew (data :crew_id) :calendar_id)))
             (:when (guard-on w :booking :reschedule :crew-is-working)))))))

(deftest derivation-is-idempotent
  (let [w (world)]
    (is (= w (occupies/derive-kinds w))
        "a registry built again from its own rdefs adds no second guard")))

;; ── the gate ─────────────────────────────────────────────────────────────

(deftest the-occupying-world-assembles
  (is (map? (ca/run-all {:kinds (world)}))))

(deftest the-findings
  (testing "[occupies-unfiltered]: the window's end is not filterable on booking"
    (is (thrown-with-msg? Exception #"booking \[occupies-unfiltered\]"
                          (ca/run-all {:kinds (world :booking {:filterable (dissoc window-filters :ends_at)})}))))
  (testing "[resource-uncalendared]: the crew names no hours"
    (is (some #(re-find #"crew \[resource-uncalendared\]" %)
              (:info (ca/run-all {:kinds (world)}))))
    (is (empty? (:info (ca/run-all {:kinds (world :crew {:calendar :calendar_id})}))))))

(deftest the-declaration-is-judged
  (testing "[occupies]: a door the kind does not have"
    (is (thrown-with-msg? Exception #"\[occupies\]"
                          (world :booking {:occupies [{:resource :crew_id :from :starts_at :to :ends_at
                                                       :in #{:scheduled} :doors #{:book}}]}))))
  (testing "[occupies]: a resource that is no ref"
    (is (thrown-with-msg? Exception #"\[occupies\]"
                          (world :booking {:occupies [{:resource :customer_name :from :starts_at
                                                       :to :ends_at :in #{:scheduled}
                                                       :doors #{:schedule}}]}))))
  (testing "[occupies]: :explain names a guard nothing derives"
    (is (thrown-with-msg? Exception #"\[occupies\]"
                          (world :booking {:explain {:truck-is-free "No truck."}}))))
  (testing "[occupies]: :calendar names a field the resource does not have"
    (is (thrown-with-msg? Exception #"\[occupies\]"
                          (world :crew {:calendar :hours_id})))))
