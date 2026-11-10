(ns waymark10.server.mcp.bench
  "The bench and the change a code seat's sit hands it, carved out of
  waymark10.server.mcp (ticket 31bf1d7c): which repository a seat
  works, read off its scope; the change row for the walk row, found or
  minted with the engine's own hand and put back to work (reopened,
  unstuck, rehomed, rebranched, linked); and the branch the worktree
  is prepared on.

  `sit`, the walk selection, the prepare and the feedback stay in mcp,
  which calls through the public vars here. `change-said` stays there
  too, because it reads the change as the sitter through the route."
  (:require [clojure.string :as str]
            [waymark10.modules.hooks :as hooks]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(defn row-of
  "One decoded row, or nil — the seat's schedule and the schedule's
  model, read the way grants.clj reads a grant."
  [eng kind id]
  (when-some [rdef (get (inv/resources eng) kind)]
    (when-some [id (some-> id str not-empty)]
      (some->> (store/with-tx (:storage eng)
                 (fn [tx] (store/load-row (:storage eng) tx kind id {})))
               (inv/decode-row rdef)))))

;; ── the bench, in the sit's answer (spec-seat.md R-12.29) ───────────
;;
;; A code seat sits down to a CHECKOUT, not to a page of rows. The
;; bench rig (bead waymark-fp62.6.3.1) holds a bare clone for each
;; repository and a worktree for each change, behind Gate. The engine
;; makes that worktree BEFORE the sit answers, so the model finds the
;; branch made and the earlier sitting's edits still in it: the sit
;; calls the rig's `prepare` with the engine's own hand, PAST
;; `invoke-for` and past the leash, exactly as the thread sources call
;; Gate (workqueue10.sources.gate-chat). `prepare` is idempotent, it
;; is on no capability token, and no scope can name it — a seat can
;; read, edit and pull on the bench, and it can never make or move a
;; worktree.
;;
;; WHAT SUBMIT MEANS IS A ROW. `repo_policy` (factory10) holds the
;; branch pattern, the base, the size ceiling, whether a push opens a
;; pull request, whether the gate merges, and how many rounds one
;; change gets. This section READS that row and says the answer in one
;; sentence. The model never chooses any of it.
;;
;; A DARK RIG DOES NOT REFUSE THE SIT. Gate down, the rig down, a
;; repository the rig does not hold: each one answers no `bench` and a
;; `bench_note` sentence, and the walk still rides. A sitting that
;; cannot reach the bench can still read its rows and say so.

(def bench-walks
  "The two kinds whose first row names a change: the walk itself, and
  the red run that points at one."
  #{"change" "ci_run"})

(def default-orientation
  "Where a seat reads what this repository expects of it, when the
  policy names no other path (R-7)."
  "docs/orientation.md")

(def no-orientation-said
  "What the sit answers in place of a path when the worktree holds no
  orientation file (bead waymark-fp62.6.3.8, R-6). A path to a file
  that is not there is a read the seat spends a call on and a refusal
  it has to reason about; one sentence says the same thing and carries
  what the document would have said first — what submit means here."
  "This repository has no orientation file. Submit means: ")

(def default-base "main")

(def ^:private default-branch-pattern
  "The branch a change gets when the policy names no pattern. The `*`
  is the change row's own id."
  "waymark/*")

(defn bench-result
  "What the rig said, as a map, or nil. A REFUSAL IS SAID HERE TOO: it
  is the rig's own answer, and the reader above decides what the
  refusal means (bead waymark-fp62.6.3.11)."
  [payload]
  (when (map? payload)
    (let [r (get-in payload [:structuredContent :result])]
      (when (map? r) r))))

(defn bench-payload
  "The worktree the rig made, or nil. A refusal is not a worktree: the
  rig says `refused` with its name, and this section then says nothing
  rather than putting a refusal where a worktree goes."
  [payload]
  (when-not (:isError payload)
    (when-some [r (bench-result payload)]
      (when (nil? (:refused r)) r))))

(defn bench-refusal
  "The refusal the rig answered with, or nil. A REFUSAL IS AN ANSWER
  (bead waymark-fp62.6.3.11): the map names the refuser, the command
  it would not run and the reason, and a sit that carries the reason
  tells the seat WHY there is no worktree."
  [payload]
  (when-some [r (bench-result payload)]
    (when (some-> (:refused r) str not-empty) r)))

(defn repo-policy-of
  "The active policy row for one repository, or nil — and nil is the
  ordinary answer in an engine that declares no `repo_policy` at all,
  which is every engine but the factory's. The repository policy's kit
  answers it through the `:gate-policy-for-repo` hook
  (waymark10.modules.hooks), the same one the gate asks."
  [eng repository]
  (hooks/hook :gate-policy-for-repo eng repository))

(defn- change-of-walk
  "The change row this firing works on, read from the first row of the
  walk: the row itself when the seat walks changes, and the run's own
  change when it walks ci_runs. nil when the first row names none."
  [eng walk]
  (when-some [id (some-> (get-in walk ["rows" 0 "id"]) str not-empty)]
    (case (str (get walk "kind"))
      "change" (row-of eng :change id)
      "ci_run" (when-some [run (row-of eng :ci_run id)]
                 (row-of eng :change (get-in run [:data :change])))
      nil)))

;; ── a seat that walks something else (R-12.32) ──────────────────────
;;
;; A CODE SEAT MUST BUILD WHAT A PERSON ASKS FOR, and an ask is a row
;; in a queue — a `task` in a task list (bead waymark-fp62.6.3.10).
;; Such a row names no repository and has no change, and `submit` is a
;; door on `change`. So the engine reads the repository off the SEAT
;; and mints one change row for the walk row it works.
;;
;; THE REPOSITORY IS THE SCOPE'S. A code seat already names its
;; repository five times: each bench power entry carries a filter with
;; the repo it may touch (R-12.30). When every entry that WRITES names
;; the same single repository, that is the seat's repository, and the
;; reading entries may name it and others beside it for reference.
;; When they name none, or two, the sit gives no bench and one
;; sentence that says what to write in the scope. Nothing new is
;; declared for this: the authority the seat already holds is the
;; authority it is read by.
;;
;; THE CHANGE IS MINTED ONCE. `change_id` is the kind name, a colon
;; and the walk row's own id, and `change_id` is `:unique`, so a
;; second sitting on the same row finds the first sitting's change
;; rather than a second one. The mint is the ENGINE's own hand:
;; `the-mirror-writes-this-row` admits a `:system` principal and
;; nobody else, which is the same wall the GitHub source writes past.

(def ^:private bench-power-prefix
  "What a scope entry's kind starts with when it names a bench power:
  `bench.find`, `bench.read`. The rig's own row name, read from the
  gate proxy so the two spellings cannot drift."
  (str gate/bench-rig "."))

(def ^:private seat-change-principal
  "The hand the sit mints a change with. It is a system principal —
  the engine's own actor, not the sitter and not the person behind
  it — because the change kind's birth door is the mirror's
  (factory10.mirror/the-mirror-writes-this-row) and a seat holds no
  key to it."
  (t/principal {:id "waymark10-seat"
                :type :system
                :display "The seat's own bench"}))

(def ^:private seat-repo-note
  "What the sit says in place of a bench when the seat's scope does
  not name ONE repository (R-12.32). It is a sentence about the SEAT,
  and it names what a person must write: the rows still ride, and the
  sitting can say what it could not do."
  (str "The bench did not open, because this seat's scope does not "
       "name one repository. A seat that walks something other than a "
       "change reads its repository from its own bench powers: every "
       "bench entry that writes (bench.edit, bench.edit_many, bench.pull, "
       "bench.feedback, bench.rerun) "
       "must carry a filter with the same one repo, and every bench.find "
       "and bench.read entry must name that repo too, alone or with "
       "others after a comma. Work from the rows, and say what you "
       "could not do."))

(def ^:private no-change-note
  "What the sit says when the change for this firing could neither be
  found nor minted. The row that refused is in the engine's log; the
  seat is told only that there is no bench, because a refusal it
  cannot act on is a refusal it must not reason about."
  (str "The bench did not open, because this firing has no change row "
       "to submit. Work from the rows, and say what you could not do."))

(def ^:private bench-write-tokens
  "The bench powers that CHOOSE the seat's repository: the ones that
  write its worktree, and the one that reads what a push of it caused.
  The reading powers (`bench.find`, `bench.read`) only have to include
  it, and may name other repositories beside it (R-12.32). `bench.rerun`
  re-runs the checks of the branch the seat pushed, so it chooses the
  repository the way `bench.pull` does. `bench.edit_many` is many
  `bench.edit`s in one call: it writes the same worktree, so it chooses
  the repository the way the single edit does, and a seat that holds
  the batch and not the single edit still chooses by what it writes
  with (ticket d4b13a7f)."
  #{"bench.edit" "bench.edit_many" "bench.pull" "bench.feedback"
    "bench.rerun"})

(defn- entry-repos
  "The repositories one bench entry's filter names, trimmed and in
  order — a comma means \"any of these\" (R-12.30) — or nil when the
  entry names none."
  [entry]
  (when-some [v (get-in entry [:filter :repo])]
    (not-empty (into [] (comp (map str/trim) (remove str/blank?))
                     (str/split (str v) #",")))))

(defn- choosing-entries
  "The entries that may choose a repository, out of `entries`. An entry
  narrowed to a `path` is an addition - one more file the seat may
  touch - so it does not choose where its power has an entry with no
  path (ticket d30c390f). A power whose every entry carries a path
  still chooses by them."
  [entries]
  (let [path? #(some? (get-in % [:filter :path]))
        general (into #{} (comp (remove path?) (map (comp str :kind))) entries)]
    (filterv #(not (and (path? %) (contains? general (str (:kind %)))))
             entries)))

(defn seat-repositories
  "The repository this seat works and the ones it may only read, read
  from its own scope (R-12.32) → {:repo r :reference [r …]}, or nil.

  The entries that WRITE choose: every one of them must carry a
  `filter` with a `repo`, every one must name the same value, and a
  comma in it — \"any of these repositories\" (R-12.30) — is not ONE
  repository. A scope with no writing entry chooses from its reading
  entries by the same rule. Every reading entry must then include the
  chosen repository, so a seat always reads what it edits; the other
  repositories it names are the seat's references."
  [seat]
  (let [entries (filterv #(str/starts-with? (str (:kind %)) bench-power-prefix)
                         (get-in seat [:data :scope]))
        writes? #(contains? bench-write-tokens (str (:kind %)))
        reads (filterv (complement writes?) entries)
        choosers (choosing-entries
                  (or (not-empty (filterv writes? entries)) reads))
        named (mapv #(some-> (get-in % [:filter :repo]) str str/trim) choosers)
        repo (when (and (seq named)
                        (every? #(not (str/blank? (str %))) named)
                        (apply = named)
                        (not (str/includes? (first named) ",")))
               (first named))
        read-lists (mapv entry-repos reads)]
    (when (and repo (every? #(some #{repo} %) read-lists))
      {:repo repo
       :reference (->> (apply concat read-lists)
                       (remove #{repo})
                       distinct
                       sort
                       vec)})))

(defn- seat-repository
  "The one repository this seat works, read from its own scope
  (R-12.32), or nil. `seat-repositories` says how."
  [seat]
  (:repo (seat-repositories seat)))

(defn- bench-seat?
  "Is this a CODE seat? A seat whose scope names no bench power at all
  never opens a worktree: it walks its queue and takes its doors, and
  the sit says nothing to it about a bench, a change or a repository.
  The post clerk is that seat, and so is every seat of the household."
  [seat]
  (boolean (some #(str/starts-with? (str (:kind %)) bench-power-prefix)
                 (get-in seat [:data :scope]))))

(defn code-seat?
  "Does this seat BUILD, and not only read? Its scope names a door of
  the `change` kind (the doors a round ends with), or it holds a bench
  power that writes its worktree (`bench-write-tokens`). A `change`
  entry with no actions reads the row and ends no round (ticket
  7a4a6f9e). A seat with neither reads code with its own calls and has
  no change to work."
  [seat]
  (let [scope (get-in seat [:data :scope])]
    (boolean (or (some #(and (= "change" (str (:kind %))) (seq (:actions %)))
                       scope)
                 (some #(contains? bench-write-tokens (str (:kind %))) scope)))))

(defn- bench-tokens
  "Every bench power this seat's scope names, sorted and without
  repeats. It is the seat's own list: a power the scope does not name
  is a power the seat does not hold."
  [seat]
  (->> (get-in seat [:data :scope])
       (map #(str (:kind %)))
       (filter #(str/starts-with? % bench-power-prefix))
       distinct
       sort
       vec))

(defn bench-tools-of
  "EVERY BENCH TOOL THE SEAT'S GRANT HOLDS (R-12.29,
  waymark-fp62.6.3.12) → {\"bench.read\" \"bench__read\",
  \"bench.prepare\" \"bench__prepare\", …}.

  The seat's scope gives the tokens and the bench row's powers give
  EVERY tool each token names, through the same `token-tools` the
  power door admits a call by. Each tool is keyed by its own bare
  name under the rig's prefix, so a token that covers several tools
  (`bench.read` → read, prepare, status; `bench.symbols` → symbols,
  read_symbol) lists them all, and a token of one tool keeps the key
  it always had. The map is built from the grant, so it cannot drift
  from it: a power the scope does not name adds no tool."
  [eng seat]
  (let [prefix (gate/bench-tool "")]
    (into (sorted-map)
          (for [token (bench-tokens seat)
                nm (gate/token-tools eng token)
                :when (str/starts-with? nm prefix)]
            [(str bench-power-prefix (subs nm (count prefix))) nm]))))

(defn- change-by-id
  "The change row with this `change_id`, or nil. `change_id` is
  `:unique`, so there is at most one."
  [eng change-id]
  (when-some [rdef (get (inv/resources eng) :change)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx]
                 (first (hooks/hook :factory-changes eng tx
                                    {:change_id (str change-id)} 1))))
             (inv/decode-row rdef))))

(def change-scan-limit
  "How many change rows one lookup by a data field reads. A walk row
  and a branch each hold one live change in a house that works, and
  the ceiling keeps a strange history from making the sit read a
  table."
  20)

(defn- change-in-state
  "The first change row whose data match `where` and whose state is
  the first of `states` any of them is in, or nil. `states` is the
  order of preference."
  [eng where states]
  (when-some [rdef (get (inv/resources eng) :change)]
    (let [st (:storage eng)
          rows (->> (store/with-tx st
                      (fn [tx]
                        (hooks/hook :factory-changes eng tx where
                                    change-scan-limit)))
                    (map #(inv/decode-row rdef %)))
          in? (fn [state row] (= state (some-> (:state row) name keyword)))]
      (some (fn [state] (some #(when (in? state %) %) rows)) states))))

(def ^:private change-title-max
  "The change kind's own ceiling on a title. A walk row's summary line
  is short, and a title that overran it would refuse the mint."
  400)

(defn- walk-row-title
  "What the change is called: the walk row's own title when the
  collection's grid carries one, else its summary line. A row says
  what it is in one of the two, and the change wears the same words so
  a person reads one story in both places."
  [row]
  (let [values (or (get row "data") (get row "fields"))
        said (or (some-> (get values "title") str not-empty)
                 (some-> (get row "summary") str not-empty)
                 (str (get row "kind") " " (get row "id")))]
    (subs said 0 (min (count said) change-title-max))))

(defn- pattern-branch
  "The branch a walk row's change is worked on: the policy's pattern
  with that row's own id in place of the `*`. The mint writes it at
  birth, and the sit mints it again when the pattern moves under a
  change that never opened (bead waymark-fp62.6.3.11)."
  [policy row-id]
  (str/replace (or (some-> (get-in policy [:data :branch_pattern])
                           str not-empty)
                   default-branch-pattern)
               "*" (str row-id)))

;; ── the walk row's own home (R-12.32a) ──────────────────────────────
;;
;; A WALK ROW MAY NAME ITS REPOSITORY AND ITS BRANCH. A seat whose
;; scope reaches several repositories cannot have one read off it, so
;; the row it walks says which one its work is in (`repo` or
;; `repository`) and, when the house names branches by something
;; other than the row's id, which branch (`branch`). The engine then
;; prepares THERE, with its own hand, so a row names only a repository
;; every bench entry of the seat already reaches: a row never widens
;; what the seat may touch. A row that names nothing leaves the seat's
;; own one repository to choose, as it always did.

(defn- row-said
  "One of the walk row's own values, trimmed, or nil."
  [row k]
  (let [values (or (get row "data") (get row "fields"))]
    (some-> (get values k) str str/trim not-empty)))

(defn- row-repository
  "The repository the walk row says its work is in, or nil."
  [row]
  (or (row-said row "repo") (row-said row "repository")))

(defn- seat-reaches-repo?
  "Does every bench power this seat holds have an entry that names
  `repo`? The entries are grouped by `kind`, and one entry of each is
  enough: an entry narrowed to a path in another repository is one more
  file the seat may touch, and does not shut the bench (ticket
  d30c390f). An entry with no repo filter names none, so a power held
  only by such entries reaches nothing by a row."
  [seat repo]
  (let [entries (filterv #(str/starts-with? (str (:kind %)) bench-power-prefix)
                         (get-in seat [:data :scope]))]
    (boolean (and (seq entries)
                  (every? (fn [[_ held]]
                            (some #(some #{repo} (entry-repos %)) held))
                          (group-by (comp str :kind) entries))))))

(defn- several-repositories?
  "Does every CHOOSING entry of the seat name the SAME two or more
  repositories? That is a seat built to work several, not a scope
  written wrong: entries that disagree still get `seat-repo-note`.
  The choosers are `seat-repositories`' own: the writing entries, or
  every bench entry when the scope names no writing token - a rig
  whose powers group its tools under other tokens (bench.write for
  edit and pull) names none of `bench-write-tokens`."
  [seat]
  (let [entries (filterv #(str/starts-with? (str (:kind %)) bench-power-prefix)
                         (get-in seat [:data :scope]))
        writes (filterv #(contains? bench-write-tokens (str (:kind %))) entries)
        choosers (choosing-entries (or (not-empty writes) entries))
        sets (mapv (comp set entry-repos) choosers)]
    (boolean (and (seq choosers)
                  (apply = sets)
                  (< 1 (count (first sets)))))))

(defn- row-elsewhere-note
  "What the sit says when the row names a repository the seat's bench
  powers do not all reach."
  [repo]
  (str "The row names the repository " repo ", which this seat's bench "
       "powers do not all reach, so no worktree was prepared. Say so in "
       "one sentence: a person corrects the row or the seat's scope."))

(def ^:private several-repos-note
  "What the sit says to a seat that works several repositories when the
  row it walks names none of them. Nothing is wrong with the seat."
  (str "This seat works several repositories and the row it walks names "
       "none of them, so the engine prepared no worktree for it. Prepare "
       "the repository the work is in with your own bench call, on the "
       "branch the work should have."))

(defn- walk-home
  "Where the walk row's change lives → [repo nil], or [nil sentence]
  when there is none: the row's own repository when the seat reaches
  it, else the seat's one repository (R-12.32a)."
  [seat row]
  (if-some [said (row-repository row)]
    (if (seat-reaches-repo? seat said)
      [said nil]
      [nil (row-elsewhere-note said)])
    (if-some [repo (seat-repository seat)]
      [repo nil]
      [nil (if (several-repositories? seat) several-repos-note seat-repo-note)])))

(defn- wanted-branch
  "The branch the walk row's change is worked on: the row's own
  `branch` when it names one other than the base, else the policy's
  pattern with the row's id in place of the `*`."
  [policy row row-id]
  (let [said (row-said row "branch")
        base (or (some-> (get-in policy [:data :base]) str not-empty)
                 default-base)]
    (if (and said (not= said base))
      said
      (pattern-branch policy row-id))))

(def ^:private spent-branch-tries
  "How many fresh names `unspent-branch` tries past a spent one."
  20)

(defn- unspent-branch
  "`branch`, or the first of `branch-2`, `branch-3` … that no MERGED
  change in `repo` names (ticket 87fc7996). A merged pull request's
  branch is spent: a walk row whose change merged before its work was
  done — an org_plan merged before go_live — gets a fresh change, cut
  from the base on a branch of its own, not on top of the merged head."
  [eng repo branch]
  (let [spent? #(some? (change-in-state eng {:repository repo :head_branch %}
                                        [:merged]))]
    (or (first (remove spent? (cons branch
                                    (map #(str branch "-" %)
                                         (range 2 (+ 2 spent-branch-tries))))))
        branch)))

(def ^:private link-doors
  "The doors the sit names a walk row's change through, in order:
  `link_change` for a row that names none (ticket db8b35bb), then
  `relink_change` for one past it whose change is gone (ticket
  87fc7996)."
  [:link_change :relink_change])

(defn- linked-change!
  "The walk row learns the change the sit found or minted for it
  (ticket db8b35bb): a kind that declares a `link_change` door — or a
  `relink_change` one — is walked through it with the engine's hand,
  so the row names its change without a door of the seat's own. The
  door's guard decides whether the row takes it — factory10's org_plan
  only when it names none or names a closed or merged one — and its
  refusal is the ordinary answer once the row names it, so it is
  swallowed. → change."
  [eng walk change]
  (let [kind (keyword (str (get walk "kind")))
        id (some-> (get-in walk ["rows" 0 "id"]) str not-empty)]
    (when id
      (doseq [door link-doors
              :when (get-in (inv/resources eng) [kind :actions door])]
        (try
          (inv/invoke! eng kind id door {:change (str (:id change))}
                       {:principal seat-change-principal})
          (catch Exception _ nil))))
    change))

(defn- unspent
  "`change`, or nil when it is merged (ticket a60aed8e). A merged
  change keeps its `change_id` when no adoption wrote over it, and the
  sit must not hand it out: nothing on it can be submitted or stalled.
  A closed one is kept, because `reopened-change` may open it again."
  [change]
  (when-not (= :merged (some-> (:state change) name keyword))
    change))

(defn- minted-change
  "The change row for one walk row: the one that is already here, or
  one minted now with the engine's own hand (R-12.32). The branch is
  the policy's pattern with the WALK row's id in place of the `*`, so
  the branch says which task it builds, and `bench-branch` reads the
  same value back off the row.

  THE ROW KEEPS WHAT IT WAS BORN FROM (bead waymark-fp62.6.3.14).
  `born_from` gets the same `<kind>:<id>`. The source ADOPTS this row
  when the push opens the pull request, and that write puts GitHub's
  identity over `change_id` — so a field of its own is what lets the
  merge complete the walk row this change was built for.

  A REOPENED ROW FINDS ITS SUBMITTED CHANGE (ticket b35ab5b5). Once
  the adoption has written GitHub's id over `change_id`, the lookup by
  `change_id` misses, so the row's own `born_from` is read first: a
  `submitted`, `failing` or `open` change is the answer (its feedback
  is the next round's work), then the one `change_id` still names
  unless it merged, then a `stuck` one (ticket a60aed8e). And a branch that already carries an open, submitted,
  failing or stuck change is that change's: no second row is minted
  under it.

  → [change nil], or [nil sentence] when there is no repository to
  mint against and when the mint itself refuses."
  [eng seat walk]
  (let [row (get-in walk ["rows" 0])
        row-id (some-> (get row "id") str not-empty)
        change-id (str (get walk "kind") ":" row-id)]
    (if-some [found (or (change-in-state eng {:born_from change-id}
                                         [:submitted :failing :open])
                        (unspent (change-by-id eng change-id))
                        (change-in-state eng {:born_from change-id}
                                         [:stuck]))]
      [found nil]
      (let [[repo note] (walk-home seat row)]
       (if-not repo
        [nil note]
        (let [policy (repo-policy-of eng repo)
              branch (unspent-branch eng repo (wanted-branch policy row row-id))]
          (if-some [held (change-in-state
                          eng {:repository repo
                               :head_branch branch}
                          [:submitted :failing :open :stuck])]
            [held nil]
            (try
              [(:row (inv/create!
                      eng :change
                      {:change_id change-id
                       ;; …and the same words again, in a field the
                       ;; adoption does not touch (waymark-fp62.6.3.14).
                       ;; `change_id` becomes GitHub's at the adoption,
                       ;; so the row would forget which walk row it was
                       ;; built for — and the merge must know, because
                       ;; it completes that row.
                       :born_from change-id
                       :repository repo
                       :title (walk-row-title row)
                       :head_branch branch
                       :base_branch (or (some-> (get-in policy [:data :base])
                                                str not-empty)
                                        default-base)
                       :author (str (get-in seat [:data :name]))}
                      {:principal seat-change-principal}))
               nil]
              (catch Exception e
                (binding [*out* *err*]
                  (println "waymark10 seat change mint failed -" (ex-message e)))
                ;; a peer sitting that minted the same id one moment ago
                ;; is the ordinary cause, and its row is the answer
                (if-some [raced (unspent (change-by-id eng change-id))]
                  [raced nil]
                  [nil no-change-note]))))))))))

(def ^:private forge-change-prefixes
  "What a `change_id` the FORGE owns starts with, one per forge an app
  mirrors: factory10's GitHub source writes `github:<repo>#<n>`, and an
  app over Bitbucket Cloud writes `bitbucket:<repo>#<n>`. A change this
  house minted for a walk row starts with the walk kind's own name and
  a colon instead (R-12.32), so these prefixes are what tell the two
  apart. A forge missing here reads as a walk kind: its `repo#n` would
  be taken for a walk row's id."
  #{"github:" "bitbucket:"})

(defn- forge-owned?
  "Whether this `change_id` or `born_from` is the forge's own id."
  [said]
  (let [s (str said)]
    (boolean (some #(str/starts-with? s %) forge-change-prefixes))))

(defn- born-row-id
  "The walk row this change was minted for, or nil. `born_from` says
  `<kind>:<id>`, and `change_id` says the same until an adoption
  writes the forge's own id over it (waymark-fp62.6.3.14)."
  [change]
  (let [said (or (some-> (get-in change [:data :born_from]) str not-empty)
                 (some-> (get-in change [:data :change_id]) str not-empty))]
    (when (and said
               (not (forge-owned? said))
               (str/includes? said ":"))
      (not-empty (subs said (inc (str/index-of said ":")))))))

(defn- unpushed-change?
  "True when the change was born here and never pushed: open or stuck,
  no pull request number, no round, and a `change_id` that is still the
  walk row's. Only such a change is minted again — its branch or its
  repository — because the forge holds nothing of it."
  [change]
  (boolean
   (and change
        (contains? #{:open :stuck} (some-> (:state change) name keyword))
        (nil? (get-in change [:data :number]))
        (zero? (long (or (get-in change [:data :rounds]) 0)))
        (not (forge-owned? (get-in change [:data :change_id]))))))

(defn- rebranched-change
  "The change this firing works, with its branch minted again from the
  policy's pattern when the pattern moved under it (bead
  waymark-fp62.6.3.11) — else the row as it stands.

  A SEAT-BORN CHANGE WRITES ITS BRANCH AT BIRTH, so a person who
  restates the pattern does not reach a change that is already here,
  and the next sitting on the same walk row opens the same bad branch
  again. A change that has never been pushed has no branch on the
  forge, so the house mints it again: no `number`, no round, and a
  `change_id` that is still the walk row's. A change that was
  submitted once KEEPS its branch, because the forge holds it.

  The write goes through `rebranch`, the mirror's own hidden door,
  with the same system hand the mint uses. A refusal costs the new
  branch and never the sit: the row stands as it was."
  ([eng change] (rebranched-change eng nil change))
  ([eng row change]
  (or (when (unpushed-change? change)
        (when-some [row-id (born-row-id change)]
          (let [policy (repo-policy-of
                        eng (str (get-in change [:data :repository])))
                ;; the row's own branch wins over the pattern (R-12.32a)
                wanted (unspent-branch
                        eng (str (get-in change [:data :repository]))
                        (wanted-branch policy row row-id))]
            (when-not (= wanted (str (get-in change [:data :head_branch])))
              (try
                (:row (inv/invoke! eng :change (str (:id change)) :rebranch
                                   {:head_branch wanted}
                                   {:principal seat-change-principal}))
                (catch Exception e
                  (binding [*out* *err*]
                    (println "waymark10 seat change rebranch failed -"
                             (ex-message e)))
                  nil))))))
      change)))

(defn- rehomed-change
  "The change this firing works, moved to the seat's repository when its
  walk row moved there after the change was born (ticket 1ebcd19f) —
  else the row as it stands.

  A TICKET RESTATED TO ANOTHER REPOSITORY KEEPS ITS `change_id`, so the
  lookup finds the change born on the old one, and every bench call of
  the seat that walks it now refuses: its grant admits its own
  repository only. A change that was never pushed has nothing on the
  old repository to keep, so the house writes the seat's repository and
  that policy's branch and base over it through `rebranch`, on the row
  that is here. A change with a pull request keeps its repository,
  because the forge holds it, and the sit says so instead.

  A refusal costs the move and never the sit: the row stands as it was."
  ([eng seat change] (rehomed-change eng seat nil change))
  ([eng seat row change]
  (let [repo (first (walk-home seat row))]
    (or (when (and repo
                   (unpushed-change? change)
                   (not= repo (str (get-in change [:data :repository]))))
          (when-some [row-id (born-row-id change)]
            (let [policy (repo-policy-of eng repo)]
              (try
                (:row (inv/invoke! eng :change (str (:id change)) :rebranch
                                   {:repository repo
                                    :head_branch (unspent-branch
                                                  eng repo (wanted-branch policy row row-id))
                                    :base_branch (or (some-> (get-in policy [:data :base])
                                                             str not-empty)
                                                     default-base)}
                                   {:principal seat-change-principal}))
                (catch Exception e
                  (binding [*out* *err*]
                    (println "waymark10 seat change rehome failed -"
                             (ex-message e)))
                  nil)))))
        change))))

(def ^:private elsewhere-change-note
  "What the sit says when the change this firing works lives in another
  repository than the seat's and could not be moved: it has a pull
  request there, which the house does not move (ticket 1ebcd19f)."
  (str "The change for this row lives in another repository than this "
       "seat's, and it already has a pull request there, so it was not "
       "moved and this seat's bench cannot reach it. Stall it with one "
       "sentence that says so, so a person closes that pull request or "
       "moves the row back."))

(def ^:private stuck-change-note
  "What the sit says when the change this firing works is still
  `stuck`: no door on it submits, so the seat can only say so. It names
  the way back for each road to `stuck` (ticket 6bdaf6fe): the round
  ceiling leaves the ticket in review, where `groom` does not serve,
  and a seat's stall shelves the ticket to draft."
  (str "The change for this row is stuck, and a stuck change offers no "
       "submit, stall or discard. A person, or a delegate acting for one, "
       "puts it back to work. A change stuck at the round ceiling leaves "
       "its ticket in review, and grooming does not serve it: unstick puts "
       "back a change with no pull request, and unstick_submitted one that "
       "has a pull request. A seat's stall sent the ticket to draft, and "
       "grooming it again puts the change back itself, at the groom: to "
       "work when it has no pull request, and back under review when it "
       "has one. Say that it is stuck, and stop."))

(def ^:private spent-change-note
  "What the sit says when the only change it found for this row is one
  no door of the seat's moves: merged, or closed and not reopened
  (ticket a60aed8e). The row is not handed out as the seat's change."
  (str "The change for this row is merged or closed, so this firing has "
       "no change to submit or stall and the bench did not open. Say so "
       "in one sentence, and stop."))

(def ^:private stallable-fallback
  "The states factory10's change kind affords `stall` from, read when
  the kind declares no `:from` on its `stall` door."
  #{:open :submitted :failing})

(defn- stallable-states
  "The states the engine's change kind affords `stall` from, as
  keywords, read off the kind (ticket a60aed8e)."
  [eng]
  (or (some->> (get-in (inv/resources eng) [:change :actions :stall :from])
               seq (map #(keyword (name %))) set)
      stallable-fallback))

(defn- handed-change
  "What the sit hands the seat for `change` → [change note]. A change
  in a state `stallable` names is the seat's; a `stuck` one is handed
  with `stuck-change-note` as context, so the seat says it is stuck;
  any other — merged, closed — is not handed at all (ticket a60aed8e)."
  [stallable change]
  (let [state (some-> (:state change) name keyword)]
    (cond
      (nil? change) [nil nil]
      (contains? stallable state) [change nil]
      (= :stuck state) [change stuck-change-note]
      :else [nil spent-change-note])))

(def ^:private groomed-walk-prefix
  "What `born_from` starts with for a change built for a ticket. A
  ticket's `groom` is the person's reading that the ask is ready."
  "ticket:")

(defn- reopened-change
  "The change this firing works, opened again when it was closed and
  GitHub never saw it (ticket ab6af67f) — else the row as it stands.

  A CLOSED CHANGE HOLDS ITS TICKET'S `change_id`. The id is
  `ticket:<id>` and unique, so the sit that finds a closed change can
  mint no second one, and a closed change offers no submit: the ticket
  could not be built again. The sit reads a row that is in the queue,
  so the ask stands, and a change with no pull request number and a
  `change_id` that is still the walk row's has nothing on the forge to
  disagree.

  ONLY AN ASK AFTER THE CLOSE REOPENS IT (ticket 7e12f1e8). A person's
  `close_without_pr` leaves the ticket where it stands, so a ticket in
  the queue is not by itself the ask: the person may have meant the
  house to stop. The change reopens only when the ticket's newest
  groom, unblock, resume, return or reopen is newer than the change's
  newest close, close_without_pr or supersede
  (`seats/ask-after-close`), as `regroomed-change` asks a groom after
  a stall. A close with no such move after it stays closed.

  It goes back to `open` through `reopen` with the same
  system hand the mint uses, its rounds at zero, and `rebranched-change`
  mints its branch from the policy's pattern after this.

  A CLOSED CHANGE WITH A PULL REQUEST IS LEFT ALONE: GitHub owns it,
  and the mirror follows GitHub's own reopen. A refusal costs the
  reopen and never the sit: the row stands as it was."
  [eng change]
  (or (when (and change
                 (= :closed (some-> (:state change) name keyword))
                 (nil? (get-in change [:data :number]))
                 (not (forge-owned? (get-in change [:data :change_id])))
                 (some? (get (inv/resources eng) :ticket))
                 (some->> (born-row-id change)
                          (seats/ask-after-close eng change)))
        (try
          (let [moved (:row (inv/invoke! eng :change (str (:id change))
                                         :reopen {}
                                         {:principal seat-change-principal}))]
            (println "waymark10 seat change reopened -" (str (:id change)))
            moved)
          (catch Exception e
            (binding [*out* *err*]
              (println "waymark10 seat change reopen failed -"
                       (ex-message e)))
            nil)))
      change))

(defn- regroomed-change
  "The change this firing works, put back to work when a person groomed
  its ticket again after the change was stalled — else the row as it
  stands.

  A STALL SENDS THE TICKET BACK TO DRAFT, AND A GROOM ANSWERS IT. The
  seat that cannot build a ticket stalls its change and ungrooms the
  ticket. Grooming it again is a person's (or a delegate's) reading
  that the ask is ready, so the sit unsticks the change that stall left
  behind: the rounds start from zero and the seat is offered submit.
  The change's `change_id` is `ticket:<id>` and unique, so without this
  the sit would hand the seat the same stuck change forever.

  THE BACKSTOP, SINCE TICKETS 9ace68fb AND 4363c63b. A groom, unblock or
  resume now walks the change's `rework` door in the same transaction,
  or `rework_submitted` for a change WITH a pull request, so a stuck
  change is already `open` or `submitted` when the sit reads it. This
  path is kept only as the fallback for rows stalled and groomed before
  those doors existed; it unsticks a change with a pull request with
  `unstick_submitted`.

  ONLY A GROOM AFTER THE STALL ANSWERS IT. When the ticket's newest
  `groom` is not newer than the change's newest `stall` — or the change
  was never stalled — nobody has read the stall yet, and the change
  stays stuck. The write goes through `unstick` with the same system
  hand the mint uses. A refusal costs the unstick and never the sit:
  the row stands as it was."
  [eng change]
  (or (when (and change
                 (= :stuck (some-> (:state change) name keyword))
                 (str/starts-with? (str (get-in change [:data :born_from]))
                                   groomed-walk-prefix)
                 (some? (get (inv/resources eng) :ticket)))
        (when-some [ticket-id (born-row-id change)]
          (when-some [groom (seats/groom-after-stall eng change ticket-id)]
            (try
              ;; a change with a pull request goes back where the pull
              ;; request is, under review (ticket 6bdaf6fe)
              (let [door (if (some? (get-in change [:data :number]))
                           :unstick_submitted
                           :unstick)
                    moved (:row (inv/invoke! eng :change (str (:id change))
                                             door {}
                                             {:principal seat-change-principal}))]
                (println "waymark10 seat change unstuck -" (str (:id change))
                         "- ticket" ticket-id "groomed by"
                         (str (get-in groom [:actor :id])) "at" (str (:at groom)))
                moved)
              (catch Exception e
                (binding [*out* *err*]
                  (println "waymark10 seat change unstick failed -"
                           (ex-message e)))
                nil)))))
      change))

(defn change-of-sitting
  "The change this firing submits → [change sentence]. The walk's own
  first row when the seat walks the code (R-12.29), and the row the
  engine finds or mints for the first walk row when it walks anything
  else (R-12.32). A seat that walks nothing, and a walk with no rows,
  answer neither."
  [eng seat walk]
  (cond
    (nil? walk) [nil nil]
    ;; A SEAT THAT ONLY READS THE RUN OPENS NO WORKTREE (bead
    ;; waymark-fp62.6.9's companion, waymark-fp62.6.7). The classifier
    ;; walks `ci_run` and answers from the log tail the row already
    ;; carries: it holds no bench power, so a worktree for it would be
    ;; a checkout nobody asked for and one rig call each firing. A
    ;; seat that walks `change` is told about its change as it always
    ;; was — that walk IS the code.
    (and (= "ci_run" (str (get walk "kind"))) (not (bench-seat? seat)))
    [nil nil]
    (contains? bench-walks (str (get walk "kind")))
    [(some->> (change-of-walk eng walk) (rebranched-change eng)) nil]
    ;; a seat with no bench powers is not a code seat, and an engine
    ;; that declares no `change` kind serves no bench at all: neither
    ;; is told anything about one
    (not (bench-seat? seat)) [nil nil]
    ;; A SEAT THAT ONLY READS THE BENCH IS NOT A CODE SEAT. Triage and
    ;; prep read code across every repository with their own bench
    ;; calls and change none; a seat whose scope names no `change` door
    ;; and holds no writing bench power is minted nothing and told nothing,
    ;; so no note tells it the bench "did not open" while its reads work
    (not (code-seat? seat)) [nil nil]
    (nil? (get (inv/resources eng) :change)) [nil nil]
    (str/blank? (str (get-in walk ["rows" 0 "id"]))) [nil nil]
    ;; a change stalled before its ticket was groomed again goes back
    ;; to work first, and the branch is minted again after that
    :else (let [row (get-in walk ["rows" 0])
                [change note] (minted-change eng seat walk)
                change (some->> change
                                (reopened-change eng)
                                (regroomed-change eng)
                                (rehomed-change eng seat row)
                                (rebranched-change eng row)
                                (linked-change! eng walk))
                repo (first (walk-home seat row))
                [change handed-note] (handed-change (stallable-states eng)
                                                    change)]
            [change
             (or note
                 (when (and change repo
                            (not= repo (str (get-in change [:data :repository]))))
                   elsewhere-change-note)
                 handed-note)])))

(defn bench-branch
  "The branch this change is worked on: the one the row already names,
  else the policy's pattern with the change's own id in place of the
  `*`. One branch per change, so a second sitting finds the first
  sitting's worktree."
  [change policy]
  (or (some-> (get-in change [:data :branch]) str not-empty)
      (some-> (get-in change [:data :head_branch]) str not-empty)
      (str/replace (or (some-> (get-in policy [:data :branch_pattern])
                               str not-empty)
                       default-branch-pattern)
                   "*" (str (:id change)))))
