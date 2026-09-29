(ns factory10.resources.change
  "The change: one pull request, as a row (bead waymark-fp62.6.2,
  R-3).

  A MIRROR, NOT A FORM. GitHub owns this row. The source of bead
  waymark-fp62.6.4 reads the pull request and mints the row; it moves
  the row when GitHub moves the pull request. A person does not write
  a change, and neither does a model. The birth door and every door
  on the machine carry `the-mirror-writes-this-row`, which is HIDDEN:
  a person and a model read the row and see no doors on it at all.
  That is task_list's pull-only posture (workqueue10), written out by
  hand because this kind keeps a machine of its own.

  THE MACHINE IS GITHUB'S. A pull request is open, merged or closed,
  so the states are `open`, `merged` and `closed`. The states are the
  machine and NOT a data field: one fact, one writer. `merged` is the
  end of the story, so it is terminal. `closed` is not: GitHub
  reopens a closed pull request, so `reopen` is a real door and the
  row comes back to `open`.

  THE BENCH ARRIVED (bead waymark-fp62.6.3.2). Two more states —
  `submitted`, where a seat has pushed at least one round, and
  `stuck`, where the house stopped — and the doors that reach the
  worktree: `submit`, `discard`, `stall` and `unstick`. `merged` is
  still the only tomb.

  A RED CHANGE IS A STATE (ticket d1742908). `failing` is a submitted
  change whose required checks all finished on its head and at least
  one went red; `failing_checks` names them. The forge pass moves it
  there and back — `fail`, `recover`, and `stick` at the round
  ceiling — and nobody else does: all three are the mirror's, hidden
  like the rest. A submit from `failing` is the seat's next round.

  `stuck` IS NOT AN ENDING, and `:over` below does not name it. An
  ending is a row whose work is over, and the engine shuts the
  household's doors on one. A stuck change is the opposite: it is work
  that is WAITING FOR A PERSON, and it must card in the feed and keep
  the door that puts it back to work (`unstick`). Naming it under
  `:let-go` would make the house read a change nobody has looked at
  yet as a change somebody decided to drop.

  WHAT THE BENCH DOORS ARE. `submit` commits the worktree with the
  seat's own sentence and pushes the branch; the rig does the git and
  holds the credential. `discard` throws the worktree's edits away.
  `stall` is the seat saying it cannot finish, which is also where the
  round ceiling sends it. `unstick` is the person's answer to that —
  a person's, or a delegate's acting for one, and never a model's
  alone. Grooming the ticket a change was born from again answers a
  stall too: the groom walks the change's `rework` door in the same
  transaction (ticket 9ace68fb). The next sit's `regroomed-change`
  (waymark10.server.mcp) is the backstop for what `rework` leaves: a
  change with a pull request, and rows stuck before `rework` existed.
  Each one reaches the rig with the ENGINE's hand, past the leash
  (factory10.bench) — the model holds the four reading and editing
  powers and never the four the engine calls.

  SUBMIT ENDS THE ROUND, AND THE HARNESS CLOSES THE SITTING (bead
  waymark-fp62.6.3.4). The submit ends the round on the change. It
  adds one to `rounds`, and the machine moves the row to `submitted`.
  The submit must not close the sitting. The harness must close the
  sitting with its Stop hook (spec-seat.md R-12.17), and that report
  carries the bill of the round. A fired run raises one Stop event,
  and that event comes after the submit. So the sitting must stay
  open at the submit. A sitting closed at the submit refuses the late
  report, and the tokens and the cost of the round then land nowhere.
  An interactive sitting tallies at each turn and keeps its own
  numbers. The idle sweep closes a sitting whose harness never
  reports.

  A SELF-LOOP IS SPELLED ONCE FOR EACH STATE. A v10 action declares
  one `:to`, so the mirror's refresh and the seat's discard each need
  a second door for the `submitted` state (`observe_submitted`,
  `discard_submitted`). The precedent is server/definitions.clj's
  `measure`/`measure_pilot`, and it is recorded in `:deviations`.

  ONE ROW PER PULL REQUEST. `change_id` is `github:owner/repo#number`,
  and it is `:unique`. The source asks for the id before it mints, so
  a second read of the same pull request finds the row that is already
  here. An index refuses a duplicate, not a sentence in a charter.

  A CHANGE IS NOT ALWAYS BORN AT GITHUB (bead waymark-fp62.6.3.10).
  A seat that walks a queue of asks — a task list a person writes —
  has no pull request to point at, so the engine mints the change row
  for the ask itself: `change_id` is the walk kind, a colon and the
  walk row's id, and there is no `number`. The seat then works that
  row on the bench and submits it, and the push opens the pull
  request. The next source pass finds a pull request whose id answers
  no row, and a row of the same repository on the same head branch:
  it ADOPTS that row rather than minting a second one. `adopt` is the
  door that write goes through, and it is the mirror's, hidden like
  every other.

  THE MERGE COMPLETES THE TASK (bead waymark-fp62.6.3.14). The task
  is done when its pull request merges. `change_id` says which ask a
  seat-born row was minted for, and the adoption OVERWRITES it with
  GitHub's own id — so the origin is kept in a field of its own,
  `born_from`, written once at the mint and never again. When such a
  row moves to `merged`, the merge walks the task's `complete` door
  with the engine's own hand. It is best-effort: a task that is
  already done, gone or behind a door that refuses is not an error,
  because GitHub merged the pull request and this row follows GitHub
  whatever the queue says.

  WHAT `observe` IS FOR. A pull request changes under the row: a new
  commit moves the head sha, a review moves the review state, a
  rebase moves the counts. `observe` is a self-loop on `open` that
  writes those facts. It does not move the row, because the machine
  advances the state and a handler never does. A closed pull request
  gets no `observe` door: the row stands as it was when it closed,
  and `reopen` is the way back to a row that moves again.

  :nav :secondary, for inbox_item's reason (workqueue10). A pull
  request is the day job's work, not the family's. A `:primary` kind's
  open rows are claimed by the feed's next-actions population, and the
  household's feed must not card the day job's queue."
  (:require [clojure.string :as str]
            [factory10.bench :as bench]
            [factory10.mirror :refer [the-mirror-writes-this-row]]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── what a pull request writes back onto the row ────────────────────

(defhandler observe-the-pull-request [row inp _ctx]
  ;; The source hands the facts it read. A fact it did not read is
  ;; absent, and absent means silent: the stored value stands. The
  ;; machine advances the state, never this handler.
  (update row :data merge (into {} (remove (comp nil? val)) inp)))

(defhandler adopt-the-pull-request [row inp _ctx]
  ;; The identity lands as `observe` writes the facts, and the forge
  ;; pass's note that nobody had adopted the landed pull request goes
  ;; with the wait it described (ticket 58e706d6).
  (-> row
      (update :data merge (into {} (remove (comp nil? val)) inp))
      (update :data assoc :landed_at nil :adoption_note nil)))

(defhandler note-the-adoption [row inp _ctx]
  ;; THE FORGE PASS'S OWN RECORD (ticket 58e706d6): when it first saw
  ;; the pull request the bench's landing opened, and, once the window
  ;; has passed with no adoption, the note that says so. An input with
  ;; neither clears both.
  (update row :data assoc
          :landed_at (some-> (:landed_at inp) str not-empty)
          :adoption_note (some-> (:adoption_note inp) str not-empty)))

;; ── the merge finishes the task the change was born from ────────────

(def ^:private born-kinds
  "What `born_from` may read as its kind, and how each one is
  finished. The merge opens these doors and no other: a kind this
  module does not know is a door this module must not walk.

  A `task` (workqueue10) keeps its lifecycle in `status`, and its
  `complete` takes nothing. A `ticket` (this module) keeps its
  lifecycle in the machine, and the merge ends it through `land` —
  the change's own door, open from every state that has not ended
  (tickets 2e869934 and 3ec37f66) — with the pull request's own
  address as the sentence the record keeps. A ticket already `done`
  or `dropped` is left as it is."
  {"task" {:kind :task
           :action :complete
           :finished? (fn [row] (= "done" (str (get-in row [:data :status]))))
           :input (fn [_change] nil)}
   "ticket" {:kind :ticket
             :action :land
             :finished? (fn [row] (contains? #{:done :dropped}
                                             (some-> (:state row) name keyword)))
             :input (fn [change]
                      ;; the pull request's url, which a reader opens;
                      ;; its id when the mirror read no url
                      {:close_reason (str "Merged: "
                                          (or (not-empty (str (get-in change [:data :url])))
                                              (get-in change [:data :change_id]))
                                          ".")})}})

(defn- born-of
  "The walk row this change was born from — [kind-entry id row] — or
  nil. nil is the ordinary answer: a change GitHub gave us was born
  from nothing, and an engine that serves no such kind reads no row
  (`:read` answers nil for a kind it does not carry)."
  [row ctx]
  (let [born (str (get-in row [:data :born_from]))
        colon (str/index-of born ":")]
    (when colon
      (let [entry (get born-kinds (subs born 0 colon))
            id (not-empty (subs born (inc (long colon))))]
        (when (and entry id)
          (when-some [read (:read ctx)]
            (when-some [walk-row (read (:kind entry) id)]
              [entry id walk-row])))))))

(def ^:private unended-changes
  "The states a duplicate may stand in when the merge closes it: every
  state but the two endings."
  #{:open :submitted :failing :stuck})

(defn- close-the-duplicates!
  "Close every other change born from the same ask on the same branch
  (ticket 3ec37f66). A second row minted for one ticket is a change
  nobody will merge, and a stuck one holds the seat's queue; once one
  of them merges, the rest are closed through `supersede`, naming the
  pull request that merged.

  BEST-EFFORT, as the ticket's ending is: a row that refuses is said in
  the log, and the merge stands. A rehearsal carries no pen, and closes
  nothing."
  [row ctx]
  (let [find' (:find ctx)
        invoke' (:invoke ctx)
        self (str (:id row))
        born (some-> (get-in row [:data :born_from]) str not-empty)
        repo (some-> (get-in row [:data :repository]) str not-empty)
        branch (some-> (get-in row [:data :head_branch]) str not-empty)]
    (when (and find' invoke' born repo branch)
      (doseq [dup (find' :change {:repository repo :head_branch branch}
                         {:limit 100})
              :when (and (not= self (str (:id dup)))
                         (= born (str (get-in dup [:data :born_from])))
                         (contains? unended-changes
                                    (some-> (:state dup) name keyword)))]
        (try
          (invoke' :change (str (:id dup)) :supersede
                   {:superseded_by (or (not-empty (str (get-in row [:data :url])))
                                       (str (get-in row [:data :change_id])))})
          (catch Exception e
            (binding [*out* *err*]
              (println "factory10 change merge: the duplicate" (:id dup)
                       "was not closed -" (ex-message e)))))))))

(defhandler complete-the-task-it-was-born-from [row _inp ctx]
  ;; THE ASK IS DONE WHEN ITS PULL REQUEST MERGES (bead
  ;; waymark-fp62.6.3.14, spec-seat.md R-12.32). The seat is told to
  ;; complete the ask after its submit, and the seat that stopped at
  ;; the submit left it open for a person to close by hand. So the
  ;; merge does it too, under the engine's own hand.
  ;;
  ;; BEST-EFFORT, AND THE MERGE NEVER FAILS FOR IT. An ask already
  ;; done is left alone, one that is gone reads nil, and a door that
  ;; refuses is caught and said in the log. GitHub merged the pull
  ;; request; this row follows GitHub whatever the queue answers.
  ;;
  ;; NO `:touches`. factory10 boots ALONE (factory10.main) and its
  ;; registry carries no `task`, so an entry naming that kind would
  ;; refuse the whole assembly — `checks-assembly/check-touches` asks
  ;; that every advertised target is registered. The precedent for a
  ;; cross-write no declaration can name is `worksheet`'s apply and
  ;; `insight`'s offer (workqueue10): the blast radius rides in prose,
  ;; here in the door's own `:one-way` sentence.
  (let [[entry id walk-row] (born-of row ctx)]
    (when (and walk-row
               (not ((:finished? entry) walk-row))
               (:invoke ctx))
      (try
        ((:invoke ctx) (:kind entry) id (:action entry) ((:input entry) row))
        (catch Exception e
          (binding [*out* *err*]
            (println "factory10 change merge: the" (name (:kind entry)) id
                     "did not complete -" (ex-message e)))))))
  (close-the-duplicates! row ctx)
  row)

;; ── the ticket follows its change's review (ticket 2e869934) ─────────

(defn- move-the-ticket!
  "Walk `action` on the ticket this change was born from, when that
  ticket stands in one of `from`: `review` when a round goes out,
  `return` when a review ends without a merge. The ticket's door opens
  for this kind's own doors and for no hand
  (`ticket/only-its-change-moves-it`).

  BEST-EFFORT, as the merge's completion is: a change born from a
  task or from nothing moves nothing, a ticket that stands elsewhere
  is left where it is, and a door that refuses is said in the log —
  the change's own move stands. A rehearsal carries no pen, and moves
  nothing."
  [row ctx from action]
  (let [[entry id walk-row] (born-of row ctx)]
    (when (and walk-row
               (= :ticket (:kind entry))
               (contains? from (some-> (:state walk-row) name keyword))
               (:invoke ctx))
      (try
        ((:invoke ctx) :ticket id action nil)
        (catch Exception e
          (binding [*out* *err*]
            (println "factory10 change: the ticket" id "did not" (name action)
                     "-" (ex-message e))))))))

(defhandler write-what-superseded-it [row inp _ctx]
  ;; The pull request that merged in this one's place rides on the row,
  ;; so a reader of a closed duplicate sees which change did the work
  ;; (ticket 3ec37f66). Its ticket already ended with that merge, so
  ;; nothing is sent back.
  (assoc-in row [:data :superseded_by] (str (:superseded_by inp))))

(defhandler fold-into-the-house-row [row inp _ctx]
  ;; A row the forge minted beside a house row on the same branch
  ;; (ticket 3c59c688) gives the pull request's id up, so the house
  ;; row can adopt it — `change_id` is `:unique` — and names the house
  ;; row that holds the work now. Nothing is sent back: the work goes
  ;; on, on the other row.
  (update row :data assoc
          :change_id (str "folded:" (get-in row [:data :change_id]))
          :superseded_by (str (:folded_into inp))))

(defhandler send-the-ticket-back [row _inp ctx]
  ;; A review that ended without a merge — a close — puts the ticket in
  ;; the queue again, where the seat that wrote the change wakes on its
  ;; `return`, and a person can ungroom it.
  (move-the-ticket! row ctx #{:in_review} :return)
  row)

(defhandler shelve-the-ticket [row _inp ctx]
  ;; A STALL IS A SEAT SAYING IT CANNOT BUILD THE TICKET AS WRITTEN, so
  ;; the ticket goes back to draft and not to the queue (ticket
  ;; 6bdaf6fe): a ticket left open beside a stuck change was handed to
  ;; the seat every wake, and every wake could only say it was stuck. A
  ;; person, or mayor, reads the stall and grooms it again.
  (move-the-ticket! row ctx #{:open :in_review} :shelve)
  row)

;; ── the bench: the four doors that reach the worktree ───────────────
;;
;; Each one calls the rig with the ENGINE's hand (factory10.bench),
;; past `invoke-for` and past the leash. The rig's refusals come back
;; as data, and this section turns the names it knows into refusals of
;; its own — a 409, which is the status the router counts on the open
;; sitting (R-8), with the way out on it.

(def ^:private pull-remedy
  "The way out of a push that did not land, and it is a POWER the seat
  already holds: bench.pull brings the worktree to the branch head, or
  merges the base in, and then the submit lands. It is named as the
  tool the model calls, because a `:kind/action` token would send the
  seat to a door that does not exist."
  (str "The branch moved under you. Call the power bench__pull with "
       "from: head, read what it answers, and submit again."))

(def ^:private ceiling-remedy
  (str "Make the change smaller: submit the part that stands on its "
       "own, or ask a person to raise max_lines on the repository "
       "policy."))

(def ^:private clean-remedy
  (str "There is nothing to submit. Edit a file with the power "
       "bench__edit first; if the work is done, say so with the stall "
       "door and let a person look at it. A landing that failed (a "
       "push the forge refused, a credential, a rig restart) is not "
       "this case: submit again and the bench retries it."))

(def ^:private nothing-detail
  "The worktree is clean: no file in it is different from the branch
  head, so there is nothing to commit and nothing to push.")

(def ^:private submitted-detail
  "This round is already submitted; wait for its checks. The worktree is
  clean and the change is in review: its pull request carries the round.")

(def ^:private submitted-remedy
  (str "Do not stall a change in review (ticket 60c2ec22): say that the "
       "round is submitted and stop. A red check, a conflict or a review "
       "comment comes back as feedback at a later sit."))

(defn- in-review?
  "True when the change's round is already submitted."
  [row]
  (= "submitted" (some-> (:state row) name)))

(defn- landing-failed?
  "True when the bench says the last landing failed, which a submit
  retries. A landing still running is not failed."
  [status]
  (= "failed" (some-> (get-in status [:landing :state]) name)))

(defn- landing-owed?
  "True when the bench still owes a landing on a clean worktree
  (ticket 4792cd3b): the last landing did not land, or the branch is
  ahead of the base with no landing that pushed this head. The rig's
  own submit takes that path and refuses `nothing_to_commit` itself
  when nothing is owed, so the door lets it through rather than
  refusing on the rig's behalf."
  [status]
  (let [landing (:landing status)
        state (some-> (:state landing) name)
        landed-head (some-> (or (:head landing) (:commit landing)) str)
        head (some-> (:head status) str)]
    (boolean
     (or (and (map? landing) (not= "landed" state))
         (and (pos? (long (or (:ahead status) 0)))
              (or (not (map? landing))
                  (and landed-head head (not= landed-head head))))))))

(defn- rig-refusal!
  "The rig's own refusal, said again as this door's.

  THE NAMES THIS DOOR KNOWS carry a remedy of their own; every other
  name carries the general one, so a refusal nobody anticipated still
  tells the seat what to do next. The rig's `remedy` sentence rides
  beside it when it sent one — it is the rig that knows what it
  refused."
  [what answer & [row]]
  (let [named (bench/refused answer)
        reason (some-> (:reason answer) str not-empty)
        theirs (some-> (:remedy answer) str not-empty)]
    (bench/refuse!
     (str "The bench refused to " what ": " named
          (when reason (str " — " reason)) ".")
     (cond-> (case named
               "push_rejected" [pull-remedy]
               "over_ceiling" [ceiling-remedy]
               "nothing_to_commit" (if (and row (in-review? row))
                                      [submitted-detail submitted-remedy]
                                      [clean-remedy])
               [(str "Read what the bench answered, do what it says, and "
                     "try again; if it refuses again, say so with the "
                     "stall door.")])
       theirs (conj theirs)))))

(def ^:private title-ceiling
  "How many characters of a change's title the pull request's title
  carries. A pull request title is a LABEL and not a sentence: a
  person reads it in a list, and 72 characters is the width the first
  line of a commit is written to. The cut loses nothing, because the
  seat's whole sentence rides as the description."
  72)

(def ^:private title-floor
  "How short a cut title may be before the word boundary is given up
  (bead waymark-fp62.6.3.13). A title whose last space is early has no
  boundary worth keeping, and half a label says more than a third of
  one."
  40)

(defn- cut-title
  "One title at the ceiling, cut at the last space before it. A cut
  through the middle of a word reads as a fault — a pull request of
  this house was titled \"…(waymark-fp62.\" — and a whole word says
  the same thing in fewer characters. A title with no space at or
  after the floor is cut hard, because there is no boundary to cut
  on."
  [title]
  (if (<= (count title) title-ceiling)
    title
    (let [space (str/last-index-of title " " title-ceiling)]
      (if (and space (>= (long space) title-floor))
        (str/trimr (subs title 0 space))
        (subs title 0 title-ceiling)))))

(defn- title-of
  "The title the pull request is opened with: this row's own title,
  cut at the ceiling and on a word boundary.

  THE ENGINE OWNS THE TITLE, and not the seat (bead
  waymark-fp62.6.3.13). The row's title is the ask's own words for a
  change a seat was given, and the pull request's own words for a
  change the mirror adopted, so a person reads one story in the queue
  and on the pull request. A row with no title at all answers nil, and
  the rig then falls back to the first line of the commit message."
  [row]
  (when-some [title (not-empty (str/trim (str (get-in row [:data :title]))))]
    (cut-title title)))

(defhandler submit-the-change [row inp ctx]
  ;; THE ROUND, IN ORDER: read the worktree, refuse a clean one, then
  ;; commit and push with the seat's sentence and the two trailers.
  ;;
  ;; THE ROUND ENDS ON THE CHANGE (bead waymark-fp62.6.3.4). `rounds`
  ;; grows by one here. The machine advances the state — the row lands
  ;; in `submitted` because the door says so, never because this
  ;; handler wrote it.
  ;;
  ;; THIS HANDLER MUST NOT CLOSE THE SITTING. The harness must close
  ;; the sitting with its Stop hook (spec-seat.md R-12.17), and that
  ;; report carries the bill of the round. A fired run raises its one
  ;; Stop event after the submit, so the sitting must stay open here.
  ;; A close written here would refuse that late report, and the
  ;; tokens and the cost of the round would land nowhere.
  (let [policy (bench/policy-of row ctx)
        repo (str (get-in row [:data :repository]))
        branch (bench/branch-of row policy)
        title (title-of row)
        ;; a sitting that no longer holds the ticket never writes its
        ;; branch (ticket d7c854b3)
        unheld (bench/unheld-detail row ctx)
        status (when-not unheld
                 (bench/ask ctx :status {:repo repo :branch branch}))]
    (cond
      unheld (bench/refuse! unheld [bench/unheld-remedy])
      (nil? status) (bench/refuse! bench/dark-detail [bench/dark-remedy])
      (bench/refused status) (rig-refusal! "read the worktree" status)
      ;; A clean worktree on a change already in review is its own
      ;; round, still landing or waiting on checks: the refusal says so
      ;; and names no stall (ticket 60c2ec22).
      (and (zero? (long (or (:dirty status) 0)))
           (in-review? row)
           (not (landing-failed? status)))
      (bench/refuse! submitted-detail [submitted-remedy])
      ;; A clean worktree is refused only when nothing is owed: a
      ;; landing that failed outside the worktree is retried by the
      ;; rig's submit, and the retry counts as a round like any other.
      (and (zero? (long (or (:dirty status) 0)))
           (not (landing-owed? status)))
      (bench/refuse! nothing-detail [clean-remedy])
      :else
      (let [answer (bench/ask ctx :submit
                              (cond-> {:repo repo
                                       :branch branch
                                       :message (str (:why inp))
                                       ;; the seat's sentence is the
                                       ;; commit message AND the pull
                                       ;; request's body; the TITLE is
                                       ;; the engine's (bead
                                       ;; waymark-fp62.6.3.13)
                                       :description (str (:why inp))
                                       ;; the blame line: this commit
                                       ;; was a SEAT's, in this
                                       ;; sitting
                                       :trailers (bench/trailers ctx)
                                       :max_lines (bench/max-lines-of policy)}
                                title (assoc :title title)))]
        (cond
          (nil? answer) (bench/refuse! bench/dark-detail [bench/dark-remedy])
          (bench/refused answer) (rig-refusal! "submit" answer row)
          :else
          (do
            ;; the round is out: the ticket leaves the walk until its
            ;; change comes back red or merges (ticket 2e869934)
            (move-the-ticket! row ctx #{:open} :review)
            (update row :data merge
                    (cond-> {:branch branch
                             :worktree_dirty 0
                             ;; a new round is a new head: the last
                             ;; red names are not its (ticket d1742908)
                             :failing_checks nil
                             :conflicts nil
                             ;; nor is the last landing's error: this
                             ;; submit is a landing of its own (ticket
                             ;; 92871afb)
                             :landing_error nil
                             ;; nor is a train's red: it named the old
                             ;; head (ticket 6566d32f)
                             :train_red_head nil
                             :train_red nil
                             ;; nor is the last merge state: the forge
                             ;; computes it again for the new head, and
                             ;; a stale `conflicted` would fail the
                             ;; round before the forge was re-read
                             ;; (ticket 5f12e772)
                             :mergeable "unknown"
                             :rounds (inc (long (or (get-in row [:data :rounds])
                                                    0)))}
                      (:commit answer) (assoc :head_sha
                                              (str (:commit answer)))))))))))

(defhandler discard-the-worktree [row inp ctx]
  ;; THE ESCAPE HATCH. The worktree goes back to the branch head and
  ;; every edit of it is gone. A person may also drop the branch; a
  ;; model may not, and the guard below is why.
  (let [policy (bench/policy-of row ctx)
        repo (str (get-in row [:data :repository]))
        branch (bench/branch-of row policy)
        drop? (true? (:drop_branch inp))
        answer (bench/ask ctx :discard
                          (cond-> {:repo repo :branch branch}
                            drop? (assoc :drop_branch true)))]
    (cond
      (nil? answer) (bench/refuse! bench/dark-detail [bench/dark-remedy])
      (bench/refused answer) (rig-refusal! "discard the worktree" answer)
      :else (update row :data merge {:branch branch :worktree_dirty 0}))))

(defhandler unstick-the-change [row _inp ctx]
  ;; A PERSON LOOKED AT IT. The rounds go back to zero, because the
  ;; ceiling counts the rounds nobody has read yet; a change a person
  ;; has read and put back to work starts its count again. A ticket
  ;; the round ceiling left in review goes back to the queue with it,
  ;; which is how a person releases a stuck change (ticket 2e869934).
  (move-the-ticket! row ctx #{:in_review} :return)
  (assoc-in row [:data :rounds] 0))

(defhandler rework-the-change [row _inp _ctx]
  ;; `unstick`'s count, walked by the TICKET (ticket 9ace68fb): a groom,
  ;; an unblock or a resume is a person's reading of the ask, so the
  ;; rounds start again. The ticket is already on its way to `open`,
  ;; so nothing here moves it.
  (assoc-in row [:data :rounds] 0))

(defn- back-under-review
  "A stuck change put back under review: the rounds start from zero,
  and the forge computes the red names again for the head it reads next."
  [row]
  (update row :data assoc
          :rounds 0
          :failing_checks nil
          :conflicts nil
          :landing_error nil
          :train_red_head nil
          :train_red nil))

(defhandler unstick-the-pull-request [row _inp ctx]
  ;; A STUCK CHANGE WITH A PULL REQUEST GOES BACK WHERE THE PULL REQUEST
  ;; IS (ticket 6bdaf6fe). An `open` change is read by neither the
  ;; failing pass nor the merge pass, and the seat's submit refuses a
  ;; clean worktree, so a green pull request put back in `open` had no
  ;; way to the merge line. In `submitted` the next forge pass judges
  ;; its head, red or green. Its ticket goes out for review with it,
  ;; when the ticket is in the queue; a draft stays a draft for a
  ;; person to groom, and the merge ends it wherever it stands.
  (move-the-ticket! row ctx #{:open} :review)
  (back-under-review row))

(defhandler rework-the-pull-request [row _inp _ctx]
  ;; `unstick_submitted`'s landing, walked by the TICKET (ticket
  ;; 4363c63b): a groom, unblock or resume puts a stuck pull request
  ;; back where the forge pass reads it, so a red or conflicted head
  ;; comes back to the seat as `failing` and a green one enters the
  ;; merge line. The ticket is already on its way to `open`, so nothing
  ;; here moves it.
  (back-under-review row))

;; ── the checks went red, or green again (ticket d1742908) ───────────

(defn- with-the-failing-checks
  ;; The names ride on the row, so the seat and the person read which
  ;; checks went red without opening GitHub. The machine moves the row.
  ;; A conflict rides as `merge-conflict` among the names, and its
  ;; paths, when the bench could name them, beside (ticket 5f12e772).
  ;; A landing that failed rides as `landing:<step>`, and the step's
  ;; output beside it (ticket 92871afb). A merge train that found the
  ;; change red rides as the head it judged and the train's reason
  ;; beside the train's red names (ticket 6566d32f).
  [row inp]
  (update row :data assoc
          :failing_checks (vec (:failing_checks inp))
          :conflicts (some-> (:conflicts inp) seq vec)
          :landing_error (some-> (:landing_error inp) str not-empty)
          :train_red_head (some-> (:train_red_head inp) str not-empty)
          :train_red (some-> (:train_red inp) str not-empty)))

(defhandler write-the-failing-checks [row inp _ctx]
  ;; the ceiling's red: the ticket stays in review, and the stuck
  ;; change is what a person reads (ticket 2e869934)
  (with-the-failing-checks row inp))

(defhandler fail-the-change [row inp ctx]
  ;; A red or conflicted head sends the ticket back to the queue, and
  ;; the seat that wrote the change wakes on its `return` and walks it
  ;; with this change and its feedback (ticket 2e869934).
  (move-the-ticket! row ctx #{:in_review} :return)
  (with-the-failing-checks row inp))

(defhandler clear-the-failing-checks [row _inp ctx]
  ;; A green head, or a new round: the names of the last red are not
  ;; this head's, and a stale list would read as a live one. A ticket
  ;; the red sent back goes out for review again (ticket 2e869934).
  (move-the-ticket! row ctx #{:open} :review)
  (update row :data assoc :failing_checks nil :conflicts nil
          :landing_error nil :train_red_head nil :train_red nil))

;; ── the walls on the bench doors ────────────────────────────────────
;;
;; The three that read the repository policy are CONFORMANCE-tier:
;; each declares `:reads [:repo_policy]` and asks the ctx `:find` hook
;; for the row. A ctx with no hook — the render probe — is answered
;; with an ALLOW, which is the framework's own posture there (the
;; envelope advertises optimistically and the door judges again with a
;; real hook behind it). Their law is proved in factory10.bench-test,
;; over a real store, because a scenario cannot stage the policy row
;; they read.

(defguardfn the-repository-has-a-policy
  {:reads [:repo_policy]
   :vars [:repository]
   :remedies [:repo_policy/create]
   :explain "What submit means in {repository} is a row, and this repository has no active policy: the branch pattern, the base, the size ceiling and the round ceiling are all unsaid. A person states them once, and the bench works this repository from then on."}
  [row _inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (if (bench/policy-of row ctx)
      (t/allow)
      (t/deny {:vars {:repository (str (get-in row [:data :repository]))}}))))

(defguardfn the-branch-matches-the-policy
  {:reads [:repo_policy]
   :vars [:branch :pattern]
   :remedies [:repo_policy/restate]
   :explain "This change works on the branch {branch}, and the policy says a work branch of this repository looks like {pattern}. A branch outside the pattern is a branch nobody agreed to, so the door refuses it before the bench does."}
  [row _inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (let [policy (bench/policy-of row ctx)
          branch (bench/branch-of row policy)
          pattern (bench/pattern-of policy)]
      (if (bench/matches-pattern? branch pattern)
        (t/allow)
        (t/deny {:vars {:branch branch :pattern pattern}})))))

(defguardfn the-branch-is-not-the-base
  {:reads [:repo_policy]
   :vars [:branch]
   :open "No door here changes this verdict. The base branch is where the work lands, never where it is written, and the bench refuses a push to it whatever the grant says."
   :explain "This change works on {branch}, which is the base branch of this repository. Nothing is ever pushed to the base branch from a seat."}
  [row _inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (let [policy (bench/policy-of row ctx)
          branch (bench/branch-of row policy)]
      (if (= branch (bench/base-of policy))
        (t/deny {:vars {:branch branch}})
        (t/allow)))))

(defguardfn under-the-round-ceiling
  {:reads [:repo_policy]
   :vars [:rounds :ceiling]
   :remedies [:change/stall]
   :explain "This change has had {rounds} of the {ceiling} rounds the policy gives it. The house stops here rather than spending another wake on the same change: say with the stall door what is wrong, and a person reads it."}
  [row _inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (let [policy (bench/policy-of row ctx)
          rounds (long (or (get-in row [:data :rounds]) 0))
          ceiling (bench/rounds-of policy)]
      (if (< rounds ceiling)
        (t/allow)
        (t/deny {:vars {:rounds rounds :ceiling ceiling}})))))

(defguardfn only-a-person-drops-the-branch
  {:judges [:drop_branch]
   :reads [:principal :within]
   :hold true
   :open "No door clears this one. The call waits as a held_call for the person's tap. A discard that keeps the branch is the model's escape hatch and is always open; dropping the branch throws away a push that GitHub may already hold, so it waits on a person's hand."
   :explain "A discard that drops the branch removes the worktree and the branch itself. That is a person's act, so an agent's drop is held for the person's tap: the call is recorded as a held_call, and the person's Allow runs it exactly as written. The model's own discard puts the worktree back to the branch head and keeps the branch."}
  [row inp ctx]
  ;; ticket's `only-a-person-reopens`, one kind over: every hand but an
  ;; agent's passes, and so does an agent's discard that keeps the
  ;; branch. An agent's drop is HELD (waymark10.holds), and the one
  ;; agent drop this admits is the engine's replay of the held call its
  ;; person allowed. `discard` and `discard_submitted` share this wall,
  ;; so the replay check names the row and not the door.
  (cond
    (not (true? (:drop_branch inp))) (t/allow)
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :change (:id row)) (t/allow)
    :else (t/deny)))

(defguardfn a-person-or-their-delegate-unsticks
  {:reads [:principal]
   :open "No door here changes this verdict. A stuck change is the house asking a person to look at it, and a model alone that could put itself back to work would be answering its own question. A person taps unstick, or unstick_submitted for a change with a pull request, or a delegate acting for one does. After a seat's stall, which sends the ticket to draft, grooming the ticket again puts a change with no pull request back to work at the groom itself, and one with a pull request back under review at the groom too; a change stuck at the round ceiling leaves its ticket in review, where grooming does not serve."
   :explain "This change is stuck: it reached the round ceiling, or a seat said it could not finish. A person, or a delegate acting for one under a grant the person approved, reads it and puts it back to work."}
  [_row _inp ctx]
  ;; ticket's `a-person-or-their-delegate-grooms`, one kind over: the
  ;; wall is against a model ALONE, and a delegate that names whom it
  ;; acts for is the person's hand.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (and (= :agent type) (str/blank? (str acts-for)))
      (t/deny)
      (t/allow))))

(defguardfn only-its-ticket-reworks-it
  {:reads [:within]
   :open "No door clears this one. A stuck change goes back to work when a person grooms, unblocks or resumes the ticket it was built for; the ticket moves it then, and a person who wants it sooner taps unstick."
   :explain "A stuck change is put back to work by its ticket's groom, unblock or resume, in the same transaction, and by no hand: a person's own door is unstick."}
  [_row _inp ctx]
  ;; ticket's `only-its-change-moves-it`, the other way round: the door
  ;; opens inside a `ticket` door's own transaction and for nobody's
  ;; hand. The wire, the render probe and every rehearsal answer nil,
  ;; so it renders refused, which is true.
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :ticket kind) (contains? #{:groom :unblock :resume} action))
      (t/allow)
      (t/deny))))

(defn- has-a-pull-request? [row]
  (some? (get-in row [:data :number])))

(defguardfn the-change-has-no-pull-request
  {:reads []
   :open "No door here changes this verdict. A change with a pull request goes back to work through unstick_submitted, which lands it where its pull request is, under review."
   :explain "This change has a pull request, so putting it back to work lands it in submitted, where the house reads its checks and merges it green: unstick_submitted is that door."}
  [row _inp _ctx]
  (if (has-a-pull-request? row) (t/deny) (t/allow)))

(defguardfn the-change-has-a-pull-request
  {:reads []
   :open "No door here changes this verdict. A change with no pull request yet goes back to work through unstick, which lands it in open for its next round."
   :explain "This change has no pull request yet, so there is nothing under review to go back to: unstick puts it in open, and the seat's next submit opens the pull request."}
  [row _inp _ctx]
  (if (has-a-pull-request? row) (t/allow) (t/deny)))

(defguardfn no-pull-request-to-close
  {:reads []
   :open "No door here changes this verdict. A change with a pull request ends at GitHub: close the pull request there, and the mirror closes the row with its own close."
   :explain "This change has a pull request, so GitHub owns its ending. Close the pull request at GitHub and the mirror follows; this door is for a change GitHub never saw."}
  [row _inp _ctx]
  (if (has-a-pull-request? row) (t/deny) (t/allow)))

(defguardfn only-a-person-closes-a-change
  {:reads [:principal :within]
   :hold true
   :vars [:title :branch]
   :open "No door clears this one. The call waits as a held_call for the person's tap: ending a change a seat was building is the person's judgment, and the person's Allow runs it exactly as written."
   :explain "Closing {title} on {branch} is held for the person's tap: the call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row _inp ctx]
  ;; `only-a-person-drops-the-branch`, one door over: every hand but an
  ;; agent's passes, a delegate's included in the hold (the mayor asks,
  ;; the person taps), and the one agent close this admits is the
  ;; engine's replay of the held call its person allowed.
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :change :close_without_pr (:id row)) (t/allow)
    :else (t/deny {:vars {:title (str (get-in row [:data :title]))
                          :branch (str (or (get-in row [:data :branch])
                                           (get-in row [:data :head_branch])))}})))

;; ── the law, written down as a scenario ─────────────────────────────
;;
;; Check-tier: no :given rows, and the one guard reads :principal and
;; nothing else. `make check-factory` judges it with no database.

(def ^:private a-pull-request
  {:change_id "github:ckopsa/waymark#31"
   :repository "ckopsa/waymark"
   :number 31
   :title "6.2 The change family: change and ci_run as kinds"
   :author "ckopsa"
   :base_branch "main"
   :head_branch "waymark-fp62.6.2"
   :head_sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"})

(defscenario a-model-does-not-move-a-pull-request
  "GitHub owns this row. A model that could merge a pull request by
   writing a row would be telling the house something that is not
   true, so the door is not there for it."
  {:kind    :change
   :attempt :merge
   :row     {:state :open :data a-pull-request}
   :as      {:id "ci-classifier" :type :agent}
   :expect  {:refused :the-mirror-writes-this-row}})

(defscenario the-source-moves-the-pull-request
  "And the door is really there for the engine's own hand, which is
   what the source writes with."
  {:kind    :change
   :attempt :merge
   :row     {:state :open :data a-pull-request}
   :as      {:id "factory10-source" :type :system}
   :expect  {:allowed true}})

(defscenario a-model-does-not-drop-the-branch
  "A discard that keeps the branch is the model's own escape hatch. A
   discard that drops it throws away a push GitHub may already hold,
   so that half of the door is a person's: the model's drop is refused
   here, and at the wire it is held for the person's tap."
  {:kind    :change
   :attempt :discard
   :row     {:state :open :data a-pull-request}
   :input   {:drop_branch true}
   :as      {:id "bench-seat" :type :agent}
   :expect  {:refused :only-a-person-drops-the-branch
             :because "held for the person's tap"}})

(defscenario the-person-drops-the-branch
  "And the door is really there for the person whose branch it is —
   one tap, no grant and no ceremony."
  {:kind    :change
   :attempt :discard
   :row     {:state :open :data a-pull-request}
   :input   {:drop_branch true}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(def ^:private a-seat-branch
  "A change a seat pushed and GitHub never opened a pull request for."
  (-> a-pull-request
      (dissoc :number)
      (assoc :change_id "ticket:be2c2c16-4876-4807-939c-69729db36152"
             :head_branch "bench/be2c2c16-4876-4807-939c-69729db36152")))

(defscenario a-model-does-not-close-a-change-alone
  "A change with no pull request has no ending GitHub will write. The
   mayor may ask to close one, a duplicate whose ticket merged under
   another row, and the ask waits for the person's tap."
  {:kind    :change
   :attempt :close_without_pr
   :row     {:state :open :data a-seat-branch}
   :input   {:why "A duplicate: its ticket merged under another row."}
   :as      {:id "mayor" :type :agent}
   :expect  {:refused :only-a-person-closes-a-change
             :because "held for the person's tap"}})

(defscenario the-person-closes-a-change-with-no-pull-request
  "And the door is really there for the person, from `submitted` too:
   a pushed branch no pull request adopted ends with one tap."
  {:kind    :change
   :attempt :close_without_pr
   :row     {:state :submitted :data a-seat-branch}
   :input   {:why "A duplicate: its ticket merged under another row."}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-change-with-a-pull-request-is-closed-at-github
  "A change with a number is GitHub's to close, and this door is not
   for it."
  {:kind    :change
   :attempt :close_without_pr
   :row     {:state :open :data a-pull-request}
   :input   {:why "No longer wanted."}
   :as      {:id "colton" :type :person}
   :expect  {:refused :no-pull-request-to-close}})

(defscenario a-model-discards-its-own-edits
  "And the escape hatch itself is always open: the worktree goes back
   to the branch head, and the branch stands."
  {:kind    :change
   :attempt :discard
   :row     {:state :open :data a-pull-request}
   :input   {}
   :as      {:id "bench-seat" :type :agent}
   :expect  {:allowed true}})

(defscenario a-model-does-not-unstick-itself
  "A stuck change is the house asking a person to look at it. A model
   ALONE — the seat that stalled it, acting for nobody — that could put
   itself back to work would be answering its own question, and the
   round ceiling would stop nothing."
  {:kind    :change
   :attempt :unstick_submitted
   :row     {:state :stuck :data (assoc a-pull-request :rounds 3)}
   :as      {:id "bench-seat" :type :agent}
   :expect  {:refused :a-person-or-their-delegate-unsticks
             :because "delegate acting for one"}})

;; A delegate that unsticks — an agent whose `acts-for` names its
;; person — cannot be spelled here, because a scenario's principal
;; carries :id, :type and :roles and nothing else. It is judged in
;; factory10.tree-test, with the envelope's own probe.

(defscenario the-person-puts-a-stuck-change-back-to-work
  "And the door is really there for the person who read it — one tap,
   and the rounds start again. A change with a pull request goes back
   to `submitted`, where its pull request is (ticket 6bdaf6fe)."
  {:kind    :change
   :attempt :unstick_submitted
   :row     {:state :stuck :data (assoc a-pull-request :rounds 3)}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-change-with-a-pull-request-is-not-put-back-in-open
  "An `open` change is read by neither the failing pass nor the merge
   pass, so a pull request put back there would wait forever."
  {:kind    :change
   :attempt :unstick
   :row     {:state :stuck :data (assoc a-pull-request :rounds 3)}
   :as      {:id "colton" :type :person}
   :expect  {:refused :the-change-has-no-pull-request}})

(defscenario a-change-with-no-pull-request-goes-back-to-open
  "A change that was never pushed has nothing under review: the person
   puts it back in `open`, for the seat's next round."
  {:kind    :change
   :attempt :unstick
   :row     {:state :stuck :data (assoc (dissoc a-pull-request :number) :rounds 3)}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-seat-does-not-say-its-own-change-is-red
  "`failing` is what the checks said, read by the engine. A seat that
   could move its change there, or back to `submitted`, would be
   writing the verdict of the checks itself."
  {:kind    :change
   :attempt :recover
   :row     {:state :failing
             :data (assoc a-pull-request :failing_checks ["gate"])}
   :as      {:id "bench-seat" :type :agent}
   :expect  {:refused :the-mirror-writes-this-row}})

;; ── :change — one pull request, mirrored ────────────────────────────

(defresource change
  {:kind :change
   :plural "changes"
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   ;; GitHub's own three, and the bench's two (waymark-fp62.6.3.2):
   ;; `submitted` is a change a seat has pushed at least one round of,
   ;; and `stuck` is one the house stopped working. `failing` is a
   ;; submitted change whose required checks went red (d1742908).
   :states [:open :submitted :failing :stuck :merged :closed]
   :initial :open
   ;; ONE TOMB. A merged pull request is finished. A closed one is
   ;; not: GitHub reopens it, so `reopen` is a real door.
   :terminal #{:merged}
   ;; what each ending MEANS, in the factory's own words. A merged
   ;; change is what the work accomplished; a closed one is what it
   ;; let go. The engine shuts the household's doors on a row whose
   ;; work is over, and `reopen` survives that because the machine
   ;; shows it as a way back — it lands in `open`, which is no ending.
   ;; `stuck` IS DELIBERATELY ABSENT from both — see the ns docstring:
   ;; a stuck change is work waiting for a person, not work that is
   ;; over, and an ending would shut the very doors it needs.
   :over {:accomplished #{:merged} :let-go #{:closed}}
   :summary "{data.repository}#{data.number} · {data.title}"
   ;; the pull request's own address, not the kind label
   :label-template "{data.repository}#{data.number}"
   :display {:title "{data.title}"}
   :filterable {:state #{:eq :in}
                :change_id #{:eq}
                :repository #{:eq}
                :head_branch #{:eq}
                :head_sha #{:eq}
                :author #{:eq}}
   ;; the collection a reader opens IS the pull requests that are
   ;; still live (inbox_item's spelling) — and a change a seat has
   ;; pushed is still live: the checks run, the review lands, and the
   ;; seat works the next round on the same row. A STUCK change is
   ;; outside this filter on purpose, so a walking seat never meets
   ;; again the change the house stopped.
   :default-filters {:state "open,submitted"}
   :sortable {:fields [:updated_at] :default "updated_at"}
   ;; ONE ROW PER PULL REQUEST, enforced by an index
   :unique [[:change_id]]
   :schema
   [:map
    ;; :raw because it is a LABEL and not prose — an opaque address is
    ;; shown exactly as it arrived. It also tells the assembly's ref
    ;; lint the truth: a field named <kind>_id for a kind this engine
    ;; serves reads as a reference by convention (checks-assembly's
    ;; id-target), and this one is not a reference to another change —
    ;; it is THIS row's own address at GitHub.
    [:change_id {:x-display
                 {:raw true
                  :label "The pull request's own id"
                  :help "The address GitHub knows this pull request by, as github:owner/repo#number. The source reads it back to see whether this pull request is already a row here. A change a seat built from an ask carries the ask's own address instead — the walk kind, a colon and the row id — until the push opens the pull request and the source adopts the row."}}
     [:string {:min 1 :max 250}]]
    [:repository {:x-display
                  {:label "The repository"
                   :help "The repository as GitHub spells it, as owner/repo. Every policy about this change reads this field first."}}
     [:string {:min 1 :max 140}]]
    ;; OPTIONAL, because a change is not always born at GitHub (bead
    ;; waymark-fp62.6.3.10). A seat that walks a queue of asks gets a
    ;; change row minted for the ask BEFORE any pull request exists,
    ;; and a number nobody has been given is a number this row must
    ;; not invent. The source writes it at the adoption, with `adopt`
    ;; below. Until then the summary line renders it as an em-dash,
    ;; which is the framework's own word for "not said yet".
    [:number {:optional true
              :x-display
              {:label "The pull request number"
               :help "The number GitHub shows on the pull request. It is unique inside the repository, not across repositories. A change a seat is still building has none until the push opens the pull request."}}
     [:maybe [:int {:min 1}]]]
    ;; :raw because it is a LABEL and not prose (inbox_item's subject,
    ;; one domain over): a long title must not refuse the mint
    [:title {:optional true
             :x-display
             {:raw true
              :label "Title"
              :help "The pull request title, exactly as it stands on GitHub. This is what the card reads."}}
     [:maybe [:string {:max 400}]]]
    [:author {:optional true
              :x-display
              {:label "Who opened it"
               :help "The GitHub login of the person or the seat that opened the pull request."}}
     [:maybe [:string {:max 120}]]]
    [:base_branch {:optional true
                   :x-display
                   {:label "The base branch"
                    :help "The branch this change merges into. It is main in most repositories."}}
     [:maybe [:string {:max 200}]]]
    [:head_branch {:optional true
                   :x-display
                   {:label "The head branch"
                    :help "The branch that holds the change. A bench seat pushes to this branch."}}
     [:maybe [:string {:max 200}]]]
    [:head_sha {:optional true
                :x-display
                {:label "The head commit"
                 :help "The full sha of the commit at the head of the branch. A ci_run names the same sha, so a red run can be read against the commit that caused it."}}
     [:maybe [:string {:max 64}]]]
    [:draft {:optional true
             :x-display
             {:label "A draft"
              :help "True while the pull request is a draft. A draft asks for no review yet."}}
     [:maybe :boolean]]
    [:mergeable {:optional true :filter #{:eq :in}
                 :x-display
                 {:label "Can it merge"
                  :choices {"clean" "It merges — no conflict and no block"
                            "conflicted" "It conflicts with the base branch"
                            "blocked" "A required check or a review blocks it"
                            "unknown" "GitHub has not answered yet"}}}
     [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]
    [:files_changed {:optional true
                     :x-display
                     {:label "Files changed"
                      :help "How many files the change touches."}}
     [:maybe [:int {:min 0}]]]
    [:lines_added {:optional true
                   :x-display
                   {:label "Lines added"
                    :help "How many lines the change adds. A repository policy may hold a ceiling against this number."}}
     [:maybe [:int {:min 0}]]]
    [:lines_removed {:optional true
                     :x-display
                     {:label "Lines removed"
                      :help "How many lines the change removes."}}
     [:maybe [:int {:min 0}]]]
    ;; a list of scalars: every client draws it as rows.
    ;; NOT filterable, deliberately: a containment filter over an
    ;; array promotes an index of its own, and no reader of this bead
    ;; asks "which changes touch this path" yet. The field is here so
    ;; a policy can read it off the row.
    [:touched_paths {:optional true
                     :x-display
                     {:label "The paths it touches"
                      :help "Every path the change writes to, as GitHub lists them. A policy that fences a directory reads this list."}}
     [:maybe [:vector [:string {:max 400}]]]]
    [:labels {:optional true
              :x-display
              {:label "Labels"
               :help "The labels that stand on the pull request now. The mirror pushes a ci label here; it does not invent one."}}
     [:maybe [:vector [:string {:max 100}]]]]
    [:review_state {:optional true :filter #{:eq :in}
                    :x-display
                    {:label "The review"
                     :choices {"pending" "Nobody has reviewed it yet"
                               "approved" "A reviewer approved it"
                               "changes_requested" "A reviewer asked for changes"
                               "commented" "A reviewer commented and asked for nothing"}}}
     [:maybe [:enum "pending" "approved" "changes_requested" "commented"]]]
    ;; ── the bench's three (waymark-fp62.6.3.2, R-4) ──────────────
    [:branch {:optional true
              :x-display
              {:raw true
               :label "The branch on the bench"
               :help "The branch the worktree sits on. It is the head branch when GitHub already named one, and the policy's pattern with this row's own id when it did not. One branch for each change, so the next sitting finds the last one's work."}}
     [:maybe [:string {:max 200}]]]
    [:worktree_dirty {:default 0
                      :x-display
                      {:label "Edits waiting in the worktree"
                       :help "How many paths of the worktree were different from the branch head when a door last looked. The bench itself holds the live count, and the sit answers it; this is what the doors wrote down."}}
     [:int {:min 0}]]
    [:rounds {:default 0
              :x-display
              {:label "Rounds spent"
               :help "How many times a seat has submitted this change. At the repository policy's ceiling the change stops and waits for a person."}}
     [:int {:min 0}]]
    ;; written by the engine's `fail` and `stick`, cleared by
    ;; `recover` and by the next submit (ticket d1742908)
    [:failing_checks {:optional true
                      :x-display
                      {:label "The checks that went red"
                       :help "The required checks that finished red on the head the seat last pushed. The house writes them when it moves the change to failing, and clears them when the head goes green or the seat submits again."}}
     [:maybe [:vector [:string {:max 200}]]]]
    ;; written beside `merge-conflict` in `failing_checks` when the
    ;; bench can name the paths; cleared with it (ticket 5f12e772)
    [:conflicts {:optional true
                 :x-display
                 {:label "The paths that conflict"
                  :help "The paths a trial merge of the base branch into this change's branch left unmerged. The house writes them when a conflict moves the change to failing, and clears them when the head merges clean and goes green, or the seat submits again."}}
     [:maybe [:vector [:string {:max 400}]]]]
    ;; written by the forge pass through the observe doors (ticket
    ;; 22f91244): the head whose interrupted run it re-ran, once, and
    ;; a note when that same head died without a verdict again
    [:rerun_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:rerun_note {:optional true
                  :x-display
                  {:label "A runner that keeps dying"
                   :help "The house re-runs a head's checks once when they die without a verdict about the code. When the same head dies again, this says so, and a person looks at the runner."}}
     [:maybe [:string {:max 240}]]]
    ;; written beside `landing:<step>` in `failing_checks` when the
    ;; rig's landing of a submit failed; cleared with it (ticket
    ;; 92871afb)
    [:landing_error {:optional true
                     :x-display
                     {:widget "prose"
                      :label "Why the push did not land"
                      :help "The end of the output of the step the bench's landing failed at, when a submit never reached GitHub. The house writes it when it moves the change to failing, and clears it when the seat submits again."}}
     [:maybe [:string {:max 4000}]]]
    ;; written by a merge train that found this change red though its
    ;; own head is green (ticket 6566d32f): the head the train judged,
    ;; and the train's branch and run url. While `head_sha` still is
    ;; that head the forge pass does not recover the change; a new
    ;; head, or the seat's next submit, clears both.
    [:train_red_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:train_red {:optional true
                 :x-display
                 {:widget "prose"
                  :label "Why a merge train found it red"
                  :help "This change's own checks are green, and the merge train that carried it with others went red with it: the train's branch and the url of its run. `failing_checks` names the train's red checks. The change stays failing until its head moves."}}
     [:maybe [:string {:max 1000}]]]
    ;; a submit whose landing opened a pull request the forge never
    ;; adopted (ticket 58e706d6): the first time the forge pass saw it,
    ;; and the note it writes once the window has passed. The adoption
    ;; clears both.
    [:landed_at {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:adoption_note {:optional true
                     :examples ["landed as #7 on ckopsa/waymark but no pull request row adopted it; head bench/58e706d6"]
                     :x-display
                     {:widget "prose"
                      :label "A pull request nobody adopted"
                      :help "The bench's landing opened a pull request for this change, and the house's row never took its number, so neither the house's merge nor a merge ask sees it. Cleared when the row adopts the pull request."}}
     [:maybe [:string {:max 500}]]]
    ;; hidden: the origin LINK below is the affordance, and a raw URL
    ;; in the fields is noise (task_list's own spelling)
    ;; the house's merge line (ticket b85aded5): the merge pass writes
    ;; these as a maintenance write each time they move, and clears
    ;; them when the change leaves submitted
    [:line_place {:optional true
                  :examples [2]
                  :x-display
                  {:label "Place in the merge line"
                   :help "Where this change stands in its repository's merge line: 1 is the front, the one the house brings up to date next. Empty when it is out of the line."}}
     [:maybe [:int {:min 1}]]]
    [:line_why {:optional true :filter #{:eq :in}
                :x-display
                {:label "Why it is not merging"
                 :choices {"front" "It is the front of the line: the house brings it up to date and merges it when green"
                           "behind" "It waits its turn: only the front is brought up to date"
                           "red" "Its checks are red, so it is out of the line"
                           "conflicted" "It conflicts with the base branch"
                           "draft" "It is a draft, and a draft is not brought forward"
                           "parked" "The bench refused this head for good"
                           "held" "Its ticket merges after tickets that are not done yet"}}}
     [:maybe [:enum "front" "behind" "red" "conflicted" "draft" "parked" "held"]]]
    [:line_reason {:optional true
                   :examples ["GitHub says it cannot merge"]
                   :x-display
                   {:widget "prose"
                    :label "Why it is parked or held"
                    :help "The bench's own reason for refusing this head for good, or the tickets a held change waits on to merge. Empty unless the change is parked or held."}}
     [:maybe [:string {:max 500}]]]
    [:url {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]
    ;; ── where a seat-born change CAME FROM (waymark-fp62.6.3.14) ──
    ;; The walk kind, a colon and the walk row's own id — the words
    ;; `change_id` carries at the mint. The adoption overwrites
    ;; `change_id` with GitHub's identity, so the origin needs a field
    ;; the adoption does not touch: the merge reads it to finish the
    ;; task this change was built for. Hidden, like every other fact
    ;; the engine writes and nobody types.
    [:born_from {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 250}]]]
    ;; the merged change a duplicate was closed for (ticket 3ec37f66):
    ;; written by `supersede` and by nothing else. Hidden, as the url is.
    [:superseded_by {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]]
   ;; THE BIRTH DOOR IS THE MIRROR'S, AND IT IS HIDDEN. A person meets
   ;; no create form for this kind. The source mints the row with what
   ;; GitHub answered; everything but the identity is optional,
   ;; because a first read does not always carry the counts.
   :create-guards [the-mirror-writes-this-row]
   :create-schema
   [:map
    ;; github:owner/repo#number from the source, and <kind>:<row id>
    ;; from a seat's own sit (spec-seat.md R-12.32)
    [:change_id {:x-display {:label "The pull request's own id"}}
     [:string {:min 1 :max 250}]]
    [:repository {:x-display {:label "The repository"}}
     [:string {:min 1 :max 140}]]
    ;; the mirror gives it; the seat's own mint does not (R-12.32)
    [:number {:optional true :x-display {:label "The pull request number"}}
     [:maybe [:int {:min 1}]]]
    [:title {:optional true :x-display {:raw true :label "Title"}}
     [:maybe [:string {:max 400}]]]
    [:author {:optional true :x-display {:label "Who opened it"}}
     [:maybe [:string {:max 120}]]]
    [:base_branch {:optional true :x-display {:label "The base branch"}}
     [:maybe [:string {:max 200}]]]
    [:head_branch {:optional true :x-display {:label "The head branch"}}
     [:maybe [:string {:max 200}]]]
    [:head_sha {:optional true :x-display {:label "The head commit"}}
     [:maybe [:string {:max 64}]]]
    [:draft {:optional true :x-display {:label "A draft"}}
     [:maybe :boolean]]
    [:url {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]
    ;; the seat's own mint writes it and the forge never does
    ;; (waymark-fp62.6.3.14)
    [:born_from {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 250}]]]]
   :actions
   {;; THE MIRROR'S REFRESH. A self-loop on `open`: the pull request
    ;; moved under the row, and these are the facts that moved.
    :observe
    {:from #{:open} :to :open
     :guards [the-mirror-writes-this-row]
     :handler observe-the-pull-request
     :input [:map
             [:title {:optional true :x-display {:raw true}}
              [:maybe [:string {:max 400}]]]
             [:head_sha {:optional true} [:maybe [:string {:max 64}]]]
             [:draft {:optional true} [:maybe :boolean]]
             [:mergeable {:optional true}
              [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]
             [:files_changed {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_added {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_removed {:optional true} [:maybe [:int {:min 0}]]]
             [:touched_paths {:optional true}
              [:maybe [:vector [:string {:max 400}]]]]
             [:labels {:optional true} [:maybe [:vector [:string {:max 100}]]]]
             [:review_state {:optional true}
              [:maybe [:enum "pending" "approved" "changes_requested"
                       "commented"]]]]
     ;; :edit-shape — the input MIRRORS the document on purpose. This
     ;; is not a person editing a row; it is the authority restating
     ;; what it holds, and a prefill for a hidden door is scaffolding
     ;; for nobody.
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Observe" :order 1
               :description "The mirror writes what GitHub says about this pull request now"}}

    ;; THE THREE MOVES GITHUB MAKES. Each one is the mirror's, and
    ;; each one is hidden from every hand but the engine's.
    ;; THE SAME REFRESH, one state over. A v10 action declares one
    ;; `:to`, so a self-loop that serves two states is spelled twice
    ;; (server/definitions.clj's `measure`/`measure_pilot`) — see
    ;; `:deviations`. A change a seat has pushed goes on moving under
    ;; the row: the checks run, a reviewer answers, the head moves.
    :observe_submitted
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler observe-the-pull-request
     :input [:map
             [:title {:optional true :x-display {:raw true}}
              [:maybe [:string {:max 400}]]]
             [:head_sha {:optional true} [:maybe [:string {:max 64}]]]
             [:draft {:optional true} [:maybe :boolean]]
             [:mergeable {:optional true}
              [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]
             [:files_changed {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_added {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_removed {:optional true} [:maybe [:int {:min 0}]]]
             [:touched_paths {:optional true}
              [:maybe [:vector [:string {:max 400}]]]]
             [:labels {:optional true} [:maybe [:vector [:string {:max 100}]]]]
             [:review_state {:optional true}
              [:maybe [:enum "pending" "approved" "changes_requested"
                       "commented"]]]
             ;; the forge pass's re-run of an interrupted head (ticket
             ;; 22f91244)
             [:rerun_head {:optional true} [:maybe [:string {:max 64}]]]
             [:rerun_note {:optional true} [:maybe [:string {:max 240}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Observe" :order 10
               :description "The mirror writes what GitHub says about this pull request now"}}

    ;; and once more for `failing` (ticket d1742908): a red change's
    ;; head moves when a person pushes to it, and the next green head
    ;; is read against the head the row names.
    :observe_failing
    {:from #{:failing} :to :failing
     :guards [the-mirror-writes-this-row]
     :handler observe-the-pull-request
     :input [:map
             [:title {:optional true :x-display {:raw true}}
              [:maybe [:string {:max 400}]]]
             [:head_sha {:optional true} [:maybe [:string {:max 64}]]]
             [:draft {:optional true} [:maybe :boolean]]
             [:mergeable {:optional true}
              [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]
             [:files_changed {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_added {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_removed {:optional true} [:maybe [:int {:min 0}]]]
             [:touched_paths {:optional true}
              [:maybe [:vector [:string {:max 400}]]]]
             [:labels {:optional true} [:maybe [:vector [:string {:max 100}]]]]
             [:review_state {:optional true}
              [:maybe [:enum "pending" "approved" "changes_requested"
                       "commented"]]]
             [:rerun_head {:optional true} [:maybe [:string {:max 64}]]]
             [:rerun_note {:optional true} [:maybe [:string {:max 240}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Observe" :order 14
               :description "The mirror writes what GitHub says about this pull request now"}}

    :merge
    {:from #{:open :submitted :failing :stuck} :to :merged
     :guards [the-mirror-writes-this-row]
     ;; the merge COMPLETES the task this change was born from
     ;; (waymark-fp62.6.3.14). The handler carries the blast radius,
     ;; because `:touches` cannot name a kind factory10 boots without
     ;; — see `complete-the-task-it-was-born-from`.
     :handler complete-the-task-it-was-born-from
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub merged this pull request. The row follows GitHub, so there is no way back: a merged pull request is not reopened. The ask this change was born from — a task, or a ticket that has not ended — is completed with it, and another change built for that ask on the same branch is closed."}
     :display {:label "Merged" :order 2
               :description "GitHub merged the pull request"}}

    :close
    {:from #{:open :submitted :failing :stuck} :to :closed
     :guards [the-mirror-writes-this-row]
     :handler send-the-ticket-back
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub closed this pull request without merging it, and a ticket this change was built for goes back to the queue. The way back is GitHub's own reopen, which the mirror follows with its reopen door."}
     :display {:label "Closed" :order 3
               :description "GitHub closed the pull request and merged nothing"}}

    ;; the ending of a change GitHub never saw (ticket be2c2c16): the
    ;; mirror's `close` follows a pull request, so a row with no number
    ;; had no door to its end. A person's, held when a model asks, and it
    ;; leaves the ticket where it stands — see `:deviations`.
    :close_without_pr
    {:from #{:open :submitted} :to :closed
     :input [:map
             [:why {:examples ["A duplicate: its ticket merged under another row."]
                    :x-display
                    {:widget "prose"
                     :label "Why it ends"
                     :help "One sentence for the next reader: why this change is let go. It rides the log beside this move."}}
              [:string {:min 1 :max 480}]]]
     :edit {:draft {:shared true :live true}}
     :guards [no-pull-request-to-close
              only-a-person-closes-a-change]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The change is let go and the house stops working it; its ticket is not touched. Nothing here brings it back to open."}
     :display {:label "Close" :style :danger :order 30
               :description "Let go of a change that never opened a pull request — its ticket stays where it is"}}

    ;; the merge's close of a DUPLICATE (ticket 3ec37f66): another
    ;; change born from the same ask on the same branch merged, so this
    ;; one is let go with the merged one's address on it. Not `close`:
    ;; that door is GitHub's word and sends the ticket back, and this
    ;; one's ticket has just ended.
    :supersede
    {:from #{:open :submitted :failing :stuck} :to :closed
     :guards [the-mirror-writes-this-row]
     :handler write-what-superseded-it
     :input [:map
             [:superseded_by {:x-display {:hidden true}}
              [:string {:min 1 :max 500}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Another change built for the same ask merged, so this duplicate is closed and names the one that merged. The way back is GitHub's own reopen, which the mirror follows with its reopen door."}
     :display {:label "Superseded" :order 18
               :description "Another change for the same ask merged, and this one is closed"}}

    :reopen
    {:from #{:closed} :to :open
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub reopened this pull request. The row moves with it, and the record of the close stands beside it."}
     :display {:label "Reopened" :order 4
               :description "GitHub reopened the pull request"}}

    ;; ── THE ADOPTION (bead waymark-fp62.6.3.10) ────────────────────
    ;; A row a seat's sit minted for an ask carries the ask's own id
    ;; and no number. The push opens the pull request, and the next
    ;; source pass writes GitHub's identity onto the row that is
    ;; already here. `observe` cannot: its input is the FACTS that
    ;; move under a row, and the id a row is known by is not one of
    ;; them. So the identity has a door of its own, hidden behind the
    ;; same wall, and `change_id` is `:unique` — the write lands or
    ;; the whole transaction does, and a second pull request can
    ;; never take a row that is already spoken for.
    :adopt
    {:from #{:open} :to :open
     :guards [the-mirror-writes-this-row]
     :handler adopt-the-pull-request
     :input [:map
             [:change_id {:x-display {:raw true}}
              [:string {:min 1 :max 250}]]
             [:number {:optional true} [:maybe [:int {:min 1}]]]
             ;; :hidden, as the field is on the document — a url has
             ;; no shape a form could draw, and the origin LINK is the
             ;; affordance (the long-text battery asks for one of the
             ;; three)
             [:url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Adopt" :order 11
               :description "The mirror writes the pull request GitHub opened for this change onto the row that asked for it"}}

    ;; the same write, one state over — a change a seat has SUBMITTED
    ;; is exactly the change whose push opened the pull request, so
    ;; this is the state the adoption lands in almost every time. A
    ;; v10 action declares one `:to`, so the self-loop is spelled
    ;; twice (the `observe`/`observe_submitted` precedent, recorded
    ;; in `:deviations`).
    :adopt_submitted
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler adopt-the-pull-request
     :input [:map
             [:change_id {:x-display {:raw true}}
              [:string {:min 1 :max 250}]]
             [:number {:optional true} [:maybe [:int {:min 1}]]]
             ;; :hidden, as the field is on the document — a url has
             ;; no shape a form could draw, and the origin LINK is the
             ;; affordance (the long-text battery asks for one of the
             ;; three)
             [:url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Adopt" :order 12
               :description "The mirror writes the pull request GitHub opened for this change onto the row that asked for it"}}

    ;; the same write on a `failing` or `stuck` row (ticket 3c59c688).
    ;; A pull request the house itself opened is the house row's
    ;; whatever state that row stands in, so it is adopted and never
    ;; minted beside it. The adoption records the identity and LEAVES
    ;; the state: a stuck row stays stuck, because a person still puts
    ;; it back to work — and with the number on it now, `unstick_submitted`
    ;; is the door that does, so the merge pass reads it.
    :adopt_failing
    {:from #{:failing} :to :failing
     :guards [the-mirror-writes-this-row]
     :handler adopt-the-pull-request
     :input [:map
             [:change_id {:x-display {:raw true}}
              [:string {:min 1 :max 250}]]
             [:number {:optional true} [:maybe [:int {:min 1}]]]
             [:url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Adopt" :order 20
               :description "The mirror writes the pull request GitHub opened for this change onto the row that asked for it"}}

    :adopt_stuck
    {:from #{:stuck} :to :stuck
     :guards [the-mirror-writes-this-row]
     :handler adopt-the-pull-request
     :input [:map
             [:change_id {:x-display {:raw true}}
              [:string {:min 1 :max 250}]]
             [:number {:optional true} [:maybe [:int {:min 1}]]]
             [:url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Adopt" :order 21
               :description "The mirror writes the pull request GitHub opened for this change onto the row that asked for it"}}

    ;; THE FOLD (ticket 3c59c688): a row the forge minted for a pull
    ;; request the house row on the same branch should have adopted.
    ;; It is closed, gives up the pull request's id and names the house
    ;; row, and the house row adopts the id in the same pass.
    :fold
    {:from #{:open :submitted :failing :stuck} :to :closed
     :guards [the-mirror-writes-this-row]
     :handler fold-into-the-house-row
     :input [:map
             [:folded_into {:x-display {:hidden true}}
              [:string {:min 1 :max 500}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This row was a second row for a pull request the house's own change opened, so it is closed and names that change, which holds the pull request from now on."}
     :display {:label "Folded" :order 22
               :description "A duplicate row for the house's own pull request, folded into the house's change"}}

    ;; THE FORGE PASS'S NOTE (ticket 58e706d6). A submitted change whose
    ;; landing opened a pull request that no row adopted is skipped by
    ;; the house's merge and by the merge ask, silently. The pass writes
    ;; when it first saw it, and after the window a note a person reads.
    ;; Hidden, and the mirror's hand alone.
    :note_adoption
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler note-the-adoption
     :input [:map
             [:landed_at {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 64}]]]
             [:adoption_note {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Adoption noted" :order 19
               :description "The mirror says a landed pull request is waiting for its row"}}

    ;; ── THE BRANCH, MINTED AGAIN (bead waymark-fp62.6.3.11) ───────
    ;; A seat-born row writes its head branch at BIRTH, from the
    ;; policy's pattern. A person who restates the pattern — because
    ;; the old one shadowed a branch the repository already has, and
    ;; git holds neither — does not reach a row that is already here,
    ;; and the next sitting opens the same bad branch. So the sit
    ;; mints the branch again and writes it through this door. It is
    ;; the mirror's, hidden like the adoptions: a change that was
    ;; pushed once keeps its branch, because the forge holds it, and
    ;; the sit is what judges that — no round, no number, and a
    ;; `change_id` that is still the walk row's.
    :rebranch
    {:from #{:open :stuck} :to :open
     :guards [the-mirror-writes-this-row]
     :handler observe-the-pull-request
     :input [:map
             [:head_branch {:x-display {:raw true}}
              [:string {:min 1 :max 200}]]
             ;; THE REPOSITORY MOVES WITH IT (ticket 1ebcd19f). A walk
             ;; row restated to another repository keeps its
             ;; `change_id`, so the sit writes the new repository and
             ;; its policy's base here too, on a change that never
             ;; opened: the old repository holds nothing of it.
             [:repository {:optional true :x-display {:raw true}}
              [:string {:min 1 :max 140}]]
             [:base_branch {:optional true :x-display {:raw true}}
              [:maybe [:string {:max 200}]]]]
     ;; :edit-shape — the input restates a field of the document, as
     ;; the adoptions do; a prefill for a hidden door is scaffolding
     ;; for nobody.
     :waives #{:edit-shape}
     ;; :one-way, and not a confirm: nobody taps this door. The old
     ;; branch was never pushed, so there is nothing on the forge to
     ;; lose — and a change that HAS been pushed never reaches this
     ;; door at all.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The house works this change on the new branch from now. The old branch was never pushed, so nothing is lost."}
     :display {:label "Rebranch" :order 13
               :description "The house mints this change's branch again from the repository policy's pattern"}}

    ;; ── THE CHECKS' VERDICT (ticket d1742908) ──────────────────────────
    ;; The forge pass reads the checks on a submitted change's head
    ;; against the repository policy and walks one of these three.
    ;; They are the mirror's and hidden: no seat and no person says a
    ;; change is red. A seat's way out of `failing` is its next
    ;; `submit`, or `stall`.
    :fail
    {:from #{:submitted} :to :failing
     :guards [the-mirror-writes-this-row]
     :handler fail-the-change
     :input [:map
             [:failing_checks [:vector {:min 1} [:string {:max 200}]]]
             [:conflicts {:optional true}
              [:maybe [:vector [:string {:max 400}]]]]
             [:landing_error {:optional true
                              :x-display {:widget "prose"
                                          :label "Why the push did not land"}}
              [:maybe [:string {:max 4000}]]]
             ;; a merge train's red (ticket 6566d32f): the head it
             ;; judged, and its branch and run url
             [:train_red_head {:optional true} [:maybe [:string {:max 64}]]]
             [:train_red {:optional true
                          :x-display {:widget "prose"
                                      :label "Why a merge train found it red"}}
              [:maybe [:string {:max 1000}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The required checks finished red on this head, and the ticket this change was built for goes back to the queue, so the seat that wrote it walks it again. The way back is a green head, which the house reads on its next pass, or the seat's next submit."}
     :display {:label "Failing" :order 15
               :description "The required checks went red on the head the seat pushed"}}

    :recover
    {:from #{:failing} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler clear-the-failing-checks
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The required checks are green on the head the row names now, so the change is submitted again and the house merges it as it would any other."}
     :display {:label "Green again" :order 16
               :description "The required checks went green on a later head"}}

    ;; the ROUND CEILING, read by the engine. A red head on the last
    ;; round the policy gives is not sent back to the seat, whose next
    ;; submit `under-the-round-ceiling` would refuse: it stops here,
    ;; and the red names ride the log as the why a person reads.
    :stick
    {:from #{:submitted} :to :stuck
     :guards [the-mirror-writes-this-row]
     :handler write-the-failing-checks
     :input [:map
             [:why {:x-display {:widget "prose"
                                :label "What went red"
                                :help "The checks that finished red on the last round the policy gives."}}
              [:string {:min 1 :max 480}]]
             [:failing_checks [:vector {:min 1} [:string {:max 200}]]]
             [:conflicts {:optional true}
              [:maybe [:vector [:string {:max 400}]]]]
             [:landing_error {:optional true
                              :x-display {:widget "prose"
                                          :label "Why the push did not land"}}
              [:maybe [:string {:max 4000}]]]]
     ;; :large-effort — NO draft here, unlike `stall`. Only the engine
     ;; walks this door and nobody composes the why in a box; and an
     ;; `:edit` implies the version fence (waymark10.resource), which
     ;; refuses the forge pass's own unfenced call.
     :waives #{:edit-shape :large-effort}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The change spent every round the policy gives and its checks are still red, so the house stops working it, and the ticket it was built for stays in review. The way back is a person's own door, or their delegate's: unstick_submitted for a change with a pull request, which puts it under review again, or unstick for one with none, which puts that ticket in the queue again. Either starts the rounds from zero; grooming does not serve a ticket in review."}
     :display {:label "Stuck on red" :order 17
               :description "The checks went red on the last round, and a person reads it next"}}

    ;; ── THE BENCH'S OWN DOORS (waymark-fp62.6.3.2) ─────────────────
    ;; These four are NOT the mirror's: a seat under a grant walks
    ;; them, and so does a person. Each one reaches the rig with the
    ;; engine's hand; the model holds the four reading and editing
    ;; powers and never these.

    ;; from `failing` too (ticket d1742908): the seat's next round on a
    ;; red change is the way back to `submitted`
    :submit
    {:from #{:open :submitted :failing} :to :submitted
     :input [:map
             [:why {:examples ["Fix the fixture's table list: the shard made no waymark10_test database, so every test10 case failed on connect."]
                    :x-display
                    {:widget "prose"
                     :label "What this round does"
                     :help "One sentence for the commit message: what you changed and why. It is what a person reads in the log, and what the review reads first."}}
              [:string {:min 1 :max 480}]]]
     ;; the sentence is composed, so a mis-click must not lose it
     ;; (the seat's own `restate` spelling)
     :edit {:draft {:shared true :live true}}
     :guards [the-repository-has-a-policy
              the-branch-matches-the-policy
              the-branch-is-not-the-base
              under-the-round-ceiling]
     :handler submit-the-change
     ;; NO `:touches` (bead waymark-fp62.6.3.4). The round ends here
     ;; on this change; the one other row it moves is the ticket it
     ;; was born from, out for review (ticket 2e869934), and that
     ;; rides the `:one-way` sentence as the merge's does. The
     ;; harness closes the sitting with its Stop hook (spec-seat.md
     ;; R-12.17), and that report carries the bill of the round.
     :safety {:idempotent false :reversible false :confirm false
              :one-way "The commit is written and the branch is pushed, so this round is on the record at GitHub, and the ticket this change was built for is out for review until the change merges or comes back red. The way forward is another round on the same change, not a way back."}
     :display {:label "Submit" :style :primary :order 5
               :description "Commit the worktree with your sentence and push the branch — this ends the round"}}

    :discard
    {:from #{:open} :to :open
     :input [:map
             [:drop_branch {:default false
                            :x-display
                            {:label "Drop the branch as well"
                             :help "True removes the worktree and the branch, not only the edits. A person's act: a model's discard keeps the branch."}}
              [:maybe :boolean]]]
     :guards [only-a-person-drops-the-branch]
     :handler discard-the-worktree
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The edits in the worktree are gone and nothing here brings them back. The change itself stands, and the next round starts from the branch head."}
     :display {:label "Discard" :style :danger :order 6
               :description "Put the worktree back to the branch head and lose every edit in it"}}

    ;; the same escape hatch, one state over — see the ns docstring
    ;; and `:deviations`
    :discard_submitted
    {:from #{:submitted} :to :submitted
     :input [:map
             [:drop_branch {:default false
                            :x-display
                            {:label "Drop the branch as well"
                             :help "True removes the worktree and the branch, not only the edits. A person's act: a model's discard keeps the branch."}}
              [:maybe :boolean]]]
     :guards [only-a-person-drops-the-branch]
     :handler discard-the-worktree
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The edits in the worktree are gone and nothing here brings them back. The rounds already pushed stand."}
     :display {:label "Discard" :style :danger :order 7
               :description "Put the worktree back to the branch head and lose every edit in it"}}

    :stall
    {:from #{:open :submitted :failing} :to :stuck
     :input [:map
             [:why {:examples ["The same test fails on the base commit, so this change is not the cause and I cannot fix it here."]
                    :x-display
                    {:widget "prose"
                     :label "What is wrong"
                     :help "One sentence for the person who reads this next: what you tried, and what stopped you. It rides the log beside this move."}}
              [:string {:min 1 :max 480}]]]
     :edit {:draft {:shared true :live true}}
     ;; a stalled change is not under review, and its ticket could not
     ;; be built as written: it goes back to draft (ticket 6bdaf6fe)
     :handler shelve-the-ticket
     ;; NOT :reversible — `unstick` brings a stuck change back to
     ;; `open` and to no other state, so a stall from `submitted` has
     ;; no transition back to where it started (checks/check-reversible
     ;; asks for one for each :from). The way back is real and it is a
     ;; PERSON'S (or their delegate's), which is what the sentence says.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The house stops working this change and waits, and the ticket it was built for goes to draft. The way back is a person's groom of that ticket, or their delegate's, which puts the change back to work at the groom itself with its rounds at zero, or back under review there when it has a pull request; unstick, or unstick_submitted for a change with a pull request, puts the change alone back to work."}
     :display {:label "Stuck" :order 8
               :description "Say what stopped you and stop working this change — a person reads it next"}}

    :unstick
    {:from #{:stuck} :to :open
     :guards [a-person-or-their-delegate-unsticks
              the-change-has-no-pull-request]
     :handler unstick-the-change
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Back to work" :style :primary :order 1
               :description "Put this change back in the queue — the rounds start again"}}

    ;; THE TICKET'S WAY BACK FOR ITS CHANGE (ticket 9ace68fb). A stall
    ;; sends the ticket to draft, and a block takes it out of the queue;
    ;; the groom, unblock or resume that puts it back in `open` puts a
    ;; stuck change with no pull request back to work with it, or the
    ;; sit would leave the ticket out beside its stuck change forever.
    ;; A change WITH a pull request takes `rework_submitted` below.
    :rework
    {:from #{:stuck} :to :open
     :guards [only-its-ticket-reworks-it
              the-change-has-no-pull-request]
     :handler rework-the-change
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Reworked" :order 21
               :description "Its ticket was groomed, unblocked or resumed — the change goes back to work"}}

    ;; and for a change WITH a pull request (ticket 4363c63b): it lands
    ;; where `unstick_submitted` lands it, so the forge pass reads its
    ;; head — red or conflicted goes `failing` and back to the seat,
    ;; green enters the merge line. A pull request that closed or
    ;; merged left `stuck` with the mirror, so this door never meets it.
    :rework_submitted
    {:from #{:stuck} :to :submitted
     :guards [only-its-ticket-reworks-it
              the-change-has-a-pull-request]
     :handler rework-the-pull-request
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Its ticket was groomed, unblocked or resumed, so the change is under review again and the house reads its checks on the next pass: green merges it, red or conflicted sends it back to the seat. The rounds start from zero."}
     :display {:label "Reworked under review" :order 22
               :description "Its ticket was groomed, unblocked or resumed — the pull request goes back under review"}}

    ;; the same door for a change with a pull request (ticket 6bdaf6fe):
    ;; it lands where the pull request is, so the forge pass reads its
    ;; head and the merge pass merges it green — see `:deviations`
    :unstick_submitted
    {:from #{:stuck} :to :submitted
     :guards [a-person-or-their-delegate-unsticks
              the-change-has-a-pull-request]
     :handler unstick-the-pull-request
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The change is under review again, and the house reads its checks on the next pass: green merges it, red sends it back to the seat. The rounds start from zero."}
     :display {:label "Back to review" :style :primary :order 2
               :description "Put this pull request back under review — the house merges it green"}}}
   :links [{:rel "origin" :href "{data.url}" :external true
            :summary "The pull request, at GitHub"}]
   :deviations
   ["`unstick` is spelled twice, `unstick` and `unstick_submitted`, because it lands in two states (ticket 6bdaf6fe): a change with no pull request goes back to `open` for its next round, and a change with one goes back to `submitted`, where the forge and merge passes read it. A v10 action declares one `:to`, so each door carries the guard that says which change it is for. `rework` and `rework_submitted`, the ticket's own walk to the same two landings, are spelled twice for the same reason (ticket 4363c63b)."
    "A self-loop that serves several states is spelled once for each: `observe` with `observe_submitted` and `observe_failing`, `discard` with `discard_submitted`, and `adopt` with `adopt_submitted`, `adopt_failing` and `adopt_stuck`. A v10 action declares one `:to`, so one door cannot rest a row where it found it in two different states. The precedent is server/definitions.clj's `measure`/`measure_pilot`, recorded there for the same reason."
    "The round ceiling REFUSES and names the way to `stuck`; it does not move the row itself. Bead waymark-fp62.6.3.2's R-5 reads \"the row moves to stuck and the door names it\", and one transition cannot do both: a handler's refusal rolls back its own transaction, and an action's `:to` is one state. So `under-the-round-ceiling` refuses with `:remedies [:change/stall]`, and `stall` — a real door, with the seat's own sentence on it — makes the move."
    "The clean-worktree check is the HANDLER's, not a guard's. The only honest reading of \"is there anything to submit\" is the rig's own `status`, and a guard that reached a wire would judge differently on a day Gate was dark. The handler asks, and refuses with a 409 that carries its remedy, so the refusal counts on the sitting exactly as a guard's does."
    "A move into `failing` is counted against the round ceiling and does not add a round of its own (ticket d1742908). `submit` already adds one for each head a seat pushes, and each red head is one of those rounds; adding a second for the red would spend the ceiling twice as fast. So the forge pass reads `rounds` against the policy's ceiling at the red: under it the change goes to `failing`, at it the change goes to `stuck` through `stick`, with the red check names as its why."
    "`close` is spelled twice, `close` and `close_without_pr` (ticket be2c2c16). The mirror's `close` follows GitHub's and sends the ticket back to the queue; a change GitHub never saw has no pull request to follow, and its ending is a person's judgment that leaves the ticket where it stands, so it is its own door with its own guards."]
   :scenarios [a-model-does-not-move-a-pull-request
               the-source-moves-the-pull-request
               a-model-does-not-drop-the-branch
               the-person-drops-the-branch
               a-model-discards-its-own-edits
               a-model-does-not-unstick-itself
               the-person-puts-a-stuck-change-back-to-work
               a-change-with-a-pull-request-is-not-put-back-in-open
               a-change-with-no-pull-request-goes-back-to-open
               a-seat-does-not-say-its-own-change-is-red
               a-model-does-not-close-a-change-alone
               the-person-closes-a-change-with-no-pull-request
               a-change-with-a-pull-request-is-closed-at-github]})
