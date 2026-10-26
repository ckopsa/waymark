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

  A SELF-LOOP IS ONE DOOR (ticket bc6a8732). `:to :same` keeps the
  state a door found, so the mirror's refresh, its adoption, the
  seat's discard and the ticket's domain move each serve every state
  they are for as one door. The copies they once were
  (`observe_submitted`, `discard_submitted` ...) answer for one
  release through `:aliases`, and the log names the alias.

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
            [factory10.mirror :refer [source-principal]]
            [factory10.resources.ticket :as ticket]
            [factorykit.ask :as ask]
            [factorykit.forge :as forge]
            [factorykit.change :as kit :refer [has-a-pull-request?
                                               the-change-has-a-pull-request
                                               the-change-has-no-pull-request
                                               the-mirror-writes-this-row
                                               unstalled]]
            [waymark10.dsl :refer [defguard defguardfn defhandler defresource
                                   defscenario refuse]]
            [waymark10.holds :as holds]
            [waymark10.server.problems :as p]
            [waymark10.types :as t]
            [waymark10.verdict :as verdict]))

(set! *warn-on-reflection* true)

;; ── what a pull request writes back onto the row ────────────────────

;; The two the source's own doors write with are the kit's
;; (`factorykit.change`, ticket 47e2c880). They keep their names here
;; for the readers of this namespace.

(def observe-the-pull-request kit/observe-the-pull-request)

(def adopt-the-pull-request kit/adopt-the-pull-request)

(defhandler record-the-update [row inp _ctx]
  ;; THE HOUSE'S UPDATE ASK (ticket a1a133ca): the head the rig's
  ;; `update_branch` was asked at, and, when the rig said the branch
  ;; already held its base, that head as `current_head`.
  (cond-> (assoc-in row [:data :updated_head] (str (:updated_head inp)))
    (contains? inp :current_head)
    (assoc-in [:data :current_head] (some-> (:current_head inp) str not-empty))))

(defhandler record-the-rerun [row inp _ctx]
  ;; THE HOUSE'S RE-RUN ASK (ticket a1a133ca): the head whose interrupted
  ;; checks the rig's `rerun` started again, once.
  (assoc-in row [:data :rerun_head] (str (:rerun_head inp))))

(defhandler note-the-adoption [row inp _ctx]
  ;; THE FORGE PASS'S OWN RECORD (ticket 58e706d6): when it first saw
  ;; the pull request the bench's landing opened, and, once the window
  ;; has passed with no adoption, the note that says so. An input with
  ;; neither clears both.
  (update row :data assoc
          :unadopted_since (some-> (:unadopted_since inp) str not-empty)
          :adoption_note (some-> (:adoption_note inp) str not-empty)))

(defhandler write-the-merge-ask [row inp _ctx]
  ;; THE HOUSE'S MERGE ASK (ticket 1ed59295, spec R-C.15): the head the
  ;; house asked the rig to merge at and, when the rig parked it, what it
  ;; said and the refusal's name. An ask with no refusal clears both.
  (update row :data assoc
          :merge_asked_head (some-> (:merge_asked_head inp) str not-empty)
          :merge_refused (some-> (:merge_refused inp) str not-empty)
          :merge_refused_as (some-> (:merge_refused_as inp) str not-empty)))

(defhandler write-the-merge-wait [row inp _ctx]
  ;; THE PERSON'S MERGE WAIT (ticket 3806fb22, spec R-C.19): the head the
  ;; ask pass is timing and when its wait started, so a restart keeps it.
  (update row :data assoc
          :merge_wait_head (some-> (:merge_wait_head inp) str not-empty)
          :merge_wait_since (some-> (:merge_wait_since inp) str not-empty)))

(defhandler clear-the-merge-wait [row inp _ctx]
  ;; the wait stops: a change no longer clean, green or submitted names no
  ;; head; a held one keeps its head with no time, so the pass that sees
  ;; it free starts the wait again from then
  (update row :data assoc
          :merge_wait_head (some-> (:merge_wait_head inp) str not-empty)
          :merge_wait_since nil))

(defhandler write-the-train-done [row inp _ctx]
  ;; A MERGE TRAIN'S DONE MARK (ticket 1ae1afbc): the head a train that
  ;; sent its lane one at a time carried this change at, and why. The
  ;; lane builds no train while the mark names the change's head.
  (update row :data assoc
          :train_done_head (some-> (:train_done_head inp) str not-empty)
          :train_done (some-> (:train_done inp) str not-empty)))

(defhandler write-the-hold [row inp _ctx]
  ;; THE MERGE LINE'S HOLD (ticket e6729daa, spec R-C.14): what holds the
  ;; change and why, and the lane's `held_reason` when the hold names it
  ;; or says to clear it.
  (update row :data
          #(cond-> (assoc % :held_as (some-> (:held_as inp) str not-empty)
                          :held_why (some-> (:held_why inp) str not-empty))
             (some-> (:held_reason inp) str not-empty)
             (assoc :held_reason (str (:held_reason inp)))
             (:clear_held_reason inp) (assoc :held_reason nil))))

(defhandler write-the-release [row inp _ctx]
  ;; nothing holds the change now (ticket e6729daa)
  (update row :data
          #(cond-> (assoc % :held_as nil :held_why nil)
             (:clear_held_reason inp) (assoc :held_reason nil))))

(defhandler write-the-line [row inp _ctx]
  ;; the change's place in its merge line and why (ticket e6729daa); an
  ;; input that leaves one out clears it
  (update row :data
          (fn [d]
            (reduce-kv (fn [d k v] (if (nil? v) (dissoc d k) (assoc d k v)))
                       d
                       {:line_place (:line_place inp)
                        :line_why (some-> (:line_why inp) str not-empty)
                        :line_reason (some-> (:line_reason inp) str not-empty)}))))

;; ── the merge finishes the task the change was born from ────────────

(def ^:private born-kinds
  "What `born_from` may read as its kind, and how each one is
  finished. The merge opens these doors and no other: a kind this
  module does not know is a door this module must not walk.

  A `task` (workqueue10) keeps its lifecycle in `status`, and its
  `complete` takes nothing. A `ticket` is not here: the merge ends it
  through its own `land`, walked by this kind's `merge` subscription
  (`subscriptions`, ticket 35c1e826), not by this handler."
  {"task" {:kind :task
           :action :complete
           :finished? (fn [row] (= "done" (str (get-in row [:data :status]))))
           :input (fn [_change] nil)}})

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

(def ^:private held-fields
  [:held_head :held_since :held_reason :held_again :held_threw])

(defn- without-the-wait
  "The row without what the forge pass said of its held head. That pass
  clears these only on a change it still reads, at submitted or failing,
  so a door that ends the change drops them itself (ticket 1a72eea8)."
  [row]
  (reduce (fn [row k]
            (cond-> row
              (some? (get-in row [:data k])) (assoc-in [:data k] nil)))
          row held-fields))

