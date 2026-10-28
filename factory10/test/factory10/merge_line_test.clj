(ns factory10.merge-line-test
  "The house merge pass brings one pull request per repository up to
  date at a time, and the rest wait in line (ticket d82d649a).

  The line is worked with no engine at all: `work-lines!` takes the
  ctx a door would, and the rig behind `:bench-rpc` is a fake that
  answers each pull request by its number.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-09-28T12:00:00Z"))

(defn- policy [repo]
  {:data {:repository repo :merge_by "house" :required_checks ["gate"]
          :merge_method "squash"}})

(def ^:private by-repo
  {"ckopsa/waymark" (policy "ckopsa/waymark")
   "ckopsa/waymark-bench" (policy "ckopsa/waymark-bench")})

(defn- a-change
  "One submitted pull request: its id ends in its number, its ticket is
  t<number>, and it was born `minute` minutes after t0."
  [repo number minute & {:as extra}]
  {:id (str "change-" number)
   :created-at (.plusSeconds ^Instant t0 (long (* 60 minute)))
   :data (merge {:repository repo :number number
                 :head_sha (str "head-" number)
                 :born_from (str "ticket:t" number)}
                extra)})

(defn- asked!
  "A ctx's door callback: it collects [door id input] in `asks`, where
  the engine would walk `update_asked` or `rerun_asked` on the row."
  [asks door]
  (fn [id input] (swap! asks conj [door id input]) true))

(defn- rig
  "A fake rig: `answers` is number -> what its merge says (behind when
  unnamed), and every update_branch says updated. Its ctx's `:recorded`
  outlives a pass, as the rows do: a rig used again reads what it wrote;
  a fresh one is a restart that reads only the rows it is given."
  [answers]
  (let [calls (atom [])
        asks (atom [])]
    {:calls calls
     :asks asks
     :ctx {:recorded (atom {})
           :update-asked! (asked! asks :update_asked)
           :rerun-asked! (asked! asks :rerun_asked)
           :services
           {:bench-rpc
            (fn [_ {tool :name args :arguments}]
              (swap! calls conj [tool (:number args)])
              {:structuredContent
               {:result (if (= tool "bench__merge")
                          (get @answers (:number args) {:state "behind"})
                          {:state "updated"})}})}}}))

(defn- numbers-of [{:keys [calls]} tool]
  (into [] (keep (fn [[t number]] (when (= t tool) number))) @calls))

(defn- pass!
  [{:keys [ctx]} _seen changes priorities]
  (bench/work-lines! ctx
                     (bench/merge-lines changes by-repo
                                        #(get priorities (bench/born-ticket %)))
                     by-repo))

(deftest the-line-is-ticket-priority-then-age
  (let [old-low (a-change "ckopsa/waymark" 1 0)
        new-high (a-change "ckopsa/waymark" 2 30)
        old-high (a-change "ckopsa/waymark" 3 10)
        lines (bench/merge-lines [old-low new-high old-high] by-repo
                                 #(get {"t1" 2 "t2" 0 "t3" 0}
                                       (bench/born-ticket %)))]
    (is (= ["change-3" "change-2" "change-1"]
           (mapv :id (get lines "ckopsa/waymark")))
        "the lower priority number first, and the older of two equals")))

(deftest one-behind-change-per-repository-is-brought-up-to-date
  (let [r (rig (atom {}))
        seen (atom {})
        a (a-change "ckopsa/waymark" 1 0)
        b (a-change "ckopsa/waymark" 2 1)
        c (a-change "ckopsa/waymark" 3 2)]
    (is (= 3 (pass! r seen [a b c] {})) "each change still gets its merge call")
    (is (= [1] (numbers-of r "bench__update_branch"))
        "three behind green changes, one update: the front's")
    (pass! r seen [a b c] {})
    (is (= [1] (numbers-of r "bench__update_branch"))
        "the front waits for its CI, and the others are still left alone")
    (testing "the front merged: the next pass brings the second forward"
      (pass! r seen [b c] {})
      (is (= [1 2] (numbers-of r "bench__update_branch"))))))

(deftest a-green-up-to-date-change-merges-whatever-its-place
  (let [r (rig (atom {2 {:state "merged"}}))
        a (a-change "ckopsa/waymark" 1 0)
        b (a-change "ckopsa/waymark" 2 1)]
    (pass! r (atom {}) [a b] {})
    (is (= [1 2] (numbers-of r "bench__merge"))
        "the second in line is offered its merge while the first is the front")
    (is (= [1] (numbers-of r "bench__update_branch")))))

