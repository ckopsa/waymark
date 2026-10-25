(ns factory10.waiting-on-test
  "What a row waits on (ticket 86af9d88): the `waiting_on` block over
  the factory's law and the fixture seats, with no database. A ticket
  for a repository no seat's scope admits says nobody holds a door for
  it; an open ticket for the repository the code seat walks names that
  seat, the door it holds and what woke it.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.seat-fixture :as fixture]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.waiting-on :as waiting-on])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-10-13T12:00:00Z"))

(def ^:private one-repo {:state "open" :repo "ckopsa/waymark"})

(def ^:private waymark-seat
  "The code seat as it is seated for one repository: it holds its
  ticket doors, and is woken, for that repository's tickets alone."
  (assoc-in fixture/code-seat [:data :scope 0 :filter] one-repo))

(defn- one-repo-wake
  "[seats subs] with the code seat's first door subscription narrowed
  to that repository's tickets."
  [[rows subs]]
  [rows (mapv #(cond-> %
                 (= (str (:id waymark-seat) "-wake-0") (:id %))
                 (assoc-in [:data :filter] one-repo))
              subs)])

(defn- ticket [repo blocked-by]
  {:id "t-1"
   :state :open
   :updated-at (Instant/parse "2026-10-13T09:00:00Z")
   :data {:title "A ticket" :repo repo :blocked_by blocked-by}})

(defn- read-row
  ([kind seats row] (read-row kind seats row {}))
  ([kind seats row reads]
   (let [by-kind (into {} (map (juxt :kind identity)) (main/resources))
         [rows subs] (one-repo-wake (fixture/wakes seats))]
     (waiting-on/block (get by-kind kind) (cracks/with-fires by-kind rows subs) by-kind row
                       (assoc reads :now now :data (:data row))))))

(defn- read-it [seats row]
  (read-row :ticket seats row))

(def ^:private models [waymark-seat fixture/judge-seat fixture/ops-seat])

(deftest an-open-ticket-the-code-seat-walks-names-it
  (let [w (read-it models (ticket "ckopsa/waymark" []))
        submit (first (filter #(= "change/submit" (:door %)) (:next w)))]
    (testing "the verdict names the seat, a door it holds and its wake"
      (is (str/starts-with? (:verdict w) "waiting on code-seat (holds ") (:verdict w))
      (is (str/includes? (:verdict w) "change/submit") (:verdict w))
      (is (str/includes? (:verdict w) "woken by groom") (:verdict w)))
    (testing "the door the ticket is left by is held by that seat"
      (is (= "seat" (:held_by submit)))
      (is (= [{:seat "code-seat" :woken_by ["groom"] :cadence_seconds 3600}]
             (:seats submit))))
    (testing "it says how long the row has sat against its expected age"
      (is (= {:at "2026-10-13T09:00:00Z" :expected_seconds 43200}
             (:since w)))
      (is (str/ends-with? (:verdict w) ", 3 h of 12 h") (:verdict w)))
    (testing "a row that is not held carries no hold"
      (is (not (contains? w :held_by))))))

(deftest a-ticket-for-a-repository-no-seat-admits-says-nobody
  (let [w (read-it models (ticket "ckopsa/messa" []))]
    (is (= "nobody: no active seat holds a door out of open for repo ckopsa/messa, 3 h of 12 h"
           (:verdict w)))
    (is (seq (:next w)))
    (is (every? #(= "nobody" (:held_by %)) (:next w)))))

(deftest a-ticket-only-a-person-can-move-says-a-person
  (let [seats (conj models (fixture/mayor (main/resources)))
        w (read-it seats (ticket "ckopsa/messa" []))]
    (is (str/starts-with?
         (:verdict w)
         "a person: no seat a model sits in holds a door out of open for repo ckopsa/messa; mayor holds ")
        (:verdict w))
    (is (not-any? #(= "seat" (:held_by %)) (:next w)))))

(defn- change [data]
  {:id "c-1"
   :state :open
   :updated-at (Instant/parse "2026-10-13T09:00:00Z")
   :data (merge {:title "A change" :repository "ckopsa/waymark"} data)})

(def ^:private draft-judge
  "A judgment seat that holds doors out of an open ticket, and walks
  its judgment's queue of drafts."
  {:id "s-draft-judge"
   :state :active
   :data {:name "draft-judge"
          :walk "ticket"
          :judgment "j-draft"
          :cadence_seconds 21600
          :scope [{:kind "ticket" :actions ["block" "drop"]}]}})

(deftest a-holder-is-a-seat-whose-walk-hands-it-the-row
  (let [mayor (fixture/mayor (main/resources))]
    (testing "a person's own change, born from no ticket, is the person's"
      (let [w (read-row :change (conj models mayor)
                        (change {:author "ckopsa"}))]
        (is (= {:type "person" :name "mayor"} (:holder w)) (:verdict w))
        (is (str/starts-with?
             (:verdict w)
             "a person: no seat a model sits in walks this row to a door out of open")
            (:verdict w))
        (testing "and `next` still names every seat that holds a door"
          (is (some #(some #{"code-seat"} (map :seat (:seats %))) (:next w))))))
    (testing "an open ticket no code seat's filter names, that the judge does not walk"
      (let [w (read-row :ticket (conj models draft-judge mayor)
                        (ticket "ckopsa/messa" [])
                        {:judgments {"j-draft" {:id "j-draft"
                                                :data {:queue {:state "draft"}}}}})]
        (is (= {:type "person" :name "mayor"} (:holder w)) (:verdict w))
        (is (some #(some #{"draft-judge"} (map :seat (:seats %))) (:next w)))))
    (testing "a change born from a groomed ticket in the code seat's repository"
      (let [w (read-row :change (conj models mayor)
                        (change {:author "code-seat" :born_from "ticket:t-1"})
                        {:tickets {"t-1" (ticket "ckopsa/waymark" [])}})]
        (is (= {:type "seat" :name "code-seat"} (:holder w)) (:verdict w))))))

(deftest a-hold-field-that-is-set-is-named
  (let [w (read-it models (ticket "ckopsa/waymark" ["t-0"]))]
    (is (= {"blocked_by" ["t-0"]} (:held_by w)))
    (is (str/ends-with? (:verdict w) "; held by blocked_by") (:verdict w))))

(deftest a-change-kept-for-a-person-says-why
  (let [reason "touches terraform/keycloak_users.tf, which this repository's policy keeps for a person"
        w (read-row :change (conj models (fixture/mayor (main/resources)))
                    (change {:author "code-seat" :line_why "person" :line_reason reason}))]
    (is (= (str "waits on a person's tap: " reason) (:note w)))
    (is (str/ends-with? (:verdict w) (str "; held by line_why; waits on a person's tap: " reason))
        (:verdict w)))
  (testing "a line_why that is not person carries no note"
    (let [w (read-row :change models
                      (change {:line_why "parked" :line_reason "not mergeable"}))]
      (is (not (contains? w :note)))
      (is (not (str/includes? (:verdict w) "person's tap")) (:verdict w)))))

(deftest a-row-at-rest-waits-on-nothing
  (let [w (read-it models (assoc (ticket "ckopsa/waymark" []) :state :done))]
    (is (= [] (:next w)))
    (is (str/starts-with? (:verdict w) "nothing: done is where a ticket row rests")
        (:verdict w))))
