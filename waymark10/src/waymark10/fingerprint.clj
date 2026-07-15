(ns waymark10.fingerprint
  "The canonical projection of a resource declaration — the law as the
  reviewer, the diff gate, and the overlays read it.

  The fingerprint is a projection of the same declaration map the
  engine consumes, never a second description. Expression laws are
  stored as wire trees (waymark10.wire), never hashed — the diff pins
  the exact leaf that moved and any stored revision reads back into an
  evaluable form. Only the imperative residue (handlers, code guards)
  is hashed, by canonical printed form.

  Ported semantics (waymark9 core/fingerprint.py):
  - classify-path → :shape / :judgment / :truth / :advertisement,
    innermost owning surface wins; derived explain/vars garnish is
    advertisement; unmatched defaults to :truth (an unclassified
    change is conservatively a change of meaning).
  - classify-diff → :data-law when EVERY changed path is overlayable
    (a derivation's tolerance/expr/edge-where, or a recoverable leaf
    of a top-level expression guard), else :code-or-shape.
  - stale-facts → derived facts whose semantic surface moved."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.schema :as schema]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── projection helpers ──────────────────────────────────────────────

(defn- form-tree [form]
  (wire/form->wire (expr/normalize form)))

(defn callable-hash
  "Identity for the imperative residue. Strings pass through (already
  a hash); anything else hashes by canonical printed form — the
  defhandler/defguard macros (phase 1) supply the source form as
  metadata; a bare fn hashes by its printed identity as a stopgap the
  checks will later refuse in strict mode."
  [h]
  (cond
    (nil? h) nil
    (string? h) h
    :else (wire/sha256-hex (pr-str (or (:waymark10/form (meta h)) h)))))

(defn- guard-fp [g]
  (if-some [w (:when g)]
    ;; expression guard: the stored tree IS the law; check is nil
    {"name"           (name (:name g))
     "expr"           (form-tree w)
     "vars_exprs"     (into (sorted-map)
                            (map (fn [[k f]] [(name k) (form-tree f)]))
                            (:vars g))
     "explain"        (:explain g)
     "remedies"       (mapv (fn [r]
                              (if (qualified-keyword? r)
                                (str (namespace r) "." (name r))
                                (name r)))
                            (:remedies g []))
     "hide"           (boolean (:hide g))
     "severity"       (name (:severity g :refuse))
     "requires_token" (:requires-token g)
     "check"          nil}
    {"name"           (name (:name g))
     "check"          (callable-hash (or (:check g) (:accepts g)))
     "explain"        (:explain g)
     "remedies"       (mapv (fn [r]
                              (if (qualified-keyword? r)
                                (str (namespace r) "." (name r))
                                (name r)))
                            (:remedies g []))
     "hide"           (boolean (:hide g))
     "severity"       (name (:severity g :refuse))
     "requires_token" (:requires-token g)}))

(defn- aggregate-fp
  "The aggregate facet, canonical: the edge by name, where sets as
  sorted vectors — a set has no order, a fingerprint must. :sum
  (batch C) adds :of, the summed target field — edge-identity class,
  like the edge names."
  [c]
  (cond-> {}
    (:related c) (assoc "related" (name (:related c)))
    (:owns c)    (assoc "owns" (name (:owns c)))
    (:of c)      (assoc "of" (name (:of c)))
    (:where c)   (assoc "where" (into (sorted-map)
                                      (map (fn [[f vs]]
                                             [(name f) (vec (sort-by str vs))]))
                                      (:where c)))))

(defn- derived-fp [d]
  (cond-> {"over" (mapv name (:over d))}
    (contains? d :expr)      (assoc "expr" (form-tree (:expr d)))
    (contains? d :count)     (assoc "count" (aggregate-fp (:count d)))
    (contains? d :sum)       (assoc "sum" (aggregate-fp (:sum d)))
    (contains? d :fn)        (assoc "fn" (callable-hash (:fn d)))
    (contains? d :tolerance) (assoc "tolerance" (str (:tolerance d)))
    (:explain d)             (assoc "explain" (:explain d))
    (:vars d)                (assoc "vars" (into (sorted-map)
                                                 (map (fn [[k f]] [(name k) (form-tree f)]))
                                                 (:vars d)))))

(defn- safety-fp [s]
  {"idempotent" (boolean (:idempotent s))
   "reversible" (boolean (:reversible s))
   "confirm"    (boolean (:confirm s))})

