(ns waymark10.seat-reads-as-test
  "A seat's page leads with the seat in words (ticket 9935e3a3): the
  charter's first sentence, what wakes it, what it may do, phrased as
  the org's law page phrases them, and its queues with their counts."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.seats :as seats]))

(def prospect
  {:kind :prospect
   :plural "prospects"
   :states [:new :qualified :engaged :declined]
   :create-action-names #{:create}
   :actions {:create {:from [] :to :new}
             :qualify {:from [:new] :to :qualified}
             :engage {:from [:qualified] :to :engaged}
             :decline {:from [:new :qualified] :to :declined}}})

(def rows-by-state
  {"new" [{:id "p1"}]
   "qualified" [{:id "p2"}]
   "engaged" [{:id "p3"}]})

(defn- rdef-of
  "The render's registry consult: a kind's rdef by its name."
  [token]
  (get {:prospect prospect} (keyword (name token))))

(defn- find' [kind where _]
  (when (= :prospect kind) (get rows-by-state (:state where) [])))

(def seat
  {:id "s1"
   :data {:name "dress_rehearsal-mayor"
          :charter "You judge each prospect against the studio's fit.\nThen you hand it on."
          :wake_on [{:kind "prospect" :actions ["create" "qualify"]}]
          :scope [{:kind "prospect" :actions ["qualify" "engage" "decline"]}
                  {:kind "task" :actions []}]}})

(deftest a-seat-reads-as-a-sentence
  (let [r (seats/reads-as rdef-of find' seat)]
    (testing "what it is for: the charter's first sentence"
      (is (= "You judge each prospect against the studio's fit." (:charter r))))
    (testing "what wakes it, in the law page's words"
      (is (= "Wakes when: a prospect is written or a new prospect becomes qualified."
             (:wakes r))))
    (testing "what it may do, in the law page's words"
      (is (= "May: qualify, engage or decline a prospect; read task." (:may r))))
    (testing "its queue: a link, with the rows waiting in a state it can move"
      (is (= [{:kind "prospect" :href "/api/prospects" :waiting 2
               :label "Prospects (2 waiting)"}]
             (:works r))))))

(deftest a-seat-with-no-find-names-no-queue
  (let [r (seats/reads-as rdef-of nil seat)]
    (is (= [] (:works r)))
    (is (string? (:may r)))))

(deftest a-kind-the-registry-does-not-answer-is-named-with-its-door
  (is (= "Wakes when: a widget goes through polish."
         (:wakes (seats/reads-as rdef-of find'
                                 {:data {:wake_on [{:kind "widget" :actions ["polish"]}]}})))))
