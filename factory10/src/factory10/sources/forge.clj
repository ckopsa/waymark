(ns factory10.sources.forge
  "The seam between a forge and the factory's rows, and the pass that
  moves one across the other (bead waymark-fp62.6.4, Reading A).

  THE PROTOCOL IS NEW, AND IT IS SMALL. The change and the ci_run are
  NOT mirror-synced kinds: neither declares an `:adapter`, each keeps
  a state machine of its own, and the engine's Mirror knows nothing
  about them. So this source does not implement workqueue10's
  `TaskSource`, and it does not reuse `ThreadSource` either. It
  implements `ForgeSource`, declared below, for `ThreadSource`'s own
  argument: a source must not answer a question its authority never
  asks. A forge has no `push`, no `create` and no `list`; it has pull
  requests, check runs, job logs and exactly one write. Five verbs say
  that (the fifth, the checks on one head, is ticket d1742908's), and
  a verb that wrote anything else would be a lie about GitHub.

  WHERE THE ENGINE IS. workqueue10's confluence hands its documents to
  the sync machine, which owns the writes. Nothing owns the writes
  here, because these two kinds have no sync machine — so `pass!`
  below writes the rows itself, through the engine's own doors, under
  `factory10.mirror/source-principal`. That principal is a system
  hand, which is what `the-mirror-writes-this-row` admits and what
  every door on both kinds stands behind. The pass invents no id: it
  reads a row by its forge identity, mints one when there is none, and
  moves the row it read.

  WHAT ONE PASS DOES, IN ORDER.
  1. It asks the forge for everything that moved (`forge-poll`).
  2. For each pull request it moves the row that is already here:
     `observe` for the facts, then `merge`, `close` or `reopen` for
     the state. A pull request whose id answers no row, on a head
     branch a row of this house already names, is ADOPTED (bead
     waymark-fp62.6.3.10): a seat built that branch from an ask, and
     its submit opened this pull request. The house must hold one row
     for that work, not two. A pull request no seat asked for is
     SKIPPED and gets no `change` (spec-change R-C.6): a person's pull
     request is GitHub's work, and its `pull_request` row comes from
     the integration's `opened` discovery and the Mirror.
  3. For each finished check run that failed, on a head the change
     still carries, it reads the log tail (`forge-log-tail`) and mints
     one `ci_run` row at `red`. It mints no second row for a check run
     id that is already here. A log it cannot read costs the excerpt
     and never the row: the reason rides in `log_note`.
  4. For each red ci_run of a head its change no longer carries, it
     walks `supersede`. The row leaves the queue, and the transition
     says why it leaves with no verdict on it.
  5. For each `classified` ci_run with no label on it, it pushes one
     label (`forge-label!`) and walks `stamp_label`. That push is the
     only write the source makes at the forge.
  6. For each `submitted` or `failing` change of a repository with an
     active policy, it reads the checks on the head (`forge-checks`)
     against the policy's required checks and walks `fail`, `recover`
     or, on the last round, `stick` (ticket d1742908). A change the
     forge reads as `conflicted` is red too, as `merge-conflict`, with
     the paths the bench's trial merge names (ticket 5f12e772). The
     bench landing is not read here: the change's own `landing_read`
     door reads it (J6 D1). A head whose run died without
     a verdict (cancelled, timed out, or failed before any test step)
     is re-run once instead, and a second death on the same head is
     noted on the change (ticket 22f91244).
  7. For each active policy whose repository the forge refused to
     list (401, 403 or 404), it writes the status, the route and the
     time on the policy row through the hidden `note_source` door, and
     clears that note the first pass the repository answers again
     (ticket 116dfb0d).
  8. For each active policy it reads the head of its base branch and
     the checks on it (`forge-base`). A base red on two passes opens
     one groomed fix ticket in that repository, a later red head is
     noted on it, and a green base completes it (ticket ade81ae9).
  9. For each drafting or proposed org_plan whose change carries a head
     its findings were not recorded at, it reads the artifact that
     head's tests.yml run uploaded (`forge-artifact`) and writes the
     org's findings through the door the plan's state affords (ticket
     c4893567).
  10. It prints one census line: the calls, the rows adopted and
     moved, and the pull requests skipped.

  A HEAD THAT MOVES (bead waymark-fp62.6.9). A run ran on one commit.
  When the head moves, that commit is gone and no seat can answer the
  run: the pass mints no new red row for the dead head, and it walks
  the `supersede` door on every red row that is already here. The row
  leaves the queue at a tomb of its own, the ledger carries the
  transition, and the new head's own failures are the work. Before
  that door the pass could only COUNT these rows, and they stayed at
  `red` in the classifier's queue.

  PARTIAL FAILURE, per workqueue10.confluence. A repository that
  throws costs its own rows one pass and nothing else. The cursor
  advances only when EVERY configured repository answered, so a
  failure in the middle of a pass makes the next pass re-read rather
  than skip.

  THE LABEL RIDES THE PASS, not a transition consumer. The framework
  has a durable consumer API (waymark10.server.consumers, which
  server/wakes rides), and a consumer would push the label seconds
  after the verdict instead of within one cadence. It is not used
  here for one reason: a consumer needs the event dispatcher, and the
  source must run over a store with no dispatcher at all. Acceptance 4
  asks for the label within one cadence, which the pass gives. The
  consumer is recorded as the cheaper beat, not as a punt."
  (:require [clojure.string :as str]
            [factory10.bench :as bench]
            [factory10.first-week :as first-week]
            [factory10.mirror :as mirror]
            [factory10.resources.org-plan :as org-plan]
            [factory10.resources.pull-request :as pull-request]
            [factory10.resources.repo-policy :as policy]
            [factory10.resources.ticket :as ticket]
            [factory10.sources.runs :as runs]
            [factory10.week :as week]
            [factorykit.change :as change-kit]
            [factorykit.forge :as kit]
            [waymark10.server.clock :as clock]
            [waymark10.server.film-rules :as film-rules]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def default-every-seconds
  "How often the pass runs when the wiring names no cadence. Five
  minutes, which is the owner's ruling for the proving ground: one
  repository at this beat is far under the forge's rate limit."
  300)

(def label-scan-limit
  "How many classified runs one pass reads for the label push. A
  ceiling rather than the whole table: a run that waits one more beat
  costs nothing, and an unbounded read costs the pass."
  50)

(def red-scan-limit
  "How many red runs one pass reads for the runs whose change ended.
  The kit's (`factorykit.forge/red-scan-limit`, ticket efff5d2e)."
  kit/red-scan-limit)

;; ── the seam ────────────────────────────────────────────────────────

(def ForgeSource
  "One forge, already speaking the factory's own documents. The
  protocol is the kit's (`factorykit.forge/ForgeSource`, ticket
  6b716e98), under the name this namespace always gave it, and its six
  verbs below are the kit's own functions: what moved, one log tail,
  one label, the call count the census prints, the checks on one head,
  and the head of a base branch with its checks."
  kit/ForgeSource)

(def forge-poll kit/forge-poll)
(def forge-log-tail kit/forge-log-tail)
(def forge-label! kit/forge-label!)
(def forge-calls kit/forge-calls)
(def forge-checks kit/forge-checks)
(def forge-base kit/forge-base)

(def ForgeRerun
  "A run that died without a verdict, asked to run again (ticket
  22f91244). The protocol is `factory10.sources.runs/ForgeRerun`, under
  the name this namespace always gave it, so the `pull_request` kind
  reads the same runs (ticket 0fac9efc)."
  runs/ForgeRerun)

(def forge-runs runs/forge-runs)
(def forge-rerun! runs/forge-rerun!)

(def ForgeCommit
  "A source that keeps its place across boots, told what became of the
  poll it answered (ticket fc3a5a40). The protocol is the kit's
  (`factorykit.forge/ForgeCommit`, ticket 75db2ef8)."
  kit/ForgeCommit)

(def forge-commit! kit/forge-commit!)
(def forge-forget! kit/forge-forget!)

(defprotocol ForgeDeploy
  "Whether a merged pull request is in a commit of its base, for the
  line that waits on a deploy (ticket 47217098). A protocol of its own:
  a source that does not implement it counts a green deploy as covering
  every merge before it."
  (forge-covers? [s repository number sha]
    "→ true when pull request `number` is merged and its merge commit is
    `sha` or an ancestor of it. Throws when the forge does not answer."))

(def ForgeCompare
  "Whether a head lacks its base's current head (ticket 498a089e). The
  protocol is the kit's (`factorykit.forge/ForgeCompare`, ticket
  b5411690), under the name this namespace always gave it, so the
  `pull_request` kind requires it rather than resolving it at the call."
  kit/ForgeCompare)

(def forge-behind? kit/forge-behind?)

(defprotocol ForgePathDiff
  "Whether a directory changed between two heads (ticket de085c93): the
  forge's answer to `git diff --quiet <from> <to> -- <path>`, which a
  base_follow mode asks of an org_dir. A protocol of its own: a source
  that does not implement it answers no path-scoped compare."
  (forge-path-changed? [s repository from-sha to-sha path]
    "→ true when some file at or under `path` (a directory relative to
    the repository root; \"\" for the whole tree) differs between
    `from-sha` and `to-sha`. Read as from...to, so `from-sha` is meant to
    be an ancestor of `to-sha`, as a take's sha is of the base's later
    head. Throws when the forge does not answer."))

(defn under-path?
  "Whether the repository file `filename` lies at or under the directory
  `dir`. A leading ./ and trailing slashes on `dir` are ignored; an empty
  `dir` holds every file."
  [dir filename]
  (let [dir (-> (str dir) (str/replace #"^\./" "") (str/replace #"/+$" ""))
        f (some-> filename str)]
    (boolean (and f (or (= "" dir) (= f dir) (str/starts-with? f (str dir "/")))))))

(def ForgePull
  "One pull request read by its number, outside the window (ticket
  949d18c5). The protocol is the kit's (`factorykit.forge/ForgePull`,
  ticket 58c51a5d)."
  kit/ForgePull)

(def forge-pull kit/forge-pull)

(defprotocol ForgeSteps
  "Which steps of a red job went red (ticket c0d7ce64). A protocol of
  its own: a source that does not implement it names no step, and the
  red-main ticket carries the log tail alone."
  (forge-failed-steps [s check]
    "→ [step-name …] for one check document carrying `:repository`:
    the steps of the job behind it that finished red, in order. Empty
    when the check names no job. Throws when the forge does not
    answer."))

(defprotocol ForgeArtifact
  "One workflow artifact, read as JSON (ticket c4893567): the org
  findings a pull request's tests.yml run uploads as
  `org-findings-<org>-<head sha>`. A protocol of its own: a source that
  does not implement it records no findings."
  (forge-artifact [s repository artifact-name]
    "→ the parsed JSON of the newest unexpired artifact of that name in
    `repository`, or nil when there is none yet. Throws when the forge
    does not answer."))

(def ForgeBudget
  "The forge's rate limit as the source last heard it (ticket
  cdba1f6a). The protocol is the kit's (`factorykit.forge/ForgeBudget`,
  ticket 75db2ef8)."
  kit/ForgeBudget)

(def forge-budget kit/forge-budget)

;; ── what the two kinds take ─────────────────────────────────────────

(def ci-run-create-fields
  "The `ci_run` create door's whole vocabulary. The kit's
  (`factorykit.forge/ci-run-create-fields`, ticket efff5d2e)."
  kit/ci-run-create-fields)

(def red-conclusions
  "The two conclusions that mint a red run. `cancelled` is not one of
  them: somebody stopped that job, so there is nothing to classify.
  They are the kit's table's reds for GitHub (ticket 6b716e98)."
  (kit/raw-states :github :red))

;; ── the engine's own hand ───────────────────────────────────────────

(defn- as-opts []
  {:principal mirror/source-principal})

(defn- base-opts
  "The engine's hand inside the base pass: the ticket's base doors open
  only `:within` it (ticket `only-the-base-pass-writes-this`)."
  []
  (assoc (as-opts) :within {:kind :repo_policy :action :note_base}))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "factory10 forge: " parts))))

(def ^:private present
  "The document, narrowed to the fields named and to what the forge
  actually read. The kit's (`factorykit.forge/present`)."
  kit/present)

(defn- row-by
  "One row of this kind by an indexed field, or nil."
  [eng kind where]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (first (store/query-rows st tx kind where
                                                        {:limit 1}))))))

