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

  • `invoke-for` — the tool resolved to its row by prefix, the power
    token judged in-process against the grant, a required `why`
    demanded, and only then the forward through the row's client. The
    payload is answered VERBATIM.

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
            [waymark10.server.mcp-client :as client]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.problems :as p]))

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
  [fname want got]
  (if (= path-filter-field fname)
    (boolean (some #(path-glob-matches? % got) (comma-values want)))
    (boolean (some #(= (str got) %) (comma-values want)))))

(defn- entry-verdict
  "One filter map against one call's arguments → {:allow globs|nil}
  when it admits the call, {:miss {…}} when it does not. `:allow` nil
  means this map narrows no path, and openness absorbs below."
  [fm args]
  (reduce
   (fn [acc [f want]]
     (let [fname (name f)
           got (get args (keyword fname))]
       (cond
         (and (= path-filter-field fname) (whole-tree? args))
         (update acc :allow (fnil into []) (comma-values want))

         (nil? got)
         (reduced {:miss {:field fname :got nil :want (str want)}})

         (field-admits? fname want got) acc

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
  [filters args]
  (if (empty? filters)
    {:allow nil}
    (let [verdicts (mapv #(entry-verdict % args) filters)
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

;; ── affordances ─────────────────────────────────────────────────────

(defn- present-schema
  "The server's own inputSchema, with Gate's `__why` surfaced as
  `why`, and a `why` added when the entry demands one the schema does
  not already name."
  [schema why?]
  (let [schema (or schema {:type "object" :properties {}})
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

(defn- affordance [{:keys [name token description input-schema why entry]}]
  (cond-> {:href (str "/api/-/gate/" name)
           :method "POST"
           :capability token
           :description (str description)
           :input (present-schema input-schema why)}
    ;; the fields a grant may narrow this power by (waymark-fp62.6.3.5)
    ;; — an agent reads them here and asks for the narrow grant itself,
    ;; rather than asking for the whole rig and being told no
    (seq (:constraints entry))
    (assoc :constraints (vec (:constraints entry)))

    why
    (assoc :why {:required true
                 :note (str "One sentence of rationale; the person who "
                            "approves this action reads it.")}
           :safety {:confirm false
                    :consequence
                    (str "This action acts on the outside: your `why` is "
                         "the sentence a person reads before or after "
                         "it lands.")})))

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

(defn- refuse-why
  "The 422 for a why-required tool called with no why."
  [tname]
  (throw (p/problem :why-required 422 "Why is required"
                    {:detail (str tname " requires a why: one sentence that"
                                  " says why this call is made. This engine"
                                  " demands it and a person reads it.")
                     :remedies ["Call again with arguments.why set to one sentence."]})))

(defn- carries-why? [args]
  (or (not (str/blank? (str (:why args))))
      (not (str/blank? (str (:__why args))))))

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

(defn invoke-for
  "POST /api/-/gate/{tool} and waymark_power: the tool resolved to its
  row by prefix, the entry's power token judged IN-PROCESS against the
  grant, a required why demanded, and only then the forward. The
  refusals come first and the order is the security property: a tool
  no entry names 404s (it does not exist through this door, whatever
  the server offers), an ungranted one 403s naming the ask, a call
  outside the grant's FILTER 403s naming the field and the value
  (waymark-fp62.6.3.5), a missing why 422s, and NONE of them touches a
  server. A granted call forwards through the row's client — with
  `allow` added when the filter narrowed paths and the call named none
  — and answers the payload VERBATIM."
  [eng-or-rpc vis tool args]
  (let [eng (engine! eng-or-rpc)
        tname (str tool)
        {:keys [row entry token why] :as hit} (servers/resolve-tool eng tname)]
    (when (or (nil? hit) (nil? entry))
      (throw (p/not-found "power" tname)))
    (let [gentry (grants/capability-entry vis token)
          verdict (when gentry (filter-verdict (:filters gentry) args))]
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

        (and why (not (carries-why? args)))
        (refuse-why tname)

        :else
        (servers/call! eng tname
                       (forward-args row (with-allow args (:allow verdict))))))))

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
              verdict (when gentry (filter-verdict (:filters gentry) args))]
          (when (and entry gentry (not (:miss verdict)))
            (try
              (servers/call! eng tname
                             (forward-args row (with-allow args (:allow verdict))))
              (catch Exception e
                (binding [*out* *err*]
                  (println "waymark10 power" tname "failed -"
                           (ex-message e)))
                nil))))))))
