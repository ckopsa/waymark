(ns factory10.red-base-test
  "A red base opens one groomed fix ticket, and a green base completes
  it (ticket ade81ae9). Judged over the REAL GitHub source and an
  in-memory GitHub, with the engine over the in-memory store, as
  factory10.github-source-test is.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private repo "ckopsa/waymark-bench")

(def ^:private a-person (t/principal {:id "colton" :display "Colton"}))

(def ^:private head-1 "1111111111111111111111111111111111111111")
(def ^:private head-2 "2222222222222222222222222222222222222222")
(def ^:private head-3 "3333333333333333333333333333333333333333")

(defn- a-check [id sha conclusion]
  {:id id :name "gate" :status "completed" :conclusion conclusion
   :head_sha sha
   :html_url (str "https://github.com/" repo "/runs/" id)
   :details_url (str "https://github.com/" repo "/actions/runs/900/job/" id)})

(defn- world
  "A fake GitHub, the real source over it, and an engine holding one
  active policy for `repo` that requires `gate`."
  []
  (let [state (gh/fake-state)
        eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})]
    (inv/create! eng :repo_policy {:repository repo :required_checks ["gate"]}
                 {:principal a-person})
    {:state state :engine eng :source (gh/fake-source state {:repos repo})}))

(defn- pass! [{:keys [source engine]}]
  (forge/pass! {:source source :engine engine :log-fn (fn [& _] nil)}))

(defn- rows [eng kind where]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/query-rows st tx kind where
                                                 {:limit 50})))))

(defn- tickets [eng] (rows eng :ticket {:repo repo}))

(defn- policy-of [eng] (first (rows eng :repo_policy {:repository repo})))

(defn- state-of [row] (some-> (:state row) name keyword))

(defn- head-at!
  "The base's head moves to `sha`, and `gate` finishes on it."
  [{:keys [state]} sha id conclusion]
  (gh/seed-branch! state repo "main" sha)
  (gh/seed-check! state repo sha (a-check id sha conclusion))
  (gh/seed-log! state (str id) "compiling\nFAIL in (the-merge-clash)\nexpected 1, got 2"))

(deftest a-base-red-for-two-passes-opens-one-groomed-ticket
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 501 "failure")

    (testing "one red pass opens nothing: a rerun may still turn it"
      (is (= 0 (:base-opened (pass! w))))
      (is (empty? (tickets engine)))
      (is (= "red" (str (get-in (policy-of engine) [:data :base_state]))))
      (is (= head-1 (get-in (policy-of engine) [:data :base_head]))))

    (testing "the second red pass opens one groomed p0 bug"
      (is (= 1 (:base-opened (pass! w))))
      (let [[tk & more] (tickets engine)
            detail (str (get-in tk [:data :detail]))]
        (is (empty? more))
        (is (= :open (state-of tk)) "groomed, so the code seat wakes")
        (is (= 0 (get-in tk [:data :priority])))
        (is (= "bug" (str (get-in tk [:data :type]))))
        (is (= "main is red: gate" (get-in tk [:data :title])))
        (is (str/includes? detail head-1))
        (is (str/includes? detail (str "https://github.com/" repo "/runs/501")))
        (is (str/includes? detail "FAIL in (the-merge-clash)")
            "the failed step's log tail rides in the body")
        (is (= [(str head-1 ": gate")] (get-in tk [:data :red_heads])))
        (is (= (str (:id tk))
               (get-in (policy-of engine) [:data :base_ticket])))))

    (testing "a third red pass on the same head opens nothing more"
      (is (= 0 (:base-opened (pass! w))))
      (is (= 1 (count (tickets engine)))))

    (testing "a new red head is noted on the same ticket, not a second one"
      (head-at! w head-2 502 "failure")
      (let [census (pass! w)]
        (is (= 0 (:base-opened census)))
        (is (= 1 (:base-noted census))))
      (let [[tk & more] (tickets engine)]
        (is (empty? more))
        (is (= [(str head-1 ": gate") (str head-2 ": gate")]
               (get-in tk [:data :red_heads])))))

    (testing "a green head completes it with the engine's own hand"
      (head-at! w head-3 503 "success")
      (is (= 1 (:base-closed (pass! w))))
      (let [tk (first (tickets engine))]
        (is (= :done (state-of tk)))
        (is (= (str "main is green again at " head-3 ".")
               (get-in tk [:data :close_reason]))))
      (is (= "green" (str (get-in (policy-of engine) [:data :base_state])))))

    (testing "a green pass with nothing new writes nothing"
      (let [before (:version (policy-of engine))]
        (is (= 0 (:base-closed (pass! w))))
        (is (= before (:version (policy-of engine))))))))

