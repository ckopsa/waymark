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
   {:kind "ticket" :state "deferred" :crack "person_only"
    :reason "A person deferred it, and a person resumes it."}
   {:kind "ticket" :state "in_review" :crack "dead_wait" :on "merge_after" :outcome "dropped"
    :reason "The law does not yet say what a dropped ticket does to what merges after it; until it does, a person lifts that wait."}
   {:kind "change" :state "stuck" :crack "person_only"
    :reason "A person reads every stall."}
   {:kind "ci_run" :state "classified" :crack "person_only"
    :reason "Classified is where a run rests; reclassify is a person overruling the label."}
   {:kind "repo_policy" :state "active" :crack "person_only"
    :reason "The policy is a person's form: only a person retires it."}
   {:kind "repo_policy" :state "retired" :crack "person_only"
    :reason "The policy is a person's form: only a person restores it."}
   {:kind "ci_run" :state "classified" :crack "silent_person_exit"
    :reason "Classified is where a run rests: reclassify is for the person who already doubts the label."}
   {:kind "repo_policy" :state "active" :crack "silent_person_exit"
    :reason "Active is where a policy rests: retire is the choice of the person who wrote it."}])

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

(deftest an-ending-left-by-reopen-alone-is-no-crack
  (let [rdefs (main/resources)
        cracks (cracks/audit rdefs (fixture/seats rdefs))]
    (doseq [[kind state] [["ticket" "done"] ["ticket" "dropped"] ["change" "closed"]]]
      (testing (str kind "/" state " is where a row rests")
        (is (= [] (into []
                        (comp (filter #(and (= kind (:kind %)) (= state (:state %))))
                              (map told))
                        cracks)))))))

;; ticket 4b0460cc: change declares its indirect exits and arrival.
(deftest a-change-is-left-by-the-mirror-and-reached-by-its-tickets-groom
  (let [rdefs (main/resources)
        others (remove #{fixture/code-seat} (fixture/seats rdefs))
        groomed (assoc-in fixture/code-seat [:data :wake_on]
                          [{:kind "ticket" :actions ["groom"] :filter {:state "open"}}
                           {:kind "change" :actions ["fail"]}])
        deaf (update-in groomed [:data :wake_on] (comp vec rest))
        undeclared (mapv #(cond-> % (= "change" (name (:kind %))) (dissoc :reached-by :left-by))
                         rdefs)
        at (fn [rdefs seat state]
             (into []
                   (comp (filter #(and (= "change" (:kind %)) (= state (:state %))))
                         (map :crack))
                   (cracks/audit rdefs (concat others [seat]))))]
    (testing "the mirror leaves submitted, and no seat is owed a wake there"
      (is (= [] (at rdefs groomed "submitted"))))
    (testing "the wake on the ticket's groom is the wake to the change born open"
      (is (= [] (at rdefs groomed "open"))))
    (testing "with no groom wake, open waits for the cadence"
      (is (= ["cadence_only"] (at rdefs deaf "open"))))
    (testing "removing the declarations brings both back"
      (is (= ["cadence_only"] (at undeclared groomed "open")))
      (is (= ["cadence_only"] (at undeclared groomed "submitted"))))))

(defn- repo-seat
  "The code seat, woken on its changes in one repository alone."
  [name repo]
  (-> fixture/code-seat
      (assoc :id name)
      (assoc-in [:data :name] name)
      (assoc-in [:data :wake_on]
                [{:kind "ticket" :actions ["groom"] :filter {:state "open"}}
                 {:kind "change" :actions ["fail" "rework" "unstick"]
                  :filter {:repository repo}}])))

(deftest seats-that-each-wake-on-one-repository-cover-a-state-together
  (let [rdefs (main/resources)
        others (remove #{fixture/code-seat} (fixture/seats rdefs))
        a (repo-seat "a-seat" "ckopsa/a")
        b (repo-seat "b-seat" "ckopsa/b")
        deaf (update-in b [:data :wake_on] (comp vec butlast))
        world {:rows {:change [{:id "1" :state :failing :data {:repository "ckopsa/a"}}
                               {:id "2" :state :open :data {:repository "ckopsa/b"}}
                               {:id "3" :state :closed :data {:repository "ckopsa/gone"}}]}}
        failing (fn [seats world]
                  (filterv #(and (= "change" (:kind %)) (= "failing" (:state %)))
                           (cracks/audit rdefs (concat others seats) world)))]
    (testing "rows in A and B alone, and a wake for each: change/failing is clean"
      (is (= [] (mapv told (failing [a b] world)))))
    (testing "drop B's wake, and the crack names the repository nobody wakes for"
      (let [[c & more] (failing [a deaf] world)]
        (is (nil? more))
        (is (= "cadence_only" (:crack c)))
        (is (= [{:field "repository" :value "ckopsa/b" :rows 1}] (:unwoken_values c)))
        (is (re-find #"repository ckopsa/b" (:what_would_close_it c)))))
    (testing "with no rows read, a narrowed wake covers nothing"
      (is (= ["cadence_only"] (mapv :crack (failing [a b] nil)))))))
