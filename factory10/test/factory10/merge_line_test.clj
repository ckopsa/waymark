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

;; ── the pass the mirror wakes (ticket 6e190062) ────────────────────────

(defn- at [seconds] (.plusSeconds ^Instant t0 (long seconds)))

(defn- counter [] (let [n (atom 0)] [n #(swap! n inc)]))

(deftest a-green-front-merges-without-the-clock
  (let [board (bench/house-board)
        r (rig (atom {7 {:state "merged"}}))
        seen (atom {})
        merge-pass! #(pass! r seen [(a-change "ckopsa/waymark" 7 0)] {})
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
  [{:keys [ctx]} seen changes pol]
  (let [by-repo {wm pol}
        trains (atom {})]
    (bench/work-lines! ctx seen
                       (bench/merge-lines changes by-repo (constantly nil) @seen)
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
            :prs [1 2 3 4] :head "train-head" :base_head "base-0"
            :run_id "77" :started_at t0}
           (get trains wm)))
    (testing "the policy says the front waits on the train"
      (is (= "train" (get-in (bench/line-marks
                              (bench/merge-lines (four) {wm (train-policy)}
                                                 (constantly nil) {})
                              {} {} [] trains)
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
  [{:keys [ctx]} seen train]
  (let [by-repo {wm (train-policy :line_train train)}
        answers (atom {})
        trains (atom {})]
    (bench/work-lines! ctx seen
                       (bench/merge-lines (four) by-repo (constantly nil) @seen)
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
    (bench/work-lines! (:ctx r) seen {} by-repo merged trains)
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

(deftest a-refused-merge-goes-one-at-a-time
  (let [answers (atom {"train_status" {:state "success" :head "train-head"}
                       "train_land" {:refused "merge_refused"}})
        r (train-rig answers)
        seen (atom {})
        train (get (train-pass! r seen (four) (train-policy)) wm)
        [after merged] (finish-pass! r seen train)]
    (is (nil? after))
    (is (= [{:repo wm :branch "train/ckopsa/waymark/1"}]
           (args-of r "bench__train_delete")))
    (is (not-any? #(= "merged" (:state %)) (vals merged)) "nothing merged")
    (train-pass! r seen (four) (train-policy))
    (is (= 1 (count (args-of r "bench__train_build"))) "no train is built again")))

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

(deftest a-cancelled-train-runs-once-more-then-the-line-goes-one-at-a-time
  (let [answers (atom {"train_status" {:state "cancelled" :head "train-head"}})
        r (train-rig answers)
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
      (train-pass! r seen (four) (train-policy))
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
