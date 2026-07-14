(ns waymark10.batch-f-memory-test
  "Batch F, deliverable 7: the in-memory Storage twin. The invoke-test
  scenario suite — create with derived materialization, guards and
  acceptance sets, natural replay, idempotency discipline, the
  acknowledge protocol, serialized concurrent writes — plus the
  collection surface (filters, vocab membership, sort, paging,
  facets) and the atomic-rollback semantics, all against an engine
  whose storage is waymark10.server.store.memory. No database: parity
  with the Postgres meanings is the whole assertion."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

;; ── the world (invoke-test's, verbatim) ─────────────────────────────

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
    :filterable {:title #{:eq :ne :contains}
                 :pokes #{:range :set}}
    :actions
    {:poke {:from #{:open} :to :open
            :safety {:idempotent false :reversible true :confirm false}
            :handler poke-handler}
     :close {:from #{:open} :to :closed
             :guards [risk-noted]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed task is history."}}}}))

(def ^:private ready-gate
  (g/expr {:name :ready
           :when '(= (data :ready) true)
           :explain "This chore is not ready."}))

(def chore
  (r/resource
   {:kind :chore
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:max 80}]]
             [:ready {:optional true} [:maybe :boolean]]]
    :actions
    {:sweep {:from #{:open} :to :done
             :guards [ready-gate]
             :bulk {:atomic true :max-items 10}
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Done is done."}}}}))

(def ^:dynamic *eng* nil)

(use-fixtures :each
  (fn [f]
    (binding [*eng* (inv/engine {:storage (memory/storage)
                                 :resources [fx/meal fx/plan task chore]})]
      (f))))

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

;; ── the family week: create, guards, replay, dry-run ────────────────

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

;; ── idempotency discipline ──────────────────────────────────────────

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

;; ── the acknowledge protocol ────────────────────────────────────────

(deftest the-acknowledge-protocol
  (let [{:keys [row]} (inv/create! *eng* :task {:title "rewire panel" :risky true} opts)
        tid (:id row)]
    (testing "an advisory guard collects into one 409 with instructions"
      (let [p (problem-of #(inv/invoke! *eng* :task tid :close nil opts))]
        (is (= :warning-required (:waymark10/problem p)))
        (is (= [:risk-noted] (get-in p [:acknowledge :names])))
        (is (= "This task is flagged risky." (-> p :warnings first :reason)))))
    (testing "acknowledging passes AND lands in the log (string tokens,
              the JSONB round-trip's shape)"
      (let [{:keys [row transition]}
            (inv/invoke! *eng* :task tid :close nil
                         (assoc opts :acknowledged #{:risk-noted}))]
        (is (= :closed (:state row)))
        (is (= [:risk-noted] (mapv keyword (:acknowledged transition))))))))

;; ── concurrent writes serialize ─────────────────────────────────────

(deftest concurrent-writes-serialize
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
      (is (= 3 (:version final)) "both writes landed, serialized")
      (is (= #{"m-a" "m-b"}
             (into #{} (keep :meal_id) (get-in final [:data :days])))))))

;; ── atomic rollback: the snapshot IS the transaction ────────────────

(deftest atomic-bulk-rolls-back
  (let [ids (mapv (fn [i]
                    (:id (:row (inv/create! *eng* :chore
                                            {:title (str "c" i)
                                             :ready (not= i 1)}
                                            opts))))
                  (range 3))]
    (testing "one refusing item aborts the whole atomic bulk"
      (let [p (problem-of #(inv/bulk! *eng* :chore :sweep {:ids ids}
                                      (assoc opts :idempotency-key "bulk-1")))]
        (is (= 409 (:status p)))))
    (testing "NOTHING committed — the twin rolled the transaction back"
      (doseq [id ids]
        (let [row (store/with-tx (:storage *eng*)
                    #(store/load-row (:storage *eng*) % :chore id {}))]
          (is (= :open (:state row)))
          (is (= 1 (:version row)))))
      (let [ts (store/with-tx (:storage *eng*)
                 #(store/transitions (:storage *eng*) %
                                     {:kind :chore} {}))]
        (is (not-any? #(= :sweep (:action %)) ts)
            "no sweep transition survived the rollback")))))

;; ── the collection surface ──────────────────────────────────────────

(defn- meal! [nm themes & [accept?]]
  (let [{:keys [row]} (inv/create! *eng* :meal {:name nm :themes themes} opts)]
    (when accept?
      (inv/invoke! *eng* :meal (:id row) :accept nil opts))
    (:id row)))

(defn- envelope [kind params]
  (let [rdef (get (inv/resources *eng*) kind)]
    (collections/envelope *eng* rdef params
                          {:principal colton
                           :now (java.time.Instant/now)
                           :resources (inv/resources *eng*)})))

(deftest collections-over-the-twin
  (meal! "tacos" ["mexican"] true)
  (meal! "fajitas" ["mexican" "american"] true)
  (meal! "burgers" ["american"] true)
  (meal! "pho" ["asian"])
  (testing "the unfiltered page: total, items, the declared name sort"
    (let [env (envelope :meal {})]
      (is (= 4 (get-in env ["data" "total"])))
      (is (= ["burgers · On list" "fajitas · On list"
              "pho · Suggested" "tacos · On list"]
             (mapv #(get % "summary") (get-in env ["data" "items"]))))))
  (testing "state filtering (the :in grammar over the state column)"
    (let [env (envelope :meal {"state" "on_list"})]
      (is (= 3 (get-in env ["data" "total"])))))
  (testing "vocab membership is :in-any — any-of, not equality"
    (let [env (envelope :meal {"themes" "mexican"})]
      (is (= 2 (get-in env ["data" "total"]))))
    (let [env (envelope :meal {"themes" "mexican,asian"})]
      (is (= 3 (get-in env ["data" "total"])))))
  (testing "facet counts unroll the array — one count per member"
    (let [env (envelope :meal {})
          facets (get-in env ["actions" "query" "input" "properties"
                              "themes" "x-facets"])]
      (is (= {"american" 2 "asian" 1 "mexican" 2} facets))))
  (testing "paging never overlaps (the id tiebreak)"
    (let [p1 (envelope :meal {"page[size]" "2" "page[number]" "1"})
          p2 (envelope :meal {"page[size]" "2" "page[number]" "2"})
          selves #(set (map (fn [i] (get i "self")) (get-in % ["data" "items"])))]
      (is (= 2 (count (selves p1))))
      (is (= 2 (count (selves p2))))
      (is (empty? (clojure.set/intersection (selves p1) (selves p2))))))
  (testing "descending sort over the promoted ordering"
    (let [env (envelope :meal {"sort" "-name"})]
      (is (= "tacos · On list"
             (get (first (get-in env ["data" "items"])) "summary"))
          "tacos is last ascending, so first descending")))
  (testing "range filters cast (dates compare as dates, not strings)"
    (create-plan! "2026-07-14" ["2026-07-14"])
    (create-plan! "2026-08-04" ["2026-08-04"])
    (let [env (envelope :plan {"start_date_gte" "2026-08-01"})]
      (is (= 1 (get-in env ["data" "total"]))))))

(deftest new-cond-ops-over-the-twin
  (inv/create! *eng* :task {:title "alpha"} opts)
  (let [{:keys [row]} (inv/create! *eng* :task {:title "Beta ray"} opts)]
    (inv/invoke! *eng* :task (:id row) :poke nil
                 (assoc opts :idempotency-key "poke-beta")))
  (inv/create! *eng* :task {:title "gamma_ray"} opts)
  (testing "ne excludes; comma list negates as not-in"
    (is (= 2 (get-in (envelope :task {"title_ne" "alpha"})
                     ["data" "total"])))
    (is (= 1 (get-in (envelope :task {"title_ne" "alpha,gamma_ray"})
                     ["data" "total"]))))
  (testing "contains is case-insensitive and wildcard-literal"
    (is (= 2 (get-in (envelope :task {"title_contains" "RAY"})
                     ["data" "total"])))
    (is (= 1 (get-in (envelope :task {"title_contains" "a_r"})
                     ["data" "total"]))
        "an unescaped _ would also match \"Beta ray\"'s space"))
  (testing "set answers presence both ways — nil pokes answer false"
    (is (= 1 (get-in (envelope :task {"pokes_set" "true"})
                     ["data" "total"])))
    (is (= 2 (get-in (envelope :task {"pokes_set" "false"})
                     ["data" "total"]))))
  (testing "the bare < cond (the before op's storage meaning)"
    (let [st (:storage *eng*)
          rows (store/with-tx st
                 #(store/search-rows st % :task
                                     [{:target :data :field :pokes
                                       :cast "bigint" :op :< :value "5"}]
                                     {}))]
      (is (= 1 (count rows))
          "only the poked task carries pokes — NULL fails < too"))))

;; ── the log and the maintenance write hold their meanings ───────────

(deftest log-and-maintenance-semantics
  (let [{:keys [row transition]} (inv/create! *eng* :task {:title "one"} opts)]
    (testing "transitions carry assigned ids and the JSONB actor shape"
      (is (pos? (:id transition)))
      (is (= "colton" (get-in transition [:actor :id])))
      (is (= "human" (get-in transition [:actor :type]))))
    (testing "update-data! is maintenance: document moves, version does not"
      (store/with-tx (:storage *eng*)
        (fn [tx]
          (store/update-data! (:storage *eng*) tx :task (:id row)
                              {:title "one" :pokes 9} nil)))
      (let [after (store/with-tx (:storage *eng*)
                    #(store/load-row (:storage *eng*) % :task (:id row) {}))]
        (is (= 9 (get-in after [:data :pokes])))
        (is (= 1 (:version after)))))))
