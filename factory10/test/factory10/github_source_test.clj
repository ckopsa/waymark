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
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]
            [waymark10.resource :as res]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
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

(defn- rig
  "A fresh in-memory GitHub, the real source over it, and an engine —
  seeded with one open pull request and one red check run on its head."
  ([] (rig []))
  ([extra]
   (let [state (gh/fake-state)]
     (gh/seed-pull! state repo a-pull-request
                    {:files the-files :reviews the-reviews})
     (gh/seed-check! state repo (get-in a-pull-request [:head :sha])
                     a-red-check)
     (gh/seed-check! state repo (get-in a-pull-request [:head :sha])
                     a-green-check)
     (gh/seed-log! state "7001" the-log)
     {:state state :source (gh/fake-source state) :engine (boot extra)})))

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

(defn- the-run [engine]
  (one-row engine :ci_run
           {:run_id "github:ckopsa/waymark/check-run/41752098311"}))

(defn- writes
  "Every request the source made that was not a read."
  [state]
  (filterv #(not= "GET" (:method %)) (gh/requests state)))

;; ── acceptance 1 ────────────────────────────────────────────────────

(deftest a-pull-request-becomes-a-change-row
  (let [{:keys [engine] :as r} (rig)
        census (pass! r)
        row (the-change engine)
        data (:data row)]
    (testing "the row is there, and its identity is GitHub's own"
      (is (some? row) "one pass minted the pull request")
      (is (= 1 (:minted census)))
      (is (= "github:ckopsa/waymark#31" (:change_id data)))
      (is (= :open (:state row)))
      (is (some? (:id row)) "the id is the engine's, and nobody typed it"))

    (testing "the size, the paths and the mergeable state are filled"
      (is (= 3 (:files_changed data)))
      (is (= 412 (:lines_added data)))
      (is (= 7 (:lines_removed data)))
      (is (= (mapv :filename the-files) (:touched_paths data)))
      (is (= "clean" (:mergeable data))))

    (testing "…and the rest of what a pull request says about itself"
      (is (= repo (:repository data)))
      (is (= 31 (:number data)))
      (is (= "6.4 GitHub events as a source" (:title data)))
      (is (= "ckopsa" (:author data)))
      (is (= "main" (:base_branch data)))
      (is (= "waymark-fp62.6.4" (:head_branch data)))
      (is (= (get-in a-pull-request [:head :sha]) (:head_sha data)))
      (is (= ["agent"] (:labels data)))
      (is (= "changes_requested" (:review_state data))
          "a request for changes outranks the comment beside it"))))

(deftest a-second-pass-moves-the-row-and-mints-nothing
  (let [{:keys [state engine] :as r} (rig)
        _ (pass! r)
        before (the-change engine)
        _ (gh/seed-pull! state repo
                         (-> a-pull-request
                             (assoc :title "6.4 GitHub as a source"
                                    :updated_at "2026-09-18T12:30:00Z")
                             (assoc :mergeable_state "dirty"))
                         {:files the-files :reviews []})
        census (pass! r)
        after (the-change engine)]
    (is (= 0 (:minted census)) "the row was already here")
    (is (= 1 (:moved census)))
    (is (= (:id before) (:id after)) "the same row, moved — not a second one")
    (is (= "6.4 GitHub as a source" (get-in after [:data :title])))
    (is (= "conflicted" (get-in after [:data :mergeable])))
    (is (= "pending" (get-in after [:data :review_state])))))

(deftest a-merged-pull-request-moves-the-row-to-merged
  (let [{:keys [state engine] :as r} (rig)
        _ (pass! r)
        _ (gh/seed-pull! state repo
                         (assoc a-pull-request
                                :state "closed"
                                :merged_at "2026-09-18T15:00:00Z"
                                :updated_at "2026-09-18T15:00:00Z"))
        _ (pass! r)]
    (is (= :merged (:state (the-change engine))))))


;; ── the adoption (bead waymark-fp62.6.3.10, R-12.32) ────────────────
;;
;; THE HOUSE ASKS FOR SOME OF ITS OWN PULL REQUESTS. A seat that walks
;; a queue of asks gets a change row minted for the ask itself, with
;; the ask's id in place of GitHub's and no number; it works that row
;; on the bench, and its submit opens the pull request. The next pass
;; then reads a pull request whose id answers no row. It must ADOPT
;; the row that is already here — same repository, same head branch —
;; because two rows for one piece of work is two queues, two round
;; counts and two stories.

(def ^:private an-ask "01HZQ7ASKR3W4V5X6Y7Z8A9B1")

(defn- a-seat-born-change!
  "The row a seat's sit minted for an ask: this repository, the branch
  the seat works on, the ask's own id in place of GitHub's, and no
  number at all."
  [engine extra]
  (:row (inv/create! engine :change
                     (merge {:change_id (str "ask:" an-ask)
                             :repository repo
                             :title "6.3.10 The code seat builds a task"
                             :head_branch "waymark-fp62.6.4"
                             :base_branch "main"
                             :author "code-seat"}
                            extra)
                     {:principal mirror/source-principal})))

(defn- put-at-submitted!
  "The row a seat has pushed one round of. The state is moved by hand
  because the `submit` door reaches a bench rig, and this suite boots
  none: what is under test is the PASS, not the push."
  [engine id]
  (let [st (:storage engine)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :change id {})]
          (store/save-row! st tx :change
                           (assoc row :state :submitted
                                  :version (inc (long (:version row))))
                           (:version row)))))))

