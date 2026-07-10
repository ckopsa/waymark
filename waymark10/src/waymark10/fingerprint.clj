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

(defn- derived-fp [d]
  (cond-> {"over" (mapv name (:over d))}
    (contains? d :expr)      (assoc "expr" (form-tree (:expr d)))
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

(defn- action-fp [a]
  {"from"    (vec (sort (map name (:from a))))
   "to"      (name (:to a))
   "safety"  (safety-fp (:safety a))
   "guards"  (mapv guard-fp (:guards a []))
   "handler" (callable-hash (:handler a))})

(defn fingerprint-of
  "Deterministic, canonically-encodable projection of a resource
  declaration map. Phase-0 facets: machine (states, actions with
  guards/safety/handler) and derived; later phases add create, schema,
  owns, vocab, query, links, storage — each facet landing with the
  feature that declares it."
  [rmap]
  {"kind" (name (:kind rmap))
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
         (:derived rmap))})

(defn fingerprint-hash ^String [fp]
  (wire/digest fp))

;; ── path classification (design §4's four classes) ──────────────────

(def ^:private shape-family #{"storage" "shape" "upcasts"})
(def ^:private judgment-family #{"guards" "unless" "when" "unique" "vocab"
                                 "safety" "tolerance"})
(def ^:private truth-family #{"derived" "machine" "authored" "owns" "compound"
                              "touches" "batch" "bulk" "handler" "renames"
                              "renamed_actions" "renamed_fields"})
(def ^:private advertisement-family #{"display" "field_display" "summary"
                                      "label_template" "explain" "links"
                                      "profiles" "query" "data_schema"
                                      "input_schema" "schema" "edit" "place"
                                      "row_affordances" "plural" "nav"})
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

(def ^:private data-law-path
  #"^derived\.[^.]+\.(?:tolerance$|expr(?:\..+)?$|over\.\d+\.(?:child|related)\.where(?:\..+)?$)")

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
