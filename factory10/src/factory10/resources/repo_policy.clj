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

(defguardfn the-house-merges-only-what-a-check-tested
  {:reads []
   :open "No other door changes this verdict. Restate the policy with at least one required check, or let GitHub merge (merge_by github), or leave the merge to a person (auto_merge false)."
   :explain "A policy that says the house merges a green change must name the checks that make it green. With no required check, the house would merge what nothing tested."}
  ;; THE ONE THING merge_by: house MUST NOT MEAN (ticket 4dfb00f6).
  ;; GitHub's auto-merge waits for the branch rules' own required
  ;; checks; the house's merge waits for the list this row names, and
  ;; an empty list is a merge on nothing. The fields are read off the
  ;; input with the schema's own defaults, because the create door may
  ;; omit what the form offered.
  [_row inp _ctx]
  (let [house? (= "house" (str (get inp :merge_by "github")))
        auto? (not (false? (get inp :auto_merge true)))
        checks (remove str/blank? (map str (get inp :required_checks [])))]
    (if (and house? auto? (empty? checks))
      (t/deny)
      (t/allow))))

(defguardfn the-test-selection-pattern-compiles
  {:reads []
   :open "No other door changes this verdict. Restate the policy with a select_pattern that is a regular expression, or leave it empty for the Clojure shape."
   :explain "The bench checks a seat's test selection against this pattern before it dispatches the workflow. A pattern that does not compile would refuse every selection."}
  ;; ticket efa54182: the rig judges `select` against the pattern, so
  ;; a pattern it cannot compile is refused here, where a person reads
  ;; why, and not at every seat's test afterwards.
  [_row inp _ctx]
  (let [p (get-in inp [:test :select_pattern])]
    (if (or (nil? p)
            (try (re-pattern (str p)) true
                 (catch Exception _ false)))
      (t/allow)
      (t/deny))))

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

