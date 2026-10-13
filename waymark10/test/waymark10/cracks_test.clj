(ns waymark10.cracks-test
  "The cracks audit (ticket 8b52a6e6): a state no active seat can
  leave, a state seats can leave and nothing wakes them to, and a
  remedy no seat holds. Only a wake_on entry wakes (ticket cd402fb6):
  a state reached by cadence alone, or left by a person alone, is its
  own crack. Then (ticket 53e55720) a state only a person can leave
  with nothing telling them, and a row value no seat's walk admits.
  `audit` is pure over declarations, seat rows and the rows it is
  handed, so the world here is maps and no engine."
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

;; ticket d9edac4e: a kind says what its rows wait on. `job` waits the
;; two ways a ticket does: a blocker's ending releases it whichever way
;; it ends, and a merge_after job that is dropped does nothing.
(def ^:private job
  {:kind :job
   :states [:open :blocked :in_review :done :dropped]
   :initial :open
   :terminal #{:done :dropped}
   :waits [{:on :blocked_by :awaits :job :in #{:blocked}
            :outcomes {:done :release :dropped :release}}
           {:on :merge_after :awaits :job :in #{:in_review}
            :restated-by [:merge_after_in_review]
            :outcomes {:done :release :dropped :nothing}}]
   :actions {:block {:from #{:open} :to :blocked}
             :unblock {:from #{:blocked} :to :open}
             :submit {:from #{:open} :to :in_review}
             :merge_after_in_review {:from #{:in_review} :to :in_review}
             :land {:from #{:in_review} :to :done}
             :drop {:from #{:open :blocked :in_review} :to :dropped}}})

(defn- dead-waits [seats]
  (filterv #(= "dead_wait" (:crack %)) (cracks/audit [job] seats)))

(deftest an-outcome-that-does-nothing-with-no-seat-woken-is-a-dead-wait
  (testing "merge_after on a dropped job is a dead wait"
    (let [[c & more] (dead-waits [])]
      (is (nil? more) "done releases, and so it is no crack")
      (is (= {:kind "job" :state "in_review" :on "merge_after"
              :awaits "job" :outcome "dropped" :seats_considered []}
             (select-keys c [:kind :state :on :awaits :outcome :seats_considered])))
      (is (re-find #"merge_after_in_review" (:what_would_close_it c)))
      (is (re-find #"naming one of: drop" (:what_would_close_it c)))))
  (testing "blocked_by on a dropped job is none: the drop releases it"
    (is (empty? (filter #(= "blocked_by" (:on %)) (dead-waits [])))))
  (let [scope [{:kind "job" :actions ["merge_after_in_review"]}]]
    (testing "a seat that holds the door and is not woken leaves it dead"
      (let [[c] (dead-waits [(seat "mayor" {:scope scope})])]
        (is (= [{:seat "mayor" :holds ["merge_after_in_review"] :wakes []}]
               (:seats_considered c)))))
    (testing "a wake on the drop, or a cadence, brings it to act"
      (is (empty? (dead-waits [(seat "mayor" {:scope scope
                                              :wake_on [{:kind "job" :actions ["drop"]}]})])))
      (is (empty? (dead-waits [(seat "mayor" {:scope scope :cadence_seconds 3600})]))))
    (testing "a wake on another ending does not"
      (is (seq (dead-waits [(seat "mayor" {:scope scope
                                           :wake_on [{:kind "job" :actions ["land"]}]})]))))
    (testing "a seat woken by the drop that holds no door on the waiter does not"
      (is (seq (dead-waits [(seat "watcher" {:scope [{:kind "job" :actions ["unblock"]}]
                                             :wake_on [{:kind "job" :actions ["drop"]}]})]))))))

(deftest a-wait-of-no-row-is-cleared-by-the-wake-alone
  (let [hold {:kind :hold
              :states [:held :done :refused]
              :initial :held
              :terminal #{:done :refused}
              :waits [{:on :hold :awaits :hold :waiter "the caller"
                       :outcomes {:done :release :refused :nothing}}]
              :actions {:allow {:from #{:held} :to :done}
                        :refuse {:from #{:held} :to :refused}}}
        dead (fn [seats]
               (filterv #(= "dead_wait" (:crack %)) (cracks/audit [hold] seats)))]
    (testing "the caller holds no door, so the crack names no state"
      (let [[c & more] (dead (vector (seat "timed" {:cadence_seconds 60})))]
        (is (nil? more))
        (is (nil? (:state c)))
        (is (= "refused" (:outcome c)))))
    (testing "a seat woken by the refusal clears it"
      (is (empty? (dead [(seat "caller" {:wake_on [{:kind "hold" :actions ["refuse"]}]})]))))))
