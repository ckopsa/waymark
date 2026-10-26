(ns factorykit.change
  "The change kind's shared core, and the builder a factory composes
  its own change kind through (epic 459ed2eb, step 2a).

  A FACTORY'S CHANGE IS THE CORE AND ITS OWN EXTENSION. The core is
  what every factory's change has: the states, the tomb, the one-row
  index, the fields the bench and the stall write, and the cracks law.
  The extension is a defresource map of everything else. `build`
  answers the map `waymark10.dsl/defresource` takes.

  THE SOURCE'S DOORS ARE THE CORE'S (step 2b-1, ticket 47e2c880):
  adopt, observe, fail, recover, merge and close, behind the wall
  `the-mirror-writes-this-row`. `adopt` and `observe` are `:to :same`
  doors over every state they serve, and the copies they once were
  (`observe_submitted`, `adopt_stuck` ...) answer for one release
  through the core's `:aliases` (ticket bc6a8732). The
  doors whose handler reads what the change was built for (merge,
  close, fail and recover) have no handler here: the extension's
  `:handlers` gives each one its own, so the core holds no ticket
  code. A factory passes every other door as its extension, and the
  guards and the handlers ride on the doors that name them. Later
  steps move those.

  THE STALL AND THE WAY BACK ARE THE CORE'S TOO (step 2b-2, ticket
  eb500c47): stall, unstick, unstick_submitted and rebranch, with the
  guards that say who unsticks and which landing a change takes.
  stall, unstick and unstick_submitted move the ask as well as the
  row, so they have no handler here either: the extension's
  `:handlers` gives each one its own, and `stalled` and `unstalled`
  are the row's part of it. A factory with no ask to move names none,
  and `build` gives the three doors `row-handlers`, the row's part
  alone (ticket d42cf49c).

  A CLOSED CHANGE WITH NO PULL REQUEST IS NO TOMB IN ANY FACTORY (step
  2c, ticket 48f9605b): close_without_pr and reopen are the core's,
  with the guards that say which change the first door is for and
  whose hand closes it. Each takes its handler from the extension's
  `:handlers`, and `reopened` is the row's part of the second. `sweep!`
  closes the changes with no pull request whose work is over, through
  close_without_pr, which moves no ask.

  A factory in this repository depends on the kit by `:local/root`. A
  factory outside it depends on it by `:git/url` with
  `:deps/root \"factorykit\"`."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]
            [waymark10.verdict :as verdict]))

(set! *warn-on-reflection* true)

;; ── the wall on the source's doors ──────────────────────────────────

(defguardfn the-mirror-writes-this-row
  {:reads [:principal]
   :hide true
   :explain "The mirror writes this row. A person and a model read it."}
  [_row _inp ctx]
  ;; The Mirror weave's own test (waymark10.server.mirror/system-only),
  ;; written out here because these kinds keep their own machines.
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(defn- landing-names
  "The `landing:<step>` names among the row's red checks: a push that
  did not land, which no check GitHub ran can judge."
  [row]
  (filterv #(str/starts-with? (str %) "landing:")
           (get-in row [:data :failing_checks])))

(defguardfn green-checks-do-not-recover-a-landing
  {:reads []
   :judges [:head_sha]
   :vars [:landing]
   :open "No door here changes this verdict. The source reads the landing itself and recovers the change once a push lands, and the seat's next submit is a new round."
   :explain "This change is failing on its own landing, {landing}, and a green pull request does not undo that: the push never landed, so the checks GitHub ran are an older head's."}
  [row inp _ctx]
  ;; only a verdict that names a head — the pull request's, through its
  ;; subscription (ticket 995570f0) — is judged here; the source's own
  ;; walk reads the landing before it recovers
  (let [landing (landing-names row)]
    (if (and (some? (:head_sha inp)) (seq landing))
      (t/deny {:vars {:landing (str/join ", " landing)}})
      (t/allow))))

;; ── who puts a stuck change back, and where it lands ────────────────

(defguardfn a-person-or-their-delegate-unsticks
  {:reads [:principal]
   :open "No door here changes this verdict. A stuck change is the house asking a person to look at it, and a model alone that could put itself back to work would be answering its own question. A person taps unstick, or unstick_submitted for a change with a pull request, or a delegate acting for one does. After a seat's stall, which sends the ticket to draft, grooming the ticket again puts a change with no pull request back to work at the groom itself, and one with a pull request back under review at the groom too; a change stuck at the round ceiling leaves its ticket in review, where grooming does not serve."
   :explain "This change is stuck: it reached the round ceiling, or a seat said it could not finish. A person, or a delegate acting for one under a grant the person approved, reads it and puts it back to work."}
  [_row _inp ctx]
  ;; the wall is against a model ALONE, and a delegate that names whom
  ;; it acts for is the person's hand.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (and (= :agent type) (str/blank? (str acts-for)))
      (t/deny)
      (t/allow))))