(defn- rows-by
  "Up to `limit` rows of this kind by indexed fields — `row-by` with
  the page it reads made explicit, for the one reader that must
  CHOOSE between the rows a filter answers."
  [eng kind where limit]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/query-rows st tx kind where
                                                 {:limit limit})))))

(defn- row-by-id [eng kind id]
  (let [st (:storage eng)]
    (when-not (str/blank? (str id))
      (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {}))))))

(def ^:private state-of
  "The row's state as a keyword, whichever way the store spells it. The
  kit's (`factorykit.forge/state-of`)."
  kit/state-of)

(declare move-change!)

(defn- hand
  "This engine's doors on the `change` kind, as the kit's
  `link-change!` and `move-change!` take them (ticket 97bd7937), under
  the source's own principal. `:move!` is this namespace's
  `move-change!`, so every pass moves a row through one function."
  [eng]
  {:find-change (fn [change-id] (row-by eng :change {:change_id change-id}))
   :branch-changes (fn [repository head limit]
                     (rows-by eng :change {:repository repository
                                           :head_branch head}
                              limit))
   :create! (fn [fields] (:row (inv/create! eng :change fields (as-opts))))
   :invoke! (fn [id door input]
              (:row (inv/invoke! eng :change id door input (as-opts))))
   :move! (fn [row doc] (move-change! eng row doc))
   ;; the change's `pull_request` row, by the mirror's own identity
   ;; (ticket 56c97770); none in an engine that does not declare it
   :find-pull-request (fn [repository number]
                        (when (contains? (inv/resources eng) :pull_request)
                          (some-> (row-by eng :pull_request
                                          {:external_id (pull-request/external-id
                                                         repository number)})
                                  :id str)))})

(defn- move-change!
  "The row that is already here, moved to what the forge says now, by
  the kit's `move-change!` through this engine's doors.

  → [row moved?]"
  [eng row doc]
  (kit/move-change! (hand eng) row doc))

;; ── the red runs ────────────────────────────────────────────────────

(def log-note-chars
  "How long `log_note` may be. The kit's
  (`factorykit.forge/log-note-chars`, ticket efff5d2e)."
  kit/log-note-chars)

(declare live-reds)

(defn- run-hand
  "This engine's doors on the `ci_run` kind, as the kit's red runs take
  them (`factorykit.forge/runs-pass!`, ticket efff5d2e), under the
  source's own principal. `:live-reds` reads the heads through
  `read-checks`, the one per-head reader of the pass."
  [eng read-checks log-fn]
  {:find-run (fn [run-id] (row-by eng :ci_run {:run_id run-id}))
   :find-change (fn [change-id] (row-by eng :change {:change_id change-id}))
   :change-by-id (fn [id] (row-by-id eng :change id))
   :reds (fn [where limit]
           (rows-by eng :ci_run (assoc where :state "red") limit))
   :create! (fn [fields] (:row (inv/create! eng :ci_run fields (as-opts))))
   :invoke! (fn [id door input]
              (:row (inv/invoke! eng :ci_run id door input (as-opts))))
   :red-conclusions red-conclusions
   :live-reds (fn [checks] (live-reds eng read-checks checks log-fn))})

;; ── the checks' verdict on a submitted change (ticket d1742908) ───────

(def green-conclusions
  "The conclusions a required check may finish with and still let the
  change merge. `neutral` and `skipped` are GitHub's own passes. They
  are the kit's table's greens for GitHub (ticket 6b716e98)."
  (kit/raw-states :github :green))

(def ^:private newest-runs
  "The runs of one check name that speak for it: the kit's rule, the
  newest alone when every run says where it stands (ticket 6bdaf6fe)."
  kit/newest-runs)

(defn check-verdict
  "What the checks on one head say, read against the policy's required
  checks: {:verdict :red :names […]} when every required check
  finished and at least one went red, {:verdict :green} when every one
  finished green, {:verdict :interrupted :names […]} when none went red
  and at least one was interrupted (cancelled, stale or waiting on a
  person) — it died without a verdict, and it is never green (ticket
  22f91244) — and nil while one is still running, has not started, or
  ended some other way. A policy that names no required check requires
  every check on the head. Of several runs of one name, the newest
  speaks for it (`newest-runs`). The reading is the kit's, over the
  kit's one build-state table (ticket 6b716e98)."
  [required checks]
  (kit/verdict {:forge :github :required required} checks))

(def rejected-verdict
  "A workflow the forge rejected on the head (a failed run with no job,
  read as a check marked `:rejected`, ticket 004188aa) → {:verdict :red
  :names [its file …]}, else nil. Read only where `check-verdict` says
  not yet: the checks that workflow would run never come. The rule is
  the pull_request row's own, so the two read one verdict."
  pull-request/rejected-verdict)

(def failing-scan-limit
  "How many rows of each of the two live states one pass reads."
  200)

(defn- live-changes
  "Every change a seat has pushed and nobody has merged: `submitted`
  and `failing`, read from the store and not from the poll — a check
  that finishes moves no pull request, so the window would miss it."
  [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (into []
              (mapcat #(store/query-rows st tx :change {:state %}
                                         {:limit failing-scan-limit}))
              ["submitted" "failing"])))))

;; A stale `unknown` (ticket 544d36dd) is no longer read again here
;; (ticket 79b4b61a): the Mirror re-reads the change's `pull_request`
;; past its TTL, and a moved `mergeable` walks `head_moved` →
;; `refresh_head` onto the change.

(defn- head-reader
  "The checks of each head, read once for the whole pass (ticket
  3aca3ae8): the run pass and the failing pass ask the same heads, and
  the forge answers each one time. A read that throws is not kept, so
  the next asker tries again."
  [source]
  (let [seen (atom {})]
    (fn [repo head]
      (let [k [repo head]]
        (if-some [hit (find @seen k)]
          (val hit)
          (let [checks (forge-checks source repo head)]
            (swap! seen assoc k checks)
            checks))))))

(defn- live-reds
  "The finished red runs on the head of every live change of a
  repository with an active policy, from the same per-head read the
  failing pass makes (ticket 3aca3ae8). A check that goes red after the
  push moves no pull request, so the poll's window never carries it;
  this read does. Each check carries its `ci_run` document on its
  metadata (`:doc`); a source whose checks carry none answers nothing
  here. A run the poll already carried is left to the poll's own."
  [eng read-checks polled log-fn]
  (let [repos (into #{}
                    (keep #(some-> (get-in % [:data :repository]) str not-empty))
                    (bench/policies eng :active))
        known (into #{} (map :run_id) polled)
        red (fn [row head check]
              (when-some [doc (:doc (meta check))]
                (when (and (= "completed" (str (:status check)))
                           (contains? red-conclusions (str (:conclusion check)))
                           (not (contains? known (:run_id doc))))
                  (assoc doc
                         :change_id (get-in row [:data :change_id])
                         :head_sha head))))]
    (into []
          (mapcat
           (fn [row]
             (let [repo (str (get-in row [:data :repository]))
                   head (some-> (get-in row [:data :head_sha]) str not-empty)]
               (when (and head (contains? repos repo))
                 (try
                   (into [] (keep #(red row head %)) (read-checks repo head))
                   (catch Exception e
                     (log-fn "the checks of " (get-in row [:data :change_id])
                             " did not answer (" (ex-message e) ")")
                     nil))))))
          (live-changes eng))))

;; ── a run that died without a verdict (ticket 22f91244) ────────────
;;
;; The pull_request row reads a run that died before its tests as
;; `pending` and re-runs it through its own pull (ticket 04e40648); this
;; pass re-runs nothing.

(def interrupted-conclusions runs/interrupted-conclusions)

(defn- text-of [v]
  (some-> v str str/trim not-empty))

(defn- tail-of [s n]
  (if (> (count s) n) (subs s (- (count s) n)) s))

;; Fail, recover and stick on a head's verdict are the change's own
;; doors, on its pull request's `checks_red` and `checks_green` (plan
;; 85b4462e, D2b): this factory hands the kit no failing pass. The
;; pull request stamps `held_head`, `held_since` and `pending_checks`
;; through `refresh_head` (ticket 6a5c9f8a).

;; ── a head that lacks a required check (ticket 498a089e) ────────────
;;
;; A branch cut before its base gained a workflow never runs that
;; workflow's check, and GitHub may call it `clean` all the same, so
;; the rig's merge answers `waiting` forever. The pull_request kind
;; writes the two facts the house needs to end that wait — the required
;; checks with no run at all on the head, and whether the head lacks its
;; base's current head — and the change follows them (tickets 6a530e5d,
;; 2edb2167); the merge line reads them (bench/work-lines!).

(def missing-checks
  "The required checks with NO run at all among `checks`: a check that
  is pending or running is not missing (ticket 498a089e). The
  pull_request kind reads the same list (ticket 6a530e5d)."
  pull-request/missing-checks)

;; ── a change's ticket, and the engine's hand that closes it ────────
;;
;; A submitted change with no pull request is the change's own
;; `landing_read`'s now (ticket 53319e32): it gives the lost number, or
;; stops the round that opened none.

(defn- ticket-of
  "The id of the ticket a seat-born change was built for, or nil."
  [row]
  (some (fn [v]
          (let [s (str v)]
            (when (str/starts-with? s "ticket:")
              (not-empty (subs s (count "ticket:"))))))
        [(get-in row [:data :born_from]) (get-in row [:data :change_id])]))

(defn- close-as-engine!
  "The engine's hand the kit's sweep closes with (ticket 028a5f6f): an
  edit door is fenced, so it names the version of the row it read, and
  a row that moved since waits a sweep."
  [eng]
  (fn [row door input]
    (inv/invoke! eng :change (str (:id row)) door input
                 (assoc (as-opts) :if-match (str (:version row))))))

;; ── the old name's last rows (ticket 2d216859) ──────────────────────
;;
;; Ticket 8f2fac64 renamed `landed_at` to `unadopted_since`, and the
;; rows the forge pass no longer looks at kept the old name. The boot
;; clears them once, beneath the doors because the field is no longer
;; law, and a boot after that finds nothing to write.

(def ^:private legacy-limit
  "The most change rows one boot reads for the old name."
  100000)

(defn- without-landed-at
  "The row with `landed_at` gone. A submitted change with no number
  keeps its stamp as `unadopted_since` when it has none of its own."
  [row]
  (let [legacy (text-of (get-in row [:data :landed_at]))]
    (cond-> (update row :data dissoc :landed_at)
      (and legacy
           (= :submitted (state-of row))
           (nil? (get-in row [:data :number]))
           (nil? (text-of (get-in row [:data :unadopted_since]))))
      (assoc-in [:data :unadopted_since] legacy))))

(defn clear-landed-at!
  "Every change row still carrying `landed_at` rewritten without it
  (ticket 2d216859); the count of rows written. Idempotent: a row
  without the field is not touched, and a row another process moved
  first is left to the next boot."
  [eng]
  (let [st (:storage eng)
        rows (store/with-tx st
               (fn [tx] (store/query-rows st tx :change {}
                                          {:limit legacy-limit})))]
    (reduce
     (fn [n row]
       (if-not (contains? (:data row) :landed_at)
         n
         (try
           (if (store/with-tx st
                 (fn [tx]
                   (let [now (store/load-row st tx :change (str (:id row)) {})]
                     (when (contains? (:data now) :landed_at)
                       (store/save-row! st tx :change
                                        (assoc (without-landed-at now)
                                               :version (inc (long (:version now))))
                                        (:version now))
                       true))))
             (inc n)
             n)
           (catch Exception e
             (warn! "the old landed_at of change " (:id row)
                    " was not cleared (" (ex-message e) ")")
             n))))
     0 rows)))

(def ^:private link-scan-limit
  "How many changes of one live state the link pass reads (ticket
  56c97770). A linked change is read again, so the ceiling is set above
  every live change this house holds."
  2000)

(defn- link-pass!
  "Every live change with a number and no link → linked to its
  `pull_request` row by the kit's `link-change!`, once the mirror holds
  that row (spec-change §3, R-C.4; ticket 56c97770). The first pass
  after the deploy is the backfill; each one after it links what the
  change pass minted or adopted before the mirror's discovery made the
  row. A refused link costs that change one pass and nothing else."
  [eng census log-fn]
  (let [st (:storage eng)
        h (hand eng)
        unlinked (store/with-tx st
                   (fn [tx]
                     (into []
                           (comp (mapcat #(store/query-rows st tx :change {:state %}
                                                            {:limit link-scan-limit}))
                                 (filter #(and (some? (get-in % [:data :number]))
                                               (str/blank? (str (get-in % [:data :pull_request]))))))
                           ["open" "submitted" "failing" "stuck"])))
        linked (count (filter #(some? (get-in (kit/link-change! h % log-fn)
                                              [:data :pull_request]))
                              unlinked))]
    (when (pos? linked)
      (log-fn "linked " linked " changes to their pull requests"))
    census))

