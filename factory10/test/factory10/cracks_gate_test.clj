(ns factory10.cracks-gate-test
  "The cracks gate (ticket cd402fb6, part 3 of epic 400609cf): the
  cracks audit over the factory's law and the fixture seats, with no
  database. A change to the law, or to the fixture, that opens a crack
  fails here until the crack is closed or the allowlist names it with
  a reason.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.seat-fixture :as fixture]
            [waymark10.server.cracks :as cracks]))

(def allowlist
  "The cracks this factory keeps on purpose. Each names its reason; an
  entry with no reason allows nothing."
  [{:kind "ticket" :state "draft" :crack "person_only"
    :reason "A person grooms a draft into the queue: that judgment is theirs."}
   {:kind "ticket" :state "blocked" :crack "person_only"
    :reason "No fixture seat holds unblock: a blocked ticket waits on its blocker or on a person."}
   {:kind "ticket" :state "deferred" :crack "person_only"
    :reason "A person deferred it, and a person resumes it."}
   {:kind "ticket" :state "done" :crack "person_only"
    :reason "Done is where a ticket rests; reopen is a person's door."}
   {:kind "ticket" :state "dropped" :crack "person_only"
    :reason "Dropped is where a ticket rests; reopen is a person's door."}
   {:kind "ticket" :state "in_review" :crack "dead_wait" :on "merge_after" :outcome "dropped"
    :reason "The law does not yet say what a dropped ticket does to what merges after it; until it does, a person lifts that wait."}
   {:kind "change" :state "submitted" :crack "cadence_only"
    :reason "A submitted change waits on its checks, not on a seat; the code seat's stall there is a door, not its work."}
   {:kind "change" :state "stuck" :crack "person_only"
    :reason "A person reads every stall."}
   {:kind "change" :state "closed" :crack "person_only"
    :reason "Closed is where a change rests; reopen is a person's door."}
   {:kind "ci_run" :state "classified" :crack "person_only"
    :reason "Classified is where a run rests; reclassify is a person overruling the label."}
   {:kind "repo_policy" :state "active" :crack "person_only"
    :reason "The policy is a person's form: only a person retires it."}
   {:kind "repo_policy" :state "retired" :crack "person_only"
    :reason "The policy is a person's form: only a person restores it."}])

(defn- crack-key
  "What makes a crack the one an entry names."
  [c]
  (select-keys c [:kind :state :crack :door :remedy :on :outcome]))

(defn- unlisted
  "The cracks no entry of `allow` names with a reason."
  [cracks allow]
  (let [allowed (into #{}
                      (comp (remove (comp str/blank? str :reason))
                            (map crack-key))
                      allow)]
    (into [] (remove (comp allowed crack-key)) cracks)))

(defn- told
  "A crack as a failure prints it."
  [c]
  (select-keys c [:kind :state :crack :door :remedy :on :outcome :what_would_close_it]))

(deftest the-law-and-the-fixture-leave-no-unlisted-crack
  (let [rdefs (main/resources)
        cracks (cracks/audit rdefs (fixture/seats rdefs))]
    (testing "every crack is on the allowlist, with its reason"
      (is (= [] (mapv told (unlisted cracks allowlist)))))
    (testing "every allowlist entry still names a crack"
      (is (= [] (into []
                      (remove (comp (into #{} (map crack-key) cracks) crack-key))
                      allowlist))))
    (testing "a ticket a seat is woken to is clean"
      (is (empty? (filter #(and (= "ticket" (:kind %))
                                (contains? #{"open" "in_review"} (:state %))
                                (not= "dead_wait" (:crack %)))
                          cracks))))))

(deftest removing-the-groom-wake-fails-the-gate
  (let [rdefs (main/resources)
        deaf (update-in fixture/code-seat [:data :wake_on]
                        (fn [entries]
                          (filterv #(not-any? #{"groom"} (:actions %)) entries)))
        cracks (cracks/audit rdefs (replace {fixture/code-seat deaf}
                                            (fixture/seats rdefs)))
        open {:kind "ticket" :state "open" :crack "cadence_only"}
        [found & more] (unlisted cracks allowlist)]
    (testing "ticket/open goes from clean to cadence_only, and the gate fails"
      (is (nil? more))
      (is (= open (crack-key found)))
      (is (= 3600 (:shortest_cadence_seconds found))))
    (testing "an allowlist entry that names its reason makes it pass"
      (is (= [] (unlisted cracks (conj allowlist
                                       (assoc open :reason "The hour is soon enough."))))))
    (testing "an entry with no reason does not"
      (is (= [found] (unlisted cracks (conj allowlist open))))
      (is (= [found] (unlisted cracks (conj allowlist (assoc open :reason " "))))))))

(deftest a-state-only-the-mayor-leaves-is-person-only
  (let [rdefs (main/resources)
        [draft & more] (filter #(and (= "ticket" (:kind %)) (= "draft" (:state %))
                                     (= "person_only" (:crack %)))
                               (cracks/audit rdefs (fixture/seats rdefs)))]
    (is (nil? more))
    (is (= [["mayor" ["interactive"]]]
           (map (juxt :seat :wakes) (:seats_considered draft))))))