(defn has-a-pull-request? [row]
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

;; ── GitHub's word that the pull request opened (ticket 20a962be) ────

(defguardfn an-outside-event-opens-it
  {:reads [:principal]
   :hide true
   :explain "GitHub's own event writes the pull request it opened onto this change. A person and a model read it."}
  [_row _inp ctx]
  ;; the integration's hand, itself or a party it speaks for
  ;; (waymark10.server.mirror/stale-caller?, less the engine's own)
  (let [p (:principal ctx)]
    (if (some #(str/starts-with? (str %) "integration:") (remove nil? [(:id p) (:via p)]))
      (t/allow)
      (t/deny))))

(defguardfn the-number-is-written-once
  {:reads []
   :open "No door here changes this verdict. The number a change learned stands, and a later event about the same pull request refreshes its pull_request row instead."
   :explain "This change already has its pull request's number, and an event does not write it again."}
  [row _inp _ctx]
  (if (has-a-pull-request? row) (t/deny) (t/allow)))

;; ── the ending of a change the forge never saw ──────────────────────

(defguardfn no-pull-request-to-close
  {:reads []
   :open "No door here changes this verdict. A change with a pull request ends at GitHub: close the pull request there, and the mirror closes the row with its own close."
   :explain "This change has a pull request, so GitHub owns its ending. Close the pull request at GitHub and the mirror follows; this door is for a change GitHub never saw."}
  [row _inp _ctx]
  (if (has-a-pull-request? row) (t/deny) (t/allow)))

(defguardfn the-engine-closes-a-stopped-change
  {:reads [:principal]
   :hide true
   :explain "A failing change and a stuck one are ended by the engine's sweep when their work is over. A person puts a stuck change back to work, and closes one that is open or submitted."}
  [row _inp ctx]
  ;; `close_without_pr` leaves `failing` and `stuck` for `sweep!` and
  ;; for no other hand: a stuck change is the house asking a person to
  ;; look at it, and the doors it offers that person stay the way back.
  (if (and (contains? #{:failing :stuck} (some-> (:state row) name keyword))
           (not= :system (:type (:principal ctx))))
    (t/deny)
    (t/allow)))

(defguardfn only-a-person-closes-a-change
  {:reads [:principal :within]
   :hold true
   :vars [:title :branch]
   :open "No door clears this one. The call waits as a held_call for the person's tap: ending a change a seat was building is the person's judgment, and the person's Allow runs it exactly as written."
   :explain "Closing {title} on {branch} is held for the person's tap: the call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row _inp ctx]
  ;; every hand but an agent's passes, a delegate's included in the
  ;; hold (the mayor asks, the person taps), and the one agent close
  ;; this admits is the engine's replay of the held call its person
  ;; allowed. The engine's own hand passes, so `sweep!` closes here.
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :change :close_without_pr (:id row)) (t/allow)
    :else (t/deny {:vars {:title (str (get-in row [:data :title]))
                          :branch (str (or (get-in row [:data :branch])
                                           (get-in row [:data :head_branch])))}})))

;; ── what a pull request writes back onto the row ────────────────────

(defhandler observe-the-pull-request [row inp ctx]
  ;; The source hands the facts it read. A fact it did not read is
  ;; absent, and absent means silent: the stored value stands. The
  ;; machine advances the state, never this handler.
  ;;
  ;; A HEAD THAT MOVES takes back what judged the old one (ticket
  ;; 35600491): see `waymark10.verdict/reopen-stale-verdicts!`.
  (let [head (some-> (:head_sha inp) str not-empty)]
    (when (and head (not= head (some-> (get-in row [:data :head_sha]) str not-empty)))
      (verdict/reopen-stale-verdicts! ctx :change (:id row) head)))
  (update row :data merge (into {} (remove (comp nil? val)) inp)))

(defhandler adopt-the-pull-request [row inp _ctx]
  ;; The identity lands as `observe` writes the facts, and the forge
  ;; pass's note that nobody had adopted the landed pull request goes
  ;; with the wait it described (ticket 58e706d6).
  (-> row
      (update :data merge (into {} (remove (comp nil? val)) inp))
      (update :data assoc :unadopted_since nil :adoption_note nil)))

(defhandler link-the-pull-request [row inp _ctx]
  ;; The change's own `pull_request` row, by its id (spec-change §3,
  ;; R-C.4; ticket 56c97770). The number, the head and the mergeable
  ;; verdict stay on the change until spec piece 6 (R-C.5).
  (assoc-in row [:data :pull_request] (some-> (:pull_request inp) str not-empty)))

(defhandler write-the-opened-pull-request [row inp _ctx]
  ;; GitHub's identity for the pull request, and its head: these four
  ;; fields and no other (ticket 20a962be)
  (update row :data merge
          (into {} (remove (comp nil? val))
                (select-keys inp [:change_id :number :url :head_sha]))))

;; ── what a stall writes on the row, and what the way back clears ────

(defn stalled
  "A stalled change says why on the row itself (ticket b6c8ea04): a
  transition's stored inputs are not served, so a reason kept only in
  the log was a blind stall to whoever reads the row next. This is the
  row's part of a factory's `stall` handler; what the stall does to
  the ask is the factory's."
  [row inp ctx]
  (update row :data assoc
          :stall_reason (some-> (:why inp) str not-empty)
          :stalled_at (:now ctx)
          :stalled_by (some-> (:id (:principal ctx)) str not-empty)))

(defn unstalled
  "A change back out of `stuck` no longer says why it stopped: the
  sentence was for the person who put it back, and the log keeps it."
  [row]
  (update row :data assoc :stall_reason nil :stalled_at nil :stalled_by nil))

(defn reopened
  "A closed change with no pull request starts again (ticket ab6af67f):
  the rounds start from zero and the row no longer says why it stopped.
  A change WITH a pull request is the forge's reopen, and the row
  stands as it was. This is the row's part of a factory's `reopen`
  handler; a field of the factory's own that says what closed the
  change is the factory's to clear."
  [row]
  (if (has-a-pull-request? row)
    row
    (-> row unstalled (assoc-in [:data :rounds] 0))))

;; THE ROW'S PART ALONE (ticket d42cf49c). A factory that has no ask to
;; move names no handler for the stall or the way back, and a door with
;; no handler writes nothing: the stall kept no reason. `build` puts
;; these on the three doors an extension's `:handlers` leaves out.

(defhandler write-the-stall [row inp ctx]
  (stalled row inp ctx))

(defhandler clear-the-stall [row _inp _ctx]
  ;; a person looked at it, so the rounds start again
  (-> row unstalled (assoc-in [:data :rounds] 0)))

;; AND OF THE PR-LESS ENDING (ticket 48f9605b): the close's sentence
;; rides the log, and the core has no field of its own for it.

(defhandler let-the-change-go [row _inp _ctx]
  row)

(defhandler start-the-change-again [row _inp _ctx]
  (reopened row))

(def row-handlers
  "The handler `build` gives a core door the extension's `:handlers`
  does not name: the row's part of the stall and of the way back, and
  of the PR-less ending and its reopen."
  {:stall write-the-stall
   :unstick clear-the-stall
   :unstick_submitted clear-the-stall
   :close_without_pr let-the-change-go
   :reopen start-the-change-again})

;; ── the changes with no pull request whose work is over ─────────────

(def why-chars
  "The most characters `close_without_pr` takes as its sentence."
  480)

(defn sweep!
  "THE PR-LESS SWEEP (step 2c, ticket 48f9605b). A factory's pass calls
  it with the changes it read. Each of `rows` that has no pull request
  and for which `ended` answers a sentence is closed through
  `close_without_pr` with that sentence as its `why`; a row `ended`
  answers nil for is left, and so is every change with a pull request,
  which is the forge's to end. The door moves no ask, so the sweep
  never does.

  `close!` is the factory's own hand: it takes the row, the door and
  the input, and walks the door as the engine. The door is an edit
  door, so that hand names the version of the row it read. A close it
  throws from
  costs that row this sweep and nothing else, and `log-fn` is told.
  → how many it closed."
  [{:keys [rows ended close! log-fn]}]
  (reduce
   (fn [n row]
     (if-some [why (when-not (has-a-pull-request? row)
                     (some-> (ended row) str not-empty))]
       (try
         (close! row :close_without_pr
                 {:why (subs why 0 (min (count why) (long why-chars)))})
         (inc (long n))
         (catch Exception e
           (when log-fn
             (log-fn "the change " (:id row) " was not swept ("
                     (ex-message e) ")"))
           n))
       n))
   0
   rows))

(def core
  "What every factory's change has. `:fields` is the entries of the
  schema's `:map`, in the core's own order."
  {;; GitHub's own three, and the bench's two (waymark-fp62.6.3.2):
   ;; `submitted` is a change a seat has pushed at least one round of,
   ;; and `stuck` is one the house stopped working. `failing` is a
   ;; submitted change whose required checks went red (d1742908).
   :states [:open :submitted :failing :stuck :merged :closed]
   ;; ONE TOMB. A merged pull request is finished. A closed one is
   ;; not: GitHub reopens it, so `reopen` is a real door.
   :terminal #{:merged}
   ;; ONE ROW PER PULL REQUEST, enforced by an index, and at most one
   ;; change linked to each `pull_request` row (R-C.4, ticket 56c97770)
   :unique [[:change_id] [:pull_request]]
   ;; A SUBMITTED CHANGE WAITS ON ITS REQUIRED CHECKS (ticket d9edac4e).
   ;; They are GitHub's and no row here, so the wait names no kind:
   ;; green lets the gate merge it, and red moves it to `failing`,
   ;; which sends its ticket back to the seat that built it.
   :waits [{:on :checks :in #{:submitted}
            :outcomes {:green :release :red :return}}]
   ;; THE EXITS NO SEAT TAKES AT A CHANGE DOOR (ticket 4b0460cc), so the
   ;; cracks audit does not report `submitted` as a state seats leave on
   ;; a cadence. The forge's mirror walks all three: the gate's merge,
   ;; a person's close on GitHub, and the red check that is `fail`.
   :left-by [{:in #{:submitted} :by :mirror :via :merge :to :merged}
             {:in #{:submitted} :by :mirror :via :close :to :closed}
             {:in #{:submitted} :by :mirror :via :fail :to :failing}]
   ;; AND THE ARRIVAL THAT IS NO CHANGE DOOR. A change is born open from
   ;; a groomed ticket (`born_from`), so the seat that builds is brought
   ;; to it by its wake on the ticket's groom, not by a change wake.
   :reached-by [{:in #{:open} :by {:kind :ticket :action :groom}}]
   ;; HOW LONG A CHANGE MAY REST (ticket 21b49b28), in seconds: open and
   ;; submitted six hours, failing four, stuck a day. The one-day
   ;; default let a change sit thirty hours at the front of its merge
   ;; line before anyone was told.
   :expected-age {:open 21600 :submitted 21600 :failing 14400 :stuck 86400}
   :fields
   [;; :raw because it is a LABEL and not prose — an opaque address is
    ;; shown exactly as it arrived. It also tells the assembly's ref
    ;; lint the truth: a field named <kind>_id for a kind this engine
    ;; serves reads as a reference by convention (checks-assembly's
    ;; id-target), and this one is not a reference to another change —
    ;; it is THIS row's own address at GitHub.
    [:change_id {:x-ref {:address true}
                 :x-display
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
    ;; not invent. The source writes it at the adoption, with `adopt`.
    ;; Until then the summary line renders it as an em-dash, which is
    ;; the framework's own word for "not said yet".
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
              :not-a-ref "A GitHub login, or the name of the seat that opened it: a handle, not a row id or a principal here."
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
    ;; WHAT THE SOURCE'S DOORS WRITE (ticket 354a7dc3). `observe`, the
    ;; one `:to :same` door, writes head_sha to missing_checks_head, and
    ;; the door is the core's, so the fields are the core's too.
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
    ;; ── a head that lacks a required check (ticket 498a089e) ──────
    ;; the forge pass writes these; the merge line reads them to bring
    ;; forward a front the rig would leave `waiting` forever
    [:missing_checks {:optional true
                      :x-display
                      {:label "Required checks that never ran"
                       :help "The policy's required checks with no run at all on the head — not merely pending. A branch cut before its base gained a workflow never runs that workflow's check."}}
     [:maybe [:vector [:string {:max 200}]]]]
    [:behind_base {:optional true
                   :x-display
                   {:label "Behind its base"
                    :help "True when the head lacks the base branch's current head, as the forge's compare of base and head answered."}}
     [:maybe :boolean]]
    [:base_compared_at {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 40}]]]
    ;; the head the two facts above were read at (ticket 716d12ba): the
    ;; merge line trusts them only while it is still the row's head_sha
    [:missing_checks_head {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
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
    ;; the stall's own sentence (ticket b6c8ea04): written by `stall`,
    ;; cleared by every door that takes the change out of `stuck`
    [:stall_reason {:optional true
                    :examples ["The same test fails on the base commit, so this change is not the cause and I cannot fix it here."]
                    :x-display
                    {:widget "prose"
                     :label "Why it was stalled"
                     :help "The sentence the stall door was given: what the seat tried, and what stopped it. Empty unless the change is stuck by a stall."}}
     [:maybe [:string {:max 480}]]]
    [:stalled_at {:optional true
                  :x-display
                  {:label "When it was stalled"
                   :help "The moment the stall door moved this change to stuck. Empty unless the change is stuck by a stall."}}
     [:maybe :waymark/instant]]
    [:stalled_by {:optional true
                  :x-ref {:principal true}
                  :x-display
                  {:label "Who stalled it"
                   :help "The principal, usually a seat, that walked the stall door. Empty unless the change is stuck by a stall."}}
     [:maybe [:string {:max 200}]]]
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
    ;; clears both. `unadopted_since` is the mirror's first-sight stamp,
    ;; NOT a landing: no bench landing or merge writes it.
    [:unadopted_since {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 64}]]]
    [:adoption_note {:optional true
                     :examples ["landed as #7 on ckopsa/waymark but no pull request row adopted it; head bench/58e706d6"]
                     :x-display
                     {:widget "prose"
                      :label "A pull request nobody adopted"
                      :help "The bench's landing opened a pull request for this change, and the house's row never took its number, so neither the house's merge nor a merge ask sees it. Cleared when the row adopts the pull request."}}
     [:maybe [:string {:max 500}]]]
    ;; hidden: the origin LINK is the affordance, and a raw URL in the
    ;; fields is noise (task_list's own spelling). The adoption writes it.
    [:url {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]
    ;; ── where a seat-born change CAME FROM (waymark-fp62.6.3.14) ──
    ;; The walk kind, a colon and the walk row's own id — the words
    ;; `change_id` carries at the mint. The adoption overwrites
    ;; `change_id` with GitHub's identity, so the origin needs a field
    ;; the adoption does not touch: the merge reads it to finish the
    ;; task this change was built for. Hidden, like every other fact
    ;; the engine writes and nobody types.
    [:born_from {:optional true :x-ref {:address true} :x-display {:hidden true}}
     [:maybe [:string {:max 250}]]]
    ;; ── the change's `pull_request` row (spec-change §3, R-C.4; ticket
    ;; 56c97770) ──
    ;; Its id, or nil before the pull request opens and the mirror holds
    ;; its row. Written by the hidden `link` door and by nothing else,
    ;; and `:unique`: a pull request has at most one change. A ref, so a
    ;; factory that builds on the core declares the `pull_request` kind.
    [:pull_request {:optional true :kind :pull_request :x-display {:hidden true}}
     [:maybe :waymark/ref]]]
   ;; THE COPIES THE FOLD RETIRED (ticket bc6a8732). Each old name runs
   ;; its door for one release, and the log says it came by the alias.
   :aliases {:observe_submitted :observe
             :observe_failing :observe
             :adopt_submitted :adopt
             :adopt_failing :adopt
             :adopt_stuck :adopt}
   ;; THE SOURCE'S DOORS (step 2b-1, ticket 47e2c880). Each one is the
   ;; mirror's, and each one is hidden from every hand but the engine's.
   :actions
   {;; THE MIRROR'S REFRESH. A self-loop on every state a pull request
    ;; goes on moving under: the row stays where it is, and these are
    ;; the facts that moved. A change a seat has pushed goes on moving
    ;; — the checks run, a reviewer answers, the head moves — and so
    ;; does a red one, when a person pushes to it.
    :observe
    {:from #{:open :submitted :failing} :to :same
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
             [:rerun_note {:optional true} [:maybe [:string {:max 240}]]]
             ;; the forge pass's read of a head that lacks a required
             ;; check (ticket 498a089e)
             [:missing_checks {:optional true}
              [:maybe [:vector [:string {:max 200}]]]]
             [:behind_base {:optional true} [:maybe :boolean]]
             [:base_compared_at {:optional true} [:maybe [:string {:max 40}]]]
             [:missing_checks_head {:optional true}
              [:maybe [:string {:max 64}]]]]
     ;; :edit-shape — the input MIRRORS the document on purpose. This
     ;; is not a person editing a row; it is the authority restating
     ;; what it holds, and a prefill for a hidden door is scaffolding
     ;; for nobody.
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Observe" :order 1
               :description "The mirror writes what GitHub says about this pull request now"}}

    ;; THE MOVES GITHUB MAKES. NO HANDLER HERE: what a merge finishes
    ;; and what a close sends back is the factory's own ask, so the
    ;; extension's `:handlers` gives `merge`, `close`, `fail` and
    ;; `recover` their handler. The `:one-way` sentences say what the
    ;; move does with it.
    :merge
    {:from #{:open :submitted :failing :stuck} :to :merged
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub merged this pull request. The row follows GitHub, so there is no way back: a merged pull request is not reopened. The ask this change was born from — a task, or a ticket that has not ended — is completed with it, and another change built for that ask on the same branch is closed."}
     :display {:label "Merged" :order 2
               :description "GitHub merged the pull request"}}

    :close
    {:from #{:open :submitted :failing :stuck} :to :closed
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub closed this pull request without merging it, and a ticket this change was built for goes back to the queue. The way back is GitHub's own reopen, which the mirror follows with its reopen door."}
     :display {:label "Closed" :order 3
               :description "GitHub closed the pull request and merged nothing"}}

    ;; THE ENDING OF A CHANGE GITHUB NEVER SAW (ticket be2c2c16), and
    ;; the core's since step 2c: the mirror's `close` follows a pull
    ;; request, so a row with no number had no door to its end. A
    ;; person's, held when a model asks, and the engine's own in
    ;; `sweep!`, which alone takes it out of `failing` and `stuck`. It
    ;; leaves the ask where it stands. NO HANDLER HERE: what the row
    ;; drops and what it says of its ending are the factory's fields.
    :close_without_pr
    {:from #{:open :submitted :failing :stuck} :to :closed
     :input [:map
             [:why {:examples ["A duplicate: its ticket merged under another row."]
                    :x-display
                    {:widget "prose"
                     :label "Why it ends"
                     :help "One sentence for the next reader: why this change is let go. It rides the log beside this move. It is at most 480 characters."}}
              [:string {:min 1 :max 480}]]]
     :edit {:draft {:shared true :live true}}
     :guards [no-pull-request-to-close
              the-engine-closes-a-stopped-change
              only-a-person-closes-a-change]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The change is let go and the house stops working it; its ticket is not touched. Nothing here brings it back to open; the house opens it again only when its ticket is put in the queue again after this close and a code seat sits down to it."}
     :display {:label "Close" :style :danger :order 30
               :description "Let go of a change that never opened a pull request — its ticket stays where it is"}}

    ;; THE WAY BACK FROM `closed`, which is why `closed` is no tomb. NO
    ;; HANDLER HERE: `reopened` is the row's part, and the factory's
    ;; own handler clears what its own fields say of the close.
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
    ;;
    ;; It LEAVES the state, whichever live state the row stands in. A
    ;; change a seat has SUBMITTED is exactly the change whose push
    ;; opened the pull request, so `submitted` is where the adoption
    ;; lands almost every time; a `failing` or `stuck` row is adopted
    ;; too (ticket 3c59c688), because a pull request the house itself
    ;; opened is the house row's whatever its state.
    :adopt
    {:from #{:open :submitted :failing :stuck} :to :same
     :guards [the-mirror-writes-this-row]
     :handler adopt-the-pull-request
     :input [:map
             [:change_id {:x-ref {:address true}
                          :x-display {:raw true}}
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

    ;; ── THE LINK (spec-change §3, R-C.4; ticket 56c97770) ────────────
    ;; The mirror writes the id of the change's own `pull_request` row,
    ;; once that row exists, and no other door writes it. Not named
    ;; `adopt`: that is also the engine's own action. `pull_request` is
    ;; `:unique`, so a second change linking a pull request another
    ;; change holds is refused. It leaves the state, on every live one.
    :link
    {:from #{:open :submitted :failing :stuck} :to :same
     :guards [the-mirror-writes-this-row]
     :handler link-the-pull-request
     :input [:map
             [:pull_request {:kind :pull_request :x-display {:hidden true}}
              :waymark/ref]]
     ;; the mirror may name any pull_request row: the wall is the
     ;; mirror's, and the unique index keeps one change per row
     :waives #{:edit-shape :ref-unjudged}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Linked" :order 12
               :description "The mirror links this change to its pull request's own row"}}

    ;; ── THE OPENING (ticket 20a962be) ────────────────────────────────
    ;; GitHub's `pull_request` event names the branch a seat's change was
    ;; built on, and this door writes the pull request's identity onto
    ;; that change at once, so nothing downstream waits for the forge
    ;; pass's `adopt`. Not named `adopt`: that door goes in a later
    ;; piece, and its name would then un-shadow the engine's own. Only
    ;; the integration's hand walks it, on a change with no number yet.
    :opened
    {:from #{:open :submitted :failing :stuck} :to :same
     :guards [an-outside-event-opens-it the-number-is-written-once]
     :handler write-the-opened-pull-request
     :input [:map
             [:change_id {:x-ref {:address true}
                          :x-display {:raw true}}
              [:string {:min 1 :max 250}]]
             [:number [:int {:min 1}]]
             [:url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]
             [:head_sha {:optional true :x-display {:raw true}}
              [:maybe [:string {:max 64}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Opened" :order 13
               :description "GitHub's event writes the pull request it opened for this change onto the row"}}

    ;; ── THE CHECKS' VERDICT (ticket d1742908) ──────────────────────────
    ;; The source reads the checks on a submitted change's head and
    ;; walks one of these. They are the mirror's and hidden: no seat
    ;; and no person says a change is red.
    :fail
    {:from #{:submitted} :to :failing
     :guards [the-mirror-writes-this-row]
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
     :guards [the-mirror-writes-this-row
              green-checks-do-not-recover-a-landing]
     ;; the pull request's green verdict names the head it judged
     ;; (ticket 995570f0); the source's own walk names none
     :input [:map
             [:head_sha {:optional true} [:maybe [:string {:max 64}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The required checks are green on the head the row names now, so the change is submitted again and the house merges it as it would any other."}
     :display {:label "Green again" :order 16
               :description "The required checks went green on a later head"}}

    ;; ── THE STALL AND THE WAY BACK (step 2b-2, ticket eb500c47) ─────
    ;; NO HANDLER on `stall`, `unstick` and `unstick_submitted`, for
    ;; `merge`'s reason: each one moves the ask the change was built
    ;; for. The extension's `:handlers` gives each its own, which
    ;; writes the row with `stalled` or `unstalled`.
    :stall
    {:from #{:open :submitted :failing} :to :stuck
     :input [:map
             [:why {:examples ["The same test fails on the base commit, so this change is not the cause and I cannot fix it here."]
                    :x-display
                    {:widget "prose"
                     :label "What is wrong"
                     :help "One sentence for the person who reads this next: what you tried, and what stopped you. It rides the log beside this move. It is at most 480 characters."}}
              [:string {:min 1 :max 480}]]]
     :edit {:draft {:shared true :live true}}
     ;; NOT :reversible — `unstick` brings a stuck change back to
     ;; `open` and to no other state, so a stall from `submitted` has
     ;; no transition back to where it started (checks/check-reversible
     ;; asks for one for each :from). The way back is real and it is a
     ;; PERSON'S (or their delegate's), which is what the sentence says.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The house stops working this change and waits, and the ticket it was built for goes to draft. The way back is a person's groom of that ticket, or their delegate's, which puts the change back to work at the groom itself with its rounds at zero, or back under review there when it has a pull request; unstick, or unstick_submitted for a change with a pull request, puts the change alone back to work."}
     :display {:label "Stuck" :order 8
               :description "Say what stopped you and stop working this change — a person reads it next"}}

    ;; `unstick` is spelled twice because it lands in two states
    ;; (ticket 6bdaf6fe): a change with no pull request goes back to
    ;; `open` for its next round, and a change with one goes back to
    ;; `submitted`, where the forge and merge passes read it. A v10
    ;; action declares one `:to`, so each door carries the guard that
    ;; says which change it is for.
    :unstick
    {:from #{:stuck} :to :open
     :guards [a-person-or-their-delegate-unsticks
              the-change-has-no-pull-request]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Back to work" :style :primary :order 1
               :description "Put this change back in the queue — the rounds start again"}}

    :unstick_submitted
    {:from #{:stuck} :to :submitted
     :guards [a-person-or-their-delegate-unsticks
              the-change-has-a-pull-request]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The change is under review again, and the house reads its checks on the next pass: green merges it, red sends it back to the seat. The rounds start from zero."}
     :display {:label "Back to review" :style :primary :order 2
               :description "Put this pull request back under review — the house merges it green"}}

    ;; ── THE BRANCH, MINTED AGAIN (bead waymark-fp62.6.3.11) ───────
    ;; A seat-born row writes its head branch at BIRTH, from the
    ;; policy's pattern. A person who restates the pattern does not
    ;; reach a row that is already here, and the next sitting opens
    ;; the same bad branch. So the sit mints the branch again and
    ;; writes it through this door. It is the mirror's, hidden like
    ;; the adoptions: a change that was pushed once keeps its branch,
    ;; because the forge holds it, and the sit is what judges that.
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
     ;; the adoptions do.
     :waives #{:edit-shape}
     ;; :one-way, and not a confirm: nobody taps this door. The old
     ;; branch was never pushed, so there is nothing on the forge to
     ;; lose.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The house works this change on the new branch from now. The old branch was never pushed, so nothing is lost."}
     :display {:label "Rebranch" :order 13
               :description "The house mints this change's branch again from the repository policy's pattern"}}}})

(def ^:private law
  "The cracks law: what a change waits on, who moves it at no change
  door, and how long it may rest."
  [:waits :left-by :reached-by :expected-age])

(defn- refuse! [why data]
  (throw (ex-info (str "factorykit.change/build: " why) data)))

(defn- not-twice!
  "Refuses an extension that names what the core already named."
  [what core-names own-names]
  (when-some [both (seq (filter (set core-names) own-names))]
    (refuse! (str "the extension redefines the core's " what " "
                  (pr-str (vec both)) ". A core name is the kit's to define.")
             {:redefined (vec both)})))

(defn- in-order
  "The fields in the order the extension said. A field the order does
  not name follows the named ones, in its own place among the rest."
  [fields order]
  (let [by-name (into {} (map (juxt first identity)) fields)
        said (set order)]
    (when-some [unknown (seq (remove by-name order))]
      (refuse! (str ":field-order names " (pr-str (vec unknown))
                    ", and neither the core nor the extension has that field.")
               {:unknown (vec unknown)}))
    (into (mapv by-name (distinct order)) (remove (comp said first)) fields)))

(defn- with-the-law
  "Each law key: the core's, with the extension's over it. Two maps
  merge and the extension's entries win; any other value of the
  extension's stands in place of the core's."
  [declaration core extension]
  (reduce (fn [declaration k]
            (let [ours (get core k)
                  theirs (get extension k)
                  v (cond
                      (nil? theirs) ours
                      (and (map? ours) (map? theirs)) (merge ours theirs)
                      :else theirs)]
              (cond-> declaration (some? v) (assoc k v))))
          declaration
          law))

(defn- with-the-handlers
  "The core's doors, each with the handler the extension's `:handlers`
  names for it. A core door that has its own handler keeps it. The
  stall and the way back take the kit's `row-handlers` when the
  extension names none, so a stall always says why on the row."
  [doors handlers]
  (when-some [unknown (seq (remove (set (keys doors)) (keys handlers)))]
    (refuse! (str ":handlers names " (pr-str (vec unknown))
                  ", and the core has no such door.")
             {:unknown (vec unknown)}))
  (when-some [taken (seq (filter #(contains? (get doors %) :handler) (keys handlers)))]
    (refuse! (str ":handlers names " (pr-str (vec taken))
                  ", and the core's own handler is on that door.")
             {:taken (vec taken)}))
  (reduce-kv (fn [doors door handler] (assoc-in doors [door :handler] handler))
             doors
             (merge (into {}
                          (filter (fn [[door _]]
                                    (and (contains? doors door)
                                         (not (contains? (get doors door) :handler)))))
                          row-handlers)
                    handlers)))

(defn build
  "The defresource map of a factory's change: `core` and the factory's
  own `extension`, which is a defresource map without the core's part.

  - `:states` and `:terminal` are the core's. An extension that names
    either is refused.
  - `:unique` is the core's, then the extension's.
  - The fields are the core's `:fields`, then the entries of the
    extension's `:schema`, which is a `[:map ...]` with no properties.
    `:field-order`, a vector of field names, says another order.
  - The doors are the core's `:actions` and the extension's. A guard
    and a handler ride on the door that names them.
  - `:handlers`, a map of a core door's name to a handler, gives a
    core door that has no handler the extension's own. A door the
    core does not have, and one with the core's handler, is refused.
    `stall`, `unstick` and `unstick_submitted` take the kit's
    `row-handlers` when `:handlers` names none.
  - An extension may not redefine a core field or a core door.
  - The cracks law (`:waits`, `:left-by`, `:reached-by`,
    `:expected-age`) is the core's, with the extension's over it.
  - `:aliases`, the old door names a fold retired, is the core's
    with the extension's beside it.
  - Every other key is the extension's."
  [core extension]
  (let [{:keys [schema field-order]} extension
        [tag & own-fields] schema]
    (when-some [named (seq (filter #(contains? extension %) [:states :terminal]))]
      (refuse! (str "the extension names " (pr-str (vec named))
                    ", and the machine's states and its tomb are the core's.")
               {:named (vec named)}))
    (when (and (some? schema)
               (or (not= :map tag) (map? (first own-fields))))
      (refuse! "the extension's :schema is not a [:map ...] of field entries with no properties."
               {:schema-tag tag}))
    (not-twice! "field" (map first (:fields core)) (map first own-fields))
    (not-twice! "door" (keys (:actions core)) (keys (:actions extension)))
    (-> (dissoc extension :field-order :handlers)
        (merge (select-keys core [:states :terminal]))
        (assoc :aliases (merge (:aliases core) (:aliases extension))
               :unique (vec (distinct (concat (:unique core) (:unique extension))))
               :schema (into [:map]
                             (in-order (into (vec (:fields core)) own-fields)
                                       field-order))
               :actions (merge (with-the-handlers (:actions core) (:handlers extension))
                               (:actions extension)))
        (with-the-law core extension))))
