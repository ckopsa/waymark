(ns waymark10.pick-test
  "The declared picker query (waymark9 pick=Query, design §24): a ref
  entry's :pick rides the published x-ref annotation as the literal
  collection query the generic client's picker fetches with —
  presentation, never fingerprinted, enforcement stays with guards.
  The assembly refuses a pick the target collection would 400:
  unknown/unfilterable keys, undeclared states. Registry-pure."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]))

;; ── the world ───────────────────────────────────────────────────────

(defn- target-map []
  {:kind :pk_target
   :plural "pk_targets"
   :states [:suggested :active :retired]
   :initial :suggested
   :terminal #{:retired}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name {:filter #{:eq}} [:string {:min 1 :max 40}]]
            [:tier {:optional true} [:maybe [:string {:max 10}]]]]
   :actions {:accept {:from #{:suggested} :to :active
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Accepted is acknowledged."}}
             :retire {:from #{:active} :to :retired
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Retired is history."}}}})

(defn- pointer-map [pick]
  {:kind :pk_pointer
   :plural "pk_pointers"
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 40}]]
            [:target_id (cond-> {:kind :pk_target}
                          pick (assoc :pick pick))
             :waymark/ref]]
   :actions {:close {:from #{:open} :to :done
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Closed is history."}}}})

(defn- assemble [pick]
  (ca/run-all {:kinds {:pk_target (r/resource (target-map))
                       :pk_pointer (r/resource (pointer-map pick))}}))

;; ── 1. the projection: pick rides x-ref, stringified ────────────────

(deftest pick-rides-the-published-x-ref
  (let [js (schema/json-schema
            [:map [:target_id {:kind :pk_target :label :target_name
                               :pick {:state :active}}
                   :waymark/ref]])]
    (is (= {:kind :pk_target :label :target_name :pick {:state "active"}}
           (get-in js [:properties :target_id :x-ref]))
        "keyword values land as their names — the picker's literal params"))
  (testing "a membership pick keeps its list shape (a,b on the wire)"
    (let [js (schema/json-schema
              [:map [:target_id {:kind :pk_target
                                 :pick {:state [:suggested :active]}}
                     :waymark/ref]])]
      (is (= ["suggested" "active"]
             (get-in js [:properties :target_id :x-ref :pick :state]))))))

(deftest a-list-of-refs-publishes-its-picker-on-the-items
  ;; waymark-fp62.7.8. :kind rides the ENTRY, so a [:vector
  ;; :waymark/ref] field advertised its picker at the ARRAY level and
  ;; the generic form built one select from it: the seed was a list of
  ;; ids, no option ever equalled it, and the seat's held_for opened as
  ;; an empty model chooser that had dropped its own prefill. The
  ;; advertisement is carried onto the ITEMS, where the value one
  ;; widget holds lives — and kept on the array, where the collection's
  ;; filter param (which filters BY this field) reads it.
  (let [js (schema/json-schema
            [:map [:held_for {:kind :model} [:vector :waymark/ref]]])
        prop (get-in js [:properties :held_for])]
    (is (= "array" (:type prop)))
    (is (= {:kind :model} (:x-ref prop))
        "the array keeps the reading the filter param takes from it")
    (is (= {:kind :model} (get-in prop [:items :x-ref]))
        "…and one row of the list is one picker")
    (is (= "waymark-ref" (get-in prop [:items :format]))
        "the item is still the ref it always was"))
  (testing "a nilable list carries it into the array arm of the oneOf"
    (let [js (schema/json-schema
              [:map [:participants {:kind :person :pick {:state :active}}
                     [:maybe [:vector :waymark/ref]]]])
          prop (get-in js [:properties :participants])
          arm (first (filter :items (or (:oneOf prop) (:anyOf prop))))]
      (is (some? arm) "a [:maybe [:vector …]] reaches the wire beside a null")
      (is (= {:kind :person :pick {:state "active"}} (get-in arm [:items :x-ref]))
          "the whole declaration rides down, pick and all")))
  (testing "a single ref is untouched — it has no items to carry to"
    (let [js (schema/json-schema
              [:map [:target_id {:kind :pk_target} :waymark/ref]])]
      (is (= {:kind :pk_target} (get-in js [:properties :target_id :x-ref])))
      (is (nil? (get-in js [:properties :target_id :items]))))))

;; ── 2. presentation, never law ──────────────────────────────────────

(deftest pick-is-not-fingerprinted
  (is (= (fp/fingerprint-hash (fp/fingerprint-of (r/resource (pointer-map nil))))
         (fp/fingerprint-hash (fp/fingerprint-of
                               (r/resource (pointer-map {:state :active})))))
      "two declarations differing only in :pick are the same law"))

;; ── 3. the assembly refuses a pick the collection would 400 ─────────

(deftest assembly-holds-pick-to-the-target
  (testing "a declared state filters fine; so does an :eq field"
    (is (map? (assemble {:state :active})))
    (is (map? (assemble {:state [:suggested :active]})))
    (is (map? (assemble {:name "x"}))))
  (testing "an undeclared state refuses"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not a state of"
         (assemble {:state :haunted}))))
  (testing "an unfilterable field refuses"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not an :eq/:in-filterable field"
         (assemble {:tier "gold"}))))
  (testing "a non-map pick refuses"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"query map"
         (assemble [:state :active])))))
