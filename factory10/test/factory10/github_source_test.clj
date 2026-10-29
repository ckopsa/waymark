(ns factory10.github-source-test
  "The GitHub source, judged over REAL rows and NO database (bead
  waymark-fp62.6.4, acceptance 1 to 4).

  WHAT THIS FILE OWNS is the claim the source is for: that a pull
  request and a red check run arrive as rows nobody typed, through the
  same doors a person would meet if the doors were not hidden. So
  every assertion here reads a row the ENGINE minted — the ids are the
  engine's own, never a literal — and the engine stands over the
  framework's in-memory store, which is why this suite needs no
  Postgres and the factory's CI job stays database-free
  (waymark10.server.store.memory, the modules suite's own arrangement
  one layer up).

  WHICH REPOSITORIES IT READS IS THE ROWS (bead waymark-fp62.6.3.8).
  The source holds a function and asks it at every pass, and the
  wiring gives it the active `repo_policy` rows — so one test here
  writes policy rows into the engine and watches the poll list move
  under them.

  THE FAKE IS AN IN-MEMORY GITHUB, not an in-memory source: it stands
  behind the transport seam, so the real window, the real cursor
  arithmetic, the real translation and the real log reading all run
  here and only the socket is missing. It is scripted with GitHub's
  OWN shapes — a pull request as the API answers one — because a test
  that seeded documents would prove the documents and not the source.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.resources.pull-request :as pull-request]
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]
            [factory10.sources.runs :as runs]
            [factorykit.forge :as forge-kit]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as wm-mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private repo "ckopsa/waymark")

(def ^:private the-classifier
  (t/principal {:id "ci-classifier" :type :agent :display "The classifier"}))

;; ── the shapes GitHub answers with ──────────────────────────────────

(def ^:private a-pull-request
  {:number 31
   :state "open"
   :title "6.4 GitHub events as a source"
   :user {:login "ckopsa"}
   :draft false
   :base {:ref "main"}
   :head {:ref "waymark-fp62.6.4"
          :sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"}
   :html_url "https://github.com/ckopsa/waymark/pull/31"
   :updated_at "2026-09-18T12:00:00Z"
   :labels [{:name "agent"}]
   :mergeable_state "clean"
   :changed_files 3
   :additions 412
   :deletions 7})

(def ^:private the-files
  [{:filename "factory10/src/factory10/sources/github.clj"}
   {:filename "factory10/src/factory10/sources/forge.clj"}
   {:filename "factory10/test/factory10/github_source_test.clj"}])

(def ^:private the-reviews
  [{:state "COMMENTED" :user {:login "colton"}}
   {:state "CHANGES_REQUESTED" :user {:login "colton"}}])

(def ^:private a-red-check
  {:id 41752098311
   :name "test10 (shard 3)"
   :status "completed"
   :conclusion "failure"
   :head_sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"
   :started_at "2026-09-18T13:41:00Z"
   :completed_at "2026-09-18T13:52:00Z"
   :html_url "https://github.com/ckopsa/waymark/runs/41752098311"
   :details_url
   "https://github.com/ckopsa/waymark/actions/runs/900/job/7001"})

(def ^:private a-green-check
  {:id 41752098399 :name "check-queue" :status "completed"
   :conclusion "success"
   :head_sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"})

(def ^:private the-log
  "Five hundred lines, so the tail is really a tail."
  (str/join "\n" (map #(str "line " %) (range 1 501))))

;; ── the rig ─────────────────────────────────────────────────────────

(defn- boot
  "An engine over the framework's in-memory store, carrying the
  factory's two kinds and nothing else — plus whatever `extra` kinds
  a test needs beside them."
  ([] (boot []))
  ([extra]
   (engine/engine {:storage (memory/storage)
                   :resources (into (vec (main/resources)) extra)})))

(def ^:private an-ask "01HZQ7ASKR3W4V5X6Y7Z8A9B1")

(defn- an-adopted-change!
  "The row a seat's sit minted for an ask, as the mirror's link leaves
  it once GitHub's `opened` event names the pull request (plan
  85b4462e): GitHub's id and number, the pull's url, head and branch,
  and the ask it was born from. The forge pass adopts and observes no
  change, so a test that needs one seeds it here."
  [engine extra]
  (:row (inv/create! engine :change
                     (merge {:change_id "github:ckopsa/waymark#31"
                             :repository repo
                             :number 31
                             :url (:html_url a-pull-request)
                             :title "6.3.10 The code seat builds a task"
                             :head_branch (get-in a-pull-request [:head :ref])
                             :head_sha (get-in a-pull-request [:head :sha])
                             :base_branch "main"
                             :born_from (str "ask:" an-ask)
                             :author "code-seat"}
                            extra)
                     {:principal mirror/source-principal})))

(defn- rig
  "A fresh in-memory GitHub, the real source over it, and an engine —
  seeded with one open pull request and one red check run on its head,
  and with the change a seat asked for, as GitHub's events leave it
  (`an-adopted-change!`). `{:ours? false}` leaves that row out: the
  pull request is then a person's, or the test seeds the row itself."
  ([] (rig []))
  ([extra] (rig extra {}))
  ([extra {:keys [ours?] :or {ours? true}}]
   (let [state (gh/fake-state)]
     (gh/seed-pull! state repo a-pull-request
                    {:files the-files :reviews the-reviews})
     (gh/seed-check! state repo (get-in a-pull-request [:head :sha])
                     a-red-check)
     (gh/seed-check! state repo (get-in a-pull-request [:head :sha])
                     a-green-check)
     (gh/seed-log! state "7001" the-log)
     (let [engine (boot extra)]
       (when ours? (an-adopted-change! engine {}))
       {:state state :source (gh/fake-source state) :engine engine}))))

(defn- quiet [& _] nil)

(defn- pass! [{:keys [source engine]}]
  (forge/pass! {:source source :engine engine :log-fn quiet}))

(defn- rows-of
  "Every row of this kind that matches an INDEXED field."
  [engine kind where]
  (let [st (:storage engine)]
    (store/with-tx st (fn [tx] (store/query-rows st tx kind where
                                                 {:limit 50})))))

(defn- one-row [engine kind where] (first (rows-of engine kind where)))

(defn- the-change [engine]
  (one-row engine :change {:change_id "github:ckopsa/waymark#31"}))

(defn- as-github-said!
  "The change moved by hand where GitHub's events walk it — `head_moved`
  writes the head, `merged` and `closed` end it — because the forge pass
  observes no change (plan 85b4462e). A nil `state` keeps the state."
  [engine state data]
  (let [st (:storage engine)
        id (str (:id (the-change engine)))]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :change id {})]
          (store/save-row! st tx :change
                           (cond-> (-> row
                                       (update :data merge data)
                                       (assoc :version (inc (long (:version row)))))
                             state (assoc :state state))
                           (:version row)))))))

(defn- the-run [engine]
  (one-row engine :ci_run
           {:run_id "github:ckopsa/waymark/check-run/41752098311"}))