(defn- benched-world
  "`world`, with a bench that answers `status` for every branch."
  [status]
  (let [state (gh/fake-state)
        eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))
                            :services {:bench-rpc
                                       (fn [_method _params]
                                         {:structuredContent {:result status}})}})]
    (inv/create! eng :repo_policy {:repository repo :required_checks ["gate"]}
                 {:principal a-person})
    {:state state :engine eng :source (gh/fake-source state {:repos repo})}))

(defn- red-ticket-with-a-change!
  "The base is red for two passes, and a seat's change is open beside
  the ticket that opened. → the change's id."
  [{:keys [engine] :as w}]
  (head-at! w head-1 511 "failure")
  (pass! w)
  (pass! w)
  (let [born (str "ticket:" (:id (first (tickets engine))))]
    (str (:id (:row (inv/create! engine :change
                                 {:change_id born
                                  :repository repo
                                  :head_branch "bench/the-red-base"
                                  :born_from born}
                                 {:principal mirror/source-principal}))))))

(defn- change-of [eng id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx :change id {})))))

(deftest a-green-base-keeps-the-ticket-whose-change-holds-work
  ;; ticket d5000a1c: the red was a flake, and the seat's edit was on
  ;; the bench branch when the next merge's run went green
  (let [{:keys [engine] :as w} (benched-world {:dirty 1 :ahead 0})
        change-id (red-ticket-with-a-change! w)]
    (head-at! w head-2 512 "success")
    (is (= 0 (:base-closed (pass! w))) "the ticket is not ended")
    (let [tk (first (tickets engine))
          note (str (get-in tk [:data :green_note]))]
      (is (= :open (state-of tk)))
      (is (str/includes? note (str "main is green again at " head-2)))
      (is (str/includes? note "bench/the-red-base"))
      (is (= :open (state-of (change-of engine change-id)))
          "and its change is not superseded")
      (testing "the next green pass writes the sentence no second time"
        (pass! w)
        (is (= (:version tk) (:version (first (tickets engine)))))))))

(deftest a-green-base-keeps-the-ticket-whose-change-has-commits
  (let [{:keys [engine] :as w} (benched-world {:dirty 0 :ahead 2})
        change-id (red-ticket-with-a-change! w)]
    (head-at! w head-2 513 "success")
    (is (= 0 (:base-closed (pass! w))))
    (is (= :open (state-of (first (tickets engine)))))
    (is (= :open (state-of (change-of engine change-id))))))

(deftest a-green-base-keeps-the-draft-ticket-whose-stuck-change-holds-work
  ;; ticket e666bd6a: the seat stalled its change, which sent the ticket
  ;; to draft, and its edit was still on the bench branch
  (let [{:keys [engine] :as w} (benched-world {:dirty 1 :ahead 0})
        change-id (red-ticket-with-a-change! w)]
    (inv/invoke! engine :change change-id :stall
                 {:why "The red test passes here, so I cannot fix it."}
                 {:principal a-person
                  :if-match (inv/etag :change change-id
                                      (:version (change-of engine change-id)))})
    (is (= :stuck (state-of (change-of engine change-id))))
    (is (= :draft (state-of (first (tickets engine)))))
    (head-at! w head-2 515 "success")
    (is (= 0 (:base-closed (pass! w))) "the ticket is not ended")
    (let [tk (first (tickets engine))
          note (str (get-in tk [:data :green_note]))]
      (is (= :draft (state-of tk)))
      (is (str/includes? note (str "main is green again at " head-2)))
      (is (str/includes? note "bench/the-red-base"))
      (is (= :stuck (state-of (change-of engine change-id)))
          "and its change is not superseded")
      (testing "the next green pass writes the sentence no second time"
        (pass! w)
        (is (= (:version tk) (:version (first (tickets engine)))))))))

