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
  scope, expires_at} through offered → approved/denied. ANY named
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
    to nothing); the :expire transition is bookkeeping anyone may run
    once the clock passes — no sweeper drives it (named punt).
  - idempotency-replay responses are stored bytes rendered without a
    visibility (the phase-3 render-fn seam's recorded punt, extended):
    a scoped replay serves the first execution's unprojected envelope.
  - own-id collections cap at 200 held grants/requests per principal
    (the member-visibility page waymark9 also capped)."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the scope schema (shared: grant, approval_request, extend) ──────

(def field-spec-schema
  "One mode spec: allow renders only :names, deny renders all but."
  [:map
   [:mode [:enum "allow" "deny"]]
   [:names [:vector [:string {:min 1 :max 64}]]]])

(def scope-schema
  [:vector
   [:map
    [:kind [:string {:min 1 :max 64}]]
    [:ids {:optional true}
     [:maybe [:vector [:string {:min 1 :max 64}]]]]
    [:actions [:vector [:string {:min 1 :max 64}]]]
    [:fields {:optional true} [:maybe field-spec-schema]]
    [:args {:optional true}
     [:maybe [:vector
              [:map
               [:action [:string {:min 1 :max 64}]]
               [:mode [:enum "allow" "deny"]]
               [:names [:vector [:string {:min 1 :max 64}]]]]]]]]])

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard audience-only
  {:reads [:principal]
   :explain "Only the grant's audience may accept it."}
  [row _inp ctx]
  (let [p (:principal ctx)]
    (if (or (= :system (:type p))
            (= (:id p) (get-in row [:data :audience])))
      (t/allow) (t/deny))))

(g/defguard no-self-dealing
  {:reads [:principal]
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
           :explain "The grant has not reached its expiry."
           :becomes-available-at (fn [row] (get-in row [:data :expires_at]))}))

(g/defguard approval-route-only
  {:reads [:principal]
   :hide true
   :explain "Scope extends only through an approved access request, never by hand."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

(defhandler extend-grant [row inp _ctx]
  (cond-> (update-in row [:data :scope] (fn [s] (vec (concat s (:scope inp)))))
    (:expires_at inp) (assoc-in [:data :expires_at] (:expires_at inp))))

;; ── the grant resource ──────────────────────────────────────────────

(defresource grant
  {:kind :grant
   :plural "grants"
   :states [:offered :accepted :revoked :expired]
   :initial :offered
   :terminal #{:revoked :expired}
   :nav :system
   :summary "Grant to {data.audience} · {state}"
   :schema [:map
            [:audience [:string {:min 1 :max 128}]]
            [:scope scope-schema]
            [:expires_at {:optional true} [:maybe :waymark/instant]]]
   :filterable {:state #{:eq :in}
                :audience #{:eq}}
   ;; the agent behind the leash — audience IS the member id for
   ;; bound/provisioned principals (beat 7: one navigation, not a
   ;; hand-built filter)
   :links [{:rel "member" :kind :member
            :href "/api/members/{data.audience}"
            :summary "The member this grant empowers"}]
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
             :input [:map
                     [:scope scope-schema]
                     [:expires_at {:optional true} [:maybe :waymark/instant]]]
             :record true
             :edit {:prefill [:scope :expires_at] :fence false
                    :unfenced-reason "Written once by the approval effect the moment its approve commits; there is no human form to clobber."}
             :guards [approval-route-only]
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
             :display {:label "Expire" :order 8}}}})

;; ── the approval_request resource (the negotiation machine) ─────────

(defn- load-decoded [eng kind id]
  (when-some [rdef (get (inv/resources eng) kind)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))
             (inv/decode-row rdef))))

