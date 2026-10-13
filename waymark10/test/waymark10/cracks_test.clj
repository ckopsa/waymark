(ns waymark10.cracks-test
  "The cracks audit (ticket 8b52a6e6): a state no active seat can
  leave, a state seats can leave and nothing wakes them to, and a
  remedy no seat holds. Only a wake_on entry wakes (ticket cd402fb6):
  a state reached by cadence alone, or left by a person alone, is its
  own crack. `audit` is pure over declarations and seat rows, so the
  world here is two maps and no engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.cracks :as cracks]))

(def ^:private task
  {:kind :task
   :states [:draft :open :done]
   :initial :draft
   :terminal #{:done}
   :actions {:groom {:from #{:draft} :to :open}
             :finish {:from #{:open} :to :done
                      :guards [{:name :task/needs-reopen
                                :explain "It was shelved."
                                :remedies [:task/reopen]}]}
             :reopen {:from #{:done} :to :open}
             :note {:from #{:open} :to :open}}})

(defn- seat [name data]
  {:id name :state :active :data (assoc data :name name)})

(defn- of [cracks crack state]
  (filterv #(and (= crack (:crack %)) (= state (:state %))) cracks))

(deftest a-state-no-seat-leaves-is-no-exit
  (let [groomer (seat "groomer" {:scope [{:kind "task" :actions ["groom"]}]
                                 :cadence_seconds 3600})
        cracks (cracks/audit [task] [groomer])]
    (testing "open has finish, and no seat holds it"
      (let [[c & more] (of cracks "no_exit" "open")]
        (is (nil? more))
        (is (= "task" (:kind c)))
        (is (= [{:seat "groomer" :holds []}] (:seats_considered c)))
        (is (re-find #"finish" (:what_would_close_it c)))
        (is (not (re-find #"note" (:what_would_close_it c)))
            "an action that lands where it left is not a way out")))
    (testing "draft is left by the groomer, and done is terminal"
      (is (empty? (of cracks "no_exit" "draft")))
      (is (empty? (filter #(= "done" (:state %)) cracks))))
    (testing "a scope filter that shuts the state out holds nothing there"
      (let [shut (seat "shut" {:scope [{:kind "task" :actions ["finish"]
                                        :filter {:state "draft"}}]})]
        (is (seq (of (cracks/audit [task] [groomer shut]) "no_exit" "open")))))
    (testing "with no seats every non-terminal state is a crack"
      (is (= #{"draft" "open"}
             (into #{} (comp (filter #(= "no_exit" (:crack %))) (map :state))
                   (cracks/audit [task] [])))))))

(deftest a-seat-nothing-wakes-is-unwoken
  (let [scope [{:kind "task" :actions ["groom" "finish" "reopen"]}]
        idle (seat "builder" {:scope scope})
        cracks (cracks/audit [task] [idle])]
    (testing "it can leave open, and has no wake_on, walk or cadence"
      (let [[c] (of cracks "unwoken" "open")]
        (is (some? c))
        (is (= [{:seat "builder" :holds ["finish"] :wakes []}]
               (:seats_considered c)))
        (is (re-find #"groom" (:what_would_close_it c))))
      (is (empty? (of cracks "no_exit" "open"))))
    (testing "a wake_on entry on a transition into the state clears it"
      (let [woken (seat "builder" {:scope scope
                                   :wake_on [{:kind "task" :actions ["groom"]}]})]
        (is (empty? (of (cracks/audit [task] [woken]) "unwoken" "open")))))
    (testing "a wake_on entry on a transition that lands elsewhere does not"
      (let [elsewhere (seat "builder" {:scope scope
                                       :wake_on [{:kind "task" :actions ["finish"]}]})]
        (is (seq (of (cracks/audit [task] [elsewhere]) "unwoken" "open")))))
    (testing "a wake_on filter narrower than the scope filter does not"
      (let [narrow (seat "builder" {:scope scope
                                    :wake_on [{:kind "task" :actions ["groom"]
                                               :filter {:repo "a"}}]})]
        (is (seq (of (cracks/audit [task] [narrow]) "unwoken" "open")))))
    (testing "create wakes a seat to the initial state"
      (let [born (seat "builder" {:scope scope
                                  :wake_on [{:kind "task" :actions ["create"]}]})
            cracks (cracks/audit [task] [born])]
        (is (empty? (of cracks "unwoken" "draft")))
        (is (seq (of cracks "unwoken" "open")))))
    (testing "a walk alone does not clear it: the wake_on it defaults to does"
      (let [walked [{:kind "task"
                     :actions ["groom" "finish"]
                     :filter {:state "open"}}]
            walker (seat "builder" {:scope walked :walk "task"})
            deaf (seat "builder" {:scope walked :walk "task"
                                  :wake_on [{:kind "task" :actions ["finish"]}]})]
        (is (empty? (of (cracks/audit [task] [walker]) "unwoken" "open"))
            "a wake_on nobody wrote is every action of the walk, under its filter")
        (is (= [{:seat "builder" :holds ["finish"] :wakes []}]
               (:seats_considered
                (first (of (cracks/audit [task] [deaf]) "unwoken" "open"))))
            "a wake_on that lands elsewhere, and the walk says nothing")))
    (testing "a cadence is not a wake: the state is cadence_only, not unwoken"
      (let [timed (seat "builder" {:scope scope :cadence_seconds 3600})
            cracks (cracks/audit [task] [timed])]
        (is (empty? (filter #(= "unwoken" (:crack %)) cracks)))
        (is (seq (of cracks "cadence_only" "open")))))))

(deftest what-no-wake-on-covers-is-graded
  (let [scope [{:kind "task" :actions ["groom" "finish" "reopen"]}]
        daily (seat "daily" {:scope scope :cadence_seconds 86400})
        hourly (seat "hourly" {:scope scope :cadence_seconds 3600})
        mayor (seat "mayor" {:scope scope :mode "interactive"})
        idle (seat "idle" {:scope scope})
        woken (seat "woken" {:scope scope
                             :wake_on [{:kind "task" :actions ["groom"]}]})
        at-open (fn [seats]
                  (into []
                        (comp (filter #(= "open" (:state %)))
                              (remove #(= "remedy_unheld" (:crack %)))
                              (map :crack))
                        (cracks/audit [task] seats)))]
    (testing "reached by cadence alone: cadence_only, with the shortest"
      (let [[c & more] (of (cracks/audit [task] [daily hourly mayor idle])
                           "cadence_only" "open")]
        (is (nil? more))
        (is (= 3600 (:shortest_cadence_seconds c)))
        (is (= [["daily" ["cadence"]] ["hourly" ["cadence"]]
                ["mayor" ["interactive"]] ["idle" []]]
               (map (juxt :seat :wakes) (:seats_considered c))))
        (is (re-find #"3600 seconds" (:what_would_close_it c)))
        (is (re-find #"groom" (:what_would_close_it c))))
      (is (= ["cadence_only"] (at-open [daily hourly mayor idle]))))
    (testing "left by a person alone: person_only"
      (let [[c] (of (cracks/audit [task] [mayor idle]) "person_only" "open")]
        (is (some? c))
        (is (re-find #"finish" (:what_would_close_it c))))
      (is (= ["person_only"] (at-open [mayor idle])))
      (is (= ["person_only"] (at-open [mayor]))))
    (testing "neither a wake_on, a cadence nor a person: unwoken"
      (is (= ["unwoken"] (at-open [idle]))))
    (testing "one leaver with a wake_on makes the state clean"
      (is (= [] (at-open [daily mayor idle woken]))))))

(deftest a-remedy-no-seat-holds-is-remedy-unheld
  (let [finisher (seat "finisher" {:scope [{:kind "task" :actions ["groom" "finish"]}]
                                   :cadence_seconds 3600})
        cracks (cracks/audit [task] [finisher])]
    (testing "finish refuses naming task/reopen, and no seat holds reopen"
      (let [[c & more] (of cracks "remedy_unheld" "open")]
        (is (nil? more))
        (is (= "finish" (:door c)))
        (is (= "task/reopen" (:remedy c)))
        (is (= [{:seat "finisher" :holds []}] (:seats_considered c)))))
    (testing "a seat that holds the remedy clears it"
      (let [reopener (seat "reopener" {:scope [{:kind "task" :actions ["reopen"]}]})]
        (is (empty? (filter #(= "remedy_unheld" (:crack %))
                            (cracks/audit [task] [finisher reopener]))))))))