(defn- ended-ticket-sentence
  "The ending of the ticket a change was built for, as the sentence the
  kit's sweep closes it with, else nil while the ticket is live."
  [eng row]
  (when-some [tid (ticket-of row)]
    (when-some [t (try (row-by-id eng :ticket tid) (catch Exception _ nil))]
      (when-some [ending (#{:done :dropped} (state-of t))]
        (ticket/ending-sentence ending (get-in t [:data :close_reason]))))))

(defn sweep-ended-tickets!
  "THE LEFTOVERS, SWEPT ONCE A DEPLOY (ticket 458d65c5). A ticket's
  ending closes its unmerged changes now, but the changes left open,
  stuck or failing beside a ticket that ended before it did not move.
  The kit's sweep (`factorykit.change/sweep!`, ticket 48f9605b) closes
  each that has no pull request through `close_without_pr`, naming the
  ending, and leaves every change whose ticket is still live. The door
  moves no ticket. `start-passes!` runs it on
  its first beat. → how many it closed."
  [eng log-fn]
  (let [closed (change-kit/sweep!
                {:rows (mapcat #(rows-by eng :change {:state %} failing-scan-limit)
                               ["open" "stuck" "failing"])
                 :ended #(ended-ticket-sentence eng %)
                 :close! (close-as-engine! eng)
                 :log-fn log-fn})]
    (log-fn closed " changes of ended tickets swept")
    closed))

;; ── a reader's change is closed (ticket 2db85f7b) ───────────────────
;;
;; Before ticket 7a4a6f9e a sit minted a change for every seat whose
;; scope named the `change` kind, a seat that only reads it too. Such a
;; change has no pull request and no seat holds a door that ends it:
;; `discard` leaves it open, and `close` is GitHub's word. The engine
;; closes it with its own hand through `supersede`, which moves no
;; ticket: the ticket is a live draft and stays where it is. Since
;; ticket 028a5f6f it walks the kit's sweep and `close_without_pr`.

(defn- seat-named
  "The seat row of this name, decoded, or nil."
  [eng seat-name]
  (when-some [rd (get (inv/resources eng) :seat)]
    (some->> (row-by eng :seat {:name seat-name}) (inv/decode-row rd))))

(defn- reader-change-close
  "The sentence closing an open change with no pull request whose
  `author` names a seat that is not a code seat, else nil. An author
  that names no seat is left: it may be a GitHub login."
  [eng row]
  (when (and (= :open (state-of row))
             (nil? (get-in row [:data :number])))
    (when-some [author (some-> (get-in row [:data :author]) str not-empty)]
      (when-some [seat (seat-named eng author)]
        (when-not (mcp/code-seat? seat)
          (str "closed: its author " author " is a seat that only reads "
               "a change, so no round could end it and it never opened "
               "a pull request (ticket 2db85f7b)"))))))

;; ── a code seat's abandoned change is closed (ticket f1deff20) ───────
;;
;; A code seat begins a change, then its ticket goes back to draft and
;; the change stands open: no pull request, no round, nothing in the
;; worktree. The same sweep closes it once its ticket has stood out of
;; the queue for a day, so an ungroom that is restated and groomed again
;; keeps its change. A change born from a task, or from no row, has no
;; queue to come back to and is closed at once. The ticket is not moved.

(def ^:private abandoned-window-ms
  "How long a ticket stands in draft, done or dropped before its
  unbuilt change is closed: one day."
  (* 24 60 60 1000))

(defn- abandoned-reason
  "Why this unbuilt change is abandoned, or nil when it is not."
  [eng row ^Instant now]
  (let [born (some-> (get-in row [:data :born_from]) str not-empty)]
    (if-some [tid (ticket-of row)]
      (when-some [t (try (row-by-id eng :ticket tid) (catch Exception _ nil))]
        (let [state (#{:draft :done :dropped} (state-of t))
              at (:updated-at t)]
          (when (and state
                     (instance? Instant at)
                     (> (- (.toEpochMilli now) (.toEpochMilli ^Instant at))
                        (long abandoned-window-ms)))
            (str "its ticket " tid " has stood " (name state)
                 " for more than a day"))))
      (cond
        (nil? born) "it was born from no row"
        (str/starts-with? born "task:") "it was born from a task"))))

(defn- abandoned-change-close
  "The sentence closing an open change with no pull request, no round
  and nothing in its worktree that is abandoned, else nil."
  [eng row ^Instant now]
  (when (and (= :open (state-of row))
             (nil? (get-in row [:data :number]))
             (zero? (long (or (get-in row [:data :rounds]) 0)))
             (zero? (long (or (get-in row [:data :worktree_dirty]) 0))))
    (when-some [reason (abandoned-reason eng row now)]
      (str "closed: " reason ", and this change has no pull request, "
           "no round and nothing in its worktree (ticket f1deff20)"))))

(defn sweep-reader-changes!
  "Closes every open change with no pull request that a seat which is
  not a code seat was minted (ticket 2db85f7b), and every abandoned one
  as of `now` (ticket f1deff20), and moves no ticket. The boot runs it
  and so does every pass. → how many it closed."
  ([eng log-fn] (sweep-reader-changes! eng log-fn (Instant/now)))
  ([eng log-fn ^Instant now]
   (let [closed (change-kit/sweep!
                 {:rows (rows-by eng :change {:state "open"} failing-scan-limit)
                  :ended #(or (reader-change-close eng %)
                              (abandoned-change-close eng % now))
                  :close! (close-as-engine! eng)
                  :log-fn log-fn})]
     (when (pos? (long closed))
       (log-fn closed " changes no seat could end were closed"))
     closed)))

(defn- sweep-at-boot!
  "The sweep, on the first beat that has an engine. → true once it has
  run, whatever it closed or refused."
  [{:keys [engine engine-ref log-fn]}]
  (if-some [eng (or engine (some-> engine-ref deref))]
    (do (try (sweep-ended-tickets! eng (or log-fn warn!))
             (catch Exception e
               (warn! "the sweep of ended tickets failed (" (ex-message e) ")")))
        (try (sweep-reader-changes! eng (or log-fn warn!))
             (catch Exception e
               (warn! "the sweep of readers' changes failed (" (ex-message e)
                      ")")))
        true)
    false))

;; ── a red base opens one ticket (ticket ade81ae9) ────────────────────
;;
;; A base branch that goes red stops every pull request on it, and
;; nothing else in the engine reads a base. So for each active policy
;; the pass reads the head of the policy's `base` and its checks, and
;; judges them with `check-verdict`, as a pull request's head is judged.
;; The state lands on the policy row (`base_state`, `base_head`) through
;; the hidden `note_base` door, only when it moved.
;;
;; RED TWICE OPENS ONE TICKET. A red read on one pass is remembered; a
;; red read on the NEXT pass too mints one p0 `bug` ticket in that
;; repository and grooms it with the engine's own hand, so the
;; repository's code seat wakes on `groom`. A check that is mid-rerun
;; reads `unknown` between, and that starts the count again. While that
;; ticket has not ended, a later red head is written on it (`red_heads`)
;; and no second ticket is minted. A green base ends it through the
;; hidden `mend` door, whether or not its own change merged.

(def ^:private base-log-tails
  "How many red checks of a base carry their log tail into the ticket."
  3)

(def ^:private ended-ticket #{:done :dropped})

(def ^:private red-head-states
  "The states `note_red` writes a red head on the ticket in. A draft, a
  blocked or a deferred ticket is a person's, and is left alone."
  #{:open :in_review})

(def ^:private green-note-states
  "The states `note_green` writes the green note on the ticket in. A
  seat's stall sends the ticket to draft, and a person may have blocked
  or deferred it since (ticket e666bd6a). A ticket under review has a
  stuck change with no pull request and work on its branch when a
  submit's landing failed on the last round: `stick` leaves the ticket
  in review (tickets 1188f3ed, 900ee2f2)."
  #{:open :in_review :draft :blocked :deferred})

(def ^:private work-holding
  "The states of a change that the ticket's ending closes when it has
  no pull request, as ticket.clj's `unmerged` names them."
  ["open" "stuck" "failing"])

(defn- cut [s n]
  (let [s (str s)] (subs s 0 (min (count s) (long n)))))

(defn- red-head-line [sha names]
  (cut (str sha ": " (str/join ", " names)) 400))

(defn- work-kept
  "A MAIN-RED TICKET THAT MENDS ITSELF KEEPS THE SEAT'S WORK (ticket
  d5000a1c). `mend` ends the ticket, and the ending closes its open
  change through `supersede`. A flake's ticket went green that way
  while a seat held an edit on its bench branch, and the edit was
  lost. So the base pass asks the bench first. A stuck or a failing
  change is closed by the ending as an open one is, so it is read the
  same way (ticket e666bd6a). → the open, stuck or failing change born
  from this ticket, with no pull request, whose branch has edits or
  commits ahead of the base; `:unknown` when the bench did not answer
  for one; `[:refused name branch]` when the bench refused for one
  that may hold work; nil when every such change is empty, which
  `mend` closes as before.

  WHAT THE RIG ANSWERS (read 2026-10-09, ticket e768af15). `status` is
  read from the branch's worktree: `dirty`, `ahead`, `behind`, `head`.
  For a branch with no worktree it answers no counts. It refuses with
  `no_worktree`, and it does not read `ahead` from the branch alone.
  `stall` asks the rig for nothing, so the stall itself removes no
  worktree. The rig's own code is not in this repository, so whether
  it removes one later is not read here.

  So a refusal is read by the change's state. An open change the rig
  refuses was never prepared and holds no work. A stuck or a failing
  change was worked on, and a refusal does not say its branch is
  empty: it is not known either, and the answer carries the refusal
  and the branch so the log names both (ticket 7984a8b1)."
  [eng policy ticket-row]
  (let [born (str "ticket:" (:id ticket-row))
        repo (str (get-in policy [:data :repository]))
        changes (into {}
                      (comp (filter #(and (= born (str (get-in % [:data :born_from])))
                                          (some #{(some-> (state-of %) name)}
                                                work-holding)
                                          (nil? (get-in % [:data :number]))))
                            (map (juxt :id identity)))
                      (mapcat (fn [state]
                                (concat (rows-by eng :change
                                                 {:state state :change_id born} 50)
                                        (rows-by eng :change
                                                 {:state state :repository repo}
                                                 200)))
                              work-holding))]
    (reduce (fn [found change]
              (let [branch (bench/branch-of change policy)
                    status (bench/ask {:services (:services eng)} :status
                                      {:repo repo :branch branch})]
                (cond
                  (nil? status) :unknown
                  (bench/refused status)
                  (if (= "open" (some-> (state-of change) name))
                    found
                    [:refused (bench/refused status) branch])
                  (or (pos? (long (or (:dirty status) 0)))
                      (pos? (long (or (:ahead status) 0)))) (reduced change)
                  :else found)))
            nil
            (vals changes))))

(defn- seen-head? [ticket-row sha]
  (boolean (some #(str/starts-with? (str %) (str sha))
                 (get-in ticket-row [:data :red_heads]))))

(def ^:private detail-reserve
  "The characters of a red-main ticket's body kept for what is not log:
  the checks and their addresses, the red steps, and what to do."
  4000)

(defn- red-main-detail
  "The body a code seat reads: what is red, where, the log, and what to
  do about it. `failed` are the checks on the head that are not
  required and went red too — the suites a required `gate` only names
  (`quick=failure`) — and their logs come first, because the cause is
  in them (ticket 9a14577e). Each log names the steps of its job that
  went red, so the step is named even when the tail is cut before it
  (ticket c0d7ce64). The logs share what the body's ceiling leaves, and
  each keeps its END: the ceiling cut the body from the end, and so cut
  the failing lines of every log but the first (ticket 3a9d6c62)."
  [source repo base head red-from red-checks failed]
  (let [logged (concat (take base-log-tails failed)
                       (take base-log-tails red-checks))
        share (quot (- (long ticket/detail-chars) (long detail-reserve))
                    (max 1 (count logged)))
        tails (for [c logged]
                (let [{:keys [excerpt note]}
                      (try (forge-log-tail source (assoc c :repository repo))
                           (catch Exception e {:note (ex-message e)}))
                      steps (when (satisfies? ForgeSteps source)
                              (try (forge-failed-steps
                                    source (assoc c :repository repo))
                                   (catch Exception _ nil)))]
                  (str "### " (:check_name c) "\n\n"
                       (when (seq steps)
                         (str "Red steps: "
                              (str/join ", " (map #(str "`" % "`") steps))
                              "\n\n"))
                       (if (str/blank? (str excerpt))
                         (str "No log tail: " (or note "the forge gave none") ".")
                         (str "```\n" (tail-of (str excerpt) share)
                              "\n```")))))]
    (cut (str "## What is red\n\n"
              "The head of `" base "` in " repo " is `" head "`, and these "
              "checks on it finished red:\n\n"
              (str/join "\n" (map #(str "- " (:check_name %)
                                        (when-some [u (:url %)] (str " — " u)))
                                  red-checks))
              "\n\n"
              (when (seq failed)
                (str "Beneath them, these checks on the same head failed "
                     "too, and their logs come first below:\n\n"
                     (str/join "\n" (map #(str "- " (:check_name %)
                                               (when-some [u (:url %)]
                                                 (str " — " u)))
                                         failed))
                     "\n\n"))
              (when red-from
                (str "The first red head after a green one was `" red-from
                     "`: that commit, or the merge that made it, is the "
                     "likely cause.\n\n"))
              "## The log\n\n" (str/join "\n\n" tails) "\n\n"
              "## What to do\n\n"
              "The engine opened this ticket because `" base "` stayed red "
              "for two passes. The fix goes on a branch like any ticket's, "
              "and merges through its pull request. Do not revert other "
              "people's merges unless the log shows that is the only fix, "
              "and if it is, say so in the submit. The engine completes "
              "this ticket when the head of `" base "` is green again, "
              "whether or not this ticket's change merged.")
         ticket/detail-chars)))

(defn- open-red-ticket!
  "One groomed p0 bug in the policy's repository, with the red head
  written on it. → its id."
  [eng source repo base head red-from red-checks failed names]
  (let [row (:row (inv/create! eng :ticket
                               {:title (cut (str base " is red: "
                                                 (str/join ", " names))
                                            200)
                                :detail (red-main-detail source repo base head
                                                         red-from red-checks
                                                         failed)
                                :type "bug"
                                :priority 0
                                :repo repo}
                               (as-opts)))
        id (str (:id row))]
    (inv/invoke! eng :ticket id :groom {} (as-opts))
    (inv/invoke! eng :ticket id :note_red
                 {:red_head (red-head-line head names)} (base-opts))
    id))

;; THE SOURCE'S CURSOR, KEPT ON THE POLICY (ticket c07b581f). The GitHub
;; source holds one cursor for each repository and loses them at a
;; restart, and a source with no cursor reads a repository from its
;; beginning. So the cursor is written on the repository's own
;; `repo_policy` row with a maintenance write, as `base_checked_at` is,
;; and a boot reads it back. A row that carries none yet is seeded from
;; the newest stored change of that repository, so the first boot with
;; this field does not read the world either; a repository with no
;; change at all answers nil, and the source reads its open pull
;; requests only.

(defn- active-policy-of [eng repo]
  (first (filter #(= (str repo) (some-> (get-in % [:data :repository]) str))
                 (bench/policies eng :active))))

(defn- newest-change-stamp
  "When the newest stored change of this repository was last written,
  or nil when the repository has none."
  [eng repo]
  (let [st (:storage eng)]
    (some-> (store/with-tx st
              (fn [tx] (first (store/query-rows st tx :change
                                                {:repository (str repo)}
                                                {:limit 1
                                                 :order-by :updated_at
                                                 :newest-first true}))))
            :updated-at str not-empty)))

(defn cursor-store
  "Where the GitHub source keeps each repository's cursor across boots:
  → {:load (fn [repo]) :save! (fn [repo cursor])}, over the active
  `repo_policy` rows of this engine."
  [eng]
  (let [save! (fn [repo cursor]
                (when-some [policy (active-policy-of eng repo)]
                  (bench/mark-row! eng :repo_policy (str (:id policy))
                                   {:forge_cursor (str cursor)} #{})))]
    {:load (fn [repo]
             (or (some-> (active-policy-of eng repo)
                         (get-in [:data :forge_cursor]) str not-empty)
                 (when-some [seed (newest-change-stamp eng repo)]
                   (save! repo seed)
                   seed)))
     :save! save!}))

(defn- blank->nil [v] (some-> v str not-empty))

(defn- now-of [eng] (if-some [f (:now-fn eng)] (f) (java.time.Instant/now)))

(defn- note-base!
  "The base's own facts on the policy, written through `note_base` only
  when they moved. A read that moved nothing still stamps
  `base_checked_at`, with a maintenance write as bench's `mark-row!`
  makes, so a quiet base says when it was last read and the log is not
  flooded (ticket c4bac627)."
  [eng policy input stored]
  (if (not= input stored)
    (inv/invoke! eng :repo_policy (str (:id policy)) :note_base input
                 (as-opts))
    (bench/mark-row! eng :repo_policy (str (:id policy))
                     {:base_checked_at (now-of eng)} #{})))

;; A BASE THE PASS COULD NOT READ (ticket c4bac627). A base read that
;; throws, or answers no head, is written on the policy's `source_note`
;; through the hidden `note_source` door (ticket 567915d7), and the
;; first good read clears it. A note that already says the same reason
;; is left alone, and a note of the source's own refusal is the larger
;; fact and `source-note-pass!`'s to clear, so the base pass never
;; writes over it. A policy that names no required checks has no base
;; verdict to lose, and is not noted.

(def ^:private base-note? policy/base-note?)

(def ^:private base-note-head policy/base-note-head)

(defn- note-base-read!
  "The policy's `source_note` after one base read: `why` nil clears a
  base note, and a reason writes one — on a policy whose base read
  matters, one naming `required_checks` or a `deploy_check`."
  [eng policy base why]
  (let [id (str (:id policy))
        stored (str (get-in (row-by-id eng :repo_policy id)
                            [:data :source_note]))]
    (cond
      (nil? why)
      (when (base-note? stored)
        (inv/invoke! eng :repo_policy id :note_source {} (as-opts)))

      (and (empty? (bench/required-checks-of policy))
           (nil? (bench/deploy-check-of policy)))
      nil

      (or (str/blank? stored)
          (and (base-note? stored)
               (not (str/starts-with? stored (base-note-head base why)))))
      (inv/invoke! eng :repo_policy id :note_source
                   {:unread (cut base 200) :because (cut why 300)}
                   (as-opts)))))

(defn- base-move!
  "One policy's base, read and judged, written, and then at most one
  ticket move. The base is recorded from the read BEFORE any ticket
  door is opened, so a door that refuses costs the ticket move and not
  the base write (ticket 806e9185).
  → the census."
  [eng source policy census log-fn]
  (let [repo (str (get-in policy [:data :repository]))
        base (bench/base-of policy)
        [base-read why] (try [(forge-base source repo base) nil]
                             (catch Exception e
                               [nil (or (ex-message e) (str (class e)))]))
        head (blank->nil (:head_sha base-read))]
    (note-base-read! eng policy base
                     (or why (when (nil? head) "the forge answered no head")))
    (if (nil? head)
      census
      (let [data (:data policy)
            verdict (check-verdict (bench/required-checks-of policy)
                                   (:checks base-read))
            now (case (:verdict verdict) :red "red" :green "green" "unknown")
            was (str (:base_state data))
            stored-ticket (blank->nil (:base_ticket data))
            known (row-by-id eng :ticket stored-ticket)
            live (when (and known (not (ended-ticket (state-of known))))
                   known)
            red-from (case now
                       "red" (if (= "green" was)
                               head
                               (blank->nil (:base_red_from data)))
                       "green" nil
                       (blank->nil (:base_red_from data)))
            names (:names verdict)
            red-checks (filterv #(and (some #{(str (:check_name %))} names)
                                      (contains? red-conclusions
                                                 (str (:conclusion %))))
                                (:checks base-read))
            ;; the red checks the policy does not require: the suites
            ;; behind an aggregating `gate`, newest run of each name
            failed (->> (:checks base-read)
                        (group-by #(str (:check_name %)))
                        vals
                        (mapcat newest-runs)
                        (filterv #(and (not (some #{(str (:check_name %))}
                                                  names))
                                       (contains? red-conclusions
                                                  (str (:conclusion %))))))
            stored {:verdict (blank->nil (:base_state data))
                    :head (blank->nil (:base_head data))
                    :red_from (blank->nil (:base_red_from data))
                    :ticket stored-ticket}
            base-facts {:verdict now :head head :red_from red-from
                        :ticket stored-ticket}
            ;; the read is written first: what the base is, and on
            ;; which head, is known here and must not ride on a ticket
            ;; door answering
            _ (note-base! eng policy base-facts stored)
            ;; the base moved: a house line's front may be behind now
            ;; (ticket 6e190062), and no pull request hears of it from
            ;; GitHub, so its compare is told here (ticket 2edb2167)
            _ (when (and (:head stored) (not= head (:head stored)))
                (pull-request/base-moved! eng repo)
                (when (bench/house-pass-merges? policy)
                  (bench/nudge-house! repo [:base repo head])))
            [census ticket-id]
            (try
              (cond
                (not (and (= "red" now) (= "red" was)))
                (if (and (= "green" now) live)
                  (let [kept (work-kept eng policy live)]
                    (cond
                      (nil? kept)
                      (do (inv/invoke! eng :ticket (str (:id live)) :mend
                                       {:close_reason (str base " is green again at "
                                                           head ".")}
                                       (base-opts))
                          [(update census :base-closed inc) stored-ticket])

                      ;; its change holds work: the ticket is not ended,
                      ;; and the sentence is written one time
                      (and (map? kept) (green-note-states (state-of live))
                           (str/blank? (str (get-in live [:data :green_note]))))
                      (do (inv/invoke! eng :ticket (str (:id live)) :note_green
                                       {:green_note
                                        (str base " is green again at " head
                                             " on its own. The change on "
                                             (bench/branch-of kept policy)
                                             " holds work that is not submitted,"
                                             " so this ticket was left open.")}
                                       (base-opts))
                          [(update census :base-kept inc) stored-ticket])

                      ;; the bench did not answer: the ticket is held and
                      ;; no sentence is written on it, so the log says so
                      ;; (ticket 6d8e3a3e). The next green pass asks again
                      (= :unknown kept)
                      (do (log-fn "the red-base ticket " (:id live) " of " repo
                                  " was left open on a green " base
                                  ": the bench did not answer for its change")
                          [(update census :base-kept inc) stored-ticket])

                      ;; the bench refused: held the same way, and the
                      ;; line names the refusal and the branch (ticket
                      ;; 7984a8b1)
                      (vector? kept)
                      (let [[_ refusal branch] kept]
                        (log-fn "the red-base ticket " (:id live) " of " repo
                                " was left open on a green " base
                                ": the bench refused " refusal
                                " for its change on " branch)
                        [(update census :base-kept inc) stored-ticket])

                      ;; noted already: the next green pass asks again
                      :else [(update census :base-kept inc) stored-ticket]))
                  [census stored-ticket])

                ;; red twice, on a head the known ticket already carries:
                ;; nothing new, and a ticket a person ended on this very
                ;; head is not minted again
                (and known (seen-head? known head))
                [census stored-ticket]

                live
                (if (red-head-states (state-of live))
                  (do (inv/invoke! eng :ticket stored-ticket :note_red
                                   {:red_head (red-head-line head names)}
                                   (base-opts))
                      [(update census :base-noted inc) stored-ticket])
                  [census stored-ticket])

                :else
                [(update census :base-opened inc)
                 (open-red-ticket! eng source repo base head red-from
                                   red-checks failed names)])
              (catch Exception e
                (log-fn "the base of " repo " was read and written, but its "
                        "ticket did not move (" (ex-message e) ")")
                [census stored-ticket]))]
        ;; the ticket the move minted, once it is minted and not before
        (when (not= ticket-id stored-ticket)
          (note-base! eng policy (assoc base-facts :ticket ticket-id)
                      base-facts))
        ;; the deploy the merge line waits on, from the same read
        ;; (ticket 47217098)
        (bench/note-deploy! eng policy base-read
                            (fn [number sha]
                              (or (not (satisfies? ForgeDeploy source))
                                  (forge-covers? source repo number sha))))
        census))))

(defn- base-pass!
  "Every active policy → its base read, judged, and written. A base the
  forge will not read costs that repository this pass and nothing
  else, and is noted on its policy."
  [eng source census log-fn]
  (reduce
   (fn [census policy]
     (try
       (base-move! eng source policy census log-fn)
       (catch Exception e
         (log-fn "the base of " (get-in policy [:data :repository])
                 " was not read (" (ex-message e) ")")
         census)))
   census
   (bench/policies eng :active)))

;; ── the groom floor (ticket eb515931) ───────────────────────────────
;;
;; A LINE THAT RUNS DRY SAYS SO. A repository whose open queue falls
;; below its policy's `groom_floor` gets one draft ticket, where mayor
;; already reads: the draft tickets. The engine grooms nothing itself;
;; grooming, completing or dropping that ticket is the answer. At most
;; one in a settle window, and none while the last is draft or open.

(def ^:private floor-scan-limit
  "How many tickets of one state in one repository the floor reads."
  1000)

(def ^:private floor-title-prefix "Groom the next batch for ")

(def ^:private groomable-types #{"bug" "task" "chore"})

(defn- floor-ticket? [repo row]
  (str/starts-with? (str (get-in row [:data :title]))
                    (str floor-title-prefix repo ":")))

(defn- floor-waits?
  "Is this policy's floor ticket still draft or open? Found by the id
  the policy keeps, whatever its title says now. A policy that filed
  before it kept one is read by the title among `rows`, one time: the
  id it finds is kept."
  [eng policy repo rows]
  (if-some [id (blank->nil (get-in policy [:data :floor_ticket]))]
    (contains? #{:draft :open} (state-of (row-by-id eng :ticket id)))
    (when-some [row (first (filter #(floor-ticket? repo %) rows))]
      (inv/invoke! eng :repo_policy (str (:id policy)) :note_floor
                   {:filed (str (:id row))} (as-opts))
      true)))

(defn- as-instant [v]
  (cond (instance? Instant v) v
        (str/blank? (str v)) nil
        :else (Instant/parse (str v))))

(defn- floor-detail [repo n floor waiting]
  (cut (str "The open queue for `" repo "` holds " n " tickets, under "
            "the floor of " floor " its policy states. " waiting
            " draft bugs, tasks and chores wait for grooming.\n\n"
            "Groom the next batch, or complete or drop this ticket to "
            "answer it. The engine grooms nothing itself, and files no "
            "other floor ticket for `" repo "` while this one is draft "
            "or open.")
       ticket/detail-chars))

(defn- floor-move!
  "One policy's floor. `open` is the queue: a blocked, a deferred and an
  in-review ticket (one beside a submitted change) each has its own
  state. → true when it filed a ticket."
  [eng policy]
  (let [floor (long (or (get-in policy [:data :groom_floor]) 0))
        settle (long (or (get-in policy [:data :groom_floor_settle_seconds])
                         3600))
        repo (blank->nil (get-in policy [:data :repository]))
        ^Instant now (now-of eng)
        ^Instant noted (as-instant (get-in policy [:data :floor_noted_at]))
        line (long (or (get-in policy [:data :groom_floor_max_priority]) 4))
        within? #(<= (long (or (get-in % [:data :priority]) 4)) line)
        in-state #(rows-by eng :ticket {:repo repo :state %} floor-scan-limit)]
    (when (and repo (pos? floor)
               (or (nil? noted)
                   (not (.isBefore now (.plusSeconds noted settle)))))
      (let [opened (in-state :open)
            n (count (filter within? opened))]
        (when (< n floor)
          (let [drafts (in-state :draft)
                waiting (count (filter #(and (within? %)
                                             (contains? groomable-types
                                                        (str (get-in % [:data :type]))))
                                       drafts))]
            ;; under a line, the floor asks only when a draft at or
            ;; above it waits; the default 4 asks as it always did
            (when-not (or (and (< line 4) (zero? waiting))
                          (floor-waits? eng policy repo (concat drafts opened)))
              (let [filed (inv/create! eng :ticket
                                       {:title (cut (str floor-title-prefix repo ": " n
                                                         " open, floor " floor)
                                                    200)
                                        :detail (floor-detail repo n floor waiting)
                                        :type "chore"
                                        :priority 1
                                        :repo repo}
                                       (as-opts))]
                (inv/invoke! eng :repo_policy (str (:id policy)) :note_floor
                             {:noted_at (str now) :open_count n
                              :filed (some-> (get-in filed [:row :id]) str)}
                             (as-opts)))
              true)))))))

(defn floor-pass!
  "Every active policy → its groom floor judged, and one draft ticket
  filed where the open queue fell below it. A policy the pass cannot
  judge costs that repository this pass and nothing else. → the
  census, with `:floor-filed` counted."
  [eng census log-fn]
  (reduce
   (fn [census policy]
     (try
       (cond-> census
         (floor-move! eng policy) (update :floor-filed (fnil inc 0)))
       (catch Exception e
         (log-fn "the groom floor of " (get-in policy [:data :repository])
                 " was not judged (" (ex-message e) ")")
         census)))
   census
   (bench/policies eng :active)))

;; ── the one write ───────────────────────────────────────────────────

(defn- unlabelled?
  "A classified run that has a verdict and no label yet."
  [row]
  (and (not (str/blank? (str (get-in row [:data :verdict]))))
       (str/blank? (str (get-in row [:data :pushed_label])))))

(defn- label-pass!
  "Every classified run with no label → one label at the forge, then
  the `stamp_label` door. The door is what puts the push in the
  transition log, so a pushed label can be told from an intended one.
  A push that fails leaves the row unstamped, and the next pass tries
  again."
  [eng source census log-fn]
  (let [st (:storage eng)
        rows (store/with-tx st
               (fn [tx] (store/query-rows st tx :ci_run {:state "classified"}
                                          {:limit label-scan-limit})))]
    (reduce
     (fn [census row]
       (let [label (mirror/label-for (str (get-in row [:data :verdict])))
             change-row (row-by-id eng :change (get-in row [:data :change]))]
         (cond
           (nil? label) census

           (nil? change-row)
           (do (log-fn "the classified run " (get-in row [:data :run_id])
                       " names no change this house holds; no label was "
                       "pushed")
               census)

           ;; a change a seat built from an ask has no pull request
           ;; until its submit opens one and the next pass adopts the
           ;; row (bead waymark-fp62.6.3.10). There is nothing at the
           ;; forge to label yet, and a push with no number would
           ;; spend a call to be refused.
           (nil? (get-in change-row [:data :number]))
           (do (log-fn "the classified run " (get-in row [:data :run_id])
                       " names a change with no pull request yet; no "
                       "label was pushed")
               census)

           :else
           (try
             (forge-label! source
                           {:repository (get-in change-row [:data :repository])
                            :number (get-in change-row [:data :number])}
                           label)
             (inv/invoke! eng :ci_run (str (:id row)) :stamp_label
                          {:label label} (as-opts))
             (update census :labelled inc)
             (catch Exception e
               (log-fn "the label " label " did not land on "
                       (get-in change-row [:data :change_id]) " ("
                       (ex-message e) "); the next pass tries again")
               (update census :refused inc))))))
     census
     (filterv unlabelled? rows))))

;; ── the pass ────────────────────────────────────────────────────────

;; A REPOSITORY THE SOURCE CANNOT READ (ticket 116dfb0d). A repository
;; whose pulls listing the token cannot read costs its rows a pass and
;; a log line, every pass, and nothing in the engine says so: its
;; changes are never adopted, have no number, and so the house's merge
;; and the person's merge ask both skip them silently. The pass writes
;; the refusal on the repository's own policy row, where a person reads
;; it, and clears it the first pass that reads the repository again. A
;; note that already says the same status for the same route is left
;; alone, so the log carries one transition when a repository goes dark
;; and one when it comes back.

(defn- source-note-pass!
  [eng refusals answered census log-fn]
  (let [answered (set answered)]
    (reduce
     (fn [census row]
       (let [repo (str (get-in row [:data :repository]))
             stored (str (get-in row [:data :source_note]))
             refusal (get refusals repo)
             input (cond
                     refusal
                     (when-not (str/starts-with?
                                stored
                                (policy/source-note-head (:status refusal)
                                                         (:route refusal)))
                       {:answered (:status refusal) :route (:route refusal)})

                     ;; a base note is the base pass's to clear
                     (and (contains? answered repo) (not (str/blank? stored))
                          (not (base-note? stored)))
                     {})]
         (if (nil? input)
           census
           (try
             (inv/invoke! eng :repo_policy (str (:id row)) :note_source
                          input (as-opts))
             (update census :noted inc)
             (catch Exception e
               (log-fn "the policy of " repo " was refused its source note ("
                       (ex-message e) ")")
               (update census :refused inc))))))
     census
     (bench/policies eng :active))))

;; ── the mirror's own beat (ticket b53f78ac) ─────────────────────────
;;
;; A MIRROR THAT STOPS SAYS SO. The pass stamps `forge_pass_at` on every
;; policy it ran over, so a stamp that stands still is a loop that
;; stopped. The loop cannot say that itself. A second thread, in every
;; process and under no election, reads the oldest stamp and files one
;; draft ticket an outage, where mayor already reads.

(def stale-passes
  "How many cadences a stamp may stand still before the mirror is said
  to have stopped. Four, which is twenty minutes at the default beat."
  4)

(def ^:private beat-title-prefix "The factory mirror stopped beating: ")

(def ^:private last-census
  "The last census line this process printed, and when: what the beat
  ticket quotes."
  (atom nil))

(defn- note-census! [eng line]
  (reset! last-census {:at (now-of eng) :line line})
  line)

(defn- oldest-stamp
  "The oldest `forge_pass_at` among the active policies, with its
  repository, or nil. A policy no pass stamped yet is no evidence."
  [eng]
  (->> (bench/policies eng :active)
       (keep (fn [row]
               (when-some [at (as-instant (get-in row [:data :forge_pass_at]))]
                 {:at at :repo (str (get-in row [:data :repository]))})))
       (sort-by :at)
       first))

(defn- beat-detail [repo ^Instant at ^Instant now every-seconds]
  (let [{:keys [line] said :at} @last-census]
    (cut (str "The factory mirror last stamped `forge_pass_at` at " at
              " on the policy of `" repo "`. That is "
              (.getSeconds (java.time.Duration/between at now))
              " seconds ago, over the " stale-passes " passes of "
              every-seconds " seconds a stamp may stand still. Nothing "
              "is adopted, held or merged while the mirror does not "
              "pass.\n\n"
              (if line
                (str "The last census line this process printed, at "
                     said ":\n\n    " line)
                (str "This process printed no census line: its mirror "
                     "finished no pass since it started."))
              "\n\nRestarting the engine starts the loop again. The "
              "engine files no other ticket while this stamp stands, and "
              "a stamp that moves ends the outage.")
         ticket/detail-chars)))

(defn beat-check!
  "The mirror's health, judged once. `raised` is an atom the watch keeps:
  the stamp its open outage was raised for, or nil.

  → nil with no stamp to read, `:fresh` when the oldest stamp is within
  `stale-passes` cadences (which ends an outage), `:raised` when a stale
  one filed its draft ticket, `:standing` on every later beat of the
  same outage. The ticket rides an idempotency key of the stamp, so two
  processes watching one database file one between them."
  [eng {:keys [every-seconds raised log-fn]
        :or {every-seconds default-every-seconds}}]
  (let [log-fn (or log-fn warn!)
        {:keys [^Instant at repo]} (oldest-stamp eng)
        ^Instant now (now-of eng)
        allowed (* (long stale-passes) (long every-seconds))]
    (cond
      (nil? at) nil

      (not (.isBefore (.plusSeconds at allowed) now))
      (do (when (some? @raised)
            (reset! raised nil)
            (log-fn "the mirror beats again: stamped at " at))
          :fresh)

      (= (str at) @raised) :standing

      :else
      (try
        (inv/create! eng :ticket
                     {:title (cut (str beat-title-prefix "no pass since " at)
                                  200)
                      :detail (beat-detail repo at now every-seconds)
                      :type "bug"
                      :priority 1
                      :repo repo}
                     (assoc (as-opts) :idempotency-key
                            (str "factory-mirror-stopped:" at)))
        (reset! raised (str at))
        (log-fn "the mirror stopped beating: no pass since " at
                ", and a draft ticket says so")
        :raised
        (catch Exception e
          (log-fn "the mirror stopped beating: no pass since " at
                  ", and its ticket was refused (" (ex-message e) ")")
          nil)))))

;; ── the org findings (ticket c4893567) ──────────────────────────────

(def findings-scan-limit
  "How many plans of each state one pass reads for their findings."
  50)

(def findings-doors
  "The findings door each plan state affords. A push to a proposed
  plan's branch still lands, so the plan a person approves is the plan
  as it stands; a push to a filming plan's branch voids its take
  (ticket 4d9311f1)."
  {:drafting :record_findings
   :proposed :record_findings
   :filming :void_take
   :simulated :record_findings})

(defn org-of
  "The org a plan names, as `orgs/<org>` spells it: the last segment of
  its directory, else its name."
  [plan]
  (or (some-> (get-in plan [:data :org_dir]) str
              (str/replace #"/+$" "") (str/split #"/") last not-empty)
      (some-> (get-in plan [:data :name]) str not-empty)))

(defn findings-of
  "The org's findings in the artifact's JSON, `{org [finding …]}`, or
  nil when it names no such org."
  [doc org]
  (when (map? doc)
    (let [found (or (get doc (keyword org)) (get doc org))]
      (when (sequential? found) (vec found)))))

(defn law-page-of
  "The law page the artifact's JSON carries beside the findings, its
  `law_page` (ticket 4ab34d18), or nil when it carries none."
  [doc]
  (when (map? doc)
    (let [page (or (get doc :law_page) (get doc "law_page"))]
      (when (and (string? page) (not (str/blank? page))) page))))

(defn first-week-scene-of
  "The first-week scene the artifact's JSON carries beside the findings,
  its `first_week_scene` as edn text (ticket 4dbb8243), or nil when it
  carries none."
  [doc]
  (when (map? doc)
    (let [scene (or (get doc :first_week_scene) (get doc "first_week_scene"))]
      (when (and (string? scene) (not (str/blank? scene))) scene))))

(defn week-labels-of
  "The org's own labels the artifact's JSON carries beside the findings,
  its `week_labels` as JSON text (ticket 4eda448b), or nil when it
  carries none."
  [doc]
  (when (map? doc)
    (let [labels (or (get doc :week_labels) (get doc "week_labels"))]
      (when (and (string? labels) (not (str/blank? labels))) labels))))

(defn live-of
  "What the artifact's JSON says go_live reads (ticket c8e131fb, M2e):
  conform's `live_flag` for the org's org.edn at that head, and the
  `mayor` its seed seats, as the findings doors take them; {} when it
  says neither, which records the head as not flipped."
  [doc]
  (if-not (map? doc)
    {}
    (let [at #(or (get %1 (keyword %2)) (get %1 %2))
          flag (at doc "live_flag")
          mayor (at doc "mayor")
          budget (when (map? mayor) (at mayor "budget_usd_per_week"))]
      (cond-> {}
        (boolean? flag) (assoc :live_flag flag)
        (and (map? mayor) (some? (at mayor "name")))
        (assoc :mayor (cond-> {:name (str (at mayor "name"))}
                        (some? budget) (assoc :budget_usd_per_week (str budget))))))))

(defn fixture-findings
  "The refusal of a fixture manifest on an org that has an org_plan
  (ticket 911a4c1e): one error finding when the artifact's JSON carries
  conform's `fixture_flag`, so a real org cannot hide its week from the
  bench check; [] otherwise."
  [doc org]
  (if (and (map? doc) (true? (or (get doc :fixture_flag) (get doc "fixture_flag"))))
    [{:key "manifest/fixture" :severity "error" :class "manifest" :subject "org.edn"
      :sentence (str "orgs/" org "/org.edn says :fixture true, but the org has an org_plan: a planned org's week is judged.")
      :remedy "Remove :fixture true from org.edn: only a test-only org under orgs/ is a fixture."}]
    []))

(def ^:private finding-caps
  "org_plan's caps on a finding's text fields (ticket 78f79ec1). A field
  past its cap is cut to one under it and ends in an ellipsis; the key
  is not cut but hashed, by `capped-key`."
  {:sentence 240 :remedy 240 :subject 200 :class 64 :severity 32})

(def ^:private key-cap 64)
(def ^:private findings-cap 200)
(def ^:private law-page-cap 40000)
(def ^:private first-week-scene-cap 65536)
(def ^:private week-labels-cap 65536)

(defn- sha1-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")]
    (apply str (map #(format "%02x" %) (.digest md (.getBytes s "UTF-8"))))))

(defn- clip [s cap]
  (let [s (str s)]
    (if (> (count s) cap) (str (subs s 0 (dec cap)) "…") s)))

(defn- capped-key
  "A key past its cap → its first 55 characters, `~` and the first 8 hex
  of the SHA-1 of the whole key: the same on every pass, and still unique."
  [k]
  (let [k (str k)]
    (if (> (count k) key-cap)
      (str (subs k 0 55) "~" (subs (sha1-hex k) 0 8))
      k)))

(defn- capped-finding [f]
  (if-not (map? f)
    f
    (let [f (into {} (map (fn [[k v]] [(keyword (name k)) v])) f)]
      (reduce-kv (fn [f field cap]
                   (if (string? (get f field)) (update f field clip cap) f))
                 (cond-> f (string? (:key f)) (update :key capped-key))
                 finding-caps))))

(defn capped-findings
  "The artifact's findings fitted to org_plan's caps (ticket 78f79ec1),
  so one over-long field from a lint does not refuse the whole record:
  each finding's fields capped, and past 200 findings the errors first,
  then the rest in artifact order, cut at 200."
  [findings]
  (let [found (mapv capped-finding findings)
        error? #(and (map? %) (= "error" (str (:severity %))))]
    (if (<= (count found) findings-cap)
      found
      (vec (take findings-cap (concat (filter error? found) (remove error? found)))))))

(defn- within-cap
  "`text` when it fits `cap`, else nil with one log line: a page past its
  cap is dropped, not cut."
  [log-fn org field cap text]
  (if (and text (> (count text) cap))
    (do (log-fn "the " field " of " org " is " (count text)
                " characters, past its cap of " cap "; dropped")
        nil)
    text))

(defn- refused-fields
  "The fields a schema refusal names, each with what it said, or \"\"."
  [e]
  (let [errors (:errors (ex-data e))]
    (if (and (map? errors) (seq errors))
      (clip (str "; refused: "
                 (str/join "; " (map (fn [[k v]]
                                       (str (if (keyword? k) (name k) (str k))
                                            " " (pr-str v)))
                                     errors)))
            2000)
      "")))

(defn- void-stale-take!
  "The filming plan whose take is not of `head`, its change's head now →
  `void_take` as the engine's hand (ticket 4d9311f1): the film must be
  of the head the owner approves. A take of `head`, or none, is left
  for the film pass. A door the engine refuses costs that plan one pass.
  → the census, with `:voided` counted."
  [eng plan head census log-fn]
  (let [take-sha (str (get-in plan [:data :take_sha]))]
    (if (or (str/blank? take-sha) (= head take-sha))
      census
      (try
        (inv/invoke! eng :org_plan (str (:id plan)) :void_take
                     {:head_sha head} (as-opts))
        (update census :voided (fnil inc 0))
        (catch Exception e
          (log-fn "the take of " (org-of plan) " at " take-sha
                  " was not voided (" (ex-message e) ")")
          census)))))

(defn- refilm-moved-head!
  "The simulated plan whose take is not of `head`, its change's head now
  → `refilm` as the engine's hand (ticket 87fc7996): a new head is
  filmed again before go_live, no exception. With `org-changed?`, a
  base move changed the org, and the plan's finding says so (ticket
  ce8a4e17). A door the engine refuses costs that plan one pass. → the
  census, with `:refilmed` counted."
  [eng plan head census log-fn & [org-changed?]]
  (try
    (inv/invoke! eng :org_plan (str (:id plan)) :refilm
                 (cond-> {:head_sha head} org-changed? (assoc :org_changed true))
                 (as-opts))
    (update census :refilmed (fnil inc 0))
    (catch Exception e
      (log-fn "the moved head of " (org-of plan) " at " head
              " was not sent to be filmed (" (ex-message e) ")")
      census)))

(defn org-dir-of
  "The directory a plan's org lives in: its `org_dir`, else orgs/ and
  its name."
  [plan]
  (or (some-> (get-in plan [:data :org_dir]) str not-empty)
      (some->> (org-of plan) (str "orgs/"))))

(defn- base-move?
  "Did the house bring `change` forward from its base, from `from` to
  `head`, leaving its own diff alone? The rig's compare_heads answers."
  [eng change from head]
  (boolean
   (and from
        (:carries (held/compared
                   eng {:repo (str (get-in change [:data :repository]))
                        :base (str (get-in change [:data :base_branch]))
                        :approved from :head head})))))

(defn- carry-or-refilm!
  "A plan whose change's head moved off its take, in going_live or in
  simulated under base_follow `when_org_dir_changes` (ticket ce8a4e17).
  A base move that left `org_dir` alone between the take and `head`
  carries the take (`carry_take`, `:carried` counted): no refilm, and a
  going_live plan's yes moves with it. A move that changed it refilms
  the plan, saying so. A going_live head that is no base move is the
  merge pass's `head_moved`; a simulated one refilms. A forge with no
  path compare refilms. → the census."
  [eng source plan change head base-move? census log-fn]
  (let [going? (= :going_live (state-of plan))
        take-sha (some-> (get-in plan [:data :take_sha]) str not-empty)
        from (if going?
               (some-> (get-in plan [:data :approved_head]) str not-empty)
               take-sha)
        repo (some-> (get-in change [:data :repository]) str not-empty)
        refilm! #(refilm-moved-head! eng plan head census log-fn %)]
    (try
      (cond
        (not (base-move? eng change from head))
        (if going? census (refilm! false))

        (or (nil? take-sha) (nil? repo) (not (satisfies? ForgePathDiff source)))
        (refilm! false)

        (forge-path-changed? source repo take-sha head (org-dir-of plan))
        (refilm! true)

        :else
        (do (inv/invoke! eng :org_plan (str (:id plan))
                         :carry_take
                         {:head_sha head} (as-opts))
            (update census :carried (fnil inc 0))))
      (catch Exception e
        (log-fn "the take of " (org-of plan) " at " take-sha
                " was not carried to " head " (" (ex-message e) ")")
        census))))

(defn- carry-or-void!
  "A filming plan whose change's head moved off its take (ticket
  4670d326). A base move that left `org_dir` alone between the take and
  `head` carries the take (`carry_take`, `:carried` counted):
  the film is of the org's files, and those did not change. Any other
  move voids it (`void-stale-take!`), and so does every move under
  base_follow `always`, the strict head equality. A forge with no path
  compare voids; one that does not answer costs that plan one pass.
  → the census."
  [eng source plan change head base-move? census log-fn]
  (let [take-sha (some-> (get-in plan [:data :take_sha]) str not-empty)
        repo (some-> (get-in change [:data :repository]) str not-empty)]
    (if (or (nil? take-sha) (= head take-sha)
            (= "always" (some-> (get-in plan [:data :base_follow]) name))
            (nil? repo) (not (satisfies? ForgePathDiff source)))
      (void-stale-take! eng plan head census log-fn)
      (try
        (if (and (base-move? eng change take-sha head)
                 (not (forge-path-changed? source repo take-sha head
                                           (org-dir-of plan))))
          (do (inv/invoke! eng :org_plan (str (:id plan)) :carry_take
                           {:head_sha head} (as-opts))
              (update census :carried (fnil inc 0)))
          (void-stale-take! eng plan head census log-fn))
        (catch Exception e
          (log-fn "the take of " (org-of plan) " at " take-sha
                  " was not carried to " head " (" (ex-message e) ")")
          census)))))

(defn findings-pass!
  "Every drafting, proposed or simulated org_plan whose change carries a head its
  findings were not recorded at → the artifact
  `org-findings-<org>-<head sha>` that head's tests.yml run uploaded,
  written through the door the plan's state affords, with the law page
  the same artifact carries (ticket 4ab34d18) and its first-week scene,
  rendered at that head (ticket 4dbb8243). Once per head: a
  plan whose `findings_sha` is its change's head is not read, and an
  artifact not there yet is tried on the next pass. A forge that does
  not answer, or a door the engine refuses, costs that plan one pass and
  nothing else. A filming plan whose change carries a head its take was
  not made at has the take voided, `:voided` counted, unless a base
  move left its org_dir alone (`carry-or-void!`, ticket 4670d326), and its findings
  land on a later pass; a simulated plan's goes back to proposed the
  same way (`refilm`, `:refilmed` counted, ticket 87fc7996). The findings are fitted to the plan's caps first
  (`capped-findings`), and a law page or scene past its cap is dropped
  (ticket 78f79ec1). A going_live plan whose change's head moved off
  its take, and a simulated one under base_follow
  `when_org_dir_changes`, keeps its take across a base move that left
  its org_dir alone and is refilmed otherwise (`carry-or-refilm!`,
  ticket ce8a4e17); `base-move?` in `opts` stands in for the rig's
  compare_heads. → the census, with `:findings` counted."
  ([eng source census log-fn] (findings-pass! eng source census log-fn {}))
  ([eng source census log-fn {:keys [base-move?] :or {base-move? base-move?}}]
  (if-not (satisfies? ForgeArtifact source)
    census
    (let [st (:storage eng)
          plans (store/with-tx st
                  (fn [tx]
                    (into []
                          (mapcat #(store/query-rows st tx :org_plan
                                                     {:state (name %)}
                                                     {:limit findings-scan-limit}))
                          (conj (vec (keys findings-doors)) :going_live))))]
      (reduce
       (fn [census plan]
         (let [door (get findings-doors (state-of plan))
               change (row-by-id eng :change (bench/org-plan-change-id eng plan))
               repo (some-> (get-in change [:data :repository]) str not-empty)
               head (some-> (get-in change [:data :head_sha]) str not-empty)
               org (org-of plan)]
           (cond
             (nil? head) census
             (= :going_live (state-of plan))
             (if (org-plan/take-of? (get-in plan [:data :take_sha]) head)
               census
               (carry-or-refilm! eng source plan change head base-move? census log-fn))
             (nil? door) census
             (= :void_take door)
             (carry-or-void! eng source plan change head base-move? census log-fn)
             (and (= :simulated (state-of plan))
                  (not (org-plan/take-of? (get-in plan [:data :take_sha]) head)))
             (if (= "when_org_dir_changes"
                    (some-> (get-in plan [:data :base_follow]) name))
               (carry-or-refilm! eng source plan change head base-move? census log-fn)
               (refilm-moved-head! eng plan head census log-fn))
             (or (nil? repo) (nil? org)
                 (= head (str (get-in plan [:data :findings_sha]))))
             census
             :else
             (try
               (let [doc (forge-artifact source repo
                                         (str "org-findings-" org "-" head))]
                 (if-some [found (findings-of doc org)]
                   (let [page (within-cap log-fn org "law_page" law-page-cap
                                          (law-page-of doc))
                         scene (within-cap log-fn org "first_week_scene"
                                           first-week-scene-cap
                                           (first-week-scene-of doc))
                         labels (within-cap log-fn org "week_labels" week-labels-cap
                                            (week-labels-of doc))
                         ;; the week report's checks join the findings
                         ;; (ticket 7fba2e9b)
                         wk (week/of-artifact doc)]
                     (inv/invoke! eng :org_plan (str (:id plan)) door
                                  (cond-> (merge {:head_sha head
                                                  :findings (capped-findings
                                                             (-> found
                                                                 (into (:findings wk))
                                                                 (into (fixture-findings doc org))))}
                                                 (live-of doc))
                                    page (assoc :law_page page)
                                    scene (assoc :first_week_scene scene)
                                    labels (assoc :week_labels labels)
                                    (:dry_week wk) (assoc :dry_week (:dry_week wk)))
                                  (as-opts))
                     (update census :findings (fnil inc 0)))
                   census))
               (catch Exception e
                 (log-fn "the findings of " org " at " head
                         " were not recorded (" (ex-message e) ")"
                         (refused-fields e))
                 census)))))
       census
       plans)))))

;; ── the org's film (ticket e552de65) ─────────────────────────────────

(defn- judge-the-take
  "The take `tk` scored against every active film rule: the engine's
  hand reads them all."
  [eng tk]
  (film-rules/judge-take eng tk nil))

(defn film-pass!
  "Every filming org_plan whose director handed in a take not yet
  landed → the film rules' answer over its take report, landed through
  `first-week/land!` as the engine's hand: no fail rule missed attaches
  the film and the plan is simulated; a fail rule missed records its
  findings and the plan drafts again. Once per take: the landing stamps
  `take_landed_at`, and a plan with it is not read. A take whose head
  the plan's change has left is voided instead (ticket 4d9311f1),
  `:voided` counted. A report that does
  not read, or a door the engine refuses, costs that plan one pass and
  nothing else. `judge` is (fn [eng take]) → the answer, the active
  rules unless said. → the census, with `:films` counted."
  ([eng census log-fn] (film-pass! eng census log-fn judge-the-take))
  ([eng census log-fn judge]
   (let [st (:storage eng)
         plans (store/with-tx st
                 (fn [tx]
                   (vec (store/query-rows st tx :org_plan {:state "filming"}
                                          {:limit findings-scan-limit}))))]
     (reduce
      (fn [census plan]
        (let [{:keys [take_film_url take_storyboard_url take_report take_sha take_landed_at]}
              (:data plan)
              head (some-> (row-by-id eng :change (get-in plan [:data :change]))
                           (get-in [:data :head_sha]) str not-empty)]
          (cond
            (or (some? take_landed_at)
                (and (str/blank? (str take_film_url))
                     (str/blank? (str take_storyboard_url)))
                (str/blank? (str take_report))
                (str/blank? (str take_sha)))
            census
            (and (some? head) (not= head (str take_sha)))
            (void-stale-take! eng plan head census log-fn)
            :else
            (try
              (let [scene (get-in plan [:data :first_week_scene])
                    taken (wire/read-json (str take_report))]
                (if-some [stopped (first-week/take-stopped taken)]
                  ;; a take cut short by a failed shot is voided, not
                  ;; judged (ticket d49008cc): the shot was the performer's
                  (do (inv/invoke! eng :org_plan (str (:id plan)) :void_take
                                   {:head_sha (str take_sha) :finding stopped} (as-opts))
                      (update census :voided (fnil inc 0)))
                  (let [tk (-> taken
                               (first-week/marked scene)
                               (first-week/clocked scene))]
                    (first-week/land! eng (str (:id plan))
                                      (judge eng (cond-> (first-week/line-up tk scene)
                                                   (sequential? (get-in plan [:data :dry_week]))
                                                   (assoc :dry_week (get-in plan [:data :dry_week]))))
                                      {:film_url (some-> take_film_url str not-empty)
                                       :storyboard_url (some-> take_storyboard_url str not-empty)
                                       :head_sha (str take_sha)
                                       :warnings (into (first-week/dropped-roles tk scene)
                                                       (first-week/take-differs
                                                        tk (get-in plan [:data :dry_week])))}
                                      (as-opts))
                    (update census :films (fnil inc 0)))))
              (catch Exception e
                (log-fn "the film of " (org-of plan) " at " take_sha
                        " was not landed (" (ex-message e) ")")
                census)))))
      census
      plans))))

(defn- stamp-pass!
  "The pass's own clock on the policy of each repository it ran over,
  with a maintenance write as `base_checked_at` is: a held pass and a
  repository that did not answer are stamped too, so a stamp that
  stands still is a pass that stopped (ticket e674e558)."
  [eng repositories log-fn]
  (let [ran (into #{} (map str) repositories)
        at (now-of eng)]
    (doseq [row (bench/policies eng :active)
            :let [repo (str (get-in row [:data :repository]))]
            :when (contains? ran repo)]
      (try
        (bench/mark-row! eng :repo_policy (str (:id row))
                         {:forge_pass_at at} #{})
        (catch Exception e
          (log-fn "the policy of " repo " was not stamped with the pass ("
                  (ex-message e) ")"))))))

(defn pass!
  "One pass of the factory mirror.

  config:
  :source     the ForgeSource
  :engine     the engine, or
  :engine-ref an atom the wiring fills before the first beat
  :log-fn     where the census line goes (default *err*)

  → the census map. A repository that will not answer costs its own
  rows one pass and holds the cursor; one row the engine refuses is
  counted and skipped. The pass throws only when it has no engine to
  write to, which is a wiring fault and not a bad beat."
  [{:keys [source engine engine-ref log-fn]}]
  (let [eng (or engine (some-> engine-ref deref))
        log-fn (or log-fn warn!)
        read-checks (head-reader source)]
    (when (nil? eng)
      (throw (ex-info "the factory mirror has no engine yet" {})))
    ;; the poll, the rate-limit hold, the source's place, the red runs
    ;; and the census line are the kit's (`factorykit.forge/pass!`,
    ;; tickets 75db2ef8 and efff5d2e); this factory's own steps are handed
    ;; to it as functions. It runs no change pass and no failing pass:
    ;; GitHub's events adopt and move the change rows (plan 85b4462e)
    (kit/pass!
     {:source source
      :census kit/fresh-census
      :log-fn log-fn
      ;; a poll that throws is a pass that ran (ticket b53f78ac): the
      ;; stamp says the loop beats, and the warning says what failed
      :poll-failed (fn [_]
                     (stamp-pass! eng (map #(get-in % [:data :repository])
                                           (bench/policies eng :active))
                                  log-fn))
      :before-changes (fn [census {:keys [refusals answered]}]
                        (source-note-pass! eng refusals answered census
                                           log-fn))
      :runs (run-hand eng read-checks log-fn)
      :after-runs (fn [census _] (label-pass! eng source census log-fn))
      :after-failing
      (fn [census _]
        (let [census (findings-pass! eng source census log-fn)
              census (film-pass! eng census log-fn)
              census (link-pass! eng census log-fn)]
          (sweep-reader-changes! eng log-fn)
          census))
      ;; the base pass runs whatever a pass before it did (ticket
      ;; c4bac627), and the stamp is written whatever the two passes
      ;; above it did (ticket b53f78ac): a pass that threw there still
      ;; beat
      :always (fn [census {:keys [repositories]} held]
                (try
                  (floor-pass! eng
                               (if held
                                 census
                                 (base-pass! eng source census log-fn))
                               log-fn)
                  (finally (stamp-pass! eng repositories log-fn))))
      ;; the census line is the kit's (`factorykit.forge/census-line`);
      ;; the beat ticket quotes the last one
      :note (fn [line] (note-census! eng line))})))

;; ── the cadence ─────────────────────────────────────────────────────

(defn start-passes!
  "The factory mirror's daemon: one `pass!` now and every
  :every-seconds after, on a daemon thread (the inbox source's shape,
  workqueue10). A beat that throws is warned and the NEXT beat still
  runs; nothing in a pass may kill the loop. The wiring owns the
  lifecycle and elects the one holder per database; tests call `pass!`
  directly."
  [config {:keys [every-seconds] :or {every-seconds default-every-seconds}}]
  (let [swept? (atom false)
        ;; an Error is caught as an Exception is (ticket b53f78ac): the
        ;; clock catches only an Exception, and no pass may end the loop
        beat! (fn []
                (when-not @swept?
                  (reset! swept? (try (sweep-at-boot! config)
                                      (catch Throwable e
                                        (warn! "the boot sweep failed ("
                                               (or (ex-message e) (class e))
                                               ")")
                                        true))))
                (try (pass! config)
                     (catch Throwable e
                       (warn! "pass failed ("
                              (or (ex-message e) (class e))
                              "); the stored rows keep serving and "
                              "the next beat still runs"))))
        stop! (clock/every! {:name "factory10-forge"
                             :period-ms (long (* 1000 every-seconds))
                             :first beat!
                             :f beat!})]
    ;; the loop's end is always said
    {:stop (fn []
             (stop!)
             (warn! "the pass loop ended: no pass runs in this "
                    "process until the role is elected again"))}))

(defn start-watch!
  "The watch over the mirror's stamp: one `beat-check!` every
  :every-seconds on a daemon thread of its own, the first one a cadence
  after boot so a pass has had its turn. It runs in every process and
  under no election, because the thread it watches cannot say it
  stopped. `stop-passes!` stops it."
  [eng {:keys [every-seconds] :or {every-seconds default-every-seconds}}]
  (let [watch {:every-seconds every-seconds :raised (atom nil)}]
    {:stop (clock/every! {:name "factory10-forge-watch"
                          :period-ms (long (* 1000 every-seconds))
                          :f #(try (beat-check! eng watch)
                                   (catch Throwable e
                                     (warn! "the beat check failed ("
                                            (or (ex-message e) (class e)) ")")))})}))

(defn stop-passes! [{:keys [stop]}]
  (when stop (stop))
  nil)
