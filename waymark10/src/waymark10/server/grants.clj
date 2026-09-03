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
    (the member-visibility page waymark9 also capped).
  - a scope entry may carry :filter {field value} — admission by
    MATCH (eq on a declared-filterable data field, never state; one
    filtered entry per kind), judged at the row (row?) and ANDed
    into the collection query (conds-of), so rows minted after the
    grant land inside the leash the moment they match. The worksheet
    export consumes both halves the way the collection does
    (waymark-ecq closed); the upload half still refuses scoped
    requests — staging lands rows the uploader cannot see."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── the scope schema (shared: grant, approval_request, extend) ──────

(def field-spec-schema
  "One mode spec: allow renders only :names, deny renders all but."
  [:map
   [:mode {:x-display {:label "Allow or deny"
                       :choices {"allow" "Allow — only the fields named here are readable"
                                 "deny" "Deny — every field except the ones named here"}}}
    [:enum "allow" "deny"]]
   ;; no :x-options here, deliberately: the vocabulary IS this kind's
   ;; data fields, but :of names a SIBLING and the kind is spelled one
   ;; level up, on the scope entry this spec hangs off. A recipe
   ;; reaching out of its own map is a hole no client could fill from
   ;; the form in front of it, so the sentence says where to look
   [:names {:x-display {:label "Field names"
                        :help "Data field names of the kind this entry names — the same names its published schema lists."}}
    [:vector [:string {:min 1 :max 64}]]]])

(def scope-example
  "Something in the textarea instead of a blank page (waymark-0ee's
  composition policy, waymark-7rw's turn at it). An example is offered
  and never applied: the shape of the smallest honest leash — one
  kind, one action, nothing else."
  [{:kind "task" :actions ["claim" "complete"]}])

;; The scope form is where a person decides whether to trust an agent,
;; so it is the last form in this codebase that should have been a
;; blank JSON textarea. Since waymark-8sg the two vocabularies it is
;; judged against can be named out loud (:x-options), and waymark-7rw
;; discovered that the spelling reaches INSIDE an array's item map with
;; no new capability at all: an item's fields are each other's
;; siblings, so :of resolves within the entry a person is filling in.
(def scope-schema
  [:vector
   [:map
    [:kind {:x-options {:from :kinds}
            :x-display {:label "Kind"
                        :help "The collection this entry opens — one kind name this engine serves."}}
     [:string {:min 1 :max 64}]]
    [:ids {:optional true
           :x-display {:label "Only these rows"
                       :help "Row ids, when the leash is a handful of documents rather than a collection. Omit it and the entry covers the whole kind (narrowed by any filter below)."}}
     [:maybe [:vector [:string {:min 1 :max 64}]]]]
    [:actions {:x-options {:from :actions :of :kind :each true}
               :x-display {:label "Actions"
                           :help "What may be done to those rows, by name. An empty list is the read-only ask — sight without a single lever."}}
     [:vector [:string {:min 1 :max 64}]]]
    [:fields {:optional true
              :x-display {:label "Field disposition"
                          :help "Which of the kind's data fields are readable at all — allow-list or deny-list. Omit it and every field the kind publishes is readable."}}
     [:maybe field-spec-schema]]
    ;; the third disposition (waymark-rci): a field named here renders
    ;; as a stable opaque token — correlate and reference, never read.
    ;; hidden = not admitted at all; hashed = admitted-as-token; read =
    ;; admitted plain. Hashing DOMINATES its kind's sibling entries —
    ;; a restriction is never absorbed the way openness absorbs.
    [:hashed {:optional true
              :x-options {:from :fields :of :kind :each true}
              :x-display {:label "Tokenised fields"
                          :help "Fields the holder may correlate but never read — each value crosses as a stable opaque token."}}
     [:maybe [:vector [:string {:min 1 :max 64}]]]]
    ;; the filter-scoped entry (waymark: pools as first-class): the
    ;; rows this entry admits are the ones MATCHING, judged at render
    ;; — rows minted after the grant land inside the leash the moment
    ;; they match. field → exact value, the collection grammar's :eq
    ;; only; the field must be one the kind declares filterable (the
    ;; scope-filters guard), so the promise is enforceable at the
    ;; query AND the row. Keys arrive keywordized off the wire.
    ;; no :x-options: the vocabulary here is the legal KEYS of an
    ;; object, and the recipe's two composition words (:each for a
    ;; token list, :composes :query for a field=value string) both
    ;; describe a value BUILT from tokens. Calling a map one or the
    ;; other would be a lie about the grammar, so the help sentence
    ;; carries it and waymark-3ox holds the honest spelling
    [:filter {:optional true
              :x-display {:label "Only rows matching"
                          :help "One field=value pair, judged at render — rows minted later land inside the leash the moment they match. The field must be one the kind declares filterable with eq; one filtered entry per kind."}}
     [:maybe [:map-of :keyword [:string {:min 1 :max 200}]]]]
    [:args {:optional true
            :x-display {:label "Argument limits"
                        :help "Per-action limits on WHICH arguments may be sent — {action, allow|deny, names}. Omit it and an admitted action takes any argument its schema accepts."}}
     [:maybe [:vector
              [:map
               ;; :of would have to reach the scope entry one level up
               ;; for the action vocabulary, the same wall
               ;; field-spec-schema's :names meets
               [:action {:x-display {:label "Action"
                                     :help "One of the action names this entry admits above."}}
                [:string {:min 1 :max 64}]]
               [:mode {:x-display {:label "Allow or deny"
                                   :choices {"allow" "Allow — only the arguments named here"
                                             "deny" "Deny — every argument except the ones named here"}}}
                [:enum "allow" "deny"]]
               [:names {:x-display {:label "Argument names"
                                    :help "Input field names of that action, as its published schema lists them."}}
                [:vector [:string {:min 1 :max 64}]]]]]]]]])

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
    (if-some [bad (some (fn [e]
                          (let [k (str (:kind e))]
                            (if (str/includes? k ".")
                              ;; a dotted token names a CAPABILITY
                              ;; (waymark-44h): judged against the
                              ;; active registry, not the routes — an
                              ;; engine without the registry, or a
                              ;; token it never registered, refuses
                              ;; the same way an unknown kind does
                              (when-not (and (:find ctx)
                                             (some #(= :active (:state %))
                                                   ((:find ctx) :capability
                                                    {:token k} {:limit 1})))
                                k)
                              (when (nil? (names-of k)) k))))
                        (:scope inp))]
      (t/deny {:vars {:kind bad}})
      (t/allow))
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

