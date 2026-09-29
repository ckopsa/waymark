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

(defn- rig
  "A fake rig: `answers` is number -> what its merge says (behind when
  unnamed), and every update_branch says updated."
  [answers]
  (let [calls (atom [])]
    {:calls calls
     :ctx {:services
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
  [{:keys [ctx]} seen changes priorities]
  (bench/work-lines! ctx seen
                     (bench/merge-lines changes by-repo
                                        #(get priorities (bench/born-ticket %))
                                        @seen)
                     by-repo))

(deftest the-line-is-ticket-priority-then-age
  (let [old-low (a-change "ckopsa/waymark" 1 0)
        new-high (a-change "ckopsa/waymark" 2 30)
        old-high (a-change "ckopsa/waymark" 3 10)
        lines (bench/merge-lines [old-low new-high old-high] by-repo
                                 #(get {"t1" 2 "t2" 0 "t3" 0}
                                       (bench/born-ticket %))
                                 {})]
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
        seen (atom {"change-1" "head-1"})]
    (pass! r seen [(a-change "ckopsa/waymark" 1 0)
                   (a-change "ckopsa/waymark" 2 1)] {})
    (is (= [2] (numbers-of r "bench__merge")))
    (is (= [2] (numbers-of r "bench__update_branch")))))

;; ── the line, written on the rows (ticket b85aded5) ─────────────────

(defn- marks!
  "One pass, and what it would write on the rows."
  [{:keys [ctx]} seen changes]
  (let [lines (bench/merge-lines changes by-repo (constantly nil) @seen)
        answers (atom {})]
    (bench/work-lines! ctx seen lines by-repo answers)
    (bench/line-marks lines @answers @seen
                      (bench/parked-changes changes by-repo @seen))))

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
  (is (= "checks" (bench/front-waits-on {:state "waiting"})))
  (is (= "merge" (bench/front-waits-on {:refused "merge_refused"
                                         :reason "GitHub has not merged it"}))))

(deftest conflicted-draft-and-parked-changes-say-why
  (let [seen (atom {"change-3" "head-3" [:parked-why "change-3"] "not mergeable"})
        m (marks! (rig (atom {})) seen
                  [(a-change "ckopsa/waymark" 1 0 :mergeable "conflicted")
                   (a-change "ckopsa/waymark" 2 1 :draft true)
                   (a-change "ckopsa/waymark" 3 2)
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
                    :missing_checks ["gate"] :behind_base true)]
    (pass! r seen [a] {})
    (is (= [1] (numbers-of r "bench__update_branch")))
    (pass! r seen [a] {})
    (is (= [1] (numbers-of r "bench__update_branch"))
        "not again for the same head")))

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
