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
            [waymark10.guards :as g]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.live-cracks :as live]
            [waymark10.server.seats :as seats]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def allowlist
  "The cracks this factory keeps on purpose. Each names its reason; an
  entry with no reason allows nothing."
  [{:kind "ticket" :state "draft" :crack "person_only"
    :reason "A person grooms a draft into the queue: that judgment is theirs."}
   {:kind "ticket" :state "deferred" :crack "person_only"
    :reason "A person deferred it, and a person resumes it."}
   {:kind "change" :state "stuck" :crack "person_only"
    :reason "A person reads every stall."}
   {:kind "ci_run" :state "classified" :crack "person_only"
    :reason "Classified is where a run rests; reclassify is a person overruling the label."}
   {:kind "ci_run" :state "classified" :crack "silent_person_exit"
    :reason "Classified is where a run rests: reclassify is for the person who already doubts the label."}
   {:kind "org_plan" :state "live" :crack "person_only"
    :reason "Live is where an org runs: retiring it is its owner's choice."}
   {:kind "repo_policy" :state "active" :crack "silent_person_exit"
    :reason "Active is where a policy rests: retire is the choice of the person who wrote it."}
   {:kind "repo_policy" :state "retired" :crack "silent_person_exit"
    :reason "Retired is where a policy rests: restore is the choice of the person who retired it."}])

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

(defn- audit
  "`cracks/audit` over `seat-rows` handed their door subscriptions
  (`fixture/fired`)."
  ([rdefs seat-rows] (cracks/audit rdefs (fixture/fired rdefs seat-rows)))
  ([rdefs seat-rows world] (cracks/audit rdefs (fixture/fired rdefs seat-rows) world)))

(defn- opened
  "`cracks/opened` between two sets of seats, each handed its door
  subscriptions (`fixture/fired`)."
  [rdefs before after]
  (cracks/opened rdefs (fixture/fired rdefs before) (fixture/fired rdefs after)))

(defn- told
  "A crack as a failure prints it."
  [c]
  (select-keys c [:kind :state :crack :door :remedy :on :outcome :what_would_close_it]))

