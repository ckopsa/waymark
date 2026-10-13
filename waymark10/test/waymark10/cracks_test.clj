(ns waymark10.cracks-test
  "The cracks audit (ticket 8b52a6e6): a state no active seat can
  leave, a state seats can leave and nothing wakes them to, and a
  remedy no seat holds. `audit` is pure over declarations and seat
  rows, so the world here is two maps and no engine."
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

;; ticket 071c563c: a kind says which of its states another kind's door,
;; or a source with no seat, moves a row out of.
(def ^:private ticket
  {:kind :ticket
   :states [:open :in_review :blocked :done]
   :initial :open
   :terminal #{:done}
   :waits [{:on :blocked_by :awaits :ticket :in #{:blocked}
            :outcomes {:done :release}}]
   :left-by [{:in #{:open} :by {:kind :change :action :submit}
              :via :review :to :in_review}
             {:in #{:in_review} :by :mirror :via :land :to :done}
             {:in #{:blocked} :by :wait :via :unblock :to :open}]
   :actions {:block {:from #{:open} :to :blocked}
             :unblock {:from #{:blocked} :to :open}
             :review {:from #{:open} :to :in_review}
             :land {:from #{:in_review} :to :done}}})

(def ^:private change
  {:kind :change
   :states [:open :submitted]
   :initial :open
   :terminal #{:submitted}
   :actions {:submit {:from #{:open} :to :submitted}}})

(defn- ticket-cracks [ticket seats]
  (filterv #(= "ticket" (:kind %)) (cracks/audit [ticket change] seats)))

(deftest a-state-another-kinds-door-leaves-is-no-crack
  (let [scope [{:kind "change" :actions ["submit"]}]
        code (seat "code" {:scope scope :cadence_seconds 3600})]
    (testing "the code seat holds change.submit and no ticket door"
      (let [cracks (ticket-cracks ticket [code])]
        (is (empty? (of cracks "no_exit" "open")))
        (is (empty? (of cracks "unwoken" "open")))))
    (testing "with nothing to wake it, it is unwoken as a direct holder is"
      (let [[c] (of (ticket-cracks ticket [(seat "code" {:scope scope})])
                    "unwoken" "open")]
        (is (= [{:seat "code" :holds ["change/submit"] :wakes []}]
               (:seats_considered c)))))
    (testing "a wake_on entry on the ticket wakes it, whatever its filter"
      (let [woken (seat "code" {:scope scope
                                :wake_on [{:kind "ticket" :actions ["create"]
                                           :filter {:repo "a"}}]})]
        (is (empty? (of (ticket-cracks ticket [woken]) "unwoken" "open")))))
    (testing "no seat holds either door, and the crack names both"
      (let [[c] (of (ticket-cracks ticket []) "no_exit" "open")]
        (is (re-find #"review" (:what_would_close_it c)))
        (is (re-find #"change/submit" (:what_would_close_it c)))))
    (testing "removing the declaration brings the crack back"
      (is (seq (of (ticket-cracks (dissoc ticket :left-by) [code])
                   "no_exit" "open"))))))

(deftest a-state-a-source-leaves-needs-no-seat
  (testing "the mirror's land leaves in_review, and a wait's ending blocked"
    (is (= #{"open"}
           (into #{} (map :state) (ticket-cracks ticket [])))))
  (testing "removing the declaration brings both back"
    (is (= #{"open" "in_review" "blocked"}
           (into #{} (comp (filter #(= "no_exit" (:crack %))) (map :state))
                 (ticket-cracks (dissoc ticket :left-by) []))))))
