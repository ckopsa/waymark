(ns waymark10.derived
  "Derivations: one fact, one definition. The pure half — computing a
  row's own expression facts from its data. The maintainer (cross-row
  recompute, clock flips, backfill) is server machinery
  (waymark10.server.maintainer, phase 6); this namespace never reads
  anything but the arguments.

  A spec {:over [kw …] :expr form} evaluates with (var k) bound to the
  row's field k; :now in :over binds the injected clock. Facts may
  depend on other facts of the same kind; materialization runs in
  dependency order (the cycle check refused cycles at load).

  Phase 6 added the aggregate spec: {:count {:related <edge> | :owns
  <child-kind>, :where {field #{values}}}} — the count of
  edge-matching rows, computed by the maintainer's SQL, never here.
  Batch C adds its sibling, earned by the meal-prep quantity rollup
  the blast-radius acceptance declares: {:sum {:related <edge> |
  :owns <child-kind>, :where {…}, :of <target-field>}} — the exact
  sum of the target field over the same edge-matched rows; its
  optional :when-empty :absent lands nil instead of 0 when the sum
  has no contributions (no information is not a zero). Cross-kind
  facts read ONLY through these declared edges; the expression
  language stays own-scope. An aggregate fact composes like any data
  field: a derived-over-derived expression names it in :over and
  reads its materialized value.

  THE DERIVED-LAW OVERLAY (batch C — judgment's structural twin,
  waymark9 derived.py specs_for): specs-under resolves a kind's
  derivation specs under the ROW's law revision. The rdef's
  :judgment-laws slot ({revision → stored fingerprint}, installed by
  the definitions lifecycle) holds every law that must be served from
  the store; a revision with an entry gets its stored expr trees and
  aggregate where-filters in place of the resident ones. Edge
  identity (:over, :owns, :related, :of) rides RESIDENT, the judgment
  precedent verbatim: those are :code-or-shape surfaces the data-law
  gate refuses to let differ between live revisions, so the overlay
  is EXACT for every law the slot can hold. fn= facts stay resident —
  a hash is not a law (waymark8's boundary, the v7 deviation now
  confined to fn facts). A stored tree that cannot be read back does
  not crash the write: the resident spec serves and *err* says so.
  Resolved maps cache per revision on the rdef's :judgment-cache
  (reset by every definitions install!, so invalidation comes free)."
  (:require [waymark10.expr :as expr]
            [waymark10.wire :as wire]))

;; ── the derived-law overlay ─────────────────────────────────────────

(defn- overlaid-spec
  "One resident spec with one stored fingerprint entry's recoverable
  leaves in place: the expr tree, the aggregate :where. nil entry, an
  fn= spec, or an unreadable tree → resident."
  [fact spec entry]
  (if-not (map? entry)
    spec
    (try
      (cond-> spec
        (and (:expr spec) (some? (get entry "expr")))
        (assoc :expr (wire/wire->form (get entry "expr")))

        (and (:count spec) (get-in entry ["count" "where"]))
        (assoc-in [:count :where]
                  (into {}
                        (map (fn [[f vs]] [(keyword f) (set vs)]))
                        (get-in entry ["count" "where"])))

        (and (:sum spec) (get-in entry ["sum" "where"]))
        (assoc-in [:sum :where]
                  (into {}
                        (map (fn [[f vs]] [(keyword f) (set vs)]))
                        (get-in entry ["sum" "where"]))))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "waymark10 derived: stored spec for fact "
                        (pr-str fact)
                        " could not be rebuilt; the resident spec serves — "
                        (ex-message e))))
        spec))))

(defn specs-from
  "The resident derived map with one stored fingerprint's recoverable
  leaves (expr trees, count/sum where-filters) substituted in — the
  blast-radius meter's \"this law's specs\" read. Name-checked by
  construction (facts key the map); edge identity rides resident."
  [rmap fp]
  (let [stored (get fp "derived")]
    (into {}
          (map (fn [[fact spec]]
                 [fact (overlaid-spec fact spec (get stored (name fact)))]))
          (:derived rmap))))

(defn specs-under
  "THE per-row derivation seam: the kind's derived spec map under one
  law revision. Resident when nothing overlays — a nil stamp
  (pre-law), the resident revision, an engine without the definitions
  boot — so the common case costs one map lookup."
  [rmap revision]
  (if-some [fp (and revision (get (:judgment-laws rmap) revision))]
    (let [cache (:judgment-cache rmap)
          k [revision ::specs]]
      (or (when cache (get @cache k))
          (let [d (specs-from rmap fp)]
            (when cache (swap! cache assoc k d))
            d)))
    (:derived rmap)))

