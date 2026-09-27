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
  requests, check runs, job logs and exactly one write. Five verbs say
  that (the fifth, the checks on one head, is ticket d1742908's), and
  a verb that wrote anything else would be a lie about GitHub.

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
  6. For each `submitted` or `failing` change of a repository with an
     active policy, it reads the checks on the head (`forge-checks`)
     against the policy's required checks and walks `fail`, `recover`
     or, on the last round, `stick` (ticket d1742908). A change the
     forge reads as `conflicted` is red too, as `merge-conflict`, with
     the paths the bench's trial merge names (ticket 5f12e772).
  7. For each active policy whose repository the forge refused to
     list (401, 403 or 404), it writes the status, the route and the
     time on the policy row through the hidden `note_source` door, and
     clears that note the first pass the repository answers again
     (ticket 116dfb0d).
  8. For each active policy it reads the head of its base branch and
     the checks on it (`forge-base`). A base red on two passes opens
     one groomed fix ticket in that repository, a later red head is
     noted on it, and a green base completes it (ticket ade81ae9).
  9. It prints one census line: the calls, the rows minted and the
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
            [factory10.bench :as bench]
            [factory10.mirror :as mirror]
            [factory10.resources.repo-policy :as policy]
            [factory10.resources.ticket :as ticket]
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
  "One forge, already speaking the factory's own documents. Six
  verbs: what moved, one log tail, one label, the call count the
  census prints, the checks on one head, and the head of a base
  branch with its checks."
  (forge-poll [s]
    "→ {:changes [change-doc …] :checks [check-doc …]
        :repositories [name …] :complete? bool}.

    A change document carries the `change` kind's own fields plus
    `:forge_state` (\"open\", \"merged\" or \"closed\"). A check
    document carries the `ci_run` kind's own fields plus
    `:change_id` (the change it ran on) and whatever the forge needs
    to find the log later. `:complete?` is false when a repository
    did not answer; the source holds its cursor where it was. A source
    may also answer `:answered` (the repositories that did answer) and
    `:refusals` ({repository {:status :route}} for each whose pulls
    listing refused the token), which the pass writes on the policy
    rows (ticket 116dfb0d).")
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
    started. The census line's first number.")
  (forge-checks [s repository head-sha]
    "→ [{:check_name :status :conclusion} …] for every check run on
    one head, the latest of each (ticket d1742908). The poll reads
    checks only on pull requests that moved, and a check that finishes
    moves no pull request, so the failing pass asks head by head.
    Throws when the forge does not answer.")
  (forge-base [s repository branch]
    "→ {:head_sha sha :checks [check-doc …]} for the head of one branch,
    or nil when the branch has none (ticket ade81ae9). Each check
    carries `:check_name`, `:status` and `:conclusion`, which
    `check-verdict` reads, and whatever `forge-log-tail` needs to find
    its log. Throws when the forge does not answer."))

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
   :submitted :observe_submitted
   :failing :observe_failing})

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
    [:failing "merged"] :merge
    [:stuck "merged"] :merge
    [:open "closed"] :close
    [:submitted "closed"] :close
    [:failing "closed"] :close
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

(def log-note-chars
  "How long `log_note` may be. One sentence about why there is no
  log, at the same ceiling a remedy sentence has: a plain text field
  wider than that reads as prose and the usability battery says so.
  A forge's error message can run past it, so the note is cut here
  rather than refused a row."
  240)

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
                             :log_note (when (and blank? note)
                                         (subs (str note) 0
                                               (min (count (str note))
                                                    log-note-chars))))
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

;; ── the checks' verdict on a submitted change (ticket d1742908) ───────

(def green-conclusions
  "The conclusions a required check may finish with and still let the
  change merge. `neutral` and `skipped` are GitHub's own passes."
  #{"success" "neutral" "skipped"})

(defn check-verdict
  "What the checks on one head say, read against the policy's required
  checks: {:verdict :red :names […]} when every required check
  finished and at least one went red, {:verdict :green} when every one
  finished green, and nil while one is still running, has not started,
  or ended some other way (a cancel). A policy that names no required
  check requires every check on the head."
  [required checks]
  (let [by-name (group-by #(str (:check_name %)) checks)
        names (if (seq required)
                (vec (distinct required))
                (vec (sort (remove str/blank? (keys by-name)))))
        runs (mapv #(get by-name %) names)
        all? (fn [pred] (every? (fn [rs] (and (seq rs) (every? pred rs))) runs))]
    (when (and (seq names) (all? #(= "completed" (str (:status %)))))
      (let [red (filterv (fn [n] (some #(contains? red-conclusions
                                                   (str (:conclusion %)))
                                       (get by-name n)))
                         names)]
        (cond
          (seq red) {:verdict :red :names red}
          (all? #(contains? green-conclusions (str (:conclusion %))))
          {:verdict :green}
          :else nil)))))

(def failing-scan-limit
  "How many rows of each of the two live states one pass reads."
  200)

(def ^:private why-chars 480)

(defn- live-changes
  "Every change a seat has pushed and nobody has merged: `submitted`
  and `failing`, read from the store and not from the poll — a check
  that finishes moves no pull request, so the window would miss it."
  [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (into []
              (mapcat #(store/query-rows st tx :change {:state %}
                                         {:limit failing-scan-limit}))
              ["submitted" "failing"])))))

;; ── a branch that conflicts with its base (ticket 5f12e772) ──────────
;;
;; A conflicted pull request runs no fresh checks, so it never goes
;; red: it just sits. The row's `mergeable` already reads `conflicted`
;; from the forge (`behind` is `blocked` there, and a branch that is
;; only behind is not failing — the merge brings it forward). So the
;; verdict counts a conflict as one more red name, `merge-conflict`,
;; and the move below is the red move: `failing` under the round
;; ceiling, `stuck` on the last round. The way back is a head with no
;; conflict and green checks, exactly as for a red check.

(def merge-conflict
  "The name a conflict rides in `failing_checks`."
  "merge-conflict")

(defn conflicted?
  "Does the forge say this change conflicts with its base?"
  [row]
  (= "conflicted" (str (get-in row [:data :mergeable]))))

(defn with-conflict
  "The checks' verdict, read together with the row's merge state. A
  conflicted row is red whatever its checks say — still running, green
  or red — and `merge-conflict` joins the red names."
  [verdict row]
  (if (conflicted? row)
    {:verdict :red
     :names (vec (distinct (conj (vec (when (= :red (:verdict verdict))
                                         (:names verdict)))
                                 merge-conflict)))}
    verdict))

(def ^:private conflict-path-limit 50)

(defn conflict-paths
  "The paths a trial merge of the base into the change's branch leaves
  unmerged, asked of the bench with the engine's own hand, or nil. The
  forge does not name them; the rig can, in its worktree, and aborts
  the merge after. A rig with no such tool, or no rig at all, answers
  nil — the change still goes failing, only without its paths."
  [eng row]
  (let [answer (bench/ask {:services (:services eng)} :conflicts
                          {:repo (str (get-in row [:data :repository]))
                           :branch (str (get-in row [:data :head_branch]))
                           :base (str (get-in row [:data :base_branch]))})
        paths (when (and (map? answer) (not (bench/refused answer)))
                (:paths answer))]
    (when (sequential? paths)
      (not-empty (into [] (comp (map str) (remove str/blank?)
                                (map #(subs % 0 (min (count %) 400)))
                                (take conflict-path-limit))
                       paths)))))

(defn- failing-move
  "The one door the verdict opens on this row, as [door input], or nil.
  A red head under the round ceiling goes to `failing`; a red head on
  the last round goes to `stuck` with the names as its why; a green
  head brings a failing change back to `submitted`. `conflicts` is the
  list of conflicting paths, written beside the names when there is one."
  [row verdict policy conflicts]
  (let [names (:names verdict)]
    (case [(state-of row) (:verdict verdict)]
      [:submitted :red]
      (if (>= (long (or (get-in row [:data :rounds]) 0))
              (bench/rounds-of policy))
        (let [why (str "The checks went red on the last round the policy "
                       "gives: " (str/join ", " names) ".")]
          [:stick (cond-> {:why (subs why 0 (min (count why) why-chars))
                           :failing_checks names}
                    (seq conflicts) (assoc :conflicts conflicts))])
        [:fail (cond-> {:failing_checks names}
                 (seq conflicts) (assoc :conflicts conflicts))])
      [:failing :green] [:recover {}]
      nil)))

(def ^:private moved-counts
  {:fail :failing :recover :recovered :stick :stuck})

(defn- failing-pass!
  "Every submitted or failing change of a repository with an active
  policy → its head's checks, read against the policy, and at most one
  door. A change with no head, or of a repository with no policy, is
  left where it is. A forge that does not answer, or a door the engine
  refuses, costs that change one pass and nothing else."
  [eng source census log-fn]
  (let [by-repo (into {}
                      (keep (fn [p]
                              (when-some [r (some-> (get-in p [:data :repository])
                                                    str not-empty)]
                                [r p])))
                      (bench/policies eng :active))]
    (reduce
     (fn [census row]
       (let [repo (str (get-in row [:data :repository]))
             head (some-> (get-in row [:data :head_sha]) str not-empty)
             policy (get by-repo repo)]
         (if-not (and head policy)
           census
           (try
             (let [verdict (with-conflict
                            (check-verdict (bench/required-checks-of policy)
                                           (forge-checks source repo head))
                            row)
                   ;; the rig is asked only when a conflict will move
                   ;; the row, never for a row that stays where it is
                   conflicts (when (and (conflicted? row)
                                        (= :submitted (state-of row)))
                               (conflict-paths eng row))]
               (if-some [[door input] (failing-move row verdict policy
                                                    conflicts)]
                 (do (inv/invoke! eng :change (str (:id row)) door input
                                  (as-opts))
                     (update census (moved-counts door) inc))
                 census))
             (catch Exception e
               (log-fn "the checks of " (get-in row [:data :change_id])
                       " did not move the change (" (ex-message e) ")")
               (update census :refused inc))))))
     census
     (live-changes eng))))

;; ── a red base opens one ticket (ticket ade81ae9) ────────────────────
;;
;; A base branch that goes red stops every pull request on it, and
;; nothing else in the engine reads a base. So for each active policy
;; the pass reads the head of the policy's `base` and its checks, and
;; judges them with `check-verdict`, as a pull request's head is judged.
;; The state lands on the policy row (`base_state`, `base_head`) through
;; the hidden `note_base` door, only when it moved.
;;
;; RED TWICE OPENS ONE TICKET. A red read on one pass is remembered; a
;; red read on the NEXT pass too mints one p0 `bug` ticket in that
;; repository and grooms it with the engine's own hand, so the
;; repository's code seat wakes on `groom`. A check that is mid-rerun
;; reads `unknown` between, and that starts the count again. While that
;; ticket has not ended, a later red head is written on it (`red_heads`)
;; and no second ticket is minted. A green base ends it through the
;; hidden `mend` door, whether or not its own change merged.

(def ^:private base-log-tails
  "How many red checks of a base carry their log tail into the ticket."
  3)

(def ^:private ended-ticket #{:done :dropped})

(def ^:private red-head-doors
  "The door that writes a red head on the ticket, read from its state.
  A self-loop is spelled once for each state it serves; a draft, a
  blocked or a deferred ticket is a person's, and is left alone."
  {:open :note_red
   :in_review :note_red_in_review})

(defn- cut [s n]
  (let [s (str s)] (subs s 0 (min (count s) (long n)))))

(defn- red-head-line [sha names]
  (cut (str sha ": " (str/join ", " names)) 400))

(defn- seen-head? [ticket-row sha]
  (boolean (some #(str/starts-with? (str %) (str sha))
                 (get-in ticket-row [:data :red_heads]))))

(defn- red-main-detail
  "The body a code seat reads: what is red, where, the log, and what to
  do about it."
  [source repo base head red-from red-checks]
  (let [tails (for [c (take base-log-tails red-checks)]
                (let [{:keys [excerpt note]}
                      (try (forge-log-tail source (assoc c :repository repo))
                           (catch Exception e {:note (ex-message e)}))]
                  (str "### " (:check_name c) "\n\n"
                       (if (str/blank? (str excerpt))
                         (str "No log tail: " (or note "the forge gave none") ".")
                         (str "```\n" excerpt "\n```")))))]
    (cut (str "## What is red\n\n"
              "The head of `" base "` in " repo " is `" head "`, and these "
              "checks on it finished red:\n\n"
              (str/join "\n" (map #(str "- " (:check_name %)
                                        (when-some [u (:url %)] (str " — " u)))
                                  red-checks))
              "\n\n"
              (when red-from
                (str "The first red head after a green one was `" red-from
                     "`: that commit, or the merge that made it, is the "
                     "likely cause.\n\n"))
              "## The log\n\n" (str/join "\n\n" tails) "\n\n"
              "## What to do\n\n"
              "The engine opened this ticket because `" base "` stayed red "
              "for two passes. The fix goes on a branch like any ticket's, "
              "and merges through its pull request. Do not revert other "
              "people's merges unless the log shows that is the only fix, "
              "and if it is, say so in the submit. The engine completes "
              "this ticket when the head of `" base "` is green again, "
              "whether or not this ticket's change merged.")
         ticket/detail-chars)))

(defn- open-red-ticket!
  "One groomed p0 bug in the policy's repository, with the red head
  written on it. → its id."
  [eng source repo base head red-from red-checks names]
  (let [row (:row (inv/create! eng :ticket
                               {:title (cut (str base " is red: "
                                                 (str/join ", " names))
                                            200)
                                :detail (red-main-detail source repo base head
                                                         red-from red-checks)
                                :type "bug"
                                :priority 0
                                :repo repo}
                               (as-opts)))
        id (str (:id row))]
    (inv/invoke! eng :ticket id :groom {} (as-opts))
    (inv/invoke! eng :ticket id :note_red
                 {:red_head (red-head-line head names)} (as-opts))
    id))

(defn- blank->nil [v] (some-> v str not-empty))

(defn- base-move!
  "One policy's base, read and judged, and at most one ticket move.
  → the census."
  [eng source policy census]
  (let [repo (str (get-in policy [:data :repository]))
        base (bench/base-of policy)
        base-read (forge-base source repo base)
        head (blank->nil (:head_sha base-read))]
    (if (nil? head)
      census
      (let [data (:data policy)
            verdict (check-verdict (bench/required-checks-of policy)
                                   (:checks base-read))
            now (case (:verdict verdict) :red "red" :green "green" "unknown")
            was (str (:base_state data))
            stored-ticket (blank->nil (:base_ticket data))
            known (row-by-id eng :ticket stored-ticket)
            live (when (and known (not (ended-ticket (state-of known))))
                   known)
            red-from (case now
                       "red" (if (= "green" was)
                               head
                               (blank->nil (:base_red_from data)))
                       "green" nil
                       (blank->nil (:base_red_from data)))
            names (:names verdict)
            red-checks (filterv #(and (some #{(str (:check_name %))} names)
                                      (contains? red-conclusions
                                                 (str (:conclusion %))))
                                (:checks base-read))
            [census ticket-id]
            (cond
              (not (and (= "red" now) (= "red" was)))
              (if (and (= "green" now) live)
                (do (inv/invoke! eng :ticket (str (:id live)) :mend
                                 {:close_reason (str base " is green again at "
                                                     head ".")}
                                 (as-opts))
                    [(update census :base-closed inc) stored-ticket])
                [census stored-ticket])

              ;; red twice, on a head the known ticket already carries:
              ;; nothing new, and a ticket a person ended on this very
              ;; head is not minted again
              (and known (seen-head? known head))
              [census stored-ticket]

              live
              (if-some [door (red-head-doors (state-of live))]
                (do (inv/invoke! eng :ticket stored-ticket door
                                 {:red_head (red-head-line head names)}
                                 (as-opts))
                    [(update census :base-noted inc) stored-ticket])
                [census stored-ticket])

              :else
              [(update census :base-opened inc)
               (open-red-ticket! eng source repo base head red-from
                                 red-checks names)])
            input {:verdict now :head head :red_from red-from
                   :ticket ticket-id}
            stored {:verdict (blank->nil (:base_state data))
                    :head (blank->nil (:base_head data))
                    :red_from (blank->nil (:base_red_from data))
                    :ticket stored-ticket}]
        (when (not= input stored)
          (inv/invoke! eng :repo_policy (str (:id policy)) :note_base input
                       (as-opts)))
        census))))

(defn- base-pass!
  "Every active policy → its base read, judged, and written. A base the
  forge will not read costs that repository this pass and nothing
  else, and writes nothing."
  [eng source census log-fn]
  (reduce
   (fn [census policy]
     (try
       (base-move! eng source policy census)
       (catch Exception e
         (log-fn "the base of " (get-in policy [:data :repository])
                 " was not read (" (ex-message e) ")")
         census)))
   census
   (bench/policies eng :active)))

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
   :runs-orphan 0 :labelled 0 :failing 0 :recovered 0 :stuck 0 :noted 0
   :base-opened 0 :base-noted 0 :base-closed 0 :refused 0})

;; A REPOSITORY THE SOURCE CANNOT READ (ticket 116dfb0d). A repository
;; whose pulls listing the token cannot read costs its rows a pass and
;; a log line, every pass, and nothing in the engine says so: its
;; changes are never adopted, have no number, and so the house's merge
;; and the person's merge ask both skip them silently. The pass writes
;; the refusal on the repository's own policy row, where a person reads
;; it, and clears it the first pass that reads the repository again. A
;; note that already says the same status for the same route is left
;; alone, so the log carries one transition when a repository goes dark
;; and one when it comes back.

(defn- source-note-pass!
  [eng refusals answered census log-fn]
  (let [answered (set answered)]
    (reduce
     (fn [census row]
       (let [repo (str (get-in row [:data :repository]))
             stored (str (get-in row [:data :source_note]))
             refusal (get refusals repo)
             input (cond
                     refusal
                     (when-not (str/starts-with?
                                stored
                                (policy/source-note-head (:status refusal)
                                                         (:route refusal)))
                       {:answered (:status refusal) :route (:route refusal)})

                     (and (contains? answered repo) (not (str/blank? stored)))
                     {})]
         (if (nil? input)
           census
           (try
             (inv/invoke! eng :repo_policy (str (:id row)) :note_source
                          input (as-opts))
             (update census :noted inc)
             (catch Exception e
               (log-fn "the policy of " repo " was refused its source note ("
                       (ex-message e) ")")
               (update census :refused inc))))))
     census
     (bench/policies eng :active))))

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
    (let [{:keys [changes checks repositories complete? answered refusals]}
          (forge-poll source)
          census (assoc fresh-census
                        :repositories (count repositories)
                        :complete? (boolean complete?))
          census (source-note-pass! eng refusals answered census log-fn)
          census (change-pass! eng changes census log-fn)
          census (run-pass! eng source checks census log-fn)
          census (stale-pass! eng changes census log-fn)
          census (label-pass! eng source census log-fn)
          census (failing-pass! eng source census log-fn)
          census (base-pass! eng source census log-fn)
          census (assoc census :calls (forge-calls source))]
      (log-fn (:calls census) " calls, " (count changes) " pull requests, "
              (:minted census) " changes minted, " (:adopted census)
              " changes adopted, " (:moved census)
              " changes moved, " (:runs-minted census) " red runs minted, "
              (:labelled census) " labels pushed"
              (when (pos? (long (:runs-superseded census)))
                (str ", " (:runs-superseded census) " red runs superseded "
                     "on a head that moved"))
              (when (pos? (long (+ (long (:failing census))
                                   (long (:recovered census))
                                   (long (:stuck census)))))
                (str ", " (:failing census) " changes failing, "
                     (:recovered census) " green again, "
                     (:stuck census) " stuck on red"))
              (when (pos? (long (:noted census)))
                (str ", " (:noted census) " policy source notes written"))
              (when (pos? (long (+ (long (:base-opened census))
                                   (long (:base-noted census))
                                   (long (:base-closed census)))))
                (str ", " (:base-opened census) " red-base tickets opened, "
                     (:base-noted census) " red heads noted, "
                     (:base-closed census) " closed on green"))
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
