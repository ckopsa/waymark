(ns factory10.resources.repo-policy
  "The repository policy: what submit MEANS in one repository, as a
  row a person restates (bead waymark-fp62.6.3.2, R-3; bead
  waymark-fp62.6.3, R-8).

  WHY THIS IS A ROW. Submit is an abstraction over whatever the seat
  and the repository need — a push, a pull request, the gate — and the
  owner's ruling is that the shape is decided per repository by a
  person and never by the model. So every number a submit obeys lives
  here: which branches the work goes on, which branch it merges into,
  how large one change may be, whether a push opens a pull request,
  whether a green gate merges it, how many rounds one change gets
  before the house stops, which paths are never served, which
  formatter rule holds, and which document the seat reads first.

  ONE ROW FOR EACH REPOSITORY. `repository` is `:unique`, so a second
  policy for the same repository is refused by an index and not by a
  sentence in a charter. The bench's sit reads this row to answer what
  submit means (server/mcp, R-12.29); the change row's `submit` and
  `discard` doors read it to judge a branch, a size and a round.

  A PERSON WRITES IT, AND ONLY A PERSON. The one guard walls every
  agent hand out of the create door and out of `restate`. This is NOT
  `unless-granted`: a grant that opened this door would be a grant
  that let a model raise its own ceiling, which is the one thing the
  ruling says the model must not do. The guard carries `:open`,
  because no door of this engine changes that verdict — an agent that
  needs a different policy says so in a finding, and a person taps.

  THE MACHINE IS TWO STATES. `active` is the policy the bench obeys.
  `retire` puts it away without deleting the record, and `restore`
  brings it back: a retired policy is the house saying it does not
  work this repository now. Neither state is a tomb.

  :nav :secondary, for change's reason: a repository is the day job's,
  not the family's. A `:primary` kind's open rows are claimed by the
  feed's next-actions population, and the household's feed must not
  card the day job's configuration."
  (:require [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the one wall ────────────────────────────────────────────────────

(defguardfn only-a-person-states-the-policy
  {:reads [:principal]
   :open "No door here changes this verdict. What submit means is a person's statement about a repository, so a model that needs another ceiling, another base or another pattern writes a finding that says which number is wrong and why, and a person taps."
   :explain "The repository policy is the house's own sentence about this repository — the branches, the base, the size ceiling, the rounds and the formatter rule. A model that could restate it could raise its own ceiling."}
  [_row _inp ctx]
  ;; The person-wall's shape (inbox_item's `the-correction-is-a-persons`,
  ;; one module over): every hand but an agent's passes, the engine's
  ;; own system actor included — a seed that writes the first policy at
  ;; boot is the house's hand, not a model's.
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

;; ── the restatement ─────────────────────────────────────────────────

(defhandler restate-the-policy [row inp _ctx]
  ;; THE WHOLE POLICY, AGAIN. A restate is the authority saying what it
  ;; holds now, so every field the door collects lands; the machine
  ;; keeps the row where it stands.
  (update row :data merge inp))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; Check-tier: no :given rows, and the one guard reads :principal and
;; nothing else. `make check-factory` judges them with no database.

(def ^:private a-policy
  {:repository "ckopsa/waymark"
   :branch_pattern "waymark/*"
   :base "main"
   :max_lines 400
   :opens_pr true
   :auto_merge true
   :rounds_per_change 3
   :formatter "runner"
   :deny ["*.env" "**/secrets/*"]
   :orientation "docs/orientation.md"})

(defscenario a-model-does-not-restate-the-policy
  "The ceiling a model works under is not a number the model may
   move. The door is walled for every agent hand, and the refusal says
   where to say so instead."
  {:kind    :repo_policy
   :attempt :restate
   :row     {:state :active :data a-policy}
   :input   (assoc a-policy :max_lines 4000)
   :as      {:id "bench-seat" :type :agent}
   :expect  {:refused :only-a-person-states-the-policy}})

(defscenario the-person-states-what-submit-means
  "And the door is really there for the person whose repository it is
   — one tap, no grant and no ceremony."
  {:kind    :repo_policy
   :attempt :restate
   :row     {:state :active :data a-policy}
   :input   (assoc a-policy :max_lines 600)
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

;; ── the fields, spelled once and read by two doors ──────────────────

(def ^:private formatter-choices
  {"runner" "The runner pushes a fixup commit when the format is wrong"
   "none" "Nothing formats: the push stands as the seat wrote it"})

(def ^:private policy-fields
  "The whole policy, as schema entries. The create door and the
  restate door collect the same fields, because a restatement is the
  whole statement again — two spellings of one policy would be two
  policies."
  [[:repository {:x-display
                 {:raw true
                  :label "The repository"
                  :help "The repository as GitHub spells it, as owner/repo. It is also the name the bench rig holds the clone under."}}
    [:string {:min 1 :max 140}]]
   [:branch_pattern {:default "waymark/*"
                     :examples ["waymark/*"]
                     :x-display
                     {:raw true
                      :label "The branch pattern"
                      :help "The shape of a work branch, with one * for the change's own id — waymark/* makes waymark/01HZQ7… . A submit on a branch outside this pattern is refused."}}
    [:string {:min 1 :max 120}]]
   [:base {:default "main"
           :examples ["main"]
           :x-display
           {:raw true
            :label "The base branch"
            :help "The branch the work merges into. The worktree starts from it, and a submit on it is refused whatever the grant says."}}
    [:string {:min 1 :max 120}]]
   [:max_lines {:default 400
                :examples [400]
                :x-display
                {:label "The size ceiling, in lines"
                 :help "The most changed lines one submit may carry, added plus removed. The bench refuses a larger change before it commits it."}}
    [:int {:min 1 :max 100000}]]
   [:opens_pr {:default true
               :x-display
               {:label "A push opens a pull request"
                :help "True when a workflow opens the pull request as soon as the branch is pushed. The seat then holds no GitHub power at all."}}
    :boolean]
   [:auto_merge {:default true
                 :x-display
                 {:label "A green gate merges it"
                  :help "True when auto-merge merges the pull request as soon as the checks are green. False when a person merges it."}}
    :boolean]
   [:rounds_per_change {:default 3
                        :examples [3]
                        :x-display
                        {:label "Rounds for one change"
                         :help "How many times a seat may submit one change before the house stops. At the ceiling the change moves to stuck and waits for a person."}}
    [:int {:min 1 :max 20}]]
   [:formatter {:default "runner"
                :x-display
                {:label "What formats the code"
                 :choices formatter-choices
                 :help "A repository that formats on CI refuses an unformatted push. The runner's fixup commit is the default, because it keeps the bench free of a toolchain."}}
    (into [:enum] (sort (keys formatter-choices)))]
   [:deny {:default []
           :examples [["*.env" "**/secrets/*"]]
           :x-display
           {:raw true
            :label "Paths the bench never serves"
            :help "Globs, one for each row. A file that matches one is never read and never written, whatever the grant says. The rig holds the same list in its own configuration; this row is what a person reads."}}
    [:vector [:string {:min 1 :max 200}]]]
   [:orientation {:default "docs/orientation.md"
                  :examples ["docs/orientation.md"]
                  :x-display
                  {:raw true
                   :label "The orientation document"
                   :help "The path a seat reads first in this repository: what the house expects of a change here. The sit answers this path with the bench."}}
    [:string {:min 1 :max 200}]]])

;; ── :repo_policy — what submit means, as a row ──────────────────────

(defresource repo-policy
  {:kind :repo_policy
   :plural "repo_policies"
   ;; the day job's configuration, not the family's — see the ns
   ;; docstring
   :nav :secondary
   :states [:active :retired]
   :initial :active
   ;; NEITHER STATE IS A TOMB. A retired policy comes back with one
   ;; tap, because a repository the house stops working is a
   ;; repository it may work again.
   :terminal #{}
   :summary "{data.repository} · {state}"
   :label-template "{data.repository}"
   :display {:title "{data.repository}"}
   :filterable {:state #{:eq :in} :repository #{:eq}}
   ;; the collection a reader opens IS the policies the bench obeys
   :default-filters {:state "active"}
   :sortable {:fields [:repository] :default "repository"}
   ;; ONE POLICY FOR EACH REPOSITORY, enforced by an index
   :unique [[:repository]]
   :schema (into [:map] policy-fields)
   ;; no :create-schema: the schema IS the create form, because a
   ;; policy is born whole. A person states every number the first
   ;; time, and the defaults above are what the form offers.
   :create-guards [only-a-person-states-the-policy]
   :actions
   {:restate
    {:from #{:active} :to :active
     :input (into [:map] policy-fields)
     :guards [only-a-person-states-the-policy]
     :handler restate-the-policy
     :record true
     ;; the form opens on the policy that stands, so a person changes
     ;; one number and restates the rest as it was
     :edit {:prefill [:repository :branch_pattern :base :max_lines :opens_pr
                      :auto_merge :rounds_per_change :formatter :deny
                      :orientation]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restate" :style :primary :order 1
               :description "State what submit means in this repository again, whole"}}

    :retire
    {:from #{:active} :to :retired :undo :restore
     :guards [only-a-person-states-the-policy]
     :safety {:idempotent true :reversible true :confirm true
              :consequence "The bench stops working this repository: a submit reads no policy and refuses. Nothing is deleted, and one tap brings it back."}
     :display {:label "Retire" :style :danger :order 9
               :description "Put this policy away — the house does not work this repository now"}}

    :restore
    {:from #{:retired} :to :active :undo :retire
     :guards [only-a-person-states-the-policy]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 2
               :description "Work this repository again, under the policy as it stands"}}}
   :scenarios [a-model-does-not-restate-the-policy
               the-person-states-what-submit-means]})
