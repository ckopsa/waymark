(ns waymark10.cracks-test
  "The cracks audit (ticket 8b52a6e6): a state no active seat can
  leave, a state seats can leave and nothing wakes them to, and a
  remedy no seat holds; then (ticket 53e55720) a state only a person
  can leave with nothing telling them, and a row value no seat's walk
  admits. `audit` is pure over declarations, seat rows and the rows it
  is handed, so the world here is maps and no engine."
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
    (testing "a walk whose filter admits the state clears it"
      (let [walker (seat "builder" {:scope [{:kind "task"
                                             :actions ["groom" "finish"]
                                             :filter {:state "open"}}]
                                    :walk "task"})
            cracks (cracks/audit [task] [walker])]
        (is (empty? (of cracks "unwoken" "open")))))
    (testing "a cadence clears it"
      (let [timed (seat "builder" {:scope scope :cadence_seconds 3600})]
        (is (empty? (filter #(= "unwoken" (:crack %))
                            (cracks/audit [task] [timed]))))))))

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

(def ^:private order
  {:kind :order
   :states [:cart :placed :void]
   :initial :cart
   :terminal #{:void}
   :actions {:place {:from #{:cart} :to :placed}
             :void {:from #{:placed} :to :void :safety {:confirm true}}}})

(deftest an-exit-only-a-person-takes-with-nothing-telling-them
  (let [clerk (seat "clerk" {:scope [{:kind "order" :actions ["place" "void"]}]
                             :cadence_seconds 3600})
        silent (fn [seats world]
                 (of (cracks/audit [order] seats world) "silent_person_exit" "placed"))]
    (testing "placed has one exit, a confirm door, and no cue"
      (let [cracks (cracks/audit [order] [clerk])
            [c & more] (of cracks "silent_person_exit" "placed")]
        (is (nil? more))
        (is (= "order" (:kind c)))
        (is (= [{:action "void" :held_by ["confirm"]}] (:exits c)))
        (is (re-find #"place" (:what_would_close_it c)))
        (is (empty? (of cracks "silent_person_exit" "cart"))
            "cart is left by a door a seat takes alone")))
    (testing "a guard declared :hold true holds the exit the same way"
      (let [held (assoc-in order [:actions :void]
                           {:from #{:placed} :to :void
                            :guards [{:name :order/over-ceiling
                                      :explain "It is past the ceiling."
                                      :hold true}]})
            [c] (of (cracks/audit [held] [clerk]) "silent_person_exit" "placed")]
        (is (= [{:action "void" :held_by ["hold"]}] (:exits c)))))
    (testing "one exit a seat takes alone clears it"
      (let [open (assoc-in order [:actions :ship] {:from #{:placed} :to :void})]
        (is (empty? (of (cracks/audit [open] [clerk]) "silent_person_exit" "placed")))))
    (testing "a notice rule on the arrival clears it"
      (is (empty? (silent [clerk] {:notice-rules [{:data {:kind "order"
                                                          :when {:to_state "placed"}}}]})))
      (is (empty? (silent [clerk] {:notice-rules [{:data {:kind "order"
                                                          :when {:action "place"}}}]}))))
    (testing "a rule on another state, another kind, or a time does not"
      (is (seq (silent [clerk] {:notice-rules [{:data {:kind "order"
                                                       :when {:to_state "void"}}}
                                               {:data {:kind "task"
                                                       :when {:to_state "placed"}}}
                                               {:data {:kind "order"
                                                       :at {:field "due"}}}]}))))
    (testing "a subscription that names the kind, or none, clears it"
      (is (empty? (silent [clerk] {:subscriptions [{:data {:kinds ["order"]}}]})))
      (is (empty? (silent [clerk] {:subscriptions [{:data {}}]})))
      (is (seq (silent [clerk] {:subscriptions [{:data {:kinds ["task"]}}]}))))
    (testing "a cue in the inbox of a seat a person sits in clears it"
      (let [inbox {:cues [{:kind "order" :actions ["place"] :note "An order came."}]}
            desk (seat "desk" {:mode "interactive" :inbox inbox})
            fired (seat "fired" {:inbox inbox})]
        (is (empty? (silent [clerk desk] nil)))
        (is (seq (silent [clerk fired] nil))
            "a fired seat's inbox tells a model, not the person")))))

(def ^:private ticket
  {:kind :ticket
   :states [:draft :open :done]
   :initial :draft
   :terminal #{:done}
   :actions {:groom {:from #{:draft} :to :open}
             :complete {:from #{:open} :to :done}}})

(deftest a-value-no-walk-admits-is-unwalked
  (let [walker (fn [name fm]
                 (seat name {:scope [{:kind "ticket" :actions ["complete"] :filter fm}]
                             :walk "ticket"}))
        builder (walker "builder" {:repo "ckopsa/waymark" :state "open"})
        rows {:ticket [{:id "1" :state :open :data {:repo "ckopsa/waymark"}}
                       {:id "2" :state :open :data {:repo "ckopsa/waymark-bench"}}
                       {:id "3" :state :done :data {:repo "ckopsa/gone"}}]}
        unwalked (fn [seats]
                   (filterv #(= "unwalked_value" (:crack %))
                            (cracks/audit [ticket] seats {:rows rows})))]
    (testing "an open ticket in a repo no seat's filter admits"
      (let [[c & more] (unwalked [builder])]
        (is (nil? more) "the admitted repo and the terminal row are no crack")
        (is (= "ticket" (:kind c)))
        (is (= "repo" (:field c)))
        (is (= "ckopsa/waymark-bench" (:value c)))
        (is (= 1 (:rows c)))
        (is (= ["open"] (:states c)))
        (is (= [{:seat "builder" :admits ["ckopsa/waymark"]}] (:seats_considered c)))
        (is (re-find #"ckopsa/waymark-bench" (:what_would_close_it c)))))
    (testing "a second seat whose walk admits the repo clears it"
      (is (empty? (unwalked [builder (walker "bench" {:repo "ckopsa/waymark-bench"})]))))
    (testing "a walk that does not filter on the field admits every value"
      (is (empty? (unwalked [builder (walker "any" {:state "open"})]))))
    (testing "with no rows read there is nothing to report"
      (is (empty? (filter #(= "unwalked_value" (:crack %))
                          (cracks/audit [ticket] [builder])))))))
