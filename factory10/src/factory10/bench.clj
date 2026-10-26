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
            [clojure.walk :as walk]
            [waymark10.server.clock :as clock]
            [waymark10.time :refer [instant-of]]
            [factory10.mirror :as mirror]
            [factorykit.forge :as kit]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.schema :as schema]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Duration Instant)))

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
  GitHub-hosted runners, sent only when the row names at least one.
  `hosted_ok` rides the same way (ticket 83dd6dc3): the rig's per-repo
  leave to run CI on GitHub-hosted runners, sent only when the row
  states it."
  [row]
  (let [test-block (get-in row [:data :test])
        check-block (get-in row [:data :check])
        pattern (:select_pattern test-block)
        hosted (seq (get-in row [:data :hosted_workflows]))
        hosted-ok (get-in row [:data :hosted_ok])]
    (cond-> {:repo (str (get-in row [:data :repository]))
             :clone_url (clone-url-of row)
             :default_branch (base-of row)
             :deny (vec (get-in row [:data :deny]))
             :land (land-of row)}
      (some? test-block) (assoc :test (cond-> (select-keys test-block [:workflow :input])
                                        (not (str/blank? (str pattern)))
                                        (assoc :select_pattern (str pattern))))
      (some? check-block) (assoc :check (select-keys check-block [:command :timeout]))
      hosted (assoc :hosted_workflows (vec hosted))
      (boolean? hosted-ok) (assoc :hosted_ok hosted-ok))))

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

