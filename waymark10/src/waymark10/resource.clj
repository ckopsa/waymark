(ns waymark10.resource
  "The declaration surface: a resource is one map, normalized and
  validated at load. defresource is the import-time gate — a
  declaration the checks refuse never serves a request.

  Normalized action keys: :from (set), :to, :input (malli form),
  :guards (vector), :safety (validated), :display, :handler,
  :emits, :edit, :place, :bulk, :batch, :waives (set), :touches,
  :unless (transition kw; its guard is appended), :record.

  Batch G: field-scoped law may be colocated on a schema entry's
  property map — {:derived spec} {:filter ops} {:sort mark}
  {:part-scope {:key …}} — and normalization projects it into the
  canonical top-level keys (:derived, :filterable, :sortable,
  :part-scopes), stripping the sugar from the schema form before it
  compiles or fingerprints. Two spellings, one law: the colocated
  and split spellings normalize to the same map, so a pure style
  refactor mints zero revisions. Declaring a concern both ways for
  one field is the :one-home definition error.

  Batch H widens the authored surface with the same discipline —
  every spelling desugars at this gate, before anything compiles or
  fingerprints: :fields lifecycle groups (→ :schema, :create-schema,
  generated editors, conditional create gates), :flow transition rows
  (→ :actions, per-origin :confirm sentences landing as a consequence
  map the render layer resolves by current state), :undo pointers
  (verified against the graph, stamping :reversible true, then
  stripped), and the {edge-name {:kind …}} owns map (→ the vector
  spelling, aggregates renamed onto the child kind)."
  (:require [clojure.string :as str]
            [waymark10.checks :as checks]
            [waymark10.declaration :as declaration]
            [waymark10.expr :as expr]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the summary template ────────────────────────────────────────────

(def ^:private template-root #"\{([A-Za-z0-9_]+)")

(defn template-fields
  "The root names a summary template reads ({data.start_date} → data)."
  [template]
  (into #{}
        (map (comp keyword second))
        (re-seq template-root (str template))))

;; ── guard-tree helpers ──────────────────────────────────────────────

(defn map-leaf-guards
  "Rebuild a guard tree with f applied to every leaf."
  [f guard]
  (cond
    (:all guard) (update guard :all #(mapv (partial map-leaf-guards f) %))
    (:any guard) (update guard :any #(mapv (partial map-leaf-guards f) %))
    :else (f guard)))

;; ── normalization ───────────────────────────────────────────────────

(defn normalize-action
  "Construction-shape validation, ported from waymark9 actions.py:
  safety declared never inferred; record needs input; bulk excludes
  drafts; batch needs input, excludes place/bulk and the fence; an
  Edit implies the fence. Public seam: waymark10.declare/defaction
  runs it eagerly at the def site so a malformed action fails at its
  own line; the cross-referencing checks still wait for defresource."
  [kind aname a]
  (declaration/check-action! kind aname a)
  (let [err (fn [msg] (throw (t/definition-error
                              (str (name kind) "/" (name aname) ": " msg))))]
    (when-not (contains? a :safety)
      (err "safety is declared, never inferred — :safety is required"))
    (let [safety (t/safety (:safety a))
          from (let [f (:from a)]
                 (cond
                   (set? f) f
                   (sequential? f) (set f)
                   (some? f) #{f}
                   :else (err ":from is required")))
          _ (when-not (:to a) (err ":to is required"))
          _ (when (and (:record a) (nil? (:input a)))
              (err ":record retains the validated input; the action declares none"))
          _ (when (and (:bulk a) (get-in a [:edit :draft]))
              (err "bulk actions cannot have drafts; drafts are per-resource"))
          _ (when (:batch a)
              (when (nil? (:input a)) (err "a batch action takes inputs"))
              (when (:place a) (err "batch and place are exclusive"))
              (when (:bulk a) (err "batch (N inputs, one resource) excludes bulk (one input, N resources)")))
          edit (:edit a)
          _ (when (and edit (false? (:fence edit)) (str/blank? (:unfenced-reason edit)))
              (err "an unfenced edit demands a written :unfenced-reason"))
          safety (if (and edit (not (false? (:fence edit))) (not (:fence safety)))
                   (assoc safety :fence true)   ; an Edit implies the fence
                   safety)
          _ (when (and (:batch a) (:fence safety))
              (err "a batch carries one body and no per-item etag; the fence would lie"))
          guards (vec (:guards a))
          guards (if-some [unless-of (:unless a)]
                   (conj guards (g/unless unless-of))
                   guards)
          display (or (:display a) {})
          ;; a string consequence advertises as the description here;
          ;; a per-origin map waits for render, where the row's
          ;; current state picks the sentence (batch H)
          display (if (and (string? (:consequence safety))
                           (nil? (:description display)))
                    (assoc display :description (:consequence safety))
                    display)
          ;; the declared cross-write set (waymark9 touches=): what
          ;; OTHER rows this action advances — blast radius as law.
          ;; :may true tolerates the touch not firing on a given run.
          touches (when-some [ts (:touches a)]
                    (when-not (sequential? ts)
                      (err ":touches is a vector of {:kind … :action …} maps"))
                    (mapv (fn [t]
                            (when-not (and (map? t)
                                           (keyword? (:kind t))
                                           (keyword? (:action t)))
                              (err ":touches entries declare :kind and :action as keywords"))
                            (when-some [extra (seq (dissoc t :kind :action :may))]
                              (err (str ":touches entry carries unknown key(s) "
                                        (vec (map first extra)))))
                            (cond-> {:kind (:kind t) :action (:action t)}
                              (:may t) (assoc :may true)))
                          ts))]
      (cond-> (assoc a
                     :from from
                     :to (:to a)
                     :safety safety
                     :guards guards
                     :display display
                     :waives (set (:waives a))
                     :emits (vec (:emits a)))
        (seq touches) (assoc :touches touches)))))

(defn- where-value-set
  "One where entry's values as a canonical set: a collection becomes
  a set, a scalar a one-value set ({:blocking true} means {:blocking
  #{true}}), and keyword tokens become their names ({:state
  #{:reviewed}} means {:state #{\"reviewed\"}} — stored data is JSON
  and never holds a keyword; the maintainer's SQL already compared
  them equal, so the gate spells them equal too). Batch H; a map
  value ({:not …} has no spelling) passes through for the checks to
  refuse by name."
  [v]
  (if (map? v)
    v
    (into #{}
          (map (fn [x] (if (keyword? x) (name x) x)))
          (if (coll? v) v [v]))))

(defn normalize-derived-spec
  "Canonicalize one derived spec: expression trees normalized, count
  :where values as sets — two spellings of one membership are one
  law. Idempotent, and public: waymark10.declare/defderived lands a
  def'd spec on the same value the inline spelling normalizes to."
  [d]
  (cond-> d
    (:expr d) (update :expr expr/normalize)
    (:vars d) (update :vars update-vals expr/normalize)
    (get-in d [:count :where])
    (update-in [:count :where] update-vals where-value-set)
    ;; :sum mirrors :count (batch C) — same membership rule
    (get-in d [:sum :where])
    (update-in [:sum :where] update-vals where-value-set)))

(defn- normalize-derived [rmap]
  (update rmap :derived
          (fn [derived]
            (into {}
                  (map (fn [[fact d]] [fact (normalize-derived-spec d)]))
                  derived))))

(defn- bind-require-specs
  "The functional bind_data: enrich every require-guard leaf with its
  fact's derived spec so refusals render the spec's explain and the
  create path can compute from input."
  [rmap]
  (let [bind (fn [leaf]
               (if-some [fact (:require leaf)]
                 (assoc leaf :require/spec (get-in rmap [:derived fact]))
                 leaf))]
    (-> rmap
        (update :actions update-vals
                (fn [a] (update a :guards #(mapv (partial map-leaf-guards bind) %))))
        (update :create-guards #(mapv (partial map-leaf-guards bind) (or % []))))))

(defn- schema-head
  "The leaf type of a schema form, unwrapping :maybe/:vector layers
  and property vectors: [:vector [:waymark/vocab {:open true}]] →
  :waymark/vocab."
  [s]
  (cond
    (keyword? s) s
    (vector? s) (if (#{:maybe :vector} (first s))
                  (schema-head (last s))
                  (first s))
    :else nil))

(defn- vocab-fields
  "Schema entries typed :waymark/vocab (bare, maybe'd, or vector-of)."
  [rmap]
  (when (:schema rmap)
    (into []
          (keep (fn [[k {:keys [schema]}]]
                  (when (= :waymark/vocab (schema-head schema)) k)))
          (schema/entry-map (:schema rmap)))))

(defn- merge-vocab-filters
  "Vocabulary fields self-merge into the filter/facet surface: one
  declaration, membership filtering and observed facets for free."
  [rmap]
  (reduce (fn [m f]
            (-> m
                (update-in [:filterable f] #(or % #{:eq :in}))
                (update :faceted (fn [fs] (vec (distinct (conj (vec fs) f)))))))
          rmap
          (vocab-fields rmap)))

;; ── schema-entry colocation (batch G) ───────────────────────────────
;; Field-scoped law may live on the schema entry whose field it
;; governs; normalization projects it into the canonical top-level
;; keys. The sugar keys are declaration ergonomics, never schema
;; properties: they are stripped before the schema compiles, so the
;; published JSON Schema and the fingerprint see the split spelling.

(def ^:private colocated-law-keys [:derived :filter :sort :part-scope])

(def ^:private sort-marks #{true :default :default-desc})

(defn- one-home-error [kind field concern]
  (throw (t/definition-error
          (str (name kind) " [one-home] field " (name field) " declares "
               concern " both on its schema entry and at the top level — "
               "a concern has exactly one home")
          {:check :one-home})))

(defn- project-into
  "Project colocated [field value] pairs under a top-level map key,
  refusing a field the top level already declares."
  [rmap kind top-key concern entries xform]
  (reduce (fn [m [f v]]
            (when (contains? (get m top-key) f)
              (one-home-error kind f concern))
            (assoc-in m [top-key f] (xform f v)))
          rmap
          entries))

(defn- part-scope-spec [kind f spec]
  (when-not (and (map? spec) (:key spec))
    (throw (t/definition-error
            (str (name kind) ": schema entry " (name f)
                 " :part-scope is a {:key …} map"))))
  (when (and (:path spec) (not= (:path spec) f))
    (throw (t/definition-error
            (str (name kind) ": schema entry " (name f) " :part-scope names "
                 ":path " (:path spec) " — a colocated part scope's path IS "
                 "its entry"))))
  (merge {:path f} spec))

(defn- project-sort
  "Colocated :sort marks join :sortable — true joins :fields;
  :default / :default-desc also claim the list default (at most one,
  counting a top-level :default; :default-desc marks \"-field\")."
  [rmap kind entries]
  (if (empty? entries)
    rmap
    (let [top (:sortable rmap)
          top-fields (set (:fields top))]
      (doseq [[f mark] entries]
        (when-not (contains? sort-marks mark)
          (throw (t/definition-error
                  (str (name kind) ": schema entry " (name f) " :sort is true, "
                       ":default, or :default-desc, got " (pr-str mark)))))
        (when (contains? top-fields f)
          (one-home-error kind f ":sort")))
      (let [defaults (into (if-some [d (:default top)] [d] [])
                           (keep (fn [[f mark]]
                                   (case mark
                                     :default (name f)
                                     :default-desc (str "-" (name f))
                                     nil)))
                           entries)]
        (when (< 1 (count defaults))
          (throw (t/definition-error
                  (str (name kind) ": at most one sort default — "
                       (pr-str defaults) " all claim it"))))
        (update rmap :sortable
                (fn [s]
                  (cond-> (update (or s {}) :fields
                                  #(into (vec %) (map first) entries))
                    (seq defaults) (assoc :default (first defaults)))))))))

(def ^:private law-key-families
  "Prefix families of the colocated law keys. malli ignores unknown
  entry properties, so a typo'd law key would silently declare
  nothing — the silent-drift hole this refusal closes: any entry prop
  in a law key's lexical family that is not the exact key refuses."
  {"filter" :filter "deriv" :derived "sort" :sort "part" :part-scope})

(defn- refuse-near-misses [kind field props]
  (doseq [k (keys props)
          :when (and (simple-keyword? k)
                     (not (contains? (set colocated-law-keys) k)))]
    (let [n (name k)]
      (doseq [[prefix correct] law-key-families]
        (when (str/starts-with? n prefix)
          (throw (t/definition-error
                  (str (name kind) " [unknown-law-key] schema entry " field
                       " declares " k " — not a law key; did you mean "
                       correct "?")
                  {:check :unknown-law-key})))))))

(defn- project-colocated
  "Field-scoped law on schema entry property maps → the canonical
  top-level keys, the sugar stripped from the schema form. After this
  step the colocated and split spellings are one map — two spellings,
  one law."
  [rmap]
  (let [form (:schema rmap)
        kind (:kind rmap)]
    (if-not (and (vector? form) (= :map (first form)))
      rmap
      (let [[head entries] (if (map? (second form))
                             [(subvec form 0 2) (drop 2 form)]
                             [(subvec form 0 1) (rest form)])
            parsed (mapv (fn [entry]
                           (if-not (vector? entry)
                             {:entry entry}
                             (let [[k & more] entry
                                   [props children] (if (map? (first more))
                                                      [(first more) (rest more)]
                                                      [nil more])
                                   _ (when props (refuse-near-misses kind k props))
                                   sugar (select-keys props colocated-law-keys)]
                               (if (empty? sugar)
                                 {:entry entry}
                                 (let [props' (apply dissoc props colocated-law-keys)]
                                   {:entry (into (cond-> [k]
                                                   (seq props') (conj props'))
                                                 children)
                                    :field k
                                    :sugar sugar})))))
                         entries)
            pick (fn [key]
                   (into []
                         (keep (fn [{:keys [field sugar]}]
                                 (when (and field (contains? sugar key))
                                   [field (get sugar key)])))
                         parsed))]
        (if (not-any? :sugar parsed)
          rmap
          (-> rmap
              (assoc :schema (into head (map :entry) parsed))
              (project-into kind :derived ":derived" (pick :derived)
                            (fn [_ spec] spec))
              (project-into kind :filterable ":filter" (pick :filter)
                            (fn [_ ops] ops))
              (project-into kind :part-scopes ":part-scope" (pick :part-scope)
                            (partial part-scope-spec kind))
              (project-sort kind (pick :sort))))))))

;; ── batch H: the richer authored spellings ──────────────────────────
;; :fields lifecycle groups, :flow transition rows, :undo pointers,
;; and the owns map — sugar that desugars HERE, at the declaration
;; gate, before anything compiles or fingerprints. Each spelling
;; normalizes into the same canonical keys the split spelling writes
;; by hand: two spellings, one law.

;; — the word channel: waymark10.declare's typed field words return
;;   plain malli forms; entry properties and editor policy ride as
;;   namespaced metadata (a keyword cannot carry meta, so a word with
;;   properties wraps its form in a one-element vector this reader
;;   unwraps). Data, not code: this namespace never requires declare.

(defn word-form
  "The malli form of a field word's return value, the one-element
  metadata wrapper unwrapped."
  [w]
  (if (and (vector? w) (= 1 (count w)) (seq (meta w)))
    (first w)
    w))

(defn word-props
  "The entry properties a field word carries as metadata."
  [w]
  (:waymark10/props (meta w)))

(defn word-edit [w] (:waymark10/edit (meta w)))
(defn word-measured [w] (:waymark10/measured (meta w)))

;; — the generated editor's handler and the measured-by check: public
;;   builders, so the split spelling can cite the same values (the
;;   batch-G shared-object precedent) and the canonical printed form
;;   is one law however it is spelled.

(defn apply-field-edits
  "The generated editor's whole behavior: write exactly the input
  fields the caller sent, nothing else — an absent key is not an
  erase."
  [row inp fields]
  (reduce (fn [r f]
            (if (contains? inp f)
              (assoc-in r [:data f] (get inp f))
              r))
          row
          fields))

(def field-writer
  "The generated editor's handler, its identity the canonical printed
  form (the defhandler discipline: the fingerprint hashes the law,
  never the object). Memoized on the field list, so the sugared and
  split spellings of one editor hold the SAME value — one law, one
  object (the batch-G shared-object precedent, made automatic)."
  (memoize
   (fn [fields]
     (let [fields (vec fields)]
       (with-meta (fn [row inp _ctx] (apply-field-edits row inp fields))
         {:waymark10/form (list 'fn '[row inp _ctx]
                                (list 'waymark10.resource/apply-field-edits
                                      'row 'inp fields))})))))

(defn measured-verdict
  "The measured-by law at the write: the amount (input, else stored)
  must fit the arm its measure (input, else stored) selects. A blank
  amount passes — required-ness is the schema's concern; a set amount
  with no measure, or outside its arm, denies."
  [row inp {:keys [field by arms]}]
  (let [pick (fn [f] (if (contains? inp f) (get inp f) (get-in row [:data f])))
        v (pick field)
        m (pick by)]
    (cond
      (nil? v) (t/allow)
      (nil? m) (t/deny {:vars {field v by "(unset)"}})
      :else (let [arm (get arms (if (keyword? m) (name m) (str m)))]
              (if (and arm (schema/validate arm v))
                (t/allow)
                (t/deny {:vars {field v by m}}))))))

(def measured-guard
  "The generated cross-field check a measured-by field lands on its
  group's editor — the recorded closest-check equivalent of the
  sibling-dispatched union the schema layer cannot express. The check
  fn's identity is its canonical printed form over the sorted arm
  map, so the spelling (generated or cited) never moves the law;
  memoized like field-writer, so both spellings hold one value."
  (memoize
   (fn [{:keys [field by arms]}]
     (let [spec {:field field :by by :arms (into (sorted-map) arms)}]
       (g/guard
        {:name (keyword (str (name field) "_measured_by_" (name by)))
         :judges [field by]
         :explain (str "{" (name field) "} is not a valid {" (name by)
                       "} amount.")
         :check (with-meta (fn [row inp _ctx] (measured-verdict row inp spec))
                  {:waymark10/form
                   (list 'fn '[row inp _ctx]
                         (list 'waymark10.resource/measured-verdict
                               'row 'inp spec))})})))))

;; — :fields lifecycle groups (batch H, delta 6) ─────────────────────

(defn- sugar-err [kind where msg]
  (throw (t/definition-error
          (str (some-> kind name) " " where " — " msg))))

(def ^:private fields-group-keys
  #{:at-create :while-open :support :when :open :facts})

(defn- parse-field-rows [kind where rows]
  (when-not (and (vector? rows) (every? vector? rows))
    (sugar-err kind where "a group is a vector of [field (word …)] rows"))
  (mapv (fn [row]
          (when-not (and (= 2 (count row)) (keyword? (first row)))
            (sugar-err kind where
                       (str "rows are [field (word …)] pairs, got "
                            (pr-str row))))
          (let [[f w] row]
            {:field f
             :form (word-form w)
             :props (word-props w)
             :edit (word-edit w)
             :measured (some-> (word-measured w) (assoc :field f))}))
        rows))

(defn- data-entry
  "One row as a data-schema entry: at-create fields are required (the
  document is born with them); every later field is optional and
  nullable — it starts blank."
  [{:keys [field form props]} required?]
  (if required?
    (if (seq props) [field props form] [field form])
    [field (merge {:optional true} props) [:maybe form]]))

(defn- editor-input-entry [{:keys [field form props]}]
  [field (merge {:optional true} props) [:maybe form]])

(defn- editor-name [base states s]
  (if (= 1 (count states))
    (keyword base)
    (keyword (str base "_in_" (name s)))))

(defn- group-draft
  "The group's one draft policy: the union of its prose fields'
  declared drafts (shared if any is shared, live if any is live) —
  one editor, one draft sub-resource, the update_recipe shape."
  [rows]
  (let [drafts (keep (comp :draft :edit) rows)]
    (when (seq drafts)
      {:shared (boolean (some :shared drafts))
       :live (boolean (some :live drafts))})))

(defn- editor-action
  [kind where label rows state]
  (let [fields (mapv :field rows)
        measured (keep :measured rows)
        _ (doseq [m measured]
            (when-not (some #(= (:by m) (:field %)) rows)
              (sugar-err kind where
                         (str "field " (:field m) " is measured by " (:by m)
                              ", which is not a field of the same group — the "
                              "generated editor judges them together"))))
        draft (group-draft rows)]
    (cond-> {:from #{state} :to state
             :input (into [:map] (map editor-input-entry) rows)
             :edit (cond-> {:prefill fields}
                     draft (assoc :draft draft))
             :guards (mapv measured-guard measured)
             :safety {:idempotent true :reversible false :confirm false}
             :handler (field-writer fields)
             :display {:label label}})))

(defn- when-discriminator
  "The at-create one-of field whose values cover every :when key —
  the field the conditional requirement dispatches on."
  [kind at-create-rows when-map]
  (let [wanted (set (map name (keys when-map)))
        candidates (filterv (fn [{:keys [form]}]
                              (and (vector? form) (= :enum (first form))
                                   (every? (set (rest form)) wanted)))
                            at-create-rows)]
    (cond
      (empty? candidates)
      (sugar-err kind ":fields :when"
                 (str "no :at-create one-of field offers every key of "
                      (vec (sort wanted))
                      " — the conditional requirement needs its discriminating "
                      "selection"))
      (< 1 (count candidates))
      (sugar-err kind ":fields :when"
                 (str "fields " (mapv :field candidates) " each offer every "
                      ":when key — name the values so exactly one field "
                      "discriminates"))
      :else (:field (first candidates)))))

(defn conditional-required-guard
  "The :when groups' create gate: outside the discriminating value the
  guard has nothing to say; under it, the field must be set. Pure
  expression data — the split spelling writes the identical g/expr."
  [by by-value field]
  (g/expr {:name (keyword (str (name field) "_required_for_" (name by-value)))
           :when (list 'or
                       (list 'not= (list 'input by) (name by-value))
                       (list 'is-set (list 'input field)))
           :explain (str "A " (str/replace (name by-value) "_" " ")
                         " declares its "
                         (str/replace (name field) "_" " ")
                         " at create.")}))

(defn- desugar-fields
  "The :fields lifecycle groups → :schema, :create-schema, generated
  editors, and the :when create gates. Group semantics, each a
  sentence:
  - :at-create fields are create input and fixed after — required in
    both schemas, written by no generated editor.
  - :while-open fields get one generated editor per OPEN state
    (:open, default #{initial} — the machine cannot infer where
    authoring ends, so the declaration says; a multi-state self-loop
    has no v10 spelling, so several open states mean several
    editors, update_fields_in_<state>).
  - :support fields' generated editor exists in every non-terminal
    state, carrying the union of the group's prose draft policy.
  - :when {value rows} fields are optional everywhere plus a
    conditional-required create gate keyed on the one :at-create
    one-of field that offers every :when value.
  - :facts rows are ENGINE-maintained entries (chore_run's clock-
    flipped overdue demanded the group): optional, nullable, written
    by no generated editor and absent from the create schema; each
    names a top-level :derived law — one fact, one writer, and the
    writer here is the engine.
  - a top-level :derived COUNT fact with no declared entry gets its
    [:maybe :int] entry appended (a count fact is an :int by law);
    any other derived fact still declares its own entry (a :facts
    row).
  Editors write through waymark10.resource/apply-field-edits; a
  measured-by field lands measured-guard on its group's editors."
  [rmap]
  (let [fields (:fields rmap)
        kind (:kind rmap)]
    (if (nil? fields)
      rmap
      (do
        (when-not (map? fields)
          (sugar-err kind ":fields" "is a map of lifecycle groups"))
        (when-some [unknown (seq (sort (remove fields-group-keys (keys fields))))]
          (sugar-err kind ":fields"
                     (str "unknown group(s) " (vec unknown) "; groups are "
                          ":at-create, :while-open, :support, :when, :open, "
                          ":facts")))
        (doseq [k [:schema :create-schema]]
          (when (contains? rmap k)
            (sugar-err kind ":fields"
                       (str "declares " k " too — the groups ARE the schema; "
                            "a concern has exactly one home"))))
        (let [states (vec (:states rmap))
              _ (when (empty? states)
                  (sugar-err kind ":fields" "needs the declared :states"))
              terminal (set (:terminal rmap))
              initial (:initial rmap)
              at-create (parse-field-rows kind ":fields :at-create"
                                          (:at-create fields []))
              while-open (parse-field-rows kind ":fields :while-open"
                                           (:while-open fields []))
              support (parse-field-rows kind ":fields :support"
                                        (:support fields []))
              facts (parse-field-rows kind ":fields :facts"
                                      (:facts fields []))
              _ (doseq [{:keys [field]} facts]
                  (when-not (contains? (:derived rmap) field)
                    (sugar-err kind ":fields :facts"
                               (str "fact " field " has no :derived law — "
                                    ":facts rows are engine-maintained (one "
                                    "fact, one writer, and the writer here "
                                    "is the engine)"))))
              when-map (:when fields {})
              _ (when-not (and (map? when-map) (every? keyword? (keys when-map)))
                  (sugar-err kind ":fields :when"
                             "is a {discriminating-value [[field (word …)] …]} map"))
              when-rows (into (sorted-map)
                              (map (fn [[v rows]]
                                     [v (parse-field-rows
                                         kind (str ":fields :when " v) rows)]))
                              when-map)
              open (let [o (:open fields #{initial})]
                     (when-not (and (set? o) (seq o) (every? keyword? o))
                       (sugar-err kind ":fields :open"
                                  "is a non-empty set of open (still-authoring) states"))
                     (when-some [bad (seq (sort (remove (set states) o)))]
                       (sugar-err kind ":fields :open"
                                  (str (vec bad) " are not declared states")))
                     (when-some [dead (seq (sort (filter terminal o)))]
                       (sugar-err kind ":fields :open"
                                  (str (vec dead) " are terminal — nothing is "
                                       "authored after the end")))
                     (filterv (set o) states))
              non-terminal (filterv (complement terminal) states)
              declared (set (map :field (concat at-create while-open support
                                                facts
                                                (mapcat val when-rows))))
              count-entries
              (into []
                    (keep (fn [[fact spec]]
                            (when-not (contains? declared fact)
                              (if (contains? spec :count)
                                [fact {:optional true} [:maybe :int]]
                                (sugar-err kind ":fields"
                                           (str "derived fact " fact " has no "
                                                "field entry — :fields appends "
                                                ":int entries for count facts "
                                                "only; declare this one's "
                                                "shape as a :facts row"))))))
                    (sort-by key (:derived rmap)))
              schema (-> [:map]
                         (into (map #(data-entry % true)) at-create)
                         (into (map #(data-entry % false)) while-open)
                         (into (map #(data-entry % false)) support)
                         (into (comp (mapcat val) (map #(data-entry % false)))
                               when-rows)
                         (into (map #(data-entry % false)) facts)
                         (into count-entries))
              create-schema (-> [:map]
                                (into (map #(data-entry % true)) at-create)
                                (into (comp (mapcat val)
                                            (map #(data-entry % false)))
                                      when-rows))
              editors
              (into {}
                    (concat
                     (when (seq while-open)
                       (for [s open]
                         [(editor-name "update_fields" open s)
                          (editor-action kind ":fields :while-open"
                                         "Update fields" while-open s)]))
                     (when (seq support)
                       (for [s non-terminal]
                         [(editor-name "update_support" non-terminal s)
                          (editor-action kind ":fields :support"
                                         "Update support" support s)]))))
              _ (doseq [aname (keys editors)]
                  (when (contains? (:actions rmap) aname)
                    (sugar-err kind ":fields"
                               (str "generated editor " aname " collides with "
                                    "a declared action of the same name"))))
              when-guards
              (when (seq when-rows)
                (let [by (when-discriminator kind at-create when-rows)]
                  (into []
                        (for [[v rows] when-rows
                              {:keys [field]} rows]
                          (conditional-required-guard by v field)))))]
          (-> rmap
              (dissoc :fields)
              (assoc :schema schema)
              (assoc :create-schema create-schema)
              (update :actions #(merge editors (or % {})))
              (cond-> (seq when-guards)
                (update :create-guards #(into (vec (or % [])) when-guards)))))))))

;; — :flow transition rows (batch H, delta 3) ────────────────────────

(def flow-opt-keys
  "A flow row's legal opts — public so the shipped clj-kondo hook's
  copy can be held equal by test (waymark10.declaration-test)."
  #{:requires :args :input :confirm :undo :one-way :safety :display
    :record :edit :place :handler :emits :waives :unless :touches})

(defn- args->input
  "Flow :args rows → the action's input schema. Every argument is
  required — an action that asks, asks for a reason."
  [kind aname rows]
  (into [:map]
        (map (fn [{:keys [field form props]}]
               (if (seq props) [field props form] [field form])))
        (parse-field-rows kind (str ":flow " (name aname) " :args") rows)))

(defn- parse-flow-row [kind row]
  (when-not (and (vector? row) (<= 3 (count row) 4)
                 (every? keyword? (take 3 row)))
    (sugar-err kind ":flow"
               (str "rows are [from action to opts?], got " (pr-str row))))
  (let [[from action to opts] row]
    (when (and opts (not (map? opts)))
      (sugar-err kind ":flow" (str (name action) ": opts must be a map")))
    (when-some [unknown (seq (sort (remove flow-opt-keys (keys opts))))]
      (sugar-err kind ":flow"
                 (str (name action) " declares unknown opt(s) " (vec unknown)
                      "; a flow row speaks " (vec (sort flow-opt-keys)))))
    (when (and (:args opts) (:input opts))
      (sugar-err kind ":flow"
                 (str (name action) " declares both :args and :input — "
                      "one spelling per action")))
    {:from from :action action :to to :opts (or opts {})}))

(defn- flow-action
  "One action's rows → today's action map. Rows sharing a name agree
  on everything but :confirm (each origin may cost something
  different — the sentences land as a per-origin consequence map);
  per-origin :requires has no spelling yet (a recorded demand)."
  [kind aname rows]
  (let [tos (distinct (map :to rows))
        _ (when (< 1 (count tos))
            (sugar-err kind ":flow"
                       (str (name aname) " lands in " (vec tos)
                            " — one action, one destination")))
        to (first tos)
        shared-of (fn [row] (dissoc (:opts row) :confirm))
        shareds (distinct (map shared-of rows))
        _ (when (< 1 (count shareds))
            (let [ks (into (sorted-set)
                           (comp (mapcat keys))
                           shareds)
                  differing (filterv (fn [k]
                                       (< 1 (count (distinct
                                                    (map #(get % k) shareds)))))
                                     (vec ks))]
              (sugar-err kind ":flow"
                         (str (name aname) "'s rows disagree on " differing
                              " — per-origin " differing " has no spelling "
                              "(a recorded demand); align the rows or split "
                              "the action"))))
        opts (first shareds)
        confirms (into {}
                       (keep (fn [{:keys [from opts]}]
                               (when-some [c (:confirm opts)]
                                 (when (or (not (string? c)) (str/blank? c))
                                   (sugar-err kind ":flow"
                                              (str (name aname) " from " from
                                                   ": :confirm is the consequence "
                                                   "sentence")))
                                 [from c])))
                       rows)
        _ (when (and (seq confirms) (< (count confirms) (count rows)))
            (sugar-err kind ":flow"
                       (str (name aname) " confirms from some origins and not "
                            "others — every origin of a confirmed action "
                            "writes its consequence")))
        consequence (when (seq confirms)
                      (let [sentences (distinct (vals confirms))]
                        (if (= 1 (count sentences))
                          (first sentences)
                          confirms)))
        self-loop? (every? #(= (:from %) to) rows)
        safety
        (if-some [s (:safety opts)]
          (do (when (or (seq confirms) (:undo opts) (:one-way opts))
                (sugar-err kind ":flow"
                           (str (name aname) ": an explicit :safety is the "
                                "whole story — :confirm/:undo/:one-way do not "
                                "combine with it")))
              s)
          (let [reversible (boolean (:undo opts))
                confirm? (boolean (seq confirms))
                one-way (:one-way opts)]
            (when-not (or reversible confirm? one-way self-loop?)
              (sugar-err kind ":flow"
                         (str (name aname) " declares no safety story — "
                              ":undo names the honest reverse, :confirm "
                              "writes the consequence, :one-way acknowledges "
                              "the door, or spell :safety yourself")))
            (cond-> {:idempotent true
                     :reversible reversible
                     :confirm confirm?}
              consequence (assoc :consequence consequence)
              one-way (assoc :one-way one-way))))]
    (cond-> {:from (into #{} (map :from) rows)
             :to to
             :guards (vec (:requires opts))
             :safety safety}
      (:args opts) (assoc :input (args->input kind aname (:args opts)))
      (:input opts) (assoc :input (:input opts))
      (:undo opts) (assoc :undo (:undo opts))
      (:display opts) (assoc :display (:display opts))
      (:record opts) (assoc :record (:record opts))
      (:edit opts) (assoc :edit (:edit opts))
      (:place opts) (assoc :place (:place opts))
      (:handler opts) (assoc :handler (:handler opts))
      (:emits opts) (assoc :emits (:emits opts))
      (:waives opts) (assoc :waives (:waives opts))
      (:unless opts) (assoc :unless (:unless opts))
      (:touches opts) (assoc :touches (:touches opts)))))

;; — :decision, the standalone verdict (spec-decision-kind) ──────────
;;
;; Some decisions are not transitions on a domain row: "may I bike to
;; the park", "approve this purchase". The decision IS the thing, and
;; before this key an app that wanted one either bolted a
;; pending_approval state onto a noun that existed for another reason
;; or copied grants.clj wholesale.
;;
;; The claim this sugar makes is the modest one — IT IS NOT A NEW
;; MECHANISM. :decision desugars into ordinary :states, :actions,
;; :guards, :schema and :on-create, ahead of :flow (a decision IS a
;; flow), before the check battery, before the fingerprint. The
;; router, the render probe, collections, OpenAPI, MCP and the
;; conformance driver learn no new noun; nothing downstream can tell
;; a desugared decision from a hand-written one, which is precisely
;; the property that makes the key safe to add.
;;
;; The proof is batch G's invariant turned on its own author: after
;; approval_request was respelled through this key, its fingerprint
;; hash did not move by one byte (waymark10.decision-sugar-test). Two
;; spellings, one law — if the hash had moved, the sugar would have
;; changed the law and the generalization would have failed.

(def ^:private decision-keys
  #{:asks :by :decider :verdicts :stamps :expires :pacing :offered
    :own-surface})

(def ^:private verdict-keys
  #{:name :to :label :style :order :note :guards :handler :safety
    :display :edit :input})

(defn- map-form-keys
  "The field keywords a [:map …] form already spells — the sugar adds
  an entry only where the author wrote none, so a hand-spelled field
  always wins over its generated twin."
  [form]
  (if (and (vector? form) (= :map (first form)))
    (into #{} (comp (filter vector?) (map first)) (rest form))
    #{}))

(defn- add-entries [form entries]
  (let [have (map-form-keys form)]
    (into (or form [:map])
          (remove #(contains? have (first %)))
          entries)))

(defn- keep-entries
  "The create model: this [:map …] form minus the named fields — what
  the engine stamps and what a verdict writes are not the author's to
  supply."
  [form drop-fields]
  (into [:map]
        (remove #(and (vector? %) (contains? drop-fields (first %))))
        (rest form)))

(defn- ttl-seconds
  "A leash length, resolved at write time: a plain number of seconds,
  or {:service :some-key :seconds <fallback>} — the deployment's own
  configured default, with an honest number when it configured none."
  [spec ctx]
  (long (if (map? spec)
          (get (:services ctx) (:service spec) (:seconds spec))
          spec)))

(defn- decision-on-create
  "The birth stamps: the requester is written from the acting
  principal (never supplied by hand — an ask that could name someone
  else as its asker is an ask that can frame them), and a leash the
  author left blank gets the declared default, stamped AT CREATE so
  the decider judges the expiry that will actually exist rather than
  one computed later."
  [by expires]
  (let [field (:field expires)
        default (:default expires)]
    (fn [row ctx]
      (cond-> (assoc-in row [:data by] (get-in ctx [:principal :id]))
        (and field (some? default))
        (update-in [:data field]
                   #(or % (.plusSeconds ^java.time.Instant (:now ctx)
                                        (ttl-seconds default ctx))))))))

(defn- leash-guard
  "The declared ceiling on a leash: an ask may propose longer than the
  default, up to the cap, and never past it. Ask for less — an
  answered ask can always be followed by another."
  [expires]
  (let [field (:field expires)
        max-spec (:max expires)]
    (g/guard {:name :the-leash-is-short
              :judges [field]
              :reads [:now]
              :vars [:max_hours :asked]
              :explain (or (:explain expires)
                           (str "A leash is short — at most {max_hours} hours; "
                                "this ask runs to {asked}. Ask for less; an "
                                "answered ask can always be followed by another."))
              :check (fn [_row inp ctx]
                       (if-some [^java.time.Instant exp (get inp field)]
                         (let [max-s (ttl-seconds max-spec ctx)
                               cap (.plusSeconds ^java.time.Instant (:now ctx)
                                                 max-s)]
                           (if (pos? (compare exp cap))
                             (t/deny {:vars {:max_hours (quot max-s 3600)
                                             :asked (str exp)}})
                             (t/allow)))
                         (t/allow)))})))

(defn- pacing-guards
  "Two walls on the ASKING, not the deciding: a rolling-hour rate and
  a cap on how many of this principal's asks may sit unanswered at
  once. The second is the one a household actually feels — a verdict
  on what is already waiting comes before a new ask.

  Both ride ctx :find, per-process and unshared, inheriting
  grants.clj's own recorded punt about the unwired :rate hook.
  Declaring pacing does not make it distributed."
  [kind by offered {:keys [limit per open-cap]}]
  (let [window (long (case (or per :hour) :hour 3600 :day 86400 :minute 60))]
    (cond-> []
      limit
      (conj (g/guard
             {:name :asks-are-paced
              :reads [:principal :now kind]
              :vars [:limit :retry_at]
              :explain "Asks are paced to {limit} an hour; the window reopens at {retry_at}."
              :check (fn [_row _inp ctx]
                       (if (nil? (:find ctx))
                         (t/allow)
                         (let [pid (:id (:principal ctx))
                               ^java.time.Instant now (:now ctx)
                               cutoff (.minusSeconds now window)
                               fresh (into []
                                           (filter
                                            (fn [r]
                                              (and (some? (:created-at r))
                                                   (neg? (compare cutoff
                                                                  (:created-at r))))))
                                           ((:find ctx) kind {by pid} {:limit 500}))]
                           (if (< (count fresh) (long limit))
                             (t/allow)
                             (let [retry (.plusSeconds
                                          ^java.time.Instant
                                          (reduce (fn [a b]
                                                    (if (neg? (compare a b)) a b))
                                                  (mapv :created-at fresh))
                                          window)]
                               (t/deny {:vars {:limit limit :retry_at (str retry)}
                                        :retry-at retry}))))))}))
      open-cap
      (conj (g/guard
             {:name :asks-are-few
              :reads [:principal kind]
              :vars [:cap :pending]
              :explain "Open asks are capped at {cap}; yours awaiting a verdict: {pending}."
              :check (fn [_row _inp ctx]
                       (if (nil? (:find ctx))
                         (t/allow)
                         (let [pid (:id (:principal ctx))
                               open ((:find ctx) kind {by pid :state offered}
                                     {:limit (inc (long open-cap))})]
                           (if (< (count open) (long open-cap))
                             (t/allow)
                             (t/deny {:vars {:cap open-cap
                                             :pending (str/join ", "
                                                                (sort (map :id open)))}})))))})))))

;; ── the prose the sugar owes (waymark-7rw) ──────────────────────────
;;
;; The same argument :note already won at verdict-action: a sugar that
;; emits a bare field emits a display-prose warning into a declaration
;; whose author never wrote the field and cannot clear it without
;; re-declaring the entry by hand — which would move the entry ORDER
;; and therefore the kind's fingerprint, to say a sentence. So the
;; entries the DECISION owns say their human words themselves, in the
;; generic voice a decision of any subject can wear, and an instance
;; with better words spells them in its own :asks / :expires map,
;; where they win whole.
(defn- humanise [f] (str/capitalize (str/replace (name f) "_" " ")))

(defn- ask-display
  "Label and hint for the question field. :asks may be the bare
  keyword or {:field … :max … :x-display …}; the author's map wins
  key by key, so an instance can retitle without restating the hint."
  [asks]
  (let [f (if (map? asks) (:field asks) asks)]
    (merge {:label (humanise f)
            :help (str "The ask, in your own words — enough for whoever "
                       "decides to answer it without coming back to you.")}
           (when (map? asks) (:x-display asks)))))

(defn- leash-display
  "Label and hint for the expiry field. The default sentence names the
  one thing a leash form must say out loud: leaving it empty is not
  forever, it is the engine's own default."
  [expires]
  (let [f (:field expires)]
    (merge {:label (humanise f)
            :help (str "How long the answer holds. Leave it empty to take "
                       "the default leash; ask for less when less will do.")}
           (:x-display expires))))

(defn- verdict-handler
  "The generic stamp: who decided, and (when the verdict takes one)
  what they wrote. Its canonical form is built as data, so the hash
  is a function of the FIELD NAMES the declaration chose rather than
  of a closure's object identity.

  A verdict that spells its own :handler keeps it whole — the sugar
  never wraps, because a wrapper is a new fn and a new fn is a new
  law. Such a verdict owns its own stamping, which is what lets
  approval_request's stamp-approver (it stamps the minted grant's id
  beside the approver) ride this key without moving its fingerprint."
  [decided-by note-field]
  (with-meta
    (fn [row inp ctx]
      (cond-> row
        decided-by (assoc-in [:data decided-by] (:id (:principal ctx)))
        note-field (assoc-in [:data note-field] (get inp note-field))))
    {:waymark10/form
     (list 'fn '[row inp ctx]
           (list 'cond-> 'row
                 (boolean decided-by)
                 (list 'assoc-in [:data decided-by] '(:id (:principal ctx)))
                 (boolean note-field)
                 (list 'assoc-in [:data note-field]
                       (list 'get 'inp note-field))))}))

(defn- decider-guards
  "The decider's eligibility, as a VECTOR of walls rather than one
  folded composite. g/and would read the same at the door, but a
  composite records opaque — the decision record (spec-decision-record)
  keeps per-guard evidence, and a folded four-eyes wall would leave
  the log a name with nothing behind it. An action's guard list is
  already a conjunction; keeping the arms apart costs nothing and
  keeps the record honest.

  Order is evaluation order and therefore refusal order: the field
  wall first (\"not you\" is the sentence the asker most needs), then
  the named decider, then the role."
  [kind {:keys [not role field] :as decider}]
  (when-not (or not role field)
    (sugar-err kind ":decision"
               ":decider says nothing — spell :not, :field or :role"))
  ;; each wall is either a bare value (:not :requested_by) or a map
  ;; carrying its own sentence ({:field :asked_by :explain "…"}). A
  ;; :name/:explain spelled at the :decider level is the shorthand for
  ;; the ONE-WALL case and refuses beside a second wall, because a
  ;; sentence that would land on two different refusals is a sentence
  ;; that is wrong on one of them.
  (let [walls (remove nil? [not field role])
        shared (select-keys decider [:name :explain :hide])
        _ (when (and (seq (dissoc shared :hide)) (< 1 (count walls)))
            (sugar-err kind ":decision"
                       (str ":decider spells " (count walls)
                            " walls and one shared :name/:explain — give "
                            "each wall its own {:field/:name … :explain …}, "
                            "so each refusal says the true thing")))
        opt-of (fn [w extra]
                 (merge (when (= 1 (count walls)) shared)
                        (select-keys decider [:hide])
                        ;; :field / :name is the wall's VALUE slot and
                        ;; :as its rename; everything else is guard opts
                        (when (map? w) (dissoc w :field :as))
                        extra))
        val-of (fn [w k] (if (map? w) (get w k) w))]
    (cond-> []
      not   (conj (g/not-the-field (val-of not :field) (opt-of not nil)))
      field (conj (g/is-the-field (val-of field :field) (opt-of field nil)))
      role  (conj (let [g (g/role (val-of role :name)
                                  (opt-of role nil))]
                    ;; g/role names itself role:<token>; a decision may
                    ;; want the household's own word for the wall
                    (if-some [nm (and (map? role) (:as role))]
                      (assoc g :name nm)
                      g))))))

(defn- verdict-action
  "One verdict → one ordinary action. Everything the author spells
  wins whole; the sugar only fills blanks."
  [kind offered decider-gs stamps v]
  (let [{:keys [name to note guards handler safety display edit input
                label style order]} v
        note-field (when note (if (map? note) (:field note) note))
        note-max (if (map? note) (:max note 240) 240)]
    (when-not (keyword? name)
      (sugar-err kind ":decision" "each verdict declares a keyword :name"))
    (when-not (keyword? to)
      (sugar-err kind ":decision"
                 (str (clojure.core/name name) " declares no :to state")))
    (when-some [unknown (seq (sort (remove verdict-keys (keys v))))]
      (sugar-err kind ":decision"
                 (str (clojure.core/name name) " declares unknown key(s) "
                      (vec unknown) "; a verdict speaks "
                      (vec (sort verdict-keys)))))
    (cond-> {:from #{offered}
             :to to
             :guards (into (vec decider-gs) (or guards []))
             :safety (or safety
                         {:idempotent true :reversible false :confirm false
                          :one-way (str "A verdict stays on record; asking "
                                        "again is a new ask.")})
             :handler (or handler
                          (verdict-handler (:decided-by stamps) note-field))
             :display (or display
                          (cond-> {:label (or label (str/capitalize
                                                     (clojure.core/name name)))}
                            style (assoc :style style)
                            order (assoc :order order)))}
      note-field
      ;; the generated note carries its own prose. A sugar that emits
      ;; a bare field emits a usability warning (waymark-0ee's display
      ;; policy) into a declaration whose author never wrote the
      ;; field and cannot clear it — so the sugar says the human words
      ;; itself, and a verdict spelling its own :input owns them
      (assoc :input (or input
                        [:map [note-field
                               {:optional true
                                :x-display
                                {:label (str/capitalize
                                         (str/replace
                                          (clojure.core/name note-field)
                                          "_" " "))
                                 :help (str "Optional. Say why, in a sentence "
                                            "the asker will read beside the "
                                            "verdict.")}}
                               [:maybe [:string {:max note-max}]]]]))
      (and note-field edit) (assoc :edit edit)
      (and note-field (nil? edit))
      (assoc :edit {:prefill [note-field] :fence false
                    :unfenced-reason (str "A verdict's note is written once "
                                          "with the verdict; there is nothing "
                                          "here to clobber.")}))))

(defn- desugar-decision
  "The :decision key → ordinary states, actions, guards, schema and
  hooks. Runs FIRST in normalize-resource's thread, ahead of :flow.

  What it projects, and from what:

  | declared              | projected                                    |
  |-----------------------|----------------------------------------------|
  | :verdicts             | :states, :initial, :terminal, the verdict     |
  |                       | actions with :from/:to/:display and the       |
  |                       | optional note input and editor                |
  | :asks :by :stamps     | the schema entries each owns, the             |
  |                       | :create-schema that omits the stamped ones,   |
  |                       | :on-create's requester stamp                  |
  | :decider              | every verdict action's :guards, walls first   |
  | :expires              | the leash field, its create default, and      |
  |                       | (when :max is spelled) the ceiling guard      |
  | :pacing               | the two create guards                         |
  | always                | :filterable over state and the requester,     |
  |                       | :default-filters to the open queue,           |
  |                       | :sortable newest-first, :nav :system          |

  Every projection fills a blank and never overwrites: a key the
  author also spells wins, exactly as :flow lets a directly declared
  action win — with one refusal, the same one :flow makes. An action
  also named by :actions is the double-declaration error (\"one home
  per action\"), and so is an :on-create spelled beside a :decision
  that already stamps at birth. The second refusal is the sugar's own
  recorded limit: a decision kind that needs an extra birth stamp has
  no spelling yet, because composing the two hooks would mint a
  wrapper fn and a wrapper fn is a hash that moves for nothing.

  Verdict arity is two or more, never one, and at least one verdict
  must leave the open state: a single-verdict decision is a task with
  a checkbox, and a decision with nowhere to land is a queue that
  never drains."
  [rmap]
  (let [d (:decision rmap)
        kind (:kind rmap)]
    (if (nil? d)
      rmap
      (do
        (when-not (map? d)
          (sugar-err kind ":decision" "is a map"))
        (when-some [unknown (seq (sort (remove decision-keys (keys d))))]
          (sugar-err kind ":decision"
                     (str "unknown key(s) " (vec unknown) "; a decision speaks "
                          (vec (sort decision-keys)))))
        (let [{:keys [asks by decider verdicts stamps expires pacing]} d
              offered (:offered d :offered)
              ask-field (if (map? asks) (:field asks) asks)
              ask-max (if (map? asks) (:max asks 240) 240)]
          (when-not (keyword? ask-field)
            (sugar-err kind ":decision" ":asks names the question's field"))
          (when-not (keyword? by)
            (sugar-err kind ":decision"
                       ":by names the field the requester is stamped into"))
          (when-not (and (vector? verdicts) (<= 2 (count verdicts)))
            (sugar-err kind ":decision"
                       (str ":verdicts is two or more — a single-verdict "
                            "decision is a task with a checkbox")))
          (when-not (some #(not= offered (:to %)) verdicts)
            (sugar-err kind ":decision"
                       (str "no verdict leaves " offered
                            " — a decision must be able to land somewhere")))
          (when (:on-create rmap)
            (sugar-err kind ":decision"
                       (str ":on-create is also spelled — the decision already "
                            "stamps " (name by) " at birth; one home per hook")))
          (let [decider-gs (decider-guards kind (or decider {}))
                actions (into {}
                              (map (fn [v]
                                     [(:name v)
                                      (verdict-action kind offered decider-gs
                                                      stamps v)]))
                              verdicts)
                _ (doseq [aname (keys actions)]
                    (when (contains? (:actions rmap) aname)
                      (sugar-err kind ":decision"
                                 (str (name aname) " is also declared in "
                                      ":actions — one home per action"))))
                note-fields (into #{}
                                  (keep (fn [v]
                                          (when-some [n (:note v)]
                                            (if (map? n) (:field n) n))))
                                  verdicts)
                decided-by (:decided-by stamps)
                exp-field (:field expires)
                ;; the entries the ENGINE owns. The question is
                ;; required (a decision with no question is a button);
                ;; the stamped names are optional and :raw, because
                ;; they are principal ids and a display layer must not
                ;; dress them up as words
                entries (cond-> [[ask-field {:x-display (ask-display asks)}
                                  [:string {:min 1 :max ask-max}]]
                                 [by {:optional true :x-display {:raw true}}
                                  [:maybe [:string {:max 128}]]]]
                          exp-field
                          (conj [exp-field {:optional true
                                            :x-display (leash-display expires)}
                                 [:maybe :waymark/instant]])
                          decided-by
                          (conj [decided-by {:optional true
                                             :x-display {:raw true}}
                                 [:maybe [:string {:max 128}]]]))
                entries (into entries
                              (map (fn [n]
                                     [n {:optional true}
                                      [:maybe [:string {:max 240}]]]))
                              (sort note-fields))
                schema (add-entries (:schema rmap) entries)
                stamped (cond-> (into #{} note-fields)
                          by (conj by)
                          decided-by (conj decided-by))]
            (-> rmap
                (dissoc :decision)
                (assoc :schema schema)
                (update :create-schema #(or % (keep-entries schema stamped)))
                (update :states
                        #(or % (vec (distinct (cons offered (map :to verdicts))))))
                (update :initial #(or % offered))
                (update :terminal
                        #(or % (into #{} (comp (map :to) (remove #{offered}))
                                     verdicts)))
                (update :actions #(merge actions (or % {})))
                (assoc :on-create (decision-on-create by expires))
                (update :create-guards
                        (fn [gs]
                          (cond-> (vec gs)
                            (:max expires) (conj (leash-guard expires))
                            pacing (into (pacing-guards kind by offered pacing)))))
                (update :filterable
                        #(merge {:state #{:eq :in} by #{:eq}} %))
                (update :default-filters #(or % {:state (name offered)}))
                (update :sortable #(or % {:fields [:created_at]
                                          :default "-created_at"}))
                (update :nav #(or % :system))
                (update :summary
                        #(or % (str "{data." (name ask-field) "} · asked by "
                                    "{data." (name by) "} · {state}")))
                (cond-> (:own-surface d)
                  (update :own-surface
                          #(or % (if (map? (:own-surface d))
                                   (:own-surface d)
                                   {:by by
                                    :actions (into #{"create"}
                                                   (map (comp name :name))
                                                   verdicts)})))))))))))

(defn- desugar-flow
  "The :flow rows → today's :actions map, merged beside any directly
  declared actions (name collisions refuse — one home per action).
  A :flow declaration IS the machine: when :states is not spelled,
  the rows name them — initial first, then first appearance in row
  order (each row's from, then to), then any terminal the rows never
  reach."
  [rmap]
  (let [flow (:flow rmap)
        kind (:kind rmap)]
    (if (nil? flow)
      rmap
      (do
        (when-not (vector? flow)
          (sugar-err kind ":flow" "is a vector of [from action to opts?] rows"))
        (let [rows (mapv #(parse-flow-row kind %) flow)
              actions (into {}
                            (map (fn [[aname rs]]
                                   [aname (flow-action kind aname rs)]))
                            (group-by :action rows))
              states (or (:states rmap)
                         (vec (distinct
                               (concat (when-some [i (:initial rmap)] [i])
                                       (mapcat (juxt :from :to) rows)
                                       (:terminal rmap)))))]
          (doseq [aname (keys actions)]
            (when (contains? (:actions rmap) aname)
              (sugar-err kind ":flow"
                         (str (name aname) " is also declared in :actions — "
                              "one home per action"))))
          (-> rmap
              (dissoc :flow)
              (assoc :states states)
              (update :actions #(merge actions (or % {})))))))))

;; — :undo pointers (batch H, delta 4) ───────────────────────────────

(defn- verify-undo-pointers
  "An :undo pointer is verified where every action is known, then
  stripped — its residue is the :reversible true it already stamped
  (the one key the render layer reads). The precise inversion rule:
  the undo departs from this action's destination, and lands exactly
  where this action began — so a multi-origin action has no single
  honest reverse. A bad pointer is a definition error at the
  declaration site."
  [rmap]
  (let [kind (:kind rmap)]
    (update rmap :actions
            (fn [actions]
              (into (sorted-map)
                    (map (fn [[aname a]]
                           (if-some [u-name (:undo a)]
                             (let [u (get actions u-name)]
                               (when-not u
                                 (sugar-err kind (str "action " (name aname))
                                            (str ":undo names " u-name
                                                 ", which is not an action of "
                                                 "this kind")))
                               (when-not (contains? (:from u) (:to a))
                                 (sugar-err kind (str "action " (name aname))
                                            (str ":undo " (name u-name)
                                                 " does not depart from "
                                                 (:to a) " — it is not this "
                                                 "edge's reverse")))
                               (when-not (= (:from a) #{(:to u)})
                                 (sugar-err kind (str "action " (name aname))
                                            (str ":undo " (name u-name)
                                                 " lands in " (:to u)
                                                 ", but this action departs "
                                                 "from " (vec (sort (:from a)))
                                                 " — the undo must return "
                                                 "exactly where it began")))
                               [aname (dissoc a :undo)])
                             [aname a])))
                    actions)))))

;; — the owns map (batch H) ──────────────────────────────────────────

(defn- desugar-owns
  "{edge-name {:kind child :on {…}}} → today's vector-of-edges
  spelling, :via defaulting to <kind>_id (the ref back at the
  parent), and every aggregate that names the EDGE renamed to the
  child kind the engine's aggregate grammar speaks."
  [rmap]
  (let [o (:owns rmap)
        kind (:kind rmap)]
    (if-not (and (map? o) (seq o) (every? map? (vals o)))
      rmap
      (let [_ (doseq [[ename spec] (sort-by key o)]
                (when-not (keyword? (:kind spec))
                  (sugar-err kind ":owns"
                             (str ename " declares no child :kind"))))
            edges (mapv (fn [[_ spec]]
                          (merge {:via (keyword (str (name kind) "_id"))}
                                 spec))
                        (sort-by key o))
            alias->kind (into {} (map (fn [[ename spec]] [ename (:kind spec)]))
                              o)
            rename (fn [spec agg]
                     (let [target (when (map? (get spec agg))
                                    (get alias->kind
                                         (get-in spec [agg :owns])))]
                       (if target
                         (assoc-in spec [agg :owns] target)
                         spec)))]
        (-> rmap
            (assoc :owns edges)
            (update :derived
                    (fn [derived]
                      (into {}
                            (map (fn [[fact spec]]
                                   [fact (-> spec (rename :count)
                                             (rename :sum))]))
                            derived))))))))

(def ^:private worksheet-column-keys
  #{:field :action :param :ref :on-set :on-clear :create-only})

(defn- check-worksheet!
  "The worksheet declaration's def-site gate: every column names a
  declared field; an editable column's :action (or :on-set/:on-clear
  actions) is declared, and its :param — the invoke input key the
  cell value rides — names one of that action's input entries. The
  offline round-trip is law-shaped enough to refuse typos at boot,
  not at the first upload."
  [rmap]
  (when-some [ws (:worksheet rmap)]
    (let [err (fn [msg] (throw (t/definition-error
                                (str (some-> (:kind rmap) name)
                                     " [worksheet] " msg))))
          fields (set (schema/entry-keys (:schema rmap)))
          actions (:actions rmap)
          check-invoke
          (fn [field action param label]
            (let [a (get actions action)]
              (when (nil? a)
                (err (str (name field) ": " label " action " action
                          " is not declared")))
              (when param
                (if (:input a)
                  (when-not (contains? (set (schema/entry-keys (:input a)))
                                       param)
                    (err (str (name field) ": " label " param " param
                              " is not an input of " action)))
                  (err (str (name field) ": " label " carries param " param
                            " but " action " takes no input"))))))]
      (when-not (and (map? ws) (vector? (:columns ws)) (seq (:columns ws)))
        (err ":worksheet is {:columns [{:field …} …] :create bool?}"))
      (when-some [k (some #(when-not (contains? #{:columns :create} %) %)
                          (keys ws))]
        (err (str "unknown key " k " — worksheet keys are [:columns :create]")))
      (doseq [col (:columns ws)]
        (when-not (map? col)
          (err "every column is a map"))
        (when-some [k (some #(when-not (contains? worksheet-column-keys %) %)
                            (keys col))]
          (err (str "unknown column key " k " — column keys are "
                    (vec (sort worksheet-column-keys)))))
        (when-not (contains? fields (:field col))
          (err (str (some-> (:field col) name (or "(no :field)"))
                    ": names no declared field")))
        (when (and (:action col) (or (:on-set col) (:on-clear col)))
          (err (str (name (:field col))
                    ": :action and :on-set/:on-clear are two grammars — pick one")))
        (when (and (:create-only col) (or (:action col) (:on-set col)))
          (err (str (name (:field col))
                    ": :create-only columns take no edit action")))
        (when-some [a (:action col)]
          (check-invoke (:field col) a (or (:param col) (:field col))
                        ":action"))
        (when-some [os (:on-set col)]
          (check-invoke (:field col) (:action os) (:param os) ":on-set"))
        (when-some [oc (:on-clear col)]
          (check-invoke (:field col) (:action oc) (:param oc) ":on-clear"))))))

(def ^:private data-template-token #"\{data\.([A-Za-z0-9_]+)")

(defn- check-secret!
  "The :secret disposition's def-site gate (waymark-kyg): a secret
  field's value never leaves the engine, so every surface that would
  materialize, advertise or print it refuses at the declaration —
  a filterable/sortable field promotes a generated column and stands
  as a filter oracle, a facet enumerates the values themselves, a
  summary or label template prints them on every envelope and event
  frame, a worksheet column exports them, a derivation re-emits them
  as a computed value, and an :edit :prefill serves them raw from the
  draft view. Runs over the FULLY normalized map, so colocated sugar
  and the vocab self-merge have already landed. Nested {:secret true}
  marks refuse too — the disposition is a top-level field's; a mark
  malli would ignore (a bare nested map, or the {:x-display {:secret
  true}} misspelling) must not read as concealment.

  RECORDED seam: a cross-kind :sum {:of <secret>} reads a field of the
  SUMMED kind, whose rdef is not reachable from this kind's gate — the
  summed kind's own gate cannot see that its field is re-emitted
  elsewhere either, so a secret field summed across an edge is not
  caught here. Own-kind :derived :over is caught (a derivation binds
  only :over fields, so :expr cannot read what :over does not name)."
  [rmap]
  (let [kind (:kind rmap)
        secret (schema/secret-fields (:schema rmap))
        err (fn [msg] (throw (t/definition-error
                              (str (name kind) " [secret] " msg)
                              {:check :secret})))
        template-hit (fn [template]
                       (some (comp secret keyword second)
                             (re-seq data-template-token (str template))))
        nested-mark? (fn walk [x]
                       (cond
                         (map? x) (or (:secret x) (some walk (vals x)))
                         (vector? x) (some walk x)
                         :else false))]
    (when (seq secret)
      (when (contains? secret :state)
        (err ":state is the machine's token, not a data value — it cannot be :secret"))
      (doseq [[f {:keys [schema]}] (schema/entry-map (:schema rmap))
              :when (contains? secret f)]
        (when (= :waymark/vocab (schema-head schema))
          (err (str (name f) " is a :secret vocabulary field — a vocabulary "
                    "filters and facets by declaration; it cannot be concealed"))))
      (doseq [f (sort (filter secret (keys (:filterable rmap))))]
        (err (str (name f) " is :secret and :filterable — a filter is a "
                  "value oracle over what the projection conceals")))
      (let [sortable (into (set (get-in rmap [:sortable :fields]))
                           (when-some [d (get-in rmap [:sortable :default])]
                             [(keyword (if (str/starts-with? d "-")
                                         (subs d 1)
                                         d))]))]
        (doseq [f (sort (filter secret sortable))]
          (err (str (name f) " is :secret and sortable — ordering by a "
                    "concealed value tells its story"))))
      (doseq [f (sort (filter secret (:faceted rmap)))]
        (err (str (name f) " is :secret and :faceted — a facet enumerates "
                  "the very values the disposition conceals")))
      (when-some [f (template-hit (:summary rmap))]
        (err (str "the summary template reads " (name f) ", a :secret field — "
                  "the summary rides every envelope and event frame")))
      (when-some [f (template-hit (:label-template rmap))]
        (err (str ":label-template reads " (name f) ", a :secret field — "
                  "labels ride pickers and touch narrations")))
      (doseq [col (get-in rmap [:worksheet :columns])
              :when (contains? secret (:field col))]
        (err (str "worksheet column " (name (:field col)) " is :secret — "
                  "the export would carry the value out of the engine")))
      (doseq [[fact d] (:derived rmap)
              f (filter secret (:over d))]
        (err (str "derivation " (name fact) " reads " (name f) ", a :secret "
                  "field — a derived value re-emits what the disposition "
                  "conceals")))
      (doseq [[aname a] (:actions rmap)
              f (filter secret (get-in a [:edit :prefill]))]
        (err (str "action " (name aname) " prefills " (name f) ", a :secret "
                  "field — the draft view serves prefill from the raw row"))))
    ;; a nested mark declares nothing (secret-fields reads a top-level
    ;; entry's properties) — refuse the silent drift instead of
    ;; ignoring it, in the schema child AND in the properties map (the
    ;; {:x-display {:secret true}} misspelling lands here, not in
    ;; secret-fields, and is the most likely mis-declaration)
    (doseq [[f {:keys [schema properties]}] (schema/entry-map (:schema rmap))
            :when (and (not (contains? secret f))
                       (or (nested-mark? schema) (nested-mark? properties)))]
      (err (str (name f) " carries a nested {:secret true} (a mark malli "
                "ignores, or under :x-display) — :secret is a top-level field "
                "disposition; mark the field itself")))
    rmap))

(defn- normalize-default-filters
  "A default filter's value is a WIRE value: it lands in the query
  string, in the collection's self href and in the chip a person
  clicks, always as text. So a keyword spelling ({:state :offered})
  normalizes to its name here, the way a where= membership already
  does — two spellings, one law. A kind declaring none stays without
  the key."
  [rmap]
  (if-some [defs (:default-filters rmap)]
    (assoc rmap :default-filters
           (into {}
                 (map (fn [[f v]] [f (if (keyword? v) (name v) (str v))]))
                 defs))
    rmap))

(defn- where-wire-str
  "One :where value as the wire text a filter param carries: a keyword
  by its name, a set/vector as the comma list the :in grammar speaks
  (sets sorted — a set has no order, a wire string must), anything
  else stringified."
  [v]
  (cond
    (keyword? v) (name v)
    (set? v) (str/join "," (sort (map #(if (keyword? %) (name %) (str %)) v)))
    (sequential? v) (str/join "," (map #(if (keyword? %) (name %) (str %)) v))
    :else (str v)))

(defn- normalize-views
  "A view's :where values are WIRE values, exactly as :default-filters'
  already are — they land in a query string when a client opens the
  view. Two spellings, one law: keywords and sets normalize to the
  filter grammar's text here; check-views then validates the
  normalized form. A kind declaring no views stays without the key."
  [rmap]
  (if-some [views (:views rmap)]
    (assoc rmap :views
           (mapv (fn [v]
                   (if (and (map? v) (map? (:where v)))
                     (update v :where
                             (fn [w]
                               (into {}
                                     (map (fn [[f val]] [f (where-wire-str val)]))
                                     w)))
                     v))
                 views))
    rmap))

(def ^:private own-surface-keys #{:by :actions :all})

(defn- normalize-own-surface
  "The own-surface declaration, canonicalized (spec-decision-kind seam
  2). Before this key, 'the kinds a named principal sees its own rows
  of' was a literal set of seven strings in grants.clj, read through
  three hand-written case blocks and copied a fourth time into the
  test packs — so a NEW decision kind was invisible to its own
  requester until all four were edited, and the failure was silent.
  The set is not removed here, it is relocated: each of the seven
  declarations grows the sentence that always described it.

  :by is a VECTOR of branches, because ownership is not always
  one-party — a letter is yours as its author OR its recipient, and
  the branches union. A branch is either a field keyword (a promoted
  column, so the id set pushes down as a query cond) or a PATH vector
  into the document (a nested requester, filtered in memory: the
  recorded window, not a new store grammar). A bare keyword spells
  the one-branch case.

  :all true is the vocabulary posture — every row of this kind is
  everyone's words, not anyone's data (the capability registry). It
  is deliberately a separate key from an empty :by, because 'owned by
  nobody' and 'owned by everybody' must not be one typo apart.

  :actions names what the courtesy confers WITHOUT a grant. The
  guards still judge every invoke; this only decides which doors are
  visible enough to be knocked on."
  [rmap]
  (if-some [os (:own-surface rmap)]
    (let [kind (:kind rmap)]
      (when-not (map? os)
        (sugar-err kind ":own-surface" "is a map"))
      (when-some [unknown (seq (sort (remove own-surface-keys (keys os))))]
        (sugar-err kind ":own-surface"
                   (str "unknown key(s) " (vec unknown) "; it speaks "
                        (vec (sort own-surface-keys)))))
      (when-not (or (:by os) (:all os))
        (sugar-err kind ":own-surface"
                   (str "names neither :by (whose id the row carries) nor "
                        ":all (every row is everyone's words)")))
      (let [branches (cond
                       (nil? (:by os)) []
                       (keyword? (:by os)) [[(:by os)]]
                       (vector? (:by os)) (mapv #(if (keyword? %) [%] (vec %))
                                                (:by os))
                       :else (sugar-err kind ":own-surface"
                                        ":by is a field, or a vector of fields and paths"))]
        (assoc rmap :own-surface
               {:by branches
                :all (boolean (:all os))
                :actions (into #{} (map name) (:actions os))})))
    rmap))

(defn normalize-resource
  [rmap]
  ;; decision first, then flow: a :decision IS a flow (it projects the
  ;; states and the verdict actions a :flow row would have spelled by
  ;; hand), so it must land before :flow can read what it left. Flow
  ;; before fields, in turn, because a :flow declaration may derive
  ;; :states, which the :fields groups read (open-state validation,
  ;; support editors)
  (let [rmap (-> rmap desugar-decision desugar-flow desugar-fields)
        {:keys [kind states initial summary]} rmap]
    (doseq [[k v] {:kind kind :states states :initial initial :summary summary
                   :schema (:schema rmap)}]
      (when (nil? v)
        (throw (t/definition-error (str (some-> kind name) " declares no " k)))))
    (when-not (pos-int? (:shape rmap 1))
      (throw (t/definition-error ":shape is a positive data-shape version")))
    (let [shape (:shape rmap 1)
          upcasts (:upcasts rmap {})]
      (when-not (= (set (keys upcasts)) (set (range 1 shape)))
        (throw (t/definition-error
                (str ":upcasts must cover exactly shapes 1.." (dec shape)
                     " — no gaps between what was stored and what serves")))))
    (when-not (contains? #{:immediate :never} (:adoption rmap :immediate))
      (throw (t/definition-error ":adoption is :immediate or :never")))
    ;; :retain — what the log carries forward past the write. One map
    ;; shared by two specs (decision record, time travel), closed here
    ;; so a third entry arrives with the feature that reads it rather
    ;; than as a silent typo that retains nothing
    (when-some [r (:retain rmap)]
      (when-not (and (map? r)
                     (every? #{:judgment :data} (keys r))
                     (every? boolean? (vals r)))
        (throw (t/definition-error
                (str ":retain is {:judgment bool? :data bool?} — what the "
                     "transition log carries forward past the write")))))
    (check-worksheet! rmap)
    (when-not (contains? #{:primary :secondary :system} (:nav rmap :primary))
      (throw (t/definition-error ":nav is :primary, :secondary, or :system")))
    (when-some [d (:domain rmap)]
      (when-not (and (keyword? d) (re-matches #"[a-z][a-z0-9_]*" (name d)))
        (throw (t/definition-error
                (str ":domain must be a snake_case keyword token, got "
                     (pr-str d))))))
    ;; recorded deviations, each a sentence (the docstring bookkeeping
    ;; the prose used to carry by hand) — fingerprint-carried, so a
    ;; deviation is reviewable law, and deleting one shows in the diff
    (let [ds (:deviations rmap)]
      (when-not (and (or (nil? ds) (vector? ds))
                     (every? #(and (string? %) (not (str/blank? %))) ds))
        (throw (t/definition-error
                ":deviations is a vector of sentences — each recorded deviation explains itself"))))
    ;; check-secret! runs LAST — colocated sugar and the vocab
    ;; self-merge must land before the disposition judges the
    ;; filter/facet surface it forbids
    (check-secret!
     (-> rmap
        project-colocated
        desugar-owns
        (update :plural #(or % (str (name kind) "s")))
        (update :terminal set)
        (update :states vec)
        (update :actions (fn [actions]
                           (into (sorted-map)
                                 (map (fn [[aname a]]
                                        ;; an :undo pointer IS the declaration
                                        ;; of reversibility; the pointer itself
                                        ;; is verified once every action is
                                        ;; normalized (verify-undo-pointers)
                                        [aname (normalize-action
                                                kind aname
                                                (cond-> a
                                                  (:undo a)
                                                  (assoc-in [:safety :reversible]
                                                            true)))]))
                                 actions)))
        verify-undo-pointers
        (update :create-guards #(vec (or % [])))
        (update :create-action-names #(or % #{:create}))
        (update :adoption #(or % :immediate))
        (update :nav #(or % :primary))
        (update :shape #(or % 1))
        (update :allow-dead set)
        (update :deviations #(vec (or % [])))
        normalize-default-filters
        normalize-views
        normalize-own-surface
        ;; the continuity map (migrate): retired tokens → their current
        ;; spellings; boot refuses rows in states neither declared nor
        ;; mapped, and replay-history reads the log through the chain
        (update :renames #(merge {:states {} :actions {}} %))
        normalize-derived
        bind-require-specs
        merge-vocab-filters))))

;; ── the import-time gate ────────────────────────────────────────────

(defn resource
  "Normalize, run the full check battery (throws the named check's
  DefinitionError), surface usability warnings on *err*, and return
  the declaration the engine serves."
  [rmap]
  (declaration/check! rmap)
  (let [r (normalize-resource rmap)
        {:keys [warnings]} (checks/run-all r)]
    (doseq [w warnings]
      (binding [*out* *err*]
        (println (str "waymark10 usability warning [" (name (:kind r)) "] " w))))
    ;; the recall affordance advertises itself (probe defect D5):
    ;; import-time prints scroll away, the metadata doesn't
    (when (seq warnings)
      (binding [*out* *err*]
        (println (str "waymark10: warnings ride the declaration — "
                      "(waymark10.dev/explain " (name (:kind r))
                      ") re-reads them any time"))))
    (vary-meta r assoc :waymark10/warnings (vec warnings))))

(defmacro defresource
  [name rmap]
  `(def ~name (resource ~rmap)))

(defn in-domain
  "Stamp every declaration with the domain token — the domain module's
  assembly fn wraps its kind list so the tag can't drift from the
  namespace that owns it. A kind may also self-declare :domain; the
  stamp only fills the blank, it never overrides. Domain placement is
  advertisement (well-known carries it, the generic UI's global nav
  reads it), not fingerprinted law — the :nav precedent."
  [domain resources]
  (when-not (and (keyword? domain)
                 (re-matches #"[a-z][a-z0-9_]*" (name domain)))
    (throw (t/definition-error
            (str "in-domain: domain must be a snake_case keyword token, got "
                 (pr-str domain)))))
  (mapv #(update % :domain (fn [d] (or d domain))) resources))

(defmacro defhandler
  "An imperative-residue handler whose identity is its canonical
  printed form: (defhandler assign-meal [row inp ctx] …)."
  [name params & body]
  `(def ~name
     (with-meta (fn ~params ~@body)
       {:waymark10/form '~(list* 'fn params body)})))

(defn fingerprint
  "The canonical projection of a normalized resource declaration."
  [rmap]
  (fp/fingerprint-of rmap))