(defhandler say-why-it-was-let-go [row inp _ctx]
  ;; the kit's `close_without_pr` (ticket 48f9605b). The wait's fields
  ;; go, and the row says what closed it: the kit's sweep closes through
  ;; this door, and a swept change names its ticket's ending on the row
  ;; as it did when the sweep walked `supersede`. `reopen` clears it.
  (-> (without-the-wait row)
      (assoc-in [:data :superseded_by] (some-> (:why inp) str not-empty))))

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
  (without-the-wait row))

;; ── the ticket follows its change (tickets 2e869934 and 35c1e826) ───
;;
;; Each move of a change born from a ticket walks the ticket's own door
;; once the change's write commits: one managed subscription per move,
;; owned by the mirror, as pull_request's `follows` is one kind over.
;; None of this kind's handlers moves the ticket. `id_from born_from`
;; names the ticket, `ticket:` stripped, and names no row for a change
;; born from another kind or from nothing, which the walk skips. A
;; ticket standing elsewhere refuses `wrong_state`, which the walk
;; records as a skip; any other refusal is kept on the subscription
;; (`last_refusal`). The ticket's doors open for these walks and no hand
;; (`factorykit.ask/only-its-change-moves-it`).

(defn- ticket-follows
  "One subscription: this kind's `action` walks the ticket's `door`,
  given `input` (the door's field → a field of the change)."
  [action door input]
  (cond-> {:name (str "ticket-follows-" action)
           :kinds ["change"] :actions [action]
           :receiver {:type "door" :kind "ticket" :door door
                      :id_from "born_from"}
           :owner (:id source-principal)}
    input (assoc-in [:receiver :input] input)))

(def ^:private subscriptions
  "Out for review on a submit, an unstuck pull request or a green head;
  back to the queue on a close, an unstick or a red head; to draft on a
  stall, with its sentence; and ended, or written on, by the merge,
  with the pull request's address."
  [(ticket-follows "submit" "review" nil)
   (ticket-follows "unstick_submitted" "review" nil)
   (ticket-follows "recover" "review" nil)
   (ticket-follows "close" "return" nil)
   (ticket-follows "unstick" "return" nil)
   (ticket-follows "fail" "return" nil)
   (assoc (ticket-follows "checks_red" "return" nil) :to ["failing"])
   (ticket-follows "stall" "shelve" {:shelved_because "stall_reason"})
   (ticket-follows "merge" "land" {:merged_change "url"
                                   :merged_change_id "change_id"})])

(defhandler release-the-lane [row inp _ctx]
  ;; the mayor's word that this edit is one the plan should take (ticket
  ;; 59cd3e78): while it stands, the merge pass holds the change for no
  ;; org plan's lane
  (assoc-in row [:data :lane_released] (str (:why inp))))

(defhandler write-what-superseded-it [row inp _ctx]
  ;; The pull request that merged in this one's place rides on the row,
  ;; so a reader of a closed duplicate sees which change did the work
  ;; (ticket 3ec37f66). Its ticket already ended with that merge, so
  ;; nothing is sent back.
  (assoc-in (without-the-wait row)
            [:data :superseded_by] (str (:superseded_by inp))))

(defhandler fold-into-the-house-row [row inp _ctx]
  ;; A row the forge minted beside a house row on the same branch
  ;; (ticket 3c59c688) gives the pull request's id up, so the house
  ;; row can adopt it — `change_id` is `:unique` — and names the house
  ;; row that holds the work now. Nothing is sent back: the work goes
  ;; on, on the other row.
  (update row :data assoc
          :change_id (str "folded:" (get-in row [:data :change_id]))
          :superseded_by (str (:folded_into inp))))

(defhandler drop-the-wait [row _inp _ctx]
  ;; A review that ended without a merge — a close. Its `close`
  ;; subscription puts the ticket in the queue again, where the seat that
  ;; wrote the change wakes on its `return`.
  (without-the-wait row))

(defhandler note-the-stall [row inp ctx]
  ;; A STALL IS A SEAT SAYING IT CANNOT BUILD THE TICKET AS WRITTEN, so
  ;; the ticket goes back to draft and not to the queue (ticket
  ;; 6bdaf6fe), through its `stall` subscription.
  ;;
  ;; THE SENTENCE RIDES ON BOTH ROWS (ticket b6c8ea04): the row keeps it
  ;; as `stall_reason`, which the subscription hands the ticket's
  ;; `shelve` as `shelved_because`. The row's own part is the kit's
  ;; (`kit/stalled`, ticket eb500c47).
  (kit/stalled row inp ctx))

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

(defn- ceiling-remedy
  "What an over-ceiling refusal tells the seat, naming the ceiling this
  change's paths took when the door knows it (ticket bacb84f2)."
  [ceiling]
  (str "Make the change smaller"
       (when ceiling
         (str " than " ceiling " changed lines, the ceiling this "
              "change's lane takes"))
       ": submit the part that stands on its own, or ask a person to "
       "raise max_lines (or lane_max_lines, for a change in one lane) "
       "on the repository policy."))

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

(defn- base-only-round?
  "True when the bench says all that is new since the last push is
  merges of the base (ticket 50f09817): one merge or more, no other
  commit and no dirty path. That round is upkeep, not a fix cycle, so
  the round ceiling does not stop it and it does not count. A status
  with no `since_push`, or no status at all, is a round like any other."
  [status]
  (let [since (:since_push status)
        n (fn [v] (cond (sequential? v) (count v)
                        (number? v) (long v)
                        :else -1))]
    (boolean
     (and (map? since)
          (pos? (n (:merges_of_base since)))
          (zero? (n (:other_commits since)))
          (zero? (n (:dirty_paths since)))))))