(defn- storage-fp
  "The storage facet: the kind's table projection canonicalized —
  columns sorted by name with type-as-string + generated flag, index
  names sorted. The SAME projection the DDL renders from
  (store/kind-projection), so a promotion change is law: classify-path
  files storage.* under :shape, the diff is :code-or-shape, and the
  boot promotes totally. Generation expressions are excluded on both
  sides (they derive mechanically from field + type)."
  [rmap]
  (let [{:keys [table columns indexes]} (store/kind-projection rmap)]
    {"table" table
     "columns" (into (sorted-map)
                     (map (fn [{col :name :keys [type generated?]}]
                            [col {"type" type
                                  "generated" (boolean generated?)}]))
                     columns)
     "indexes" (vec (sort (keys indexes)))}))

(defn- action-fp [a]
  (cond-> {"from"    (vec (sort (map name (:from a))))
           "to"      (name (:to a))
           "safety"  (safety-fp (:safety a))
           "guards"  (mapv guard-fp (:guards a []))
           "handler" (callable-hash (:handler a))}
    ;; blast radius is law (waymark9 touches=): projected only when
    ;; declared, so every touch-free action hashes byte-identical to
    ;; the pre-touches era
    (seq (:touches a))
    (assoc "touches" (mapv (fn [t]
                             (cond-> {"kind" (name (:kind t))
                                      "action" (name (:action t))}
                               (:may t) (assoc "may" true)))
                           (:touches a)))))

(defn- authority-fp
  "The mirror sync-law facet: the document contract, push-on-write,
  and per-field authority (external keys, adopts/frozen windows).
  The sync CADENCES (:ttl-seconds :discover-every) are OPERATIONS,
  deliberately outside the law — a polling tweak must not mint a
  revision. Empty (and omitted) for a pull-only, whole-document,
  window-less mirror, so every such kind's hash stays byte-identical
  to the pre-authority era."
  [rmap]
  (let [spec (:mirror rmap)
        fields (into (sorted-map)
                     (keep (fn [[f {:keys [properties]}]]
                             (let [{:keys [external-key adopts frozen
                                           expect]} properties
                                   m (cond-> {}
                                       external-key
                                       (assoc "external_key" (name external-key))
                                       adopts (assoc "adopts" adopts)
                                       (true? frozen) (assoc "frozen" true)
                                       (string? frozen) (assoc "frozen" frozen)
                                       (keyword? expect)
                                       (assoc "expect" (name expect))
                                       (map? expect)
                                       (assoc "expect" {"churn" (:churn expect)}))]
                               (when (seq m) [(name f) m]))))
                     (schema/entry-map (:schema rmap)))]
    (cond-> {}
      (= :partial (:document spec)) (assoc "document" "partial")
      (:push-on-write spec) (assoc "push_on_write" true)
      (seq fields) (assoc "fields" fields))))

(defn fingerprint-of
  "Deterministic, canonically-encodable projection of a resource
  declaration map. Phase-0 facets: machine (states, actions with
  guards/safety/handler) and derived; the migrate phase adds storage
  (the table projection — present when the declaration carries a
  schema, i.e. every normalized kind); later phases add create,
  schema, owns, vocab, query, links — each facet landing with the
  feature that declares it. A mirror kind additionally projects its
  AUTHORITY facet (sync law: document contract, push-on-write,
  external keys, adopts/frozen windows) — never its cadences."
  [rmap]
  (cond-> {"kind" (name (:kind rmap))
           "machine"
           {"states"   (mapv name (:states rmap))
            "initial"  (name (:initial rmap))
            "terminal" (vec (sort (map name (:terminal rmap))))
            "actions"  (into (sorted-map)
                             (map (fn [[k a]] [(name k) (action-fp a)]))
                             (:actions rmap))}
           "derived"
           (into (sorted-map)
                 (map (fn [[k d]] [(name k) (derived-fp d)]))
                 (:derived rmap))}
    ;; the facet exists exactly when the declaration names a table —
    ;; :plural is normalized in, so every registered kind carries it
    (and (:schema rmap) (:plural rmap))
    (assoc "storage" (storage-fp rmap))

    ;; recorded deviations are reviewable law (advertisement-class):
    ;; editing one shows in the diff and mints a revision. Projected
    ;; only when non-empty, so every deviation-free kind's hash is
    ;; byte-identical to the pre-deviations era
    (seq (:deviations rmap))
    (assoc "deviations" (vec (:deviations rmap)))

    (and (:mirror rmap) (seq (authority-fp rmap)))
    (assoc "authority" (authority-fp rmap))))

(defn fingerprint-hash ^String [fp]
  (wire/digest fp))

;; ── path classification (design §4's four classes) ──────────────────

(def ^:private shape-family #{"storage" "shape" "upcasts"})
(def ^:private judgment-family #{"guards" "unless" "when" "unique" "vocab"
                                 "safety" "tolerance"})
(def ^:private truth-family #{"derived" "machine" "authored" "owns" "compound"
                              "touches" "batch" "bulk" "handler" "renames"
                              "renamed_actions" "renamed_fields" "authority"})