(defn- force-ticket-state!
  "The one ticket stands in `state`, as a submit beside it would leave it."
  [eng state]
  (let [st (:storage eng)
        id (str (:id (first (tickets eng))))]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :ticket id {})]
          (store/save-row! st tx :ticket
                           (assoc row :state state
                                  :version (inc (long (:version row))))
                           (:version row)))))))

(deftest a-green-base-keeps-the-ticket-under-review-whose-change-holds-work
  ;; ticket 1188f3ed: the ticket is out for review, and a change born
  ;; from it with no pull request still holds an edit on its branch
  (let [{:keys [engine] :as w} (benched-world {:dirty 1 :ahead 0})
        change-id (red-ticket-with-a-change! w)]
    (force-ticket-state! engine :in_review)
    (head-at! w head-2 516 "success")
    (let [census (pass! w)]
      (is (= 0 (:base-closed census)) "the ticket is not ended")
      (is (= 1 (:base-kept census))))
    (let [tk (first (tickets engine))
          note (str (get-in tk [:data :green_note]))]
      (is (= :in_review (state-of tk)))
      (is (str/includes? note (str "main is green again at " head-2)))
      (is (str/includes? note "bench/the-red-base"))
      (is (= :open (state-of (change-of engine change-id)))
          "and its change is not superseded")
      (testing "the next green pass writes the sentence no second time"
        (pass! w)
        (is (= (:version tk) (:version (first (tickets engine)))))))))

(deftest a-green-base-ends-the-ticket-whose-change-is-empty
  (let [{:keys [engine] :as w} (benched-world {:dirty 0 :ahead 0})
        change-id (red-ticket-with-a-change! w)]
    (head-at! w head-2 514 "success")
    (is (= 1 (:base-closed (pass! w))))
    (let [tk (first (tickets engine))]
      (is (= :done (state-of tk)))
      (is (str/blank? (str (get-in tk [:data :green_note])))))
    (is (= :closed (state-of (change-of engine change-id)))
        "an empty change is closed with its ticket, as before")))

(deftest a-green-base-keeps-the-ticket-when-the-bench-is-dark
  ;; ticket 6d8e3a3e: the bench answers nothing, so the pass cannot tell
  ;; an empty change from one that holds work
  (let [{:keys [engine source] :as w} (benched-world nil)
        change-id (red-ticket-with-a-change! w)
        lines (atom [])
        _ (head-at! w head-2 515 "success")
        census (forge/pass! {:source source :engine engine
                             :log-fn (fn [& parts]
                                       (swap! lines conj (apply str parts)))})
        tk (first (tickets engine))]
    (is (= 0 (:base-closed census)))
    (is (= 1 (:base-kept census)) "the census counts the ticket it held")
    (is (= :open (state-of tk)))
    (is (= :open (state-of (change-of engine change-id))))
    (is (str/blank? (str (get-in tk [:data :green_note]))) "no note is written")
    (is (= 1 (count (filter #(str/includes? % "the bench did not answer")
                            @lines)))
        "and one line says why it was held")))