(deftest the-pull-request-a-seat-opened-is-adopted-and-not-minted-again
  (let [{:keys [engine] :as r} (rig)
        ours (a-seat-born-change! engine {})
        census (pass! r)
        rows (rows-of engine :change {})
        row (first rows)
        data (:data row)]
    (is (= 1 (count rows)) "one row for one piece of work, not two")
    (is (= (:id ours) (:id row))
        "and it is the row the seat built, adopted where it stands")
    (is (= 1 (:adopted census)))
    (is (= 0 (:minted census)) "nothing was born in this pass")

    (testing "GitHub's identity is on it now"
      (is (= "github:ckopsa/waymark#31" (:change_id data))
          "the id a later pass reads it back by")
      (is (= 31 (:number data)) "the number the row was born without")
      (is (= "https://github.com/ckopsa/waymark/pull/31" (:url data))))

    (testing "and the facts followed through observe, as for any row"
      (is (= 3 (:files_changed data)))
      (is (= "changes_requested" (:review_state data)))
      (is (= (get-in a-pull-request [:head :sha]) (:head_sha data))))

    (testing "the next pass finds it by that id and moves it"
      (let [census (pass! r)]
        (is (= 0 (:adopted census))
            "an adoption happens once: the id answers the row now")
        (is (= 1 (count (rows-of engine :change {}))))))))

