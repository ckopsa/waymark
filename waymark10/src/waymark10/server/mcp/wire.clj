(ns waymark10.server.mcp.wire
  "The plumbing every MCP tool family shares, carved out of
  waymark10.server.mcp (ticket 3a44c697) so a family can stand in its
  own namespace without requiring mcp back: the transport's session
  table, the in-process door, the tool-result shapes, the kind and
  action lookup, and the `return: summary` projection. A pure move —
  mcp keeps a private stub for each var it still calls."
  (:require [clojure.string :as str]
            [jsonista.core :as j]
            [reitit.ring :as ring]
            [waymark10.server.collections :as coll]
            [waymark10.server.invoke :as inv]
            [waymark10.server.keys :as keys]
            [waymark10.server.mcp-sessions :as sessions]
            [waymark10.server.modules-off :as modules-off]
            [waymark10.server.problems :as p]
            [waymark10.server.quiet :as quiet]
            [waymark10.server.router :as router]
            [waymark10.server.seats :as seats]
            [waymark10.wire :as wire])
  (:import (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.time Instant)))

(set! *warn-on-reflection* true)

;; ── the transport's sessions (spec-seat.md R-12.14) ─────────────────
;;
;; MCP's Streamable HTTP lets a server answer `initialize` with an
;; Mcp-Session-Id and lets a client send it back on every message
;; after. This engine did not, because nothing needed one: identity
;; rides the bearer and the surface is the grant's projection, and a
;; session id would have been state for its own sake.
;;
;; THE KEYED SITTER SESSION is what needed one. Every session of a
;; person's connector is the SAME delegate on the SAME bearer, so
;; "which of my tool's sessions is this" has no answer in the
;; credential — and `waymark_sit` has to weld a seat to ONE of them
;; and leave the person's other chats alone. The id is what it welds
;; to.
;;
;; The sessions are never law, but they are no longer ephemeral. Over
;; Postgres they live in a table both allocations of a deploy share
;; (waymark10.server.mcp-sessions), so a session minted by the old
;; process is known to the new one with its binding intact, and no
;; deploy answers a live client 404. Over the in-memory twin they stay
;; the atom on the engine they always were. Either way a client whose
;; id is truly gone is told to initialize again — which is exactly what
;; the protocol's 404 means — and eviction is lazy and rides the
;; traffic, so a process nobody talks to holds nothing open.

(def session-ttl-seconds
  "How long an MCP session lives past its last message: eight hours,
  one working day. A Routine's firing lasts minutes and a person's
  chat lasts an afternoon; anything still here after eight idle hours
  is a client that went away without saying so."
  (* 8 3600))

(defn new-session-id
  "128 bits of real randomness, base64url, unpadded (`keys/mint`) — the
  id shape MCP's own examples use, and the one a header carries
  unescaped."
  []
  (keys/mint))

(defn evict
  "The sessions map minus everything untouched for longer than the
  TTL. Runs inside every swap, so the sweep is the traffic."
  [m ^Instant now]
  (let [cutoff (.minusSeconds now session-ttl-seconds)]
    (into {}
          (remove (fn [[_ e]] (neg? (compare (:touched e) cutoff))))
          m)))

(defn session-table?
  "Does this engine keep its sessions in the shared table? Only an
  engine that keeps sessions at all (the atom is its sign) and stores
  in Postgres; every other engine keeps the atom. A quiet engine
  (server/quiet) keeps the atom too: its sessions are its own, and
  the read-only pool would refuse the row."
  [eng]
  (and (some? (:mcp-sessions eng))
       (not (quiet/quiet? eng))
       (sessions/postgres? (:storage eng))))

(defn ttl-cutoff ^Instant [^Instant now]
  (.minusSeconds now session-ttl-seconds))

