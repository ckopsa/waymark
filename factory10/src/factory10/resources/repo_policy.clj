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

  THE ROW IS THE ONE SENTENCE, AND THE ENGINE TELLS THE RIG (bead
  waymark-fp62.6.3.8). A person writes this row and nothing else: the
  create and the restate call the bench rig's `enroll` with the
  repository, the clone URL, the base branch and the deny list, the
  retire calls `unenroll`, and the restore enrols again. The rig's
  answer never refuses a person's sentence — the row lands either way,
  and `enrolled_at` beside `note` says whether the bench holds this
  repository yet. A row the rig did not take is offered again by the
  retry pass (factory10.bench/enroll-unenrolled!), which stamps it
  through the hidden `mark_enrolled` door so the transition log
  carries the enrolment. The GitHub source reads the active rows at
  every pass, so a repository is polled because a person stated a
  policy for it and for no other reason.

  :nav :secondary, for change's reason: a repository is the day job's,
  not the family's. A `:primary` kind's open rows are claimed by the
  feed's next-actions population, and the household's feed must not
  card the day job's configuration."
  (:require [clojure.string :as str]
            [factory10.bench :as bench]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the one wall ────────────────────────────────────────────────────

(defguardfn a-person-or-their-delegate-states-the-policy
  {:reads [:principal]
   :open "No door here changes this verdict. What submit means is a person's statement about a repository. A person states it in person, or through a delegate that acts for them under a grant the person approved. A model in a seat writes a finding that says which number is wrong and why, and a person taps."
   :explain "The repository policy is the house's own sentence about this repository — the branches, the base, the size ceiling, the rounds and the formatter rule. A model that could restate its own policy could raise its own ceiling. A delegate acting for a person is the person's hand, and the grant it wears is the person's decision."}
  [_row _inp ctx]
  ;; The owner's ruling, 2026-09-19: a person works with a model to
  ;; add a repository, so the wall is against a model ALONE — a seat's
  ;; sitter, an agent that acts for nobody — and not against every
  ;; agent. The shape is mcp_server's `a-person-or-the-engine`: a
  ;; person, the engine's own system actor, or an agent whose
  ;; principal names whom it acts for. The grant is the leash; this
  ;; guard only keeps a seat from restating the policy it works under.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (and (= :agent type) (str/blank? (str acts-for)))
      (t/deny)
      (t/allow))))

