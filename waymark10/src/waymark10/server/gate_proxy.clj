(ns waymark10.server.gate-proxy
  "The Gate hypermedia proxy's stateless core (waymark-q95): the door
  that holds the leash and stores nothing.

  Waymark NAMES ten capabilities that point at Gate (ckopsa/gate — a
  FastAPI + MCP Server(\"gate\") on the private LAN, aggregating rig
  servers and re-exposing their tools as <rig>__<tool>), but until
  this namespace it did not sit on the wire: a grant saying
  \"email.read only\" was not enforced against Gate at all. This is
  the enforcement point — the inversion `feed.preview_as` recorded in
  server/capabilities.clj (\"enforced by this engine's own route\"),
  generalized: Waymark is public and holds the grant; Gate stays
  simple, private, and does NOT authenticate its caller.

  THE CONSTRAINT THAT SHAPED IT: no mirrored resources, no stored
  state. Capabilities exist precisely so external data (email bodies,
  texts, budget rows) is NEVER copied into Waymark. So this is NOT a
  defresource kind and there are no rows here — Waymark holds the
  RULE (`tool-capability`, the one static artifact) and forwards the
  payload through untouched. Every request recomputes from Gate live.

  Two functions, one per direction:

  • `affordances-for` — Gate's LIVE `tools/list` ∩ the caller's
    grant, rendered as a hypermedia document: Gate's `allow` tools
    (its policies.yaml's reads — recognizable on the wire because
    Gate does not require `__why` of them) as links, its
    `require_approval` tools (mutations) as action forms whose input
    schema is Gate's own inputSchema. A grant admitting no token
    skips Gate entirely and reads an empty document.

  • `invoke-for` — the tool's capability token looked up in the map,
    grant-checked in-process (the same `capability-entry` read the
    feed door and `/api/-/grant-check` make), refused 403/404 before
    any wire is touched, else forwarded and answered VERBATIM.

  GATE'S `__why` CONVENTION, surfaced as `why`: Gate augments every
  require_approval tool's schema with a `__why` argument — the
  one-sentence rationale its human approver reads — and requires it.
  This door speaks `why` on its own surface (the affordance's input
  schema and its `:why` field both say so) and translates it back to
  `__why` on the forward, passing a caller's literal `__why` through
  untouched.

  THE FILTER IS REFUSED, not interpreted: a capability entry's
  :filter is a constraint the enforcement point interprets, and this
  door interprets none yet — an email.read grant filtered to
  {folder: X} would be honoured by forwarding everything, which is
  honouring nothing. The feed door made the same call about an
  unfiltered preview grant, for the same reason, in the other
  direction: refuse what you cannot honestly enforce.

  THE WIRE to Gate is MCP's streamable HTTP transport, the simple
  half — POST one JSON-RPC message, read one response (JSON or a
  single-response SSE body), exactly as Gate itself connects to its
  rig backends. One session per engine, reused across requests
  (`rpc-of` is called once at route build), re-initialized once on a
  session-expiry answer. Nothing else is kept."
  (:require [clojure.string :as str]
            [waymark10.server.grants :as grants]
            [waymark10.server.problems :as p]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

(set! *warn-on-reflection* true)

;; ── configuration ───────────────────────────────────────────────────

(def default-url
  "Where Gate answers on the household LAN — the deployment value,
  overridable per engine as (:gate eng) {:url …}, the same
  engine-opt spelling :events-poll-ms and the feed recipe use. The
  network backstop (firewalling :8100 so only this host may reach
  it) is home-infrastructure's, recorded in the bead, not code here."
  "http://192.168.1.40:8100/mcp/")

;; ── the one static artifact: tool → capability ──────────────────────

(def tool-capability
  "THE SECURITY POLICY — the only thing Waymark knows about Gate
  beyond the live tool list. Small config data, not a resource: a
  Gate tool absent from this map does not exist through this door,
  whatever Gate serves. These are the bead's draft rows, whole —
  every rig Gate aggregates, each tool bound to the dotted token the
  capability registry already names; a tool named here that a given
  Gate does not serve live simply never survives the ∩, so the map
  may bind more than today's Gate lists and a rig's later tool
  arrives leashed or not at all. gsd__* (todos + calendar) is
  deliberately absent — DECIDED, not open: waymark already owns
  tasks and calendar natively (workqueue10/calendar10), and a gsd
  capability would leash an agent around the queue's own law."
  {;; emila — email
   "emila__inbox"               "email.read"
   "emila__list_messages"       "email.read"
   "emila__search"              "email.read"
   "emila__read"                "email.read"
   "emila__read_batch"          "email.read"
   "emila__download_attachment" "email.read"
   "emila__summary"             "email.read"
   "emila__folders"             "email.read"
   "emila__move"                "email.move"
   "emila__move_from_sender"    "email.move"
   "emila__send"                "email.send"
   ;; tgram — telegram
   "tgram__get_messages"        "telegram.read"
   "tgram__list_chats"          "telegram.read"
   "tgram__search_messages"     "telegram.read"
   "tgram__search_all_chats"    "telegram.read"
   "tgram__send_message"        "telegram.send"
   ;; messa — the phone's texts
   "messa__threads"             "messages.read"
   "messa__read_messages"       "messages.read"
   "messa__reset"               "messages.read"
   ;; keep — the household's notes (read-only rig; no mutating tools exist)
   "keep__list_notes"           "notes.read"
   "keep__search"               "notes.read"
   "keep__read"                 "notes.read"
   ;; ynab — the budget
   "ynab__accounts"             "ynab.read"
   "ynab__transactions"         "ynab.read"
   "ynab__budget_month"         "ynab.read"
   "ynab__categories"           "ynab.read"
   "ynab__update_transaction"   "ynab.write"
   "ynab__split_transaction"    "ynab.write"
   "ynab__bulk_approve"         "ynab.write"
   "ynab__create_transaction"   "ynab.write"
   ;; amzn — amazon
   "amzn__orders"               "amazon.read"
   "amzn__search"               "amazon.read"
   "amzn__product_details"      "amazon.read"
   "amzn__view_cart"            "amazon.read"
   "amzn__reset"                "amazon.read"
   "amzn__add_to_cart"          "amazon.cart"
   ;; gsd__* — deliberately no rows: waymark owns tasks/calendar
   ;; natively (workqueue10/calendar10), per the bead's decision.
   })

(def capability-tokens
  "Every token the map names — what `affordances-for` intersects the
  grant against before it touches any wire."
  (into #{} (vals tool-capability)))

;; ── the MCP client (streamable HTTP, the simple half) ───────────────

(def ^:private protocol-version "2025-06-18")

(defn- gate-unreachable [detail]
  (p/problem :gate-unreachable 502 "Gate unreachable"
             {:detail (str "Gate did not answer this engine: " detail
                           " Nothing was read and nothing was done; the"
                           " grant leash was judged here either way.")}))

(defn- sse-answer
  "A streamable-HTTP response body that arrived as text/event-stream:
  the JSON-RPC answer is the last data: event carrying a result or an
  error (the transport allows a server to stream related messages
  first; this client asked for the simple exchange and takes the
  reply)."
  [body]
  (->> (str/split-lines (str body))
       (map str/trim)
       (filter #(str/starts-with? % "data:"))
       (keep #(try (wire/read-json (str/trim (subs % 5)))
                   (catch Exception _ nil)))
       (filter #(or (contains? % :result) (contains? % :error)))
       last))

(defn- post-message!
  [^HttpClient http ^String url session-id msg]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "application/json, text/event-stream"))
        builder (if session-id
                  (.header builder "mcp-session-id" (str session-id))
                  builder)
        req (.build (.POST builder (HttpRequest$BodyPublishers/ofString
                                    (wire/write-json msg))))
        resp (.send http req (HttpResponse$BodyHandlers/ofString))
        headers (.headers resp)
        ctype (.orElse (.firstValue headers "content-type") "")]
    {:status (.statusCode resp)
     :session-id (.orElse (.firstValue headers "mcp-session-id") nil)
     :answer (let [^String body (.body resp)]
               (cond
                 (str/blank? (str body)) nil
                 (str/includes? ctype "text/event-stream") (sse-answer body)
                 :else (try (wire/read-json body)
                            (catch Exception _ nil))))}))

(defn http-rpc
  "A JSON-RPC caller for one Gate: (fn [method params]) → the
  :result. Holds exactly one piece of state — the transport session —
  opened lazily on first use, reused across requests, re-initialized
  once when Gate answers that it expired (a 404 on an established
  session, per the transport spec). A JSON-RPC error or a transport
  failure surfaces as a 502 problem; the leash was judged before any
  of this ran, so a dark Gate never changes what was allowed."
  [url]
  (let [http (-> (HttpClient/newBuilder)
                 (.connectTimeout (Duration/ofSeconds 5))
                 (.build))
        state (atom {:session nil :id 0})
        next-id! #(:id (swap! state update :id inc))
        raw! (fn [msg]
               (try (post-message! http url (:session @state) msg)
                    (catch Exception e
                      (throw (gate-unreachable (ex-message e))))))
        handshake! (fn []
                     (let [{:keys [status session-id answer]}
                           (raw! {:jsonrpc "2.0" :id (next-id!)
                                  :method "initialize"
                                  :params {:protocolVersion protocol-version
                                           :capabilities {}
                                           :clientInfo {:name "waymark10"
                                                        :version "10"}}})]
                       (when (or (:error answer)
                                 (not (<= 200 (long status) 299)))
                         (throw (gate-unreachable
                                 (str "initialize answered " status " "
                                      (some-> (:error answer) :message)))))
                       (swap! state assoc :session session-id)
                       ;; the transport's second half of the handshake;
                       ;; a notification, so no answer is owed
                       (raw! {:jsonrpc "2.0"
                              :method "notifications/initialized"})))
        request! (fn [method params]
                   (raw! {:jsonrpc "2.0" :id (next-id!)
                          :method method :params params}))]
    (fn rpc [method params]
      (when (nil? (:session @state)) (handshake!))
      (let [{:keys [status answer]} (request! method params)
            ;; an expired session is the transport's 404 — open one
            ;; new session and retry once, never a loop
            {:keys [status answer]} (if (= 404 (long status))
                                      (do (swap! state assoc :session nil)
                                          (handshake!)
                                          (request! method params))
                                      {:status status :answer answer})]
        (cond
          (:error answer)
          (throw (gate-unreachable (str method " answered JSON-RPC error "
                                        (get-in answer [:error :code]) ": "
                                        (get-in answer [:error :message]))))

          (not (<= 200 (long status) 299))
          (throw (gate-unreachable (str method " answered HTTP " status ".")))

          :else (:result answer))))))

(defn rpc-of
  "The engine's Gate caller, built ONCE per engine at route build (so
  the session is the connection reuse the bead asks for): (:gate eng)
  may carry :rpc — a caller handed in whole, the tests' seam and any
  future transport's — or :url; absent both, the deployment default."
  [eng]
  (or (get-in eng [:gate :rpc])
      (http-rpc (get-in eng [:gate :url] default-url))))

;; ── the grant's read of the map ─────────────────────────────────────

(defn- admitted?
  "Does the presented visibility admit this capability token, as this
  door enforces it? The entry must EXIST (visibility already judged
  audience, acceptance, expiry and revocation — every one of those is
  nil here) and must carry NO filter, because this door interprets no
  constraint yet and forwarding under one it had not understood would
  be honouring nothing."
  [vis token]
  (let [entry (grants/capability-entry vis token)]
    (and (some? entry) (nil? (:filters entry)))))

(defn admitted-tokens [vis]
  (into #{} (filter #(admitted? vis %)) capability-tokens))

;; ── affordances ─────────────────────────────────────────────────────

(defn- why-required?
  "Gate's own read/act split, read off the wire: Gate augments every
  require_approval (mutation) tool's schema with a REQUIRED `__why`,
  and leaves it optional on allow (read) tools — the same partition
  its policies.yaml draws, arriving live instead of copied here."
  [tool]
  (boolean (some #{"__why"} (get-in tool [:inputSchema :required]))))

(defn- present-schema
  "Gate's own inputSchema, with its `__why` convention surfaced as
  `why` — the spelling this door's surface speaks; `gate-args`
  translates it back on the forward."
  [schema]
  (let [schema (or schema {:type "object" :properties {}})]
    (if-some [why (get-in schema [:properties :__why])]
      (-> schema
          (update :properties #(-> % (dissoc :__why) (assoc :why why)))
          (cond-> (:required schema)
            (update :required (partial mapv #(if (= "__why" %) "why" %)))))
      schema)))

(defn- affordance [tool]
  (let [tname (str (:name tool))
        mutation? (why-required? tool)]
    (cond-> {:href (str "/api/-/gate/" tname)
             :method "POST"
             :capability (get tool-capability tname)
             :description (str (:description tool))
             :input (present-schema (:inputSchema tool))}
      mutation?
      (assoc :why {:required true
                   :note (str "One sentence of rationale; Gate shows it to "
                              "the human who approves this action.")}
             :safety {:confirm false
                      :consequence
                      (str "Gate holds its own approval loop: this action "
                           "pauses for a human's yes through Gate's "
                           "notifier, and your `why` is the sentence that "
                           "human reads.")}))))

(defn- survivors
  "THE one computation both surfaces project: Gate's LIVE tools ∩
  the caller's grant, recomputed per call, nothing cached. A grant
  admitting no token skips Gate entirely — what a caller may not see
  costs no wire at all."
  [rpc vis]
  (let [tokens (admitted-tokens vis)]
    (if (empty? tokens)
      []
      (into []
            (filter #(contains? tokens
                                (get tool-capability (str (:name %)))))
            (:tools (rpc "tools/list" {}))))))

(defn affordances-for
  "GET /api/-/gate's document: Gate's LIVE tools ∩ the caller's
  grant, recomputed per request, nothing cached and nothing stored.
  Reads (Gate's allow policy) under :links, mutations (its
  require_approval policy) under :actions as forms; each survivor's
  input schema is Gate's own. A grant admitting no token reads an
  empty document — and Gate is never contacted for it, so what a
  caller may not see costs no wire at all."
  [rpc vis]
  (let [{reads false mutations true} (group-by why-required?
                                               (survivors rpc vis))
        entry #(vector (str (:name %)) (affordance %))]
    {:waymark "10"
     :self "/api/-/gate"
     :note (str "External powers reached THROUGH this engine: Gate's live "
                "tools intersected with your grant, recomputed on every "
                "read. Nothing behind these affordances is stored here — "
                "invoke one and the payload passes through untouched. "
                "Mutations carry a `why`: one sentence a human approver "
                "reads before Gate acts.")
     :links (into {} (map entry) reads)
     :actions (into {} (map entry) mutations)
     :ask {:href "/api/approval_requests"
           :method "POST"
           :note (str "More sight is asked for, never assumed: scope entries "
                      "naming a dotted capability token (email.read, "
                      "email.send, email.move — GET /api/capabilities for "
                      "the words) mint the grant this door reads.")}}))

;; ── the MCP projection (the second surface) ─────────────────────────

(defn tool-listing-for
  "The MCP surface's half of the bead's TWO THIN SURFACES, ONE CORE:
  the same survivors `affordances-for` keeps, rendered as MCP tool
  entries for server/mcp.clj's `tools/list` to APPEND after the six
  fixed waymark_* tools. Each entry is Gate's own inputSchema with
  the `__why` convention surfaced as `why` (`present-schema`), so
  the two surfaces advertise one vocabulary and `invoke-for`
  translates it back on the forward. A grant admitting no token
  appends nothing — and Gate is never contacted for it, so an
  agent's ordinary tools/list costs no wire."
  [rpc vis]
  (mapv (fn [tool]
          {:name (str (:name tool))
           :description (str (:description tool))
           :inputSchema (present-schema (:inputSchema tool))})
        (survivors rpc vis)))

;; ── invoke ──────────────────────────────────────────────────────────

(defn- refuse-invoke
  "One 403, spelled so the next move is obvious — the feed door's own
  posture: capabilities are WORDS, readable by every named principal
  without a grant, so naming the token discloses nothing a GET
  /api/capabilities would not, and what it buys is that an agent
  reading this sentence knows how to ASK."
  [detail token]
  (throw (p/problem :gate-not-granted 403 "Not granted"
                    {:detail detail
                     :remedies
                     [(str "POST /api/approval_requests with scope "
                           "[{\"kind\": \"" token "\", \"actions\": []}]"
                           " — a human in the house approves it, and the"
                           " grant it mints is what this door reads.")]})))

(defn- gate-args
  "The caller's arguments as Gate expects them: `why` translated back
  to Gate's `__why`; a caller who already speaks Gate's spelling
  passes through untouched (and an explicit `__why` wins, so the
  translation never overwrites a deliberate one)."
  [args]
  (let [args (or args {})]
    (if (and (contains? args :why) (not (contains? args :__why)))
      (-> args (dissoc :why) (assoc :__why (:why args)))
      (dissoc args :why))))

(defn invoke-for
  "POST /api/-/gate/{tool}: the capability token looked up in the
  map, the grant judged IN-PROCESS — the same `capability-entry` read
  the feed door makes and the same law `/api/-/grant-check` answers —
  and only then the forward. The refusals come first and the order is
  the security property: an unknown tool 404s (a tool outside the map
  does not exist through this door, whatever Gate serves), an
  ungranted one 403s naming the ask, and NEITHER touches Gate. A
  granted call forwards over the LAN and answers Gate's payload
  VERBATIM — its content, its isError, its `__why`-approval refusals
  — because the payload is exactly what this engine must never hold
  or rewrite."
  [rpc vis tool args]
  (let [tname (str tool)
        token (get tool-capability tname)]
    (when (nil? token)
      (throw (p/not-found "gate tool" tname)))
    (let [entry (grants/capability-entry vis token)]
      (cond
        (nil? entry)
        (refuse-invoke
         (str "Invoking " tname " through Gate is the " token
              " capability, and this request wears no live grant that"
              " names it. Present an accepted grant as X-Waymark-Grant,"
              " or file the ask.")
         token)

        (some? (:filters entry))
        (refuse-invoke
         (str "This grant names " token " with a filter, and this door"
              " interprets no constraint yet — forwarding under a"
              " constraint it had not understood would be honouring"
              " nothing. Ask again without a filter.")
         token)

        :else
        (rpc "tools/call" {:name tname :arguments (gate-args args)})))))