(deftest each-repository-has-its-own-front
  (let [r (rig (atom {}))
        changes [(a-change "ckopsa/waymark" 1 0)
                 (a-change "ckopsa/waymark" 2 1)
                 (a-change "ckopsa/waymark-bench" 3 2)
                 (a-change "ckopsa/waymark-bench" 4 3)]]
    (pass! r (atom {}) changes {})
    (is (= #{1 3} (set (numbers-of r "bench__update_branch"))))))

(deftest a-conflicted-or-draft-change-does-not-hold-the-line
  (let [r (rig (atom {}))
        changes [(a-change "ckopsa/waymark" 1 0 :mergeable "conflicted")
                 (a-change "ckopsa/waymark" 2 1 :draft true)
                 (a-change "ckopsa/waymark" 3 2)]]
    (pass! r (atom {}) changes {})
    (is (= [3] (numbers-of r "bench__update_branch")))))

(deftest a-parked-front-leaves-the-line
  (let [r (rig (atom {}))
        seen (atom {})]
    (pass! r seen [(a-change "ckopsa/waymark" 1 0 :merge_asked_head "head-1"
                             :merge_refused "draft")
                   (a-change "ckopsa/waymark" 2 1)] {})
    (is (= [2] (numbers-of r "bench__merge")))
    (is (= [2] (numbers-of r "bench__update_branch")))))

;; ── the line, written on the rows (ticket b85aded5) ─────────────────

(defn- marks!
  "One pass, and what it would write on the rows."
  [{:keys [ctx]} _seen changes]
  (let [lines (bench/merge-lines changes by-repo (constantly nil))
        answers (atom {})
        ctx (update ctx :recorded #(or % (atom {})))]
    (bench/work-lines! ctx lines by-repo answers)
    (bench/line-marks (into {}
                            (map (fn [[repo line]]
                                   [repo (bench/with-records @(:recorded ctx) line)]))
                            lines)
                      @answers
                      (bench/parked-changes changes by-repo))))

(deftest the-policy-names-the-front-and-each-change-its-place
  (let [m (marks! (rig (atom {})) (atom {})
                  [(a-change "ckopsa/waymark" 1 0)
                   (a-change "ckopsa/waymark" 2 1)
                   (a-change "ckopsa/waymark" 3 2)])]
    (is (= {:line_front "change-1" :line_front_pr 1
            :line_front_waiting "update" :line_waiting 2}
           (get-in m [:policies "ckopsa/waymark"]))
        "the front, its pull request, what it waits on, and 2 waiting")
    (is (= {"change-1" {:line_place 1 :line_why "front"}
            "change-2" {:line_place 2 :line_why "behind"}
            "change-3" {:line_place 3 :line_why "behind"}}
           (:changes m)))))

(deftest a-red-change-leaves-the-count
  (let [m (marks! (rig (atom {2 {:state "red"}})) (atom {})
                  [(a-change "ckopsa/waymark" 1 0)
                   (a-change "ckopsa/waymark" 2 1)
                   (a-change "ckopsa/waymark" 3 2)])]
    (is (= 1 (get-in m [:policies "ckopsa/waymark" :line_waiting])))
    (is (= {:line_why "red"} (get-in m [:changes "change-2"]))
        "a red change has no place, and says why")
    (is (= {:line_place 2 :line_why "behind"} (get-in m [:changes "change-3"])))))

(deftest a-red-answer-on-a-head-read-green-stands-in-the-line
  (testing "the rig's red on the head the mirror read green is carried over (ticket baf76388)"
    (let [m (marks! (rig (atom {2 {:state "red"}})) (atom {})
                    [(a-change "ckopsa/waymark" 1 0)
                     (a-change "ckopsa/waymark" 2 1 :green_head "head-2")
                     (a-change "ckopsa/waymark" 3 2)])]
      (is (= 2 (get-in m [:policies "ckopsa/waymark" :line_waiting])))
      (is (= {:line_place 2 :line_why "behind"} (get-in m [:changes "change-2"])))
      (is (= "behind" (:state (bench/head-answer (a-change "ckopsa/waymark" 2 1 :green_head "head-2")
                                                  {:state "red"})))
          "green on its own head, so it may ride the next train")))
  (testing "a head that moved since the green read keeps the rig's red"
    (let [m (marks! (rig (atom {2 {:state "red"}})) (atom {})
                    [(a-change "ckopsa/waymark" 1 0)
                     (a-change "ckopsa/waymark" 2 1 :green_head "head-old")])]
      (is (= {:line_why "red"} (get-in m [:changes "change-2"]))))))

(deftest a-red-front-does-not-hold-the-line
  (let [r (rig (atom {1 {:state "red"}}))
        m (marks! r (atom {}) [(a-change "ckopsa/waymark" 1 0)
                               (a-change "ckopsa/waymark" 2 1)])]
    (is (= "change-2" (get-in m [:policies "ckopsa/waymark" :line_front])))
    (is (= [2] (numbers-of r "bench__update_branch"))
        "the change behind the red one is brought up to date")))

(deftest after-the-front-merges-the-next-pass-moves-the-front
  (let [r (rig (atom {}))
        seen (atom {})
        a (a-change "ckopsa/waymark" 1 0)
        b (a-change "ckopsa/waymark" 2 1)
        c (a-change "ckopsa/waymark" 3 2)]
    (marks! r seen [a b c])
    (let [m (marks! r seen [b c])]
      (is (= {:line_front "change-2" :line_front_pr 2
              :line_front_waiting "update" :line_waiting 1}
             (get-in m [:policies "ckopsa/waymark"])))
      (is (= {:line_place 1 :line_why "front"} (get-in m [:changes "change-2"])))
      (is (nil? (get-in m [:changes "change-1"]))
          "the merged change is not in the pass, so its marks are cleared"))))

(deftest the-front-says-what-it-waits-on
  (is (= "update" (bench/front-waits-on {:state "behind"})))
  (is (= "checks" (bench/front-waits-on {:state "waiting"}))
      "a rig that names no waits_on keeps the old reading")
  (is (= "checks" (bench/front-waits-on {:state "waiting" :waits_on "checks"
                                          :pending ["gate"]})))
  (is (= "github" (bench/front-waits-on {:state "waiting" :waits_on "github"}))
      "green checks GitHub has not judged wait on GitHub, not on checks")
  (is (= "update" (bench/front-waits-on {:state "behind" :waits_on "github"})))
  (is (= "merge" (bench/front-waits-on {:refused "merge_refused"
                                         :reason "GitHub has not merged it"}))))

;; ── a front whose check was interrupted (ticket 68423628) ───────────

(defn- reruns-of [{:keys [calls]}]
  (count (filter #(= "bench__rerun" (first %)) @calls)))

(deftest an-interrupted-front-is-rerun-once-and-stays-the-front
  (let [answers (atom {1 {:state "interrupted" :interrupted ["gate"]}})
        r (rig answers)
        seen (atom {})
        a (a-change "ckopsa/waymark" 1 0 :head_branch "bench/one")
        b (a-change "ckopsa/waymark" 2 1)
        m (marks! r seen [a b])]
    (is (= 1 (reruns-of r)) "the rig is asked to run the front's checks again")
    (is (= {:repo "ckopsa/waymark" :branch "bench/one"} (bench/rerun-args a)))
    (is (= {:line_front "change-1" :line_front_pr 1
            :line_front_waiting "checks" :line_waiting 1}
           (get-in m [:policies "ckopsa/waymark"]))
        "it stays the front and waits on its checks, not on merge")
    (is (= {:line_place 1 :line_why "front"} (get-in m [:changes "change-1"])))
    (is (= [] (numbers-of r "bench__update_branch"))
        "nothing behind it is brought forward meanwhile")
    (testing "the rerun is green: the front merges and the line moves on"
      (swap! answers assoc 1 {:state "merged"})
      (let [m (marks! r seen [a b])]
        (is (= 1 (reruns-of r)))
        (is (= "change-2" (get-in m [:policies "ckopsa/waymark" :line_front])))))))

(deftest a-second-interruption-takes-the-change-out-of-the-line
  (let [r (rig (atom {1 {:state "interrupted" :interrupted ["gate" "lint"]}}))
        seen (atom {})
        a (a-change "ckopsa/waymark" 1 0 :head_branch "bench/one")
        b (a-change "ckopsa/waymark" 2 1)]
    (marks! r seen [a b])
    (let [m (marks! r seen [a b])]
      (is (= 1 (reruns-of r)) "one rerun per head")
      (is (= {:line_why "interrupted" :line_reason "gate, lint"}
             (get-in m [:changes "change-1"]))
          "it has no place, and names the checks")
      (is (= "change-2" (get-in m [:policies "ckopsa/waymark" :line_front])))
      (is (= [2] (numbers-of r "bench__update_branch"))
          "the line moves on to the change behind it"))
    (testing "a new head gets its own rerun"
      (let [m (marks! r seen [(assoc-in a [:data :head_sha] "head-1b") b])]
        (is (= 2 (reruns-of r)))
        (is (= "change-1" (get-in m [:policies "ckopsa/waymark" :line_front]))))))
  (testing "a head the forge mirror already ran again is not run a second time"
    (let [r (rig (atom {1 {:state "interrupted" :interrupted ["gate"]}}))
          m (marks! r (atom {}) [(a-change "ckopsa/waymark" 1 0 :rerun_head "head-1")
                                 (a-change "ckopsa/waymark" 2 1)])]
      (is (= 0 (reruns-of r)))
      (is (= {:line_why "interrupted" :line_reason "gate"}
             (get-in m [:changes "change-1"])))))
  (testing "an interrupted change behind the front keeps its place until it is the front"
    (let [r (rig (atom {2 {:state "interrupted" :interrupted ["gate"]}}))
          m (marks! r (atom {}) [(a-change "ckopsa/waymark" 1 0)
                                 (a-change "ckopsa/waymark" 2 1)])]
      (is (= 0 (reruns-of r)))
      (is (= {:line_place 2 :line_why "behind"} (get-in m [:changes "change-2"]))))))

(deftest an-interrupted-answer-on-a-head-read-green-reads-behind
  (let [interrupted {:state "interrupted" :interrupted ["gate"]}]
    (is (= {:state "behind" :rig_state "interrupted" :interrupted ["gate"]}
           (bench/head-answer (a-change "ckopsa/waymark" 2 1 :green_head "head-2")
                              interrupted))
        "like a red carried over: the mirror read this very head green")
    (is (= interrupted
           (bench/head-answer (a-change "ckopsa/waymark" 2 1 :green_head "head-old")
                              interrupted))
        "a head that moved since the green read keeps the rig's word")))

(deftest an-interrupted-front-waits-on-checks
  (is (= "checks" (bench/front-waits-on {:state "interrupted"
                                          :interrupted ["gate"]}))))

(deftest a-waiting-answer-that-names-a-failed-check-is-waiting
  (let [r (rig (atom {1 {:state "waiting" :waits_on "checks"
                         :pending ["gate"] :failed ["lint"]}}))
        m (marks! r (atom {}) [(a-change "ckopsa/waymark" 1 0)
                               (a-change "ckopsa/waymark" 2 1)])]
    (is (= {:line_front "change-1" :line_front_pr 1
            :line_front_waiting "checks" :line_waiting 1}
           (get-in m [:policies "ckopsa/waymark"]))
        "a pending required check keeps the front, whatever is already red")
    (is (= {:line_place 1 :line_why "front"} (get-in m [:changes "change-1"])))
    (is (= 0 (reruns-of r)))
    (is (= [] (numbers-of r "bench__update_branch")))))

;; ── a front that holds its base is not held on update (ticket 5ba28a11)

(deftest a-front-the-mirror-read-up-to-date-is-not-brought-forward
  (testing "a red carried over on a green head that is 0 behind its base"
    (let [r (rig (atom {1 {:state "red"}}))
          seen (atom {})
          a (a-change "ckopsa/waymark" 1 0 :green_head "head-1"
                      :base_compared_head "head-1" :behind_base false)
          m (marks! r seen [a])]
      (is (= [] (numbers-of r "bench__update_branch"))
          "there is nothing to bring forward")
      (is (= "merge" (get-in m [:policies "ckopsa/waymark" :line_front_waiting])))
      (is (= {:line_place 1 :line_why "front"} (get-in m [:changes "change-1"])))
      (marks! r seen [a])
      (is (= [1 1] (numbers-of r "bench__merge"))
          "it is offered for merge each pass")))
  (testing "a rig that says behind itself, on a row the mirror read up to date"
    (let [r (rig (atom {}))
          m (marks! r (atom {}) [(a-change "ckopsa/waymark" 1 0 :base_branch "master"
                                           :base_compared_head "head-1" :behind_base false)])]
      (is (= [] (numbers-of r "bench__update_branch")))
      (is (= "merge" (get-in m [:policies "ckopsa/waymark" :line_front_waiting])))))
  (testing "a compare read at an older head is not trusted"
    (let [r (rig (atom {}))
          m (marks! r (atom {}) [(a-change "ckopsa/waymark" 1 0
                                           :base_compared_head "head-0" :behind_base false)])]
      (is (= [1] (numbers-of r "bench__update_branch")))
      (is (= "update" (get-in m [:policies "ckopsa/waymark" :line_front_waiting]))))))

(deftest an-update-with-nothing-to-bring-forward-asks-the-merge-again
  (let [calls (atom [])
        r {:calls calls
           :ctx {:recorded (atom {})
                 :services
                 {:bench-rpc
                  (fn [_ {tool :name args :arguments}]
                    (swap! calls conj [tool (:number args)])
                    {:structuredContent
                     {:result (if (= tool "bench__merge")
                                {:state "behind"}
                                {:refused "update_refused"
                                 :reason "There are no new commits on the base branch."})}})}}}
        seen (atom {})
        a (a-change "ckopsa/waymark" 1 0)
        m (marks! r seen [a])]
    (is (= [1] (numbers-of r "bench__update_branch")))
    (is (= [1 1] (numbers-of r "bench__merge"))
        "the merge is asked again in the pass that learned it")
    (is (= "merge" (get-in m [:policies "ckopsa/waymark" :line_front_waiting]))
        "the front does not wait on an update that cannot come")
    (let [m (marks! r seen [a])]
      (is (= [1] (numbers-of r "bench__update_branch")) "not updated again")
      (is (= [1 1 1] (numbers-of r "bench__merge")) "and offered again next pass")
      (is (= "merge" (get-in m [:policies "ckopsa/waymark" :line_front_waiting]))))
    (testing "a new head is a new question"
      (marks! r seen [(a-change "ckopsa/waymark" 1 0 :head_sha "head-1b")])
      (is (= [1 1] (numbers-of r "bench__update_branch")))))
  (is (bench/nothing-forward? {:state "up_to_date"}))
  (is (not (bench/nothing-forward? {:state "updated"})))
  (is (not (bench/nothing-forward? {:refused "update_refused" :reason "merge conflict"})))
  (is (= "merge" (bench/front-waits-on {:state "behind"} true)))
  (is (= "update" (bench/front-waits-on {:state "behind"} false))))

(deftest conflicted-draft-and-parked-changes-say-why
  (let [seen (atom {})
        m (marks! (rig (atom {})) seen
                  [(a-change "ckopsa/waymark" 1 0 :mergeable "conflicted")
                   (a-change "ckopsa/waymark" 2 1 :draft true)
                   (a-change "ckopsa/waymark" 3 2 :merge_asked_head "head-3"
                             :merge_refused "not mergeable"
                             :merge_refused_as "not_mergeable")
                   (a-change "ckopsa/waymark" 4 3)])]
    (is (= {"change-1" {:line_why "conflicted"}
            "change-2" {:line_why "draft"}
            "change-3" {:line_why "parked" :line_reason "not mergeable"}
            "change-4" {:line_place 1 :line_why "front"}}
           (:changes m)))
    (is (= 0 (get-in m [:policies "ckopsa/waymark" :line_waiting])))))

(deftest an-unchanged-line-writes-nothing
  (let [data {:line_front "change-1" :line_front_pr 1
              :line_front_waiting "checks" :line_waiting 2
              :line_at "2026-09-28T12:00:00Z"}]
    (is (nil? (bench/moved-marks data (assoc data :line_at "2026-09-28T12:05:00Z")
                                 #{:line_at}))
        "only the time moved, so nothing is written")
    (is (some? (bench/moved-marks data (assoc data :line_waiting 1) #{:line_at}))
        "a moved count is written")
    (is (nil? (bench/moved-marks {} {:line_place nil :line_why nil} #{}))
        "clearing what is already clear writes nothing")))

;; ── a front that waits on a check its head never ran (ticket 498a089e)

(deftest a-waiting-front-missing-a-check-on-a-moved-base-is-updated-once
  (let [r (rig (atom {1 {:state "waiting"}}))
        seen (atom {})
        a (a-change "ckopsa/waymark" 1 0
                    :missing_checks ["gate"] :behind_base true
                    :missing_checks_head "head-1")]
    (pass! r seen [a] {})
    (is (= [1] (numbers-of r "bench__update_branch")))
    (pass! r seen [a] {})
    (is (= [1] (numbers-of r "bench__update_branch"))
        "not again for the same head")))

(deftest a-waiting-front-whose-facts-name-an-older-head-is-left-alone
  (let [r (rig (atom {1 {:state "waiting"}}))]
    (pass! r (atom {}) [(a-change "ckopsa/waymark" 1 0
                                  :missing_checks ["gate"] :behind_base true
                                  :missing_checks_head "head-0")] {})
    (is (= [] (numbers-of r "bench__update_branch"))
        "the facts were read at a head the row no longer names")))

(deftest a-waiting-front-whose-check-is-running-is-left-alone
  (let [r (rig (atom {1 {:state "waiting"}}))]
    (pass! r (atom {}) [(a-change "ckopsa/waymark" 1 0
                                  :missing_checks [] :behind_base true)] {})
    (is (= [] (numbers-of r "bench__update_branch")))))

(deftest a-waiting-front-missing-a-check-but-not-behind-is-left-alone
  (let [r (rig (atom {1 {:state "waiting"}}))]
    (pass! r (atom {}) [(a-change "ckopsa/waymark" 1 0
                                  :missing_checks ["gate"] :behind_base false)] {})
    (is (= [] (numbers-of r "bench__update_branch")))))

;; ── a behind change missing a check past the wait (ticket 7100a73b) ─

;; ── what an ask leaves on the row (ticket a1a133ca) ─────────────────

(defn- no-change-keys? [seen]
  (not-any? #(and (vector? %) (#{:updated :current :rerun} (first %)))
            (keys seen)))

(deftest an-update-is-recorded-on-the-row-and-not-asked-again-after-a-restart
  (let [r (rig (atom {}))
        seen (atom {})]
    (pass! r seen [(a-change "ckopsa/waymark" 1 0)] {})
    (is (= [[:update_asked "change-1" {:updated_head "head-1"}]] @(:asks r))
        "the head goes to the row's door")
    (is (no-change-keys? @seen) "seen holds no change keys"))
  (testing "a restart: a fresh pass and an empty seen read only the row"
    (let [fresh (rig (atom {}))
          seen (atom {})]
      (pass! fresh seen [(a-change "ckopsa/waymark" 1 0 :updated_head "head-1")] {})
      (is (= [] (numbers-of fresh "bench__update_branch"))
          "no second update_branch at the head the row records")
      (pass! fresh seen [(a-change "ckopsa/waymark" 1 0 :updated_head "head-1"
                                   :head_sha "head-1b")] {})
      (is (= [1] (numbers-of fresh "bench__update_branch"))
          "a new head allows one more")
      (is (no-change-keys? @seen)))))

(deftest a-rerun-is-recorded-on-the-row-and-not-asked-again-after-a-restart
  (let [answers (atom {1 {:state "interrupted" :interrupted ["gate"]}})
        one (fn [& {:as extra}]
              (apply a-change "ckopsa/waymark" 1 0 :head_branch "bench/one"
                     (mapcat identity extra)))]
    (let [r (rig answers)
          seen (atom {})]
      (marks! r seen [(one)])
      (is (= 1 (reruns-of r)))
      (is (= [[:rerun_asked "change-1" {:rerun_head "head-1"}]] @(:asks r))
          "the head goes to the row's door")
      (is (no-change-keys? @seen) "seen holds no change keys"))
    (testing "a restart: the row's rerun_head alone keeps a second one away"
      (let [fresh (rig answers)
            seen (atom {})
            m (marks! fresh seen [(one :rerun_head "head-1")])]
        (is (= 0 (reruns-of fresh)))
        (is (= {:line_why "interrupted" :line_reason "gate"}
               (get-in m [:changes "change-1"])))
        (marks! fresh seen [(one :rerun_head "head-1" :head_sha "head-1b")])
        (is (= 1 (reruns-of fresh)) "a new head allows one more")
        (is (no-change-keys? @seen))))))

(defn- update-rig
  "A fake rig whose merge says waiting and whose update_branch answers
  `update`, an hour after t0; `reds` collects what `:update-red!` is told."
  [update reds]
  (let [calls (atom [])]
    {:calls calls
     :ctx {:recorded (atom {})
           :now-fn (constantly (.plusSeconds ^Instant t0 3600))
           :update-red! (fn [id input] (swap! reds conj [id input]) true)
           :services
           {:bench-rpc
            (fn [_ {tool :name args :arguments}]
              (swap! calls conj [tool (:number args)])
              {:structuredContent
               {:result (if (= tool "bench__merge") {:state "waiting"} update)}})}}}))

(defn- missing-behind
  "Change `number`, second in line, missing `gate` on its head behind a
  moved base, held since `since`."
  [number since & {:as extra}]
  (let [head (str "head-" number)]
    (apply a-change "ckopsa/waymark" number number
           (mapcat identity
                   (merge {:head_sha head :missing_checks ["gate"]
                           :missing_checks_head head :behind_base true
                           :head_seen_at since}
                          extra)))))

(deftest a-behind-change-missing-a-check-past-the-wait-is-updated-once
  (let [reds (atom [])
        r (update-rig {:state "updated"} reds)
        seen (atom {})
        front (a-change "ckopsa/waymark" 1 0)
        b (missing-behind 2 "2026-09-28T12:00:00Z")]
    (pass! r seen [front b] {})
    (is (= [2] (numbers-of r "bench__update_branch"))
        "brought up to date though it is not the front")
    (pass! r seen [front b] {})
    (is (= [2] (numbers-of r "bench__update_branch")) "once per head")
    (pass! r seen [front (missing-behind 2 "2026-09-28T12:00:00Z"
                                         :head_sha "head-2b"
                                         :missing_checks_head "head-2b"
                                         :missing_checks [])] {})
    (is (= [2] (numbers-of r "bench__update_branch"))
        "its new head has the check, so it is not brought forward again")
    (is (= [] @reds))))

(deftest a-behind-change-missing-a-check-within-the-wait-is-left-alone
  (let [reds (atom [])
        r (update-rig {:state "updated"} reds)]
    (pass! r (atom {}) [(a-change "ckopsa/waymark" 1 0)
                        (missing-behind 2 "2026-09-28T12:45:00Z")] {})
    (is (= [] (numbers-of r "bench__update_branch"))
        "fifteen minutes is within the default thirty")))

(deftest a-conflicting-update-sends-the-change-failing-naming-the-conflict
  (let [reds (atom [])
        r (update-rig {:refused "merge_conflict"
                       :reason "merge conflict in deps.edn"} reds)
        seen (atom {})
        b (missing-behind 2 "2026-09-28T12:00:00Z")]
    (pass! r seen [(a-change "ckopsa/waymark" 1 0) b] {})
    (is (= [["change-2" ["merge-conflict"] ["merge conflict in deps.edn"]]]
           (mapv (fn [[id input]] [id (:failing_checks input) (:conflicts input)])
                 @reds)))
    (pass! r seen [(a-change "ckopsa/waymark" 1 0) b] {})
    (is (= 1 (count @reds)) "once for the head")))

;; ── a green front that is only behind its base (ticket fc997a92) ─────

(deftest a-lone-green-front-behind-its-base-is-updated-in-one-pass
  (let [r (rig (atom {1 {:state "waiting"}}))
        seen (atom {})
        a (a-change "ckopsa/waymark" 1 0
                    :green_head "head-1" :missing_checks [] :behind_base true)
        m (marks! r seen [a])]
    (is (= [1] (numbers-of r "bench__update_branch"))
        "the rig says waiting, but every check is green and the base moved")
    (is (= {:line_front "change-1" :line_front_pr 1
            :line_front_waiting "update" :line_waiting 0}
           (get-in m [:policies "ckopsa/waymark"]))
        "it waits on the update, not on checks")
    (pass! r seen [a] {})
    (is (= [1] (numbers-of r "bench__update_branch"))
        "not again for the same head")))

(deftest a-waiting-front-read-green-at-an-older-head-is-left-alone
  (let [r (rig (atom {1 {:state "waiting"}}))
        m (marks! r (atom {}) [(a-change "ckopsa/waymark" 1 0
                                         :green_head "head-0" :missing_checks []
                                         :behind_base true)])]
    (is (= [] (numbers-of r "bench__update_branch"))
        "the green read is of a head the row no longer names")
    (is (= "checks" (get-in m [:policies "ckopsa/waymark" :line_front_waiting])))))

(deftest a-waiting-green-front-that-is-not-behind-is-left-alone
  (let [r (rig (atom {1 {:state "waiting"}}))]
    (pass! r (atom {}) [(a-change "ckopsa/waymark" 1 0
                                  :green_head "head-1" :missing_checks []
                                  :behind_base false)] {})
    (is (= [] (numbers-of r "bench__update_branch")))))

;; ── the pass the mirror wakes (ticket 6e190062) ────────────────────────

(defn- at [seconds] (.plusSeconds ^Instant t0 (long seconds)))

(defn- counter [] (let [n (atom 0)] [n (fn [& _] (swap! n inc))]))

(deftest a-green-front-merges-without-the-clock
  (let [board (bench/house-board)
        r (rig (atom {7 {:state "merged"}}))
        seen (atom {})
        merge-pass! (fn [_] (pass! r seen [(a-change "ckopsa/waymark" 7 0)] {}))
        [clocks clock!] (counter)]
    (testing "no wake, no clock: nothing runs"
      (is (= {:clock? false :woken #{}}
             (bench/house-beat! board (at 1) false clock! merge-pass!)))
      (is (= [] (numbers-of r "bench__merge"))))
    (testing "the gate turns green: the next tick merges the front"
      (is (true? (bench/nudge-house! board "ckopsa/waymark"
                                     [:checks "change-7" "head-7" :green])))
      (is (= #{"ckopsa/waymark"}
             (:woken (bench/house-beat! board (at 5) false clock! merge-pass!))))
      (is (= [7] (numbers-of r "bench__merge")))
      (is (zero? @clocks)))))

(deftest two-completions-inside-the-window-cause-one-pass
  (let [board (bench/house-board)
        [passes pass-once!] (counter)
        [_ clock!] (counter)]
    (bench/nudge-house! board "ckopsa/waymark" [:checks "change-7" "head-7" :green])
    (bench/nudge-house! board "ckopsa/waymark" [:checks "change-8" "head-8" :green])
    (bench/house-beat! board (at 5) false clock! pass-once!)
    (bench/house-beat! board (at 10) false clock! pass-once!)
    (is (= 1 @passes))
    (testing "the same observation again wakes nothing"
      (is (false? (bench/nudge-house! board "ckopsa/waymark"
                                      [:checks "change-7" "head-7" :green]))))
    (testing "a completion after the pass waits out the window, and is not lost"
      (bench/nudge-house! board "ckopsa/waymark" [:checks "change-9" "head-9" :green])
      (bench/house-beat! board (at 20) false clock! pass-once!)
      (is (= 1 @passes))
      (bench/house-beat! board (at 35) false clock! pass-once!)
      (is (= 2 @passes)))))

(deftest the-clock-pass-still-runs
  (let [board (bench/house-board)
        [passes pass-once!] (counter)
        [clocks clock!] (counter)]
    (testing "with nothing woken, the clock's beat runs its passes"
      (is (:clock? (bench/house-beat! board (at 300) true clock! pass-once!)))
      (is (= 1 @clocks))
      (is (zero? @passes)))
    (testing "a wake due on a clock beat is answered by the clock alone"
      (bench/nudge-house! board "ckopsa/waymark" [:base "ckopsa/waymark" "base-2"])
      (bench/house-beat! board (at 600) true clock! pass-once!)
      (is (= 2 @clocks))
      (is (zero? @passes)))))

(deftest a-woken-pass-is-told-which-repositories-woke-it
  ;; ticket 26a8d561
  (let [board (bench/house-board)
        asked (atom [])
        [_ clock!] (counter)]
    (bench/nudge-house! board "ckopsa/waymark" [:checks "change-7" "head-7" :green])
    (bench/house-beat! board (at 5) false clock! #(swap! asked conj %))
    (is (= [#{"ckopsa/waymark"}] @asked)
        "the pass is given the woken repository, and no other")))

;; ── the merge train (ticket 47519515, slice 2 of 3deb06ed) ───────────

(def ^:private wm "ckopsa/waymark")

(defn- train-policy [& {:as extra}]
  (update (policy wm) :data merge {:merge_strategy "train" :train_size 4} extra))

(defn- train-rig
  "A fake rig: every merge says behind (green on its own head), every
  update_branch says updated, and each train tool answers what
  `answers` holds under its bare name — train_build merging every pull
  request it is given unless told otherwise."
  [answers]
  (let [calls (atom [])]
    {:calls calls
     :ctx {:now-fn (constantly t0)
           :services
           {:bench-rpc
            (fn [_ {tool :name args :arguments}]
              (swap! calls conj [tool args])
              {:structuredContent
               {:result
                (case tool
                  "bench__merge" {:state "behind"}
                  "bench__update_branch" {:state "updated"}
                  "bench__train_build"
                  (get @answers "train_build"
                       {:branch (:branch args) :base_head "base-0" :head "train-head"
                        :merged (:prs args) :conflicted []})
                  "bench__train_checks"
                  (get @answers "train_checks" {:run_id "77" :head "train-head"})
                  "bench__train_status"
                  (get @answers "train_status" {:state "pending" :head "train-head"})
                  "bench__train_land" (get @answers "train_land" {:landed true})
                  "bench__train_open" (get @answers "train_open" {:refused "unknown_tool"})
                  "bench__train_delete" {:repo wm :branch (:branch args)}
                  {:refused "unknown_tool"})}})}}}))

(defn- args-of [{:keys [calls]} tool]
  (into [] (keep (fn [[t args]] (when (= t tool) args))) @calls))

(defn- train-pass!
  "One pass over `changes` under `pol` → the trains it built or read."
  [{:keys [ctx]} _seen changes pol]
  (let [by-repo {wm pol}
        trains (atom {})]
    (bench/work-lines! ctx
                       (bench/merge-lines changes by-repo (constantly nil))
                       by-repo (atom {}) trains)
    @trains))

(defn- four [] (mapv #(a-change wm % %) [1 2 3 4]))

(deftest four-green-changes-ride-one-train
  (let [r (train-rig (atom {}))
        trains (train-pass! r (atom {}) (conj (four) (a-change wm 5 5)) (train-policy))]
    (is (= [{:repo wm :base "main" :branch "train/ckopsa/waymark/1" :prs [1 2 3 4]}]
           (args-of r "bench__train_build"))
        "the front and the three behind it, in line order, and not a fifth")
    (is (= [{:repo wm :branch "train/ckopsa/waymark/1"}]
           (args-of r "bench__train_checks"))
        "its checks are dispatched once")
    (is (empty? (args-of r "bench__update_branch"))
        "no change is brought up to date on its own")
    (is (= {:branch "train/ckopsa/waymark/1"
            :changes ["change-1" "change-2" "change-3" "change-4"]
            :prs [1 2 3 4] :heads ["head-1" "head-2" "head-3" "head-4"]
            :head "train-head" :base_head "base-0"
            :run_id "77" :started_at t0}
           (get trains wm)))
    (testing "the policy says the front waits on the train"
      (is (= "train" (get-in (bench/line-marks
                              (bench/merge-lines (four) {wm (train-policy)}
                                                 (constantly nil))
                              {} [] trains)
                             [:policies wm :line_front_waiting]))))))

(deftest a-conflicting-change-is-left-in-line
  (let [r (train-rig (atom {"train_build" {:branch "train/ckopsa/waymark/1"
                                           :base_head "base-0" :head "train-head"
                                           :merged [1 2 4] :conflicted [3]}}))
        train (get (train-pass! r (atom {}) (four) (train-policy)) wm)]
    (is (= [1 2 4] (:prs train)))
    (is (= ["change-1" "change-2" "change-4"] (:changes train))
        "the conflicted change does not ride, and stays in the line")))

(deftest the-line-strategy-is-unchanged
  (let [r (train-rig (atom {}))
        trains (train-pass! r (atom {}) (four) (train-policy :merge_strategy "line"))]
    (is (empty? (args-of r "bench__train_build")))
    (is (= [1] (mapv :number (args-of r "bench__update_branch")))
        "only the front is brought up to date")
    (is (empty? trains)))
  (testing "a train of only the front behaves as the line"
    (let [r (train-rig (atom {}))]
      (train-pass! r (atom {}) [(a-change wm 1 1)] (train-policy))
      (is (empty? (args-of r "bench__train_build")))
      (is (= [1] (mapv :number (args-of r "bench__update_branch")))))))

(deftest a-change-whose-merge-after-is-unmet-does-not-ride
  (let [r (train-rig (atom {}))
        changes (four)
        held (bench/held-changes changes {wm (train-policy)}
                                 {"t2" [{:id "394d0602" :state "open"}]})
        offered (remove (set held) changes)]
    (is (= ["change-2"] (mapv :id held)))
    (train-pass! r (atom {}) offered (train-policy))
    (is (= [[1 3 4]] (mapv :prs (args-of r "bench__train_build"))))))

(deftest switching-to-line-mid-train-finishes-the-train-first
  (let [answers (atom {})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)
        line-policy (train-policy :merge_strategy "line" :line_train train)]
    (testing "a pending train holds the line under the restated policy"
      (is (= {wm train} (train-pass! r seen (four) line-policy)))
      (is (= [{:repo wm :run_id "77"}] (args-of r "bench__train_status")))
      (is (empty? (args-of r "bench__update_branch")))
      (is (= 1 (count (args-of r "bench__train_build")))))
    (testing "the finished train is deleted, and the line goes one at a time"
      (swap! answers assoc "train_status" {:state "success" :head "train-head"})
      (is (= {wm nil} (train-pass! r seen (four) line-policy)))
      (is (= [{:repo wm :branch "train/ckopsa/waymark/1"}]
             (args-of r "bench__train_delete")))
      (train-pass! r seen (four) (train-policy :merge_strategy "line"))
      (is (= [1] (mapv :number (args-of r "bench__update_branch"))))
      (is (= 1 (count (args-of r "bench__train_build")))))))

(deftest a-train-with-no-run-yet-is-read-by-its-branch
  (let [answers (atom {"train_checks" {:run_id nil :head "train-head"}})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)]
    (is (nil? (:run_id train)))
    (train-pass! r seen (four) (train-policy :line_train train))
    (is (= [{:repo wm :branch "train/ckopsa/waymark/1" :head "train-head"}]
           (args-of r "bench__train_status")))
    (testing "a train read by its branch lands when it finishes green"
      (swap! answers assoc "train_status" {:state "success" :head "train-head"})
      (is (= {wm nil} (train-pass! r seen (four) (train-policy :line_train train))))
      (is (= 1 (count (args-of r "bench__train_land")))))))

(deftest a-train-names-the-policy-s-test-workflow
  (let [answers (atom {"train_checks" {:run_id nil :head "train-head"}})
        r (train-rig answers)
        seen (atom {})
        pol (train-policy :test {:workflow "tests.yml" :input "only"})
        train (get (train-pass! r seen (four) pol) wm)]
    (is (= [{:repo wm :branch "train/ckopsa/waymark/1" :workflow "tests.yml"}]
           (args-of r "bench__train_checks"))
        "the checks dispatch the policy's workflow")
    (is (= "tests.yml" (:workflow train)) "the train records it")
    (train-pass! r seen (four) (assoc-in pol [:data :line_train] train))
    (is (= [{:repo wm :branch "train/ckopsa/waymark/1" :head "train-head"
             :workflow "tests.yml"}]
           (args-of r "bench__train_status"))
        "with no run yet, the status reads the same workflow")))

(deftest a-standing-train-with-no-line-is-read
  (let [r (train-rig (atom {}))
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)]
    (is (= {wm train} (train-pass! r seen [] (train-policy :line_train train)))
        "a pending train with no change in its line stands, and nothing throws")
    (is (= [{:repo wm :run_id "77"}] (args-of r "bench__train_status")))))

;; ── a finished train (ticket 6033c287, slice 3 of 3deb06ed) ──────────

(defn- finish-pass!
  "One pass over the four with `train` standing under a train policy →
  [the train that stands after it, change id → the pass's answers]."
  [{:keys [ctx]} _seen train]
  (let [by-repo {wm (train-policy :line_train train)}
        answers (atom {})
        trains (atom {})]
    (bench/work-lines! ctx
                       (bench/merge-lines (four) by-repo (constantly nil))
                       by-repo answers trains)
    [(get @trains wm) @answers]))

(deftest a-green-train-lands-whole
  (let [answers (atom {"train_status" {:state "success" :head "train-head"}})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)
        [after merged] (finish-pass! r seen train)]
    (is (= [{:repo wm :base "main" :branch "train/ckopsa/waymark/1"
             :expect_base_head "base-0" :head "train-head"}]
           (args-of r "bench__train_land"))
        "the base is fast-forwarded to the train's head, once")
    (is (nil? after) "no train stands after it")
    (is (= [{:repo wm :branch "train/ckopsa/waymark/1"}]
           (args-of r "bench__train_delete")))
    (is (= (repeat 4 "merged")
           (map #(:state (get merged %)) ["change-1" "change-2" "change-3" "change-4"]))
        "all four merge in one go, and the pass writes them as house merges")
    (is (= 1 (count (args-of r "bench__train_checks"))))))

(deftest a-green-train-with-no-line-lands-whole
  (let [answers (atom {"train_status" {:state "success" :head "train-head"}})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)
        by-repo {wm (train-policy :line_train train)}
        merged (atom {})
        trains (atom {})]
    (bench/work-lines! (:ctx r) {} by-repo merged trains)
    (is (= 1 (count (args-of r "bench__train_land")))
        "a standing train whose repository has no line still lands")
    (is (nil? (get @trains wm)) "no train stands after it")
    (is (= (repeat 4 "merged")
           (map #(:state (get @merged %)) ["change-1" "change-2" "change-3" "change-4"]))
        "the pass writes its riders as house merges")))

(deftest a-base-moved-outside-the-house-builds-the-train-again
  (let [answers (atom {"train_status" {:state "success" :head "train-head"}
                       "train_land" {:refused "base_moved"}})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)
        [after merged] (finish-pass! r seen train)]
    (is (nil? after))
    (is (= [{:repo wm :branch "train/ckopsa/waymark/1"}]
           (args-of r "bench__train_delete")))
    (is (not-any? #(= "merged" (:state %)) (vals merged)) "nothing merged")
    (train-pass! r seen (four) (train-policy))
    (is (= [[1 2 3 4] [1 2 3 4]] (mapv :prs (args-of r "bench__train_build")))
        "the next pass builds it again on the moved base")))

(deftest a-waiting-landing-keeps-the-train-standing
  (let [answers (atom {"train_status" {:state "success" :head "train-head"}
                       "train_land" {:state "waiting" :number 300
                                     :pending ["gate"]}})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)
        [after merged] (finish-pass! r seen train)]
    (is (= (assoc train :pr 300) after)
        "the train stands unchanged, its pull request noted")
    (is (empty? (args-of r "bench__train_delete")) "the train is not deleted")
    (is (not-any? #(= "merged" (:state %)) (vals merged)) "nothing merged")
    (swap! answers assoc "train_land" {:landed true :number 300 :sha "s"})
    (let [[again merged] (finish-pass! r seen after)]
      (is (nil? again))
      (is (= 2 (count (args-of r "bench__train_land"))) "the next pass asks again")
      (is (= (repeat 4 "merged")
             (map #(:state (get merged %)) ["change-1" "change-2" "change-3" "change-4"]))))))

(defn- marking
  "The rig with a `:train-done!` hand that keeps each change a train
  marked done in `done`."
  [r done]
  (update r :ctx assoc :train-done! (fn [id _why] (swap! done conj id))))

(defn- marked
  "The four as their rows read after the train marked `done`: each one
  names its head as `train_done_head`."
  [done]
  (mapv #(cond-> %
           (contains? done (:id %))
           (assoc-in [:data :train_done_head] (get-in % [:data :head_sha])))
        (four)))

(defn- train-keys [seen]
  (filter #(and (vector? %) (= :train-done (first %))) (keys seen)))

(deftest a-refused-merge-goes-one-at-a-time
  (let [answers (atom {"train_status" {:state "success" :head "train-head"}
                       "train_land" {:refused "merge_refused"}})
        done (atom #{})
        r (marking (train-rig answers) done)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)
        [after merged] (finish-pass! r seen train)]
    (is (nil? after))
    (is (= [{:repo wm :branch "train/ckopsa/waymark/1"}]
           (args-of r "bench__train_delete")))
    (is (not-any? #(= "merged" (:state %)) (vals merged)) "nothing merged")
    (is (= #{"change-1" "change-2" "change-3" "change-4"} @done)
        "every change the train carried walks its done door")
    (is (empty? (train-keys @seen)) "`seen` holds no train mark")
    (testing "a restart after the finished train builds nothing again"
      (train-pass! r (atom {}) (marked @done) (train-policy))
      (is (= 1 (count (args-of r "bench__train_build"))) "no train is built again"))
    (testing "a line no mark names at its head trains again"
      (train-pass! r (atom {}) (four) (train-policy))
      (is (= 2 (count (args-of r "bench__train_build")))))))

(deftest a-red-train-bisects-to-its-one-red-change
  (let [answers (atom {"train_status" {:state "failure" :head "train-head"}})
        reds (atom [])
        r (update (train-rig answers) :ctx assoc
                  :train-red! (fn [id _why] (swap! reds conj id)))
        seen (atom {})
        t1 (get (train-pass! r seen (four) (train-policy)) wm)
        [t2] (finish-pass! r seen t1)
        [t3] (finish-pass! r seen t2)
        [t4] (finish-pass! r seen t3)]
    (is (= [[1 2 3 4] [1 2] [1]] (mapv :prs (args-of r "bench__train_build")))
        "the front half rides again, then its front half")
    (is (= 3 (count (args-of r "bench__train_checks")))
        "two more runs after the train's own")
    (is (= [2 3] [(:tries t2) (:tries t3)]))
    (is (= ["change-1"] @reds) "only the change red alone is marked red")
    (is (nil? t4))
    (is (empty? (args-of r "bench__train_land")))
    (testing "the others go back to the line, and the next train takes them"
      (is (not (get @seen [:train-done wm])))
      (train-pass! r seen (rest (four)) (train-policy))
      (is (= [2 3 4] (:prs (last (args-of r "bench__train_build"))))))))

(deftest a-train-red-on-a-head-that-moved-marks-nothing-red
  ;; ticket e23f8842: the train was built on head-1, and change-1 was
  ;; pushed again while it ran
  (doseq [[now reds-want] [["head-1b" []] ["head-1" ["change-1"]]]]
    (let [answers (atom {"train_status" {:state "failure" :head "train-head"}})
          reds (atom [])
          r (update (train-rig answers) :ctx assoc
                    :train-red! (fn [id _why] (swap! reds conj id))
                    :head-of (fn [id] (when (= "change-1" id) now)))
          seen (atom {})
          t1 (get (train-pass! r seen (four) (train-policy)) wm)
          [t2] (finish-pass! r seen t1)
          [t3] (finish-pass! r seen t2)
          [t4] (finish-pass! r seen t3)]
      (is (= reds-want @reds) (str "the change is at " now))
      (is (nil? t4) "no train stands either way")
      (is (not (get @seen [:train-done wm])) "and the next train takes it"))))

(deftest a-cancelled-train-runs-once-more-then-the-line-goes-one-at-a-time
  (let [answers (atom {"train_status" {:state "cancelled" :head "train-head"}})
        done (atom #{})
        r (marking (train-rig answers) done)
        seen (atom {})
        t1 (get (train-pass! r seen (four) (train-policy)) wm)
        [t2] (finish-pass! r seen t1)]
    (is (true? (:retried t2)))
    (is (= 2 (count (args-of r "bench__train_checks"))) "its checks run once more")
    (is (= 1 (count (args-of r "bench__train_build"))) "on the same train")
    (let [[t3] (finish-pass! r seen t2)]
      (is (nil? t3))
      (is (= [{:repo wm :branch "train/ckopsa/waymark/1"}]
             (args-of r "bench__train_delete")))
      (is (empty? (train-keys @seen)) "`seen` holds no train mark")
      (train-pass! r (atom {}) (marked @done) (train-policy))
      (is (= 1 (count (args-of r "bench__train_build"))) "no train is built again")
      (is (= [1] (mapv :number (args-of r "bench__update_branch")))))))

(deftest a-retried-train-read-by-its-branch-skips-the-cancelled-run
  (let [answers (atom {"train_checks" {:run_id nil :head "train-head"}
                       "train_status" {:state "cancelled" :run_id "77" :head "train-head"}})
        r (train-rig answers)
        seen (atom {})
        t1 (get (train-pass! r seen (four) (train-policy)) wm)
        [t2] (finish-pass! r seen t1)]
    (is (true? (:retried t2)))
    (is (= "77" (:stale_run_id t2)) "the cancelled run stays on the train")
    (is (nil? (:run_id t2)) "the retry showed no run yet")
    (testing "the rig skips the old cancelled run, so the retry reads as not shown yet"
      (swap! answers assoc "train_status" {:state "pending" :run_id nil :head "train-head"})
      (let [[t3] (finish-pass! r seen t2)]
        (is (= t2 t3))
        (is (= {:repo wm :branch "train/ckopsa/waymark/1" :head "train-head"
                :skip_run_id "77"}
               (last (args-of r "bench__train_status")))
            "the rig is told to skip the cancelled run")
        (is (not (get @seen [:train-done wm])) "the line is not sent one at a time")
        (is (empty? (args-of r "bench__train_delete")))))
    (testing "the retry's own run is read, and lands green"
      (swap! answers assoc "train_status" {:state "success" :run_id "78" :head "train-head"})
      (let [[t4] (finish-pass! r seen t2)]
        (is (nil? t4))
        (is (= 1 (count (args-of r "bench__train_land"))))))))

;; ── the pull request's own run is the train's check (e2d485c2) ──────

(deftest a-train-whose-pull-request-opens-runs-its-checks-once
  (let [answers (atom {"train_open" {:number 397 :head "train-head"}})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)]
    (is (= [{:repo wm :base "main" :branch "train/ckopsa/waymark/1" :head "train-head"}]
           (args-of r "bench__train_open"))
        "the train's pull request opens right after the build")
    (is (empty? (args-of r "bench__train_checks")) "no second run is dispatched")
    (is (= 397 (:pr train)))
    (is (true? (:pr_run train)))
    (is (nil? (:run_id train)))
    (testing "the pull request's run is read by branch and head"
      (let [[after] (finish-pass! r seen train)]
        (is (= train after) "pending, it stands")
        (is (= [{:repo wm :branch "train/ckopsa/waymark/1" :head "train-head"}]
               (args-of r "bench__train_status")))))
    (testing "green, it lands, and its checks never ran twice"
      (swap! answers assoc "train_status" {:state "success" :run_id "90" :head "train-head"})
      (let [[after merged] (finish-pass! r seen train)]
        (is (nil? after))
        (is (= 1 (count (args-of r "bench__train_land"))))
        (is (= (repeat 4 "merged")
               (map #(:state (get merged %)) ["change-1" "change-2" "change-3" "change-4"])))
        (is (empty? (args-of r "bench__train_checks")))))))

(deftest a-red-pull-request-train-bisects-without-dispatching
  (let [answers (atom {"train_open" {:number 397 :head "train-head"}
                       "train_status" {:state "failure" :head "train-head"}})
        r (train-rig answers)
        seen (atom {})
        t1 (get (train-pass! r seen (four) (train-policy)) wm)
        [t2] (finish-pass! r seen t1)]
    (is (= [[1 2 3 4] [1 2]] (mapv :prs (args-of r "bench__train_build"))))
    (is (= 2 (count (args-of r "bench__train_open"))) "the half's pull request opens too")
    (is (true? (:pr_run t2)))
    (is (empty? (args-of r "bench__train_checks")))))

(deftest a-pull-request-that-shows-no-run-gets-its-checks-dispatched
  (let [answers (atom {"train_open" {:number 397 :head "train-head"}})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)
        late (assoc-in r [:ctx :now-fn]
                       (constantly (.plusSeconds ^Instant t0
                                                 (long bench/pr-run-grace-seconds))))]
    (testing "inside the grace, it waits"
      (let [[after] (finish-pass! r seen train)]
        (is (= train after))
        (is (empty? (args-of r "bench__train_checks")))))
    (testing "past it, the checks are dispatched once"
      (let [[after] (finish-pass! late seen train)]
        (is (= [{:repo wm :branch "train/ckopsa/waymark/1"}]
               (args-of r "bench__train_checks")))
        (is (= "77" (:run_id after)))
        (is (nil? (:pr_run after)))
        (is (= 397 (:pr after)))
        (let [[again] (finish-pass! late seen after)]
          (is (= after again))
          (is (= 1 (count (args-of r "bench__train_checks"))) "not twice"))))))

;; ── a change held only for being behind its base (ticket 6add57e9) ──

(defn- held-behind
  "Change `number`, outside every line, whose head the mirror read green
  and held, `behind` its base, on its last round."
  [number behind & {:as extra}]
  (let [head (str "head-" number)]
    (apply a-change "ckopsa/waymark" number number
           (mapcat identity
                   (merge {:green_head head :base_compared_head head
                           :behind_base behind :rounds 6}
                          extra)))))

(defn- held-pass!
  [{:keys [ctx]} _seen conflicts changes]
  (bench/bring-held-forward!
   (assoc ctx :held-conflict! (fn [id cs] (swap! conflicts conj [id cs]) true))
   changes))

(deftest a-held-behind-green-change-is-brought-up-to-date-once
  (let [reds (atom [])
        conflicts (atom [])
        r (update-rig {:state "updated"} reds)
        seen (atom {})
        c (held-behind 1526 true)]
    (is (= 1 (held-pass! r seen conflicts [c])))
    (is (= [1526] (numbers-of r "bench__update_branch")))
    (held-pass! r seen conflicts [c])
    (is (= [1526] (numbers-of r "bench__update_branch")) "once per head")
    (is (= [] @reds) "it spends no round: nothing goes back to its seat")
    (is (= [] @conflicts))
    (is (= 6 (get-in c [:data :rounds])))))

(deftest a-conflicting-held-change-records-its-conflict-and-stays-held
  (let [reds (atom [])
        conflicts (atom [])
        r (update-rig {:refused "merge_conflict"
                       :reason "merge conflict between base and head"
                       :conflicts ["a.clj"]}
                      reds)
        seen (atom {})
        c (held-behind 7 true)]
    (held-pass! r seen conflicts [c])
    (is (= [7] (numbers-of r "bench__update_branch")))
    (is (= [["change-7" ["a.clj"]]] @conflicts))
    (is (= [] @reds) "it stays held, not failing")
    (held-pass! r seen conflicts [c])
    (is (= [7] (numbers-of r "bench__update_branch")) "asked once")))

(deftest a-change-not-held-behind-is-not-brought-forward
  (let [reds (atom [])
        conflicts (atom [])
        r (update-rig {:state "updated"} reds)]
    (held-pass! r (atom {}) conflicts
                [(held-behind 1 false)
                 (held-behind 2 true :green_head "head-old")
                 (held-behind 3 true :mergeable "conflicted")
                 (held-behind 4 true :draft true)])
    (is (= [] (numbers-of r "bench__update_branch")))))

;; ── a filmed org_plan's change waits for go_live (ticket 8288529f) ──

(deftest a-simulated-plans-change-follows-its-base-only-as-the-plan-says
  (let [plan (fn [state & [follow]]
               {:state state :data (cond-> {} follow (assoc :base_follow follow))})
        pass! (fn [p]
                (let [r (update-rig {:state "updated"} (atom []))]
                  (held-pass! (assoc-in r [:ctx :plan-of] {"change-9" p})
                              (atom {}) (atom []) [(held-behind 9 true)])
                  (numbers-of r "bench__update_branch")))]
    (testing "under the default, a simulated plan's change is left behind"
      (is (= [] (pass! (plan :simulated))))
      (is (= [] (pass! (plan :simulated "at_go_live")))))
    (testing "under always, it is brought forward"
      (is (= [9] (pass! (plan :simulated "always")))))
    (testing "under when_org_dir_changes, it is brought forward too (ticket ce8a4e17)"
      (is (= [9] (pass! (plan :simulated "when_org_dir_changes")))))
    (testing "once the plan is going_live, the default brings it forward too"
      (is (= [9] (pass! (plan :going_live))))
      (is (= [9] (pass! (plan :going_live "at_go_live")))))
    (testing "a change no plan names is brought forward as before"
      (is (= [9] (pass! nil))))))

;; ── a plan waiting for its film keeps its head (ticket eaa316cc) ────

(deftest a-proposed-plans-change-waits-for-its-take-to-land
  (let [plan (fn [state & {:as data}] {:state state :data (or data {})})
        pass! (fn [p]
                (let [r (update-rig {:state "updated"} (atom []))]
                  (held-pass! (assoc-in r [:ctx :plan-of] {"change-19" p})
                              (atom {}) (atom []) [(held-behind 19 true)])
                  (numbers-of r "bench__update_branch")))]
    (testing "a proposed or filming plan with no take at its head is skipped"
      (is (= [] (pass! (plan :proposed))))
      (is (= [] (pass! (plan :filming))))
      (is (= [] (pass! (plan :proposed :base_follow "always"))))
      (is (= [] (pass! (plan :filming :take_sha "head-19"))) "handed in, not landed")
      (is (= [] (pass! (plan :filming :take_sha "head-18"
                             :take_landed_at "2026-10-22T18:00:00Z")))
          "landed at another head"))
    (testing "the same plan after a landed take at its head is merged"
      (is (= [19] (pass! (plan :proposed :take_sha "head-19"
                               :take_landed_at "2026-10-22T18:00:00Z"))))
      (is (= [19] (pass! (plan :filming :take_sha "head-19"
                               :take_landed_at "2026-10-22T18:00:00Z")))))
    (testing "a plan back in drafting is merged"
      (is (= [19] (pass! (plan :drafting)))))))

;; ── a train per lane (ticket 55d584f2) ──────────────────────────────

(defn- lane-policy [& {:as extra}]
  (update (train-policy) :data merge
          {:lanes [{:name "docs" :paths ["docs/**"]}]} extra))

(defn- in-lane [lane n]
  (a-change wm n n :touched_paths [(if (= "docs" lane)
                                     (str "docs/note-" n ".md")
                                     (str "factory10/src/f" n ".clj"))]))

(defn- two-lanes []
  [(in-lane "engine" 1) (in-lane "engine" 2) (in-lane "docs" 3) (in-lane "docs" 4)])

(defn- lane-rig
  "`train-rig`, but each train's run is named for its branch, and
  train_status answers what `statuses` holds under that run."
  [statuses]
  (let [r (train-rig (atom {}))
        rpc (get-in r [:ctx :services :bench-rpc])
        answer (fn [tool args result]
                 (swap! (:calls r) conj [tool args])
                 {:structuredContent {:result result}})]
    (assoc-in r [:ctx :services :bench-rpc]
              (fn [svc {tool :name args :arguments :as call}]
                (case tool
                  "bench__train_checks"
                  (answer tool args {:run_id (:branch args) :head "train-head"})
                  "bench__train_status"
                  (answer tool args (get statuses (:run_id args)
                                         {:state "pending" :head "train-head"}))
                  (rpc svc call))))))

(defn- standing
  "The policy as the pass that filled `trains` leaves it."
  [pol trains]
  (update pol :data merge (get (bench/train-marks {wm pol} trains) wm)))

(deftest two-lanes-build-two-trains-that-land-on-their-own
  (let [r (lane-rig {"train/ckopsa/waymark/3" {:state "success" :head "train-head"}})
        seen (atom {})
        pol (lane-policy)
        t1 (train-pass! r seen (two-lanes) pol)]
    (is (= [[1 2] [3 4]] (mapv :prs (args-of r "bench__train_build")))
        "each lane's green changes ride their own train, and no train mixes lanes")
    (is (= #{wm [wm "docs"]} (set (keys t1))))
    (is (= "docs" (:lane (get t1 [wm "docs"]))))
    (is (nil? (:lane (get t1 wm))) "the engine lane's train stands as one always stood")
    (let [t2 (train-pass! r seen (two-lanes) (standing pol t1))]
      (is (= ["train/ckopsa/waymark/3"] (mapv :branch (args-of r "bench__train_land")))
          "the docs train lands while the engine train still runs")
      (is (nil? (get t2 [wm "docs"])))
      (is (= (get t1 wm) (get t2 wm)))
      (is (= {wm {:line_train (get t1 wm) :lane_trains nil}}
             (bench/train-marks {wm (standing pol t1)} t2))
          "the landed lane leaves the policy, and the engine train stays"))))

(deftest a-red-train-in-one-lane-does-not-hold-the-other
  (let [r (lane-rig {"train/ckopsa/waymark/1" {:state "success" :head "train-head"}
                     "train/ckopsa/waymark/3" {:state "failure" :head "train-head"}})
        seen (atom {})
        pol (lane-policy)
        t1 (train-pass! r seen (two-lanes) pol)
        t2 (train-pass! r seen (two-lanes) (standing pol t1))]
    (is (= ["train/ckopsa/waymark/1"] (mapv :branch (args-of r "bench__train_land")))
        "the engine train lands though the docs train went red")
    (is (nil? (get t2 wm)))
    (is (= [3] (:prs (last (args-of r "bench__train_build"))))
        "the docs train looks for its red inside its lane")
    (is (= "docs" (:lane (get t2 [wm "docs"]))))
    (is (not (get @seen [:train-done wm])))))

(deftest a-train-s-dispatch-names-its-lane
  (let [r (lane-rig {})
        _ (train-pass! r (atom {}) (two-lanes) (lane-policy))
        sent (into {} (map (juxt :branch identity)) (args-of r "bench__train_checks"))]
    (is (= "docs" (:lane (get sent "train/ckopsa/waymark/3")))
        "a docs train's dispatch carries lane=docs")
    (is (contains? #{nil "engine"} (:lane (get sent "train/ckopsa/waymark/1")))
        "an engine train's dispatch carries none or engine")))

(deftest with-no-lanes-one-train-carries-every-path
  (let [r (train-rig (atom {}))
        trains (train-pass! r (atom {}) (two-lanes) (train-policy))]
    (is (= [[1 2 3 4]] (mapv :prs (args-of r "bench__train_build"))))
    (is (= [wm] (keys trains)))))

(deftest the-line-place-counts-within-the-lane
  (let [pol (lane-policy)
        m (bench/line-marks (bench/merge-lines (two-lanes) {wm pol} (constantly nil))
                            {} [] {} #(bench/lane-of % pol))]
    (is (= [1 2 1 2]
           (mapv #(get-in m [:changes (str "change-" %) :line_place]) [1 2 3 4])))
    (is (= 3 (get-in m [:policies wm :line_waiting]))
        "the line itself still counts every change")))
