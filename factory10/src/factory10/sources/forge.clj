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
     the paths the bench's trial merge names (ticket 5f12e772). A
     submitted change whose bench landing failed is red before any
     check is read, as `landing:<step>`, with the step's output in
     `landing_error` (ticket 92871afb). A head whose run died without
     a verdict (cancelled, timed out, or failed before any test step)
     is re-run once instead, and a second death on the same head is
     noted on the change (ticket 22f91244).
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
  (:import (java.time Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

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
    Commit statuses count as checks. A check may carry its `ci_run`
    document on its metadata (`:doc`), from which the run pass mints a
    red that finished after the push (ticket 3aca3ae8).
    Throws when the forge does not answer.")
  (forge-base [s repository branch]
    "→ {:head_sha sha :checks [check-doc …]} for the head of one branch,
    or nil when the branch has none (ticket ade81ae9). Each check
    carries `:check_name`, `:status` and `:conclusion`, which
    `check-verdict` reads, and whatever `forge-log-tail` needs to find
    its log. Throws when the forge does not answer."))

(defprotocol ForgeRerun
  "A run that died without a verdict, asked to run again (ticket
  22f91244). A protocol of its own, so a source that does not implement
  it re-runs nothing and the pass goes on as before."
  (forge-runs [s repository head-sha]
    "→ [{:run_id :status :conclusion :jobs [{:name :conclusion :steps
    [{:name :conclusion} …]} …]} …], the latest run of each workflow on
    one head. `:jobs` is read only for a run that finished and did not
    pass. Throws when the forge does not answer.")
  (forge-rerun! [s repository run-id]
    "Re-run the failed jobs of one run. Throws when the forge refuses."))