(deftest a-green-base-keeps-the-ticket-whose-stuck-change-the-bench-refuses
  ;; ticket e768af15: the rig answers `no_worktree` for a branch with no
  ;; worktree, which does not say a stalled change's branch is empty
  (let [{:keys [engine source] :as w} (benched-world {:refused "no_worktree"})
        change-id (red-ticket-with-a-change! w)
        lines (atom [])
        _ (inv/invoke! engine :change change-id :stall
                       {:why "The red test passes here, so I cannot fix it."}
                       {:principal a-person
                        :if-match (inv/etag :change change-id
                                            (:version (change-of engine change-id)))})
        _ (head-at! w head-2 516 "success")
        census (forge/pass! {:source source :engine engine
                             :log-fn (fn [& parts]
                                       (swap! lines conj (apply str parts)))})
        tk (first (tickets engine))]
    (is (= 0 (:base-closed census)) "the ticket is not ended")
    (is (= 1 (:base-kept census)))
    (is (= :draft (state-of tk)))
    (is (= :stuck (state-of (change-of engine change-id)))
        "and its change is not superseded")
    (is (str/blank? (str (get-in tk [:data :green_note]))) "no note is written")
    (is (= 1 (count (filter #(str/includes? % "the bench did not answer")
                            @lines))))))

(deftest a-green-base-ends-the-ticket-whose-open-change-the-bench-refuses
  ;; an open change the rig refuses was never prepared: it holds no work
  (let [{:keys [engine] :as w} (benched-world {:refused "no_worktree"})
        change-id (red-ticket-with-a-change! w)]
    (head-at! w head-2 517 "success")
    (is (= 1 (:base-closed (pass! w))))
    (is (= :done (state-of (first (tickets engine)))))
    (is (= :closed (state-of (change-of engine change-id))))))

(deftest a-base-red-once-and-green-next-opens-nothing
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 601 "failure")
    (pass! w)
    (head-at! w head-2 602 "success")
    (let [census (pass! w)]
      (is (= 0 (:base-opened census)))
      (is (= 0 (:base-closed census))))
    (is (empty? (tickets engine)))
    (is (= "green" (str (get-in (policy-of engine) [:data :base_state]))))))

(deftest a-red-gate-carries-the-log-of-the-suite-that-failed
  ;; ticket 9a14577e: `gate` only says `quick=failure`; the `quick`
  ;; job's own log is what names the red step
  (let [{:keys [state engine] :as w} (world)]
    (gh/seed-branch! state repo "main" head-1)
    (gh/seed-check! state repo head-1 (a-check 701 head-1 "failure"))
    (gh/seed-log! state "701" "quick=failure")
    (gh/seed-check! state repo head-1
                    (assoc (a-check 702 head-1 "failure") :name "quick"))
    (gh/seed-log! state "702" "Run make check-queue\nFAIL in (calendar10-clash)")
    (gh/seed-check! state repo head-1
                    (assoc (a-check 703 head-1 "success") :name "slow"))
    (gh/seed-log! state "703" "all green here")
    (pass! w)
    (is (= 1 (:base-opened (pass! w))))
    (let [tk (first (tickets engine))
          detail (str (get-in tk [:data :detail]))]
      (is (= "main is red: gate" (get-in tk [:data :title])))
      (is (str/includes? detail "quick=failure") "the gate's log still rides")
      (is (str/includes? detail "- quick"))
      (is (str/includes? detail "FAIL in (calendar10-clash)")
          "the failed suite's own log rides in the body")
      (is (not (str/includes? detail "all green here"))
          "a suite that passed is not quoted"))))

(defn- red-gate-with-steps!
  "`main` red on `gate` for two passes, its job's steps seeded in the
  run listing: one green, one red. → the minted ticket's detail."
  [{:keys [state engine] :as w}]
  (gh/seed-branch! state repo "main" head-1)
  (gh/seed-check! state repo head-1 (a-check 701 head-1 "failure"))
  (gh/seed-log! state "701" "Run make check-queue\nFAIL in (calendar10-clash)")
  (gh/seed-job! state repo 900
                {:id 701 :run_id 900 :name "gate" :status "completed"
                 :conclusion "failure"
                 :steps [{:name "Set up job" :conclusion "success"}
                         {:name "check-queue" :conclusion "failure"}]})
  (pass! w)
  (is (= 1 (:base-opened (pass! w))))
  (str (get-in (first (tickets engine)) [:data :detail])))