(defn session-now
  "Transport time: the wall clock, never the demo clock. A shifted
  engine (clock-shift) moves law-facing time only; read through
  `:now-fn`, a P1D shift aged every session past the TTL and the
  transport answered 404 until the engine was taken down (ticket
  f0365a18). `:recording-clock` is the real clock where a demo clock
  is laid over the engine, and an unshifted engine has only `:now-fn`."
  ^Instant [eng]
  ((or (get-in eng [:services :recording-clock]) (:now-fn eng))))

(defn binding-of
  "The binding on this session, or nil. A plain read — no touch,
  because the callers are not messages of their own."
  [eng id]
  (when-some [id (some-> id str not-empty)]
    (if (session-table? eng)
      (sessions/binding-of (:storage eng) id)
      (some-> (:mcp-sessions eng) deref (get id) :bound))))

(defn open-session!
  "Register a fresh session and answer its id, or nil on an engine
  that keeps none (a bare test handler built without the atom).
  `declared` is `app-declaration`'s map, kept on the entry."
  [eng & [declared]]
  (when-some [a (:mcp-sessions eng)]
    (let [id (new-session-id)
          now (session-now eng)]
      (if (session-table? eng)
        (sessions/open! (:storage eng) id now (ttl-cutoff now) declared)
        (swap! a (fn [m]
                   (assoc (evict m now) id
                          {:created now :touched now :bound nil
                           :app-ui (boolean (:app-ui declared))
                           :client-name (:client-name declared)
                           :client-version (:client-version declared)
                           :client-caps (:client-caps declared)}))))
      id)))

(defn touch-session!
  "The entry this id names, its `:touched` moved to now — or nil when
  the id names nothing here. nil is the whole of the 404 the transport
  answers: an unknown id and an expired one say the same thing, and
  the remedy for both is to initialize again."
  [eng id]
  (when-some [a (:mcp-sessions eng)]
    (when-some [id (some-> id str not-empty)]
      (let [now (session-now eng)]
        (if (session-table? eng)
          (sessions/touch! (:storage eng) id now (ttl-cutoff now))
          (let [m (swap! a (fn [m]
                             (let [m (evict m now)]
                               (cond-> m
                                 (contains? m id) (assoc-in [id :touched] now)))))]
            (get m id)))))))

(defn bind-session!
  "Weld a seat's sitter to this session (R-12.15). Idempotent by
  overwrite: a second `waymark_sit` on the same session moves it to
  the seat the second key named, which is the honest reading of a
  person handing a Routine a new key."
  [eng id binding]
  (when-some [a (:mcp-sessions eng)]
    (when-some [id (some-> id str not-empty)]
      (if (session-table? eng)
        (sessions/bind! (:storage eng) id binding)
        (swap! a (fn [m]
                   (cond-> m
                     (contains? m id) (assoc-in [id :bound] binding)))))
      binding)))

(defn bound-sitting
  "The sitting this session is bound to, or nil. A plain read of the
  binding — no touch, because the counter that calls it is not a
  message of its own."
  [eng id]
  (some-> (binding-of eng id) :sitting))

(defn calling-sitting
  "The id of the sitting a call is served, tallied and refused as
  (ticket f6c8d5ce): the one THIS connector session is bound to, open
  or closed — never a sibling that happens to be the newest open
  sitting under the seat's shared grant. Only a session bound to no
  sitting falls back to the open one under its grant."
  [eng session]
  (if-some [bound (some-> (bound-sitting eng (:mcp-session-id session))
                          str not-empty)]
    bound
    (when-some [gid (get-in session [:visibility :grant :id])]
      (some-> (seats/open-sitting-for-grant eng gid) :id str))))

(defn bound-elsewhere
  "Which of `sitting-ids` another live connector session is bound to,
  as a set: a sitting some run is using, even one its harness never
  stamped (ticket 5d4bbab6)."
  [eng id sitting-ids]
  (let [id (some-> id str not-empty)]
    (cond
      (empty? sitting-ids) #{}
      (session-table? eng)
      (sessions/bound-elsewhere (:storage eng) id sitting-ids
                                (ttl-cutoff (session-now eng)))
      :else
      (let [wanted (set (map str sitting-ids))]
        (into #{}
              (comp (remove (fn [[k _]] (= k id)))
                    (keep (fn [[_ e]] (some-> e :bound :sitting str)))
                    (filter wanted))
              (some-> (:mcp-sessions eng) deref (evict (session-now eng))))))))

