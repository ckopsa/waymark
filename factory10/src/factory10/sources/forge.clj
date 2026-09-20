(ns factory10.sources.forge
  "The seam between a forge and the factory's rows, and the pass that
  moves one across the other (bead waymark-fp62.6.4, Reading A).

  THE PROTOCOL IS NEW, AND IT IS SMALL. The change and the ci_run are
  NOT mirror-synced kinds: neither declares an `:adapter`, each keeps
  a state machine of its own, and the engine's Mirror knows nothing
  about them. So this source does not implement workqueue10's
  `TaskSource`, and it does not reuse `ThreadSource` either. It
  implements `ForgeSource`, declared below, for `ThreadSource`'s own
  argument: a source must not answer a question its authority never
  asks. A forge has no `push`, no `create` and no `list`; it has pull
  requests, check runs, job logs and exactly one write. Four verbs say
  that, and a fifth would be a lie about GitHub.

  WHERE THE ENGINE IS. workqueue10's confluence hands its documents to
  the sync machine, which owns the writes. Nothing owns the writes
  here, because these two kinds have no sync machine — so `pass!`
  below writes the rows itself, through the engine's own doors, under
  `factory10.mirror/source-principal`. That principal is a system
  hand, which is what `the-mirror-writes-this-row` admits and what
  every door on both kinds stands behind. The pass invents no id: it
  reads a row by its forge identity, mints one when there is none, and
  moves the row it read.

  WHAT ONE PASS DOES, IN ORDER.
  1. It asks the forge for everything that moved (`forge-poll`).
  2. For each pull request it mints a `change` row, or it moves the
     row that is already here: `observe` for the facts, then `merge`,
     `close` or `reopen` for the state. A pull request whose id
     answers no row, on a head branch a row of this house already
     names, is ADOPTED rather than minted (bead waymark-fp62.6.3.10):
     a seat built that branch from an ask, and its submit opened this
     pull request. The house must hold one row for that work, not
     two.
  3. For each finished check run that failed, on a head the change
     still carries, it reads the log tail (`forge-log-tail`) and mints
     one `ci_run` row at `red`. It mints no second row for a check run
     id that is already here. A log it cannot read costs the excerpt
     and never the row: the reason rides in `log_note`.
  4. For each red ci_run of a head its change no longer carries, it
     walks `supersede`. The row leaves the queue, and the transition
     says why it leaves with no verdict on it.
  5. For each `classified` ci_run with no label on it, it pushes one
     label (`forge-label!`) and walks `stamp_label`. That push is the
     only write the source makes at the forge.
  6. It prints one census line: the calls, the rows minted and the
     rows moved.

  A HEAD THAT MOVES (bead waymark-fp62.6.9). A run ran on one commit.
  When the head moves, that commit is gone and no seat can answer the
  run: the pass mints no new red row for the dead head, and it walks
  the `supersede` door on every red row that is already here. The row
  leaves the queue at a tomb of its own, the ledger carries the
  transition, and the new head's own failures are the work. Before
  that door the pass could only COUNT these rows, and they stayed at
  `red` in the classifier's queue.

  PARTIAL FAILURE, per workqueue10.confluence. A repository that
  throws costs its own rows one pass and nothing else. The cursor
  advances only when EVERY configured repository answered, so a
  failure in the middle of a pass makes the next pass re-read rather
  than skip.

  THE LABEL RIDES THE PASS, not a transition consumer. The framework
  has a durable consumer API (waymark10.server.consumers, which
  server/wakes rides), and a consumer would push the label seconds
  after the verdict instead of within one cadence. It is not used
  here for one reason: a consumer needs the event dispatcher, and the
  source must run over a store with no dispatcher at all. Acceptance 4
  asks for the label within one cadence, which the pass gives. The
  consumer is recorded as the cheaper beat, not as a punt."
  (:require [clojure.string :as str]
            [factory10.mirror :as mirror]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(def default-every-seconds
  "How often the pass runs when the wiring names no cadence. Five
  minutes, which is the owner's ruling for the proving ground: one
  repository at this beat is far under the forge's rate limit."
  300)

(def label-scan-limit
  "How many classified runs one pass reads for the label push. A
  ceiling rather than the whole table: a run that waits one more beat
  costs nothing, and an unbounded read costs the pass."
  50)

;; ── the seam ────────────────────────────────────────────────────────

(defprotocol ForgeSource
  "One forge, already speaking the factory's own documents. Four
  verbs: what moved, one log tail, one label, and the call count the
  census prints."
  (forge-poll [s]
    "→ {:changes [change-doc …] :checks [check-doc …]
        :repositories [name …] :complete? bool}.

    A change document carries the `change` kind's own fields plus
    `:forge_state` (\"open\", \"merged\" or \"closed\"). A check
    document carries the `ci_run` kind's own fields plus
    `:change_id` (the change it ran on) and whatever the forge needs
    to find the log later. `:complete?` is false when a repository
    did not answer; the source holds its cursor where it was.")
  (forge-log-tail [s check]
    "→ {:excerpt \"…\" :note \"…\" } for one check document: the tail
    of the failed job's log. A log the forge will not hand over in
    plain text answers an empty excerpt and a note that says why.
    Never throws — a missing log costs the excerpt, never the row.")
  (forge-label! [s change label]
    "Push ONE label to the pull request the change document names.
    The only write this source makes. Throws when the forge refuses.")
  (forge-calls [s]
    "→ how many calls this source made since the last `forge-poll`
    started. The census line's first number."))

;; ── what the two kinds take ─────────────────────────────────────────

(def change-create-fields
  "The `change` create door's whole vocabulary. The door is a closed
  map, so a field it does not name refuses the mint."
  [:change_id :repository :number :title :author :base_branch
   :head_branch :head_sha :draft :url])

(def change-observe-fields
  "What `observe` writes. The state is NOT here: the machine advances
  the state and a handler never does."
  [:title :head_sha :draft :mergeable :files_changed :lines_added
   :lines_removed :touched_paths :labels :review_state])

(def observe-doors
  "Which door writes the facts, read from the row's state. `observe` is
  a self-loop on `open`. The bench's `submitted` state carries a door
  of its own, because a v10 action declares one `:to` and a self-loop
  is spelled once for each state (bead waymark-fp62.6.3.2). A state
  that is in neither column keeps its stored facts: a closed row has
  no observe door at all, and the pass does not argue with the
  machine."
  {:open :observe
   :submitted :observe_submitted})

(def forge-id-prefix
  "What a `change_id` the FORGE owns starts with. A row the engine
  minted for a seat's ask starts with the walk kind's own name and a
  colon instead (spec-seat.md R-12.32), so this prefix is what tells
  a pull request's row from an ask's row.

  waymark10.server.mcp writes the other spelling. The two namespaces
  share no code, and they do not need to: one writes `<kind>:<id>`
  and this one only asks whether an id is GitHub's."
  "github:")

(def adopt-doors
  "Which door writes the forge's identity onto a row this house
  already holds, read from the row's state. The same two states
  `observe` serves, and for the same reason: a v10 action declares
  one `:to`, so a self-loop is spelled once for each state. A state
  in neither column is adopted by nobody — a stuck, merged or closed
  row is not a row waiting for its pull request."
  {:open :adopt
   :submitted :adopt_submitted})

(def adoption-scan-limit
  "How many rows of one branch the adoption reads. A head branch
  holds one change in every house that works, and the ceiling is
  there so a repository with a strange branch cannot make the pass
  read a table."
  10)

(def adopt-fields
  "What the adoption writes: the pull request's own identity, and
  nothing else. The facts follow through `observe`, as they do for
  every other row."
  [:change_id :number :url])

(def ci-run-create-fields
  "The `ci_run` create door's whole vocabulary. `verdict`, `remedy`,
  `pushed_label` and `labelled_at` are absent on purpose: a row born
  with a verdict is a row that skipped the walk."
  [:run_id :change :head_sha :check_name :conclusion :log_excerpt
   :log_note :started_at :finished_at :url])

(def red-conclusions
  "The two conclusions that mint a red run. `cancelled` is not one of
  them: somebody stopped that job, so there is nothing to classify."
  #{"failure" "timed_out"})

;; ── the engine's own hand ───────────────────────────────────────────

(defn- as-opts []
  {:principal mirror/source-principal})

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "factory10 forge: " parts))))

(defn- present
  "The document, narrowed to the fields named and to what the forge
  actually read. A field the forge did not read is absent, and absent
  means silent: the stored value stands."
  [doc fields]
  (into {} (keep (fn [k] (when-some [v (get doc k)] [k v]))) fields))

(defn- row-by
  "One row of this kind by an indexed field, or nil."
  [eng kind where]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (first (store/query-rows st tx kind where
                                                        {:limit 1}))))))

(defn- rows-by
  "Up to `limit` rows of this kind by indexed fields — `row-by` with
  the page it reads made explicit, for the one reader that must
  CHOOSE between the rows a filter answers."
  [eng kind where limit]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/query-rows st tx kind where
                                                 {:limit limit})))))