(defn- without-steps
  "The source with every ForgeSource verb and no ForgeSteps."
  [s]
  (reify forge/ForgeSource
    (forge-poll [_] (forge/forge-poll s))
    (forge-log-tail [_ check] (forge/forge-log-tail s check))
    (forge-label! [_ change label] (forge/forge-label! s change label))
    (forge-calls [_] (forge/forge-calls s))
    (forge-checks [_ repository sha] (forge/forge-checks s repository sha))
    (forge-base [_ repository branch] (forge/forge-base s repository branch))))

(deftest a-red-main-ticket-names-the-red-step
  ;; ticket c0d7ce64: the step is named even when the tail is cut
  (let [detail (red-gate-with-steps! (world))]
    (is (str/includes? detail "### gate\n\nRed steps: `check-queue`\n\n```")
        "the red step heads the check's log")
    (is (not (str/includes? detail "`Set up job`"))
        "a step that passed is not named")
    (is (str/includes? detail "FAIL in (calendar10-clash)"))))

(deftest a-source-without-steps-writes-the-detail-as-before
  (let [w (update (world) :source without-steps)
        detail (red-gate-with-steps! w)]
    (is (not (satisfies? forge/ForgeSteps (:source w))))
    (is (not (str/includes? detail "Red steps:")))
    (is (str/includes? detail "### gate\n\n```\n")
        "the log tail follows the check's heading directly")
    (is (str/includes? detail "FAIL in (calendar10-clash)"))))

