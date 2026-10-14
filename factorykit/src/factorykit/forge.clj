(ns factorykit.forge
  "The seam between a forge and a factory, and ONE reading of a build
  state (ticket 6b716e98).

  THE PROTOCOL is `ForgeSource`, lifted from factory10's
  `sources/forge.clj`. A forge adapter (GitHub today, Bitbucket next)
  implements it and speaks the factory's own documents.

  THE TABLE is data: `resources/build-states.edn`, and the same content
  as `build-states.json` for a reader that is not Clojure. Each forge's
  raw state maps to green, red, pending or interrupted, and no adapter
  judges a raw state on its own. A state the table does not name has no
  verdict: it is never green.

  THE CONFORMANCE SUITE is data too: `resources/forge-conformance.edn`
  and its JSON copy. Each case gives raw statuses and required checks
  and the answer every adapter must give. `conformance-failures` runs
  them against one judge.

  THE CHANGE PASS is `change-pass!` (ticket 97bd7937): every pull
  request a poll answered becomes a row minted, adopted, folded or
  moved. It requires no engine. The factory hands it the `change`
  kind's doors as functions, with its own principal closed into them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── the seam ────────────────────────────────────────────────────────

(defprotocol ForgeSource
  "One forge, already speaking the factory's own documents. Six
  verbs: what moved, one log tail, one label, the call count the
  census prints, the checks on one head, and the head of a base
  branch with its checks."
  (forge-poll [s]
    "→ {:changes [change-doc …] :checks [check-doc …]
        :repositories [name …] :complete? bool}.

    A change document carries the `change` kind's own fields plus
    `:forge_state` (\"open\", \"merged\" or \"closed\"). A check
    document carries the `ci_run` kind's own fields plus
    `:change_id` (the change it ran on) and whatever the forge needs
    to find the log later. `:complete?` is false when a repository
    did not answer; the source holds that repository's cursor where it
    was, and moves the cursor of each one that answered. A source
    may also answer `:answered` (the repositories that did answer) and
    `:refusals` ({repository {:status :route}} for each whose pulls
    listing refused the token), which the pass writes on the policy
    rows (ticket 116dfb0d). A pull request whose head branch a row of
    the house already names is adopted by that branch, not minted.")
  (forge-log-tail [s check]
    "→ {:excerpt \"…\" :note \"…\" } for one check document: the tail
    of the failed job's log. A log the forge will not hand over in
    plain text answers an empty excerpt and a note that says why.
    Never throws — a missing log costs the excerpt, never the row.")
  (forge-label! [s change label]
    "Push ONE label to the pull request the change document names.
    The only write this source makes. Throws when the forge refuses.")
  (forge-calls [s]
    "→ how many calls this source made since the last `forge-poll`
    started. The census line's first number.")
  (forge-checks [s repository head-sha]
    "→ [{:check_name :status :conclusion} …] for every check run on
    one head, the latest of each (ticket d1742908). The poll reads
    checks only on pull requests that moved, and a check that finishes
    moves no pull request, so the failing pass asks head by head.
    Commit statuses count as checks. A forge with one raw state for a
    build (Bitbucket) answers it as `:state` in place of `:status` and
    `:conclusion`. A check may carry its `ci_run` document on its
    metadata (`:doc`), from which the run pass mints a red that
    finished after the push (ticket 3aca3ae8).
    Throws when the forge does not answer.")
  (forge-base [s repository branch]
    "→ {:head_sha sha :checks [check-doc …]} for the head of one branch,
    or nil when the branch has none (ticket ade81ae9). Each check
    carries `:check_name`, `:status` and `:conclusion`, which
    `verdict` reads, and whatever `forge-log-tail` needs to find
    its log. Throws when the forge does not answer."))

;; ── the build-state table ───────────────────────────────────────────

(defn- read-resource [n]
  (edn/read-string (slurp (io/resource n))))

(def build-states
  "The table as it is written: {:verdicts [...] :forges {forge {verdict
  [raw-state …]}}}."
  (read-resource "build-states.edn"))

(def ^:private state-index
  (into {}
        (for [[forge by-verdict] (:forges build-states)]
          [forge (into {} (for [[v raws] by-verdict, raw raws] [raw v]))])))

(defn raw-states
  "The raw states of one forge that read as one verdict, as a set."
  [forge verdict]
  (set (get-in build-states [:forges forge verdict])))

(defn build-state
  "What one check document says: :green, :red, :pending or :interrupted,
  or nil for a finished state the table does not name. A document with
  `:state` is read by it. One with `:status` is pending until the
  status is `completed`, and is read by its `:conclusion` after."
  [forge check]
  (let [table (get state-index forge)]
    (cond
      (contains? check :state) (get table (str (:state check)))
      (= "completed" (str (:status check))) (get table (str (:conclusion check)))
      :else :pending)))

;; ── the verdict on one head ─────────────────────────────────────────

(defn- run-order
  "Where one run of a check stands among the runs of the same name:
  its forge id first, which grows with every run the forge starts, and
  its start time beside it. A run that carries neither sorts first."
  [check]
  (let [id (str (:id check))]
    [(if (re-matches #"\d{1,18}" id) (Long/parseLong id) -1)
     (str (:started_at check))]))

(defn newest-runs
  "The runs of one check name that speak for it: the newest alone when
  every run says where it stands (ticket 6bdaf6fe) — a re-run that went
  green on the same head answers for the red run before it — and every
  run as it came when one of them does not, because then there is no
  telling which is newer."
  [runs]
  (if (and (next runs) (every? #(or (some? (:id %)) (some? (:started_at %))) runs))
    [(last (sort-by run-order runs))]
    runs))

(defn verdict
  "What the checks on one head say, read against a policy:
  {:verdict :red :names […]} when every required check finished and at
  least one went red, {:verdict :green} when every one finished green,
  {:verdict :interrupted :names […]} when none went red and at least
  one was interrupted — it died without a verdict, it is never green
  and never a recovery — and nil while one is pending, has not started,
  or ended in a state the table does not name. A policy that names no
  required check requires every check on the head. Of several runs of
  one name, the newest speaks for it (`newest-runs`).

  `:advisory` names the checks that count only when red: a red one is
  named beside the required reds, and one that is pending, interrupted
  or absent holds nothing. The policy declares them; none is built in."
  [{:keys [forge required advisory] :or {forge :github}} checks]
  (let [by-name (update-vals (group-by #(str (:check_name %)) checks)
                             newest-runs)
        advisory (set advisory)
        names (if (seq required)
                (vec (distinct required))
                (vec (sort (remove #(or (str/blank? %) (contains? advisory %))
                                   (keys by-name)))))
        states (fn [n] (map #(build-state forge %) (get by-name n)))
        named (fn [v of] (filterv (fn [n] (some #(= v %) (states n))) of))
        runs (mapv states names)
        all? (fn [pred] (every? (fn [ss] (and (seq ss) (every? pred ss))) runs))
        advised (named :red (sort (remove (set names) advisory)))]
    (cond
      (empty? names) (when (seq advised) {:verdict :red :names advised})
      (not (all? #(not= :pending %))) nil
      :else
      (let [red (into (named :red names) advised)]
        (cond
          (seq red) {:verdict :red :names red}
          (all? #(= :green %)) {:verdict :green}
          :else
          (let [cut (named :interrupted names)]
            (when (seq cut) {:verdict :interrupted :names cut})))))))

(defn behind?
  "Is a head behind its base, given how many of the base's commits it
  lacks? None, or a count the forge did not give, is not behind."
  [behind-by]
  (pos? (long (or behind-by 0))))

;; ── the change pass ─────────────────────────────────────────────────
;;
;; Lifted from factory10's `sources/forge.clj` (ticket 97bd7937). The
;; pass names no engine and no principal. It takes the factory's HAND,
;; a map of the `change` kind's doors, and the factory closes its own
;; engine and its own system principal into them:
;;
;;   :find-change     (fn [change-id]) → the row with that id, or nil
;;   :branch-changes  (fn [repository head-branch limit]) → the rows of
;;                    that repository on that head branch
;;   :create!         (fn [fields]) → the row the create door minted
;;   :invoke!         (fn [row-id door input]) → the row after the door
;;   :forge-id-prefix what a `change_id` this forge owns starts with;
;;                    `forge-id-prefix` when the hand names none
;;   :move!           (fn [row doc]) → [row moved?], for a factory that
;;                    wraps `move-change!`; the pass moves through it
;;
;; A row is {:id :state :data}. A door that refuses throws.

(def change-create-fields
  "The `change` create door's whole vocabulary. The door is a closed
  map, so a field it does not name refuses the mint."
  [:change_id :repository :number :title :author :base_branch
   :head_branch :head_sha :draft :url])

(def change-observe-fields
  "What `observe` writes. The state is NOT here: the machine advances
  the state and a handler never does."
  [:title :head_sha :draft :mergeable :files_changed :lines_added
   :lines_removed :touched_paths :labels :review_state])

(def observe-doors
  "Which door writes the facts, read from the row's state. `observe` is
  a self-loop on `open`. The bench's `submitted` state carries a door
  of its own, because a v10 action declares one `:to` and a self-loop
  is spelled once for each state (bead waymark-fp62.6.3.2). A state
  that is in neither column keeps its stored facts: a closed row has
  no observe door at all, and the pass does not argue with the
  machine."
  {:open :observe
   :submitted :observe_submitted
   :failing :observe_failing})

(def forge-id-prefix
  "What a `change_id` GitHub owns starts with, and what the pass reads
  when the hand names no `:forge-id-prefix`. A row the engine minted
  for a seat's ask starts with the walk kind's own name and a colon
  instead (spec-seat.md R-12.32), so the prefix is what tells a pull
  request's row from an ask's row."
  "github:")

(def adopt-doors
  "Which door writes the forge's identity onto a row this house
  already holds, read from the row's state. The same two states
  `observe` serves, and for the same reason: a v10 action declares
  one `:to`, so a self-loop is spelled once for each state. A
  `failing` or `stuck` row is adopted too (ticket 3c59c688): a pull
  request the house itself opened is that row's whatever its state,
  and the adoption leaves the state where it is. A merged or closed
  row is adopted by nobody — its story is over."
  {:open :adopt
   :submitted :adopt_submitted
   :failing :adopt_failing
   :stuck :adopt_stuck})

(def fold-states
  "The states a forge-minted duplicate can be folded from (ticket
  3c59c688). A merged or closed row is left as it is."
  #{:open :submitted :failing :stuck})

(def adoption-scan-limit
  "How many rows of one branch the adoption reads. A head branch
  holds one change in every house that works, and the ceiling is
  there so a repository with a strange branch cannot make the pass
  read a table."
  10)

(def adopt-fields
  "What the adoption writes: the pull request's own identity, and
  nothing else. The facts follow through `observe`, as they do for
  every other row."
  [:change_id :number :url])

(defn present
  "The document, narrowed to the fields named and to what the forge
  actually read. A field the forge did not read is absent, and absent
  means silent: the stored value stands."
  [doc fields]
  (into {} (keep (fn [k] (when-some [v (get doc k)] [k v]))) fields))

(defn state-of
  "The row's state as a keyword, whichever way the store spells it."
  [row]
  (some-> (:state row) name keyword))

(defn holds-conflict?
  "Does the row's `conflicted` stand against what the forge reads now
  (ticket bf8ba0a6)? The merge pass writes `conflicted` when the rig's
  merge is refused as not mergeable, and parks that head: nothing writes
  it a second time. The forge's own read of the same head may still say
  `unknown` or `blocked` — it has not computed the merge, or the policy
  word outranks it — and that word used to replace the conflict before
  the failing pass read it, so #706 sat submitted and parked. Neither
  word says the conflict is gone: only `clean`, or a new head, does."
  [row doc]
  (and (= "conflicted" (str (get-in row [:data :mergeable])))
       (contains? #{"unknown" "blocked"} (str (:mergeable doc)))
       (or (nil? (:head_sha doc))
           (= (str (:head_sha doc)) (str (get-in row [:data :head_sha]))))))

(defn changed-facts
  "The facts that moved under the row. A value equal to the stored one
  is not written again, so a pass that saw nothing new logs nothing. A
  conflict the row holds (`holds-conflict?`) is not written over."
  [row doc]
  (into {}
        (keep (fn [[k v]] (when (not= v (get-in row [:data k])) [k v])))
        (present (cond-> doc (holds-conflict? row doc) (dissoc :mergeable))
                 change-observe-fields)))

(defn state-door
  "The one door from the row's state to the forge's. nil when the row
  already says what the forge says, and nil for a merged row: that
  state is the kind's only tomb.

  A `submitted` or `stuck` row follows the forge too (bead
  waymark-fp62.6.3.14). A seat-born change stands at `submitted` once
  the seat has pushed, and that is the state the adoption keeps — so
  the pull request the forge then merges must move THAT row, or the row
  never reaches `merged` and the task it was born from is never
  completed. The kind's own `merge` and `close` doors admit all three
  states; this table only says the same."
  [row-state forge-state]
  (case [row-state (str forge-state)]
    [:open "merged"] :merge
    [:submitted "merged"] :merge
    [:failing "merged"] :merge
    [:stuck "merged"] :merge
    [:open "closed"] :close
    [:submitted "closed"] :close
    [:failing "closed"] :close
    [:stuck "closed"] :close
    [:closed "open"] :reopen
    nil))

(defn mint-change!
  "One pull request the house has never seen → one `change` row. The
  create door takes the identity and the words; `observe` takes the
  counts, because the create door does not name them."
  [{:keys [create! invoke!]} doc]
  (let [row (create! (present doc change-create-fields))
        facts (present doc change-observe-fields)]
    (if (seq facts)
      (invoke! (str (:id row)) :observe facts)
      row)))

(defn move-change!
  "The row that is already here, moved to what the forge says now. The
  facts are written while the row can still take them — `observe` is a
  self-loop on `open` — and the state moves after. A row that comes
  back from `closed` reopens first, because a closed row has no
  `observe` door at all.

  → [row moved?]"
  [{:keys [invoke!]} row doc]
  (let [id (str (:id row))
        from (state-of row)
        door (state-door from (:forge_state doc))
        reopening? (= :reopen door)
        row (if reopening? (invoke! id :reopen {}) row)
        facts (changed-facts row doc)
        observe-door (get observe-doors (state-of row))
        observing? (boolean (and (seq facts) observe-door))
        row (if observing? (invoke! id observe-door facts) row)
        row (if (and door (not reopening?)) (invoke! id door {}) row)]
    [row (boolean (or door observing?))]))

(defn- move!
  "The move the pass makes: the factory's own when the hand names one,
  and `move-change!` when it does not."
  [hand row doc]
  (if-some [move (:move! hand)]
    (move row doc)
    (move-change! hand row doc)))

(defn adoptable-row
  "The row this house minted for an ask, waiting for the pull request
  its own submit opened (bead waymark-fp62.6.3.10), or nil.

  Three things make one: the same repository, the same head branch,
  and a `change_id` that is not the forge's. A row at a state with no
  adopt door is not one — a merged or closed row on that branch is a
  row whose story is elsewhere. nil is the ordinary
  answer: almost every pull request the forge reads was opened by a
  person, and that is a mint and not an adoption."
  [{:keys [branch-changes] :as hand} doc]
  (when-some [head (some-> (:head_branch doc) str not-empty)]
    (when-not (str/blank? (str (:repository doc)))
      (->> (branch-changes (str (:repository doc)) head adoption-scan-limit)
           (filter (fn [row]
                     (and (not (str/starts-with?
                                (str (get-in row [:data :change_id]))
                                (or (:forge-id-prefix hand) forge-id-prefix)))
                          (contains? adopt-doors (state-of row)))))
           first))))

(defn adopt-change!
  "The row the house minted, given the pull request's own identity:
  the id, the number and the url, through the mirror's own `adopt`
  door. `change_id` is `:unique`, so the write lands whole or the
  transaction does not land at all — a second pull request can never
  take a row that is already spoken for.

  → the row, with the forge's identity on it."
  [{:keys [invoke!]} row doc]
  (invoke! (str (:id row))
           (get adopt-doors (state-of row))
           (present doc adopt-fields)))

(defn fold-duplicate!
  "A row the forge minted beside the house's own row for the same pull
  request (ticket 3c59c688: 0243f0a6 minted for #268 while 71cbbef9
  stood stuck). The minted row is closed, gives up the id and names
  the house row; the house row adopts the id and moves with the forge."
  [{:keys [invoke!] :as hand} minted ours doc]
  (invoke! (str (:id minted)) :fold {:folded_into (str (:id ours))})
  (move! hand (adopt-change! hand ours doc) doc))

(defn forge-minted?
  "A row the forge minted, not one the house built for an ask: it was
  born of no ask, and it stands where a fold can take it."
  [row]
  (and (str/blank? (str (get-in row [:data :born_from])))
       (contains? fold-states (state-of row))))

(defn- counted [census k]
  (update census k (fnil inc 0)))

(defn change-pass!
  "Every pull request the forge answered → a row minted, a row
  adopted, a duplicate folded, or a row moved, through the factory's
  hand. → the census, with `:minted`, `:adopted`, `:folded`, `:moved`
  and `:refused` counted. A row the engine refuses is counted and
  skipped, and its document is kept in `refused`, a volatile the
  caller hands back to the source: the cursor has already moved past
  it, so only a retry offers it again (ticket 365043a6). `log-fn` takes
  the parts of one line."
  ([hand changes census log-fn]
   (change-pass! hand changes census log-fn (volatile! [])))
  ([hand changes census log-fn refused]
   (reduce
    (fn [census doc]
      (try
        (if-some [existing ((:find-change hand) (:change_id doc))]
          (if-some [ours (when (forge-minted? existing)
                           (adoptable-row hand doc))]
            ;; a duplicate minted before the house row could adopt:
            ;; fold it, so one row holds the pull request
            (do (fold-duplicate! hand existing ours doc)
                (counted census :folded))
            (let [[_ moved?] (move! hand existing doc)]
              (cond-> census moved? (counted :moved))))
          (if-some [ours (adoptable-row hand doc)]
            ;; the house asked for this pull request: the seat built
            ;; the branch and its submit opened it. One row, adopted
            ;; where it stands, rather than a second row beside it
            (do (move! hand (adopt-change! hand ours doc) doc)
                (counted census :adopted))
            ;; a birth lands at `open`, because that is the kind's
            ;; initial state; a pull request first seen after it merged
            ;; walks its door in the same pass rather than waiting for
            ;; a move that will never come again
            (do (move! hand (mint-change! hand doc) doc)
                (counted census :minted))))
        (catch Exception e
          (log-fn "the pull request " (:change_id doc)
                  " was refused a row (" (ex-message e) ")")
          (vswap! refused conj doc)
          (counted census :refused))))
    census
    changes)))

;; ── the conformance suite ───────────────────────────────────────────

(def conformance
  "The cases as they are written: {:cases [{:name :asks :expect …} …]}."
  (read-resource "forge-conformance.edn"))

(defn judge
  "The kit's own answer to one conformance case. `:asks` is \"verdict\"
  (`:forge`, `:required`, `:advisory`, `:checks`), \"state\" (`:forge`,
  `:check`) or \"behind\" (`:behind_by`)."
  [{:keys [asks forge required advisory checks check behind_by]}]
  (case asks
    "verdict" (verdict {:forge (keyword forge) :required required
                        :advisory advisory}
                       checks)
    "state" (build-state (keyword forge) check)
    "behind" (behind? behind_by)))

(defn- spelled
  "An answer as the data files spell it: a verdict is a string."
  [answer]
  (cond
    (keyword? answer) (name answer)
    (map? answer) (update answer :verdict name)
    :else answer))

(defn conformance-failures
  "The cases `judge-fn` answers wrongly, as [{:case :expect :actual} …];
  empty when the adapter conforms. `judge-fn` takes one case and answers
  as `judge` does. An adapter passes its own judge for the cases it
  judges itself and hands the rest to `judge`."
  ([] (conformance-failures judge))
  ([judge-fn]
   (vec (for [c (:cases conformance)
              :let [actual (spelled (judge-fn c))]
              :when (not= (:expect c) actual)]
          {:case (:name c) :expect (:expect c) :actual actual}))))
