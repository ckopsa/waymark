(ns waymark10.server.gate-proxy
  "The power door's grant judgment (waymark-q95, waymark-fp62.10): the
  door that holds the leash and stores nothing of what passes through.

  Since waymark-fp62.10 the servers behind this door are ROWS of the
  `mcp_server` kind (waymark10.server.mcp-servers). Each row holds its
  client and its `powers` list, and that list is the policy which
  replaced the static tool→token map this namespace used to hold. This
  namespace keeps the two things that are about the GRANT and not
  about a server:

  • `affordances-for` — the mirrored tools of every live server ∩ the
    caller's grant, rendered as a hypermedia document: reads as links,
    mutations (an entry with `why` true) as action forms whose input
    schema is the server's own. No wire is touched: the row is the
    record of what the server offers (R-4, R-6).

  • `invoke-for` — the name resolved to a tool (`tool-name-of`: a
    one-tool power's token is that tool's name), the tool resolved to
    its row by prefix, the power token judged in-process against the
    grant, a required `why` demanded, and only then the forward
    through the row's client. The payload is answered VERBATIM.

  GATE'S `__why` CONVENTION, surfaced as `why`: a passthrough row's
  tools carry `__why` in their schemas; this door speaks `why` and
  translates it back on the forward. On a server that is not
  passthrough, `why` is demanded when the entry says so and removed
  before the forward, because that server never asked for it.

  THE FILTER IS INTERPRETED, per call (waymark-fp62.6.3.5). A grant
  entry may carry filters, and the mcp_server row's `constraints` said
  at the ask which fields they may name — so this door never meets a
  field it has not been taught. `filter-verdict` judges each call
  against them before the forward: a `repo` value must be one the
  filter names, a `path` must match one of its globs, a call that
  names no path at all is forwarded with the globs as `allow`, and a
  call outside every entry refuses 403 without touching the rig. The
  entry's existence is what `admitted?` still answers, so affordances
  offer a narrowed power rather than hiding it.

  THE BENCH HELPERS (`bench-rig`, `bench-tool`, `bench-tool?`,
  `bench-max-bytes`, `bench-capped`) stay here because the bench's
  prefix and its byte ceiling must be spelled once for the sit, the
  power door and the change row's doors alike. They are text and
  arithmetic only: the bench is a row like any other server, reached
  through `rpc-of` / `mcp-servers/call!` by its prefix.

  The public names other namespaces call — `rpc-of`, `affordances-for`,
  `invoke-for`, `power-of` — keep their shapes. Each takes the engine,
  a dispatcher `rpc-of` built (it carries the engine), or a delay over
  one; `mcp-servers/engine-of` reads all three."
  (:require [clojure.string :as str]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.problems :as p]
            [waymark10.server.secrets :as secrets]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── the seeds and the seams ─────────────────────────────────────────

(def default-url
  "Where Gate answers on the household LAN. It seeds the gate row's
  url in `ensure-gate-row!` and nothing else reads it: there is no
  hidden fallback to it."
  servers/default-gate-url)

(def http-rpc
  "The streamable-HTTP client, kept under its old name for callers
  that build one by hand: (http-rpc url) → (fn [method params])."
  client/http-client)

(def rpc-of
  "The engine's power dispatcher, (fn [method params]) — tools/list
  answers the live rows' mirrored tools, tools/call resolves the
  prefixed name to a row and forwards. Takes the engine or something
  that derefs to it (main's engine-ref), read on every call."
  servers/rpc-of)

(def ensure-gate-row!
  "The bridge (R-13): the one row named gate, passthrough, seeded with
  the powers the static map used to hold."
  servers/ensure-gate-row!)

(def power-tokens
  "Every power token the rows' powers name, sorted."
  servers/power-tokens)

(def nameable-tokens
  "Every dotted token a scope may name — the rows' powers and the
  standing capability rows beside them (waymark-fp62.10.4). What
  discover's doors.ask.powers lists."
  servers/nameable-tokens)

(def power-constraints
  "Token → the fields a grant's filter may narrow it by, for the
  tokens that admit any (waymark-fp62.6.3.5). What discover's
  doors.ask.constraints lists beside the powers."
  servers/power-constraints)

(def power-glob-constraints
  "Token → the constraint fields whose filter values are GLOBS and not
  exact words, for the tokens that name any. What discover's
  doors.ask.glob_constraints lists beside the constraints."
  servers/power-glob-constraints)

;; ── the bench, as this door knows it (waymark-fp62.6.3.2) ───────────

(def bench-rig
  "The `name` of the bench's own `mcp_server` row, so the rig's tools
  wear `bench__<tool>` to every caller (waymark-fp62.6.3.3). Named
  once here because the row's `powers`, the power door's cap and the
  engine's own calls must all spell it the same. `rpc-of` and `call!`
  resolve this prefix to that row and ride its one client."
  "bench")

(defn bench-tool
  "One bench tool, as the prefix names it: `bench__prepare`,
  `bench__submit`. This is the name the dispatcher resolves."
  [tool]
  (str bench-rig "__" (name tool)))

(def bench-max-bytes
  "The rig's OWN ceiling on one answer, in bytes (the rig's
  CEILING_MAX_BYTES). The power door clamps `max_bytes` to the seat's
  ceiling when the seat names one, and to this when it does not: an
  answer larger than this is not served by the rig whatever a caller
  asks for, so a cap above it is a cap that says nothing."
  65536)

(defn bench-tool? [tool]
  (str/starts-with? (str tool) (str bench-rig "__")))

(defn bench-capped
  "The arguments of a bench call, with `max_bytes` clamped to the
  ceiling (R-2). A call that names no cap is untouched — the rig's own
  default (16,384) then stands, which is the smaller number and the
  one a seat should usually read under.

  The ceiling is the SEAT's when the request carries one and
  `bench-max-bytes` when it does not, and it is never above the rig's
  own: a seat may read less than the rig serves, never more."
  [args tool ceiling]
  (let [ceiling (min (long (or ceiling bench-max-bytes)) bench-max-bytes)
        asked (:max_bytes args)]
    (if (and (bench-tool? tool) (number? asked) (> (long asked) ceiling))
      (assoc args :max_bytes ceiling)
      args)))

(defn- engine!
  "The engine behind what a caller handed in, or the 502 that says
  there is none yet."
  [x]
  (or (servers/engine-of x)
      (throw (client/unreachable "the engine is not started yet."))))

;; ── the name the door resolves (waymark-fp62.6.3.12) ────────────────

(defn tool-name-of
  "The tool this call is about, for a caller that typed either
  spelling: a tool name is itself, and a power TOKEN that admits
  exactly one tool is that tool (`bench.read` → `bench__read`).
  `mcp-servers/tool-name-of` holds the rule; this wrapper reads the
  engine out of a dispatcher and answers the name unchanged when
  there is no engine yet, because a name is text and a dark engine
  must not cost a caller its refusal."
  [eng-or-rpc nm]
  (if-some [eng (servers/engine-of eng-or-rpc)]
    (servers/tool-name-of eng nm)
    (str nm)))

(defn token-tool
  "The ONE tool a power token admits, or nil when it admits none or
  more than one — `mcp-servers/token-tool`, over a dispatcher."
  [eng-or-rpc token]
  (some-> (servers/engine-of eng-or-rpc) (servers/token-tool token)))

(defn token-tools
  "EVERY tool a power token admits, as a caller spells them —
  `mcp-servers/token-tools`, over a dispatcher. Empty when there is
  no engine yet."
  [eng-or-rpc token]
  (if-some [eng (servers/engine-of eng-or-rpc)]
    (servers/token-tools eng token)
    []))

;; ── the grant's read of the policy ──────────────────────────────────

(defn- admitted?
  "Does the presented visibility admit this power token at all? The
  entry must EXIST — visibility already judged audience, acceptance,
  expiry and revocation, so every one of those has collapsed into a
  missing entry by the time this asks.

  A FILTER NO LONGER DISQUALIFIES (waymark-fp62.6.3.5). The entry says
  the token is held; whether THIS call sits inside the filter is a
  question about the call, and `filter-verdict` asks it per call in
  `invoke-for`. Affordances are a question about the token, so they
  read this: a seat granted bench.read over one repository is offered
  bench__read and refused the repositories it was not granted."
  [vis token]
  (some? (grants/capability-entry vis token)))

(defn admitted-tokens
  "The power tokens any row names that this visibility admits."
  [eng-or-rpc vis]
  (let [eng (engine! eng-or-rpc)]
    (into #{} (filter #(admitted? vis %)) (servers/power-tokens eng))))

;; ── the filter, interpreted (waymark-fp62.6.3.5) ────────────────────
;;
;; The owner's ruling: the ENGINE is the enforcement point for the
;; bench, the rig is the hand. The seat key stays in the engine and
;; the rig holds no seat and no rule between calls — so a narrow power
;; is a sentence the grant carries and this door reads on every call,
;; not a configuration somebody remembered to put on the rig.
;;
;; A grant entry's `:filters` is a vector of {field value} maps (the
;; mcp_server row's `constraints` already refused, at the ask, every
;; field this door would not know what to do with). The door admits a
;; call that ANY of the maps admits. A `path` value is a comma list of
;; GLOBS; every other field is a comma list of WORDS compared for
;; equality, which is the collection grammar's own `:eq`.

(def ^:private path-filter-field
  "The one filter field whose values are PATH GLOBS and not words. It
  is spelled once because the judgment below and the `allow` argument
  the rig receives must mean the same field."
  "path")

(defn- comma-values
  "A filter value as the list it may be: `a,b` is 'either', a blank
  part is nothing at all."
  [v]
  (into [] (comp (map str/trim) (remove str/blank?))
        (str/split (str v) #",")))

(defn path-glob-matches?
  "Does this glob match this path? The grammar is the bench rig's own
  deny grammar, Python's fnmatch, so a person writing a grant filter
  and a person writing a repository policy write the same sentence:
  `*` matches any run of characters, slashes included, `?` matches
  one character, and `**` is `*`. A glob also matches when it matches
  the path's last part alone (`*.pem` matches keys/server.pem), and a
  glob that starts with `**/` also matches with that prefix removed.
  `docs/*` matches docs/a/b.md; `*.md` matches docs/b.md."
  [glob path]
  (let [glob (str glob)
        path (str path)
        re-of (fn [g]
                (re-pattern
                 (str "\\A"
                      (str/join (map (fn [tok]
                                       (case tok
                                         "**" ".*"
                                         "*" ".*"
                                         "?" "."
                                         (java.util.regex.Pattern/quote tok)))
                                     (re-seq #"\*\*|\*|\?|[^*?]+" g)))
                      "\\z")))
        base (last (str/split path #"/"))
        full (re-of glob)]
    (boolean
     (or (re-matches full path)
         (re-matches full base)
         (and (str/starts-with? glob "**/")
              (re-matches (re-of (subs glob 3)) path))))))

(defn- whole-tree?
  "Is this call's `path` the whole checkout? A find that names no path
  and the rig's own `.` are one ask, and a path filter answers both
  the same way: not a refusal, but the `allow` list the rig is told to
  hold itself to."
  [args]
  (let [p (str/trim (str (:path args)))]
    (or (str/blank? p) (= "." p))))

(defn- field-admits?
  "Does the filter's `want` admit the call's `got` on this field? The
  path field, and every field in `globs` — the names the power's own
  entry lists in `glob_constraints` — read each wanted value as a glob
  in path's grammar. Every other field matches a wanted value exactly,
  so a grant on an entry that names no glob field means what it meant
  before."
  ([fname want got] (field-admits? fname want got nil))
  ([fname want got globs]
   (if (or (= path-filter-field fname) (contains? globs fname))
     (boolean (some #(path-glob-matches? % got) (comma-values want)))
     (boolean (some #(= (str got) %) (comma-values want))))))

(defn- entry-verdict
  "One filter map against one call's arguments → {:allow globs|nil}
  when it admits the call, {:miss {…}} when it does not. `:allow` nil
  means this map narrows no path, and openness absorbs below.

  A path filter judges a move's `move_to` too: a move writes the file
  it names, so the destination must match one of the same globs as the
  source, or the call misses on `move_to`.

  `globs` is the set of field names the power's entry says take globs
  (`glob_constraints`); left out, only the path does."
  [fm args & [globs]]
  (reduce
   (fn [acc [f want]]
     (let [fname (name f)
           got (get args (keyword fname))
           move-to (:move_to args)]
       (cond
         (and (= path-filter-field fname)
              (some? move-to)
              (not (field-admits? fname want move-to)))
         (reduced {:miss {:field "move_to" :got (str move-to)
                          :want (str want)}})

         (and (= path-filter-field fname) (whole-tree? args))
         (update acc :allow (fnil into []) (comma-values want))

         (nil? got)
         (reduced {:miss {:field fname :got nil :want (str want)}})

         (field-admits? fname want got globs) acc

         :else (reduced {:miss {:field fname :got (str got)
                                :want (str want)}}))))
   {:allow nil}
   fm))

(defn- filter-verdict
  "THE PER-CALL JUDGMENT. The grant entry's filters against this
  call's arguments → {:allow globs|nil} to forward, or {:miss {:field
  :got :want}} to refuse. An entry with no filters admits everything,
  which is a grant behaving exactly as it did before this leg.

  `:allow` is the union of the path globs of the entries that admitted
  the call, and nil when ONE of them narrows no path: openness absorbs
  a sibling's narrowing here as it does everywhere else in the
  surface, because a caller admitted by an unnarrowed entry is not
  narrowed at all."
  [filters args & [globs]]
  (if (empty? filters)
    {:allow nil}
    (let [verdicts (mapv #(entry-verdict % args globs) filters)
          ok (remove :miss verdicts)]
      (cond
        (empty? ok) {:miss (:miss (first verdicts))}
        (some #(nil? (:allow %)) ok) {:allow nil}
        :else {:allow (not-empty (vec (distinct (mapcat :allow ok))))}))))

(defn- with-allow
  "The arguments the rig receives, with `allow` on them when the
  filter narrowed paths and the call named none: the rig holds itself
  to the globs for the one call, and the engine never has to enumerate
  a tree to answer a find."
  [args allow]
  (cond-> (or args {}) (seq allow) (assoc :allow (vec allow))))

;; ── the protected paths: the engine decides `allow_protected` ───────
;;
;; The rig refuses a write under .github/ or .claude/ unless the call
;; carries `allow_protected: true`. A workflow reads the repository's
;; secrets and a .claude/ hook runs in every session that opens the
;; repository, so the flag is the ENGINE's to set and never the
;; caller's: it is dropped from every bench call, and set on a
;; bench.edit — or on every item of a bench.edit_many, which is many
;; of that one edit in a single call — only when the admitting filter
;; names the path (R-12.30).

(def ^:private protected-prefixes [".github/" ".claude/"])

(defn- protected-path? [p]
  (boolean (some #(str/starts-with? (str p) %) protected-prefixes)))

(defn- clean-path?
  "A path with no empty, `.` or `..` part and no backslash: one whose
  prefix says where it lands, so a glob matched against it means what
  it says."
  [p]
  (let [p (str p)]
    (and (not (str/includes? p "\\"))
         (not-any? #{"" "." ".."} (str/split p #"/" -1)))))

(defn- protected-globs
  "The path globs of the filter maps that admit this call and that
  themselves start with a protected prefix. A `*` or `**` glob matches
  .github/ only by accident, and it never counts."
  [filters args]
  (into []
        (comp (remove #(:miss (entry-verdict % args)))
              (mapcat (fn [fm]
                        (some (fn [[k v]]
                                (when (= path-filter-field (name k))
                                  (comma-values v)))
                              fm)))
              (filter protected-path?))
        filters))

(def ^:private edit-items-field
  "The argument a bench.edit_many carries its writes in. Each item is
  one edit with its own `path`, so the engine judges every one of them
  the way it judges the lone edit's."
  :edits)

(defn- arg-str
  "One argument as a non-blank string, spelled either way: a call that
  arrives over the wire is keywordized, and one an engine hands in may
  not be."
  [m k]
  (when (map? m)
    (some-> (or (get m k) (get m (name k))) str not-empty)))

(defn- edit-targets
  "Every path a bench write means to write — {:item n :path p}, with
  `item` nil for the call's own `path` and `move_to` and the item's
  position, counted from one, for a bench.edit_many's. That number is
  what the refusal names, so a seat fixes the item it wrote instead of
  doubting a scope that was right."
  [args]
  (let [own (keep (fn [k] (when-some [p (arg-str args k)] {:path p}))
                  [:path :move_to])
        items (or (get args edit-items-field)
                  (get args (name edit-items-field)))
        each (when (sequential? items)
               (mapcat (fn [i it]
                         (keep (fn [k]
                                 (when-some [p (arg-str it k)]
                                   {:item (inc i) :path p}))
                               [:path :move_to]))
                       (range) items))]
    (vec (concat own each))))

(def ^:private protected-edit-tokens
  "The powers whose filters may name a protected path. An edit_many is
  many bench.edits in one call, so a seat told it may write
  .github/workflows/tests.yml has been told that however it batches
  the write: both entries' protected globs stand for either tool."
  ["bench.edit" "bench.edit_many"])

(defn- protected-edit-globs
  "The protected path globs that admit this call: those on the entry
  the call is judged under, and those on the seat's other edit entry."
  [vis gentry args]
  (into (vec (when gentry (protected-globs (:filters gentry) args)))
        (comp (keep #(grants/capability-entry vis %))
              (mapcat #(protected-globs (:filters %) args)))
        protected-edit-tokens))

(defn- protected-verdict
  "How a bench edit's protected targets stand: nil when it writes none,
  {:allow true} when every one of them is clean and named by a
  protected glob the seat's edit entries carry, and {:refuse {…}}
  naming the first that is not. The refusal is the BATCH's alone — a
  lone bench.edit reaches the rig without the flag, as it always has,
  and the rig refuses that write there."
  [vis tname gentry args]
  (when (some #(= tname (bench-tool %)) [:edit :edit_many])
    (let [targets (edit-targets args)
          guarded (filterv #(protected-path? (:path %)) targets)]
      (when (seq guarded)
        (let [globs (protected-edit-globs vis gentry args)
              named? (fn [{:keys [path]}]
                       (and (clean-path? path)
                            (boolean (some #(path-glob-matches? % path)
                                           globs))))
              bad (or (first (remove named? guarded))
                      (first (remove #(clean-path? (:path %)) targets)))]
          (cond
            (nil? bad) {:allow true}
            (= tname (bench-tool :edit_many)) {:refuse bad}
            :else nil))))))

(defn- bench-protected
  "The arguments of a bench call with `allow_protected` decided by the
  engine: any the caller sent is dropped, and the flag set when the
  judgment above admitted every protected target of the call. Every
  other call reaches the rig without it, and the rig refuses a
  protected write as it always has."
  [tname args protected]
  (if-not (bench-tool? tname)
    args
    (cond-> (dissoc (or args {}) :allow_protected "allow_protected")
      (:allow protected) (assoc :allow_protected true))))

;; ── affordances ─────────────────────────────────────────────────────

(defn- present-schema
  "The server's own inputSchema, with Gate's `__why` surfaced as
  `why`, and a `why` added when the entry demands one the schema does
  not already name."
  [schema why?]
  (let [schema (secrets/describe-refs
                (or schema {:type "object" :properties {}}))
        schema (if-some [why (get-in schema [:properties :__why])]
                 (-> schema
                     (update :properties #(-> % (dissoc :__why) (assoc :why why)))
                     (cond-> (:required schema)
                       (update :required
                               (partial mapv #(if (= "__why" %) "why" %)))))
                 schema)]
    (if (and why? (nil? (get-in schema [:properties :why])))
      (-> schema
          (assoc-in [:properties :why]
                    {:type "string"
                     :description (str "One sentence of reason. This engine "
                                       "demands it and a person reads it.")})
          (update :required #(vec (distinct (conj (vec %) "why")))))
      schema)))

(defn- affordance [{:keys [name token description input-schema why entry
                           approval]}]
  (cond-> {:href (str "/api/-/gate/" name)
           :method "POST"
           :capability token
           :description (cond-> (str description)
                          (= :person approval)
                          (str " A person must allow this call before the"
                               " engine makes it."))
           :input (present-schema input-schema why)}
    ;; the fields a grant may narrow this power by (waymark-fp62.6.3.5)
    ;; — an agent reads them here and asks for the narrow grant itself,
    ;; rather than asking for the whole rig and being told no
    (seq (:constraints entry))
    (assoc :constraints (vec (:constraints entry)))

    ;; and which of them a filter may name a glob on
    (seq (:glob_constraints entry))
    (assoc :glob_constraints (vec (:glob_constraints entry)))

    why
    (assoc :why {:required true
                 :note (str "One sentence of rationale; the person who "
                            "approves this action reads it.")}
           :safety {:confirm false
                    :consequence
                    (str "This action acts on the outside: your `why` is "
                         "the sentence a person reads before or after "
                         "it lands.")})

    ;; THE APPROVAL IS SURFACED LIKE THE WHY (waymark-fp62.10.2, R-14).
    ;; An agent that reads `approval: person` here knows the call will
    ;; not run at once, that it will get a held_call id back rather
    ;; than the tool's answer, and that the wait is the design and not
    ;; a fault. A tool whose entry says so and does not say so here
    ;; would teach the agent to retry.
    (= :person approval)
    (assoc :approval "person"
           :held {:note (str "A person must allow this call. The engine "
                             "answers {held: true, held_call: <id>} at "
                             "once and forwards the call only after the "
                             "tap. Read the held_call row for the "
                             "answer; do not call again.")})))

(defn- survivors
  "THE one computation both surfaces project: the live rows' mirrored
  tools ∩ the caller's grant, recomputed per call from the rows."
  [eng vis]
  (let [tokens (admitted-tokens eng vis)]
    (if (empty? tokens)
      []
      (into []
            (filter #(and (:token %) (contains? tokens (:token %))))
            (servers/offered eng)))))

(defn affordances-for
  "GET /api/-/gate's document and waymark_powers' answer: the live
  servers' tools ∩ the caller's grant, recomputed per call from the
  rows. Reads under :links, mutations (why required) under :actions
  as forms; each survivor's input schema is the server's own. A grant
  admitting no token reads an empty document with the ask door."
  [eng-or-rpc vis]
  (let [eng (engine! eng-or-rpc)
        {reads false mutations true} (group-by :why (survivors eng vis))
        entry #(vector (str (:name %)) (affordance %))]
    {:waymark "10"
     :self "/api/-/gate"
     :note (str "External powers reached THROUGH this engine: the live "
                "servers' tools intersected with your grant, read from each "
                "server's row on every call. Nothing behind these "
                "affordances is stored here — invoke one and the payload "
                "passes through untouched. Mutations carry a `why`: one "
                "sentence a person reads.")
     :links (into {} (map entry) reads)
     :actions (into {} (map entry) mutations)
     :ask {:href "/api/approval_requests"
           :method "POST"
           :note (str "More sight is asked for, never assumed: scope entries "
                      "naming a dotted capability token (email.read, "
                      "email.send, email.move — GET /api/capabilities for "
                      "the words) mint the grant this door reads.")}}))

;; ── the bench's own guard: prepare is bench.edit's door ────────────
;;
;; bench.read's tools can be widened to admit bench__prepare (the
;; mcp_server row's `powers` is data this file does not hold), and a
;; reader that opens a worktree could land in a code seat's own or
;; take the branch name it wants next. This door refuses that one
;; call whatever token got a caller past the grant and filter checks
;; above: bench.edit on the repository is the only way through a
;; protected branch, the same as the rig already refuses submit on
;; the default branch.

(def ^:private default-base "main")

(def ^:private default-branch-pattern "waymark/*")

(defn- repo-policy-of
  "The active `repo_policy` row for one repository, or nil — nil is
  also the answer in an engine that declares no `repo_policy` kind at
  all, which is every engine but the factory's. Duplicated from
  waymark10.server.mcp's own read of the same row: the gate is the
  enforcement point and must not require the layer built on top of it."
  [eng repository]
  (when-some [rdef (when-not (str/blank? (str repository))
                     (get (inv/resources eng) :repo_policy))]
    (some->> (store/with-tx (:storage eng)
               (fn [tx]
                 (first (store/query-rows (:storage eng) tx :repo_policy
                                          {:repository (str repository)
                                           :state :active}
                                          {:limit 1}))))
             (inv/decode-row rdef))))

(defn- bench-edit-admits-repo?
  "Does this visibility's bench.edit or bench.edit_many — if it holds
  either — admit this repository? No entry, or only ones filtered away
  from this repository, answers false. The batch is many bench.edits
  in one call, so a seat that holds it alone holds a write power on
  the repository the way a seat that holds the single edit does: the
  tokens are `protected-edit-tokens`, the two that write a path."
  [vis repo]
  (boolean (some (fn [token]
                   (when-some [gentry (grants/capability-entry vis token)]
                     (not (:miss (filter-verdict (:filters gentry) {:repo repo})))))
                 protected-edit-tokens)))

(defn- protected-branch?
  "Is this branch the repository's base, or one its own branch_pattern
  would mint? Both are bench.edit's to prepare."
  [policy branch]
  (let [base (or (some-> (get-in policy [:data :base]) str not-empty)
                 default-base)
        pattern (or (some-> (get-in policy [:data :branch_pattern]) str not-empty)
                    default-branch-pattern)]
    (or (= base branch) (path-glob-matches? pattern branch))))

(defn- bench-prepare-block
  "Why a bench__prepare call should be refused before the forward, as
  {:repo :policy :branch}, or nil when it should not be: bench.edit on
  the repository stands untouched, and so does a branch outside the
  repository's own convention. A repository with no active repo_policy
  row blocks every branch, because there is no convention to stand
  outside of."
  [eng vis tname args]
  (when (= tname (bench-tool :prepare))
    (let [repo (some-> (:repo args) str not-empty)
          branch (some-> (:branch args) str not-empty)]
      (when (and repo branch (not (bench-edit-admits-repo? vis repo)))
        (let [policy (repo-policy-of eng repo)]
          (when (or (nil? policy) (protected-branch? policy branch))
            {:repo repo :policy policy :branch branch}))))))

;; ── invoke ──────────────────────────────────────────────────────────

(defn- refuse-invoke
  "One 403, spelled so the next move is obvious: capabilities are
  WORDS, so naming the token discloses nothing, and what it buys is
  that an agent reading this sentence knows how to ASK."
  [detail token]
  (throw (p/problem :gate-not-granted 403 "Not granted"
                    {:detail detail
                     :remedies
                     [(str "POST /api/approval_requests with scope "
                           "[{\"kind\": \"" token "\", \"actions\": []}]"
                           " — a human in the house approves it, and the"
                           " grant it mints is what this door reads.")]})))

(defn- refuse-filter
  "The 403 for a call the grant admits the TOKEN for and not this
  call (waymark-fp62.6.3.5). It names four things — the token, the
  field, what the call carried and what the filter admits — because an
  agent that reads all four either calls inside the filter or asks for
  one that names what it needs, and an agent told only `refused` can
  do neither. Nothing reached the rig."
  [tname token {:keys [field got want]}]
  (refuse-invoke
   (str "Invoking " tname " is the " token " capability, and this grant"
        " names it with a filter this call stands outside of: `" field
        "` is " (if got (str "\"" got "\"") "not on this call")
        " and the filter admits " want ". Call inside the filter, or"
        " ask for one that names what you need.")
   token))

(defn- refuse-bench-protected
  "The 403 for a batch that writes under .github/ or .claude/ where the
  seat's edit filters name no such path. It names the TOOL and the
  ITEM, because a seat told only `protected` concludes its whole scope
  is wrong and files a follow-up against a scope that was right.
  Nothing reached the rig."
  [tname token {:keys [item path]}]
  (refuse-invoke
   (str tname (when item (str " item " item)) " writes " path
        ", which no bench.edit filter of this seat names. A write under"
        " .github/ or .claude/ is the engine's to allow, and it allows"
        " only a clean path that a protected glob on this grant's edit"
        " entries names. Write the path that glob names, or ask for a"
        " filter that names this one.")
   token))

(defn- refuse-unknown
  "The 404 for a name this door does not answer to (R-5).

  A name that is a power TOKEN of more than one tool is the one case
  that says more: the token is real, and it names no single tool, so
  the detail LISTS the tool names it admits and the caller spells one
  of them. Every other name gets the plain not-found: a tool no
  powers entry names does not exist through this door, and neither
  does a token no row names."
  [eng nm]
  (let [tools (servers/token-tools eng nm)]
    (if (> (count tools) 1)
      (throw (p/problem :not-found 404 "Not found"
                        {:detail (str "The power " nm " admits more than"
                                      " one tool, so it is not a tool name"
                                      " here. Call one of these: "
                                      (str/join ", " tools) ".")
                         :remedies [(str "Call again with tool set to one of "
                                         (str/join ", " tools) ".")]}))
      (throw (p/not-found "power" nm)))))

(defn- refuse-why
  "The 422 for a why-required tool called with no why."
  [tname]
  (throw (p/problem :why-required 422 "Why is required"
                    {:detail (str tname " requires a why: one sentence that"
                                  " says why this call is made. This engine"
                                  " demands it and a person reads it.")
                     :remedies ["Call again with arguments.why set to one sentence."]})))

;; ── the shape of a call, judged before a person is asked ────────────
;;
;; The row mirrors each tool's input schema, and `affordances-for`
;; already serves it. A call that schema does not admit is refused
;; HERE, before a held_call row exists and before the wire: a person's
;; tap is never spent on a call the server would refuse for its shape.
;; The check is the plain part of JSON Schema — required, type, enum,
;; `additionalProperties: false`, nested properties and items. What it
;; does not read (anyOf, formats, bounds) it lets through, and the
;; server still judges the call.

(def ^:private why-names #{"why" "__why"})

(defn- sget
  "A schema key in either spelling: a row's stored schema is keyworded,
  and one built by hand may not be."
  [m k]
  (when (map? m)
    (if (contains? m k) (get m k) (get m (name k)))))

(defn- props-of [schema]
  (into {} (map (fn [[k v]] [(name k) v])) (sget schema :properties)))

(defn- types-of [schema]
  (let [t (sget schema :type)]
    (cond (nil? t) nil
          (sequential? t) (mapv str t)
          :else [(str t)])))

(defn- type-ok?
  "A secret-row reference is a bare id, so it is a string where a
  string is expected. A type this door does not know admits anything."
  [t v]
  (case (str t)
    "string" (string? v)
    "integer" (or (integer? v)
                  (and (number? v) (== v (Math/rint (double v)))))
    "number" (number? v)
    "boolean" (boolean? v)
    "array" (sequential? v)
    "object" (map? v)
    "null" (nil? v)
    true))

(defn- shape-errors
  "[{:field :reason} …] for what `schema` does not admit of `v`. A nil
  under an optional property counts as absent."
  [schema v path]
  (let [types (types-of schema)
        enum (sget schema :enum)
        at (if (str/blank? path) "arguments" path)
        sub #(if (str/blank? path) % (str path "." %))]
    (cond
      (and types (not-any? #(type-ok? % v) types))
      [{:field at :reason (str "takes " (str/join " or " types))}]

      (and (sequential? enum) (not-any? #(= % v) enum))
      [{:field at
        :reason (str "takes one of " (str/join ", " (map pr-str enum)))}]

      (map? v)
      (let [props (props-of schema)
            given (into {} (map (fn [[k x]] [(name k) x])) v)]
        (vec
         (concat
          (for [r (map str (sget schema :required))
                :when (nil? (get given r))]
            {:field (sub r) :reason "is required"})
          (when (false? (sget schema :additionalProperties))
            (for [k (sort (keys given))
                  :when (not (contains? props k))]
              {:field (sub k) :reason "is not an argument of this tool"}))
          (for [[k x] (sort-by key given)
                :when (and (some? x) (contains? props k))
                e (shape-errors (get props k) x (sub k))]
            e))))

      (sequential? v)
      (let [items (sget schema :items)]
        (vec (mapcat (fn [i x] (shape-errors items x (str at "[" i "]")))
                     (range) v)))

      :else [])))

(defn- call-shape-errors
  "`shape-errors` for one call's arguments, minus the why in either
  spelling: the why is this door's, and `refuse-why` judges it."
  [schema args]
  (when (map? schema)
    (->> (shape-errors schema
                       (into {}
                             (remove (fn [[k _]] (contains? why-names (name k))))
                             (or args {}))
                       "")
         (remove #(contains? why-names (:field %)))
         vec)))

(defn- expected-shape
  "The arguments a tool takes, in one line: required ones first, each
  with its type."
  [schema]
  (let [required (set (map str (sget schema :required)))]
    (->> (props-of schema)
         (remove (fn [[k _]] (contains? why-names k)))
         (sort-by (fn [[k _]] [(not (contains? required k)) k]))
         (map (fn [[k prop]]
                (str "`" k "` ("
                     (str/join " or " (or (types-of prop) ["any"]))
                     (when (contains? required k) ", required") ")")))
         (str/join ", "))))

(defn- refuse-shape
  "The 422 for arguments the tool's own input schema does not admit:
  each mismatch by field, and the shape the tool takes."
  [tname schema why? errors]
  (let [takes (expected-shape schema)]
    (throw (p/problem :invalid-arguments 422
                      "Arguments do not match the tool's input schema"
                      {:detail (str tname " was not called: "
                                    (str/join "; "
                                              (map #(str "`" (:field %) "` "
                                                         (:reason %))
                                                   errors))
                                    "."
                                    (if (str/blank? takes)
                                      " It takes no arguments."
                                      (str " It takes " takes ".")))
                       :errors (vec errors)
                       :expected (present-schema schema why?)
                       :remedies [(str "Call again with arguments that match"
                                       " the tool's input schema;"
                                       " waymark_powers serves it.")]}))))

(def ^:private shape-words
  "What a server's own refusal says when it is about the arguments."
  #"(?i)argument|parameter|required|missing|unknown|unexpected|invalid|schema|must be")

(defn- teach-shape
  "A server's own `isError` answer about its arguments, with the tool's
  mirrored input schema added as one last text part: an open schema
  passes this door's check, and the server may still refuse the call.
  It is one tool's schema, so one refusal teaches the call. Any other
  answer passes as it came."
  [tname schema why? res]
  (if (and (map? res) (true? (:isError res)) (map? schema)
           (some #(and (string? (:text %)) (re-find shape-words (:text %)))
                 (:content res)))
    (update res :content
            #(conj (vec %)
                   {:type "text"
                    :text (str tname " takes these arguments: "
                               (wire/write-json (present-schema schema why?)))}))
    res))

(defn- refuse-anonymous
  "The 403 for a call this door would HOLD and cannot: a held call
  names its caller, and the first wall on answering it is `not the
  caller`. A call that reached here with nobody's name on it would
  mint a row anybody could allow, so the door refuses instead."
  [tname]
  (throw (p/problem :gate-not-granted 403 "Not granted"
                    {:detail (str tname " waits on a person's tap, and a"
                                  " held call names who made it. This"
                                  " request carries no principal, so there"
                                  " is nobody to hold the call for.")
                     :remedies ["Call again as a named principal: a session that sat in a seat, or a signed-in person."]})))

(defn- refuse-bench-prepare
  "The 403 for bench__prepare on a branch this repository's own
  convention claims, from a grant that holds no bench.edit there: a
  reader who opened that worktree would land in a code seat's own, or
  take the branch name it wants next."
  [tname {:keys [repo policy branch]}]
  (throw (p/problem
          :gate-not-granted 403 "Not granted"
          {:detail
           (str "Invoking " tname " on " repo " at `" branch "` is"
                " bench.edit, and this grant holds only bench.read"
                " there: "
                (if policy
                  (str "`" branch "` is " repo "'s base or a branch its"
                       " branch_pattern would mint, and only bench.edit"
                       " prepares one of those.")
                  (str repo " has no active repo_policy row, so every"
                       " branch on it is reserved.")))
           :remedies
           [(if policy
              "Prepare a branch outside the branch_pattern instead — `read/<seat name>`, by convention."
              (str "State a repo_policy for " repo ", or prepare in an"
                   " enrolled repository."))]})))

;; ── the sitting that holds the row (ticket d7c854b3) ────────────────
;;
;; A branch names ONE walk row, and a write on it is the write of the
;; sitting that holds that row. The row is the walked row whose own
;; `branch` field is the call's branch (a ticket carries a descriptive
;; one, ticket 81931c1c); only when no walked row claims the branch is
;; it what the `*` of the repository's branch_pattern stands for. A sitting closed early while its run
;; still edits, or one another open sitting of its seat has taken the
;; row from, is refused before the forward: two sittings never write
;; one branch. Reads stay open, a branch no row claims and outside the
;; pattern is not judged, and neither is a call that names no sitting (the REST door,
;; the engine's own hand). A sitting whose walk handed it no row at all
;; is judged only on being open and on no other sitting holding the row.

(def ^:private bench-writes #{:edit :edit_many :pull :submit})

(defn- bench-write? [tname]
  (boolean (some #(= tname (bench-tool %)) bench-writes)))

(defn- branch-row-id
  "The walk row a branch was minted for: what the `*` of the
  repository's branch_pattern stands for in it, or nil for a branch
  outside the pattern. The fallback, when no walked row's own `branch`
  field claims the branch (`claiming-row-id`)."
  [policy branch]
  (let [pattern (or (some-> (get-in policy [:data :branch_pattern]) str not-empty)
                    default-branch-pattern)
        [pre post] (str/split pattern #"\*" 2)
        pre (str pre)
        post (str post)
        branch (str branch)]
    (when (and (str/includes? pattern "*")
               (str/starts-with? branch pre)
               (str/ends-with? branch post)
               (> (count branch) (+ (count pre) (count post))))
      (subs branch (count pre) (- (count branch) (count post))))))

(defn- holds-row? [sitting row-id]
  (boolean (some #(= row-id (str %)) (get-in sitting [:data :walked_rows]))))

(defn- walk-kind-of
  "The kind this seat walks, when this engine declares it, or nil."
  [eng st tx seat]
  (when seat
    (let [k (some-> (store/load-row st tx :seat seat {})
                    (get-in [:data :walk]) str not-empty keyword)]
      (when (and k (get (inv/resources eng) k)) k))))

(defn- claiming-row-id
  "The walk row whose own `branch` field is this branch, among the rows
  these sittings were handed, or nil when none names it."
  [st tx kind sittings branch]
  (when kind
    (->> sittings
         (mapcat #(get-in % [:data :walked_rows]))
         (keep #(some-> % str not-empty))
         distinct
         (some (fn [id]
                 (when (= branch (some-> (store/load-row st tx kind id {})
                                         (get-in [:data :branch]) str))
                   id))))))

(defn- bench-hold-block
  "Why a bench write should be refused because the calling sitting does
  not hold the row its branch was minted for, as {:row :sitting :state
  :ended :holder}, or nil when it should not be."
  [eng tname args opts]
  (when (bench-write? tname)
    (let [sid (some-> (:sitting opts) str not-empty)
          branch (some-> (:branch args) str not-empty)]
      (when (and sid branch (get (inv/resources eng) :sitting))
        (let [st (:storage eng)
              policy (repo-policy-of eng (:repo args))
              [sitting row-id holder]
              (store/with-tx st
                (fn [tx]
                  (let [s (store/load-row st tx :sitting sid {})
                        seat (some-> (get-in s [:data :seat]) str not-empty)
                        others (when seat
                                 (->> (store/query-rows st tx :sitting
                                                        {:seat seat :state :open}
                                                        {:limit 50 :newest-first true})
                                      (remove #(= sid (str (:id %))))))
                        row-id (or (claiming-row-id st tx (walk-kind-of eng st tx seat)
                                                    (cons s others) branch)
                                   (branch-row-id policy branch))]
                    [s row-id (when row-id
                                (first (filter #(holds-row? % row-id) others)))])))
              state (some-> (:state sitting) name)
              walked (seq (get-in sitting [:data :walked_rows]))]
          (when (and sitting row-id
                     (or holder
                         (not= "open" state)
                         (and walked (not (holds-row? sitting row-id)))))
            {:row row-id :sitting sid :state state
             :ended (get-in sitting [:data :ended_at])
             :holder (some-> (:id holder) str)}))))))

(defn- refuse-bench-hold
  "The 409 for a bench write from a sitting that no longer holds the
  row its branch was minted for. It names the row and why, and the
  remedy is to stop: the sitting that holds the row finishes it."
  [tname {:keys [row sitting state ended holder]}]
  (throw (p/problem
          :sitting-does-not-hold 409 "Not this sitting's row"
          {:detail
           (str "Invoking " tname " writes the branch of ticket " row
                ", and this sitting no longer holds ticket " row " ("
                (cond
                  holder (str "held by sitting " holder)
                  (not= "open" state) (str "sitting " sitting " is " state
                                           (when ended (str ", closed at " ended)))
                  :else (str "sitting " sitting "'s walk never handed it that row"))
                "); stop, do not write.")
           :remedies
           ["Stop: do not write this branch. The sitting that holds the row finishes it; close this one."]})))

(defn- carries-why? [args]
  (or (not (str/blank? (str (:why args))))
      (not (str/blank? (str (:__why args))))))

(defn- why-of
  "The caller's one sentence, in either spelling. The held call keeps
  it, because the person who taps reads it."
  [args]
  (first (remove str/blank? [(str (:why args)) (str (:__why args)) ""])))

(defn- gate-args
  "The caller's arguments as Gate expects them: `why` translated to
  Gate's `__why`; an explicit `__why` wins."
  [args]
  (let [args (or args {})]
    (if (and (contains? args :why) (not (contains? args :__why)))
      (-> args (dissoc :why) (assoc :__why (:why args)))
      (dissoc args :why))))

(defn- forward-args
  "What the row's server receives: Gate's spelling on a passthrough
  row, and neither spelling on a server that never asked for one."
  [row args]
  (if (true? (get-in row [:data :passthrough]))
    (gate-args args)
    (dissoc (or args {}) :why :__why)))

(defn- stored
  "One row as it stands now, decoded, or nil."
  [eng kind id]
  (when-some [rdef (get (inv/resources eng) kind)]
    (let [st (:storage eng)]
      (some->> (store/with-tx st
                 (fn [tx] (store/load-row st tx kind (str id) {})))
               (inv/decode-row rdef)))))

(defn- approved-run?
  "Is this call the run of a scheduled action a person approved when it
  was scheduled (docs/spec-scheduled-actions.md R-4.3)? `:within` names
  the row. It must be `running`, name this tool and this caller, and
  name a held call that is `done` by a person's allow of `arm` on that
  same row: `holds/allowed-hold`'s second shape, for a call that has no
  engine door. Only the engine's own run passes `:within`; a hand at the
  wire passes a caller and a sitting."
  [eng tools {:keys [within caller]}]
  (let [{wkind :kind waction :action sid :id} within]
    (boolean
     (when (and (= :scheduled_action wkind) (= :run waction) sid)
       (when-some [s (stored eng :scheduled_action sid)]
         (let [{:keys [acts_as held_call target]} (:data s)
               h (some->> held_call str not-empty (stored eng :held_call))
               door (get-in h [:data :door])]
           (and (= :running (:state s))
                (contains? tools (str (:tool target)))
                (= (str caller) (str (:id acts_as)))
                (= :done (:state h))
                (not (str/blank? (str (get-in h [:data :decided_by]))))
                (= "scheduled_action" (str (:kind door)))
                (= "arm" (str (:action door)))
                (= (str sid) (str (:id door)))
                (= (str caller) (str (get-in h [:data :caller]))))))))))

(defn invoke-for
  "POST /api/-/gate/{tool} and waymark_power: the NAME resolved to a
  tool, the tool resolved to its row by prefix, the entry's power
  token judged IN-PROCESS against the grant, a required why demanded,
  and only then the forward.

  THE NAME COMES FIRST (waymark-fp62.6.3.12). A caller may spell
  either the tool (`bench__read`) or a power token that admits
  exactly one tool (`bench.read`): `tool-name-of` answers the tool,
  and the grant, the filter and the why are judged on it. A token
  that admits two tools 404s with both tool names in the detail.

  The refusals come first and the order is the security property: a
  tool no entry names 404s (it does not exist through this door,
  whatever the server offers), an ungranted one 403s naming the ask, a
  call outside the grant's FILTER 403s naming the field and the value
  (waymark-fp62.6.3.5), a missing why 422s, arguments the tool's
  mirrored input schema does not admit 422 naming each field and the
  shape the tool takes, and NONE of them touches a server. A granted call forwards through the row's client — with
  `allow` added when the filter narrowed paths and the call named
  none. It answers the payload VERBATIM, but for one case: a server's
  own `isError` about its arguments gains the tool's input schema as a
  last text part (`teach-shape`).

  THE HOLD IS THE LAST GATE BEFORE THE FORWARD (waymark-fp62.10.2,
  R-14). An entry that says `approval person` does not forward at
  all: the engine mints a `held_call` row carrying the arguments this
  door has just prepared, and answers {held true, held_call <id>} at
  once. It is an ANSWER and never a refusal, so it stands AFTER every
  refusal above and BEFORE the wire. `opts` is what only the caller's
  side knows, {:caller <principal id> :principal <principal> :sitting
  <id>}; a forward carries it to the server as headers. A call
  that names no caller cannot be held, because a row with nobody's
  name on it is a row nobody is barred from allowing."
  ([eng-or-rpc vis tool args] (invoke-for eng-or-rpc vis tool args nil))
  ([eng-or-rpc vis tool args opts]
   (let [eng (engine! eng-or-rpc)
         asked (str tool)
         ;; THE NAME FIRST (waymark-fp62.6.3.12): a one-tool power's
         ;; token is that tool's name here, and everything below judges
         ;; the tool it resolved to
         tname (servers/tool-name-of eng asked)
         {:keys [row entry token why approval] :as hit}
         (servers/resolve-tool eng tname)]
     (when (or (nil? hit) (nil? entry))
       (refuse-unknown eng asked))
     (let [gentry (grants/capability-entry vis token)
           ;; the caller's own arguments against the tool's mirrored
           ;; schema, read before this door adds anything to them
           schema (some #(when (= (str (:bare hit)) (str (:name %)))
                           (:input_schema %))
                        (get-in row [:data :tools]))
           shape (seq (call-shape-errors schema args))
           protected (protected-verdict vis tname gentry args)
           args (bench-protected tname args protected)
           verdict (when gentry (filter-verdict (:filters gentry) args
                                                (servers/glob-fields entry)))
           prepare-block (bench-prepare-block eng vis tname args)
           hold-block (bench-hold-block eng tname args opts)
           ;; the person said yes when this call was scheduled, so its
           ;; run forwards and nobody is asked twice
           approved (and (= :person approval)
                         (approved-run? eng (hash-set asked tname) opts))]
       (cond
         (nil? gentry)
         (refuse-invoke
          (str "Invoking " tname " is the " token
               " capability, and this request wears no live grant that"
               " names it. Present an accepted grant as X-Waymark-Grant,"
               " or file the ask.")
          token)

         (:miss verdict)
         (refuse-filter tname token (:miss verdict))

         (:refuse protected)
         (refuse-bench-protected tname token (:refuse protected))

         prepare-block
         (refuse-bench-prepare tname prepare-block)

         hold-block
         (refuse-bench-hold tname hold-block)

         (and why (not (carries-why? args)))
         (refuse-why tname)

         ;; held or not, a call the tool's schema does not admit stops
         ;; here: no held_call row, no wire
         shape
         (refuse-shape tname schema why shape)

         (and (= :person approval) (not approved)
              (some-> (:caller opts) str not-empty))
         (do
           ;; a ref that names no secret refuses here, before a held_call
           ;; row exists and a person's tap is spent on it
           (servers/check-secret-refs! eng tname args)
           (:answer (held/hold!
                     eng
                     {:server (:id row)
                      :tool tname
                      :entry entry
                      :input (or args {})
                      :forward (forward-args row (with-allow args (:allow verdict)))
                      :why (why-of args)
                      :caller (:caller opts)
                      :sitting (:sitting opts)})))

         (and (= :person approval) (not approved))
         (refuse-anonymous tname)

         :else
         ;; the caller rides as headers the engine writes (`caller-headers`)
         (binding [servers/*caller* (when (or (:principal opts) (:caller opts))
                                      (select-keys opts [:principal :caller :sitting]))]
           (teach-shape
            tname schema why
            (servers/call! eng tname
                           (forward-args row (with-allow args (:allow verdict)))))))))))

;; ── the engine's own hand (the write path) ──────────────────────────

(defn power-of
  "THE CTX `:power` HOOK (waymark-fp62.7.16, R-2; R-7 of
  spec-mcp-servers): the same leash, for a call the ENGINE makes on
  the model's behalf. A function of a prefixed tool name and its
  arguments that answers the server's payload, or nil.

  Built only for a request that wears a visibility admitting at least
  one token any row names. Each call resolves the tool to its row by
  prefix and judges the entry's token against the grant exactly as
  `invoke-for` does, filter and all — the engine's hand is leashed no
  more loosely than the model's. It carries no session, so it stamps
  no seat and no sitting: a handler's own call is the ENGINE acting,
  and an office it made up would be a lie in the rig's record.
  IT DOES NOT THROW: a refusal, a dark row,
  a server that answers an error — each one answers nil, and the
  write it was opened inside of commits without the field it could
  not fill."
  [eng-or-rpc vis]
  (let [eng (servers/engine-of eng-or-rpc)]
    (when (and eng vis (seq (admitted-tokens eng vis)))
      (fn power [tool args]
        (let [tname (str tool)
              {:keys [row entry token]} (servers/resolve-tool eng tname)
              gentry (when token (grants/capability-entry vis token))
              protected (protected-verdict vis tname gentry args)
              args (bench-protected tname args protected)
              verdict (when gentry (filter-verdict (:filters gentry) args
                                                   (servers/glob-fields entry)))
              prepare-block (bench-prepare-block eng vis tname args)]
          (when (and entry gentry (not (:miss verdict)) (not prepare-block)
                     (not (:refuse protected)))
            (try
              (servers/call! eng tname
                             (forward-args row (with-allow args (:allow verdict))))
              (catch Exception e
                (binding [*out* *err*]
                  (println "waymark10 power" tname "failed -"
                           (ex-message e)))
                nil))))))))
