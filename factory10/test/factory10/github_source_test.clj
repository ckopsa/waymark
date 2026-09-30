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

(deftest mergeable-false-outranks-the-state-word
  (testing "GitHub's own mergeable: false is a conflict, whatever the policy word"
    (is (= "conflicted" (gh/mergeable-of {:mergeable_state "blocked" :mergeable false})))
    (is (= "conflicted" (gh/mergeable-of {:mergeable_state "behind" :mergeable false})))
    (is (= "conflicted" (gh/mergeable-of {:mergeable false}))))
  (testing "without it the state word still speaks"
    (is (= "blocked" (gh/mergeable-of {:mergeable_state "blocked" :mergeable true})))
    (is (= "blocked" (gh/mergeable-of {:mergeable_state "blocked" :mergeable nil})))
    (is (= "unknown" (gh/mergeable-of {})))))

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

(defn- put-at!
  "The row moved to `state` by hand, because the doors that reach it
  need a bench rig, and this suite boots none: what is under test is
  the PASS, not the push."
  [engine id state]
  (let [st (:storage engine)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :change id {})]
          (store/save-row! st tx :change
                           (assoc row :state state
                                  :version (inc (long (:version row))))
                           (:version row)))))))

(defn- put-at-submitted!
  "The row a seat has pushed one round of."
  [engine id]
  (put-at! engine id :submitted))

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

(deftest a-pull-request-that-opens-while-its-house-row-is-stuck-is-adopted
  ;; ticket 3c59c688: #268 opened while 71cbbef9 stood stuck
  (let [{:keys [engine] :as r} (rig)
        ours (a-seat-born-change! engine {})
        _ (put-at! engine (str (:id ours)) :stuck)
        census (pass! r)
        rows (rows-of engine :change {})
        row (first rows)]
    (is (= 1 (count rows)) "one row for the house's own pull request")
    (is (= 1 (:adopted census)))
    (is (= 0 (:minted census)))
    (is (= (:id ours) (:id row)))
    (is (= :stuck (:state row))
        "the adoption leaves the row stuck: a person puts it back")
    (is (= "github:ckopsa/waymark#31" (get-in row [:data :change_id])))
    (is (= 31 (get-in row [:data :number])))))

(deftest a-minted-duplicate-on-the-house-rows-branch-is-folded-into-it
  ;; the regression: 0243f0a6 was minted for #268 beside 71cbbef9, and
  ;; every later pass moved the minted row and never the house's
  (let [{:keys [engine] :as r} (rig)
        _ (pass! r)
        minted (one-row engine :change {:change_id "github:ckopsa/waymark#31"})
        ours (a-seat-born-change! engine {})
        census (pass! r)
        by-id (into {} (map (juxt :id identity)) (rows-of engine :change {}))
        house (get by-id (:id ours))
        folded (get by-id (:id minted))]
    (is (= 1 (:folded census)))
    (is (= 0 (:minted census)))
    (testing "the house row holds the pull request now"
      (is (= "github:ckopsa/waymark#31" (get-in house [:data :change_id])))
      (is (= 31 (get-in house [:data :number]))))
    (testing "and the minted row is closed, naming the house row"
      (is (= :closed (:state folded)))
      (is (= (str (:id ours)) (get-in folded [:data :superseded_by])))
      (is (not= "github:ckopsa/waymark#31"
                (get-in folded [:data :change_id]))))
    (testing "the next pass moves the house row and folds nothing"
      (let [census (pass! r)]
        (is (= 0 (:folded census)))
        (is (= 0 (:minted census)))
        (is (= 2 (count (rows-of engine :change {}))))))))


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
        source (gh/fake-source state {:repos private-repo})
        census (forge/pass! {:source source :engine engine :log-fn quiet})
        change (one-row engine :change
                        {:change_id "github:ckopsa/waymark-doors#5"})
        run (one-row engine :ci_run
                     {:run_id "github:ckopsa/waymark-doors/check-run/7001"})]
    (testing "the pull request is adopted with its number"
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
        doors "ckopsa/waymark-doors"]
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
    r))

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
    (let [census (pass! r)
          row (the-change engine)]
      (is (= a-new-head (get-in row [:data :head_sha]))
          "a failing row follows the head it is read against")
      (is (= :submitted (:state row)))
      (is (nil? (get-in row [:data :failing_checks])))
      (is (= 1 (:recovered census))))))

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
      (let [census (pass! r)
            row (the-change engine)]
        (is (= a-new-head (get-in row [:data :head_sha])))
        (is (= :submitted (:state row)))
        (is (nil? (get-in row [:data :train_red_head])))
        (is (nil? (get-in row [:data :train_red])))
        (is (= 1 (:recovered census)))))))

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
  `:asked` holds every tool it was called with. A `landing` is what the
  bench's feedback answers for the branch."
  ([mergeable-state paths policy rounds]
   (conflict-world mergeable-state paths policy rounds nil))
  ([mergeable-state paths policy rounds landing]
  (let [state (gh/fake-state)
        asked (atom [])
        rpc (fn [_method params]
              (swap! asked conj params)
              (cond
                (and paths (= "bench__conflicts" (str (:name params))))
                {:structuredContent {:result {:paths paths}}}

                (and landing (= "bench__feedback" (str (:name params))))
                {:structuredContent {:result {:landing landing}}}))
        engine (engine/engine {:storage (memory/storage)
                               :resources (vec (main/resources))
                               :services {:bench-rpc rpc}})
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
    r)))

