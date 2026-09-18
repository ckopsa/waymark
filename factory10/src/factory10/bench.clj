(ns factory10.bench
  "The half the change row's doors share: how a handler reaches the
  bench rig, how it reads what the rig answered, and how it finds the
  seat and the sitting a commit is stamped with (bead
  waymark-fp62.6.3.2).

  HOW A DOOR REACHES THE BENCH. Through `gate-proxy/rpc-of` — the
  engine's OWN Gate caller — and PAST `invoke-for`, exactly as the
  thread sources reach Gate (workqueue10.sources.gate-chat). That door
  judges a CALLER's grant; `prepare`, `status`, `submit` and `discard`
  are on no capability token at all, so no scope can name them, no
  grant can admit them, and `waymark_power` refuses all four as not
  granted. The four reading and editing tools are the model's
  (bench.find, bench.read, bench.edit, bench.pull); these four are the
  ENGINE's. What submit means is a `repo_policy` row a person
  restates, never an argument a model gives.

  THE `:power` HOOK IS NOT THIS. `(:power ctx)` is the engine's hand
  on a power THE REQUEST'S OWN LEASH ADMITS (inbox_item's research
  door). It answers nil for a tool outside `tool-capability`, which is
  what these four are, so a door that used it would reach nothing. The
  caller here is the engine, and the leash it obeys is the change
  row's own doors.

  WHERE THE CALLER COMES FROM. `(:services ctx)` first — the map an
  application wires into its engine, and the seam a test scripts a
  fake rig through. Nothing wired, the deployment's own Gate: the same
  URL the household's thread sources read (WORKQUEUE10_GATE_URL), or
  the proxy's default. Built ONCE and held in a delay, so the MCP
  session to Gate is opened one time and reused.

  A REFUSAL IS AN ANSWER. Every tool of the rig answers its refusals
  as data — `{\"refused\": \"<name>\", …}` with isError — and never a
  stack trace. `ask` gives that map back whole, and the doors turn the
  names they know (nothing_to_commit, push_rejected, over_ceiling)
  into refusals of their own with a remedy on them. A rig that does
  not answer at all is nil, and a door that reads nil refuses with the
  sentence that says the bench is dark."
  (:require [clojure.string :as str]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.problems :as p]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── the caller ──────────────────────────────────────────────────────

(def gate-url-env
  "The environment variable that names the household's Gate. The same
  one the thread sources read (workqueue10.main), because there is one
  Gate and two readers of it."
  "WORKQUEUE10_GATE_URL")

(def ^:private deployment-rpc
  "The engine's own Gate caller, built at most once. A delay: an
  engine whose doors never touch the bench opens no client at all."
  (delay
   (let [url (some-> (System/getenv gate-url-env) str str/trim not-empty)]
     (gate/rpc-of {:gate (cond-> {} url (assoc :url url))}))))

(defn rpc-of
  "The Gate caller this ctx reaches the bench with: the one the
  application wired as `(:services :bench-rpc)` — a test's fake rig,
  or a deployment that holds its own client — else the deployment's.
  `(fn [method params])`, the shape `gate-proxy/rpc-of` answers."
  [ctx]
  (or (get-in ctx [:services :bench-rpc]) (force deployment-rpc)))

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