(defn- row-by-id [eng kind id]
  (let [st (:storage eng)]
    (when-not (str/blank? (str id))
      (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {}))))))

(defn- state-of
  "The row's state as a keyword, whichever way the store spells it."
  [row]
  (some-> (:state row) name keyword))

(defn- changed-facts
  "The facts that moved under the row. A value equal to the stored one
  is not written again, so a pass that saw nothing new logs nothing."
  [row doc]
  (into {}
        (keep (fn [[k v]] (when (not= v (get-in row [:data k])) [k v])))
        (present doc change-observe-fields)))

(defn- state-door
  "The one door from the row's state to the forge's. nil when the row
  already says what the forge says, and nil for a merged row: that
  state is the kind's only tomb.

  A `submitted` or `stuck` row follows the forge too (bead
  waymark-fp62.6.3.14). A seat-born change stands at `submitted` once
  the seat has pushed, and that is the state the adoption keeps — so
  the pull request GitHub then merges must move THAT row, or the row
  never reaches `merged` and the task it was born from is never
  completed. The kind's own `merge` and `close` doors admit all three
  states; this table only says the same."
  [row-state forge-state]
  (case [row-state (str forge-state)]
    [:open "merged"] :merge
    [:submitted "merged"] :merge
    [:stuck "merged"] :merge
    [:open "closed"] :close
    [:submitted "closed"] :close
    [:stuck "closed"] :close
    [:closed "open"] :reopen
    nil))

