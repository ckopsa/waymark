(ns waymark10.resource
  "The declaration surface: a resource is one map, normalized and
  validated at load. defresource is the import-time gate — a
  declaration the checks refuse never serves a request.

  Normalized action keys: :from (set), :to, :input (malli form),
  :guards (vector), :safety (validated), :display, :handler,
  :emits, :edit, :place, :bulk, :batch, :waives (set), :touches,
  :unless (transition kw; its guard is appended), :record."
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

(defn- normalize-action
  "Construction-shape validation, ported from waymark9 actions.py:
  safety declared never inferred; record needs input; bulk excludes
  drafts; batch needs input, excludes place/bulk and the fence; an
  Edit implies the fence."
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

(defn- normalize-derived [rmap]
  (update rmap :derived
          (fn [derived]
            (into {}
                  (map (fn [[fact d]]
                         [fact
                          (cond-> d
                            (:expr d) (update :expr expr/normalize)
                            (:vars d) (update :vars update-vals expr/normalize)
                            ;; count where values are sets — two
                            ;; spellings of one membership are one law
                            (get-in d [:count :where])
                            (update-in [:count :where] update-vals set))]))
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
