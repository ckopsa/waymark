(ns waymark10.lines
  "Ledger step 6a (docs/spec-ledger-money.md §6): a kind that declares
  `:lines` gets a line-item child kind the engine makes. The parent's
  own normalization (resource/project-lines) gives it the :owns edge
  and the `lines` input on its create and edit doors; this namespace
  makes the child itself, and the ledger module enrols one per
  declaring kind (modules, :line_items).

  A line kind has no doors. Its rows are written by the engine's quiet
  birth door in the parent door's transaction (server.invoke's
  write-lines!), so a line is addressable, readable and filterable by
  its parent and its refs, and no hand at the wire writes one."
  (:require [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(g/defguard the-parent-door-writes-it
  {:reads [:principal]
   :hide true
   :explain "A line is written by its parent's create and edit doors; no hand at the wire writes one."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(defn line-kind
  "The child kind one parent's normalized :lines declares: the line's
  :fields, a ref back at the parent under :parent, and `n`, its place
  in the list the door was given."
  [parent]
  (let [{child :kind via :parent fields :fields} (:lines parent)
        pkind (:kind parent)
        refs (schema/ref-fields (into [:map] fields))]
    (r/resource
     (cond-> {:kind child
              :states [:held]
              :initial :held
              :terminal #{:held}
              :nav :secondary
              :summary (str "{data." (name via) "} · line {data.n}")
              :schema (into [:map
                             [via {:kind pkind
                                   :x-display {:raw true :label "Belongs to"
                                               :help "The row whose doors wrote this line."}}
                              :waymark/ref]
                             [:n {:x-display {:label "Line"
                                              :help "Its place in the list, from 0. The default sort."}}
                              [:int {:min 0}]]]
                            fields)
              :create-guards [the-parent-door-writes-it]
              :filterable (into {via #{:eq}}
                                (map (fn [{:keys [field]}] [field #{:eq}]))
                                refs)
              :sortable {:fields [:n] :default "n"}
              :deviations
              [(str "Ledger step 6a: each line is inserted by the engine's quiet birth door with no transition of its own; the "
                    (name pkind) " door that carried it is the write, and its inputs carry the lines.")]}
       (:domain parent) (assoc :domain (:domain parent))))))

(defn kinds
  "Every line kind the resources' :lines declare — the ledger module's
  when-declared enrollment."
  [resources]
  (into [] (comp (filter :lines) (map line-kind)) resources))
