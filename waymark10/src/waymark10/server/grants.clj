(ns waymark10.server.grants
  "Grants: least-privilege agent links (waymark9 server/grants.py,
  phase 9a's deliverable widened by batch B). A grant is an ordinary
  resource — {audience, scope, expires_at} through offered → accepted
  → revoked/expired — and its enforcement is RENDERING AT THE SOURCE:
  a request that presents X-Waymark-Grant sees only the granted
  surface. Non-granted kinds 404, non-granted actions are absent from
  envelopes (never narrated as unavailable) and 404 when invoked —
  concealment, waymark9's default-deny-is-rendering discipline.

  The visibility is resolved ONCE per request at the router's
  identity boundary (judgment-style: what this request may see is
  fixed before any handler runs) and rides the request as a closure
  map {:kind? :row? :action? :field? :arg? :ids-of}. A
  presented-but-dead grant (unknown id, wrong audience, unaccepted,
  revoked, expired) scopes to NOTHING — dead means scoped-to-nothing,
  never a fall-through to the full surface (waymark9's dead_grant
  law).

  ── batch B: field/argument modes, the negotiation machine, the
     own-grant surface ────────────────────────────────────────────────

  FIELD MODES. A scope entry may carry {:fields {:mode allow|deny
  :names […]}} — an allow-list renders only the named fields, a
  deny-list renders everything but. Entries sharing a kind union
  their admissions (a field visible under any entry is visible); an
  entry WITHOUT :fields leaves the kind's fields unrestricted, and
  that openness absorbs any sibling's narrowing — the same absorption
  rule ids follow. A redacted field is ABSENT: from data, from the
  published schema view (/api/schemas/{kind}), from part items, from
  links that draw on it (badge, edge params, href templates), and
  from the summary (see render's honesty-trap note). Never narrated.

  ARG MODES. {:args [{:action … :mode … :names […]}]} narrows the
  arguments of a granted action, per entry. Advertised input schemas
  lose denied properties (their folded enums with them); a denied arg
  arriving in a body — dry-run included — answers the SAME 422 an
  unknown field draws (check-args!), so a probing client cannot
  distinguish 'denied to you' from 'never existed'. Recorded spelling
  deviation: the wire shape is a vector of {action, mode, names}
  entries, not waymark9's kind→action→arg→mode nesting — a schema the
  fingerprint already knows how to hash.

  THE NEGOTIATION MACHINE (waymark9's request_access, resized to the
  v10 grant): an :approval_request resource — {grant_id?, task,
  scope, expires_at} through offered → approved/denied. Since
  waymark-442.6 it is the first INSTANCE of the :decision declaration
  key (spec-decision-kind) rather than a machine written by hand: the
  states, the two verdict actions, the four-eyes wall, the requester
  stamp, the default leash and the queue's own filter and sort are
  projected from that one key. It kept its fingerprint hash to the
  byte, which is the proof the key is a spelling and not a mechanism. ANY named
  (non-anonymous) principal may file one — the grant anchor is
  optional, because the machine's original purpose is bootstrap: an
  agent asks for the access it needs, a human approves, and the
  approval MINTS the grant. The approver is four-eyes'd from the
  requester by guard; approve's post-commit effect
  (approval-effects!, the router's one grants seam — the attachments
  put-bytes! precedent) lands the grant, system actor, logged,
  idempotency-keyed on the approval id so redelivery is a replay:
  an ANCHORED ask (grant_id names a grant its requester holds)
  EXTENDS that grant through the concealed :extend transition; an
  ANCHORLESS ask mints a fresh grant — audience = requester, scope =
  exactly the approved ask, accepted through the machine's own
  accept (the ask WAS the audience's consent), its id stamped onto
  the ask at the verdict so the requester can read where to go. Deny
  records the note and no grant moves or exists — the 404s persist.

  THE OWN-GRANT SURFACE: every scoped request by a named principal
  carries the negotiation surface — :grant and :approval_request join
  its kinds whether the presented grant is live, dead, foreign or
  unknown (the asking door is never concealed; it is how access
  starts and how a dead grant's holder asks again). Rows stay gated
  per row (audience / requested_by = self), collections narrowed by
  the same visibility cond every id-scoped grant uses (ids-of queries
  own ids; no special route). GET-only besides
  approval_request/create: no grant or approval action is granted, so
  envelopes render with empty action maps — concealment discipline
  unchanged.

  ── the seat grant (docs/spec-seat.md § 5) ──────────────────────────

  A GRANT MAY CITE A SEAT INSTEAD OF CARRYING A SCOPE. `seat` and
  `substitute` are the two fields (R-5.1), and a grant that cites one
  holds no scope of its own: `visibility` resolves the seat row AT
  EACH REQUEST (R-5.2) — three walls, then the seat's scope minus a
  substitute's drop list minus the sweep's stale list — so a `restate`
  of the seat moves every sitter's leash with no new grant and nothing
  copied. That is the answer to the drift on record in spec-seat § 3:
  a stored scope is a COPY, and every copy in this file's history
  rotted. The ask spells the seat's NAME and the mint resolves it to
  the ref (R-5.4); an extend on a seat grant slides the expiry and
  nothing else (R-5.3).

  Recorded deviations and named punts (each a sentence):
  - approver-edited scope maps and review-note round-trips are
    unported: v10's approve grants the ask as-is; the send-back is
    deny with a note, and a new ask is a new approval_request.
  - waymark9's attenuation ceiling (the approver's own live
    visibility intersected onto the holder's) is unported — v10 has
    no per-member visibility to intersect; grants.py's ceiling is the
    live kind, not a max-grantable check, so nothing simpler stands
    in for it.
  - an anchored ask's approval extends the REQUEST'S named grant
    rather than minting a sibling: the holder already carries the
    link. The anchorless mint is the bootstrap path, not a widening
    path — a holder widening scope still extends.
  - an ungranted kind's 404 never grows a request-access remedy —
    a remedy would leak existence. Discoverability is the negotiation
    surface riding every named principal's scoped request instead;
    the agent asks for what it believes exists (the 404 bytes are
    pinned in batch_b_mint_test).
  - anchorless creates are paced (20/hour/principal) and open asks
    capped (10/requester), both guards reading the requester's own
    rows through ctx :find — the guards/rate-limit builder wants the
    engine's :rate hook, which v10 never wired (recorded). Past the
    500th lifetime ask the pace window reads a stale oldest-first
    page (query-rows' one ordering); the open cap, whose churn needs
    a human verdict per ask, is the standing wall.
  - the approve effect rides the wire boundary (the router calls
    approval-effects! post-commit); an engine-internal invoke of
    approve does not extend — the attachments discipline, recorded.
  - field modes are the v10 pair allow/deny; waymark9's hashed mode
    (render the sha, not the value) is unported.
  - field granularity is the top-level data field: item fields inside
    a part array are not separately gradable — redact the array.
  - a granted action's guard NARRATION may name a redacted field's
    value in its deny vars — unavailable reasons are not projected
    (named punt; the summary and data are).
  - create-argument modes are unported: :args grades declared
    actions; the create body is judged by the create schema alone.
  - grant projection of SSE, surfaces, openapi and collab stays the
    phase-9b named punt — those routes still answer a scoped request
    404 (their modules are other batches' files this run).
  - collection query/create input schemas and facet counts
    (server/collections.clj) are unprojected — a scoped collection
    still advertises the kind's full filter grammar (named punt, not
    this batch's file); items themselves project fully.
  - the token IS the grant id carried in X-Waymark-Grant (waymark9
    minted an opaque wmk_ bearer token); the requesting principal
    must be the grant's audience, so the header is a scope selector,
    not a credential.
  - expiry is enforced live (an accepted grant past expires_at scopes
    to nothing), and the one expiry sweep (waymark10.server.expiry)
    walks the :expire door once the clock passes, as :expires declares.
  - idempotency-replay responses are stored bytes rendered without a
    visibility (the phase-3 render-fn seam's recorded punt, extended):
    a scoped replay serves the first execution's unprojected envelope.
  - own-id collections cap at 200 held grants/requests per principal
    (the member-visibility page waymark9 also capped).
  - a scope entry may carry :filter {field value} — admission by
    MATCH (eq on `state`, or on a declared-filterable data field; one
    filtered entry per kind), judged at the row (row?) and ANDed
    into the collection query (conds-of), so rows minted after the
    grant land inside the leash the moment they match. The worksheet
    export consumes both halves the way the collection does
    (waymark-ecq closed); the upload half still refuses scoped
    requests — staging lands rows the uploader cannot see.
  - a grant with NEITHER a scope nor a seat is not refused: it confers
    nothing, which is a harmless thing for a row to say, and R-5.1
    asks only that the two never stand together.
  - the seat resolve's budget wall is one SUM over the seat's closed
    sittings, and no index serves it: `sum-matching` matches through
    `data->>` expressions, and this store declares indexes for
    `:unique` groups alone. A seat's sittings are a week of wakes, so
    the scan is small by construction.
  - the halt R-7.7 wants is WRITTEN BY THE ROUTER, not here: this
    namespace reads the seat and reports the wall on `:seat`, because
    `visibility` is also called by a preview and by the capability
    check, where no request met anything."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.money :as money]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            ;; hold-owner: the person an agent's mint past its leash
            ;; waits on (HC1). delegation requires nothing that
            ;; requires grants
            [waymark10.server.delegation :as delegation]
            ;; the powers vocabulary a dotted scope entry is judged
            ;; against (waymark-fp62.10.4). One-directional: mcp-servers
            ;; knows nothing of grants, so this require cannot close a
            ;; circle, and `tokens-of-rows` is pure over the page the
            ;; guard's own ctx hands it
            [waymark10.server.mcp-servers :as servers]
            ;; spellings-of: the ids a reader answers to (waymark-27j).
            ;; members requires nothing that requires grants
            [waymark10.server.members :as members]
            [waymark10.server.problems :as p]
            ;; a-manager: widening authority is a manager's (R-3.2);
            ;; the requester role unscoped-visibility narrows (R-3.4);
            ;; roles requires nothing that requires grants
            [waymark10.server.roles :as roles]
            ;; the scope schema itself: spelled where members (which
            ;; cannot require grants) can wear it on an invite too
            [waymark10.server.scope :as scope]
            [waymark10.server.postings :as postings]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── the scope schema (shared: grant, approval_request, extend, seat,
;;    and a member's invite) ─────────────────────────────────────────
;; Spelled in waymark10.server.scope, because members cannot require
;; grants and an invite's grant is a scope like any other. Named here
;; as well, so the doors that already say grants/… keep saying it.

(def field-spec-schema scope/field-spec-schema)
(def scope-example scope/scope-example)
(def filter-map-schema scope/filter-map-schema)
(def filter-spelled-by-hand scope/filter-spelled-by-hand)
(def scope-schema scope/scope-schema)

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard audience-only
  {:reads [:principal]
   :open "A grant is offered to one audience and no door re-addresses it; a caller who wants access of its own files an ask."
   :explain "Only the grant's audience may accept it."}
  [row _inp ctx]
  (let [p (:principal ctx)]
    (if (or (= :system (:type p))
            (= (:id p) (get-in row [:data :audience])))
      (t/allow) (t/deny))))

(g/defguard no-self-dealing
  {:reads [:principal]
   :open "The holder's own hand is the wall: the principal who offered the grant revokes it, and it lapses by itself at its expiry."
   :explain "A holder cannot judge its own access; someone else revokes it."}
  [row _inp ctx]
  (let [p (:principal ctx)]
    (if (and (not= :system (:type p))
             (= (:id p) (get-in row [:data :audience])))
      (t/deny) (t/allow))))

(def ^:private past-expiry
  (g/expr {:name :past-expiry
           :when '(and (is-set (data :expires_at))
                       (<= (data :expires_at) (now)))
           :open "Time clears it: the refusal carries the grant's own expiry as the moment this door opens."
           :explain "The grant has not reached its expiry."
           :becomes-available-at (fn [row] (get-in row [:data :expires_at]))}))

;; ── scope honesty (waymark-vnc): a scope names things that exist ────
;;
;; The field-test finding: an ask for meal.update_details sailed
;; through before that action existed — accepted and silently useless.
;; An authoring-time refusal is the honest answer, spelled with the
;; kind's REAL action vocabulary so the requester can respell the ask.
;; The vocabulary is the ctx :action-names hook (invoke's registry —
;; the same union of :flow-desugared :actions and the create verb that
;; check-action! enforces and well-known publishes); a ctx without the
;; hook (render's pure probe) declines rather than guesses, the
;; phase-8 discipline. Validation gates NEW asks and grants only: a
;; STORED scope naming an action that later disappeared loads and
;; boots untouched — enforcement (surface-of / :action?) simply never
;; matches it against a live route, so upgrade honesty costs nothing
;; retroactive. "actions []" stays the legal read-only ask; the KIND
;; must still exist.

(g/defguard scope-names-real-kinds
  {:judges [:scope]
   :reads [:services]
   :vars [:kind]
   :open "The legal kind names are well-known's resources, one GET away; enumerating the registry into every scope form would duplicate it."
   :explain "A scope grants kinds this surface serves; there is no kind {kind}."}
  [_row inp ctx]
  (if-some [names-of (:action-names ctx)]
    (let [find-rows (:find ctx)
          ;; the powers of every non-retired mcp_server row, read ONCE
          ;; per judgment however many dotted entries a scope carries,
          ;; and not at all when it carries none
          powers (delay
                   (set (servers/tokens-of-rows
                         (when find-rows
                           (remove #(= :retired (:state %))
                                   (find-rows :mcp_server {} {:limit 1000}))))))
          registered? (fn [k]
                        (boolean
                         (and find-rows
                              (some #(= :active (:state %))
                                    (find-rows :capability
                                               {:token k} {:limit 1})))))]
      (if-some [bad (some (fn [e]
                            (let [k (str (:kind e))]
                              (if (str/includes? k ".")
                                ;; a dotted token names a POWER
                                ;; (waymark-fp62.10.4). The rows hold
                                ;; the policy, so the rows hold the
                                ;; word: a token any non-retired
                                ;; server's powers name is real. The
                                ;; capability registry answers only
                                ;; for a token NO server names — this
                                ;; engine's own powers, today
                                ;; schedule.write — and a token
                                ;; neither knows refuses the same way
                                ;; an unknown kind does
                                (when-not (or (contains? @powers k)
                                              (registered? k))
                                  k)
                                (when (nil? (names-of k)) k))))
                          (:scope inp))]
        (t/deny {:vars {:kind bad}})
        (t/allow)))
    (t/allow)))

(g/defguard scope-names-real-actions
  {:judges [:scope]
   :reads [:services]
   :vars [:kind :action :actions]
   :open "Each kind's action vocabulary is well-known's actions list, one GET away; the refusal spells the kind's real actions when an ask misses."
   :explain "There is no action {action} on {kind}; its actions are: {actions}. (An empty actions list is the read-only ask.)"}
  [_row inp ctx]
  (if-some [names-of (:action-names ctx)]
    (if-some [bad (first
                   (for [e (:scope inp)
                         :let [known (names-of (:kind e))]
                         :when known
                         a (:actions e)
                         :when (not (contains? known (str a)))]
                     {:kind (:kind e) :action (str a)
                      :actions (str/join ", " known)}))]
      (t/deny {:vars bad})
      (t/allow))
    (t/allow)))

(defn- team-of-ref
  "The kind a :team-of `fname` names rows of — :team or :member — when it
  is one unlisted ref field of the kind `rdef` that names one of the
  two; nil otherwise."
  [rdef fname]
  (some (fn [{:keys [field kind listed]}]
          (when (and (= (str fname) (name field)) (not listed))
            (#{:team :member} (keyword (name kind)))))
        (schema/ref-fields (:schema rdef))))

(defn- team-of-key
  "One :team-of as [field team], however the wire spelled it: what makes
  two entries' team-of the same."
  [t]
  (when (map? t)
    [(some-> (:field t) name) (some-> (:team t) str)]))

(g/defguard scope-filters-are-filterable
  {:judges [:scope]
   :reads [:services]
   :vars [:kind :field]
   :open "A grant filter narrows by a field the kind already declares filterable with eq, or — for a dotted power — by a field its server's entry lists in `constraints`; both vocabularies are one GET away."
   :explain "The kind {kind} cannot be filter-scoped by {field}: a filter on a kind names `state`, or a data field the kind declares filterable (eq), a comma-separated value (any of) only on a field it declares filterable with in, and several entries may filter one kind only when one of them names an action, each admitting its own actions on its own rows; a filter on a dotted power names a field the server's powers entry lists in `constraints`, a power that lists none admits no filter at all, and only ONE entry may filter a dotted token no server's powers name; a team-of names one field that refs a team or a member, not the field its own filter names, in one entry per kind; a names-me names only fields that each ref a member."}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (let [entries (filter :filter (:scope inp))
          find-rows (:find ctx)
          ;; the rows' constraints, read ONCE per judgment and not at
          ;; all when no filtered entry is dotted — `scope-names-real-
          ;; kinds`' own arrangement, over the same rows
          allowed (delay
                    (servers/constraints-of-rows
                     (when find-rows
                       (remove #(= :retired (:state %))
                               (find-rows :mcp_server {} {:limit 1000})))))
          ;; A NARROWABLE POWER is a dotted token a non-retired
          ;; mcp_server row's powers name (waymark-fp62.6.3.5). Its
          ;; vocabulary is that entry's `constraints`, and the power
          ;; door admits a call ANY entry admits — so a grant narrows
          ;; one power to two repositories with two entries. A KIND
          ;; takes several filtered entries too (ticket d4211f9a): its
          ;; sight is the union of their filters (`conds-of` spells
          ;; the OR) and a door opens only under an entry that names
          ;; it (`door-on-row?`) — read these, act on those. Entries
          ;; that ALL name no action are still refused, as they were
          ;; before the ticket: nothing sets them apart but their
          ;; filters, and that is the scope a doubled renewal spelled
          ;; (waymark-ycp). The one-entry rule is kept for a
          ;; dotted token only the capability registry names (this
          ;; engine's own powers), whose own enforcement point reads
          ;; the first filter and must not be handed a second.
          power? (fn [e] (and (str/includes? (str (:kind e)) ".")
                              (contains? @allowed (str (:kind e)))))
          dup (->> entries (remove power?)
                   (filter #(str/includes? (str (:kind %)) "."))
                   (map :kind) frequencies
                   (some (fn [[k n]] (when (< 1 n) k))))
          idle (->> entries (remove power?)
                    (remove #(str/includes? (str (:kind %)) "."))
                    (group-by :kind)
                    (some (fn [[k es]]
                            (when (and (next es)
                                       (not-any? (comp seq :actions) es))
                              k))))
          bad (first
               (for [e entries
                     :when (not (power? e))
                     :let [rdef (rdef-of (:kind e))]
                     :when rdef
                     [f v] (:filter e)
                     :let [fname (name f)
                           ops (get (:filterable rdef) (keyword fname))]
                     ;; STATE IS FILTERABLE HERE (bead waymark-fp62.12).
                     ;; It was refused until this bead, because the
                     ;; query half and the row half both read the JSON
                     ;; document and `state` is a column — a filter
                     ;; nothing could answer. Both halves now address
                     ;; the column (`conds-of`, `row-matches?`), and a
                     ;; seat whose walk is a state needs it: the rows
                     ;; it may see are the rows it walks, and a row
                     ;; that leaves the filter leaves its sight in the
                     ;; same commit. Every kind's collection grammar
                     ;; answers `state` (collections/param-map adds it
                     ;; whatever `:filterable` says), so no
                     ;; `:filterable` entry is owed for it.
                     ;; a comma value is any of only where the field
                     ;; declares :in; elsewhere it would match one
                     ;; literal text, which is nothing, silently
                     :when (and (not= "state" fname)
                                (or (not (contains? (or ops #{}) :eq))
                                    (and (str/includes? (str v) ",")
                                         (not (contains? ops :in)))))]
                 {:kind (:kind e) :field fname}))
          bad-power (first
                     (for [e entries
                           :when (power? e)
                           :let [k (str (:kind e))]
                           [f _] (:filter e)
                           :when (not (contains? (get @allowed k) (name f)))]
                       {:kind k :field (name f)}))
          ;; :team-of (docs/spec-teams.md §6): one entry per kind, on a
          ;; kind's single ref to a team or a member, and never the
          ;; field the entry's own filter already names
          tes (filter :team-of (:scope inp))
          team-bad (or (some (fn [[k es]]
                               (when (next es)
                                 {:kind (str k) :field "(two team-of entries)"}))
                             (group-by #(str (some-> (:kind %) name)) tes))
                       (first
                        (for [e tes
                              :let [k (str (some-> (:kind e) name))
                                    fname (some-> (get-in e [:team-of :field]) name)
                                    dotted? (str/includes? k ".")
                                    rdef (when-not dotted? (rdef-of (:kind e)))]
                              :when (or dotted?
                                        (and rdef (nil? (team-of-ref rdef fname)))
                                        (contains? (into #{} (map name) (keys (:filter e)))
                                                   fname))]
                          {:kind k :field (str "team-of " fname)})))
          ;; :names-me (docs/spec-teams.md §7): each field one unlisted
          ;; ref to a member, on a kind
          names-bad (first
                     (for [e (:scope inp)
                           :when (seq (:names-me e))
                           :let [k (str (some-> (:kind e) name))
                                 rdef (when-not (str/includes? k ".")
                                        (rdef-of (:kind e)))]
                           f (:names-me e)
                           :when (or (nil? rdef)
                                     (not= :member (team-of-ref rdef (name f))))]
                       {:kind k :field (str "names-me " (name f))}))]
      (cond
        dup (t/deny {:vars {:kind dup :field "(two filtered entries)"}})
        team-bad (t/deny {:vars team-bad})
        names-bad (t/deny {:vars names-bad})
        idle (t/deny {:vars {:kind idle
                             :field "(two filtered entries, neither naming an action)"}})
        bad (t/deny {:vars bad})
        bad-power (t/deny {:vars bad-power})
        :else (t/allow)))
    ;; probe ctx carries no registry — decline to guess (phase-8)
    (t/allow)))

;; ── private, own-surface-only kinds (waymark-4zj.1): never grantable ─
;;
;; :self, :journal and :letter are OWN-SURFACE-ONLY private kinds — an
;; agent sees and edits ONLY the rows that are ITS OWN (data.owner ==
;; its principal id; a letter from EITHER end — data.owner or
;; data.to, waymark-tti.3), unscoped humans see all, and there is NO
;; grant path in between. A grant (or an ask, which MINTS a grant on
;; approval) whose scope named one of these would BYPASS own-surface:
;; row?/ids-of consult the grant surface — which carries no owner
;; filter — and would expose EVERY agent's private rows. Privacy here
;; is STRUCTURAL, not a human remembering to hand-craft an {:filter
;; {:owner …}}: these kinds are simply not shareable, so the scope
;; validator refuses any entry naming them at BOTH doors (grant create
;; AND approval_request create; the trio is shared, so the guard rides
;; :extend too — defense in depth for the approval effect's mint).
;; Own-surface (owner sees own) is untouched — it never goes through a
;; grant.
;;
;; Which kinds are private is read off the registry (waymark-ti0): each
;; declares `:own-surface {… :grantable false}`, the same declaration
;; that says who owns its rows. Core used to carry the literal set
;; #{"self" "journal" "letter"}, naming three kinds an APP declares.
(defn private-kind?
  "Does this rdef declare its own-surface the only path to its rows
  (`:own-surface {:grantable false}`)? The ephemeral surfaces ask
  (waymark-tti.3 L7): a reported presence/intent self naming one of
  these rows must pass the REPORTER's own sight, or a stranger could
  name a letter it 404s and have the frame delivered to exactly the
  two people who can read it."
  [rdef]
  (false? (get-in rdef [:own-surface :grantable])))

(g/defguard scope-omits-private-kinds
  {:judges [:scope]
   :reads [:services]
   :vars [:kind]
   :open "A private kind says so on its own declaration (:own-surface :grantable false); the refusal names the entry it refuses."
   :explain "{kind} is private to its own members and cannot be granted; the entry is refused (the kind rides the own-surface, where owner sees own, with no grant path)."}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [bad (some (fn [e]
                          (let [k (str (:kind e))]
                            (when (some-> (rdef-of k) private-kind?) k)))
                        (:scope inp))]
      (t/deny {:vars {:kind bad}})
      (t/allow))
    (t/allow)))

(g/defguard an-outside-grant-names-them
  {:judges [:scope]
   :reads [:services :member]
   :vars [:kind]
   :open "An outside party's grant is written as :names-me entries — a kind, its actions, and the fields that name the member — on kinds that are not member, team, grant or a :system kind."
   :explain "An outside party's grant admits rows that name them, and this entry admits every {kind}."}
  [row inp ctx]
  ;; docs/spec-teams.md §7: the audience's member row says it is
  ;; outside; the invite's own twin is members/an-outside-invite-names-them
  (let [aud (some-> (or (:audience inp) (get-in row [:data :audience])) str not-empty)
        m (when-some [read' (:read ctx)] (when aud (read' :member aud)))]
    (if-some [bad (when (= "outside" (str (get-in m [:data :actor_type])))
                    (members/outside-entry-bad (:rdef-of ctx) (:scope inp)))]
      (t/deny {:vars {:kind bad}})
      (t/allow))))

(g/defguard not-a-substitute
  {:reads [:principal :grant]
   :open "No door turns a substitute into the seat's full sitter mid-session; the full sitter's own session writes the memory."
   :explain "A substitute reads the seat's memory and does not write it. The seat's own sitter writes here."}
  ;; R-8.2, and the reason is CONTINUITY, not capability: the memory is
  ;; the seat's voice across sessions, and a stand-in that wrote it
  ;; would leave the next full sitter inheriting somebody else's words.
  ;; The bar is a guard rather than a scope entry because self, journal
  ;; and letter are private own-surface kinds no scope can name
  ;; (scope-omits-private-kinds, just above) — so the only fact that
  ;; distinguishes the two hands is the one the visibility carries
  ;; beside the leash. It lives HERE, not in the app that declares the
  ;; three kinds, because `substitute` is grant law and the sentence is
  ;; pinned by the spec; the apps wear it.
  ;;
  ;; Reads are untouched (R-8.3): a guard judges a write.
  [_row _inp ctx]
  (if (true? (:substitute (:grant ctx)))
    (t/deny)
    (t/allow)))

;; ── the seat grant (docs/spec-seat.md § 5) ─────────────────────────
;;
;; A SEAT GRANT CARRIES NO SCOPE. Its authority is the seat row, read
;; at EACH request (R-5.2), so a `restate` moves every sitter's leash
;; with no new grant and nothing copied — which is the whole answer to
;; the drift on record in spec-seat § 3 (74 entries for 20 kinds; a
;; stored scope naming a retired action; a default expiry copied onto
;; a grant nobody meant to shorten). What this file owns is the field
;; that cites the seat, the ask that spells its name, and the resolve.

(defn- nonblank
  "One field as a non-blank string, however the wire spelled it."
  [x]
  (some-> x str str/trim not-empty))

(defn- seat-cited
  "The seat id a grant row cites, or nil — the one test for `is this a
  seat grant`, spelled once."
  [row]
  (nonblank (get-in row [:data :seat])))

(g/defguard a-seat-or-a-scope
  {:judges [:seat]
   :open "The way out is in this same form: drop the seat or drop the scope, and no other door changes the verdict."
   :explain "A grant CITES A SEAT or CARRIES A SCOPE, never both. A seat grant's authority is the seat row, read at every request, so a scope written beside it would be a second and frozen copy of exactly the thing the seat exists to keep live — and the two would disagree the first time somebody restated the seat. Drop one."}
  [_row inp _ctx]
  (if (and (some? (nonblank (:seat inp))) (seq (:scope inp)))
    (t/deny)
    (t/allow)))

(declare surface-of visibility)

(defn- entry-within-surface?
  "Does one minted scope entry fit inside one minter grant's surface
  for its kind? Subset on actions and ids; a minter filter must ride
  the minted entry whole (equal-or-narrower — every minter pair
  present); a minter carrying field/hashed/arg restrictions on the
  kind refuses delegation of that kind outright (v1's conservative
  floor, recorded — comparing admission algebras is not this
  guard's afternoon). The minted entry's OWN extra narrowing is
  always welcome."
  [e s]
  (when-some [sk (get s (str (:kind e)))]
    (let [acts (map str (:actions e))
          rides? (fn [fm]
                   (and (:filter e)
                        (every? (fn [[f v]]
                                  (= (str v)
                                     (str (get (:filter e) (keyword (name f))))))
                                fm)))]
      (and (empty? (remove (:actions sk) acts))
           (or (nil? (:ids sk))
               (and (seq (:ids e))
                    (every? (:ids sk) (map str (:ids e)))))
           (or (nil? (:filters sk))
               (some rides? (:filters sk)))
           ;; several entries on one kind: the minted entry sits inside
           ;; ONE of them — its actions, its ids and its filter — and
           ;; never inside their union, which no entry conferred
           (or (nil? (next (:entries sk)))
               (some (fn [n]
                       (and (every? (:actions n) acts)
                            (or (nil? (:ids n))
                                (and (seq (:ids e))
                                     (every? (:ids n) (map str (:ids e)))))
                            (or (nil? (:filter n)) (rides? (:filter n)))))
                     (:entries sk)))
           ;; a minter read through :team-of hands the kind on only
           ;; under the same team-of, or under a sibling that has none
           (some (fn [n]
                   (or (nil? (:team-of n))
                       (= (team-of-key (:team-of e)) (team-of-key (:team-of n)))))
                 (:entries sk))
           (nil? (:fields sk))
           (empty? (:hashed sk))
           (empty? (:args sk))))))

(defn- leash-gap
  "The first entry of an agent's mint that no live grant it holds
  covers, as {:kind}, or nil. nil for every other principal, and for a
  probe ctx with no :find hook — decline to guess."
  [inp ctx]
  (let [p (:principal ctx)]
    (when (and (= :agent (:type p)) (:find ctx))
      (let [now (:now ctx)
            live (filter (fn [g]
                           (and (= :accepted (:state g))
                                (let [exp (get-in g [:data :expires_at])]
                                  (or (nil? exp) (neg? (compare now exp))))))
                         ((:find ctx) :grant {:audience (:id p)} {:limit 100}))
            surfaces (map surface-of live)]
        (first (for [e (:scope inp)
                     :when (not (some #(entry-within-surface? e %)
                                      surfaces))]
                 {:kind (str (:kind e))}))))))

(defn- leash-holder
  "The person an agent's mint past its leash waits on, or nil (HC1,
  ticket b83e690d): the agent's person (delegation/hold-owner — the
  seat's owner, or a delegate's person) when that person holds the
  manager role. Never for a grant whose audience is the asking agent
  itself: widening itself stays a seat restate."
  [inp ctx]
  (let [p (:principal ctx)]
    (when (not= (nonblank (:audience inp)) (nonblank (:id p)))
      (when-some [owner (delegation/hold-owner ctx)]
        (when (roles/manager?
               (assoc ctx :principal (t/principal {:id owner :display owner})))
          owner)))))

(defn- entry-said
  "One scope entry as a door a person reads: `read org_plan named
  tax_desk`, `finish ticket where repo bench`. A dotted power with no
  actions reads `use bench.edit where path ...`, never `read`: a power
  may write or run (ticket 50722c43)."
  [e]
  (let [acts (seq (map name (:actions e)))
        k (name (:kind e))
        f (:filter e)
        nm (or (get f :name) (get f "name"))
        others (dissoc f :name "name")]
    (str (cond acts (str/join "/" acts)
               (str/includes? k ".") "use"
               :else "read")
         " " k
         (when (nonblank nm) (str " named " nm))
         (when (seq others)
           (str " where " (str/join ", " (map (fn [[k v]] (str (name k) " " v))
                                              (sort-by (comp name key) others)))))
         (when (seq (:ids e)) (str " rows " (str/join ", " (map str (:ids e))))))))

(defn- mint-shown
  "What a held mint says to the person, in plain words: who gets what,
  and until when."
  [inp ctx]
  (let [aud (str (:audience inp))
        who (or (some-> (:read ctx) (apply [:member aud])
                        (get-in [:data :display]) nonblank)
                aud)
        until (nonblank (:expires_at inp))]
    (str "Give " who ": " (str/join "; " (map entry-said (:scope inp)))
         (if until (str " · until " until) " · no end"))))

(g/defguard agents-mint-within-their-leash
  {:judges [:scope]
   :reads [:principal :now :grant :member :domain :approval_request]
   :vars [:kind]
   :open "Delegation attenuates: an agent hands out only slices of the leash it holds — hold it first (an approved ask), then mint within it."
   :explain "An agent mints only within its own live leash: no grant you hold covers the {kind} entry as asked (kind, every action, every id, and at least your own filter must all fit inside one grant you hold)."}
  [_row inp ctx]
  ;; a gap whose agent has a manager behind it is not refused here:
  ;; `a-mint-past-the-leash-waits-on-its-person` holds it instead
  (if-some [gap (leash-gap inp ctx)]
    (if (leash-holder inp ctx) (t/allow) (t/deny {:vars gap}))
    (t/allow)))

(g/defguard a-mint-past-the-leash-waits-on-its-person
  {:hold true
   :judges [:scope]
   :reads [:principal :now :grant :member :domain :approval_request]
   :vars [:kind]
   :open "No door clears this one. The mint waits as a held_call for the agent's person, and the person's Allow makes the grant as the person, judged as the person's own create."
   :explain "Held for your person's tap. No grant you hold covers the {kind} entry, so this grant is your person's to make: the call is recorded as a held_call, and their Allow creates it as them."}
  [_row inp ctx]
  ;; HC1 (ticket b83e690d). The Allow replays as the PERSON
  ;; (held-calls/door-principal), who is no agent, so no gap is met
  ;; on the replay and the person's own guards judge it
  (if-some [gap (leash-gap inp ctx)]
    (if (leash-holder inp ctx)
      (t/deny {:vars gap :evidence {:shown (mint-shown inp ctx)}})
      (t/allow))
    (t/allow)))

(g/defguard approval-route-only
  {:reads [:principal]
   :hide true
   :remedies [:approval_request/create]
   :explain "Scope extends only through an approved access request, never by hand."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

;; ── the merge: an extension REPLACES per kind, never appends ────────
;;
;; THE FIELD FINDING (waymark-ycp, 2026-08-28). The standing composer's
;; grant carried 74 scope entries for ~20 kinds. `extend-grant` used to
;; concatenate the ask's scope onto the grant's, and the driver's
;; anchored extend-ask copies the grant's scope verbatim — so every
;; renewal doubled the list. `feed.preview_as` (filter-scoped to one
;; member) appeared several times over, and the NEXT ask was refused at
;; the door by scope-filters-are-filterable: "only ONE entry may filter
;; a kind". A refused ask is no ask, so the leash lapsed on its own
;; clock (18:15Z on the 28th, 14:05Z on the 29th) and every sitting was
;; dark until a person re-granted by hand. The renewal loop ate itself.
;;
;; THE RULING. An extension MERGES per kind — one entry per kind, and
;; the merged entry is what the grant stores. It is also the HEAL: the
;; existing scope is folded in on the same pass, so a grant carrying 74
;; entries collapses to one per kind the first time an approval lands.
;;
;;   actions  union — the widening the ask came for
;;   ids      union, BUT openness absorbs: an entry with no :ids is the
;;            whole kind (the schema's own help says so), and the whole
;;            kind swallows any sibling's id list, exactly as
;;            surface-of already reads it
;;   hashed   union — a hashing restriction is never absorbed
;;   filter,  the LAST entry that SPELLS the key decides, and silence
;;   fields   INHERITS. A narrowing is not dropped because the ask
;;            forgot to repeat it; an ask that means to drop one says
;;            so out loud, with an explicit null. (This is the one
;;            deliberate departure from surface-of's openness-absorbs:
;;            there, an unfiltered sibling widens the kind. A merge
;;            writes the leash down permanently, so it keeps the
;;            narrower reading and leaves the widening path spelled.)
;;   args     one spec per action, the last spelled winning
;;
;; The merge only ever changes the SHAPE of a stored scope. Nothing
;; downstream cares: surface-of groups by kind before it reads
;; anything, so 74 entries and 20 conferred the same surface all along
;; — what the 74 broke was the DOOR the next ask had to walk through.

(defn- scope-name
  "One name off a scope entry, however the wire spelled it."
  [x]
  (cond (nil? x) nil (keyword? x) (name x) :else (str x)))

(defn- union-scope-names
  "The names across these lists, deduped, first-seen order kept — a
  merged entry reads in the order a person built it, not sorted."
  [lists]
  (into [] (comp cat (keep scope-name) (distinct)) lists))

(defn- spelled
  "The value of an optional narrowing key, where SILENCE INHERITS and
  an explicit null clears: the last entry that spells the key decides.
  Boxed in a vector so `nobody spoke` and `spoke null` stay different
  answers."
  [k entries]
  (when-some [spoke (seq (filter #(contains? % k) entries))]
    [(get (last spoke) k)]))

(defn- merge-scope-entries
  "One kind's entries, folded to one."
  [entries]
  (let [ids (when-not (some #(nil? (:ids %)) entries)
              (union-scope-names (map :ids entries)))
        hashed (union-scope-names (map :hashed entries))
        specs (remove nil? (mapcat :args entries))
        last-spec (reduce (fn [m a] (assoc m (scope-name (:action a)) a)) {} specs)
        args (into [] (comp (map (comp scope-name :action)) (distinct)
                            (map last-spec))
                   specs)
        filt (spelled :filter entries)
        tofs (spelled :team-of entries)
        nms (spelled :names-me entries)
        flds (spelled :fields entries)]
    (cond-> {:kind (:kind (first entries))
             :actions (union-scope-names (map :actions entries))}
      (some? ids)         (assoc :ids ids)
      (seq hashed)        (assoc :hashed hashed)
      (seq args)          (assoc :args args)
      (first filt)        (assoc :filter (first filt))
      (first tofs)        (assoc :team-of (first tofs))
      (first nms)         (assoc :names-me (first nms))
      (first flds)        (assoc :fields (first flds)))))

(defn- filter-key
  "One filter map as {field-name value-text}, however its keys were
  spelled: what makes two entries' filters the same filter."
  [fm]
  (into {} (map (fn [[f v]] [(scope-name f) (str v)])) fm))

(defn- split-scope-entries
  "One kind's entries, folded to one PER FILTER, filters in first-seen
  order. An entry silent about its filter joins the last one spelled,
  so its actions land on one filter's rows and never on every row."
  [entries]
  (let [last-f (last (keep :filter entries))
        keyed (mapv (fn [e]
                      (let [f (or (:filter e) last-f)]
                        [(filter-key f) (assoc e :filter f)]))
                    entries)]
    (into [] (comp (map first) (distinct)
                   (map (fn [k]
                          (merge-scope-entries
                           (into [] (comp (filter #(= k (first %))) (map second))
                                 keyed)))))
          keyed)))

(defn merge-scope
  "The scopes folded into ONE ENTRY PER KIND, kinds in first-seen
  order. Called with the grant's stored scope and the approved ask's,
  it is both the extension and the heal.

  A kind that ONE scope filters in two different ways keeps an entry
  per filter (ticket d4211f9a): those entries read the kind as a union
  and each admits its own actions, so folding them would hand one
  entry's actions to the other's rows."
  [& scopes]
  (let [all (into [] (comp cat (filter map?)) scopes)
        ;; an entry with an end is never folded (ticket b177a14b): a
        ;; fold would drop its `until`, and break glass would stand
        ;; for good. It rides behind the folded entries as written.
        ending (filterv #(some? (:until %)) all)
        entries (into [] (remove #(some? (:until %))) all)
        by-kind (group-by #(scope-name (:kind %)) entries)
        split? (into #{}
                     (mapcat (fn [scope]
                               (for [[k es] (group-by #(scope-name (:kind %))
                                                      (remove #(some? (:until %))
                                                              (filter map? scope)))
                                     :when (next (distinct (map filter-key
                                                                (keep :filter es))))]
                                 k)))
                     scopes)]
    (into (into [] (comp (map #(scope-name (:kind %))) (distinct)
                         (mapcat #(if (split? %)
                                    (split-scope-entries (by-kind %))
                                    [(merge-scope-entries (by-kind %))])))
                entries)
          ending)))

(defn- ->instant ^java.time.Instant [v]
  (if (instance? java.time.Instant v) v (java.time.Instant/parse (str v))))

;; ── break glass: a scope entry with an end (ticket b177a14b) ────────
;;
;; A seat collected powers that were added for one incident each and
;; never taken back. An entry may now carry `until` and `because`. The
;; resolve judges an entry past its `until` as absent, so the power
;; stops at that instant with no write; the seat's clock sweep then
;; takes the entry off the row (seats/lapse!), so the row says what the
;; seat holds today.
;;
;; A grant's own scope is not swept (ticket c823dc49). The resolve
;; judges its ended entry absent the same way, and the grant's stored
;; scope is the record of what was approved: it stays as written until
;; the grant's `expires_at` or a revoke ends the row. The doors that
;; write a scope onto an ask or a grant carry `scope-ends-are-stated`,
;; so an entry there ends at most 30 days ahead and says why.

(defn entry-end
  "The instant a scope entry's `until` names, or nil: for an entry that
  carries none, and for one whose `until` does not parse, which
  `scope-ends-are-stated` refuses at the door."
  ^java.time.Instant [entry]
  (when-some [u (nonblank (:until entry))]
    (try (.toInstant (java.time.OffsetDateTime/parse u))
         (catch Exception _ nil))))

(defn lapsed?
  "Has this entry's `until` passed at `now`? The instant itself is past:
  an entry is held up to its end and not at it."
  [^java.time.Instant now entry]
  (boolean (when-some [end (entry-end entry)]
             (not (.isBefore now end)))))

(defn live-entries
  "One scope without the entries whose `until` has passed at `now`."
  [now scope]
  (into [] (remove #(lapsed? now %)) scope))

(defn lapsed-entries
  "The entries of one scope whose `until` has passed at `now`."
  [now scope]
  (into [] (filter #(lapsed? now %)) scope))

(defn lapsing-entries
  "The entries of one scope that are held at `now` and end within
  `seconds` of it, soonest first."
  [^java.time.Instant now seconds scope]
  (let [edge (.plusSeconds now (long seconds))]
    (->> scope
         (filter (fn [e]
                   (when-some [end (entry-end e)]
                     (and (.isBefore now end) (not (.isAfter end edge))))))
         (sort-by entry-end)
         vec)))

(def ^:private longest-end-seconds
  "How far ahead an entry's `until` may be: 30 days."
  (* 30 86400))

(defn- end-misstated
  "Why one scope entry's end is not well stated at `now`, as the tail
  of a sentence, or nil. An `until` already past is not refused: the
  resolve judges it absent and the sweep takes it off, and a restate
  that carries the row's own scope back must not fail on it."
  [^java.time.Instant now entry]
  (when-some [u (nonblank (:until entry))]
    (let [end (entry-end entry)]
      (cond
        (nil? end)
        (str "its until, " u ", is not an RFC 3339 time with an offset")

        (.isAfter end (.plusSeconds now (long longest-end-seconds)))
        (str "its until, " u ", is more than 30 days ahead")

        (nil? (nonblank (:because entry)))
        (str "it carries until " u " and no because")))))

(g/defguard scope-ends-are-stated
  {:judges [:scope]
   :reads [:now]
   :vars [:kind :detail]
   :open "An end is a time the caller chooses and a reason the caller writes; no form can list either."
   :explain "The scope entry for {kind} states its end badly: {detail}. An entry's until is an RFC 3339 time at most 30 days ahead, and it carries a because of one sentence."}
  [_row inp ctx]
  (let [now (or (some-> (:now ctx) ->instant) (java.time.Instant/now))]
    (if-some [[e m] (some (fn [e]
                            (when-some [m (end-misstated now e)] [e m]))
                          (filter map? (:scope inp)))]
      (t/deny {:vars {:kind (str (:kind e)) :detail m}})
      (t/allow))))

(defn later-expiry
  "The expiry a widen leaves on a grant: the later of the grant's
  `current` and the ask's `asked`. A widen never shortens (waymark-fp62.20:
  a standing grant died because an anchored ask's earlier expiry was
  written over its own). A nil `current` means no expiry, and stays nil."
  [current asked]
  (cond
    (nil? current) nil
    (nil? asked) current
    (neg? (compare (->instant current) (->instant asked))) asked
    :else current))

(defhandler extend-grant [row inp _ctx]
  ;; R-5.3: a SEAT grant has no scope to fold, and an extend on one
  ;; moves the expiry alone. Nothing here writes a scope onto a seat
  ;; grant even if an ask somehow carried one — the ask door refuses
  ;; that shape (extend-of-a-seat-holds-no-scope), and this is the
  ;; second wall, where the write would actually land.
  (cond-> row
    (and (nil? (seat-cited row)) (some? (:scope inp)))
    (update-in [:data :scope] merge-scope (:scope inp))

    ;; a seat grant's extend comes only from an approved anchored ask
    ;; too, so it never means 'shorten' either: the same later-of rule
    (and (:expires_at inp) (some? (get-in row [:data :expires_at])))
    (update-in [:data :expires_at] later-expiry (:expires_at inp))))

;; ── the grant resource ──────────────────────────────────────────────

(defresource grant
  {:kind :grant
   :plural "grants"
   :states [:offered :accepted :revoked :expired]
   :initial :offered
   :terminal #{:revoked :expired}
   :nav :system
   ;; the one expiry sweep walks `expire` past `expires_at`; the check
   ;; at use stays, so a grant the sweep has not reached confers nothing
   :expires {:field :expires_at :door :expire}
   :summary "Grant to {data.audience} · {state}"
   ;; a grant is its AUDIENCE'S, with no grant needed to see it: the
   ;; asking door is never concealed, because it is how access starts
   ;; and how a dead grant's holder asks again. ACCEPTING rides the
   ;; courtesy too — since the agent default (waymark-rci) leaves no
   ;; unscoped moment in which to accept, and an offer its audience
   ;; cannot take is dead law. The guards still judge every invoke
   :own-surface {:by :audience :actions #{"accept" "revoke"}}
   :schema [:map
            ;; :raw, like every other principal id in this codebase: an
            ;; audience is an opaque member id and a display layer that
            ;; title-cased it would be dressing up a key as a word
            [:audience {:x-display {:raw true
                                    :label "Who holds it"
                                    :help "The member id this grant empowers — the principal that will present it, not a display name."}}
             [:string {:min 1 :max 128}]]
            ;; OPTIONAL since the seat (R-5.1): a grant carries a scope
            ;; or cites a seat, and `a-seat-or-a-scope` is the wall that
            ;; keeps it to one. The entry stays a plain vector rather
            ;; than a [:maybe …] — a seat grant OMITS the key, and a
            ;; grant whose scope was explicitly null would be a third
            ;; state for a field with two honest ones
            [:scope {:optional true
                     :examples [scope-example]
                     :x-display {:label "What it opens"
                                 ;; waymark-2hd0: a list of maps can't list
                                 ;; another kind's fields; the box wears this
                                 :spelled-by-hand "A scope is a list of entries — a kind, its actions, the rows, fields and filter that narrow it — and a list of maps can't list another kind's field names, so each entry is typed by hand: the example above is the whole shape, and the chips beside the box offer every kind and action name."
                                 :help "The leash, entry by entry: a kind, the actions allowed on it, and optionally the rows, fields and filter that narrow it. Everything not named here stays shut. Leave it empty when the grant cites a seat: a seat grant's authority is the seat's own scope, read fresh at every request."}}
             scope-schema]
            ;; THE SEAT (R-5.1). A ref rather than a name, because the
            ;; name is one spelling per ACTIVE seat and a grant outlives
            ;; a retirement; the ASK spells the name, and the mint
            ;; resolves it to this ref (R-5.4)
            [:seat {:optional true
                    :kind :seat
                    :x-display {:label "The seat it sits in"
                                :help "The office this grant is a seat in. A grant that cites a seat carries no scope of its own: the seat's scope is resolved at every request, so restating the seat moves every sitter with it and nothing is copied."}}
             [:maybe :waymark/ref]]
            ;; optional, not defaulted: the mint path (approval-effects!,
            ;; an engine-internal create) skips declared defaults, and
            ;; every reader asks `true?`, so absent IS false
            [:substitute {:optional true
                          :x-display {:label "Sitting as a substitute"
                                      :help "A substitute stands in: it gets the seat's scope minus the seat's drop list, and it reads the seat's memory (self, journal, letter) without writing it. The reason is continuity — the memory is the seat's voice across sessions."}}
             [:maybe :boolean]]
            [:expires_at {:optional true
                          :x-display {:label "Good until"
                                      :help "When the leash goes dead on its own. Leave it empty for a grant that lasts until somebody revokes it."}}
             [:maybe :waymark/instant]]]
   :filterable {:state #{:eq :in}
                :audience #{:eq}}
   ;; the agent behind the leash — audience IS the member id for
   ;; bound/provisioned principals (beat 7: one navigation, not a
   ;; hand-built filter)
   :links [{:rel "member" :kind :member
            :href "/api/members/{data.audience}"
            :summary "The member this grant empowers"}
           ;; the office, for the grant that sits in one — a nil seat
           ;; omits the link, so a scope grant points at nothing
           {:rel "seat" :kind :seat
            :href "/api/seats/{data.seat}"
            :summary "The office this grant sits in"}]
   ;; a hand-offered grant speaks the same vocabulary an ask must
   ;; (waymark-vnc): a scope naming a kind or action that does not
   ;; exist refuses at the door, never lands silently useless — and
   ;; an AGENT's hand mints only within its own leash (waymark9's
   ;; attenuation ceiling, landed at the mint): delegation
   ;; attenuates, never widens; the widening path stays the ask
   :create-guards [a-seat-or-a-scope
                   scope-names-real-kinds scope-names-real-actions
                   scope-filters-are-filterable
                   scope-omits-private-kinds
                   scope-ends-are-stated
                   an-outside-grant-names-them
                   agents-mint-within-their-leash
                   a-mint-past-the-leash-waits-on-its-person]
   :actions
   {:accept {:from #{:offered} :to :accepted
             :guards [audience-only]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Acceptance turns the offer live for its audience; the way back is revoke."}
             :display {:label "Accept" :style :primary :order 1}}
    :revoke {:from #{:offered :accepted} :to :revoked
             :guards [no-self-dealing]
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The link goes dead immediately and for good; the audience keeps nothing."}
             :display {:label "Revoke" :style :danger :order 9}}
    ;; the negotiation machine's landing (batch B): concealed from
    ;; every envelope, invokable only by the approval effect's system
    ;; actor — a human widening scope by hand is not a thing
    :extend {:from #{:accepted} :to :accepted
             ;; :scope is OPTIONAL since the seat (R-5.3): an extend on
             ;; a seat grant moves `expires_at` and nothing else, because
             ;; there is no scope on the grant to fold — the leash's
             ;; authority is the seat row, and the way to widen it is to
             ;; restate the seat
             :input [:map
                     [:scope {:optional true} scope-schema]
                     [:expires_at {:optional true} [:maybe :waymark/instant]]]
             :record true
             :edit {:prefill [:scope :expires_at] :fence false
                    :unfenced-reason "Written once by the approval effect the moment its approve commits; there is no human form to clobber."}
             ;; the scope pair rides behind the hide guard: a
             ;; non-system probe conceals before any scope truth could
             ;; narrate; the system caller (an approve whose ask was
             ;; validated at create) meets them only when the registry
             ;; changed in between — approval-effects! catches and
             ;; warns, the grant honestly does not move
             :guards [approval-route-only
                      scope-names-real-kinds scope-names-real-actions
                      scope-filters-are-filterable
                      scope-omits-private-kinds
                      scope-ends-are-stated
                      an-outside-grant-names-them]
             ;; idempotent, deliberately: a non-idempotent action's 428
             ;; fires before the hide guard can conceal (invoke's step
             ;; order), and a keyless human probe must see 404, not a
             ;; confirmation; a byte-identical redelivery natural-replays
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Scope only widens through an approved request; the way back is revoke."}
             :handler extend-grant
             :display {:label "Extend" :order 7}}
    :expire {:from #{:offered :accepted} :to :expired
             :guards [past-expiry]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Expiry is the clock's bookkeeping; fresh access is a new grant, never an un-expire."}
             :display {:label "Expire" :order 8}}}
   :deviations
   ["R-5.1's exclusive pair — a grant with `seat` holds no `scope`, a grant with `scope` holds no `seat` — is a GUARD (`a-seat-or-a-scope`) rather than a schema constraint. A malli predicate over the whole map would have said the same thing to the engine and nothing at all to the caller: the fingerprint hashes the entry shapes, the JSON-Schema projection publishes properties, and the form renders fields, so a map-level refusal would have reached a person as an unattributed 422. The guard says which two fields collided and which one to drop."
    "A grant carrying neither a scope nor a seat is not refused. It confers nothing, which is an honest thing for a row to say (an offer withdrawn to the empty leash), and R-5.1 asks only that the two never stand together."
    "R-4.9 (the seat row is own-surface for its sitters, read-only) is not an `:own-surface` declaration on the seat kind: that key names a field of the row being READ, and the sitter is named on the GRANT. The resolve adds the cited seat as a synthetic, unstored scope entry instead, so the read rides the one admission algebra rather than a second one."]})

;; ── the approval_request resource (the negotiation machine) ─────────

(defn- load-decoded [eng kind id]
  (when-some [rdef (get (inv/resources eng) kind)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))
             (inv/decode-row rdef))))

(defn- seat-by-name
  "The ACTIVE seat wearing this spelling, engine-side (the ask's mint
  runs post-commit, outside any ctx). nil when the ask named no seat,
  when this engine serves no seats, or when nothing open answers to the
  name — the ask door already refused that last one, and the mint
  refusing to guess is the second wall."
  [eng nm]
  (when-some [nm (nonblank nm)]
    (when-some [rdef (get (inv/resources eng) :seat)]
      (some->> (first (store/with-tx (:storage eng)
                        (fn [tx]
                          (store/query-rows (:storage eng) tx :seat
                                            {:name nm :state :active}
                                            {:limit 1}))))
               (inv/decode-row rdef)))))

(g/defguard requester-holds-the-grant
  {:reads [:principal :grant]
   :open "The grants you hold are the grants collection, one query away; leave grant_id empty and the ask is a bootstrap that mints its own."
   :explain "An access request extends a grant its requester holds; the named grant's audience must be you."}
  [_row inp ctx]
  (let [p (:principal ctx)]
    (cond
      ;; the anchorless ask holds nothing yet — its approval MINTS
      ;; the grant, so there is nothing here to judge
      (nil? (:grant_id inp)) (t/allow)
      (= :system (:type p)) (t/allow)
      ;; render's probe ctx carries no :read — cross-kind admissions
      ;; decline rather than guess (the phase-8 discipline)
      (nil? (:read ctx)) (t/allow)
      :else (let [row ((:read ctx) :grant (:grant_id inp))]
              (if (and row (= (get-in row [:data :audience]) (:id p)))
                (t/allow) (t/deny))))))

(defn- auto-anchorable?
  "An ask the engine may anchor for its requester (waymark-7v7v): it
  names no grant, it is not a seat ask (a seat ask is always a
  bootstrap — R-5.4's middle row), and a person or agent files it."
  [inp ctx]
  (and (nil? (:grant_id inp))
       (nil? (nonblank (:seat inp)))
       (not= :system (:type (:principal ctx)))))

(defn- live-leashes
  "The requester's live scope grants: accepted, unexpired by the ctx
  clock, and holding no seat (an office widens by restating the seat,
  never by an ask). nil when the ctx cannot look — a probe ctx carries
  no :find, and a door that cannot look declines to guess."
  [ctx pid]
  (when-some [find' (:find ctx)]
    (let [now (:now ctx)]
      (filterv (fn [g]
                 (and (= :accepted (:state g))
                      (nil? (get-in g [:data :seat]))
                      (let [exp (get-in g [:data :expires_at])]
                        (or (nil? exp) (neg? (compare now exp))))))
               (find' :grant {:audience pid} {:limit 100})))))

(g/defguard an-anchorless-ask-names-its-grant
  {:reads [:principal :now :grant]
   :vars [:grants]
   :explain "You hold several live grants ({grants}); pass grant_id to name the one this ask widens."
   :open "The way out is in this same form: pass grant_id, one of the grants the sentence lists. No door picks among several live grants for you."}
  [_row inp ctx]
  ;; an anchorless ask from a holder of live grants would MINT a
  ;; replacement, and the session would wear it and lose the sight it
  ;; had. One live grant is anchored at birth (anchor-the-lone-grant);
  ;; several leave the engine nothing honest to pick
  (let [live (when (auto-anchorable? inp ctx)
               (live-leashes ctx (:id (:principal ctx))))]
    (if (< 1 (count live))
      (t/deny {:vars {:grants (str/join ", " (sort (map :id live)))}})
      (t/allow))))

(defn anchor-the-lone-grant
  "The ask's birth hook (waymark-7v7v): an anchorless ask from a
  holder of exactly ONE live grant is anchored to it, and the note
  says the engine did it, so its approval widens that grant rather
  than minting a replacement. None stays the bootstrap mint; several
  were refused at the door by an-anchorless-ask-names-its-grant."
  [row ctx]
  (if-not (auto-anchorable? (:data row) ctx)
    row
    (let [live (live-leashes ctx (get-in row [:data :requested_by]))]
      (if (= 1 (count live))
        (let [gid (str (:id (first live)))]
          (-> row
              (assoc-in [:data :grant_id] gid)
              (assoc-in [:data :note]
                        (str "Anchored by the engine to " gid
                             ", the one live grant you hold: approval widens it."))))
        row))))

(defn- member-named
  "The member row a principal id names: the row of that id, or the row
  a binding stamped with it as `subject`."
  [ctx who]
  (when-some [who (some-> who str not-empty)]
    (or ((:read ctx) :member who)
        (first ((:find ctx) :member {:subject who} {:limit 1})))))

(defn waits-on
  "The member a new ask or held call waits on
  (docs/spec-addressed-notice.md): the member `who` is, or, when that
  member is a seat's sitter or a person's delegate, the member its
  `acts_for` names. Read through ctx, so the engine stamps it at create
  and no body supplies it. → a member id, or nil when nobody answers
  or the ctx cannot look."
  [ctx who]
  (when (and (:read ctx) (:find ctx))
    (when-some [m (member-named ctx who)]
      (let [person (some-> (get-in m [:data :acts_for]) str not-empty)]
        (some-> (if person (member-named ctx person) m) :id str)))))

(defn- stamp-waits-on
  "The ask waits on the person its requester acts for, or on the
  requester themselves. Whatever the body said is dropped."
  [row ctx]
  (let [p (:principal ctx)
        m (waits-on ctx (or (some-> (:acts-for p) str not-empty) (:id p)))]
    (if m
      (assoc-in row [:data :waits_on] m)
      (update row :data dissoc :waits_on))))

(defn- stamp-domain
  "The ask carries the name of the domain its requester's seat stores
  (epic aff24e84, piece 3). The requester's seat is the one a sitter's
  id names (`seat:<the seat>`). A member with no seat, and a seat that
  stores no domain, stamp nothing, and :absent-as filters the row as
  factory. Whatever the body said is dropped."
  [row ctx]
  (let [sid (members/seat-id (get-in ctx [:principal :id]))
        read' (:read ctx)
        d (when (and read' sid)
            (some-> (read' :seat sid)
                    (get-in [:data :domain]) str not-empty))
        nm (when d
             (some-> (read' :domain d) (get-in [:data :name]) str not-empty))]
    (if nm
      (assoc-in row [:data :domain] nm)
      (update row :data dissoc :domain))))

(defn- born-ask
  "The ask's birth hook: anchor-the-lone-grant, then the person it
  waits on, then its requester's seat's domain."
  [row ctx]
  (-> row (anchor-the-lone-grant ctx) (stamp-waits-on ctx) (stamp-domain ctx)))

(g/defguard requester-is-named
  {:reads [:principal]
   :open "No door names an anonymous caller: sign in, or sit in a seat, and ask again."
   :explain "An access request names its requester; an anonymous ask would grant nobody."}
  [_row _inp ctx]
  (if (= (:id (:principal ctx)) (:id t/anonymous))
    (t/deny) (t/allow)))

(def ask-pace-limit
  "Anchorless asks per rolling hour per principal — the generous
  ceiling on the one create any named principal may issue.

  It STAYS under waymark-1uv's ruling (ranked, not capped) because the
  write costs the household: an access ask is an obligation with a
  deadline placed on an approver (laws v3 law 2, outside the contest)
  and what it asks for is a leash on the house."
  20)

(g/defguard asks-are-paced
  {:reads [:principal :now :approval_request]
   :vars [:limit :retry_at]
   :explain "Fresh access asks are paced to {limit} an hour; the window reopens at {retry_at}."}
  [_row inp ctx]
  (if (or (some? (:grant_id inp))       ; anchored asks ride the grant they hold
          (nil? (:find ctx)))           ; probe ctx carries no :find — decline to guess
    (t/allow)
    (let [pid (:id (:principal ctx))
          ^java.time.Instant now (:now ctx)
          cutoff (.minusSeconds now 3600)
          fresh (into []
                      (filter (fn [r]
                                (and (nil? (get-in r [:data :grant_id]))
                                     (some? (:created-at r))
                                     (neg? (compare cutoff (:created-at r))))))
                      ((:find ctx) :approval_request {:requested_by pid}
                       {:limit 500}))]
      (if (< (count fresh) ask-pace-limit)
        (t/allow)
        (let [retry (.plusSeconds
                     ^java.time.Instant (reduce (fn [a b] (if (neg? (compare a b)) a b))
                                                (mapv :created-at fresh))
                     3600)]
          (t/deny {:vars {:limit ask-pace-limit :retry_at (str retry)}
                   :retry-at retry}))))))

(def open-asks-cap
  "Open (offered) asks a requester may hold at once; a verdict on
  those comes before a new one.

  It STAYS under waymark-1uv's ruling (ranked, not capped) for the
  pace's own reason: each open ask is a verdict owed by a person on a
  leash, outside the contest, and no rank stands between the asker
  and the approver's fridge."
  10)

(g/defguard asks-are-few
  {:reads [:principal :approval_request]
   :vars [:cap :pending]
   :explain "Open access asks are capped at {cap}; yours awaiting a verdict: {pending}."}
  [_row inp ctx]
  (if (or (some? (:grant_id inp)) (nil? (:find ctx)))
    (t/allow)
    (let [pid (:id (:principal ctx))
          open ((:find ctx) :approval_request
                {:requested_by pid :state :offered}
                {:limit (inc open-asks-cap)})]
      (if (< (count open) open-asks-cap)
        (t/allow)
        (t/deny {:vars {:cap open-asks-cap
                        :pending (str/join ", " (sort (map :id open)))}})))))

(defn- seat-named
  "The ACTIVE seat an ask spells by name, through the ctx :find hook —
  the one lookup the three seat-ask guards share. nil when the ask
  names no seat, when no active seat wears that spelling, or when the
  ctx carries no hooks (the storage-free render probe, which declines
  rather than guesses — the phase-8 discipline)."
  [inp ctx]
  (when-some [nm (nonblank (:seat inp))]
    (when-some [find' (:find ctx)]
      (first (find' :seat {:name nm :state "active"} {:limit 1})))))

(g/defguard asks-are-short
  {:judges [:expires_at]
   :reads [:now :seat]
   :vars [:max_hours :asked :whose]
   :open "The ceiling is in the sentence; the way out is an earlier expires_at in this same form, and an approved follow-up ask extends."
   :explain "A leash is short — at most {max_hours} hours{whose}; this ask runs to {asked}. Propose less; an approved follow-up ask can always extend."}
  [_row inp ctx]
  (if-some [^java.time.Instant exp (:expires_at inp)]
    ;; R-5.6: a SEAT ask is capped by the seat's own standing_ttl_seconds
    ;; — the office decides how long a sitter's leash may be, because it
    ;; is the office's fuel — and a scope ask keeps the 24-hour ceiling
    (let [seat (seat-named inp ctx)
          seat-ttl (when seat (some-> (get-in seat [:data :standing_ttl_seconds]) long))
          max-s (or seat-ttl
                    (long (:grant-max-ttl-seconds (:services ctx) 86400)))
          cap (.plusSeconds ^java.time.Instant (:now ctx) max-s)]
      (if (pos? (compare exp cap))
        (t/deny {:vars {:max_hours (quot max-s 3600)
                        :asked (str exp)
                        :whose (if seat-ttl
                                 (str ", which is the seat "
                                      (get-in seat [:data :name])
                                      "'s own standing leash")
                                 "")}})
        (t/allow)))
    (t/allow)))

;; ── the three shapes of an ask (R-5.4), and the two it refuses ──────

(g/defguard ask-names-one-thing
  {:judges [:seat]
   :remedies [:approval_request/create]
   :explain "An ask names a SEAT or spells a SCOPE, never both. A seat ask asks to sit in an office somebody already opened, and what it opens is the office's own scope, read fresh at every request; a scope ask spells its leash entry by entry. File two asks if you want both."}
  [_row inp _ctx]
  (if (and (some? (nonblank (:seat inp))) (seq (:scope inp)))
    (t/deny)
    (t/allow)))

(g/defguard extend-of-a-seat-holds-no-scope
  {:judges [:scope]
   :reads [:grant]
   :vars [:grant_id]
   :open "A seat grant widens only by a restate of its seat, the opener's door on the seat row; this ask drops its scope and keeps its expiry."
   :explain "The grant {grant_id} sits in a seat, and a seat grant has no scope to widen: its authority is the seat's, restated on the seat by the person who opened it (R-5.3). An extend ask for it carries the new expiry and nothing else."}
  [_row inp ctx]
  (let [gid (nonblank (:grant_id inp))]
    (if (and gid (seq (:scope inp)) (:read ctx))
      (if (seat-cited ((:read ctx) :grant gid))
        (t/deny {:vars {:grant_id gid}})
        (t/allow))
      (t/allow))))

(g/defguard the-seat-is-open
  {:judges [:seat]
   :reads [:seat]
   :vars [:seat]
   :open "The offices a person has opened are the seats collection, one query away; a chip row of them here would be the household's roster rendered into somebody else's form."
   :explain "No open seat is named {seat}. An ask to sit spells an ACTIVE seat's name exactly as the seat spells it — a parked, merged or retired office serves nothing, so a grant citing one would scope to nothing the moment it was minted."}
  [_row inp ctx]
  (if (and (some? (nonblank (:seat inp)))
           (some? (:find ctx))
           (nil? (seat-named inp ctx)))
    (t/deny {:vars {:seat (nonblank (:seat inp))}})
    (t/allow)))

(defn- model-name
  "One model ref as the identifier a harness declares, for a sentence
  a reader can act on; the ref itself when the row is gone."
  [ctx id]
  (or (when-some [read' (:read ctx)]
        (nonblank (get-in (read' :model (str id)) [:data :name])))
      (str id)))

(defn- model-list-of
  "The seat's list for the ask's kind: `substitute_for` for a
  substitute ask, `held_for` for a full one (R-5.7). Empty means any
  model may sit."
  [seat substitute?]
  (into [] (map str) (get-in seat [:data (if substitute?
                                           :substitute_for
                                           :held_for)])))

(g/defguard model-may-sit
  {:judges [:seat]
   :reads [:principal :seat :model]
   :vars [:seat :model :list :which]
   :open "The models a seat is held for are that seat's own row, one GET away — and the list is a handful of refs, not a vocabulary this form could recite."
   :explain "The seat {seat} is held for {which}: {list}. This session declares {model}, which is not one of them — so a grant minted here would scope to nothing at its first request. Ask to sit as a substitute if the seat takes one, or start the session on a model the seat is held for."}
  [_row inp ctx]
  (if-some [seat (seat-named inp ctx)]
    (let [substitute? (true? (:substitute inp))
          wanted (model-list-of seat substitute?)
          claim (nonblank (get-in ctx [:principal :model]))
          mine (when (and claim (:find ctx))
                 (some-> (first ((:find ctx) :model {:name claim} {:limit 1}))
                         :id str))]
      (if (or (empty? wanted) (and mine (some #{mine} wanted)))
        (t/allow)
        (t/deny {:vars {:seat (get-in seat [:data :name])
                        :model (or claim "no model at all")
                        :which (if substitute?
                                 "substitutes"
                                 "its full sitter")
                        ;; the list by the IDENTIFIER a harness declares,
                        ;; never by row id: the refusal has to be
                        ;; actionable at the door the caller can reach,
                        ;; and that door speaks model names
                        :list (str/join ", " (map #(model-name ctx %) wanted))}})))
    (t/allow)))

;; The four-eyes wall, no longer written by hand. g/not-the-field IS
;; this guard generalized — same :reads, same explain, and a :check
;; whose canonical form is built to print exactly as the defguard body
;; that stood here did, so approval_request's fingerprint does not
;; move. The var stays because the codebase names it in twelve places
;; and because "someone else decides" is the sentence this particular
;; wall means; what changed is that the household's own decision kinds
;; now get the same wall from a declaration instead of a copy.
(def someone-else-decides
  (g/not-the-field
   :requested_by
   {:name :someone-else-decides
    :open "The wall is about who: any principal but the requester decides, and no door makes the requester another one."
    :explain "The requester cannot judge its own ask; another principal decides."}))

(g/defguard grant-still-accepting
  {:reads [:grant]
   :open "A revoked or lapsed grant never comes back; the requester files a fresh anchorless ask, and its approval mints a new one."
   :explain "The named grant no longer accepts scope; offer a fresh grant instead."}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    ;; an anchorless ask names no grant to judge — approval mints one
    (if-some [gid (get-in row [:data :grant_id])]
      (let [grant-row (read :grant gid)]
        (if (and grant-row (= :accepted (:state grant-row)))
          (t/allow) (t/deny)))
      (t/allow))
    (t/allow)))

;; R-5.8. The scan is a PAGE OF ACCEPTED GRANTS rather than a filtered
;; query, and that is a recorded cost: `seat` is an optional field, a
;; promoted column is generated only for a non-:maybe entry (sitting's
;; own deviation records the same wall), so the collection cannot be
;; filtered by it. The door this runs at is a person's tap on an
;; approval, not a sitter's request, so one page is the right price for
;; a wall that must not be wrong.
(g/defguard seat-has-one-sitter
  {:reads [:principal :now :grant :seat]
   :vars [:seat :sitter]
   :open "The standing grant clears by its own revoke or its expiry, and a substitute ask is not limited; neither is a door on this ask."
   :explain "The seat {seat} is taken: {sitter} holds a live grant sitting in it. One office, one full sitter — two sessions under one seat's authority is two hands nobody can tell apart in the log afterwards. A SUBSTITUTE is not limited and this ask can be filed as one; otherwise revoke the grant that stands, or wait for it to lapse."}
  [row _inp ctx]
  (let [nm (nonblank (get-in row [:data :seat]))
        substitute? (true? (get-in row [:data :substitute]))
        find' (:find ctx)]
    (if (or substitute? (nil? nm) (nil? find'))
      (t/allow)
      (let [seat (first (find' :seat {:name nm :state "active"} {:limit 1}))
            now (:now ctx)
            live? (fn [g]
                    (and (= :accepted (:state g))
                         (= (str (:id seat)) (seat-cited g))
                         (not (true? (get-in g [:data :substitute])))
                         (let [e (get-in g [:data :expires_at])]
                           (or (nil? e) (neg? (compare now e))))))
            sitter (when seat
                     (some #(when (live? %) (get-in % [:data :audience]))
                           (find' :grant {:state :accepted} {:limit 500})))]
        (if sitter
          (t/deny {:vars {:seat nm :sitter sitter}})
          (t/allow))))))

(defhandler stamp-approver [row _inp ctx]
  (cond-> (assoc-in row [:data :approved_by] (:id (:principal ctx)))
    ;; the anchorless ask learns the grant its approval will mint —
    ;; stamped at the verdict, deterministically from the ask's own
    ;; id, so a replay restamps the same name and the requester can
    ;; read where to go
    (nil? (get-in row [:data :grant_id]))
    (assoc-in [:data :grant_id] (str "grant-" (:id row)))))

(defhandler record-verdict-note [row inp _ctx]
  (assoc-in row [:data :note] (:note inp)))

;; ── the four-eyes rule, written down ────────────────────────────────
;; The one sentence core's own law most wants checked, and the reason
;; it can be checked for free: :deny carries exactly one guard, and
;; that guard declares :reads [:principal]. (:approve carries
;; grant-still-accepting beside it, which reads :grant — so an
;; approve scenario would declare :given and drop to the suite. The
;; tier rule doing its job, not a gap.)

(defscenario the-asker-does-not-decide
  "Nobody judges their own ask: the principal who requested the
   access cannot be the one who denies it."
  {:kind    :approval_request
   :attempt :deny
   :row     {:state :offered
             :data {:task "read the pantry" :requested_by "agent-ari"}}
   :input   {:note "changed my mind"}
   :as      {:id "agent-ari" :type :agent}
   :expect  {:refused :someone-else-decides
             :because "The requester cannot judge its own ask"}})

(defscenario another-principal-may-deny
  "Somebody else in the house can say no — the four-eyes wall bars
   the requester, not the household."
  {:kind    :approval_request
   :attempt :deny
   :row     {:state :offered
             :data {:task "read the pantry" :requested_by "agent-ari"}}
   :input   {:note "not this week"}
   :as      {:id "mom" :type :person}
   :expect  {:allowed true}})

(defresource approval-request
  {:kind :approval_request
   :plural "approval_requests"
   :nav :system
   :summary "Access request by {data.requested_by} · {state} · until {data.expires_at}"
   ;; an offered ask is left by a person's verdict, and by no seat
   :left-by [{:in #{:offered} :by :person :via :approve :to :approved}
             {:in #{:offered} :by :person :via :deny :to :denied}]
   ;; THE FIRST INSTANCE OF THE DECISION PATTERN (spec-decision-kind).
   ;; Everything below the :decision key used to be spelled by hand
   ;; here: the three states, the initial and the terminal pair, the
   ;; two verdict actions with their four-eyes wall, the requester
   ;; stamp and the default leash at birth, the schema entries the
   ;; engine owns, the create model that omits them, the decision
   ;; queue's own filter and sort. None of it was ever
   ;; grant-specific — it was the shape of a verdict, written once
   ;; because there was only one.
   ;;
   ;; The proof that respelling it changed nothing:
   ;; approval_request's fingerprint hash is pinned as a literal in
   ;; waymark10.decision-sugar-test and did not move by one byte. Two
   ;; spellings, one law.
   :decision
   ;; the question — and its own words for it. The sugar writes generic
   ;; prose for the entries it owns (waymark-7rw); an access ask has a
   ;; better sentence available, so it spells one HERE rather than
   ;; re-declaring :task in :schema, which would move the entry order
   ;; and with it a fingerprint, to say a sentence
   {:asks    {:field :task
              :x-display
              {:label "What you need it for"
               :help "The work this access is for, in one sentence. The approver is deciding about the TASK as much as the scope — 'file the week's receipts' earns a yes that 'admin' does not."}}
    :by      :requested_by               ; stamped from the principal
    :decider {:not {:field :requested_by ; the field wall, not four-eyes:
                    :open "The wall is about who: any principal but the requester decides, and no door makes the requester another one."}
              ;; a decision row's requester is stamped by :on-create,
              ;; before any transition exists to be the actor of
              :name :someone-else-decides
              :explain "The requester cannot judge its own ask; another principal decides."}
    :stamps  {:decided-by :approved_by}
    ;; born-ask stamps waits_on and domain at birth, so the create
    ;; model omits them
    :engine-fields [:waits_on :domain]
    ;; short-lived is the DEFAULT, not an opt-in: an ask naming no
    ;; expiry gets the engine's configured TTL (24h, the leash's own
    ;; cap — waymark-h6y: a shorter default killed the minted grant
    ;; minutes after a late approval, because the offer window and
    ;; the grant lifetime are one field), stamped AT CREATE so the
    ;; approver approves the leash that will actually exist. An agent
    ;; proposes shorter at will; the approver sees the number either way.
    :expires {:field :expires_at
              :default {:service :grant-default-ttl-seconds :seconds 86400}
              :x-display
              {:label "Good until"
               :help "When the access should die on its own. Leave it empty and the engine stamps its own short default at birth, so the approver approves the leash that will actually exist."}}
    ;; the ceiling (asks-are-short) and the pacing pair stay
    ;; hand-written below rather than riding :expires :max and
    ;; :pacing: both need the ANCHORED-ASK EXEMPTION — an ask that
    ;; names a grant its requester already holds is not a fresh ask
    ;; and is neither paced nor capped — and that exemption is
    ;; grant law, not decision law. The sugar offers both; this
    ;; instance declines both, which is the floor-not-ceiling rule
    ;; working rather than failing.
    :verdicts
    [{:name :approve :to :approved
      :label "Approve" :style :primary :order 1
      ;; a-manager (R-3.2): an approve widens a grant, and widening
      ;; authority is a manager's; deny stays open to any decider
      :guards [roles/a-manager grant-still-accepting seat-has-one-sitter]
      :safety {:idempotent true :reversible false :confirm true
               :consequence "The requester's grant gains exactly the scope shown, immediately."}
      ;; stamp-approver stamps the approver AND the id of the grant
      ;; the verdict will mint — a decision-specific stamp, so the
      ;; verdict keeps its own handler whole and the sugar does not
      ;; wrap it
      :handler stamp-approver}
     {:name :deny :to :denied
      :label "Deny" :style :danger :order 9
      :note :note
      :edit {:prefill [:note] :fence false
             :unfenced-reason "A denial's note is written once with the verdict; a frozen offered ask has nothing to clobber."}
      :safety {:idempotent true :reversible false :confirm false
               :one-way "A denied ask stays on record; asking differently is a new request."}
      :handler record-verdict-note}]
    ;; the asking door is never concealed: it is how access starts
    :own-surface {:by :requested_by
                  :actions #{"create" "approve" "deny"}}}
   ;; the extra law THIS decision declares, over the pattern's floor:
   ;; grant_id (an anchorless ask is the bootstrap path — its approval
   ;; mints the grant and stamps the id here), the scope it asks for,
   ;; and — since the seat (R-5.4) — the OFFICE it asks to sit in. The
   ;; three shapes an ask may wear:
   ;;
   ;;   bootstrap, scope  {task, scope, expires_at}
   ;;   bootstrap, seat   {task, seat, substitute?, expires_at}
   ;;   extend            {grant_id, task, expires_at}
   ;;
   ;; and `scope` is optional because two of the three carry none.
   :schema [:map
            [:grant_id {:optional true :kind :grant
                        :x-display {:label "Widen this grant"
                                    :help "The grant you already hold and want more of. Leave it empty for the bootstrap ask — an approval then mints a fresh grant in your name."}}
             [:maybe :waymark/ref]]
            [:scope {:optional true
                     :examples [scope-example]
                     :x-display {:label "What you are asking for"
                                 :spelled-by-hand "A scope is a list of entries — a kind, its actions, the rows, fields and filter that narrow it — and a list of maps can't list another kind's field names, so each entry is typed by hand: the example above is the whole shape, and the chips beside the box offer every kind and action name."
                                 :help "The leash you want, entry by entry: a kind, the actions on it, and optionally the rows, fields and filter that narrow it. Ask for the least that does the job — an approver reads this. Leave it empty when you are asking to sit in a seat: the office's scope is the ask."}}
             scope-schema]
            ;; THE SEAT'S NAME, not its ref (R-5.4): a person spells the
            ;; office out loud — "inbox-clerk" — and the mint resolves
            ;; the name to the row and writes the REF on the grant. The
            ;; name is what a charter, a handover note and a harness
            ;; script all already say; a ref would make the ask
            ;; unwritable without a prior GET
            [:seat {:optional true
                    :not-a-ref "The seat's NAME, spelled out loud; the mint resolves it to the row and writes the ref on the grant."
                    :x-display {:raw true
                                :label "The seat you are asking to sit in"
                                :help "The name of an office somebody already opened — \"inbox-clerk\", say. What it opens is the seat's own scope, read fresh at every request, so a restate of the seat moves your leash with it and you never ask again for the same office."}}
             [:maybe [:string {:min 1 :max 40}]]]
            [:substitute {:optional true
                          :x-display {:label "As a substitute"
                                      :help "Tick this to stand in rather than hold the seat: a substitute gets the seat's scope minus its drop list, reads the seat's memory without writing it, and is not limited to one per seat."}}
             [:maybe :boolean]]
            ;; THE PERSON WHOSE TAP THIS WAITS ON
            ;; (docs/spec-addressed-notice.md): stamped at birth by
            ;; born-ask, never read from the body, so a notice_rule
            ;; can address {field waits_on}
            [:waits_on {:optional true :kind :member
                        :x-display {:label "Waits on"
                                    :help "The member who must approve this ask: the person the requesting seat or delegate acts for, or the requester themselves. The engine stamps it at birth."}}
             [:maybe :waymark/ref]]
            ;; THE REQUESTER'S SEAT'S DOMAIN, BY NAME (epic aff24e84,
            ;; piece 3): stamped at birth by born-ask, never read from
            ;; the body. An ask with none filters as factory.
            [:domain {:optional true
                      :not-a-ref "The domain's NAME, as the seat's domain row spells it: never a row id."
                      :x-display {:label "The requester's domain"
                                  :help "The name of the domain the requesting seat was in when it asked. The engine stamps it at birth. An ask with none filters as factory."}}
             [:string {:min 1 :max 120}]]]
   :filterable {:grant_id #{:eq}
                :domain #{:eq}}
   :absent-as {:domain "factory"}
   ;; the approval page opens on the decision queue: newest ask first,
   ;; and only the ones still waiting on a person — both projected by
   ;; the sugar (:default-filters {:state "offered"}, :sortable
   ;; "-created_at"). Every judged ask stays on record forever, so
   ;; recency alone would bury the three rows that need a verdict
   ;; under a year of verdicts already given. The filter is allowed to
   ;; hide rows there only because it cannot hide QUIETLY — it rides
   ;; the self href, the summary's "filtered:" echo and a removable
   ;; chip, so a requester looking for their own denied ask is one
   ;; chip click, or one empty state=, away.
   ;;
   ;; the grant this ask anchors or (once approved) minted; a nil
   ;; grant_id omits the link — an unjudged bootstrap ask points at
   ;; nothing yet
   :links [{:rel "grant" :kind :grant
            :href "/api/grants/{data.grant_id}"
            :summary "The grant this request extends or minted"}]
   ;; the engine anchors an anchorless ask from a holder of one live
   ;; grant, and stamps the member the ask waits on; the sugar runs its
   ;; stamps first, so requested_by is set
   :on-create born-ask
   :create-guards [requester-is-named
                   requester-holds-the-grant
                   an-anchorless-ask-names-its-grant
                   asks-are-paced
                   asks-are-few
                   asks-are-short
                   ;; the shape gate (R-5.5): one ask names one thing,
                   ;; an extend on a seat grant carries no scope, and a
                   ;; seat ask names an office that is OPEN — each
                   ;; refused at the door rather than approved into a
                   ;; grant that scopes to nothing
                   ask-names-one-thing
                   extend-of-a-seat-holds-no-scope
                   the-seat-is-open
                   model-may-sit
                   ;; the honesty gate (waymark-vnc): an ask for a
                   ;; (kind, action) that does not exist refuses NOW,
                   ;; naming the kind's real actions — never approved
                   ;; into a scope that can never match
                   scope-names-real-kinds
                   scope-names-real-actions
                   scope-filters-are-filterable
                   scope-omits-private-kinds
                   scope-ends-are-stated]
   :scenarios [the-asker-does-not-decide another-principal-may-deny]
   :deviations
   ["`seat-has-one-sitter` (R-5.8) reads a PAGE of accepted grants rather than a query filtered by seat: `grant.seat` is optional, and a promoted column — the thing a filter would walk — is generated only for a non-`:maybe` entry (the sitting kind records the same wall). The door it runs at is a person's tap on an approval, not a sitter's request, so one page is the right price for a wall that must not be wrong."
    "A seat ask is always a BOOTSTRAP ask (R-5.4's middle row): it names no `grant_id`, so it is paced and capped like any other fresh ask, and its approval mints rather than extends. An anchored ask on a seat grant is the third row — expiry only — and `extend-of-a-seat-holds-no-scope` is what keeps it there."]})

;; ── the approve effect (the router's one grants seam) ───────────────

(def approvals-actor
  "The system actor an approved request's grant extension acts as."
  (t/principal {:id "waymark10-grants" :type :system
                :display "Grant approvals"}))

(def wire-boundary-effects
  "THE DOORS WHOSE EFFECT DOES NOT LIVE IN THEIR HANDLER — every
  (kind, action) pair whose full consequence runs POST-COMMIT at the
  wire boundary rather than inside the transition, so a caller that
  reaches the door any other way gets the transition and not the
  effect.

  There is exactly one, and it is `approval-effects!` below: an
  `approve` on an `approval_request` moves the ask and then MINTS THE
  GRANT out here, under a system actor, keyed on the approval's id.
  waymark-442.14 is the bead for moving it inside; until it lands,
  this set is the honest name of the gap.

  It is a `def` rather than a literal in the `when` because something
  else now has to read it. A staged piece (waymark-jfv.9) may name any
  door in the house, and a piece naming THIS one would move an ask to
  `approved` — terminally — while the grant it was the whole point of
  never appeared. That is not a capability question and no wall about
  authority would catch it; it is a fact about where this engine keeps
  one effect, and the piece's own staging door refuses it by reading
  this set. The day 442.14 lands, the set empties and the refusal
  disappears with it."
  #{[:approval_request :approve]})

(defn approval-effects!
  "Post-commit, wire-boundary (the router calls this after every
  single invoke, the attachments put-bytes! precedent): a fresh,
  non-replayed approve on an :approval_request lands its grant —
  system actor, logged, keyed on the approval id so a redelivery
  replays instead of double-appending. The named grant EXTENDS when
  it exists (the anchored ask) and is MINTED when it does not (the
  anchorless ask, its id stamped by the approve handler): audience =
  requester, scope = the approved ask MERGED per kind into whatever the
  grant already held (waymark-ycp — never appended, or the next ask is
  refused for a capability filter-scoped twice), then accepted through
  the machine's own accept — the ask WAS the audience's consent, and
  the requester's next presentation of the stamped grant id scopes it
  in. A refusal here (the grant revoked between guard and effect) is
  warned on *err*, never thrown: the approval committed; the grant
  honestly did not move.

  A SEAT ASK MINTS A SEAT GRANT (R-5.4): the ask spelled the office's
  NAME and this is where the name becomes a ref — audience = requester,
  `seat` = the row that wears that spelling, `substitute` as asked, the
  expiry, and NO SCOPE. The resolve reads the seat at each request from
  there on. A seat ask arrives anchorless by construction (an anchored
  one is an extend, which slides the expiry alone), so the mint branch
  is the only one that reads the name."
  [eng rdef action-name result]
  (when (and (contains? wire-boundary-effects [(:kind rdef) action-name])
             (:transition result)
             (nil? (:replayed? result)))
    (let [row (:row result)
          gid (get-in row [:data :grant_id])
          corr (get-in result [:transition :correlation-id])
          expires (get-in row [:data :expires_at])
          seat (seat-by-name eng (get-in row [:data :seat]))]
      (try
        (if (load-decoded eng :grant gid)
          (inv/invoke! eng :grant gid :extend
                       (cond-> {}
                         (seq (get-in row [:data :scope]))
                         (assoc :scope (get-in row [:data :scope]))

                         expires (assoc :expires_at (str expires)))
                       {:principal approvals-actor
                        :correlation-id corr
                        :idempotency-key (str "approval-extend-" (:id row))})
          (do
            (inv/create! eng :grant
                         (cond-> {:audience (get-in row [:data :requested_by])}
                           seat
                           (assoc :seat (str (:id seat))
                                  :substitute (true? (get-in row [:data :substitute])))

                           (nil? seat)
                           ;; merged at the mint too, so a grant's
                           ;; stored scope has ONE shape wherever it
                           ;; came from (waymark-ycp)
                           (assoc :scope (merge-scope (get-in row [:data :scope])))

                           expires (assoc :expires_at (str expires)))
                         {:principal approvals-actor
                          :id gid
                          :correlation-id corr
                          :idempotency-key (str "approval-mint-" (:id row))})
            (inv/invoke! eng :grant gid :accept nil
                         {:principal approvals-actor
                          :correlation-id corr
                          :idempotency-key (str "approval-accept-" (:id row))})))
        (catch Exception e
          (binding [*out* *err*]
            (println "waymark10 grants: approval" (:id row)
                     "could not land grant" gid "-" (ex-message e)))))))
  result)

;; ── scope evaluation ────────────────────────────────────────────────

(defn- active?
  "Does this grant confer anything right now? Accepted and unexpired —
  enforcement reads the clock live, so a stale :expired transition
  never extends access."
  [row now]
  (and (= :accepted (:state row))
       (let [exp (get-in row [:data :expires_at])]
         (or (nil? exp) (neg? (compare now exp))))))

(defn grant-by-id
  "The grant row with this id, decoded, as it stands now; nil when no
  such grant exists. For a reader that is not at the wire — a scheduled
  run holds the ID of the grant it was arranged under and no request
  (docs/spec-scheduled-actions.md R-4.2). It conceals nothing and
  judges nothing, so it is for the engine's own reads and never for an
  answer a caller sees."
  [eng grant-id]
  (when (some? grant-id)
    (load-decoded eng :grant (str grant-id))))

(defn grant-standing
  "Where the grant with this id stands right now: {:standing :grant}.
  :standing is :absent (no such row), :offered (not yet accepted),
  :active (accepted and unexpired), :revoked, or :expired. Expiry is
  judged by the live clock, same as enforcement, so a grant past its
  `expires_at` reads :expired before the `expire` transition is
  written. :grant is the decoded row, nil when absent.

  NARROWED is not a standing. An :active grant's scope is read off
  :grant as it is at this moment, wider or narrower than the day it was
  cited; whether it still admits one call is `visibility`'s answer for
  the principal that wears it."
  [eng grant-id]
  (let [row (grant-by-id eng grant-id)
        now ((:now-fn eng))
        exp (get-in row [:data :expires_at])
        past? (and (some? exp) (not (neg? (compare now exp))))]
    {:standing (cond
                 (nil? row) :absent
                 (= :revoked (:state row)) :revoked
                 (or past? (= :expired (:state row))) :expired
                 (active? row now) :active
                 :else (:state row))
     :grant row}))

(defn- mode-spec [m]
  (when m
    {:mode (keyword (:mode m))
     :names (into #{} (map str) (:names m))}))

(defn- admits?
  "Does any spec in the vector admit the name? nil specs = the kind is
  unrestricted (some entry declared no narrowing)."
  [specs n]
  (or (nil? specs)
      (boolean (some (fn [{:keys [mode names]}]
                       (case mode
                         :allow (contains? names n)
                         :deny (not (contains? names n))
                         false))
                     specs))))

(defn- args-of
  "action → [spec …] for one kind's entries. Each entry granting an
  action contributes its declared spec for it — or openness when its
  :args does not name the action; one open contribution unrestricts
  the action's args (absent from the map)."
  [entries]
  (let [contribs (for [e entries
                       a (map str (:actions e))]
                   [a (some #(when (= a (str (:action %))) (mode-spec %))
                            (:args e))])]
    (into {}
          (keep (fn [[a pairs]]
                  (let [specs (map second pairs)]
                    (when (every? some? specs)
                      [a (vec specs)]))))
          (group-by first contribs))))

(defn- surface-of
  "The granted surface: kind → {:ids set|nil :actions #{string}
  :fields [spec…]|nil :args {action [spec…]}}. Entries sharing a kind
  union their actions and admissions; an entry without ids (or
  fields, or args for an action) is unrestricted there and absorbs
  any sibling's narrowing."
  [row]
  (into {}
        (map (fn [[kind entries]]
               [kind
                {:ids (when-not (some #(nil? (:ids %)) entries)
                        (into #{} (comp (mapcat :ids) (map str)) entries))
                 :actions (into #{} (comp (mapcat :actions) (map str)) entries)
                 :fields (when-not (some #(nil? (:fields %)) entries)
                           (mapv (comp mode-spec :fields) entries))
                 ;; hashing dominates: any entry naming a field hashed
                 ;; hashes it kind-wide — a privacy restriction is
                 ;; never absorbed the way openness absorbs
                 :hashed (into #{} (comp (mapcat :hashed) (map str)) entries)
                 ;; filter-scoped admission: nil when any entry lacks a
                 ;; filter (openness absorbs, the ids rule). For a KIND
                 ;; the vec is the SIGHT: a row any of them admits is
                 ;; seen (row-matches?), and conds-of spells the same
                 ;; union for a listing. What a row AFFORDS is asked of
                 ;; :entries below, one entry at a time.
                 ;; For a dotted POWER it holds every entry and the
                 ;; power door admits a call that ANY of them admits
                 ;; (waymark-fp62.6.3.5) — two repositories on one
                 ;; grant is two entries, because one entry cannot say
                 ;; two different paths for two different repositories
                 :filters (when-not (some #(nil? (:filter %)) entries)
                            (mapv :filter entries))
                 ;; each entry as it was written: the actions it names,
                 ;; under its own ids and its own filter. A door opens
                 ;; on a row only where ONE entry names the door and
                 ;; admits the row (`door-on-row?`, ticket d4211f9a)
                 :entries (mapv (fn [e]
                                  {:actions (into #{} (map str) (:actions e))
                                   :ids (when (some? (:ids e))
                                          (into #{} (map str) (:ids e)))
                                   :filter (:filter e)
                                   :team-of (:team-of e)})
                                entries)
                 :args (args-of entries)}]))
        (group-by :kind (get-in row [:data :scope]))))

(defn filter-values
  "The values one filter pair names. A comma-separated value is any of
  on a field that admits :in — `state` always does, as the collection's
  grammar reads it — and one literal text otherwise: the split
  `collections/parse-query` makes, so a grant's row check, its query
  conds and a wake's count all read \"a,b\" alike."
  [rdef f v]
  (if (coll? v)
    ;; a :team-of field, already read to the ids it may name
    ;; (`resolve-team-of`); empty when it may name none
    (mapv str v)
    (let [raw (str v)
          fname (name f)
          in? (or (= "state" fname)
                  (contains? (get (:filterable rdef) (keyword fname)) :in))
          vs (when (and in? (str/includes? raw ","))
               (into [] (comp (map str/trim) (remove str/blank?))
                     (str/split raw #",")))]
      (if (seq vs) vs [raw]))))

(defn row-matches?
  "Does this decoded row sit inside one of the entry's filter maps?
  Exact text comparison against the data field — the same value the
  collection's :eq cond compares in SQL, so the row check and the
  query check tell one story. A comma value on a field the kind
  declares :in is any of its parts, as the SQL :in cond reads it.

  `state` is the one name that is not a data field: it is the row's
  own column, and it reads off the row rather than out of the
  document (bead waymark-fp62.12). `conds-of` addresses the same
  column, so the two halves stay one story here too."
  [row filter-maps rdef]
  (boolean
   (some (fn [fm]
           (every? (fn [[f v]]
                     (let [fname (name f)
                           have (str (if (= "state" fname)
                                       (some-> (:state row) name)
                                       (get-in row [:data (keyword fname)])))]
                       (boolean (some #(= have %) (filter-values rdef f v)))))
                   fm))
         filter-maps)))

(defn- door-on-row?
  "Does the scope open `action` on the row `id`, given `e`, the kind's
  entry of the surface? An action is admitted only by an entry that
  names it, under that entry's own ids and filter (ticket d4211f9a):
  one entry may read a kind widely while another acts on part of it.
  When every entry names the action the row's sight has already
  answered, and nothing is loaded."
  [eng k e action id]
  (let [a (name action)
        entries (:entries e)
        named (filter #(contains? (:actions %) a) entries)]
    (boolean
     (or (= (count named) (count entries))
         (when-some [row (load-decoded eng (keyword k) id)]
           (let [rdef (get (inv/resources eng) (keyword k))]
             (some (fn [n]
                     (and (or (nil? (:ids n)) (contains? (:ids n) (str id)))
                          (or (nil? (:filter n))
                              (row-matches? row [(:filter n)] rdef))))
                   named)))))))

(def dead
  "The scoped-to-nothing surface a dead or unknown grant confers."
  {})

(defn- field-hash
  "The hashed disposition's token: stable per (kind, field, value) so
  an agent can correlate and reference, never read. Salted by the
  engine (:services :field-hash-salt — set a real secret in
  production; the default constant leaves low-entropy values open to
  dictionary guessing and is only dev's convenience)."
  [eng kind field value]
  (str "#" (subs (wire/sha256-hex
                  (str (or (get-in eng [:services :field-hash-salt])
                           "waymark10-field-hash")
                       "\u0000" (name kind)
                       "\u0000" (name field)
                       "\u0000" (pr-str value)))
                 0 16)))

(defn- required-arg-names
  "The declared action's required input fields, as strings; nil when
  the action or its input is undeclared."
  [eng kind action]
  (when-some [input (get-in (inv/resources eng)
                            [(keyword kind) :actions (keyword action) :input])]
    (into #{}
          (keep (fn [[k e]] (when-not (:optional e) (name k))))
          (schema/entry-map input))))

(defn- prune-unusable
  "Denying a REQUIRED argument denies the action (recorded closure of
  the honesty gap): an action advertised with an unsatisfiable form
  is a lie — and its missing-key 422 would NAME the hidden argument —
  so the surface drops it whole; waymark9 routed such invocations to
  approval mode, which is unported."
  [eng surface]
  (into {}
        (map (fn [[kind e]]
               [kind
                (update e :actions
                        (fn [actions]
                          (into #{}
                                (remove
                                 (fn [a]
                                   (when-some [specs (get-in e [:args a])]
                                     (some #(not (admits? specs %))
                                           (required-arg-names eng kind a)))))
                                actions)))]))
        surface))

(defn- own-surfaces
  "Every kind on THIS engine that declares an own-surface, as
  {\"kind\" <normalized :own-surface>} — read off the registry, never
  a literal.

  It used to be a literal: a set of seven kind-name strings sitting in
  this file, consulted by three hand-written case blocks below and
  copied a fourth time into the test packs and a fifth into the
  clj-kondo hook. THREE of those seven kinds — :self, :journal and
  :letter — are declared in an APP this namespace has no business
  naming, and a decision kind an app declared was invisible to its own
  requester no matter what it said, until every copy was edited. The
  failure was silent: the rows simply were not there. The set is not
  gone, it is where it always belonged — on the declarations that each
  describe their own ownership (waymark10.resource's :own-surface).

  An engine serving none of them gets an empty map and the whole
  courtesy costs one registry read."
  [eng]
  (into {}
        (keep (fn [[k rdef]]
                (when-some [os (:own-surface rdef)]
                  [(name k) os])))
        (inv/resources eng)))

(defn- branch-owns?
  "Does this row's `branch` name the principal? A branch is a PATH
  into the document — [:requested_by] for a promoted column,
  [:requested_by :id] for a requester riding as an object.

  `pids` is a SET: every spelling the principal answers to
  (members/spellings-of — the principal id and its bound member row
  id), so a row addressed by either spelling is the reader's own
  (waymark-27j)."
  [row branch pids]
  (contains? pids (get-in row (into [:data] branch))))

(defn- own-branch-ids
  "The principal's own ids for one branch of one kind, inside the
  caller's transaction. A single-field branch is a query cond the
  store pushes down; a deeper PATH sits out of cond-sql's top-level
  reach, so that window filters in memory — a recorded seam (the
  orphan sweep keeps such tables small; a deployment that outgrows the
  window promotes the id to its own field).

  The window takes the NEWEST 200 (:newest-first), not the oldest: the
  stores' default ordering is created_at ASC, so a principal past 200
  own rows was silently losing its FRESHEST ones out of every listing
  and total while row GETs still succeeded — listing and row
  disagreeing about the same row (waymark-tti.3 L6). It always failed
  closed (no foreign id can enter either way), but mail accumulates
  and journals do not shrink. The window is still a window; what
  changed is which end of the rope it holds."
  [eng tx kind branch pids]
  (let [st (:storage eng)]
    (if (= 1 (count branch))
      (into #{}
            (comp (mapcat #(store/query-rows st tx kind {(first branch) %}
                                             {:limit 200 :newest-first true}))
                  (map :id))
            pids)
      (into #{}
            (comp (filter #(branch-owns? % branch pids)) (map :id))
            (store/query-rows st tx kind {} {:limit 200 :newest-first true})))))

(defn- own-ids
  "The principal's own rows of one own-surface kind, as the id cond
  the collection pushes down. Every declared branch queries
  separately and the id sets UNION, deduped — because ownership is
  not always one-party (a letter is yours as its author OR as its
  recipient; the store's cond map is a conjunction with no OR). One
  transaction covers every branch, so a listing cannot see one end of
  a row and miss the other.

  nil = unrestricted, the :all posture: the whole kind lists.
  Otherwise never empty — an impossible id keeps an empty surface's
  total honestly zero (an empty IN would not parse).

  The principal is asked for under EVERY spelling it answers to
  (members/spellings-of, waymark-27j): a letter addressed by the
  member row id is the reader's as surely as one addressed by the
  principal id, and the feed and the open guard already say so. The
  cost is one member read per collection request, and one query per
  spelling per branch (at most a handful).

  `person` is the one a delegate acts for, or nil. A kind whose
  own-surface says :delegates true asks for that person's spellings
  too; every other kind never reads it."
  [eng kind os pid person]
  (when-not (:all os)
    (let [pids (cond-> (set (members/spellings-of eng pid))
                 (and (:delegates os) person)
                 (into (members/spellings-of eng person)))
          ids (store/with-tx
                (:storage eng)
                (fn [tx]
                  (into (sorted-set)
                        (mapcat #(own-branch-ids eng tx kind % pids))
                        (:by os))))]
      (if (seq ids) (vec ids) ["-none-"]))))

(defn check-capability
  "The introspection answer (waymark-44h, the grant-check door): does
  grant G, held by principal P, admit capability C right now? Rides
  the same visibility resolution every request rides — wrong
  audience, unaccepted, revoked, expired and unknown all collapse to
  {:allowed false} with nothing else said (concealment: the caller
  learns no scope it did not name). :constraints is the capability
  entry's FIRST filter map, the enforcement point's to interpret;
  :expires_at rides along so the caller can cache the yes no longer
  than it lives.

  It is the first and not all of them because this door answers an
  OUTSIDE enforcement point, and one filter is the shape those have
  always read. A power narrowed by several entries (waymark-fp62.6.3.5
  — a bench grant naming two repositories) is judged at this engine's
  own power door, which reads every entry; a rig that wants the whole
  sentence asks the door that enforces it."
  [eng grant-id principal-id capability]
  (let [vis (visibility eng (str grant-id)
                        (t/principal {:id (str principal-id) :type :agent
                                      :display (str principal-id)}))
        e (get (:surface vis) (str capability))]
    (if (nil? e)
      {:allowed false}
      {:allowed true
       :constraints (some-> (:filters e) first)
       :actions (vec (sort (:actions e)))
       :expires_at (some-> (load-decoded eng :grant (str grant-id))
                           (get-in [:data :expires_at]) str)})))

(defn standing-grant-for
  "The grant the guest door hangs a session on: the newest grant for
  this audience that still confers or could — :accepted first, then
  a still-:offered one (the door accepts it AS the audience on
  arrival; acceptance is the audience's own act). Unexpired judged
  by the live clock, same as enforcement. nil when nothing stands —
  and the door goes dark with it.

  `keep?` narrows the candidates before the pick (ticket acc89add):
  the sit passes one that refuses a grant near its end, so an older
  accepted grant does not hide the fresh offered one minted beside it."
  ([eng audience] (standing-grant-for eng audience (constantly true)))
  ([eng audience keep?]
  (let [now ((:now-fn eng))
        rows (->> (store/with-tx (:storage eng)
                    (fn [tx] (store/query-rows (:storage eng) tx :grant
                                               {:audience (str audience)}
                                               {:limit 100})))
                  ;; oldest-first is the one ordering; decode restores
                  ;; instants for the expiry compare
                  (map #(load-decoded eng :grant (:id %)))
                  (remove nil?)
                  reverse)
        unexpired? (fn [row]
                     (let [exp (get-in row [:data :expires_at])]
                       (or (nil? exp) (neg? (compare now exp)))))]
    (or (some #(when (and (= :accepted (:state %)) (unexpired? %) (keep? %)) %) rows)
        (some #(when (and (= :offered (:state %)) (unexpired? %) (keep? %)) %) rows)))))

(defn accept-as-audience!
  "The guest door's first-arrival courtesy: accept a still-:offered
  grant AS its audience (the accept guard's own rule — acceptance is
  never someone else's act). Returns the accepted row, or the row
  unchanged when it already stands accepted; nil when the accept
  refuses (a race's loser reloads and moves on)."
  [eng row principal]
  (if (= :offered (:state row))
    (try
      (inv/invoke! eng :grant (:id row) :accept {} {:principal principal})
      (load-decoded eng :grant (:id row))
      (catch Exception _ (load-decoded eng :grant (:id row))))
    row))

;; ── the seat resolve (R-5.2) ────────────────────────────────────────
;;
;; A seat grant's visibility is resolved FROM THE SEAT ROW AT EACH
;; REQUEST, in seven steps, of which the first three are WALLS: the
;; seat is not active, the session's model is not one the seat is held
;; for, or the week's fuel is spent. A wall is hard — the grant scopes
;; to nothing — and the router writes the seat's `halt` from what this
;; returns (R-7.7). The remaining four take the seat's scope, subtract
;; the substitute's drop list, subtract the sweep's stale list, and
;; hand the result to the ordinary scope resolution below, which has
;; no idea a seat was involved.
;;
;; THE COST IS BOUNDED, because it is paid per REQUEST: one seat load,
;; one models lookup (only when the seat names a list), and one SUM
;; over the seat's spend postings of the window. No writes — the halt is the
;; router's, and it moves only when the wall itself moves.

(def budget-window-seconds
  "The seat's fuel window: seven days, rolling, measured from a spend
  posting's `effective_at` (R-5.2 step 3, docs/spec-spend.md §5)."
  (* 7 86400))

(defn- without-entries
  "One scope MINUS a list of entries, entry-wise: a subtracted entry
  removes its actions from the entry naming the same kind, and removes
  the whole entry when it names no actions at all (the read-only
  spelling, which subtracts everything) or when it names every action
  the entry had. R-5.2 steps 5 and 6 — the substitute's drop list and
  the sweep's stale list are one arithmetic, spelled once."
  [scope entries]
  (if (empty? entries)
    (vec scope)
    (let [by-kind (group-by #(scope-name (:kind %)) entries)]
      (into []
            (keep (fn [e]
                    (if-some [drops (seq (get by-kind (scope-name (:kind e))))]
                      (let [dropped (into #{} (comp (mapcat :actions)
                                                    (keep scope-name))
                                          drops)
                            had (into [] (keep scope-name) (:actions e))
                            left (into [] (remove dropped) had)]
                        (when-not (or (some #(empty? (:actions %)) drops)
                                      (and (seq had) (empty? left)))
                          (assoc e :actions left)))
                      e)))
            scope))))

(defn- own-seat-entry
  "R-4.9, as a scope entry the engine adds at resolve time and nobody
  stores: a sitter reads the ONE seat row its grant cites, and no
  action on it. `:own-surface {:by …}` names a field of the row being
  read, and a seat carries no sitter field and must not grow one (a
  seat with a sitter column would be a second copy of the grant), so
  the courtesy is spelled where the sitter IS identified — here, at
  the resolve, out of the grant. Going through the scope means one
  admission algebra rather than two: `:kind?`, `:row?`, `:field?` and
  `:ids-of` all answer for the seat exactly as they answer for
  anything else, and `:whole-kind?` stays false, because one row is
  not the collection."
  [seat-id]
  {:kind "seat" :ids [(str seat-id)] :actions []})

(defn seat-window-conds
  "The ONE reader of a seat's window (waymark-fp62.1.2): the three conds
  that name this seat's sittings started at or after `since`, in
  `states`. The wall no longer reads it: it sums spend postings by
  `effective_at` (`spend-window-conds`). The ledger (routes/seats)
  reads its finished bills with closed alone, and the opening's
  agreement check (seats/ledger-agreement) reads closed and open. One state is
  spelled `:=`, more than one `:in`."
  [seat-id ^java.time.Instant since states]
  [(if (= 1 (count states))
     {:target :state :op := :value (first states)}
     {:target :state :op :in :values (vec states)})
   {:target :data :field :seat :cast "text" :op :=
    :value (str seat-id)}
   {:target :data :field :started_at :cast "timestamptz" :op :>=
    :value (str since)}])

(defn spend-window-conds
  "The three conds that name a seat's fuel (docs/spec-spend.md §5): the
  postings to its `seat/<id>/spend` account whose `effective_at` falls
  in the rolling window [now - budget-window-seconds, now]. The upper
  end is closed: a posting effective at this very instant has been
  reported, so it is spent. The account and `effective_at` are
  promoted and indexed together, so the sum walks the index.

  NOT `at`: the commit instant is not settable, so migrated postings
  would all count in the migration's week and every seat would hit its
  wall that day. An open sitting's spend counts when it is reported,
  and a long sitting's lands when it is reported, not on the day it
  started.

  PUBLIC, and said ONCE (waymark-fp62.7.13): the seat's fire door
  judges the same wall with no sitter in the room, and two spellings
  of one arithmetic are two answers to R-5.2's third wall, correct on
  the day they were written."
  [seat-id ^java.time.Instant now]
  [{:target :data :field :account :op :=
    :value (postings/account-address :seat (str seat-id) :spend)}
   {:target :data :field :effective_at :cast "timestamptz" :op :>=
    :value (str (.minusSeconds now budget-window-seconds))}
   {:target :data :field :effective_at :cast "timestamptz" :op :<=
    :value (str now)}])

(defn spent-with
  "The week's spend, over a summing hand the caller holds: `sum-amounts`
  is (fn [kind field conds] → {unit sum}), store/sum-amounts' answer,
  which is what the ctx `:sum-amounts` hook gives a guard inside a
  write. No hand, no number: the answer is then 0M, exactly as a house
  with no posting kind answers."
  [sum-amounts seat-id ^java.time.Instant now]
  (or (when sum-amounts
        (some-> (sum-amounts :posting :amount (spend-window-conds seat-id now))
                (get "USD")
                bigdec))
      0M))

(defn under-budget?
  "Is there fuel left? R-5.2 step 3's comparison, said once: the wall
  holds while the spend is NOT less than the budget, so a seat whose
  budget is zero is at the wall from the first request."
  [spent budget]
  (neg? (compare spent (or budget 0M))))

(defn spent-this-week
  "The dollars this seat's spend postings of the last seven days add —
  ONE aggregate read, never a page of rows. A cond whose cast matches a
  promoted column matches through that column, so a declared index
  serves the sum — a kind's :index names one, a composite included, as
  `posting`'s [:account :effective_at] does for spend step 5. A seat's
  postings are a week of wakes, so this scan is small by construction."
  [eng seat-id ^java.time.Instant now]
  (spent-with (when (get (inv/resources eng) :posting)
                (fn [kind field conds]
                  (store/with-tx (:storage eng)
                    (fn [tx]
                      (store/sum-amounts (:storage eng) tx kind field conds)))))
              seat-id now))

(defn spend-postings
  "The postings the wall sums (`spend-window-conds`), oldest effective
  first, each {:effective_at Instant :amount BigDecimal :source_id id}:
  what a view reads to say WHEN the week's sum can drop. Empty in a
  house with no posting kind."
  [eng seat-id ^java.time.Instant now]
  (if (get (inv/resources eng) :posting)
    (let [st (:storage eng)]
      (->> (store/with-tx st
             (fn [tx]
               (store/search-rows st tx :posting (spend-window-conds seat-id now)
                                  {:all true})))
           (keep (fn [{:keys [data]}]
                   (let [m (money/from-wire (:amount data) nil)
                         at (some-> (:effective_at data) str not-empty)]
                     (when (and (money/money? m) at)
                       {:effective_at (java.time.Instant/parse at)
                        :amount (:amount m)
                        :source_id (some-> (:source_id data) str)}))))
           (sort-by :effective_at)
           vec))
    []))

(defn- model-id-of
  "The models row the session's own claim names, or nil — one query by
  the unique `name` index. A claim naming no row is nil, and a seat
  that names a list refuses it: the harness's word is not the engine's
  registry, and a seat held for named models cannot be held by a model
  this house has never priced."
  [eng claim]
  (when-some [c (nonblank claim)]
    (when (get (inv/resources eng) :model)
      (some-> (first (store/with-tx (:storage eng)
                       (fn [tx]
                         (store/query-rows (:storage eng) tx :model
                                           {:name c} {:limit 1}))))
              :id str))))

(def ^:private sitting-count-fields
  "The four token counts a tally writes. A sitting's ceiling is spent
  against all four: a cache read is cheaper than an input token and it
  is not free, and a ceiling that counted two of the four would be a
  ceiling nobody could reason about."
  [:input_tokens :output_tokens :cache_read_tokens :cache_write_tokens])

(defn- tallied-tokens
  "How many tokens the CALLING sitting has reported so far (R-12.27),
  or nil when there is no sitting to ask.

  The calling sitting is `sitting-id` when the request names one (the
  MCP session's binding): every sitting of a seat shares the seat's
  grant, so the newest open one under it is usually a sibling, and
  the wall would judge this sitting by another's fuel (ticket
  8358b658). Only a request that names none falls back to ONE query,
  by the promoted `grant` column and state — the shape
  `seats/open-sitting-for-grant` uses, spelled here because seats.clj
  requires THIS namespace and not the other way round. A sitting
  nobody has tallied carries the zeroes its birth wrote, which is the
  honest answer: nothing reported, nothing spent."
  [eng grant-id sitting-id]
  (when (and grant-id (get (inv/resources eng) :sitting))
    (when-some [row (or (when-some [sid (some-> sitting-id str not-empty)]
                          (load-decoded eng :sitting sid))
                        (first (store/with-tx (:storage eng)
                                 (fn [tx]
                                   (store/query-rows (:storage eng) tx :sitting
                                                     {:grant (str grant-id)
                                                      :state :open}
                                                     {:limit 1
                                                      :newest-first true})))))]
      (reduce (fn [n f] (+ n (long (or (get-in row [:data f]) 0))))
              0 sitting-count-fields))))

(defn- resolve-seat
  "R-5.2, whole. → {:id, :halt (the reason the seat row carries right
  now, or nil), :reason (the wall this request met, or nil), :detail,
  :scope}. `:scope` is the effective scope — empty at a wall — and the
  caller writes it onto the grant row it hands to `surface-of`.
  `sitting-id` is the calling sitting, or nil; only the per-sitting
  wall reads it."
  [eng row principal ^java.time.Instant now sitting-id]
  (let [seat-id (seat-cited row)
        substitute? (true? (get-in row [:data :substitute]))
        seat (load-decoded eng :seat seat-id)
        halt (nonblank (get-in seat [:data :halt :reason]))
        named (or (nonblank (get-in seat [:data :name])) seat-id)
        ;; `:haltable?` is the seat's OWN state, not the wall's: a
        ;; parked, merged or retired seat cannot carry a halt (the
        ;; three concealed doors are active → active, and a parked seat
        ;; is already scoped to nothing by the person's own hand), so
        ;; the router must not spend a row load per request trying
        wall (fn [reason detail]
               {:id seat-id :halt halt :reason reason :detail detail
                :haltable? (= :active (:state seat)) :scope []})
        held (when seat
               (mapv str (get-in seat [:data (if substitute?
                                               :substitute_for
                                               :held_for)])))
        claim (nonblank (:model principal))]
    (cond
      ;; 1 · the seat itself
      (not (and seat (= :active (:state seat))))
      (wall "seat_not_active"
            (str "The seat " named " is "
                 (if seat (name (:state seat)) "gone")
                 ", so it serves nothing; every grant citing it scopes"
                 " to nothing until somebody opens it again."))

      ;; 2 · the model this session declares
      (and (seq held)
           (not (when-some [mine (model-id-of eng claim)]
                  (boolean (some #{mine} held)))))
      (wall "model_not_held"
            (str "This session declares " (or claim "no model")
                 " and " named " is held for "
                 (count held) " model(s) "
                 (if substitute? "as a substitute" "as its full sitter")
                 "; a session outside the list sees nothing."))

      :else
      ;; 3 · the week's fuel
      (let [spent (spent-this-week eng seat-id now)
            budget (or (get-in seat [:data :budget_usd_per_week]) 0M)
            ;; 4 · THIS SITTING'S fuel (R-12.27). Only an interactive
            ;; sitting is asked: a fired one is bounded by its run and
            ;; reports its counts at the close, when it is no longer
            ;; open, so the read would answer zero for every seat in
            ;; the house on every request and cost a round trip to do
            ;; it. The ceiling is the seat's own `sitting_budget_tokens`
            ;; — the number already passed to the harness as the cap on
            ;; one wake — so the wall says what the seat already said.
            ;; …unless the person told the seat to ignore it: then
            ;; there is no ceiling to read, and zero is how this line
            ;; already says so
            ceiling (if (true? (get-in seat [:data :ignore_sitting_budget]))
                      0
                      (long (or (get-in seat [:data :sitting_budget_tokens]) 0)))
            burnt (when (and (pos? ceiling)
                             (= "interactive"
                                (some-> (get-in seat [:data :mode]) str)))
                    (tallied-tokens eng (:id row) sitting-id))]
        (cond
          (not (under-budget? spent budget))
          (wall "budget_reached"
                (str "The week's fuel is spent: " (str spent) " of "
                     (str budget) " over " named "'s sittings of the"
                     " last seven days. The wall lifts on its own as the"
                     " window rolls."))

          (and burnt (<= ceiling (long burnt)))
          (wall "sitting_budget_reached"
                (str "This sitting's fuel is spent: " burnt " of " ceiling
                     " tokens. Close the sitting; a new one opens fresh."))

          :else
          ;; 5 · the seat's scope, 6 · minus the drop list for a
          ;; substitute, 7 · minus the sweep's stale entries, and the
          ;; sitter's own read of the seat row beside it (R-4.9)
          {:id seat-id :halt halt :reason nil :detail nil :haltable? true
           :scope (conj (cond-> (vec (get-in seat [:data :scope]))
                          substitute?
                          (without-entries (get-in seat [:data :substitute_drop]))

                          :always
                          (without-entries (get-in seat [:data :stale])))
                        (own-seat-entry seat-id))})))))

(defn seat-of-grant
  "The seat a grant row cites, as the welcome document names it:
  {:id, :name, :scope}, or nil for a grant that carries its own scope.
  `:scope` is the seat's scope as this grant wears it — minus the drop
  list for a substitute, minus the sweep's stale entries — and no wall
  is judged here: the walls are per request, and this is what the
  grant carries when none stands."
  [eng row]
  (when-some [seat-id (seat-cited row)]
    (let [seat (load-decoded eng :seat seat-id)]
      {:id seat-id
       :name (nonblank (get-in seat [:data :name]))
       :scope (cond-> (live-entries ((:now-fn eng)) (get-in seat [:data :scope]))
                (true? (get-in row [:data :substitute]))
                (without-entries (get-in seat [:data :substitute_drop]))

                :always
                (without-entries (get-in seat [:data :stale])))})))

(defn seat-grant-of-another?
  "Does `grant-id` name a SEAT grant whose audience is not this
  principal? The identity boundary asks it of the header a delegate
  presented: the seat's view is the connector's alone, so the HTTP door
  refuses that header by name instead of resolving it to a dead
  surface. A seat grant its own audience presents is not this
  question's, and neither is a grant that carries its own scope."
  [eng grant-id principal]
  (when-some [row (when grant-id (load-decoded eng :grant grant-id))]
    (boolean (and (seat-cited row)
                  (not= (get-in row [:data :audience]) (:id principal))))))

(defn as-the-seats-sitter
  "`principal` as it sits under `grant-id` where no session is there to
  declare a model: a planner hears a principal id and holds no session,
  and a seat held for named models serves nothing to a hand that
  declares none (`resolve-seat`, wall 2). So the principal takes the
  name of the first model the grant's seat is held for —
  `substitute_for` for a substitute — which is the claim its sitter's
  own session makes. The principal comes back as it was when it declares
  a model already, when the grant is not its own or cites no seat, and
  when the seat names no model."
  [eng grant-id principal]
  (or (when-not (nonblank (:model principal))
        (when-some [row (when grant-id (load-decoded eng :grant grant-id))]
          (when (= (get-in row [:data :audience]) (:id principal))
            (when-some [seat (some->> (seat-cited row) (load-decoded eng :seat))]
              (when-some [held (some (fn [id]
                                       (nonblank (get-in (load-decoded eng :model (str id))
                                                         [:data :name])))
                                     (get-in seat [:data (if (true? (get-in row [:data :substitute]))
                                                           :substitute_for
                                                           :held_for)]))]
                (assoc principal :model held))))))
      principal))

(declare member-audience)

;; ── :team-of, read live (docs/spec-teams.md §6) ─────────────────────

(def ^:private team-page
  ;; the most team rows one resolve reads; a house has a handful
  500)

(defn- active-teams
  "The active team rows, decoded; empty where no team kind is served."
  [eng]
  (if-some [rdef (get (inv/resources eng) :team)]
    (into []
          (comp (map #(inv/decode-row rdef %))
                (filter #(= "active" (some-> (:state %) name))))
          (store/with-tx (:storage eng)
            (fn [tx] (store/query-rows (:storage eng) tx :team {} {:limit team-page}))))
    []))

(defn- team-of-ids
  "The ids the field of one :team-of entry may name: the admitted teams'
  own ids for a team field, their members for a member field. A team is
  admitted when the entry names it, or, naming none, when `mine` (the
  ids the grant's member answers to) is on it. Only active teams are
  read, so a retired one admits nothing."
  [rdef spec teams mine]
  (let [team (some-> (:team spec) str)
        admitted (filter (fn [t]
                           (if team
                             (= team (str (:id t)))
                             (some #(contains? mine (str %))
                                   (get-in t [:data :members]))))
                         teams)]
    (case (team-of-ref rdef (some-> (:field spec) name))
      :team (into (sorted-set) (map (comp str :id)) admitted)
      :member (into (sorted-set)
                    (comp (mapcat #(get-in % [:data :members])) (map str))
                    admitted)
      #{})))

(defn- resolve-team-of
  "The scope with each :team-of read NOW into its entry's filter: the
  field → the set of ids it may name, ANDed with the entry's own
  filter. `row-matches?` and `conds-of` then read one answer, so the
  row judgment and the collection query agree, and the next request
  reads membership again."
  [eng mine scope]
  (if-not (some :team-of scope)
    scope
    (let [teams (active-teams eng)
          rs (inv/resources eng)]
      (mapv (fn [e]
              (if-some [spec (:team-of e)]
                (let [rdef (get rs (keyword (scope-name (:kind e))))]
                  (assoc e :filter
                         (assoc (or (:filter e) {})
                                (keyword (name (:field spec)))
                                (if rdef (team-of-ids rdef spec teams mine) #{}))))
                e))
            scope))))

;; ── :names-me, read live (docs/spec-teams.md §7) ────────────────────

(defn- resolve-names-me
  "The scope with each :names-me entry read into one entry per field,
  each filtered to the ids the grant's member answers to (`mine`) and
  ANDed with the entry's own filter. Entries of one kind are a union,
  so a row is admitted when any of those fields names the member; an
  empty `mine` admits nothing."
  [mine scope]
  (if-not (some :names-me scope)
    scope
    (let [ids (into (sorted-set) (map str) mine)]
      (into []
            (mapcat (fn [e]
                      (if-some [fields (seq (:names-me e))]
                        (for [f fields]
                          (-> (dissoc e :names-me)
                              (assoc :filter (assoc (or (:filter e) {})
                                                    (keyword (name f)) ids))))
                        [(dissoc e :names-me)])))
            scope))))

(defn visibility
  "The per-request visibility, resolved once: the X-Waymark-Grant
  header names a grant whose audience must be this principal; an
  active grant confers its surface, anything else confers `dead` —
  plus the own-grant surface (batch B, widened by the mint fix) for
  every NAMED principal, whatever the presented grant's fate: the
  negotiation kinds are how access starts, so a dead, foreign or
  unknown grant conceals the domain but never the asking door.
  Returns closures the render/router consult — {:kind? :row? :action?
  :field? :arg? :ids-of} — plus :grant-id for narration-free
  diagnostics and :grant, the guard's-eye view a LIVE grant confers
  (waymark-sfe): {:id :action? :row? :substitute}, nil for every other
  fate.

  A SEAT GRANT is resolved here too (R-5.2): the seat row's scope
  stands in for the grant's own before a single closure is built, so
  everything below this line — the surface, the whole-kind question,
  the guard's-eye view, the collection's ids — reads one kind of
  scope and never learns where it came from. The walls ride out on
  `:seat` for the router to write the halt from (R-7.7); this
  namespace reads rows and writes none. The fourth arity names the
  calling sitting, which the per-sitting wall judges instead of the
  newest open sitting under the grant."
  ([eng grant-id principal] (visibility eng grant-id principal nil))
  ([eng grant-id principal sitting-id]
  (let [pid (:id principal)
        ;; a grant ROW in hand (a role's standing scope,
        ;; `requester-visibility`) is read as it stands: it is never
        ;; stored, so there is nothing to load
        row0 (if (map? grant-id)
               grant-id
               (when grant-id (load-decoded eng :grant grant-id)))
        grant-id (if (map? grant-id) (:id grant-id) grant-id)
        ;; a person bound by subject presents the grant their MEMBER
        ;; row holds (ticket 36fce73d): it is theirs, read with the
        ;; audience as their principal, as `person-worn-visibility`
        ;; wears it. The member read is paid only on a mismatch
        aud (some-> (get-in row0 [:data :audience]) str)
        row0 (if (and row0 aud (not= aud (some-> pid str))
                      (= aud (member-audience eng principal)))
               (assoc-in row0 [:data :audience] pid)
               row0)
        own? (boolean (and row0 (= (get-in row0 [:data :audience]) pid)))
        named? (and (some? pid) (not= pid (:id t/anonymous)))
        now ((:now-fn eng))
        live? (boolean (and own? (active? row0 now)))
        seat (when (and live? (seat-cited row0))
               (resolve-seat eng row0 principal now sitting-id))
        row (if seat (assoc-in row0 [:data :scope] (:scope seat)) row0)
        ;; an entry past its `until` is absent from this instant on, in
        ;; a seat's scope and in a grant's own (ticket b177a14b)
        row (cond-> row
              (seq (get-in row [:data :scope]))
              (update-in [:data :scope] #(live-entries now %)))
        ;; a :team-of entry is read at this request, into a filter both
        ;; halves below read (docs/spec-teams.md §6)
        row (cond-> row
              (some :team-of (get-in row [:data :scope]))
              (update-in [:data :scope]
                         #(resolve-team-of eng
                                           (if named? (set (members/spellings-of eng pid)) #{})
                                           %)))
        ;; a :names-me entry likewise: one entry per field, filtered to
        ;; the ids this principal answers to (docs/spec-teams.md §7)
        row (cond-> row
              (some :names-me (get-in row [:data :scope]))
              (update-in [:data :scope]
                         #(resolve-names-me
                           (if named? (set (members/spellings-of eng pid)) #{})
                           %)))
        surface (if live? (prune-unusable eng (surface-of row)) dead)
        ;; whole-kind sight is a PER-ENTRY question, judged on the
        ;; SCOPE, never on surface-of's output: surface-of absorbs
        ;; :ids and :filters INDEPENDENTLY (openness in one entry
        ;; erases a sibling's narrowing of that dimension), so an
        ;; ids-narrowed entry beside a filter-narrowed one leaves
        ;; both nil there and would read as the whole kind while
        ;; neither entry ever conferred it. One entry with neither
        ;; narrowing is whole-kind sight; two half-narrowed ones are
        ;; not.
        whole-kinds (if live?
                      (into #{}
                            (keep (fn [e]
                                    (when (and (nil? (:ids e))
                                               (nil? (:filter e)))
                                      (name (:kind e)))))
                            (get-in row [:data :scope]))
                      #{})
        ;; the own-surface, read off the registry (spec-decision-kind
        ;; seam 2). Three hand-written case blocks used to stand here,
        ;; each enumerating the same seven kind names in a different
        ;; order, and a fourth copy lived in the test packs. They are
        ;; now one lookup and one path walk, so a kind that declares
        ;; :own-surface — core or app, decision or not — is visible to
        ;; its own principal on the day it is declared
        surfaces (own-surfaces eng)
        own-of (fn [k] (when named? (get surfaces k)))
        own-kind? (fn [k] (some? (own-of k)))
        ;; every id the principal answers to (waymark-27j), read once
        ;; and only when a row GET actually asks
        spellings (delay (set (members/spellings-of eng pid)))
        ;; the person a delegate acts for (oidc.clj's :acts-for), and
        ;; every id that person answers to — read only for a kind whose
        ;; own-surface opts in with :delegates true. The private kinds
        ;; (self, journal, letter) do not, so a tool a person signs in
        ;; to never reads its person's own private rows
        person (when (= :agent (:type principal))
                 (some-> (:acts-for principal) str not-empty))
        person-spellings (delay (if person
                                  (set (members/spellings-of eng person))
                                  #{}))
        own-row? (fn [k id]
                   (when-some [os (own-of k)]
                     (if (:all os)
                       ;; the vocabulary posture: the registry's rows
                       ;; are everyone's words, so existing is owning
                       (some? (load-decoded eng (keyword k) id))
                       ;; a row is yours if ANY declared branch names
                       ;; you — one-party for a grant or an ask, two
                       ;; for a letter (yours as its author OR as its
                       ;; recipient; a third agent's foreign letter
                       ;; falls out of :row? and 404s like any
                       ;; un-granted one)
                       (when-some [row (load-decoded eng (keyword k) id)]
                         (let [pids (cond-> @spellings
                                      (:delegates os) (into @person-spellings))]
                           (boolean (some #(branch-owns? row % pids)
                                          (:by os))))))))
        ;; the two closures the GUARD-side of the leash consults
        ;; (waymark-sfe) are bound here rather than only in the map
        ;; below, because `:grant` — the guard's-eye view of the
        ;; presented grant — is built out of them. Naming them once
        ;; is what keeps `unless-granted` reading the SAME admission
        ;; the router's concealment reads, instead of a second
        ;; definition of what a scope admits.
        row?* (fn [kind id]
                (let [k (name kind)]
                  (boolean
                   (if-some [e (get surface k)]
                     (and (or (nil? (:ids e)) (contains? (:ids e) (str id)))
                          ;; filter-scoped: the row itself is the judge —
                          ;; one load per check, paid only by filtered
                          ;; entries; a row outside the filter is the
                          ;; same 404 as a row outside the ids
                          (or (nil? (:filters e))
                              (when-some [row (load-decoded eng (keyword k) id)]
                                (row-matches? row (:filters e)
                                              (get (inv/resources eng) (keyword k))))))
                     (own-row? k id)))))
        action?* (fn [kind action]
                   (let [k (name kind) a (name action)]
                     (or (contains? (get-in surface [k :actions] #{}) a)
                         ;; the own-surface affordances: filing an ask
                         ;; and its verdict doors, ACCEPTING an offered
                         ;; grant (since the agent default, waymark-rci,
                         ;; leaves no unscoped moment in which to accept,
                         ;; and an offer its audience cannot take is dead
                         ;; law), writing one's own dwelling rows, posting
                         ;; and opening a letter. Each is row-gated to a
                         ;; row the principal SEES (own-row? above) and
                         ;; each kind's own guards narrow further where
                         ;; they must — a self-judging requester meets the
                         ;; four-eyes guard's honest 409, never a mute
                         ;; 404; a letter's recipient guards keep
                         ;; open/discard to the addressee alone. The list
                         ;; used to be a per-kind case block here; it is
                         ;; now each kind's own :own-surface :actions, so
                         ;; the affordance and the law it opens live in
                         ;; one file
                         (contains? (:actions (own-of k) #{}) a))))
        ;; the same question asked of ONE ROW. A door the scope names
        ;; opens on every row the scope shows. A door only the
        ;; own-surface confers opens on the principal's own rows and
        ;; no others: when the scope also names the kind, :row? shows
        ;; rows that are not theirs, so the row is asked of own-row?
        ;; here. A create has no row (nil id) and stays kind-level.
        ;; A READER WITH NO ROW IN HAND (the schema digest, a
        ;; collection's doors, a mirror's sync trigger, a walk's create
        ;; form, a refusal's remedy) asks :action? and is told of the
        ;; owner's doors under a grant that names the kind: they open
        ;; on the reader's own rows, and nil id here answers the same.
        ;; Which row a door opens on is the row's reader's to say
        action-on?* (fn [kind action id]
                      (let [k (name kind) a (name action)]
                        (boolean
                         (or (and (contains? (get-in surface [k :actions] #{}) a)
                                  ;; the scope names the door: it opens
                                  ;; on the rows of an entry that names it
                                  (or (nil? id)
                                      (door-on-row? eng k (get surface k) a id)))
                             (and (contains? (:actions (own-of k) #{}) a)
                                  (or (nil? id)
                                      (not (contains? surface k))
                                      (own-row? k id)))))))]
    {:grant-id (str grant-id)
     :surface surface
     :own? own?
     ;; THE SEAT THIS REQUEST SAT IN, and the wall it met if it met one
     ;; (R-5.2, R-7.7): {:id :halt :reason :detail :scope}, nil for
     ;; every grant that cites no seat. The ROUTER reads it and writes
     ;; the seat's halt — the write is a request's act, not a
     ;; resolution's, and resolving is the one thing this function
     ;; does everywhere it is called, including from a preview.
     :seat seat
     ;; THE GUARD'S-EYE VIEW (waymark-sfe). Present only when a LIVE
     ;; grant conferred a surface: a dead, foreign, unknown or absent
     ;; grant leaves it nil, and `unless-granted` refuses an agent that
     ;; carries none — dead means scoped-to-nothing here exactly as it
     ;; does at the router. It rides the invoke ctx as `(:grant ctx)`
     ;; and the render probe's ctx alongside it, so advertisement and
     ;; enforcement read one fact.
     ;;
     ;; :substitute rides BESIDE the two closures (R-8.2) because a
     ;; substitute's bar is not a scope question — the three kinds it
     ;; bars are private own-surface kinds no scope can name — so the
     ;; guard that judges it needs the one fact the scope cannot
     ;; carry: whose hand is on this leash, the seat's own sitter or a
     ;; stand-in.
     :grant (when (and live? (seq surface))
              {:id (str grant-id) :action? action?* :row? row?*
               :action-on? action-on?*
               ;; a field this leash shows plain, neither hidden nor
               ;; hashed: what a scheduled action's conditions may
               ;; name (docs/spec-scheduled-actions.md R-2.3)
               :plain? (fn [kind field]
                         (let [k (name kind) f (name field)]
                           (if-some [e (get surface k)]
                             (and (admits? (:fields e) f)
                                  (not (contains? (:hashed e) f)))
                             (own-kind? k))))
               ;; a power token this leash names: what a scheduled
               ;; power call may name (docs/spec-scheduled-actions.md
               ;; R-4.4)
               :power? (fn [token] (contains? surface (str token)))
               :substitute (true? (get-in row [:data :substitute]))})
     :kind? (fn [kind]
              (let [k (name kind)]
                (or (contains? surface k) (own-kind? k))))
     ;; the honest KIND-LEVEL sight (waymark-tti.4 — presence's
     ;; collection-frame widening, the intents widening and seasons'
     ;; projection all consult THIS one closure): true only when SOME
     ;; single scope entry admits the WHOLE kind, neither :ids nor
     ;; :filter narrowing it. Deliberately NOT :kind? (which also
     ;; answers for narrowed entries and the own-kinds courtesy
     ;; surface) and never a :row?-sampling approximation — granted
     ;; sight of SOME rows is not sight of the collection.
     :whole-kind? (fn [kind] (contains? whole-kinds (name kind)))
     :row? row?*
     :action? action?*
     :action-on? action-on?*
     :field? (fn [kind field]
               (let [k (name kind)]
                 (if-some [e (get surface k)]
                   ;; hashed is a disposition of ADMISSION: the field
                   ;; is seen — as a token (waymark-rci)
                   (or (admits? (:fields e) (name field))
                       (contains? (:hashed e) (name field)))
                   ;; the own surface renders whole — its rows are the
                   ;; principal's own record
                   (own-kind? k))))
     :hashed? (fn [kind field]
                (contains? (get-in surface [(name kind) :hashed] #{})
                           (name field)))
     :hash (fn [kind field value]
             (field-hash eng kind field value))
     :arg? (fn [kind action arg]
             (let [k (name kind)]
               (if-some [e (get surface k)]
                 (admits? (get-in e [:args (name action)]) (name arg))
                 (own-kind? k))))
     ;; the filter entry's query half: conds the collection ANDs into
     ;; its search, so listings, totals and facets tell the same story
     ;; row? tells. One filtered entry is a conjunction, as it always
     ;; was; several are ONE :any-of cond over a conjunction each, the
     ;; union row? reads (ticket d4211f9a)
     :conds-of (fn [kind]
                 (when-some [fms (get-in surface [(name kind) :filters])]
                   (let [rdef (get (inv/resources eng) (keyword (name kind)))
                         conds-for
                         (fn [fm]
                    (vec (for [[f v] fm
                              ;; a comma value on an :in field is any
                              ;; of — `row-matches?` reads it the same
                              :let [vs (filter-values rdef f v)
                                    one (cond
                                          ;; a :team-of naming nobody (a
                                          ;; retired team): no row's state
                                          ;; is empty, so it admits none
                                          (empty? vs) {:target :state :op := :value ""}
                                          (next vs) {:op :in :values vs}
                                          :else {:op := :value (first vs)})]]
                          ;; :vis? marks the cond as the LEASH, never a
                          ;; client filter — facet counting strips a
                          ;; field's own client conds so options don't
                          ;; collapse, and must never strip this one
                          ;; (hospitality audit, guest walk #3)
                          (if (= "state" (name f))
                            ;; the state column, not the document
                            ;; (bead waymark-fp62.12) — `row-matches?`
                            ;; reads the same column, and facet
                            ;; counting already knows a :state cond
                            (merge {:target :state :vis? true} one)
                            (merge {:target :data :field (keyword (name f))
                                    :cast "text" :vis? true}
                                   one)))))
                         fms (vec (distinct fms))]
                     (if (next fms)
                       [{:op :any-of :vis? true :of (mapv conds-for fms)}]
                       (conds-for (first fms))))))
     :ids-of (fn [kind]
               (let [k (name kind)]
                 (if-some [e (get surface k)]
                   (some-> (:ids e) sort vec)
                   ;; the third and last of the case blocks, gone the
                   ;; same way: each kind's declared branches ARE the
                   ;; query, unioned. nil (the :all posture) is
                   ;; unrestricted — the whole registry lists
                   (when-some [os (own-of k)]
                     (own-ids eng (keyword k) os pid person)))))})))

(defn- sitting-grant
  "The grant the sitting `sitting-id` was opened under, when it still
  stands for `audience`: accepted or offered, and unexpired by the live
  clock, as `standing-grant-for` judges. nil otherwise."
  [eng audience sitting-id]
  (when-some [gid (some-> sitting-id str not-empty
                          (->> (load-decoded eng :sitting))
                          (get-in [:data :grant]) str not-empty)]
    (when-some [g (load-decoded eng :grant gid)]
      (let [exp (get-in g [:data :expires_at])]
        (when (and (= (str audience) (str (get-in g [:data :audience])))
                   (#{:accepted :offered} (:state g))
                   (or (nil? exp) (neg? (compare ((:now-fn eng)) exp))))
          g)))))

(defn worn-visibility
  "The visibility a principal that can present NO grant header arrives
  with — the connector door's delegate (docs/spec-connector-door.md
  § 3): the standing grant for its own id, accepted as the audience on
  arrival when it still stands :offered (the guest door's courtesy,
  the same two calls /auth/agent makes once per session — paid here
  per request, because the transport is stateless; the recorded
  cost). nil when nothing stands, and the caller falls to the agent
  default: the bootstrap surface, never full sight.

  THE VOCABULARY STAYS OPEN on a worn grant (waymark-kkx.6). A
  presented grant narrows well-known to its own kinds, and that
  posture's justification is that the agent wanting the vocabulary
  back drops its header for one read. A delegate never presented a
  header and cannot drop one: narrowed, it could not NAME a kind it
  was not granted, and the asking loop would close after its first
  grant. So the flag bootstrap-visibility sets rides here too — names
  are schema, not data; rows outside the grant stay concealed exactly
  as before, because every other check reads the grant, not the flag.
  `sitting-id`, when given, is the calling sitting (see `visibility`).

  THE CALLING SITTING'S OWN GRANT IS WORN FIRST (ticket acc89add):
  a sit that minted a fresh grant beside a near-expired accepted one
  must hand its run the fresh one, or the run loses its powers with
  the old grant. The newest standing grant answers only when the
  sitting's grant no longer stands, or no sitting is named."
  ([eng principal] (worn-visibility eng principal nil))
  ([eng principal sitting-id]
   (when-some [row (or (sitting-grant eng (:id principal) sitting-id)
                       (standing-grant-for eng (:id principal)))]
     (when-some [row (accept-as-audience! eng row principal)]
       (when (= :accepted (:state row))
         (assoc (visibility eng (:id row) principal sitting-id)
                :vocabulary-open? true))))))

(defn bootstrap-visibility
  "The agent default (waymark-rci): a named agent that presents NO
  grant runs scoped to the own-grant surface — the asking door and
  nothing else; sight is always negotiated, never assumed. The same
  closures a dead grant confers, plus :vocabulary-open? — well-known
  keeps the FULL kind/action vocabulary for this one posture, because
  an agent that cannot name a kind cannot compose its ask (names are
  schema, not data; rows stay concealed). A granted request keeps
  today's narrowed well-known: the agent that wants the vocabulary
  back simply drops its grant header for that one read."
  [eng principal]
  (assoc (visibility eng nil principal) :vocabulary-open? true))

(defn- person-member
  "The member row of the person `principal` is: the row of that id, or
  the row a binding stamped with it as `subject`. nil for an agent, a
  system actor, anyone without a member row, and on an engine that
  serves no members."
  [eng principal]
  (when-not (contains? #{:agent :system} (:type principal))
    (when-some [pid (some-> (:id principal) str not-empty)]
      (when-some [rdef (get (inv/resources eng) :member)]
        (or (load-decoded eng :member pid)
            (some->> (first (store/with-tx (:storage eng)
                              (fn [tx]
                                (store/query-rows (:storage eng) tx :member
                                                  {:subject pid} {:limit 1}))))
                     (inv/decode-row rdef)))))))

(defn member-audience
  "The id a grant on the person `principal` is addressed to when it is
  their member row's (ticket 36fce73d): that row's id, which differs
  from the principal id for a person bound by subject. nil wherever
  `person-member` answers nil."
  [eng principal]
  (some-> (person-member eng principal) :id str not-empty))

(defn- requester-member
  "The member row of the person `principal` is, when that row holds the
  `requester` role. nil for an agent, a system actor, anyone else, and
  on an engine that serves no tickets."
  [eng principal]
  (when (contains? (inv/resources eng) :ticket)
    (when-some [m (person-member eng principal)]
      (when (contains? (set (get-in m [:data :roles])) roles/requester)
        m))))

(def ^:private never-narrowed-roles
  "The roles whose holder a standing grant never narrows (ticket
  152ff81d): the owner must not lock themselves out by being the
  audience of some grant. `approver` is held_calls' `approver-role`,
  spelled here because grants sits below that namespace."
  #{"approver" "recovery-admin"})

(defn- never-narrowed?
  "Does the person `principal` hold a role `never-narrowed-roles`
  names — on the principal itself, or on their member row?"
  [eng principal]
  (boolean
   (or (some never-narrowed-roles (:roles principal))
       (some never-narrowed-roles
             (get-in (person-member eng principal) [:data :roles])))))

(defn- person-worn-visibility
  "The visibility a person wears from the standing grant on their
  MEMBER row (ticket dbcc3fc5). A person bound by subject keeps their
  own principal id, but the grant is addressed to the member id: the
  grant is found by `member-id`, accepted as that audience (the
  acceptance is still the audience's own act), and worn with the
  audience read as the person's principal, so their own surface stays
  the rows that name them. nil when nothing stands or it is not
  accepted."
  [eng principal member-id]
  (when-some [g (standing-grant-for eng member-id)]
    (let [row (accept-as-audience! eng g (assoc principal :id member-id))]
      (when (= :accepted (:state row))
        (assoc (visibility eng (assoc-in row [:data :audience] (:id principal))
                           principal)
               :vocabulary-open? true)))))

(defn requester-visibility
  "What a person holding `requester` sees (epic 46831b3c, R-3.4): the
  ticket kind narrowed to the rows whose `filed_by` is their member
  id, and its create door — the scope of a grant that is never stored,
  read through `visibility` like any presented one, so a requester's
  get and query meet the same narrowing every scoped reader meets.

  A requester KEEPS the own-surface `visibility` adds for every named
  principal (their own asks, grants, sittings, letters ...): it is
  the asking door, and a requester who wants more than their own
  tickets asks for it there like anyone else. It shows them only rows
  that already name them, so it widens nothing past their own."
  [eng principal member]
  (visibility eng {:id (str "role:" roles/requester)
                   :state :accepted
                   :data {:audience (:id principal)
                          :scope [{:kind "ticket"
                                   :actions ["create"]
                                   :filter {"filed_by" (str (:id member))}}]}}
              principal))

(defn unscoped-visibility
  "The visibility a principal arrives with when it presents NO grant —
  nil for a human or a system actor (unscoped sight, the router's
  `visibility-of` answering nothing), the bootstrap surface for a
  named agent, which never runs unscoped. A PERSON WHO HOLDS
  `requester` is the one human who is not unscoped: they arrive with
  `requester-visibility`, their own tickets and the create door. The
  cost is one member read per request a person makes.

  This is `wrap-identity`'s own else-branch, and it lives here rather
  than inline there because a SECOND door now needs it for somebody
  other than the caller: `feed.preview_as` (retired 2026-09) had to build the visibility
  the previewed member would arrive with, and the only honest way to
  do that is to call the same expression the gate calls. A preview
  that re-derived 'what a member can see' would be a second definition
  of sight, correct on the day it was written and wrong on the day the
  agent default changes.

  The DELEGATE (docs/spec-connector-door.md § 3) is the one agent that
  arrives with no grant because it CANNOT present one — a person's
  tool at the MCP door carries a bearer and nothing else. For a
  principal the resolver marked :acts-for, the engine looks for the
  worn grant itself (worn-visibility, on the delegate's own id) and
  falls to the bootstrap surface when nothing stands. Any other agent
  can present the header, so it must; waymark-rci's meaning for them
  is untouched. The rule lives here, in the one expression, for the
  reason the paragraph above gives.

  A PERSON WITH A STANDING GRANT WEARS IT (ticket 152ff81d): a human
  the owner scoped with a grant arrives with `worn-visibility`, exactly
  as a delegate does — accepted on first arrival when it stands
  :offered — whichever door they signed in through. A grant on a
  person is no longer ignored. A person with no standing grant is
  unchanged (unscoped, or a requester's own tickets), and a person
  holding `approver` or `recovery-admin` is never narrowed this way:
  they keep today's sight unless they present the header. The rule
  reads the member row: a member whose `actor_type` is agent, named
  without the actor-type header (a seed's agent driven through a
  clone door), is not a person and keeps today's sight. The cost is
  one grant query per request a person makes.

  THE GRANT IS THE MEMBER ROW'S (ticket dbcc3fc5): a person bound by
  invite carries their sign-in subject as principal id, while the
  grant names the member id; `person-worn-visibility` finds it there.
  And it FAILS CLOSED: a person with any standing grant that cannot
  be worn — the accept refused, any error — arrives with the bootstrap
  surface, never unscoped. Unscoped stays only for a person with no
  standing grant at all."
  [eng principal]
  (cond
    (= :agent (:type principal))
    (or (when (:acts-for principal)
          (worn-visibility eng principal))
        (bootstrap-visibility eng principal))

    (= :system (:type principal))
    nil

    :else
    (let [m (person-member eng principal)
          mid (when (and m
                         (not= "agent" (get-in m [:data :actor_type]))
                         (not (never-narrowed? eng principal)))
                (some-> (:id m) str not-empty))]
      (if (and mid (standing-grant-for eng mid))
        (or (try (person-worn-visibility eng principal mid)
                 (catch Exception _ nil))
            (bootstrap-visibility eng principal))
        (when-some [m (requester-member eng principal)]
          (requester-visibility eng principal m))))))

(defn capability-entry
  "The live surface entry a PRESENTED grant confers for one dotted
  capability token, or nil (waymark-iqa.23 — the in-house enforcement
  point's own read of the law it is about to apply).

  `visibility` has already judged audience, acceptance, expiry and
  revocation, so every one of those refusals has collapsed into this
  single nil by the time an enforcement point looks; and nil is also
  what an unscoped human gets, because a capability is worn, never
  inherited from being trusted. The entry's `:filters` is the
  CONSTRAINT — waymark validated its shape and carried it, and the
  meaning is the reader's, exactly as for an external capability."
  [vis token]
  (when vis (get (:surface vis) (str token))))

;; ── who else holds a door (the quest's `waiting_on`) ────────────────

(def ^:private takers-cap
  "The most seat rows, and the most grant rows, one `door-takers` reads."
  200)

(defn- scope-admits-door?
  "Does this scope admit `action` on the row `id` of `kind`? One scope
  read as `visibility` reads a live grant's: the same surface, the same
  pruning, and its `:row?`'s judgment of ids and filters. A nil `id`
  names no row, so the kind and the action alone are judged."
  [eng scope kind action id]
  (let [k (name kind)
        ;; no audience here: a :team-of naming no team admits nothing,
        ;; and a :names-me names nobody
        scope (resolve-names-me #{} (resolve-team-of eng #{} (mapv #(update % :kind scope-name) scope)))
        e (get (prune-unusable eng (surface-of {:data {:scope scope}})) k)]
    (boolean
     (and e
          (contains? (:actions e) (name action))
          (or (nil? id)
              (and (or (nil? (:ids e)) (contains? (:ids e) (str id)))
                   (or (nil? (:filters e))
                       (when-some [row (load-decoded eng (keyword k) id)]
                         (row-matches? row (:filters e)
                                       (get (inv/resources eng) (keyword k)))))
                   (door-on-row? eng k e action id)))))))

(defn- display-of
  "The display name of the member a principal id names: the row of that
  id, or the row a binding stamped with it as `subject`; the raw id
  when no member row answers."
  [eng pid]
  (or (when-some [rdef (get (inv/resources eng) :member)]
        (nonblank
         (get-in (or (load-decoded eng :member pid)
                     (some->> (first (store/with-tx (:storage eng)
                                       (fn [tx]
                                         (store/query-rows (:storage eng) tx :member
                                                           {:subject pid} {:limit 1}))))
                              (inv/decode-row rdef)))
                 [:data :display])))
      pid))

(defn door-takers
  "Who can take `action` on the row `id` of `kind` right now, by display
  name, `except` one principal: each active seat whose scope admits it,
  then each principal holding an accepted, unexpired grant whose own
  scope admits it. A seat grant is its seat's entry and is read there.
  The own-surface kinds are nobody's by grant, so they name nobody.

  The engine's own read: it conceals nothing, so it is for a sentence
  that says whom a blocked step waits on and never for a scope a caller
  did not name. At most `takers-cap` rows of each kind are read, oldest
  first; no index serves a scope's kind."
  [eng kind action id except]
  (let [st (:storage eng)
        now ((:now-fn eng))
        mine (set (members/spellings-of eng except))
        rows (fn [k where]
               (when-some [rdef (get (inv/resources eng) k)]
                 (mapv #(inv/decode-row rdef %)
                       (store/with-tx st
                         (fn [tx]
                           (vec (store/query-rows st tx k where {:limit takers-cap})))))))
        seats (for [s (rows :seat {:state :active})
                    :when (and (not (contains? mine (str "seat:" (:id s))))
                               (scope-admits-door?
                                eng
                                (without-entries (live-entries now (get-in s [:data :scope]))
                                                 (get-in s [:data :stale]))
                                kind action id))]
                (nonblank (get-in s [:data :name])))
        holders (for [g (rows :grant {:state :accepted})
                      :let [aud (nonblank (get-in g [:data :audience]))]
                      :when (and aud
                                 (not (seat-cited g))
                                 (active? g now)
                                 (not (contains? mine aud))
                                 (scope-admits-door? eng (get-in g [:data :scope])
                                                     kind action id))]
                  (display-of eng aud))]
    (into [] (comp (remove nil?) (distinct)) (concat seats holders))))

;; ── enforcement helpers (the router's consults) ─────────────────────

(defn plain-field?
  "Does this visibility admit the field in PLAIN sight — visible and
  not hashed? The predicate filters and sorts must pass: a hashed
  token neither matches raw values nor orders honestly."
  [vis kind f]
  (and ((:field? vis) kind f)
       (not ((:hashed? vis) kind f))))

(defn check-query!
  "The collection oracle, closed (waymark-rci): a REQUESTED filter or
  sort naming a field this visibility does not admit plain — hidden
  and hashed alike — answers the SAME 422 an unknown parameter draws,
  so a probing client cannot tell 'denied to you' from 'never
  existed'. This also closes the pre-existing hole for hidden fields:
  a filtered count was a value oracle. The kind's own DEFAULT sort is
  the caller's to soften (fall back, never refuse — the client asked
  for nothing). nil visibility checks nothing; :id and :state stay
  machine, not data."
  [vis rdef conds requested-sort]
  (when vis
    (let [kind (:kind rdef)
          ;; a data cond's judged NAME is its :field — :target is the
          ;; storage column (:data), which is not a field and judged
          ;; every data filter with one wrong answer (hospitality
          ;; audit, guest walk #2: the oracle stood open for hashed
          ;; filterable fields and 422'd every filter under an
          ;; allow-list)
          bad (into []
                    (comp (keep (fn [c]
                                  (if (= :data (:target c))
                                    (:field c)
                                    (:target c))))
                          (remove #{:id :state})
                          (remove #(plain-field? vis kind %))
                          (map name))
                    conds)
          bad (cond-> bad
                (and requested-sort
                     (not (#{:id :state} requested-sort))
                     (not (plain-field? vis kind requested-sort)))
                (conj (str "sort=" (name requested-sort))))]
      (when (seq bad)
        (throw (p/problem :invalid-params 422 "Unknown parameters"
                          {:detail (str "Unknown parameter(s): "
                                        (str/join ", " (distinct bad)))}))))))

(defn check-args!
  "Batch B's arg enforcement: a denied argument arriving in a body —
  dry-run included — answers exactly the 422 an unknown field draws
  (malli's closed-map words), so 'denied to you' and 'never existed'
  are one answer. A nil visibility (unscoped request) checks nothing.
  Recorded seam: this runs at the router boundary, AHEAD of invoke's
  step order — a denied arg 422s where a truly unknown field would
  first meet the fence or the state check; ordering can differ."
  [vis rdef action body]
  (when-some [arg? (:arg? vis)]
    (when (map? body)
      (when-some [denied (seq (remove (fn [[k _]] (arg? (:kind rdef) action k))
                                      body))]
        (throw (p/schema-invalid
                action
                (into {} (map (fn [[k _]] [k ["disallowed key"]])) denied)))))))

(defn project-json-schema
  "The published schema view under a grant (/api/schemas/{kind}):
  redacted fields are not in the schema — absent from properties and
  required alike. nil visibility returns the schema untouched."
  [vis kind js]
  (if-some [field? (:field? vis)]
    (let [dropped (into #{} (remove #(field? kind %)) (keys (:properties js)))
          hashed? (:hashed? vis)
          hashed (when hashed?
                   (into #{} (filter #(hashed? kind %)) (keys (:properties js))))
          js (if (empty? dropped)
               js
               (let [names (into #{} (map name) dropped)]
                 (-> js
                     (update :properties #(apply dissoc % dropped))
                     (update :required
                             (fn [r]
                               (some->> r (filterv #(not (contains? names (name %))))))))))]
      ;; a hashed field keeps its place but the published type tells
      ;; the truth: an opaque string token, never the declared shape
      (cond-> js
        (seq hashed)
        (update :properties
                #(reduce (fn [props f]
                           (if (contains? props f)
                             (assoc props f {:type "string" :x-hashed true})
                             props))
                         % hashed))))
    js))
