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
  one field is the :one-home definition error."
  (:require [clojure.string :as str]
            [waymark10.checks :as checks]
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
          display (if (and (:consequence safety) (nil? (:description display)))
                    (assoc display :description (:consequence safety))
                    display)]
      (assoc a
             :from from
             :to (:to a)
             :safety safety
             :guards guards
             :display display
             :waives (set (:waives a))
             :emits (vec (:emits a))))))

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
    (update-in [:count :where] update-vals set)))

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

(defn normalize-resource
  [rmap]
  (let [{:keys [kind states initial summary]} rmap]
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
    (when-not (contains? #{:primary :secondary} (:nav rmap :primary))
      (throw (t/definition-error ":nav is :primary or :secondary")))
    (-> rmap
        project-colocated
        (update :plural #(or % (str (name kind) "s")))
        (update :terminal set)
        (update :states vec)
        (update :actions (fn [actions]
                           (into (sorted-map)
                                 (map (fn [[aname a]]
                                        [aname (normalize-action kind aname a)]))
                                 actions)))
        (update :create-guards #(vec (or % [])))
        (update :create-action-names #(or % #{:create}))
        (update :adoption #(or % :immediate))
        (update :nav #(or % :primary))
        (update :shape #(or % 1))
        (update :allow-dead set)
        ;; the continuity map (migrate): retired tokens → their current
        ;; spellings; boot refuses rows in states neither declared nor
        ;; mapped, and replay-history reads the log through the chain
        (update :renames #(merge {:states {} :actions {}} %))
        normalize-derived
        bind-require-specs
        merge-vocab-filters)))

;; ── the import-time gate ────────────────────────────────────────────

(defn resource
  "Normalize, run the full check battery (throws the named check's
  DefinitionError), surface usability warnings on *err*, and return
  the declaration the engine serves."
  [rmap]
  (let [r (normalize-resource rmap)
        {:keys [warnings]} (checks/run-all r)]
    (doseq [w warnings]
      (binding [*out* *err*]
        (println (str "waymark10 usability warning [" (name (:kind r)) "] " w))))
    (vary-meta r assoc :waymark10/warnings (vec warnings))))

(defmacro defresource
  [name rmap]
  `(def ~name (resource ~rmap)))

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