(defn- mint-change!
  "One pull request the house has never seen → one `change` row. The
  create door takes the identity and the words; `observe` takes the
  counts, because the create door does not name them."
  [eng doc]
  (let [row (:row (inv/create! eng :change (present doc change-create-fields)
                               (as-opts)))
        facts (present doc change-observe-fields)]
    (if (seq facts)
      (:row (inv/invoke! eng :change (str (:id row)) :observe facts (as-opts)))
      row)))

(defn- move-change!
  "The row that is already here, moved to what the forge says now. The
  facts are written while the row can still take them — `observe` is a
  self-loop on `open` — and the state moves after. A row that comes
  back from `closed` reopens first, because a closed row has no
  `observe` door at all.

  → [row moved?]"
  [eng row doc]
  (let [id (str (:id row))
        from (state-of row)
        door (state-door from (:forge_state doc))
        reopening? (= :reopen door)
        row (if reopening?
              (:row (inv/invoke! eng :change id :reopen {} (as-opts)))
              row)
        facts (changed-facts row doc)
        observe-door (get observe-doors (state-of row))
        observing? (boolean (and (seq facts) observe-door))
        row (if observing?
              (:row (inv/invoke! eng :change id observe-door facts (as-opts)))
              row)
        row (if (and door (not reopening?))
              (:row (inv/invoke! eng :change id door {} (as-opts)))
              row)]
    [row (boolean (or door observing?))]))