(defn- writes
  "Every request the source made that was not a read."
  [state]
  (filterv #(not= "GET" (:method %)) (gh/requests state)))

;; ── acceptance 1 ────────────────────────────────────────────────────

(deftest mergeable-false-outranks-the-state-word
  (testing "GitHub's own mergeable: false is a conflict, whatever the policy word"
    (is (= "conflicted" (gh/mergeable-of {:mergeable_state "blocked" :mergeable false})))
    (is (= "conflicted" (gh/mergeable-of {:mergeable_state "behind" :mergeable false})))
    (is (= "conflicted" (gh/mergeable-of {:mergeable false}))))
  (testing "without it the state word still speaks"
    (is (= "blocked" (gh/mergeable-of {:mergeable_state "blocked" :mergeable true})))
    (is (= "blocked" (gh/mergeable-of {:mergeable_state "blocked" :mergeable nil})))
    (is (= "unknown" (gh/mergeable-of {})))))

;; ── acceptance 2 ────────────────────────────────────────────────────

(deftest a-failed-check-run-becomes-a-red-run-with-the-log-tail
  (let [{:keys [engine] :as r} (rig)
        census (pass! r)
        row (the-run engine)
        data (:data row)]
    (testing "the red run is a row, and the green one is not"
      (is (some? row))
      (is (= 1 (:runs-minted census)))
      (is (= :red (:state row)))
      (is (nil? (one-row engine :ci_run
                         {:run_id "github:ckopsa/waymark/check-run/41752098399"}))
          "a check run that passed is nobody's work"))

    (testing "what the row says about the failure"
      (is (= "test10 (shard 3)" (:check_name data)))
      (is (= "failure" (:conclusion data)))
      (is (= (get-in a-pull-request [:head :sha]) (:head_sha data))))

    (testing "the log tail is the END of the log, and it is bounded"
      (let [lines (str/split-lines (:log_excerpt data))]
        (is (= 200 (count lines)) "the last 200 lines, and no more")
        (is (= "line 500" (last lines)))
        (is (= "line 301" (first lines)))))

    (testing "and the change it ran on resolves to the row that is here"
      (let [change (the-change engine)]
        (is (= (str (:id change)) (str (:change data))))
        (is (= "github:ckopsa/waymark#31"
               (get-in (store/with-tx (:storage engine)
                         (fn [tx] (store/load-row (:storage engine) tx :change
                                                  (str (:change data)) {})))
                       [:data :change_id])))))))

(deftest a-check-run-mints-one-row-and-never-a-second
  (let [{:keys [engine] :as r} (rig)
        first-pass (pass! r)
        row (the-run engine)
        second-pass (pass! r)
        third-pass (pass! r)]
    (is (= 1 (:runs-minted first-pass)))
    (is (= 0 (:runs-minted second-pass)))
    (is (= 0 (:runs-minted third-pass)))
    (is (= 1 (:runs-known second-pass))
        "the pass says it knew this check run already")
    (is (= 1 (count (rows-of engine :ci_run
                             {:run_id (get-in row [:data :run_id])})))
        "one check run id, one row")
    (is (= (:id row) (:id (the-run engine))) "and it is the same row")))

(deftest a-log-that-is-not-plain-text-costs-the-excerpt-and-not-the-row
  (let [{:keys [state engine] :as r} (rig)
        _ (gh/log-mode! state :zip)
        census (pass! r)
        data (:data (the-run engine))]
    (is (= 1 (:runs-minted census)) "the row is minted anyway")
    (is (nil? (:log_excerpt data))
        "the excerpt is EMPTY: a sentence about a missing log written
         where the tail goes reads as the end of a build log, and the
         classifier reasons about it as one (bead waymark-fp62.6.9)")
    (is (str/includes? (str (:log_note data)) "plain text")
        "and the note says why there is no tail, in its own field")))

(deftest a-log-the-forge-will-not-answer-at-all-carries-the-note-and-no-excerpt
  ;; The other half of the same rule: a 404 on the job log is as
  ;; ordinary as an archive, and the row is minted either way.
  (let [{:keys [state engine] :as r} (rig)
        _ (gh/log-mode! state :missing)
        census (pass! r)
        row (the-run engine)
        data (:data row)]
    (is (= 1 (:runs-minted census)) "the red run is a row, log or no log")
    (is (= :red (:state row)) "…and it is in the classifier's queue")
    (is (nil? (:log_excerpt data)))
    (is (str/includes? (str (:log_note data)) "404")
        "the forge's own answer, so a person reads what happened")
    (is (>= 240 (count (str (:log_note data))))
        "one sentence, and the source cuts it to the field's ceiling")))

(deftest the-job-log-redirect-is-followed-without-the-token
  (let [{:keys [state engine] :as r} (rig)
        _ (gh/log-mode! state :redirect)
        _ (pass! r)
        blob (first (filter #(str/starts-with? (str (:path %)) gh/blob-base)
                            (gh/requests state)))]
    (is (some? blob) "the source followed the redirect to the blob")
    (is (true? (:anonymous blob))
        "and it carried no bearer to another host")
    (is (= 200 (count (str/split-lines
                       (get-in (the-run engine) [:data :log_excerpt])))))))

;; ── acceptance 3 ────────────────────────────────────────────────────

(deftest a-head-that-moves-supersedes-the-old-heads-red-run
  ;; A RUN RAN ON ONE COMMIT (bead waymark-fp62.6.9). When the head
  ;; moves that commit is gone, and the run is a question no seat can
  ;; answer. The pass mints nothing for the dead head and walks the
  ;; kind's `supersede` door on the rows already here, so the
  ;; classifier's queue holds only runs of the commit the branch
  ;; carries now.
  (let [{:keys [state engine] :as r} (rig)
        _ (pass! r)
        run (the-run engine)
        new-head "9999888877776666555544443333222211110000"
        _ (gh/seed-pull! state repo
                         (-> a-pull-request
                             (assoc-in [:head :sha] new-head)
                             (assoc :updated_at "2026-09-18T14:00:00Z"))
                         {:files the-files :reviews []})
        ;; a red check run on the NEW head, so the pass has something
        ;; to mint that is not the dead one
        _ (gh/seed-check! state repo new-head
                          (assoc a-red-check :id 41752099999
                                 :head_sha new-head))
        _ (gh/seed-log! state "7001" the-log)
        _ (as-github-said! engine nil {:head_sha new-head})
        census (pass! r)]
    (is (= new-head (get-in (the-change engine) [:data :head_sha]))
        "the change carries the new head")
    (is (= 1 (:runs-superseded census))
        "and the pass says it superseded one red run of the old head")
    (is (= :superseded (:state (the-run engine)))
        "the row left the queue through the door, at a tomb of its own")
    (is (nil? (get-in (the-run engine) [:data :verdict]))
        "with no verdict on it: nobody classified this run, and the
         supersede door writes no field at all")
    (is (empty? (rows-of engine :ci_run
                         {:state "red"
                          :head_sha (get-in a-pull-request [:head :sha])}))
        "acceptance 3: a moved head leaves NO red ci_run of the old
         head in the queue")
    (is (= 1 (:runs-minted census))
        "the new head's failure is a row of its own")
    (is (some? (one-row engine :ci_run
                        {:run_id "github:ckopsa/waymark/check-run/41752099999"})))
    (is (= (:id run) (:id (the-run engine)))
        "and the old row was not re-minted")

    (testing "the transition is on the record, so the ledger sees it"
      (let [actions (store/with-tx (:storage engine)
                      (fn [tx]
                        (into #{}
                              (map #(name (:action %)))
                              (store/transitions
                               (:storage engine) tx
                               {:kind :ci_run :resource-id (str (:id run))}
                               {:limit 20}))))]
        (is (contains? actions "supersede")
            "a superseded run is a transition and not a delete, so the
             ledger counts what the head took with it")))

    (testing "and a second pass supersedes nothing, because nothing is red"
      (is (= 0 (:runs-superseded (pass! r)))))))

(deftest a-change-that-ends-supersedes-its-red-runs
  ;; ticket ebe54a72: the head did not move, the change ENDED. A red
  ;; run of a merged or closed change is a question no seat can answer,
  ;; and the seat is not offered the door, so the pass walks it.
  (doseq [[ending expected] [[{:merged_at "2026-09-18T15:00:00Z"} :merged]
                             [{} :closed]]]
    (testing (str "a change that is " (name expected))
      (let [{:keys [state engine] :as r} (rig)
            _ (pass! r)
            _ (is (= :red (:state (the-run engine)))
                  "the run is red while its change is open at that head")
            _ (gh/seed-pull! state repo
                             (merge a-pull-request
                                    {:state "closed"
                                     :updated_at "2026-09-18T15:00:00Z"}
                                    ending))
            _ (as-github-said! engine expected {})
            census (pass! r)]
        (is (= expected (:state (the-change engine))))
        (is (= 1 (:runs-superseded census))
            "the pass says it superseded the run")
        (is (= :superseded (:state (the-run engine)))
            "the red queue holds no run of a change that ended")
        (is (nil? (get-in (the-run engine) [:data :verdict]))
            "and it left with no verdict on it")
        (is (= 0 (:runs-superseded (pass! r)))
            "a second pass supersedes nothing")))))

;; ── acceptance 4 ────────────────────────────────────────────────────

(deftest a-classified-run-gets-one-label-and-one-stamp
  (let [{:keys [state engine] :as r} (rig)
        _ (pass! r)
        run (the-run engine)
        ;; the seat's own walk: the classifier writes the verdict
        _ (inv/invoke! engine :ci_run (str (:id run)) :classify_infra
                       {:remedy (str "Re-run the shard: the dependency cache "
                                     "died and the job never reached a test.")}
                       {:principal the-classifier})
        census (pass! r)
        after (the-run engine)]
    (testing "one label call, and its name is the verdict's own"
      (is (= 1 (:labelled census)))
      (is (= [{:repository repo :number 31 :labels ["ci:infra"]}]
             (gh/labels-pushed state)))
      (is (= "ci:infra" (mirror/label-for "infra"))
          "and the table both halves read says the same word"))

    (testing "the mirror walked stamp_label, so the row says what landed"
      (is (= "ci:infra" (get-in after [:data :pushed_label])))
      (is (some? (get-in after [:data :labelled_at])))
      (is (= :classified (:state after)) "a stamp is a self-loop"))

    (testing "and the source made NO other write, on any pass"
      (is (= 1 (count (writes state))))
      (is (= "POST" (:method (first (writes state)))))
      (is (= "/repos/ckopsa/waymark/issues/31/labels"
             (:path (first (writes state))))))

    (testing "a later pass pushes the same label no second time"
      (let [again (pass! r)]
        (is (= 0 (:labelled again)))
        (is (= 1 (count (gh/labels-pushed state))))))))

;; ── the cursor ──────────────────────────────────────────────────────

(deftest the-window-re-asks-from-five-minutes-behind
  (let [state (gh/fake-state)
        source (gh/fake-source state {:cursor "2026-09-18T12:00:00Z"})
        pull (fn [n at]
               {:number n :state "open" :title (str "pull " n)
                :user {:login "ckopsa"} :base {:ref "main"}
                :head {:ref (str "b" n) :sha (str "sha" n)}
                :updated_at at :labels []})]
    ;; inside the five minutes (though outside sixty seconds), and
    ;; outside them
    (gh/seed-pull! state repo (pull 7 "2026-09-18T11:56:00Z"))
    (gh/seed-pull! state repo (pull 8 "2026-09-18T11:54:30Z"))
    (let [answer (forge/forge-poll source)]
      (is (= ["github:ckopsa/waymark#7"] (mapv :change_id (:changes answer)))
          "the window re-asks from five minutes behind the cursor, and
           stops at the first pull request older than that")
      (is (true? (:complete? answer)))
      (is (= "2026-09-18T12:00:00Z" (gh/cursor source))
          "a re-read does not walk the cursor backwards"))

    (testing "a newer pull request moves the cursor to GitHub's own stamp"
      (gh/seed-pull! state repo (pull 9 "2026-09-18T13:00:00Z"))
      (forge/forge-poll source)
      (is (= "2026-09-18T13:00:00Z" (gh/cursor source))))))

(deftest a-repository-that-does-not-answer-holds-the-cursor
  (let [state (gh/fake-state)
        source (gh/fake-source state {:repos "ckopsa/waymark,ckopsa/bench"})]
    (gh/seed-pull! state repo
                   {:number 1 :state "open" :title "one"
                    :user {:login "ckopsa"} :base {:ref "main"}
                    :head {:ref "b" :sha "sha1"}
                    :updated_at "2026-09-18T10:00:00Z" :labels []})
    (gh/down! state true)
    (let [answer (forge/forge-poll source)]
      (is (false? (:complete? answer)) "neither repository answered")
      (is (nil? (gh/cursor source)) "so the cursor stands where it was"))

    (gh/down! state false)
    (let [answer (forge/forge-poll source)]
      (is (true? (:complete? answer)))
      (is (= 1 (count (:changes answer))))
      (is (= "2026-09-18T10:00:00Z" (gh/cursor source))
          "the cursor advances only when every repository answered"))))

;; ── one cursor for each repository, and stored (ticket c07b581f) ────

(defn- pull-at [n at]
  {:number n :state "open" :title (str "pull " n)
   :user {:login "ckopsa"} :base {:ref "main"}
   :head {:ref (str "b" n) :sha (str "sha" n)}
   :updated_at at :labels []})

(defn- a-cursor-store
  "A cursor store as the wiring's, over a map a second source reads."
  []
  (let [kept (atom {})]
    {:kept kept
     :load (fn [repository] (get @kept repository))
     :save! (fn [repository cursor] (swap! kept assoc repository cursor))}))

(def ^:private the-bench-repo "ckopsa/bench")

(deftest a-source-built-again-starts-at-the-stored-cursor
  (let [state (gh/fake-state)
        store (a-cursor-store)
        opts {:repos [repo the-bench-repo] :store store}]
    (gh/seed-pull! state repo (pull-at 1 "2026-09-18T10:00:00Z"))
    (gh/seed-pull! state repo (pull-at 2 "2026-09-18T12:00:00Z"))
    (gh/seed-pull! state the-bench-repo (pull-at 3 "2026-09-18T11:00:00Z"))
    (let [source (gh/fake-source state opts)
          answer (forge/forge-poll source)]
      (is (= {} @(:kept store))
          "a poll alone stores nothing (ticket fc3a5a40)")
      (forge/forge-commit! source (:answered answer)))
    (is (= {repo "2026-09-18T12:00:00Z" the-bench-repo "2026-09-18T11:00:00Z"}
           @(:kept store))
        "each repository's cursor is written when its rows are")
    (let [before (count (gh/requests state))
          answer (forge/forge-poll (gh/fake-source state opts))
          asked (drop before (gh/requests state))
          listing? #(re-matches #"/repos/[^/]+/[^/]+/pulls" (str (:path %)))
          paths (mapv :path asked)]
      (is (= [(str "/repos/" repo "/pulls")
              (str "/repos/" the-bench-repo "/pulls")]
             (mapv :path (filter listing? asked)))
          "one listing call for each repository")
      (is (every? #(= "all" (get-in % [:params :state])) (filter listing? asked))
          "and it is the window's listing, not a first read")
      (is (not-any? #{(str "/repos/" repo "/pulls/1")} paths)
          "a pull request behind the window is not read again")
      (is (= #{"github:ckopsa/waymark#2" "github:ckopsa/bench#3"}
             (set (map :change_id (:changes answer))))
          "only the ones inside the five minutes are seen again"))))

(deftest a-repository-that-does-not-answer-holds-only-its-own-cursor
  (let [state (gh/fake-state)
        store (a-cursor-store)
        source (gh/fake-source state {:repos [repo the-bench-repo]
                                      :store store})]
    (gh/seed-pull! state repo (pull-at 1 "2026-09-18T10:00:00Z"))
    (gh/seed-pull! state the-bench-repo (pull-at 2 "2026-09-18T09:00:00Z"))
    (gh/refuse! state the-bench-repo 403)
    (let [answer (forge/forge-poll source)]
      (is (false? (:complete? answer)))
      (is (= [repo] (:answered answer)))
      (is (= "2026-09-18T10:00:00Z" (gh/cursor source repo))
          "the repository that answered moves its cursor")
      (is (nil? (gh/cursor source the-bench-repo))
          "the one that did not keeps its own")
      (forge/forge-commit! source (:answered answer))
      (is (= {repo "2026-09-18T10:00:00Z"} @(:kept store))))
    (gh/refuse! state the-bench-repo nil)
    (let [before (count (gh/requests state))
          answer (forge/forge-poll source)
          asked (drop before (gh/requests state))]
      (is (true? (:complete? answer)))
      (is (= "2026-09-18T09:00:00Z" (gh/cursor source the-bench-repo)))
      (is (= "all" (:state (:params (first asked))))
          "and the first one is read from its window, not from nothing"))))

(def ^:private a-merged-pull-request
  (assoc a-pull-request
         :state "closed"
         :merged_at "2026-09-18T15:00:00Z"
         :updated_at "2026-09-18T15:00:00Z"))

(deftest a-pass-that-throws-before-its-rows-leaves-the-stored-cursor
  ;; ticket fc3a5a40
  (let [{:keys [state engine]} (rig)
        store (a-cursor-store)
        source (gh/fake-source state {:store store})
        r {:source source :engine engine}]
    (pass! r)
    (let [stood (get @(:kept store) repo)]
      (is (some? stood) "a pass that wrote its rows stores the cursor")
      (gh/seed-pull! state repo a-merged-pull-request
                     {:files the-files :reviews the-reviews})
      (with-redefs [forge-kit/commit-poll!
                    (fn [& _] (throw (ex-info "thrown by the test" {})))]
        (is (thrown? Exception (pass! r))))
      (is (= {repo stood} @(:kept store))
          "the stored cursor stands where it was")
      (is (= stood (gh/cursor source repo))
          "and the source goes back to it, so the next poll answers again")
      (pass! r)
      (is (= {repo "2026-09-18T15:00:00Z"} @(:kept store))))))

(deftest a-new-repository-is-first-read-for-its-open-pull-requests
  (let [state (gh/fake-state)
        source (gh/fake-source state)
        listings (fn [] (filterv #(= (str "/repos/" repo "/pulls") (:path %))
                                 (gh/requests state)))]
    (gh/seed-pull! state repo (pull-at 1 "2026-09-18T10:00:00Z"))
    (gh/seed-pull! state repo (assoc (pull-at 2 "2026-09-18T11:00:00Z")
                                     :state "closed"
                                     :merged_at "2026-09-18T11:00:00Z"))
    (let [answer (forge/forge-poll source)]
      (is (= ["open"] (mapv #(get-in % [:params :state]) (listings))))
      (is (= ["github:ckopsa/waymark#1"] (mapv :change_id (:changes answer)))
          "what merged before the house read the repository is not mirrored")
      (is (not-any? #{(str "/repos/" repo "/pulls/2")}
                    (map :path (gh/requests state)))))
    (testing "with a cursor the listing follows merges and closes again"
      (forge/forge-poll source)
      (is (= ["open" "all"] (mapv #(get-in % [:params :state]) (listings)))))))

;; ── check runs refused (a private repository) ───────────────────────
;;
;; A fine-grained token cannot hold `Checks`, so on a PRIVATE repository
;; the check-runs route answers 403. The source reads the same head
;; through the Actions API instead, and no failure of the check read
;; costs a repository its pass.

(def ^:private private-repo "ckopsa/waymark-doors")

(def ^:private a-private-pull
  (assoc a-pull-request
         :number 5
         :html_url "https://github.com/ckopsa/waymark-doors/pull/5"))

(def ^:private a-red-job
  "The Actions job behind the red check, in the shape the jobs route
  answers."
  {:id 7001
   :run_id 900
   :name "test10 (shard 3)"
   :status "completed"
   :conclusion "failure"
   :head_sha (get-in a-pull-request [:head :sha])
   :started_at "2026-09-18T13:41:00Z"
   :completed_at "2026-09-18T13:52:00Z"
   :html_url "https://github.com/ckopsa/waymark-doors/actions/runs/900/job/7001"})

(def ^:private a-green-job
  {:id 7002 :run_id 900 :name "check-queue" :status "completed"
   :conclusion "success"
   :head_sha (get-in a-pull-request [:head :sha])
   :html_url "https://github.com/ckopsa/waymark-doors/actions/runs/900/job/7002"})

(defn- private-rig
  "A private repository whose check-runs route answers `status`, with
  one open pull request and one workflow run of one red and one green
  job on its head."
  [state status]
  (let [sha (get-in a-pull-request [:head :sha])]
    (gh/seed-pull! state private-repo a-private-pull {:files the-files})
    (gh/checks-answer! state private-repo status)
    (gh/seed-run! state private-repo sha {:id 900 :head_sha sha})
    (gh/seed-job! state private-repo 900 a-red-job)
    (gh/seed-job! state private-repo 900 a-green-job)
    (gh/seed-log! state "7001" the-log)))

(deftest the-red-steps-of-a-job-are-named-from-its-run-listing
  ;; ticket c0d7ce64: the red-main ticket names the step that went red
  (let [state (gh/fake-state)
        _ (gh/seed-job! state repo 900
                        (assoc a-red-job :steps
                               [{:name "Set up job" :conclusion "success"}
                                {:name "check-queue" :conclusion "failure"}
                                {:name "calendar10" :conclusion "skipped"}]))
        source (gh/fake-source state)]
    (is (= ["check-queue"]
           (forge/forge-failed-steps
            source {:repository repo :details_url (:html_url a-red-job)})))
    (is (= [] (forge/forge-failed-steps source {:repository repo}))
        "a check that names no run names no step")))

(deftest a-refused-check-read-is-made-through-actions
  (let [state (gh/fake-state)
        _ (private-rig state 403)
        ;; a second open head, so the pass shows it remembered the 403
        _ (gh/seed-pull! state private-repo
                         (-> a-private-pull
                             (assoc :number 6)
                             (assoc-in [:head :ref] "another")
                             (assoc-in [:head :sha] "feedface")))
        engine (boot)
        _ (an-adopted-change! engine
                              {:change_id "github:ckopsa/waymark-doors#5"
                               :repository private-repo
                               :number 5
                               :url (str "https://github.com/" private-repo
                                         "/pull/5")})
        _ (an-adopted-change! engine
                              {:change_id "github:ckopsa/waymark-doors#6"
                               :repository private-repo
                               :number 6
                               :url (str "https://github.com/" private-repo
                                         "/pull/6")
                               :head_branch "another"
                               :head_sha "feedface"
                               :born_from (str "ask:" an-ask "-6")})
        source (gh/fake-source state {:repos private-repo})
        census (forge/pass! {:source source :engine engine :log-fn quiet})
        change (one-row engine :change
                        {:change_id "github:ckopsa/waymark-doors#5"})
        run (one-row engine :ci_run
                     {:run_id "github:ckopsa/waymark-doors/check-run/7001"})]
    (testing "the change carries its pull request's number"
      (is (some? change))
      (is (= 5 (get-in change [:data :number])))
      (is (= (get-in a-pull-request [:head :sha])
             (get-in change [:data :head_sha])))
      (is (some? (one-row engine :change
                          {:change_id "github:ckopsa/waymark-doors#6"}))))

    (testing "its red Actions job is a ci_run, as a red check run would be"
      (is (= 1 (:runs-minted census)))
      (is (= :red (:state run)))
      (is (= "test10 (shard 3)" (get-in run [:data :check_name])))
      (is (= "failure" (get-in run [:data :conclusion])))
      (is (= (str (:id change)) (str (get-in run [:data :change]))))
      (is (= "line 500"
             (last (str/split-lines (get-in run [:data :log_excerpt]))))
          "the job page names the job, so the log hop still finds it")
      (is (nil? (one-row engine :ci_run
                         {:run_id "github:ckopsa/waymark-doors/check-run/7002"}))
          "the green job is nobody's work"))

    (testing "the refusal is remembered for the rest of the pass"
      (is (= 1 (count (filter #(str/ends-with? (str (:path %)) "/check-runs")
                              (gh/requests state))))
          "the second head went straight to Actions"))

    (testing "the checks on one head read through Actions too"
      (is (= [{:check_name "test10 (shard 3)" :status "completed"
               :conclusion "failure" :id 7001
               :started_at "2026-09-18T13:41:00Z"}
              {:check_name "check-queue" :status "completed"
               :conclusion "success" :id 7002 :started_at nil}]
             (forge/forge-checks source private-repo
                                 (get-in a-pull-request [:head :sha])))
          "each run carries its id and start, so the newest of one
           name speaks for it (ticket 6bdaf6fe)"))))

(deftest a-failed-check-read-costs-the-checks-and-not-the-pass
  (let [state (gh/fake-state)
        _ (private-rig state 500)
        _ (gh/seed-pull! state repo a-pull-request {:files the-files})
        _ (gh/seed-check! state repo (get-in a-pull-request [:head :sha])
                          a-red-check)
        source (gh/fake-source state {:repos [private-repo repo]})
        answer (forge/forge-poll source)]
    (is (true? (:complete? answer))
        "both repositories answered, so the cursor may move")
    (is (= #{"github:ckopsa/waymark-doors#5" "github:ckopsa/waymark#31"}
           (into #{} (map :change_id) (:changes answer)))
        "the pull request is still adopted, checks or no checks")
    (is (= 5 (:number (first (filter #(= private-repo (:repository %))
                                     (:changes answer))))))
    (is (= ["github:ckopsa/waymark/check-run/41752098311"]
           (mapv :run_id (:checks answer)))
        "the failing repository has no checks, and the other's pass is
         untouched")
    (is (not-any? #(str/includes? (str (:path %)) "/actions/runs")
                  (filter #(str/includes? (str (:path %)) private-repo)
                          (gh/requests state)))
        "a 500 is not a refusal: Actions is read only on 401 and 403")))

;; ── the repositories are the rows (bead waymark-fp62.6.3.8) ─────────

(def ^:private a-person (t/principal {:id "colton" :display "Colton"}))

(defn- policy!
  "One repo_policy row, as a person writes one. Every other field of
  the policy carries a declared default, so the repository is the
  whole sentence a test needs. No bench is wired behind this engine,
  so the enrolment answers nothing and the row lands with its note —
  which is the contract: a dark bench does not refuse a person."
  [engine repository]
  (:row (inv/create! engine :repo_policy {:repository repository}
                     {:principal a-person})))

(deftest the-cursor-is-kept-on-the-policy-row
  ;; ticket c07b581f
  (let [{:keys [state engine]} (rig [] {:ours? false})
        policy (policy! engine repo)
        on-the-row #(get-in (first (bench/policies engine :active))
                            [:data :forge_cursor])]
    (testing "no stored cursor and no change is no cursor"
      (is (nil? ((:load (forge/cursor-store engine)) repo))))
    (testing "a pass writes the repository's cursor on its policy"
      (pass! {:source (gh/fake-source state {:store (forge/cursor-store engine)})
              :engine engine})
      (is (= "2026-09-18T12:00:00Z" (on-the-row))))
    (testing "a source built again reads it back"
      (let [again (gh/fake-source state {:store (forge/cursor-store engine)})
            before (count (gh/requests state))]
        (forge/forge-poll again)
        (is (= "all" (get-in (first (drop before (gh/requests state)))
                             [:params :state])))
        (is (= "2026-09-18T12:00:00Z" (gh/cursor again repo)))))
    (testing "a row with none is seeded from the newest stored change"
      (an-adopted-change! engine {})
      (bench/mark-row! engine :repo_policy (str (:id policy))
                       {:forge_cursor nil} #{})
      (let [seed ((:load (forge/cursor-store engine)) repo)]
        (is (some? seed))
        (is (= seed (on-the-row)) "and written, so the next boot reads it")))))

(deftest the-source-polls-the-repositories-the-active-rows-name
  (let [state (gh/fake-state)
        engine (boot)
        source (gh/fake-source state {:repos-fn #(bench/active-repositories
                                                  engine)})]
    (gh/seed-pull! state repo a-pull-request {:files the-files})
    (gh/seed-pull! state "ckopsa/waymark-bench"
                   (assoc a-pull-request :number 7
                          :html_url "https://github.com/ckopsa/waymark-bench/pull/7"
                          :updated_at "2026-09-18T11:00:00Z")
                   {})

    (testing "no policy is no repository: the pass polls nothing"
      (is (= [] (:repositories (forge/forge-poll source)))))

    (testing "a policy a person writes IS the poll list, read at this pass"
      (policy! engine repo)
      (is (= [repo] (:repositories (forge/forge-poll source)))
          "no deploy and no environment variable between the row and
           the pass"))

    (testing "…and a second policy is polled beside the first"
      (policy! engine "ckopsa/waymark-bench")
      (is (= #{repo "ckopsa/waymark-bench"}
             (set (:repositories (forge/forge-poll source))))))

    (testing "a retired policy is not polled at all"
      (let [row (first (rows-of engine :repo_policy
                                {:repository "ckopsa/waymark-bench"}))]
        (inv/invoke! engine :repo_policy (str (:id row)) :retire {}
                     {:principal a-person}))
      (is (= [repo] (:repositories (forge/forge-poll source)))
          "the house stops working a repository with one tap"))))

;; ── a repository the token cannot read (ticket 116dfb0d) ───────────────

(defn- source-note-of [engine repository]
  (get-in (one-row engine :repo_policy {:repository repository})
          [:data :source_note]))

(deftest a-repository-the-source-cannot-read-says-so-on-its-policy-row
  (let [state (gh/fake-state)
        engine (boot)
        source (gh/fake-source state {:repos-fn #(bench/active-repositories
                                                  engine)})
        doors "ckopsa/waymark-doors"
        _ (an-adopted-change! engine {})]
    (gh/seed-pull! state repo a-pull-request {:files the-files})
    (gh/seed-pull! state doors
                   (assoc a-pull-request :number 7
                          :html_url "https://github.com/ckopsa/waymark-doors/pull/7")
                   {})
    (policy! engine repo)
    (policy! engine doors)
    (gh/refuse! state doors 403)

    (testing "a 403 on one repository's pulls listing lands on its row"
      (let [census (pass! {:source source :engine engine})
            note (source-note-of engine doors)]
        (is (= 1 (:noted census)))
        (is (str/starts-with?
             (str note)
             "GitHub answered 403 for GET /repos/ckopsa/waymark-doors/pulls at ")
            "the status, the route and the time")
        (is (nil? (source-note-of engine repo))
            "and the repository that answered carries no note")
        (is (some? (the-change engine))
            "the other repository's pass is not cost by the refusal")))

    (testing "a second refusal of the same kind writes nothing again"
      (let [before (source-note-of engine doors)]
        (is (= 0 (:noted (pass! {:source source :engine engine}))))
        (is (= before (source-note-of engine doors)))))

    (testing "a good pass clears the note"
      (gh/refuse! state doors nil)
      (is (= 1 (:noted (pass! {:source source :engine engine}))))
      (is (nil? (source-note-of engine doors))))

    (testing "a pass with nothing to say writes nothing"
      (is (= 0 (:noted (pass! {:source source :engine engine})))))))

(deftest a-source-note-is-the-engines-hand-alone
  (let [engine (boot)
        row (policy! engine "ckopsa/waymark-doors")]
    (is (thrown? Exception
                 (inv/invoke! engine :repo_policy (str (:id row)) :note_source
                              {:answered 403 :route "GET /repos/x/y/pulls"}
                              {:principal a-person}))
        "a person reads the note and never writes it")))

;; ── the wiring's own contract ───────────────────────────────────────

(deftest no-token-means-no-source
  (is (nil? (gh/from-env (constantly nil)))
      "no token, no source — and the wiring starts nothing")
  (let [src (gh/from-env {"FACTORY10_GITHUB_TOKEN" "ghp-not-a-real-token"}
                         (constantly ["ckopsa/waymark-bench"]))]
    (is (some? src) "the token alone configures it")
    (is (= ["ckopsa/waymark-bench"] ((:repos-fn src)))
        "and the repositories are the wiring's own reading of the
         rows, not a variable the environment holds")))

(deftest the-repositories-are-read-in-the-order-they-are-named
  (is (= ["ckopsa/waymark" "ckopsa/waymark-bench"]
         (gh/parse-repos "ckopsa/waymark, ckopsa/waymark-bench")))
  (is (= ["ckopsa/waymark"] (gh/parse-repos ""))
      "nothing named is the proving ground's own repository"))

;; ── a red change is a state (ticket d1742908) ──────────────────────────

(def ^:private a-new-head "9a8b7c6d5e4f30291827364554637281900aabbc")

;; Every world J6 moves on a verdict seeds the change's `pull_request`
;; row beside it (ticket e35e26cb), so J6 can read its verdict from the
;; row (ticket 0d2ff5b0). J6 still reads its own checks today: the row
;; is only there. Its fields are what the world seeded, read the way
;; the pull reads them, and written through the store, as the worlds
;; write their change: this rig has no Mirror to pull it.

(defn- seed-pull-request!
  "World `r`, with a `pull_request` row for its change and the change
  naming it. The forge is read by a fresh source over the world's fake,
  and the requests that read made are taken back off it, so a test
  that counts requests counts only its own pass's. `conflict-paths` is
  what the world's bench answers for a conflicted head. A world seeded
  again, after its head or its checks moved, rewrites the row it has."
  ([r] (seed-pull-request! r nil))
  ([{:keys [state engine] :as r} conflict-paths]
   (let [before (gh/requests state)
         source (gh/fake-source state)
         change (the-change engine)
         number (get-in change [:data :number])
         doc (forge-kit/forge-pull source repo number)
         head (some-> (:head_sha doc) str not-empty)
         mergeable (:mergeable doc)
         policy (some #(when (= repo (str (get-in % [:data :repository]))) %)
                      (bench/policies engine :active))
         runs-of (when (and head (satisfies? runs/ForgeRerun source))
                   #(runs/forge-runs source repo head))
         required (bench/required-checks-of policy)
         checks (when head (forge-kit/forge-checks source repo head))
         now (java.time.Instant/now)
         ;; the pull's own waits too: a required check queued past the
         ;; policy's limit is red on the row
         verdict (#'pull-request/waited
                  (pull-request/checks-verdict required checks mergeable runs-of)
                  policy required checks (str now) (delay false) {} now)
         _ (swap! state assoc :requests before)
         pull-id (or (some-> (get-in change [:data :pull_request]) str not-empty)
                     (str (:id (:row (inv/create! engine :pull_request
                                                  {:external_id (pull-request/external-id
                                                                 repo number)}
                                                  {:principal wm-mirror/system-observer
                                                   :mint? true})))))
         fields (cond-> (merge {:repository repo :number number
                                :head_sha head :checks_head head
                                :mergeable mergeable
                                :gh_state (:forge_state doc)
                                :change (str (:id change))}
                               verdict)
                  (= "conflicted" mergeable)
                  (assoc :conflict_paths conflict-paths
                         :conflict_paths_head head))
         ;; and what its `refresh_head` writes of the checks (ticket
         ;; 6a5c9f8a): the first pull of the head, its green, or its wait
         seen-at (or (when (= head (some-> (get-in change [:data :head_sha]) str))
                       (some-> (get-in change [:data :head_seen_at]) str not-empty))
                     (str now))
         pending? (= "pending" (:checks_verdict verdict))
         checks-facts {:green_head (when (= "green" (:checks_verdict verdict)) head)
                       :head_seen_at seen-at
                       :held_head (when pending? head)
                       :held_since (when pending? seen-at)
                       :pending_checks (when pending?
                                         (pull-request/pending-checks required checks))}
         st (:storage engine)]
     (store/with-tx st
       (fn [tx]
         (doseq [[kind id f] [[:pull_request pull-id #(merge % fields)]
                              ;; and the head and facts GitHub's events
                              ;; write on the change (plan 85b4462e)
                              [:change (:id change)
                               #(cond-> (assoc % :pull_request pull-id)
                                  head (assoc :head_sha head
                                              :mergeable mergeable)
                                  head (merge checks-facts))]]]
           (let [row (store/load-row st tx kind (str id) {})]
             (store/save-row! st tx kind
                              (-> row
                                  (update :data f)
                                  (assoc :version (inc (long (:version row)))))
                              (:version row))))))
     r)))

(defn- red-world
  "The rig, a policy for its repository, and its one change minted and
  then put at `submitted` with `rounds` spent. The head carries the
  rig's red `test10 (shard 3)` and green `check-queue`."
  [policy rounds]
  (let [{:keys [engine] :as r} (rig)]
    (inv/create! engine :repo_policy (merge {:repository repo} policy)
                 {:principal a-person})
    (pass! r)
    (let [id (str (:id (the-change engine)))
          st (:storage engine)]
      (store/with-tx st
        (fn [tx]
          (let [row (store/load-row st tx :change id {})]
            (store/save-row! st tx :change
                             (-> row
                                 (assoc :state :submitted
                                        :version (inc (long (:version row))))
                                 (assoc-in [:data :rounds] rounds))
                             (:version row))))))
    (seed-pull-request! r)))

(deftest a-red-required-check-moves-a-submitted-change-to-failing
  (let [{:keys [engine] :as r} (red-world {:required_checks ["test10 (shard 3)"
                                                              "check-queue"]}
                                          1)
        census (pass! r)
        row (the-change engine)]
    (is (= :failing (:state row)))
    (is (= ["test10 (shard 3)"] (get-in row [:data :failing_checks]))
        "the red names ride on the row, and the green one does not")
    (is (= 1 (:failing census)))
    (testing "a second pass on the same red head moves nothing"
      (let [census (pass! r)]
        (is (= :failing (:state (the-change engine))))
        (is (= 0 (:failing census)))))))

(deftest a-pending-required-check-does-not-move-the-change
  (let [{:keys [state engine] :as r}
        (red-world {:required_checks ["test10 (shard 3)" "gate"]} 1)]
    (testing "a required check that has not started is not finished"
      (pass! r)
      (is (= :submitted (:state (the-change engine)))))
    (testing "nor is one still running, whatever is red beside it"
      (gh/seed-check! state repo (get-in a-pull-request [:head :sha])
                      {:id 41752098400 :name "gate" :status "in_progress"})
      (pass! r)
      (is (= :submitted (:state (the-change engine)))))))

(deftest a-green-later-head-moves-a-failing-change-back
  (let [{:keys [state engine] :as r}
        (red-world {:required_checks ["test10 (shard 3)"]} 1)]
    (pass! r)
    (is (= :failing (:state (the-change engine))))
    (gh/seed-pull! state repo
                   (assoc a-pull-request
                          :head {:ref "waymark-fp62.6.4" :sha a-new-head}
                          :updated_at "2026-09-18T14:00:00Z")
                   {:files the-files :reviews the-reviews})
    (gh/seed-check! state repo a-new-head
                    {:id 41752098500 :name "test10 (shard 3)"
                     :status "completed" :conclusion "success"
                     :head_sha a-new-head})
    (seed-pull-request! r)
    (let [census (pass! r)
          row (the-change engine)]
      (is (= a-new-head (get-in row [:data :head_sha]))
          "a failing row follows the head it is read against")
      (is (= :submitted (:state row)))
      (is (nil? (get-in row [:data :failing_checks])))
      (is (= 1 (:recovered census))))))

(deftest a-merge-the-window-missed-is-read-before-a-red-moves-the-change
  ;; ticket a24a1e01: #562 merged green at a new head the listing never
  ;; showed, and the stored head's red returned its ticket to open
  (let [{:keys [state engine] :as r}
        (red-world {:required_checks ["test10 (shard 3)"]} 1)]
    (gh/seed-pull! state repo
                   (assoc a-pull-request
                          :head {:ref "waymark-fp62.6.4" :sha a-new-head}
                          :state "closed" :merged true
                          :merged_at "2026-01-01T00:00:00Z"
                          :updated_at "2026-01-01T00:00:00Z")
                   {:files the-files :reviews the-reviews})
    (let [census (pass! r)
          row (the-change engine)]
      (is (= :merged (:state row))
          "the pull request read by number merges the row")
      ;; the pass moves only the state: the head is the pull request's
      ;; `head_moved` to write (plan 85b4462e)
      (is (nil? (get-in row [:data :failing_checks]))
          "the stale head's red never lands on the row")
      (is (= 0 (:failing census))))))

(defn- line-why
  "The `line_why` one merge pass would write on `change`, its rig
  answering `red` to every merge (ticket 37e838b3)."
  [change]
  (let [by-repo {repo {:data {:repository repo :merge_by "house"
                              :required_checks ["test10 (shard 3)"]}}}
        ctx {:services
             {:bench-rpc
              (fn [_ {tool :name}]
                {:structuredContent
                 {:result (if (= tool "bench__merge")
                            {:state "red"}
                            {:state "updated"})}})}}
        lines (bench/merge-lines [change] by-repo (constantly nil))
        answers (atom {})]
    (bench/work-lines! ctx lines by-repo answers)
    (get-in (bench/line-marks lines @answers
                              (bench/parked-changes [change] by-repo))
            [:changes (str (:id change)) :line_why])))

(deftest a-green-resubmitted-head-is-stamped-and-leaves-red-in-the-line
  (testing "the pull request stamps green_head on a submitted head read
            green, and the merge line then reads the rig's red for it as
            behind (tickets baf76388, 6a5c9f8a)"
    (let [{:keys [state engine] :as r}
          (red-world {:required_checks ["test10 (shard 3)"]} 1)]
      (pass! r)
      (is (= :failing (:state (the-change engine))))
      (is (nil? (get-in (the-change engine) [:data :green_head]))
          "a red head is not stamped")
      (gh/seed-pull! state repo
                     (assoc a-pull-request
                            :head {:ref "waymark-fp62.6.4" :sha a-new-head}
                            :updated_at "2026-09-18T14:00:00Z")
                     {:files the-files :reviews the-reviews})
      (gh/seed-check! state repo a-new-head
                      {:id 41752098500 :name "test10 (shard 3)"
                       :status "completed" :conclusion "success"
                       :head_sha a-new-head})
      (seed-pull-request! r)
      (pass! r)
      (is (= a-new-head (get-in (the-change engine) [:data :green_head]))
          "its pull request stamps the green head")
      (let [row (the-change engine)]
        (is (= :submitted (:state row)))
        (is (= a-new-head (get-in row [:data :head_sha]))))
      (pass! r)
      (let [row (the-change engine)]
        (is (= a-new-head (get-in row [:data :green_head]))
            "the forge pass leaves the stamp as it is")
        (is (contains? #{"front" "behind"} (line-why row))
            "the rig's red on the stamped head leaves `red` on the next merge pass")))))

(deftest a-train-red-head-is-not-recovered-by-its-own-green
  ;; ticket 6566d32f: a merge train found this head red, so the head's
  ;; own green checks do not bring it back; a new head does
  (let [{:keys [state engine] :as r}
        (red-world {:required_checks ["check-queue"]} 1)
        id (str (:id (the-change engine)))
        head (get-in a-pull-request [:head :sha])]
    (inv/invoke! engine :change id :fail
                 {:failing_checks ["merge-train"]
                  :train_red_head head
                  :train_red "the train's test10 went red"}
                 {:principal mirror/source-principal})
    (is (= :failing (:state (the-change engine))))
    (testing "a green pass on the train's red head leaves it failing"
      (let [census (pass! r)
            row (the-change engine)]
        (is (= head (get-in row [:data :head_sha])))
        (is (= :failing (:state row)))
        (is (= head (get-in row [:data :train_red_head])))
        (is (= "the train's test10 went red" (get-in row [:data :train_red])))
        (is (= 0 (:recovered census)))))
    (testing "a green new head recovers it and clears the train's red"
      (gh/seed-pull! state repo
                     (assoc a-pull-request
                            :head {:ref "waymark-fp62.6.4" :sha a-new-head}
                            :updated_at "2026-09-18T14:00:00Z")
                     {:files the-files :reviews the-reviews})
      (gh/seed-check! state repo a-new-head
                      {:id 41752098500 :name "check-queue"
                       :status "completed" :conclusion "success"
                       :head_sha a-new-head})
      (seed-pull-request! r)
      (let [census (pass! r)
            row (the-change engine)]
        (is (= a-new-head (get-in row [:data :head_sha])))
        (is (= :submitted (:state row)))
        (is (nil? (get-in row [:data :train_red_head])))
        (is (nil? (get-in row [:data :train_red])))
        (is (= 1 (:recovered census)))))))

(deftest a-started-check-does-not-recover-a-train-red-head
  ;; ticket e05642b5: the change's recover refuses a head the train
  ;; proved red, so the started arm skips it the same as the green arm
  (let [head "abc123"
        row {:state :failing :data {:head_sha head :train_red_head head}}]
    (is (nil? (forge-kit/failing-move row {:verdict :started} 3 nil)))
    (is (nil? (forge-kit/failing-move row {:verdict :green} 3 nil)))
    (is (= [:recover {:head_sha "def456"}]
           (forge-kit/failing-move (assoc-in row [:data :head_sha] "def456")
                                   {:verdict :started} 3 nil))
        "a new head is not the train's red one")
    (is (= [:recover {:head_sha head}]
           (forge-kit/failing-move (update row :data dissoc :train_red_head)
                                   {:verdict :started} 3 nil))
        "a head no train judged recovers as before")))

(deftest the-red-head-on-the-last-round-sticks-the-change
  (let [{:keys [engine] :as r}
        (red-world {:required_checks ["test10 (shard 3)"] :rounds_per_change 2}
                   2)
        census (pass! r)
        row (the-change engine)]
    (is (= :stuck (:state row))
        "the seat's next submit would be refused at the ceiling, so the
         house stops here and a person reads it")
    (is (= ["test10 (shard 3)"] (get-in row [:data :failing_checks])))
    (is (= 1 (:stuck census)))
    (is (= 0 (:failing census)))))

(deftest a-policy-with-no-required-check-requires-every-finished-check
  (let [{:keys [engine] :as r} (red-world {} 1)]
    (pass! r)
    (is (= :failing (:state (the-change engine))))
    (is (= ["test10 (shard 3)"]
           (get-in (the-change engine) [:data :failing_checks])))))

(deftest the-verdict-reads-the-required-checks
  (let [done (fn [n c] {:check_name n :status "completed" :conclusion c})]
    (is (= {:verdict :red :names ["a"]}
           (forge/check-verdict ["a" "b"] [(done "a" "failure") (done "b" "success")])))
    (is (= {:verdict :green}
           (forge/check-verdict ["a"] [(done "a" "success") (done "x" "failure")]))
        "a check the policy does not require does not make it red")
    (is (nil? (forge/check-verdict ["a" "b"] [(done "a" "failure")]))
        "a required check that has not run is not finished")
    (is (= {:verdict :interrupted :names ["a"]}
           (forge/check-verdict ["a"] [(done "a" "cancelled")]))
        "a cancelled required check died without a verdict (ticket 22f91244)")
    (is (= {:verdict :interrupted :names ["b"]}
           (forge/check-verdict ["a" "b"] [(done "a" "success")
                                           (done "b" "cancelled")]))
        "and a cancel never counts as green")
    (is (= {:verdict :red :names ["a"]}
           (forge/check-verdict ["a" "b"] [(done "a" "failure")
                                           (done "b" "cancelled")]))
        "a red check beside a cancel is red")
    (is (nil? (forge/check-verdict [] []))
        "no check at all says nothing")))

(deftest a-green-re-run-answers-for-the-red-run-before-it
  ;; ticket 6bdaf6fe: `gate` went red, ran again on the same head and
  ;; went green, and the pass kept reading the red run
  (let [run (fn [id started c] {:check_name "gate" :status "completed"
                                :conclusion c :id id :started_at started})]
    (is (= {:verdict :green}
           (forge/check-verdict ["gate"]
                                [(run 101 "2026-09-28T01:37:00Z" "failure")
                                 (run 202 "2026-09-28T01:38:30Z" "success")]))
        "red then green on one head reads green")
    (is (= {:verdict :green}
           (forge/check-verdict ["gate"]
                                [(run 202 "2026-09-28T01:38:30Z" "success")
                                 (run 101 "2026-09-28T01:37:00Z" "failure")]))
        "whatever order the forge lists them in")
    (is (= {:verdict :red :names ["gate"]}
           (forge/check-verdict ["gate"]
                                [(run 101 "2026-09-28T01:37:00Z" "success")
                                 (run 202 "2026-09-28T01:38:30Z" "failure")]))
        "green then red reads red")
    (is (nil? (forge/check-verdict
               ["gate"]
               [(run 101 "2026-09-28T01:37:00Z" "failure")
                {:check_name "gate" :status "in_progress" :conclusion nil
                 :id 202 :started_at "2026-09-28T01:38:30Z"}]))
        "a re-run still running is not finished")))

;; ── a branch that conflicts with its base (ticket 5f12e772) ──────────

(def ^:private the-conflicts
  ["clone-mcp/clone_mcp/nomad.py" "clone-mcp/clone_mcp/server.py"])

(defn- conflict-world
  "One change at `submitted` with `rounds` spent, whose pull request
  GitHub reads with `mergeable-state` and whose one check is green. The
  bench behind the engine answers `conflicts` with `paths`, and
  `:asked` holds every tool it was called with."
  [mergeable-state paths policy rounds]
  (let [state (gh/fake-state)
        asked (atom [])
        rpc (fn [_method params]
              (swap! asked conj params)
              (when (and paths (= "bench__conflicts" (str (:name params))))
                {:structuredContent {:result {:paths paths}}}))
        engine (engine/engine {:storage (memory/storage)
                               :resources (vec (main/resources))
                               :services {:bench-rpc rpc}})
        _ (an-adopted-change! engine {})
        r {:state state :source (gh/fake-source state) :engine engine
           :asked asked}]
    (gh/seed-pull! state repo (assoc a-pull-request
                                     :mergeable_state mergeable-state)
                   {:files the-files :reviews the-reviews})
    (gh/seed-check! state repo (get-in a-pull-request [:head :sha])
                    a-green-check)
    (inv/create! engine :repo_policy (merge {:repository repo} policy)
                 {:principal a-person})
    (pass! r)
    (let [id (str (:id (the-change engine)))
          st (:storage engine)]
      (store/with-tx st
        (fn [tx]
          (let [row (store/load-row st tx :change id {})]
            (store/save-row! st tx :change
                             (-> row
                                 (assoc :state :submitted
                                        :version (inc (long (:version row))))
                                 (assoc-in [:data :rounds] rounds))
                             (:version row))))))
    (seed-pull-request! r paths)))

(defn- conflict-asks [{:keys [asked]}]
  (filterv #(= "bench__conflicts" (str (:name %))) @asked))

(deftest a-conflicted-submitted-change-goes-failing-with-its-paths
  (let [{:keys [engine] :as r} (conflict-world "dirty" the-conflicts {} 1)
        census (pass! r)
        row (the-change engine)]
    (is (= "conflicted" (get-in row [:data :mergeable])))
    (is (= :failing (:state row))
        "a conflicted pull request runs no fresh checks, and still fails")
    (is (= ["merge-conflict"] (get-in row [:data :failing_checks]))
        "its checks are green, so the conflict is the one red name")
    (is (= the-conflicts (get-in row [:data :conflicts]))
        "the paths the bench's trial merge named ride on the row")
    (is (= 1 (:failing census)))
    (is (empty? (conflict-asks r))
        "the paths are the pull_request row's, so the bench is not asked (J6 D1)")
    (testing "a second pass on the same conflict moves nothing and asks nothing"
      (let [census (pass! r)]
        (is (= :failing (:state (the-change engine))))
        (is (= 0 (:failing census)))
        (is (empty? (conflict-asks r)))))))

(deftest a-conflict-with-no-bench-answer-still-goes-failing
  (let [{:keys [engine] :as r} (conflict-world "dirty" nil {} 1)]
    (pass! r)
    (is (= :failing (:state (the-change engine))))
    (is (= ["merge-conflict"] (get-in (the-change engine) [:data :failing_checks])))
    (is (nil? (get-in (the-change engine) [:data :conflicts]))
        "a bench that cannot name the paths costs the paths, never the move")))

(deftest the-rigs-held-conflict-is-not-recovered-by-a-green
  ;; ticket bf8ba0a6: #706's merge was refused as not mergeable and the
  ;; merge pass wrote `conflicted` with every check green, and the next
  ;; poll read `blocked` at the same head. The merge pass fails the
  ;; change on the rig's word now (T6), and J6 reds nothing of its own
  ;; (J6 D1): what it must not do is recover it on the forge's green
  ;; (ticket 449fcdff)
  (let [{:keys [engine] :as r} (conflict-world "behind" the-conflicts {} 1)
        id (str (:id (the-change engine)))
        st (:storage engine)
        _ (is (= "blocked" (get-in (the-change engine) [:data :mergeable])))
        _ (bench/mark-row! engine :change id {:mergeable "conflicted"} #{})
        census (pass! r)]
    (is (= :submitted (:state (the-change engine)))
        "J6 reads the pull_request row alone, and that row is green")
    (is (= 0 (:failing census)))
    (is (empty? (conflict-asks r)) "and the rig is not asked")
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :change id {})]
          (store/save-row! st tx :change
                           (assoc row :state :failing
                                  :version (inc (long (:version row))))
                           (:version row)))))
    (let [census (pass! r)
          row (the-change engine)]
      (is (= "conflicted" (get-in row [:data :mergeable]))
          "the forge's `blocked` at the same head does not replace the conflict")
      (is (= :failing (:state row))
          "a green at the head the rig found conflicting does not recover it")
      (is (= 0 (:recovered census))))))

(deftest a-behind-change-is-not-failing
  (let [{:keys [engine] :as r} (conflict-world "behind" the-conflicts {} 1)
        census (pass! r)]
    (is (= :submitted (:state (the-change engine)))
        "behind is not a conflict: the merge brings the branch forward")
    (is (= 0 (:failing census)))
    (is (empty? (conflict-asks r)) "and the bench is not asked")))

(deftest a-resolved-green-head-returns-the-change-to-submitted
  (let [{:keys [state engine] :as r} (conflict-world "dirty" the-conflicts {} 1)]
    (pass! r)
    (is (= :failing (:state (the-change engine))))
    (gh/seed-pull! state repo
                   (assoc a-pull-request
                          :head {:ref "waymark-fp62.6.4" :sha a-new-head}
                          :mergeable_state "clean"
                          :updated_at "2026-09-18T14:00:00Z")
                   {:files the-files :reviews the-reviews})
    (gh/seed-check! state repo a-new-head
                    (assoc a-green-check :id 41752098600 :head_sha a-new-head))
    (seed-pull-request! r)
    (let [census (pass! r)
          row (the-change engine)]
      (is (= :submitted (:state row)))
      (is (nil? (get-in row [:data :failing_checks])))
      (is (nil? (get-in row [:data :conflicts])))
      (is (= 1 (:recovered census))))))

(deftest a-conflict-on-the-last-round-sticks-the-change
  (let [{:keys [engine] :as r}
        (conflict-world "dirty" the-conflicts {:rounds_per_change 2} 2)
        census (pass! r)
        row (the-change engine)]
    (is (= :stuck (:state row))
        "a conflict round counts against the ceiling like a red round")
    (is (= ["merge-conflict"] (get-in row [:data :failing_checks])))
    (is (= the-conflicts (get-in row [:data :conflicts])))
    (is (= 1 (:stuck census)))))

;; ── J6 reads its verdict from the pull_request row (plan 85b4462e, D1) ──

(defn- pull-says!
  "The change's `pull_request` row with `fields` merged in, as its own
  pull would write them."
  [engine fields]
  (let [st (:storage engine)
        id (str (get-in (the-change engine) [:data :pull_request]))]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :pull_request id {})]
          (store/save-row! st tx :pull_request
                           (-> row
                               (update :data merge fields)
                               (assoc :version (inc (long (:version row)))))
                           (:version row)))))))

(deftest j6-reads-its-verdict-from-the-pull-request-row
  (testing "a red row fails the change, and a green row at that head recovers it"
    (let [{:keys [engine] :as r} (red-world {:required_checks ["check-queue"]} 1)
          head (get-in a-pull-request [:head :sha])]
      (pull-says! engine {:checks_head head :checks_verdict "red"
                          :failing_checks ["gate"]})
      (pass! r)
      (is (= :failing (:state (the-change engine))))
      (is (= ["gate"] (get-in (the-change engine) [:data :failing_checks]))
          "the names are the row's, though the forge's check-queue is green")
      (pull-says! engine {:checks_verdict "green" :failing_checks nil})
      (let [census (pass! r)]
        (is (= :submitted (:state (the-change engine))))
        (is (= 1 (:recovered census))))))
  (testing "a row read at another head says nothing"
    (let [{:keys [engine] :as r} (red-world {:required_checks ["check-queue"]} 1)]
      (pull-says! engine {:checks_head a-new-head :checks_verdict "red"
                          :failing_checks ["gate"]})
      (pass! r)
      (is (= :submitted (:state (the-change engine)))))))

;; ── the old name's last rows (ticket 2d216859) ──────────────────────

(def ^:private a-landed-branch "bench/58e706d6")

(defn- the-unadopted [engine]
  (one-row engine :change {:change_id "ticket:58e706d6"}))

(defn- rewrite-unadopted! [engine f]
  (let [id (str (:id (the-unadopted engine)))
        st (:storage engine)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :change id {})]
          (store/save-row! st tx :change
                           (assoc (f row) :version (inc (long (:version row))))
                           (:version row)))))))

(defn- unadopted-world
  "One seat-born change at `submitted` with no number, whose bench
  feedback names `pr` for its branch, over a forge that lists no pull
  request at all, so nothing adopts it."
  [pr]
  (let [state (gh/fake-state)
        rpc (fn [_method params]
              (when (= "bench__feedback" (str (:name params)))
                {:structuredContent
                 {:result {:repo repo :landing {:state "landed"}
                           :pull_request pr
                           :pipelines [] :statuses [] :comments []}}}))
        engine (engine/engine {:storage (memory/storage)
                               :resources (vec (main/resources))
                               :services {:bench-rpc rpc}})]
    (inv/create! engine :repo_policy {:repository repo} {:principal a-person})
    (inv/create! engine :change {:change_id "ticket:58e706d6"
                                 :repository repo
                                 :title "A landed change"
                                 :base_branch "main"
                                 :head_branch a-landed-branch}
                 {:principal mirror/source-principal})
    (rewrite-unadopted! engine #(assoc % :state :submitted))
    {:state state :source (gh/fake-source state) :engine engine}))

(deftest the-boot-clears-the-old-name-once
  ;; ticket 2d216859: `landed_at` (renamed by ticket 8f2fac64) is
  ;; cleared at boot, and a stamp that still means something moves
  (let [{:keys [engine]} (unadopted-world nil)
        st (:storage engine)
        stamp "2026-09-20T10:00:00Z"
        _ (inv/create! engine :change {:change_id "ticket:5e7ded00"
                                       :repository repo
                                       :title "A superseded change"
                                       :base_branch "main"
                                       :head_branch "bench/5e7ded00"}
                       {:principal mirror/source-principal})
        old (one-row engine :change {:change_id "ticket:5e7ded00"})]
    (rewrite-unadopted! engine #(assoc-in % [:data :landed_at] stamp))
    (store/with-tx st
      (fn [tx]
        (store/save-row! st tx :change
                         (-> old
                             (assoc :state :superseded)
                             (assoc-in [:data :landed_at] stamp)
                             (assoc :version (inc (long (:version old)))))
                         (:version old))))
    (is (= 2 (forge/clear-landed-at! engine)))
    (is (= 0 (forge/clear-landed-at! engine)) "a second boot writes nothing")
    (let [row (the-unadopted engine)
          gone (one-row engine :change {:change_id "ticket:5e7ded00"})]
      (is (= stamp (get-in row [:data :unadopted_since]))
          "the submitted change keeps its stamp under the new name")
      (is (not (contains? (:data row) :landed_at)))
      (is (not (contains? (:data gone) :landed_at)))
      (is (nil? (get-in gone [:data :unadopted_since]))
          "the superseded change loses the value"))))

;; ── a run that died without a verdict (ticket 22f91244) ────────────

(def ^:private the-head (get-in a-pull-request [:head :sha]))

(def ^:private died-in-a-test
  [{:name "Set up job" :conclusion "success"}
   {:name "Run the tests" :conclusion "failure"}])

(def ^:private the-gate-decided
  [{:name "Set up job" :conclusion "success"}
   {:name "Decide" :conclusion "failure"}])

(deftest a-run-with-a-conclusion-is-finished
  ;; GitHub's own run shape: `updated_at` and no `completed_at`
  ;; (ticket 62e29b7b)
  (let [state (gh/fake-state)
        source (gh/fake-source state)
        run (fn [id] (first (filter #(= id (:run_id %))
                                    (forge/forge-runs source repo the-head))))]
    (gh/seed-run! state repo the-head
                  {:id 900 :workflow_id 11 :head_sha the-head
                   :status "in_progress" :conclusion "success"
                   :created_at "2026-10-09T14:00:00Z"
                   :run_started_at "2026-10-09T14:00:05Z"
                   :updated_at "2026-10-09T14:02:26Z"})
    (gh/seed-run! state repo the-head
                  {:id 901 :workflow_id 12 :head_sha the-head
                   :status "in_progress" :conclusion "cancelled"
                   :created_at "2026-10-09T14:00:00Z"
                   :run_started_at "2026-10-09T14:00:05Z"
                   :updated_at "2026-10-09T14:02:26Z"})
    (gh/seed-job! state repo 901
                  {:id 7001 :run_id 901 :name "test10 (shard 3)"
                   :status "completed" :conclusion "cancelled" :steps []})
    (gh/seed-run! state repo the-head
                  {:id 902 :workflow_id 13 :head_sha the-head
                   :status "in_progress" :conclusion nil
                   :created_at "2026-10-09T14:00:00Z"
                   :run_started_at "2026-10-09T14:00:05Z"
                   :updated_at "2026-10-09T14:01:00Z"})
    (gh/seed-job! state repo 902
                  {:id 7002 :run_id 902 :name "test10 (shard 3)"
                   :status "in_progress" :steps []})
    (is (= "completed" (:status (run 900)))
        "the conclusion speaks, not the status word")
    (is (= "success" (:conclusion (run 900))))
    (testing "a finished run that did not pass carries its jobs"
      (is (= "completed" (:status (run 901))))
      (is (= ["test10 (shard 3)"] (mapv :name (:jobs (run 901)))))
      (is (runs/interrupted-run? (run 901))))
    (testing "a run with no conclusion still waits"
      (is (= "in_progress" (:status (run 902))))
      (is (nil? (:conclusion (run 902))))
      (is (not (contains? (run 902) :jobs)))
      (is (not (runs/interrupted-run? (run 902)))))))

(deftest a-run-is-interrupted-only-with-no-red-job
  (let [run (fn [& jobs] {:status "completed" :jobs (vec jobs)})]
    (is (runs/interrupted-run? (run {:conclusion "timed_out"})))
    (is (runs/interrupted-run? (run {:conclusion "failure" :steps []}))
        "a job killed with no step failing died without a verdict")
    (is (not (runs/interrupted-run?
              (run {:conclusion "failure" :steps died-in-a-test}
                   {:conclusion "cancelled"})))
        "a fail-fast sibling cancelled beside a red test is red")
    (is (not (runs/interrupted-run? (run {:conclusion "success"}))))
    (is (not (runs/interrupted-run?
              {:status "in_progress" :jobs [{:conclusion "cancelled"}]}))
        "a run still running is not judged")))

;; the gate beside cancelled suites (ticket 2f8a03cd)

(def ^:private a-cancelled-suite
  {:name "test10 (shard 3)" :conclusion "cancelled" :steps []
   :started_at "2026-10-05T12:00:00Z" :completed_at "2026-10-05T12:04:00Z"})

(def ^:private the-red-gate
  {:name "gate" :conclusion "failure" :steps the-gate-decided
   :started_at "2026-10-05T12:04:05Z" :completed_at "2026-10-05T12:04:10Z"})

(deftest an-aggregator-is-red-only-beside-a-red-test
  (let [run (fn [& jobs] {:status "completed" :jobs (vec jobs)})
        a-red-test {:name "test10 (shard 1)" :conclusion "failure"
                    :steps died-in-a-test
                    :started_at "2026-10-05T12:00:00Z"
                    :completed_at "2026-10-05T12:03:50Z"}]
    (is (runs/interrupted-run?
         (run a-cancelled-suite {:name "quick" :conclusion "success"}
              the-red-gate))
        "a gate that began after the suites were cancelled reports them")
    (is (not (runs/interrupted-run? (run a-red-test a-cancelled-suite)))
        "a fail-fast sibling cancelled beside a real red test stays red")
    (is (not (runs/interrupted-run?
              (run a-red-test a-cancelled-suite the-red-gate)))
        "and so does the run when the gate is red beside that red test")
    (is (not (runs/interrupted-run?
              (run a-cancelled-suite
                   (dissoc the-red-gate :started_at))))
        "a red job whose times the forge did not say is read as a test")
    (is (not (runs/interrupted-run?
              (run {:name "quick" :conclusion "success"} the-red-gate)))
        "a red job with no interrupted job beside it is red")))

;; ── commit statuses and late reds (ticket 3aca3ae8) ─────────────────

(defn- a-status [state]
  {:context "quality-gate" :state state
   :target_url "https://gate.example/runs/1"
   :created_at "2026-09-18T13:40:00Z" :updated_at "2026-09-18T13:50:00Z"})

(deftest a-failing-commit-status-makes-the-change-failing
  (let [{:keys [state engine] :as r}
        (red-world {:required_checks ["quality-gate"]} 1)
        sha (get-in a-pull-request [:head :sha])]
    (gh/seed-status! state repo sha (a-status "failure"))
    (seed-pull-request! r)
    (pass! r)
    (let [row (the-change engine)]
      (is (= :failing (:state row)))
      (is (= ["quality-gate"] (get-in row [:data :failing_checks]))
          "the status's context is the red name"))
    (let [run (one-row engine :ci_run
                       {:run_id (gh/status-id repo sha "quality-gate")})]
      (is (= :red (:state run)) "the red status is a red run, keyed by head and context")
      (is (= "quality-gate" (get-in run [:data :check_name]))))))

(deftest a-pending-commit-status-keeps-the-change-unknown
  (let [{:keys [state engine] :as r}
        (red-world {:required_checks ["quality-gate"]} 1)]
    (gh/seed-status! state repo (get-in a-pull-request [:head :sha])
                     (a-status "pending"))
    (pass! r)
    (is (= :submitted (:state (the-change engine))))))

(deftest a-check-that-goes-red-after-the-push-mints-one-run
  (let [{:keys [state engine] :as r}
        (red-world {:required_checks ["gate"]} 1)
        sha (get-in a-pull-request [:head :sha])
        run-id (gh/run-id repo 41752098600)]
    (is (nil? (one-row engine :ci_run {:run_id run-id})))
    ;; the check finishes; the pull request's updated_at does not move
    (gh/seed-check! state repo sha
                    {:id 41752098600 :name "late" :status "completed"
                     :conclusion "failure" :head_sha sha
                     :details_url "https://github.com/ckopsa/waymark/actions/runs/900/job/7001"})
    (let [first-pass (pass! r)
          second-pass (pass! r)]
      (is (= 1 (:runs-minted first-pass)))
      (is (= 0 (:runs-minted second-pass)) "and not twice")
      (is (= 1 (count (rows-of engine :ci_run {:run_id run-id}))))
      (is (= (str (:id (the-change engine)))
             (str (get-in (one-row engine :ci_run {:run_id run-id})
                          [:data :change])))))))

;; ── a head that lacks a required check (ticket 498a089e) ────────────

(defn- missing-world
  "One change at `submitted` under a policy that requires `gate`, with
  `checks` seeded on its head."
  [checks]
  (let [state (gh/fake-state)
        engine (boot)
        _ (an-adopted-change! engine {})
        r {:state state :source (gh/fake-source state) :engine engine}]
    (gh/seed-pull! state repo a-pull-request
                   {:files the-files :reviews the-reviews})
    (doseq [check checks]
      (gh/seed-check! state repo the-head check))
    (inv/create! engine :repo_policy
                 {:repository repo :required_checks ["gate"]}
                 {:principal a-person})
    (pass! r)
    (let [id (str (:id (the-change engine)))
          st (:storage engine)]
      (store/with-tx st
        (fn [tx]
          (let [row (store/load-row st tx :change id {})]
            (store/save-row! st tx :change
                             (-> row
                                 (assoc :state :submitted
                                        :version (inc (long (:version row))))
                                 (assoc-in [:data :rounds] 1))
                             (:version row))))))
    (seed-pull-request! r)))

(deftest a-rejected-workflow-fails-the-change-it-would-have-checked
  ;; ticket 004188aa: GitHub answers a workflow file it cannot parse
  ;; with a failed run that has no job, and no check run at all
  (let [{:keys [state engine] :as r} (missing-world [])
        file ".github/workflows/tests.yml"]
    (gh/seed-run! state repo the-head
                  {:id 39147178078 :workflow_id 21 :head_sha the-head
                   :status "completed" :conclusion "failure"
                   :name file :path file
                   :html_url (str "https://github.com/" repo
                                  "/actions/runs/39147178078")})
    (seed-pull-request! r)
    (pass! r)
    (let [row (the-change engine)]
      (is (= :failing (:state row))
          "the change leaves the line rather than waiting on checks that never come")
      (is (= [file] (get-in row [:data :failing_checks]))
          "the red name is the workflow file GitHub rejected"))
    (testing "a failed run with jobs is not a rejected workflow"
      (is (empty? (forge/rejected-verdict
                   [{:check_name "gate" :status "completed"
                     :conclusion "failure"}]))))))

(deftest missing-checks-counts-only-checks-with-no-run
  (is (= ["tests"] (forge/missing-checks ["gate" "tests" "gate"]
                                         [{:check_name "gate" :status "queued"}]))))

;; ── a held change says why (ticket 5b1c2c88) ────────────────────────

(deftest a-change-waiting-on-checks-names-the-ones-without-a-verdict
  (let [checks [{:check_name "gate" :status "in_progress"}
                {:check_name "lint" :status "completed" :conclusion "success"}
                {:check_name "scan" :status "completed" :conclusion "startup_failure"}]]
    (is (= [{:name "gate" :status "in_progress" :conclusion nil}
            {:name "scan" :status "completed" :conclusion "startup_failure"}
            {:name "tests" :status nil :conclusion nil}]
           (forge/held-checks ["gate" "lint" "scan" "tests"] checks)))
    (is (= (str "the checks of change-1 at abc have no verdict: "
                "gate (status in_progress, conclusion none), "
                "scan (status completed, conclusion startup_failure), tests (no run)")
           (forge/held-why "change-1" "abc" ["gate" "lint" "scan" "tests"]
                           checks nil)))
    (is (= [] (forge/held-checks ["lint"] checks))
        "a check with a verdict is not named")
    (is (= [] (forge/held-checks
               ["old" "wait"]
               [{:check_name "old" :status "completed" :conclusion "stale"}
                {:check_name "wait" :status "completed"
                 :conclusion "action_required"}]))
        "the kit's table reads these two as interrupted, which is a verdict")))

(deftest a-change-held-only-because-it-is-behind-its-base-says-so
  (let [green [{:check_name "gate" :status "completed" :conclusion "success"}]]
    (is (= (str "change-1 at abc is held only because it is behind its "
                "base; its required checks are green")
           (forge/held-why "change-1" "abc" ["gate"] green true)))
    (is (nil? (forge/held-why "change-1" "abc" ["gate"] green false))
        "a green head level with its base has nothing to say")
    (is (re-find #"have no verdict: gate \(status queued"
                 (forge/held-why "change-1" "abc" ["gate"]
                                 [{:check_name "gate" :status "queued"}] true))
        "a head that still waits on a check is not held only by its base")))

(deftest a-held-change-is-due-one-time-past-the-bound
  (let [t0 (java.time.Instant/parse "2026-10-09T10:00:00Z")
        at #(.plusSeconds t0 (* 60 (long %)))
        [s1 due1] (forge/held-due {} "c" "abc" (at 0) 20)
        [s2 due2] (forge/held-due s1 "c" "abc" (at 19) 20)
        [s3 due3] (forge/held-due s2 "c" "abc" (at 20) 20)
        [s4 due4] (forge/held-due s3 "c" "abc" (at 40) 20)
        [s5 due5] (forge/held-due s4 "c" "def" (at 41) 20)
        [_ due6] (forge/held-due s5 "c" "def" (at 61) 20)]
    (is (= [false false true false false true]
           [due1 due2 due3 due4 due5 due6])
        "due when the wait reaches the bound, one time for each head")))

;; the same line, through the pass itself (ticket da550baa)

(def ^:private a-held-green
  {:id 41752098951 :name "gate" :status "completed" :conclusion "success"
   :head_sha the-head})

(defn- held-log-world
  "`missing-world` with `checks` on its head, an engine on a clock the
  test holds, and the lines the pass logs."
  [checks]
  (let [clock (atom (java.time.Instant/now))]
    (-> (missing-world checks)
        (update :engine assoc :now-fn #(deref clock))
        (assoc :clock clock :lines (atom [])))))

(defn- held-pass! [{:keys [source engine lines]}]
  (forge/pass! {:source source :engine engine
                :log-fn (fn [& parts] (swap! lines conj (apply str parts)))}))

(defn- minutes-later! [{:keys [clock]} n]
  (swap! clock (fn [^java.time.Instant t] (.plusSeconds t (* 60 (long n))))))

(defn- held-lines [{:keys [lines]}]
  (filterv #(re-find #"have no verdict|is held only because" %) @lines))

(deftest a-pass-logs-a-running-required-check-one-time-past-the-bound
  (let [{:keys [engine] :as r}
        (held-log-world [{:id 41752098950 :name "gate" :status "in_progress"
                      :head_sha the-head}])
        change-id (get-in (the-change engine) [:data :change_id])]
    (held-pass! r)
    (is (= [] (held-lines r)) "the first pass starts the wait and says nothing")
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (is (= [(str "the checks of " change-id " at " the-head
                 " have no verdict: gate (status in_progress, conclusion none)")]
           (held-lines r)))
    (is (= :submitted (:state (the-change engine))))
    (testing "a later pass on the same head does not say it again"
      (minutes-later! r (inc forge/held-log-minutes))
      (held-pass! r)
      (is (= 1 (count (held-lines r)))))))

(deftest a-pass-logs-a-green-head-that-is-behind-its-base
  (let [{:keys [engine] :as r} (held-log-world [a-held-green])
        change-id (get-in (the-change engine) [:data :change_id])]
    (held-pass! r)
    (is (= [] (held-lines r)))
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (is (= [(str change-id " at " the-head " is held only because it is behind"
                 " its base; its required checks are green")]
           (held-lines r))
        "the fake's compare answers diverged for a head with no ancestor seeded")
    (is (= :submitted (:state (the-change engine))))))

;; ticket 14e72c6e: #1095 stood green, with no check missing and no
;; `behind_base` on its row, so the merge line read the rig's `waiting`
;; as a wait on checks and never brought it up to date
(deftest a-green-head-held-behind-its-base-says-so-on-the-row
  (let [{:keys [engine] :as r} (held-log-world [a-held-green])
        waiting {:state "waiting"}]
    (held-pass! r)
    (let [row (the-change engine)]
      (is (= the-head (get-in row [:data :green_head])))
      (is (nil? (get-in row [:data :behind_base]))
          "no compare is made inside the bound")
      (is (= "waiting" (:state (bench/head-answer row waiting)))))
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (let [row (the-change engine)]
      (is (true? (get-in row [:data :behind_base])))
      (is (some? (get-in row [:data :base_compared_at])))
      (is (nil? (get-in row [:data :checks_wait_reason]))
          "a head only behind its base waits on no check")
      (is (nil? (get-in row [:data :held_reason])) "nor is it held")
      (is (= "behind" (:state (bench/head-answer row waiting)))
          "the merge line brings the front up to date"))))

(deftest a-compare-that-throws-does-not-cost-the-held-change-its-pass
  (let [{:keys [engine lines state] :as r} (held-log-world [a-held-green])
        asked (fn [] (count (filter #(re-find #"/compare/" (str (:path %)))
                                    (gh/requests state))))]
    (gh/fail-path! state #"/compare/")
    (held-pass! r)
    (minutes-later! r (inc forge/held-log-minutes))
    (let [census (held-pass! r)
          row (the-change engine)]
      (is (= 1 (asked)) "the due pass asked the forge for the compare")
      (is (= [] (held-lines r)) "a forge that does not answer says nothing")
      (is (not-any? #(re-find #"did not move the change" %) @lines))
      (is (= 0 (:refused census)))
      (is (= :submitted (:state row)))
      (is (= the-head (get-in row [:data :green_head]))
          "the rest of the change's pass ran"))))

;; ticket 443bcbc2: the due pass was the one compare a held green head
;; got, so one forge error left it waiting on `checks` for good
(deftest a-compare-that-throws-on-the-due-pass-is-asked-again
  (let [{:keys [engine state] :as r} (held-log-world [a-held-green])
        change-id (get-in (the-change engine) [:data :change_id])]
    (gh/fail-path! state #"/compare/")
    (held-pass! r)
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (is (nil? (get-in (the-change engine) [:data :behind_base]))
        "the due compare threw and wrote nothing")
    (gh/fail-path! state nil)
    (minutes-later! r 1)
    (held-pass! r)
    (let [row (the-change engine)]
      (is (true? (get-in row [:data :behind_base])))
      (is (some? (get-in row [:data :base_compared_at])))
      (is (= [(str change-id " at " the-head " is held only because it is"
                   " behind its base; its required checks are green")]
             (held-lines r))
          "the line is said on the pass that got the answer")
      (is (= "behind" (:state (bench/head-answer row {:state "waiting"})))))
    (testing "a head that is behind is not compared or said again"
      (let [asked (fn [] (count (filter #(re-find #"/compare/" (str (:path %)))
                                        (gh/requests state))))
            before (asked)]
        (minutes-later! r (inc forge/held-compare-minutes))
        (held-pass! r)
        (is (= before (asked)))
        (is (= 1 (count (held-lines r))))))))

(deftest a-held-green-head-level-with-its-base-is-compared-again-later
  (let [{:keys [engine state] :as r} (held-log-world [a-held-green])
        asked (fn [] (count (filter #(re-find #"/compare/" (str (:path %)))
                                    (gh/requests state))))]
    (gh/seed-ancestor! state repo the-head "main")
    (held-pass! r)
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (is (false? (get-in (the-change engine) [:data :behind_base])))
    (is (= [] (held-lines r)))
    (let [before (asked)]
      (minutes-later! r 1)
      (held-pass! r)
      (is (= before (asked)) "not again inside the bound"))
    ;; the base moves: the head no longer holds it
    (swap! state update-in [:repos repo :ancestors] dissoc the-head)
    (minutes-later! r forge/held-compare-minutes)
    (held-pass! r)
    (is (true? (get-in (the-change engine) [:data :behind_base])))
    (is (= 1 (count (held-lines r))) "and the pass says so one time")))

(deftest a-held-head-is-compared-again-by-what-the-compare-answered
  (let [now (java.time.Instant/parse "2026-10-10T10:00:00Z")]
    (is (nil? (forge/held-again true now)))
    (is (= now (forge/held-again nil now)) "no answer: the next pass")
    (is (= (.plusSeconds now (* 60 (long forge/held-compare-minutes)))
           (forge/held-again false now)))))

;; ticket dfd181ae: a compare route that kept throwing cost a held green
;; head one request on every pass, and no line said so
(deftest a-compare-that-keeps-throwing-waits-longer-each-time
  (let [now (java.time.Instant/parse "2026-10-10T10:00:00Z")
        later (fn [m] (.plusSeconds now (* 60 (long m))))]
    (is (= [0 0 1 2 4 8 16 forge/held-compare-minutes
            forge/held-compare-minutes]
           (mapv forge/held-backoff [0 1 2 3 4 5 6 7 40])))
    (is (= now (forge/held-again nil now 1)) "one error: the next pass")
    (is (= (later 2) (forge/held-again nil now 3)))
    (is (= (later forge/held-compare-minutes) (forge/held-again nil now 9)))
    (is (nil? (forge/held-again true now 9)))
    (is (= (later forge/held-compare-minutes)
           (forge/held-again false now 9)))))

(deftest a-compare-that-keeps-throwing-is-asked-a-bounded-number-of-times
  (let [{:keys [engine lines state] :as r} (held-log-world [a-held-green])
        asked (fn [] (count (filter #(re-find #"/compare/" (str (:path %)))
                                    (gh/requests state))))
        failing (fn [] (filterv #(re-find #"has thrown" %) @lines))]
    (gh/fail-path! state #"/compare/")
    (held-pass! r)
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (is (= 1 (asked)))
    (minutes-later! r 1)
    (held-pass! r)
    (is (= 2 (asked)) "one error is asked again on the next pass")
    (is (= [] (failing)))
    (held-pass! r)
    (is (= 2 (asked)) "the second error in a row waits")
    ;; an hour of passes a minute apart, the forge failing throughout
    (dotimes [_ 59]
      (minutes-later! r 1)
      (held-pass! r))
    (is (= 8 (asked)) "the waits double up to the bound")
    (is (= 1 (count (failing))) "and the log says so one time")
    (is (re-find (re-pattern (str "has thrown " forge/held-compare-errors
                                  " passes in a row"))
                 (first (failing))))
    (is (nil? (get-in (the-change engine) [:data :behind_base])))
    (testing "a forge that answers again is heard at the next compare"
      (gh/fail-path! state nil)
      (minutes-later! r forge/held-compare-minutes)
      (held-pass! r)
      (is (true? (get-in (the-change engine) [:data :behind_base])))
      (is (= 1 (count (held-lines r)))))))

;; ticket 72e75ad9: a green head makes no failing door, so a merge no
;; event reported was never read and the row sat `submitted`
(defn- asked-of [{:keys [state]} pattern]
  (count (filter #(re-find pattern (str (:path %))) (gh/requests state))))

(deftest a-held-green-head-whose-pull-request-merged-walks-merged
  (let [{:keys [engine state] :as r} (held-log-world [a-held-green])]
    (held-pass! r)
    (is (= the-head (get-in (the-change engine) [:data :green_head])))
    (gh/seed-pull! state repo (assoc a-merged-pull-request
                                     :updated_at (:updated_at a-pull-request))
                   {:files the-files :reviews the-reviews})
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (let [row (the-change engine)]
      (is (= :merged (:state row)) "the due pass read the merge")
      (is (every? #(nil? (get-in row [:data %]))
                  [:held_head :held_since :checks_wait_reason :held_again :held_threw])
          "and its held fields are cleared")
      (is (= 0 (asked-of r #"/compare/")) "a merged head is not compared"))))

(deftest a-held-green-head-whose-pull-request-is-open-is-compared-as-before
  (let [{:keys [engine] :as r} (held-log-world [a-held-green])]
    (held-pass! r)
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (let [row (the-change engine)]
      (is (= 1 (asked-of r #"/compare/")))
      (is (= :submitted (:state row)))
      (is (= the-head (get-in row [:data :green_head])) "it is still held green")
      (is (true? (get-in row [:data :behind_base])))
      (is (= 1 (count (held-lines r)))))))

(deftest a-pull-request-read-that-throws-leaves-the-held-compare-as-it-was
  (let [{:keys [engine lines state] :as r} (held-log-world [a-held-green])]
    (held-pass! r)
    (gh/fail-path! state #"/pulls/31$")
    (gh/fail-path! state #"/compare/")
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (let [row (the-change engine)]
      (is (some #(re-find #"could not be read again" %) @lines))
      (is (= 1 (asked-of r #"/compare/")) "the compare still ran")
      (is (= 1 (get-in row [:data :held_threw])) "and its throw is counted")
      (is (= :submitted (:state row))))))

;; ticket 93072465: the count and the next compare's time were kept in
;; the process alone, so a restart asked at once and said the line again
(deftest a-failing-compare-keeps-its-backoff-across-a-restart
  (let [{:keys [engine lines state] :as r} (held-log-world [a-held-green])
        asked (fn [] (count (filter #(re-find #"/compare/" (str (:path %)))
                                    (gh/requests state))))
        failing (fn [] (filterv #(re-find #"has thrown" %) @lines))]
    (gh/fail-path! state #"/compare/")
    (held-pass! r)
    (minutes-later! r (inc forge/held-log-minutes))
    (held-pass! r)
    (dotimes [_ 2]
      (minutes-later! r 1)
      (held-pass! r))
    (is (= 3 (asked)))
    (is (= 1 (count (failing))))
    (is (= 3 (get-in (the-change engine) [:data :held_threw])))
    ;; a restart: the process forgets every head it saw
    (reset! @#'forge/held-seen {})
    (held-pass! r)
    (minutes-later! r 1)
    (held-pass! r)
    (is (= 3 (asked)) "the wait the third error earned is still kept")
    (minutes-later! r 1)
    (held-pass! r)
    (is (= 4 (asked)) "and the compare is asked when it ends")
    (is (= 4 (get-in (the-change engine) [:data :held_threw]))
        "the count goes on from the row's")
    (is (= 1 (count (failing))) "the failing line is not said again")))

;; ── the held reason and its clock are on the row (ticket b256d053) ──

(defn- held-world
  "`missing-world` with `gate` still running, read by an engine whose
  clock is `clock`."
  [clock]
  (-> (missing-world [{:id 41752098850 :name "gate" :status "in_progress"
                       :head_sha the-head}])
      (update :engine assoc :now-fn (fn [] @clock))))

(defn- logging-pass!
  "One pass whose log lines land in `lines`."
  [{:keys [source engine]} lines]
  (forge/pass! {:source source :engine engine
                :log-fn (fn [& xs] (swap! lines conj (apply str xs)))}))

(deftest the-checks-wait-reason-is-written-on-the-change
  (let [t0 (java.time.Instant/now)
        clock (atom t0)
        {:keys [engine] :as r} (held-world clock)
        lines (atom [])]
    (pass! r)
    (let [row (the-change engine)]
      (is (= :submitted (:state row)))
      (is (= the-head (get-in row [:data :held_head])))
      (is (nil? (get-in row [:data :checks_wait_reason]))
          "nothing is said inside the bound"))
    (reset! clock (.plusSeconds t0 (* 60 (inc forge/held-log-minutes))))
    (logging-pass! r lines)
    (let [reason (get-in (the-change engine) [:data :checks_wait_reason])]
      (is (re-find #"have no verdict: gate \(status in_progress" (str reason)))
      (is (nil? (get-in (the-change engine) [:data :held_reason]))
          "a head waiting on its checks is not held")
      (is (some #{reason} @lines) "the row carries the line the pass logs"))))

(deftest the-held-wait-counts-from-the-row-across-a-restart
  (let [t0 (java.time.Instant/now)
        clock (atom t0)
        {:keys [engine] :as r} (held-world clock)
        lines (atom [])]
    (pass! r)
    (let [since (get-in (the-change engine) [:data :held_since])]
      (is (some? since) "its pull request stamps the wait")
      (is (= (get-in (the-change engine) [:data :head_seen_at]) since)
          "from its first pull of the head")
      ;; a restart: the process forgets every head it saw
      (reset! @#'forge/held-seen {})
      (reset! clock (.plusSeconds t0 (* 60 (inc forge/held-log-minutes))))
      (logging-pass! r lines)
      (is (= since (get-in (the-change engine) [:data :held_since]))
          "the wait is not started again"))
    (is (some #(re-find #"have no verdict: gate" %) @lines)
        "due on the first pass after the restart")))

;; ── a required check nobody runs (ticket bc3ff12c) ──────────────────

(defn- minutes-ago [n]
  (str (.minusSeconds (java.time.Instant/now) (* 60 (long n)))))

(deftest a-required-check-queued-past-the-limit-fails-the-change
  (let [{:keys [state engine] :as r}
        (missing-world [{:id 41752098900 :name "gate" :status "queued"
                         :head_sha the-head :started_at (minutes-ago 45)}])]
    (pass! r)
    (let [row (the-change engine)
          names (get-in row [:data :failing_checks])]
      (is (= "failing" (name (:state row))))
      (is (= 1 (count names)) "one finding")
      (is (re-matches
           #"check gate has been queued \d+ minutes; no runner took it \(check its runs-on\)"
           (str (first names)))))
    (testing "a run that starts clears the finding on the row, whose own
              checks_started door recovers the change (J6 D1)"
      (gh/seed-check! state repo the-head
                      {:id 41752098901 :name "gate" :status "in_progress"
                       :head_sha the-head :started_at (minutes-ago 1)})
      (seed-pull-request! r)
      (pass! r)
      (let [pull (one-row engine :pull_request {})]
        (is (= "pending" (get-in pull [:data :checks_verdict])))
        (is (empty? (get-in pull [:data :failing_checks])))))))

(deftest a-required-check-queued-under-the-limit-stays-submitted
  (let [{:keys [engine] :as r}
        (missing-world [{:id 41752098900 :name "gate" :status "queued"
                         :head_sha the-head :started_at (minutes-ago 10)}])]
    (pass! r)
    (let [row (the-change engine)]
      (is (= "submitted" (name (:state row))))
      (is (empty? (get-in row [:data :failing_checks]))))))

(deftest the-queued-verdict-reads-the-limit-it-is-given
  (let [now (java.time.Instant/now)
        queued {:check_name "gate" :status "queued"
                :started_at (minutes-ago 45)}]
    (is (= :red (:verdict (pull-request/queued-verdict ["gate"] [queued] 30 now))))
    (is (nil? (pull-request/queued-verdict ["gate"] [queued] 60 now))
        "the policy's own limit, not the default")
    (is (nil? (pull-request/queued-verdict ["gate"]
                                           [(assoc queued :status "in_progress")]
                                           30 now))
        "a running check is not a queued one")
    (is (nil? (pull-request/queued-verdict ["gate"] [(dissoc queued :started_at)]
                                           30 now))
        "a check that does not say when it was queued is not judged")))

(deftest the-missing-verdict-names-a-required-check-with-no-run
  (let [now (java.time.Instant/now)
        since (minutes-ago 45)
        ran {:check_name "gate" :status "completed" :conclusion "success"
             :started_at (minutes-ago 40)}]
    (let [v (pull-request/missing-verdict ["gate" "lint"] [ran] 30 since now)]
      (is (= :red (:verdict v)))
      (is (= 1 (count (:names v))) "only the check with no run")
      (is (re-matches
           #"check lint has no run after \d+ minutes; no workflow started it \(check its triggers\)"
           (str (first (:names v))))))
    (is (nil? (pull-request/missing-verdict ["gate" "lint"] [ran] 60 since now))
        "the policy's own limit, not the default")
    (is (nil? (pull-request/missing-verdict ["gate"] [ran] 30 since now))
        "a check that has a run is not missing")
    (is (nil? (pull-request/missing-verdict ["gate" "lint"] [ran] 30 nil now))
        "a head that does not say since when it waits is not judged")))

;; ── GitHub's rate limit (ticket cdba1f6a) ───────────────────────────
;;
;; A 403 that says the hour's calls are spent is a throttle and not a
;; refusal: the pass that meets it stops, moves no row and notes no
;; repository unreadable, and every call waits for the reset. An answer
;; GitHub has not changed is a 304, which costs nothing, and the census
;; line carries what is left.

(def ^:private limit-hour (java.time.Instant/parse "2026-10-01T20:42:00Z"))
(def ^:private limit-reset (java.time.Instant/parse "2026-10-01T21:00:00Z"))
(def ^:private limit-head (get-in a-pull-request [:head :sha]))

(defn- limit-rig
  "A fresh in-memory GitHub with one open pull request and one green
  check on its head, the real source over it on a clock the test holds,
  and an engine whose one policy names the repository."
  []
  (let [state (gh/fake-state)
        engine (boot)
        clock (atom limit-hour)
        source (gh/fake-source state {:repos-fn #(bench/active-repositories
                                                  engine)
                                      :now-fn #(deref clock)})]
    (gh/seed-pull! state repo a-pull-request {:files the-files})
    (gh/seed-check! state repo limit-head a-green-check)
    (an-adopted-change! engine {})
    (policy! engine repo)
    {:state state :engine engine :source source :clock clock
     :lines (atom [])}))

(deftest a-check-with-a-conclusion-and-an-end-is-finished
  ;; the shape GitHub left on ckopsa/waymark#1095: check run
  ;; 113855755394 (ticket 9112f56e)
  (let [{:keys [state source]} (limit-rig)
        gate {:id 113855755394 :name "gate" :status "in_progress"
              :conclusion "success" :head_sha limit-head
              :started_at "2026-10-09T14:02:24Z"
              :completed_at "2026-10-09T14:02:26Z"}
        named (fn [n] (first (filter #(= n (:check_name %))
                                     (forge/forge-checks source repo
                                                         limit-head))))]
    (gh/seed-check! state repo limit-head gate)
    (is (= "completed" (:status (named "gate")))
        "the conclusion and the end speak, not the status word")
    (is (= {:verdict :green}
           (forge/check-verdict ["gate"]
                                (forge/forge-checks source repo limit-head))))

    (testing "a run with no conclusion is still running"
      (gh/seed-check! state repo limit-head
                      {:id 113855755401 :name "image" :status "in_progress"
                       :head_sha limit-head
                       :started_at "2026-10-09T14:02:24Z"})
      (is (= "in_progress" (:status (named "image"))))
      (is (nil? (forge/check-verdict
                 ["gate" "image"]
                 (forge/forge-checks source repo limit-head)))))))

(deftest an-actions-run-or-job-with-a-conclusion-and-an-end-is-finished
  ;; the same shape, read through Actions where the check-runs route
  ;; refuses the token (ticket b5e8bbd8)
  (let [state (gh/fake-state)
        source (gh/fake-source state)
        ended {:status "in_progress"
               :started_at "2026-10-09T14:02:24Z"
               :completed_at "2026-10-09T14:02:26Z"}
        named (fn [n] (first (filter #(= n (:check_name %))
                                     (forge/forge-checks source repo
                                                         limit-head))))]
    (gh/checks-answer! state repo 403)
    (gh/seed-run! state repo limit-head
                  (assoc ended :id 900 :workflow_id 11 :head_sha limit-head
                         :conclusion "failure"))
    (gh/seed-job! state repo 900
                  (assoc ended :id 7001 :run_id 900 :name "gate"
                         :conclusion "failure" :head_sha limit-head))
    (gh/seed-job! state repo 900
                  {:id 7002 :run_id 900 :name "image" :status "in_progress"
                   :head_sha limit-head
                   :started_at "2026-10-09T14:02:24Z"})
    (testing "a job"
      (is (= "completed" (:status (named "gate")))
          "the conclusion and the end speak, not the status word")
      (is (= "in_progress" (:status (named "image")))
          "a job with no conclusion is still running"))
    (testing "a workflow run"
      (let [[run] (forge/forge-runs source repo limit-head)]
        (is (= "completed" (:status run)))
        (is (= ["gate" "image"] (mapv :name (:jobs run)))
            "its jobs are read, as a finished red run's are")))))

(defn- limit-pass! [{:keys [source engine lines]}]
  (forge/pass! {:source source :engine engine
                :log-fn (fn [& parts] (swap! lines conj (apply str parts)))}))

(defn- wire-count [state] (count (gh/requests state)))

(deftest the-headers-tell-a-throttle-from-a-refusal
  (let [epoch (str (.getEpochSecond ^java.time.Instant limit-reset))
        facts (fn [status headers body]
                (gh/answer-facts status headers body limit-hour))]
    (testing "no call remains: the limit lifts at the reset"
      (is (= limit-reset
             (:throttle (facts 403 {"x-ratelimit-remaining" "0"
                                    "x-ratelimit-reset" epoch}
                               "")))))
    (testing "a retry-after is the wait, whatever else is said"
      (is (= (.plusSeconds ^java.time.Instant limit-hour 30)
             (:throttle (facts 429 {"retry-after" "30"
                                    "x-ratelimit-remaining" "12"
                                    "x-ratelimit-reset" epoch}
                               "")))))
    (testing "a body that names the rate limit, and no header: a minute"
      (is (= (.plusSeconds ^java.time.Instant limit-hour 60)
             (:throttle (facts 403 {}
                               "You have exceeded a secondary rate limit")))))
    (testing "a 403 with calls left is a refusal of the token"
      (is (= {:remaining 4999 :limit 5000 :reset limit-reset}
             (facts 403 {"x-ratelimit-remaining" "4999"
                         "x-ratelimit-limit" "5000"
                         "x-ratelimit-reset" epoch}
                    "Resource not accessible by integration"))
          "no throttle, and the budget is read from it all the same"))
    (testing "an answer that is not a 403 or a 429 is never a throttle"
      (is (nil? (:throttle (facts 200 {"x-ratelimit-remaining" "0"
                                       "x-ratelimit-reset" epoch}
                                  "")))))))

(deftest a-spent-rate-limit-holds-the-pass-and-refuses-nothing
  (let [{:keys [state engine clock lines] :as w} (limit-rig)]
    (gh/budget! state {:remaining 4321 :limit 5000 :reset limit-reset})
    (limit-pass! w)
    (is (str/includes? (str (last @lines))
                       (str "4321 of 5000 calls left until " limit-reset))
        "the census line carries the remaining calls and the reset")
    (let [before (the-change engine)]
      (is (some? before) "the first pass minted the change")
      (gh/throttle! state limit-reset)

      (testing "the pass that meets the spent limit stops"
        (let [so-far (wire-count state)
              census (limit-pass! w)]
          (is (= (str limit-reset) (:held-until census)))
          (is (= (inc so-far) (wire-count state))
              "one answer said the limit is spent, and nothing was asked
               after it")
          (is (false? (:complete? census)) "the cursor stands")
          (is (= 0 (:noted census)))
          (is (nil? (source-note-of engine repo))
              "a throttle is not a repository the token cannot read")
          (is (= 0 (+ (long (:failing census)) (long (:stuck census))
                      (long (:moved census)) (long (:refused census))))
              "and no row moved or was refused on it")
          (is (= before (the-change engine)))
          (is (str/includes? (str (last @lines))
                             (str "every call held until " limit-reset))
              "the census line names the hold and the reset")))

      (testing "the next pass waits for the reset, though GitHub would answer"
        (gh/throttle! state nil)
        (let [so-far (wire-count state)
              census (limit-pass! w)]
          (is (= so-far (wire-count state)) "nothing went on the wire")
          (is (= 0 (:calls census)))
          (is (= (str limit-reset) (:held-until census)))
          (is (nil? (source-note-of engine repo)))
          (is (= before (the-change engine)))))

      (testing "past the reset the pass reads again"
        (reset! clock (.plusSeconds ^java.time.Instant limit-reset 1))
        (let [census (limit-pass! w)]
          (is (nil? (:held-until census)))
          (is (pos? (long (:calls census))))
          (is (true? (:complete? census))))))))

(deftest every-pass-stamps-the-policy-and-a-held-pass-does-too
  ;; ticket e674e558: `base_checked_at` stands still on a held pass
  (let [{:keys [state engine] :as w} (limit-rig)
        policy #(one-row engine :repo_policy {:repository repo})
        stamp #(get-in (policy) [:data :forge_pass_at])]
    (limit-pass! w)
    (is (some? (stamp)) "a pass that read the repository stamps its policy")
    (bench/mark-row! engine :repo_policy (str (:id (policy)))
                     {:forge_pass_at nil} #{})
    (is (nil? (stamp)))
    (gh/throttle! state limit-reset)
    (let [census (limit-pass! w)]
      (is (= (str limit-reset) (:held-until census)))
      (is (some? (stamp)) "the held pass ran, and the policy says when"))))

(deftest an-unchanged-answer-is-a-304-and-costs-nothing
  (let [{:keys [state source]} (limit-rig)
        _ (gh/budget! state {:remaining 4321 :limit 5000 :reset limit-reset})
        ;; the first read of a new repository lists `state=open` only
        ;; (ticket c07b581f), which is another request than every later
        ;; listing: the two passes compared here come after it
        _ (forge/forge-poll source)
        first-poll (forge/forge-poll source)
        first-checks (forge/forge-checks source repo limit-head)
        so-far (wire-count state)
        spared (count (gh/unchanged state))]
    (is (= 1 (count (:changes first-poll))))

    (testing "a second pass over a pull request that did not move"
      (let [second-poll (forge/forge-poll source)
            second-checks (forge/forge-checks source repo limit-head)]
        (is (pos? (- (wire-count state) so-far)))
        (is (= (- (wire-count state) so-far)
               (- (count (gh/unchanged state)) spared))
            "every request of it was answered 304")
        (is (= (:changes first-poll) (:changes second-poll))
            "and the documents are the ones the first pass read")
        (is (= first-checks second-checks))
        (is (= {:remaining 4321 :limit 5000 :reset (str limit-reset)}
               (select-keys (forge/forge-budget source)
                            [:remaining :limit :reset])))
        (is (pos? (long (:unchanged (forge/forge-budget source)))))))

    (testing "a check that finishes changes one route's answer, and only
              that route is read whole"
      (gh/seed-check! state repo limit-head a-red-check)
      (let [so-far (wire-count state)
            spared (count (gh/unchanged state))
            checks (forge/forge-checks source repo limit-head)]
        (is (= 3 (- (wire-count state) so-far))
            "the check runs, the workflow runs (ticket 004188aa) and the
             statuses")
        (is (= 2 (- (count (gh/unchanged state)) spared))
            "the workflow runs and the statuses did not change")
        (is (contains? (set (map :check_name checks)) "test10 (shard 3)")
            "the new red check is read")))))

;; ── the artifact read (ticket 38251e4b) ──────────────────────────────

(defn- a-zip
  "A zip archive holding one entry, as upload-artifact makes one."
  [^String entry ^String text]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [zout (java.util.zip.ZipOutputStream. out)]
      (.putNextEntry zout (java.util.zip.ZipEntry. entry))
      (.write zout (.getBytes text "UTF-8"))
      (.closeEntry zout))
    (.toByteArray out)))

(deftest an-artifact-is-read-through-its-redirect-without-the-token
  (let [state (gh/fake-state)
        source (gh/fake-source state)]
    (gh/seed-artifact! state repo {:id 502 :name "findings" :expired false}
                       (a-zip "findings.json"
                              "{\"greenleaf\": [{\"key\": \"gap\"}]}"))
    (gh/seed-artifact! state repo {:id 501 :name "findings" :expired true}
                       (a-zip "findings.json" "{\"greenleaf\": []}"))
    (gh/seed-artifact! state repo {:id 503 :name "other" :expired false}
                       (a-zip "other.json" "{\"other\": []}"))
    (is (= {:greenleaf [{:key "gap"}]}
           (forge/forge-artifact source repo "findings"))
        "the newest unexpired artifact of that name, its first entry as JSON")
    (let [asked (gh/requests state)
          listing (first asked)
          zip (second asked)
          blob (nth asked 2 nil)]
      (is (= 3 (count asked)) "the listing, the zip route and the blob")
      (is (= (str "/repos/" repo "/actions/artifacts") (:path listing)))
      (is (= "findings" (str (:name (:params listing))))
          "the listing is asked by name")
      (is (= (str "/repos/" repo "/actions/artifacts/502/zip") (:path zip))
          "the expired artifact listed before it is passed over")
      (is (false? (:anonymous zip)) "the zip route carries the token")
      (is (= (str gh/archive-base "502") (:path blob)))
      (is (true? (:anonymous blob))
          "and the redirect is followed with no bearer to another host")
      (is (true? (:binary blob)) "for the archive's bytes"))))

(deftest an-artifact-is-read-from-its-json-entry
  (let [state (gh/fake-state)
        source (gh/fake-source state)
        out (java.io.ByteArrayOutputStream.)]
    (with-open [zout (java.util.zip.ZipOutputStream. out)]
      (doseq [[entry ^String text] [["first_week_scene.edn" "{:scene {}}"]
                                    ["org-findings.json" "{\"acme\": []}"]]]
        (.putNextEntry zout (java.util.zip.ZipEntry. ^String entry))
        (.write zout (.getBytes text "UTF-8"))
        (.closeEntry zout)))
    (gh/seed-artifact! state repo {:id 504 :name "findings" :expired false}
                       (.toByteArray out))
    (is (= {:acme []} (forge/forge-artifact source repo "findings"))
        "the scene beside it does not stand in for the findings")))

(deftest an-artifact-listed-only-expired-answers-nil
  (let [state (gh/fake-state)
        source (gh/fake-source state)]
    (is (nil? (forge/forge-artifact source repo "findings"))
        "nothing listed yet")
    (gh/seed-artifact! state repo {:id 501 :name "findings" :expired true}
                       (a-zip "findings.json" "{\"greenleaf\": []}"))
    (is (nil? (forge/forge-artifact source repo "findings"))
        "only an expired one")
    (is (not-any? #(str/includes? (str (:path %)) "/zip")
                  (gh/requests state))
        "and no archive is asked for")))
