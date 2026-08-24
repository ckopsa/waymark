(ns waymark10.fingerprint
  "The canonical projection of a resource declaration — the law as the
  reviewer, the diff gate, and the overlays read it.

  The fingerprint is a projection of the same declaration map the
  engine consumes, never a second description. Expression laws are
  stored as wire trees (waymark10.wire), never hashed — the diff pins
  the exact leaf that moved and any stored revision reads back into an
  evaluable form. Only the imperative residue (handlers, code guards)
  is hashed, by canonical printed form — and where there is no form to
  print, by the ADDRESS the declaration carries the code at, never by
  the resident object (waymark-j82; see callable-hash for the trade).
  The fingerprint must be a function of the declaration and of nothing
  else: not of load order, not of a class counter, not of a JVM run.

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

(def ^:private opaque-address
  "The salt that makes an address-hash unmistakable in a diff: this
  leaf says WHERE opaque code sits, not what it does."
  "waymark10.opaque-code@")

(defn callable-hash
  "Identity for the imperative residue, at the address the declaration
  carries it. Strings pass through (already a hash); a callable
  carrying :waymark10/form — what defhandler, defguard and the
  hand-built sugar forms supply — hashes by that canonical printed
  form, byte-for-byte as it always has.

  A BARE fn has no content to hash, so it hashes by its ADDRESS: the
  kind, action and slot the declaration puts it in. That is the same
  honesty the decision record already keeps about opaque code — the
  fingerprint says where the opacity is, never what it does — and the
  trade is recorded loudly here: swapping one bare fn's body for
  another at the same address is INVISIBLE to the fingerprint. It was
  exactly as invisible before (a printed object identity says nothing
  about a body either); what changes is that it is now STABLE.

  Before waymark-j82 a bare fn hashed by `pr-str` of the object —
  #object[…$fn__12873 0x7698a3d9 …] — a compiler-assigned class
  number and a JVM identity hash, both functions of everything loaded
  before it. Nine workqueue10 kinds therefore hashed differently on
  every boot with no code change at all, and production minted 73 law
  revisions that were almost entirely boot noise. An identity that
  moves without the law moving is not an identity.

  The stopgap posture stands, one step further along: the checks now
  WARN on a bare fn in a fingerprinted slot (checks/[opaque-residue]),
  and a strict mode may later refuse it. The cure is the same one it
  always was — declare it with defguard/defhandler, or mint its form
  by hand the way guards/not-the-field does — which upgrades the leaf
  from an address to a law."
  [address h]
  (cond
    (nil? h) nil
    (string? h) h
    :else (if-some [form (:waymark10/form (meta h))]
            (wire/sha256-hex (pr-str form))
            (wire/sha256-hex (str opaque-address address)))))

(defn- guard-fp [address g]
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
     "check"          (callable-hash (str address ".check")
                                     (or (:check g) (:accepts g)))
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
  like the edge names — and :when-empty, the empty-sum semantics
  (projected only when declared, so every default-spelled sum hashes
  byte-identical to before the option existed)."
  [c]
  (cond-> {}
    (:related c)    (assoc "related" (name (:related c)))
    (:owns c)       (assoc "owns" (name (:owns c)))
    (:of c)         (assoc "of" (name (:of c)))
    (:when-empty c) (assoc "when_empty" (name (:when-empty c)))
    (:where c)      (assoc "where" (into (sorted-map)
                                         (map (fn [[f vs]]
                                                [(name f) (vec (sort-by str vs))]))
                                         (:where c)))))

(defn- derived-fp [address d]
  (cond-> {"over" (mapv name (:over d))}
    (contains? d :expr)      (assoc "expr" (form-tree (:expr d)))
    (contains? d :count)     (assoc "count" (aggregate-fp (:count d)))
    (contains? d :sum)       (assoc "sum" (aggregate-fp (:sum d)))
    (contains? d :fn)        (assoc "fn" (callable-hash (str address ".fn")
                                                        (:fn d)))
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

(defn- unwrap-maybe-form [s]
  (if (and (vector? s) (= :maybe (first s))) (second s) s))

(defn- canonical-default
  "A default value as canonical wire law: decimals cross as the
  {\"dec\" …} node (exact, no floats), keywords as names, dates as
  ISO strings — the same discipline every law tree already keeps."
  [v]
  (cond
    (decimal? v) {"dec" (.toPlainString ^java.math.BigDecimal v)}
    (keyword? v) (name v)
    (instance? java.time.LocalDate v) (str v)
    (instance? java.time.Instant v) (str v)
    (sequential? v) (mapv canonical-default v)
    (map? v) (into (sorted-map)
                   (map (fn [[k x]] [(if (keyword? k) (name k) k)
                                     (canonical-default x)]))
                   v)
    :else v))

(defn- schema-defaults
  "The declared :default values of a schema form, flattened —
  {\"field\" value}, item defaults as \"field.item_field\". Empty map
  when the form declares none (the facet stays absent — hash
  stability for the default-free world)."
  [form]
  (if (nil? form)
    (sorted-map)
    (into (sorted-map)
          (mapcat (fn [[k {:keys [properties schema]}]]
                    (let [s (unwrap-maybe-form schema)
                          item (when (and (vector? s) (= :vector (first s)))
                                 (last s))
                          own (when (contains? properties :default)
                                [[(name k) (canonical-default
                                            (:default properties))]])
                          nested (when (and (vector? item)
                                            (= :map (first item)))
                                   (map (fn [[ik iv]]
                                          [(str (name k) "." ik) iv])
                                        (schema-defaults item)))]
                      (concat own nested))))
          (schema/entry-map form))))

(defn- action-fp [address a]
  (cond-> {"from"    (vec (sort (map name (:from a))))
           "to"      (name (:to a))
           "safety"  (safety-fp (:safety a))
           ;; a guard's address names it, never its position: inserting
           ;; a wall ahead of another must not restate the other's
           ;; identity (the diff paths stay positional, and honest)
           "guards"  (mapv (fn [g]
                             (guard-fp (str address ".guards."
                                            (name (:name g)))
                                       g))
                           (:guards a []))
           "handler" (callable-hash (str address ".handler")
                                    (:handler a))}
    ;; a default changes what a blank write stores — law, non-empty-only
    (seq (schema-defaults (:input a)))
    (assoc "input_defaults" (schema-defaults (:input a)))
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
      ;; the gone-policy is law (what a feed-dropped row MEANS);
      ;; :keep — the default — stays out, hash-identical to before
      (map? (:on-gone spec))
      (assoc "on_gone" {"set" (into (sorted-map)
                                    (map (fn [[f v]] [(name f) v]))
                                    (get-in spec [:on-gone :set]))})
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
  external keys, adopts/frozen windows) — never its cadences.

  The kind's name roots the ADDRESS every opaque leaf hashes by (see
  callable-hash): `plan_day.machine.actions.assign_meal.guards.meal-
  fits-day.check` is where that opacity sits, and the address is the
  only thing about it the law can honestly state."
  [rmap]
  (let [kind (name (:kind rmap))
        actions (into (sorted-map)
                      (map (fn [[k a]]
                             [(name k)
                              (action-fp
                               (str kind ".machine.actions." (name k)) a)]))
                      (:actions rmap))
        derived (into (sorted-map)
                      (map (fn [[k d]]
                             [(name k)
                              (derived-fp
                               (str kind ".derived." (name k)) d)]))
                      (:derived rmap))]
    (cond-> {"kind" kind
             "machine"
             {"states"   (mapv name (:states rmap))
              "initial"  (name (:initial rmap))
              "terminal" (vec (sort (map name (:terminal rmap))))
              "actions"  actions}
             "derived" derived}
      ;; the facet exists exactly when the declaration names a table —
      ;; :plural is normalized in, so every registered kind carries it
      (and (:schema rmap) (:plural rmap))
      (assoc "storage" (storage-fp rmap))

      ;; the :secret disposition (waymark-kyg) is shape-class law: a field
      ;; whose value never leaves the engine. Removing it flips a field
      ;; concealed→world-readable, so it must mint a revision and show in
      ;; the diff. Non-empty-only, so every secret-free kind hashes
      ;; byte-identical to before the disposition existed
      (and (:schema rmap) (seq (schema/secret-fields (:schema rmap))))
      (assoc "shape" {"secret" (vec (sort (map name (schema/secret-fields
                                                     (:schema rmap)))))})

      ;; the create facet (design §24, anticipated above): declared
      ;; field defaults are law — a default changes what a blank write
      ;; stores. Non-empty-only: the default-free world hashes as ever
      (seq (schema-defaults (or (:create-schema rmap) (:schema rmap))))
      (assoc "create" {"defaults" (schema-defaults
                                   (or (:create-schema rmap) (:schema rmap)))})

      ;; recorded deviations are reviewable law (advertisement-class):
      ;; editing one shows in the diff and mints a revision. Projected
      ;; only when non-empty, so every deviation-free kind's hash is
      ;; byte-identical to the pre-deviations era
      (seq (:deviations rmap))
      (assoc "deviations" (vec (:deviations rmap)))

      (and (:mirror rmap) (seq (authority-fp rmap)))
      (assoc "authority" (authority-fp rmap)))))

(defn fingerprint-hash ^String [fp]
  (wire/digest fp))

;; ── path classification (design §4's four classes) ──────────────────

(def ^:private shape-family #{"storage" "shape" "upcasts"})
(def ^:private judgment-family #{"guards" "unless" "when" "unique" "vocab"
                                 "safety" "tolerance"})
(def ^:private truth-family #{"derived" "machine" "authored" "owns" "compound"
                              "touches" "batch" "bulk" "handler" "renames"
                              "renamed_actions" "renamed_fields" "authority"
                              "create"})
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
;; column the SQL reads — and stays :code-or-shape with the edges, as
;; does sum.when_empty — what an empty sum MEANS is the resident
;; read, not a stored parameter, so flipping it promotes totally (and
;; the promote's backfill restamps every landed value).
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