(deftest a-red-main-ticket-carries-the-red-steps-own-lines
  ;; ticket 3a9d6c62: the tail of the whole job was the steps after the
  ;; red one, and the body's ceiling cut what was left of the failure
  (let [{:keys [state engine] :as w} (world)
        wide (apply str (repeat 100 "x"))
        step (str/join "\n" (map #(str "compiling namespace " % " " wide)
                                 (range 400)))
        after (str "##[group]Run crash report\nenv: LATER_DUMP\n##[endgroup]\n"
                   (str/join "\n" (map #(str "post-job line " %) (range 400))))
        ended "\n##[error]Process completed with exit code 1.\n"]
    (gh/seed-branch! state repo "main" head-1)
    (gh/seed-check! state repo head-1 (a-check 701 head-1 "failure"))
    (gh/seed-log! state "701"
                  (str step "\n##[group]Run clojure -M:test\nenv: STEP_DUMP\n"
                       "##[endgroup]\nFAIL in (the-merge-clash)" ended after))
    (gh/seed-check! state repo head-1
                    (assoc (a-check 702 head-1 "failure") :name "quick"))
    (gh/seed-log! state "702"
                  (str step "\nFAIL in (calendar10-clash)" ended after))
    (pass! w)
    (is (= 1 (:base-opened (pass! w))))
    (let [detail (str (get-in (first (tickets engine)) [:data :detail]))]
      (is (str/includes? detail "FAIL in (calendar10-clash)")
          "the first log ends at its failing lines")
      (is (str/includes? detail "FAIL in (the-merge-clash)")
          "and the ceiling leaves the second log its failing lines too")
      (is (str/includes? detail "Run clojure -M:test")
          "the red step's own command is named")
      (is (not (str/includes? detail "STEP_DUMP")) "its env dump is not")
      (is (not (str/includes? detail "crash report"))
          "a step after the red one is left out")
      (is (not (str/includes? detail "post-job line")))
      (is (str/includes? detail "## What to do")
          "the body is whole under its ceiling"))))

(deftest a-red-after-green-names-the-head-that-turned-it
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 701 "success")
    (pass! w)
    (head-at! w head-2 702 "failure")
    (pass! w)
    (is (= head-2 (get-in (policy-of engine) [:data :base_red_from])))
    (is (= 1 (:base-opened (pass! w))))
    (is (str/includes? (str (get-in (first (tickets engine)) [:data :detail]))
                       (str "first red head after a green one was `" head-2)))))

(deftest a-private-base-is-read-through-actions
  (let [{:keys [state engine] :as w} (world)]
    (gh/checks-answer! state repo 403)
    (gh/seed-branch! state repo "main" head-1)
    (gh/seed-run! state repo head-1 {:id 900})
    (gh/seed-job! state repo 900
                  {:id 801 :name "gate" :status "completed"
                   :conclusion "failure" :head_sha head-1
                   :html_url (str "https://github.com/" repo
                                  "/actions/runs/900/job/801")})
    (gh/seed-log! state "801" "FAIL in the private base")
    (pass! w)
    (is (= 1 (:base-opened (pass! w))))
    (is (str/includes? (str (get-in (first (tickets engine)) [:data :detail]))
                       "FAIL in the private base"))))

(deftest a-base-with-no-head-writes-nothing
  (let [{:keys [engine] :as w} (world)
        before (:version (policy-of engine))]
    (pass! w)
    (is (= before (:version (policy-of engine)))
        "a branch the forge will not read costs this pass, and no row")))

(deftest the-base-doors-are-the-engines-hand-alone
  (let [{:keys [engine]} (world)
        tk (:row (inv/create! engine :ticket {:title "A ticket" :repo repo}
                              {:principal a-person}))
        id (str (:id tk))]
    (inv/invoke! engine :ticket id :groom {} {:principal a-person})
    (is (thrown? Exception
                 (inv/invoke! engine :ticket id :mend {:close_reason "No."}
                              {:principal a-person})))
    (is (thrown? Exception
                 (inv/invoke! engine :ticket id :note_red {:red_head "x: gate"}
                              {:principal a-person})))
    (is (thrown? Exception
                 (inv/invoke! engine :repo_policy (str (:id (policy-of engine)))
                              :note_base {:verdict "red"}
                              {:principal a-person})))))

(deftest a-ticket-door-that-throws-does-not-cost-the-base-write
  (let [{:keys [engine] :as w} (world)
        invoke! inv/invoke!]
    (head-at! w head-1 801 "failure")
    (pass! w)
    (head-at! w head-2 802 "failure")
    (with-redefs-fn
      {#'inv/invoke! (fn [eng kind & more]
                       (if (= :ticket kind)
                         (throw (ex-info "the ticket door is shut" {}))
                         (apply invoke! eng kind more)))}
      (fn []
        (is (= 0 (:base-opened (pass! w)))
            "the ticket door refused, so nothing was opened")))
    (let [policy (policy-of engine)]
      (is (= "red" (str (get-in policy [:data :base_state]))))
      (is (= head-2 (get-in policy [:data :base_head]))
          "the base WAS read on this pass, so the base was written")
      (is (str/blank? (str (get-in policy [:data :base_ticket])))
          "and no ticket is claimed that the door never opened"))))

(deftest a-quiet-base-is-stamped-each-pass
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 901 "success")
    (pass! w)
    (let [before (policy-of engine)
          checked (str (get-in before [:data :base_checked_at]))]
      (is (not (str/blank? checked)))
      (Thread/sleep 20)
      (pass! w)
      (let [after (policy-of engine)]
        (is (not= checked (str (get-in after [:data :base_checked_at])))
            "a base that moved nothing is still stamped as read")
        (is (= (:version before) (:version after))
            "with a maintenance write, not a transition")))))

(deftest a-base-the-pass-cannot-read-is-noted-on-the-policy
  (let [{:keys [engine] :as w} (world)]
    (testing "a refused base read writes source_note"
      (pass! w)
      (is (str/starts-with? (str (get-in (policy-of engine)
                                         [:data :source_note]))
                            "The base `main` was not read (")))

    (testing "a second refusal of the same kind writes nothing again"
      (let [before (get-in (policy-of engine) [:data :source_note])]
        (Thread/sleep 20)
        (pass! w)
        (is (= before (get-in (policy-of engine) [:data :source_note])))))

    (testing "a good read clears it"
      (head-at! w head-1 902 "success")
      (pass! w)
      (is (str/blank? (str (get-in (policy-of engine) [:data :source_note]))))
      (is (= head-1 (get-in (policy-of engine) [:data :base_head]))))))

