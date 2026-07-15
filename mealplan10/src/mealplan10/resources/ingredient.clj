(ns mealplan10.resources.ingredient
  "The Ingredient resource: the canonical pantry concept (\"chicken
  thighs\"), store-agnostic — how stores sell it is the product's
  story, what stands in for it is the substitution's.

  The AI (reading receipts and recipes) suggests ingredients; a human
  verdict accepts or declines each, which is what keeps the list
  canonical. Aliases fold in every name it goes by on receipts and
  recipes — a confirmed match teaches the spelling. preferred_stores
  is the family's buying preference, best first; its membership
  filter (?preferred_stores=costco) is the whole-Costco-trip entry
  point, and the pricing law reads its ORDER when several stores
  offer the same ingredient.

  absorb is the dedupe verdict — same concept, different spelling
  (the substitution is the other cut: different concept, acceptable
  stand-in). The survivor takes the duplicate's name and aliases,
  repoints its live products through product.rematch (the handler's
  ctx :invoke door, advertised as :touches), and retires it —
  acknowledging the tracked-products warning, honestly: the stored
  rollup is stale at handler time because its products were just
  repointed in this very transaction."
  (:require [waymark10.dsl :refer [defaction defderived defguardfn
                                   defresource defhandler expr-guard]]
            [waymark10.types :as t]))

(def alias-schema
  [:vector {:max 30} [:waymark/vocab {:open true}]])

(def store-schema
  [:vector {:max 10} [:waymark/vocab {:open true}]])

(def overwrite
  {:idempotent true :reversible false :confirm false})

