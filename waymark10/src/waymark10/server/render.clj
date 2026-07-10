(ns waymark10.server.render
  "The envelope projection: one decoded row + one declaration + one
  probe ctx → the v10 wire document. Pure in (rdef, row, ctx) — no
  storage reads; guards probe with inp nil, mode :probe.

  Envelope partition per action:
  - state ∈ :from, probe allows (or pends)     → actions entry
    (a :warning deny does not block — the invoke-side 409
    acknowledge protocol owns it)
  - state ∈ :from, :refuse deny                → unavailable entry
  - state ∈ :from, deny with :hide             → omitted (concealed)
  - state ∉ :from                              → unavailable with the
    in-states narration — unless a hide guard denies (concealed).

  ── batch A: the shape decisions the ancestors left open ────────────

  PARTS ARE A REFINEMENT, NOT A REPLACEMENT (waymark9 render.py:389,
  verified and kept): a placed action renders top-level in `actions`
  (the complete truth) AND re-renders per data item under `parts`,
  with the scope key const-bound. The exclusive shape (moving placed
  actions OUT of actions) was considered and rejected: depth=summary
  drops parts, so exclusivity would conceal live affordances from
  summary readers — advertisement would no longer equal enforcement —
  and v10's client contract (act! reads `actions`) plus the walker
  both depend on the top-level map staying complete. parts renders
  only when non-empty and only for actions that survived into
  `actions` (visibility rides along for free).

  Per-item honesty inside a group: the key acceptance set (each
  single-field :accepts leaf judging the key, guard order) is
  computed ONCE and intersected per item. An item whose key the set
  admits carries the const-bound entry; an item it excludes carries a
  per-item `unavailable` narration rendered from the SAME leaf and
  deny vars enforcement would use — reasons that differ per item
  narrate per item; a row-level deny (out of state, guard refusal,
  empty required admission) narrates ONCE top-level, as before.
  Two-field relation acceptance sets fold per item: the tuples
  matching the item's key become the other judged field's enum; a
  key no tuple admits drops the action from that item WITHOUT
  narration (the relation's explain speaks both fields — rendering it
  with one would put words in the law's mouth). Part entries drop the
  :draft advert: v10 drafts key on (resource, action) with no
  part-key sub-resource — a placed draftable action is a recorded
  punt.

  LINKS render only when DECLARED — no engine defaults (waymark9
  injected collection/events rels on every envelope; v10 keeps the
  phase-3 pin that an undeclaring kind's links stay {}). A link is
  {:rel …} plus exactly one of :edge (a :related entry — href
  compiled onto the public filter grammar, := \"\" / :<= \"_gte\" /
  :>= \"_lte\", waymark9 compile_edge_links), :owns (a child kind —
  href = the child collection filtered by its :via ref to this row's
  id), or :href (a template over {id} and {data.field}, the escape
  hatch). A nil join/template value omits the link — a row with no
  boundary relates to nothing. :badge names a data field whose
  CURRENT MATERIALIZED value rides the link (nil is absence, never a
  zero); :summary is the declared sentence, verbatim; :embed true
  rides as an invitation — the ROUTER splices :embedded (target
  envelope-minus-data items, capped) so this namespace stays
  storage-free. Target plurals resolve through ctx-opts :resources
  (the engine's kind map); absent, the default plural — a custom
  :plural target needs the map.

  EFFORT (waymark10.demand) stamps every action entry — top-level,
  part-bound (recomputed: the const key demands nothing), and the
  engine-injected adopt (assent). Envelope-only, never fingerprinted.
  The collection create/query/bulk entries live in
  server/collections.clj and are not stamped here (recorded seam).

  DEPTH: envelope-summary is the envelope minus data AND parts —
  actions stay complete (the refinement shape is what makes this
  honest). :rows :none in ctx-opts is the cheap stub: no probe runs,
  and actions AND unavailable are null — explicitly unknown, not
  empty."
  (:require [clojure.string :as str]
            [waymark10.demand :as demand]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.judgment :as judgment]
            [waymark10.server.problems :as p]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.time LocalDate)))

(set! *warn-on-reflection* true)

;; ── the probe ───────────────────────────────────────────────────────

(defn- probe-transition
  "→ {:status :available|:unavailable|:hidden (:deny :denier)}."
  [defn' row ctx]
  (loop [gs (:guards defn')]
    (if-some [guard (first gs)]
      (let [[v d] (g/evaluate guard row nil ctx)]
        (if (and (t/deny? v) (not= :warning (:severity d)))
          (if (:hide d)
            {:status :hidden}
            {:status :unavailable :deny v :denier d})
          (recur (rest gs))))
      {:status :available})))

(defn- probe-hidden-only?
  "Concealment holds out-of-state too: a hide-flagged deny on probe
  means the action is omitted, never narrated."
  [defn' row ctx]
  (boolean
   (some (fn [guard]
           (let [[v d] (g/evaluate guard row nil ctx)]
             (and (t/deny? v) (:hide d))))
         (:guards defn'))))

;; ── acceptance-set folding ──────────────────────────────────────────

(defn- encode-enum [v]
  (if (instance? LocalDate v) (str v) v))

(defn- fold-acceptance
  "Each leaf guard's single-field acceptance set becomes that field's
  enum in the advertised input schema — the form never offers a value
  the server already knows it will refuse. Sets sharing a field
  intersect. Relations: folded per part item only (see the parts
  section) — the unscoped rendering cannot pick a tuple."
  [js defn' row ctx]
  (reduce
   (fn [js leaf]
     (if (or (:relation leaf)
             (nil? (:accepts leaf))
             (not= 1 (count (:judges leaf))))
       js
       (if-some [values (g/admitted leaf row ctx)]
         (let [field (first (:judges leaf))
               values (mapv encode-enum values)]
           (if (get-in js [:properties field])
             (update-in js [:properties field]
                        (fn [prop]
                          (assoc prop :enum
                                 (if-some [prev (:enum prop)]
                                   (filterv (set values) prev)
                                   values))))
             js))
         js)))
   js
   (mapcat g/iter-leaves (:guards defn'))))

;; ── the empty-admission narration (phase 8, waymark9 render.py) ─────

(defn- member-str? [v allowed]
  (let [s (str v)]
    (boolean (some #(= s (str %)) allowed))))

(defn- empty-required-admission
  "The first required input field whose guard-declared acceptance set
  came back EMPTY — no value on this document currently qualifies, so
  the action has nothing valid to offer no matter what the client
  submits: it narrates as unavailable instead of advertising an empty
  enum. Fields with no accepts guard are unaffected."
  [defn' row ctx]
  (when (:input defn')
    (let [required (into #{}
                         (keep (fn [[k e]] (when-not (:optional e) k)))
                         (schema/entry-map (:input defn')))
          singles (reduce
                   (fn [m leaf]
                     (if (and (:accepts leaf)
                              (not (:relation leaf))
                              (= 1 (count (:judges leaf))))
                       (let [f (first (:judges leaf))]
                         (if-some [vals (g/admitted leaf row ctx)]
                           (assoc m f
                                  (if-some [prior (get m f)]
                                    (filterv #(member-str? % vals) prior)
                                    (vec vals)))
                           m))
                       m))
                   {}
                   (mapcat g/iter-leaves (:guards defn')))]
      (some (fn [f] (when (and (contains? singles f)
                               (empty? (get singles f)))
                      f))
            (sort required)))))

(defn- no-admissible-entry [defn' field]
  (let [label (or (get-in defn' [:display :label])
                  (str/replace (name (:name defn')) "_" " "))]
    {:reason (str "No " (str/replace (name field) "_" " ")
                  " currently qualifies for '" label "'.")}))

;; ── entries ─────────────────────────────────────────────────────────

(defn- action-entry [defn' rdef self row ctx]
  (let [{:keys [to safety display]} defn'
        href (str self "/-/" (name (:name defn')))
        input-js (when (:input defn')
                   (fold-acceptance (schema/json-schema (:input defn'))
                                    defn' row ctx))
        key-field (when-some [place (:place defn')]
                    (get-in rdef [:part-scopes place :key]))]
    (cond-> {:method "POST"
             :href href}
      input-js
      (assoc :input input-js)
      true
      (assoc :effect (cond-> {:to (name to)}
                       (contains? (:terminal rdef) to) (assoc :terminal true)))
      true
      (assoc :safety (cond-> {:idempotent (boolean (:idempotent safety))
                              :reversible (boolean (:reversible safety))
                              :confirm (boolean (:confirm safety))}
                       (:fence safety) (assoc :fence true)))
      ;; the demand class (batch A): derived from the folded schema,
      ;; envelope-only — never fingerprinted
      true
      (assoc :effort (demand/effort defn' input-js key-field))
      (seq display)
      (assoc :display display)
      ;; the composition surface (phase 7): an :edit action with a
      ;; declared draft policy affords its draft sub-resource
      (get-in defn' [:edit :draft])
      (assoc :draft {:href (str href "/draft")
                     :shared (boolean (get-in defn' [:edit :draft :shared]))}))))

(defn- unavailable-entry [denier deny row]
  (into {}
        (filter (comp some? val))
        {:reason (g/render-reason denier deny row)
         :remedies (not-empty (vec (:remedies denier)))
         :becomes-available (g/becomes-available denier deny row)}))

(defn- out-of-state-entry [defn' state]
  (let [states (sort (:from defn'))]
    ;; humanized state labels: reasons are prose, not tokens; the
    ;; machine-readable states ride becomes_available
    {:reason (str "Available in state(s) "
                  (str/join ", " (map summary/state-label states))
                  "; the resource is " (summary/state-label state) ".")
     :becomes-available {:in-states (mapv name states)}}))

;; ── parts: placed actions re-rendered per data item ─────────────────

(defn- key-acceptance-leaves
  "[[leaf #{stringified admitted}] …] for the single-field acceptance
  leaves judging the part key, in guard order: per item, the FIRST
  leaf whose set excludes the key is exactly the denier enforcement
  would name. A leaf that declines to constrain (nil) drops out."
  [defn' field row ctx]
  (into []
        (keep (fn [leaf]
                (when (and (:accepts leaf) (not (:relation leaf))
                           (= [field] (:judges leaf)))
                  (when-some [vals (g/admitted leaf row ctx)]
                    [leaf (into #{} (map str) vals)]))))
        (mapcat g/iter-leaves (:guards defn'))))

(defn- relation-tuple-sets
  "The relation leaves judging the part key, with their admitted
  tuples — computed once per action, filtered per item."
  [defn' field row ctx]
  (into []
        (keep (fn [leaf]
                (when (and (:relation leaf) (:accepts leaf)
                           (some #{field} (:judges leaf)))
                  (when-some [tuples (g/admitted leaf row ctx)]
                    {:judges (:judges leaf) :tuples tuples}))))
        (mapcat g/iter-leaves (:guards defn'))))

(defn- item-relation-enums
  "The per-item relation fold: tuples matching the item's key value
  become the other judged field's enum (2-field relations; wider ones
  only gate admissibility). → {field [values]}, or :inadmissible when
  some relation admits nothing for this key."
  [rels field kv]
  (reduce
   (fn [enums {:keys [judges tuples]}]
     (let [ki (long (.indexOf ^java.util.List judges field))
           matching (filterv (fn [tup]
                               (and (= (count tup) (count judges))
                                    (let [v (nth tup ki)]
                                      (or (= v kv) (= (str v) (str kv))))))
                             tuples)]
       (cond
         (empty? matching) (reduced :inadmissible)
         (not= 2 (count judges)) enums
         :else
         (let [oi (- 1 ki)
               other (nth judges oi)
               vals (into [] (comp (map #(nth % oi)) (distinct)
                                   (map encode-enum))
                          matching)]
           (update enums other
                   (fn [prev] (if prev (filterv (set vals) prev) vals)))))))
   {}
   rels))

(defn- bind-part-entry
  "The per-item projection of an action entry: the scope key becomes
  a const (the user never re-picks the row they clicked — its folded
  enum drops), relation-admitted values for this key fold as the
  other judged field's enum, and effort recomputes with the key
  pre-bound. The :draft advert drops (ns docstring: recorded punt)."
  [entry defn' key-field kv rel-enums]
  (let [entry (dissoc entry :draft)
        entry (if (:input entry)
                (update entry :input
                        (fn [js]
                          (reduce-kv
                           (fn [j f vs]
                             (if (get-in j [:properties f])
                               (update-in j [:properties f]
                                          (fn [prop]
                                            (assoc prop :enum
                                                   (if-some [prev (:enum prop)]
                                                     (filterv (set vs) prev)
                                                     vs))))
                               j))
                           (update-in js [:properties key-field]
                                      #(-> % (dissoc :enum)
                                           (assoc :const (encode-enum kv))))
                           rel-enums)))
                entry)]
    (assoc entry :effort (demand/effort defn' (:input entry) key-field))))

(defn- parts-of
  "The parts namespace over the FINAL actions map (visibility rides
  for free): {scope-path {:key kw :items [{:key … :item enc-data
  (:actions {}) (:unavailable {})} …]}}. Every data item appears —
  the group mirrors the array, so a client renders rows for the whole
  scope; items the acceptance set excludes narrate per item, items a
  relation empties stay silent (ns docstring)."
  [rdef row ctx by-name actions enc-data]
  (reduce-kv
   (fn [parts aname entry]
     (let [defn' (get by-name aname)
           scope (when defn' (get-in rdef [:part-scopes (:place defn')]))]
       (if-not scope
         parts
         (let [{:keys [path key]} scope
               items (vec (get-in row [:data path]))
               enc-items (vec (get enc-data path))]
           (if (empty? items)
             parts
             (let [leaves (key-acceptance-leaves defn' key row ctx)
                   rels (relation-tuple-sets defn' key row ctx)]
               (update parts path
                 (fn [group]
                   (let [group (or group
                                   {:key key
                                    :items (mapv (fn [it enc]
                                                   {:key (encode-enum (get it key))
                                                    :item enc
                                                    :actions {}})
                                                 items enc-items)})]
                     (update group :items
                       (fn [ivec]
                         (mapv
                          (fn [it ientry]
                            (let [kv (get it key)
                                  denier (some (fn [[leaf allowed]]
                                                 (when-not (contains? allowed (str kv))
                                                   leaf))
                                               leaves)
                                  enums (when-not denier
                                          (item-relation-enums rels key kv))]
                              (cond
                                denier
                                (update ientry :unavailable assoc aname
                                        {:reason (g/render-reason
                                                  denier
                                                  (t/deny {:vars {key kv}})
                                                  row)})

                                (= :inadmissible enums) ientry

                                :else
                                (update ientry :actions assoc aname
                                        (bind-part-entry entry defn' key kv
                                                         enums)))))
                          items ivec))))))))))))
   {}
   actions))

;; ── links: the declared relations, compiled ─────────────────────────

(def ^:private forward-suffix
  "The public filter grammar a forward join compiles onto: target
  rows where theirs OP ours-value (waymark9 _FORWARD_SUFFIX, minus
  the strict ops the checks already refuse on links)."
  {:= "" :<= "_gte" :>= "_lte"})

(defn- enc-param ^String [v]
  (URLEncoder/encode (str v) StandardCharsets/UTF_8))

(defn- plural-of [resources kind]
  (or (:plural (get resources kind)) (str (name kind) "s")))

(defn- edge-link [rdef row resources {:keys [edge kind]}]
  (when-some [e (get (:related rdef) edge)]
    (let [params (mapv (fn [[ours op theirs]]
                         (when-some [v (get-in row [:data ours])]
                           (str (name theirs) (forward-suffix op)
                                "=" (enc-param (encode-enum v)))))
                       (:on e))]
      (when (every? some? params)
        {:href (str "/api/" (plural-of resources (:kind e))
                    "?" (str/join "&" params))
         :kind (name (or kind (str (name (:kind e)) "_collection")))}))))

(defn- owns-link [rdef row resources {:keys [owns kind]}]
  (when-some [e (some #(when (= owns (:kind %)) %) (:owns rdef))]
    {:href (str "/api/" (plural-of resources (:kind e))
                "?" (name (:via e)) "=" (enc-param (:id row)))
     :kind (name (or kind (str (name (:kind e)) "_collection")))}))

(def ^:private href-placeholder #"\{(id|data\.[A-Za-z0-9_]+)\}")

(defn- template-link [row {:keys [href kind]}]
  (let [missing (volatile! false)
        out (str/replace href href-placeholder
                         (fn [[_ token]]
                           (let [v (if (= "id" token)
                                     (:id row)
                                     (get-in row [:data (keyword (subs token 5))]))]
                             (if (some? v)
                               (str (encode-enum v))
                               (do (vreset! missing true) "")))))]
    (when-not @missing
      (cond-> {:href out}
        kind (assoc :kind (name kind))))))

(defn- render-links
  "The declared :links of one row. resources is the engine's kind
  map (ctx-opts :resources) for target plurals."
  [rdef row resources]
  (into {}
        (keep (fn [{:keys [rel summary badge embed] :as ld}]
                (when-some [entry (cond
                                    (:edge ld) (edge-link rdef row resources ld)
                                    (:owns ld) (owns-link rdef row resources ld)
                                    (:href ld) (template-link row ld))]
                  [rel (cond-> entry
                         summary (assoc :summary summary)
                         embed (assoc :embed true)
                         (some? (get-in row [:data badge]))
                         (assoc :badge (encode-enum
                                        (get-in row [:data badge]))))])))
        (:links rdef)))

;; ── the envelope ────────────────────────────────────────────────────

(defn envelope
  "The v10 wire document (snake string keys, JSON-ready values) for a
  DECODED row. ctx-opts: :principal (default anonymous), :now
  (Instant, required for time-reading guards), :services,
  :visibility (phase 9a) — the per-request grant projection, resolved
  once at the identity boundary: when present, only granted actions
  survive, absent from actions AND unavailable alike (concealment,
  never narration) — and :resources (batch A), the engine's kind map
  for link target plurals."
  [rdef row {:keys [principal now services visibility resources]}]
  (let [ctx (t/ctx {:principal (or principal t/anonymous)
                    :now now :services services :mode :probe})
        self (str "/api/" (:plural rdef) "/" (:id row))
        state (:state row)
        ;; the row's law resolves the probe's guards too (phase 5):
        ;; advertisement equals enforcement, per row
        resolved (map #(judgment/resolve-action rdef % (:law-revision row))
                      (remove :bulk (machine/actions-seq rdef)))
        by-name (into {} (map (juxt :name identity)) resolved)
        {:keys [actions unavailable]}
        (reduce
         (fn [acc defn']
           (if (contains? (:from defn') state)
             (let [{:keys [status deny denier]} (probe-transition defn' row ctx)]
               (case status
                 :available
                 (if-some [field (empty-required-admission defn' row ctx)]
                   (assoc-in acc [:unavailable (:name defn')]
                             (no-admissible-entry defn' field))
                   (assoc-in acc [:actions (:name defn')]
                             (action-entry defn' rdef self row ctx)))
                 :unavailable (assoc-in acc [:unavailable (:name defn')]
                                        (unavailable-entry denier deny row))
                 :hidden acc))
             (if (probe-hidden-only? defn' row ctx)
               acc
               (assoc-in acc [:unavailable (:name defn')]
                         (out-of-state-entry defn' state)))))
         {:actions {} :unavailable {}}
         resolved)
        ;; the engine-injected adopt (phase 5): a row living under an
        ;; older law than the kind's current one affords stepping
        ;; forward — unless the machine declares its own :adopt
        actions (if (and (:current-law rdef)
                         (:law-revision row)
                         (< (:law-revision row) (:current-law rdef))
                         (not (contains? (:terminal rdef) state))
                         (not (contains? (:actions rdef) :adopt)))
                  (assoc actions :adopt
                         {:method "POST"
                          :href (str self "/-/adopt")
                          :effect {:to (name state)}
                          :safety {:idempotent true :reversible false
                                   :confirm false}
                          :effort "assent"
                          :display {:label "Adopt the current law"}})
                  actions)
        ;; the visibility projection (phase 9a): a scoped request's
        ;; non-granted actions do not exist — dropped from both maps,
        ;; the engine-injected adopt included
        granted? (when visibility
                   (fn [[aname _]] ((:action? visibility) (:kind rdef) aname)))
        actions (cond->> actions granted? (into {} (filter granted?)))
        unavailable (cond->> unavailable granted? (into {} (filter granted?)))
        enc-data (schema/encode (:schema rdef) (:data row))
        ;; parts render over the SURVIVING actions — a concealed placed
        ;; action never re-renders per item
        parts (parts-of rdef row ctx by-name actions enc-data)]
    (p/wire-value
     (cond-> {:waymark "10"
              :kind (name (:kind rdef))
              :self self
              :state (name state)
              :summary (summary/render (:summary rdef) (assoc row :kind (:kind rdef)))
              :data enc-data
              :actions actions
              :unavailable unavailable
              :links (render-links rdef row resources)
              :meta (cond-> {:version (:version row)
                             :etag (inv/etag (:kind rdef) (:id row) (:version row))}
                      (:updated-at row) (assoc :updated-at (str (:updated-at row)))
                      (:law-revision row) (assoc :law-revision (:law-revision row)))}
       (seq parts) (assoc :parts parts)))))

(defn envelope-stub
  "The rows=none item (batch A): no probe runs — actions and
  unavailable are null, EXPLICITLY UNKNOWN (the spec's answer for a
  page that declined to pay per-row probes; a follow-up GET tells).
  State, summary, links and meta stay — they cost nothing."
  [rdef row {:keys [resources]}]
  (p/wire-value
   {:waymark "10"
    :kind (name (:kind rdef))
    :self (str "/api/" (:plural rdef) "/" (:id row))
    :state (name (:state row))
    :summary (summary/render (:summary rdef) (assoc row :kind (:kind rdef)))
    :actions nil
    :unavailable nil
    :links (render-links rdef row resources)
    :meta (cond-> {:version (:version row)
                   :etag (inv/etag (:kind rdef) (:id row) (:version row))}
            (:updated-at row) (assoc :updated-at (str (:updated-at row)))
            (:law-revision row) (assoc :law-revision (:law-revision row)))}))

(defn envelope-summary
  "Depth summary: the full envelope minus data AND parts — state,
  summary, the COMPLETE actions/unavailable partition, links and meta
  stay (collection items; ?depth=summary). ctx-opts :rows :none is
  the cheap stub instead (see envelope-stub)."
  [rdef row ctx-opts]
  (if (= :none (:rows ctx-opts))
    (envelope-stub rdef row ctx-opts)
    (dissoc (envelope rdef row ctx-opts) "data" "parts")))