(defn- conflict-asks [{:keys [asked]}]
  (filterv #(= "bench__conflicts" (str (:name %))) @asked))

(deftest a-conflicted-change-goes-failing-while-its-landing-still-runs
  ;; ticket 7af7d506: a landing that never said it finished held a
  ;; conflicted pull request at `submitted`, parked, and nothing woke
  ;; the seat
  (let [{:keys [engine] :as r} (conflict-world "dirty" the-conflicts {} 1
                                               {:state "running"})
        census (pass! r)
        row (the-change engine)]
    (is (= :failing (:state row)))
    (is (= ["merge-conflict"] (get-in row [:data :failing_checks])))
    (is (= the-conflicts (get-in row [:data :conflicts])))
    (is (= 1 (:failing census)))))

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
    (let [args (:arguments (first (conflict-asks r)))]
      (is (= repo (:repo args)))
      (is (= "waymark-fp62.6.4" (:branch args)))
      (is (= "main" (:base args))))
    (testing "a second pass on the same conflict moves nothing and asks nothing"
      (let [census (pass! r)]
        (is (= :failing (:state (the-change engine))))
        (is (= 0 (:failing census)))
        (is (= 1 (count (conflict-asks r))))))))

(deftest a-conflict-with-no-bench-answer-still-goes-failing
  (let [{:keys [engine] :as r} (conflict-world "dirty" nil {} 1)]
    (pass! r)
    (is (= :failing (:state (the-change engine))))
    (is (= ["merge-conflict"] (get-in (the-change engine) [:data :failing_checks])))
    (is (nil? (get-in (the-change engine) [:data :conflicts]))
        "a bench that cannot name the paths costs the paths, never the move")))

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

;; ── a landing that failed (ticket 92871afb) ─────────────────────────────

(def ^:private a-rejected-push
  (str "To github.com:ckopsa/waymark-doors.git\n"
       " ! [remote rejected] waymark/1bae3a0a -> waymark/1bae3a0a "
       "(refusing to allow a Personal Access Token to create or update "
       "workflow `.github/workflows/test.yml` without `workflow` scope)\n"
       "error: failed to push some refs"))

(defn- a-failed-landing []
  {:state "failed"
   :steps [{:name "commit" :state "done" :output "[waymark/one 1bae3a0] Fix"}
           {:name "push" :state "failed" :output a-rejected-push}]})

(defn- landing-world
  "One change at `submitted` with `rounds` spent, whose one check is
  `check`. The bench behind the engine answers `feedback` with whatever
  the `landing` atom holds."
  [landing check policy rounds]
  (let [state (gh/fake-state)
        rpc (fn [_method params]
              (when (= "bench__feedback" (str (:name params)))
                {:structuredContent
                 {:result {:repo repo :landing @landing :pull_request nil
                           :pipelines [] :statuses [] :comments []}}}))
        engine (engine/engine {:storage (memory/storage)
                               :resources (vec (main/resources))
                               :services {:bench-rpc rpc}})
        r {:state state :source (gh/fake-source state) :engine engine}]
    (gh/seed-pull! state repo (assoc a-pull-request :mergeable_state "clean")
                   {:files the-files :reviews the-reviews})
    (gh/seed-check! state repo (get-in a-pull-request [:head :sha]) check)
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
    r))