(g/defguard requester-holds-the-grant
  {:reads [:principal :grant]
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

(g/defguard requester-is-named
  {:reads [:principal]
   :explain "An access request names its requester; an anonymous ask would grant nobody."}
  [_row _inp ctx]
  (if (= (:id (:principal ctx)) (:id t/anonymous))
    (t/deny) (t/allow)))

(def ask-pace-limit
  "Anchorless asks per rolling hour per principal — the generous
  ceiling on the one create any named principal may issue."
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
  those comes before a new one."
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

(g/defguard asks-are-short
  {:judges [:expires_at]
   :reads [:now]
   :vars [:max_hours :asked]
   :explain "A leash is short — at most {max_hours} hours; this ask runs to {asked}. Propose less; an approved follow-up ask can always extend."}
  [_row inp ctx]
  (if-some [^java.time.Instant exp (:expires_at inp)]
    (let [max-s (long (:grant-max-ttl-seconds (:services ctx) 86400))
          cap (.plusSeconds ^java.time.Instant (:now ctx) max-s)]
      (if (pos? (compare exp cap))
        (t/deny {:vars {:max_hours (quot max-s 3600) :asked (str exp)}})
        (t/allow)))
    (t/allow)))

(g/defguard someone-else-decides
  {:reads [:principal]
   :explain "The requester cannot judge its own ask; another principal decides."}
  [row _inp ctx]
  (if (= (:id (:principal ctx)) (get-in row [:data :requested_by]))
    (t/deny) (t/allow)))

(g/defguard grant-still-accepting
  {:reads [:grant]
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

(defresource approval-request
  {:kind :approval_request
   :plural "approval_requests"
   :states [:offered :approved :denied]
   :initial :offered
   :terminal #{:approved :denied}
   :nav :system
   :summary "Access request by {data.requested_by} · {state} · until {data.expires_at}"
   ;; grant_id is OPTIONAL: an anchorless ask is the bootstrap path —
   ;; its approval mints the grant and stamps the id here
   :schema [:map
            [:grant_id {:optional true :kind :grant} [:maybe :waymark/ref]]
            [:task [:string {:min 1 :max 240}]]
            [:scope scope-schema]
            [:expires_at {:optional true} [:maybe :waymark/instant]]
            ;; stamped by the engine (on-create / the approve handler);
            ;; never supplied by hand — the create schema omits them
            [:requested_by {:optional true :x-display {:raw true}}
             [:maybe [:string {:max 128}]]]
            [:approved_by {:optional true :x-display {:raw true}}
             [:maybe [:string {:max 128}]]]
            [:note {:optional true} [:maybe [:string {:max 240}]]]]
   :create-schema [:map
                   [:grant_id {:optional true :kind :grant}
                    [:maybe :waymark/ref]]
                   [:task [:string {:min 1 :max 240}]]
                   [:scope scope-schema]
                   [:expires_at {:optional true} [:maybe :waymark/instant]]]
   :filterable {:state #{:eq :in}
                :grant_id #{:eq}
                :requested_by #{:eq}}
   ;; the grant this ask anchors or (once approved) minted; a nil
   ;; grant_id omits the link — an unjudged bootstrap ask points at
   ;; nothing yet
   :links [{:rel "grant" :kind :grant
            :href "/api/grants/{data.grant_id}"
            :summary "The grant this request extends or minted"}]
   :create-guards [requester-is-named
                   requester-holds-the-grant
                   asks-are-paced
                   asks-are-few
                   asks-are-short]
   :on-create (fn [row ctx]
                (-> row
                    (assoc-in [:data :requested_by]
                              (get-in ctx [:principal :id]))
                    ;; short-lived is the DEFAULT, not an opt-in: an ask
                    ;; naming no expiry gets the engine's default TTL
                    ;; (1h; :grant-default-ttl-seconds) — stamped at
                    ;; create, so the approver approves the leash that
                    ;; will actually exist. An agent proposes longer at
                    ;; will up to the cap (asks-are-short,
                    ;; :grant-max-ttl-seconds, 24h); the approver sees
                    ;; the number either way.
                    (update-in [:data :expires_at]
                               #(or % (.plusSeconds
                                       ^java.time.Instant (:now ctx)
                                       (long (:grant-default-ttl-seconds
                                              (:services ctx) 3600)))))))
   :actions
   {:approve {:from #{:offered} :to :approved
              :guards [someone-else-decides grant-still-accepting]
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The requester's grant gains exactly the scope shown, immediately."}
              :handler stamp-approver
              :display {:label "Approve" :style :primary :order 1}}
    :deny {:from #{:offered} :to :denied
           :input [:map [:note {:optional true} [:maybe [:string {:max 240}]]]]
           :edit {:prefill [:note] :fence false
                  :unfenced-reason "A denial's note is written once with the verdict; a frozen offered ask has nothing to clobber."}
           :guards [someone-else-decides]
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A denied ask stays on record; asking differently is a new request."}
           :handler record-verdict-note
           :display {:label "Deny" :style :danger :order 9}}}})

;; ── the approve effect (the router's one grants seam) ───────────────

(def approvals-actor
  "The system actor an approved request's grant extension acts as."
  (t/principal {:id "waymark10-grants" :type :system
                :display "Grant approvals"}))

(defn approval-effects!
  "Post-commit, wire-boundary (the router calls this after every
  single invoke, the attachments put-bytes! precedent): a fresh,
  non-replayed approve on an :approval_request lands its grant —
  system actor, logged, keyed on the approval id so a redelivery
  replays instead of double-appending. The named grant EXTENDS when
  it exists (the anchored ask) and is MINTED when it does not (the
  anchorless ask, its id stamped by the approve handler): audience =
  requester, scope = exactly the approved ask, then accepted through
  the machine's own accept — the ask WAS the audience's consent, and
  the requester's next presentation of the stamped grant id scopes it
  in. A refusal here (the grant revoked between guard and effect) is
  warned on *err*, never thrown: the approval committed; the grant
  honestly did not move."
  [eng rdef action-name result]
  (when (and (= :approval_request (:kind rdef))
             (= :approve action-name)
             (:transition result)
             (nil? (:replayed? result)))
    (let [row (:row result)
          gid (get-in row [:data :grant_id])
          corr (get-in result [:transition :correlation-id])
          expires (get-in row [:data :expires_at])]
      (try
        (if (load-decoded eng :grant gid)
          (inv/invoke! eng :grant gid :extend
                       (cond-> {:scope (get-in row [:data :scope])}
                         expires (assoc :expires_at (str expires)))
                       {:principal approvals-actor
                        :correlation-id corr
                        :idempotency-key (str "approval-extend-" (:id row))})
          (do
            (inv/create! eng :grant
                         (cond-> {:audience (get-in row [:data :requested_by])
                                  :scope (get-in row [:data :scope])}
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
                 :args (args-of entries)}]))
        (group-by :kind (get-in row [:data :scope]))))

(def dead
  "The scoped-to-nothing surface a dead or unknown grant confers."
  {})

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

(def ^:private own-kinds
  "The negotiation surface's kinds — riding every named principal's
  scoped request, whatever the presented grant's fate: the asking
  door is never concealed, because it is how access starts."
  #{"grant" "approval_request"})

(defn- own-ids
  "The principal's own rows of one negotiation kind, as the id cond
  the collection pushes down. Never empty — an impossible id keeps an
  empty surface's total honestly zero (an empty IN would not parse)."
  [eng kind where]
  (let [ids (store/with-tx (:storage eng)
              (fn [tx]
                (mapv :id (store/query-rows (:storage eng) tx kind where
                                            {:limit 200}))))]
    (if (seq ids) (vec (sort ids)) ["-none-"])))

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
  diagnostics."
  [eng grant-id principal]
  (let [pid (:id principal)
        row (load-decoded eng :grant grant-id)
        own? (boolean (and row (= (get-in row [:data :audience]) pid)))
        named? (and (some? pid) (not= pid (:id t/anonymous)))
        surface (if (and own? (active? row ((:now-fn eng))))
                  (prune-unusable eng (surface-of row))
                  dead)
        own-kind? (fn [k] (and named? (contains? own-kinds k)))
        own-row? (fn [k id]
                   (when (own-kind? k)
                     (case k
                       "grant"
                       (= pid (some-> (load-decoded eng :grant id)
                                      (get-in [:data :audience])))
                       "approval_request"
                       (= pid (some-> (load-decoded eng :approval_request id)
                                      (get-in [:data :requested_by]))))))]
    {:grant-id (str grant-id)
     :surface surface
     :own? own?
     :kind? (fn [kind]
              (let [k (name kind)]
                (or (contains? surface k) (own-kind? k))))
     :row? (fn [kind id]
             (let [k (name kind)]
               (boolean
                (if-some [e (get surface k)]
                  (or (nil? (:ids e)) (contains? (:ids e) (str id)))
                  (own-row? k id)))))
     :action? (fn [kind action]
                (let [k (name kind) a (name action)]
                  (or (contains? (get-in surface [k :actions] #{}) a)
                      ;; the one own-surface affordance: filing an ask
                      (and (own-kind? k)
                           (= k "approval_request")
                           (= a "create")))))
     :field? (fn [kind field]
               (let [k (name kind)]
                 (if-some [e (get surface k)]
                   (admits? (:fields e) (name field))
                   ;; the own surface renders whole — its rows are the
                   ;; principal's own record
                   (own-kind? k))))
     :arg? (fn [kind action arg]
             (let [k (name kind)]
               (if-some [e (get surface k)]
                 (admits? (get-in e [:args (name action)]) (name arg))
                 (own-kind? k))))
     :ids-of (fn [kind]
               (let [k (name kind)]
                 (if-some [e (get surface k)]
                   (some-> (:ids e) sort vec)
                   (when (own-kind? k)
                     (own-ids eng (keyword k)
                              (case k
                                "grant" {:audience pid}
                                "approval_request" {:requested_by pid}))))))}))

;; ── enforcement helpers (the router's consults) ─────────────────────

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
    (let [dropped (into #{} (remove #(field? kind %)) (keys (:properties js)))]
      (if (empty? dropped)
        js
        (let [names (into #{} (map name) dropped)]
          (-> js
              (update :properties #(apply dissoc % dropped))
              (update :required
                      (fn [r]
                        (some->> r (filterv #(not (contains? names (name %)))))))))))
    js))
