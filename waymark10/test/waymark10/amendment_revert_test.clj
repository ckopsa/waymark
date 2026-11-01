(ns waymark10.amendment-revert-test
  "Amendments step 9 (docs/spec-amendment.md §14): revert puts back
  `before` once, through the target's restate, and leaves a field
  another hand changed alone and named."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.amendment-revert :as revert]
            [waymark10.server.amendments :as amendments]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.promises :as promises]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(def ^:private office
  {:name "planner"
   :charter "Plan the week."
   :scope [{:kind "amendment" :actions ["withdraw"]}]
   :held_for []
   :substitute_for []
   :standing_ttl_seconds 604800
   :cadence_seconds 3600
   :budget_usd_per_week 5M
   :sitting_budget_tokens 60000
   :rows_per_firing 20})

(defn- load-row [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st #(store/load-row st % kind id {}))))

(defn- restates [eng sid]
  (let [st (:storage eng)]
    (count (filter #(and (= sid (str (:resource-id %))) (= "restate" (name (:action %))))
                   (store/with-tx st #(store/transitions st % {:kind :seat}
                                                         {:newest-first true :limit 1000}))))))

(def ^:private walker
  "A seat that walks the replayed amendments."
  (assoc office :name "walker" :walk "amendment"
         :scope [{:kind "amendment" :actions ["withdraw"] :filter {:state "replayed"}}]))

(defn- eng! []
  (engine/engine {:storage (memory/storage)
                  :resources [promises/promise amendments/amendment]}))

(defn- amendment! [eng sid patch]
  (str (:id (:row (inv/create! eng :amendment
                               {:title "Plan next week"
                                :changes {:kind "seat" :id sid :door "restate" :patch patch}}
                               {:principal elena})))))

(defn- adopted
  ([patch] (adopted office patch))
  ([seat patch]
   (let [eng (eng!)
         sid (str (:id (:row (inv/create! eng :seat seat {:principal elena}))))
         aid (amendment! eng sid patch)]
     (amendments/replay! eng aid)
     (inv/invoke! eng :amendment aid :adopt nil {:principal elena :idempotency-key "adopt"})
     {:eng eng :sid sid :aid aid})))

(defn- walk-state
  "The `state` the seat's walk filter names."
  [eng sid]
  (let [f (revert/walk-filter-of (:data (load-row eng :seat sid)))]
    (some-> (or (get f :state) (get f "state")) name)))

(defn- restate-seat!
  "Another hand's restate of the seat: its current values, overlaid."
  [eng sid aid patch]
  (let [seat (load-row eng :seat sid)
        fields (get-in (load-row eng :amendment aid) [:data :replay :target :input])
        whole (into {} (keep (fn [f] (let [v (get (:data seat) (keyword (name f)))]
                                       (when (some? v) [(keyword (name f)) v]))))
                    fields)]
    (inv/invoke! eng :seat sid :restate (merge whole patch)
                 {:principal elena :idempotency-key (str "restate-" (hash patch))
                  :acknowledged #{:opens-no-crack}
                  :if-match (inv/etag :seat sid (:version seat))})))

(deftest plan-reads-each-field
  (is (= {:restore {:charter "a"} :conflicts ["walk"] :unchanged ["cadence_seconds"]}
         (revert/plan {:charter "a" :walk nil :cadence_seconds 60}
                      {:charter "b" :walk "x" :cadence_seconds 120}
                      {:charter "b" :walk "y" :cadence_seconds 60}))))

(deftest the-owners-revert-restores-before
  (let [{:keys [eng sid aid]} (adopted {:charter "Plan next week."})]
    (is (= "Plan next week." (get-in (load-row eng :seat sid) [:data :charter])))
    (inv/invoke! eng :amendment aid :revert nil {:principal elena :idempotency-key "revert"})
    (is (= "reverted" (name (:state (load-row eng :amendment aid)))))
    (is (= "Plan the week." (get-in (load-row eng :seat sid) [:data :charter])))
    (is (= 2 (restates eng sid)) "one restate by adopt, one by revert")
    (testing "a second revert writes no restate"
      (try (inv/invoke! eng :amendment aid :revert nil {:principal elena :idempotency-key "again"})
           (catch Exception _ nil))
      (try (revert/revert! eng aid) (catch Exception _ nil))
      (is (= "reverted" (name (:state (load-row eng :amendment aid)))))
      (is (= 2 (restates eng sid))))))

(deftest a-conflicted-field-is-left-and-named
  (let [{:keys [eng sid aid]} (adopted {:charter "Plan next week." :rows_per_firing 10})]
    (restate-seat! eng sid aid {:charter "Plan the fortnight."})
    (inv/invoke! eng :amendment aid :revert nil {:principal elena :idempotency-key "revert"})
    (let [seat (:data (load-row eng :seat sid))
          a (:data (load-row eng :amendment aid))]
      (is (= "Plan the fortnight." (:charter seat)) "the other hand's change stands")
      (is (= 20 (:rows_per_firing seat)) "the untouched field is restored")
      (is (= ["charter"] (get-in a [:revert :conflicts])))
      (is (= ["rows_per_firing"] (get-in a [:revert :restored]))))))

(deftest a-row-already-holding-before-writes-no-restate
  (let [{:keys [eng sid aid]} (adopted {:charter "Plan next week."})]
    (restate-seat! eng sid aid {:charter "Plan the week."})
    (let [n (restates eng sid)]
      (inv/invoke! eng :amendment aid :revert nil {:principal elena :idempotency-key "revert"})
      (is (= "reverted" (name (:state (load-row eng :amendment aid)))))
      (is (= n (restates eng sid)))
      (is (= ["charter"] (get-in (load-row eng :amendment aid) [:data :revert :unchanged]))))))

(deftest the-engines-revert-runs-once
  (let [{:keys [eng sid aid]} (adopted {:charter "Plan next week."})]
    (revert/revert! eng aid {:verdict "worse"})
    (revert/revert! eng aid {:verdict "worse"})
    (is (= "reverted" (name (:state (load-row eng :amendment aid)))))
    (is (= "worse" (get-in (load-row eng :amendment aid) [:data :verdict])))
    (is (= "Plan the week." (get-in (load-row eng :seat sid) [:data :charter])))
    (is (= 2 (restates eng sid)))))

(deftest a-walk-filter-amendment-replays-and-adopts-through-the-owner
  (let [{:keys [eng sid aid]} (adopted walker {:walk_filter {:state "draft"}})
        a (:data (load-row eng :amendment aid))]
    (is (= "trial" (name (:state (load-row eng :amendment aid)))))
    (is (= "draft" (walk-state eng sid)) "the walk's scope entry carries the filter")
    (is (nil? (get-in (load-row eng :seat sid) [:data :walk_filter])) "no seat stores the field")
    (is (contains? (:replay a) :walk) "the replay ran the walk part")
    (is (some? (get-in a [:before :walk_filter])))
    (is (not= (get-in a [:before :walk_filter]) (get-in a [:after :walk_filter])))
    (testing "the owner's tap puts the walk filter back"
      (inv/invoke! eng :amendment aid :revert nil {:principal elena :idempotency-key "revert"})
      (is (= "reverted" (name (:state (load-row eng :amendment aid)))))
      (is (= "replayed" (walk-state eng sid))))))

(deftest a-walk-filter-naming-an-entry-is-refused-with-the-raw-door-named
  (let [eng (eng!)
        sid (str (:id (:row (inv/create! eng :seat walker {:principal elena}))))]
    (doseq [patch [{:walk_filter {:kind "amendment" :actions ["withdraw" "adopt"]}}
                   {:walk_filter {:actions ["adopt"]}}
                   {:scope [{:kind "promise" :actions ["restate"]}]}]]
      (let [r (try (amendment! eng sid patch) nil
                   (catch clojure.lang.ExceptionInfo e
                     {:guard (some-> (:guard (ex-data e)) name keyword)
                      :text (str (ex-message e) " " (pr-str (ex-data e)))}))]
        (is (= :an-amendment-stays-in-its-lane (:guard r)) (pr-str patch))
        (is (str/includes? (str (:text r)) "seat.restate") (pr-str patch))))))

(deftest an-armed-worse-verdict-on-a-walk-filter-proposes-a-held-revert
  (let [{:keys [eng sid aid]} (adopted walker {:walk_filter {:state "draft"}})
        n (restates eng sid)
        r (revert/revert! eng aid {:trial {} :verdict "worse"})]
    (is (some? (:held r)) (pr-str r))
    (is (= "trial" (name (:state (load-row eng :amendment aid)))))
    (is (= "draft" (walk-state eng sid)) "nothing is restored on its own")
    (is (= n (restates eng sid)))
    (testing "asked again, it is the same held call"
      (is (= (str (:id (:held r)))
             (str (:id (:held (revert/revert! eng aid {:verdict "worse"})))))))))
