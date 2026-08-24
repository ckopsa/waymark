(ns waymark10.demand
  "Demand classes (waymark9 core/demand.py, design §10): every input
  field is a demand the server makes of the human, and the class is
  derived from the declaration and rendered on the wire (\"effort\")
  so clients, agents, and checks share one vocabulary:

  - assent      — empty or const-only input: one click.
  - selection   — every field enumerable (enum / const / boolean /
                  acceptance set / ref picker): choosing, not typing.
  - recall      — open but format-constrained typing.
  - composition — unbounded prose; drafts are the floor here.

  (traversal — links and summaries — is below the action layer.)

  ENVELOPE-ONLY: the class is a projection of the declaration, never
  a fact of it — it rides every rendered action entry and is NOT
  fingerprinted (two laws that differ only in effort are the same
  law, because effort is derived, not declared).

  Ported deviations, recorded:
  - waymark9 excluded fields a When conditionally requires (the entry
    reflects the base branch). v10 declares no conditional-input
    grammar yet, so there is nothing to exclude — the port drops
    that branch with this note as its tombstone.
  - waymark9 resolved pydantic's $ref/allOf wrappers; v10's published
    schemas arrive inlined (schema/json-schema), so resolution is
    :oneOf/:anyOf variant walking only."
  (:require [waymark10.guards :as g]))

(set! *warn-on-reflection* true)

(def classes ["assent" "selection" "recall" "composition"])

(def ^:private order {"assent" 0 "selection" 1 "recall" 2 "composition" 3})

(defn- widget [prop] (get-in prop [:x-display :widget]))

(defn- variants
  "anyOf/oneOf branches minus null, else the property itself."
  [prop]
  (let [branches (or (:anyOf prop) (:oneOf prop))]
    (if-not (seq branches)
      [prop]
      (let [out (into [] (remove #(= "null" (:type %))) branches)]
        (if (seq out) out [prop])))))

(defn- accepted-fields
  "Input fields some guard's declared acceptance set closes: a
  single-field :accepts closes its judged field; a relation closes
  ALL its judged fields (design §5)."
  [defn']
  (into #{}
        (mapcat (fn [leaf]
                  (when (:accepts leaf)
                    (if (:relation leaf)
                      (:judges leaf)
                      [(first (:judges leaf))]))))
        (mapcat g/iter-leaves (:guards defn'))))

(defn heavier?
  "Is class a a heavier demand than class b? The order is the
  vocabulary's own: assent < selection < recall < composition. Public
  because the usability battery (waymark10.usability) asks the
  comparison out loud — a gesture may not collect more than a
  selection — and a second spelling of this order would be a second
  opinion about what effort means."
  [a b]
  (> (long (order a)) (long (order b))))

(defn field-class
  "The demand class of ONE input field: fname its key, prop its
  RENDERED JSON-Schema property (keyword keys), accepted the set of
  fields some guard's declared acceptance set closes. Public for the
  same reason `heavier?` is — the usability battery reads effort
  per FIELD, not per action, because the fix is per field."
  [fname prop accepted]
  (cond
    (= "prose" (widget prop)) "composition"
    (or (contains? prop :enum) (contains? prop :const)) "selection"
    (or (contains? accepted fname)
        (= "resource" (widget prop))
        (:x-ref prop)
        (= "waymark-ref" (:format prop))) "selection"
    :else
    (or (some (fn [v]
                (let [t (:type v)]
                  (cond
                    (or (= "boolean" t)
                        (contains? v :enum) (contains? v :const)) nil
                    (= "string" t) (if (= "prose" (widget prop))
                                     "composition" "recall")
                    (#{"number" "integer" "array" "object"} t) "recall"
                    :else nil)))
              (variants prop))
        "selection")))

(defn effort
  "The demand class of one action, from its RENDERED input schema
  (acceptance sets already folded as enums — keyword keys, before the
  wire boundary) and its guards' declared acceptance sets. key-field
  is the place-scope key when the action is placed: pre-bound by the
  part binding (or const already), it demands nothing."
  [defn' input-schema & [key-field]]
  (let [props (:properties input-schema)]
    (if (or (nil? (:input defn')) (empty? props))
      "assent"
      (let [accepted (accepted-fields defn')]
        (reduce
         (fn [worst [fname prop]]
           (if (or (contains? prop :const) (= fname key-field))
             worst                        ; pre-bound: no demand
             (let [cls (field-class fname prop accepted)]
               (if (> (order cls) (order worst)) cls worst))))
         "assent"
         props)))))