(deftest a-deploy-only-policy-notes-a-base-it-cannot-read
  (let [state (gh/fake-state)
        eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})]
    (inv/create! eng :repo_policy {:repository repo :deploy_check "deploy"}
                 {:principal a-person})
    (pass! {:source (gh/fake-source state {:repos repo}) :engine eng})
    (is (str/starts-with? (str (get-in (policy-of eng) [:data :source_note]))
                          "The base `main` was not read (")
        "no required check, but the deploy read rides on the base")))

(deftest the-base-pass-runs-when-a-pass-before-it-throws
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 903 "failure")
    (with-redefs-fn
      {#'forge/label-pass! (fn [& _] (throw (ex-info "the label pass broke" {})))}
      (fn []
        (is (thrown? Exception (pass! w)) "the throw is not swallowed")))
    (is (= head-1 (get-in (policy-of engine) [:data :base_head]))
        "but the base was read and written first")))

;; ── the groom floor (ticket eb515931) ───────────────────────────────
;;
;; A queue under its policy's floor files one draft ticket for mayor
;; and stamps the policy. The floor pass is called directly: it reads
;; the engine's rows and no forge.

(defn- floor-world
  "An engine holding one active policy for `repo` at this floor, `open`
  groomed tickets and two draft bugs."
  [floor open]
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})
        ticket! (fn [title type]
                  (str (:id (:row (inv/create! eng :ticket
                                               {:title title :type type
                                                :repo repo}
                                               {:principal a-person})))))]
    (inv/create! eng :repo_policy {:repository repo :required_checks ["gate"]
                                   :groom_floor floor}
                 {:principal a-person})
    (dotimes [i open]
      (inv/invoke! eng :ticket (ticket! (str "Open ask " i) "task") :groom {}
                   {:principal a-person}))
    (ticket! "A draft bug" "bug")
    (ticket! "Another draft bug" "bug")
    eng))

(defn- floor-tickets [eng]
  (filterv #(str/starts-with? (str (get-in % [:data :title]))
                              "Groom the next batch for ")
           (tickets eng)))

(defn- floor-pass! [eng]
  (:floor-filed (forge/floor-pass! eng {} (fn [& _] nil)) 0))

