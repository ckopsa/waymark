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
  kind's doors as functions, with its own principal closed into them.

  THE RED RUNS are `runs-pass!` (ticket efff5d2e): every failed check
  becomes a `ci_run` row at red, and a red row no seat can answer walks
  `supersede`. The factory hands it the `ci_run` kind's doors.

  THE FAILING PASS is `failing-pass!` (ticket 58c51a5d): every live
  change's head, read by the factory's own hand, walks at most one of
  `fail`, `recover` and `stick`, after the pull request is read again
  by number (`ForgePull`).

  THE PASS is `pass!` (ticket 75db2ef8): the poll, the rate-limit hold,
  the change pass, the source's place, the red runs, the failing pass
  and the census line, with the factory's own steps handed in as
  functions."
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

(defprotocol ForgeRetry
  "The pull requests whose rows the change pass refused, handed back
  to the source (ticket 365043a6). The poll's cursor has already moved
  past their stamps, so a merged pull request that never moves again
  would never be offered again, and its row would keep a head it no
  longer has. A protocol of its own, so a source that does not
  implement it retries nothing and the pass goes on as before."
  (forge-retry! [s docs]
    "Offer these change documents again at the next `forge-poll`,
    read afresh by `:repository` and `:number`."))

(defprotocol ForgeCommit
  "A source that keeps its place across boots, told what became of the
  poll it answered (ticket fc3a5a40). A process that stops between the
  poll and the row writes must not have stored a cursor past pull
  requests whose rows were never written, so the place is stored only
  when the pass says the rows are. A protocol of its own, so a source
  that does not implement it stores nothing and the pass goes on as
  before."
  (forge-commit! [s repositories]
    "The rows of these repositories are written: store each one's
    cursor and the pull requests it still has to read again.")
  (forge-forget! [s]
    "The rows of the last `forge-poll` were not written: go back to
    the place of the last commit, so the next poll answers them again."))

(defprotocol ForgeBudget
  "The forge's rate limit as the source last heard it (ticket
  cdba1f6a). A protocol of its own: a source that does not implement it
  has no budget to print and is never held."
  (forge-budget [s]
    "Answers {:remaining n :limit n :reset instant :unchanged n
    :held-until instant}, each absent when the forge has not said it.
    `:unchanged` counts the answers of this pass the forge did not
    count against the limit. `:held-until` stands while the source
    holds every call for a spent limit, and no read of the forge
    answers before that instant. Never throws."))

(defprotocol ForgePull
  "One pull request read by its number, outside the window (ticket
  949d18c5). A protocol of its own: a source that does not implement
  it adopts only what the window shows, and the pass notes the rest."
  (forge-pull [s repository number]
    "→ the change document of pull request `number`, in the shape
    `forge-poll` answers, or nil. Throws when the forge does not
    answer or has no such pull request."))

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

(def observe-states
  "The states `observe` writes the facts in. It is one `:to :same` door
  over all of them (ticket bc6a8732). A state that is not here keeps
  its stored facts: a closed row has no observe door at all, and the
  pass does not argue with the machine."
  #{:open :submitted :failing})

(def forge-id-prefix
  "What a `change_id` GitHub owns starts with, and what the pass reads
  when the hand names no `:forge-id-prefix`. A row the engine minted
  for a seat's ask starts with the walk kind's own name and a colon
  instead (spec-seat.md R-12.32), so the prefix is what tells a pull
  request's row from an ask's row."
  "github:")

(def adopt-states
  "The states `adopt` writes the forge's identity onto a row this house
  already holds in, one `:to :same` door over all of them (ticket
  bc6a8732). A `failing` or `stuck` row is adopted too (ticket
  3c59c688): a pull request the house itself opened is that row's
  whatever its state, and the adoption leaves the state where it is.
  A merged or closed row is adopted by nobody — its story is over."
  #{:open :submitted :failing :stuck})

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
  `observe` door at all. A door the engine refuses `wrong_state` is
  nothing to do: the pull request's own subscription walked it first
  (docs/spec-change.md R-C.7).

  → [row moved?]"
  [{:keys [invoke!]} row doc]
  (try
    (let [id (str (:id row))
          from (state-of row)
          door (state-door from (:forge_state doc))
          reopening? (= :reopen door)
          row (if reopening? (invoke! id :reopen {}) row)
          facts (changed-facts row doc)
          observing? (boolean (and (seq facts)
                                   (contains? observe-states (state-of row))))
          row (if observing? (invoke! id :observe facts) row)
          row (if (and door (not reopening?)) (invoke! id door {}) row)]
      [row (boolean (or door observing?))])
    (catch clojure.lang.ExceptionInfo e
      (if (= :wrong-state (:waymark10/problem (ex-data e)))
        [row false]
        (throw e)))))

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
                          (contains? adopt-states (state-of row)))))
           first))))

