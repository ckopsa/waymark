(ns waymark10.live-cracks-test
  "The live cracks audit (ticket 26e51658): a row past its state's
  expected age, a seat silent with rows in its walk, a ticket shelved
  twice for one reason, a seat refused out on one guard, and a change
  whose ticket is gone. `find-cracks` is pure over a world of rows, so
  the world here is a map and no engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.live-cracks :as live])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-10-13T12:00:00Z"))

(defn- ago [seconds] (.minusSeconds ^Instant now (long seconds)))

(def ^:private task
  {:kind :task
   :states [:draft :open :done]
   :initial :draft
   :terminal #{:done}
   :expected-age {:open 3600}
   :actions {:groom {:from #{:draft} :to :open}
             :finish {:from #{:open} :to :done}}})

(defn- seat [name data]
  {:id name :state :active :data (assoc data :name name)})

(defn- of [cracks word]
  (filterv #(= word (:crack %)) cracks))

(def ^:private finisher
  (seat "finisher" {:scope [{:kind "task" :actions ["finish"]}]
                    :cadence_seconds 3600}))

(deftest an-aged-row-is-reported-once-across-two-passes
  (let [world {:rdefs [task]
               :seats [finisher]
               :rows {:task [{:id "t1" :state :open :updated-at (ago 7200) :data {}}
                             {:id "t2" :state :open :updated-at (ago 60) :data {}}
                             {:id "t3" :state :draft :updated-at (ago 999999) :data {}}
                             {:id "t4" :state :done :updated-at (ago 999999) :data {}}]}
               :now now}
        first-pass (live/unfiled (live/find-cracks world) [])]
    (testing "the first pass names the row past its state's age, and the seat"
      (is (= 1 (count first-pass)))
      (let [[c] first-pass]
        (is (= ["aged" "task" "t1" "open"] ((juxt :crack :kind :id :state) c)))
        (is (= ["finisher"] (:seats c)))
        (is (= 3600 (:expected_seconds c)))))
    (let [ticket (fn [state]
                   {:id "filed" :state state :data (live/ticket-of (first first-pass))})]
      (testing "the ticket names the row, the crack and the seat"
        (let [detail (get-in (ticket :draft) [:data :detail])]
          (is (re-find #"Row: task t1" detail))
          (is (re-find #"Crack: aged" detail))
          (is (re-find #"finisher" detail))))
      (testing "a second pass, with that ticket in draft or open, adds nothing"
        (is (empty? (live/unfiled (live/find-cracks world) [(ticket :draft)])))
        (is (empty? (live/unfiled (live/find-cracks world) [(ticket :open)]))))
      (testing "a ticket that is done stands for nothing, and the crack is filed again"
        (is (= 1 (count (live/unfiled (live/find-cracks world) [(ticket :done)]))))))
    (testing "one crack found twice in a pass is filed one time"
      (let [found (live/find-cracks world)]
        (is (= 1 (count (live/unfiled (concat found found) []))))))))

(deftest a-state-with-no-declared-age-takes-the-default-for-its-name
  (let [plain (dissoc task :expected-age)
        found (live/find-cracks
               {:rdefs [plain]
                :seats [finisher]
                :rows {:task [{:id "t1" :state :open :updated-at (ago 7200) :data {}}
                              {:id "t2" :state :open :updated-at (ago 172800) :data {}}
                              {:id "t3" :state :draft :updated-at (ago 9999999) :data {}}]}
                :now now})]
    (is (= ["t2"] (mapv :id (of found "aged"))))
    (is (nil? (live/expected-age plain :draft)))))

(deftest a-seat-with-a-walk-row-and-a-stale-fire-is-silent
  (let [walker (seat "walker" {:scope [{:kind "task"
                                        :actions ["finish"]
                                        :filter {:state "open"}}]
                               :walk "task"
                               :cadence_seconds 600})
        rows {:task [{:id "t1" :state :open :updated-at (ago 60) :data {}}]}
        stale {:data {:wake_fired_at (str (ago 3600))
                      :last_silent_wake "fire_gap"
                      :last_silent_wake_at (str (ago 1800))}}
        world (fn [rows sched]
                {:rdefs [task] :seats [walker] :rows rows
                 :schedules {"walker" sched} :now now})]
    (testing "it is reported with what its schedule says held the wake"
      (let [[c & more] (of (live/find-cracks (world rows stale)) "silent")]
        (is (some? c))
        (is (empty? more))
        (is (= ["seat" "walker"] ((juxt :kind :id) c)))
        (is (= "fire_gap" (:last_silent_wake c)))
        (is (= (str (ago 1800)) (:last_silent_wake_at c)))
        (is (= 1 (:waiting c)))
        (is (re-find #"fire_gap" (:detail (live/ticket-of c))))))
    (testing "a fire inside the cadence is not silence"
      (is (empty? (of (live/find-cracks
                       (world rows {:data {:wake_fired_at (str (ago 60))}}))
                      "silent"))))
    (testing "a seat with nothing in its walk is not silent"
      (is (empty? (of (live/find-cracks
                       (world {:task [{:id "t1" :state :draft
                                       :updated-at (ago 60) :data {}}]}
                              stale))
                      "silent"))))))

(deftest a-ticket-shelved-twice-for-one-reason-is-a-bounce
  (let [ticket {:kind :ticket
                :states [:draft :open]
                :initial :draft
                :terminal #{}
                :actions {:groom {:from #{:draft} :to :open}
                          :shelve {:from #{:open} :to :draft}}}
        groomer (seat "groomer" {:scope [{:kind "ticket" :actions ["groom"]}]})
        world (fn [reasons]
                {:rdefs [ticket] :seats [groomer]
                 :rows {:ticket [{:id "k1" :state :draft :updated-at (ago 60)
                                  :data {:shelved_because "no repo"}}]}
                 :shelvings {"k1" reasons} :now now})]
    (let [[c] (of (live/find-cracks (world ["other" "no repo" "no repo"])) "bounce")]
      (is (= "k1" (:id c)))
      (is (= "no repo" (:shelved_because c)))
      (is (= ["groomer"] (:seats c))))
    (is (empty? (of (live/find-cracks (world ["no repo"])) "bounce")))
    (is (empty? (of (live/find-cracks (world ["no repo" "other"])) "bounce")))
    (is (empty? (of (live/find-cracks (world [nil nil])) "bounce")))))

(deftest a-seat-refused-out-on-one-guard-is-a-refusal-loop
  (let [builder (seat "builder" {:scope []})
        out (fn [guard] {:outcome "refused_out" :flags [(str "refused_by:" guard)]})
        cut (fn [guard] {:outcome "cut_short" :last_refusal {:guard guard}})
        loops (fn [sittings]
                (of (live/find-cracks {:rdefs [] :seats [builder]
                                       :sittings {"builder" sittings} :now now})
                    "refusal_loop"))]
    (is (= ["task/needs-reopen"]
           (mapv :guard (loops [(out "task/needs-reopen") (cut "task/needs-reopen")
                                (out "task/needs-reopen")]))))
    (is (empty? (loops [(out "a") (out "b") (out "a")])))
    (is (empty? (loops [(out "a") (out "a")])))
    (is (empty? (loops [(out "a") {:outcome "submitted"} (out "a")])))))

(deftest an-aged-row-goes-to-the-seat-that-holds-it-and-to-the-mayor-when-none-does
  (let [change {:kind :change
                :states [:open :submitted :merged]
                :initial :open
                :terminal #{:merged}
                :actions {:submit {:from #{:open} :to :submitted}
                          :stall {:from #{:open} :to :open}}}
        ticket {:kind :ticket
                :states [:draft :open :done]
                :initial :draft
                :terminal #{:done}
                :actions {:groom {:from #{:draft} :to :open}}}
        coder (seat "coder" {:walk "ticket"
                             :scope [{:kind "ticket" :actions ["groom"]}
                                     {:kind "change" :actions ["stall" "submit"]}]})
        world (fn [seats]
                {:rdefs [change ticket]
                 :seats seats
                 :rows {:change [{:id "c1" :state :open :updated-at (ago 100000)
                                  :data {:born_from "ticket:k1"}}]}
                 :tickets {"k1" {:id "k1" :state :open :data {}}}
                 :now now})
        aged (fn [seats] (first (of (live/find-cracks (world seats)) "aged")))]
    (testing "an aged change in open wakes the code seat that holds stall and submit"
      (let [c (aged [coder])]
        (is (= {:seat "coder" :name "coder"} (:wake c)))
        (is (= :wake (first (live/route c nil now))))
        (is (re-find #"\"id\":\s*\"c1\"" (live/wake-text c)))
        (is (re-find #"aged:change:c1" (live/wake-text c)))
        (testing "and waits on that seat for one expected age"
          (is (= :wait (first (live/route c (ago 60) now)))))
        (testing "and then reaches the mayor, saying which seat was tried"
          (let [[door tried] (live/route c (ago 90000) now)]
            (is (= :file door))
            (is (re-find #"Seat tried: coder" (:detail (live/ticket-of tried))))))))
    (testing "an aged row no seat holds still reaches the mayor"
      (let [c (aged [])
            [door filed] (live/route c nil now)]
        (is (nil? (:wake c)))
        (is (= :file door))
        (is (not (re-find #"Seat tried" (:detail (live/ticket-of filed)))))))
    (testing "a seat that holds the door and does not walk the row is not woken"
      (is (nil? (:wake (aged [(seat "closer" {:scope [{:kind "change"
                                                         :actions ["submit"]}]})])))))
    (testing "two seats that hold the row name no one seat"
      (is (nil? (:wake (aged [coder (assoc coder :id "coder2")])))))))

(deftest a-change-whose-ticket-is-dropped-is-an-orphan
  (let [change {:kind :change
                :states [:open :submitted :merged :closed]
                :initial :open
                :terminal #{:merged}
                :actions {:submit {:from #{:open} :to :submitted}
                          :close {:from #{:open :submitted} :to :closed}}}
        closer (seat "closer" {:scope [{:kind "change" :actions ["close"]}]})
        found (live/find-cracks
               {:rdefs [change]
                :seats [closer]
                :rows {:change [{:id "c1" :state :submitted :updated-at (ago 60)
                                 :data {:born_from "ticket:k1"}}
                                {:id "c2" :state :open :updated-at (ago 60)
                                 :data {:born_from "ticket:k2"}}
                                {:id "c3" :state :closed :updated-at (ago 60)
                                 :data {:born_from "ticket:k1"}}
                                {:id "c4" :state :open :updated-at (ago 60)
                                 :data {:born_from "ticket:k3"}}]}
                :tickets {"k1" {:id "k1" :state :dropped}
                          "k2" {:id "k2" :state :open}
                          "k3" {:id "k3" :state :done}}
                :now now})
        orphans (of found "orphan")]
    (is (= ["c1" "c4"] (mapv :id orphans)))
    (let [[c] orphans]
      (is (= ["k1" "dropped"] ((juxt :ticket :ticket_state) c)))
      (is (= ["closer"] (:seats c)))
      (is (re-find #"\[crack orphan:change:c1\]" (:detail (live/ticket-of c)))))))
