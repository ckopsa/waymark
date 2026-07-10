(ns waymark10.test.walker
  "Random machine walks: from a synthesized create, repeatedly pick a
  random advertised action, synthesize its input, invoke — and after
  EVERY step assert the framework's promises: outcome honesty (state
  equals the action's :to), version discipline (+1 or a natural
  replay), the log (a matching transition record), and post-step
  honesty (what the new row advertises survives an identical
  re-probe — advertisement = enforcement).

  Deterministic given :seed: one java.util.Random threads every
  choice, and generation seeds derive from it."
  (:require [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.test.factories :as fac]))

(set! *warn-on-reflection* true)

(defn- latest-transition [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (first (store/transitions (:storage eng) tx
                                {:kind kind :resource-id id}
                                {:newest-first true :limit 1})))))

(defn- violation! [invariant data]
  (throw (ex-info (str "walker invariant violated: " invariant)
                  (assoc data :invariant invariant))))

(defn- assert-step!
  "The per-step invariants; returns the new row or throws with the
  violated invariant."
  [eng rdef old-row action res]
  (let [kind (:kind rdef)
        row (:row res)
        replay? (some? (:replayed? res))]
    (when-not (= (:to action) (:state row))
      (violation! "the resulting state equals the action's :to"
                  {:action (:name action)
                   :expected (:to action) :actual (:state row)}))
    (when-not (or replay? (= (inc (:version old-row)) (:version row)))
      (violation! "version increased by exactly 1"
                  {:action (:name action)
                   :before (:version old-row) :after (:version row)}))
    (let [rec (latest-transition eng kind (:id row))]
      (when-not (and rec
                     (= (:action rec) (:name action))
                     (= (:to-state rec) (:to action))
                     (or replay? (= (:from-state rec) (:state old-row))))
        (violation! "a transition record exists with matching action/from/to"
                    {:action (:name action) :from (:state old-row)
                     :to (:to action) :record rec})))
    ;; post-step honesty: every action the new row advertises must
    ;; allow on an identical re-probe — a listed action whose guards
    ;; hard-deny is a lie in the advertisement
    (let [ctx (fac/probe-ctx eng)
          advertised (vec (fac/available-actions rdef row ctx))]
      (doseq [a advertised]
        (when-some [denial (fac/probe-denial a row ctx)]
          (violation! "an advertised action hard-denies on identical probe"
                      {:action (:name a) :denial denial}))))
    row))

(defn- shuffled [^java.util.Random rnd xs]
  (let [a (java.util.ArrayList. ^java.util.Collection (vec xs))]
    (java.util.Collections/shuffle a rnd)
    (vec a)))

(defn random-walk
  "Walk `steps` random advertised actions from a synthesized create,
  asserting the invariants after every step. Bulk actions are
  skipped; non-idempotent actions get fresh Idempotency-Keys and
  warnings are acknowledged on retry (fac/walker-invoke!). Stops
  early at a terminal (or unsynthesizable) frontier. Returns
  {:steps [{:action … :from … :to … :replayed? …} …] :row final}."
  [eng kind {:keys [seed steps] :or {seed 0 steps 10}}]
  (let [rdef (or (get (inv/resources eng) kind)
                 (throw (ex-info (str "no enrolled kind " kind) {:kind kind})))
        rnd (java.util.Random. (long seed))
        created (fac/create-example eng kind {:seed seed})]
    (loop [row (:row created)
           taken []
           n 0]
      (if (= n steps)
        {:steps taken :row row}
        (let [ctx (fac/probe-ctx eng)
              candidates (shuffled rnd (fac/available-actions rdef row ctx))
              chosen (some (fn [a]
                             (let [b (fac/synthesize-input
                                      eng rdef a row ctx
                                      {:seed (.nextInt rnd 1000000)})]
                               (if (and (nil? b) (:input a) (fac/skip-reason))
                                 nil
                                 [a b])))
                           candidates)]
          (if (nil? chosen)
            {:steps taken :row row}
            (let [[action body] chosen
                  res (fac/walker-invoke! eng kind row action body)
                  row' (assert-step! eng rdef row action res)]
              (recur row'
                     (conj taken {:action (:name action)
                                  :from (:state row)
                                  :to (:state row')
                                  :replayed? (:replayed? res)})
                     (inc n)))))))))
