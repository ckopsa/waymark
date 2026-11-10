(ns waymark10.server.held-calls.notice-rule-guards
  "The walls a `notice_rule` wears on create and restate
  (docs/spec-addressed-notice.md): its address names a member through
  ref fields, its `at` names a datetime, its `when.fact` names a
  derived fact. Split out of waymark10.server.held-calls so that
  namespace keeps room under its ns-size entry (ticket 829a08aa)."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(defn ref-named
  "The ref field of this resource named `field`, or nil."
  [rd field]
  (some #(when (= (str field) (name (:field %))) %)
        (schema/ref-fields (:schema rd))))

(defn address-path
  "The field names an address walks, in order: `field`, then `then`."
  [address]
  (into [] (keep #(some-> (get address %) str not-empty)) [:field :then]))

(g/defguard address-names-a-member
  {:reads [:services]
   :vars [:kind :field :why]
   :open "The kinds and their ref fields are each kind's published schema, one GET away; enumerating them into this form would duplicate it."
   :explain "A notice rule addresses the member a ref field on the kind it hears names, directly or through one more ref; {kind}.{field} {why}."}
  [row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (let [kind (str (or (:kind inp) (get-in row [:data :kind])))
          hops (address-path (or (:address inp) (get-in row [:data :address])))
          field (str/join "." hops)
          why (loop [k kind
                     [h & more] hops
                     first? true]
                (let [rd (when-not (str/blank? k) (rdef-of k))
                      ref (when rd (ref-named rd h))
                      at (fn [s] (if first? s (str "reaches " k ", whose " h " " s)))]
                  (cond
                    (nil? rd) (if first?
                                "names no kind this engine serves"
                                (str "reaches " k ", a kind this engine does not serve"))
                    (nil? ref) (at "is not a ref field of that kind")
                    (:listed ref) (at "is a list of refs, and a rule addresses one member")
                    (seq more) (recur (name (:kind ref)) more false)
                    (not= :member (keyword (:kind ref)))
                    (at (str "names a " (name (:kind ref)) ", not a member")))))]
      (if why
        (t/deny {:vars {:kind kind :field field :why why}})
        (t/allow)))
    (t/allow)))

(g/defguard at-names-a-datetime
  {:reads [:services]
   :vars [:kind :field :why]
   :open "The kinds and their fields are each kind's published schema, one GET away; enumerating them into this form would duplicate it."
   :explain "A notice rule's `at` names a datetime field of the kind it hears; {kind}.{field} {why}."}
  [row inp ctx]
  (let [kind (str (or (:kind inp) (get-in row [:data :kind])))
        field (some-> (or (:at inp) (get-in row [:data :at])) :field str not-empty)
        rd (when (and field (not (str/blank? kind)))
             (some-> (:rdef-of ctx) (#(% kind))))]
    ;; an unknown kind is address-names-a-member's sentence, not this one's
    (if (and rd field)
      (let [s (schema/field-schema (:schema rd) (keyword field))
            head (if (vector? s) (first s) s)]
        (cond
          (nil? s) (t/deny {:vars {:kind kind :field field
                                   :why "is not a field of that kind"}})
          (not= :waymark/instant head)
          (t/deny {:vars {:kind kind :field field
                          :why "is not a datetime, so it would never tell"}})
          :else (t/allow)))
      (t/allow))))

(g/defguard fact-names-a-derived-fact
  {:reads [:services]
   :vars [:kind :field :why]
   :open "The kinds and their derived facts are each kind's published schema, one GET away; enumerating them into this form would duplicate it."
   :explain "A notice rule's `when.fact` names a derived fact of the kind it hears, and hears it turning true; {kind}.{field} {why}."}
  [row inp ctx]
  (let [kind (str (or (:kind inp) (get-in row [:data :kind])))
        w (or (:when inp) (get-in row [:data :when]))
        field (some-> (:fact w) str not-empty)
        rd (when (and field (not (str/blank? kind)))
             (some-> (:rdef-of ctx) (#(% kind))))]
    ;; an unknown kind is address-names-a-member's sentence, not this one's
    (cond
      (nil? field) (t/allow)
      (false? (:becomes w))
      (t/deny {:vars {:kind kind :field field
                      :why "is heard turning true only, so becomes false would never tell"}})
      (and rd (not (contains? (:derived rd) (keyword field))))
      (t/deny {:vars {:kind kind :field field
                      :why "is not a derived fact of that kind"}})
      :else (t/allow))))
