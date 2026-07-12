(ns waymark10.machine
  "Graph queries over the declared state machine. The machine is not a
  separate object: it is a view over the normalized resource map
  (:states :initial :terminal :actions), where each action carries
  :name, :from (set), :to, and optionally :bulk. Guards are the
  walker's problem — the graph only promises edges exist.

  Ported from waymark9 core/machine.py; path-to feeds the conformance
  walker (any state reachable without a hand-written factory).")

(defn actions-seq
  "Actions in deterministic (name-sorted) order, each with :name
  assoc'd. Clojure map literals over 8 entries lose authoring order,
  so name order is the deterministic law: display order is declared
  explicitly (:display :order) and guard evaluation order lives in
  each action's guard vector."
  [rmap]
  (map (fn [[k a]] (assoc a :name k))
       (sort-by key (:actions rmap))))

(defn transitions-from [rmap state]
  (filter #(contains? (:from %) state) (actions-seq rmap)))

(defn transitions-not-from [rmap state]
  (remove #(contains? (:from %) state) (actions-seq rmap)))

(defn reachable-states
  "States reachable from :initial by any declared transition."
  [rmap]
  (loop [seen #{(:initial rmap)}
         queue [(:initial rmap)]]
    (if-let [here (first queue)]
      (let [next-states (->> (transitions-from rmap here)
                             (map :to)
                             (remove seen))]
        (recur (into seen next-states)
               (into (subvec (vec queue) 1) next-states)))
      seen)))

(defn path-to
  "Shortest action path initial → target (BFS over non-bulk,
  state-changing transitions), [] when target is initial, nil when
  unreachable."
  [rmap target]
  (let [initial (:initial rmap)]
    (if (= target initial)
      []
      (loop [prev {}
             queue [initial]]
        (if-let [here (first queue)]
          (let [steps (->> (transitions-from rmap here)
                           (remove #(or (:bulk %)
                                        (= (:to %) here)
                                        (contains? prev (:to %))
                                        (= (:to %) initial))))
                prev (reduce (fn [p d] (assoc p (:to d) [here d])) prev steps)]
            (if (contains? prev target)
              (loop [at target, path ()]
                (if (= at initial)
                  (vec path)
                  (let [[from step] (prev at)]
                    (recur from (cons step path)))))
              (recur prev (into (subvec (vec queue) 1) (map :to steps)))))
          nil)))))

(defn reverse-edges
  "For each source state of the action, the actions leading back to it
  from the action's :to (self-loops count as trivially reversible).
  Backs the reversible=true import check."
  [rmap action]
  (into {}
        (map (fn [src]
               [src (if (= (:to action) src)
                      [action]
                      (->> (actions-seq rmap)
                           (filter #(and (contains? (:from %) (:to action))
                                         (= (:to %) src)))
                           vec))]))
        (:from action)))

(defn dead-states
  "Non-initial, non-terminal states that are unreachable, plus
  reachable non-terminal states with no exit — the two accidental
  dead-state shapes the import gate refuses."
  [rmap]
  (let [reachable (reachable-states rmap)
        terminal (set (:terminal rmap))]
    {:unreachable (->> (:states rmap)
                       (remove #(= % (:initial rmap)))
                       (remove reachable)
                       vec)
     :no-exit (->> (:states rmap)
                   (filter reachable)
                   (remove terminal)
                   (remove #(seq (transitions-from rmap %)))
                   vec)}))