(deftest a-change-a-seat-has-submitted-is-adopted-in-the-state-it-stands-in
  (let [{:keys [engine] :as r} (rig)
        ours (a-seat-born-change! engine {})
        _ (put-at-submitted! engine (str (:id ours)))
        census (pass! r)
        row (first (rows-of engine :change {}))]
    (is (= 1 (:adopted census))
        "`submitted` is where the adoption almost always lands: the
         push that opened the pull request is what moved the row
         there")
    (is (= :submitted (:state row))
        "the adoption writes the identity and moves nothing — the
         machine advances the state, and GitHub says open")
    (is (= "github:ckopsa/waymark#31" (get-in row [:data :change_id])))
    (is (= 31 (get-in row [:data :number])))))

(deftest a-row-on-another-branch-is-not-adopted
  (let [{:keys [engine] :as r} (rig)
        ours (a-seat-born-change! engine {:head_branch "waymark/other"})
        census (pass! r)
        rows (rows-of engine :change {})]
    (is (= 0 (:adopted census)))
    (is (= 1 (:minted census)))
    (is (= 2 (count rows))
        "a pull request that is not this row's work gets a row of its
         own")
    (is (= (str "ask:" an-ask)
           (get-in (one-row engine :change {:change_id (str "ask:" an-ask)})
                   [:data :change_id]))
        "and the seat's own row keeps its ask's id")
    (is (some? (:id ours)))))


;; ── the merge finishes the task (bead waymark-fp62.6.3.14) ──────────
;;
;; THE TASK IS DONE WHEN ITS PULL REQUEST MERGES. The seat that built
;; the change submitted and stopped, and the task stayed open until a
;; person closed it by hand. So the engine does it: a change born from
;; a task carries the task's address in `born_from` — a field the
;; adoption does not touch — and the merge walks the task's own
;; `complete` door.

(def ^:private the-person
  (t/principal {:id "colton" :display "Colton Kopsa"}))

(res/defhandler mark-the-task-done [row _inp _ctx]
  (assoc-in row [:data :status] "done"))

(def ^:private task
  "The smallest `task`: the household's own task kind is workqueue10's
  and this suite boots the factory alone, so the queue gets a kind of
  its own here — shaped the way `task` is shaped in the one fact the
  merge reads, a `status` field its `complete` door writes `done`
  into. bench_test's `ask` is the same stand-in, one bead over."
  (res/resource
   {:kind :task
    :plural "tasks"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {data.status}"
    :label-template "{data.title}"
    :schema [:map
             [:title {:examples ["Put the size ceiling on the policy form"]
                      :x-display {:label "What to build"}}
              [:string {:min 1 :max 200}]]
             [:status {:optional true :filter #{:eq :in}
                       :x-display {:label "Where it stands"}}
              [:maybe [:enum "open" "done"]]]]
    :filterable {:state #{:eq :in}}
    :default-filters {:status "open"}
    :actions
    {:complete {:from #{:open} :to :done
                :handler mark-the-task-done
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}
                :display {:label "Complete" :style :primary :order 1
                          :description "Say the task is done"}}}}))

(defn- a-task!
  "One row of the queue a person writes."
  [engine]
  (:row (inv/create! engine :task
                     {:title "6.3.14 The merge completes the task"
                      :status "open"}
                     {:principal the-person})))

(defn- merge-the-pull-request!
  "GitHub merged it, as the API answers a merged pull request."
  [state]
  (gh/seed-pull! state repo
                 (assoc a-pull-request
                        :state "closed"
                        :merged_at "2026-09-19T15:00:00Z"
                        :updated_at "2026-09-19T15:00:00Z")))

(deftest a-merged-pull-request-completes-the-task-the-change-was-born-from
  (let [{:keys [state engine] :as r} (rig [task])
        asked (a-task! engine)
        ours (a-seat-born-change! engine
                                  {:change_id (str "task:" (:id asked))
                                   :born_from (str "task:" (:id asked))})
        _ (pass! r)
        adopted (one-row engine :change {})
        _ (merge-the-pull-request! state)
        _ (pass! r)
        change (one-row engine :change {})
        done (one-row engine :task {})]
    (testing "the adoption takes GitHub's id and keeps the origin"
      (is (= (:id ours) (:id adopted)))
      (is (= "github:ckopsa/waymark#31" (get-in adopted [:data :change_id])))
      (is (= (str "task:" (:id asked)) (get-in adopted [:data :born_from]))
          "`change_id` is GitHub's now, and the row still says which
           task it was built for"))

    (testing "and the merge completes that task"
      (is (= :merged (:state change)))
      (is (= "done" (get-in done [:data :status]))
          "the task is done when its pull request merges, whether or
           not the seat completed it")
      (is (= :done (:state done))
          "through the task's own complete door, so the task's log
           carries the move"))))

(deftest a-merged-pull-request-on-a-submitted-change-completes-the-task-too
  ;; the state a seat-born row STANDS IN on the day: the seat pushed,
  ;; so the row is `submitted`, and the adoption keeps it there. The
  ;; source's state table must move a submitted row to `merged` as it
  ;; moves an open one, or the task is never completed.
  (let [{:keys [state engine] :as r} (rig [task])
        asked (a-task! engine)
        ours (a-seat-born-change! engine
                                  {:change_id (str "task:" (:id asked))
                                   :born_from (str "task:" (:id asked))})
        _ (put-at-submitted! engine (str (:id ours)))
        _ (pass! r)
        adopted (one-row engine :change {})
        _ (merge-the-pull-request! state)
        _ (pass! r)
        change (one-row engine :change {})
        done (one-row engine :task {})]
    (is (= :submitted (:state adopted))
        "the adoption keeps the state the row stands in")
    (is (= :merged (:state change))
        "and the merge moves a submitted row as it moves an open one")
    (is (= "done" (get-in done [:data :status]))
        "so the task is done when its pull request merges")))

(deftest a-merge-whose-task-is-already-done-moves-the-change-all-the-same
  (let [{:keys [state engine] :as r} (rig [task])
        asked (a-task! engine)
        _ (inv/invoke! engine :task (str (:id asked)) :complete nil
                       {:principal the-person})
        _ (a-seat-born-change! engine
                               {:change_id (str "task:" (:id asked))
                                :born_from (str "task:" (:id asked))})
        _ (pass! r)
        _ (merge-the-pull-request! state)
        _ (pass! r)]
    (is (= :merged (:state (one-row engine :change {})))
        "a task the seat already completed is not an error: GitHub
         merged the pull request, and the row follows GitHub")
    (is (= "done" (get-in (one-row engine :task {}) [:data :status])))))

(deftest a-merge-whose-task-is-gone-moves-the-change-all-the-same
  (let [{:keys [state engine] :as r} (rig [task])
        _ (a-seat-born-change! engine
                               {:change_id "task:01HZQ7NOSUCHR0W4V5X6Y7Z8"
                                :born_from "task:01HZQ7NOSUCHR0W4V5X6Y7Z8"})
        _ (pass! r)
        _ (merge-the-pull-request! state)
        _ (pass! r)]
    (is (= :merged (:state (one-row engine :change {})))
        "a task nobody can find is not an error either: the merge must
         never fail for the queue")))

(deftest a-change-github-gave-us-was-born-from-nothing
  (let [{:keys [state engine] :as r} (rig [task])
        _ (pass! r)
        _ (merge-the-pull-request! state)
        _ (pass! r)]
    (is (= :merged (:state (the-change engine))))
    (is (nil? (get-in (the-change engine) [:data :born_from]))
        "almost every pull request the forge reads was opened by a
         person, and that row was born from no row of ours")
    (is (empty? (rows-of engine :task {}))
        "so the merge completes nothing")))

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

(deftest the-window-re-asks-from-sixty-seconds-behind
  (let [state (gh/fake-state)
        source (gh/fake-source state {:cursor "2026-09-18T12:00:00Z"})
        pull (fn [n at]
               {:number n :state "open" :title (str "pull " n)
                :user {:login "ckopsa"} :base {:ref "main"}
                :head {:ref (str "b" n) :sha (str "sha" n)}
                :updated_at at :labels []})]
    ;; inside the sixty seconds, and outside them
    (gh/seed-pull! state repo (pull 7 "2026-09-18T11:59:30Z"))
    (gh/seed-pull! state repo (pull 8 "2026-09-18T11:58:00Z"))
    (let [answer (forge/forge-poll source)]
      (is (= ["github:ckopsa/waymark#7"] (mapv :change_id (:changes answer)))
          "the window re-asks from sixty seconds behind the cursor, and
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
