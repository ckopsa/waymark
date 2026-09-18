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

;; ── how a row's work ENDS, and which doors survive it ───────────────
;;
;; The machine knows a state is TERMINAL and nothing more. It cannot
;; know that a skipped chore run is over while a due one is not (both
;; are non-terminal, because un-skip is a real verb), nor that a
;; DISCARDED grocery list is not an accomplishment (both endings are
;; terminal), nor that a mirrored task whose `:status` says `done` is
;; finished while its sync state says `fresh`. Those are the
;; household's own words, so the DECLARATION says them (`:over`).
;;
;; This is where that declaration is read. The feed asks the same two
;; questions of it (`waymark10.server.feed`, which delegates here), and
;; since waymark-fp62.4.1 the ENVELOPE asks a third: which doors does
;; an ended row still offer? A door a finished task advertises and
;; cannot honestly take is a false promise, and a false promise makes
;; every other promise worth checking.

(defn over-vocabulary
  "The kind's declared `:over`, with the machine's own defaults filled
  in — `{:field :accomplished :let-go}`. One vocabulary, read in one
  place: a second opinion about what 'finished' means would let one
  reader card what another calls history."
  [rdef]
  (let [o (:over rdef)]
    {:field (:field o)
     :accomplished (set (if o (:accomplished o) (:terminal rdef)))
     :let-go (set (:let-go o))}))

(defn ending-word
  "The word this row's ending would be spelled with: its machine state,
  or — when the kind declared a `:field` — the value that field holds.
  A mirror's lifecycle is data, so a mirrored row's ending is read off
  the document the authority sent."
  [rdef row]
  (let [{:keys [field]} (over-vocabulary rdef)]
    (if field
      (some-> (get-in row [:data field]) str not-empty)
      (keyword (:state row)))))

(defn work-over?
  "Is this row's work OVER — terminal by the machine, or resting in an
  ending the kind declared? A row that is over is never a next action
  and never anything but history."
  [rdef row]
  (let [{:keys [accomplished let-go]} (over-vocabulary rdef)
        w (ending-word rdef row)]
    (boolean (or (contains? (:terminal rdef #{}) (keyword (:state row)))
                 (and w (or (contains? accomplished w)
                            (contains? let-go w)))))))

(defn accomplished?
  "Did the household FINISH this row, rather than let it go? The
  narrower question: every accomplishment is over, and a discarded
  list, an abandoned book and a skipped chore are over without being
  deeds."
  [rdef row]
  (boolean (contains? (:accomplished (over-vocabulary rdef))
                      (ending-word rdef row))))

(defn ways-back
  "The action names that stay open on a row whose work is over — the
  ways BACK, and nothing else (waymark-fp62.4.1).

  A row that is over takes no more doors, because a door offered on a
  finished row is a promise the household never meant. The exception
  is the door that says the ending was wrong: reopen, unskip, restore,
  start again. Where an ending is a STATE the machine shows those
  doors itself — any transition that departs an ending and lands
  outside every ending is a way back — so a kind that spells its
  endings in states declares nothing more. Where an ending is a FIELD
  VALUE (a mirror kind, whose machine is the sync machine) the machine
  can see nothing, so the kind names its ways back in `:over
  :ways-back`, and `check-over!` refuses a name that is not a door.

  A kind that declares no `:over` keeps exactly the old meaning: its
  terminal states are its endings, the machine already refuses to
  leave them, and this rule has nothing to add."
  [rdef]
  (let [{:keys [field accomplished let-go]} (over-vocabulary rdef)
        endings (into accomplished let-go)
        ;; the ENGINE's own doors never close. A mirror must go on
        ;; recording what its authority says about a task the house
        ;; finished last month, and a conflicted row must stay
        ;; resolvable — sync bookkeeping is not the household's work,
        ;; so the household's ending has no opinion about it
        declared (into (into #{} (map keyword) (:ways-back (:over rdef)))
                       (comp (filter :engine) (map :name))
                       (actions-seq rdef))]
    (if field
      declared
      (into declared
            (comp (filter (fn [a] (some endings (:from a))))
                  (remove (fn [a] (contains? endings (:to a))))
                  (map :name))
            (actions-seq rdef)))))

(defn door-shut-when-over?
  "Is this door shut BECAUSE the row's work is over? True only for a
  kind that declares `:over`: the machine owns terminal states, and a
  kind that never named its endings has none to close a door on."
  [rdef row action-name]
  (boolean (and (:over rdef)
                (some? row)
                (work-over? rdef row)
                (not (contains? (ways-back rdef) action-name)))))
