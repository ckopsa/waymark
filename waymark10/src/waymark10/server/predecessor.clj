(ns waymark10.server.predecessor
  "Predecessor refs (design E7, ported from waymark9 invoke.py
  _resolve_predecessors + core/refs.py Predecessor): period chaining
  as data, not date arithmetic.

  A :waymark/ref schema entry declaring
  {:kind :plan :predecessor {:order :start_date :partition :ledger}}
  resolves at CREATE when the body left it blank: the newest existing
  row of the target kind by :order — ties break toward the smallest
  id (search-rows' id tiebreak, so resolution is deterministic) —
  becomes the field's value; no sibling → the field stays nil. The
  resolving query runs before :on-create (waymark9's step order), so
  the hook may read the resolved sibling for carry-forward; a value
  the body supplied always wins over resolution.

  The waymark9 ≤-seeding survives: when the new row already carries
  its own :order value, only siblings at or before it qualify — a
  backdated period links backward, never forward. A blank :order
  value (an :on-create default not yet applied) simply takes the
  newest sibling overall.

  Recorded boundaries:
  - :order must be a promoted field on the TARGET kind (declared
    filterable or sortable — the ordering runs over its generated
    column), refused loudly at create; waymark9 checked this at
    assembly (_check_predecessor) — the v10 assembly check is a named
    punt, the create-time refusal holds the same line.
  - a declared :partition whose value is blank on the new row
    resolves nothing — half a partition key must not link across
    partitions.

  invoke.clj's create! runs resolve! at its one batch-E seam (after
  decode, before :on-create); this namespace owns all the machinery."
  (:require [waymark10.schema :as schema]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- schema-head
  "The leaf type of an entry's schema form, :maybe unwrapped."
  [s]
  (let [s (if (and (vector? s) (= :maybe (first s))) (second s) s)]
    (if (vector? s) (first s) s)))

(defn specs
  "field → {:kind … :order … :partition …} for every :waymark/ref
  entry of one :map form that declares :predecessor — the refs the
  engine resolves at create."
  [form]
  (into {}
        (keep (fn [[f {:keys [properties schema]}]]
                (when-some [pred (:predecessor properties)]
                  (when (and (= :waymark/ref (schema-head schema))
                             (:kind properties))
                    [f {:kind (:kind properties)
                        :order (:order pred)
                        :partition (:partition pred)}]))))
        (schema/entry-map form)))

(defn- data-cond [target-schema field op value]
  {:target :data
   :field field
   :cast (or (store/generated-column-type
              (schema/field-schema target-schema field))
             "text")
   :op op
   :value (str value)})

(defn- resolve-one
  [storage tx kinds kind row f {target-kind :kind :keys [order partition]}]
  (let [target (or (get kinds target-kind)
                   (throw (t/definition-error
                           (str (name kind) "." (name f) ": predecessor "
                                "target kind " (name target-kind)
                                " is not enrolled"))))
        promoted (into (set (keys (:filterable target)))
                       (get-in target [:sortable :fields]))]
    (when-not (contains? promoted order)
      (throw (t/definition-error
              (str (name kind) "." (name f) ": predecessor order "
                   (name order) " must be filterable or sortable on "
                   (name target-kind)
                   " — the resolving query orders by its promoted column"))))
    (let [own-order (get-in row [:data order])
          pvalue (when partition (get-in row [:data partition]))]
      (if (and partition (nil? pvalue))
        ;; half a partition key must not link across partitions
        row
        (let [conds (cond-> []
                      (some? own-order)
                      (conj (data-cond (:schema target) order :<= own-order))
                      partition
                      (conj (data-cond (:schema target) partition := pvalue)))
              [sibling] (store/search-rows storage tx target-kind conds
                                           {:order-by order :desc true
                                            :limit 1})]
          (if sibling
            (assoc-in row [:data f] (:id sibling))
            row))))))

(defn resolve!
  "Fill every blank predecessor ref of one about-to-be-created row
  from its newest existing sibling (see the ns docstring for the full
  semantics). kinds is the engine's kind → rdef map; runs inside the
  create's own transaction. Returns the row."
  [storage tx kinds rdef row]
  (reduce-kv
   (fn [row f spec]
     (if (some? (get-in row [:data f]))
       row ; a supplied value wins over resolution
       (resolve-one storage tx kinds (:kind rdef) row f spec)))
   row
   (specs (:schema rdef))))
