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
            [waymark10.server.waiting-on :as waiting-on])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-10-13T12:00:00Z"))

(def ^:private one-repo {:state "open" :repo "ckopsa/waymark"})

(def ^:private waymark-seat
  "The code seat as it is seated for one repository: it holds its
  ticket doors, and is woken, for that repository's tickets alone."
  (-> fixture/code-seat
      (assoc-in [:data :scope 0 :filter] one-repo)
      (assoc-in [:data :wake_on 0 :filter] one-repo)))

(defn- ticket [repo blocked-by]
  {:id "t-1"
   :state :open
   :updated-at (Instant/parse "2026-10-13T09:00:00Z")
   :data {:title "A ticket" :repo repo :blocked_by blocked-by}})

(defn- read-it [seats row]
  (let [by-kind (into {} (map (juxt :kind identity)) (main/resources))]
    (waiting-on/block (get by-kind :ticket) seats by-kind row
                      {:now now :data (:data row)})))

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
      (is (= {:at "2026-10-13T09:00:00Z" :expected_seconds 86400}
             (:since w)))
      (is (str/ends-with? (:verdict w) ", 3 h of 24 h") (:verdict w)))
    (testing "a row that is not held carries no hold"
      (is (not (contains? w :held_by))))))

(deftest a-ticket-for-a-repository-no-seat-admits-says-nobody
  (let [w (read-it models (ticket "ckopsa/messa" []))]
    (is (= "nobody: no active seat holds a door out of open for repo ckopsa/messa, 3 h of 24 h"
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

(deftest a-hold-field-that-is-set-is-named
  (let [w (read-it models (ticket "ckopsa/waymark" ["t-0"]))]
    (is (= {"blocked_by" ["t-0"]} (:held_by w)))
    (is (str/ends-with? (:verdict w) "; held by blocked_by") (:verdict w))))

(deftest a-row-at-rest-waits-on-nothing
  (let [w (read-it models (assoc (ticket "ckopsa/waymark" []) :state :done))]
    (is (= [] (:next w)))
    (is (str/starts-with? (:verdict w) "nothing: done is where a ticket row rests")
        (:verdict w))))
