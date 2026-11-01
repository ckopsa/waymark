(ns waymark10.server.replay-walks
  "Walk and holder replay for an amendment (docs/spec-amendment.md §14
  step 4; the first two bullets of R-A.3.3), over the timelines
  `replay/history` reconstructs. Pure: nothing here reads the store,
  writes, moves or wakes.

  Walk membership: at each transition in the window on the walked
  kind, which rows `live-cracks/walk-admits?` hands the seat under its
  row as it stands (`before`) and as the change would leave it
  (`after`). The report names the rows each side adds and drops, with
  their state at the time. A nil `after` is a seat that leaves: its
  walk hands it nothing.

  Holder choice: for the same rows, the holder `waiting-on/block` names
  (its `choose`) under each side, the changed seat beside the other
  active seats. The report names the rows whose holder moves and calls
  out every row that would move from a seat a model sits in to a
  person (`to_person`) or to nobody (`to_nobody`)."
  (:require [waymark10.server.live-cracks :as live-cracks]
            [waymark10.server.waiting-on :as waiting-on]))

(set! *warn-on-reflection* true)

(defn- text [v] (if (keyword? v) (name v) (str v)))

(defn- rows-of
  "The reconstructed rows of one kind, by id."
  [history kind]
  (sort-by (comp second key)
           (filter (fn [[[k _] _]] (= kind k)) (:rows history))))

(defn- row-at
  "The row as one moment of its timeline leaves it."
  [id m]
  {:id id :state (:to m) :data (or (:data m) {}) :updated-at (:at m)})

(defn- admits? [by-kind reads seat rdef row]
  (boolean (and seat (live-cracks/walk-admits? by-kind reads seat rdef row))))

(defn- holder
  "The holder the row's waiting_on block names among `seat-rows`, nil
  for a state a row rests in."
  [rdef seat-rows by-kind reads scheduled row]
  (:holder (waiting-on/block rdef seat-rows by-kind row
                             (assoc reads
                                    :now (:updated-at row)
                                    :entered {:at (:updated-at row)}
                                    :data (:data row)
                                    :scheduled scheduled))))

(defn- model-seat? [h] (= "seat" (:type h)))

(defn- per-row
  "One entry for each row among `seen`: its first such moment, its state
  then, and how many moments it was so."
  [seen & ks]
  (into []
        (map (fn [ms]
               (merge {:id (:id (first ms)) :at (:at (first ms))
                       :state (:state (first ms)) :moments (count ms)}
                      (select-keys (first ms) ks))))
        (partition-by :id seen)))

(defn- moved-to [seen type]
  (into [] (comp (filter #(and (model-seat? (:before %))
                                (= type (:type (:after %)))))
                 (map :id)
                 (distinct))
        seen))

(defn replay
  "What a change to one seat would do to the walk of `rdef`'s rows over
  `history` (`replay/history`'s answer):
  {:kind :moments :adds :drops :holders :to_person :to_nobody :blind}.
  `adds` and `drops` are the rows the `after` side's walk hands the
  seat and the `before` side's does not, and the reverse, each
  {:id :at :state :moments} at its first such moment. `holders` is the
  rows whose holder moves, with `:before` and `:after` as the
  waiting_on block spells them. `seats` is the OTHER active seats;
  `reads` is `walk-admits?`'s (:tickets :judgments :breaches) and
  `scheduled` the block's. `blind` is the history's."
  [{:keys [rdef by-kind history seats before after reads scheduled]}]
  (let [kind (text (:kind rdef))
        side (fn [seat] (cond-> (vec seats) seat (conj seat)))
        before-seats (side before)
        after-seats (side after)
        seen (vec
              (for [[[_ id] r] (rows-of history kind)
                    m (:timeline r)
                    :let [row (row-at id m)]]
                {:id id :at (:at m) :state (:to m)
                 :was (admits? by-kind reads before rdef row)
                 :now (admits? by-kind reads after rdef row)
                 :before (holder rdef before-seats by-kind reads scheduled row)
                 :after (holder rdef after-seats by-kind reads scheduled row)}))
        moved (filterv #(not= (:before %) (:after %)) seen)]
    {:kind kind
     :moments (count seen)
     :adds (per-row (filterv #(and (:now %) (not (:was %))) seen))
     :drops (per-row (filterv #(and (:was %) (not (:now %))) seen))
     :holders (per-row moved :before :after)
     :to_person (moved-to moved "person")
     :to_nobody (moved-to moved "nobody")
     :blind (vec (:blind history))}))