(defn adopt-change!
  "The row the house minted, given the pull request's own identity:
  the id, the number and the url, through the mirror's own `adopt`
  door. `change_id` is `:unique`, so the write lands whole or the
  transaction does not land at all — a second pull request can never
  take a row that is already spoken for.

  → the row, with the forge's identity on it."
  [{:keys [invoke!]} row doc]
  (invoke! (str (:id row)) :adopt (present doc adopt-fields)))

(defn link-change!
  "The row, linked to its `pull_request` row through the mirror's own
  `link` door (spec-change §3, R-C.4; ticket 56c97770), when it has a
  number, stands where `link` opens, holds no link yet, and the hand's
  `:find-pull-request` — `(fn [repository number])` → that row's id, or
  nil — finds one. A hand with no `:find-pull-request` links nothing,
  and a pull request whose row does not exist yet is linked by a later
  pass. A refused link costs the row its link and nothing else: the
  pass moves it all the same, and `log-fn` hears why.

  → the row, linked or as it was."
  ([hand row] (link-change! hand row (fn [& _])))
  ([{:keys [invoke! find-pull-request]} row log-fn]
   (let [{:keys [repository number pull_request]} (:data row)
         repository (some-> repository str not-empty)]
     (if-not (and find-pull-request repository number
                  (str/blank? (str pull_request))
                  (contains? adopt-states (state-of row)))
       row
       (try
         (if-some [pr (some-> (find-pull-request repository number) str not-empty)]
           (invoke! (str (:id row)) :link {:pull_request pr})
           row)
         (catch Exception e
           (log-fn "the change " (get-in row [:data :change_id])
                   " was refused its link (" (ex-message e) ")")
           row))))))

(defn fold-duplicate!
  "A row the forge minted beside the house's own row for the same pull
  request (ticket 3c59c688: 0243f0a6 minted for #268 while 71cbbef9
  stood stuck). The minted row is closed, gives up the id and names
  the house row; the house row adopts the id and moves with the forge."
  [{:keys [invoke!] :as hand} minted ours doc]
  (invoke! (str (:id minted)) :fold {:folded_into (str (:id ours))})
  (move! hand (link-change! hand (adopt-change! hand ours doc)) doc))

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
            (let [[_ moved?] (move! hand (link-change! hand existing log-fn) doc)]
              (cond-> census moved? (counted :moved))))
          (if-some [ours (adoptable-row hand doc)]
            ;; the house asked for this pull request: the seat built
            ;; the branch and its submit opened it. One row, adopted
            ;; where it stands, rather than a second row beside it
            (do (move! hand (link-change! hand (adopt-change! hand ours doc) log-fn) doc)
                (counted census :adopted))
            ;; a birth lands at `open`, because that is the kind's
            ;; initial state; a pull request first seen after it merged
            ;; walks its door in the same pass rather than waiting for
            ;; a move that will never come again
            (do (move! hand (link-change! hand (mint-change! hand doc) log-fn) doc)
                (counted census :minted))))
        (catch Exception e
          (log-fn "the pull request " (:change_id doc)
                  " was refused a row (" (ex-message e) ")")
          (vswap! refused conj doc)
          (counted census :refused))))
    census
    changes)))

