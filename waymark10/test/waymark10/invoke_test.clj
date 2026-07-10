(ns waymark10.invoke-test
  "Phase-2 acceptance: the transition algorithm against real
  Postgres. Needs the waymark10_test database (make db10);
  WAYMARK10_TEST_DSN overrides the default local DSN."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]))

(def dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

(def risk-noted
  (g/expr {:name :risk-noted
           :severity :warning
           :when '(not (data :risky))
           :explain "This task is flagged risky."}))

(r/defhandler poke-handler [row _inp _ctx]
  (update-in row [:data :pokes] (fnil inc 0)))

(def task
  (r/resource
   {:kind :task
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:max 80}]]
             [:risky {:optional true} [:maybe :boolean]]
             [:pokes {:optional true} [:maybe :int]]]
    :actions
    {:poke {:from #{:open} :to :open
            :safety {:idempotent false :reversible true :confirm false}
            :handler poke-handler}
     :close {:from #{:open} :to :closed
             :guards [risk-noted]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed task is history."}}}}))

(def ^:dynamic *eng* nil)

(defn- with-engine [f]
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["plans" "meals" "tasks"
                         "waymark10_transitions" "waymark10_idempotency"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (binding [*eng* (inv/engine {:storage st :resources [fx/meal fx/plan task]})]
        (f))
      (finally (pg/close! st)))))

(use-fixtures :once with-engine)

(def colton (t/principal {:id "colton" :display "Colton"}))
(def opts {:principal colton})

(defn- problem-of [thunk]
  (try (thunk) nil
       (catch Exception e (ex-data e))))

(defn- create-plan! [start days]
  (inv/create! *eng* :plan
               {:start_date start :weeks 1
                :days (mapv (fn [d] {:date d}) days)}
               opts))

(deftest the-family-week
  (let [{:keys [row]} (create-plan! "2026-07-14" ["2026-07-14" "2026-07-15"])
        pid (:id row)]
    (testing "creation materializes the derived facts"
      (is (= :draft (:state row)))
      (is (= "2026-07-20" (str (get-in row [:data :end_date]))))
      (is (false? (get-in row [:data :all_days_covered]))))

    (testing "finalize refused while the coverage law does not hold"
      (let [p (problem-of #(inv/invoke! *eng* :plan pid :finalize nil opts))]
        (is (= :guard-refused (:waymark10/problem p)))
        (is (= "Not yet: all days covered does not hold." (:detail p)))
        (is (= [:plan/assign_meal] (:remedies p)))))

    (testing "acceptance sets are the law: a date outside the plan refuses"
      (let [p (problem-of #(inv/invoke! *eng* :plan pid :assign_meal
                                        {:date "2026-07-19" :meal_id "m"} opts))]
        (is (= :guard-refused (:waymark10/problem p)))
        (is (= "2026-07-19 is not a day of this plan." (:detail p)))))

    (testing "unknown input fields refuse, never silently drop"
      (let [p (problem-of #(inv/invoke! *eng* :plan pid :assign_meal
                                        {:date "2026-07-14" :meal_id "m" :evil 1}
                                        opts))]
        (is (= 422 (:status p)))
        (is (= {:evil ["disallowed key"]} (:errors p)))))

    (testing "covering every day flips the fact and opens the gate"
      (inv/invoke! *eng* :plan pid :assign_meal
                   {:date "2026-07-14" :meal_id "m-tacos"} opts)
      (let [{:keys [row]} (inv/invoke! *eng* :plan pid :assign_meal
                                       {:date "2026-07-15" :meal_id "m-soup"} opts)]
        (is (true? (get-in row [:data :all_days_covered])))
        (is (= 3 (:version row))))
      (let [{:keys [row transition]} (inv/invoke! *eng* :plan pid :finalize nil opts)]
        (is (= :planned (:state row)))
        (is (= "Week of 2026-07-14 · 1 wk · Planned" (:summary transition)))))

    (testing "natural replay: same action, same input, state at outcome"
      (let [res (inv/invoke! *eng* :plan pid :finalize nil opts)]
        (is (= :natural (:replayed? res)))
        (is (= 4 (get-in res [:row :version])) "no re-execution")))

    (testing "wrong state narrates with becomes-available"
      (let [p (problem-of #(inv/invoke! *eng* :plan pid :assign_meal
                                        {:date "2026-07-14" :meal_id "m2"} opts))]
        (is (= 409 (:status p)))
        (is (= {:in-states [:draft]} (:becomes-available p)))))

    (testing "dry-run validates without effect"
      (let [before (store/with-tx (:storage *eng*)
                     #(store/load-row (:storage *eng*) % :plan pid {}))
            res (inv/invoke! *eng* :plan pid :reopen nil (assoc opts :dry-run true))
            after (store/with-tx (:storage *eng*)
                    #(store/load-row (:storage *eng*) % :plan pid {}))]
        (is (:valid? res))
        (is (= (:version before) (:version after)))))))

(deftest idempotency-discipline
  (let [{:keys [row]} (inv/create! *eng* :task {:title "water plants"} opts)
        tid (:id row)]
    (testing "a non-idempotent action without a key is 428"
      (let [p (problem-of #(inv/invoke! *eng* :task tid :poke nil opts))]
        (is (= 428 (:status p)))
        (is (= :idempotency-key-required (:waymark10/problem p)))))

    (testing "same key + same body replays byte-identically"
      (let [k "key-1"
            first-run (inv/invoke! *eng* :task tid :poke nil
                                   (assoc opts :idempotency-key k))
            replay (inv/invoke! *eng* :task tid :poke nil
                                (assoc opts :idempotency-key k))]
        (is (= 1 (get-in first-run [:row :data :pokes])))
        (is (= :idempotency (:replayed? replay)))
        (is (string? (get-in replay [:response :response])))
        (let [again (inv/invoke! *eng* :task tid :poke nil
                                 (assoc opts :idempotency-key k))]
          (is (= (get-in replay [:response :response])
                 (get-in again [:response :response]))
              "byte-identical across replays"))))

    (testing "the same key on a different action is reuse, refused"
      (let [p (problem-of #(inv/invoke! *eng* :task tid :close nil
                                        (assoc opts :idempotency-key "key-1")))]
        (is (= 409 (:status p)))
        (is (= :idempotency-key-reuse (:waymark10/problem p)))))))

(deftest the-acknowledge-protocol
  (let [{:keys [row]} (inv/create! *eng* :task {:title "rewire panel" :risky true} opts)
        tid (:id row)]
    (testing "an advisory guard collects into one 409 with instructions"
      (let [p (problem-of #(inv/invoke! *eng* :task tid :close nil opts))]
        (is (= :warning-required (:waymark10/problem p)))
        (is (= "Waymark-Acknowledge" (get-in p [:acknowledge :header])))
        (is (= [:risk-noted] (get-in p [:acknowledge :names])))
        (is (= "This task is flagged risky." (-> p :warnings first :reason)))))
    (testing "acknowledging passes AND lands in the log"
      (let [{:keys [row transition]}
            (inv/invoke! *eng* :task tid :close nil
                         (assoc opts :acknowledged #{:risk-noted}))]
        (is (= :closed (:state row)))
        (is (= [:risk-noted] (mapv keyword (:acknowledged transition))))))))

(deftest row-locks-serialize-concurrent-writes
  (let [{:keys [row]} (create-plan! "2026-09-01" ["2026-09-01" "2026-09-02"])
        pid (:id row)
        assign (fn [date meal]
                 (future (inv/invoke! *eng* :plan pid :assign_meal
                                      {:date date :meal_id meal} opts)))
        a (assign "2026-09-01" "m-a")
        b (assign "2026-09-02" "m-b")]
    (is (map? @a))
    (is (map? @b))
    (let [final (store/with-tx (:storage *eng*)
                  #(store/load-row (:storage *eng*) % :plan pid {}))]
      (is (= 3 (:version final)) "both writes landed, serialized on the row lock")
      (is (= #{"m-a" "m-b"}
             (into #{} (keep :meal_id) (get-in final [:data :days])))))))
