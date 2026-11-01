(ns waymark10.replay-walks-test
  "Walk and holder replay (docs/spec-amendment.md §14 step 4): a walk
  narrowed to nothing drops every row, and a row whose only walker
  leaves moves to the person."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.replay-walks :as replay-walks])
  (:import (java.time Instant)))

(def ^:private job
  {:kind :job
   :states [:draft :open :done]
   :initial :draft
   :terminal #{:done}
   :actions {:groom {:from #{:draft} :to :open}
             :finish {:from #{:open} :to :done}}})

(defn- seat [name data]
  {:id name :state :active :data (assoc data :name name)})

(def ^:private mayor
  (seat "mayor" {:mode "interactive"
                 :scope [{:kind "job" :actions ["groom" "finish"]}]}))

(defn- coder [walk flt]
  (seat "coder" {:walk walk
                 :cadence_seconds 3600
                 :scope [(cond-> {:kind "job" :actions ["finish"]}
                           flt (assoc :filter flt))]}))

(def ^:private t1 (Instant/parse "2026-10-20T00:00:00Z"))
(def ^:private t2 (Instant/parse "2026-10-25T00:00:00Z"))

(defn- groomed [id at repo]
  [["job" id] {:kind "job" :id id :opening "draft"
               :timeline [{:at at :action "groom" :from "draft" :to "open"
                           :data {:repo repo}}]}])

(def ^:private history
  {:rows (into {} [(groomed "j1" t1 "r1") (groomed "j2" t2 "r2")])
   :blind []})

(defn- replay [before after]
  (replay-walks/replay {:rdef job :by-kind {:job job} :history history
                        :seats [mayor] :before before :after after}))

(deftest a-walk-narrowed-to-nothing-drops-every-row
  (let [r (replay (coder "job" nil) (coder "job" {:repo "nowhere"}))]
    (is (= 2 (:moments r)))
    (is (= [] (:adds r)))
    (is (= [{:id "j1" :at t1 :state "open" :moments 1}
            {:id "j2" :at t2 :state "open" :moments 1}]
           (:drops r)))
    (testing "the same walk on both sides changes nothing"
      (let [same (replay (coder "job" nil) (coder "job" nil))]
        (is (= [] (:adds same) (:drops same) (:holders same)))))
    (testing "widening it back adds every row"
      (is (= ["j1" "j2"]
             (mapv :id (:adds (replay (coder "job" {:repo "nowhere"})
                                      (coder "job" nil)))))))))

(deftest a-row-whose-only-walker-leaves-moves-to-the-person
  (testing "the walk moves to another kind"
    (let [r (replay (coder "job" {:repo "r1"}) (coder "task" {:repo "r1"}))
          j1 (some #(when (= "j1" (:id %)) %) (:holders r))]
      (is (= ["j1"] (mapv :id (:drops r))))
      (is (= {:type "seat" :name "coder"} (:before j1)))
      (is (= {:type "person" :name "mayor"} (:after j1)))
      (is (= ["j1"] (:to_person r)))
      (is (= [] (:to_nobody r)))
      (testing "a row the seat never walked keeps its holder"
        (is (not-any? #(= "j2" (:id %)) (:holders r))))))
  (testing "the seat leaves"
    (let [r (replay (coder "job" nil) nil)]
      (is (= ["j1" "j2"] (:to_person r)))
      (is (= ["j1" "j2"] (mapv :id (:drops r)))))))