;; ── the red runs ────────────────────────────────────────────────────
;;
;; Lifted from factory10's `sources/forge.clj` (ticket efff5d2e). The
;; factory hands the `ci_run` kind's doors as functions, as it hands
;; the `change` kind's to the change pass:
;;
;;   :find-run        (fn [run-id]) → the `ci_run` row with that run
;;                    id, or nil
;;   :find-change     (fn [change-id]) → the `change` row with that
;;                    `change_id`, or nil
;;   :change-by-id    (fn [row-id]) → the `change` row with that id, or nil
;;   :reds            (fn [where limit]) → up to `limit` `ci_run` rows at
;;                    red that match `where`, oldest first
;;   :create!         (fn [fields]) → the row the create door minted
;;   :invoke!         (fn [row-id door input]) → the row after the door
;;   :red-conclusions the conclusions that mint a red run; GitHub's reds
;;                    when the hand names none
;;   :live-reds       optional (fn [checks]) → the red check documents
;;                    the poll did not carry
;;
;; A door that refuses throws.

(def ci-run-create-fields
  "The `ci_run` create door's whole vocabulary. `verdict`, `remedy`,
  `pushed_label` and `labelled_at` are absent on purpose: a row born
  with a verdict is a row that skipped the walk."
  [:run_id :change :head_sha :check_name :conclusion :log_excerpt
   :log_note :started_at :finished_at :url])

(def log-note-chars
  "How long `log_note` may be. One sentence about why there is no
  log, at the same ceiling a remedy sentence has: a plain text field
  wider than that reads as prose and the usability battery says so.
  A forge's error message can run past it, so the note is cut here
  rather than refused a row."
  240)

(def stale-scan-limit
  "How many red runs of one change one pass reads for the runs of a
  head that moved."
  50)

(def red-scan-limit
  "How many red runs one pass reads for the runs whose change ended,
  oldest first. A ceiling above the queue as it stood when the sweep
  was written (135 rows, ticket ebe54a72), so one pass clears it."
  200)

(defn mint-run!
  "One failed check run → one `ci_run` row at red, with the log tail
  on it and a ref to the change it ran on."
  [runs source check change-row census log-fn]
  (let [{:keys [excerpt note]} (try (forge-log-tail source check)
                                    (catch Exception e
                                      {:excerpt nil
                                       :note (str "the log could not be read ("
                                                  (ex-message e) ")")}))
        ;; THE NOTE HAS A FIELD OF ITS OWN (bead waymark-fp62.6.9). A
        ;; sentence written inside `log_excerpt` reads as the end of a
        ;; build log to the next reader, and the classifier reasons
        ;; about it as one. So the excerpt stays empty and `log_note`
        ;; says why it is empty. `present` drops a nil, which is what
        ;; makes each of the two absent when the other answers.
        blank? (str/blank? (str excerpt))
        body (present (assoc check
                             :change (str (:id change-row))
                             :log_excerpt (when-not blank? excerpt)
                             :log_note (when (and blank? note)
                                         (subs (str note) 0
                                               (min (count (str note))
                                                    log-note-chars))))
                      ci-run-create-fields)]
    (try
      ((:create! runs) body)
      (counted census :runs-minted)
      (catch Exception e
        (log-fn "the check run " (:run_id check) " was refused a row ("
                (ex-message e) ")")
        (counted census :refused)))))

(defn run-pass!
  "Every finished failure the forge answered → a red row, once. A run
  on a head the change no longer carries mints NOTHING and is counted
  as skipped: the head moved under it while the pass was reading."
  [runs source checks census log-fn]
  (let [reds (or (:red-conclusions runs) (raw-states :github :red))]
    (reduce
     (fn [census check]
       (cond
         (not (contains? reds (str (:conclusion check))))
         census

         (some? ((:find-run runs) (:run_id check)))
         (counted census :runs-known)

         :else
         (if-some [change-row ((:find-change runs) (:change_id check))]
           (if (= (str (:head_sha check))
                  (str (get-in change-row [:data :head_sha])))
             (mint-run! runs source check change-row census log-fn)
             (counted census :runs-skipped))
           (counted census :runs-orphan))))
     census
     checks)))

