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
  round ceiling sends it. `unstick` is the person's answer to that.
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
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── what a pull request writes back onto the row ────────────────────

(defhandler observe-the-pull-request [row inp _ctx]
  ;; The source hands the facts it read. A fact it did not read is
  ;; absent, and absent means silent: the stored value stands. The
  ;; machine advances the state, never this handler.
  (update row :data merge (into {} (remove (comp nil? val)) inp)))

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
       "door and let a person look at it."))

(def ^:private nothing-detail
  "The worktree is clean: no file in it is different from the branch
  head, so there is nothing to commit and nothing to push.")

(defn- rig-refusal!
  "The rig's own refusal, said again as this door's.

  THE NAMES THIS DOOR KNOWS carry a remedy of their own; every other
  name carries the general one, so a refusal nobody anticipated still
  tells the seat what to do next. The rig's `remedy` sentence rides
  beside it when it sent one — it is the rig that knows what it
  refused."
  [what answer]
  (let [named (bench/refused answer)
        reason (some-> (:reason answer) str not-empty)
        theirs (some-> (:remedy answer) str not-empty)]
    (bench/refuse!
     (str "The bench refused to " what ": " named
          (when reason (str " — " reason)) ".")
     (cond-> (case named
               "push_rejected" [pull-remedy]
               "over_ceiling" [ceiling-remedy]
               "nothing_to_commit" [clean-remedy]
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

(defn- title-of
  "The title the pull request is opened with: this row's own title,
  cut at the ceiling.

  THE ENGINE OWNS THE TITLE, and not the seat (bead
  waymark-fp62.6.3.13). The row's title is the ask's own words for a
  change a seat was given, and the pull request's own words for a
  change the mirror adopted, so a person reads one story in the queue
  and on the pull request. A row with no title at all answers nil, and
  the rig then falls back to the first line of the commit message."
  [row]
  (when-some [title (not-empty (str/trim (str (get-in row [:data :title]))))]
    (subs title 0 (min (count title) title-ceiling))))

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
        status (bench/ask ctx :status {:repo repo :branch branch})]
    (cond
      (nil? status) (bench/refuse! bench/dark-detail [bench/dark-remedy])
      (bench/refused status) (rig-refusal! "read the worktree" status)
      (zero? (long (or (:dirty status) 0)))
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
          (bench/refused answer) (rig-refusal! "submit" answer)
          :else
          (update row :data merge
                  (cond-> {:branch branch
                           :worktree_dirty 0
                           :rounds (inc (long (or (get-in row [:data :rounds])
                                                  0)))}
                    (:commit answer) (assoc :head_sha
                                            (str (:commit answer))))))))))

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
  ;; has read and put back to work starts its count again.
  (assoc-in row [:data :rounds] 0))

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
   :reads [:principal]
   :open "No door here changes this verdict. A discard that keeps the branch is the model's escape hatch and is always open; dropping the branch throws away a push that GitHub may already hold, so it is a person's hand."
   :explain "A discard that drops the branch removes the worktree and the branch itself. That is a person's act: the model's discard puts the worktree back to the branch head and keeps the branch."}
  [_row inp ctx]
  (if (and (true? (:drop_branch inp)) (= :agent (:type (:principal ctx))))
    (t/deny)
    (t/allow)))

(defguardfn only-a-person-unsticks-a-change
  {:reads [:principal]
   :open "No door here changes this verdict. A stuck change is the house asking a person to look at it, and a model that could put itself back to work would be answering its own question."
   :explain "This change is stuck: it reached the round ceiling, or a seat said it could not finish. A person reads it and puts it back to work."}
  [_row _inp ctx]
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

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
   so that half of the door is a person's."
  {:kind    :change
   :attempt :discard
   :row     {:state :open :data a-pull-request}
   :input   {:drop_branch true}
   :as      {:id "bench-seat" :type :agent}
   :expect  {:refused :only-a-person-drops-the-branch}})

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
   that could put itself back to work would be answering its own
   question, and the round ceiling would stop nothing."
  {:kind    :change
   :attempt :unstick
   :row     {:state :stuck :data (assoc a-pull-request :rounds 3)}
   :as      {:id "bench-seat" :type :agent}
   :expect  {:refused :only-a-person-unsticks-a-change}})