(defn- rig-refusal!
  "The rig's own refusal, said again as this door's.

  THE NAMES THIS DOOR KNOWS carry a remedy of their own; every other
  name carries the general one, so a refusal nobody anticipated still
  tells the seat what to do next. The rig's `remedy` sentence rides
  beside it when it sent one — it is the rig that knows what it
  refused."
  [what answer & [row ceiling]]
  (let [named (bench/refused answer)
        reason (some-> (:reason answer) str not-empty)
        theirs (some-> (:remedy answer) str not-empty)]
    (bench/refuse!
     (str "The bench refused to " what ": " named
          (when reason (str " — " reason)) ".")
     (cond-> (case named
               "push_rejected" [pull-remedy]
               "over_ceiling" [(ceiling-remedy ceiling)]
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
      (let [;; the lane's own ceiling when every path sits in one
            ;; (ticket bacb84f2), and max_lines otherwise
            ceiling (bench/max-lines-for policy (:paths status))
            base-only (base-only-round? status)
            answer (bench/ask ctx :submit
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
                                       :max_lines ceiling}
                                title (assoc :title title)))]
        (cond
          (nil? answer) (bench/refuse! bench/dark-detail [bench/dark-remedy])
          (bench/refused answer) (rig-refusal! "submit" answer row ceiling)
          :else
          (do
            ;; the round is out: the `submit` subscription takes the
            ;; ticket out of the walk until its change comes back red or
            ;; merges (ticket 2e869934)
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
                             ;; a round that only merges the base does
                             ;; not count (ticket 50f09817)
                             :rounds (cond-> (long (or (get-in row [:data :rounds])
                                                       0))
                                       (not base-only) inc)
                             :last_round_kind (when base-only "base_merge")}
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

(defhandler unstick-the-change [row _inp _ctx]
  ;; A PERSON LOOKED AT IT. The rounds go back to zero, because the
  ;; ceiling counts the rounds nobody has read yet; a change a person
  ;; has read and put back to work starts its count again. A ticket
  ;; the round ceiling left in review goes back to the queue with it,
  ;; through the `unstick` subscription (ticket 2e869934).
  (-> row unstalled (assoc-in [:data :rounds] 0)))

(defhandler rework-the-change [row _inp _ctx]
  ;; `unstick`'s count, walked by the TICKET (ticket 9ace68fb): a groom,
  ;; an unblock or a resume is a person's reading of the ask, so the
  ;; rounds start again. The ticket is already on its way to `open`,
  ;; so nothing here moves it.
  (-> row unstalled (assoc-in [:data :rounds] 0)))

(defhandler reopen-the-change [row _inp _ctx]
  ;; A CHANGE GITHUB NEVER SAW STARTS AGAIN (ticket ab6af67f). The sit
  ;; walks this door for a closed change with no pull request whose
  ;; ticket is in the queue again: the rounds start from zero and the
  ;; row no longer says what closed it, as the log does. A change WITH
  ;; a pull request is GitHub's reopen, and the row stands as it was.
  ;; The row's part is the kit's `reopened`; `superseded_by` is this
  ;; factory's own field.
  (cond-> (kit/reopened row)
    (not (has-a-pull-request? row)) (assoc-in [:data :superseded_by] nil)))

(defn- back-under-review
  "A stuck change put back under review: the rounds start from zero,
  and the forge computes the red names again for the head it reads next."
  [row]
  (update (unstalled row) :data assoc
          :rounds 0
          :failing_checks nil
          :conflicts nil
          :landing_error nil
          :train_red_head nil
          :train_red nil))

(defhandler unstick-the-pull-request [row _inp _ctx]
  ;; A STUCK CHANGE WITH A PULL REQUEST GOES BACK WHERE THE PULL REQUEST
  ;; IS (ticket 6bdaf6fe). An `open` change is read by neither the
  ;; failing pass nor the merge pass, and the seat's submit refuses a
  ;; clean worktree, so a green pull request put back in `open` had no
  ;; way to the merge line. In `submitted` the next forge pass judges
  ;; its head, red or green. Its ticket goes out for review with it,
  ;; through the `unstick_submitted` subscription, when the ticket is in
  ;; the queue; a draft stays a draft for a person to groom, and the
  ;; merge ends it wherever it stands.
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

(defhandler fail-the-change [row inp _ctx]
  ;; A red or conflicted head sends the ticket back to the queue through
  ;; the `fail` subscription, and the seat that wrote the change wakes on
  ;; its `return` and walks it with this change and its feedback (ticket
  ;; 2e869934).
  (with-the-failing-checks row inp))

(defn- refuse-a-stale-head!
  ;; A verdict the pull request read on another head than the one the
  ;; change names now is not this head's (ticket 995570f0): it refuses
  ;; `wrong_state`, which the subscription's walk notes as a skip. No
  ;; head is the forge pass's own walk, which reads the head itself.
  [row inp action]
  (let [head (some-> (:head_sha inp) str not-empty)
        now (some-> (get-in row [:data :head_sha]) str not-empty)]
    (when (and head now (not= head now))
      (throw (p/wrong-state action (:state row) #{(:state row)}
                            {:kind :change :id (str (:id row))}
                            (str "The verdict is for head " head
                                 ", and this change's head is now " now "."))))))

(defhandler fail-or-stick-the-change [row inp ctx]
  ;; The pull request's red, walked by its subscription (ticket
  ;; 995570f0). Where it lands is the change's own `rounds` against the
  ;; policy's ceiling, the forge pass's choice between `fail` and
  ;; `stick`: under it the change goes back to the seat, at it the
  ;; change stops for a person. A `failing` landing sends the ticket
  ;; back to the queue through the `checks_red` subscription.
  (refuse-a-stale-head! row inp :checks_red)
  (let [rounds (long (or (get-in row [:data :rounds]) 0))
        ceiling (long (bench/rounds-of (bench/policy-of row ctx)))]
    (assoc (with-the-failing-checks row inp)
           :state (if (< rounds ceiling) :failing :stuck))))

(defhandler clear-the-failing-checks [row inp _ctx]
  ;; A green head, or a new round: the names of the last red are not
  ;; this head's, and a stale list would read as a live one. A ticket
  ;; the red sent back goes out for review again, through the `recover`
  ;; subscription (ticket 2e869934).
  (refuse-a-stale-head! row inp :recover)
  (update row :data assoc :failing_checks nil :conflicts nil
          :landing_error nil :train_red_head nil :train_red nil))

(defguardfn the-pull-requests-walk-writes-the-head
  {:reads [:principal]
   :hide true
   :explain "The pull request this change follows writes its head, through its own subscription. A person and a model read it."}
  [_row _inp ctx]
  ;; ticket 423164bf: a kind's own subscription walks as the system
  ;; principal it declares, carrying the subscription (door_receivers
  ;; `walker`); the forge pass's own hand carries none
  (let [who (:principal ctx)]
    (if (and (= :system (:type who)) (some? (:subscription who)))
      (t/allow)
      (t/deny))))

(defhandler refresh-the-head [row inp ctx]
  ;; The pull request's head and merge word, walked by its subscription
  ;; (ticket 423164bf). A conflict the row holds stands against an
  ;; `unknown` or `blocked` read of the same head (`holds-conflict?`),
  ;; and a head that moves takes back what judged the old one, as
  ;; `observe` does.
  (let [facts (cond-> (into {} (remove (comp nil? val))
                            (select-keys inp [:head_sha :mergeable]))
                (forge/holds-conflict? row inp) (dissoc :mergeable))
        head (some-> (:head_sha facts) str not-empty)]
    (when (and head (not= head (some-> (get-in row [:data :head_sha]) str not-empty)))
      (verdict/reopen-stale-verdicts! ctx :change (:id row) head))
    (update row :data merge facts)))

;; ── the walls on the bench doors ────────────────────────────────────
;;
;; Whether the repository has a policy is DECLARATIVE
;; (docs/spec-related-row-law.md §7): an `exists` over `repo_policy`,
;; the very filter `bench/policy-of` asks. The three that read the
;; policy's values are code: each declares `:reads [:repo_policy]`,
;; asks the ctx `:find` hook for the row, and says `:check-tier :given`,
;; so a scenario's :given rows answer that read and submit's scenarios
;; need no database. A ctx with no hook — the render probe — is
;; answered with an ALLOW (the declarative wall's unknown verdict is
;; pending, which is advertised), the framework's own posture there:
;; the envelope advertises optimistically and the door judges again
;; with a real hook behind it. Their law is proved in
;; factory10.bench-test as well, over a real store.

(defguard the-repository-has-a-policy
  (refuse "What submit means in {repository} is a row, and this repository has no active policy: the branch pattern, the base, the size ceiling and the round ceiling are all unsaid. A person states them once, and the bench works this repository from then on.")
  '(exists [p :repo_policy {:repository (= (data :repository))
                            :state (= "active")}])
  {:remedies [:repo_policy/create]})

(defguardfn the-branch-matches-the-policy
  {:reads [:repo_policy]
   :check-tier :given
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
   :check-tier :given
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
   :check-tier :given
   :vars [:rounds :ceiling]
   :remedies [:change/stall]
   :explain "This change has had {rounds} of the {ceiling} rounds the policy gives it. The house stops here rather than spending another wake on the same change: say with the stall door what is wrong, and a person reads it."}
  [row _inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (let [policy (bench/policy-of row ctx)
          rounds (long (or (get-in row [:data :rounds]) 0))
          ceiling (bench/rounds-of policy)]
      ;; at the ceiling the rig says whether this round only merges
      ;; the base, which passes (ticket 50f09817); an unreachable rig
      ;; counts the round as any other
      (if (or (< rounds ceiling)
              (base-only-round?
               (bench/ask ctx :status
                          {:repo (str (get-in row [:data :repository]))
                           :branch (bench/branch-of row policy)})))
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
  ;; person allowed. `discard` and its old alias `discard_submitted`
  ;; share this wall, so the replay check names the row and not the door.
  (cond
    (not (true? (:drop_branch inp))) (t/allow)
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :change (:id row)) (t/allow)
    :else (t/deny)))

;; `a-person-or-their-delegate-unsticks`, ticket's
;; `a-person-or-their-delegate-grooms` one kind over, is the kit's with
;; the two unstick doors it guards (ticket eb500c47).

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

(defguardfn a-hand-releases-the-lane
  {:reads [:principal]
   :open "No door clears this one. The mirror and the engine's own passes never release a lane hold; the mayor does, or a person."
   :explain "A change held for an open org plan's lane is let go on a judgment that the edit is one the plan should take (ticket 59cd3e78). That is a hand's door: the engine's own passes, the mirror among them, do not walk it."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

;; `the-change-has-no-pull-request` and `the-change-has-a-pull-request`
;; are the kit's (ticket eb500c47); `rework` and `rework_submitted`
;; here carry them as the kit's two unstick doors do.
;; `no-pull-request-to-close`, `the-engine-closes-a-stopped-change` and
;; `only-a-person-closes-a-change` are the kit's too (ticket 48f9605b),
;; on its `close_without_pr`.

(defguardfn the-mirror-or-its-tickets-ending-closes-it
  {:reads [:principal :within]
   :hide true
   :explain "The mirror closes this row, or the ending of the ticket it was built for does. A person and a model read it."}
  [_row _inp ctx]
  ;; `mirror/the-mirror-writes-this-row`, and one hand more (ticket
  ;; 458d65c5): a ticket's ending closes the unmerged changes born from
  ;; it inside its own transaction, whoever's hand ended it. A child's
  ;; birth or reparent closes its new parent's stuck change, since a
  ;; parent is not built (ticket 898caffd).
  (let [{:keys [kind action]} (:within ctx)]
    (if (or (= :system (:type (:principal ctx)))
            (and (= :ticket kind)
                 (contains? #{:complete :drop :land :mend :finish
                              :create :reparent} action)))
      (t/allow)
      (t/deny))))

(defguardfn only-its-tickets-move-writes-the-domain
  {:reads [:within]
   :hide true
   :explain "The domain of a change is its ticket's: a move of the ticket into another domain writes it here, inside that move. A person and a model read it."}
  [_row _inp ctx]
  ;; ticket 66d080b0: the ticket's two move doors (folded by ticket
  ;; 45a8d06c), and no hand
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :ticket kind)
             (contains? #{:move_domain :move_domain_carried} action))
      (t/allow)
      (t/deny))))

(defhandler write-the-tickets-domain [row inp _ctx]
  ;; its ticket moved into another domain, and the change goes with it
  (assoc-in row [:data :domain] (:domain inp)))

(def ^:private take-domain-input
  [:map
   [:domain {:not-a-ref "The name of a domain: a word, not a row id."
             :x-display {:hidden true :raw true :label "The domain"}}
    [:string {:min 1 :max 64}]]])

(def ^:private take-domain-description
  "Its ticket moved into another domain, and the change went with it")

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

(defscenario the-mayor-releases-a-lane-hold
  "A change that touches an open org plan's lane is held for that plan.
   The mayor lets one go that the plan should take, and says why."
  {:kind    :change
   :attempt :release_lane
   :row     {:state :submitted :data a-pull-request}
   :input   {:why "The plan should take this README fix as it is."}
   :as      {:id "mayor" :type :agent}
   :expect  {:allowed true}})

(defscenario the-mirror-does-not-release-a-lane-hold
  "The release is a judgment about the edit, so the engine's own hand
   never makes it."
  {:kind    :change
   :attempt :release_lane
   :row     {:state :submitted :data a-pull-request}
   :input   {:why "The plan should take this README fix as it is."}
   :as      {:id "factory10-source" :type :system}
   :expect  {:refused :a-hand-releases-the-lane}})

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

;; Check-tier as well: submit's four walls read the policy, one
;; declarative and three that say `:check-tier :given`, so a scenario
;; with no :given policy is a repository that never stated one.

(defscenario a-change-is-not-submitted-where-no-policy-says-what-submit-means
  "A repository with no active policy never said what a submit is: its
   branch pattern, its base and its ceilings are unsaid. The door
   refuses, and names the policy a person states."
  {:kind    :change
   :attempt :submit
   :row     {:state :open :data (dissoc a-pull-request :number)}
   :input   {:why "Fix the fixture's table list."}
   :as      {:id "code-seat" :type :agent}
   :expect  {:refused :the-repository-has-a-policy
             :because "has no active policy"
             :remedies [:repo_policy/create]}})

;; ── :change — one pull request, mirrored ────────────────────────────

(defn- take-the-tickets-domain
  "The birth's stamp (ticket 20fab5f9): a change built for a ticket takes
  the domain that ticket stores. A change the forge mints, and one
  whose ticket stores none, is left with none and filters as factory.

  It takes the ticket's `requested_by` in the same way (ticket
  a9b3d233), so the domain that asked for the work hears the change
  move as it hears the ticket."
  [row ctx]
  (let [born (str (get-in row [:data :born_from]))
        ticket (when-some [read' (:read ctx)]
                 (when-some [[_ id] (re-matches #"ticket:(.+)" born)]
                   (read' :ticket id)))
        domain (some-> (get-in ticket [:data :domain]) str not-empty)
        asker (some-> (get-in ticket [:data :requested_by]) str not-empty)]
    (cond-> row
      domain (assoc-in [:data :domain] domain)
      asker (assoc-in [:data :requested_by] asker))))

(def ^:private factory10s-own
  "What factory10 adds to the kit's core (ticket ce3dd7b1): every door
  that is not one of the core's twelve, with its guards and its
  handler, the ticket's part of the seven core doors that read it
  (`:handlers`), and the fields that are not shared. The states, the
  tomb, the one-row index, the shared fields, the source's eight
  doors, the stall's four (stall, unstick, unstick_submitted and
  rebranch, ticket eb500c47), the PR-less ending's two
  (close_without_pr and reopen, ticket 48f9605b) and the cracks law
  are `factorykit.change/core`."
  {:kind :change
   :plural "changes"
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   :initial :open
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
                :author #{:eq}
                ;; a guard finds the change built for a ticket by it:
                ;; (exists [c :change {:born_from (= (str "ticket:" (self)))}])
                :born_from #{:eq}
                ;; one change per pull request, by index (ticket 56c97770)
                :pull_request #{:eq}
                :domain #{:eq :in}}
   ;; a change born before domains stores none, and is factory's
   :absent-as {:domain "factory"}
   ;; the collection a reader opens IS the pull requests that are
   ;; still live (inbox_item's spelling) — and a change a seat has
   ;; pushed is still live: the checks run, the review lands, and the
   ;; seat works the next round on the same row. A STUCK change is
   ;; outside this filter on purpose, so a walking seat never meets
   ;; again the change the house stopped.
   :default-filters {:state "open,submitted"}
   :sortable {:fields [:updated_at] :default "updated_at"}
   ;; the change's path lane (ticket 9b27142e): shown, not acted on yet
   :computed {:lane
              {:schema :string
               :x-display
               {:label "Lane"
                :help "The lane of its repository's policy this change's touched paths stand in, worked out at read time: docs, orgs/<name>, or engine for a change no lane covers or one that spans lanes. An open org plan's change owns its orgs/<name> lane: another change that touches it is held until the plan's change merges or closes."}
               ;; it reads the repository's policy: with no :find it is
               ;; absent, never a guessed engine
               :reads? true
               :fn (fn [row ctx]
                     (when (:find ctx)
                       (bench/lane-of row (bench/policy-of row ctx))))}}
   ;; WHERE THE KIT'S FIELDS STAND AMONG THESE. The builder puts the
   ;; core's fields first; this is the order the kind had as one map.
   :field-order [:change_id :repository :number :title :author :base_branch
                 :head_branch :head_sha :draft :mergeable :files_changed
                 :lines_added :lines_removed :touched_paths :labels
                 :review_state :missing_checks :behind_base :base_compared_at
                 :missing_checks_head :green_head :held_head :held_since
                 :held_again :held_threw :held_reason :branch :worktree_dirty
                 :rounds :last_round_kind :failing_checks :conflicts
                 :rerun_head :rerun_note :updated_head :current_head
                 :landing_error :train_red_head :train_red :merge_asked_head
                 :merge_refused :merge_refused_as :merge_wait_head
                 :merge_wait_since :train_done_head :train_done
                 :unadopted_since
                 :adoption_note :stall_reason :stalled_at :stalled_by
                 :line_place :line_why :line_reason :held_as :held_why
                 :lane_released :url :born_from
                 :superseded_by :domain :requested_by]
   ;; the fields that are factory10's own. The kit's are change_id to
   ;; head_branch, worktree_dirty, rounds, the stall's three, born_from,
   ;; and every field a source door of the kit writes (ticket 354a7dc3):
   ;; head_sha to missing_checks_head, failing_checks to adoption_note,
   ;; and url.
   :schema
   [:map
    ;; what the last submit was (ticket 50f09817): "base_merge" when it
    ;; only merged the base and so did not count toward `rounds`
    [:last_round_kind {:optional true :x-display {:hidden true}}
     [:maybe [:enum "base_merge"]]]
    ;; the head the forge pass last read green on every required check
    ;; (ticket baf76388): the merge line does not take a rig's red for it
    [:green_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    ;; the head the house asked the rig's `update_branch` at, and the head
    ;; the rig said already held its base (ticket a1a133ca): `update_asked`
    ;; writes both, so a restart does not ask the same head again
    [:updated_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:current_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    ;; the house's last merge ask that parked or un-parked a head (ticket
    ;; 1ed59295): the head asked at, the rig's reason and the refusal's
    ;; name; the park counts only while `merge_asked_head` is the head
    [:merge_asked_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:merge_refused {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]
    [:merge_refused_as {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    ;; the person's merge wait (ticket 3806fb22): the head the ask pass
    ;; times and when its wait started; a head with no time is held
    [:merge_wait_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:merge_wait_since {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 40}]]]
    ;; a merge train's done mark (ticket 1ae1afbc): the head it carried
    ;; this change at when it sent the lane one at a time, and why; it
    ;; counts only while `train_done_head` is the head
    [:train_done_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:train_done {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]
    ;; a submitted head that waits (ticket b256d053): the forge pass
    ;; stamps the head and the time it first saw it held, counts the wait
    ;; from that time, writes why when the wait is long, and clears all
    ;; three when the head is no longer held; a door that merges or
    ;; closes the change drops them too (ticket 1a72eea8)
    [:held_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:held_since {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 40}]]]
    ;; the held head's compare (ticket 93072465): when the pass asks it
    ;; again, and how many compares in a row threw, so a restart keeps
    ;; the backoff; cleared with the three around them
    [:held_again {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 40}]]]
    [:held_threw {:optional true :x-display {:hidden true}}
     [:maybe [:int {:min 0}]]]
    [:held_reason {:optional true
                   :examples ["the checks of github:ckopsa/waymark#31 at 0a1b2c3 have no verdict: gate (no run)"]
                   :x-display
                   {:widget "prose"
                    :label "Why its head waits"
                    :help "Why this submitted head has not merged after a long wait: the required checks that have no verdict yet, or that it is only behind its base. Empty while the wait is short, and when the head is not held."}}
     [:maybe [:string {:max 500}]]]
    ;; ── the bench's three (waymark-fp62.6.3.2, R-4) ──────────────
    [:branch {:optional true
              :x-display
              {:raw true
               :label "The branch on the bench"
               :help "The branch the worktree sits on. It is the head branch when GitHub already named one, and the policy's pattern with this row's own id when it did not. One branch for each change, so the next sitting finds the last one's work."}}
     [:maybe [:string {:max 200}]]]
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
                           "interrupted" "Its checks died without a verdict again after the house ran them again once, so it is out of the line"
                           "conflicted" "It conflicts with the base branch"
                           "draft" "It is a draft, and a draft is not brought forward"
                           "parked" "The bench refused this head for good"
                           "held" "Its ticket merges after tickets that are not done yet, or it touches an open org plan's lane"
                           "person" "A person merges it on GitHub: the house does not merge this repository, and nobody is asked here"
                           "org_plan_waits_go_live" "An org plan not yet live names it: only its owner's go_live merges it"
                           "org_plan_retired" "A retired org plan names it or bore it: it is never merged"}}}
     [:maybe [:enum "front" "behind" "red" "interrupted" "conflicted" "draft" "parked" "held" "person" "org_plan_waits_go_live" "org_plan_retired"]]]
    [:line_reason {:optional true
                   :examples ["GitHub says it cannot merge"]
                   :x-display
                   {:widget "prose"
                    :label "Why it is parked or held"
                    :help "The bench's own reason for refusing this head for good, or the tickets a held change waits on to merge. Empty unless the change is parked or held."}}
     [:maybe [:string {:max 500}]]]
    ;; the merge line's hold (ticket e6729daa): written by the `hold`
    ;; door, cleared by `release`
    [:held_as {:optional true
               :x-display
               {:label "What holds it"
                :help "What keeps this change out of the merge line. Empty when nothing holds it."
                :choices {"merge_holds" "Its ticket merges after tickets that are not done yet"
                          "lane" "It touches an open org plan's lane"
                          "deploy" "Its repository waits for the last merge to deploy"
                          "go_live" "An org plan names it: only its owner's go_live merges it"
                          "person" "A person merges it on GitHub"}}}
     [:maybe [:enum "merge_holds" "lane" "deploy" "go_live" "person"]]]
    [:held_why {:optional true
                :examples ["waits on ticket 1ed59295 (open)"]
                :x-display
                {:widget "prose"
                 :label "Why it is held"
                 :help "The merge pass's sentence for the hold. Empty when nothing holds it."}}
     [:maybe [:string {:max 500}]]]
    ;; the mayor's release of a lane hold (ticket 59cd3e78): written by
    ;; `release_lane`; while it stands the merge pass holds the change
    ;; for no org plan's lane
    [:lane_released {:optional true
                     :x-display
                     {:widget "prose"
                      :label "Why it merges past a plan's lane"
                      :help "Why the mayor let this change merge though it touches an open org plan's lane: an edit the plan should take. Empty unless released."}}
     [:maybe [:string {:max 480}]]]
    ;; the merged change a duplicate was closed for (ticket 3ec37f66):
    ;; written by `supersede`, and by `close_without_pr` with the
    ;; sentence it was closed for (ticket 48f9605b). Hidden, as the url is.
    [:superseded_by {:optional true :not-a-ref "The web address (a URL) of the pull request that merged in this one's place, not a row id."
                     :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]
    ;; the domain's NAME, taken at birth from the ticket this change
    ;; was built for (ticket 20fab5f9), and written again by `take_domain`
    ;; when that ticket is moved (ticket 66d080b0)
    [:domain {:optional true
              :not-a-ref "The name of a domain, which no restate changes: a word, not a row id."
              :examples ["factory"]
              :x-display
              {:raw true
               :label "Domain that wants the work"
               :help "The name of the domain of the ticket this change was built for, written when the change is born and again when that ticket is moved into another domain. A change that stores none is in factory."}}
     [:maybe [:string {:max 64}]]]
    ;; the asking domain's NAME, taken at birth from the ticket this
    ;; change was built for (ticket a9b3d233), and written by nothing else
    [:requested_by {:optional true
                    :not-a-ref "The name of a domain, which no restate changes: a word, not a row id."
                    :examples ["household"]
                    :x-display
                    {:raw true
                     :label "Domain that asked"
                     :help "The name of the domain whose mayor asked another domain for the ticket this change was built for, written when the change is born. Empty for a change whose ticket was filed in its own domain."}}
     [:maybe [:string {:max 64}]]]]
   ;; THE BIRTH DOOR IS THE MIRROR'S, AND IT IS HIDDEN. A person meets
   ;; no create form for this kind. The source mints the row with what
   ;; GitHub answered; everything but the identity is optional,
   ;; because a first read does not always carry the counts.
   :create-guards [the-mirror-writes-this-row]
   :on-create take-the-tickets-domain
   :no-own-surface "stalled_by is written by the stall door, not at birth; a change is the repository's record, shown by a grant that names it."
   :create-schema
   [:map
    ;; github:owner/repo#number from the source, and <kind>:<row id>
    ;; from a seat's own sit (spec-seat.md R-12.32)
    [:change_id {:x-ref {:address true}
                 :x-display {:label "The pull request's own id"}}
     [:string {:min 1 :max 250}]]
    [:repository {:x-display {:label "The repository"}}
     [:string {:min 1 :max 140}]]
    ;; the mirror gives it; the seat's own mint does not (R-12.32)
    [:number {:optional true :x-display {:label "The pull request number"}}
     [:maybe [:int {:min 1}]]]
    [:title {:optional true :x-display {:raw true :label "Title"}}
     [:maybe [:string {:max 400}]]]
    [:author {:optional true :not-a-ref "A GitHub login, or the name of the seat that opened it: a handle, not a row id or a principal here."
              :x-display {:label "Who opened it"}}
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
    ;;
    ;; A SECOND LIVE ROW WITH THE SAME `born_from` IS NOT REFUSED HERE
    ;; (ticket 5aa79167), and no caller needs one: the sit's mint
    ;; (waymark10.server.mcp/minted-change) is the only writer and looks
    ;; for a live row first, the forge's mint, `adopt` and `fold` never
    ;; write the field, and factory10.bench-test builds its duplicates by
    ;; hand; one that is born anyway, when that lookup misses, is closed
    ;; by the merge (`close-the-duplicates!`) and by the ticket's ending.
    [:born_from {:optional true :x-ref {:address true} :x-display {:hidden true}}
     [:maybe [:string {:max 250}]]]]
   ;; THE TICKET'S PART OF FOUR CORE DOORS (ticket 47e2c880). The kit's
   ;; `merge`, `close`, `fail` and `recover` carry no handler, because
   ;; what each one does to the ask the change was built for is this
   ;; factory's: the merge ends the ticket, a close and a red send it
   ;; back, and a green sends it out for review again.
   ;;
   ;; AND OF THREE MORE (ticket eb500c47). The kit's `stall`, `unstick`
   ;; and `unstick_submitted` carry none either: a stall sends the
   ;; ticket to draft with the seat's sentence, and each unstick moves
   ;; the ticket with its change.
   ;;
   ;; THE TICKET FOLLOWS BY SUBSCRIPTION (ticket 35c1e826): none of these
   ;; handlers moves it; `subscriptions` walks its doors after each move.
   :subscriptions subscriptions
   :handlers {:merge complete-the-task-it-was-born-from
              :close drop-the-wait
              :fail fail-the-change
              :recover clear-the-failing-checks
              :stall note-the-stall
              :unstick unstick-the-change
              :unstick_submitted unstick-the-pull-request
              ;; AND OF TWO MORE (ticket 48f9605b): what the close drops
              ;; and what the reopen clears are this factory's fields.
              :close_without_pr say-why-it-was-let-go
              :reopen reopen-the-change}
   :actions
   {;; THE MIRROR'S REFRESH is the kit's `observe`, on `open`,
    ;; `submitted` and `failing` alike (ticket d1742908 gave it
    ;; `failing`): a red change's head moves when a person pushes to
    ;; it, and the next green head is read against the head the row
    ;; names.

    ;; `merge` and `close` are the kit's, with the handlers `:handlers`
    ;; names. The merge COMPLETES the task this change was born from
    ;; (waymark-fp62.6.3.14), and its handler carries the blast radius,
    ;; because `:touches` cannot name a kind factory10 boots without
    ;; — see `complete-the-task-it-was-born-from`.

    ;; `close_without_pr`, the ending of a change GitHub never saw
    ;; (ticket be2c2c16), is the kit's (ticket 48f9605b), with the
    ;; handler `:handlers` names. It leaves the ticket where it stands —
    ;; see `:deviations`.

    ;; the merge's close of a DUPLICATE (ticket 3ec37f66): another
    ;; change born from the same ask on the same branch merged, so this
    ;; one is let go with the merged one's address on it. Not `close`:
    ;; that door is GitHub's word and sends the ticket back, and this
    ;; one's ticket has just ended.
    :supersede
    {:from #{:open :submitted :failing :stuck} :to :closed
     :guards [the-mirror-or-its-tickets-ending-closes-it]
     :handler write-what-superseded-it
     :input [:map
             [:superseded_by {:not-a-ref "The web address (a URL) of the pull request that merged in this one's place, not a row id."
                              :x-display {:hidden true}}
              [:string {:min 1 :max 500}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Another change built for the same ask merged, so this duplicate is closed and names the one that merged. The way back is GitHub's own reopen, which the mirror follows with its reopen door."}
     :display {:label "Superseded" :order 18
               :description "Another change for the same ask merged, and this one is closed"}}

    ;; `reopen` is the kit's too, with the handler `:handlers` names.

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
    ;; `adopt` is the kit's (`factorykit.change/core`), on every live
    ;; state. A pull request the house itself opened is the house row's
    ;; whatever state that row stands in, so it is adopted and never
    ;; minted beside it (ticket 3c59c688). The adoption records the
    ;; identity and LEAVES the state: a stuck row stays stuck, because a
    ;; person still puts it back to work — and with the number on it
    ;; now, `unstick_submitted` is the door that does, so the merge pass
    ;; reads it.

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
     :guards [the-mirror-or-its-tickets-ending-closes-it]
     :handler note-the-adoption
     :input [:map
             [:unadopted_since {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 64}]]]
             [:adoption_note {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Adoption noted" :order 19
               :description "The mirror says a landed pull request is waiting for its row"}}

    ;; THE HOUSE'S ASKS (ticket a1a133ca). The merge pass asks the rig's
    ;; `update_branch` and its `rerun` once per head; these write that
    ;; head on the row, so a restart does not ask it again. Hidden, and
    ;; the mirror's hand alone.
    :update_asked
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler record-the-update
     :input [:map
             [:updated_head [:string {:min 1 :max 64}]]
             [:current_head {:optional true} [:maybe [:string {:max 64}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Update asked" :order 20
               :description "The house asked the rig to bring the branch up to date at this head"}}

    :rerun_asked
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler record-the-rerun
     :input [:map
             [:rerun_head [:string {:min 1 :max 64}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Re-run asked" :order 21
               :description "The house asked the rig to run this head's interrupted checks again, once"}}

    ;; THE HOUSE'S MERGE ASK (ticket 1ed59295, spec R-C.15). The merge
    ;; pass writes the head it asked the rig at and, for a refusal a new
    ;; pass cannot fix, why, so the park survives a restart. Hidden, and
    ;; the engine's own hand alone.
    :merge_asked
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler write-the-merge-ask
     :input [:map
             [:merge_asked_head {:x-display {:hidden true}}
              [:string {:min 1 :max 64}]]
             [:merge_refused {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]
             [:merge_refused_as {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 64}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Merge asked" :order 24
               :description "The house asked the rig to merge this head, and what the rig said when it parked it"}}

    ;; THE PERSON'S MERGE WAIT (ticket 3806fb22, spec R-C.19). The ask
    ;; pass starts the wait on a clean, green, unheld head and clears it
    ;; when that stops holding. Hidden, and the engine's own hand alone.
    :wait_started
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler write-the-merge-wait
     :input [:map
             [:merge_wait_head {:x-display {:hidden true}}
              [:string {:min 1 :max 64}]]
             [:merge_wait_since {:x-display {:hidden true}}
              [:string {:min 1 :max 40}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Merge wait started" :order 30
               :description "The house started timing this head before it asks a person to merge it"}}

    :wait_cleared
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler clear-the-merge-wait
     :input [:map
             [:merge_wait_head {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 64}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Merge wait cleared" :order 31
               :description "The house stopped timing this head: it is held, or no longer clean, green or submitted"}}

    ;; A MERGE TRAIN'S DONE MARK (ticket 1ae1afbc). A train that sends
    ;; its lane one at a time writes it on each change it carried, so a
    ;; restart does not build that train again. Hidden, and the engine's
    ;; own hand alone, as `merge_asked` is.
    :train_done
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler write-the-train-done
     :input [:map
             [:train_done_head {:x-display {:hidden true}}
              [:string {:min 1 :max 64}]]
             [:train_done {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Train done" :order 29
               :description "A merge train that carried this head sent its line one at a time"}}

    ;; THE MERGE LINE'S HOLDS AND PLACES (ticket e6729daa, spec R-C.14).
    ;; The merge pass walks these where it once wrote the row by hand, so
    ;; each hold, release and place is a transition with its reason that
    ;; a seat can subscribe to. Hidden, and the engine's own hand alone.
    :hold
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler write-the-hold
     :input [:map
             [:held_as {:x-display {:hidden true}}
              [:enum "merge_holds" "lane" "deploy" "go_live" "person"]]
             [:held_why {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]
             [:held_reason {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]
             [:clear_held_reason {:optional true :x-display {:hidden true}}
              [:maybe :boolean]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Held" :order 26
               :description "The merge pass holds this change out of the line, and says what holds it and why"}}

    ;; `release` serves every live state (ticket 0c2940ca): a held
    ;; change that leaves `submitted` for `open`, `failing` or `stuck`
    ;; is released there, so its `held_as` does not outlive the line.
    :release
    {:from #{:open :submitted :failing :stuck} :to :same
     :guards [the-mirror-writes-this-row]
     :handler write-the-release
     :input [:map
             [:clear_held_reason {:optional true :x-display {:hidden true}}
              [:maybe :boolean]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Released" :order 27
               :description "Nothing holds this change out of the merge line now"}}

    :line
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler write-the-line
     :input [:map
             [:line_place {:optional true :x-display {:hidden true}}
              [:maybe [:int {:min 1}]]]
             [:line_why {:optional true :x-display {:hidden true}}
              [:maybe [:enum "front" "behind" "red" "interrupted" "conflicted" "draft" "parked" "held" "person" "org_plan_waits_go_live" "org_plan_retired"]]]
             [:line_reason {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Placed in the line" :order 28
               :description "The merge pass says where this change stands in its merge line, and why it is not merging"}}

    ;; THE TICKET'S MOVE (ticket 66d080b0). A ticket that moves into
    ;; another domain takes its open changes with it, inside its own
    ;; door. Hidden, and no hand's; one self-loop over every state a
    ;; change is still live in.
    :take_domain
    {:from #{:open :submitted :failing :stuck} :to :same
     :guards [only-its-tickets-move-writes-the-domain]
     :handler write-the-tickets-domain
     :input take-domain-input
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Moved with its ticket" :order 23
               :description take-domain-description}}

    ;; `rebranch`, the sit's door that mints the branch again, is the
    ;; kit's (ticket eb500c47).

    ;; ── THE CHECKS' VERDICT (ticket d1742908) ──────────────────────────
    ;; The forge pass reads the checks on a submitted change's head
    ;; against the repository policy and walks one of these three.
    ;; They are the mirror's and hidden: no seat and no person says a
    ;; change is red. A seat's way out of `failing` is its next
    ;; `submit`, or `stall`.
    ;; `fail` and `recover` are the kit's, with the handlers `:handlers`
    ;; names; `stick`, the third, is this factory's.
    ;;
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

    ;; THE PULL REQUEST'S RED (ticket 995570f0). Its `checks_red` walks
    ;; this door through the kind's subscription, and the change's own
    ;; `rounds` chooses the landing: `failing` under the ceiling, as
    ;; `fail` writes it, `stuck` at it, as `stick` writes it. A verdict
    ;; for another head than the change's refuses `wrong_state`.
    :checks_red
    {:from #{:submitted} :to #{:failing :stuck}
     :guards [the-mirror-writes-this-row]
     :handler fail-or-stick-the-change
     :input [:map
             [:why {:optional true
                    :x-display {:widget "prose"
                                :label "What went red"
                                :help "The checks that finished red on the pull request's head."}}
              [:maybe [:string {:min 1 :max 480}]]]
             [:failing_checks [:vector {:min 1} [:string {:max 200}]]]
             [:head_sha [:string {:min 1 :max 64}]]]
     :waives #{:edit-shape :large-effort}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The required checks finished red on the pull request's head. Under the round ceiling the ticket goes back to the queue and the seat walks it again; at the ceiling the change is stuck and a person reads it next."}
     :display {:label "Red at GitHub" :order 18
               :description "The pull request's required checks went red on the change's head"}}

    ;; THE PULL REQUEST'S HEAD (ticket 423164bf, R-C.5). Its `head_moved`
    ;; walks this door through the kind's subscription, so a webhook's
    ;; head move lands on the change within the event, with no forge
    ;; pass. The fields stay stored: what changes is who writes them.
    :refresh_head
    {:from #{:open :submitted :failing :stuck} :to :same
     :guards [the-pull-requests-walk-writes-the-head]
     :handler refresh-the-head
     :input [:map
             [:head_sha {:optional true} [:maybe [:string {:max 64}]]]
             [:mergeable {:optional true}
              [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Follow the head" :order 19
               :description "The pull request writes its head and merge word on the change it follows"}}

    ;; THE MAYOR'S RELEASE (ticket 59cd3e78). A change toward main that
    ;; touches an open org plan's lane is held for the plan; this lets
    ;; one go, for an edit the plan should take.
    :release_lane
    {:from #{:submitted} :to :submitted
     :input [:map
             [:why {:examples ["The plan should take this README fix as it is."]
                    :x-display
                    {:widget "prose"
                     :label "Why it merges past the plan"
                     :help "One sentence: why this edit should merge before the org plan's change, which then takes it from main. It is at most 480 characters."}}
              [:string {:min 1 :max 480}]]]
     :edit {:draft {:shared true :live true}}
     :guards [a-hand-releases-the-lane]
     :handler release-the-lane
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The change merges past the org plan's lane when its checks are green, and the plan's next update from main takes the edit. Nothing puts the hold back."}
     :display {:label "Release the lane" :order 25
               :description "Let a change held for an open org plan's lane merge: an edit the plan should take"}}

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
                     :help "One sentence for the commit message: what you changed and why. It is what a person reads in the log, and what the review reads first. It is at most 480 characters."}}
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

    ;; on `open` and on `submitted` alike — see the ns docstring
    :discard
    {:from #{:open :submitted} :to :same
     :input [:map
             [:drop_branch {:default false
                            :x-display
                            {:label "Drop the branch as well"
                             :help "True removes the worktree and the branch, not only the edits. A person's act: a model's discard keeps the branch."}}
              [:maybe :boolean]]]
     :guards [only-a-person-drops-the-branch]
     :handler discard-the-worktree
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The edits in the worktree are gone and nothing here brings them back. The change itself stands, with every round already pushed, and the next round starts from the branch head."}
     :display {:label "Discard" :style :danger :order 6
               :description "Put the worktree back to the branch head and lose every edit in it"}}

    ;; `stall`, `unstick` and `unstick_submitted` are the kit's (ticket
    ;; eb500c47), with the handlers `:handlers` names: a stalled change
    ;; is not under review, and its ticket could not be built as
    ;; written, so it goes back to draft (ticket 6bdaf6fe).

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
               :description "Its ticket was groomed, unblocked or resumed — the pull request goes back under review"}}}
   ;; THE COPIES THE FOLD RETIRED (ticket bc6a8732), beside the kit's
   ;; own: each answers for one release, and the log names the alias.
   :aliases {:discard_submitted :discard
             :take_domain_submitted :take_domain
             :take_domain_failing :take_domain
             :take_domain_stuck :take_domain}
   :links [{:rel "origin" :href "{data.url}" :external true
            :summary "The pull request, at GitHub"}]
   :deviations
   ["`unstick` is spelled twice, `unstick` and `unstick_submitted`, because it lands in two states (ticket 6bdaf6fe): a change with no pull request goes back to `open` for its next round, and a change with one goes back to `submitted`, where the forge and merge passes read it. A v10 action declares one `:to`, so each door carries the guard that says which change it is for. `rework` and `rework_submitted`, the ticket's own walk to the same two landings, are spelled twice for the same reason (ticket 4363c63b)."
    "A self-loop that serves several states is one `:to :same` door (ticket bc6a8732): `observe`, `adopt`, `discard` and `take_domain`. The copies they once were (`observe_submitted`, `observe_failing`, `adopt_submitted`, `adopt_failing`, `adopt_stuck`, `discard_submitted` and `take_domain_submitted`, `_failing` and `_stuck`) answer for one release through `:aliases`, so a seat scope or a caller that names one keeps working while the mayor restates it, and the log names the alias it came by."
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

(defresource change (kit/build kit/core factory10s-own))
