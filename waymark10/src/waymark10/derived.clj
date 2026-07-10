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

  Phase 6 adds the aggregate spec: {:count {:related <edge> | :owns
  <child-kind>, :where {field #{values}}}} — the count of
  edge-matching rows, computed by the maintainer's SQL, never here.
  Cross-kind facts read ONLY through these declared edges; the
  expression language stays own-scope. A count fact composes like any
  data field: a derived-over-derived expression names it in :over and
  reads its materialized value. :sum is NOT included — unearned."
  (:require [waymark10.expr :as expr]))

(defn count-specs
  "The kind's aggregate count facts ({:count …} specs) — the
  maintainer's to compute (SQL over the edge's target); materialize
  never touches them, it only reads their stored values as inputs."
  [rmap]
  (into {} (filter (comp :count val)) (:derived rmap)))

(defn expr-facts
  "The kind's expression-derived facts, dependency-ordered: a fact
  whose :over names another fact computes after it."
  [rmap]
  (let [derived (:derived rmap)
        expr-only (into {} (filter (comp :expr val)) derived)
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

(defn materialize
  "Recompute the row's own expression facts into :data. Total: a
  missing input flows to a nil fact, never a throw."
  [rmap row now]
  (update row :data
          (fn [data]
            (reduce (fn [d [fact spec]]
                      (let [vars (into {}
                                       (map (fn [k]
                                              [k (if (= k :now) now (get d k))]))
                                       (:over spec))]
                        (assoc d fact (expr/evaluate (:expr spec)
                                                     {:vars vars :now now}))))
                    data
                    (expr-facts rmap)))))

(defn tampered
  "Derived fields the handler wrote values into that disagree with
  what materialization computes — refused, because one fact has one
  definition. A count fact the handler moved at all is tampering: its
  one writer is the maintainer, and a handler cannot compute it."
  [rmap before after now]
  (let [facts (map first (expr-facts rmap))
        recomputed (:data (materialize rmap {:data (:data after)} now))]
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
              (keys (count-specs rmap))))))

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