(defn person-path
  "The first path this change touches that its policy keeps for a person
  (ticket 15305c4a): a `touched_paths` entry one of the policy's
  `person_paths` globs matches, in the deny list's grammar
  (`gate/path-glob-matches?`), or nil."
  [change policy]
  (let [globs (keep #(some-> % str not-empty) (get-in policy [:data :person_paths]))]
    ;; the owner's go_live yes for this head is a person's (`go-live-yeses!`)
    (when (and (seq globs) (nil? (::go-live-yes change)))
      (some (fn [path]
              (when (some #(gate/path-glob-matches? % path) globs)
                (str path)))
            (get-in change [:data :touched_paths])))))

(def engine-lane
  "The catch-all lane every policy implies and none declares."
  "engine")

(defn- org-dir
  "The org directory a path stands in — `orgs/<x>` for orgs/<x>/… — or
  nil for a path that is not inside one."
  [path]
  (let [[top org & more] (str/split (str path) #"/")]
    (when (and (= "orgs" top) (not-empty org) (seq more))
      (str "orgs/" org))))

(defn lane-of
  "The lane this change stands in (ticket 9b27142e): the name of the
  first of its policy's `lanes` whose globs cover EVERY path the change
  touches, in the grammar of `person-path` (`gate/path-glob-matches?`).
  A lane that covers by `orgs/**` is one lane for each org directory,
  `orgs/<x>`. A change no lane covers, one that spans lanes or org
  directories, one that touches nothing, and every change of a policy
  that declares no lanes is `engine-lane`. Pure: it reads the two rows
  it is given and nothing else."
  [change policy]
  (let [paths (keep #(some-> % str not-empty) (get-in change [:data :touched_paths]))
        covers? (fn [globs path] (some #(gate/path-glob-matches? % path) globs))
        lane (when (seq paths)
               (some (fn [lane]
                       (let [globs (keep #(some-> % str not-empty) (:paths lane))
                             lane-name (some-> (:name lane) str not-empty)]
                         (when (and lane-name (seq globs)
                                    (every? #(covers? globs %) paths))
                           {:globs globs :name lane-name})))
                     (map #(update-keys % keyword)
                          (filter map? (get-in policy [:data :lanes])))))]
    (cond
      (nil? lane) engine-lane
      (some #{"orgs/**"} (:globs lane))
      (let [orgs (set (map org-dir paths))]
        (if (and (= 1 (count orgs)) (first orgs)) (first orgs) engine-lane))
      :else (:name lane))))

(defn lane-max-lines-of
  "The size ceiling for a change in LANE (ticket bacb84f2): the policy's
  `lane_max_lines` maps a lane prefix to a ceiling of its own, and the
  lane `orgs/<x>` takes the entry `orgs` as the lane `orgs` does. The
  longest prefix that names the lane wins. A lane no entry names —
  `engine-lane` among them — and every lane of a policy without the map
  takes `max-lines-of`."
  [policy lane]
  (let [lane (some-> lane str not-empty)
        names? (fn [prefix]
                 (and (not-empty prefix)
                      (or (= lane prefix)
                          (str/starts-with? lane (str prefix "/")))))
        hit (when lane
              (some (fn [[prefix n]]
                      (when (and (integer? n) (names? prefix)) n))
                    (->> (get-in policy [:data :lane_max_lines])
                         (map (fn [[k n]]
                                ;; a JSON key `orgs/x` reads as the
                                ;; keyword :orgs/x, whose name is x
                                [(str/replace (if (keyword? k) (subs (str k) 1) (str k))
                                              #"/+$" "")
                                 n]))
                         (sort-by (comp - count first)))))]
    (long (or hit (max-lines-of policy)))))

(defn max-lines-for
  "The size ceiling for one submit that touches PATHS: the ceiling of
  the lane `lane-of` puts those paths in. A submit that names no paths
  stands in `engine-lane`, and so takes max_lines."
  [policy paths]
  (let [paths (keep #(some-> (if (map? %) (:path %) %) str not-empty) paths)]
    (lane-max-lines-of policy
                       (lane-of {:data {:touched_paths (vec paths)}} policy))))

(defn house-merges-change?
  "Does the house's merge pass merge THIS change? Only when its policy
  `house-pass-merges?` and the change touches no path the policy keeps
  for a person (`person-path`): such a change is treated as auto_merge
  false for itself alone, and asks for its merge as a held call."
  [change policy]
  (boolean (and (house-pass-merges? policy)
                (nil? (person-path change policy)))))

(defn person-path-reason
  "What a change kept for a person says on its row."
  [path]
  (str "touches " path ", which this repository's policy keeps for a person"))

(defn person-marks
  "The mark of every change a person merges: one in a repository a
  person merges on GitHub (`person-merges-on-github?`) → {:line_why
  \"person\"}, and one in a house-merged repository that touches a path
  its policy keeps for a person (`person-path`) → the same, with that
  path in its `line_reason`."
  [changes by-repo]
  (into {}
        (keep (fn [c]
                (let [policy (get by-repo (str (get-in c [:data :repository])))
                      path (when (house-pass-merges? policy) (person-path c policy))]
                  (cond
                    (person-merges-on-github? policy)
                    [(str (:id c)) {:line_why "person"}]
                    path
                    [(str (:id c)) {:line_why "person"
                                    :line_reason (person-path-reason path)}]))))
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

;; THE PARK IS ON THE ROW (ticket 1ed59295, spec R-C.15). A head the rig
;; refused for good was kept in the sweeper's `seen`, which a restart
;; forgot, so the next process asked the rig again. The house now walks
;; the change's own `merge_asked` door: the head it asked at, and for a
;; parked refusal the rig's reason and the refusal's name. A new head is
;; a new offer, because the park counts only at the head it names.

(defn parked-answer?
  "Did the rig's merge answer park the head: a `parked-refusals` name
  that does not say the branch is behind?"
  [answer]
  (boolean (and (contains? parked-refusals (refused answer))
                (not (behind? answer)))))

(defn parked-head?
  "Does the change's row say the rig parked its current head?"
  [change]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (boolean (and head
                  (= head (some-> (get-in change [:data :merge_asked_head]) str))
                  (some-> (get-in change [:data :merge_refused]) str not-empty)))))

(defn merge-asked!
  "Write one merge ask on the change row with the engine's own hand: the
  head asked at and, when `answer` parked it (`parked-answer?`), the
  rig's reason and the refusal's name. Any other answer, or nil, clears
  a park the row holds. Writes only when the row would change. Throws
  nothing. → true when the row moved."
  [eng change answer]
  (let [id (str (:id change))
        data (:data change)
        head (some-> (:head_sha data) str not-empty)
        as (when (parked-answer? answer) (refused answer))
        why (when as (let [r (reason-of answer)] (subs r 0 (min 500 (count r)))))]
    (boolean
     (when (and head
                (or as (some-> (:merge_refused data) str not-empty))
                (not= [head why as]
                      [(some-> (:merge_asked_head data) str)
                       (:merge_refused data) (:merge_refused_as data)]))
       (try
         (inv/invoke! eng :change id :merge_asked
                      {:merge_asked_head head :merge_refused why
                       :merge_refused_as as}
                      {:principal mirror/source-principal})
         true
         (catch Exception e
           (warn! "the merge ask of " id " was not written on its row ("
                  (ex-message e) "); the next pass asks again")
           false))))))

;; A FRONT THAT HOLDS ITS BASE (ticket 5ba28a11). A merge answer can
;; read as behind for a branch that is not: a red carried over on a head
;; read green (`head-answer`), or a refusal `behind-reason` matches. The
;; update then has nothing to bring forward, no new head ever comes, and
;; the front waited on `update` for good. So the line takes two words
;; over that reading: the mirror's own compare at this head, and the
;; rig's `update_branch` saying there was nothing to bring forward.

(def ^:private nothing-forward-reason
  "What GitHub says when it is asked to update a branch that already
  holds its base's head."
  #"(?i)no new commits|already up.to.date|^up.to.date|nothing to (update|bring forward)")

(defn nothing-forward?
  "Does the rig's `update_branch` answer say the branch already holds
  its base, so there was nothing to bring forward?"
  [answer]
  (or (contains? #{"current" "up_to_date"} (some-> (:state answer) name))
      (boolean (and (refused answer)
                    (re-find nothing-forward-reason
                             (str (refused answer) " " (:reason answer)))))))

;; WHAT AN ASK LEAVES ON THE ROW (ticket a1a133ca). The pass asks the
;; rig's `update_branch` and its `rerun` once per head, and the head is
;; written on the change's row (`updated_head`, `current_head`,
;; `rerun_head`) through the ctx's `:update-asked!` and `:rerun-asked!`,
;; the change's hidden `update_asked` and `rerun_asked`, so a restart
;; does not ask it again. The rows were read before the pass, so the
;; ctx's `:recorded` (id → what this pass wrote) is read over them
;; until the next pass reads them again.

(defn as-recorded
  "The change as its row reads with `recorded` (id → fields) written on
  it."
  [recorded change]
  (if-some [r (get recorded (str (:id change)))]
    (update change :data merge r)
    change))

(defn with-records
  "Each of `changes` as its row reads with `recorded` written on it."
  [recorded changes]
  (mapv #(as-recorded recorded %) changes))

(defn- recorded-of [ctx] (some-> (:recorded ctx) deref))

(defn- with-recorded
  "The ctx with a `:recorded` for this pass, when it carries none."
  [ctx]
  (update ctx :recorded #(or % (atom {}))))

(defn- record!
  "Write `input` on change `id`'s row through the ctx's door `k`
  (`:update-asked!` or `:rerun-asked!`), and in its `:recorded` for the
  rest of this pass."
  [ctx k id input]
  (some-> (:recorded ctx) (swap! update id merge input))
  (when-some [f (get ctx k)] (f id input)))

(defn holds-base?
  "Does the line know this change's head holds its base? The rig's
  `update_branch` said so for this head (`current_head`, which
  `update_asked` writes), or the mirror's compare did: `behind_base`
  false, read at the head `held_head` names."
  [change]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (boolean
     (and head
          (or (= head (some-> (get-in change [:data :current_head]) str))
              (and (false? (get-in change [:data :behind_base]))
                   (= head (some-> (get-in change [:data :held_head]) str))))))))

(defn- answer-words
  "A merge answer in one clause, for the log."
  [answer]
  (or (not-empty
       (str/join ", "
                 (keep (fn [k]
                         (when-some [v (some-> (get answer k) str not-empty)]
                           (str (name k) " " v)))
                       [:state :rig_state :refused :reason :waits_on])))
      "no answer"))

(defn- conflicted-change? [change]
  (= "conflicted" (str (get-in change [:data :mergeable]))))

(defn update-args
  "What the rig's `update_branch` is told: the pull request and the head
  it may bring forward."
  [change]
  {:repo (str (get-in change [:data :repository]))
   :number (get-in change [:data :number])
   :head_sha (str (get-in change [:data :head_sha]))})

(defn- update-asked?
  "Was the rig's `update_branch` already asked at `head` for this
  change? Its row says so: `updated_head`, which `update_asked` writes."
  [change head]
  (= head (some-> (get-in change [:data :updated_head]) str)))

(defn- update-behind!
  "Bring one behind change's branch up to date, once for this head. The
  head is written on the row as `updated_head` (the ctx's
  `:update-asked!`, ticket a1a133ca); a missing power or no answer is
  not written, so the next pass asks again. An answer that there was
  nothing to bring forward (`nothing-forward?`) writes it as
  `current_head` as well."
  [ctx change id head]
  (cond
    (conflicted-change? change)
    (warn! id " is behind and conflicted; the failing path has it")

    (true? (get-in change [:data :draft]))
    (warn! id " is behind but a draft; it is not brought forward")

    (update-asked? (as-recorded (recorded-of ctx) change) head)
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
        (let [current? (nothing-forward? answer)]
          (record! ctx :update-asked! id
                   (cond-> {:updated_head head} current? (assoc :current_head head)))
          (cond
            current?
            (warn! id " at " head " already holds its base ("
                   (reason-of answer) "); the merge is asked again")

            why
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
  order it is worked. A change needs a number, a head its row has not
  parked (`parked-head?`), and a policy that merges it (`house-merges-change?`). `priority-of` is
  change → its ticket's priority, or nil."
  [changes by-repo priority-of]
  (->> changes
       (filter (fn [change]
                 (let [head (some-> (get-in change [:data :head_sha]) str not-empty)
                       policy (get by-repo (str (get-in change [:data :repository])))]
                   (and (get-in change [:data :number]) head policy
                        (house-merges-change? change policy)
                        (not (parked-head? change))))))
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
  (ticket fc997a92). The rig's own word is kept as `rig_state`.
  A rig's `interrupted` for that very head is carried over the same way
  as its `red` (ticket 68423628)."
  [change answer]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)
        state (answer-state answer)]
    (if (and head
             (= head (some-> (get-in change [:data :green_head]) str))
             (or (contains? #{"red" "interrupted"} state)
                 (and (= "waiting" state)
                      (true? (get-in change [:data :behind_base])))))
      (assoc answer :state "behind" :rig_state state)
      answer)))

(defn rerun-spent?
  "Were this head's interrupted checks already run again (ticket
  68423628)? The row says so: `rerun_head`, which the line writes
  through `rerun_asked` (ticket a1a133ca) and the forge mirror through
  `observe`."
  [change]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (boolean
     (and head
          (= head (some-> (get-in change [:data :rerun_head]) str))))))

(defn out-of-line
  "Why a change of a line does not stand in it this pass, or nil when it
  does. A conflicted branch is the failing path's, a draft is not
  brought forward, a red head waits on its seat, and a head the rig
  refused for good (its row's `merge_asked`, or this pass's answer) is
  parked. A head whose checks were
  interrupted stands in the line until they were run again once
  (`rerun-spent?`), and is `interrupted` after. A change the rig just
  merged has left, and says `merged`. `answer` is what the rig's merge
  said of it this pass, or nil."
  [change answer]
  (cond
    (conflicted-change? change) "conflicted"
    (true? (get-in change [:data :draft])) "draft"
    (= "red" (answer-state answer)) "red"
    (and (= "interrupted" (answer-state answer))
         (rerun-spent? change)) "interrupted"
    (or (parked-head? change) (parked-answer? answer)) "parked"
    (= "merged" (answer-state answer)) "merged"))

(defn front-of
  "The change at the front of a line: the first one that stands in it
  (`out-of-line`). `answers` is change id → the rig's merge answer this
  pass; without it, only a conflicted branch, a draft or a head its row
  parked steps out of the line."
  ([line] (front-of line {}))
  ([line answers]
   (first (remove #(out-of-line % (get answers (str (:id %)))) line))))

(defn- offer-merge!
  "One merge call for one change, with the engine's own hand → the rig's
  answer, or nil. Each answer the rig gave is written on the row by
  `:merge-asked!` in `ctx` (change, answer — `merge-asked!`): a refusal
  a new pass cannot fix parks the head there, so a restart keeps it,
  and any other answer clears an old park."
  [ctx change policy]
  (let [id (str (:id change))
        head (str (get-in change [:data :head_sha]))]
    (try
      (let [answer (ask ctx :merge (merge-args change policy))
            why (refused answer)]
        (when-some [asked! (when (and answer
                                      (not (contains? missing-power-refusals why)))
                             (:merge-asked! ctx))]
          (asked! change answer))
        (cond
          (nil? answer)
          (warn! "the merge of " id " had no answer; the next pass asks again")

          (contains? missing-power-refusals why)
          (warn! "the rig has no merge yet (" why "); the next pass asks again")

          (behind? answer)
          nil

          (contains? parked-refusals why)
          (warn! "the rig refused to merge " id " at " head " ("
                 (reason-of answer) "); this head is not asked again")

          why
          (warn! "the rig refused to merge " id " at " head " ("
                 (reason-of answer) "); the next pass asks again")

          :else nil)
        answer)
      (catch Exception e
        (warn! "the merge of " id " failed (" (ex-message e)
               "); the next pass asks again")
        nil))))

(defn rerun-args
  "What the rig's `rerun` is told: the repository and the branch whose
  head's failed jobs start again."
  [change]
  {:repo (str (get-in change [:data :repository]))
   :branch (str (get-in change [:data :head_branch]))})

(defn- rerun-interrupted!
  "Ask the rig to run an interrupted front's checks again, once for this
  head (ticket 68423628). The head is written on the row as `rerun_head`
  (the ctx's `:rerun-asked!`, ticket a1a133ca); a missing power or no
  answer is not written, so the next pass asks
  again. → true when the checks run again and the front waits on them; a
  rerun the rig refused is spent all the same, and the change leaves the
  line."
  [ctx change id head answer]
  (let [res (ask ctx :rerun (rerun-args change))
        why (refused res)]
    (cond
      (nil? res)
      (do (warn! "the rerun of " id " had no answer; the next pass asks again")
          false)

      (contains? missing-power-refusals why)
      (do (warn! "the rig has no rerun yet (" why "); the next pass asks again")
          false)

      :else
      (do (record! ctx :rerun-asked! id {:rerun_head head})
          (if why
            (warn! "the rig refused to rerun " id " at " head " ("
                   (reason-of res) "); it leaves the line as interrupted")
            (warn! id " at " head " was interrupted ("
                   (str/join ", " (map str (:interrupted answer)))
                   "); its checks run again, once for this head"))
          (nil? why)))))

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

(defn train-key
  "Where a lane's train stands among the `trains` a pass fills (ticket
  55d584f2): the engine lane's under its repository, as the one train
  of a repository always stood, and any other lane's under
  [repository lane]."
  [repo lane]
  (if (= engine-lane lane) repo [repo lane]))

(defn- lane-of-train
  "The lane a train rides in: the one it carries, else `engine-lane`."
  [train]
  (or (some-> (:lane train) str not-empty) engine-lane))

(defn- train-done?
  "Does the change's row say a merge train sent its lane one at a time
  while it carried this head (its `train_done` door, ticket 1ae1afbc)?
  While one change in a lane's line says so the lane builds no train,
  across a restart; a new head is a new offer."
  [change]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (boolean (and head
                  (= head (some-> (get-in change [:data :train_done_head]) str))))))

(defn trains-of
  "Every train standing on a policy → {lane train}: the engine lane's in
  `line_train`, as before lanes, and each other lane's in
  `lane_trains`, which carries its `lane`."
  [policy]
  (into (if-some [t (get-in policy [:data :line_train])] {engine-lane t} {})
        (keep (fn [t]
                (let [lane (lane-of-train t)]
                  (when (not= engine-lane lane) [lane t]))))
        (get-in policy [:data :lane_trains])))

(defn train-marks
  "What a pass writes on each policy from the `trains` it filled
  (`train-key` → the train standing now, nil for none) → {repo marks}:
  the engine lane's train as `line_train`, and the others' as the whole
  `lane_trains`, a lane this pass did not touch kept as the policy had it."
  [by-repo trains]
  (update-vals
   (reduce-kv
    (fn [acc k t]
      (if (vector? k)
        (let [[repo lane] k
              had (or (get-in acc [repo :lane_trains])
                      (vec (get-in (get by-repo repo) [:data :lane_trains])))]
          (assoc-in acc [repo :lane_trains]
                    (cond-> (filterv #(not= lane (lane-of-train %)) had)
                      t (conj (assoc t :lane lane)))))
        (assoc-in acc [k :line_train] t)))
    {} trains)
   (fn [marks]
     (cond-> marks
       (contains? marks :lane_trains) (update :lane_trains not-empty)))))

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
  [line answers policy]
  (let [answer-of #(get answers (str (:id %)))
        in (remove #(out-of-line % (answer-of %)) line)]
    (if (and (seq in) (green-behind? (answer-of (first in))))
      (vec (take (train-size-of policy) (filter #(green-behind? (answer-of %)) in)))
      [])))

(defn- pass-now [ctx]
  (if-some [f (:now-fn ctx)] (f) (Instant/now)))

(defn- number-of [change] (get-in change [:data :number]))

(defn- dispatch-checks!
  "Dispatch a built train's checks → the train with its `run_id`, nil
  when no run showed yet (`train_status` then reads it by branch). A
  train of a lane other than engine names it as `lane` (ticket
  929ada09), and tests.yml runs that lane's jobs instead of the diff's."
  [ctx repo train]
  (let [answer (ask ctx :train_checks
                    (cond-> {:repo repo :branch (:branch train)}
                      (:workflow train) (assoc :workflow (:workflow train))
                      (:lane train) (assoc :lane (:lane train))))]
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
  did not merge cleanly stays in the line untouched. The train carries
  the heads its riders had (`heads`, beside `changes`), so its red is
  not written on a head it did not test (e23f8842), and a restart
  still knows them (ffd36c98). A `lane` other than engine rides on the
  train before its checks are dispatched, so the dispatch names it."
  ([ctx repo policy riders] (build-train! ctx repo policy riders nil))
  ([ctx repo policy riders lane]
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
                                   :heads (mapv #(str (get-in % [:data :head_sha])) rode)
                                   :head (some-> (:head answer) str)
                                   :base_head (some-> (:base_head answer) str)
                                   :started_at (pass-now ctx)}
                            (test-workflow-of policy)
                            (assoc :workflow (test-workflow-of policy))
                            (and lane (not= engine-lane lane))
                            (assoc :lane (str lane)))))))))

(defn- one-at-a-time!
  "Delete a train and send its lane one at a time, so no train is built
  only to be thrown away again: every change it carried walks its
  `train_done` door (`:train-done!` in `ctx`, change id and why), and
  the lane builds no train while one of them stands in its line at that
  head (`train-done?`), a restart included (ticket 1ae1afbc). → nil."
  [ctx repo train why]
  (warn! repo ": the train " (:branch train) " " why
         "; it is deleted and the line goes one at a time")
  (ask ctx :train_delete {:repo repo :branch (:branch train)})
  (if-some [done! (:train-done! ctx)]
    (doseq [id (:changes train)]
      (done! (str id) (str "The merge train " (:branch train) " " why
                           "; the line goes one at a time.")))
    (warn! "no hand marks the train " (:branch train) " done this pass"))
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
  [ctx repo policy train]
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
      (one-at-a-time! ctx repo train
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
  "The riders a standing train carries, as `build-train!` takes them,
  each with the head the train judged when the train carries one."
  [train]
  (mapv (fn [id n head] {:id id :data {:number n :head_sha head}})
        (:changes train) (:prs train)
        (concat (:heads train) (repeat nil))))

(defn- bisect-train!
  "A red train looks for its red: the front half of its changes (at
  least one) rides again, and the rest go back to the line for the next
  train. A change red alone on a train goes back to its seat as a red
  head does (`:train-red!` in `ctx`, change id and why) — unless its
  head moved since the train was built (the train keeps the riders'
  heads as `heads`, and `:head-of` in `ctx` reads the head
  now): the train judged a head the change no longer has, so the change
  stays in the line and its new head rides the next train (ticket
  e23f8842). At most
  log2(n)+1 trains per train of n, then the line goes one at a time.
  → the train that stands after it."
  [ctx repo policy train]
  (let [riders (riders-of train)
        size (or (:size train) (count riders))
        tries (inc (long (or (:tries train) 1)))]
    (cond
      (= 1 (count riders))
      (let [id (str (:id (first riders)))
            judged (some-> (first (:heads train)) str not-empty)
            now (when-some [head-of (:head-of ctx)]
                  (some-> (head-of id) str not-empty))]
        (warn! repo ": " id " is red alone on the train " (:branch train))
        (cond
          (and judged now (not= judged now))
          (warn! repo ": the train " (:branch train) " judged " judged
                 " and " id " is at " now " now; its new head rides again")

          (:train-red! ctx)
          ((:train-red! ctx) id (str "The merge train " (:branch train)
                                     " went red with this change alone on it."))

          :else (warn! "no hand marks " id " red this pass"))
        (ask ctx :train_delete {:repo repo :branch (:branch train)})
        nil)

      (> tries (train-cap size))
      (one-at-a-time! ctx repo train
                      (str "found no one red change in " (dec tries) " trains"))

      :else
      (some-> (build-train! ctx repo policy
                            (subvec riders 0 (quot (count riders) 2))
                            (:lane train))
              (assoc :tries tries :size size)
              (cond-> (:lane train) (assoc :lane (:lane train)))))))

(defn- run-of [x] (some-> (:run_id x) str not-empty))

(defn- retry-train!
  "A train whose run was cancelled or timed out runs its checks once
  more, and that counts as one of its trains; the second time, the line
  goes one at a time. The cancelled run stays on the train as
  `stale_run_id`, so a retry read by its branch never reads that run
  again. → the train that stands after it."
  [ctx repo train verdict]
  (if (:retried train)
    (one-at-a-time! ctx repo train (str "finished " verdict " twice"))
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
  [ctx repo policy train verdict]
  (cond
    (= "success" verdict) (land-train! ctx repo policy train)

    (not= "train" (merge-strategy-of policy))
    (do (warn! repo ": the train " (:branch train) " finished " verdict
               "; it is deleted and the line goes one at a time")
        (ask ctx :train_delete {:repo repo :branch (:branch train)})
        nil)

    (= "failure" verdict) (bisect-train! ctx repo policy train)
    :else (retry-train! ctx repo train verdict)))

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
  [ctx repo policy train]
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
              (train-finished! ctx repo policy train "cancelled"))
      (or (nil? st) (= "pending" st))
      (if (pr-run-overdue? ctx known)
        (do (warn! repo ": no run showed on the pull request of " (:branch train)
                   " in " pr-run-grace-seconds " s; its checks are dispatched")
            (dispatch-checks! ctx repo (dissoc known :pr_run)))
        known)
      :else (train-finished! ctx repo policy known st))))

;; A BEHIND CHANGE MISSING A REQUIRED CHECK (ticket 7100a73b). A head
;; that predates the workflow its required check lives in never runs
;; that check, so it can neither go green nor leave the line, wherever
;; it stands. Past the policy's `queued_check_minutes`, counted from
;; `held_since` as the forge counts a missing check (ticket 3520d15b),
;; the pass brings any such change up to date through the front's own
;; `update_branch`, once per head (the row's `updated_head`), so the
;; check runs on a new head. The forge leaves that change's missing red
;; to this pass (`house-brings-forward?`). An update the rig does not
;; make sends the change to its seat as failing (the ctx's
;; `:update-red!`): a conflict names `merge-conflict` and what the rig
;; said, any other refusal names each missing check with the reason.

(def ^:private conflict-reason #"(?i)conflict|not.mergeable")

(defn update-conflict?
  "Does the rig's `update_branch` answer say the branch conflicts with
  its base?"
  [answer]
  (boolean (when-some [why (refused answer)]
             (or (= not-mergeable-refusal why)
                 (re-find conflict-reason (str why " " (:reason answer)))))))

(defn missing-behind?
  "Did the forge read, at this change's very head, a required check with
  no run at all and a base that moved?"
  [change]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (boolean
     (and head
          (= head (some-> (get-in change [:data :missing_checks_head]) str))
          (seq (get-in change [:data :missing_checks]))
          (true? (get-in change [:data :behind_base]))))))

(defn house-brings-forward?
  "Will the house pass bring this change up to date for a required check
  its head never ran? Its policy's house pass merges it, and it is
  `missing-behind?`."
  [change policy]
  (boolean (and policy
                (house-merges-change? change policy)
                (missing-behind? change))))

(defn missing-past-wait?
  "Has this change waited longer than the policy's `queued_check_minutes`
  since its head was first seen held (`held_since` at `held_head`), at
  `now`?"
  [change policy now]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)
        since (when (and head (= head (some-> (get-in change [:data :held_head]) str)))
                (try (Instant/parse (str (get-in change [:data :held_since])))
                     (catch Exception _ nil)))]
    (boolean
     (and since
          (> (quot (- (inst-ms now) (inst-ms since)) 60000)
             (queued-check-minutes-of policy))))))

(defn- clipped-to [s n]
  (let [s (str s)] (subs s 0 (min (long n) (count s)))))

(defn update-red-input
  "What a change whose update the rig did not make goes failing with:
  {:failing_checks :conflicts :why}."
  [change answer]
  (let [why (reason-of answer)]
    (if (update-conflict? answer)
      {:failing_checks ["merge-conflict"]
       :conflicts (mapv #(clipped-to % 400)
                        (or (seq (:conflicts answer)) [why]))
       :why (clipped-to (str "bringing the branch up to date conflicts with"
                             " its base: " why)
                        480)}
      {:failing_checks (mapv #(clipped-to
                               (str "check " % " has no run after the wait;"
                                    " the house could not bring the branch"
                                    " up to date (" why ")")
                               200)
                             (get-in change [:data :missing_checks]))
       :why (clipped-to (str "the house could not bring the branch up to"
                             " date for its missing checks: " why)
                        480)})))

(defn- bring-missing-forward!
  "Bring each change of a line that is `missing-behind?` past the
  policy's wait up to date, once for its head; one the rig does not
  update goes to its seat through the ctx's `:update-red!`, and its head
  is remembered only once that moved it."
  [ctx line policy answers]
  (let [now (pass-now ctx)
        red! (or (:update-red! ctx) (constantly false))]
    (doseq [change line
            :let [id (str (:id change))
                  head (str (get-in change [:data :head_sha]))]
            :when (and (missing-behind? change)
                       (missing-past-wait? change policy now)
                       (not (true? (get-in change [:data :draft])))
                       (not= "merged" (answer-state (get @answers id)))
                       (not (update-asked? (as-recorded (recorded-of ctx) change) head)))]
      (let [answer (ask ctx :update_branch (update-args change))]
        (cond
          (nil? answer)
          (warn! "the update of " id " had no answer; the next pass asks again")

          (or (refused answer) (nothing-forward? answer))
          (when (red! id (update-red-input change answer))
            (record! ctx :update-asked! id {:updated_head head})
            (warn! "the rig did not bring " id " at " head " up to date ("
                   (reason-of answer) "); it goes back to its seat"))

          :else
          (do (record! ctx :update-asked! id {:updated_head head})
              (warn! id " at " head " waited past the limit on "
                     (str/join ", " (map str (get-in change [:data :missing_checks])))
                     ", which its head never ran; it was brought up to date,"
                     " once for this head")))))))

;; A CHANGE HELD ONLY FOR BEING BEHIND ITS BASE (ticket 6add57e9). The
;; line brings only its front forward, and only a change the house
;; merges stands in a line: one a person merges, one an org_plan not yet
;; live names, one whose ticket waits on another, would sit green and
;; behind for good, and its seat may have no round left to move it. So
;; each submitted change outside the lines whose head the mirror read
;; green (`green_head`) and behind its base (`behind_base` at
;; `held_head`) is brought up to date with the rig's `update_branch` —
;; a merge commit, as a system act that spends no round — once per head,
;; so at most once per base move. A conflict is not resolved: it is
;; written on the row as `conflicts` through the ctx's
;; `:held-conflict!`, and the change stays held.
;;
;; A FILMED PLAN'S CHANGE WAITS FOR go_live (ticket 8288529f). Each move
;; of an org_plan's change refilms the plan, so a change a `simulated`
;; plan names is left behind its base unless that plan's `base_follow`
;; says `always` or `when_org_dir_changes` (ticket ce8a4e17: the
;; findings pass then refilms only a move that changed the org); the
;; default, `at_go_live` (or none), brings it forward only from
;; `going_live` on. The ctx's `:plan-of` answers the plan a change id
;; names.
;;
;; A PLAN WAITING FOR ITS FILM KEEPS ITS HEAD (ticket eaa316cc). A
;; `proposed` or `filming` plan whose take has not landed at the
;; change's head is left behind whatever its `base_follow`: each merge
;; of the base moves the head the director is filming, and on a busy
;; base no take would ever land. It is brought forward once the take
;; lands at that head, or when the plan returns to drafting.

(defn- take-landed-at?
  "Has `plan`'s take landed (`take_landed_at`) and is it a film of
  `head`? The director may hand in a short sha, of seven characters
  and up, so a take the head begins with names it."
  [plan head]
  (let [tk (str (get-in plan [:data :take_sha]))
        hd (str head)]
    (boolean (and (some? (get-in plan [:data :take_landed_at]))
                  (<= 7 (count tk))
                  (str/starts-with? hd tk)))))

(defn follows-base?
  "Does the held-forward pass bring forward the change `plan` names, at
  `head`? Yes without a plan. A `proposed` or `filming` plan whose take
  has not landed at `head` is left behind. Otherwise yes under
  `base_follow` `always` or `when_org_dir_changes`, and in every state
  but `simulated`."
  ([plan] (follows-base? plan nil))
  ([plan head]
   (let [state (some-> (:state plan) name)]
     (or (nil? plan)
         (and (not (and (contains? #{"proposed" "filming"} state)
                        (not (take-landed-at? plan head))))
              (or (contains? #{"always" "when_org_dir_changes"}
                             (some-> (get-in plan [:data :base_follow]) name))
                  (not= "simulated" state)))))))

(defn held-only-behind?
  "Is this change held only for being behind its base? Its head is the
  one the mirror read green (`green_head`) and behind (`behind_base` at
  `held_head`), and it is neither a draft nor conflicted."
  [change]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)]
    (boolean
     (and head
          (= head (some-> (get-in change [:data :green_head]) str))
          (= head (some-> (get-in change [:data :held_head]) str))
          (true? (get-in change [:data :behind_base]))
          (not (true? (get-in change [:data :draft])))
          (not (conflicted-change? change))))))

(defn bring-held-forward!
  "Bring each of `changes` that is `held-only-behind?` up to date with
  the rig's `update_branch`, once for its head (the row's `updated_head`,
  ticket a1a133ca). A conflict goes to the ctx's `:held-conflict!` as
  id, conflicts; no answer or a missing power is not remembered, so the
  next pass asks again. A change whose plan (the ctx's `:plan-of`) does
  not `follows-base?` is left behind. → the number of `update_branch`
  calls made."
  [ctx changes]
  (let [ctx (with-recorded ctx)
        conflict! (or (:held-conflict! ctx) (constantly false))
        plan-of (or (:plan-of ctx) (constantly nil))
        asked (volatile! 0)]
    (doseq [change changes
            :let [id (str (:id change))
                  head (str (get-in change [:data :head_sha]))]
            :when (and (held-only-behind? change)
                       (follows-base? (plan-of id) head)
                       (not (update-asked? (as-recorded (recorded-of ctx) change) head)))]
      (vswap! asked inc)
      (let [answer (ask ctx :update_branch (update-args change))
            why (refused answer)]
        (cond
          (nil? answer)
          (warn! "the update of " id " had no answer; the next pass asks again")

          (contains? missing-power-refusals why)
          (warn! "the rig has no update_branch yet (" why
                 "); the next pass asks again")

          :else
          (do (record! ctx :update-asked! id
                       (cond-> {:updated_head head}
                         (nothing-forward? answer) (assoc :current_head head)))
              (cond
                (update-conflict? answer)
                (do (conflict! id (:conflicts (update-red-input change answer)))
                    (warn! id " at " head " is held behind its base and conflicts"
                           " with it (" (reason-of answer) "); it stays held"))

                (nothing-forward? answer)
                (warn! id " at " head " already holds its base ("
                       (reason-of answer) ")")

                why
                (warn! "the rig refused to update " id " at " head " ("
                       (reason-of answer) ")")

                :else
                (warn! id " at " head " was held only behind its base; it was"
                       " brought up to date, spending no round"))))))
    @asked))

(defn work-lines!
  "One merge call for every change of every line, with the engine's own
  hand; then only a line's front is brought up to date when it is
  behind, or when it waits on a required check its head never ran
  while its base moved (`stale-waiting?`). A front that reads behind
  and holds its base (`holds-base?`) is not brought up to date: the pass
  logs the answer it read, and when the update itself said there was
  nothing to bring forward the merge is asked once more.
  The front is chosen after the answers, so a change that went
  red or was parked this pass does not hold the line. `answers`, when
  given, is an atom the pass fills with change id → the rig's answer.
  A repository whose policy says `merge_strategy: train` sends each
  lane's front and the green changes behind it in that lane as one
  train instead (`build-train!`, ticket 55d584f2): a train never mixes
  lanes, and each lane keeps its own standing train (`trains-of`), read
  once (`advance-train!`) while the line waits. The repository's front
  waits when a train stands in its lane. `trains`, when given, is an
  atom the pass fills with `train-key` → the train that stands now, nil
  for none, for each lane whose train it built or read. A
  standing train whose repository has no line this pass — every rider
  merged or left, or the repository is deploy-held — is read as well,
  so it still finishes and leaves the policy.
  → the number of `merge` calls made."
  ([ctx lines by-repo] (work-lines! ctx lines by-repo (atom {})))
  ([ctx lines by-repo answers]
   (work-lines! ctx lines by-repo answers (atom {})))
  ([ctx lines by-repo answers trains]
   (let [asked (volatile! 0)
         ctx (with-recorded ctx)]
     (doseq [[repo line] lines
             :let [policy (get by-repo repo)
                   line (with-records (recorded-of ctx) line)]]
       ;; with a deploy check, one merge ends the line's offers: the
       ;; next change waits on its deploy (ticket 47217098)
       (loop [[change & more] line]
         (when change
           (vswap! asked inc)
           (let [answer (offer-merge! ctx change policy)]
             (swap! answers assoc (str (:id change)) (head-answer change answer))
             (when-not (and (deploy-check-of policy)
                            (= "merged" (answer-state answer)))
               (recur more)))))
       (let [front (front-of line @answers)
             id (some-> front :id str)
             in-lane #(lane-of % policy)
             standing (trains-of policy)
             built (into {}
                         (keep
                          (fn [lane]
                            (when (and (nil? (get standing lane))
                                       (= "train" (merge-strategy-of policy))
                                       (not-any? #(and (= lane (in-lane %)) (train-done? %))
                                                 line))
                              (let [riders (train-riders (filterv #(= lane (in-lane %)) line)
                                                         @answers policy)]
                                (when-some [t (when (next riders)
                                                (build-train! ctx repo policy riders lane))]
                                  [lane (cond-> t
                                          (not= engine-lane lane) (assoc :lane lane))])))))
                         (distinct (map in-lane line)))]
         (bring-missing-forward! ctx line policy answers)
         (warn! repo ": " (or id "nothing") " is the front of the merge line, "
                (count (remove #(= id (str (:id %))) line)) " wait")
         (doseq [[lane train] standing]
           (swap! trains assoc (train-key repo lane)
                  (advance-train! (assoc ctx :answers answers)
                                  repo policy train)))
         (doseq [[lane train] built]
           (swap! trains assoc (train-key repo lane) train))
         (cond
           ;; a train stands in the front's lane: the front rides or waits
           (and front (let [lane (in-lane front)]
                        (or (contains? standing lane) (contains? built lane))))
           nil
           ;; an interrupted front that stands in the line was not run
           ;; again yet: it is, and it waits on its checks (ticket 68423628)
           (and front (= "interrupted" (answer-state (get @answers id))))
           (when (rerun-interrupted! ctx front id
                                     (str (get-in front [:data :head_sha]))
                                     (get @answers id))
             (swap! answers update id assoc :state "waiting" :waits_on "checks"
                    :rig_state "interrupted"))
           (and front (or (behind? (get @answers id))
                          (stale-waiting? front (get @answers id))))
           (let [head (str (get-in front [:data :head_sha]))]
             (if (holds-base? (as-recorded (recorded-of ctx) front))
               (warn! id " at " head " reads behind ("
                      (answer-words (get @answers id))
                      ") and holds its base; it is not brought up to date,"
                      " and the next pass asks the merge again")
               (do (update-behind! ctx front id head)
                   (when (holds-base? (as-recorded (recorded-of ctx) front))
                     (vswap! asked inc)
                     (let [again (head-answer front (offer-merge! ctx front policy))]
                       (swap! answers assoc id again)
                       (warn! id " at " head " was offered again: "
                              (answer-words again))))))))))
     ;; a standing train is read whatever its line: a repository with
     ;; no line this pass would otherwise keep it on the policy for good
     (doseq [[repo policy] by-repo
             :when (not (contains? lines repo))
             [lane train] (trains-of policy)]
       (swap! trains assoc (train-key repo lane)
              (advance-train! (assoc ctx :answers answers)
                              repo policy train)))
     @asked)))

;; ── the line, written on the rows (ticket b85aded5) ─────────────────
;;
;; The line above lives in memory and is rebuilt each pass, so without
;; this nothing but a log line said which pull request is at the front
;; or why a green one is not merging. Each pass writes the line on the
;; rows: the repository's policy names its front and how many wait, and
;; each change says its place and why it is not merging. Each goes
;; through its row's hidden `line` door (tickets e6729daa, c1dfd672), so
;; the history says when the line moved. A value that did not move is
;; not written, so a quiet line writes nothing and logs nothing, and a
;; change that left `submitted` has its place cleared.

(def line-policy-fields
  [:line_front :line_front_pr :line_front_waiting :line_waiting :line_at])

(def line-change-fields [:line_place :line_why :line_reason])

(def line-whys
  "Every word `line_why` may carry."
  ["front" "behind" "red" "interrupted" "conflicted" "draft" "parked" "held"
   "person" "org_plan_waits_go_live" "org_plan_retired"])

(def ^:private reason-chars 500)

(defn front-waits-on
  "One word for what the front waits on, from its merge answer: `update`
  when the pass asked to bring it up to date and CI runs, `checks` while
  its checks run, `github` when its checks are green and GitHub has not
  judged it mergeable yet, `merge` when it was offered and GitHub has
  not merged it yet. A `waiting` answer says which of the two in its
  `waits_on`; one that carries none (a rig not yet deployed) reads as
  `checks`. A front that reads behind and holds its base (`current?`,
  from `holds-base?`) has nothing to bring forward: it waits on `merge`.
  An `interrupted` front waits on `checks`: they are run again."
  ([answer] (front-waits-on answer false))
  ([answer current?]
   (cond (behind? answer) (if current? "merge" "update")
         (= "waiting" (answer-state answer))
         (if (= "github" (some-> (:waits_on answer) name)) "github" "checks")
         (= "interrupted" (answer-state answer)) "checks"
         :else "merge")))

(defn- interrupted-reason
  "The checks an `interrupted` answer names, in one clause."
  [answer]
  (some-> (not-empty (str/join ", " (map str (:interrupted answer))))
          (as-> s (subs s 0 (min reason-chars (count s))))))

(defn- parked-reason
  "What the rig said when it parked the head: the row's `merge_refused`,
  else this pass's answer."
  [change answer]
  (some-> (or (when (parked-head? change) (get-in change [:data :merge_refused]))
              (when (parked-answer? answer) (reason-of answer)))
          str not-empty
          (as-> s (subs s 0 (min reason-chars (count s))))))

(defn parked-changes
  "The submitted changes the house pass would offer but for a head it
  parked — the ones `merge-lines` leaves out for that reason alone."
  [changes by-repo]
  (filterv (fn [change]
             (let [head (some-> (get-in change [:data :head_sha]) str not-empty)
                   policy (get by-repo (str (get-in change [:data :repository])))]
               (and (get-in change [:data :number]) head policy
                    (house-merges-change? change policy)
                    (parked-head? change))))
           changes))

(defn line-marks
  "What one pass writes on the rows, from the lines it worked, the rig's
  `answers` (change id → merge answer), and the `parked` changes the lines left out. → {:policies {repo marks}
  :changes {id marks}}; a field a marks map leaves out is cleared. The
  front's place is 1 and the others in the line follow in order; a
  change out of the line has no place and says why. A repository with
  a train in `trains` (`train-key` → its standing train) in its front's
  lane says its front waits on the `train`. `lane-fn` (change → its
  lane, `lane-of`) counts each place within the change's lane (ticket
  55d584f2); without it every change stands in `engine-lane`."
  ([lines answers parked] (line-marks lines answers parked {}))
  ([lines answers parked trains]
   (line-marks lines answers parked trains (constantly engine-lane)))
  ([lines answers parked trains lane-fn]
  (let [answer-of #(get answers (str (:id %)))
        in-line (fn [line] (vec (remove #(out-of-line % (answer-of %)) line)))]
    {:policies
     (into {}
           (map (fn [[repo line]]
                  (let [in (in-line line)
                        front (first in)]
                    [repo (if front
                            {:line_front (str (:id front))
                             :line_front_pr (get-in front [:data :number])
                             :line_front_waiting (if (get trains (train-key repo (lane-fn front)))
                                                   "train"
                                                   (front-waits-on (answer-of front)
                                                                   (holds-base? front)))
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
                            :line_reason (parked-reason change nil)})])))
            parked)
      (into {}
            (mapcat (fn [[_ line]]
                      (let [place (into {}
                                        (mapcat (fn [[_ in]]
                                                  (zipmap (map (comp str :id) in)
                                                          (iterate inc 1))))
                                        (group-by lane-fn (in-line line)))]
                        (map (fn [change]
                               (let [id (str (:id change))
                                     out (out-of-line change (answer-of change))]
                                 [id (cond
                                       (= "merged" out) {}
                                       (= "parked" out) {:line_why out
                                                         :line_reason (parked-reason change (answer-of change))}
                                       (= "interrupted" out) {:line_why out
                                                              :line_reason (interrupted-reason
                                                                            (answer-of change))}
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

(defn with-marks
  "`data` with `marks` on it, nil removing a field."
  [data marks]
  (reduce-kv (fn [d k v] (if (nil? v) (dissoc d k) (assoc d k v))) data marks))

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
                  (schema/encode (:schema rd) (with-marks data m))
                  (:next-flip-at raw))
                 true)))))))))

(defn- row-now
  "The row as stored now: its state and its decoded data, or nil."
  [eng kind id]
  (when-some [rd (rdef-of-kind eng kind)]
    (let [st (:storage eng)]
      (when-some [raw (store/with-tx st (fn [tx] (store/load-row st tx kind id {})))]
        {:state (some-> (:state raw) name) :data (:data (inv/decode-row rd raw))}))))

(defn- line-change!
  "Walk a submitted change through its `line` door with `marks`, by the
  engine's own hand, when one of them moved (`moved-marks`, ticket
  e6729daa); a change in any other state takes them as a maintenance
  write (`mark-row!`). → true when it wrote."
  [eng id marks]
  (let [row (row-now eng :change id)]
    (if (= "submitted" (:state row))
      (boolean
       (when (moved-marks (:data row) marks #{})
         (inv/invoke! eng :change id :line marks {:principal mirror/source-principal})
         true))
      (mark-row! eng :change id marks #{}))))

(defn- line-policy!
  "Walk an active policy through its `line` door with `marks`, by the
  engine's own hand, when one of them moved (`moved-marks`; a field in
  `quiet` rides a walk and never causes one, ticket c1dfd672). The
  marks are judged as the row holds them, and cross the door with each
  instant as its RFC 3339 string, the wire's own spelling. → true when
  it wrote."
  [eng id marks quiet]
  (let [row (row-now eng :repo_policy id)]
    (boolean
     (when (and (= "active" (:state row)) (moved-marks (:data row) marks quiet))
       (inv/invoke! eng :repo_policy id :line
                    (walk/postwalk #(if (instance? Instant %) (str %) %) marks)
                    {:principal mirror/source-principal})
       true))))

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
  cleared. A policy's train marks (`train-marks`), under `:trains`, ride
  the same walk of its `line` door, and a train the marks leave out is
  kept. With `repos` (`in-scope?`), only the rows of those
  repositories are written, and every other row keeps its marks: a
  woken pass's marks map names no other line (ticket 26a8d561).
  → how many rows were written."
  ([eng marks submitted] (mark-lines! eng marks submitted nil))
  ([eng marks submitted repos]
   (let [now (if-some [f (:now-fn eng)] (f) (Instant/now))
         blank-policy (zipmap line-policy-fields (repeat nil))
         blank-change (zipmap line-change-fields (repeat nil))
         wrote (volatile! 0)]
     (doseq [p (policies eng :active)
             :let [repo (str (get-in p [:data :repository]))
                   m (get (:policies marks) repo)]
             :when (in-scope? repos repo)]
       (when (line-policy! eng (str (:id p))
                           (merge blank-policy m (when (seq m) {:line_at now})
                                  (get (:trains marks) repo))
                           #{:line_at})
         (vswap! wrote inc)))
     (doseq [id (into (set (map #(str (:id %))
                                (filter #(in-scope? repos (get-in % [:data :repository]))
                                        submitted)))
                      (marked-change-ids eng repos))]
       (when (line-change! eng id (merge blank-change (get (:changes marks) id)))
         (vswap! wrote inc)))
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
;; `merge_after`. Each write walks one of the policy's hidden deploy doors
;; (`deploy_wait`, `deploy_let_go`, `deployed`, ticket 9a6534b7), so the
;; policy's history says when and why the line stopped.

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

(defn- clip [s] (subs s 0 (min reason-chars (count s))))

(defn- merges-named [waits]
  (str/join ", " (map #(if (:number %) (str "#" (:number %)) (:change %)) waits)))

(defn deploy-marks
  "What one of the policy's deploy doors writes on its `data`, from the
  door's `input` at `now` (ticket 9a6534b7). `deploy_wait` puts a house
  merge on the waits and stamps since, or writes the note the line waits
  with; `deploy_let_go` clears the waits; `deployed` writes the verdict.
  The doors write it, and the pass reads it to walk them only when
  something moved."
  [door data input now]
  (case door
    :deploy_wait
    (cond-> {}
      (:merged input)
      (assoc :deploy_waits_on (conj (vec (:deploy_waits_on data))
                                    (wait-entry {:change (:merged input)
                                                 :ticket (:merged_for input)
                                                 :number (:number input)}))
             :deploy_waiting_since (or (instant-of (:deploy_waiting_since data)) now))
      (:held_because input) (assoc :deploy_note (:held_because input)))

    :deploy_let_go
    {:deploy_waits_on nil :deploy_waiting_since nil :deploy_note (:why input)}

    :deployed
    (if (= "green" (:verdict input))
      (cond-> {:deployed_head (:head input)
               :deploy_state "green"
               :deploy_waits_on (not-empty (vec (:left input)))}
        (not= (:head input) (:deployed_head data)) (assoc :deployed_at now)
        (empty? (:left input)) (assoc :deploy_waiting_since nil :deploy_note nil))
      {:deploy_state "red" :deploy_note (:red_because input)})))

(defn- deploy-door!
  "Walk one of the policy's deploy doors by the engine's own hand, only
  when what it writes moved (`moved-marks`), so a quiet pass adds no
  line to the history. → true when it wrote."
  [eng id door input]
  (let [row (row-now eng :repo_policy id)]
    (boolean
     (when (and row (moved-marks (:data row)
                                 (deploy-marks door (:data row) input (now-of eng))
                                 #{}))
       ;; `deploy_wait` appends, so it is not idempotent: each walk is the
       ;; pass's own and carries its own key
       (inv/invoke! eng :repo_policy id door input
                    {:principal mirror/source-principal
                     :idempotency-key (str "deploy:" id ":" (random-uuid))})
       true))))

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
                        (do (deploy-door! eng (str (:id policy)) :deploy_let_go
                                          {:why (clip (str "The deploy of " (merges-named waits)
                                                           " did not report on " check " within "
                                                           cap " seconds; the line went on at "
                                                           now "."))})
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
    (doseq [c merged]
      (deploy-door! eng (str (:id policy)) :deploy_wait
                    {:merged (str (:id c))
                     :merged_for (born-ticket c)
                     :number (get-in c [:data :number])}))))

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
  "The conclusions of a deploy check that say the deploy did not land.
  The house reruns no deploy, so the kit's table's reds for GitHub and
  its interrupted states both hold the line here (ticket 4a7b5ebc).
  `startup_failure` is a workflow run's conclusion that no check run
  carries, so the table does not name it."
  (-> (kit/raw-states :github :red)
      (into (kit/raw-states :github :interrupted))
      (conj "startup_failure")))

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
        (deploy-door! eng id :deployed
                      {:verdict "green" :head head :left (mapv wait-entry left)}))

      (contains? red-deploys conclusion)
      (deploy-door! eng id :deployed
                    {:verdict "red" :head head
                     :red_because (clip (str check " finished " conclusion " at " head
                                      "; the house merges nothing more here"
                                      " until it is green."))})

      :else false)))

;; ── a merge that waits on other tickets (ticket d069bc3b) ───────────────
;;
;; A ticket's `merge_after` names the tickets that must be done before
;; its change merges. The pass HOLDS such a change: no merge call, no
;; update and no place in its line, so the change behind it is the
;; front. `done` releases it, and so does `dropped`, as a dropped
;; blocker releases what it blocks (ticket 97697177): the work it waited
;; on will never land, and a hold on it would wait until a person
;; noticed. The hold is written on the change (`line_why` held) and on
;; its ticket (`merge_waits`).

(defn merge-holds
  "Ticket id → what it still waits on to merge, for the tickets these
  changes were born from: each ticket its `merge_after` names that is
  neither done nor dropped, as {:id :state}, with `missing` for one
  that is gone. A
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
                                       ;; a dropped one releases the hold
                                       ;; (ticket 97697177)
                                       (= "dropped" s) nil

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
  each with its state."
  [waits]
  (let [s (str "waits on "
               (str/join ", " (map #(str (:id %) " (" (:state %) ")") waits))
               " to merge")]
    (subs s 0 (min reason-chars (count s)))))

(def ^:private unended-ticket-states
  "The ticket states `note_merge_waits` serves: every one not ended."
  #{:draft :open :in_review :blocked :deferred})

(defn- tickets-said-to-wait
  "The ids of the tickets not ended whose stored `merge_waits` is set,
  read from the rows and not from a pass's memory (ticket 5391f738)."
  [eng]
  (if-some [rd (rdef-of-kind eng :ticket)]
    (let [st (:storage eng)]
      (into #{}
            (comp (map #(inv/decode-row rd %))
                  (filter #(some-> (get-in % [:data :merge_waits]) str not-empty))
                  (map #(str (:id %))))
            (store/with-tx st
              (fn [tx]
                (into []
                      (mapcat #(store/query-rows st tx :ticket {:state %} {:all true}))
                      unended-ticket-states)))))
    #{}))

(defn- mark-held-tickets!
  "Walk `note_merge_waits` on the ticket of every submitted change whose
  wait moved, and clear it on every ticket whose row still says it
  waits that nothing holds now. Those are read from the rows (ticket
  5391f738), so a restart between marking and clearing loses none. A
  scoped pass (`whole?` false) walks only the tickets of its own
  `changes`. Throws nothing."
  ([eng changes holds] (mark-held-tickets! eng changes holds true))
  ([eng changes holds whole?]
   (let [mine (set (keep born-ticket changes))]
     (doseq [tid (if whole? (into mine (tickets-said-to-wait eng)) mine)
             :let [row (row-now eng :ticket tid)
                   waits (some-> (get holds tid) held-reason)]
             :when (and row
                        (contains? unended-ticket-states (keyword (:state row)))
                        (moved-marks (:data row) {:merge_waits waits} #{}))]
       (try
         (inv/invoke! eng :ticket tid :note_merge_waits {:merge_waits waits}
                      {:principal mirror/source-principal
                       :within {:kind :repo_policy :action :merge_pass}})
         (catch Exception e
           (warn! "the merge waits of ticket " tid " were not walked ("
                  (ex-message e) "); the next pass walks them")))))))

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

;; A HEAD PARKED AS NOT MERGEABLE (ticket 4df76da5). The row keeps that
;; head (`merge_refused_as`, ticket 1ed59295), and `mark-conflicts!` writes only
;; from the pass the rig refused. So a row that stops saying `conflicted`
;; at the same head — a later read of the mirror's, or any other writer —
;; would sit submitted and parked, offered nothing and asked for nothing.
;; Each pass reads its parked conflicts again: a row that says `clean` at
;; the parked head is un-parked, and the rig is asked once more; any
;; other word is written back to `conflicted`.

(defn- parked-conflict?
  "Is this change's head the one its row parked as not mergeable?"
  [change]
  (boolean (and (parked-head? change)
                (= not-mergeable-refusal
                   (str (get-in change [:data :merge_refused_as]))))))

(defn- review-parked-conflicts!
  "Read again every change whose row parked its head as not mergeable and
  does not say `conflicted`: `clean` un-parks the head, and any
  other word is written back to `conflicted`. Throws nothing.
  → the changes, as their rows read after it."
  [eng changes]
  (mapv (fn [change]
          (let [id (str (:id change))]
            (cond
              (or (not (parked-conflict? change))
                  (conflicted-change? change))
              change

              (= "clean" (str (get-in change [:data :mergeable])))
              (do (merge-asked! eng change nil)
                  (warn! "the row of " id " says clean at the head the rig"
                         " refused as not mergeable; this pass asks again")
                  (update change :data assoc
                          :merge_refused nil :merge_refused_as nil))

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
  (filterv #(and (some->> (get by-repo (str (get-in % [:data :repository])))
                          (house-merges-change? %))
                 (get holds (born-ticket %)))
           changes))

(defn- walk-asked!
  "Write the head an ask was made at on the change's row, through its
  hidden `update_asked` or `rerun_asked` with the mirror's hand, so a
  restart does not ask that head again (ticket a1a133ca). A door that
  refuses costs the record and never the pass: the next pass asks
  again."
  [eng door id input]
  (try
    (inv/invoke! eng :change id door input {:principal mirror/source-principal})
    true
    (catch Exception e
      (warn! "the " (name door) " of " id " was not written (" (ex-message e)
             "); the next pass asks again")
      false)))

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

(defn- train-done!
  "Write a finished train's done mark on one change it carried, with the
  engine's own hand: its `train_done` door, naming the change's head and
  why (ticket 1ae1afbc). Throws nothing."
  [eng changes id why]
  (when-some [row (some #(when (= id (str (:id %))) %) changes)]
    (when-some [head (some-> (get-in row [:data :head_sha]) str not-empty)]
      (try
        (inv/invoke! eng :change id :train_done
                     {:train_done_head head :train_done (clip why)}
                     {:principal mirror/source-principal})
        (catch Exception e
          (warn! "the train's done mark was not written on " id " ("
                 (ex-message e) ")"))))))

(defn- update-red!
  "Send a change the house could not bring up to date back to its seat,
  as the forge sends a red head: `fail` naming what the rig said, or
  `stick` on the policy's last round (ticket 7100a73b). → true when the
  row moved."
  [eng changes by-repo id {:keys [failing_checks conflicts why]}]
  (when-some [row (some #(when (= id (str (:id %))) %) changes)]
    (let [policy (get by-repo (str (get-in row [:data :repository])))
          last? (>= (long (or (get-in row [:data :rounds]) 0))
                    (long (rounds-of policy)))]
      (try
        (inv/invoke! eng :change id (if last? :stick :fail)
                     (cond-> {:failing_checks failing_checks}
                       (seq conflicts) (assoc :conflicts conflicts)
                       last? (assoc :why (clip why)))
                     {:principal mirror/source-principal})
        true
        (catch Exception e
          (warn! "the update's red did not move " id " (" (ex-message e)
                 "); the next pass tries again")
          false)))))

;; ── the go_live yes (ticket c8e131fb, M2e) ──────────────────────────

(def ^:private live-change-states
  "The change states whose pull request is still in play."
  #{"open" "submitted" "failing" "stuck"})

(defn org-plan-change-id
  "The id of the change an org_plan names — or, for a plan whose
  `change` is nil (one minted before the sit wrote it, ticket
  db8b35bb), of the live change born from `org_plan:<id>`. nil when
  there is neither."
  [eng plan]
  (or (some-> (get-in plan [:data :change]) str not-empty)
      (when (rdef-of-kind eng :change)
        (let [st (:storage eng)]
          (some->> (store/with-tx st
                     (fn [tx]
                       (store/query-rows st tx :change
                                         {:born_from (str "org_plan:" (:id plan))}
                                         {:limit 20})))
                   (filter #(contains? live-change-states
                                       (some-> (:state %) name)))
                   first
                   :id
                   str)))))

(defn- org-plans
  "Every org_plan in one of `states`, decoded, by the id of the change it
  names (`org-plan-change-id`) — empty in an engine that declares no
  org_plan kind."
  [eng states]
  (if-some [rd (rdef-of-kind eng :org_plan)]
    (let [st (:storage eng)]
      (into {}
            (keep (fn [raw]
                    (let [plan (inv/decode-row rd raw)]
                      (some-> (org-plan-change-id eng plan)
                              (vector plan)))))
            (store/with-tx st
              (fn [tx]
                (into []
                      (mapcat #(store/query-rows st tx :org_plan {:state %}
                                                 {:limit 1000}))
                      states)))))
    {}))

(defn- going-live-plans
  "Every going_live org_plan, decoded, by the id of the change it names."
  [eng]
  (org-plans eng ["going_live"]))

(defn- go-live-wait-states
  "Every org_plan state whose pull request merges only on the owner's
  go_live yes (ticket 7aff6a5c): all but live and retired. Read from the
  kind's declared states, not a hand list, so a state added later waits
  too (ticket b79643fe: `filming` was missing and leaked a merge)."
  [eng]
  (into []
        (comp (map name) (remove #{"live" "retired"}))
        (:states (rdef-of-kind eng :org_plan))))

(defn- go-live-waits
  "The changes whose pull request an org_plan not yet live names, and
  that carry no go_live yes (`go-live-yeses!`): change id → that plan.
  Such a change merges only through go_live — the house does not merge
  it and no person is asked to (ticket 7aff6a5c). Once its plan is live
  the change is ordinary again. A retired plan's change is never
  merge-eligible (ticket e0ff6d71): it is held too, whether the plan
  names it or it was born from that plan, unless a live plan names it."
  [eng changes]
  (let [live (org-plans eng ["live"])
        retired (apply dissoc (org-plans eng ["retired"]) (keys live))
        born-of (into {}
                      (map (fn [p] [(str "org_plan:" (:id p)) p]))
                      (vals retired))
        plans (merge retired (org-plans eng (go-live-wait-states eng)))]
    (into {}
          (keep (fn [c]
                  (let [id (str (:id c))]
                    (when-some [plan (or (get plans id)
                                         (when-not (contains? live id)
                                           (get born-of (str (get-in c [:data :born_from])))))]
                      (when (nil? (::go-live-yes c)) [id plan])))))
          changes)))

(defn- retired-plan?
  [plan]
  (= "retired" (some-> (:state plan) name)))

(defn go-live-wait-reason
  "What a change waiting on its org_plan's go_live says on its row."
  [plan]
  (if (retired-plan? plan)
    (str "org plan " (:id plan) " is retired: its pull request is never"
         " merged; close it")
    (str "org plan " (:id plan) " is " (some-> (:state plan) name)
         ": only its owner's go_live merges this pull request")))

;; ── a plan's lane (ticket 59cd3e78) ──────────────────────────────────
;;
;; An open change born from an org_plan owns its org directory, its lane
;; (`lane-of`). A change toward the same base that touches a path in
;; that lane makes the plan's next update from its base conflict, so the
;; pass holds it as it holds a change whose ticket waits: out of its
;; line, `line_why` held, and `held_reason` naming the plan's pull
;; request and its lane. The hold lifts when the plan's change merges or
;; closes, and when the mayor's `release_lane` writes `lane_released`.
;; The plan's own change is never held by it.

(def ^:private lane-held-prefix "held for the lane ")

(defn- plan-born?
  [change]
  (str/starts-with? (str (get-in change [:data :born_from])) "org_plan:"))

(defn lane-hold-reason
  "The sentence a change held for a plan's lane carries: the lane and
  the plan's pull request."
  [plan lane]
  (let [d (:data plan)
        pr (or (some-> (:url d) str not-empty)
               (when (:number d) (str (:repository d) "#" (:number d)))
               (str "change " (:id plan)))
        s (str lane-held-prefix lane " of the org plan's pull request " pr
               ": it merges once that change merges or closes, or on release_lane")]
    (subs s 0 (min reason-chars (count s)))))

(defn lane-holds
  "Change id → the sentence it is held with (`lane-hold-reason`), for
  each of `changes` that touches a path under the org lane (`orgs/<x>`)
  of another change among `plans` — the live changes born from an
  org_plan — in the same repository and toward the same base. `lane-fn`
  reads a change's lane (`lane-of`). A change born from an org_plan,
  and one whose `lane_released` stands, is never held. Pure."
  [changes plans lane-fn]
  (let [owned (keep (fn [p]
                      (let [lane (some-> (lane-fn p) str)]
                        (when (and lane (str/starts-with? lane "orgs/"))
                          [p lane])))
                    plans)]
    (into {}
          (keep (fn [c]
                  (when-not (or (plan-born? c)
                                (some-> (get-in c [:data :lane_released]) str not-empty))
                    (let [paths (keep #(some-> % str not-empty)
                                      (get-in c [:data :touched_paths]))
                          same? (fn [p k] (= (str (get-in p [:data k]))
                                             (str (get-in c [:data k]))))]
                      (some (fn [[p lane]]
                              (when (and (not= (str (:id p)) (str (:id c)))
                                         (same? p :repository)
                                         (same? p :base_branch)
                                         (some #(str/starts-with? % (str lane "/")) paths))
                                [(str (:id c)) (lane-hold-reason p lane)]))
                            owned)))))
          changes)))

(defn- plan-changes
  "Every live change born from an org_plan, decoded — empty in an engine
  that declares no change kind."
  [eng]
  (if-some [rd (rdef-of-kind eng :change)]
    (let [st (:storage eng)]
      (into []
            (comp (map #(inv/decode-row rd %)) (filter plan-born?))
            (store/with-tx st
              (fn [tx]
                (into []
                      (mapcat #(store/query-rows st tx :change {:state %}
                                                 {:limit 1000}))
                      live-change-states)))))
    []))

(defn hold-marks
  "Each held change's hold, as the `hold` door takes it — what holds it
  (`held_as`) and the line's sentence for it (`held_why`) — read from one
  pass's change marks; `merge-held` is the ids `merge-holds` holds, the
  rest of the `held` line being the lane's. Pure."
  [change-marks merge-held]
  (into {}
        (keep (fn [[id {:keys [line_why line_reason]}]]
                (when-some [as (case line_why
                                 ("org_plan_waits_go_live" "org_plan_retired") "go_live"
                                 "held" (if (contains? merge-held id) "merge_holds" "lane")
                                 "person" "person"
                                 nil)]
                  [id {:held_as as :held_why (some-> line_reason str not-empty)}])))
        change-marks))

(defn- mark-holds!
  "Walk each of `changes` through its hold (ticket e6729daa): `hold` when
  what holds it (`holds`, as `hold-marks` answers it) or the lane's
  `held_reason` (`lane-held`, as `lane-holds` answers it) moved, and
  `release` when nothing holds it now and its row still says something
  did. A hold that did not move walks nothing. Throws nothing."
  [eng changes holds lane-held]
  (doseq [c changes
          :let [id (str (:id c))
                data (:data (row-now eng :change id))
                why (get lane-held id)
                was (some-> (:held_reason data) str)
                stale? (boolean (and (nil? why) was (str/starts-with? was lane-held-prefix)))
                h (get holds id)]]
    (try
      (cond
        h (when (moved-marks data (cond-> h
                                    why (assoc :held_reason why)
                                    stale? (assoc :held_reason nil))
                             #{})
            (inv/invoke! eng :change id :hold
                         (cond-> h
                           why (assoc :held_reason why)
                           stale? (assoc :clear_held_reason true))
                         {:principal mirror/source-principal}))
        (or (some-> (:held_as data) str not-empty) stale?)
        (inv/invoke! eng :change id :release {:clear_held_reason stale?}
                     {:principal mirror/source-principal}))
      (catch Exception e
        (warn! "the hold of " id " was not walked (" (ex-message e)
               "); the next pass walks it")))))

(def ^:private off-line-change-states
  "The live change states the merge line does not walk (ticket 0c2940ca)."
  #{"open" "failing" "stuck"})

(defn- release-off-line!
  "Walk `release` on each change that left `submitted` for a live state
  while its row still says something holds it (ticket 0c2940ca): the
  `hold` door and `mark-holds!` serve `submitted` alone, so its
  `held_as`, `held_why` and a lane's `held_reason` would otherwise
  outlive the line. With `repos`, only those repositories. Throws
  nothing."
  [eng repos]
  (when-some [rd (rdef-of-kind eng :change)]
    (let [st (:storage eng)]
      (doseq [c (store/with-tx st
                  (fn [tx]
                    (into []
                          (mapcat #(store/query-rows st tx :change {:state %}
                                                     {:limit 1000}))
                          off-line-change-states)))
              :let [c (inv/decode-row rd c)
                    id (str (:id c))
                    data (:data c)
                    stale? (boolean (some-> (:held_reason data) str
                                            (str/starts-with? lane-held-prefix)))]
              :when (and (in-scope? repos (get-in c [:data :repository]))
                         (or (some-> (:held_as data) str not-empty) stale?))]
        (try
          (inv/invoke! eng :change id :release {:clear_held_reason stale?}
                       {:principal mirror/source-principal})
          (catch Exception e
            (warn! "the hold of " id " was not released (" (ex-message e)
                   "); the next pass releases it")))))))

(defn- go-live-yeses!
  "The pass's changes, each whose pull request a going_live org_plan
  names marked with the owner's go_live yes when its head is the plan's
  `approved_head`: that yes is the person's merge yes, so `person-path`
  keeps nothing of it for a person and the house merges it with no
  second ask. A head the rig's compare_heads says carries from it (a
  base move) is not merged yet: the findings pass moves the yes to it
  when the move left the org alone, or refilms the plan when it did not
  (ticket ce8a4e17). Any other head is not merged: the plan's
  `head_moved` retires the yes and sends it back to simulated."
  [eng changes]
  (let [plans (going-live-plans eng)]
    (mapv (fn [change]
            (if-some [plan (get plans (str (:id change)))]
              (let [approved (some-> (get-in plan [:data :approved_head]) str not-empty)
                    head (some-> (get-in change [:data :head_sha]) str not-empty)]
                (cond
                  (and approved head (= approved head))
                  (assoc change ::go-live-yes approved)

                  (and approved head
                       (:carries (held/compared
                                  eng {:repo (str (get-in change [:data :repository]))
                                       :base (str (get-in change [:data :base_branch]))
                                       :approved approved :head head})))
                  change

                  :else
                  (do (try
                        (inv/invoke! eng :org_plan (str (:id plan)) :head_moved
                                     {:head_sha (str head)}
                                     {:principal mirror/source-principal})
                        (catch Exception e
                          (warn! "the moved head of " (:id plan) " did not send it back ("
                                 (ex-message e) "); the next pass tries again")))
                      change)))
              change))
          changes)))

(defn merge-green!
  "One merge pass. Every submitted change with a number and a head,
  whose repository's active policy says `auto_merge` and `merge_by:
  house`, gets ONE `merge` call with the engine's own hand. A head the
  rig refused for good (a `parked-refusals` name) is written on its row
  (`merge-asked!`, ticket 1ed59295): that head is never offered again,
  not after a restart either, and a new head is offered afresh — but for a head parked as not mergeable whose
  row no longer says `conflicted`, which is offered once more when the
  row says `clean` and marked again when it says anything else
  (`review-parked-conflicts!`). A branch the rig says is behind its base is
  brought up to date with `update_branch` only when it is the front of
  its repository's line (`merge-lines`), once per head (the row's
  `updated_head`, ticket a1a133ca); a green change held behind outside every
  line is brought up to date the same way (`bring-held-forward!`, ticket
  6add57e9). `merged` needs nothing here, because the
  mirror moves the row; `waiting` and every other refusal are asked
  again next pass; `red` is left, because the seat's feedback already
  carries the red checks; a refusal that says the pull request is not
  mergeable writes `conflicted` on the row (`mark-conflicts!`), because
  the mirror's own read may never say so. Then the line is written on
  the rows
  (`mark-lines!`): the policy names its front and who waits, and each
  change its place and why it is not merging. A change whose ticket
  still waits on another to merge (`merge-holds`) is held out of all
  of it, and says so; so is one that touches the org lane of an open
  org plan's change (`lane-holds`, ticket 59cd3e78). A repository whose last house merge is not yet
  deployed (`deploy-held`) is offered nothing. With `repos`, a set of
  repository names, the pass works only those repositories: no other
  is offered a merge and no other's marks are written (a woken pass,
  ticket 26a8d561). A change whose pull request a going_live org_plan
  names merges on the owner's go_live yes (`go-live-yeses!`, ticket
  c8e131fb); one an org_plan not yet live names is merged by nothing
  else, and says `org_plan_waits_go_live` (`go-live-waits`, ticket
  7aff6a5c); one a retired org_plan names or bore is merged by nothing
  and says `org_plan_retired` (ticket e0ff6d71). Throws nothing.
  → the number of `merge` calls made."
  ([eng] (merge-green! eng nil))
  ([eng repos]
  (let [by-repo (into {} (filter #(in-scope? repos (key %))) (policies-by-repo eng))
        deploy-holds (deploy-held eng by-repo)
        changes (go-live-yeses!
                 eng
                 (review-parked-conflicts!
                  eng
                  (filterv #(in-scope? repos (get-in % [:data :repository]))
                           (submitted-changes eng))))
        house? #(some->> (get by-repo (str (get-in % [:data :repository])))
                         (house-merges-change? %))
        holds (merge-holds eng (filter house? changes))
        held (held-changes changes by-repo holds)
        waits (go-live-waits eng changes)
        lane-held (lane-holds (filter house? changes) (plan-changes eng)
                              #(lane-of % (get by-repo (str (get-in % [:data :repository])))))
        held-ids (-> (into (set (keys waits)) (map #(str (:id %))) held)
                     (into (keys lane-held)))
        offered (remove #(held-ids (str (:id %))) changes)
        priorities (ticket-priorities eng offered)
        lines (merge-lines offered by-repo
                           #(get priorities (born-ticket %)))
        answers (atom {})
        trains (atom {})
        recorded (atom {})
        update-asked! #(walk-asked! eng :update_asked %1 %2)
        rerun-asked! #(walk-asked! eng :rerun_asked %1 %2)
        asked (work-lines! {:services (:services eng) :now-fn (:now-fn eng)
                            :recorded recorded
                            :update-asked! update-asked!
                            :rerun-asked! rerun-asked!
                            :merge-asked! #(merge-asked! eng %1 %2)
                            :train-red! #(train-red! eng changes by-repo %1 %2)
                            :train-done! #(train-done! eng changes %1 %2)
                            :update-red! #(update-red! eng changes by-repo %1 %2)
                            :head-of (fn [id]
                                       (some #(when (= id (str (:id %)))
                                                (get-in % [:data :head_sha]))
                                             changes))}
                           (apply dissoc lines (keys deploy-holds)) by-repo
                           answers trains)
        standing (merge (into {}
                              (mapcat (fn [[repo p]]
                                        (map (fn [[lane t]] [(train-key repo lane) t])
                                             (trains-of p))))
                              by-repo)
                        @trains)]
    (try
      (let [in-line (into #{} (comp (mapcat val) (map #(str (:id %)))) lines)
            filmed (org-plans eng ["proposed" "filming" "simulated"])]
        (bring-held-forward! {:services (:services eng) :now-fn (:now-fn eng)
                              :recorded recorded
                              :update-asked! update-asked!
                              :plan-of #(get filmed %)
                              :held-conflict! (fn [id conflicts]
                                                (mark-row! eng :change id
                                                           {:conflicts conflicts} #{}))}
                             (remove #(in-line (str (:id %))) changes)))
      (note-merges! eng by-repo lines @answers)
      (mark-conflicts! eng @answers)
      (doseq [[repo why] deploy-holds]
        (deploy-door! eng (str (:id (get by-repo repo))) :deploy_wait {:held_because why}))
      ;; the line is read after `mark-conflicts!`, so the pass that finds
      ;; a conflict says `conflicted` and not `parked` (ticket 4df76da5)
      (let [marks
                   (update (let [found (conflicted-ids @answers)]
                             (line-marks (into (empty lines)
                                               (map (fn [[repo line]]
                                                      [repo (with-conflicts found (with-records @recorded line))]))
                                               lines)
                                         @answers
                                         (parked-changes (with-conflicts found offered)
                                                         by-repo)
                                         standing
                                         #(lane-of % (get by-repo (str (get-in % [:data :repository]))))))
                           :changes merge
                           ;; a change nobody here can merge says so
                           ;; (ticket 60962bcf)
                           (person-marks changes by-repo)
                           ;; held for an open plan's lane (ticket 59cd3e78)
                           (into {}
                                 (map (fn [[id why]]
                                        [id {:line_why "held" :line_reason why}]))
                                 lane-held)
                           (into {}
                                 (map (fn [c]
                                        [(str (:id c))
                                         {:line_why "held"
                                          :line_reason (held-reason
                                                        (get holds (born-ticket c)))}]))
                                 held)
                           ;; only go_live merges an org's pull request
                           ;; (ticket 7aff6a5c)
                           (into {}
                                 (map (fn [[id plan]]
                                        [id {:line_why (if (retired-plan? plan)
                                                         "org_plan_retired"
                                                         "org_plan_waits_go_live")
                                             :line_reason (go-live-wait-reason plan)}]))
                                 waits))]
        ;; the trains ride the policy's one `line` walk (ticket c1dfd672)
        (mark-lines! eng (assoc marks :trains (train-marks by-repo @trains))
                     changes repos)
        (mark-holds! eng changes
                     (hold-marks (:changes marks) (set (map #(str (:id %)) held)))
                     lane-held))
      (mark-held-tickets! eng changes holds (nil? repos))
      (release-off-line! eng repos)
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
;; again for the same head. The wait counts from the row's `held_since`
;; when its `held_head` is the head at hand (ticket 665fcb50): the engine
;; deploys more often than hourly, so a wait kept only in memory started
;; again on each deploy and a person-kept change was never asked for. A
;; row that names no time for this head is timed from the first pass
;; that sees it clean, in memory, as before.

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

(defn- stored-since
  "The time the change row says the line first held `head`: its
  `held_since`, when its `held_head` names this head, or nil."
  [change head]
  (when (= head (some-> (get-in change [:data :held_head]) str))
    (try (Instant/parse (str (get-in change [:data :held_since])))
         (catch Exception _ nil))))

(defn- wait-since
  "When the change row says the merge wait for `head` started: its
  `merge_wait_since`, when its `merge_wait_head` names this head, or nil."
  [change head]
  (when (= head (some-> (get-in change [:data :merge_wait_head]) str))
    (try (Instant/parse (str (get-in change [:data :merge_wait_since])))
         (catch Exception _ nil))))

(defn- walk-wait!
  "Walk one of the change's merge wait doors. Throws nothing."
  [eng change door input]
  (try
    (inv/invoke! eng :change (str (:id change)) door input
                 {:principal mirror/source-principal})
    (catch Exception e
      (warn! "the merge wait of " (:id change) " was not written on its row ("
             (ex-message e) "); the next pass writes it"))))

(defn- same-pull?
  "Is this ask for this change's pull request?"
  [ask change]
  (let [in (get-in ask [:data :input])]
    (and (= (str (field-of in :repo))
            (str (get-in change [:data :repository])))
         (= (str (field-of in :number))
            (str (get-in change [:data :number]))))))

(defn- ask-state [ask] (some-> (:state ask) name keyword))

(defn- asked-already?
  "Is there an ask for this pull request still waiting, or one for this
  head at all?"
  [asks change head]
  (some (fn [a]
          (and (same-pull? a change)
               (or (contains? #{:held :allowed :allowed_waiting} (ask-state a))
                   (= head (str (field-of (get-in a [:data :input])
                                          :head_sha))))))
        asks))

(defn- waiting-yes
  "The person's yes on this pull request at this head that the rig
  answered `waiting` (`allowed_waiting`, ticket 4180a699), or nil."
  [asks change head]
  (some (fn [a]
          (when (and (= :allowed_waiting (ask-state a))
                     (same-pull? a change)
                     (= head (str (field-of (get-in a [:data :input])
                                            :head_sha))))
            a))
        asks))

(defn- change-of-ask
  "The change row an ask names by repository and number, or nil."
  [eng ask]
  (when-some [rd (rdef-of-kind eng :change)]
    (let [st (:storage eng)
          in (get-in ask [:data :input])]
      (some #(when (same-pull? ask %) %)
            (mapv #(inv/decode-row rd %)
                  (store/with-tx st
                    (fn [tx] (store/query-rows st tx :change
                                               {:repository (str (field-of in :repo))}
                                               {:limit 1000}))))))))

(defn- expire-spent-yeses!
  "Expire every `allowed_waiting` merge ask whose change has closed. One
  whose head moved from the one the person allowed asks the rig first
  (`held/carry-or-expire!`, ticket abd12627): a head the house brought
  up to date from base with the pull request's own diff unchanged keeps
  the yes, pinned to the new head; any other expires. A change still
  open at that head keeps its yes, green or not."
  [eng asks]
  (doseq [a asks
          :when (= :allowed_waiting (ask-state a))
          :let [change (try (change-of-ask eng a)
                            (catch Exception _ ::unread))]
          :when (not= ::unread change)
          :let [closed? (or (nil? change)
                            (contains? #{:closed :merged}
                                       (some-> (:state change) name keyword)))
                head (some-> (get-in change [:data :head_sha]) str)]
          :when (or closed?
                    (not= (str (field-of (get-in a [:data :input]) :head_sha))
                          head))]
    (try
      (if closed?
        (held/expire! eng a)
        (held/carry-or-expire! eng a
                               {:repo (str (get-in change [:data :repository]))
                                :base (str (get-in change [:data :base_branch]))
                                :head head}))
      (catch Exception e
        (warn! "the waiting merge " (:id a) " did not expire ("
               (ex-message e) "); the next pass tries again")))))

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

(defn- head-not-green?
  "Does the forge pass say this very head is not green yet? It read the
  head's checks (`missing_checks_head`) and found a required check with
  no run at all (`missing_checks`, ticket 8e809671), or one that has run
  but not gone green: a check queued or in progress is not missing, so
  a head the pass read but never stamped `green_head` still waits on it
  (ticket 9f19742e). Such a head is not green, whatever `mergeable` says."
  [change head]
  (boolean
   (and (= head (some-> (get-in change [:data :missing_checks_head]) str))
        (or (seq (get-in change [:data :missing_checks]))
            (not= head (some-> (get-in change [:data :green_head]) str))))))

(defn merge-ask-why
  "The one sentence the person reads: the pull request, the title, the
  required checks it saw green and how long it has waited."
  [change policy seconds]
  (str "Merge " (pull-url change) " ("
       (or (some-> (get-in change [:data :title]) str not-empty) "untitled")
       "): its required checks (" (str/join ", " (required-checks-of policy))
       ") are green, it merges clean, and it has waited "
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

(def merge-ask-hand
  "The hand a merge ask's held call is born by: the held calls' own."
  held/engine-actor)

(defn merge-ask-call
  "The one held call a person's tap turns into the rig's merge, as the
  change's `ask_merge` door raises it from its input (ticket e9a985d4)."
  [change {:keys [head_sha why required_checks merge_method owner]}]
  (let [args {:repo (str (get-in change [:data :repository]))
              :number (get-in change [:data :number])
              :head_sha (str head_sha)
              :required_checks (vec required_checks)
              :merge_method (str merge_method)}
        owner (some-> owner str not-empty)]
    (cond-> {:tool (gate/bench-tool :merge)
             :why (str why)
             :caller merge-asker
             :input args
             :forward args
             :shown (:text (held/capped
                            (str "merge " (:repo args) "#" (:number args))
                            140))}
      owner (assoc :owner owner))))

(defn- raise-merge-ask!
  "Walk the change's `ask_merge` at its head, whose handler raises the
  one held call a person's tap turns into the rig's merge (ticket
  e9a985d4). → true when it asked a head the row did not name yet."
  [eng change policy seconds]
  (let [args (merge-args change policy)
        owner (owner-of eng change)]
    (when (not= (:head_sha args)
                (some-> (get-in change [:data :merge_ask_head]) str))
      (inv/invoke! eng :change (str (:id change)) :ask_merge
                   (cond-> {:head_sha (:head_sha args)
                            :why (:text (held/capped (merge-ask-why change policy
                                                                    seconds)
                                                     240))
                            :required_checks (:required_checks args)
                            :merge_method (:merge_method args)}
                     owner (assoc :owner owner))
                   {:principal mirror/source-principal})
      true)))

(defn ask-for-merges!
  "One ask pass. Every submitted change with a number and a head, whose
  repository's active policy leaves the merge to a person
  (`house-merges-change?` is false: the policy does not merge by the
  house, or the change touches a path it keeps for a person, ticket
  15305c4a), that GitHub calls `clean` and is not
  a draft, is timed from the row's `held_since` for this head
  (`stored-since`, so a restart does not start the wait again, ticket
  665fcb50), or else from the first pass that saw it clean at this head.
  Once it has waited the policy's `merge_wait_seconds`, it raises one
  merge ask unless `asked-already?`. A change whose ticket still waits
  on another to merge (`merge-holds`) is not timed and not asked; its
  wait starts once nothing holds it, at the pass that sees it free. A repository whose policy names no
  required check is asked nothing (`person-merges-on-github?`): the rig
  would refuse the allowed call, so the tap would merge nothing, and the
  merge pass writes `line_why: person` on the change instead. A head
  the forge pass read with a required check missing or still running is
  not green, so it is neither timed nor asked (`head-not-green?`,
  tickets 8e809671 and 9f19742e). A person's yes the rig answered
  `waiting` (`allowed_waiting`, ticket 4180a699) is forwarded again,
  with no new ask, by the pass that reads its change green on the same
  head; one whose change closed is expired, and one whose head moved
  carries over only when the rig says the house brought it up to date
  from base with its own diff unchanged (ticket abd12627). The wait is
  on the change row (ticket 3806fb22): the pass walks `wait_started`
  for a head the row does not time yet, `wait_cleared` with the head
  kept while a merge hold keeps it, and `wait_cleared` with no head once
  the change is no longer clean, green or submitted. No ask is raised for a change an org_plan not
  yet live names: only the owner's go_live merges it (`go-live-waits`,
  ticket 7aff6a5c). Throws nothing. → the number
  of asks raised."
  [eng ^Instant now]
  (let [by-repo (policies-by-repo eng)
        asks (merge-asks eng)
        changes (submitted-changes eng)
        person? #(some->> (get by-repo (str (get-in % [:data :repository])))
                          (house-merges-change? %) not)
        holds (merge-holds eng (filter person? changes))
        ;; the owner's go_live yes is this pull request's ask (M2e,
        ;; ticket 7aff6a5c)
        waits (go-live-waits eng changes)
        raised (volatile! 0)]
    (doseq [change changes
            :let [id (str (:id change))
                  number (get-in change [:data :number])
                  head (some-> (get-in change [:data :head_sha]) str not-empty)
                  policy (get by-repo (str (get-in change [:data :repository])))
                  timed (some-> (get-in change [:data :merge_wait_head]) str not-empty)]]
      (cond
        (not (and number head policy
                  (not (contains? waits id))
                  (not (house-merges-change? change policy))
                  (not (person-merges-on-github? policy))
                  (= "clean" (str (get-in change [:data :mergeable])))
                  (not (true? (get-in change [:data :draft])))
                  (not (head-not-green? change head))))
        ;; a change that is no longer clean, or no longer green, starts
        ;; its wait again the next time it is
        (when timed (walk-wait! eng change :wait_cleared {}))

        (get holds (born-ticket change))
        ;; not timed while held; the row keeps the head with no time, so
        ;; the pass that sees it free starts its wait, not its stored
        ;; held_since
        (when (or (not= head timed) (wait-since change head))
          (walk-wait! eng change :wait_cleared {:merge_wait_head head}))

        :else
        (let [kept (wait-since change head)
              ^Instant since (or kept
                                 (when (not= head timed) (stored-since change head))
                                 now)
              seconds (.getSeconds (Duration/between since now))]
          (when-not kept
            (walk-wait! eng change :wait_started
                        {:merge_wait_head head :merge_wait_since (str since)}))
          (when-some [yes (waiting-yes asks change head)]
            (try
              (held/reforward! eng yes)
              (catch Exception e
                (warn! "the waiting merge for " id " did not go out ("
                       (ex-message e) "); the next pass tries again"))))
          (when (and (<= (merge-wait-of policy) seconds)
                     (not (asked-already? asks change head)))
            (try
              (when (raise-merge-ask! eng change policy seconds)
                (vswap! raised inc))
              (catch Exception e
                (warn! "the merge ask for " id " did not land ("
                       (ex-message e) "); the next pass asks again")))))))
    (expire-spent-yeses! eng asks)
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
  same election, so one process per database asks the rig to merge. So
  does the person's merge ask (`ask-for-merges!`), whose wait the change row
  keeps (`merge_wait_since`). Between beats the merge pass also runs when the
  mirror wakes it (`house-wakes`, `house-beat!`)."
  [eng {:keys [every-seconds] :or {every-seconds default-enrol-seconds}}]
  (let [every-ms (* 1000 (long every-seconds))
        tick (max 1 (min (long every-seconds) (long house-tick-seconds)))
        merge-pass! (fn merge-pass!
                      ([] (merge-pass! nil))
                      ([repos]
                       (try (merge-green! eng repos)
                            (catch Exception e
                              (warn! "the merge pass failed ("
                                     (ex-message e) ")")))))
        clock-pass! (fn []
                      (try (enroll-unenrolled! eng)
                           (catch Exception e
                             (warn! "the enrolment pass failed ("
                                    (ex-message e) ")")))
                      (merge-pass!)
                      (try (ask-for-merges! eng (Instant/now))
                           (catch Exception e
                             (warn! "the merge ask pass failed ("
                                    (ex-message e) ")"))))
        next-clock (atom (+ (System/currentTimeMillis) every-ms))]
    {:stop (clock/every!
            {:name "factory10-bench-enrol"
             :period-ms (* 1000 tick)
             :f (fn []
                  (let [ms (System/currentTimeMillis)
                        clock? (>= ms (long @next-clock))]
                    (try (house-beat! house-wakes (Instant/now) clock?
                                      clock-pass! merge-pass!)
                         (catch Exception e
                           (warn! "the sweeper's beat failed ("
                                  (ex-message e) ")")))
                    (when clock? (reset! next-clock (+ ms every-ms)))))})}))

(defn stop-enrol-sweeper! [{:keys [stop]}]
  (when stop (stop))
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