(defguardfn the-engine-marks-the-enrolment
  {:reads [:principal]
   :hide true
   :explain "The engine stamps the enrolment. A person and a model read it."}
  ;; The hidden shape of ci_run's `stamp_label`, one kind over: the
  ;; retry pass is the only hand that walks this door, and a hidden
  ;; door owes no remedy because it answers 404 and says nothing.
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

;; ── the restatement, and the rig it tells ───────────────────────────

(defhandler restate-the-policy [row inp ctx]
  ;; THE WHOLE POLICY, AGAIN. A restate is the authority saying what it
  ;; holds now, so every field the door collects lands; the machine
  ;; keeps the row where it stands. Then the engine tells the rig, and
  ;; the row says whether the rig took it (R-2).
  (bench/enrolled (update row :data merge inp) ctx))

(defn- enrol-at-birth
  "The create's enrolment (R-2). A create cannot walk a door on a row
  that does not exist yet, so the call rides the create's own hook —
  mcp_server's `born`, one module over. A rig that does not answer
  costs the enrolment and never the row."
  [row ctx]
  (bench/enrolled row ctx))

(defhandler unenrol-the-repository [row _inp ctx]
  ;; R-3: the bench is told to stop holding this repository. A refusal
  ;; is noted on the row and never raised — a person who retires a
  ;; policy has retired it, whatever the rig says.
  (bench/unenrolled row ctx))

(defhandler enrol-the-repository-again [row _inp ctx]
  ;; R-3: a restore is a create again, as far as the rig is concerned.
  (bench/enrolled row ctx))

(defhandler mark-the-enrolment [row _inp ctx]
  ;; THE RETRY'S OWN RECORD (R-4). The pass called `enroll` and the rig
  ;; took it; this door writes the stamp, so a reader of the log can
  ;; tell an enrolment the retry landed from one the create did.
  (-> row
      (assoc-in [:data :enrolled_at] (:now ctx))
      (assoc-in [:data :note] nil)))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; Check-tier: no :given rows, and both guards read :principal and
;; nothing else. `make check-factory` judges them with no database.

(def ^:private a-policy
  {:repository "ckopsa/waymark"
   :clone_url "https://github.com/ckopsa/waymark"
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
   :expect  {:refused :a-person-or-their-delegate-states-the-policy}})

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
   [:clone_url {:optional true
                :examples ["https://github.com/ckopsa/waymark"]
                :x-display
                {:raw true
                 :label "Where the bench clones it from"
                 :help "The URL the rig clones this repository from. Leave it empty for https://github.com/<the repository>, which is what a GitHub repository needs."}}
    [:maybe [:string {:max 300}]]]
   [:branch_pattern {:default "waymark/*"
                     :examples ["waymark/*"]
                     :x-display
                     {:raw true
                      :label "The branch pattern"
                      :help "The shape of a work branch, with one * for the change's own id — waymark/* makes waymark/01HZQ7… . A submit on a branch outside this pattern is refused. The text before the * must not be the name of a branch this repository already has: git holds refs/heads/seat and refs/heads/seat/01HZQ7… never at the same time, so seat/* refuses every worktree in a repository with a branch named seat."}}
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

(def ^:private engine-fields
  "What the ENGINE writes about this row, and a person never does: when
  the bench took this repository, and why it did not. They are on the
  schema and on no door's input, so no form offers them and the
  restate prefills neither (mcp_server's `engine-fields`, one module
  over)."
  [[:enrolled_at {:optional true
                  :examples ["2026-09-19T14:00:00Z"]
                  :x-display
                  {:label "Enrolled at"
                   :help "When the bench rig took this repository. Empty means the bench does not hold it yet, and the retry pass offers it again."}}
    [:maybe :waymark/instant]]
   [:note {:optional true
           :examples ["The bench has not enrolled this repository: the clone failed."]
           :x-display
           {:widget "prose"
            :label "Note"
            :help "What the engine has to say about this row — the reason the bench did not enrol the repository, and nothing when it did."}}
    [:maybe [:string {:max 500}]]]])

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
   :schema (into [:map] (concat policy-fields engine-fields))
   ;; THE CREATE FORM IS THE WHOLE POLICY AND NOTHING ELSE. A person
   ;; states every number the first time, and the defaults above are
   ;; what the form offers; the two engine fields are on the schema so
   ;; a reader sees them and on no form so a person never writes them.
   :create-schema (into [:map] policy-fields)
   :create-guards [a-person-or-their-delegate-states-the-policy]
   ;; …and the rig is told at the birth (R-2): a create cannot walk a
   ;; door on a row that does not exist yet
   :on-create enrol-at-birth
   :actions
   {:restate
    {:from #{:active} :to :active
     :input (into [:map] policy-fields)
     :guards [a-person-or-their-delegate-states-the-policy]
     :handler restate-the-policy
     :record true
     ;; the form opens on the policy that stands, so a person changes
     ;; one number and restates the rest as it was. The engine's own
     ;; two fields are absent here for the reason they are absent from
     ;; the create form: a person does not state them.
     :edit {:prefill [:repository :clone_url :branch_pattern :base :max_lines
                      :opens_pr :auto_merge :rounds_per_change :formatter
                      :deny :orientation]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restate" :style :primary :order 1
               :description "State what submit means in this repository again, whole"}}

    :retire
    {:from #{:active} :to :retired :undo :restore
     :guards [a-person-or-their-delegate-states-the-policy]
     :handler unenrol-the-repository
     :safety {:idempotent true :reversible true :confirm true
              :consequence "The bench stops working this repository: a submit reads no policy and refuses. Nothing is deleted, and one tap brings it back."}
     :display {:label "Retire" :style :danger :order 9
               :description "Put this policy away — the house does not work this repository now"}}

    :restore
    {:from #{:retired} :to :active :undo :retire
     :guards [a-person-or-their-delegate-states-the-policy]
     :handler enrol-the-repository-again
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 2
               :description "Work this repository again, under the policy as it stands"}}

    ;; THE RETRY'S OWN DOOR (R-4). Hidden, and the engine's hand alone:
    ;; a person never meets it, and the model cannot see it. A
    ;; self-loop, because taking a repository does not move the policy.
    ;; The rig's bare path rides as the input: it is the rig's own
    ;; answer and no field of the row, so the log says where the clone
    ;; landed and a second pass is a second call rather than a replay
    ;; of the first. (A field the row already holds would make this
    ;; door edit-shaped in the usability battery's eyes.)
    :mark_enrolled
    {:from #{:active} :to :active
     :guards [the-engine-marks-the-enrolment]
     :handler mark-the-enrolment
     :input [:map
             [:bare {:optional true
                     :examples ["/var/lib/bench/ckopsa/waymark/bare.git"]
                     :x-display {:hidden true :raw true
                                 :label "The bare clone"}}
              [:maybe [:string {:max 300}]]]]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Enrolled" :order 4
               :description "The bench took this repository and the engine says when"}}}
   ;; The delegate's allow — an agent whose principal names whom it
   ;; acts for — is the suite's to prove (bench_test): a check-tier
   ;; scenario's actor carries id, roles and type, and no acts-for.
   :scenarios [a-model-does-not-restate-the-policy
               the-person-states-what-submit-means]})