(defn- adoptable-row
  "The row this house minted for an ask, waiting for the pull request
  its own submit opened (bead waymark-fp62.6.3.10), or nil.

  Three things make one: the same repository, the same head branch,
  and a `change_id` that is not the forge's. A row at a state with no
  adopt door is not one — a stuck, merged or closed row on that
  branch is a row whose story is elsewhere. nil is the ordinary
  answer: almost every pull request the forge reads was opened by a
  person, and that is a mint and not an adoption."
  [eng doc]
  (when-some [head (some-> (:head_branch doc) str not-empty)]
    (when-not (str/blank? (str (:repository doc)))
      (->> (rows-by eng :change {:repository (str (:repository doc))
                                 :head_branch head}
                    adoption-scan-limit)
           (filter (fn [row]
                     (and (not (str/starts-with?
                                (str (get-in row [:data :change_id]))
                                forge-id-prefix))
                          (contains? adopt-doors (state-of row)))))
           first))))

(defn- adopt-change!
  "The row the house minted, given the pull request's own identity:
  the id, the number and the url, through the mirror's own `adopt`
  door. `change_id` is `:unique`, so the write lands whole or the
  transaction does not land at all — a second pull request can never
  take a row that is already spoken for.

  → the row, with GitHub's identity on it."
  [eng row doc]
  (:row (inv/invoke! eng :change (str (:id row))
                     (get adopt-doors (state-of row))
                     (present doc adopt-fields)
                     (as-opts))))

(defn- change-pass!
  "Every pull request the forge answered → a row minted, a row
  adopted, or a row moved. A row the engine refuses is counted and
  skipped: the next pass offers it again."
  [eng changes census log-fn]
  (reduce
   (fn [census doc]
     (try
       (if-some [existing (row-by eng :change {:change_id (:change_id doc)})]
         (let [[_ moved?] (move-change! eng existing doc)]
           (cond-> census moved? (update :moved inc)))
         (if-some [ours (adoptable-row eng doc)]
           ;; the house asked for this pull request: the seat built
           ;; the branch and its submit opened it. One row, adopted
           ;; where it stands, rather than a second row beside it
           (do (move-change! eng (adopt-change! eng ours doc) doc)
               (update census :adopted inc))
           ;; a birth lands at `open`, because that is the kind's
           ;; initial state; a pull request first seen after it merged
           ;; walks its door in the same pass rather than waiting for
           ;; a move that will never come again
           (do (move-change! eng (mint-change! eng doc) doc)
               (update census :minted inc))))
       (catch Exception e
         (log-fn "the pull request " (:change_id doc)
                 " was refused a row (" (ex-message e) ")")
         (update census :refused inc))))
   census
   changes))

;; ── the red runs ────────────────────────────────────────────────────

(defn- mint-run!
  "One failed check run → one `ci_run` row at red, with the log tail
  on it and a ref to the change it ran on."
  [eng source check change-row census log-fn]
  (let [{:keys [excerpt note]} (try (forge-log-tail source check)
                                    (catch Exception e
                                      {:excerpt nil
                                       :note (str "the log could not be read ("
                                                  (ex-message e) ")")}))
        ;; THE NOTE HAS A FIELD OF ITS OWN (bead waymark-fp62.6.9). A
        ;; sentence written inside `log_excerpt` reads as the end of a
        ;; build log to the next reader, and the classifier reasons
        ;; about it as one. So the excerpt stays empty and `log_note`
        ;; says why it is empty. `present` drops a nil, which is what
        ;; makes each of the two absent when the other answers.
        blank? (str/blank? (str excerpt))
        body (present (assoc check
                             :change (str (:id change-row))
                             :log_excerpt (when-not blank? excerpt)
                             :log_note (when blank? note))
                      ci-run-create-fields)]
    (try
      (inv/create! eng :ci_run body (as-opts))
      (update census :runs-minted inc)
      (catch Exception e
        (log-fn "the check run " (:run_id check) " was refused a row ("
                (ex-message e) ")")
        (update census :refused inc)))))