(defderived products-tracked
  {:count {:owns :product :where {:state #{"tracked"}}}})

;; retire warns (acknowledgeable), never walls: the family may retire
;; an ingredient whose products are history they no longer track
(def no-tracked-products
  (expr-guard {:name :no-tracked-products
               :severity :warning
               :when '(= 0 (data :products_tracked))
               :explain "{n} tracked product(s) still point here — absorb the duplicate or rematch them first."
               :vars {:n '(data :products_tracked)}}))

(defguardfn duplicate-is-absorbable
  {:judges [:duplicate_id]
   :reads [:ingredient]
   :explain "Only another ACTIVE ingredient can be absorbed — and never this one itself."
   :remedies [:ingredient/accept]}
  [row inp ctx]
  (if-some [read (:read ctx)]
    (let [dup-id (:duplicate_id inp)]
      (cond
        (= (str dup-id) (str (:id row))) (t/deny)
        (not= :active (:state (read :ingredient dup-id))) (t/deny)
        :else (t/allow)))
    ;; pure render probe carries no hooks — optimistic, the invoke
    ;; loop re-judges with them
    (t/allow)))

(defhandler apply-aliases [row inp _ctx]
  (assoc-in row [:data :aliases] (vec (distinct (:aliases inp)))))

(defhandler apply-details [row inp _ctx]
  (cond-> row
    (some? (:category inp))
    (assoc-in [:data :category] (:category inp))
    (some? (:unit inp))
    (assoc-in [:data :unit] (:unit inp))
    (some? (:preferred_stores inp))
    (assoc-in [:data :preferred_stores] (vec (distinct (:preferred_stores inp))))))

(defhandler absorb-duplicate [row inp ctx]
  (let [invoke! (:invoke ctx)
        dup-id (:duplicate_id inp)
        dup ((:read ctx) :ingredient dup-id)
        known (into #{(get-in row [:data :name])}
                    (get-in row [:data :aliases]))
        incoming (cons (get-in dup [:data :name])
                       (get-in dup [:data :aliases]))]
    ;; repoint the duplicate's live products; rematch re-parents and
    ;; confirms in one verdict, so both rollups tell the truth in this
    ;; same call. Rematched products leave the query, so the re-query
    ;; loop terminates; discontinued ones keep their historical match.
    (loop []
      (let [page ((:find ctx) :product {:ingredient_id dup-id} {:limit 200})
            due (filterv #(contains? #{:suggested :tracked} (:state %)) page)]
        (when (seq due)
          (doseq [p due]
            (invoke! :product (:id p) :rematch {:ingredient_id (:id row)}))
          (when (= 200 (count page))
            (recur)))))
    (invoke! :ingredient dup-id :retire nil
             {:acknowledged #{:no-tracked-products}})
    (update-in row [:data :aliases]
               (fn [aliases]
                 (vec (take 30 (into (vec aliases)
                                     (comp (distinct) (remove known))
                                     incoming)))))))

(defaction update-aliases
  {:from #{:active} :to :active
   :input [:map [:aliases alias-schema]]
   :edit {:prefill [:aliases]}
   :safety overwrite
   :handler apply-aliases
   :display {:label "Update aliases" :order 3
             :description "Every name this ingredient goes by on receipts and recipes"}})

(defaction update-details
  {:from #{:active} :to :active
   :input [:map
           [:category {:optional true} [:maybe [:string {:max 50}]]]
           [:unit {:optional true} [:maybe [:enum "g" "ml" "each"]]]
           [:preferred_stores {:optional true} [:maybe store-schema]]]
   :edit {:prefill [:category :unit :preferred_stores]}
   :safety overwrite
   :handler apply-details
   :display {:label "Update details" :order 4}})

(defaction absorb
  {:from #{:active} :to :active
   :input [:map [:duplicate_id {:kind :ingredient :pick {:state "active"}}
                 :waymark/ref]]
   :guards [duplicate-is-absorbable]
   :touches [{:kind :product :action :rematch :may true}
             {:kind :ingredient :action :retire}]
   :safety {:idempotent true :reversible false :confirm true
            :consequence "The duplicate's name and aliases fold into this ingredient, its products repoint here, and it retires."}
   :handler absorb-duplicate
   :display {:label "Absorb duplicate" :order 5}})

(defresource ingredient
  {:kind :ingredient
   :states [:suggested :active :retired]
   :initial :suggested
   :terminal #{:retired}
   :summary "{data.name} · {state}"
   :nav :secondary
   :schema [:map
            [:name {:sort :default} [:string {:min 1 :max 200}]]
            [:aliases {:optional true :default []} [:maybe alias-schema]]
            [:category {:optional true :filter #{:eq :in}}
             [:maybe [:string {:max 50}]]]
            [:unit {:optional true :default "g"}
             [:maybe [:enum "g" "ml" "each"]]]
            [:preferred_stores {:optional true :default []}
             [:maybe store-schema]]
            [:products_tracked {:optional true :derived products-tracked}
             [:maybe :int]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in}}
   :display {:title "{data.name}"}
   :owns [{:kind :product :via :ingredient_id}
          ;; the substitutions FROM this ingredient — an owns edge so
          ;; the accepted stand-ins embed on this page
          {:kind :substitution :via :from_ingredient_id}]
   :links [{:rel :products :owns :product :embed true
            :badge :products_tracked
            :summary "How stores sell this ingredient"}
           {:rel :substitutions :owns :substitution :embed true
            :where {:state "accepted"}
            :summary "What the family accepts in its place"}]
   :flow
   [[:suggested :accept :active
     {:one-way "Joining the pantry is low-stakes; Retire takes an ingredient out again."
      :display {:label "Accept" :style :primary :order 1}}]
    [:suggested :decline :retired
     {:one-way "Declining a suggestion is cheap — the AI can suggest it again any time."
      :display {:label "No thanks" :order 2}}]]
   :actions
   {:accept_many {:from #{:suggested} :to :active
                  :bulk {:max-items 200 :defer-over 50}
                  :safety {:idempotent true :reversible false :confirm true
                           :consequence "Every selected suggestion joins the pantry."}
                  :display {:label "Accept selected" :style :primary}}
    :update_aliases update-aliases
    :update_details update-details
    :absorb absorb
    :retire {:from #{:active} :to :retired
             :guards [no-tracked-products]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Retired ingredients stay retired — absorb points a duplicate at its survivor instead."}
             :display {:label "Retire" :style :danger :order 9}}}})
