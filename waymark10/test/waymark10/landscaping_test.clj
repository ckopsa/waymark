(ns waymark10.landscaping-test
  "The landscaping booking (docs/spec-related-row-law.md §3 worked
  example, build step 12): the fixture pair `crew` and `booking`, and
  the scene's own sentence as the acceptance test — booking the
  Hendersons' patio with the crew already at the Parks' is refused,
  naming the Parks' job and the free crew. The free crew is a `first`
  in :evidence: correlated, evidence-only, asked when the guard refuses."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.declare :refer [defguard refuse]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

;; ── the fixture pair: crew, booking ───────────────────────────────────

(defguard the-crew-is-free
  (refuse "That crew is already at {b.customer_name} from {b.starts_at} to {b.ends_at}. {free.name} is free.")
  '(none [b :booking {:crew_id   (= (input :crew_id))
                      :starts_at (< (input :ends_at))
                      :ends_at   (> (input :starts_at))
                      :state     (in "scheduled" "in_progress")}])
  {:remedies [{:door :booking/reschedule :id '(evidence :b)}]
   :evidence {:free '(first [c :crew {:state (= "active")}]
                       (none [o :booking {:crew_id   (= (get c :id))
                                          :starts_at (< (input :ends_at))
                                          :ends_at   (> (input :starts_at))
                                          :state     (in "scheduled" "in_progress")}]))}})

(def ^:private safety
  {:idempotent true :reversible false :confirm false :one-way "Booked is booked."})

(def ^:private slot
  [:map
   [:crew_id {:kind :crew} :waymark/ref]
   [:starts_at :waymark/instant]
   [:ends_at :waymark/instant]])