(defn- run-pass!
  "Every finished failure the forge answered → a red row, once. A run
  on a head the change no longer carries mints NOTHING and is counted
  as skipped: the head moved under it while the pass was reading."
  [eng source checks census log-fn]
  (reduce
   (fn [census check]
     (cond
       (not (contains? red-conclusions (str (:conclusion check))))
       census

       (some? (row-by eng :ci_run {:run_id (:run_id check)}))
       (update census :runs-known inc)

       :else
       (if-some [change-row (row-by eng :change {:change_id (:change_id check)})]
         (if (= (str (:head_sha check))
                (str (get-in change-row [:data :head_sha])))
           (mint-run! eng source check change-row census log-fn)
           (update census :runs-skipped inc))
         (update census :runs-orphan inc))))
   census
   checks))

(defn- stale-reds
  "The red rows of this change that ran on a head it no longer
  carries. A run of a dead commit is a run nobody can answer, so
  these are the rows the `supersede` door is for."
  [eng change-row]
  (let [st (:storage eng)
        head (str (get-in change-row [:data :head_sha]))
        rows (store/with-tx st
               (fn [tx] (store/query-rows st tx :ci_run
                                          {:state "red"
                                           :change (str (:id change-row))}
                                          {:limit label-scan-limit})))]
    (filterv #(not= head (str (get-in % [:data :head_sha]))) rows)))

(defn- stale-pass!
  "Every red run of a head its change no longer carries → the
  `supersede` door. The row leaves the classifier's queue and the
  transition says it left with no verdict. A row the engine refuses is
  counted and skipped: the next pass offers it again."
  [eng changes census log-fn]
  (reduce
   (fn [census doc]
     (if-some [change-row (row-by eng :change {:change_id (:change_id doc)})]
       (reduce
        (fn [census row]
          (try
            (inv/invoke! eng :ci_run (str (:id row)) :supersede {} (as-opts))
            (update census :runs-superseded inc)
            (catch Exception e
              (log-fn "the red run " (get-in row [:data :run_id])
                      " was refused the supersede door (" (ex-message e) ")")
              (update census :refused inc))))
        census
        (stale-reds eng change-row))
       census))
   census
   changes))

;; ── the one write ───────────────────────────────────────────────────

(defn- unlabelled?
  "A classified run that has a verdict and no label yet."
  [row]
  (and (not (str/blank? (str (get-in row [:data :verdict]))))
       (str/blank? (str (get-in row [:data :pushed_label])))))

(defn- label-pass!
  "Every classified run with no label → one label at the forge, then
  the `stamp_label` door. The door is what puts the push in the
  transition log, so a pushed label can be told from an intended one.
  A push that fails leaves the row unstamped, and the next pass tries
  again."
  [eng source census log-fn]
  (let [st (:storage eng)
        rows (store/with-tx st
               (fn [tx] (store/query-rows st tx :ci_run {:state "classified"}
                                          {:limit label-scan-limit})))]
    (reduce
     (fn [census row]
       (let [label (mirror/label-for (str (get-in row [:data :verdict])))
             change-row (row-by-id eng :change (get-in row [:data :change]))]
         (cond
           (nil? label) census

           (nil? change-row)
           (do (log-fn "the classified run " (get-in row [:data :run_id])
                       " names no change this house holds; no label was "
                       "pushed")
               census)

           ;; a change a seat built from an ask has no pull request
           ;; until its submit opens one and the next pass adopts the
           ;; row (bead waymark-fp62.6.3.10). There is nothing at the
           ;; forge to label yet, and a push with no number would
           ;; spend a call to be refused.
           (nil? (get-in change-row [:data :number]))
           (do (log-fn "the classified run " (get-in row [:data :run_id])
                       " names a change with no pull request yet; no "
                       "label was pushed")
               census)

           :else
           (try
             (forge-label! source
                           {:repository (get-in change-row [:data :repository])
                            :number (get-in change-row [:data :number])}
                           label)
             (inv/invoke! eng :ci_run (str (:id row)) :stamp_label
                          {:label label} (as-opts))
             (update census :labelled inc)
             (catch Exception e
               (log-fn "the label " label " did not land on "
                       (get-in change-row [:data :change_id]) " ("
                       (ex-message e) "); the next pass tries again")
               (update census :refused inc))))))
     census
     (filterv unlabelled? rows))))

;; ── the pass ────────────────────────────────────────────────────────

(def ^:private fresh-census
  {:calls 0 :repositories 0 :complete? true :minted 0 :adopted 0 :moved 0
   :runs-minted 0 :runs-known 0 :runs-skipped 0 :runs-superseded 0
   :runs-orphan 0 :labelled 0 :refused 0})

(defn pass!
  "One pass of the factory mirror.

  config:
  :source     the ForgeSource
  :engine     the engine, or
  :engine-ref an atom the wiring fills before the first beat
  :log-fn     where the census line goes (default *err*)

  → the census map. A repository that will not answer costs its own
  rows one pass and holds the cursor; one row the engine refuses is
  counted and skipped. The pass throws only when it has no engine to
  write to, which is a wiring fault and not a bad beat."
  [{:keys [source engine engine-ref log-fn]}]
  (let [eng (or engine (some-> engine-ref deref))
        log-fn (or log-fn warn!)]
    (when (nil? eng)
      (throw (ex-info "the factory mirror has no engine yet" {})))
    (let [{:keys [changes checks repositories complete?]} (forge-poll source)
          census (assoc fresh-census
                        :repositories (count repositories)
                        :complete? (boolean complete?))
          census (change-pass! eng changes census log-fn)
          census (run-pass! eng source checks census log-fn)
          census (stale-pass! eng changes census log-fn)
          census (label-pass! eng source census log-fn)
          census (assoc census :calls (forge-calls source))]
      (log-fn (:calls census) " calls, " (count changes) " pull requests, "
              (:minted census) " changes minted, " (:adopted census)
              " changes adopted, " (:moved census)
              " changes moved, " (:runs-minted census) " red runs minted, "
              (:labelled census) " labels pushed"
              (when (pos? (long (:runs-superseded census)))
                (str ", " (:runs-superseded census) " red runs superseded "
                     "on a head that moved"))
              (when (pos? (long (:refused census)))
                (str ", " (:refused census) " refused"))
              (when-not (:complete? census)
                ", and a repository did not answer — the cursor stands"))
      census)))

;; ── the cadence ─────────────────────────────────────────────────────

(defn start-passes!
  "The factory mirror's daemon: one `pass!` now and every
  :every-seconds after, on a daemon thread (the inbox source's shape,
  workqueue10). A beat that throws is warned and the NEXT beat still
  runs; nothing in a pass may kill the loop. The wiring owns the
  lifecycle and elects the one holder per database; tests call `pass!`
  directly."
  [config {:keys [every-seconds] :or {every-seconds default-every-seconds}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (try (pass! config)
                            (catch Exception e
                              (warn! "pass failed (" (ex-message e)
                                     "); the stored rows keep serving and "
                                     "the next beat still runs")))
                       (when-not (.await stop (long (* 1000 every-seconds))
                                         TimeUnit/MILLISECONDS)
                         (recur))))
                   "factory10-forge")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-passes! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
