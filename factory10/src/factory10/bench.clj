(ns factory10.bench
  "The half the change row's doors share: how a handler reaches the
  bench rig, how it reads what the rig answered, and how it finds the
  seat and the sitting a commit is stamped with (bead
  waymark-fp62.6.3.2).

  HOW A DOOR REACHES THE BENCH. Through `gate-proxy/rpc-of` — the
  engine's OWN power dispatcher — and PAST `invoke-for`, exactly as
  the thread sources reach their rigs (workqueue10.sources.gate-chat).
  That door judges a CALLER's grant; NO powers entry on the bench row
  names `prepare`, `status`, `submit`, `discard`, `enroll`, `repos` or
  `unenroll`, so no scope can name them, no grant can admit them, and
  `waymark_power` answers every one of them 404: a tool no entry names
  does not exist through that door. The engine's own hand takes the
  other path, `mcp-servers/call!`, which resolves `bench__<tool>` by
  the ROW's name and rides the row's one client — it asks the row and
  not the grant, so it reaches a tool the powers never name. The four
  reading and editing tools are the model's (bench.find, bench.read,
  bench.edit, bench.pull); the other seven are the ENGINE's. What
  submit means is a `repo_policy` row a person restates, never an
  argument a model gives, and which repositories the rig holds is that
  same row (bead waymark-fp62.6.3.8).

  THE `:power` HOOK IS NOT THIS. `(:power ctx)` is the engine's hand
  on a power THE REQUEST'S OWN LEASH ADMITS (inbox_item's research
  door). It answers nil for a tool no powers entry names, which is
  what these four are, so a door that used it would reach nothing. The
  caller here is the engine, and the leash it obeys is the change
  row's own doors.

  WHERE THE CALLER COMES FROM. `(:services ctx)` — the map an
  application wires into its engine, under `:bench-rpc`. Since
  waymark-fp62.10 the bench is an `mcp_server` ROW named `bench`
  (waymark-fp62.6.3.3) and that seam holds the ENGINE'S OWN power
  dispatcher, `gate-proxy/rpc-of`: a `bench__<tool>` name resolves by
  its prefix to that row and rides the row's one client. A test
  scripts a fake rig through the very same seam. Nothing wired is
  nothing reached: `ask` answers nil and a door reads that as a dark
  bench, because a bench with no row is a bench that is not there.

  A REFUSAL IS AN ANSWER. Every tool of the rig answers its refusals
  as data — `{\"refused\": \"<name>\", …}` with isError — and never a
  stack trace. `ask` gives that map back whole, and the doors turn the
  names they know (nothing_to_commit, push_rejected, over_ceiling)
  into refusals of their own with a remedy on them. A rig that does
  not answer at all is nil, and a door that reads nil refuses with the
  sentence that says the bench is dark."
  (:require [clojure.string :as str]
            [factory10.mirror :as mirror]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.schema :as schema]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Duration Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "factory10 bench: " parts))))

;; ── the caller ──────────────────────────────────────────────────────

(defn rpc-of
  "The power dispatcher this ctx reaches the bench row with: the one
  the application wired as `(:services :bench-rpc)`. In a deployment
  that is `gate-proxy/rpc-of` over the engine, so the call resolves
  `bench__<tool>` by its prefix to the `bench` row and forwards
  through that row's ONE client — the same client every other reader
  of that row uses. In a suite it is the fake rig, on the same seam.
  `(fn [method params])`, the shape `gate-proxy/rpc-of` answers; nil
  when nothing is wired."
  [ctx]
  (get-in ctx [:services :bench-rpc]))

;; ── one call ────────────────────────────────────────────────────────

