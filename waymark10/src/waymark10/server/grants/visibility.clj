(ns waymark10.server.grants.visibility
  "What a grant lets one request see, moved out of
  waymark10.server.grants: the :team-of and :names-me reads,
  `visibility` and the postures built on it (worn, bootstrap,
  requester, unscoped), the capability reads (`capability-entry`,
  `check-capability`) and `door-takers`. It reads rows and writes
  none; the scope evaluation it calls stays in grants."
  (:require [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.roles :as roles]
            [waymark10.server.scope :as scope]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

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
    (case (grants/team-of-ref rdef (some-> (:field spec) name))
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
                (let [rdef (get rs (keyword (grants/scope-name (:kind e))))]
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
               (when grant-id (grants/load-decoded eng :grant grant-id)))
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
        live? (boolean (and own? (grants/active? row0 now)))
        seat (when (and live? (grants/seat-cited row0))
               (grants/resolve-seat eng row0 principal now sitting-id))
        row (if seat (assoc-in row0 [:data :scope] (:scope seat)) row0)
        ;; an entry past its `until` is absent from this instant on, in
        ;; a seat's scope and in a grant's own (ticket b177a14b)
        row (cond-> row
              (seq (get-in row [:data :scope]))
              (update-in [:data :scope] #(grants/live-entries now %)))
        ;; a `*` entry is read at this request, into one read-only entry
        ;; per kind the law declares now (ticket 6aad331d)
        row (cond-> row
              (some scope/every-kind? (get-in row [:data :scope]))
              (update-in [:data :scope] #(grants/expand-every-kind (grants/engine-rdefs eng) %)))
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
        surface (if live? (grants/prune-unusable eng (grants/surface-of row)) grants/dead)
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
        surfaces (grants/own-surfaces eng)
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
        ;; the seat a live seat grant cites, as the owner its rows
        ;; carry — read only for a kind whose own-surface opts in with
        ;; :seats true (journal, self), so a connector sitting in the
        ;; seat reads what the seat's last sitter left. A wall, a
        ;; revoke, an expiry or a stand-down leaves `seat` nil or
        ;; walled, and the seat's rows go dark with it
        seat-owner (when (and seat (nil? (:reason seat)))
                     (str "seat:" (:id seat)))
        own-row? (fn [k id]
                   (when-some [os (own-of k)]
                     (if (:all os)
                       ;; the vocabulary posture: the registry's rows
                       ;; are everyone's words, so existing is owning
                       (some? (grants/load-decoded eng (keyword k) id))
                       ;; a row is yours if ANY declared branch names
                       ;; you — one-party for a grant or an ask, two
                       ;; for a letter (yours as its author OR as its
                       ;; recipient; a third agent's foreign letter
                       ;; falls out of :row? and 404s like any
                       ;; un-granted one)
                       (when-some [row (grants/load-decoded eng (keyword k) id)]
                         (let [pids (cond-> @spellings
                                      (:delegates os) (into @person-spellings)
                                      (and (:seats os) seat-owner) (conj seat-owner))]
                           (boolean (some #(grants/branch-owns? row % pids)
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
                              (when-some [row (grants/load-decoded eng (keyword k) id)]
                                (grants/row-matches? row (:filters e)
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
                                      (grants/door-on-row? eng k (get surface k) a id)))
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
                             (and (grants/admits? (:fields e) f)
                                  (not (contains? (:hashed e) f)))
                             (own-kind? k))))
               ;; a power token this leash names: what a scheduled
               ;; power call may name (docs/spec-scheduled-actions.md
               ;; R-4.4)
               :power? (fn [token] (contains? surface (str token)))
               ;; the owner a sitter's write to the seat's memory is
               ;; stamped with (workqueue10's dwelling reads it)
               :seat-owner seat-owner
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
                   (or (grants/admits? (:fields e) (name field))
                       (contains? (:hashed e) (name field)))
                   ;; the own surface renders whole — its rows are the
                   ;; principal's own record
                   (own-kind? k))))
     :hashed? (fn [kind field]
                (contains? (get-in surface [(name kind) :hashed] #{})
                           (name field)))
     :hash (fn [kind field value]
             (grants/field-hash eng kind field value))
     :arg? (fn [kind action arg]
             (let [k (name kind)]
               (if-some [e (get surface k)]
                 (grants/admits? (get-in e [:args (name action)]) (name arg))
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
                              :let [vs (grants/filter-values rdef f v)
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
                     (grants/own-ids eng (keyword k) os pid person seat-owner)))))})))

(defn- sitting-grant
  "The grant the sitting `sitting-id` was opened under, when it still
  stands for `audience`: accepted or offered, and unexpired by the live
  clock, as `standing-grant-for` judges. nil otherwise."
  [eng audience sitting-id]
  (when-some [gid (some-> sitting-id str not-empty
                          (->> (grants/load-decoded eng :sitting))
                          (get-in [:data :grant]) str not-empty)]
    (when-some [g (grants/load-decoded eng :grant gid)]
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
                       (grants/standing-grant-for eng (:id principal)))]
     (when-some [row (grants/accept-as-audience! eng row principal)]
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
        (or (grants/load-decoded eng :member pid)
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
  (when-some [g (grants/standing-grant-for eng member-id)]
    (let [row (grants/accept-as-audience! eng g (assoc principal :id member-id))]
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
      (if (and mid (grants/standing-grant-for eng mid))
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
       :expires_at (some-> (grants/load-decoded eng :grant (str grant-id))
                           (get-in [:data :expires_at]) str)})))

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
        scope (resolve-names-me #{} (resolve-team-of eng #{} (mapv #(update % :kind grants/scope-name) scope)))
        e (get (grants/prune-unusable eng (grants/surface-of {:data {:scope scope}})) k)]
    (boolean
     (and e
          (contains? (:actions e) (name action))
          (or (nil? id)
              (and (or (nil? (:ids e)) (contains? (:ids e) (str id)))
                   (or (nil? (:filters e))
                       (when-some [row (grants/load-decoded eng (keyword k) id)]
                         (grants/row-matches? row (:filters e)
                                       (get (inv/resources eng) (keyword k)))))
                   (grants/door-on-row? eng k e action id)))))))

(defn- display-of
  "The display name of the member a principal id names: the row of that
  id, or the row a binding stamped with it as `subject`; the raw id
  when no member row answers."
  [eng pid]
  (or (when-some [rdef (get (inv/resources eng) :member)]
        (grants/nonblank
         (get-in (or (grants/load-decoded eng :member pid)
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
                                (grants/without-entries (grants/live-entries now (get-in s [:data :scope]))
                                                 (get-in s [:data :stale]))
                                kind action id))]
                (grants/nonblank (get-in s [:data :name])))
        holders (for [g (rows :grant {:state :accepted})
                      :let [aud (grants/nonblank (get-in g [:data :audience]))]
                      :when (and aud
                                 (not (grants/seat-cited g))
                                 (grants/active? g now)
                                 (not (contains? mine aud))
                                 (scope-admits-door? eng (get-in g [:data :scope])
                                                     kind action id))]
                  (display-of eng aud))]
    (into [] (comp (remove nil?) (distinct)) (concat seats holders))))