(deftest a-failed-landing-moves-a-submitted-change-to-failing
  (let [landing (atom (a-failed-landing))
        {:keys [engine] :as r} (landing-world landing a-green-check {} 1)
        census (pass! r)
        row (the-change engine)]
    (is (= :failing (:state row))
        "a push that never landed will never run a check, so it fails now")
    (is (= ["landing:push"] (get-in row [:data :failing_checks]))
        "the failed step is the red name")
    (is (= a-rejected-push (get-in row [:data :landing_error]))
        "and the step's own output rides beside it")
    (is (= 1 (:failing census)))
    (testing "a second pass on the same landing moves nothing"
      (let [census (pass! r)]
        (is (= :failing (:state (the-change engine))))
        (is (= 0 (:failing census)))))))

(deftest a-failed-landing-on-the-last-round-sticks-the-change
  (let [landing (atom (a-failed-landing))
        {:keys [engine] :as r}
        (landing-world landing a-green-check {:rounds_per_change 2} 2)
        census (pass! r)
        row (the-change engine)]
    (is (= :stuck (:state row))
        "a failed landing counts against the ceiling like a red check")
    (is (= ["landing:push"] (get-in row [:data :failing_checks])))
    (is (= a-rejected-push (get-in row [:data :landing_error])))
    (is (= 1 (:stuck census)))))

(deftest a-running-landing-does-not-move-the-change
  (let [landing (atom {:state "running"
                       :steps [{:name "push" :state "running"}]})
        {:keys [engine] :as r}
        (landing-world landing
                       {:id 41752098700 :name "test10 (shard 3)"
                        :status "completed" :conclusion "failure"
                        :head_sha (get-in a-pull-request [:head :sha])}
                       {} 1)
        census (pass! r)]
    (is (= :submitted (:state (the-change engine)))
        "a landing still running is left alone, whatever the old head said")
    (is (= 0 (:failing census)))))

(deftest a-resubmit-that-lands-stays-submitted
  (let [landing (atom (a-failed-landing))
        {:keys [engine] :as r} (landing-world landing a-green-check {} 1)]
    (pass! r)
    (is (= :failing (:state (the-change engine))))
    ;; the seat's next submit: the door clears the red names and the
    ;; landing's error (factory10.bench-test's own a-seat-submits-
    ;; again-from-failing walks the door itself), and the rig lands it
    (let [id (str (:id (the-change engine)))
          st (:storage engine)]
      (store/with-tx st
        (fn [tx]
          (let [row (store/load-row st tx :change id {})]
            (store/save-row! st tx :change
                             (-> row
                                 (assoc :state :submitted
                                        :version (inc (long (:version row))))
                                 (update :data assoc :failing_checks nil
                                         :landing_error nil))
                             (:version row))))))
    (reset! landing {:state "succeeded"
                     :steps [{:name "commit" :state "done"}
                             {:name "push" :state "done"}]})
    (let [census (pass! r)
          row (the-change engine)]
      (is (= :submitted (:state row))
          "a landing that finished well hands the change back to its checks")
      (is (nil? (get-in row [:data :landing_error])))
      (is (= 0 (:failing census))))))

;; ── a landed pull request nobody adopted (ticket 58e706d6) ──────────

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