(defn- floor-noted! [eng ^Instant at]
  (bench/mark-row! eng :repo_policy (str (:id (policy-of eng)))
                   {:floor_noted_at at} #{}))

(deftest a-queue-under-its-floor-files-one-draft-ticket
  (let [eng (floor-world 4 3)]
    (is (= 1 (floor-pass! eng)))
    (let [[ticket & more] (floor-tickets eng)
          policy (policy-of eng)]
      (is (nil? more))
      (is (= :draft (state-of ticket)))
      (is (= "Groom the next batch for ckopsa/waymark-bench: 3 open, floor 4"
             (get-in ticket [:data :title])))
      (is (= 1 (get-in ticket [:data :priority])))
      (is (str/includes? (str (get-in ticket [:data :detail]))
                         "2 draft bugs, tasks and chores"))
      (is (some? (get-in policy [:data :floor_noted_at])))
      (is (= 3 (get-in policy [:data :floor_count]))))
    (testing "a pass past the window, while the floor ticket is still draft, files none"
      (floor-noted! eng (.minusSeconds (Instant/now) 7200))
      (is (zero? (floor-pass! eng)))
      (is (= 1 (count (floor-tickets eng)))))))

;; the floor ticket is found by the id its policy keeps (ticket ba20278d)

(defn- floor-past-window! [eng]
  (floor-noted! eng (.minusSeconds (Instant/now) 7200)))

(deftest a-renamed-floor-ticket-is-still-found
  (let [eng (floor-world 5 3)]
    (is (= 1 (floor-pass! eng)))
    (let [id (str (:id (first (floor-tickets eng))))
          before (count (tickets eng))]
      (is (= id (get-in (policy-of eng) [:data :floor_ticket]))
          "the policy keeps the id of the ticket it filed")
      (bench/mark-row! eng :ticket id {:title "Batch seven, by hand"} #{})
      (is (empty? (floor-tickets eng)) "no title names the floor now")
      (testing "while it is draft, a pass past the window files none"
        (floor-past-window! eng)
        (is (zero? (floor-pass! eng)))
        (is (= before (count (tickets eng)))))
      (testing "and none while it is open"
        (inv/invoke! eng :ticket id :groom {} {:principal a-person})
        (floor-past-window! eng)
        (is (zero? (floor-pass! eng)))
        (is (= before (count (tickets eng))))))))

(deftest a-floor-ticket-filed-before-the-id-was-kept-is-found-by-title
  (let [eng (floor-world 4 3)]
    (is (= 1 (floor-pass! eng)))
    (let [id (str (:id (first (floor-tickets eng))))]
      (bench/mark-row! eng :repo_policy (str (:id (policy-of eng)))
                       {:floor_ticket nil} #{})
      (floor-past-window! eng)
      (is (zero? (floor-pass! eng)))
      (is (= 1 (count (floor-tickets eng))))
      (is (= id (get-in (policy-of eng) [:data :floor_ticket]))
          "and the policy keeps its id from then on"))))

(deftest the-floor-files-none-inside-its-settle-window
  (let [eng (floor-world 4 3)]
    (floor-noted! eng (Instant/now))
    (is (zero? (floor-pass! eng)))
    (is (empty? (floor-tickets eng)))
    (testing "and files once the window has passed"
      (floor-noted! eng (.minusSeconds (Instant/now) 7200))
      (is (= 1 (floor-pass! eng)))
      (is (= 1 (count (floor-tickets eng)))))))

(deftest a-queue-at-its-floor-files-none
  (let [eng (floor-world 4 4)]
    (is (zero? (floor-pass! eng)))
    (is (empty? (floor-tickets eng)))))

(deftest a-floor-of-zero-never-files
  (let [eng (floor-world 0 0)]
    (is (zero? (floor-pass! eng)))
    (is (empty? (floor-tickets eng)))
    (is (nil? (get-in (policy-of eng) [:data :floor_noted_at])))))

;; ── the floor's priority line (ticket 08efd286) ─────────────────────

(defn- line-world
  "An engine holding one policy at floor 4 with this priority `line`
  (none states the default), no open ticket, and one draft bug at each
  of `priorities`."
  [line priorities]
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})]
    (inv/create! eng :repo_policy
                 (cond-> {:repository repo :required_checks ["gate"]
                          :groom_floor 4}
                   line (assoc :groom_floor_max_priority line))
                 {:principal a-person})
    (doseq [p priorities]
      (inv/create! eng :ticket {:title (str "A P" p " draft bug") :type "bug"
                                :priority p :repo repo}
                   {:principal a-person}))
    eng))

(deftest only-p4-drafts-under-a-line-of-3-file-none
  (let [eng (line-world 3 [4 4])]
    (is (zero? (floor-pass! eng)))
    (is (empty? (floor-tickets eng)))))

(deftest a-p3-draft-under-a-line-of-3-files-one
  (let [eng (line-world 3 [3 4])]
    (is (= 1 (floor-pass! eng)))
    (is (str/includes? (str (get-in (first (floor-tickets eng)) [:data :detail]))
                       "1 draft bugs, tasks and chores"))))

(deftest the-default-line-keeps-every-priority
  (let [eng (line-world nil [4 4])]
    (is (= 1 (floor-pass! eng)))
    (is (str/includes? (str (get-in (first (floor-tickets eng)) [:data :detail]))
                       "2 draft bugs, tasks and chores"))))