(defprotocol ForgeDeploy
  "Whether a merged pull request is in a commit of its base, for the
  line that waits on a deploy (ticket 47217098). A protocol of its own:
  a source that does not implement it counts a green deploy as covering
  every merge before it."
  (forge-covers? [s repository number sha]
    "→ true when pull request `number` is merged and its merge commit is
    `sha` or an ancestor of it. Throws when the forge does not answer."))

(defprotocol ForgeCompare
  "Whether a head lacks its base's current head (ticket 498a089e). A
  protocol of its own: a source that does not implement it writes no
  `behind_base`, and the merge line brings no waiting front forward."
  (forge-behind? [s repository base head-sha]
    "→ true when `head-sha` lacks the head of branch `base` (the
    compare of base...head is behind by at least one commit). Throws
    when the forge does not answer."))

(defprotocol ForgePull
  "One pull request read by its number, outside the window (ticket
  949d18c5). A protocol of its own: a source that does not implement
  it adopts only what the window shows, and the pass notes the rest."
  (forge-pull [s repository number]
    "→ the change document of pull request `number`, in the shape
    `forge-poll` answers, or nil. Throws when the forge does not
    answer or has no such pull request."))

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
  one `:to`, so a self-loop is spelled once for each state. A
  `failing` or `stuck` row is adopted too (ticket 3c59c688): a pull
  request the house itself opened is that row's whatever its state,
  and the adoption leaves the state where it is. A merged or closed
  row is adopted by nobody — its story is over."
  {:open :adopt
   :submitted :adopt_submitted
   :failing :adopt_failing
   :stuck :adopt_stuck})

(def fold-states
  "The states a forge-minted duplicate can be folded from (ticket
  3c59c688). A merged or closed row is left as it is."
  #{:open :submitted :failing :stuck})

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

(defn- base-opts
  "The engine's hand inside the base pass: the ticket's base doors open
  only `:within` it (ticket `only-the-base-pass-writes-this`)."
  []
  (assoc (as-opts) :within {:kind :repo_policy :action :note_base}))

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
  adopt door is not one — a merged or closed row on that branch is a
  row whose story is elsewhere. nil is the ordinary
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

(defn- fold-duplicate!
  "A row the forge minted beside the house's own row for the same pull
  request (ticket 3c59c688: 0243f0a6 minted for #268 while 71cbbef9
  stood stuck). The minted row is closed, gives up the id and names
  the house row; the house row adopts the id and moves with the forge."
  [eng minted ours doc]
  (inv/invoke! eng :change (str (:id minted)) :fold
               {:folded_into (str (:id ours))} (as-opts))
  (move-change! eng (adopt-change! eng ours doc) doc))

(defn- forge-minted?
  "A row the forge minted, not one the house built for an ask: it was
  born of no ask, and it stands where a fold can take it."
  [row]
  (and (str/blank? (str (get-in row [:data :born_from])))
       (contains? fold-states (state-of row))))

(defn- change-pass!
  "Every pull request the forge answered → a row minted, a row
  adopted, or a row moved. A row the engine refuses is counted and
  skipped: the next pass offers it again."
  [eng changes census log-fn]
  (reduce
   (fn [census doc]
     (try
       (if-some [existing (row-by eng :change {:change_id (:change_id doc)})]
         (if-some [ours (when (forge-minted? existing)
                          (adoptable-row eng doc))]
           ;; a duplicate minted before the house row could adopt:
           ;; fold it, so one row holds the pull request
           (do (fold-duplicate! eng existing ours doc)
               (update census :folded inc))
           (let [[_ moved?] (move-change! eng existing doc)]
             (cond-> census moved? (update :moved inc))))
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

(defn- run-order
  "Where one run of a check stands among the runs of the same name:
  its forge id first, which grows with every run the forge starts, and
  its start time beside it. A run that carries neither sorts first."
  [check]
  (let [id (str (:id check))]
    [(if (re-matches #"\d{1,18}" id) (Long/parseLong id) -1)
     (str (:started_at check))]))

(defn- newest-runs
  "The runs of one check name that speak for it: the newest alone when
  every run says where it stands (ticket 6bdaf6fe) — a re-run that went
  green on the same head answers for the red run before it — and every
  run as it came when one of them does not, because then there is no
  telling which is newer."
  [runs]
  (if (and (next runs) (every? #(or (some? (:id %)) (some? (:started_at %))) runs))
    [(last (sort-by run-order runs))]
    runs))

(defn check-verdict
  "What the checks on one head say, read against the policy's required
  checks: {:verdict :red :names […]} when every required check
  finished and at least one went red, {:verdict :green} when every one
  finished green, {:verdict :interrupted :names […]} when none went red
  and at least one was cancelled — it died without a verdict, and it is
  never green (ticket 22f91244) — and nil while one is still running,
  has not started, or ended some other way. A policy that names no
  required check requires every check on the head. Of several runs of
  one name, the newest speaks for it (`newest-runs`)."
  [required checks]
  (let [by-name (update-vals (group-by #(str (:check_name %)) checks)
                             newest-runs)
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
          ;; a cancelled required check died without a verdict about
          ;; the code (ticket 22f91244): never green, and named, so the
          ;; pass can re-run it
          :else
          (let [cut (filterv (fn [n] (some #(= "cancelled" (str (:conclusion %)))
                                           (get by-name n)))
                             names)]
            (when (seq cut) {:verdict :interrupted :names cut})))))))

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

(defn- head-reader
  "The checks of each head, read once for the whole pass (ticket
  3aca3ae8): the run pass and the failing pass ask the same heads, and
  the forge answers each one time. A read that throws is not kept, so
  the next asker tries again."
  [source]
  (let [seen (atom {})]
    (fn [repo head]
      (let [k [repo head]]
        (if-some [hit (find @seen k)]
          (val hit)
          (let [checks (forge-checks source repo head)]
            (swap! seen assoc k checks)
            checks))))))

(defn- live-reds
  "The finished red runs on the head of every live change of a
  repository with an active policy, from the same per-head read the
  failing pass makes (ticket 3aca3ae8). A check that goes red after the
  push moves no pull request, so the poll's window never carries it;
  this read does. Each check carries its `ci_run` document on its
  metadata (`:doc`); a source whose checks carry none answers nothing
  here. A run the poll already carried is left to the poll's own."
  [eng read-checks polled log-fn]
  (let [repos (into #{}
                    (keep #(some-> (get-in % [:data :repository]) str not-empty))
                    (bench/policies eng :active))
        known (into #{} (map :run_id) polled)
        red (fn [row head check]
              (when-some [doc (:doc (meta check))]
                (when (and (= "completed" (str (:status check)))
                           (contains? red-conclusions (str (:conclusion check)))
                           (not (contains? known (:run_id doc))))
                  (assoc doc
                         :change_id (get-in row [:data :change_id])
                         :head_sha head))))]
    (into []
          (mapcat
           (fn [row]
             (let [repo (str (get-in row [:data :repository]))
                   head (some-> (get-in row [:data :head_sha]) str not-empty)]
               (when (and head (contains? repos repo))
                 (try
                   (into [] (keep #(red row head %)) (read-checks repo head))
                   (catch Exception e
                     (log-fn "the checks of " (get-in row [:data :change_id])
                             " did not answer (" (ex-message e) ")")
                     nil))))))
          (live-changes eng))))

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

;; ── a run that died without a verdict (ticket 22f91244) ────────────
;;
;; A runner that freezes, or a Docker that runs out of networks, kills a
;; job before any test ran. That run says nothing about the code, and
;; the change used to sit on it until a person re-ran it by hand. So the
;; pass re-runs the failed jobs of an interrupted run ONCE per head, and
;; writes the head on the row (`rerun_head`) so a restart does not forget
;; it. The same head interrupted again is left alone, and `rerun_note`
;; tells a person the runner is broken.

(def interrupted-conclusions
  "The job conclusions that end a job without a verdict."
  #{"cancelled" "timed_out"})

(def ^:private setup-step
  "The steps that run before any test: the runner's own set-up, the
  checkout, the tool set-up, the containers and the services, and the
  clean-up after. A failure in one of these is the runner's, not the
  code's."
  #"(?i)\s*(set ?up|checkout|run actions/(checkout|setup-|cache)|initialize containers|create local container network|start(ing)? |pull |post |complete job|stop containers).*")

(defn- setup-step? [step]
  (boolean (re-matches setup-step (str (:name step)))))

(defn- job-verdict
  "One job of a finished run: :red when a step past set-up failed,
  :interrupted when it was cancelled, timed out, or failed with no step
  past set-up failing, and nil when it passed."
  [job]
  (let [c (str (:conclusion job))]
    (cond
      (contains? interrupted-conclusions c) :interrupted
      (= "failure" c) (if (some #(and (= "failure" (str (:conclusion %)))
                                      (not (setup-step? %)))
                                (:steps job))
                        :red
                        :interrupted)
      :else nil)))

(defn interrupted-run?
  "A finished run with at least one interrupted job and no red one. A
  run with a failing test step is red, not interrupted — and so is a
  fail-fast matrix, whose siblings a red job cancelled."
  [run]
  (let [verdicts (mapv job-verdict (:jobs run))]
    (boolean (and (= "completed" (str (:status run)))
                  (some #{:interrupted} verdicts)
                  (not-any? #{:red} verdicts)))))

(defn- short-head [head]
  (subs head 0 (min (count head) 12)))

(defn- rerun-noted? [row head]
  (str/includes? (str (get-in row [:data :rerun_note])) (short-head head)))

(defn- write-rerun! [eng row input]
  (when-some [door (get observe-doors (state-of row))]
    (inv/invoke! eng :change (str (:id row)) door input (as-opts))))

(defn- rerun-interrupted!
  "The interrupted runs of one head, re-run once, or noted when this
  head was already re-run. → [census re-ran?]. A forge that refuses
  costs the re-run and never the change's own move."
  [eng source row repo head census log-fn]
  (let [done (= head (str (get-in row [:data :rerun_head])))]
    (if (or (not (satisfies? ForgeRerun source))
            (and done (rerun-noted? row head)))
      [census false]
      (try
        (let [runs (filterv interrupted-run? (forge-runs source repo head))]
          (cond
            (empty? runs) [census false]

            done
            (do (write-rerun! eng row
                              {:rerun_note
                               (str "The checks on head " (short-head head)
                                    " died without a verdict again after the "
                                    "house re-ran them once, so they are left "
                                    "alone: look at the runner.")})
                [(update census :rerun-noted inc) false])

            :else
            (do (doseq [run runs]
                  (forge-rerun! source repo (:run_id run)))
                (write-rerun! eng row {:rerun_head head})
                [(update census :rerun inc) true])))
        (catch Exception e
          (log-fn "the interrupted checks of " (get-in row [:data :change_id])
                  " were not re-run (" (ex-message e) ")")
          [(update census :refused inc) false])))))

;; ── a landing that failed (ticket 92871afb) ─────────────────────────
;;
;; The rig lands a submit in steps: it commits, pushes, and opens the
;; pull request. When a step fails, nothing reached GitHub, so no check
;; will ever run on the head and the change would sit at `submitted`
;; for good. The bench's `feedback` names the landing. A failed one is
;; one more red name, `landing:<step>`, and the step's output rides
;; beside it in `landing_error`; the move is the red move, so it counts
;; against the round ceiling and wakes the seat as a red check does. A
;; landing still running leaves the row alone, and the seat's next
;; submit is a landing of its own and brings the row back to
;; `submitted`.

(def ^:private landing-error-chars 4000)

(def ^:private failed-landing-states #{"failed" "failure" "error"})

(def ^:private running-landing-states
  #{"running" "pending" "queued" "in_progress" "started"})

(defn- text-of [v]
  (some-> v str str/trim not-empty))

;; The rig's shape (ckopsa/waymark-bench bench/landing.py), pinned by
;; `the-landing-verdict-reads-the-rigs-shape`: {state, failed_step,
;; steps: [{name, state, seconds, exit_code, output, commit}]}. Only
;; `state`, `failed_step` and each step's `name`, `state` and `output`
;; are read; a key off that shape is not guessed at.

(defn- state-text [m]
  (let [v (:state m)]
    (str/lower-case (if (keyword? v) (name v) (str v)))))

(defn- step-name [step]
  (text-of (:name step)))

(defn- tail-of [s n]
  (if (> (count s) n) (subs s (- (count s) n)) s))

(defn landing-verdict
  "What the bench's landing of the last submit says: {:verdict :red
  :names [\"landing:<step>\"] :error \"…\"} when a step failed, with the
  tail of that step's output; {:verdict :running} while it still runs;
  nil for a landing that finished well, or no landing at all."
  [landing]
  (when (map? landing)
    (let [st (state-text landing)]
      (cond
        (contains? failed-landing-states st)
        (let [steps (filter map? (when (sequential? (:steps landing))
                                   (:steps landing)))
              named (text-of (:failed_step landing))
              failed (or (when named
                           (some #(when (= named (step-name %)) %) steps))
                         (some #(when (contains? failed-landing-states
                                                 (state-text %))
                                  %)
                               steps))
              step (or named (step-name failed) "unknown")
              name' (str "landing:" step)
              out (text-of (:output failed))]
          (cond-> {:verdict :red
                   :names [(subs name' 0 (min (count name') 200))]}
            out (assoc :error (tail-of out landing-error-chars))))

        (contains? running-landing-states st)
        {:verdict :running}

        :else nil))))

(defn landing-of
  "The landing the bench's feedback names for this change's branch,
  asked with the engine's own hand, or nil. A rig that does not answer,
  or refuses, says nothing about the landing, and the checks decide."
  [eng row policy]
  (let [answer (bench/ask {:services (:services eng)} :feedback
                          {:repo (str (get-in row [:data :repository]))
                           :branch (bench/branch-of row policy)})]
    (when (and (map? answer) (not (bench/refused answer)))
      (:landing answer))))

(defn- failing-move
  "The one door the verdict opens on this row, as [door input], or nil.
  A red head under the round ceiling goes to `failing`; a red head on
  the last round goes to `stuck` with the names as its why; a green
  head brings a failing change back to `submitted`, unless a merge train
  found that same head red (`train_red_head`, ticket 6566d32f): its own
  green does not clear the train's red, and a new head does. `conflicts` is the
  list of conflicting paths, written beside the names when there is one;
  a failed landing's output rides as the verdict's `:error`."
  [row verdict policy conflicts]
  (let [names (:names verdict)
        error (:error verdict)]
    (case [(state-of row) (:verdict verdict)]
      [:submitted :red]
      (if (>= (long (or (get-in row [:data :rounds]) 0))
              (bench/rounds-of policy))
        (let [why (str "The checks went red on the last round the policy "
                       "gives: " (str/join ", " names) ".")]
          [:stick (cond-> {:why (subs why 0 (min (count why) why-chars))
                           :failing_checks names}
                    (seq conflicts) (assoc :conflicts conflicts)
                    error (assoc :landing_error error))])
        [:fail (cond-> {:failing_checks names}
                 (seq conflicts) (assoc :conflicts conflicts)
                 error (assoc :landing_error error))])
      [:failing :green]
      (let [train-head (some-> (get-in row [:data :train_red_head]) str not-empty)]
        (when-not (and train-head
                       (= train-head (str (get-in row [:data :head_sha]))))
          [:recover {}]))
      nil)))

(def ^:private moved-counts
  {:fail :failing :recover :recovered :stick :stuck})

(defn- failing-pass!
  "Every submitted or failing change of a repository with an active
  policy → its head's checks, read against the policy, and at most one
  door. A change with no head, or of a repository with no policy, is
  left where it is. A forge that does not answer, or a door the engine
  refuses, costs that change one pass and nothing else. `read-checks`
  is the pass's one read of each head (`head-reader`)."
  [eng source read-checks census log-fn]
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
             (let [;; a change's landing first (ticket 92871afb): a
                   ;; push that failed has no checks to wait on, and
                   ;; one still landing has none yet. A failing change
                   ;; is read too, so the old head's green checks do
                   ;; not recover a landing that is still red.
                   landing (when (contains? #{:submitted :failing}
                                            (state-of row))
                             (landing-verdict (landing-of eng row policy)))
                   checked (when-not landing
                             (check-verdict (bench/required-checks-of policy)
                                            (read-checks repo head)))
                   ;; every required check finished on a house change:
                   ;; wake the merge pass (ticket 6e190062)
                   _ (when (and checked (bench/house-pass-merges? policy))
                       (bench/nudge-house! repo [:checks (str (:id row)) head
                                                 (:verdict checked)]))
                   ;; a conflicted row is red while its landing still
                   ;; runs too: GitHub reads the pull request's own
                   ;; head, and each submit resets `mergeable`, so a
                   ;; landing that never says it finished does not hold
                   ;; a conflict at `submitted` (ticket 7af7d506)
                   verdict (case (:verdict landing)
                             :red landing
                             :running (when (conflicted? row)
                                        (with-conflict nil row))
                             (with-conflict checked row))
                   ;; the mirror's own read of a submitted head, stamped
                   ;; for the merge line: a rig's red on a head read green
                   ;; here is the failing round's (ticket baf76388)
                   _ (when (= :submitted (state-of row))
                       (case (:verdict verdict)
                         :green (bench/mark-row! eng :change (str (:id row))
                                                 {:green_head head} #{})
                         :red (bench/mark-row! eng :change (str (:id row))
                                               {:green_head nil} #{})
                         nil))
                   ;; a head whose run died without a verdict is re-run
                   ;; once, and the re-run is its move for this pass
                   ;; (ticket 22f91244)
                   [census re-ran?] (if (and checked
                                             (not (conflicted? row))
                                             (contains? #{:red :interrupted}
                                                        (:verdict checked)))
                                      (rerun-interrupted! eng source row repo
                                                          head census log-fn)
                                      [census false])
                   ;; the rig is asked only when a conflict will move
                   ;; the row, never for a row that stays where it is
                   conflicts (when (and (not= :red (:verdict landing))
                                        (conflicted? row)
                                        (= :submitted (state-of row)))
                               (conflict-paths eng row))]
               (if-some [[door input] (when (and verdict (not re-ran?))
                                        (failing-move row verdict policy
                                                      conflicts))]
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

;; ── a head that lacks a required check (ticket 498a089e) ────────────
;;
;; A branch cut before its base gained a workflow never runs that
;; workflow's check, and GitHub may call it `clean` all the same, so
;; the rig's merge answers `waiting` forever. This pass writes the two
;; facts the house needs to end that wait — the required checks with no
;; run at all on the head, and whether the head lacks its base's
;; current head — and the merge line reads them (bench/work-lines!).

(defn missing-checks
  "The required checks with NO run at all among `checks`: a check that
  is pending or running is not missing (ticket 498a089e)."
  [required checks]
  (let [ran (into #{} (map #(str (:check_name %))) checks)]
    (into [] (comp (map str) (distinct) (remove ran)) required)))

(defn- staleness-pass!
  "Every submitted or failing change of a repository with an active
  policy → `missing_checks` read from its head and, when one is missing
  and the source can compare, `behind_base` with `base_compared_at`,
  both stamped with the head they were read at (`missing_checks_head`,
  ticket 716d12ba). Written only when a fact changed. A forge that does not answer, or a
  door the engine refuses, costs that change one pass."
  [eng source read-checks census log-fn]
  (let [by-repo (into {}
                      (keep (fn [p]
                              (when-some [r (some-> (get-in p [:data :repository])
                                                    str not-empty)]
                                [r p])))
                      (bench/policies eng :active))
        compare? (satisfies? ForgeCompare source)]
    (reduce
     (fn [census row]
       (let [repo (str (get-in row [:data :repository]))
             head (some-> (get-in row [:data :head_sha]) str not-empty)
             base (some-> (get-in row [:data :base_branch]) str not-empty)
             policy (get by-repo repo)
             door (get observe-doors (state-of row))]
         (if-not (and head policy door)
           census
           (try
             (let [missing (missing-checks (bench/required-checks-of policy)
                                           (read-checks repo head))
                   behind (when (and (seq missing) base compare?)
                            (boolean (forge-behind? source repo base head)))
                   facts (cond-> {:missing_checks missing
                                  :missing_checks_head head}
                           (some? behind) (assoc :behind_base behind))]
               (if (= facts (select-keys (:data row) (keys facts)))
                 census
                 (do (inv/invoke! eng :change (str (:id row)) door
                                  (cond-> facts
                                    (some? behind)
                                    (assoc :base_compared_at
                                           (str (java.time.Instant/now))))
                                  (as-opts))
                     (update census :stale-noted inc))))
             (catch Exception e
               (log-fn "the missing checks of " (get-in row [:data :change_id])
                       " were not written (" (ex-message e) ")")
               (update census :refused inc))))))
     census
     (live-changes eng))))

;; ── a landed pull request nobody adopted (ticket 58e706d6) ──────────
;;
;; A submitted change whose bench landing opened a pull request, but
;; which the forge never adopts, keeps its ask's id and no number, and
;; the house's merge and the person's merge ask both skip it silently.
;; The pass asks the bench's feedback for the branch's pull request,
;; stamps the first time it saw one, and once the window has passed
;; writes a note on the row. The adoption clears it, and a note that
;; already says the same thing is left alone.

(def adoption-note-window-ms
  "How long a landed pull request waits for its adoption before the
  row says so: a few passes, so an ordinary adoption never notes."
  (* 15 60 1000))

(def ^:private adoption-note-chars 500)

(defn adoption-note
  "The words a change carries when its landed pull request `pr` (the
  bench feedback's `pull_request`) has no row: the number, the
  repository and the head, and whether the forge already ended it."
  [repo branch pr]
  (let [st (some-> (:state pr) name str/lower-case)
        ended (cond (or (true? (:merged pr)) (= "merged" st)) "merged"
                    (= "closed" st) "closed")
        note (str "landed as #" (:number pr) " on " repo
                  " but no pull request row adopted it; head " branch
                  (when ended
                    (str "; the pull request is already " ended
                         " on the forge")))]
    (subs note 0 (min (count note) adoption-note-chars))))

(defn- landed-pull-request
  "The pull request the bench's feedback names for this change's
  branch, or nil. A rig that does not answer, or refuses, names none."
  [eng row policy]
  (let [answer (bench/ask {:services (:services eng)} :feedback
                          {:repo (str (get-in row [:data :repository]))
                           :branch (bench/branch-of row policy)})
        pr (when (and (map? answer) (not (bench/refused answer)))
             (:pull_request answer))]
    (when (and (map? pr) (integer? (:number pr)))
      pr)))

(defn- forge-doc-of
  "The forge's own document for the pull request the bench names for
  this row, or nil (ticket 949d18c5). The listing's window can pass a
  pull request before any pass adopts it, and then no later pass sees
  it until it moves; asking for it by number closes that gap. Only a
  pull request on the row's own head branch counts, and a forge that
  will not answer costs the row this pass's adoption and nothing else."
  [source row repo pr log-fn]
  (when (satisfies? ForgePull source)
    (let [doc (try (forge-pull source repo (:number pr))
                   (catch Exception e
                     (log-fn "the pull request #" (:number pr) " of " repo
                             " could not be read (" (ex-message e) ")")
                     nil))]
      (when (and (map? doc)
                 (= (str (get-in row [:data :head_branch]))
                    (str (:head_branch doc))))
        doc))))

(defn- adoption-move
  "The `note_adoption` input this pass writes on the row, or nil: the
  first sight stamped, or the note once the window has passed and the
  stored note says something else."
  [row repo branch pr ^Instant now]
  (let [stamp (text-of (get-in row [:data :unadopted_since]))
        seen (when stamp
               (try (Instant/parse stamp) (catch Exception _ nil)))
        note (adoption-note repo branch pr)]
    (cond
      (nil? seen) {:unadopted_since (str now)}

      (and (> (- (.toEpochMilli now) (.toEpochMilli ^Instant seen))
              (long adoption-note-window-ms))
           (not= note (str (get-in row [:data :adoption_note]))))
      {:unadopted_since stamp :adoption_note note})))

;; ── a submitted change that never opened a pull request (ticket 226d2b85)
;;
;; A submitted change with no number whose bench names no pull request
;; either has an ended ticket, and is closed so the submitted list is
;; the real queue, or has a live one, and goes stuck once the window
;; has passed so a person sees it. Close only: the branch is untouched.

(defn- ticket-of
  "The id of the ticket a seat-born change was built for, or nil."
  [row]
  (some (fn [v]
          (let [s (str v)]
            (when (str/starts-with? s "ticket:")
              (not-empty (subs s (count "ticket:"))))))
        [(get-in row [:data :born_from]) (get-in row [:data :change_id])]))

(defn- merged-beside?
  "Whether another change built for the same ticket, on the same
  branch, has merged."
  [eng row repo branch ticket-id]
  (let [born (str "ticket:" ticket-id)]
    (boolean
     (some #(and (not= (str (:id %)) (str (:id row)))
                 (= :merged (state-of %))
                 (= born (str (get-in % [:data :born_from]))))
           (rows-by eng :change {:repository repo :head_branch branch} 100)))))

(defn unopened-note
  "The words a submitted change carries when no pull request ever came
  of it and its ticket is still open."
  [repo branch]
  (str "submitted on " repo " but never opened a pull request and no"
       " pull request row adopted it; head " branch))

(defn- unopened-move
  "The door and input for a submitted change with no number whose bench
  names no pull request, or nil: closed when its ticket ended or another
  change on it merged, else the first sight stamped, else stuck once
  the window has passed. A change whose ticket is not known is left."
  [eng row repo branch ^Instant now]
  (when-some [tid (ticket-of row)]
    (when-some [t (try (row-by-id eng :ticket tid) (catch Exception _ nil))]
      (let [stamp (text-of (get-in row [:data :unadopted_since]))
            seen (when stamp
                   (try (Instant/parse stamp) (catch Exception _ nil)))
            note (unopened-note repo branch)]
        (cond
          (or (#{:done :dropped} (state-of t))
              (merged-beside? eng row repo branch tid))
          [:supersede {:superseded_by
                       (str "closed: ticket " tid " ended; this change "
                            "never opened a pull request")}]

          (nil? seen) [:note_adoption {:unadopted_since (str now)}]

          (> (- (.toEpochMilli now) (.toEpochMilli ^Instant seen))
             (long adoption-note-window-ms))
          [:stick {:why (subs note 0 (min (count note) why-chars))
                   :failing_checks ["no pull request"]}])))))

;; ── the old name's last rows (ticket 2d216859) ──────────────────────
;;
;; Ticket 8f2fac64 renamed `landed_at` to `unadopted_since`, and the
;; rows the forge pass no longer looks at kept the old name. The boot
;; clears them once, beneath the doors because the field is no longer
;; law, and a boot after that finds nothing to write.

(def ^:private legacy-limit
  "The most change rows one boot reads for the old name."
  100000)

(defn- without-landed-at
  "The row with `landed_at` gone. A submitted change with no number
  keeps its stamp as `unadopted_since` when it has none of its own."
  [row]
  (let [legacy (text-of (get-in row [:data :landed_at]))]
    (cond-> (update row :data dissoc :landed_at)
      (and legacy
           (= :submitted (state-of row))
           (nil? (get-in row [:data :number]))
           (nil? (text-of (get-in row [:data :unadopted_since]))))
      (assoc-in [:data :unadopted_since] legacy))))

(defn clear-landed-at!
  "Every change row still carrying `landed_at` rewritten without it
  (ticket 2d216859); the count of rows written. Idempotent: a row
  without the field is not touched, and a row another process moved
  first is left to the next boot."
  [eng]
  (let [st (:storage eng)
        rows (store/with-tx st
               (fn [tx] (store/query-rows st tx :change {}
                                          {:limit legacy-limit})))]
    (reduce
     (fn [n row]
       (if-not (contains? (:data row) :landed_at)
         n
         (try
           (if (store/with-tx st
                 (fn [tx]
                   (let [now (store/load-row st tx :change (str (:id row)) {})]
                     (when (contains? (:data now) :landed_at)
                       (store/save-row! st tx :change
                                        (assoc (without-landed-at now)
                                               :version (inc (long (:version now))))
                                        (:version now))
                       true))))
             (inc n)
             n)
           (catch Exception e
             (warn! "the old landed_at of change " (:id row)
                    " was not cleared (" (ex-message e) ")")
             n))))
     0 rows)))

;; ── a merge before the children (ticket 499bcd72) ───────────────────
;;
;; A parent whose pull request merged while a child was open stayed in
;; review with no door left (c8bc5a6a). The last child's ending now
;; ends it; the boot ends the ones already stranded, once, through the
;; merged change's own `land`, and a boot after that finds nothing.

(defn finish-merged-parents!
  "Every ticket in review with children, all ended, and a merged change
  born from it, ended through `land`; the count ended. A ticket with an
  unfinished child or no merged change is left."
  [eng]
  (let [merged (into {}
                     (keep (fn [c]
                             (when-some [born (text-of (get-in c [:data :born_from]))]
                               [born c])))
                     (rows-by eng :change {:state :merged} legacy-limit))
        waiting? #(contains? #{:open :in_review :blocked :deferred} (state-of %))]
    (reduce
     (fn [n t]
       (let [id (str (:id t))
             c (get merged (str "ticket:" id))
             kids (when c (rows-by eng :ticket {:parent id} legacy-limit))]
         (if (or (empty? kids) (some waiting? kids))
           n
           (try
             (inv/invoke! eng :ticket id :land
                          {:close_reason (str "Merged: "
                                              (or (text-of (get-in c [:data :url]))
                                                  (get-in c [:data :change_id]))
                                              "; children done.")}
                          (assoc (as-opts) :within {:kind :change :action :merge}))
             (inc n)
             (catch Exception e
               (warn! "the merged parent " id " was not ended ("
                      (ex-message e) ")")
               n)))))
     0 (rows-by eng :ticket {:state :in_review} legacy-limit))))

(defn- adoption-note-pass!
  "Every submitted change of a repository with an active policy that
  has no number → the pull request its landing opened, adopted when
  the forge answers it by number, else at most one `note_adoption`;
  when its landing opened none, the `unopened-move`. The first-sight
  stamp never stands in the adoption's way: the adopt door clears it.
  A rig that does not answer, or a door the engine refuses, costs that
  change one pass and nothing else."
  [eng source census log-fn]
  (let [by-repo (into {}
                      (keep (fn [p]
                              (when-some [r (some-> (get-in p [:data :repository])
                                                    str not-empty)]
                                [r p])))
                      (bench/policies eng :active))
        now (if-some [f (:now-fn eng)] (f) (Instant/now))]
    (reduce
     (fn [census row]
       (let [repo (str (get-in row [:data :repository]))
             policy (get by-repo repo)]
         (if-not (and policy
                      (= :submitted (state-of row))
                      (nil? (get-in row [:data :number])))
           census
           (try
             (let [pr (landed-pull-request eng row policy)
                   branch (bench/branch-of row policy)]
               (if-some [doc (when pr (forge-doc-of source row repo pr log-fn))]
                 (change-pass! eng [doc] census log-fn)
                 (let [[door input] (if pr
                                      (some->> (adoption-move row repo branch
                                                              pr now)
                                               (vector :note_adoption))
                                      (unopened-move eng row repo branch now))]
                   (if (nil? input)
                     census
                     (do (inv/invoke! eng :change (str (:id row)) door input
                                      (as-opts))
                         (cond-> census
                           (:adoption_note input) (update :adoption-noted inc)
                           (= :stick door) (update :stuck inc)
                           (= :supersede door) (update :unopened-closed inc)))))))
             (catch Exception e
               (log-fn "the change " (get-in row [:data :change_id])
                       " was refused its adoption note (" (ex-message e) ")")
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
  do about it. `failed` are the checks on the head that are not
  required and went red too — the suites a required `gate` only names
  (`quick=failure`) — and their logs come first, because the cause is
  in them (ticket 9a14577e)."
  [source repo base head red-from red-checks failed]
  (let [tails (for [c (concat (take base-log-tails failed)
                              (take base-log-tails red-checks))]
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
              (when (seq failed)
                (str "Beneath them, these checks on the same head failed "
                     "too, and their logs come first below:\n\n"
                     (str/join "\n" (map #(str "- " (:check_name %)
                                               (when-some [u (:url %)]
                                                 (str " — " u)))
                                         failed))
                     "\n\n"))
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
  [eng source repo base head red-from red-checks failed names]
  (let [row (:row (inv/create! eng :ticket
                               {:title (cut (str base " is red: "
                                                 (str/join ", " names))
                                            200)
                                :detail (red-main-detail source repo base head
                                                         red-from red-checks
                                                         failed)
                                :type "bug"
                                :priority 0
                                :repo repo}
                               (as-opts)))
        id (str (:id row))]
    (inv/invoke! eng :ticket id :groom {} (as-opts))
    (inv/invoke! eng :ticket id :note_red
                 {:red_head (red-head-line head names)} (base-opts))
    id))

(defn- blank->nil [v] (some-> v str not-empty))

(defn- now-of [eng] (if-some [f (:now-fn eng)] (f) (java.time.Instant/now)))

(defn- note-base!
  "The base's own facts on the policy, written through `note_base` only
  when they moved. A read that moved nothing still stamps
  `base_checked_at`, with a maintenance write as bench's `mark-row!`
  makes, so a quiet base says when it was last read and the log is not
  flooded (ticket c4bac627)."
  [eng policy input stored]
  (if (not= input stored)
    (inv/invoke! eng :repo_policy (str (:id policy)) :note_base input
                 (as-opts))
    (bench/mark-row! eng :repo_policy (str (:id policy))
                     {:base_checked_at (now-of eng)} #{})))

;; A BASE THE PASS COULD NOT READ (ticket c4bac627). A base read that
;; throws, or answers no head, is written on the policy's `source_note`
;; with a maintenance write, and the first good read clears it. A note
;; that already says the same reason is left alone, and a note of the
;; source's own refusal is the larger fact and `source-note-pass!`'s to
;; clear, so the base pass never writes over it. A policy that names no
;; required checks has no base verdict to lose, and is not noted.

(def ^:private base-note-prefix "The base ")

(defn- base-note? [note] (str/starts-with? (str note) base-note-prefix))

(defn- base-note-head [base why]
  (cut (str base-note-prefix "`" base "` was not read (" why ")") 300))

(defn- note-base-read!
  "The policy's `source_note` after one base read: `why` nil clears a
  base note, and a reason writes one."
  [eng policy base why]
  (let [id (str (:id policy))
        stored (str (get-in (row-by-id eng :repo_policy id)
                            [:data :source_note]))]
    (cond
      (nil? why)
      (when (base-note? stored)
        (bench/mark-row! eng :repo_policy id {:source_note nil} #{}))

      (empty? (bench/required-checks-of policy))
      nil

      (or (str/blank? stored)
          (and (base-note? stored)
               (not (str/starts-with? stored (base-note-head base why)))))
      (bench/mark-row! eng :repo_policy id
                       {:source_note
                        (cut (str (base-note-head base why) " at "
                                  (now-of eng)
                                  ": the house cannot judge this "
                                  "repository's base, so a red base opens "
                                  "no ticket.")
                             480)}
                       #{}))))

(defn- base-move!
  "One policy's base, read and judged, written, and then at most one
  ticket move. The base is recorded from the read BEFORE any ticket
  door is opened, so a door that refuses costs the ticket move and not
  the base write (ticket 806e9185).
  → the census."
  [eng source policy census log-fn]
  (let [repo (str (get-in policy [:data :repository]))
        base (bench/base-of policy)
        [base-read why] (try [(forge-base source repo base) nil]
                             (catch Exception e
                               [nil (or (ex-message e) (str (class e)))]))
        head (blank->nil (:head_sha base-read))]
    (note-base-read! eng policy base
                     (or why (when (nil? head) "the forge answered no head")))
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
            ;; the red checks the policy does not require: the suites
            ;; behind an aggregating `gate`, newest run of each name
            failed (->> (:checks base-read)
                        (group-by #(str (:check_name %)))
                        vals
                        (mapcat newest-runs)
                        (filterv #(and (not (some #{(str (:check_name %))}
                                                  names))
                                       (contains? red-conclusions
                                                  (str (:conclusion %))))))
            stored {:verdict (blank->nil (:base_state data))
                    :head (blank->nil (:base_head data))
                    :red_from (blank->nil (:base_red_from data))
                    :ticket stored-ticket}
            base-facts {:verdict now :head head :red_from red-from
                        :ticket stored-ticket}
            ;; the read is written first: what the base is, and on
            ;; which head, is known here and must not ride on a ticket
            ;; door answering
            _ (note-base! eng policy base-facts stored)
            ;; the base moved: a house line's front may be behind now
            ;; (ticket 6e190062)
            _ (when (and (:head stored) (not= head (:head stored))
                         (bench/house-pass-merges? policy))
                (bench/nudge-house! repo [:base repo head]))
            [census ticket-id]
            (try
              (cond
                (not (and (= "red" now) (= "red" was)))
                (if (and (= "green" now) live)
                  (do (inv/invoke! eng :ticket (str (:id live)) :mend
                                   {:close_reason (str base " is green again at "
                                                       head ".")}
                                   (base-opts))
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
                                   (base-opts))
                      [(update census :base-noted inc) stored-ticket])
                  [census stored-ticket])

                :else
                [(update census :base-opened inc)
                 (open-red-ticket! eng source repo base head red-from
                                   red-checks failed names)])
              (catch Exception e
                (log-fn "the base of " repo " was read and written, but its "
                        "ticket did not move (" (ex-message e) ")")
                [census stored-ticket]))]
        ;; the ticket the move minted, once it is minted and not before
        (when (not= ticket-id stored-ticket)
          (note-base! eng policy (assoc base-facts :ticket ticket-id)
                      base-facts))
        ;; the deploy the merge line waits on, from the same read
        ;; (ticket 47217098)
        (bench/note-deploy! eng policy base-read
                            (fn [number sha]
                              (or (not (satisfies? ForgeDeploy source))
                                  (forge-covers? source repo number sha))))
        census))))

(defn- base-pass!
  "Every active policy → its base read, judged, and written. A base the
  forge will not read costs that repository this pass and nothing
  else, and is noted on its policy."
  [eng source census log-fn]
  (reduce
   (fn [census policy]
     (try
       (base-move! eng source policy census log-fn)
       (catch Exception e
         (log-fn "the base of " (get-in policy [:data :repository])
                 " was not read (" (ex-message e) ")")
         census)))
   census
   (bench/policies eng :active)))

;; ── the groom floor (ticket eb515931) ───────────────────────────────
;;
;; A LINE THAT RUNS DRY SAYS SO. A repository whose open queue falls
;; below its policy's `groom_floor` gets one draft ticket, where mayor
;; already reads: the draft tickets. The engine grooms nothing itself;
;; grooming, completing or dropping that ticket is the answer. At most
;; one in a settle window, and none while the last is draft or open.

(def ^:private floor-scan-limit
  "How many tickets of one state in one repository the floor reads."
  1000)

(def ^:private floor-title-prefix "Groom the next batch for ")

(def ^:private groomable-types #{"bug" "task" "chore"})

(defn- floor-ticket? [repo row]
  (str/starts-with? (str (get-in row [:data :title]))
                    (str floor-title-prefix repo ":")))

(defn- as-instant [v]
  (cond (instance? Instant v) v
        (str/blank? (str v)) nil
        :else (Instant/parse (str v))))

(defn- floor-detail [repo n floor waiting]
  (cut (str "The open queue for `" repo "` holds " n " tickets, under "
            "the floor of " floor " its policy states. " waiting
            " draft bugs, tasks and chores wait for grooming.\n\n"
            "Groom the next batch, or complete or drop this ticket to "
            "answer it. The engine grooms nothing itself, and files no "
            "other floor ticket for `" repo "` while this one is draft "
            "or open.")
       ticket/detail-chars))

(defn- floor-move!
  "One policy's floor. `open` is the queue: a blocked, a deferred and an
  in-review ticket (one beside a submitted change) each has its own
  state. → true when it filed a ticket."
  [eng policy]
  (let [floor (long (or (get-in policy [:data :groom_floor]) 0))
        settle (long (or (get-in policy [:data :groom_floor_settle_seconds])
                         3600))
        repo (blank->nil (get-in policy [:data :repository]))
        ^Instant now (now-of eng)
        ^Instant noted (as-instant (get-in policy [:data :floor_noted_at]))
        in-state #(rows-by eng :ticket {:repo repo :state %} floor-scan-limit)]
    (when (and repo (pos? floor)
               (or (nil? noted)
                   (not (.isBefore now (.plusSeconds noted settle)))))
      (let [opened (in-state :open)
            n (count opened)]
        (when (< n floor)
          (let [drafts (in-state :draft)]
            (when-not (some #(floor-ticket? repo %) (concat drafts opened))
              (inv/create! eng :ticket
                           {:title (cut (str floor-title-prefix repo ": " n
                                             " open, floor " floor)
                                        200)
                            :detail (floor-detail
                                     repo n floor
                                     (count (filter #(contains? groomable-types
                                                                (str (get-in % [:data :type])))
                                                    drafts)))
                            :type "chore"
                            :priority 1
                            :repo repo}
                           (as-opts))
              (bench/mark-row! eng :repo_policy (str (:id policy))
                               {:floor_noted_at now :floor_count n} #{})
              true)))))))

(defn floor-pass!
  "Every active policy → its groom floor judged, and one draft ticket
  filed where the open queue fell below it. A policy the pass cannot
  judge costs that repository this pass and nothing else. → the
  census, with `:floor-filed` counted."
  [eng census log-fn]
  (reduce
   (fn [census policy]
     (try
       (cond-> census
         (floor-move! eng policy) (update :floor-filed (fnil inc 0)))
       (catch Exception e
         (log-fn "the groom floor of " (get-in policy [:data :repository])
                 " was not judged (" (ex-message e) ")")
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
  {:calls 0 :repositories 0 :complete? true :minted 0 :adopted 0 :folded 0 :moved 0
   :runs-minted 0 :runs-known 0 :runs-skipped 0 :runs-superseded 0
   :runs-orphan 0 :labelled 0 :failing 0 :recovered 0 :stuck 0 :noted 0 :adoption-noted 0
   :unopened-closed 0
   :rerun 0 :rerun-noted 0 :base-opened 0 :base-noted 0 :base-closed 0
   :stale-noted 0 :floor-filed 0 :refused 0})

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

                     ;; a base note is the base pass's to clear
                     (and (contains? answered repo) (not (str/blank? stored))
                          (not (base-note? stored)))
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
          ;; the base pass runs whatever a pass before it did (ticket
          ;; c4bac627): a throw above it is thrown again only after
          ;; every base was read
          [census thrown]
          (try
            (let [census (source-note-pass! eng refusals answered census
                                            log-fn)
                  census (change-pass! eng changes census log-fn)
                  read-checks (head-reader source)
                  census (run-pass! eng source
                                    (into (vec checks)
                                          (live-reds eng read-checks checks
                                                     log-fn))
                                    census log-fn)
                  census (stale-pass! eng changes census log-fn)
                  census (label-pass! eng source census log-fn)
                  census (failing-pass! eng source read-checks census log-fn)
                  census (staleness-pass! eng source read-checks census
                                          log-fn)
                  census (adoption-note-pass! eng source census log-fn)]
              [census nil])
            (catch Exception e [census e]))
          census (base-pass! eng source census log-fn)
          census (floor-pass! eng census log-fn)
          _ (when thrown (throw thrown))
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
              (when (pos? (long (:adoption-noted census)))
                (str ", " (:adoption-noted census)
                     " landed pull requests noted unadopted"))
              (when (pos? (long (+ (long (:rerun census))
                                   (long (:rerun-noted census)))))
                (str ", " (:rerun census) " interrupted heads re-run, "
                     (:rerun-noted census) " interrupted again and left"))
              (when (pos? (long (+ (long (:base-opened census))
                                   (long (:base-noted census))
                                   (long (:base-closed census)))))
                (str ", " (:base-opened census) " red-base tickets opened, "
                     (:base-noted census) " red heads noted, "
                     (:base-closed census) " closed on green"))
              (when (pos? (long (:floor-filed census)))
                (str ", " (:floor-filed census) " groom-floor tickets filed"))
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