;; ── spec selection ──────────────────────────────────────────────────

(defn- aggregate? [spec] (boolean (or (:count spec) (:sum spec))))

(defn aggregate-specs
  "The kind's aggregate facts ({:count …} / {:sum …} specs) — the
  maintainer's to compute (SQL over the edge's target); materialize
  never touches them, it only reads their stored values as inputs.
  With a revision, the specs resolve under that law (where-filters
  from the stored fingerprint)."
  ([rmap] (into {} (filter (comp aggregate? val)) (:derived rmap)))
  ([rmap revision]
   (into {} (filter (comp aggregate? val)) (specs-under rmap revision))))

(defn count-specs
  "Phase 6's name for the aggregate facts, kept: callers that predate
  :sum read the whole aggregate family through it."
  [rmap]
  (aggregate-specs rmap))

(defn- expr-facts*
  "A derived spec MAP's expression facts, dependency-ordered: a fact
  whose :over names another fact computes after it."
  [derived]
  (let [expr-only (into {} (filter (comp :expr val)) derived)
        fact? (set (keys expr-only))
        deps (fn [[_ d]] (filter fact? (:over d)))]
    (loop [ordered []
           remaining expr-only]
      (if (empty? remaining)
        ordered
        (let [ready (filter (fn [e] (not-any? (set (keys remaining)) (deps e)))
                            remaining)]
          (if (empty? ready)
            ;; a cycle — the checks refused this at load; degrade to
            ;; declaration order rather than loop forever
            (into ordered remaining)
            (recur (into ordered ready)
                   (apply dissoc remaining (map key ready)))))))))

(defn expr-facts
  "The kind's expression-derived facts, dependency-ordered; with a
  revision, resolved under that law's stored trees."
  ([rmap] (expr-facts* (:derived rmap)))
  ([rmap revision] (expr-facts* (specs-under rmap revision))))

;; ── computation ─────────────────────────────────────────────────────

(defn compute-facts
  "Expression facts computed over `data` under an explicit spec map,
  in dependency order — the blast-radius meter's \"this row under
  that law\" read. Total: a missing input flows to a nil fact."
  [derived data now]
  (reduce (fn [d [fact spec]]
            (let [vars (into {}
                             (map (fn [k]
                                    [k (if (= k :now) now (get d k))]))
                             (:over spec))]
              (assoc d fact (expr/evaluate (:expr spec)
                                           {:vars vars :now now}))))
          data
          (expr-facts* derived)))

(defn materialize
  "Recompute the row's own expression facts into :data — under the
  ROW's law (the :law-revision stamp resolves the specs through the
  derived-law overlay; batch C closes waymark9's v7 deviation for
  expr facts: a grandfathered row's facts recompute under its birth
  law). Total: a missing input flows to a nil fact, never a throw."
  [rmap row now]
  (update row :data
          #(compute-facts (specs-under rmap (:law-revision row)) % now)))

(defn tampered
  "Derived fields the handler wrote values into that disagree with
  what materialization computes — refused, because one fact has one
  definition. The recompute runs under the ROW's law (the tamper
  witness must judge by the same law the write materializes under).
  An aggregate fact the handler moved at all is tampering: its one
  writer is the maintainer, and a handler cannot compute it."
  [rmap before after now]
  (let [revision (:law-revision before)
        specs (specs-under rmap revision)
        facts (map first (expr-facts* specs))
        recomputed (compute-facts specs (:data after) now)]
    (-> (into []
              (keep (fn [fact]
                      (let [handler-wrote (get-in after [:data fact])
                            was (get-in before [:data fact])]
                        (when (and (not= handler-wrote was)
                                   (not= handler-wrote (get recomputed fact)))
                          fact))))
              facts)
        (into (keep (fn [fact]
                      (when (not= (get-in after [:data fact])
                                  (get-in before [:data fact]))
                        fact)))
              (keys (aggregate-specs rmap))))))

(defn vars-for
  "The refusal-surface garnish of a fact's spec, evaluated over the
  row (swallow errors — garnish never blocks)."
  [spec row now]
  (when-some [vars (:vars spec)]
    (into {}
          (keep (fn [[k form]]
                  (try [k (expr/evaluate form {:vars (:data row) :now now})]
                       (catch Exception _ nil))))
          vars)))
