(ns waymark10.derived
  "Derivations: one fact, one definition. The pure half — computing a
  row's own expression facts from its data. The maintainer (cross-row
  recompute, clock flips, backfill) is server machinery and arrives
  in phase 6; this namespace never reads anything but the arguments.

  A spec {:over [kw …] :expr form} evaluates with (var k) bound to the
  row's field k; :now in :over binds the injected clock. Facts may
  depend on other facts of the same kind; materialization runs in
  dependency order (the cycle check refused cycles at load)."
  (:require [waymark10.expr :as expr]))

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
  definition."
  [rmap before after now]
  (let [facts (map first (expr-facts rmap))
        recomputed (:data (materialize rmap {:data (:data after)} now))]
    (into []
          (keep (fn [fact]
                  (let [handler-wrote (get-in after [:data fact])
                        was (get-in before [:data fact])]
                    (when (and (not= handler-wrote was)
                               (not= handler-wrote (get recomputed fact)))
                      fact))))
          facts)))

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