(defn- supersede-run!
  "One red row → the `supersede` door. A row the engine refuses is
  counted and skipped: the next pass offers it again."
  [runs census row log-fn]
  (try
    ((:invoke! runs) (str (:id row)) :supersede {})
    (counted census :runs-superseded)
    (catch Exception e
      (log-fn "the red run " (get-in row [:data :run_id])
              " was refused the supersede door (" (ex-message e) ")")
      (counted census :refused))))

(defn stale-reds
  "The red rows of this change that ran on a head it no longer
  carries. A run of a dead commit is a run nobody can answer, so
  these are the rows the `supersede` door is for."
  [runs change-row]
  (let [head (str (get-in change-row [:data :head_sha]))]
    (filterv #(not= head (str (get-in % [:data :head_sha])))
             ((:reds runs) {:change (str (:id change-row))}
                           stale-scan-limit))))

(defn stale-pass!
  "Every red run of a head its change no longer carries → the
  `supersede` door. The row leaves the classifier's queue and the
  transition says it left with no verdict."
  [runs changes census log-fn]
  (reduce
   (fn [census doc]
     (if-some [change-row ((:find-change runs) (:change_id doc))]
       (reduce #(supersede-run! runs %1 %2 log-fn)
               census
               (stale-reds runs change-row))
       census))
   census
   changes))