(defscenario the-person-puts-a-stuck-change-back-to-work
  "And the door is really there for the person who read it — one tap,
   and the rounds start again."
  {:kind    :change
   :attempt :unstick
   :row     {:state :stuck :data (assoc a-pull-request :rounds 3)}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

;; ── :change — one pull request, mirrored ────────────────────────────

(defresource change
  {:kind :change
   :plural "changes"
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   ;; GitHub's own three, and the bench's two (waymark-fp62.6.3.2):
   ;; `submitted` is a change a seat has pushed at least one round of,
   ;; and `stuck` is one the house stopped working.
   :states [:open :submitted :stuck :merged :closed]
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
    ;; hidden: the origin LINK below is the affordance, and a raw URL
    ;; in the fields is noise (task_list's own spelling)
    [:url {:optional true :x-display {:hidden true}}
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
     [:maybe [:string {:max 500}]]]]
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
                       "commented"]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Observe" :order 10
               :description "The mirror writes what GitHub says about this pull request now"}}

    :merge
    {:from #{:open :submitted :stuck} :to :merged
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub merged this pull request. The row follows GitHub, so there is no way back: a merged pull request is not reopened."}
     :display {:label "Merged" :order 2
               :description "GitHub merged the pull request"}}

    :close
    {:from #{:open :submitted :stuck} :to :closed
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub closed this pull request without merging it. The way back is GitHub's own reopen, which the mirror follows with its reopen door."}
     :display {:label "Closed" :order 3
               :description "GitHub closed the pull request and merged nothing"}}

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
     :handler observe-the-pull-request
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
     :handler observe-the-pull-request
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

    ;; ── THE BENCH'S OWN DOORS (waymark-fp62.6.3.2) ─────────────────
    ;; These four are NOT the mirror's: a seat under a grant walks
    ;; them, and so does a person. Each one reaches the rig with the
    ;; engine's hand; the model holds the four reading and editing
    ;; powers and never these.

    :submit
    {:from #{:open :submitted} :to :submitted
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
     ;; on this change, and this door writes no other kind. The
     ;; harness closes the sitting with its Stop hook (spec-seat.md
     ;; R-12.17), and that report carries the bill of the round.
     :safety {:idempotent false :reversible false :confirm false
              :one-way "The commit is written and the branch is pushed, so this round is on the record at GitHub. The way forward is another round on the same change, not a way back."}
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
    {:from #{:open :submitted} :to :stuck
     :input [:map
             [:why {:examples ["The same test fails on the base commit, so this change is not the cause and I cannot fix it here."]
                    :x-display
                    {:widget "prose"
                     :label "What is wrong"
                     :help "One sentence for the person who reads this next: what you tried, and what stopped you. It rides the log beside this move."}}
              [:string {:min 1 :max 480}]]]
     :edit {:draft {:shared true :live true}}
     ;; NOT :reversible — `unstick` brings a stuck change back to
     ;; `open` and to no other state, so a stall from `submitted` has
     ;; no transition back to where it started (checks/check-reversible
     ;; asks for one for each :from). The way back is real and it is a
     ;; PERSON'S, which is what the sentence says.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The house stops working this change and waits. The way back is a person's own door, which puts the change in the queue again and starts the rounds from zero."}
     :display {:label "Stuck" :order 8
               :description "Say what stopped you and stop working this change — a person reads it next"}}

    :unstick
    {:from #{:stuck} :to :open
     :guards [only-a-person-unsticks-a-change]
     :handler unstick-the-change
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Back to work" :style :primary :order 1
               :description "Put this change back in the queue — the rounds start again"}}}
   :links [{:rel "origin" :href "{data.url}" :external true
            :summary "The pull request, at GitHub"}]
   :deviations
   ["A self-loop that serves two states is spelled twice: `observe` with `observe_submitted`, `discard` with `discard_submitted`, and `adopt` with `adopt_submitted`. A v10 action declares one `:to`, so one door cannot rest a row where it found it in two different states. The precedent is server/definitions.clj's `measure`/`measure_pilot`, recorded there for the same reason."
    "The round ceiling REFUSES and names the way to `stuck`; it does not move the row itself. Bead waymark-fp62.6.3.2's R-5 reads \"the row moves to stuck and the door names it\", and one transition cannot do both: a handler's refusal rolls back its own transaction, and an action's `:to` is one state. So `under-the-round-ceiling` refuses with `:remedies [:change/stall]`, and `stall` — a real door, with the seat's own sentence on it — makes the move."
    "The clean-worktree check is the HANDLER's, not a guard's. The only honest reading of \"is there anything to submit\" is the rig's own `status`, and a guard that reached a wire would judge differently on a day Gate was dark. The handler asks, and refuses with a 409 that carries its remedy, so the refusal counts on the sitting exactly as a guard's does."]
   :scenarios [a-model-does-not-move-a-pull-request
               the-source-moves-the-pull-request
               a-model-does-not-drop-the-branch
               a-model-discards-its-own-edits
               a-model-does-not-unstick-itself
               the-person-puts-a-stuck-change-back-to-work]})