(def ^:private advertisement-family #{"display" "field_display" "summary"
                                      "label_template" "explain" "links"
                                      "profiles" "query" "data_schema"
                                      "input_schema" "schema" "edit" "place"
                                      "row_affordances" "plural" "nav"
                                      "deviations"})
(def ^:private derived-garnish #{"explain" "vars"})

(defn classify-path
  "Rules are ordered: a guard's explain under machine.actions.*.guards
  is a judgment change, not a machine change — the innermost owning
  surface wins. A derivation's explain/vars are refusal-surface
  garnish: advertisement."
  [^String path]
  (let [segs (str/split path #"\.")]
    (if (and (= (first segs) "derived")
             (some derived-garnish (drop 2 segs)))
      :advertisement
      (or (some (fn [[family cls]]
                  (when (some family segs) cls))
                [[shape-family :shape]
                 [judgment-family :judgment]
                 [truth-family :truth]
                 [advertisement-family :advertisement]])
          :truth))))

;; ── the diff ────────────────────────────────────────────────────────

(defn- flatten-fp [v prefix]
  (cond
    (and (map? v) (seq v))
    (into {}
          (mapcat (fn [[k x]]
                    (flatten-fp x (str prefix
                                       (if (keyword? k) (name k) (str k))
                                       "."))))
          v)

    (and (sequential? v) (seq v))
    (into {}
          (comp (map-indexed (fn [i x] (flatten-fp x (str prefix i "."))))
                cat)
          v)

    :else
    {(if (str/ends-with? prefix ".")
       (subs prefix 0 (dec (count prefix)))
       prefix)
     (if (or (map? v) (sequential? v)) "<empty>" v)}))

(defn diff-fingerprints
  "Path-level diff of two fingerprints, each path tagged with its §4
  class. List paths are positional — a reordered guard list reads as
  changed paths, which is honest: order is evaluation order."
  [old new]
  (let [a (flatten-fp old "")
        b (flatten-fp new "")
        entries (fn [paths]
                  (mapv (fn [p] {:path p :class (classify-path p)})
                        (sort paths)))]
    ;; contains?, not map-as-predicate: a leaf holding nil/false (a
    ;; guard's check, an off safety flag) is still a present path
    {:added   (entries (remove #(contains? a %) (keys b)))
     :removed (entries (remove #(contains? b %) (keys a)))
     :changed (entries (for [p (keys a)
                             :when (and (contains? b p)
                                        (not= (a p) (b p)))]
                         p))}))

;; ── the deploy-mode gate: what the overlays can serve ───────────────
;; data law (waymark8 §2): a derivation's stored parameters or tree.
;; judgment law (waymark9 §2): the recoverable leaves of a top-level
;; expression guard. Everything else is code_or_shape — the resident
;; objects ARE that law, and a hold would be a lie.

;; count.where is the tolerance precedent (phase 6): the filter values
;; are stored parameters an overlay can serve; the edge itself
;; (count.related / count.owns) is :code-or-shape — the maintainer's
;; reverse map is built from resident declarations. sum.where (batch
;; C) inherits the rule verbatim; sum.of is edge identity — the
;; column the SQL reads — and stays :code-or-shape with the edges.
(def ^:private data-law-path
  #"^derived\.[^.]+\.(?:tolerance$|expr(?:\..+)?$|(?:count|sum)\.where(?:\..+)?$|over\.\d+\.(?:child|related)\.where(?:\..+)?$)")

(def ^:private judgment-law-path
  #"^machine\.actions\.[^.]+\.guards\.\d+\.(?:expr(?:\..+)?$|vars_exprs(?:\..+)?$|explain$|remedies(?:\.\d+)?$|hide$|severity$|requires_token$)")

(defn classify-diff
  ":data-law when every added/removed/changed path is overlayable —
  may be held at proposed / piloted per population; else
  :code-or-shape — promotes totally. An empty diff cannot reach here
  (the hash moved)."
  [diff]
  (let [paths (map :path (mapcat diff [:added :removed :changed]))]
    (if (and (seq paths)
             (every? #(or (re-matches data-law-path %)
                          (re-matches judgment-law-path %))
                     paths))
      :data-law
      :code-or-shape)))

(defn stale-facts
  "Derived facts marked stale by definition: every fact named by an
  added/changed/removed path under derived.<fact>., garnish excluded —
  a materialized value computed under the previous law can disagree
  with the semantic surface; a changed explain cannot."
  [diff]
  (->> (mapcat diff [:added :changed :removed])
       (keep (fn [{:keys [path]}]
               (let [segs (str/split path #"\.")]
                 (when (and (= (first segs) "derived")
                            (>= (count segs) 3)
                            (not (some derived-garnish (drop 2 segs))))
                   (second segs)))))
       distinct
       sort
       vec))