(g/defguard scope-filters-are-filterable
  {:judges [:scope]
   :reads [:services]
   :vars [:kind :field]
   :open "A grant filter narrows by a field the kind already declares filterable with eq — the collection grammar is the vocabulary; one filtered entry per kind."
   :explain "The kind {kind} cannot be filter-scoped by {field}: a filter names a data field the kind declares filterable (eq), never state, and only ONE entry may filter a kind."}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (let [entries (filter :filter (:scope inp))
          dup (->> entries (map :kind) frequencies
                   (some (fn [[k n]] (when (< 1 n) k))))
          bad (first
               (for [e entries
                     :let [rdef (rdef-of (:kind e))]
                     :when rdef
                     [f _] (:filter e)
                     :let [fname (name f)
                           ops (get (:filterable rdef) (keyword fname))]
                     :when (or (= "state" fname)
                               (not (contains? (or ops #{}) :eq)))]
                 {:kind (:kind e) :field fname}))]
      (cond
        dup (t/deny {:vars {:kind dup :field "(two filtered entries)"}})
        bad (t/deny {:vars bad})
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
(def ^:private private-own-surface-kinds
  "Kinds that live ONLY on the own-surface and can never be granted."
  #{"self" "journal" "letter"})

(defn private-kind?
  "Is this kind one of the private own-surface trio? The ephemeral
  surfaces ask (waymark-tti.3 L7): a reported presence/intent self
  naming one of these rows must pass the REPORTER's own sight, or a
  stranger could name a letter it 404s and have the frame delivered
  to exactly the two people who can read it."
  [kind]
  (contains? private-own-surface-kinds (name kind)))

(g/defguard scope-omits-private-kinds
  {:judges [:scope]
   :vars [:kind]
   :open "The private kinds are the own-surface-only trio (self, journal, letter); enumerating them into every scope form would duplicate a house rule the refusal already spells."
   :explain "self, journal and letter are private to their own members and cannot be granted; the {kind} entry is refused (these kinds ride the own-surface, where owner sees own, with no grant path)."}
  [_row inp _ctx]
  (if-some [bad (some (fn [e]
                        (let [k (str (:kind e))]
                          (when (contains? private-own-surface-kinds k) k)))
                      (:scope inp))]
    (t/deny {:vars {:kind bad}})
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
    (and (empty? (remove (:actions sk) (map str (:actions e))))
         (or (nil? (:ids sk))
             (and (seq (:ids e))
                  (every? (:ids sk) (map str (:ids e)))))
         (or (nil? (:filters sk))
             (and (:filter e)
                  (every? (fn [[f v]]
                            (= (str v)
                               (str (get (:filter e) (keyword (name f))))))
                          (first (:filters sk)))))
         (nil? (:fields sk))
         (empty? (:hashed sk))
         (empty? (:args sk)))))

(g/defguard agents-mint-within-their-leash
  {:judges [:scope]
   :reads [:principal :now :grant]
   :vars [:kind]
   :open "Delegation attenuates: an agent hands out only slices of the leash it holds — hold it first (an approved ask), then mint within it."
   :explain "An agent mints only within its own live leash: no grant you hold covers the {kind} entry as asked (kind, every action, every id, and at least your own filter must all fit inside one grant you hold)."}
  [_row inp ctx]
  (let [p (:principal ctx)]
    (if (or (not= :agent (:type p))
            (nil? (:find ctx)))          ; probe ctx — decline to guess
      (t/allow)
      (let [now (:now ctx)
            live (filter (fn [g]
                           (and (= :accepted (:state g))
                                (let [exp (get-in g [:data :expires_at])]
                                  (or (nil? exp) (neg? (compare now exp))))))
                         ((:find ctx) :grant {:audience (:id p)} {:limit 100}))
            surfaces (map surface-of live)
            bad (first (for [e (:scope inp)
                             :when (not (some #(entry-within-surface? e %)
                                              surfaces))]
                         {:kind (str (:kind e))}))]
        (if bad (t/deny {:vars bad}) (t/allow))))))

(g/defguard approval-route-only
  {:reads [:principal]
   :hide true
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
        flds (spelled :fields entries)]
    (cond-> {:kind (:kind (first entries))
             :actions (union-scope-names (map :actions entries))}
      (some? ids)         (assoc :ids ids)
      (seq hashed)        (assoc :hashed hashed)
      (seq args)          (assoc :args args)
      (first filt)        (assoc :filter (first filt))
      (first flds)        (assoc :fields (first flds)))))

(defn merge-scope
  "The scopes folded into ONE ENTRY PER KIND, kinds in first-seen
  order. Called with the grant's stored scope and the approved ask's,
  it is both the extension and the heal."
  [& scopes]
  (let [entries (into [] (comp cat (filter map?)) scopes)
        by-kind (group-by #(scope-name (:kind %)) entries)]
    (into [] (comp (map #(scope-name (:kind %))) (distinct)
                   (map #(merge-scope-entries (by-kind %))))
          entries)))

(defhandler extend-grant [row inp _ctx]
  (cond-> (update-in row [:data :scope] merge-scope (:scope inp))
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
            [:scope {:examples [scope-example]
                     :x-display {:label "What it opens"
                                 :help "The leash, entry by entry: a kind, the actions allowed on it, and optionally the rows, fields and filter that narrow it. Everything not named here stays shut."}}
             scope-schema]
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
            :summary "The member this grant empowers"}]
   ;; a hand-offered grant speaks the same vocabulary an ask must
   ;; (waymark-vnc): a scope naming a kind or action that does not
   ;; exist refuses at the door, never lands silently useless — and
   ;; an AGENT's hand mints only within its own leash (waymark9's
   ;; attenuation ceiling, landed at the mint): delegation
   ;; attenuates, never widens; the widening path stays the ask
   :create-guards [scope-names-real-kinds scope-names-real-actions
                   scope-filters-are-filterable
                   scope-omits-private-kinds
                   agents-mint-within-their-leash]
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
             ;; the scope pair rides behind the hide guard: a
             ;; non-system probe conceals before any scope truth could
             ;; narrate; the system caller (an approve whose ask was
             ;; validated at create) meets them only when the registry
             ;; changed in between — approval-effects! catches and
             ;; warns, the grant honestly does not move
             :guards [approval-route-only
                      scope-names-real-kinds scope-names-real-actions
                      scope-filters-are-filterable
                      scope-omits-private-kinds]
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
    :explain "The requester cannot judge its own ask; another principal decides."}))

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
    :decider {:not :requested_by         ; the field wall, not four-eyes:
              ;; a decision row's requester is stamped by :on-create,
              ;; before any transition exists to be the actor of
              :name :someone-else-decides
              :explain "The requester cannot judge its own ask; another principal decides."}
    :stamps  {:decided-by :approved_by}
    ;; short-lived is the DEFAULT, not an opt-in: an ask naming no
    ;; expiry gets the engine's configured TTL (1h), stamped AT
    ;; CREATE so the approver approves the leash that will actually
    ;; exist. An agent proposes longer at will up to the cap; the
    ;; approver sees the number either way.
    :expires {:field :expires_at
              :default {:service :grant-default-ttl-seconds :seconds 3600}
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
      :guards [grant-still-accepting]
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
   ;; mints the grant and stamps the id here) and the scope it asks for
   :schema [:map
            [:grant_id {:optional true :kind :grant
                        :x-display {:label "Widen this grant"
                                    :help "The grant you already hold and want more of. Leave it empty for the bootstrap ask — an approval then mints a fresh grant in your name."}}
             [:maybe :waymark/ref]]
            [:scope {:examples [scope-example]
                     :x-display {:label "What you are asking for"
                                 :help "The leash you want, entry by entry: a kind, the actions on it, and optionally the rows, fields and filter that narrow it. Ask for the least that does the job — an approver reads this."}}
             scope-schema]]
   :filterable {:grant_id #{:eq}}
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
   :create-guards [requester-is-named
                   requester-holds-the-grant
                   asks-are-paced
                   asks-are-few
                   asks-are-short
                   ;; the honesty gate (waymark-vnc): an ask for a
                   ;; (kind, action) that does not exist refuses NOW,
                   ;; naming the kind's real actions — never approved
                   ;; into a scope that can never match
                   scope-names-real-kinds
                   scope-names-real-actions
                   scope-filters-are-filterable
                   scope-omits-private-kinds]
   :scenarios [the-asker-does-not-decide another-principal-may-deny]})

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
  honestly did not move."
  [eng rdef action-name result]
  (when (and (contains? wire-boundary-effects [(:kind rdef) action-name])
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
                                  ;; merged at the mint too, so a grant's
                                  ;; stored scope has ONE shape wherever it
                                  ;; came from (waymark-ycp)
                                  :scope (merge-scope (get-in row [:data :scope]))}
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
                 ;; hashing dominates: any entry naming a field hashed
                 ;; hashes it kind-wide — a privacy restriction is
                 ;; never absorbed the way openness absorbs
                 :hashed (into #{} (comp (mapcat :hashed) (map str)) entries)
                 ;; filter-scoped admission: nil when any entry lacks a
                 ;; filter (openness absorbs, the ids rule); the guard
                 ;; keeps filtered entries to one per kind, so the vec
                 ;; is the row?/conds-of contract, not an OR machine
                 :filters (when-not (some #(nil? (:filter %)) entries)
                            (mapv :filter entries))
                 :args (args-of entries)}]))
        (group-by :kind (get-in row [:data :scope]))))

(defn- row-matches?
  "Does this decoded row sit inside one of the entry's filter maps?
  Exact text comparison against the data field — the same value the
  collection's :eq cond compares in SQL, so the row check and the
  query check tell one story."
  [row filter-maps]
  (boolean
   (some (fn [fm]
           (every? (fn [[f v]]
                     (= (str (get-in row [:data (keyword (name f))]))
                        (str v)))
                   fm))
         filter-maps)))

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
  [:requested_by :id] for a requester riding as an object."
  [row branch pid]
  (= pid (get-in row (into [:data] branch))))

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
  [eng tx kind branch pid]
  (let [st (:storage eng)
        opts {:limit 200 :newest-first true}]
    (if (= 1 (count branch))
      (into #{} (map :id)
            (store/query-rows st tx kind {(first branch) pid} opts))
      (into #{}
            (comp (filter #(branch-owns? % branch pid)) (map :id))
            (store/query-rows st tx kind {} opts)))))

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
  total honestly zero (an empty IN would not parse)."
  [eng kind os pid]
  (when-not (:all os)
    (let [ids (store/with-tx
                (:storage eng)
                (fn [tx]
                  (into (sorted-set)
                        (mapcat #(own-branch-ids eng tx kind % pid))
                        (:by os))))]
      (if (seq ids) (vec ids) ["-none-"]))))

(defn check-capability
  "The introspection answer (waymark-44h, the grant-check door): does
  grant G, held by principal P, admit capability C right now? Rides
  the same visibility resolution every request rides — wrong
  audience, unaccepted, revoked, expired and unknown all collapse to
  {:allowed false} with nothing else said (concealment: the caller
  learns no scope it did not name). :constraints is the capability
  entry's filter map, the enforcement point's to interpret;
  :expires_at rides along so the caller can cache the yes no longer
  than it lives."
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
  and the door goes dark with it."
  [eng audience]
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
    (or (some #(when (and (= :accepted (:state %)) (unexpired? %)) %) rows)
        (some #(when (and (= :offered (:state %)) (unexpired? %)) %) rows))))

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
  (waymark-sfe): {:id :action? :row?}, nil for every other fate."
  [eng grant-id principal]
  (let [pid (:id principal)
        row (when grant-id (load-decoded eng :grant grant-id))
        own? (boolean (and row (= (get-in row [:data :audience]) pid)))
        named? (and (some? pid) (not= pid (:id t/anonymous)))
        live? (boolean (and own? (active? row ((:now-fn eng)))))
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
                         (boolean (some #(branch-owns? row % pid)
                                        (:by os)))))))
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
                                (row-matches? row (:filters e)))))
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
                         (contains? (:actions (own-of k) #{}) a))))]
    {:grant-id (str grant-id)
     :surface surface
     :own? own?
     ;; THE GUARD'S-EYE VIEW (waymark-sfe). Present only when a LIVE
     ;; grant conferred a surface: a dead, foreign, unknown or absent
     ;; grant leaves it nil, and `unless-granted` refuses an agent that
     ;; carries none — dead means scoped-to-nothing here exactly as it
     ;; does at the router. It rides the invoke ctx as `(:grant ctx)`
     ;; and the render probe's ctx alongside it, so advertisement and
     ;; enforcement read one fact.
     :grant (when (and live? (seq surface))
              {:id (str grant-id) :action? action?* :row? row?*})
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
     ;; row? tells — the guard's one-filtered-entry rule is what keeps
     ;; this a conjunction instead of an OR machine
     :conds-of (fn [kind]
                 (when-some [fms (get-in surface [(name kind) :filters])]
                   (vec (for [[f v] (first fms)]
                          ;; :vis? marks the cond as the LEASH, never a
                          ;; client filter — facet counting strips a
                          ;; field's own client conds so options don't
                          ;; collapse, and must never strip this one
                          ;; (hospitality audit, guest walk #3)
                          {:target :data :field (keyword (name f))
                           :cast "text" :op := :value (str v)
                           :vis? true}))))
     :ids-of (fn [kind]
               (let [k (name kind)]
                 (if-some [e (get surface k)]
                   (some-> (:ids e) sort vec)
                   ;; the third and last of the case blocks, gone the
                   ;; same way: each kind's declared branches ARE the
                   ;; query, unioned. nil (the :all posture) is
                   ;; unrestricted — the whole registry lists
                   (when-some [os (own-of k)]
                     (own-ids eng (keyword k) os pid)))))}))

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
  as before, because every other check reads the grant, not the flag."
  [eng principal]
  (when-some [row (standing-grant-for eng (:id principal))]
    (when-some [row (accept-as-audience! eng row principal)]
      (when (= :accepted (:state row))
        (assoc (visibility eng (:id row) principal)
               :vocabulary-open? true)))))

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

(defn unscoped-visibility
  "The visibility a principal arrives with when it presents NO grant —
  nil for a human or a system actor (unscoped sight, the router's
  `visibility-of` answering nothing), the bootstrap surface for a
  named agent, which never runs unscoped.

  This is `wrap-identity`'s own else-branch, and it lives here rather
  than inline there because a SECOND door now needs it for somebody
  other than the caller: `feed.preview_as` must build the visibility
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
  reason the paragraph above gives."
  [eng principal]
  (when (= :agent (:type principal))
    (or (when (:acts-for principal)
          (worn-visibility eng principal))
        (bootstrap-visibility eng principal))))

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