(deftest the-law-and-the-fixture-leave-no-unlisted-crack
  (let [rdefs (main/resources)
        cracks (audit rdefs (fixture/seats rdefs))]
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

;; ticket 42259e4b: the house's export is the fixture's shape.
(deftest the-fixture-reads-back-from-the-seat-export
  (let [rdefs (main/resources)
        seats (fixture/fired rdefs (fixture/seats rdefs))
        back (fixture/from-export (cracks/seat-export seats))]
    (testing "each seat comes back as it was written"
      (is (= (sort-by :id seats) back)))
    (testing "and the audit over them finds the same cracks"
      (is (= (cracks/audit rdefs seats) (cracks/audit rdefs back))))))

(deftest removing-the-groom-wake-fails-the-gate
  (let [rdefs (main/resources)
        deaf (update-in fixture/code-seat [:data :wake_on]
                        (fn [entries]
                          (filterv #(not-any? #{"groom"} (:actions %)) entries)))
        cracks (audit rdefs (replace {fixture/code-seat deaf}
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
                               (audit rdefs (fixture/seats rdefs)))]
    (is (nil? more))
    (is (= [["mayor" ["interactive"]]]
           (map (juxt :seat :wakes) (:seats_considered draft))))))

(deftest an-ending-left-by-reopen-alone-is-no-crack
  (let [rdefs (main/resources)
        cracks (audit rdefs (fixture/seats rdefs))]
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
                   (audit rdefs (concat others [seat]))))]
    (testing "the mirror leaves submitted, and no seat is owed a wake there"
      (is (= [] (at rdefs groomed "submitted"))))
    (testing "the wake on the ticket's groom is the wake to the change born open"
      (is (= [] (at rdefs groomed "open"))))
    (testing "with no groom wake, open waits for the cadence"
      (is (= ["cadence_only"] (at rdefs deaf "open"))))
    (testing "removing the declarations brings both back"
      (is (= ["cadence_only"] (at undeclared groomed "open")))
      (is (= ["cadence_only"] (at undeclared groomed "submitted"))))))

;; ticket 21b49b28: ticket and change declare their expected ages.
(deftest a-row-past-its-declared-age-is-aged
  (let [rdefs (main/resources)
        now (Instant/parse "2026-10-13T12:00:00Z")
        row (fn [id state hours]
              {:id id :state state :data {}
               :updated-at (.minusSeconds ^Instant now (long (* 3600 hours)))})
        aged (live/aged-rows
              rdefs (fixture/seats rdefs)
              {:ticket [(row "t-open" :open 13)
                        (row "t-fresh" :open 11)
                        (row "t-review" :in_review 7)
                        (row "t-blocked" :blocked 25)
                        (row "t-deferred" :deferred 9999)]
               :change [(row "c-open" :open 7)
                        (row "c-submitted" :submitted 7)
                        (row "c-failing" :failing 5)
                        (row "c-stuck" :stuck 23)
                        (row "c-stuck-old" :stuck 25)]}
              now)]
    (is (= #{["ticket" "t-open" 43200]
             ["ticket" "t-review" 21600]
             ["change" "c-open" 21600]
             ["change" "c-submitted" 21600]
             ["change" "c-failing" 14400]
             ["change" "c-stuck-old" 86400]}
           (into #{} (map (juxt :kind :id :expected_seconds)) aged)))))

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
                           (audit rdefs (concat others seats) world)))]
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

;; ticket 5f3c276c: a staffing change that opens a crack stops at the door.

(defn- narrowed-wake
  "The seat with its ticket wake narrowed to the tickets in review, so
  that a groom into open does not wake it."
  [seat]
  (assoc-in seat [:data :wake_on 0 :filter] {:state "in_review"}))

(def ^:private ticket-open
  {:kind "ticket" :state "open" :crack "cadence_only"})

(deftest narrowing-the-ticket-wake-opens-a-crack-and-widening-it-does-not
  (let [rdefs (main/resources)
        seats (vec (fixture/seats rdefs))
        narrowed (vec (replace {fixture/code-seat (narrowed-wake fixture/code-seat)}
                               seats))]
    (testing "narrowed, ticket/open has no woken seat"
      (let [found (opened rdefs seats narrowed)]
        (is (some #(= ticket-open (crack-key %)) found) (pr-str (mapv told found)))
        (is (not-any? (comp str/blank? str :what_would_close_it) found))))
    (testing "widened again, a crack closed is no crack opened"
      (is (= [] (mapv told (opened rdefs narrowed seats)))))
    (testing "the same seats open nothing"
      (is (= [] (mapv told (opened rdefs seats seats)))))
    (testing "a seat that leaves opens the states it alone could leave"
      (is (some #(= "ticket" (:kind %))
                (opened rdefs seats (vec (remove #{fixture/code-seat} seats))))))
    (testing "a seat that comes back opens nothing"
      (is (= [] (mapv told (opened rdefs
                                   (vec (remove #{fixture/code-seat} seats))
                                   seats)))))))

(defn- door-ctx
  "What a staffing door's guard reads: the caller, the kinds, the active
  seats and the subscriptions in the state it asks for."
  ([rdefs seats principal] (door-ctx rdefs seats [] principal))
  ([rdefs seats subs principal]
   (t/ctx {:principal principal
           :rdefs (fn [] rdefs)
           :read (fn [_kind _id] nil)
           :find (fn [kind where _opts]
                   (case kind
                     :seat seats
                     :subscription (filterv #(= (str (:state where)) (name (:state %))) subs)
                     nil))})))

(defn- woken-by
  "[seat subs]: `seat` woken by its door subscriptions alone
  (`fixture/wakes`)."
  [seat]
  (let [[[s] subs] (fixture/wakes [seat])]
    [s subs]))

(defn- judged
  "[denied? the refusal's sentence] of one guard at the restate door of
  code-seat, or at its park door when `inp` is empty."
  [guard row inp ctx]
  (let [[v d] (g/evaluate guard row inp ctx)]
    [(t/deny? v) (when (t/deny? v) (g/render-reason d v row))]))

(defn- woken-fixture
  "[seats subs]: the fixture's seats with code-seat woken by its door
  subscriptions alone."
  [rdefs]
  (let [[code subs] (woken-by fixture/code-seat)]
    [(vec (replace {fixture/code-seat code} (fixture/seats rdefs))) subs]))

(deftest the-subscription-door-stops-a-narrowing-that-opens-a-crack
  (let [rdefs (main/resources)
        [seats subs] (woken-fixture rdefs)
        groom (first subs)
        narrow {:filter {:state "in_review"}}
        narrowed (assoc-in groom [:data :filter] {:state "in_review"})
        at (fn [subs principal]
             (assoc (door-ctx rdefs seats subs principal) :door :restate))
        person {:id "colton" :type :human}
        named #"ticket/open cadence_only: \S"]
    (testing "the warning names the crack a person's narrowing opens"
      (let [[denied? why] (judged seats/a-dropped-wake-opens-no-crack groom narrow
                                  (at subs person))]
        (is denied?)
        (is (re-find named (str why)) why))
      (is (= :warning (:severity seats/a-dropped-wake-opens-no-crack)))
      (is (= [false nil] (judged seats/a-dropped-wake-waits-on-the-person groom narrow
                                 (at subs person)))))
    (testing "and a person's widening passes it"
      (is (= [false nil] (judged seats/a-dropped-wake-opens-no-crack narrowed
                                 {:filter {:state "open"}}
                                 (at (assoc subs 0 narrowed) person)))))
    (testing "an authoring seat's narrowing restate is held, with the crack named"
      (with-redefs [delegation/author-seat (fn [& _] {:id "mayor" :data {:name "mayor"}})]
        (let [agent {:id "mayor-model" :type :agent :acts-for "colton"}
              [denied? why] (judged seats/a-dropped-wake-waits-on-the-person groom narrow
                                    (at subs agent))]
          (is denied?)
          (is (re-find named (str why)) why)
          (is (delegation/hold-guard? (:name seats/a-dropped-wake-waits-on-the-person)))
          (testing "and the warning leaves it to the hold"
            (is (= [false nil] (judged seats/a-dropped-wake-opens-no-crack groom narrow
                                       (at subs agent)))))
          (testing "its widening restate passes"
            (is (= [false nil] (judged seats/a-dropped-wake-waits-on-the-person narrowed
                                       {:filter {:state "open"}}
                                       (at (assoc subs 0 narrowed) agent))))))))))

(deftest pausing-the-only-wake-for-a-state-is-stopped
  (let [rdefs (main/resources)
        [seats subs] (woken-fixture rdefs)
        groom (first subs)
        person {:id "colton" :type :human}
        at (fn [door] (assoc (door-ctx rdefs seats subs person) :door door))]
    (testing "a person's pause of the one row woken for ticket/open is warned, with the crack named"
      (let [[denied? why] (judged seats/a-dropped-wake-opens-no-crack groom nil (at :pause))]
        (is denied?)
        (is (re-find #"ticket/open cadence_only: \S" (str why)) why)))
    (testing "and so is its revoke"
      (is (first (judged seats/a-dropped-wake-opens-no-crack groom nil (at :revoke)))))
    (testing "a row whose wake another seat's covers is paused freely"
      (let [[code more] (woken-by (assoc fixture/code-seat :id "code-twin"))]
        (is (= [false nil]
               (judged seats/a-dropped-wake-opens-no-crack groom nil
                       (assoc (door-ctx rdefs (conj seats code) (into subs more) person)
                              :door :pause))))))))

;; ticket adcf09e1: a far door excuses no wake filter on the kind itself.

(deftest a-ticket-wake-on-one-repository-is-narrower-than-a-far-door
  (let [rdefs (main/resources)
        seats (vec (fixture/seats rdefs))
        one-repo (assoc-in fixture/code-seat [:data :wake_on 0 :filter]
                           {:state "open" :repo "ckopsa/waymark"})
        narrowed (vec (replace {fixture/code-seat one-repo} seats))
        person {:id "colton" :type :human}]
    (testing "code-seat holds change submit, and ticket/open is still unwoken"
      (let [found (opened rdefs seats narrowed)]
        (is (some #(= ticket-open (crack-key %)) found) (pr-str (mapv told found)))))
    (testing "and the subscription door's crack guard names it"
      (let [[woken subs] (woken-fixture rdefs)
            [denied? why] (judged seats/a-dropped-wake-opens-no-crack (first subs)
                                  {:filter {:state "open" :repo "ckopsa/waymark"}}
                                  (assoc (door-ctx rdefs woken subs person)
                                         :door :restate))]
        (is denied?)
        (is (re-find #"ticket/open cadence_only: \S" (str why)) why)))))

;; ticket 841eb517: a seat restate is judged once, at the seat door, with
;; its managed rows as its write leaves them.

(deftest a-seat-restate-is-judged-once-with-its-managed-rows
  (let [rdefs (main/resources)
        code (update fixture/code-seat :data dissoc :wake_on)
        managed (mapv (fn [e] {:id "code-seat-managed" :state :active
                               :data (assoc (seats/wake-subscription code e)
                                            :managed_by "seat")})
                      (seats/wake-entries code))
        seats (vec (replace {fixture/code-seat code} (fixture/seats rdefs)))
        ;; it still holds ticket/open's doors, and its managed row now
        ;; hears a change born and no ticket
        walks-changes (assoc (:data code) :walk "change")
        person {:id "colton" :type :human}
        at (fn [principal door]
             (assoc (door-ctx rdefs seats managed principal) :door door))]
    (is (= 1 (count managed)))
    (testing "a restate of the walk rewrites the managed row, and the seat door names the crack"
      (let [[denied? why] (judged seats/opens-no-crack code walks-changes (at person :restate))]
        (is denied?)
        (is (re-find #"ticket/open \w+: \S" (str why)) why)))
    (testing "the same restate with its walk kept opens nothing"
      (is (= [false nil] (judged seats/opens-no-crack code (:data code)
                                 (at person :restate)))))
    (let [row (first managed)
          narrow {:filter {:state "open" :repo "ckopsa/waymark"}}
          nested (assoc (at seats/seats-actor :restate) :within {:kind :seat})]
      (testing "its nested managed-row writes are not judged again"
        (is (= [false nil] (judged seats/a-dropped-wake-opens-no-crack row narrow nested)))
        (is (= [false nil] (judged seats/a-dropped-wake-waits-on-the-person row narrow nested)))
        (is (= [false nil] (judged seats/a-dropped-wake-opens-no-crack row nil
                                   (assoc nested :door :pause)))))
      (testing "but the seats actor outside a seat write is"
        (is (first (judged seats/a-dropped-wake-opens-no-crack row narrow
                           (at seats/seats-actor :restate))))))))

(deftest a-retire-or-merge-is-judged-at-its-own-door
  (let [rdefs (main/resources)
        [seats subs] (fixture/wakes (fixture/seats rdefs))
        person {:id "colton" :type :human}
        at (fn [door] (assoc (door-ctx rdefs seats subs person) :door door))]
    (testing "a retire that takes the only woken seat out is warned, by its door"
      (let [[denied? why] (judged seats/opens-no-crack fixture/code-seat nil
                                  (at :retire))]
        (is denied?)
        (is (re-find #"ticket/" (str why)) why)))
    (testing "the same empty body at a door that is not a leaving one is no crack"
      (is (= [false nil] (judged seats/opens-no-crack fixture/code-seat
                                 (:data fixture/code-seat) (at :restate)))))))

;; ticket 9e2ff8a9: a merge is read as the seat leaving and its scope
;; folding into `into`; its wakes do not fold.

(defn- twin
  "code-seat under another name, changed by `f`: a merge's `into`."
  [seat-name f]
  (-> fixture/code-seat
      (assoc :id seat-name)
      (assoc-in [:data :name] seat-name)
      f))

(deftest a-merge-is-read-as-its-scope-folded-into-the-target
  (let [rdefs (main/resources)
        person {:id "colton" :type :human}
        with (fn [target] (conj (vec (fixture/seats rdefs)) target))
        at (fn [seats door]
             (let [[rows subs] (fixture/wakes seats)]
               (assoc (door-ctx rdefs rows subs person) :door door)))]
    (testing "a target woken as the seat is closes, with the scope it is given, what the seat alone could leave"
      (let [bare (twin "bare-seat"
                       (fn [s] (update-in s [:data :scope]
                                          (fn [entries]
                                            (filterv #(not= "ticket" (:kind %)) entries)))))
            ;; the mayor holds every door, so without it the seat alone
            ;; holds ticket/open's
            seats (conj (vec (remove #(= "mayor" (:id %)) (fixture/seats rdefs)))
                        bare)]
        (testing "retired instead, the seat's ticket doors leave with it"
          (is (first (judged seats/opens-no-crack fixture/code-seat nil
                             (at seats :retire)))))
        (is (= [false nil] (judged seats/opens-no-crack fixture/code-seat
                                   {:into "bare-seat"} (at seats :merge))))))
    (testing "a target whose wakes do not cover the seat's opens the crack its wake closed"
      (let [[code code-subs] (woken-by fixture/code-seat)
            [deaf deaf-subs] (woken-by
                              (twin "deaf-seat"
                                    (fn [s] (update-in s [:data :wake_on]
                                                       (fn [entries]
                                                         (filterv #(not-any? #{"groom"} (:actions %))
                                                                  entries))))))
            seats (conj (vec (replace {fixture/code-seat code} (fixture/seats rdefs))) deaf)
            [denied? why] (judged seats/opens-no-crack code {:into "deaf-seat"}
                                  (assoc (door-ctx rdefs seats (into code-subs deaf-subs) person)
                                         :door :merge))]
        (is denied?)
        (is (re-find #"ticket/open cadence_only: \S" (str why)) why)))))