(deftest a-landed-pull-request-nobody-adopted-says-so-after-the-window
  (let [{:keys [engine] :as r}
        (unadopted-world {:number 7 :state "open"
                          :url "https://github.com/ckopsa/waymark/pull/7"})
        note (str "landed as #7 on " repo
                  " but no pull request row adopted it; head " a-landed-branch)
        long-ago (str (.minus (java.time.Instant/now)
                              (java.time.Duration/ofMinutes 20)))]
    (let [census (pass! r)
          row (the-unadopted engine)]
      (is (some? (get-in row [:data :unadopted_since]))
          "the pass stamps the first time it saw the pull request")
      (is (nil? (get-in row [:data :adoption_note]))
          "and says nothing inside the window")
      (is (= 0 (:adoption-noted census))))
    (rewrite-unadopted! engine #(assoc-in % [:data :unadopted_since] long-ago))
    (let [census (pass! r)
          row (the-unadopted engine)]
      (is (= note (get-in row [:data :adoption_note]))
          "after the window the row says what landed and where")
      (is (= long-ago (get-in row [:data :unadopted_since]))
          "the first sight stands")
      (is (= 1 (:adoption-noted census))))
    (testing "a second pass does not write the same note again"
      (let [census (pass! r)]
        (is (= 0 (:adoption-noted census)))
        (is (= note (get-in (the-unadopted engine) [:data :adoption_note])))))
    (testing "the adoption clears it"
      (inv/invoke! engine :change (str (:id (the-unadopted engine)))
                   :adopt_submitted
                   {:change_id "github:ckopsa/waymark#7" :number 7}
                   {:principal mirror/source-principal})
      (let [row (one-row engine :change {:change_id "github:ckopsa/waymark#7"})]
        (is (= 7 (get-in row [:data :number])))
        (is (nil? (get-in row [:data :adoption_note])))
        (is (nil? (get-in row [:data :unadopted_since])))))))

(deftest a-change-with-no-landed-pull-request-is-not-noted
  (let [{:keys [engine] :as r} (unadopted-world nil)]
    (pass! r)
    (is (nil? (get-in (the-unadopted engine) [:data :unadopted_since])))
    (is (nil? (get-in (the-unadopted engine) [:data :adoption_note])))))

(deftest a-stamp-under-the-old-name-moves-to-unadopted-since
  ;; ticket 8f2fac64: `landed_at` was renamed, and the pass migrates it
  (let [{:keys [engine] :as r}
        (unadopted-world {:number 7 :state "open"
                          :url "https://github.com/ckopsa/waymark/pull/7"})
        stamp (str (.minus (java.time.Instant/now)
                           (java.time.Duration/ofMinutes 5)))]
    (rewrite-unadopted! engine #(assoc-in % [:data :landed_at] stamp))
    (pass! r)
    (let [row (the-unadopted engine)]
      (is (= stamp (get-in row [:data :unadopted_since]))
          "the first sight stands, under its new name")
      (is (nil? (get-in row [:data :landed_at])) "the old name is cleared")
      (is (nil? (get-in row [:data :adoption_note]))))))

(deftest a-landed-pull-request-the-window-missed-is-adopted-by-number
  ;; ticket 949d18c5: the landing reported, the pass stamped the row,
  ;; and the listing's window had already passed the pull request
  (let [{:keys [state engine]}
        (unadopted-world {:number 7 :state "open"
                          :url "https://github.com/ckopsa/waymark/pull/7"})
        r {:source (gh/fake-source state {:cursor "2026-09-18T13:00:00Z"})
           :engine engine}
        id (str (:id (the-unadopted engine)))]
    (gh/seed-pull! state repo
                   {:number 7 :state "open" :title "A landed change"
                    :user {:login "ckopsa"} :base {:ref "main"}
                    :head {:ref a-landed-branch :sha "sha7"}
                    :html_url "https://github.com/ckopsa/waymark/pull/7"
                    :updated_at "2026-09-18T12:00:00Z" :labels []})
    (rewrite-unadopted! engine
                        #(assoc-in % [:data :unadopted_since] "2026-09-18T12:05:00Z"))
    (let [census (pass! r)
          row (one-row engine :change {:change_id "github:ckopsa/waymark#7"})]
      (is (= 1 (:adopted census)) "the pull request is adopted by number")
      (is (= id (str (:id row))) "onto the row that asked for it")
      (is (= 7 (get-in row [:data :number])))
      (is (= :submitted (:state row)))
      (is (nil? (get-in row [:data :unadopted_since]))
          "the first-sight stamp goes with the adoption")
      (is (nil? (get-in row [:data :adoption_note]))))))

;; ── a submitted change that never opened a pull request (ticket 226d2b85)

(defn- a-ticket-at!
  "One ticket in the engine, forced to `state`, and the unadopted
  change born from it. → the ticket's id."
  [engine state]
  (let [id (str (:id (:row (inv/create! engine :ticket
                                        {:title "A seat's ticket"
                                         :type "feature" :repo repo}
                                        {:principal a-person}))))
        st (:storage engine)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :ticket id {})]
          (store/save-row! st tx :ticket
                           (assoc row :state state
                                  :version (inc (long (:version row))))
                           (:version row)))))
    (rewrite-unadopted! engine #(assoc-in % [:data :born_from]
                                          (str "ticket:" id)))
    id))