(def ^:private world
  (delay
    {:crew (r/resource
            {:kind :crew
             :plural "crews"
             :states [:active :resting]
             :initial :active
             :terminal #{:resting}
             :summary "{data.name} · {state}"
             :schema [:map [:name [:string {:min 1 :max 80}]]]
             :filterable {:state #{:eq :in}}
             :actions {:rest {:from #{:active} :to :resting :safety safety}}})
     :booking (r/resource
               {:kind :booking
                :plural "bookings"
                :states [:draft :scheduled :in_progress :done]
                :initial :draft
                :terminal #{:done}
                :summary "{data.customer_name} · {state}"
                :schema [:map
                         [:customer_name [:string {:min 1 :max 80}]]
                         [:crew_id {:optional true :kind :crew} [:maybe :waymark/ref]]
                         [:starts_at {:optional true} [:maybe :waymark/instant]]
                         [:ends_at {:optional true} [:maybe :waymark/instant]]]
                :filterable {:state #{:eq :in} :crew_id #{:eq} :starts_at #{:eq} :ends_at #{:eq}}
                :actions {:schedule {:from #{:draft} :to :scheduled :input slot
                                     :guards [the-crew-is-free] :safety safety}
                          :reschedule {:from #{:scheduled} :to :scheduled :input slot
                                       :guards [the-crew-is-free] :safety safety}
                          :start {:from #{:scheduled} :to :in_progress :safety safety}
                          :finish {:from #{:scheduled :in_progress} :to :done :safety safety}}})}))

(defn- the-guard []
  (first (get-in (:booking @world) [:actions :schedule :guards])))

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
      :< (neg? c)
      :<= (not (pos? c))
      :> (pos? c)
      :>= (not (neg? c))
      :in (contains? (set (map str values)) v))))

(defn- ctx-over
  "The write ctx's hooks over `rows`: ids id-ordered, as ids-matching
  answers them, and `sees?` as the caller's grant."
  ([rows] (ctx-over rows nil))
  ([rows sees?]
   (let [hits (fn [kind conds]
                (filter (fn [row] (and (= kind (:kind row)) (every? #(holds? row %) conds)))
                        rows))]
     (cond-> {:rows-matching (fn [kind conds limit]
                               (vec (take limit (sort (map :id (hits kind conds))))))
              :count-matching (fn [kind conds] (count (hits kind conds)))
              :read (fn [kind id]
                      (some #(when (and (= kind (:kind %)) (= (str id) (:id %))) %) rows))}
       sees? (assoc :grant {:row? sees?})))))

(def ^:private hendersons {:id "bk-hendersons" :data {:customer_name "the Hendersons' patio"}})

(defn- thursday [crew]
  {:crew_id crew :starts_at "2026-10-22T09:00:00Z" :ends_at "2026-10-22T11:00:00Z"})

;; ── the scene ─────────────────────────────────────────────────────────

(deftest the-scene-is-refused-in-its-own-sentence
  (let [[v d] (g/evaluate (the-guard) hendersons (thursday "crew-oak") (ctx-over rows))]
    (is (t/deny? v))
    (is (= {:b "bk-parks" :free "crew-elm"} (:evidence v))
        "the Parks' job, and the first active crew with no overlapping booking")
    (is (= (str "That crew is already at the Parks' yard from "
                "08:00 to 12:00. "
                "The Elm Street crew is free.")
           (g/render-reason d v hendersons)))
    (is (= [{:door :booking/reschedule :id "bk-parks"}]
           (g/resolve-remedies d hendersons (thursday "crew-oak") (:evidence v))))))

(deftest the-free-crew-is-booked
  (is (t/allow? (first (g/evaluate (the-guard) hendersons (thursday "crew-elm") (ctx-over rows))))))

(deftest first-binds-only-what-the-caller-may-see
  (testing "the Elm Street crew hidden: no other active crew is free, so free binds nothing"
    (let [[v d] (g/evaluate (the-guard) hendersons (thursday "crew-oak")
                            (ctx-over rows (fn [_ id] (not= "crew-elm" id))))]
      (is (t/deny? v))
      (is (= {:b "bk-parks"} (:evidence v)))
      (is (= (str "That crew is already at the Parks' yard from "
                  "08:00 to 12:00.")
             (g/render-reason d v hendersons))
          "the sentence that reads the unbound free drops; {free.name} never renders"))))

;; ── first: correlated, canonical, evidence-only ───────────────────────

(deftest first-is-canonical-and-evidence-only
  (let [free (get-in (the-guard) [:evidence :free])]
    (is (= :crew (:kind free)))
    (is (= '(= (get (it 0) :id)) (get-in (vec (:pred free)) [2 :crew_id]))
        "the binder is erased: the pred's where reads the candidate as (it 0)"))
  (testing "a verdict may not ask first"
    (is (thrown? Exception
                 (g/expr {:name :first-in-a-verdict :explain "No crew is free."
                          :when '(first [c :crew {:state (= "active")}] true)})))))

(deftest the-fixture-pair-assembles
  (is (map? (ca/run-all {:kinds {:crew (:crew @world) :booking (:booking @world)}}))))

(defn- assemble-with [guard]
  (ca/run-all {:kinds {:crew (:crew @world)
                       :booking (assoc-in (:booking @world) [:actions :schedule :guards] [guard])}}))

(defn- free-guard [opts free]
  (g/expr (merge {:name :crew-free :explain "That crew is busy. {free.name} is free."
                  :when '(none [b :booking {:crew_id (= (input :crew_id))}])
                  :evidence {:free free}}
                 opts)))

(deftest the-gate-judges-a-first-as-it-judges-exists
  (testing "[exists-unfiltered]: a first's where asks a field its kind does not index"
    (is (thrown-with-msg? Exception #"\[exists-unfiltered\]"
                          (assemble-with (free-guard {} '(first [c :crew {:name (= "Elm")}] true))))))
  (testing "[exists-unfiltered]: a first's pred asks a field its kind does not index"
    (is (thrown-with-msg? Exception #"\[exists-unfiltered\]"
                          (assemble-with
                           (free-guard {} '(first [c :crew {:state (= "active")}]
                                             (none [o :booking {:customer_name (= (get c :name))}])))))))
  (testing "[remedy-on-evidence-kind]: (evidence :free) names a door of the first's kind"
    (is (thrown-with-msg? Exception #"\[remedy-on-evidence-kind\]"
                          (assemble-with (free-guard {:remedies [{:door :booking/reschedule :id '(evidence :free)}]}
                                                     '(first [c :crew {:state (= "active")}] true)))))
    (is (map? (assemble-with (free-guard {:remedies [{:door :crew/rest :id '(evidence :free)}]}
                                         '(first [c :crew {:state (= "active")}] true))))
        "a door of crew is the free crew's")))
