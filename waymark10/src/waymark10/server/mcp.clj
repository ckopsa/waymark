(ns waymark10.server.mcp
  "The MCP surface: eight fixed tools over the grant's projection of the
  declaration (docs/spec-mcp-surface.md), plus the two power tools.

  Every fact an agent needs to drive a waymark engine is already on
  the wire — kinds and their doors at `.well-known`, the projected
  JSON Schema at `/api/schemas/{kind}`, and on every envelope the
  available actions WITH their input schemas, their prose, their
  safety and their refusal reasons. What was missing was a protocol
  an off-the-shelf model client already speaks. This namespace is
  that, and nothing more: it invents no surface, and each tool is a
  thin call onto a route that already exists.

  ── the two decisions ──

  1. NOT ONE TOOL PER ACTION. Fifteen kinds × six actions is ninety
     tools before the household adds anything, re-generated on every
     law change, and a model's tool list is a scarce thing. Six tools,
     stable across every waymark engine forever; the INTERESTING
     surface — which kinds, which actions, which fields — arrives as
     DATA through discover/schema/get, which is exactly how the
     generic UI already works. An agent that can read a schema needs
     no bespoke tool.

  2. THE SURFACE IS THE GRANT'S PROJECTION. Nothing here filters
     anything: the tools go through the real routes, wearing the
     identity the router already resolved, so an ungranted kind is
     ABSENT from discover (concealment, `router.clj`'s standing
     posture), a schema arrives already projected by
     `grants/project-json-schema`, and an ungranted action is a door
     that does not exist. Least privilege and tool-surface generation
     are the same operation, and that is the product thesis rather
     than an implementation note.

  ── the door ──

  `door` builds core's own routes into an in-process ring handler and
  hands back a function every tool calls. It is the honest seam: the
  alternative — each tool reaching for `collections/envelope` and
  `inv/invoke!` itself — is how a second, quietly divergent copy of
  the router's concealment checks gets written. The one thing this
  door does NOT wear is `wrap-identity`: identity resolves ONCE, at
  the outer HTTP boundary, and rides in on the session, so no tool
  can re-authenticate itself into someone else.

  ── the gate projection (waymark-q95, the second surface) ──

  Two FIXED tools carry the external powers (waymark-912p):
  `waymark_powers` answers gate-proxy's affordance document — Gate's
  live tools ∩ the caller's grant, recomputed per call, each wearing
  Gate's own inputSchema with `__why` surfaced as `why` — and
  `waymark_power` takes a tool name and its arguments and dispatches
  to `invoke-for`, which judges the grant IN-PROCESS and answers
  Gate's CallToolResult verbatim. Same stateless core and same leash
  as the hypermedia door at /api/-/gate; a caller wearing no gate
  grant reads an empty powers document, and Gate is never contacted
  on its behalf.

  The list used to APPEND the admitted Gate tools after the fixed
  ones, which made the tool list a function of the grant — and a
  grant approved mid-conversation then needed the client to honour
  tools/list_changed before the agent could see what it had just
  been given (claude.ai did not, reliably). Now the list is the same
  for every caller from the first connect and an approval takes
  effect on the very next waymark_powers call: the surface is DATA
  the agent reads, not a tool list the client caches. The power
  dispatcher (`gate-rpc`) rides in from the transport, which builds it
  once per engine via gate-proxy/rpc-of — since waymark-fp62.10 a
  dispatcher over the `mcp_server` rows, each holding its own client
  — never re-shaken per message.

  Core's routes and no module's, deliberately: the six tools address
  the well-known document, the schemas, the plural grammar and the
  invoke door, every one of them core's. A module's route is reachable
  over HTTP like any other; it is not a tool.

  ── the confirm gate ──

  `waymark_invoke` refuses a `safety.confirm` action unless the call
  echoes the consequence sentence back as `acknowledge`. The engine
  already computes that sentence (a per-origin `:consequence` map
  resolves against the row's CURRENT state at render), so the gate
  reads it off the row's own envelope and compares exactly. This is
  the one refusal MCP issues in its own voice; every other refusal in
  this namespace is the engine's, verbatim.

  The gate is PER ITEM at the bulk door (waymark-pywy.4). `waymark_invoke`
  reaches the collection's bulk door with `ids` (one shared input, the
  phase-7 shape) or `items` (each row its own input) rather than
  through a seventh tool; a confirm action refuses `ids` outright —
  one acknowledgement over many rows is a guard-override device — and
  over `items` every item must carry its own sentence, or the call is
  refused before anything runs, naming each item that owes one.

  ── refusals ──

  A refusal is TOOL OUTPUT — the RFC 9457 body, reasons and remedies
  and becomes_available intact, carried as text with `isError` set —
  never a JSON-RPC error. An agent learns from an honest refusal and
  learns nothing from a transport fault, and the whole point of this
  engine's refusal vocabulary is that it says what a competent person
  would do next.

  ── return: summary (waymark-pywy.1) ──

  `waymark_query`, `waymark_get` and `waymark_invoke` take `return`,
  \"envelope\" (the default: the route's own bytes, unchanged) or
  \"summary\". The connector's first real session received the
  identical action-schema block — every action's input schema, prose
  and safety — fourteen times in a row, once per invoke, from a
  caller that had already read waymark_schema. A summary is a
  PROJECTION of the envelope the route already answered: the row's
  id, kind, state, summary line and data (a collection item's
  `fields`), for an invoke the transition and the fields that
  changed, for a collection its paging and facets — and nothing the
  envelope did not carry. It is computed AFTER the route answered,
  over the route's own document, so concealment is inherited exactly
  as it is for the envelope: a field the grant redacts is not in the
  envelope, so it cannot be in the summary. No route changes, and
  nothing here reads wider than the envelope did.

  ── the message layer ──

  The bottom section is MCP's JSON-RPC exchange as a function of one
  parsed message. It knows nothing about HTTP: the Streamable HTTP
  transport lives in waymark10.server.routes.mcp, and a stdio
  transport is a read-line/write-line loop around this same fn.

  Recorded punts, all of them the spec's: no streaming (SSE frames
  map to MCP notifications, and that wants addressed notice first),
  no worksheet upload (binary through MCP is possible and unpleasant
  — the HTTP door is right there), and tool descriptions generated
  from prose written for humans, which will read badly for some kinds
  and is a useful forcing function on those declarations."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as j]
            [waymark10.client :as client]
            [waymark10.confirm :as confirm]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as coll]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.in-flight :as in-flight]
            [waymark10.server.live-cracks :as live-cracks]
            [waymark10.server.invoke :as inv]
            [waymark10.server.judgments :as judgments]
            [waymark10.server.leases :as leases]
            [waymark10.server.keys :as keys]
            [waymark10.server.mcp-client :as mcp-client]
            [waymark10.server.mcp.act :as mcp-act]
            [waymark10.server.mcp.bench :as mcp-bench]
            [waymark10.server.mcp.gate :as mcp-gate]
            [waymark10.server.mcp.notes :as mcp-notes]
            [waymark10.server.mcp.read :as mcp-read]
            [waymark10.server.mcp.served :as mcp-served]
            [waymark10.server.mcp.surface :as mcp-surface]
            [waymark10.server.mcp.wire :as mcp-wire]
            [waymark10.server.mcp-sessions :as sessions]
            [waymark10.server.members :as members]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.presence :as presence]
            [waymark10.server.problems :as p]
            [waymark10.server.quiet :as quiet]
            [waymark10.server.kind-doc :as kind-doc]
            [waymark10.server.render :as render]
            [waymark10.server.router :as router]
            [waymark10.server.seats :as seats]
            [waymark10.server.seats.unsat :as unsat]
            [waymark10.server.store :as store]
            [waymark10.server.transcripts :as transcripts]
            [waymark10.server.walks :as walks]
            [waymark10.types :as t])
  (:import (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(set! *warn-on-reflection* true)

;; ── the handshake, the fixed tools and MCP Apps ──────────────────────
;;
;; The protocol version and the instructions, the fixed tools'
;; definitions and `listing`, the MCP Apps page, resource and tools with
;; the app tools' bodies, and the bodies of waymark_discover and
;; waymark_schema live in mcp.surface (ticket 70629989). These are its
;; stubs, one for each var this namespace still calls; the public ones
;; keep `mcp/protocol-version`, `mcp/instructions`, `mcp/tools`,
;; `mcp/listing` and the app vars whole for their callers.

(def protocol-version mcp-surface/protocol-version)
(def supported-versions mcp-surface/supported-versions)
(def server-info mcp-surface/server-info)
(def instructions mcp-surface/instructions)


;; ── the transport's sessions, the door and tool results ─────────────
;;
;; The session table (spec-seat.md R-12.14), the in-process door, the
;; tool-result shapes, the kind and action lookup and the summary
;; projection live in mcp.wire (ticket 3a44c697), so a tool family
;; carved out of this namespace can require them without requiring it
;; back. These are its stubs, one for each var this namespace still
;; calls; the public ones keep `mcp/door` and the session doors whole
;; for their callers.

(def session-ttl-seconds mcp-wire/session-ttl-seconds)
(def new-session-id mcp-wire/new-session-id)
(def open-session! mcp-wire/open-session!)
(def touch-session! mcp-wire/touch-session!)
(def bind-session! mcp-wire/bind-session!)
(def ^:private bound-sitting mcp-wire/bound-sitting)
(def ^:private calling-sitting mcp-wire/calling-sitting)
(def ^:private bound-seat mcp-wire/bound-seat)
(def ^:private bench-bound! mcp-wire/bench-bound!)
(def ^:private bound-bench mcp-wire/bound-bench)

(def door mcp-wire/door)
(def ^:private request mcp-wire/request)
(def ^:private body-text mcp-wire/body-text)
(def ^:private body-json mcp-wire/body-json)
(def ^:private query-string mcp-wire/query-string)

(def ^:private result mcp-wire/result)
(def ^:private value-result mcp-wire/value-result)
(def ^:private pass-through mcp-wire/pass-through)
(def ^:private refusal mcp-wire/refusal)

(def ^:private rdef-of mcp-wire/rdef-of)
(def ^:private declared-action mcp-wire/declared-action)
(def ^:private collection-door mcp-wire/collection-door)
(def ^:private wire-action mcp-wire/wire-action)

(def ^:private verbatim-mapper mcp-wire/verbatim-mapper)
(def ^:private verbatim-json mcp-wire/verbatim-json)
(def ^:private return-of mcp-wire/return-of)
(def ^:private id-of-self mcp-wire/id-of-self)
(def ^:private collection-doc? mcp-wire/collection-doc?)
(def ^:private row-doc? mcp-wire/row-doc?)
(def ^:private row-summary mcp-wire/row-summary)
(def ^:private collection-summary mcp-wire/collection-summary)
(def ^:private invoke-summary mcp-wire/invoke-summary)
(def ^:private answer mcp-wire/answer)

;; ── the fixed tools ─────────────────────────────────────────────────
;;
;; The fixed tools' definitions, `tools` and `listing` live in
;; mcp.surface (ticket 70629989), with the MCP Apps definitions. These
;; are its stubs, one for each var this namespace or its callers read.

(def sit-description mcp-surface/sit-description)

(def app-mime mcp-surface/app-mime)
(def app-resource-uri mcp-surface/app-resource-uri)
(def app-declaration mcp-surface/app-declaration)
(def ^:private app-session? mcp-surface/app-session?)
(def app-page mcp-surface/app-page)
(def ^:private app-resource mcp-surface/app-resource)

(def tools mcp-surface/tools)
(def listing mcp-surface/listing)

;; ── tool bodies ─────────────────────────────────────────────────────

;; waymark_discover's and waymark_schema's bodies live in mcp.surface
;; (ticket 70629989)
(def ^:private discover mcp-surface/discover)

(def ^:private kind-schema mcp-surface/kind-schema)

;; ── staging, invoke, pursue, history, in-flight and resolve ─────────
;;
;; The staging (docs/spec-agent-demo-walks.md § 2), the confirm gate,
;; the MCP origin key and its counts, and the bodies of waymark_invoke,
;; waymark_pursue, waymark_history, waymark_in_flight and
;; waymark_resolve live in mcp.act (ticket 066b9c29). These are its
;; stubs, one for each var this namespace still calls; the public ones
;; keep `mcp/origin-key`, `mcp/rehearse` and the counts whole for their
;; callers.

(def ^:private beat! mcp-act/beat!)
(def ^:private gaze-before mcp-act/gaze-before)
(def ^:private stage mcp-act/stage)
(def ^:private stage-caption! mcp-act/stage-caption!)

(defn- query [eng call session args]
  (let [{:keys [kind page_size page_number rows]} args
        return (return-of args)
        rdef (rdef-of eng kind)
        params (cond-> (into {} (map (fn [[k v]] [(name k) (str v)])) (:filter args))
                 (:sort args) (assoc "sort" (str (:sort args)))
                 page_size (assoc "page[size]" (str page_size))
                 page_number (assoc "page[number]" (str page_number))
                 rows (assoc "rows" (str rows))
                 ;; the caller's projection (waymark-pywy.2), passed
                 ;; through as the route's own comma list — the route
                 ;; judges it, and the subset-of-grant law is the
                 ;; route's, not this layer's. A comma string arriving
                 ;; where the schema asks an array is taken as spelled.
                 (some? (:fields args))
                 (assoc "fields" (let [f (:fields args)]
                                   (if (string? f)
                                     f
                                     (str/join "," (map str f))))))
        self (str "/api/" (:plural rdef))
        ;; captioned (§ 3): the line is written before the call's beats
        refused (stage-caption! eng session rdef self nil args)
        resp (when-not refused
               (call (request session :get self {:query (query-string params)})))]
    ;; staged (§ 2): the gaze goes to the collection, and its screen
    ;; says what was asked. A query for totals alone shows nothing
    (when (and (<= 200 (:status resp 500) 299)
               (not= "none" (some-> rows str)))
      (when-some [st (stage eng session rdef)]
        (beat! st self
               {:collection (cond-> {:self self}
                              (map? (:filter args)) (assoc :filter (:filter args))
                              (:sort args) (assoc :sort (str (:sort args)))
                              (integer? page_number) (assoc :page page_number))})))
    (or refused
        (answer resp return #(when (collection-doc? %) (collection-summary %))))))

;; return: export — a sealed walk's file, described and not carried
;; (docs/spec-agent-demo-walks.md § 4). The export is a route and not an
;; action, so no tool reached it. A megabyte of frames in a model's
;; context is cost and no use, so the answer is the file's size, its
;; digest and its first lines; `href` is where the whole of it is read.

(def export-lines-cap
  "How many bytes of a walk's export ride the answer as `lines`."
  (* 64 1024))

(defn- export-sha256 [^bytes bs]
  (apply str (map #(format "%02x" %)
                  (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn- first-lines
  "The first of `lines` that fit in `cap` bytes, each counted with its
  newline."
  [lines cap]
  (let [cap (long cap)]
    (loop [kept [] used 0 more (seq lines)]
      (if-some [l (first more)]
        (let [used (+ used 1 (alength (.getBytes ^String l StandardCharsets/UTF_8)))]
          (if (> used cap)
            kept
            (recur (conj kept l) used (next more))))
        kept))))

(defn- export-digest
  "What an agent needs to know about a walk's export, read off the
  route's own bytes: `bytes` and `sha256` are of the whole file, and
  `lines` is its first lines, the header line among them."
  [href ^String text]
  (let [bs (.getBytes text StandardCharsets/UTF_8)
        all (str/split-lines text)
        lines (first-lines all export-lines-cap)
        header (try (j/read-value (first all) verbatim-mapper)
                    (catch Exception _ nil))]
    (array-map "format" (get header "format")
               "header" header
               "frames" (max 0 (dec (count all)))
               "bytes" (alength bs)
               "sha256" (export-sha256 bs)
               "href" href
               "lines" lines
               "truncated" (< (count lines) (count all)))))

(defn- not-sealed
  "The refusal for an export of a walk that is not sealed. A recording
  walk names `seal` as the remedy; a purged one has no frames left."
  [state]
  (p/problem
   :wrong-state 409 "Wrong state"
   (if (= "recording" state)
     {:detail (str "Only a sealed walk exports; this walk is recording. "
                   "Seal it first: waymark_invoke with kind \"walk\" and "
                   "action \"seal\".")
      :state state
      :becomes-available {:in-states ["sealed"]}
      :remedies ["walk.seal"]}
     {:detail (str "Only a sealed walk exports; this walk is " state
                   ", and its frames are gone.")
      :state state})))

(defn- walk-export
  "`waymark_get` with `return: \"export\"`. The row is read first, so a
  walk the session cannot see answers the row's own not-found; the
  export is then the route's answer under the session's visibility,
  the same bytes GET /api/walks/{id}/export gives."
  [eng call session {:keys [kind id]}]
  (let [rdef (rdef-of eng kind)]
    (when-not (= walks/kind (:kind rdef))
      (throw (p/problem :invalid-argument 422 "Invalid argument"
                        {:detail (str "return: \"export\" reads a sealed walk; "
                                      (name (:kind rdef)) " has no export.")
                         :argument "return"
                         :given "export"})))
    (let [self (str "/api/" (:plural rdef) "/" id)
          row (call (request session :get self {}))]
      (if-not (<= 200 (:status row 500) 299)
        (pass-through row)
        (let [state (str (get (verbatim-json row) "state"))]
          (if (not= "sealed" state)
            (refusal (not-sealed state))
            (let [href (str self "/export")
                  resp (call (request session :get href {}))]
              (if (<= 200 (:status resp 500) 299)
                (result (j/write-value-as-string
                         (export-digest href (body-text resp))
                         verbatim-mapper))
                (pass-through resp)))))))))

(defn- get-row [eng call session {:keys [kind id depth] :as args}]
  (if (= "export" (some-> (:return args) str))
    (walk-export eng call session args)
    (let [return (return-of args)
          rdef (rdef-of eng kind)
          self (str "/api/" (:plural rdef) "/" id)
          ;; captioned (§ 3): the line is written before the call's beat
          refused (stage-caption! eng session rdef self nil args)
          ;; staged (§ 2): the stage is set before the read, which moves
          ;; a scoped caller's gaze with no frame (`gaze-before`)
          st (when-not refused (stage eng session rdef))
          since (some-> st gaze-before)
          resp (when-not refused
                 (call (request session :get self
                                {:query (when depth (query-string {"depth" (str depth)}))})))]
      ;; the gaze goes to the row that was read
      (when (and st (<= 200 (:status resp 500) 299))
        (beat! st self nil since))
      (or refused
          (answer resp return #(when (row-doc? %) (row-summary %)))))))

(def ^:private consequence-of mcp-act/consequence-of)
(def ^:private confirm-refusal mcp-act/confirm-refusal)

(def origin-prefix mcp-act/origin-prefix)
(def origin-key mcp-act/origin-key)
(def origin-of mcp-act/origin-of)
(def log-scan-cap mcp-act/log-scan-cap)
(def actions-from-mcp mcp-act/actions-from-mcp)
(def app-origin-prefix mcp-act/app-origin-prefix)
(def taps-from-app mcp-act/taps-from-app)

(def ^:private schedule-keys mcp-act/schedule-keys)
(def ^:private schedule-call mcp-act/schedule-call)
(def ^:private invoke mcp-act/invoke)

(def rehearse mcp-act/rehearse)
(def ^:private pursue mcp-act/pursue)
(def ^:private history mcp-act/history)
(def ^:private in-flight-read mcp-act/in-flight-read)

(def resolve-chunk mcp-act/resolve-chunk)
(def resolvable-fields mcp-act/resolvable-fields)
(def ^:private resolve-rows mcp-act/resolve-rows)

;; ── waymark_sit: the keyed sitter session (spec-seat.md R-12.14) ────
;;
;; The problem, stated once: a person signed in through the connector
;; resolves to ONE delegate on ONE bearer, and every session of that
;; connector — a Routine's firing, an afternoon of chat — is that same
;; delegate. Nothing in the credential says which is which, so a seat
;; could not be handed to a Routine without handing it to the person's
;; chats as well.
;;
;; The owner's ruling (2026-09-17) is the fix: "a key in the
;; instructions that can be used in conjunction with the MCP server to
;; get the proper delegate". The person mints 128 bits, offers them to
;; the seat (`offer_key`), and pastes them into the Routine's
;; instructions. The firing presents the key here, once, and THIS MCP
;; session — that one, by its Mcp-Session-Id, and no other — becomes
;; the seat's sitter.
;;
;; What the sitter IS, after this call, is nothing new: an ordinary
;; agent member wearing an ordinary seat grant. The router's own seat
;; machinery does the rest — R-5.2's walls, the sitting's counters,
;; the ledger — because a bound session is, from the router's side,
;; simply a different principal wearing a different leash.
;;
;; THE KEY MAY BE THE FIRING'S OWN (R-12.37). A key a person pastes
;; into a Routine is a secret in a prompt a person writes by hand, and
;; a missed step is silent until the first firing. So the engine mints
;; one key for each fire of a seat that has instructions, the fire text
;; carries it on the line under the seat's, and the seat row keeps the
;; hash. This door takes that key exactly as it takes a standing one,
;; with one difference: it SPENDS it. The key opens one sit, and the
;; next session that presents it reads the uniform sentence.
;;
;; The sit also names the RUN, when the harness knows its own session
;; id (R-12.15): the sitting is stamped with it at birth, and the hook
;; that reports the bill at the end names the same id (R-12.17). Two
;; runs of one seat in the same hour — a person tapping Run now during
;; the scheduled firing — are then two sittings, each closed by its
;; own hook, rather than one row with both wakes' tokens on it and one
;; hook left holding a bill for a sitting somebody else already shut.

;; ── the sit's refusals, its sitting, its notes and its feedback ──────
;;
;; The sit's refusals and the sitting it opens, the filter and judgment
;; blocks of a walk, the walk's notes and withheld reasons, the bench's
;; feedback, the submit sentence and the references, the app's own
;; sections and an org mayor's first sit live in mcp.notes (ticket
;; ed0a593e). These are its stubs, one for each var this namespace
;; still calls; `mcp/sit-sections-key` stays public for its callers.

(def ^:private sit-no-session mcp-notes/sit-no-session)
(def ^:private sit-not-a-delegate mcp-notes/sit-not-a-delegate)
(def ^:private sit-interactive mcp-notes/sit-interactive)
(def ^:private sit-no-seat mcp-notes/sit-no-seat)
(def ^:private sit-no-model mcp-notes/sit-no-model)
(def ^:private sit-superseded-judgment mcp-notes/sit-superseded-judgment)
(def ^:private open-sitting! mcp-notes/open-sitting!)

(def ^:private filter-params mcp-notes/filter-params)
(def ^:private queue-params mcp-notes/queue-params)
(def ^:private judgment-block mcp-notes/judgment-block)
(def ^:private own-held-reason mcp-notes/own-held-reason)

(def ^:private walk-note mcp-notes/walk-note)
(def ^:private judgment-walk-note mcp-notes/judgment-walk-note)
(def ^:private no-walk-note mcp-notes/no-walk-note)
(def ^:private halted-note mcp-notes/halted-note)
(def ^:private halted-of mcp-notes/halted-of)
(def ^:private walk-filter-left-out mcp-notes/walk-filter-left-out)
(def ^:private walked-nothing-why mcp-notes/walked-nothing-why)
(def ^:private may-create-first-note mcp-notes/may-create-first-note)
(def ^:private graced-reason mcp-notes/graced-reason)
(def ^:private with-lease-ends mcp-notes/with-lease-ends)
(def ^:private with-graced-reasons mcp-notes/with-graced-reasons)
(def ^:private other-seat-reason mcp-notes/other-seat-reason)
(def ^:private defer-past-other-seats! mcp-notes/defer-past-other-seats!)
(def ^:private with-other-seat-reasons mcp-notes/with-other-seat-reasons)
(def ^:private change-beside-the-walk-note mcp-notes/change-beside-the-walk-note)
(def ^:private bench-filled-note mcp-notes/bench-filled-note)
(def ^:private every-row-held-note mcp-notes/every-row-held-note)
(def ^:private named-row-withheld-note mcp-notes/named-row-withheld-note)
(def ^:private default-filter-note mcp-notes/default-filter-note)
(def ^:private named-row-held-note mcp-notes/named-row-held-note)
(def ^:private with-failing-change mcp-notes/with-failing-change)
(def ^:private held-by-note mcp-notes/held-by-note)
(def ^:private held-by-sat-note mcp-notes/held-by-sat-note)
(def ^:private held-by-why mcp-notes/held-by-why)

(def ^:private orientation-there? mcp-notes/orientation-there?)
(def ^:private with-train-red mcp-notes/with-train-red)
(def ^:private with-ops-verdicts mcp-notes/with-ops-verdicts)
(def ^:private ci-runs-of mcp-notes/ci-runs-of)
(def ^:private feedback-of mcp-notes/feedback-of)
(def ^:private submit-means mcp-notes/submit-means)
(def ^:private bench-dark-note mcp-notes/bench-dark-note)
(def ^:private bench-refused-note mcp-notes/bench-refused-note)
(def ^:private reference-of mcp-notes/reference-of)

(def sit-sections-key mcp-notes/sit-sections-key)
(def ^:private app-sections mcp-notes/app-sections)
(def ^:private org-live-of mcp-notes/org-live-of)
(def ^:private org-live-note mcp-notes/org-live-note)

;; one decoded row by id, shared with the change minting in mcp.bench
;; (ticket 31bf1d7c)
(def ^:private row-of mcp-bench/row-of)

;; ── the walk, in the sit's answer (R-12.28) ─────────────────────────
;;
;; Measured on production: ten calls and 34 KB before the first
;; invoke, every one of them a turn that re-read the whole prefix.
;; The engine knew all of it at the sit — the seat row names the walk
;; and the charter, the sitter's grant names the rows it may see, and
;; the collection item IS an envelope minus data, so its doors and
;; their input schemas are already on the page the query answers.
;; What follows SHAPES that page and decides nothing: one read,
;; through the plural route waymark_query takes, wearing the leash
;; every call after the bind will wear.

(defn- sitter-session
  "The session the sitter's own reads run as — built here exactly as
  the transport builds it for every call AFTER the bind
  (routes/mcp.clj's `sitter-session`): the sitter's principal, and
  the worn seat grant, accepted as the audience on arrival the way
  the guest door accepts it, or the bootstrap surface when nothing
  stands. Two spellings of one resolution would be two answers to
  what a sitter may see, so this one calls the same two fns.

  ONE DIFFERENCE, RECORDED: the transport also writes the seat's halt
  line here (`router/mind-the-wall!`, R-7.7). This does not. The wall
  is judged all the same — a seat behind one scopes to nothing and
  the walk comes back empty — and the first call the bound session
  makes after the sit is the request that records it.

  `sitting-id` is the sitting being opened, passed on exactly as the
  transport passes the bound one: the per-sitting wall judges ITS
  fuel, not the newest sibling's (ticket 8358b658)."
  [eng sitter sitting-id]
  {:principal sitter
   :visibility (or (grants/worn-visibility eng sitter sitting-id)
                   (grants/bootstrap-visibility eng sitter))})

(defn- walk-door
  "One door of a walk row, as the sitter must call it: the action's
  name, the input `waymark_invoke` wants — the fields and their types,
  so the sitter never reads the kind's schema for them — and, when the
  door is confirm-gated, the sentence it must echo back as
  `acknowledge`.

  The entry is the envelope's own, so an action the grant does not
  admit, or one this row's state does not afford, is not here to be
  shaped. The sentence is `consequence-of`'s reading, over the wire's
  own string keys: two readings of one sentence is a gate that can be
  walked around, so the accessor stays the confirm gate's."
  [aname entry]
  (cond-> {"action" (str aname)}
    (get entry "input") (assoc "input" (get entry "input"))
    (get-in entry ["safety" "confirm"])
    (assoc "confirm" true
           "acknowledge"
           (consequence-of {:display {:description (get-in entry ["display"
                                                                  "description"])
                                      :label (get-in entry ["display" "label"])}}))))

(defn- walk-row
  "One row of the walk: `row-summary`'s projection of the collection
  item — the same one `waymark_query` answers under return=summary —
  and the doors that item advertises."
  [item]
  (cond-> (row-summary item)
    (seq (get item "actions"))
    (assoc "doors" (mapv (fn [[aname entry]] (walk-door aname entry))
                         (sort-by key (get item "actions"))))))

;; ── the walk of a judgment (bead waymark-fp62.11, R-4) ──────────────
;;
;; THE QUEUE IS A QUERY. A seat that says a judgment walks the
;; judgment's `subject_kind` under the judgment's `queue`, minus the
;; subjects that already carry a verdict under it. Nothing is copied
;; and no row is minted for work not yet done, so a judgment promoted
;; this morning has a full queue by lunch with no backfill anywhere.
;;
;; The judgment row is read THROUGH THE ENGINE and not through the
;; sitter (`repo-policy-of`'s posture): what the seat may see decides
;; which SUBJECTS reach the page, and the law those subjects are
;; judged under is the seat's own declaration, already on its row.

(def ^:private walk-pages-max
  "The most pages of its queue one walk reads past the rows it
  subtracts (ticket f175f8c5). A queue whose first page is all judged,
  claimed or stuck rows has its free rows on a later one."
  10)

(defn- kind-walk-of
  "The seat's walk, read AS THE SITTER: the kind `walk` names, through
  the same plural route `waymark_query` takes, under that kind's own
  default filter and its own default sort — oldest first for a queue
  that sorts by when the work arrived — at most `rows_per_firing`
  rows, and never more than one page.

  THE WALK'S FILTER IS ITS SCOPE ENTRY'S (R-1 of bead
  waymark-fp62.12). When the entry that opens the walked kind carries
  a `filter`, that filter rides as query parameters and the kind's own
  default stands aside for it, exactly as a caller's query string
  does. Then the rows the seat MAY see are the rows it walks, and a
  row that leaves the filter leaves both in the same commit. An entry
  with no filter adds no parameter at all, so the plain walk is the
  request it has always been, byte for byte.

  A SEAT THAT SAYS A JUDGMENT WALKS THE JUDGMENT'S QUEUE (R-4): the
  judgment's `queue` rides as the filter, the subjects already judged
  are left out by the store in that same query (`judgments/walk-conds`,
  ticket 279366ee), and the answer carries the `judgment` block beside
  the rows. The claimed and the stuck rows are subtracted after the
  read. That subtraction can empty a page, so the read asks for a
  WHOLE page and the cap bites after the minus rather than before it.
  It can empty that whole page too, so the read goes on to the queue's
  next pages, `walk-pages-max` at most, until the rows are found or
  the queue ends (ticket f175f8c5).

  nil when the seat walks nothing, when `walk` names a kind this
  engine does not serve, or when the read does not answer 2xx: a
  queue the sitter's grant conceals — a seat at one of R-5.2's walls
  scopes to nothing — is ABSENT from the answer rather than a refusal
  of the sit, which is R-10.6's rule at this door as at every other.

  Nothing here decides what the sitter may see. The route reads under
  the seat grant's own visibility, so a row outside the grant is not
  on the page it answers and cannot be in what this shapes. `total`
  stays the collection's own count under that filter — the queue as
  the list page would show it — and the rows are what this firing
  works. A judging walk's first page answers that count beside the
  anti-joined one (`listed_total`), so the sit makes no read whose
  only purpose is `total` (ticket 7b308e02).

  `claimed` is the rows another open sitting of this seat was already
  handed (`seats/claimed-rows`). They are subtracted as the judged
  subjects are, and the read asks for a whole page for the same
  reason, so a second run of the seat walks the next rows and not the
  first run's.

  `held` is the rows THIS sitting already holds (its leases,
  `seats/held-row-ids`). A re-sit after a connector drop reuses the sitting,
  and those rows, while still in the queue, keep their place on the
  page before the queue's next rows fill it, so the run lands back on
  the row it was walking. The page keeps the queue's order. A row that left the queue is
  simply not on the page, and the queue's next row takes its place.

  `only` is the one row a fire's text named (`seats/fire-key-row`):
  when it is given, the page holds that row alone, or nothing when the
  row is not in the queue or is claimed. The row a fire named may sit
  on any page (ticket 59582777), so when the queue's first page does
  not hold it, ONE more read asks the queue for that row by its id
  (the collection's `id` filter, ticket ba123a99): two queue reads
  however long the queue is. `total` and `withheld` stay the first
  page's. A named OPEN ticket whose
  change is submitted is withheld as the plain walk withholds it
  (ticket 6ca380da).

  `stuck` is the tickets whose change waits for a person, each with
  its reason (`seats/stuck-walk-reasons`, ticket 6bdaf6fe). They are
  subtracted as the claimed rows are: a seat handed one could only say
  it is stuck. The queue rows left out for either cause are answered
  under `withheld`, each with its reason (ticket 87c928e9), so an empty
  walk over a queue that is not empty says why."
  [eng call session seat claimed held only stuck]
  (when-some [walk (some-> (get-in seat [:data :walk]) str not-empty)]
    (when-some [rdef (get (inv/resources eng) (keyword walk))]
      (let [judgment (row-of eng :judgment (get-in seat [:data :judgment]))
            ;; a judgment seat walks the judgment's own queue (R-3),
            ;; and that queue is the filter; the scope entry's filter
            ;; is the walk's for every other seat
            walk-filter (when-not judgment (seats/walk-filter seat))
            n (min (long (or (get-in seat [:data :rows_per_firing]) 20))
                   coll/page-size-max)
            ;; a named ticket beside a live change is read under its
            ;; own state, in review as well as open, and is not left
            ;; out as stuck: the fire sent the run to it (ticket
            ;; 7af7d506). A JUDGMENT SEAT TOO: its queue names `state`,
            ;; and the state named here stands in for it, while the
            ;; judged subjects are still subtracted - so an unjudged
            ;; ticket in review is handed, and a judged one is not
            only-state (when (and only
                                  (seats/named-beside-a-live-change?
                                   eng walk only))
                         (some-> (row-of eng (keyword walk) only) :state name))
            ;; save an open ticket whose round is already submitted:
            ;; the named walk withholds it as the plain one does
            ;; (ticket 6ca380da)
            stuck (cond-> stuck
                    (and only (not (seats/named-open-beside-a-submitted-change?
                                    eng walk only)))
                    (dissoc (str only)))
            subtract? (or judgment (seq claimed) (seq stuck))
            asked (if (or subtract? (seq held) only) coll/page-size-max n)
            params (cond-> {"page[size]" (str asked)}
                     judgment (merge (queue-params judgment))
                     walk-filter (merge (filter-params walk-filter))
                     only-state (assoc "state" only-state))
            ;; one page of the queue: the first is the request it has
            ;; always been, and a later one names its number
            ;; a judgment's queue is asked for less its judged subjects:
            ;; the store answers that anti-join in the page's own query,
            ;; so no page of judged rows is read (ticket 279366ee)
            without (when judgment
                      (not-empty (judgments/walk-conds eng judgment (:id seat))))
            ;; `more` is one read's own filter: the named row's id
            page (fn page*
                   ([k] (page* k nil))
                   ([k more]
                   (let [resp (call (request session :get (str "/api/" (:plural rdef))
                                             {:query (query-string
                                                      (cond-> (merge params more)
                                                        (> k 1) (assoc "page[number]"
                                                                       (str k))))
                                              :conds without}))
                         doc (when (<= 200 (:status resp 500) 299)
                               (verbatim-json resp))]
                     (when (collection-doc? doc) doc))))
            doc (page 1)]
        (when doc
          (let [id-of #(id-of-self (get % "self"))
                skip (if subtract?
                       (into (set claimed) (keys stuck))
                       #{})
                free? #(not (contains? skip (id-of %)))
                ;; the subtraction can empty a whole page while free
                ;; rows wait on the next, so the read goes on until the
                ;; firing's rows are found or the queue ends (ticket
                ;; f175f8c5). A named row the first page does not hold
                ;; is asked for by its id, in one read under the same
                ;; filter: it is one row, and neither the cap on pages
                ;; nor the queue's length stands between the run and it
                ;; (tickets 59582777, ba123a99)
                named? #(= (str only) (id-of %))
                total (long (or (get-in doc ["data" "total"]) 0))
                ;; the answer's `total` stays the queue as the list page
                ;; shows it, judged subjects and all: the collection
                ;; counts it in the read that left them out, so no read
                ;; is made for the count alone (ticket 7b308e02)
                queue-total (or (get-in doc ["data" "listed_total"])
                                (get-in doc ["data" "total"]))
                first-page (vec (get-in doc ["data" "items"]))
                queue (if only
                        (if (or (some named? first-page)
                                (>= (long asked) total))
                          first-page
                          (into first-page
                                (filter named?)
                                (get-in (page 1 {"id" (str only)})
                                        ["data" "items"])))
                        (loop [k 1
                               acc first-page]
                          (if (or (>= (* k (long asked)) total)
                                  (not subtract?)
                                  (>= (count (filter free? acc)) n)
                                  (>= k (long walk-pages-max)))
                            acc
                            (let [more (get-in (page (inc k)) ["data" "items"])
                                  seen (into #{} (map id-of) acc)]
                              (if (empty? more)
                                acc
                                (recur (inc k)
                                       (into acc
                                             (remove #(contains? seen (id-of %)))
                                             more)))))))
                items (cond->> (filter free? queue)
                        only (filter named?))
                ;; the rows this sitting already walks, while they are
                ;; still in the queue, are kept first and the queue's
                ;; next rows fill what room is left: a re-sit is handed
                ;; back its own row, not the queue's new first one. The
                ;; page keeps the queue's order, so a walk of many rows
                ;; reads oldest first as it always did
                mine (into #{} (keep #(some-> % str not-empty)) held)
                mine? #(contains? mine (id-of %))
                kept (take n (filter mine? items))
                chosen (into #{}
                             (map id-of)
                             (concat kept
                                     (take (- n (count kept))
                                           (remove mine? items))))
                items (into [] (filter #(contains? chosen (id-of %))) items)
                claimed? (set claimed)
                withheld (into []
                               (keep (fn [item]
                                       (let [id (id-of item)]
                                         (cond
                                           (contains? stuck id)
                                           {"id" id "reason" (get stuck id)}

                                           (contains? claimed? id)
                                           {"id" id
                                            "reason" own-held-reason}))))
                               queue)]
            (cond-> {"kind" walk
                     "charter" (str (get-in seat [:data :charter]))
                     "total" queue-total
                     "rows" (mapv walk-row items)}
              judgment (assoc "judgment" (judgment-block judgment))
              (seq withheld) (assoc "withheld" withheld))))))))

(defn- breach-rows
  "The rows of the breaches this seat keeps (R-P.6.1,
  `live-cracks/promise-walk`), newest break first, each read AS THE
  SITTER through the row's own route, as `change-said` reads its row,
  projected the way a walk row is, with its `cause` and its `promise`.
  A row the sitter's grant conceals is absent. nil for a seat that does
  not walk the promises it keeps."
  [eng call session seat]
  (when (true? (get-in seat [:data :walk_promises]))
    (into []
          (keep (fn [b]
                  (when-some [rdef (get (inv/resources eng) (keyword (:kind b)))]
                    (let [resp (call (request session :get
                                              (str "/api/" (:plural rdef) "/" (:id b))
                                              {}))
                          doc (when (<= 200 (:status resp 500) 299) (verbatim-json resp))]
                      (when (row-doc? doc)
                        (assoc (walk-row doc)
                               "cause" (:cause b)
                               "promise" (:promise b)))))))
          (store/with-tx (:storage eng) #(live-cracks/promise-walk eng % seat)))))

(defn- with-breach-rows
  "A walk joined with the seat's breach rows (R-P.6.1): its own rows
  first, then each breach row not already handed, by (kind, id), less
  the rows another sitting of the seat `claimed` and, when a fire named
  one row (`only`), every row but that one. A seat that walks no kind
  is handed the breach rows alone."
  [walk seat rows claimed only]
  (let [key-of (juxt #(get % "kind") #(get % "id"))
        skip (into #{} (map str) claimed)
        more (second
              (reduce (fn [[seen acc] r]
                        (if (or (contains? seen (key-of r))
                                (contains? skip (get r "id"))
                                (and only (not= (str only) (get r "id"))))
                          [seen acc]
                          [(conj seen (key-of r)) (conj acc r)]))
                      [(into #{} (map key-of) (get walk "rows")) []]
                      rows))]
    (-> (or walk {"charter" (str (get-in seat [:data :charter])) "total" 0 "rows" []})
        (update "rows" (fnil into []) more)
        (update "total" #(+ (long (or % 0)) (count more))))))

(defn- walk-of
  "The seat's walk: `kind-walk-of`'s, joined with the rows of the
  breaches it keeps when it walks the promises it keeps (`walk_promises`,
  R-P.6.1; `with-breach-rows`). Every other seat is handed exactly
  `kind-walk-of`'s answer: `walk` keeps its meaning."
  [eng call session seat claimed held only stuck]
  (let [walk (kind-walk-of eng call session seat claimed held only stuck)]
    (if-some [rows (breach-rows eng call session seat)]
      (with-breach-rows walk seat rows claimed only)
      walk)))

(def ^:private claim-tries
  "How many times one sit reads its walk again past the rows another
  sit of the seat claimed first."
  3)

(defn- claimed-walk!
  "The walk this sit hands, its rows CLAIMED for `sitting` in the same
  transaction that finds them free (`seats/claim-rows-atomically!`),
  so two sits of one seat at the same instant never hand the same row.
  A sit that loses the race reads its walk again past the rows the
  other took, and after `claim-tries` it hands no rows at all.

  `named` is the row the fire's text named (`seats/fire-key-row`): it
  is handed alone while no other open sitting holds it, and when one
  does the sit hands the queue's next free rows and says so.
  → {:walk w :named-held? bool :named-withheld? bool :all-held? bool}."
  [eng call sitter-sees seat sitting named]
  (let [seat-id (str (:id seat))
        ;; a named FAILING change the seat can fix marks its ticket's
        ;; row, read before the change is read back (ticket c91b3af9)
        mark (seats/failing-change-mark eng seat named)
        ;; a named change is read back to its ticket (ticket 7af7d506)
        named (seats/named-walk-row eng (get-in seat [:data :walk]) named)
        held (some->> (:id sitting) (seats/held-row-ids eng))
        ;; read once for the sit: the tickets beside a stuck change
        ;; are out of the walk whichever try claims (ticket 6bdaf6fe)
        stuck (seats/stuck-walk-reasons eng (get-in seat [:data :walk]))
        ;; the claimed rows a closed sitting's grace holds, named apart
        graced (seats/graced-row-ids eng seat-id (:id sitting))
        walk-past (fn [taken only]
                    (walk-of eng call sitter-sees seat taken held only stuck))]
    (loop [n 1
           taken (seats/claimed-rows eng seat-id (:id sitting))]
      (let [only (when (and named (not (contains? taken named))) named)
            named-walk (when only
                         (let [w (walk-past taken only)]
                           (when (seq (get w "rows")) w)))
            walk (or named-walk (walk-past taken nil))
            ;; a breach row the walk joined (R-P.6.1) is claimed under
            ;; its own kind, so its lease names that kind
            ids (mapv #(if (contains? % "promise")
                         {:kind (get % "kind") :id (get % "id")}
                         (get % "id"))
                      (get walk "rows"))
            claim (if sitting
                    (seats/claim-rows-atomically! eng seat-id (:id sitting) ids)
                    {:claimed? true :taken taken})
            seen (into (set taken) (:taken claim))
            said (fn [w]
                   {:walk (-> (with-graced-reasons w graced)
                              (->> (with-lease-ends eng))
                              (with-other-seat-reasons
                                (seats/other-seats-row-ids eng seat-id))
                              (with-failing-change mark named))
                    :named-held? (boolean (and named (contains? seen named)))
                    ;; named, held by nobody, and still not handed: the
                    ;; walk withheld it and the queue's rows stand in
                    :named-withheld? (boolean (and only (nil? named-walk)))
                    :all-held? (boolean (and w (empty? (get w "rows"))
                                             (seq seen)))})]
        (cond
          (:claimed? claim) (said walk)
          (< n (long claim-tries)) (recur (inc n) seen)
          :else (said (some-> walk (assoc "rows" []))))))))

(defn- held-by-own-sittings
  "The ids of the open sittings of this seat that hold every row the
  sit would walk, or nil while any row is free. Read BEFORE the
  sitting opens (ticket 471d69dd): a fire whose every row another run
  of the seat holds opens no sitting, so it spends no wake and leaves
  no empty sitting behind. A walk a closed sitting's grace or another
  seat's sitting emptied is not this, and sits as it always did."
  [eng call sitter seat named]
  (let [{:keys [walk all-held?]}
        (claimed-walk! eng call (sitter-session eng sitter nil) seat nil named)
        withheld (get walk "withheld")
        reasons (into #{} (map #(get % "reason")) withheld)]
    (when (and all-held?
               (contains? reasons own-held-reason)
               (not (contains? reasons graced-reason))
               (not (contains? reasons other-seat-reason)))
      (not-empty
       (seats/holding-sittings
        eng (str (:id seat))
        (into [] (comp (filter #(= own-held-reason (get % "reason")))
                       (map #(get % "id")))
              withheld))))))


;; the bench and the change it submits live in mcp.bench (ticket
;; 31bf1d7c); `change-said` stays here, because it reads as the sitter.

(defn- change-said
  "The change row beside the walk, read AS THE SITTER: the row's own
  envelope through the same route `waymark_get` takes, projected the
  way a walk row is, so the doors in the answer are the doors the
  seat's scope opens and no others (R-12.32).

  nil when the read does not answer 2xx. A change the sitter's grant
  conceals is ABSENT from the answer rather than a refusal of the sit,
  which is R-10.6's rule at this door as at every other."
  [eng call session change]
  (when-some [rdef (get (inv/resources eng) :change)]
    (let [resp (call (request session :get
                              (str "/api/" (:plural rdef) "/" (:id change))
                              {}))
          doc (when (<= 200 (:status resp 500) 299) (verbatim-json resp))]
      (when (row-doc? doc) (walk-row doc)))))

(def ^:private bench-prepare-timeout-ms
  "How long the sit waits on the rig's `prepare`, in place of the 30 s
  every other bench call gets. The rig serialises git work per
  repository, so a fetch, a first worktree or another seat's git call
  on the same repository can hold a prepare well past 30 s while the
  rig is up (ticket 57fda9cb)."
  120000)

(def ^:private bench-fetch-retry-pause-ms
  "How long the sit waits before it asks for the `prepare` again, when
  the rig refused it on a network error (ticket 2576d7a1)."
  2000)

(def ^:private network-refusal-re
  "What git says when it could not reach the forge. Such a refusal is
  about the minute and not about the repository: the same fetch is
  often good on the next try."
  #"(?i)could not resolve host|temporary failure in name resolution|failed to connect to|connection timed out|connection reset|connection refused|operation timed out|network is unreachable|the remote end hung up|could not read from remote repository")

(defn- network-refusal?
  "True when the rig refused the `prepare` and its reason is a network
  error."
  [answer]
  (boolean (some->> (mcp-bench/bench-refusal answer) :reason str
                    (re-find network-refusal-re))))

(defn- prepare-answer
  "The rig's answer to `prepare`, or nil when it did not answer. A call
  that ran out the whole `timeout-ms` is tried ONCE more, because the
  rig was busy and not down: the first try's git work is often done by
  the second. A call that failed sooner (a refused connection answers
  at once) is not tried again. A REFUSAL whose reason is a network
  error is tried ONCE more too, after a short wait: one failed fetch
  is no cause to stall a change (ticket 2576d7a1). When that try
  refuses as well, or says nothing, the first refusal stands."
  ([gate-rpc arguments]
   (prepare-answer gate-rpc arguments bench-prepare-timeout-ms))
  ([gate-rpc arguments timeout-ms]
   (let [once (fn []
                (let [t0 (System/nanoTime)]
                  (try
                    {:answer (binding [mcp-client/*call-timeout-ms* timeout-ms]
                               (gate-rpc "tools/call"
                                         {:name (gate/bench-tool :prepare)
                                          :arguments arguments}))}
                    (catch Exception e
                      (binding [*out* *err*]
                        (println "waymark10 bench prepare failed -"
                                 (ex-message e)))
                      {:timed-out (>= (quot (- (System/nanoTime) t0) 1000000)
                                      (long timeout-ms))}))))
         first-try (once)
         answer (:answer (if (:timed-out first-try) (once) first-try))]
     (if (network-refusal? answer)
       (do (Thread/sleep (long bench-fetch-retry-pause-ms))
           (let [again (:answer (once))]
             (if (or (mcp-bench/bench-payload again) (mcp-bench/bench-refusal again))
               again
               answer)))
       answer))))

(defn- bench-of
  "The bench section of the sit's answer (R-12.29), or nil when this
  firing has no change to work.

  In order: the change's own repository, that repository's policy,
  the branch, and then ONE call to the rig's `prepare` — the engine's
  own hand, past the leash. The answer carries the worktree (the
  repository, the branch, the base, the head commit and how many paths
  are dirty from an earlier sitting), the orientation path the seat
  reads first — or the sentence that says there is no such document,
  which costs one more read of the rig (R-6) — and what submit means
  here.

  A change that has already been submitted gets one call more: the
  rig's `feedback`, which says what that submit caused (R-12.31). The
  engine asks for it when the change names a `head_branch` and a
  `number` — a person's own pull request — or when it has had a
  round, because a branch nobody has pushed has no pull request and
  no pipeline to read. The answer rides as `feedback`, and a rig that
  refuses or faults costs the key and never the sit.

  `gate-rpc` is this engine's Gate caller, built once by the transport.
  It THROWS when Gate is dark, and the throw is caught here: the sit
  answers without a bench rather than not at all.

  The change is the caller's: the walk's own first row for a code
  seat, and the row the engine found or minted for a seat that walks
  anything else (R-12.32). This section reads the repository, the
  branch and the rounds off that row and asks nothing about the walk.

  THE TOOLS RIDE WITH THE WORKTREE (waymark-fp62.6.3.12). The bench
  map carries `tools`: each bench power the SEAT's scope names, with
  the tool name the power door resolves it to. A seat reads what to
  call from the answer it sits down with."
  [eng gate-rpc seat change]
  (when change
    (let [repo (str (get-in change [:data :repository]))
          policy (mcp-bench/repo-policy-of eng repo)
          base (or (some-> (get-in policy [:data :base]) str not-empty)
                   mcp-bench/default-base)
          branch (mcp-bench/bench-branch change policy)
          ;; the prepare's whole answer is kept, because a REFUSAL is
          ;; an answer: the sit says its reason rather than the
          ;; sentence for a bench that was silent (waymark-fp62.6.3.11)
          answer (prepare-answer gate-rpc
                                 {:repo repo :branch branch :base base})
          made (mcp-bench/bench-payload answer)
          refusal (mcp-bench/bench-refusal answer)
          means (submit-means policy change)
          ;; what the last submit caused, for a change that HAS one:
          ;; a person's own branch, or a round this house already
          ;; pushed (R-12.31). A worktree the rig did not make is
          ;; asked nothing. A CHANGE THE ENGINE MINTED FOR A WALK ROW
          ;; (R-12.32) names a head branch from its birth and has no
          ;; pull request at all, so a head branch alone is not a
          ;; submit here: such a row carries no `number` until a round
          ;; pushes it and the source adopts it.
          ;; a merge train's red rides on the change row, not on the
          ;; branch the rig reads, so it is added here (ticket 238f45b3)
          ;; and the ops seat's verdicts on the change's head ride
          ;; first of all (ticket 954e9d25)
          feedback (with-ops-verdicts
                    (with-train-red
                     (when (and made
                                (or (>= (long (or (get-in change [:data :rounds])
                                                  0))
                                        1)
                                    (and (some-> (get-in change [:data :head_branch])
                                                 str not-empty)
                                         (some? (get-in change [:data :number])))))
                       (feedback-of gate-rpc (str (or (:repo made) repo))
                                    (str (or (:branch made) branch))
                                    (mcp-bench/bench-tools-of eng seat)))
                     change)
                    change (ci-runs-of eng change))
          ;; the path the policy names, answered only when the file
          ;; is really there (R-6); a worktree that was never made
          ;; holds nothing, so a dark rig is asked for no read
          path (or (some-> (get-in policy [:data :orientation])
                           str not-empty)
                   mcp-bench/default-orientation)]
      (cond-> {"orientation"
               (if (and made (orientation-there?
                              gate-rpc (str (or (:repo made) repo))
                              (str (or (:branch made) branch)) path))
                 path
                 (str mcp-bench/no-orientation-said means))
               "submit_means" means}
        made (assoc "bench" {"repo" (str (or (:repo made) repo))
                             "branch" (str (or (:branch made) branch))
                             "base" (str (or (:base made) base))
                             "head" (some-> (:head made) str)
                             "dirty" (long (or (:dirty made) 0))
                             "tools" (mcp-bench/bench-tools-of eng seat)})
        ;; the repositories the seat may only read, beside the one
        ;; it edits (R-12.32)
        made (merge (reference-of eng seat (str (or (:repo made) repo))))
        feedback (assoc "feedback" feedback)
        (and (nil? made) refusal) (assoc "bench_note"
                                         (bench-refused-note refusal))
        (and (nil? made) (nil? refusal)) (assoc "bench_note"
                                                bench-dark-note)))))

(defn- sit
  "R-12.14, in order, and every refusal is one plain sentence an agent
  can act on.

  `gate-rpc` is this engine's Gate caller (the transport's own, built
  once): the bench section below reaches the rig with it, past
  `invoke-for`. It is why this body is dispatched from `call-tool`
  rather than from `bodies`, as the two power tools are."
  [eng call gate-rpc session args]
  (let [sid (some-> (:mcp-session-id session) str not-empty)
        person (some-> (:acts-for (:principal session)) str not-empty)
        ;; the seat the instructions NAMED, when they named one (R-4 of
        ;; waymark-fp62.7.23): the key is then the seat's own or its
        ;; chair's, which is what lets one Routine hold several
        ;; offices. With no seat named the key answers for itself,
        ;; exactly as it always did.
        ;;
        ;; Either way the seat is read before the person is judged, so
        ;; an INTERACTIVE seat can answer with its own sentence
        ;; (R-10.8) rather than with the general one about delegates.
        named (some-> (:seat args) str str/trim not-empty)
        keyed (when sid
                (if named
                  (seats/seat-for-key eng named (:key args))
                  (seats/seat-by-key eng (:key args))))
        ;; A FIRING'S SPENT KEY RE-SITS ITS OWN OPEN SITTING (R-12.16,
        ;; R-12.37): a run whose bind died with the engine presents it
        ;; again, with the harness session stamped on that sitting at
        ;; birth, and lands where it was. It opens nothing else.
        resit (when (and sid named (nil? keyed))
                (seats/resit-sitting eng named (:key args) (:session args)))
        seat (or keyed (first resit))
        now ((:now-fn eng))
        ;; A SUPERSEDED JUDGMENT IS JUDGED BEFORE THE SPEND (ticket
        ;; 017814ab): a seat that walks no law in force refuses the
        ;; sit without burning a firing's key, so a restate can be
        ;; followed by the same fire's key rather than a new fire.
        ;; `seat` is only ever a row the presented key already opened
        ;; (or a re-sit's own), and a person must stand behind the
        ;; session, so an unkeyed caller learns nothing here that an
        ;; unknown key would not tell it.
        stood-down (when (and seat person) (sit-superseded-judgment eng seat))
        ;; A SEAT THAT NAMES NO MODEL OPENS NO SITTING, and so is not
        ;; sat at all (ticket 7e177f8d): judged before the spend, like
        ;; the superseded judgment, and a re-sit already has its sitting
        modelless (when (and seat person (nil? resit) (nil? stood-down)
                             (nil? (seats/seat-model eng seat)))
                    (sit-no-model (str (get-in seat [:data :name]))))
        ;; THE FIRING'S OWN KEY IS SPENT HERE, and one time (R-12.37).
        ;; A standing key (the seat's own, or its chair's) is spent by
        ;; nothing and answers true at once. Anything else that got
        ;; this far is the key one fire text carried, and the spend is
        ;; what takes its hash off the seat row: the next sit that
        ;; presents it reads a row that no longer answers, and gets
        ;; the uniform sentence.
        ;;
        ;; THE `and` IS WHAT FENCES THE WRITE. A session with no seat
        ;; and a session with nobody behind it stop at the first two
        ;; terms, so neither can burn a key it may not use. The cond
        ;; below then reads this as its last wall, after the walls
        ;; that cost nothing.
        standing? (boolean (and keyed person
                                (seats/standing-key? eng keyed (:key args))))
        ;; the walk row the firing's text named, read BEFORE the spend,
        ;; which takes the key's entry off the seat row
        named-row (when (and keyed person (not standing?))
                    (seats/fire-key-row eng keyed (:key args)))
        ;; … and the sitting the fire opened, read before the spend too
        ;; (ticket 9100c7b9)
        fire-sitting (when (and keyed person (not standing?))
                       (seats/fire-key-sitting eng keyed (:key args)))
        fired? (and keyed person (not standing?) (nil? stood-down) (nil? modelless)
                    (true? (seats/spend-fire-key! eng keyed (:key args))))
        spent? (and seat person (or standing? fired? (some? resit)))]
    (cond
      ;; a · a session to bind to
      (nil? sid) (result sit-no-session true)
      ;; b · a person behind the tool — and, at an interactive seat,
      ;; the refusal says which kind of seat this is: a Routine's run
      ;; is not somebody who forgot to sign in, it is a session that
      ;; may not sit here at all
      ;; Each refusal from here is stamped on the seat it reached, by
      ;; its own name (ticket dfbb48b3)
      (nil? person)
      (unsat/refused! eng (or seat named)
                      (if (seats/interactive-seat? seat) "interactive" "not-a-delegate")
                      (result (if (seats/interactive-seat? seat)
                                (sit-interactive (str (get-in seat [:data :name])))
                                sit-not-a-delegate)
                              true))
      ;; c · a seat that answers the key
      (nil? seat) (unsat/refused! eng named "no-seat" (result sit-no-seat true))
      ;; c' · a seat whose judgment was superseded walks no law in
      ;; force, and the refusal names the successor (ticket 86514746).
      ;; It comes before the spend's wall: the key was never spent
      stood-down (unsat/refused! eng seat "stood-down" (result stood-down true))
      ;; c'' · a seat that names no model binds nothing
      modelless (unsat/refused! eng seat "modelless" (result modelless true))
      ;; c'' · and a key still worth something: a firing's key that a
      ;; second session took first says what an unknown key says
      (not spent?) (unsat/refused! eng seat "no-seat" (result sit-no-seat true))
      :else
      (let [seat-id (str (:id seat))
            named (str (get-in seat [:data :name]))
            ;; the run's own id, when the harness knows one — the same
            ;; id its session-end hook will report (R-12.15)
            harness (some-> (:session args) str str/trim not-empty)
            sitter-id (seats/sitter-id seat)
            display (seats/sitter-display seat)
            ;; d · the sitter row, minted once per seat and found ever after
            _ (members/ensure-sitter! eng sitter-id display person)
            ;; d' · the sitting this run's fire opened, taken for this
            ;; session (ticket 9100c7b9): the claim stamps the person
            ;; behind it and the run's own id, and lets the lease from
            ;; now. One another session took first, or one already
            ;; ended, is no answer, and the sit opens its own below
            taken (when (and fired? fire-sitting (nil? resit))
                    (seats/claim-sitting!
                     eng fire-sitting (some-> sid str not-empty sessions/id-hash)
                     #(nil? (some-> (:connector_session %) str not-empty))
                     true
                     (cond-> {:person person}
                       harness (assoc :harness_session harness))))
            ;; e · the leash
            ;; a re-sit wears the grant its sitting was opened under, and
            ;; so does the sitting a fire opened
            grant (or (when resit
                        (row-of eng :grant (get-in (second resit) [:data :grant])))
                      (when taken
                        (row-of eng :grant (get-in taken [:data :grant])))
                      (seats/standing-seat-grant eng sitter-id seat now)
                      (seats/mint-seat-grant! eng sitter-id seat now))
            ;; f · the model claim, when the schedule makes one
            model-row (seats/seat-model eng seat)
            model (some-> model-row (get-in [:data :name]) str not-empty)
            ;; g · the binding: this session, that sitter, from now on
            ;; the roles the sitter row holds ride on the principal,
            ;; as gate! puts them on any member who arrives
            sitter (assoc (t/principal {:id sitter-id :type :agent
                                        :display display :model model
                                        :roles (members/held-roles eng sitter-id)})
                          :acts-for person)
            ;; g' · the sitting the router counts against, opened here
            ;; because nobody else opens one for a keyed session
            ;; g' · … unless every row is held: a fire whose rows another
            ;; open sitting of the seat holds opens none (ticket 471d69dd).
            ;; A standing key and a re-sit sit as they always did
            held-by (when (and fired? (not resit)
                               (not (halted-of eng seat named now)))
                      (held-by-own-sittings eng call sitter seat named-row))
            ;; … and a fire whose rows are all held still takes the
            ;; sitting its fire opened, which walks nothing (ticket
            ;; 9100c7b9): its hook closes it, so its spend is counted
            sitting (or taken
                        (when-not held-by
                          (or (second resit)
                              (open-sitting! eng sid sitter grant seat model-row harness))))
            ;; … and the spent firing's key leaves its trace on the
            ;; sitting it opened, so a lost bind can find its way back
            _ (when (and fired? sitting)
                (seats/keep-fire-key! eng (:id sitting) (:key args)))
            ;; … and what this session's client declared at initialize
            ;; rides the sitting, written at every sit (ticket b9f90987):
            ;; one read then answers why a host got no waymark_show
            _ (when sitting
                (seats/stamp-client! eng (:id sitting)
                                     {:client-name (:client-name session)
                                      :client-version (:client-version session)
                                      :app-ui (:app-ui session)
                                      :app-tools (app-session? eng session)}))
            ;; g'' · the transcript's key (docs/spec-transcript.md R-4):
            ;; born with the first sit, a fresh key at each sit after,
            ;; and nil when the seat keeps no transcript of this
            ;; sitting. The hook reads it out of THIS answer, as it
            ;; reads the sitting's id.
            transcript-key (when sitting
                             (transcripts/issue-key! eng seat sitting))
            ;; g''' · the inbox's key, for a seat that declares an
            ;; `inbox` and for every interactive seat: a fresh key at
            ;; each sit, alive while the sitting is open. A re-sit of
            ;; the same harness session stops no earlier key
            ;; (seats/issue-inbox-key!)
            inbox-key (when sitting
                        (seats/issue-inbox-key! eng seat sitting harness))
            ;; g'''' · the outside feed's token, for a seat that declares
            ;; a `feed_url`: a fresh token at each sit, alive while the
            ;; sitting is open and 35 minutes at most. A re-sit stops
            ;; no earlier token: the one a stream is using answers
            ;; until its own end (seats/issue-feed-token!)
            feed (when sitting
                   (seats/issue-feed-token! eng seat sitting))
            ;; h · the bind, BEFORE the walk is read: the session is
            ;; the seat's from this moment, whatever the queue answers
            _ (when (or taken (not held-by))
                (bind-session! eng sid {:seat seat-id :sitter sitter
                                        :bound-at now :sitting (:id sitting)}))
            ;; h' · the fuel wall. A seat whose week is spent walks
            ;; nothing (the router scopes its grant to nothing), so the
            ;; sit says so in words and hands no walk, no change and no
            ;; bench: a run already in flight, or one a person started
            ;; by hand, then stops for the right reason
            halted (halted-of eng seat named now)
            ;; i · the walk, read as the sitter under the seat's grant
            ;; and through the query path — the rows this firing works
            ;; through, with the doors each one affords
            sitter-sees (sitter-session eng sitter
                                        (some-> (:id sitting) str not-empty))
            ;; … the rows this sitting already holds, read before its
            ;; walk claims: a re-sit of a sitting that walked holds them
            held-before (some->> (:id sitting) (seats/held-row-ids eng))
            ;; … past the rows another open sitting of this seat was
            ;; handed: a fire and a wake that land together are two
            ;; runs, and the second walks the next row, not the first's.
            ;; A reused sitting's own rows are kept, so a re-sit after a
            ;; connector drop is handed back the row it was walking.
            ;; The rows this sitting was handed are its own until it
            ;; closes, claimed in the transaction that found them free
            {walk :walk named-held? :named-held? named-withheld? :named-withheld?
             all-held? :all-held?}
            (when-not (or halted held-by)
              (claimed-walk! eng call sitter-sees seat sitting named-row))
            ;; … and a sitting the walk handed nothing is stamped as
            ;; such — no rows free, or an empty queue — so seat health
            ;; counts an idle wake without reading an absent
            ;; lease. A seat at a wall was not let walk: it is
            ;; stamped `halted` and not `walked_nothing`. The rows this
            ;; sitting already holds count: a re-sit of a sitting that
            ;; walked is not it.
            _ (when sitting
                (seats/stamp-walked-nothing!
                 eng (:id sitting)
                 (and (empty? (get walk "rows"))
                      (empty? held-before))
                 ;; … with the reason it was empty, and a seat at a
                 ;; wall stamped with the wall instead (ticket ae64b57c)
                 ;; … and with the rows its filter left out, counted
                 ;; under the seat's own grant (ticket 6ea8f277)
                 (if held-by
                   {:why (held-by-why held-by)}
                   {:why (walked-nothing-why
                          walk
                          (walk-filter-left-out eng call sitter-sees seat walk))
                    :halted halted})))
            ;; … and a walk another seat's open sitting emptied wakes
            ;; the seat again when that hold lifts (ticket bf17ce48)
            _ (when (and sitting (not held-by)
                         (empty? (get walk "rows"))
                         (empty? held-before))
                (defer-past-other-seats! eng seat walk named-row))
            ;; … and the schedule says the wall too, beside the wake
            ;; it holds back
            _ (seats/stamp-halted-schedule! eng seat-id halted)
            ;; … and the seat row, once: the halted sit marks the seat
            ;; `budget_reached` the way the router's wall does
            _ (when halted
                (seats/seat-halt-at-the-wall! eng seat-id (get halted "detail")))
            ;; i' · the change this firing submits: the walk's own
            ;; first row for a code seat (R-12.29), and the row the
            ;; engine finds or mints for a seat that walks a queue of
            ;; asks (R-12.32). The sentence is what the seat is told
            ;; when there is no change and therefore no bench.
            [change change-note] (when-not (or halted held-by)
                                   (mcp-bench/change-of-sitting eng seat walk))
            ;; i'' · the bench, for a seat whose work is the code: the
            ;; worktree is made before this answer leaves (R-12.29)
            bench (when-not (or halted held-by)
                    (bench-of eng gate-rpc seat change))
            ;; … and the worktree it made, on the binding: every
            ;; bench call after this one carries its repository and
            ;; its branch without the seat copying them (`bench-stamped`)
            _ (when-some [made (get bench "bench")]
                (bench-bound! eng sid (get made "repo") (get made "branch")))
            ;; i''' · and the change beside the walk, for a walk whose
            ;; rows are NOT changes: the row the submit door is on,
            ;; read as the sitter so its doors are the seat's own
            said (when (and change walk
                            (not (contains? mcp-bench/bench-walks
                                            (str (get walk "kind")))))
                   (change-said eng call sitter-sees change))
            ;; i'''' · the app's own sections, after the walk is chosen
            ;; (`sit-sections-key`): nothing for a seat at a wall
            sections (when-not (or halted held-by) (app-sections eng seat walk))
            ;; i''''' · an org mayor's first sit leads with the org's
            ;; live line and what waits on its person (ticket 9b16b765)
            org-live (when-not (or halted held-by resit)
                       (org-live-of eng seat sitting now))]
        ;; j · what the firing reads next. The walk rides as the wire
        ;; wrote it — a route's own document, string keys and all —
        ;; so the kebab→snake boundary cannot rewrite a key inside a
        ;; row's values on the way out (the summary section's rule).
        (result
         (j/write-value-as-string
          (merge
           sections
           (cond-> (p/wire-value
                   {:seat named
                    :sitter sitter-id
                    :model model
                    :grant (:id grant)
                    :sitting (:id sitting)
                    ;; R-10.8: the hook learns the mode from the sit's
                    ;; answer, and it is what decides whether a Stop
                    ;; closes or tallies
                    :mode (or (some-> (get-in seat [:data :mode]) str not-empty)
                              seats/default-mode)
                    :note (str (when org-live (org-live-note org-live))
                               "You sit in `" named "`. "
                               (cond halted halted-note
                                     held-by (if taken
                                               (held-by-sat-note held-by)
                                               (held-by-note held-by))
                                     all-held? every-row-held-note
                                     walk walk-note
                                     :else no-walk-note)
                               (when (and named-held? (not all-held?))
                                 (named-row-held-note named-row))
                               (when named-withheld?
                                 (str (named-row-withheld-note named-row)
                                      (default-filter-note eng seat walk named-row)))
                               (when (get walk "judgment")
                                 judgment-walk-note)
                               (when-not (or halted held-by all-held?)
                                 (may-create-first-note eng call sitter-sees seat walk))
                               (when said change-beside-the-walk-note)
                               (when (get bench "bench") bench-filled-note))})
            transcript-key
            (assoc "transcript"
                   {"url" (transcripts/upload-url
                           (or (get-in eng [:services :transcripts :public-origin])
                               (:origin session)
                               ""))
                    "key" transcript-key})
            inbox-key
            (assoc "inbox"
                   {"url" (seats/inbox-url
                           (or (get-in eng [:services :transcripts :public-origin])
                               (:origin session)
                               ""))
                    "key" inbox-key
                    "header" seats/inbox-key-header
                    "how" seats/inbox-how
                    "client" seats/inbox-client})
            feed
            (assoc "feed"
                   {"url" (:url feed)
                    "token" (:token feed)
                    "expires_at" (:expires_at feed)
                    "note" (:note feed)})
            halted (assoc "halted" halted)
            org-live (assoc "org_live" org-live)
            walk (assoc "walk" walk)
            said (assoc "change" said)
            bench (merge bench)
            (and (nil? bench) change-note) (assoc "bench_note" change-note)
            ;; a bench that opened on a change still stuck: the note
            ;; says why no door on it submits
            (and (some? bench) change-note) (assoc "change_note" change-note)))
          verbatim-mapper))))))

(def ^:private bodies
  {"waymark_discover" discover
   "waymark_schema" kind-schema
   "waymark_query" query
   "waymark_get" get-row
   "waymark_invoke" invoke
   "waymark_pursue" pursue
   "waymark_history" history
   "waymark_resolve" resolve-rows
   "waymark_in_flight" in-flight-read})

(defn- attempt
  "One tool body, run behind the refusal boundary: a tagged problem —
  the engine's own refusal, the gate core's 403/404, a dark Gate's
  502 — comes back as tool output with isError set, and anything
  else is logged and answered as an anonymous 500 problem."
  [tool-name thunk]
  (try
    (thunk)
    (catch Exception e
      (if (p/problem? e)
        (refusal e)
        (do (binding [*out* *err*]
              (println "waymark10 mcp tool" tool-name "failed -" (ex-message e)))
            (with-meta (value-result {:type (str p/base-uri "internal-error")
                                      :title "Internal error"
                                      :status 500}
                                     true)
              {::status 500}))))))

;; the power answer's shape, its byte counts and its refusal test live
;; in mcp.gate (ticket 5ef47ef7)
(def ^:private result-bytes mcp-gate/result-bytes)

(def ^:private shaped mcp-gate/shaped)

(def ^:private seat-byte-ceiling mcp-gate/seat-byte-ceiling)

;; the stamp a bench call wears, the inbox hint, the closed-sitting
;; refusal and the served counters live in mcp.served (ticket 70629989)
(def ^:private bench-stamped mcp-served/bench-stamped)

(def ^:private rig-dropped mcp-gate/rig-dropped)

(def ^:private dropped-bytes mcp-gate/dropped-bytes)

;; ── the app tools' bodies (docs/spec-mcp-apps.md § 1–3) ─────────────

;; The rest of them, with the ticket they sign and read, live in
;; mcp.surface (ticket 70629989). waymark_show stays here, beside the
;; `get-row` it reads through.

(defn- show
  "waymark_show: the row through the real route as the session's OWN
  principal, so it shows nothing the agent could not already read."
  [eng call session {:keys [kind id]}]
  (let [r (get-row eng call session {:kind kind :id id :return "summary"})]
    (cond-> r
      (not (:isError r))
      (update :content conj
              {:type "text"
               :text (str "This row is in front of your person now. The tap is "
                          "theirs; read the row again to see what they chose.")}))))

(def ^:private app-bodies mcp-surface/app-bodies)

(def ^:private app-tool? mcp-surface/app-tool?)

(def ^:private with-inbox-hint mcp-served/with-inbox-hint)

(defn call-tool
  "One `tools/call`. `call` is a `door` for this engine; `gate-rpc`
  is a gate-proxy caller for this engine (the four-arg arity builds
  one from the engine opt for callers outside the transport);
  `session` is {:principal :visibility}, resolved by whichever
  transport let the caller in.

  The two power tools are the second surface (waymark-q95,
  waymark-912p): `waymark_powers` answers gate-proxy's affordance
  document for the session's visibility, and `waymark_power`
  dispatches its named tool to `gate/invoke-for` wearing that
  visibility, answering Gate's CallToolResult VERBATIM — the grant
  judged in-process before any wire, a refusal (ungranted 403, a
  tool outside the policy 404) arriving as isError tool output like
  every other refusal here. They take the Gate caller, which is why
  they are dispatched here rather than from `bodies`.

  VERBATIM, UNLESS THE CALLER ASKED FOR LESS (waymark-fp62.7.16):
  `text_only` and `max_chars` shape the text parts on the way
  through, and `shaped` records what it removed for the sitting's
  counter. A call that names neither is the pass-through it always
  was.

  A refusal the engine raised comes back as tool output with isError
  set — never as a protocol error, because an agent learns from a
  refusal and learns nothing from a fault. An unknown tool name is
  the one exception the MCP spec itself makes: it is a protocol
  error, and the caller sees it as one."
  ([eng call session tool-name args]
   (call-tool eng call (gate/rpc-of eng) session tool-name args))
  ([eng call gate-rpc session tool-name args]
   (cond
     (contains? bodies tool-name)
     (attempt tool-name
              #((get bodies tool-name) eng call session (or args {})))

     (= "waymark_powers" tool-name)
     (attempt tool-name
              #(value-result (gate/affordances-for gate-rpc (:visibility session))))

     (= "waymark_power" tool-name)
     (attempt tool-name
              #(let [;; THE NAME FIRST (waymark-fp62.6.3.12): a seat may
                     ;; spell a one-tool power's token, and the stamp
                     ;; and the cap below must see the TOOL it means
                     tname (gate/tool-name-of eng (str (:tool args)))
                     ;; the caller's arguments, capped to the seat's
                     ;; ceiling and stamped with the office
                     sent (bench-stamped
                           eng session tname
                           (gate/bench-capped (or (:arguments args) {}) tname
                                              (seat-byte-ceiling session)))
                     ;; WHO IS CALLING, and from which office
                     ;; (waymark-fp62.10.2, R-14). A tool whose powers
                     ;; entry says `approval person` is held rather
                     ;; than forwarded, and the row it mints names
                     ;; both: the caller, because the first wall on
                     ;; answering is `not the caller`, and the
                     ;; sitting, so a seat's ledger and its wake read
                     ;; the call in the session it was made in. The
                     ;; door itself is a function of a grant and a
                     ;; call, and a session is neither, so the stamp
                     ;; rides in from here, exactly as the bench's
                     ;; office does.
                     who {:caller (:id (:principal session))
                          :principal (:principal session)
                          :sitting (bound-sitting
                                    eng (:mcp-session-id session))}]
                 (if (some? (:at args))
                   ;; `at`: the call is stored for its time, not made
                   ;; (R-7.2, R-4.4)
                   (schedule-call eng call session {:tool tname}
                                  (assoc (select-keys args (conj schedule-keys :at))
                                         :input sent))
                   (-> (gate/invoke-for gate-rpc (:visibility session) tname
                                        sent who)
                       (shaped args)
                       (rig-dropped)))))

     ;; the sit needs the Gate caller too, for the bench it prepares
     ;; (R-12.29) — the one body that is not in `bodies`
     (= "waymark_sit" tool-name)
     (attempt tool-name #(sit eng call gate-rpc session (or args {})))

     ;; the one model-visible app tool. It runs as the session's own
     ;; principal, so it is walled and counted like every tool above
     (and (= "waymark_show" tool-name) (app-session? eng session))
     (attempt tool-name #(show eng call session (or args {})))

     :else ::unknown-tool)))

;; ── the JSON-RPC message layer ──────────────────────────────────────
;;
;; MCP over JSON-RPC 2.0, as a function of ONE PARSED MESSAGE. No
;; transport lives here: waymark10.server.routes.mcp wraps this in
;; Streamable HTTP, and a stdio server for a local agent is the same
;; fn with read-line around it. Keeping the two apart is the whole
;; reason the tool layer above never sees a ring request either.

(def ^:private method-not-found -32601)
(def ^:private invalid-request -32600)

(defn- rpc-error [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- rpc-result [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- initialize
  "The handshake. A client's protocol version is echoed back when we
  know it and ours is offered when we do not — refusing an unknown
  revision would make every future MCP release a waymark outage, and
  the methods this server implements have been stable across all of
  them."
  [eng session params]
  {:protocolVersion (let [asked (:protocolVersion params)]
                      (if (contains? supported-versions asked)
                        asked
                        protocol-version))
   ;; true since the notice stream landed (routes/mcp.clj's GET): a
   ;; grant that widens or narrows mid-session pushes
   ;; notifications/tools/list_changed, and a client that believed
   ;; the list frozen would never re-list after the person's tap
   :capabilities (cond-> {:tools {:listChanged true}}
                   ;; the page is a resource, for a session with the app tools
                   (app-session? eng (merge session (app-declaration params)))
                   (assoc :resources {}))
   :serverInfo server-info
   ;; …then what each kind is for (kind-doc/purpose-lines)
   :instructions (str instructions (kind-doc/purpose-lines eng session))})

;; ── the list-changed notice ─────────────────────────────────────────
;;
;; The notice, `grant-moved-for?`, and the counters each answered call
;; runs live in mcp.served (ticket 70629989). These are its stubs; the
;; public ones keep `mcp/list-changed` and `mcp/grant-moved-for?` whole
;; for the transport, and `cancelled-run` stays for its test.

(def list-changed mcp-served/list-changed)
(def grant-moved-for? mcp-served/grant-moved-for?)
(def ^:private served-name mcp-served/served-name)
(def ^:private cancelled-run mcp-served/cancelled-run)
(def ^:private count-served! mcp-served/count-served!)
(def ^:private record-call! mcp-served/record-call!)
(def ^:private count-refused! mcp-served/count-refused!)
(def ^:private closed-sitting-refusal mcp-served/closed-sitting-refusal)

(defn message
  "One JSON-RPC message → the response to send, or nil when there is
  nothing to send (a notification). `session` carries the resolved
  identity every tool runs as; `gate-rpc` is this engine's Gate
  caller, built once by the transport (gate-proxy/rpc-of at route
  build) so the Gate session is reused rather than re-shaken per
  message — the four-arg arity builds one from the engine opt for a
  caller outside a transport."
  ([eng call session msg]
   (message eng call (gate/rpc-of eng) session msg))
  ([eng call gate-rpc session {:keys [id method params]}]
   (cond
     (str/blank? (str method))
     (rpc-error id invalid-request "A JSON-RPC message needs a method.")

     ;; notifications: no id, no answer. initialized is the only one a
     ;; client sends today; the rest are acknowledged by silence, which
     ;; is what the protocol asks for.
     (str/starts-with? (str method) "notifications/")
     nil

     ;; the PERSON's tap, from the page: ahead of the closed-sitting
     ;; refusal and outside the sitting's counters
     (and (= "tools/call" (str method)) (app-tool? eng session (:name params)))
     (rpc-result id (attempt (:name params)
                             #((app-bodies (str (:name params)))
                               eng call session (or (:arguments params) {}))))
     :else
     (case (str method)
       "initialize" (rpc-result id (initialize eng session params))
       "ping" (rpc-result id {})
       "tools/list"
       (rpc-result id {:tools (listing eng session)})
       ;; the page; method-not-found, as ever, without the app tools
       ("resources/list" "resources/read")
       (cond
         (not (app-session? eng session))
         (rpc-error id method-not-found (str "Method not found: " method))
         (= "resources/list" (str method))
         (rpc-result id {:resources [app-resource]})
         (= app-resource-uri (str (:uri params)))
         (rpc-result id {:contents [(assoc app-resource :text @app-page)]})
         :else (rpc-error id -32002 (str "Resource not found: " (:uri params))))
       "tools/call"
       (let [out (or (closed-sitting-refusal eng session (:name params)
                                             (:arguments params))
                     (call-tool eng call gate-rpc session
                                (:name params) (:arguments params)))]
         (if (= ::unknown-tool out)
           (rpc-error id invalid-request
                      (str "Unknown tool " (pr-str (:name params))
                           " — this engine serves exactly "
                           (mapv :name tools)
                           "; external powers go through waymark_power."))
           (let [served (served-name eng (:name params) params)]
             (count-served! eng session served out)
             (record-call! eng session served (:arguments params) out)
             (count-refused! eng session (:name params) out)
               (rpc-result id (with-inbox-hint eng session out)))))
       (rpc-error id method-not-found
                  (str "Method not found: " method))))))