(defguardfn the-engine-notes-the-source
  {:reads [:principal]
   :hide true
   :explain "The GitHub source writes what it could not read. A person and a model read it."}
  ;; The shape of `the-engine-marks-the-enrolment`, for the source's
  ;; own note (ticket 116dfb0d): the forge pass is the only hand that
  ;; walks this door, and a hidden door answers 404 and says nothing.
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(defguardfn the-engine-notes-the-base
  {:reads [:principal]
   :hide true
   :explain "The GitHub source writes what the base branch's checks say. A person and a model read it."}
  ;; `the-engine-notes-the-source`'s shape, for the base pass (ticket
  ;; ade81ae9): the forge pass is the only hand that walks this door.
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

(defn source-note-head
  "The words a source note opens with: the status and the route, and
  not the time. The forge pass compares a stored note by this head, so
  a repository that keeps refusing is noted once and not once a beat."
  [answered route]
  (str "GitHub answered " answered " for " route))

(defhandler note-the-source [row inp ctx]
  ;; THE SOURCE'S OWN RECORD (ticket 116dfb0d). A pass that could not
  ;; read this repository says so with the status, the route and the
  ;; time; a pass that read it clears the note. The input names neither
  ;; field of the row, so the door is not edit-shaped.
  (assoc-in row [:data :source_note]
            (when (and (some? (:answered inp))
                       (not (str/blank? (str (:route inp)))))
              (str (source-note-head (:answered inp) (:route inp))
                   " at " (:now ctx)
                   ": the token cannot read this repository, so the house"
                   " sees none of its pull requests and merges none."))))

(defhandler note-the-base [row inp ctx]
  ;; THE BASE PASS'S OWN RECORD (ticket ade81ae9). The input names no
  ;; field of the row, so the door is not edit-shaped; the pass walks it
  ;; only when one of the four moved.
  (update row :data assoc
          :base_state (:verdict inp)
          :base_head (:head inp)
          :base_red_from (:red_from inp)
          :base_ticket (:ticket inp)
          :base_checked_at (:now ctx)))

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

(defscenario a-test-selection-pattern-must-compile
  "A select_pattern the rig could not compile would refuse every
   seat's test, so the restate refuses it first."
  {:kind    :repo_policy
   :attempt :restate
   :row     {:state :active :data a-policy}
   :input   (assoc a-policy :test {:workflow "tests.yml" :input "only"
                                   :select_pattern "^[A-Za-z_"})
   :as      {:id "colton" :type :person}
   :expect  {:refused :the-test-selection-pattern-compiles}})

(defscenario a-test-selection-pattern-that-compiles-stands
  "…and a pattern that compiles is the person's to state."
  {:kind    :repo_policy
   :attempt :restate
   :row     {:state :active :data a-policy}
   :input   (assoc a-policy :test {:workflow "tests.yml" :input "only"
                                   :select_pattern "^[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+$"})
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario the-house-never-merges-what-nothing-tested
  "A policy that says the house merges a green change names the checks
   that make it green. An empty list is refused, even from the person."
  {:kind    :repo_policy
   :attempt :restate
   :row     {:state :active :data a-policy}
   :input   (assoc a-policy :merge_by "house" :required_checks [])
   :as      {:id "colton" :type :person}
   :expect  {:refused :the-house-merges-only-what-a-check-tested}})

(defscenario the-house-merges-behind-a-named-check
  "…and with one check named, the house may merge."
  {:kind    :repo_policy
   :attempt :restate
   :row     {:state :active :data a-policy}
   :input   (assoc a-policy :merge_by "house" :required_checks ["gate"]
                   :merge_method "squash")
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

;; ── the fields, spelled once and read by two doors ──────────────────

(def ^:private formatter-choices
  {"runner" "The runner pushes a fixup commit when the format is wrong"
   "none" "Nothing formats: the push stands as the seat wrote it"})

(def ^:private merge-by-choices
  {"github" "GitHub's own auto-merge merges it"
   "house" "The engine asks the bench rig to merge it"})

(def ^:private merge-method-choices
  {"merge" "A merge commit"
   "squash" "One squashed commit"
   "rebase" "The commits, rebased onto the base"})

;; the merge train (ticket 394d0602, slice a of 3deb06ed). A later
;; strategy is one more member here, and the enum follows the keys.
(def ^:private merge-strategy-choices
  {"line" "The line: one change at a time, each brought up to date and re-checked before it merges"
   "train" "The merge train: the front and up to train_size-1 green changes behind it are tested together and land together"})

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
   ;; the house's merge (ticket 4dfb00f6). OPTIONAL, because a policy
   ;; stated before these three existed is restated whole without them,
   ;; and every reader spells the default itself.
   [:merge_by {:optional true
               :default "github"
               :x-display
               {:label "Who merges a green change"
                :choices merge-by-choices
                :help "github turns on GitHub's own auto-merge. house is for a repository where GitHub cannot: its auto-merge needs a public repository or a paid plan. With house, the engine asks the bench rig to merge each green change every five minutes. Read only when auto-merge is true."}}
    (into [:enum] (sort (keys merge-by-choices)))]
   [:required_checks {:optional true
                      :default []
                      :examples [["gate"]]
                      :x-display
                      {:raw true
                       :label "The checks that must be green"
                       :help "The check names the house waits for before it merges, one for each row. The house merges nothing when this list is empty."}}
    [:vector [:string {:min 1 :max 200}]]]
   [:merge_method {:optional true
                   :default "merge"
                   :x-display
                   {:label "How the house merges"
                    :choices merge-method-choices
                    :help "The merge the rig asks GitHub for when the house merges a green change."}}
    (into [:enum] (sort (keys merge-method-choices)))]
   ;; the person's merge (ticket 4d59b22d). OPTIONAL for the reason the
   ;; three above are: a policy stated before it existed is restated
   ;; whole without it, and the reader spells the default itself.
   [:merge_wait_seconds {:optional true
                         :default 3600
                         :examples [3600]
                         :x-display
                         {:label "How long a green change waits on you"
                          :help "When the house does not merge this repository, a change whose checks are green and that merges clean waits this many seconds for a person. Then the house asks for the merge once, as a held call: your Allow merges it."}}
    [:int {:min 60 :max 604800}]]
   ;; one deploy at a time (ticket 47217098). OPTIONAL for the reason
   ;; merge_wait_seconds is.
   [:deploy_check {:optional true
                   :examples ["deploy"]
                   :x-display
                   {:raw true
                    :label "The job that deploys the base"
                    :help "The name of the check on the base branch whose success means that commit is deployed. After each house merge the house merges nothing more here until this check is green on a commit that holds the merge. Empty means a merge counts as deployed."}}
    [:maybe [:string {:max 200}]]]
   [:deploy_wait_seconds {:optional true
                          :default 1800
                          :examples [1800]
                          :x-display
                          {:label "How long the line waits on a deploy"
                           :help "The longest the house waits for the deploy check to report on a merge before it notes that and merges the next change. A red deploy holds the line past it."}}
    [:int {:min 60 :max 86400}]]
   [:rounds_per_change {:default 3
                        :examples [3]
                        :x-display
                        {:label "Rounds for one change"
                         :help "How many times a seat may submit one change before the house stops. At the ceiling the change moves to stuck and waits for a person."}}
    [:int {:min 1 :max 20}]]
   ;; the merge train (ticket 394d0602, slice a of 3deb06ed). Both
   ;; OPTIONAL for the reason merge_wait_seconds is: a row that
   ;; predates them reads as line / 4 (bench's `merge-strategy-of` and
   ;; `train-size-of`). Nothing reads them yet
   [:merge_strategy {:optional true
                     :default "line"
                     :examples ["line"]
                     :x-display
                     {:label "How the house merges"
                      :choices merge-strategy-choices
                      :help "line merges one change at a time, each brought up to date and re-checked first. train tests the front and the green changes behind it together, and lands them together. Switching is safe at any time: a train that is running finishes, or is discarded, before the line changes shape."}}
    (into [:enum] (sort (keys merge-strategy-choices)))]
   [:train_size {:optional true
                 :default 4
                 :examples [4]
                 :x-display
                 {:label "How many changes ride one train"
                  :help "How many green changes the house tests and merges together as one train, from 2 to 10. It applies only to the train strategy; the line reads none of it."}}
    [:int {:min 2 :max 10}]]
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
   ;; the bench's test (ticket bae401d5). OPTIONAL: a repository with
   ;; no test workflow states none, and enroll then sends no `test`.
   [:test {:optional true
           :examples [{:workflow "tests.yml" :input "only"}]
           :x-display
           {:raw true
            :label "The test workflow"
            :help "The workflow the bench's test dispatches, and the name of its input that narrows the run to what a seat touched."}}
    [:map
     [:workflow {:x-display {:label "Workflow"
                             :help "The workflow file the bench's test dispatches, such as tests.yml."}}
      [:string {:min 1 :max 200}]]
     [:input {:x-display {:label "Narrowing input"
                          :help "The name of the workflow's input that narrows the run to what a seat touched."}}
      [:string {:min 1 :max 120}]]
     ;; ticket efa54182: OPTIONAL, and the rig's default when absent
     [:select_pattern {:optional true
                       :examples ["^[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+$"]
                       :x-display {:raw true
                                   :label "What a test selection looks like"
                                   :help "A regular expression the bench checks a seat's test selection against before it dispatches the workflow. Leave it empty for the Clojure shape: a dotted namespace ending in -test. A Python repository states its own, such as dotted module names."}}
      [:maybe [:string {:min 1 :max 200}]]]]]
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
    [:maybe [:string {:max 500}]]]
   ;; the GitHub source's own note (ticket 116dfb0d): a repository the
   ;; token cannot read otherwise costs only a log line, and a green
   ;; pull request there never merges while nobody is told
   [:source_note {:optional true
                  :examples ["GitHub answered 403 for GET /repos/ckopsa/waymark-doors/pulls at 2026-09-27T14:00:00Z: the token cannot read this repository, so the house sees none of its pull requests and merges none."]
                  :x-display
                  {:widget "prose"
                   :label "What the GitHub source could not read"
                   :help "The status and the route of the last pass that could not read this repository's pull requests, and when. Empty when the last pass read them."}}
    [:maybe [:string {:max 500}]]]
   ;; the base branch's own state (ticket ade81ae9), so a person reads
   ;; whether main is red without opening GitHub
   [:base_state {:optional true
                 :x-display
                 {:label "The base branch is"
                  :help "What the checks on the head of the base branch said at the last pass that saw them move: green, red, or unknown while one is still running. Empty until the first read."}}
    [:maybe [:enum "green" "red" "unknown"]]]
   [:base_head {:optional true
                :examples ["1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"]
                :x-display
                {:raw true
                 :label "The base branch's head"
                 :help "The commit those checks ran on."}}
    [:maybe [:string {:max 64}]]]
   [:base_red_from {:optional true
                    :examples ["1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"]
                    :x-display
                    {:raw true
                     :label "Red since"
                     :help "The first red head after a green one, while the base is not green again. Empty when the pass cannot tell."}}
    [:maybe [:string {:max 64}]]]
   [:base_ticket {:optional true
                  :examples ["01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]
                  :x-display
                  {:raw true
                   :label "The red-base ticket"
                   :help "The ticket the engine opened the last time the base stayed red. While it has not ended, a later red head is written on it and no second ticket is opened."}}
    [:maybe [:string {:max 64}]]]
   [:base_checked_at {:optional true
                      :examples ["2026-09-27T19:30:00Z"]
                      :x-display
                      {:label "Base read at"
                       :help "When the pass last wrote the base's state, which it does when that state moves."}}
    [:maybe :waymark/instant]]
   ;; one deploy at a time (ticket 47217098): the merge pass writes the
   ;; house's merges here, and the forge pass takes each off once the
   ;; deploy check is green on a base commit that holds it
   [:deploy_waits_on {:optional true
                      :examples [["2847912e-7783-4651-bead-61eab0492776 47217098-4c6a-484d-88c0-f6eb69ab820c 250"]]
                      :x-display
                      {:raw true
                       :label "Merges not deployed yet"
                       :help "Each house merge whose deploy the line waits on: the change, its ticket and its pull request. The house merges nothing more in this repository while one is here."}}
    [:maybe [:vector [:string {:max 200}]]]]
   [:deploy_waiting_since {:optional true
                           :examples ["2026-09-28T12:00:00Z"]
                           :x-display
                           {:label "Waiting on a deploy since"
                            :help "When the oldest merge above was made."}}
    [:maybe :waymark/instant]]
   [:deployed_head {:optional true
                    :examples ["1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"]
                    :x-display
                    {:raw true
                     :label "The last deployed commit"
                     :help "The newest base commit the forge pass saw the deploy check green on."}}
    [:maybe [:string {:max 64}]]]
   [:deployed_at {:optional true
                  :examples ["2026-09-28T12:00:00Z"]
                  :x-display
                  {:label "Deployed at"
                   :help "When the forge pass first saw that commit deployed."}}
    [:maybe :waymark/instant]]
   [:deploy_state {:optional true
                   :x-display
                   {:label "The last deploy was"
                    :help "What the deploy check said at its last finish: green, or red, which holds the line until it is green again."}}
    [:maybe [:enum "green" "red"]]]
   [:deploy_note {:optional true
                  :examples ["The line waits on the deploy of #250, since 2026-09-28T12:00:00Z."]
                  :x-display
                  {:widget "prose"
                   :label "What the line waits for"
                   :help "The deploy the house's merge line waits on and since when, a red deploy, or a wait the house gave up on."}}
    [:maybe [:string {:max 500}]]]
   ;; the house's merge line (ticket b85aded5): the merge pass writes
   ;; these each time the line moves, as a maintenance write, and
   ;; clears them when no change stands in the line
   [:line_front {:optional true :filter #{:eq}
                 :examples ["2847912e-7783-4651-bead-61eab0492776"]
                 :x-display
                 {:raw true
                  :label "The front of the merge line"
                  :help "The change the house brings up to date next. Only the front is brought up to date; the others wait their turn. Empty when no change stands in the line."}}
    [:maybe [:string {:max 64}]]]
   [:line_front_pr {:optional true
                    :examples [250]
                    :x-display
                    {:label "The front's pull request"
                     :help "The number of the front's pull request."}}
    [:maybe [:int {:min 1}]]]
   [:line_front_waiting {:optional true :filter #{:eq :in}
                         :x-display
                         {:label "The front waits on"
                          :choices {"update" "It was just brought up to date, and its checks run on the new head"
                                    "checks" "Its checks are still running"
                                    "merge" "It was offered the merge, and GitHub has not merged it yet"
                                    "train" "It rides a train, and the train's checks run"}}}
    [:maybe [:enum "update" "checks" "merge" "train"]]]
   [:line_waiting {:optional true
                   :examples [2]
                   :x-display
                   {:label "Changes waiting behind it"
                    :help "How many other changes stand in the line behind the front."}}
    [:maybe [:int {:min 0}]]]
   [:line_at {:optional true
              :examples ["2026-09-28T12:00:00Z"]
              :x-display
              {:label "Line read at"
               :help "When the merge pass last wrote the line, which it does when the line moves."}}
    [:maybe :waymark/instant]]
   ;; the merge train (ticket 394d0602): the train that stands now, as
   ;; the merge pass writes it. Nothing writes it yet
   [:line_train {:optional true
                 :examples [{:branch "train/ckopsa/waymark/1"
                             :changes ["2847912e-7783-4651-bead-61eab0492776"]
                             :prs [250]
                             :head "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"
                             :base_head "0e1d2c3b4a5968778695a4b3c2d1e0f912345678"
                             :run_id "123456789"
                             :workflow "tests.yml"
                             :started_at "2026-09-29T12:00:00Z"}]
                 :x-display
                 {:raw true
                  :label "The train"
                  :help "The changes that ride one train together: its branch, their changes and pull requests, the train's head and the base head it was built on, the check run that tests it, and when it started. Empty when no train stands."}}
    [:maybe
     [:map
      [:branch [:string {:max 200}]]
      [:changes [:vector [:string {:max 64}]]]
      [:prs [:vector [:int {:min 1}]]]
      [:head {:optional true} [:maybe [:string {:max 64}]]]
      [:base_head {:optional true} [:maybe [:string {:max 64}]]]
      [:run_id {:optional true} [:maybe [:string {:max 64}]]]
      ;; the policy's test workflow the checks ran (90ce5c73), so a
      ;; train with no run yet is read by the same workflow
      [:workflow {:optional true} [:maybe [:string {:max 200}]]]
      ;; a red train's halves count their trains against the train
      ;; they look in, and a cancelled run is run once more (6033c287)
      [:tries {:optional true} [:maybe [:int {:min 1}]]]
      [:size {:optional true} [:maybe [:int {:min 1}]]]
      [:retried {:optional true} [:maybe :boolean]]
      ;; the pull request a waiting landing answered (c3f0f094)
      [:pr {:optional true} [:maybe [:int {:min 1}]]]
      ;; the pull request's own run is the train's check, and none was
      ;; dispatched (e2d485c2)
      [:pr_run {:optional true} [:maybe :boolean]]
      [:started_at :waymark/instant]]]]])

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
   :create-guards [a-person-or-their-delegate-states-the-policy
                   the-house-merges-only-what-a-check-tested
                   the-test-selection-pattern-compiles]
   ;; …and the rig is told at the birth (R-2): a create cannot walk a
   ;; door on a row that does not exist yet
   :on-create enrol-at-birth
   :actions
   {:restate
    {:from #{:active} :to :active
     :input (into [:map] policy-fields)
     :guards [a-person-or-their-delegate-states-the-policy
              the-house-merges-only-what-a-check-tested
              the-test-selection-pattern-compiles]
     :handler restate-the-policy
     :record true
     ;; the form opens on the policy that stands, so a person changes
     ;; one number and restates the rest as it was. The engine's own
     ;; two fields are absent here for the reason they are absent from
     ;; the create form: a person does not state them.
     :edit {:prefill [:repository :clone_url :branch_pattern :base :max_lines
                      :opens_pr :auto_merge :merge_by :required_checks
                      :merge_method :merge_wait_seconds
                      :deploy_check :deploy_wait_seconds
                      :rounds_per_change :merge_strategy :train_size :formatter
                      :deny :test :orientation]}
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
               :description "The bench took this repository and the engine says when"}}

    ;; THE SOURCE'S OWN DOOR (ticket 116dfb0d). Hidden, and the engine's
    ;; hand alone, for mark_enrolled's reasons. A status and a route
    ;; write the note; an input with neither clears it.
    :note_source
    {:from #{:active} :to :active
     :guards [the-engine-notes-the-source]
     :handler note-the-source
     :input [:map
             [:answered {:optional true
                         :examples [403]
                         :x-display {:hidden true
                                     :label "The status GitHub answered"}}
              [:maybe [:int {:min 100 :max 599}]]]
             [:route {:optional true
                      :examples ["GET /repos/ckopsa/waymark-doors/pulls"]
                      :x-display {:hidden true :raw true
                                  :label "The route that refused"}}
              [:maybe [:string {:max 200}]]]]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Source noted" :order 5
               :description "The GitHub source says whether it could read this repository"}}

    ;; THE BASE PASS'S OWN DOOR (ticket ade81ae9). Hidden, and the
    ;; engine's hand alone, for note_source's reasons.
    :note_base
    {:from #{:active} :to :active
     :guards [the-engine-notes-the-base]
     :handler note-the-base
     :input [:map
             [:verdict {:optional true
                        :x-display {:hidden true
                                    :label "What the base's checks said"}}
              [:maybe [:enum "green" "red" "unknown"]]]
             [:head {:optional true
                     :x-display {:hidden true :raw true
                                 :label "The base's head"}}
              [:maybe [:string {:max 64}]]]
             [:red_from {:optional true
                         :x-display {:hidden true :raw true
                                     :label "The first red head"}}
              [:maybe [:string {:max 64}]]]
             [:ticket {:optional true
                       :x-display {:hidden true :raw true
                                   :label "The red-base ticket"}}
              [:maybe [:string {:max 64}]]]]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Base noted" :order 6
               :description "The GitHub source says what the base branch's checks say"}}}
   ;; The delegate's allow — an agent whose principal names whom it
   ;; acts for — is the suite's to prove (bench_test): a check-tier
   ;; scenario's actor carries id, roles and type, and no acts-for.
   :scenarios [a-model-does-not-restate-the-policy
               the-person-states-what-submit-means
               the-house-never-merges-what-nothing-tested
               the-house-merges-behind-a-named-check]})
