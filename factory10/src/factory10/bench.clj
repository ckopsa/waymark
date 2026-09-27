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
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
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
  so the rig replaces its entry."
  [row]
  {:repo (str (get-in row [:data :repository]))
   :clone_url (clone-url-of row)
   :default_branch (base-of row)
   :deny (vec (get-in row [:data :deny]))
   :land (land-of row)})

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

(defn merge-args
  "What the rig's `merge` is told: the pull request, the head it may
  merge and nothing else, and the policy's checks and method."
  [change policy]
  {:repo (str (get-in change [:data :repository]))
   :number (get-in change [:data :number])
   :head_sha (str (get-in change [:data :head_sha]))
   :required_checks (required-checks-of policy)
   :merge_method (merge-method-of policy)})

(defn merge-green!
  "One merge pass. Every submitted change with a number and a head,
  whose repository's active policy says `auto_merge` and `merge_by:
  house`, gets ONE `merge` call with the engine's own hand. `seen` is
  an atom of change id → the head the rig last refused: a refused head
  is logged once and never offered again, and a new head is offered
  afresh. `merged` needs nothing here, because the mirror moves the
  row; `waiting` is asked again next pass; `red` is left, because the
  seat's feedback already carries the red checks. Throws nothing.
  → the number of `merge` calls made."
  [eng seen]
  (let [ctx {:services (:services eng)}
        by-repo (policies-by-repo eng)
        asked (volatile! 0)]
    (doseq [change (submitted-changes eng)
            :let [id (str (:id change))
                  number (get-in change [:data :number])
                  head (some-> (get-in change [:data :head_sha]) str not-empty)
                  policy (get by-repo (str (get-in change [:data :repository])))]
            :when (and number head policy
                       (house-pass-merges? policy)
                       (not= head (get @seen id)))]
      (try
        (vswap! asked inc)
        (let [answer (ask ctx :merge (merge-args change policy))
              why (refused answer)]
          (cond
            (nil? answer)
            (warn! "the merge of " id " had no answer; the next pass asks again")

            (contains? missing-power-refusals why)
            (warn! "the rig has no merge yet (" why "); the next pass asks again")

            why
            (do (swap! seen assoc id head)
                (warn! "the rig refused to merge " id " at " head " ("
                       (reason-of answer) "); this head is not asked again"))

            :else nil))
        (catch Exception e
          (warn! "the merge of " id " failed (" (ex-message e)
                 "); the next pass asks again"))))
    @asked))

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
  merge ask unless `asked-already?`. `waiting` is an atom of change id
  → {:head :since}. Throws nothing. → the number of asks raised."
  [eng waiting ^Instant now]
  (let [by-repo (policies-by-repo eng)
        asks (merge-asks eng)
        raised (volatile! 0)
        clean (volatile! {})]
    (doseq [change (submitted-changes eng)
            :let [id (str (:id change))
                  number (get-in change [:data :number])
                  head (some-> (get-in change [:data :head_sha]) str not-empty)
                  policy (get by-repo (str (get-in change [:data :repository])))]
            :when (and number head policy
                       (not (house-pass-merges? policy))
                       (= "clean" (str (get-in change [:data :mergeable])))
                       (not (true? (get-in change [:data :draft]))))]
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
  change turned clean."
  [eng {:keys [every-seconds] :or {every-seconds default-enrol-seconds}}]
  (let [stop (CountDownLatch. 1)
        seen (atom {})
        waiting (atom {})
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long every-seconds)
                                         TimeUnit/SECONDS)
                         (try (enroll-unenrolled! eng)
                              (catch Exception e
                                (warn! "the enrolment pass failed ("
                                       (ex-message e) ")")))
                         (try (merge-green! eng seen)
                              (catch Exception e
                                (warn! "the merge pass failed ("
                                       (ex-message e) ")")))
                         (try (ask-for-merges! eng waiting (Instant/now))
                              (catch Exception e
                                (warn! "the merge ask pass failed ("
                                       (ex-message e) ")")))
                         (recur))))
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

(defn sitting-id
  "The open sitting of the leash this request wears, or nil.

  `(:grant ctx)` is the guard's-eye view of the grant presented with
  this request (invoke.clj's make-ctx), and the sitting is the row
  under that grant that is still open — `seats/open-sitting-for-grant`'s
  own query, made here through the ctx `:find` hook so it runs in the
  write's own transaction. A request with no grant, or no open
  sitting, names none, and the commit carries one trailer instead of
  two."
  [ctx]
  (when-some [find' (:find ctx)]
    (when-some [gid (some-> (get-in ctx [:grant :id]) str not-empty)]
      (some-> (first (find' :sitting {:grant gid :state :open}
                            {:limit 1 :newest-first true}))
              :id str not-empty))))

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