(defn live-sessions
  "The hashes of every connector session heard from since `since`, as
  a set: the sessions whose claim on a sitting still stands (ticket
  7496403e). A plain read — no touch."
  [eng ^Instant since]
  (if (session-table? eng)
    (sessions/live-hashes (:storage eng) since)
    (into #{}
          (comp (remove (fn [[_ e]] (neg? (compare (:touched e) since))))
                (map (comp sessions/id-hash key)))
          (some-> (:mcp-sessions eng) deref))))

(defn bound-seat
  "The seat this session is bound to, or nil. Read from the BINDING
  and not from the sitting row: `waymark_sit` writes the seat and the
  sitting onto the binding in one breath (they cannot disagree), and a
  row load per bench call would be a read the office already knows the
  answer to."
  [eng id]
  (some-> (binding-of eng id) :seat))

(defn bench-bound!
  "Put the worktree the sit prepared on this session's binding: the
  repository and the branch `bench-of` answered, so every bench call
  after the sit may leave them out.

  ON THE BINDING, NOT BESIDE IT. A lost bind (R-12.16) loses the bench
  with it, and a second sit overwrites the whole binding, so a stale
  worktree can never be filled into a call the new sitting makes. A
  session with no binding is left untouched: there is no sitting for
  the bench to belong to."
  [eng id repo branch]
  (when-some [a (:mcp-sessions eng)]
    (when-some [id (some-> id str not-empty)]
      (if (session-table? eng)
        (sessions/bench! (:storage eng) id repo branch)
        (swap! a (fn [m]
                   (cond-> m
                     (some? (get-in m [id :bound]))
                     (assoc-in [id :bound :bench] {:repo repo :branch branch}))))))))

(defn bound-bench
  "The worktree this session's sit prepared, {:repo :branch}, or nil."
  [eng id]
  (some-> (binding-of eng id) :bench))

;; ── the in-process door ─────────────────────────────────────────────

(defn door
  "Core's routes as a function of one ring request, built once per
  engine: the wire the six tools speak, minus the socket.

  It wears the refusal boundary (`router/wrap-problems`) so a handler's
  tagged problem arrives as the RFC 9457 response a real client would
  have received, and it deliberately does NOT wear `wrap-identity` —
  the identity on the request is the one the outer HTTP boundary
  already resolved, and re-resolving it here from headers a tool
  composed would be a door into somebody else's session.

  It wears `router/wrap-refusals-counted` too (R-10.6,
  waymark-fp62.7). The counter reads `:waymark10/visibility`, and
  `request` writes the session's resolved visibility onto every
  request this door serves — the seat grant itself for a bound
  session. So a 409 at this door counts one refusal on the open
  sitting, exactly as a 409 at an HTTP door does. It is mounted INSIDE
  `wrap-problems`, because it counts the thrown problem and lets the
  boundary project it."
  [eng]
  (router/wrap-problems
   (router/wrap-refusals-counted
    (ring/ring-handler
     (ring/router (router/assemble-routes eng nil) {:conflicts nil})
     (fn [_]
       (p/->response (p/problem :not-found 404 "Not found"
                                {:detail "No such route."}))))
    eng)))