(deftest a-change-that-never-opened-a-pull-request-closes-when-its-ticket-ends
  (doseq [state [:done :dropped]]
    (testing (name state)
      (let [{:keys [engine] :as r} (unadopted-world nil)
            tid (a-ticket-at! engine state)
            census (pass! r)
            row (the-unadopted engine)]
        (is (= "closed" (name (:state row))))
        (is (= (str "closed: ticket " tid " ended; this change never opened"
                    " a pull request")
               (get-in row [:data :superseded_by])))
        (is (= 1 (:unopened-closed census)))))))

(deftest a-change-that-never-opened-a-pull-request-sticks-after-the-window
  (let [{:keys [engine] :as r} (unadopted-world nil)
        _ (a-ticket-at! engine :open)
        long-ago (str (.minus (java.time.Instant/now)
                              (java.time.Duration/ofMinutes 20)))]
    (pass! r)
    (let [row (the-unadopted engine)]
      (is (= "submitted" (name (:state row))) "inside the window it waits")
      (is (some? (get-in row [:data :unadopted_since])) "the first sight is stamped"))
    (rewrite-unadopted! engine #(assoc-in % [:data :unadopted_since] long-ago))
    (let [census (pass! r)
          row (the-unadopted engine)]
      (is (= "stuck" (name (:state row))) "after the window a person sees it")
      (is (= 1 (:stuck census))))))

(deftest a-change-with-a-pull-request-is-untouched-when-its-ticket-ends
  (let [{:keys [engine] :as r} (unadopted-world nil)
        _ (a-ticket-at! engine :done)]
    (rewrite-unadopted! engine #(assoc-in % [:data :number] 7))
    (let [census (pass! r)
          row (the-unadopted engine)]
      (is (not= "closed" (name (:state row))))
      (is (nil? (get-in row [:data :superseded_by])))
      (is (= 0 (:unopened-closed census))))))

(deftest the-adoption-note-says-when-the-forge-already-ended-it
  (is (= (str "landed as #7 on " repo " but no pull request row adopted"
              " it; head b; the pull request is already merged on the forge")
         (forge/adoption-note repo "b" {:number 7 :state "merged"})))
  (is (str/ends-with? (forge/adoption-note repo "b" {:number 7 :state "closed"})
                      "already closed on the forge"))
  (is (str/ends-with? (forge/adoption-note repo "b" {:number 7 :state "open"})
                      "head b")))

(deftest the-landing-verdict-names-the-failed-step
  (is (= {:verdict :red :names ["landing:push"] :error a-rejected-push}
         (forge/landing-verdict (a-failed-landing))))
  (is (= {:verdict :red :names ["landing:push"]}
         (forge/landing-verdict {:state "failed" :failed_step "push"
                                 :error "rejected"}))
      "a landing that names its failed step and no steps still says which;
       output off the steps is not the rig's shape and is not read")
  (is (= {:verdict :red :names ["landing:unknown"]}
         (forge/landing-verdict {:state "failed"}))
      "a failed landing that names nothing is still red")
  (is (= {:verdict :running} (forge/landing-verdict {:state "running"})))
  (is (nil? (forge/landing-verdict {:state "succeeded"})))
  (is (nil? (forge/landing-verdict nil))))

;; The rig's own `feedback.landing` (ckopsa/waymark-bench
;; bench/landing.py): {state, failed_step, steps: [{name, state,
;; seconds, exit_code, output, commit}]}. A rename on the rig must break
;; these, not read a failed landing as not failed (ticket 92871afb).

(def ^:private rig-commit-step
  {:name "commit" :state "passed" :seconds 0.4 :exit_code 0
   :output "[bench/one 1bae3a0] Fix" :commit "1bae3a0"})

(def ^:private rig-failed-landing
  {:state "failed" :failed_step "push"
   :steps [rig-commit-step
           {:name "push" :state "failed" :seconds 1.2 :exit_code 1
            :output a-rejected-push :commit nil}]})

(def ^:private rig-running-landing
  {:state "running" :failed_step nil
   :steps [rig-commit-step
           {:name "push" :state "running" :seconds nil :exit_code nil
            :output nil :commit nil}]})

(def ^:private rig-passed-landing
  {:state "passed" :failed_step nil
   :steps [rig-commit-step
           {:name "push" :state "passed" :seconds 1.1 :exit_code 0
            :output "To github.com:ckopsa/waymark.git" :commit "1bae3a0"}]})

(deftest the-landing-verdict-reads-the-rigs-shape
  (is (= {:verdict :red :names ["landing:push"] :error a-rejected-push}
         (forge/landing-verdict rig-failed-landing))
      "the failed step's name and output ride the red name")
  (is (= {:verdict :red :names ["landing:push"] :error a-rejected-push}
         (forge/landing-verdict (dissoc rig-failed-landing :failed_step)))
      "without failed_step, the first step whose state failed is the one")
  (is (= {:verdict :running} (forge/landing-verdict rig-running-landing)))
  (is (nil? (forge/landing-verdict rig-passed-landing)))
  (is (nil? (forge/landing-verdict {:status "failed"}))
      "`status` is not the rig's key: only `state` is read")
  (is (= {:verdict :red :names ["landing:unknown"]}
         (forge/landing-verdict
          {:state "failed"
           :steps [{:step "push" :state "failed" :error "rejected"}]}))
      "a step's name and output are read from `name` and `output` only"))

(deftest a-conflict-joins-the-red-names
  (let [row {:data {:mergeable "conflicted"}}]
    (is (= {:verdict :red :names ["a" "merge-conflict"]}
           (forge/with-conflict {:verdict :red :names ["a"]} row)))
    (is (= {:verdict :red :names ["merge-conflict"]}
           (forge/with-conflict {:verdict :green} row)))
    (is (= {:verdict :red :names ["merge-conflict"]}
           (forge/with-conflict nil row))
        "a conflict fails the change while its checks still run")
    (is (= {:verdict :green}
           (forge/with-conflict {:verdict :green}
                                {:data {:mergeable "blocked"}})))))

;; ── a run that died without a verdict (ticket 22f91244) ────────────

(def ^:private the-head (get-in a-pull-request [:head :sha]))

(def ^:private died-in-set-up
  [{:name "Set up job" :conclusion "success"}
   {:name "Initialize containers" :conclusion "failure"}
   {:name "Run the tests" :conclusion "skipped"}])

(def ^:private died-in-a-test
  [{:name "Set up job" :conclusion "success"}
   {:name "Run the tests" :conclusion "failure"}])

(defn- interrupted-world
  "One change at `submitted`, whose one required check ended with
  `conclusion`, on a head whose one workflow run ended with one job at
  `job-conclusion` and `steps`."
  [conclusion job-conclusion steps]
  (let [state (gh/fake-state)
        engine (boot)
        r {:state state :source (gh/fake-source state) :engine engine}]
    (gh/seed-pull! state repo a-pull-request
                   {:files the-files :reviews the-reviews})
    (gh/seed-check! state repo the-head
                    {:id 41752098700 :name "test10 (shard 3)"
                     :status "completed" :conclusion conclusion
                     :head_sha the-head})
    (gh/seed-run! state repo the-head
                  {:id 900 :workflow_id 11 :head_sha the-head
                   :status "completed" :conclusion job-conclusion})
    (gh/seed-job! state repo 900
                  {:id 7001 :run_id 900 :name "test10 (shard 3)"
                   :status "completed" :conclusion job-conclusion
                   :steps steps})
    (inv/create! engine :repo_policy
                 {:repository repo :required_checks ["test10 (shard 3)"]}
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
    r))

(deftest an-interrupted-run-is-re-run-once-per-head
  (let [{:keys [state engine] :as r}
        (interrupted-world "cancelled" "cancelled" [])
        census (pass! r)
        row (the-change engine)]
    (is (= [{:repository repo :run 900}] (gh/reruns state))
        "the run that died without a verdict runs again")
    (is (= 1 (:rerun census)))
    (is (= the-head (get-in row [:data :rerun_head]))
        "the head is remembered on the row, so a restart does not forget it")
    (is (= :submitted (:state row)) "a cancel is not red")
    (testing "the same head interrupted again is not re-run a second time"
      (let [census (pass! r)
            row (the-change engine)]
        (is (= 1 (count (gh/reruns state))))
        (is (= 0 (:rerun census)))
        (is (= 1 (:rerun-noted census)))
        (is (str/includes? (str (get-in row [:data :rerun_note]))
                           (subs the-head 0 12))
            "and the change says so, so a person sees a broken runner")))
    (testing "a third pass writes the note no second time"
      (let [census (pass! r)]
        (is (= 0 (:rerun-noted census)))
        (is (= 1 (count (gh/reruns state))))))))

(deftest a-run-that-failed-in-set-up-is-re-run-and-not-failed
  (let [{:keys [state engine] :as r}
        (interrupted-world "failure" "failure" died-in-set-up)
        census (pass! r)]
    (is (= [{:repository repo :run 900}] (gh/reruns state)))
    (is (= 1 (:rerun census)))
    (is (= :submitted (:state (the-change engine)))
        "the re-run is the move this pass: no seat is woken for the runner")
    (testing "the same head red in set-up again goes failing as red"
      (let [census (pass! r)]
        (is (= 1 (count (gh/reruns state))))
        (is (= 1 (:rerun-noted census)))
        (is (= :failing (:state (the-change engine))))))))

(deftest a-run-with-a-red-test-step-is-not-re-run
  (let [{:keys [state engine] :as r}
        (interrupted-world "failure" "failure" died-in-a-test)
        census (pass! r)]
    (is (empty? (gh/reruns state)) "a red test is a verdict about the code")
    (is (= 0 (:rerun census)))
    (is (= :failing (:state (the-change engine))))
    (is (nil? (get-in (the-change engine) [:data :rerun_head])))))

(deftest a-run-is-interrupted-only-with-no-red-job
  (let [run (fn [& jobs] {:status "completed" :jobs (vec jobs)})]
    (is (forge/interrupted-run? (run {:conclusion "timed_out"})))
    (is (forge/interrupted-run? (run {:conclusion "failure" :steps []}))
        "a job killed with no step failing died without a verdict")
    (is (not (forge/interrupted-run?
              (run {:conclusion "failure" :steps died-in-a-test}
                   {:conclusion "cancelled"})))
        "a fail-fast sibling cancelled beside a red test is red")
    (is (not (forge/interrupted-run? (run {:conclusion "success"}))))
    (is (not (forge/interrupted-run?
              {:status "in_progress" :jobs [{:conclusion "cancelled"}]}))
        "a run still running is not judged")))

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
    r))

(deftest a-missing-check-and-a-moved-base-are-written-on-the-change
  (let [{:keys [engine] :as r} (missing-world [])
        census (pass! r)
        row (the-change engine)]
    (is (= ["gate"] (get-in row [:data :missing_checks])))
    (is (true? (get-in row [:data :behind_base])))
    (is (some? (get-in row [:data :base_compared_at])))
    (is (= the-head (get-in row [:data :missing_checks_head])))
    (is (= 1 (:stale-noted census)))
    (testing "an unchanged read writes nothing"
      (is (= 0 (:stale-noted (pass! r)))))))

(deftest a-missing-check-on-a-head-that-holds-its-base-is-not-behind
  (let [{:keys [state engine] :as r} (missing-world [])]
    (gh/seed-ancestor! state repo the-head "main")
    (pass! r)
    (let [row (the-change engine)]
      (is (= ["gate"] (get-in row [:data :missing_checks])))
      (is (false? (get-in row [:data :behind_base]))))))

(deftest a-running-required-check-is-not-missing
  (let [{:keys [engine] :as r}
        (missing-world [{:id 41752098800 :name "gate" :status "in_progress"
                         :head_sha the-head}])]
    (pass! r)
    (let [row (the-change engine)]
      (is (= [] (get-in row [:data :missing_checks])))
      (is (nil? (get-in row [:data :behind_base]))
          "no compare is made when nothing is missing"))))

(deftest missing-checks-counts-only-checks-with-no-run
  (is (= ["tests"] (forge/missing-checks ["gate" "tests" "gate"]
                                         [{:check_name "gate" :status "queued"}]))))