(defn- parsed-part
  "The first text part of a tool result, parsed — the fallback for a
  rig that answers no structuredContent (gate-chat's own reading)."
  [payload]
  (some (fn [part]
          (when-some [t (:text part)]
            (try (let [v (wire/read-json (str t))] (when (map? v) v))
                 (catch Exception _ nil))))
        (:content payload)))

(defn ask
  "One bench tool, called with the engine's own hand → the rig's
  answer as a map, or nil when the rig did not answer.

  A REFUSAL COMES BACK AS A MAP with `:refused` on it. Nothing throws:
  a dark Gate, a rig that faulted, a payload this reader cannot parse
  — each one is nil, and the door above decides what nil means for
  the person in front of it."
  [ctx tool args]
  (when-some [rpc (rpc-of ctx)]
    (try
      (let [payload (rpc "tools/call" {:name (gate/bench-tool tool)
                                       :arguments args})
            result (or (get-in payload [:structuredContent :result])
                       (parsed-part payload))]
        (when (map? result) result))
      (catch Exception e
        (binding [*out* *err*]
          (println "waymark10 bench" (str tool) "failed -" (ex-message e)))
        nil))))

(defn refused
  "The refusal name the rig answered, as a string, or nil."
  [answer]
  (some-> (:refused answer) str not-empty))

;; ── the refusals a door makes of them ───────────────────────────────

(defn refuse!
  "A door's own refusal, thrown: a 409 carrying the sentence and the
  way out.

  409 IS THE STATUS THAT COUNTS. The router counts a refusal on the
  open sitting when it is a 409 (router/wrap-refusals-counted), which
  is what R-8 asks of every refusal here — a guard's and a handler's
  alike. `remedies` are sentences rather than `:kind/action` tokens: a
  guard's remedies name doors of this engine, and the way out of a
  rejected push is a POWER the seat already holds."
  [detail remedies]
  (throw (p/problem :bench-refused 409 "Refused"
                    {:detail detail :remedies (vec remedies)})))

(def dark-detail
  "What a door says when the bench did not answer at all."
  (str "The bench did not answer, so nothing was read and nothing was "
       "written. The worktree stands as it was."))

(def dark-remedy
  (str "Wait for the next sitting and try again; if the bench stays "
       "dark, say so in a finding and let a person look at the rig."))

;; ── the policy ──────────────────────────────────────────────────────

(def default-base "main")
(def default-branch-pattern "waymark/*")
(def default-max-lines 400)
(def default-rounds 3)

(defn policy-of
  "The active `repo_policy` row for this change's repository, or nil.

  The ctx `:find` hook is the read (invoke.clj's cross-resource hook,
  the same transaction as the write). A ctx that carries no hook — the
  render probe — answers nil, and a guard that reads this must then
  ALLOW rather than guess: an envelope that advertises optimistically
  is the framework's own posture, and the door itself judges again
  with a real hook behind it."
  [row ctx]
  (when-some [find' (:find ctx)]
    (when-some [repo (some-> (get-in row [:data :repository]) str not-empty)]
      (first (find' :repo_policy {:repository repo :state :active}
                    {:limit 1})))))

(defn base-of [policy]
  (or (some-> (get-in policy [:data :base]) str not-empty) default-base))

(defn pattern-of [policy]
  (or (some-> (get-in policy [:data :branch_pattern]) str not-empty)
      default-branch-pattern))

(defn max-lines-of [policy]
  (long (or (get-in policy [:data :max_lines]) default-max-lines)))

(defn rounds-of [policy]
  (long (or (get-in policy [:data :rounds_per_change]) default-rounds)))

(defn merge-strategy-of
  "How the house merges (ticket 394d0602): \"line\" or \"train\". A row
  that predates the field reads as \"line\", today's one-at-a-time line."
  [policy]
  (or (some-> (get-in policy [:data :merge_strategy]) str not-empty) "line"))

(defn train-size-of
  "How many changes ride one merge train (ticket 394d0602), read only
  when the strategy is train. A row that predates the field reads as 4."
  [policy]
  (long (or (get-in policy [:data :train_size]) 4)))

(defn test-workflow-of
  "The workflow the policy's test block names (ticket 90ce5c73), nil
  when it names none: a train's checks dispatch it and its status reads
  it, so a rig whose default workflow differs reads the right run."
  [policy]
  (some-> (get-in policy [:data :test :workflow]) str not-empty))

(defn house-merges?
  "Does this policy say the house merges a green change (ticket
  4dfb00f6)? Only `merge_by: house`; an absent field is GitHub's."
  [policy]
  (= "house" (some-> (get-in policy [:data :merge_by]) str)))

(defn required-checks-of [policy]
  (into [] (comp (map str) (remove str/blank?))
        (get-in policy [:data :required_checks])))

(defn merge-method-of [policy]
  (or (some-> (get-in policy [:data :merge_method]) str not-empty) "merge"))

(defn branch-of
  "The branch this change is worked on: the one the row names, else
  the policy's pattern with the change's own id in place of the `*`.
  One branch for each change, so a second sitting finds the worktree
  the first one left. The sit computes the same branch the same way
  (server/mcp `bench-branch`), and a change that has been prepared
  once carries it in `branch` from then on."
  [row policy]
  (or (some-> (get-in row [:data :branch]) str not-empty)
      (some-> (get-in row [:data :head_branch]) str not-empty)
      (str/replace (pattern-of policy) "*" (str (:id row)))))

(defn matches-pattern?
  "Does this branch match the policy's pattern? The pattern is a glob
  with one `*`, which stands for any text with no `/` at the start of
  it — `waymark/*` matches `waymark/fp62.6.3` and does not match
  `main`."
  [branch pattern]
  (let [parts (mapv #(java.util.regex.Pattern/quote %)
                    (str/split (str pattern) #"\*" -1))
        re (re-pattern (str "\\A" (str/join ".*" parts) "\\z"))]
    (boolean (re-matches re (str branch)))))

;; ── the enrolment (bead waymark-fp62.6.3.8) ─────────────────────────
;;
;; ONE SENTENCE ABOUT A REPOSITORY. Adding a repository to the house
;; was seven hand steps in four places. The owner's ruling: the
;; repo_policy row IS the sentence, a person writes it, and the engine
;; tells the rig and the GitHub source. These functions are the
;; engine's half of that — the row's own doors call them, and the
;; retry pass below calls the same ones, so the create, the restate,
;; the restore and the retry cannot say different things to the rig.

(def github-base
  "Where a repository named as owner/repo lives when the row names no
  other clone URL."
  "https://github.com/")

(defn clone-url-of
  "Where the rig clones this repository from: the row's own
  `clone_url` when a person wrote one, else GitHub's own address for
  the repository (R-1)."
  [row]
  (or (some-> (get-in row [:data :clone_url]) str not-empty)
      (str github-base (str (get-in row [:data :repository])))))

(def not-enrolled-prefix
  "What the row says when the bench does not hold this repository.
  The sentence names the reason, because the next hand to read it is a
  person deciding whether the rig or the URL is wrong."
  "The bench has not enrolled this repository: ")

(def not-unenrolled-prefix
  "…and what it says when the bench would not let it go."
  "The bench has not unenrolled this repository: ")

(defn- reason-of
  "Why the rig did not do it, in one clause: the refusal's own reason,
  else the refusal's name, else the sentence for a rig that said
  nothing at all."
  [answer]
  (or (some-> (:reason answer) str not-empty)
      (refused answer)
      "the bench did not answer"))

(defn- took-it?
  "Did the rig answer, and answer with something other than a refusal?"
  [answer]
  (and (map? answer) (nil? (refused answer))))

(defn land-of
  "The rig's `land` block for this repository, from the row (bead
  waymark-fp62.6.3.9). WITHOUT IT THE RIG ONLY PUSHES: it opens no
  pull request, and `feedback` answers that there is no pull_request
  block. The row already says what submit means here, so the block is
  a reading of the row and never an argument a model gives.

  `target` is the policy's base — the branch a submit lands on.
  `rebase` is FALSE because a seat may work on a person's own pull
  request branch (the change's `head_branch`), and a rebase there
  rewrites a person's history. `stages` is empty in this bead: the
  policy's formatter does not ride yet. `pull_request` is a block
  unless the policy says `opens_pr` false, and a block with no forge
  named means the rig reads the forge from the clone URL.

  `auto_merge` in that block is the policy's own (bead
  waymark-fp62.6.3.15). TRUE AND THE RIG TURNS AUTO-MERGE ON for the
  pull request it opened, so the gate merges the change when the
  checks are green and no person taps. A repository whose branch rules
  refuse auto-merge is a finding in `feedback`, and the pull request
  stands.

  `merge_by` rides the block only when the policy says `house` (ticket
  4dfb00f6): the rig then arms no GitHub auto-merge, and the engine's
  merge pass below asks the rig to merge each green change instead."
  [row]
  (cond-> {:target (base-of row)
           :rebase false
           :stages []}
    (not (false? (get-in row [:data :opens_pr])))
    (assoc :pull_request
           (cond-> {:auto_merge (boolean (get-in row [:data :auto_merge]))}
             (house-merges? row) (assoc :merge_by "house")))))

(defn enrol-args
  "What the rig's `enroll` is told about this repository: the name it
  holds the clone under, where to clone it from, which branch a
  worktree starts from, the paths it never serves, and what a submit
  lands. The deny list is the row's, so the rig and the row hold one
  list; the land block is the row's too, and a restate sends it again
  so the rig replaces its entry.

  `test` rides only when the row has one (ticket bae401d5): the
  workflow the bench's `test` dispatches and the input that narrows
  it. A row without one sends no key, so a rig that does not know the
  key still takes it. `select_pattern` rides inside it the same way
  (ticket efa54182): only when the row states one, so the rig keeps
  its own Clojure default otherwise. `check` rides the same way
  (ticket 856e825c): the command the bench's check runs after its lint,
  sent only when the row states one. `hosted_workflows` rides the same
  way (ticket 0de73a7a): the workflow files a person allowed on
  GitHub-hosted runners, sent only when the row names at least one."
  [row]
  (let [test-block (get-in row [:data :test])
        check-block (get-in row [:data :check])
        pattern (:select_pattern test-block)
        hosted (seq (get-in row [:data :hosted_workflows]))]
    (cond-> {:repo (str (get-in row [:data :repository]))
             :clone_url (clone-url-of row)
             :default_branch (base-of row)
             :deny (vec (get-in row [:data :deny]))
             :land (land-of row)}
      (some? test-block) (assoc :test (cond-> (select-keys test-block [:workflow :input])
                                        (not (str/blank? (str pattern)))
                                        (assoc :select_pattern (str pattern))))
      (some? check-block) (assoc :check (select-keys check-block [:command :timeout]))
      hosted (assoc :hosted_workflows (vec hosted)))))

(defn enrolled
  "The row after the engine offered this repository to the rig (R-2).

  THE ROW WRITES WHETHER THE RIG ANSWERS OR NOT. A rig that took the
  repository stamps `enrolled_at` and clears the note; a rig that
  refused, or that said nothing, leaves `enrolled_at` empty and writes
  why, and the retry pass offers the row again. Nothing throws: the
  door above is a person's create or restate, and a dark bench must
  not refuse a person's own sentence about a repository."
  [row ctx]
  (try
    (let [answer (ask ctx :enroll (enrol-args row))]
      (if (took-it? answer)
        (-> row
            (assoc-in [:data :enrolled_at] (:now ctx))
            (assoc-in [:data :note] nil))
        (-> row
            (assoc-in [:data :enrolled_at] nil)
            (assoc-in [:data :note]
                      (str not-enrolled-prefix (reason-of answer))))))
    (catch Exception e
      (warn! "the enrolment of " (get-in row [:data :repository])
             " failed (" (ex-message e) "); the row stands and the retry "
             "offers it again")
      (-> row
          (assoc-in [:data :enrolled_at] nil)
          (assoc-in [:data :note] (str not-enrolled-prefix (ex-message e)))))))

(defn unenrolled
  "The row after the engine told the rig to stop holding this
  repository (R-3). A refusal is noted and never raised: a person who
  retires a policy has retired it, whatever the rig says."
  [row ctx]
  (try
    (let [answer (ask ctx :unenroll
                      {:repo (str (get-in row [:data :repository]))})]
      (if (took-it? answer)
        (-> row
            (assoc-in [:data :enrolled_at] nil)
            (assoc-in [:data :note] nil))
        (assoc-in row [:data :note]
                  (str not-unenrolled-prefix (reason-of answer)))))
    (catch Exception e
      (assoc-in row [:data :note]
                (str not-unenrolled-prefix (ex-message e))))))

;; ── the rows, read by the engine's own passes ───────────────────────

(def enrol-actor
  "The system hand the retry pass writes with: the engine's own actor,
  not a person and not a model. It is what the hidden `mark_enrolled`
  door admits."
  (t/principal {:id "factory10-bench" :type :system
                :display "The bench"}))

(defn- rdef-of [eng] (get (inv/resources eng) :repo_policy))

(defn policies
  "Every `repo_policy` row of this engine in one state, decoded — and
  an empty vector in an engine that declares no policy kind at all,
  which is every engine but the factory's."
  [eng state]
  (if-some [rd (rdef-of eng)]
    (let [st (:storage eng)]
      (mapv #(inv/decode-row rd %)
            (store/with-tx st
              (fn [tx] (store/query-rows st tx :repo_policy {:state state}
                                         {:limit 1000})))))
    []))

(defn active-repositories
  "The repositories the house works now: one for each active policy row
  (R-5). The GitHub source reads this at EVERY pass, so a policy a
  person retires stops being polled with no deploy and no environment
  variable."
  [eng]
  (into [] (keep #(some-> (get-in % [:data :repository]) str not-empty))
        (policies eng :active)))

(defn enroll-unenrolled!
  "One retry pass (R-4): every active policy the bench does not hold
  yet, offered to the rig again. A rig that takes one stamps the row
  through its own hidden door, so the transition log carries the
  enrolment; a rig that refuses leaves the row as it was, and the next
  pass tries again. Idempotent — a row with `enrolled_at` on it is not
  offered at all — and it throws nothing: a pass is a beat, not a
  request.

  It lives HERE and not in the discover sweep it rides beside: that
  sweep is core's (server/mcp_servers), the policy kind is factory10's,
  and core does not read a module's kinds."
  [eng]
  (let [ctx {:services (:services eng)}]
    (doseq [row (policies eng :active)
            :when (str/blank? (str (get-in row [:data :enrolled_at])))]
      (try
        (let [answer (ask ctx :enroll (enrol-args row))]
          (when (took-it? answer)
            (inv/invoke! eng :repo_policy (str (:id row)) :mark_enrolled
                         {:bare (some-> (:bare answer) str not-empty)}
                         {:principal enrol-actor})))
        (catch Exception e
          (warn! "the enrolment of " (get-in row [:data :repository])
                 " did not land (" (ex-message e) "); the next pass tries "
                 "again"))))))

;; ── the house's merge (ticket 4dfb00f6) ─────────────────────────────────
;;
;; GitHub's auto-merge needs a public repository or a paid plan, so a
;; private repository on a free plan never arms it and every green
;; change waits for a person. A policy that says `merge_by: house`
;; hands that merge to the engine: at every pass, each submitted change
;; with a number and a head is offered to the rig's `merge`, which
;; merges only when the policy's required checks are green on that
;; head. The mirror then moves the change to `merged`, as it does for
;; any merge, and that completes the ticket.

(def missing-power-refusals
  "The refusals that say the rig has no `merge` yet (ticket d7cf3f9c in
  ckopsa/waymark-bench). The pass logs them and asks again next time,
  rather than remembering the head as refused."
  #{"unknown_tool" "no_such_tool" "not_found"})

(defn- rdef-of-kind [eng kind] (get (inv/resources eng) kind))

(defn- submitted-changes
  "Every `change` row in `submitted`, decoded — empty in an engine that
  declares no change kind."
  [eng]
  (if-some [rd (rdef-of-kind eng :change)]
    (let [st (:storage eng)]
      (mapv #(inv/decode-row rd %)
            (store/with-tx st
              (fn [tx] (store/query-rows st tx :change {:state :submitted}
                                         {:limit 1000})))))
    []))

(defn- policies-by-repo
  "The active policies, by the repository each one names."
  [eng]
  (into {}
        (keep (fn [p]
                (when-some [repo (some-> (get-in p [:data :repository])
                                         str not-empty)]
                  [repo p])))
        (policies eng :active)))

(defn house-pass-merges?
  "Does the house's merge pass merge this repository's green changes?
  Only when the policy says `auto_merge`, `merge_by: house` and names
  at least one required check. Every other policy leaves the merge to
  somebody else, and a green change there waits on its person."
  [policy]
  (boolean (and (not (false? (get-in policy [:data :auto_merge])))
                (house-merges? policy)
                (seq (required-checks-of policy)))))

(defn person-merges-on-github?
  "Is this a repository nobody here can merge (ticket 60962bcf)? The
  house pass does not merge it, and its policy names no required check,
  so the rig would refuse a merge ask's call (`no_required_checks`: it
  never merges a change nothing has tested). Its person merges it on
  GitHub, and no merge ask is raised."
  [policy]
  (boolean (and policy
                (not (house-pass-merges? policy))
                (empty? (required-checks-of policy)))))

(defn person-marks
  "The mark of every change a person merges on GitHub
  (`person-merges-on-github?`): change id → {:line_why \"person\"}."
  [changes by-repo]
  (into {}
        (keep (fn [c]
                (when (person-merges-on-github?
                       (get by-repo (str (get-in c [:data :repository]))))
                  [(str (:id c)) {:line_why "person"}])))
        changes))

(defn merge-args
  "What the rig's `merge` is told: the pull request, the head it may
  merge and nothing else, and the policy's checks and method."
  [change policy]
  {:repo (str (get-in change [:data :repository]))
   :number (get-in change [:data :number])
   :head_sha (str (get-in change [:data :head_sha]))
   :required_checks (required-checks-of policy)
   :merge_method (merge-method-of policy)})

(def default-deploy-wait-seconds
  "How long the line waits on a deploy that does not report, when the
  policy names no `deploy_wait_seconds` (ticket 47217098)."
  1800)

(def default-queued-check-minutes
  "How long a required check may sit queued before the wait itself is
  the change's red, when the policy names no `queued_check_minutes`
  (ticket bc3ff12c)."
  30)

(defn queued-check-minutes-of [policy]
  (long (or (get-in policy [:data :queued_check_minutes])
            default-queued-check-minutes)))

(defn deploy-check-of
  "The check on the base whose success means a commit is deployed, or
  nil when a merge counts as deployed."
  [policy]
  (some-> (get-in policy [:data :deploy_check]) str str/trim not-empty))

(defn deploy-wait-of [policy]
  (long (or (get-in policy [:data :deploy_wait_seconds])
            default-deploy-wait-seconds)))

;; A BRANCH BEHIND ITS BASE (ticket a95c3d63). Main's protection wants a
;; branch up to date before it merges, so a green change whose branch
;; fell behind is refused by GitHub and would wait forever. The pass
;; asks the rig's `update_branch` to bring it forward instead: the new
;; head re-runs CI, and a later pass merges it when green. One update
;; per head, so a busy main cannot make the pass loop; a conflicted
;; branch is left to the failing path (ticket 5f12e772).

(def not-mergeable-refusal
  "The merge refusal that means GitHub found a conflict between the
  branch and its base."
  "not_mergeable")

(def parked-refusals
  "The merge refusals a new pass cannot fix: the head is remembered and
  not offered again. Any other refusal — `head_moved` among them — is
  asked again next pass, and a new head is a new offer anyway."
  #{"draft" "no_required_checks" not-mergeable-refusal})

(def ^:private behind-reason
  "What GitHub says when it refuses a merge because the branch is not
  up to date with its base, or a required status is still expected."
  #"(?i)out of date|not up to date|up-to-date|status checks? .*(is|are) expected")

(defn behind?
  "Does the rig's merge answer say the branch is behind its base?"
  [answer]
  (or (= "behind" (some-> (:state answer) str))
      (and (= "merge_refused" (refused answer))
           (boolean (re-find behind-reason (str (:reason answer)))))))

(defn- conflicted-change? [change]
  (= "conflicted" (str (get-in change [:data :mergeable]))))

(defn update-args
  "What the rig's `update_branch` is told: the pull request and the head
  it may bring forward."
  [change]
  {:repo (str (get-in change [:data :repository]))
   :number (get-in change [:data :number])
   :head_sha (str (get-in change [:data :head_sha]))})

(defn- update-behind!
  "Bring one behind change's branch up to date, once for this head.
  `seen` remembers the head under `[:updated id]`; a missing power or
  no answer is not remembered, so the next pass asks again."
  [ctx seen change id head]
  (cond
    (conflicted-change? change)
    (warn! id " is behind and conflicted; the failing path has it")

    (true? (get-in change [:data :draft]))
    (warn! id " is behind but a draft; it is not brought forward")

    (= head (get @seen [:updated id]))
    nil

    :else
    (let [answer (ask ctx :update_branch (update-args change))
          why (refused answer)]
      (cond
        (nil? answer)
        (warn! "the update of " id " had no answer; the next pass asks again")

        (contains? missing-power-refusals why)
        (warn! "the rig has no update_branch yet (" why
               "); the next pass asks again")

        :else
        (do (swap! seen assoc [:updated id] head)
            (when why
              (warn! "the rig refused to update " id " at " head " ("
                     (reason-of answer) ")")))))))

;; ONE CHANGE PER REPOSITORY AT A TIME (ticket d82d649a). Bringing every
;; behind change up to date in one pass starts one CI run each, and the
;; first merge makes all the others stale again. So each repository's
;; submitted changes stand in ONE LINE — ticket priority first (lower
;; number first), then the change's birth, then its id — and only the
;; FRONT is brought up to date. Every change still gets its one merge
;; call a pass, because a merge costs no CI: one that is green and up to
;; date merges at once, whatever its place. A behind change that is not
;; the front is left alone until it is. The front leaves the line when
;; it merges or goes red (either way it is no longer submitted) or when
;; its head is parked; the line is rebuilt from the rows each pass, so
;; a restart loses only the memory of the head already updated.

(defn born-ticket
  "The id of the ticket this change was born from, or nil."
  [change]
  (let [born (str (get-in change [:data :born_from]))]
    (when (str/starts-with? born "ticket:")
      (not-empty (subs born (count "ticket:"))))))

(defn- ticket-priorities
  "Ticket id → its priority, for the tickets these changes were born
  from — empty in an engine that declares no ticket kind."
  [eng changes]
  (if-some [rd (rdef-of-kind eng :ticket)]
    (let [st (:storage eng)]
      (into {}
            (keep (fn [tid]
                    (when-some [raw (try (store/with-tx st
                                           (fn [tx] (store/load-row st tx :ticket tid {})))
                                         (catch Exception _ nil))]
                      [tid (get-in (inv/decode-row rd raw) [:data :priority])])))
            (distinct (keep born-ticket changes))))
    {}))

(defn line-key
  "Where a change stands in its repository's line: its ticket's
  priority (none is last), then when the change was born, then its id."
  [priority-of change]
  (let [prio (priority-of change)
        born (:created-at change)]
    [(if (number? prio) (long prio) Long/MAX_VALUE)
     (if (inst? born) (inst-ms born) Long/MAX_VALUE)
     (str (:id change))]))

(defn merge-lines
  "The changes the house pass offers, as repository → its line in the
  order it is worked. A change needs a number, a head that `seen` has
  not parked, and a policy `house-pass-merges?`. `priority-of` is
  change → its ticket's priority, or nil."
  [changes by-repo priority-of seen]
  (->> changes
       (filter (fn [change]
                 (let [head (some-> (get-in change [:data :head_sha]) str not-empty)
                       policy (get by-repo (str (get-in change [:data :repository])))]
                   (and (get-in change [:data :number]) head policy
                        (house-pass-merges? policy)
                        (not= head (get seen (str (:id change))))))))
       (group-by #(str (get-in % [:data :repository])))
       (into (sorted-map)
             (map (fn [[repo line]]
                    [repo (vec (sort-by #(line-key priority-of %) line))])))))

(defn- answer-state
  "The state word a rig's merge answer carries, or nil."
  [answer]
  (some-> (:state answer) name not-empty))

(defn head-answer
  "The rig's merge answer as the line reads it (ticket baf76388). The
  forge mirror stamps `green_head` when it reads every required check
  green on a submitted change's head, so a rig's `red` for that very head
  is the failing round's, carried over: it reads as `behind`, and the
  change stands in the line and may ride a train. A head that moved since
  the green read keeps the rig's red until its own checks are read.
  A rig's `waiting` for that very head, when the mirror also read it
  behind its base (`behind_base`), waits on no check: it reads as
  `behind` too, so the front is brought up to date or rides a train
  (ticket fc997a92)."
  [change answer]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)
        state (answer-state answer)]
    (if (and head
             (= head (some-> (get-in change [:data :green_head]) str))
             (or (= "red" state)
                 (and (= "waiting" state)
                      (true? (get-in change [:data :behind_base])))))
      (assoc answer :state "behind")
      answer)))

(defn out-of-line
  "Why a change of a line does not stand in it this pass, or nil when it
  does. A conflicted branch is the failing path's, a draft is not
  brought forward, a red head waits on its seat, and a head the rig
  refused for good (`seen`) is parked. A change the rig just merged has
  left, and says `merged`. `answer` is what the rig's merge said of it
  this pass, or nil."
  [change answer seen]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (cond
      (conflicted-change? change) "conflicted"
      (true? (get-in change [:data :draft])) "draft"
      (= "red" (answer-state answer)) "red"
      (and head (= head (get seen (str (:id change))))) "parked"
      (= "merged" (answer-state answer)) "merged")))

(defn front-of
  "The change at the front of a line: the first one that stands in it
  (`out-of-line`). `answers` is change id → the rig's merge answer this
  pass and `seen` the parked heads; with neither, only a conflicted
  branch or a draft steps out of the line."
  ([line] (front-of line {} {}))
  ([line answers seen]
   (first (remove #(out-of-line % (get answers (str (:id %))) seen) line))))

(defn- offer-merge!
  "One merge call for one change, with the engine's own hand → the rig's
  answer, or nil. A refusal a new pass cannot fix parks the head in
  `seen`, with its reason under `[:parked-why id]` and the refusal's
  name under `[:parked-as id]`."
  [ctx seen change policy]
  (let [id (str (:id change))
        head (str (get-in change [:data :head_sha]))]
    (try
      (let [answer (ask ctx :merge (merge-args change policy))
            why (refused answer)]
        (cond
          (nil? answer)
          (warn! "the merge of " id " had no answer; the next pass asks again")

          (contains? missing-power-refusals why)
          (warn! "the rig has no merge yet (" why "); the next pass asks again")

          (behind? answer)
          nil

          (contains? parked-refusals why)
          (do (swap! seen assoc id head [:parked-why id] (reason-of answer)
                     [:parked-as id] why)
              (warn! "the rig refused to merge " id " at " head " ("
                     (reason-of answer) "); this head is not asked again"))

          why
          (warn! "the rig refused to merge " id " at " head " ("
                 (reason-of answer) "); the next pass asks again")

          :else nil)
        answer)
      (catch Exception e
        (warn! "the merge of " id " failed (" (ex-message e)
               "); the next pass asks again")
        nil))))

(defn stale-waiting?
  "Does a front wait on a required check its head will never run while
  its base has moved (ticket 498a089e)? The rig answers `waiting`, and
  the forge pass wrote `missing_checks` (checks with no run at all, not
  merely running) and `behind_base` on the row, read at the head it
  names in `missing_checks_head`: facts read at an older head than the
  row's `head_sha` are not trusted (ticket 716d12ba). Bringing the
  branch up to date makes CI run on the new head."
  [change answer]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (boolean
     (and (= "waiting" (answer-state answer))
          head
          (= head (some-> (get-in change [:data :missing_checks_head]) str))
          (seq (get-in change [:data :missing_checks]))
          (true? (get-in change [:data :behind_base]))))))

;; THE MERGE TRAIN (ticket 47519515, slice 2 of 3deb06ed). With
;; `merge_strategy: train` the front and up to train_size-1 changes
;; behind it that are green on their own heads ride ONE branch,
;; `train/<repo>/<the front's number>`: the rig merges each pull
;; request's head onto the base's head (`train_build`) and the branch is
;; tested once: by its own pull request's run when the rig opens one
;; (`train_open`, ticket e2d485c2), else by a dispatched run
;; (`train_checks`). The train stands on the policy
;; (`line_train`), so a restart finds it, and each pass reads its run
;; once (`train_status`). A standing train finishes whatever the
;; strategy says now, so a restate back to `line` waits for it. What a
;; finished train does is `train-finished!`'s.

(defn train-branch
  "The branch a repository's train rides, named for its front."
  [repo front]
  (str "train/" repo "/" (get-in front [:data :number])))

(defn- green-behind?
  "Does the rig's merge answer say the change is green on its own head
  and only behind its base?"
  [answer]
  (= "behind" (answer-state answer)))

(defn train-riders
  "The changes one train would carry: the front and up to train_size-1
  changes behind it in line order, each standing in the line
  (`out-of-line`) and green on its own head. `line` already leaves out
  a change whose ticket's merge_after is not met (`held-changes`).
  Empty when the front is not green on its own head."
  [line answers seen policy]
  (let [answer-of #(get answers (str (:id %)))
        in (remove #(out-of-line % (answer-of %) seen) line)]
    (if (and (seq in) (green-behind? (answer-of (first in))))
      (vec (take (train-size-of policy) (filter #(green-behind? (answer-of %)) in)))
      [])))

(defn- pass-now [ctx]
  (if-some [f (:now-fn ctx)] (f) (Instant/now)))

(defn- number-of [change] (get-in change [:data :number]))

(defn- dispatch-checks!
  "Dispatch a built train's checks → the train with its `run_id`, nil
  when no run showed yet (`train_status` then reads it by branch)."
  [ctx repo train]
  (let [answer (ask ctx :train_checks
                    (cond-> {:repo repo :branch (:branch train)}
                      (:workflow train) (assoc :workflow (:workflow train))))]
    (when (refused answer)
      (warn! "the rig refused the checks of " (:branch train) " ("
             (reason-of answer) ")"))
    (assoc train :run_id (some-> (:run_id answer) str not-empty))))

(def pr-run-grace-seconds
  "How long a train whose pull request opened waits for that pull
  request's own run to show before its checks are dispatched instead: a
  repository whose pull requests run no CI still gets its train tested."
  600)

(defn- open-train!
  "Open a built train's pull request (`train_open`, ticket e2d485c2) →
  the train with its `:pr` and `:pr_run`: that pull request's own run
  is the train's check, read by branch and head, and no second run is
  dispatched. A rig with no `train_open`, or one that refused or did
  not answer, gets the checks dispatched as before (`dispatch-checks!`)."
  [ctx repo policy train]
  (let [answer (ask ctx :train_open {:repo repo :base (base-of policy)
                                     :branch (:branch train)
                                     :head (:head train)})
        why (refused answer)
        n (:number answer)]
    (if (and (nil? why) (pos-int? n))
      (assoc train :pr n :pr_run true)
      (do (when (and why (not (contains? missing-power-refusals why)))
            (warn! "the rig refused to open the pull request of " (:branch train)
                   " (" (reason-of answer) "); its checks are dispatched"))
          (dispatch-checks! ctx repo train)))))

(defn- pr-run-overdue?
  "Has a train tested by its pull request's run waited
  `pr-run-grace-seconds` since it was built with no run showing?"
  [ctx train]
  (let [started (:started_at train)]
    (boolean
     (and (:pr_run train) (nil? (some-> (:run_id train) str not-empty))
          (instance? Instant started)
          (not (.isBefore ^Instant (pass-now ctx)
                          (.plusSeconds ^Instant started (long pr-run-grace-seconds))))))))

(defn build-train!
  "Build one train of `riders` with the rig and open its pull request
  (`open-train!`), or dispatch its checks when the rig opens none →
  the train as `line_train` records it, or nil when the rig built none
  and the line goes one at a time this pass. A rider whose pull request
  did not merge cleanly stays in the line untouched."
  [ctx repo policy riders]
  (let [branch (train-branch repo (first riders))
        answer (ask ctx :train_build {:repo repo :base (base-of policy)
                                      :branch branch
                                      :prs (mapv number-of riders)})
        merged (set (:merged answer))]
    (cond
      (nil? answer)
      (warn! "the train " branch " had no answer; the line goes one at a time")

      (refused answer)
      (warn! "the rig refused the train " branch " (" (reason-of answer)
             "); the line goes one at a time")

      (empty? merged)
      (do (ask ctx :train_delete {:repo repo :branch branch})
          (warn! "no pull request of " branch
                 " merged cleanly; the line goes one at a time"))

      :else
      (let [rode (filterv #(merged (number-of %)) riders)]
        (open-train! ctx repo policy
                          (cond-> {:branch branch
                                   :changes (mapv #(str (:id %)) rode)
                                   :prs (mapv number-of rode)
                                   :head (some-> (:head answer) str)
                                   :base_head (some-> (:base_head answer) str)
                                   :started_at (pass-now ctx)}
                            (test-workflow-of policy)
                            (assoc :workflow (test-workflow-of policy))))))))

(defn- one-at-a-time!
  "Delete a train and send its repository one at a time for the rest of
  this process (`seen` keeps that under `[:train-done repo]`), so no
  train is built only to be thrown away again. → nil."
  [ctx seen repo train why]
  (warn! repo ": the train " (:branch train) " " why
         "; it is deleted and the line goes one at a time")
  (ask ctx :train_delete {:repo repo :branch (:branch train)})
  (swap! seen assoc [:train-done repo] true)
  nil)

(defn- land-train!
  "Fast-forward the base to a green train's head → the train that stands
  after it. Landed, every change it carried is merged: its answer this
  pass says so (`:answers` in `ctx`), so the pass writes those merges as
  it writes a house merge's (`note-merges!`), and the mirror ends each
  change and its ticket as it does any merge. A base that moved outside
  the house (`base_moved`) throws the train away and the next pass
  builds another; a rig that does not answer is asked again next pass.
  Only `landed: true` is a landing: an answer neither landed nor refused
  (`state: waiting`, while GitHub computes mergeability or a check is
  pending) keeps the train standing, its pull request noted as `:pr`,
  and the next pass asks again (ticket c3f0f094)."
  [ctx seen repo policy train]
  (let [answer (ask ctx :train_land {:repo repo :base (base-of policy)
                                      :branch (:branch train)
                                      :expect_base_head (:base_head train)
                                      :head (:head train)})
        why (refused answer)]
    (cond
      (or (nil? answer) (contains? missing-power-refusals why)) train

      (= "base_moved" why)
      (do (warn! repo ": the base moved under the train " (:branch train)
                 "; it is deleted and the next pass builds another")
          (ask ctx :train_delete {:repo repo :branch (:branch train)})
          nil)

      why
      (one-at-a-time! ctx seen repo train
                      (str "was refused its landing (" (reason-of answer) ")"))

      (true? (:landed answer))
      (do (when-some [answers (:answers ctx)]
            (swap! answers into
                   (map (fn [id] [id {:state "merged" :head (:head train)}]))
                   (:changes train)))
          (ask ctx :train_delete {:repo repo :branch (:branch train)})
          nil)

      :else
      (let [n (:number answer)]
        (if (pos-int? n) (assoc train :pr n) train)))))

(defn- train-cap
  "How many trains one train of `n` changes may run, its own and the
  halves that look for its red: log2(n)+1."
  [n]
  (inc (long (Math/ceil (/ (Math/log (double (max 1 (long n))))
                           (Math/log 2.0))))))

(defn- riders-of
  "The riders a standing train carries, as `build-train!` takes them."
  [train]
  (mapv (fn [id n] {:id id :data {:number n}}) (:changes train) (:prs train)))

(defn- bisect-train!
  "A red train looks for its red: the front half of its changes (at
  least one) rides again, and the rest go back to the line for the next
  train. A change red alone on a train goes back to its seat as a red
  head does (`:train-red!` in `ctx`, change id and why). At most
  log2(n)+1 trains per train of n, then the line goes one at a time.
  → the train that stands after it."
  [ctx seen repo policy train]
  (let [riders (riders-of train)
        size (or (:size train) (count riders))
        tries (inc (long (or (:tries train) 1)))]
    (cond
      (= 1 (count riders))
      (let [id (str (:id (first riders)))]
        (warn! repo ": " id " is red alone on the train " (:branch train))
        (if-some [red! (:train-red! ctx)]
          (red! id (str "The merge train " (:branch train)
                        " went red with this change alone on it."))
          (warn! "no hand marks " id " red this pass"))
        (ask ctx :train_delete {:repo repo :branch (:branch train)})
        nil)

      (> tries (train-cap size))
      (one-at-a-time! ctx seen repo train
                      (str "found no one red change in " (dec tries) " trains"))

      :else
      (some-> (build-train! ctx repo policy
                            (subvec riders 0 (quot (count riders) 2)))
              (assoc :tries tries :size size)))))

(defn- run-of [x] (some-> (:run_id x) str not-empty))

(defn- retry-train!
  "A train whose run was cancelled or timed out runs its checks once
  more, and that counts as one of its trains; the second time, the line
  goes one at a time. The cancelled run stays on the train as
  `stale_run_id`, so a retry read by its branch never reads that run
  again. → the train that stands after it."
  [ctx seen repo train verdict]
  (if (:retried train)
    (one-at-a-time! ctx seen repo train (str "finished " verdict " twice"))
    (do (warn! repo ": the train " (:branch train) " finished " verdict
               "; its checks run once more")
        (assoc (dispatch-checks! ctx repo
                                 (cond-> (dissoc train :pr_run)
                                   (run-of train) (assoc :stale_run_id (run-of train))))
               :retried true :tries (inc (long (or (:tries train) 1)))))))

(defn train-finished!
  "What a finished train does with its `verdict` (ticket 6033c287, slice
  3 of 3deb06ed) → the train that stands after it, nil for none. A green
  train lands whole (`land-train!`), a red one looks for its red
  (`bisect-train!`), and a cancelled or timed-out run runs once more
  (`retry-train!`). Under a policy restated back to `line` only a green
  train lands; any other is deleted and the line goes one at a time."
  [ctx seen repo policy train verdict]
  (cond
    (= "success" verdict) (land-train! ctx seen repo policy train)

    (not= "train" (merge-strategy-of policy))
    (do (warn! repo ": the train " (:branch train) " finished " verdict
               "; it is deleted and the line goes one at a time")
        (ask ctx :train_delete {:repo repo :branch (:branch train)})
        nil)

    (= "failure" verdict) (bisect-train! ctx seen repo policy train)
    :else (retry-train! ctx seen repo train verdict)))

(defn- status-args
  "A train with no run yet is read by its branch and head, and by the
  workflow it dispatched: the train's own, else the policy's test block.
  A train a retry left a cancelled run behind on names it as
  `skip_run_id`, so the rig answers past it (ticket a4890118)."
  [repo policy train]
  (if-some [run (run-of train)]
    {:repo repo :run_id run}
    (let [workflow (or (some-> (:workflow train) str not-empty)
                       (test-workflow-of policy))
          stale (:stale_run_id train)]
      (cond-> {:repo repo :branch (:branch train) :head (:head train)}
        workflow (assoc :workflow workflow)
        (some? stale) (assoc :skip_run_id stale)))))

(defn advance-train!
  "Read a standing train's run once → the train that stands after it:
  the same one while its run is pending or the rig does not answer,
  and what `train-finished!` says once it finished or was refused. A
  train read by its branch keeps the run the answer names; the run a
  retry left behind (`stale_run_id`) is the rig's to skip, through
  `status-args`' `skip_run_id`. A train tested
  by its pull request's run that showed none in `pr-run-grace-seconds`
  has its checks dispatched instead, once."
  [ctx seen repo policy train]
  (let [answer (ask ctx :train_status (status-args repo policy train))
        why (refused answer)
        st (some-> (:state answer) name)
        run (run-of answer)
        known (cond-> train
               (and run (nil? (run-of train))) (assoc :run_id run))]
    (cond
      (nil? answer) train
      (contains? missing-power-refusals why) train
      why (do (warn! "the rig refused the status of " (:branch train) " ("
                     (reason-of answer) ")")
              (train-finished! ctx seen repo policy train "cancelled"))
      (or (nil? st) (= "pending" st))
      (if (pr-run-overdue? ctx known)
        (do (warn! repo ": no run showed on the pull request of " (:branch train)
                   " in " pr-run-grace-seconds " s; its checks are dispatched")
            (dispatch-checks! ctx repo (dissoc known :pr_run)))
        known)
      :else (train-finished! ctx seen repo policy known st))))

(defn work-lines!
  "One merge call for every change of every line, with the engine's own
  hand; then only a line's front is brought up to date when it is
  behind, or when it waits on a required check its head never ran
  while its base moved (`stale-waiting?`). The front is chosen after the answers, so a change that went
  red or was parked this pass does not hold the line. `answers`, when
  given, is an atom the pass fills with change id → the rig's answer.
  A repository whose policy says `merge_strategy: train` sends its
  front and the green changes behind it as one train instead
  (`build-train!`), and a standing train (`line_train`) is read once
  (`advance-train!`) while its front waits. `trains`, when given, is an
  atom the pass fills with repository → the train that stands now, nil
  for none, for each repository whose train it built or read. A
  standing train whose repository has no line this pass — every rider
  merged or left, or the repository is deploy-held — is read as well,
  so it still finishes and leaves the policy.
  → the number of `merge` calls made."
  ([ctx seen lines by-repo] (work-lines! ctx seen lines by-repo (atom {})))
  ([ctx seen lines by-repo answers]
   (work-lines! ctx seen lines by-repo answers (atom {})))
  ([ctx seen lines by-repo answers trains]
   (let [asked (volatile! 0)]
     (doseq [[repo line] lines
             :let [policy (get by-repo repo)]]
       ;; with a deploy check, one merge ends the line's offers: the
       ;; next change waits on its deploy (ticket 47217098)
       (loop [[change & more] line]
         (when change
           (vswap! asked inc)
           (let [answer (offer-merge! ctx seen change policy)]
             (swap! answers assoc (str (:id change)) (head-answer change answer))
             (when-not (and (deploy-check-of policy)
                            (= "merged" (answer-state answer)))
               (recur more)))))
       (let [front (front-of line @answers @seen)
             id (some-> front :id str)
             train (get-in policy [:data :line_train])
             riders (when (and (nil? train)
                               (= "train" (merge-strategy-of policy))
                               (not (get @seen [:train-done repo])))
                      (train-riders line @answers @seen policy))
             built (when (next riders) (build-train! ctx repo policy riders))]
         (warn! repo ": " (or id "nothing") " is the front of the merge line, "
                (count (remove #(= id (str (:id %))) line)) " wait")
         (cond
           train (swap! trains assoc repo
                        (advance-train! (assoc ctx :answers answers)
                                        seen repo policy train))
           built (swap! trains assoc repo built)
           (and front (or (behind? (get @answers id))
                          (stale-waiting? front (get @answers id))))
           (update-behind! ctx seen front id
                           (str (get-in front [:data :head_sha]))))))
     ;; a standing train is read whatever its line: a repository with
     ;; no line this pass would otherwise keep it on the policy for good
     (doseq [[repo policy] by-repo
             :when (not (contains? lines repo))
             :let [train (get-in policy [:data :line_train])]
             :when train]
       (swap! trains assoc repo
              (advance-train! (assoc ctx :answers answers)
                              seen repo policy train)))
     @asked)))

;; ── the line, written on the rows (ticket b85aded5) ─────────────────
;;
;; The line above lives in memory and is rebuilt each pass, so without
;; this nothing but a log line said which pull request is at the front
;; or why a green one is not merging. Each pass writes the line on the
;; rows: the repository's policy names its front and how many wait, and
;; each change says its place and why it is not merging. It is a
;; MAINTENANCE write (`store/update-data!`, belief.clj's door): the
;; document moves, the version does not, and no transition is logged,
;; because a place in a line is not a thing that happened to the row.
;; A value that did not move is not written, so a quiet line writes
;; nothing, and a change that left `submitted` has its place cleared.

(def line-policy-fields
  [:line_front :line_front_pr :line_front_waiting :line_waiting :line_at])

(def line-change-fields [:line_place :line_why :line_reason])

(def line-whys
  "Every word `line_why` may carry."
  ["front" "behind" "red" "conflicted" "draft" "parked" "held" "person"])

(def ^:private reason-chars 500)

(defn front-waits-on
  "One word for what the front waits on, from its merge answer: `update`
  when the pass asked to bring it up to date and CI runs, `checks` while
  its checks run, `github` when its checks are green and GitHub has not
  judged it mergeable yet, `merge` when it was offered and GitHub has
  not merged it yet. A `waiting` answer says which of the two in its
  `waits_on`; one that carries none (a rig not yet deployed) reads as
  `checks`."
  [answer]
  (cond (behind? answer) "update"
        (= "waiting" (answer-state answer))
        (if (= "github" (some-> (:waits_on answer) name)) "github" "checks")
        :else "merge"))

(defn- parked-reason [seen id]
  (some-> (get seen [:parked-why id]) str not-empty
          (as-> s (subs s 0 (min reason-chars (count s))))))

(defn parked-changes
  "The submitted changes the house pass would offer but for a head it
  parked — the ones `merge-lines` leaves out for that reason alone."
  [changes by-repo seen]
  (filterv (fn [change]
             (let [head (some-> (get-in change [:data :head_sha]) str not-empty)
                   policy (get by-repo (str (get-in change [:data :repository])))]
               (and (get-in change [:data :number]) head policy
                    (house-pass-merges? policy)
                    (= head (get seen (str (:id change)))))))
           changes))

(defn line-marks
  "What one pass writes on the rows, from the lines it worked, the rig's
  `answers` (change id → merge answer), the parked heads in `seen`, and
  the `parked` changes the lines left out. → {:policies {repo marks}
  :changes {id marks}}; a field a marks map leaves out is cleared. The
  front's place is 1 and the others in the line follow in order; a
  change out of the line has no place and says why. A repository with
  a train in `trains` (repository → its standing train) says its front
  waits on the `train`."
  ([lines answers seen parked] (line-marks lines answers seen parked {}))
  ([lines answers seen parked trains]
  (let [answer-of #(get answers (str (:id %)))
        in-line (fn [line] (vec (remove #(out-of-line % (answer-of %) seen) line)))]
    {:policies
     (into {}
           (map (fn [[repo line]]
                  (let [in (in-line line)
                        front (first in)]
                    [repo (if front
                            {:line_front (str (:id front))
                             :line_front_pr (get-in front [:data :number])
                             :line_front_waiting (if (get trains repo)
                                                   "train"
                                                   (front-waits-on (answer-of front)))
                             :line_waiting (dec (count in))}
                            {})])))
           lines)
     :changes
     (merge
      (into {}
            (map (fn [change]
                   (let [id (str (:id change))]
                     [id (if (conflicted-change? change)
                           {:line_why "conflicted"}
                           {:line_why "parked"
                            :line_reason (parked-reason seen id)})])))
            parked)
      (into {}
            (mapcat (fn [[_ line]]
                      (let [place (zipmap (map (comp str :id) (in-line line))
                                          (iterate inc 1))]
                        (map (fn [change]
                               (let [id (str (:id change))
                                     out (out-of-line change (answer-of change) seen)]
                                 [id (cond
                                       (= "merged" out) {}
                                       (= "parked" out) {:line_why out
                                                         :line_reason (parked-reason seen id)}
                                       out {:line_why out}
                                       :else {:line_place (place id)
                                              :line_why (if (= 1 (place id))
                                                          "front" "behind")})]))
                             line))))
            lines))})))

(defn moved-marks
  "The marks to write on a row whose data is `data`, or nil when none of
  them moved. A field in `quiet` rides a write and never causes one."
  [data marks quiet]
  (when (some (fn [[k v]] (not= v (get data k))) (apply dissoc marks quiet))
    marks))

(defn mark-row!
  "One maintenance write: `marks` onto the row's data, nil removing a
  field, only when one of them moved. → true when it wrote."
  [eng kind id marks quiet]
  (let [st (:storage eng)]
    (boolean
     (when-some [rd (rdef-of-kind eng kind)]
       (store/with-tx st
         (fn [tx]
           (when-some [raw (store/load-row st tx kind id {:for-update true})]
             (let [data (:data (inv/decode-row rd raw))]
               (when-some [m (moved-marks data marks quiet)]
                 (store/update-data!
                  st tx kind id
                  (schema/encode (:schema rd)
                                 (reduce-kv (fn [d k v]
                                              (if (nil? v) (dissoc d k) (assoc d k v)))
                                            data m))
                  (:next-flip-at raw))
                 true)))))))))

(defn- in-scope?
  "Whether `repo` is one of `repos`; a nil `repos` is every repository."
  [repos repo]
  (or (nil? repos) (contains? repos (str repo))))

(defn- marked-change-ids
  "The id of every change that carries a `line_why`, whatever its state —
  only the changes of `repos` when it is given (`in-scope?`)."
  ([eng] (marked-change-ids eng nil))
  ([eng repos]
   (if-some [rd (rdef-of-kind eng :change)]
     (let [st (:storage eng)]
       (into #{}
             (comp (mapcat (fn [why]
                             (store/with-tx st
                               (fn [tx] (store/query-rows st tx :change {:line_why why}
                                                          {:limit 1000})))))
                   (filter #(or (nil? repos)
                                (in-scope? repos (get-in (inv/decode-row rd %)
                                                         [:data :repository]))))
                   (map #(str (:id %))))
             line-whys))
     #{})))

(defn mark-lines!
  "Write one pass's `marks` (`line-marks`) on the rows: every active
  policy and every submitted change, and every change that still says a
  place though it left `submitted`. What a marks map leaves out is
  cleared. With `repos` (`in-scope?`), only the rows of those
  repositories are written, and every other row keeps its marks: a
  woken pass's marks map names no other line (ticket 26a8d561).
  → how many rows were written."
  ([eng marks submitted] (mark-lines! eng marks submitted nil))
  ([eng marks submitted repos]
   (let [now (if-some [f (:now-fn eng)] (f) (Instant/now))
         blank-policy (zipmap line-policy-fields (repeat nil))
         blank-change (zipmap line-change-fields (repeat nil))
         wrote (volatile! 0)
         write! (fn [kind id m quiet]
                  (when (mark-row! eng kind id m quiet) (vswap! wrote inc)))]
     (doseq [p (policies eng :active)
             :let [repo (str (get-in p [:data :repository]))
                   m (get (:policies marks) repo)]
             :when (in-scope? repos repo)]
       (write! :repo_policy (str (:id p))
               (merge blank-policy m (when (seq m) {:line_at now}))
               #{:line_at}))
     (doseq [id (into (set (map #(str (:id %))
                                (filter #(in-scope? repos (get-in % [:data :repository]))
                                        submitted)))
                      (marked-change-ids eng repos))]
       (write! :change id (merge blank-change (get (:changes marks) id)) #{}))
     @wrote)))

;; ── one deploy at a time (ticket 47217098) ────────────────────────────
;;
;; A repository whose policy names a `deploy_check` merges one change and
;; then waits for it to deploy. The merge pass writes each house merge on
;; the policy (`deploy_waits_on`, as "change ticket number"), and while
;; one is there the repository is offered nothing. The forge pass takes
;; a merge off once that check is green on a base commit that holds it
;; (`note-deploy!`). A red deploy holds the line and says so; a deploy
;; that never reports holds it `deploy_wait_seconds` at most. A ticket
;; whose merge is still on the list does not yet meet another ticket's
;; `merge_after`. All of it is a maintenance write, as the line is.

(defn deploy-waits
  "The house merges a policy says are not deployed yet, oldest first, as
  [{:change :ticket :number}]."
  [policy]
  (mapv (fn [s]
          (let [[change ticket number] (str/split (str s) #" ")]
            {:change change
             :ticket (when (and ticket (not= "-" ticket)) ticket)
             :number (some-> number parse-long)}))
        (get-in policy [:data :deploy_waits_on])))

(defn- wait-entry [{:keys [change ticket number]}]
  (str change " " (or ticket "-") " " (or number "-")))

(defn- now-of [eng] (if-some [f (:now-fn eng)] (f) (Instant/now)))

(defn- instant-of [v]
  (cond (instance? Instant v) v
        (inst? v) (Instant/ofEpochMilli (inst-ms v))
        (string? v) (try (Instant/parse v) (catch Exception _ nil))))

(defn- clip [s] (subs s 0 (min reason-chars (count s))))

(defn- merges-named [waits]
  (str/join ", " (map #(if (:number %) (str "#" (:number %)) (:change %)) waits)))

(defn deploy-held
  "Repository → the sentence its line waits on, for every policy with a
  `deploy_check` whose house merges are not all deployed. A wait longer
  than `deploy_wait_seconds` on a deploy that is not red is let go here:
  the waits are cleared and the policy's note says so."
  [eng by-repo]
  (let [now (now-of eng)]
    (into {}
          (keep (fn [[repo policy]]
                  (let [check (deploy-check-of policy)
                        waits (deploy-waits policy)
                        since (instant-of (get-in policy [:data :deploy_waiting_since]))
                        red? (= "red" (str (get-in policy [:data :deploy_state])))
                        cap (deploy-wait-of policy)]
                    (when (and check (seq waits))
                      (if (and (not red?) since
                               (>= (.getSeconds (Duration/between since now)) cap))
                        (do (mark-row! eng :repo_policy (str (:id policy))
                                       {:deploy_waits_on nil
                                        :deploy_waiting_since nil
                                        :deploy_note
                                        (clip (str "The deploy of " (merges-named waits)
                                                   " did not report on " check " within "
                                                   cap " seconds; the line went on at "
                                                   now "."))}
                                       #{})
                            nil)
                        [repo (clip (str "The line waits on the deploy of "
                                         (merges-named waits)
                                         (when since (str ", since " since))
                                         (when red?
                                           (str "; " check " is red, and a red deploy"
                                                " holds the line"))
                                         "."))])))))
          by-repo)))

(defn- note-merges!
  "Write this pass's house merges on their policies' waits, for the
  repositories with a `deploy_check`."
  [eng by-repo lines answers]
  (doseq [[repo line] lines
          :let [policy (get by-repo repo)
                merged (filter #(= "merged" (answer-state (get answers (str (:id %)))))
                               line)]
          :when (and (deploy-check-of policy) (seq merged))]
    (mark-row! eng :repo_policy (str (:id policy))
               {:deploy_waits_on
                (into (vec (get-in policy [:data :deploy_waits_on]))
                      (map #(wait-entry {:change (str (:id %))
                                         :ticket (born-ticket %)
                                         :number (get-in % [:data :number])}))
                      merged)
                :deploy_waiting_since
                (or (instant-of (get-in policy [:data :deploy_waiting_since]))
                    (now-of eng))}
               #{})))

(defn undeployed-tickets
  "Ticket id → the repository whose deploy it still waits on, for every
  house merge an active policy says is not deployed yet."
  [eng]
  (into {}
        (for [p (policies eng :active)
              :when (deploy-check-of p)
              w (deploy-waits p)
              :when (:ticket w)]
          [(:ticket w) (str (get-in p [:data :repository]))])))

(def ^:private red-deploys
  #{"failure" "timed_out" "cancelled" "action_required" "startup_failure"})

(defn- deploy-run-order
  "Where one run of the deploy check stands among its runs on one head:
  when it finished, or when it started while it has not, and its forge
  id beside that, which grows with every run the forge starts. The
  forge answers its runs newest first and a fake answers them as they
  were seeded, so the place in the list says nothing."
  [run]
  (let [id (str (or (:check_id run) (:id run)))]
    [(str (or (:finished_at run) (:started_at run)))
     (if (re-matches #"\d{1,18}" id) (Long/parseLong id) -1)]))

(defn note-deploy!
  "What the forge pass read of one policy's base says of its deploy.
  `base-read` is forge-base's {:head_sha :checks}; `covers?` is (fn
  [number sha]) → whether that pull request's merge is `sha` or an
  ancestor of it. The `deploy_check` green on the head records it as
  deployed and takes every merge it covers off the waits; red says so
  and holds the line; one still running writes nothing. Of several runs
  of the check on the head the newest speaks (`deploy-run-order`), so a
  green re-apply answers for the red run before it. → true when it
  wrote."
  [eng policy base-read covers?]
  (let [check (deploy-check-of policy)
        head (some-> (:head_sha base-read) str not-empty)
        run (->> (:checks base-read)
                 (filter #(= check (str (:check_name %))))
                 (sort-by deploy-run-order)
                 last)
        conclusion (some-> (:conclusion run) str not-empty)
        id (str (:id policy))]
    (cond
      (not (and check head conclusion)) false

      (= "success" conclusion)
      (let [left (filterv #(not (and (:number %) (covers? (:number %) head)))
                          (deploy-waits policy))]
        (mark-row! eng :repo_policy id
                   (cond-> {:deployed_head head
                            :deploy_state "green"
                            :deploy_waits_on (not-empty (mapv wait-entry left))}
                     (not= head (get-in policy [:data :deployed_head]))
                     (assoc :deployed_at (now-of eng))
                     (empty? left)
                     (assoc :deploy_waiting_since nil :deploy_note nil))
                   #{}))

      (contains? red-deploys conclusion)
      (mark-row! eng :repo_policy id
                 {:deploy_state "red"
                  :deploy_note (clip (str check " finished " conclusion " at " head
                                          "; the house merges nothing more here"
                                          " until it is green."))}
                 #{})

      :else false)))

;; ── a merge that waits on other tickets (ticket d069bc3b) ───────────────
;;
;; A ticket's `merge_after` names the tickets that must be done before
;; its change merges. The pass HOLDS such a change: no merge call, no
;; update and no place in its line, so the change behind it is the
;; front. Only `done` releases it. A DROPPED dependency does not, as a
;; dropped blocker does: the work it waited on will never land, and a
;; person decides whether this one still should. The hold is written on
;; the change (`line_why` held) and on its ticket (`merge_waits`).

(defn merge-holds
  "Ticket id → what it still waits on to merge, for the tickets these
  changes were born from: each ticket its `merge_after` names that is
  not done, as {:id :state}, with `missing` for one that is gone. A
  ticket that waits on nothing is not in the map, and an engine that
  declares no ticket kind answers an empty one."
  [eng changes]
  (if-some [rd (rdef-of-kind eng :ticket)]
    (let [st (:storage eng)
          load-ticket (fn [tid]
                        (some->> (try (store/with-tx st
                                        (fn [tx] (store/load-row st tx :ticket tid {})))
                                      (catch Exception _ nil))
                                 (inv/decode-row rd)))
          ;; done is not enough while its merge waits on a deploy
          ;; (ticket 47217098)
          undeployed (undeployed-tickets eng)
          waits-of (fn [tid]
                     (into []
                           (keep (fn [dep]
                                   (let [s (some-> (load-ticket (str dep)) :state name)
                                         undeployed-in (get undeployed (str dep))]
                                     (cond
                                       (not= "done" s)
                                       {:id (str dep) :state (or s "missing")}

                                       undeployed-in
                                       {:id (str dep)
                                        :state (str "done, not deployed in "
                                                    undeployed-in)}))))
                           (get-in (load-ticket tid) [:data :merge_after])))]
      (into {}
            (keep (fn [tid]
                    (let [waits (waits-of tid)]
                      (when (seq waits) [tid waits]))))
            (distinct (keep born-ticket changes))))
    {}))

(defn held-reason
  "The sentence a held change and its ticket carry: what it waits on,
  each with its state, and that a dropped one waits on a person."
  [waits]
  (let [s (str "waits on "
               (str/join ", " (map #(str (:id %) " (" (:state %) ")") waits))
               " to merge"
               (when (some #(= "dropped" (:state %)) waits)
                 "; a dropped ticket holds it until a person restates merge_after"))]
    (subs s 0 (min reason-chars (count s)))))

(defn- mark-held-tickets!
  "Write `merge_waits` on the ticket of every submitted change, and
  clear it on the tickets the last pass held that nothing holds now.
  `seen` keeps the held tickets under `:held-tickets`. A scoped pass
  (`whole?` false) writes only the tickets of its own `changes`, and
  keeps the others in `seen` for the next whole pass."
  ([eng seen changes holds] (mark-held-tickets! eng seen changes holds true))
  ([eng seen changes holds whole?]
   (let [mine (set (keep born-ticket changes))
         before (set (get @seen :held-tickets))]
     (doseq [tid (into mine (if whole? before (filter mine before)))]
       (mark-row! eng :ticket tid
                  {:merge_waits (some-> (get holds tid) held-reason)} #{}))
     (swap! seen assoc :held-tickets
            (into (set (keys holds)) (when-not whole? (remove mine before)))))))

;; ── a merge refused as not mergeable (ticket 0b564d2d) ───────────────
;;
;; The failing path finds a conflict in ONE field: the row's
;; `mergeable`. The mirror fills that from GitHub, and GitHub computes
;; mergeability after the push without touching the pull request's
;; `updated_at` — so a row read too early keeps `unknown` (or `blocked`,
;; when the state word outranks it) and no later poll corrects it. The
;; merge call is the honest read: a rig refused `not_mergeable` has just
;; been told the pull request conflicts. So the pass writes `conflicted`
;; on the row, a maintenance write as the line is, and the forge's
;; failing pass takes it from there — `failing` with its conflicted
;; paths, and a `return` that wakes the seat. Without it the change sits
;; submitted and parked, offered nothing and asked for nothing.

(defn conflicted-ids
  "The ids of the changes whose merge the rig refused this pass because
  the pull request is not mergeable. `answers` is change id → answer."
  [answers]
  (into (sorted-set)
        (keep (fn [[id answer]]
                (when (= not-mergeable-refusal (refused answer)) (str id))))
        answers))

(defn- mark-conflicts!
  "Write `mergeable: conflicted` on every change the rig refused as not
  mergeable this pass. → how many rows were written."
  [eng answers]
  (let [wrote (volatile! 0)]
    (doseq [id (conflicted-ids answers)]
      (when (mark-row! eng :change id {:mergeable "conflicted"} #{})
        (vswap! wrote inc)
        (warn! "the rig says " id " conflicts with its base; the row says"
               " conflicted, and the failing pass has it")))
    @wrote))

(defn- with-conflicts
  "`changes` as their rows read once `mark-conflicts!` has written: the
  ones in `ids` say `conflicted`."
  [ids changes]
  (mapv #(cond-> %
           (contains? ids (str (:id %))) (assoc-in [:data :mergeable] "conflicted"))
        changes))

;; A HEAD PARKED AS NOT MERGEABLE (ticket 4df76da5). `seen` keeps that
;; head until the process restarts, and `mark-conflicts!` writes only
;; from the pass the rig refused. So a row that stops saying `conflicted`
;; at the same head — a later read of the mirror's, or any other writer —
;; would sit submitted and parked, offered nothing and asked for nothing.
;; Each pass reads its parked conflicts again: a row that says `clean` at
;; the parked head is un-parked, and the rig is asked once more; any
;; other word is written back to `conflicted`.

(defn- parked-conflict?
  "Is this change's head the one `seen` parked as not mergeable?"
  [seen change]
  (let [id (str (:id change))
        head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (boolean (and head
                  (= head (get seen id))
                  (= not-mergeable-refusal (get seen [:parked-as id]))))))

(defn- review-parked-conflicts!
  "Read again every change whose head `seen` parked as not mergeable and
  whose row does not say `conflicted`: `clean` un-parks the head, and any
  other word is written back to `conflicted`. Throws nothing.
  → the changes, as their rows read after it."
  [eng seen changes]
  (mapv (fn [change]
          (let [id (str (:id change))]
            (cond
              (or (not (parked-conflict? @seen change))
                  (conflicted-change? change))
              change

              (= "clean" (str (get-in change [:data :mergeable])))
              (do (swap! seen dissoc id [:parked-why id] [:parked-as id])
                  (warn! "the row of " id " says clean at the head the rig"
                         " refused as not mergeable; this pass asks again")
                  change)

              :else
              (try
                (mark-row! eng :change id {:mergeable "conflicted"} #{})
                (warn! "the row of " id " lost its conflict at the parked"
                       " head; it says conflicted again")
                (assoc-in change [:data :mergeable] "conflicted")
                (catch Exception e
                  (warn! "the conflict of " id " was not written again ("
                         (ex-message e) "); the next pass writes it")
                  change)))))
        changes))

(defn held-changes
  "The changes a house pass holds out of its lines: those of a house
  repository whose ticket still waits on another to merge (`holds`, as
  `merge-holds` answers it)."
  [changes by-repo holds]
  (filterv #(and (some-> (get by-repo (str (get-in % [:data :repository])))
                         house-pass-merges?)
                 (get holds (born-ticket %)))
           changes))

(defn- train-red!
  "Send a change a merge train found red alone back to its seat, as the
  forge sends a red head: `fail`, naming the head the train judged, or
  `stick` on the policy's last round (ticket 6033c287)."
  [eng changes by-repo id why]
  (when-some [row (some #(when (= id (str (:id %))) %) changes)]
    (let [policy (get by-repo (str (get-in row [:data :repository])))
          last? (>= (long (or (get-in row [:data :rounds]) 0))
                    (long (rounds-of policy)))]
      (try
        (inv/invoke! eng :change id (if last? :stick :fail)
                     (if last?
                       {:why (clip why) :failing_checks ["merge-train"]}
                       {:failing_checks ["merge-train"]
                        :train_red_head (str (get-in row [:data :head_sha]))
                        :train_red (clip why)})
                     {:principal mirror/source-principal})
        (catch Exception e
          (warn! "the train's red did not move " id " (" (ex-message e)
                 "); it rides again"))))))

(defn merge-green!
  "One merge pass. Every submitted change with a number and a head,
  whose repository's active policy says `auto_merge` and `merge_by:
  house`, gets ONE `merge` call with the engine's own hand. `seen` is
  an atom of change id → the head the rig refused for good (a
  `parked-refusals` name): that head is never offered again, and a new
  head is offered afresh — but for a head parked as not mergeable whose
  row no longer says `conflicted`, which is offered once more when the
  row says `clean` and marked again when it says anything else
  (`review-parked-conflicts!`). A branch the rig says is behind its base is
  brought up to date with `update_branch` only when it is the front of
  its repository's line (`merge-lines`), once per head (`seen` keeps
  that under `[:updated id]`). `merged` needs nothing here, because the
  mirror moves the row; `waiting` and every other refusal are asked
  again next pass; `red` is left, because the seat's feedback already
  carries the red checks; a refusal that says the pull request is not
  mergeable writes `conflicted` on the row (`mark-conflicts!`), because
  the mirror's own read may never say so. Then the line is written on
  the rows
  (`mark-lines!`): the policy names its front and who waits, and each
  change its place and why it is not merging. A change whose ticket
  still waits on another to merge (`merge-holds`) is held out of all
  of it, and says so. A repository whose last house merge is not yet
  deployed (`deploy-held`) is offered nothing. With `repos`, a set of
  repository names, the pass works only those repositories: no other
  is offered a merge and no other's marks are written (a woken pass,
  ticket 26a8d561). Throws nothing.
  → the number of `merge` calls made."
  ([eng seen] (merge-green! eng seen nil))
  ([eng seen repos]
  (let [by-repo (into {} (filter #(in-scope? repos (key %))) (policies-by-repo eng))
        deploy-holds (deploy-held eng by-repo)
        changes (review-parked-conflicts!
                 eng seen
                 (filterv #(in-scope? repos (get-in % [:data :repository]))
                          (submitted-changes eng)))
        house? #(some-> (get by-repo (str (get-in % [:data :repository])))
                        house-pass-merges?)
        holds (merge-holds eng (filter house? changes))
        held (held-changes changes by-repo holds)
        held-ids (into #{} (map #(str (:id %))) held)
        offered (remove #(held-ids (str (:id %))) changes)
        priorities (ticket-priorities eng offered)
        lines (merge-lines offered by-repo
                           #(get priorities (born-ticket %)) @seen)
        answers (atom {})
        trains (atom {})
        asked (work-lines! {:services (:services eng) :now-fn (:now-fn eng)
                            :train-red! #(train-red! eng changes by-repo %1 %2)}
                           seen
                           (apply dissoc lines (keys deploy-holds)) by-repo
                           answers trains)
        standing (merge (into {}
                              (keep (fn [[repo p]]
                                      (when-some [t (get-in p [:data :line_train])]
                                        [repo t])))
                              by-repo)
                        @trains)]
    (try
      (note-merges! eng by-repo lines @answers)
      (mark-conflicts! eng @answers)
      (doseq [[repo t] @trains]
        (mark-row! eng :repo_policy (str (:id (get by-repo repo)))
                   {:line_train t} #{}))
      (doseq [[repo why] deploy-holds]
        (mark-row! eng :repo_policy (str (:id (get by-repo repo)))
                   {:deploy_note why} #{}))
      ;; the line is read after `mark-conflicts!`, so the pass that finds
      ;; a conflict says `conflicted` and not `parked` (ticket 4df76da5)
      (mark-lines! eng
                   (update (let [found (conflicted-ids @answers)]
                             (line-marks (into (empty lines)
                                               (map (fn [[repo line]]
                                                      [repo (with-conflicts found line)]))
                                               lines)
                                         @answers @seen
                                         (parked-changes (with-conflicts found offered)
                                                         by-repo @seen)
                                         standing))
                           :changes merge
                           ;; a change nobody here can merge says so
                           ;; (ticket 60962bcf)
                           (person-marks changes by-repo)
                           (into {}
                                 (map (fn [c]
                                        [(str (:id c))
                                         {:line_why "held"
                                          :line_reason (held-reason
                                                        (get holds (born-ticket c)))}]))
                                 held))
                   changes repos)
      (mark-held-tickets! eng seen changes holds (nil? repos))
      (catch Exception e
        (warn! "the merge line was not written on the rows (" (ex-message e)
               "); the next pass writes it")))
    asked)))

;; ── the person's merge (ticket 4d59b22d) ───────────────────────────────
;;
;; A repository the house pass does not merge leaves a green change to
;; its person, and nothing said so but somebody polling. So a submitted
;; change GitHub calls `clean` (no conflict, the required checks green)
;; that has stood clean at one head for the policy's
;; `merge_wait_seconds` raises ONE held call for a person's tap: the
;; rig's `merge`, with the very arguments the house pass would send. The
;; person's Allow forwards it (held-calls/forward!), so the tap merges
;; the change exactly as the house would have.
;;
;; ONE ASK PER HEAD, AND THE ROWS REMEMBER IT. The held_call rows are
;; the record: while an ask for the pull request is still held, no
;; second one is raised, whatever head it stands at now; and an ask
;; that was answered — refused, expired or merged — is never raised
;; again for the same head. Only the moment a change turned clean lives
;; in memory, so a restart makes the wait start again, which errs on the
;; side of asking later rather than twice.

(def default-merge-wait-seconds
  "How long a green, clean change waits on its person before the house
  asks for the merge, when the policy names no `merge_wait_seconds`."
  3600)

(defn merge-wait-of [policy]
  (long (or (get-in policy [:data :merge_wait_seconds])
            default-merge-wait-seconds)))

(def merge-asker
  "The caller a merge ask names: the house's own pass. It is never the
  person who answers, so the held call's first wall stands."
  "factory10-merge-ask")

(defn- field-of
  "One field of a held call's stored input, however its keys decoded."
  [m k]
  (or (get m k) (get m (name k))))

(defn merge-asks
  "Every merge ask the house has raised, decoded — empty in an engine
  that declares no held_call kind."
  [eng]
  (if-some [rd (rdef-of-kind eng :held_call)]
    (let [st (:storage eng)]
      (mapv #(inv/decode-row rd %)
            (store/with-tx st
              (fn [tx] (store/query-rows st tx :held_call
                                         {:tool (gate/bench-tool :merge)
                                          :caller merge-asker}
                                         {:limit 1000})))))
    []))

(defn- asked-already?
  "Is there an ask for this pull request still waiting, or one for this
  head at all?"
  [asks change head]
  (some (fn [a]
          (let [in (get-in a [:data :input])]
            (and (= (str (field-of in :repo))
                    (str (get-in change [:data :repository])))
                 (= (str (field-of in :number))
                    (str (get-in change [:data :number])))
                 (or (contains? #{:held :allowed}
                                (some-> (:state a) name keyword))
                     (= head (str (field-of in :head_sha)))))))
        asks))

(defn pull-url [change]
  (or (some-> (get-in change [:data :url]) str not-empty)
      (str "https://github.com/" (get-in change [:data :repository])
           "/pull/" (get-in change [:data :number]))))

(defn waited-text
  "A wait in seconds, as a person reads it: 1h 5m, or 45m."
  [seconds]
  (let [m (quot (long seconds) 60)
        h (quot m 60)]
    (if (pos? h)
      (str h "h " (rem m 60) "m")
      (str m "m"))))

(defn merge-ask-why
  "The one sentence the person reads: the pull request, the title and
  how long it has waited."
  [change seconds]
  (str "Merge " (pull-url change) " ("
       (or (some-> (get-in change [:data :title]) str not-empty) "untitled")
       "): its checks are green, it merges clean, and it has waited "
       (waited-text seconds) " for a person."))

(defn- owner-of
  "The person the change's seat belongs to: the `owner` of the seat the
  change's `author` names, or nil when the seat names none. A merge ask
  is a tool call, answered by a person who holds the approver role; the
  owner is who it is written for."
  [eng change]
  (when-some [author (some-> (get-in change [:data :author]) str not-empty)]
    (when-some [rd (rdef-of-kind eng :seat)]
      (let [st (:storage eng)
            raw (store/with-tx st
                  (fn [tx] (first (store/query-rows st tx :seat {:name author}
                                                    {:limit 1}))))]
        (some-> raw (->> (inv/decode-row rd)) (get-in [:data :owner])
                str not-empty)))))

(defn- raise-merge-ask!
  "Mint the one held call a person's tap turns into the rig's merge."
  [eng change policy seconds]
  (let [args (merge-args change policy)
        owner (owner-of eng change)]
    (inv/create! eng :held_call
                 (cond-> {:tool (gate/bench-tool :merge)
                          :why (:text (held/capped (merge-ask-why change seconds)
                                                   240))
                          :caller merge-asker
                          :input args
                          :forward args
                          :shown (:text (held/capped
                                         (str "merge " (:repo args) "#"
                                              (:number args))
                                         140))}
                   owner (assoc :owner owner))
                 {:principal held/engine-actor})))

(defn ask-for-merges!
  "One ask pass. Every submitted change with a number and a head, whose
  repository's active policy leaves the merge to a person
  (`house-pass-merges?` is false), that GitHub calls `clean` and is not
  a draft, is timed from the first pass that saw it clean at this head.
  Once it has waited the policy's `merge_wait_seconds`, it raises one
  merge ask unless `asked-already?`. A change whose ticket still waits
  on another to merge (`merge-holds`) is not timed and not asked; its
  wait starts once nothing holds it. A repository whose policy names no
  required check is asked nothing (`person-merges-on-github?`): the rig
  would refuse the allowed call, so the tap would merge nothing, and the
  merge pass writes `line_why: person` on the change instead. `waiting`
  is an atom of change id → {:head :since}. Throws nothing. → the number
  of asks raised."
  [eng waiting ^Instant now]
  (let [by-repo (policies-by-repo eng)
        asks (merge-asks eng)
        changes (submitted-changes eng)
        person? #(some-> (get by-repo (str (get-in % [:data :repository])))
                         house-pass-merges? not)
        holds (merge-holds eng (filter person? changes))
        raised (volatile! 0)
        clean (volatile! {})]
    (doseq [change changes
            :let [id (str (:id change))
                  number (get-in change [:data :number])
                  head (some-> (get-in change [:data :head_sha]) str not-empty)
                  policy (get by-repo (str (get-in change [:data :repository])))]
            :when (and number head policy
                       (not (house-pass-merges? policy))
                       (not (person-merges-on-github? policy))
                       (= "clean" (str (get-in change [:data :mergeable])))
                       (not (true? (get-in change [:data :draft])))
                       (not (get holds (born-ticket change))))]
      (let [prior (get @waiting id)
            ^Instant since (if (= head (:head prior)) (:since prior) now)
            seconds (.getSeconds (Duration/between since now))]
        (vswap! clean assoc id {:head head :since since})
        (when (and (<= (merge-wait-of policy) seconds)
                   (not (asked-already? asks change head)))
          (try
            (raise-merge-ask! eng change policy seconds)
            (vswap! raised inc)
            (catch Exception e
              (warn! "the merge ask for " id " did not land ("
                     (ex-message e) "); the next pass asks again"))))))
    ;; a change that is no longer clean, or no longer submitted, starts
    ;; its wait again the next time it is
    (reset! waiting @clean)
    @raised))

(def default-enrol-seconds
  "How often the retry pass runs. The discover sweep's own cadence, in
  seconds: a repository the bench did not take is a repository nobody
  can work, so the house asks again soon."
  300)

;; ── the house pass, woken by the mirror (ticket 6e190062) ────────────
;;
;; On the clock alone, each step of a merge line (update the front, wait
;; for its gate, merge, next front) can wait a whole beat after its
;; check already finished. So the forge mirror WAKES the merge pass when
;; it sees a required check finish on a house change, or the base move:
;; it writes the repository on a board, and the sweeper reads the board
;; every `house-tick-seconds`. A repository is woken at most once per
;; `house-debounce-seconds`; the clock stays as the fallback.

(def house-debounce-seconds
  "The least time between two woken merge passes for one repository."
  30)

(def house-tick-seconds
  "How often the sweeper reads the wake board between clock beats."
  5)

(def ^:private house-seen-cap
  "How many observations the board remembers before it starts over."
  4096)

(defn house-board
  "A fresh wake board: `:seen` the observations already woken on,
  `:pending` the repositories waiting for a woken pass, `:last`
  repository → the Instant of its last woken pass."
  []
  (atom {:seen #{} :pending #{} :last {}}))

(defonce ^{:doc "The process's wake board: the mirror writes it, the
  sweeper reads it. Both run in the one elected holder."}
  house-wakes
  (house-board))

(defn nudge-house!
  "The mirror saw something that can move `repo`'s merge line. `what`
  names the observation (a change's head and its verdict, or a base
  head), and the same observation twice wakes nothing, so a green change
  that waits does not wake the pass every beat. → true when it woke."
  ([repo what] (nudge-house! house-wakes repo what))
  ([board repo what]
   (let [repo (some-> repo str not-empty)
         [old new] (swap-vals!
                    board
                    (fn [b]
                      (if (or (nil? repo) (contains? (:seen b) what))
                        b
                        (-> b
                            (update :seen #(if (< (count %) house-seen-cap)
                                             (conj % what)
                                             #{what}))
                            (update :pending conj repo)))))]
     (not= old new))))

(defn due-wakes!
  "The repositories whose woken pass is due at `now`: pending, and not
  woken in the last `house-debounce-seconds`. They leave `:pending` and
  are stamped `now`; one still inside its window stays pending, so a
  wake is delayed and never lost. → the set, maybe empty."
  [board ^Instant now]
  (let [ready? (fn [b repo]
                 (let [^Instant at (get-in b [:last repo])]
                   (or (nil? at)
                       (not (.isBefore now (.plusSeconds at (long house-debounce-seconds)))))))
        [old _] (swap-vals!
                 board
                 (fn [b]
                   (let [due (filterv #(ready? b %) (:pending b))]
                     (-> b
                         (update :pending #(reduce disj % due))
                         (update :last into (map (fn [r] [r now])) due)))))]
    (into #{} (filter #(ready? old %)) (:pending old))))

(defn house-beat!
  "One tick of the sweeper. When `clock?`, the clock's passes
  (`clock-pass!`), which merge every line and so answer any wake due
  now; otherwise the merge pass (`merge-pass!`), given the set of the
  repositories whose wake is due, so it works only those (ticket
  26a8d561). → {:clock? … :woken #{repo}}."
  [board ^Instant now clock? clock-pass! merge-pass!]
  (let [woken (due-wakes! board now)]
    (cond
      clock? (clock-pass!)
      (seq woken) (merge-pass! woken))
    {:clock? (boolean clock?) :woken woken}))

(defn start-enrol-sweeper!
  "The retry's daemon: one `enroll-unenrolled!` every `:every-seconds`,
  on a daemon thread (the forge pass's own shape). The first pass is
  one interval after the start, so a boot writes nothing. The wiring
  owns the lifecycle and elects the one holder per database; a suite
  calls `enroll-unenrolled!` directly.

  The house's merge pass (`merge-green!`) rides the same beat and the
  same election, so one process per database asks the rig to merge,
  and its memory of refused heads lives as long as that holder. So does
  the person's merge ask (`ask-for-merges!`), and its memory of when a
  change turned clean. Between beats the merge pass also runs when the
  mirror wakes it (`house-wakes`, `house-beat!`)."
  [eng {:keys [every-seconds] :or {every-seconds default-enrol-seconds}}]
  (let [stop (CountDownLatch. 1)
        seen (atom {})
        waiting (atom {})
        every-ms (* 1000 (long every-seconds))
        tick (max 1 (min (long every-seconds) (long house-tick-seconds)))
        merge-pass! (fn merge-pass!
                      ([] (merge-pass! nil))
                      ([repos]
                       (try (merge-green! eng seen repos)
                            (catch Exception e
                              (warn! "the merge pass failed ("
                                     (ex-message e) ")")))))
        clock-pass! (fn []
                      (try (enroll-unenrolled! eng)
                           (catch Exception e
                             (warn! "the enrolment pass failed ("
                                    (ex-message e) ")")))
                      (merge-pass!)
                      (try (ask-for-merges! eng waiting (Instant/now))
                           (catch Exception e
                             (warn! "the merge ask pass failed ("
                                    (ex-message e) ")"))))
        t (Thread. ^Runnable
                   (fn []
                     (loop [next-clock (+ (System/currentTimeMillis) every-ms)]
                       (when-not (.await stop tick TimeUnit/SECONDS)
                         (let [ms (System/currentTimeMillis)
                               clock? (>= ms next-clock)]
                           (try (house-beat! house-wakes (Instant/now) clock?
                                             clock-pass! merge-pass!)
                                (catch Exception e
                                  (warn! "the sweeper's beat failed ("
                                         (ex-message e) ")")))
                           (recur (if clock? (+ ms every-ms) next-clock))))))
                   "factory10-bench-enrol")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-enrol-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)

;; ── who is committing ───────────────────────────────────────────────

(def seat-trailer "Waymark-Seat")
(def sitting-trailer "Waymark-Sitting")

(defn seat-id
  "The seat this hand sits in, or nil. The sitter's member id IS the
  office — `seat:<the seat's id>` (seats/sitter-id), derived and never
  stored — so the principal in front of the door names the seat with
  no read at all. A person's own hand names no seat, and the trailer
  is then absent: blame maps to a seat when a seat did it."
  [ctx]
  (let [id (str (get-in ctx [:principal :id]))]
    (when (str/starts-with? id "seat:")
      (not-empty (subs id (count "seat:"))))))

(defn- own-sitting-id
  "The sitting this request was made from, or nil: the one its MCP
  session is bound to, carried on the grant as `:sitting` (mcp's
  waymark_invoke, then the router's invoke-opts). Never the newest
  sitting under the grant: every sitting of a seat shares the seat's
  grant, so with several open the newest is usually another one
  (ticket 51dfd10b)."
  [ctx]
  (some-> (get-in ctx [:grant :sitting]) str not-empty))

(defn sitting-id
  "The open sitting of the leash this request wears, or nil. The
  request's own sitting when it names one (own-sitting-id); otherwise
  the open sitting under its grant, as below.

  `(:grant ctx)` is the guard's-eye view of the grant presented with
  this request (invoke.clj's make-ctx), and the sitting is the row
  under that grant that is still open — `seats/open-sitting-for-grant`'s
  own query, made here through the ctx `:find` hook so it runs in the
  write's own transaction. A request with no grant, or no open
  sitting, names none, and the commit carries one trailer instead of
  two."
  [ctx]
  (or (own-sitting-id ctx)
      (when-some [find' (:find ctx)]
        (when-some [gid (some-> (get-in ctx [:grant :id]) str not-empty)]
          (some-> (first (find' :sitting {:grant gid :state :open}
                                {:limit 1 :newest-first true}))
                  :id str not-empty)))))

(defn trailers
  "The git trailers a bench commit carries: the seat and the sitting,
  each as one `Key: value` text, which is the shape the rig takes.

  THIS IS WHY THE DOOR EXISTS AT ALL. `git blame` on a line a seat
  wrote must answer the OFFICE, so a correction counts against the
  seat and never against an engineer who never saw the line."
  [ctx]
  (into []
        (keep (fn [[k v]] (when v (str k ": " v))))
        [[seat-trailer (seat-id ctx)]
         [sitting-trailer (sitting-id ctx)]]))

;; ── the sitting that holds the ticket (ticket d7c854b3) ──────────────

(def unheld-remedy
  "Stop: do not submit. The sitting that holds the ticket finishes it; close this one.")

(defn unheld-detail
  "Why a seat's submit on this change is not its sitting's to make, as
  one sentence, or nil. The request's own sitting (own-sitting-id, never
  the newest under the seat's shared grant) must be open, no OTHER open
  sitting of the seat may hold the ticket the change was born from, and
  a sitting whose walk handed it rows must hold that ticket among them.
  A person's hand, a change born of no ticket, and a request that names
  no sitting are not judged here.
  The same wall stands on bench writes at the power door
  (waymark10.server.gate-proxy)."
  [row ctx]
  (when-some [find' (:find ctx)]
    (when-some [seat (seat-id ctx)]
      (when-some [ticket (born-ticket row)]
        (when-some [sid (own-sitting-id ctx)]
          (when-some [mine (when-some [read' (:read ctx)]
                             (read' :sitting sid))]
            (let [holds? (fn [s] (boolean (some #(= ticket (str %))
                                                (get-in s [:data :walked_rows]))))
                  holder (->> (find' :sitting {:seat seat :state :open}
                                     {:limit 50 :newest-first true})
                              (remove #(= sid (str (:id %))))
                              (filter holds?)
                              first)
                  state (some-> (:state mine) name)
                  walked (seq (get-in mine [:data :walked_rows]))]
              (when (or holder
                        (not= "open" state)
                        (and walked (not (holds? mine))))
                (str "This sitting no longer holds ticket " ticket " ("
                     (cond
                       holder (str "held by sitting " (:id holder))
                       (not= "open" state)
                       (str "sitting " (:id mine) " is " state
                            (when-some [t (get-in mine [:data :ended_at])]
                              (str ", closed at " t)))
                       :else (str "sitting " (:id mine)
                                  "'s walk never handed it that ticket"))
                     "); stop, do not write.")))))))))
