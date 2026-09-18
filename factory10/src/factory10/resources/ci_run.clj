(ns factory10.resources.ci-run
  "The ci_run: one red check run, and the tree that classifies it
  (bead waymark-fp62.6.2, R-4).

  THE DECISION TREE IS A KIND. A seat walks a tree. For each red run
  it reads the log tail and says which of three things went wrong:
  the infrastructure broke, the base branch was already red, or this
  change is wrong. A tree with branches is a state machine with
  several doors from one state, which is an ordinary declaration. The
  tree lives here, in code, BECAUSE IT IS LAW. The seat that walks it
  lives in a row, because a seat is fluid. Nothing here names a seat.

  THE ENVELOPE IS THE WALK. At `red` the envelope offers three doors
  and nothing else. At `classified` it offers one door, and only to a
  person. A model cannot reclassify its own verdict, because the door
  is ABSENT for it — not discouraged, absent. This is inbox_item's
  shape (workqueue10), one domain over.

  EACH CLASSIFY DOOR DEMANDS THE REMEDY. The door takes one sentence:
  what somebody should do about this red run. A door that took the
  verdict and nothing else would let a seat file three words a day and
  teach the house nothing. The sentence is required, so a classify
  with no remedy refuses and the refusal names the field.

  `reclassify` IS THE PERSON'S DOOR AND NOBODY ELSE'S. A
  reclassification is a CORRECTION: it is how the house counts what
  the classifier got wrong, and the count per transition is what
  decides whether a cheaper model holds this seat. An agent that could
  correct its own verdict could answer its own question, and the count
  would measure nothing. So `only-a-person-reclassifies` refuses every
  agent hand. It is not grantable: a grant that opened this door would
  let the house buy back its own correction count.

  THE LABEL IS THE MIRROR'S JOB, NOT THE MODEL'S (R-5). A classified
  run earns one label on its pull request: ci:infra, ci:base-red or
  ci:this-change. The seat never holds a GitHub power. The source
  reads the verdict, pushes the label, and then walks `stamp_label` —
  a hidden door on the classified row. That door IS the effect point
  this bead declares: it writes `pushed_label` and `labelled_at`, so the
  transition log carries the push and a reader can tell a pushed label
  from an intended one. The GitHub call itself is bead
  waymark-fp62.6.4's. The verdict-to-label table is factory10.mirror,
  so the two halves read one value.

  WHAT IS STORED, AND WHAT IS NEVER STORED. The identity, the change,
  the commit, the check's name, GitHub's conclusion, the times, and
  the TAIL of the failed job's log. The whole log is never here: the
  source fetches the last 200 lines at mint time and the field caps
  the string. The classifier reads the tail, which is where a failure
  says what it was. Nothing in this row carries a token.

  :nav :secondary, for change's reason: a red build is the day job's
  work, not the family's."
  (:require [factory10.mirror :refer [the-mirror-writes-this-row]]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── how much of a log one row keeps ─────────────────────────────────

(def log-excerpt-lines
  "How many lines of the failed job the source fetches. The owner's
  ruling: the tail, not the whole job. A failure says what it was at
  the end."
  200)

(def log-excerpt-chars
  "…and the ceiling on the string those lines make. 200 lines of a
  build log are about 16,000 characters; the cap is above that, so an
  ordinary tail arrives whole and a pathological one is cut rather
  than refused at the mint."
  20000)

;; ── the three verdicts a classifier may write ───────────────────────

(defhandler classify-as-infra [row inp _ctx]
  ;; The machine advances the state; this handler writes the two
  ;; words the verdict IS. An action's input is never merged into the
  ;; document, so a handler spells what it keeps.
  (-> row
      (assoc-in [:data :verdict] "infra")
      (assoc-in [:data :remedy] (:remedy inp))))

(defhandler classify-as-base-red [row inp _ctx]
  (-> row
      (assoc-in [:data :verdict] "base_red")
      (assoc-in [:data :remedy] (:remedy inp))))

(defhandler classify-as-this-change [row inp _ctx]
  (-> row
      (assoc-in [:data :verdict] "this_change")
      (assoc-in [:data :remedy] (:remedy inp))))

(defhandler reclassify-the-run [row inp _ctx]
  ;; The person names the verdict, because a correction that could
  ;; only say "wrong" would leave the ledger counting a disagreement
  ;; instead of an answer.
  (-> row
      (assoc-in [:data :verdict] (:verdict inp))
      (assoc-in [:data :remedy] (:remedy inp))))

;; ── the mirror's record of its own push ─────────────────────────────

(defhandler stamp-the-label [row inp ctx]
  ;; R-5's effect point. The source pushes the label to GitHub and
  ;; then walks this door, so the transition log carries the push and
  ;; the row says which label landed and when.
  (-> row
      (assoc-in [:data :pushed_label] (:label inp))
      (assoc-in [:data :labelled_at] (:now ctx))))

;; ── the one wall ────────────────────────────────────────────────────

(defguardfn only-a-person-reclassifies
  {:reads [:principal]
   :explain "A reclassification is the person's correction, and a correction an agent could make on its own is a correction the house cannot count — the reclassifications after a verdict are exactly how this seat's judgment is measured. If you classified this run and now think you were wrong, say so where an agent may, and let a person tap."
   :open "No door clears this one. The correction is a person's tap, and a grant that opened it would let the house buy back the number it grades itself on."}
  [_row _inp ctx]
  ;; The person-wall's own shape, spelled by hand and NOT made
  ;; grantable: every hand but an agent's passes, the engine's own
  ;; actor included, and no scope opens it.
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; All three are check-tier: no :given rows, and every guard in the
;; tree reads :principal and nothing else, so `make check-factory`
;; judges them with no database.

(def ^:private a-classified-run
  {:run_id "github:ckopsa/waymark/check-run/41752098311"
   :head_sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"
   :check_name "test10 (shard 3)"
   :conclusion "failure"
   :verdict "infra"
   :remedy "Re-run the shard: the dependency cache died and the job never reached a test."})

(defscenario an-agent-may-not-correct-its-own-verdict
  "The reclassifications after a verdict are how this seat's judgment
   is measured, so the hand that classified may not take it back. An
   agent that thinks it was wrong waits for a person."
  {:kind    :ci_run
   :attempt :reclassify
   :row     {:state :classified :data a-classified-run}
   :as      {:id "ci-classifier" :type :agent}
   :expect  {:refused :only-a-person-reclassifies
             :because "person's correction"}})

(defscenario the-person-corrects-the-classifier
  "And the door is really there for the person whose build it is —
   one tap, no grant and no ceremony."
  {:kind    :ci_run
   :attempt :reclassify
   :row     {:state :classified :data a-classified-run}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario the-tree-has-no-shortcut-to-a-correction
  "A red run cannot be reclassified, because it has never been
   classified. The machine refuses it with no guard behind the
   refusal, which is the strongest way a tree can be enforced."
  {:kind    :ci_run
   :attempt :reclassify
   :row     {:state :red
             :data {:run_id "github:ckopsa/waymark/check-run/41752098312"
                    :head_sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"
                    :check_name "test-queue (shard 1)"
                    :conclusion "failure"}}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state
             :because "Classified"}})

;; ── :ci_run — the red runs, and the tree over them ──────────────────

(defresource ci-run
  {:kind :ci_run
   :plural "ci_runs"
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   :states [:red :classified :reclassified]
   :initial :red
   ;; A correction is the last word. Nothing departs it, so it is a
   ;; tomb, and the tree ends where the person ended it.
   :terminal #{:reclassified}
   ;; NO :over, deliberately. `:over` shuts the household's doors on a
   ;; row whose work is over (waymark-fp62.4.1), and the machine shows
   ;; a way back only where the door lands OUTSIDE an ending. A
   ;; classified run still owes two doors — the person's reclassify
   ;; and the mirror's stamp_label — so naming `classified` an ending
   ;; would shut the correction this kind exists to count. The tomb
   ;; says what is over here, and it says it about one state.
   :summary "{data.check_name} · {data.conclusion} · {state}"
   ;; the check's own line, not the kind label
   :label-template "{data.check_name}"
   :display {:title "{data.check_name}"}
   ;; THE QUEUE IS THE COLLECTION UNDER ITS DEFAULT FILTER
   ;; (inbox_item's spelling): a walker opens /api/ci_runs and gets
   ;; exactly the runs nobody has classified, oldest first.
   :filterable {:state #{:eq :in}
                :run_id #{:eq}
                :change #{:eq}
                :head_sha #{:eq}
                :check_name #{:eq}
                :verdict #{:eq :in}}
   :default-filters {:state "red"}
   :sortable {:fields [:started_at] :default "started_at"}
   ;; ONE ROW PER CHECK RUN, enforced by an index rather than by a
   ;; sentence in a charter. Uniqueness is enforced on the promoted
   ;; column, which is why :run_id is filterable above.
   :unique [[:run_id]]
   :schema
   [:map
    ;; :raw for change_id's reason, one kind over: an opaque address is
    ;; a LABEL and not prose, and it is shown exactly as it arrived.
    [:run_id {:x-display
              {:raw true
               :label "The check run's own id"
               :help "The address GitHub knows this check run by. github:ckopsa/waymark/check-run/41752098311 is one of them. The source reads it back to see whether this run is already a row here."}}
     [:string {:min 1 :max 250}]]
    ;; the pull request this run ran on, as a ROW and not a sentence.
    ;; The ref is what makes "which changes are red" answerable
    ;; without reading prose.
    [:change {:optional true :kind :change
              :x-display {:label "The change it ran on"}}
     [:maybe :waymark/ref]]
    [:head_sha {:optional true
                :x-display
                {:label "The commit it ran on"
                 :help "The full sha of the commit the check ran against. A run on an old head says nothing about the branch now."}}
     [:maybe [:string {:max 64}]]]
    [:check_name {:optional true
                  :x-display
                  {:label "The check"
                   :help "The name of the check run, as GitHub shows it — the shard, the job or the gate. This is what a person recognises the failure by."}}
     [:maybe [:string {:max 200}]]]
    [:conclusion {:optional true :filter #{:eq :in}
                  :x-display
                  {:label "How it ended"
                   :choices {"failure" "It ran and it failed"
                             "timed_out" "It ran past its limit and was stopped"
                             "cancelled" "Somebody or something stopped it"
                             "action_required" "GitHub asks a person to act"
                             "stale" "The run went stale before it finished"
                             "neutral" "It ended without a pass or a fail"}}}
     [:maybe [:enum "failure" "timed_out" "cancelled" "action_required"
              "stale" "neutral"]]]
    ;; THE TAIL, AND ONLY THE TAIL. The source fetches the last 200
    ;; lines at mint time, with no tokens spent, and this field caps
    ;; the string. The whole log is never here.
    [:log_excerpt {:optional true
                   :x-display
                   {:widget "prose"
                    :label "The end of the log"
                    :help "The last 200 lines of the failed job, as the source read them at mint time. Read the end first: a failure says what it was there. The whole log is not kept."}}
     [:maybe [:string {:max 20000}]]]
    [:started_at {:optional true
                  :x-display
                  {:label "When it started"
                   :help "GitHub's own start time for the run. The queue is walked oldest first."}}
     [:maybe :waymark/instant]]
    [:finished_at {:optional true
                   :x-display
                   {:label "When it ended"
                    :help "GitHub's own end time for the run. The two times together say how long the house waited for this failure."}}
     [:maybe :waymark/instant]]
    ;; hidden: the origin LINK below is the affordance, and a raw URL
    ;; in the fields is noise
    [:url {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]
    ;; WRITTEN BY THE THREE CLASSIFY DOORS, and rewritten by the
    ;; person's correction. It is absent from the create door on
    ;; purpose: a row born with a verdict is a row that skipped the
    ;; walk.
    [:verdict {:optional true
               :x-display
               {:label "What went wrong"
                :choices {"infra" "The infrastructure broke — the cache, the runner or the network"
                          "base_red" "The base branch was already red before this change"
                          "this_change" "This change is wrong"}}}
     [:maybe [:enum "infra" "base_red" "this_change"]]]
    ;; WRITTEN BY THE SAME DOORS. One sentence, and the door will not
    ;; open without it.
    [:remedy {:optional true
              :x-display
              {:label "What to do about it"
               :help "One sentence: what somebody should do next about this red run. It is what the next reader has, so write the act and not the diagnosis."}}
     [:maybe [:string {:max 240}]]]
    ;; STAMPED BY THE MIRROR, after the label lands on GitHub (R-5).
    ;; A person writes neither of these: they are on no door a person
    ;; meets, exactly as a sitting's `served` is.
    ;; `pushed_label` and not `label`: the ref machinery writes a
    ;; target's rendered label into the field a ref NAMES (invoke's
    ;; label-pass), and a bare `label` beside a ref would read as that
    ;; field to the next person here. This one says what it is.
    [:pushed_label {:optional true
                    :x-display
                    {:label "The label that was pushed"
                     :choices {"ci:infra" "ci:infra — the infrastructure broke"
                               "ci:base-red" "ci:base-red — the base branch was already red"
                               "ci:this-change" "ci:this-change — this change is wrong"}}}
     [:maybe [:enum "ci:infra" "ci:base-red" "ci:this-change"]]]
    [:labelled_at {:optional true
                   :x-display
                   {:label "When the label landed"
                    :help "The moment the mirror pushed the label to the pull request. It is empty while the push has not happened, which is the honest thing to say about it."}}
     [:maybe :waymark/instant]]]
   ;; THE BIRTH DOOR IS THE MIRROR'S, AND IT IS HIDDEN. A person meets
   ;; no create form for this kind. `verdict`, `remedy`, `pushed_label`
   ;; and `labelled_at` are absent from the create model on purpose: a row
   ;; born already classified is a row that skipped the tree, and the
   ;; whole claim of this kind is that the tree was walked.
   :create-guards [the-mirror-writes-this-row]
   :create-schema
   [:map
    [:run_id {:x-display {:label "The check run's own id"}}
     [:string {:min 1 :max 250}]]
    [:change {:optional true :kind :change
              :x-display {:label "The change it ran on"}}
     [:maybe :waymark/ref]]
    [:head_sha {:optional true :x-display {:label "The commit it ran on"}}
     [:maybe [:string {:max 64}]]]
    [:check_name {:optional true :x-display {:label "The check"}}
     [:maybe [:string {:max 200}]]]
    [:conclusion {:optional true :x-display {:label "How it ended"}}
     [:maybe [:enum "failure" "timed_out" "cancelled" "action_required"
              "stale" "neutral"]]]
    [:log_excerpt {:optional true
                   :x-display {:widget "prose" :label "The end of the log"}}
     [:maybe [:string {:max 20000}]]]
    [:started_at {:optional true :x-display {:label "When it started"}}
     [:maybe :waymark/instant]]
    [:finished_at {:optional true :x-display {:label "When it ended"}}
     [:maybe :waymark/instant]]
    [:url {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 500}]]]]
   :actions
   {;; THREE DOORS AT `red`, AND EACH ONE DEMANDS THE SENTENCE.
    :classify_infra
    {:from #{:red} :to :classified
     :handler classify-as-infra
     :input [:map
             [:remedy
              {:examples ["Re-run the shard: the dependency cache died and the job never reached a test."]
               :x-display
               {:label "What to do about it"
                :help "One sentence: what somebody should do next. Say the act — re-run the job, wait for the runner, raise the cache key — and not the diagnosis."}}
              [:string {:min 1 :max 240}]]]
     ;; :edit-shape — a first remedy onto a blank row is not an edit
     ;; of one. There is no earlier value to prefill from and no
     ;; second classify door.
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This is the verdict on the record, and the mirror pushes a label for it. The way back is the person's reclassify door, which adds the correction rather than erasing this."}
     :display {:label "Infrastructure" :order 1
               :description "The cache, the runner or the network broke — the change is not the cause"}}

    :classify_base_red
    {:from #{:red} :to :classified
     :handler classify-as-base-red
     :input [:map
             [:remedy
              {:examples ["Fix main first: the same test fails on the base commit, so this change is not the cause."]
               :x-display
               {:label "What to do about it"
                :help "One sentence: what somebody should do next. Say the act — fix the base branch, rebase this change, wait for the base to go green."}}
              [:string {:min 1 :max 240}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This is the verdict on the record, and the mirror pushes a label for it. The way back is the person's reclassify door, which adds the correction rather than erasing this."}
     :display {:label "Base was red" :order 2
               :description "The base branch already failed this way before the change — fix the base, not the change"}}

    :classify_this_change
    {:from #{:red} :to :classified
     :handler classify-as-this-change
     :input [:map
             [:remedy
              {:examples ["Add the new column to the conformance fixture's table list: the shard drops tables it does not name."]
               :x-display
               {:label "What to do about it"
                :help "One sentence: what somebody should do next. Say the act — the file, the line or the missing piece — so the next reader starts from an edit and not from the log."}}
              [:string {:min 1 :max 240}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This is the verdict on the record, and the mirror pushes a label for it. The way back is the person's reclassify door, which adds the correction rather than erasing this."}
     :display {:label "This change" :order 3
               :description "The change itself is wrong — say what to fix, in one line"}}

    ;; THE MIRROR'S RECORD OF ITS OWN PUSH (R-5). Hidden, and the
    ;; engine's hand alone: the seat holds no GitHub power and never
    ;; meets this door. A self-loop, because pushing a label does not
    ;; move the verdict.
    :stamp_label
    {:from #{:classified} :to :classified
     :guards [the-mirror-writes-this-row]
     :handler stamp-the-label
     :input [:map
             [:label [:enum "ci:infra" "ci:base-red" "ci:this-change"]]]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Label pushed" :order 4
               :description "The mirror pushed the label to the pull request and says which one"}}

    ;; THE PERSON'S OWN DOOR. Every reclassification is a correction
    ;; on the record, and the count of them per transition is what
    ;; decides whether this seat's judgment needs a bigger model.
    :reclassify
    {:from #{:classified} :to :reclassified
     :guards [only-a-person-reclassifies]
     :handler reclassify-the-run
     :input [:map
             [:verdict
              {:x-display
               {:label "What actually went wrong"
                :choices {"infra" "The infrastructure broke — the cache, the runner or the network"
                          "base_red" "The base branch was already red before this change"
                          "this_change" "This change is wrong"}}}
              [:enum "infra" "base_red" "this_change"]]
             [:remedy
              {:examples ["The runner was fine: the test really does fail on this branch, so fix the fixture."]
               :x-display
               {:label "What to do about it"
                :help "One sentence, in place of the one the classifier wrote. Say the act, as the classify doors ask for it."}}
              [:string {:min 1 :max 240}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The correction stands on the record beside the verdict it overrules. Nothing about the first verdict is erased; this adds the correction to it, and the ledger counts it."}
     :display {:label "Reclassify" :order 5
               :description "The classifier was wrong — say what actually went wrong, and what to do"}}}
   :links [{:rel "origin" :href "{data.url}" :external true
            :summary "The check run, at GitHub"}]
   :scenarios [an-agent-may-not-correct-its-own-verdict
               the-person-corrects-the-classifier
               the-tree-has-no-shortcut-to-a-correction]})