(defn request
  "One ring request wearing the session's already-resolved identity."
  [session method uri {:keys [query body headers conds]}]
  (cond-> {:request-method method
           :uri uri
           :headers (or headers {})
           :waymark10/principal (:principal session)}
    (:visibility session) (assoc :waymark10/visibility (:visibility session))
    ;; the sitting this session is bound to rides every door call, so
    ;; the router counts a transition on THIS sitting (ticket f6c8d5ce)
    (:sitting session) (assoc :waymark10/sitting (:sitting session))
    ;; waymark_invoke's `why` (ticket 1d8f5f1c): the router judges it
    ;; and the log entry keeps it
    (:why session) (assoc :waymark10/why (:why session))
    query (assoc :query-string query)
    ;; store conds a collection read adds to its own (`walk-of`)
    conds (assoc :waymark10/extra-conds conds)
    body (assoc :body (wire/write-json body))))

(defn body-text [resp]
  (let [b (:body resp)]
    (cond (nil? b) "" (string? b) b :else (slurp b))))

(defn body-json [resp]
  (let [s (body-text resp)]
    (when-not (str/blank? s)
      (try (wire/read-json s) (catch Exception _ nil)))))

(defn enc ^String [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn query-string [params]
  (when (seq params)
    (str/join "&" (map (fn [[k v]] (str (enc k) "=" (enc v))) params))))

;; ── tool results ────────────────────────────────────────────────────

(def not-found-hint
  "The second content block EVERY 404 carries, word for word the same
  whether the kind is unknown, the row is gone, the action is not
  declared, or the caller was simply not granted it. The engine
  conceals rather than refuses (router.clj's standing rule, byte-pinned
  in batch_b_mint_test's concealment-404), so the problem document
  cannot say which of those it is — and this block does not try. It
  says only what a competent agent would do next if it EXPECTED the
  thing: ask — now, anchored, and for everything at once
  (waymark-r1m7). One sentence on every 404 leaks nothing about any
  one kind; a sentence on SOME 404s would be the hint the pin forbids."
  (str "Not found is also what a kind, row or action outside your grant "
       "answers: this engine conceals what you were not granted rather "
       "than refusing it, and cannot tell you which of the two this is. "
       "If you expected to find it, file an approval_request NOW rather "
       "than reporting that you cannot (waymark_invoke with kind "
       "\"approval_request\", action \"create\", no id: a `task` sentence "
       "saying what the access is for, a `scope` listing each kind — or "
       "dotted power token — and the actions you need, [] for read-only, "
       "and `grant_id` set to the grant you are wearing so the approval "
       "WIDENS it instead of minting a replacement). waymark_discover's "
       "doors.ask names that anchor and the powers; ask for everything "
       "the task needs in one request, and a person approves it."))

(defn result
  "A tool's answer: one text block carrying JSON, and whether it is a
  refusal. The text is the wire's own bytes wherever there are wire
  bytes to pass through — a refusal an agent reads here is
  character-for-character the refusal it would have read over HTTP.
  A 404 carries `not-found-hint` as a SECOND block, after the
  untouched problem document: the remedy the concealed door cannot
  name, said the same way for every not-found there is.

  The status rides out as METADATA under `:waymark10.server.mcp/status`
  when there is one, never on the wire: the refusal counter reads it
  to tell the engine's refusal from a wire failure (`count-refused!`)."
  ([text] (result text false))
  ([text error?] (result text error? nil))
  ([text error? status]
   (cond-> {:content (cond-> [{:type "text" :text text}]
                       (and error? (= 404 status))
                       (conj {:type "text" :text not-found-hint}))
            :isError error?}
     status (with-meta {:waymark10.server.mcp/status status}))))

(defn value-result
  ([v] (value-result v false))
  ([v error?] (result (wire/write-json (p/wire-value v)) error?)))

(defn pass-through
  "A route's answer as a tool result: 2xx is content, anything else is
  a refusal carrying the engine's own problem document."
  [resp]
  (let [status (:status resp 500)]
    (result (body-text resp) (not (<= 200 status 299)) status)))

(defn refusal
  "A problem this namespace raises in its own voice — the confirm gate
  (single and per item), the unknown-kind lookup, a `return` outside
  its enum, and an invoke that names one row and many at once; nothing
  else."
  [e]
  (let [resp (p/->response e)]
    (result (body-text resp) true (:status resp))))

;; ── kind and action lookup ──────────────────────────────────────────

(defn rdef-of
  "The declaration for a kind named by an agent. Absence is the same
  404 the plural door answers — an unknown kind and a kind this engine
  does not serve are one sentence, as they are everywhere else."
  [eng kind]
  (or (get (inv/resources eng) (keyword kind))
      ;; …except a kind the :modules-off setting switched off, which
      ;; says so
      (when (modules-off/off? eng kind)
        (throw (modules-off/refusal kind)))
      (throw (p/not-found "kind" (str kind)))))

(defn declared-action
  "The DECLARED action keyword for a name an agent typed. Both
  spellings are accepted: `.well-known` advertises action names
  verbatim (`mark-done`) while an envelope's own `actions` map crosses
  the wire snake (`mark_done`), and an agent that reads one and types
  the other has made no mistake worth a refusal."
  [rdef action]
  (let [wanted (p/wire-key (keyword action))]
    (some (fn [a] (when (= wanted (p/wire-key a)) a))
          (concat (keys (:actions rdef)) (:create-action-names rdef)))))

(defn collection-door
  "The kind's safe collection door an agent named, in either spelling
  (`declared-action`), as the collection document advertises it."
  [rdef action]
  (let [wanted (p/wire-key (keyword action))]
    (some (fn [[dname door]] (when (= wanted (p/wire-key dname)) door))
          (coll/collection-doors rdef))))

(defn wire-action
  "The action keyword as it comes back out of a parsed envelope."
  [aname]
  (keyword (p/wire-key aname)))

;; ── return: summary — the envelope, projected ───────────────────────
;;
;; Everything in this section reads a route's answer AS THE WIRE
;; CARRIES IT — string keys, never keywordized — and writes the
;; projection back out the same way. That is not fussiness: the
;; kebab→snake boundary (`p/wire-value`) rewrites a hyphen in any map
;; key, and a facet count keyed by a date, or a free-form data map a
;; person keyed by hand, would come back altered. A projection that
;; changed a value the envelope carried would be a second, quietly
;; divergent wire, which is the one thing this namespace exists not
;; to be.

(def verbatim-mapper
  "Jackson as the wire boundary configured it, minus keywordizing:
  decimals stay exact, keys stay the strings the route wrote."
  (j/object-mapper {:bigdecimals true}))

(defn verbatim-json
  "A route's JSON body with its keys untouched, or nil when the body is
  empty or not JSON — the summary projection then falls back to the
  bytes themselves."
  [resp]
  (let [s (body-text resp)]
    (when-not (str/blank? s)
      (try (j/read-value s verbatim-mapper) (catch Exception _ nil)))))

(defn return-of
  "The `return` argument, validated: nil/\"envelope\" → :envelope,
  \"summary\" → :summary, anything else a 422 in this namespace's own
  voice — the enum is on the tool's input schema, but a client that
  skipped the schema deserves the sentence rather than a silent
  envelope it did not ask for."
  [args]
  (let [r (:return args)]
    (case (some-> r str)
      (nil "envelope") :envelope
      "summary" :summary
      (throw (p/problem :invalid-argument 422 "Invalid argument"
                        {:detail (str "return must be \"envelope\" or \"summary\", not "
                                      (pr-str r) ".")
                         :argument "return"
                         :given r
                         :enum ["envelope" "summary"]})))))

(defn id-of-self
  "A row's id, read off its own `self` — the envelope carries the
  address, not the id, and the last segment of `/api/{plural}/{id}` is
  the id the tools take."
  [self]
  (when (string? self)
    (URLDecoder/decode ^String (last (str/split self #"/")) "UTF-8")))

(defn collection-doc?
  "A collection envelope: data.items is the page."
  [doc]
  (and (map? doc) (sequential? (get-in doc ["data" "items"]))))

(defn row-doc?
  "A row envelope at any depth — full, ?depth=summary, or the
  rows=none stub — as against a dry-run verdict, a report, a job."
  [doc]
  (and (map? doc)
       (= "10" (get doc "waymark"))
       (string? (get doc "self"))
       (contains? doc "state")
       (contains? doc "summary")
       (not (collection-doc? doc))))

(defn row-summary
  "One row, projected: id, kind, state, the summary line, and its
  values — `data` where the envelope carried data (a full read, an
  invoke's answer), else `fields` (a collection item's grid columns,
  which is all a depth=summary item ever carries), else neither (the
  rows=none stub). Absent stays absent: a field the projection hid
  is not here because it was not there."
  [env]
  (cond-> {"id" (id-of-self (get env "self"))
           "kind" (get env "kind")
           "state" (get env "state")
           "summary" (get env "summary")}
    (contains? env "data") (assoc "data" (get env "data"))
    (and (not (contains? env "data")) (contains? env "fields"))
    (assoc "fields" (get env "fields"))
    ;; what the row waits on (ticket 86af9d88): a full row read carries
    ;; it; a collection item and an action's answer do not
    (contains? env "waiting_on") (assoc "waiting_on" (get env "waiting_on"))))

(defn facets-of
  "The collection's facet counts, lifted out of the query action's
  input schema where `collections/splice-facets` put them — the one
  thing from the actions block a scanning reader still wants."
  [doc]
  (not-empty
   (into {}
         (keep (fn [[f prop]]
                 (when-some [counts (get prop "x-facets")] [f counts])))
         (get-in doc ["actions" "query" "input" "properties"]))))

(defn collection-summary
  "The page, projected: each item through `row-summary`, then total,
  page, the next/prev hrefs and the facets. The query action's input
  schema — the filter grammar an agent learns once — is what this
  leaves behind."
  [doc]
  (let [data (get doc "data")
        links (get doc "links")]
    (cond-> {"kind" (get doc "kind")
             "summary" (get doc "summary")
             "items" (mapv row-summary (get data "items"))
             "total" (get data "total")
             "page" (get data "page")}
      (get-in links ["next" "href"]) (assoc "next" (get-in links ["next" "href"]))
      (get-in links ["prev" "href"]) (assoc "prev" (get-in links ["prev" "href"]))
      (facets-of doc) (assoc "facets" (facets-of doc)))))

(defn changed-fields
  "The data keys whose value differs between the row as read and the
  row as answered — both the wire's encoding, so equal is equal. A
  create has no before: every present, non-null field changed."
  [before after]
  (let [bd (get before "data") ad (get after "data")]
    (->> (concat (keys bd) (keys ad))
         distinct
         (filter (fn [k] (not= (get bd k) (get ad k))))
         sort
         vec)))

(defn invoke-summary
  "The moved row through `row-summary`, plus what the move was:
  `transition` {action from to} — `from` read off the row BEFORE the
  invoke (the same read the confirm gate and the ETag come from), so
  it is the state the agent saw, not a reconstruction — and `changed`,
  the data fields whose values differ. A create carries no from."
  [aname before after]
  (assoc (row-summary after)
         "transition" (cond-> {"action" (p/wire-key aname)
                               "to" (get after "state")}
                        (get before "state") (assoc "from" (get before "state")))
         "changed" (changed-fields before after)))

(defn answer
  "A route's answer under the caller's `return`: the envelope's own
  bytes (:envelope, and every non-2xx — a refusal is never
  summarized), or `project` over a verbatim reading of a 2xx body.
  `project` answers nil for a document it does not recognize — a
  dry-run verdict, a stored replay of something else, a job — and
  the bytes pass through unchanged rather than half-projected."
  [resp return project]
  (if (and (= :summary return) (<= 200 (:status resp 500) 299))
    (if-some [s (some-> (verbatim-json resp) project)]
      (result (j/write-value-as-string s verbatim-mapper))
      (pass-through resp))
    (pass-through resp)))
