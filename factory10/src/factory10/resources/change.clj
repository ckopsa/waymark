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

  THE STATE LIST IS OPEN. Bead waymark-fp62.6.3.2 adds two more
  states, `submitted` and `stuck`, and two more doors, `submit` and
  `discard`, when the bench arrives. Nothing here closes the door on
  them: `merged` is the only tomb, `open` keeps its exits, and no
  check in this file counts the states.

  ONE ROW PER PULL REQUEST. `change_id` is `github:owner/repo#number`,
  and it is `:unique`. The source asks for the id before it mints, so
  a second read of the same pull request finds the row that is already
  here. An index refuses a duplicate, not a sentence in a charter.

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
  (:require [factory10.mirror :refer [the-mirror-writes-this-row]]
            [waymark10.dsl :refer [defhandler defresource defscenario]]))

(set! *warn-on-reflection* true)

;; ── what a pull request writes back onto the row ────────────────────

(defhandler observe-the-pull-request [row inp _ctx]
  ;; The source hands the facts it read. A fact it did not read is
  ;; absent, and absent means silent: the stored value stands. The
  ;; machine advances the state, never this handler.
  (update row :data merge (into {} (remove (comp nil? val)) inp)))

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

;; ── :change — one pull request, mirrored ────────────────────────────

(defresource change
  {:kind :change
   :plural "changes"
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   ;; GitHub's own three. The list is open: waymark-fp62.6.3.2 adds
   ;; `submitted` and `stuck`.
   :states [:open :merged :closed]
   :initial :open
   ;; ONE TOMB. A merged pull request is finished. A closed one is
   ;; not: GitHub reopens it, so `reopen` is a real door.
   :terminal #{:merged}
   ;; what each ending MEANS, in the factory's own words. A merged
   ;; change is what the work accomplished; a closed one is what it
   ;; let go. The engine shuts the household's doors on a row whose
   ;; work is over, and `reopen` survives that because the machine
   ;; shows it as a way back — it lands in `open`, which is no ending.
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
   ;; still live (inbox_item's spelling)
   :default-filters {:state "open"}
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
                  :help "The address GitHub knows this pull request by, as github:owner/repo#number. The source reads it back to see whether this pull request is already a row here."}}
     [:string {:min 1 :max 250}]]
    [:repository {:x-display
                  {:label "The repository"
                   :help "The repository as GitHub spells it, as owner/repo. Every policy about this change reads this field first."}}
     [:string {:min 1 :max 140}]]
    [:number {:x-display
              {:label "The pull request number"
               :help "The number GitHub shows on the pull request. It is unique inside the repository, not across repositories."}}
     [:int {:min 1}]]
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
    [:change_id {:x-display {:label "The pull request's own id"}}
     [:string {:min 1 :max 250}]]
    [:repository {:x-display {:label "The repository"}}
     [:string {:min 1 :max 140}]]
    [:number {:x-display {:label "The pull request number"}}
     [:int {:min 1}]]
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
    :merge
    {:from #{:open} :to :merged
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub merged this pull request. The row follows GitHub, so there is no way back: a merged pull request is not reopened."}
     :display {:label "Merged" :order 2
               :description "GitHub merged the pull request"}}

    :close
    {:from #{:open} :to :closed
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
               :description "GitHub reopened the pull request"}}}
   :links [{:rel "origin" :href "{data.url}" :external true
            :summary "The pull request, at GitHub"}]
   :scenarios [a-model-does-not-move-a-pull-request
               the-source-moves-the-pull-request]})
