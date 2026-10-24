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

(def ^:private fire-door
  "The door subscription on the seat's fire door that wakes it (E-5.4)."
  {:data {:kinds ["prospect"] :actions ["create" "qualify"]
          :receiver {:type "door" :kind "seat" :id "s1" :action "fire"}}})

(defn- find' [kind where _]
  (case kind
    :prospect (get rows-by-state (:state where) [])
    :subscription [fire-door]
    nil))

(def seat
  {:id "s1"
   :data {:name "dress_rehearsal-mayor"
          :charter "You judge each prospect against the studio's fit.\nThen you hand it on."
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

(def ^:private chrome
  "What the film's chrome-leaks counts on a page: an /api path, a uuid
  or a raw key (snake_case)."
  #"/api/|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}|\b[a-z0-9]+_[a-z0-9_]+\b")

(deftest a-seat-page-shows-no-chrome
  (let [find' (fn [kind where opts]
                (if (= :sitting kind)
                  [{:id "0b9c1d2e-3f40-4a5b-8c6d-7e8f9a0b1c2d"}]
                  (find' kind where opts)))
        r (seats/reads-as rdef-of find'
                          (assoc-in seat [:data :scope 1]
                                    {:kind "sitting_call" :actions ["mark_read"]}))]
    (doseq [line (concat (keep r [:charter :wakes :may])
                         (map :label (:works r))
                         [(get-in r [:sitting :label])])]
      (is (nil? (re-find chrome line)) line))))

(deftest a-seat-with-no-find-names-no-queue
  (let [r (seats/reads-as rdef-of nil seat)]
    (is (= [] (:works r)))
    (is (string? (:may r)))))

(deftest a-kind-the-registry-does-not-answer-is-named-with-its-door
  (is (= "Wakes when: a widget goes through polish."
         (:wakes (seats/reads-as rdef-of
                                 (fn [kind _ _]
                                   (when (= :subscription kind)
                                     [{:data {:kinds ["widget"] :actions ["polish"]
                                              :receiver {:type "door" :id "w1"}}}]))
                                 {:id "w1" :data {}})))))
