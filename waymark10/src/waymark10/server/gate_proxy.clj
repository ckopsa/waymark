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

  THE FILTER IS REFUSED, not interpreted, as before: a grant entry
  carrying a filter admits nothing here, because forwarding under a
  constraint this door had not understood would honour nothing.

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
  "Every power token the rows' powers name, sorted — what discover's
  doors.ask.powers lists."
  servers/power-tokens)

(defn- engine!
  "The engine behind what a caller handed in, or the 502 that says
  there is none yet."
  [x]
  (or (servers/engine-of x)
      (throw (client/unreachable "the engine is not started yet."))))

;; ── the grant's read of the policy ──────────────────────────────────

(defn- admitted?
  "Does the presented visibility admit this power token, as this door
  enforces it? The entry must EXIST (visibility already judged
  audience, acceptance, expiry and revocation) and must carry NO
  filter, because this door interprets no constraint."
  [vis token]
  (let [entry (grants/capability-entry vis token)]
    (and (some? entry) (nil? (:filters entry)))))

(defn admitted-tokens
  "The power tokens any row names that this visibility admits."
  [eng-or-rpc vis]
  (let [eng (engine! eng-or-rpc)]
    (into #{} (filter #(admitted? vis %)) (servers/power-tokens eng))))

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

(defn- affordance [{:keys [name token description input-schema why]}]
  (cond-> {:href (str "/api/-/gate/" name)
           :method "POST"
           :capability token
           :description (str description)
           :input (present-schema input-schema why)}
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
  the server offers), an ungranted one 403s naming the ask, a missing
  why 422s, and NONE of them touches a server. A granted call forwards
  through the row's client and answers the payload VERBATIM."
  [eng-or-rpc vis tool args]
  (let [eng (engine! eng-or-rpc)
        tname (str tool)
        {:keys [row entry token why] :as hit} (servers/resolve-tool eng tname)]
    (when (or (nil? hit) (nil? entry))
      (throw (p/not-found "power" tname)))
    (let [gentry (grants/capability-entry vis token)]
      (cond
        (nil? gentry)
        (refuse-invoke
         (str "Invoking " tname " is the " token
              " capability, and this request wears no live grant that"
              " names it. Present an accepted grant as X-Waymark-Grant,"
              " or file the ask.")
         token)

        (some? (:filters gentry))
        (refuse-invoke
         (str "This grant names " token " with a filter, and this door"
              " interprets no constraint yet — forwarding under a"
              " constraint it had not understood would be honouring"
              " nothing. Ask again without a filter.")
         token)

        (and why (not (carries-why? args)))
        (refuse-why tname)

        :else
        (servers/call! eng tname (forward-args row args))))))

;; ── the engine's own hand (the write path) ──────────────────────────

(defn power-of
  "THE CTX `:power` HOOK (waymark-fp62.7.16, R-2; R-7 of
  spec-mcp-servers): the same leash, for a call the ENGINE makes on
  the model's behalf. A function of a prefixed tool name and its
  arguments that answers the server's payload, or nil.

  Built only for a request that wears a visibility admitting at least
  one token any row names. Each call resolves the tool to its row by
  prefix and asks `admitted?` about the entry's token, which is
  `invoke-for`'s own read. IT DOES NOT THROW: a refusal, a dark row,
  a server that answers an error — each one answers nil, and the
  write it was opened inside of commits without the field it could
  not fill."
  [eng-or-rpc vis]
  (let [eng (servers/engine-of eng-or-rpc)]
    (when (and eng vis (seq (admitted-tokens eng vis)))
      (fn power [tool args]
        (let [tname (str tool)
              {:keys [row entry token]} (servers/resolve-tool eng tname)]
          (when (and entry token (admitted? vis token))
            (try
              (servers/call! eng tname (forward-args row args))
              (catch Exception e
                (binding [*out* *err*]
                  (println "waymark10 power" tname "failed -"
                           (ex-message e)))
                nil))))))))