(defn settled-reds
  "The red rows no seat can answer, whatever the forge listed this
  pass (ticket ebe54a72): a run whose change is merged or closed, and
  a run on a head its change no longer carries. `stale-reds` reads only
  the changes one pass was answered, so a change that ended before its
  runs were revisited kept them red for good. Oldest first, so the
  legacy rows go before the new ones. A run that names no change this
  house holds is left where it is."
  [runs]
  (filterv
   (fn [row]
     (when-some [change-row ((:change-by-id runs)
                             (get-in row [:data :change]))]
       (let [head (str (get-in change-row [:data :head_sha]))]
         (or (contains? #{:merged :closed} (keyword (:state change-row)))
             (and (not (str/blank? head))
                  (not= head (str (get-in row [:data :head_sha]))))))))
   ((:reds runs) {} red-scan-limit)))

(defn settled-pass!
  "Every red run of a change that is merged or closed, or of a head
  its change no longer carries → the `supersede` door, for the rows
  `stale-pass!` never meets."
  [runs census log-fn]
  (reduce #(supersede-run! runs %1 %2 log-fn) census (settled-reds runs)))

(defn runs-pass!
  "The red runs of one pass, through the factory's `ci_run` hand: the
  checks the poll carried and the hand's `:live-reds` minted, the runs
  of a head that moved superseded, then every run no seat can answer.
  → the census, with `:runs-minted`, `:runs-known`, `:runs-skipped`,
  `:runs-orphan`, `:runs-superseded` and `:refused` counted."
  [runs source {:keys [changes checks]} census log-fn]
  (let [checks (into (vec checks)
                     (when-some [live (:live-reds runs)] (live checks)))]
    (as-> census census
      (run-pass! runs source checks census log-fn)
      (stale-pass! runs changes census log-fn)
      (settled-pass! runs census log-fn))))

;; ── the failing pass ────────────────────────────────────────────────

(def ^:private why-chars 480)

(defn failing-move
  "The one door the verdict opens on this row, as [door input], or nil.
  A red head under the round ceiling (`rounds`) goes to `failing`; a
  red head on the last round goes to `stuck` with the names as its why;
  a green head brings a failing change back to `submitted`, unless a
  merge train found that same head red (`train_red_head`, ticket
  6566d32f): its own green does not clear the train's red, and a new
  head does. `conflicts` is the list of conflicting paths, written
  beside the names when there is one; a failed landing's output rides
  as the verdict's `:error`."
  [row verdict rounds conflicts]
  (let [names (:names verdict)
        error (:error verdict)]
    (case [(state-of row) (:verdict verdict)]
      [:submitted :red]
      (if (>= (long (or (get-in row [:data :rounds]) 0)) (long rounds))
        (let [why (str "The checks went red on the last round the policy "
                       "gives: " (str/join ", " names) ".")]
          [:stick (cond-> {:why (subs why 0 (min (count why) why-chars))
                           :failing_checks names}
                    (seq conflicts) (assoc :conflicts conflicts)
                    error (assoc :landing_error error))])
        [:fail (cond-> {:failing_checks names}
                 (seq conflicts) (assoc :conflicts conflicts)
                 error (assoc :landing_error error))])
      [:failing :green]
      (let [train-head (some-> (get-in row [:data :train_red_head]) str not-empty)]
        (when-not (and train-head
                       (= train-head (str (get-in row [:data :head_sha]))))
          [:recover {}]))
      ;; a queued check a runner took at last (ticket bc3ff12c)
      [:failing :started] [:recover {}]
      nil)))

(def moved-counts
  "The census count each failing door adds one to."
  {:fail :failing :recover :recovered :stick :stuck})

(defn forge-moved!
  "The row moved to what its pull request says NOW, read by number just
  before the failing pass would move it (ticket a24a1e01): the poll's
  window can miss a merge or a new round, and a verdict on the stored
  head then judges a head that is no longer the pull request's — 1610db25
  went back to `open` on its first round's red after #562 merged green.
  A merged or closed pull request walks its own door through the hand's
  `:move!`, and a new head is observed and judged on the next pass.
  → true when the row moved; nil when the forge says what the row says,
  or has nothing to say."
  [failing source row log-fn]
  (let [repo (str (get-in row [:data :repository]))
        number (get-in row [:data :number])
        doc (when (and (some? number) (satisfies? ForgePull source))
              (try (forge-pull source repo number)
                   (catch Exception e
                     (log-fn "the pull request #" number " of " repo
                             " could not be read again (" (ex-message e) ")")
                     nil)))
        head (some-> (:head_sha doc) str not-empty)]
    (when (and (map? doc)
               (or (contains? #{"merged" "closed"} (str (:forge_state doc)))
                   (and head
                        (not= head (str (get-in row [:data :head_sha]))))))
      ((:move! failing) row doc))))

(defn failing-pass!
  "Every live change of a repository with an active policy → its head's
  verdict, read by the factory's hand, and at most one door. A change
  with no head, or of a repository with no policy, is left where it is.
  A forge that does not answer, or a door the engine refuses, costs that
  change one pass and nothing else. The failing hand:

  :changes    (fn []) → the live `change` rows
  :policies   (fn []) → the active policy rows, each naming `:repository`
  :rounds-of  (fn [policy]) → the rounds the policy gives a change
  :read       (fn [row policy head census]) → {:verdict verdict
              :census census :conflicts [path …] :re-ran? bool}: the
              factory's own reading of one head (its landing, its held
              log); a re-run is that head's move for this pass
  :move!      (fn [row doc]) → truthy when the row moved to `doc`
  :invoke!    (fn [row-id door input]) → the row after the door

  → the census, with `:failing`, `:recovered`, `:stuck`, `:moved` and
  `:refused` counted."
  [failing source census log-fn]
  (let [by-repo (into {}
                      (keep (fn [p]
                              (when-some [r (some-> (get-in p [:data :repository])
                                                    str not-empty)]
                                [r p])))
                      ((:policies failing)))]
    (reduce
     (fn [census row]
       (let [repo (str (get-in row [:data :repository]))
             head (some-> (get-in row [:data :head_sha]) str not-empty)
             policy (get by-repo repo)]
         (if-not (and head policy)
           census
           (try
             (let [{:keys [verdict conflicts re-ran?] :as read}
                   ((:read failing) row policy head census)
                   census (:census read census)]
               (if-some [[door input] (when (and verdict (not re-ran?))
                                        (failing-move
                                         row verdict
                                         ((:rounds-of failing) policy)
                                         conflicts))]
                 (if (forge-moved! failing source row log-fn)
                   (counted census :moved)
                   (do ((:invoke! failing) (str (:id row)) door input)
                       (counted census (moved-counts door))))
                 census))
             (catch Exception e
               (log-fn "the checks of " (get-in row [:data :change_id])
                       " did not move the change (" (ex-message e) ")")
               (counted census :refused))))))
     census
     ((:changes failing)))))

;; ── the pass ────────────────────────────────────────────────────────

(defn budget-of
  "What the source last heard of the forge's rate limit, or nothing
  from a source that keeps no budget (ticket cdba1f6a)."
  [source]
  (when (satisfies? ForgeBudget source)
    (forge-budget source)))

(defn retry-refused!
  "Hand the refused documents back to a source that retries them."
  [source docs log-fn]
  (when (and (seq docs) (satisfies? ForgeRetry source))
    (try (forge-retry! source docs)
         (catch Exception e
           (log-fn "the refused pull requests could not be offered again ("
                   (ex-message e) ")")))))

(defn commit-poll!
  "Tell a source that keeps its place that the rows of these
  repositories are written (ticket fc3a5a40)."
  [source repositories log-fn]
  (when (satisfies? ForgeCommit source)
    (try (forge-commit! source repositories)
         (catch Exception e
           (log-fn "the source's place could not be stored ("
                   (ex-message e) ")")))))

(defn forget-poll!
  "Tell a source that keeps its place that the poll's rows were not
  written, so it answers them again (ticket fc3a5a40)."
  [source log-fn]
  (when (satisfies? ForgeCommit source)
    (try (forge-forget! source)
         (catch Exception e
           (log-fn "the source could not go back to its stored place ("
                   (ex-message e) ")")))))

(defn- warn-line [& parts]
  (binding [*out* *err*] (println (apply str parts))))

(def fresh-census
  "The census a factory's pass starts from: every count the census line
  prints, at zero."
  {:calls 0 :repositories 0 :complete? true :minted 0 :adopted 0 :folded 0 :moved 0
   :runs-minted 0 :runs-known 0 :runs-skipped 0 :runs-superseded 0
   :runs-orphan 0 :labelled 0 :failing 0 :recovered 0 :stuck 0 :noted 0 :adoption-noted 0
   :unopened-closed 0
   :rerun 0 :rerun-noted 0 :base-opened 0 :base-noted 0 :base-closed 0
   :base-kept 0
   :stale-noted 0 :floor-filed 0 :findings 0 :refused 0})

(defn census-line
  "The one line a pass prints of its census, so every factory prints
  the same line (ticket 58c51a5d). A count the census does not carry
  reads as zero."
  [census {:keys [changes]}]
  (let [n #(long (or (get census %) 0))]
    (str
     (n :calls) " calls, " (count changes) " pull requests, "
     (n :minted) " changes minted, " (n :adopted)
     " changes adopted, " (n :moved)
     " changes moved, " (n :runs-minted) " red runs minted, "
     (n :labelled) " labels pushed"
     (when (pos? (n :runs-superseded))
       (str ", " (n :runs-superseded) " red runs superseded "
            "on a head that moved or a change that ended"))
     (when (pos? (+ (n :failing) (n :recovered) (n :stuck)))
       (str ", " (n :failing) " changes failing, "
            (n :recovered) " green again, "
            (n :stuck) " stuck on red"))
     (when (pos? (n :noted))
       (str ", " (n :noted) " policy source notes written"))
     (when (pos? (n :adoption-noted))
       (str ", " (n :adoption-noted)
            " landed pull requests noted unadopted"))
     (when (pos? (+ (n :rerun) (n :rerun-noted)))
       (str ", " (n :rerun) " interrupted heads re-run, "
            (n :rerun-noted) " interrupted again and left"))
     (when (pos? (+ (n :base-opened) (n :base-noted)
                    (n :base-closed) (n :base-kept)))
       (str ", " (n :base-opened) " red-base tickets opened, "
            (n :base-noted) " red heads noted, "
            (n :base-closed) " closed on green, "
            (n :base-kept) " kept on green"))
     (when (pos? (n :floor-filed))
       (str ", " (n :floor-filed) " groom-floor tickets filed"))
     (when (pos? (n :findings))
       (str ", " (n :findings) " org findings heads recorded"))
     (when (pos? (n :refused))
       (str ", " (n :refused) " refused"))
     (when-some [left (:remaining census)]
       (str ", " left
            (when-some [limit (:limit census)] (str " of " limit))
            " calls left"
            (when-some [reset (:reset census)]
              (str " until " reset))))
     (when (:unchanged census)
       (str ", " (:unchanged census)
            " answers unchanged and not counted"))
     (when-some [until (:held-until census)]
       (str ", every call held until " until
            ": the forge's rate limit is spent"))
     (when-not (or (:complete? census) (:held-until census))
       ", and a repository did not answer — the cursor stands"))))

(defn pass!
  "One pass of a forge (ticket 75db2ef8): poll, run the change pass,
  store the source's place or send it back, and call the factory's own
  steps between. It requires no engine and no factory namespace.

  config:
  :source          the ForgeSource
  :hand            the `change` kind's doors, as `change-pass!` takes them
  :change-pass!    optional (fn [changes census refused]) → census, run
                   in place of `change-pass!` over `:hand`
  :census          the census the pass starts from (default {})
  :log-fn          takes the parts of one line (default *err*)
  :poll-failed     optional (fn [e]): the poll threw, and the throw
                   goes on after it
  :before-changes  optional (fn [census poll]) → census, before the
                   change pass
  :after-changes   optional (fn [census poll]) → census, after the
                   source's place is stored
  :runs            optional: the `ci_run` kind's doors, as `runs-pass!`
                   takes them; the red runs run after `:after-changes`
  :after-runs      optional (fn [census poll]) → census, after the red
                   runs
  :failing         optional: the failing hand, as `failing-pass!` takes
                   it; the failing pass runs after `:after-runs`
  :after-failing   optional (fn [census poll]) → census, after the
                   failing pass
  :always          optional (fn [census poll held-until]) → census, run
                   whether the steps above ran, were held or threw
  :report          optional (fn [census poll]), given the census last,
                   in place of the census line
  :note            optional (fn [line]) → line, given the census line
                   before it is logged

  `poll` is what `forge-poll` answered. A spent rate limit (ticket
  cdba1f6a) holds every step from `:before-changes` to
  `:after-failing`, so nothing there asks the forge. A pass that was
  held or threw before its rows were written sends the source back
  (ticket fc3a5a40), and a throw is thrown again only after `:always`
  ran (ticket c4bac627). Without `:report` the pass logs its
  `census-line`. → the census, with `:repositories`, `:complete?`,
  `:calls` and the source's budget."
  [{:keys [source hand census log-fn poll-failed before-changes
           after-changes runs after-runs failing after-failing always
           report note]
    run-changes :change-pass!}]
  (let [log-fn (or log-fn warn-line)
        run-changes (or run-changes
                        (fn [changes census refused]
                          (change-pass! hand changes census log-fn refused)))
        poll (try (forge-poll source)
                  (catch Exception e
                    (when poll-failed (poll-failed e))
                    (throw e)))
        {:keys [changes repositories complete? answered]} poll
        census (assoc (or census {})
                      :repositories (count repositories)
                      :complete? (boolean complete?))
        held (:held-until (budget-of source))
        committed (volatile! false)
        [census thrown]
        (if held
          [census nil]
          (try
            (let [census (cond-> census
                           before-changes (before-changes poll))
                  refused (volatile! [])
                  census (run-changes changes census refused)
                  _ (retry-refused! source @refused log-fn)
                  ;; the change rows are written and the refused ones
                  ;; handed back, so the source may store its place
                  _ (do (commit-poll! source (or answered repositories)
                                      log-fn)
                        (vreset! committed true))
                  census (cond-> census
                           after-changes (after-changes poll))
                  census (if runs
                           (runs-pass! runs source poll census log-fn)
                           census)
                  census (cond-> census
                           after-runs (after-runs poll))
                  census (if failing
                           (failing-pass! failing source census log-fn)
                           census)
                  census (cond-> census
                           after-failing (after-failing poll))]
              [census nil])
            (catch Exception e [census e])))
        _ (when-not @committed (forget-poll! source log-fn))
        census (cond-> census always (always poll held))
        _ (when thrown (throw thrown))
        census (merge (assoc census :calls (forge-calls source))
                      (budget-of source))]
    (if report
      (report census poll)
      (log-fn ((or note identity) (census-line census poll))))
    census))

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
