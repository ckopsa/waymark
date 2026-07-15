(ns mealplan10.resources.meal-line
  "The MealLine resource: one recipe line — this meal asks for that
  many grams of that ingredient. Promoted from the meal's embedded
  data because the COST question crosses kinds: a line's estimate
  depends on the ingredient's tracked products (and its accepted
  substitutions), and an embedded ref is invisible to the join
  grammar. Promoted, the refs are real columns and the meal's cost
  rollups are engine-maintained sums over the owns edge.

  Removing is a transition, not a delete — the row stays as history;
  the meal's rollups count only on_recipe lines.

  Pricing is write-time: a line prices itself the moment it exists
  ((ingredient, grams) is enough), from the cheapest unit-priceable
  tracked product — preferred-store order first, then cents_per_100g,
  the same rule the trip math uses. When the ingredient itself is
  unpriceable, the ACCEPTED substitutions from it price the line
  through the cheapest stand-in and the estimate says so:
  priced_via = the stand-in's name (×ratio when ≠ 1) — an estimate
  via a stand-in never masquerades as the real thing. A direct price
  arriving later wins the line back on the next reprice. reprice is
  honestly NON-idempotent: its outcome depends on the price world
  outside the row, so natural replay must never swallow a repeat.

  The substitute SWAP is distinct from the pricing fallback: it
  mutates the line to BECOME the stand-in (ingredient, grams×ratio),
  re-prices, and thereby consumes the acceptance — the swapped line's
  ingredient no longer matches the substitution's from side, so a
  double-tap with the same input replays naturally instead of denying
  (v9's rule: idempotent stays declared, replay-safety over a guard
  that would refuse the second tap)."
  (:require [waymark10.dsl :refer [defaction defderived defresource
                                   defhandler guard]])
  (:import (java.math RoundingMode)))

(def overwrite
  {:idempotent true :reversible false :confirm false})

;; ── the pricing law (handler code — the v9 fn= boundary) ────────────

(defn- best-unit-price
  "The cheapest unit-priceable TRACKED product of an ingredient:
  preferred-store order first (the ingredient's own list, best
  first), then cents_per_100g. nil when nothing is unit-priceable."
  [ctx ingredient-id]
  (when-some [find' (:find ctx)]
    (let [ing ((:read ctx) :ingredient ingredient-id)
          prefs (into {}
                      (map-indexed (fn [i s] [s i]))
                      (get-in ing [:data :preferred_stores]))
          offers (keep (fn [p]
                         (when-some [u (get-in p [:data :cents_per_100g])]
                           {:unit u :store (get-in p [:data :store])}))
                       (find' :product {:ingredient_id ingredient-id
                                        :state :tracked}
                              {:limit 200}))]
      (:unit (first (sort-by (fn [{:keys [store unit]}]
                               [(get prefs store 99) unit])
                             offers))))))

(defn- ratio-str
  "×0.8, trailing zeros dropped — garnish for priced_via."
  [^java.math.BigDecimal ratio]
  (str "×" (.toPlainString (.stripTrailingZeros ratio))))

(defn- best-substitute-price
  "The cheapest ACCEPTED stand-in for this line's ingredient:
  {:cost cents :label \"Margarine ×0.8\"}, nil when none prices."
  [ctx ingredient-id grams]
  (when-some [find' (:find ctx)]
    (->> (find' :substitution {:from_ingredient_id ingredient-id
                               :state :accepted}
                {:limit 100})
         (keep (fn [s]
                 (when-some [unit (best-unit-price
                                   ctx (get-in s [:data :to_ingredient_id]))]
                   (let [ratio ^java.math.BigDecimal
                         (or (get-in s [:data :ratio]) 1M)
                         cost (-> (bigdec grams)
                                  (.multiply ratio)
                                  (.multiply (bigdec unit))
                                  (.divide 100M 0 RoundingMode/HALF_UP)
                                  long)]
                     {:cost cost
                      :label (str (get-in s [:data :to_ingredient_name])
                                  (when (not (zero? (.compareTo ratio 1M)))
                                    (str " " (ratio-str ratio))))}))))
         (sort-by :cost)
         first)))

(defn price-line
  "Stamp the line's estimate from the current price world. An
  explicit value wins unless refresh?; a blank always re-fills. Order:
  the ingredient's own best unit price (priced as itself, via nil),
  else the cheapest accepted substitution (via = the stand-in's
  name), else keep what it has — blank included, honestly."
  [data ctx refresh?]
  (if (and (some? (:est_cost_cents data)) (not refresh?))
    data
    (let [grams (long (:grams data))]
      (if-some [unit (best-unit-price ctx (:ingredient_id data))]
        (assoc data
               :est_cost_cents (quot (+ (* grams (long unit)) 50) 100)
               :priced_via nil)
        (if-some [best (best-substitute-price ctx (:ingredient_id data) grams)]
          (assoc data :est_cost_cents (:cost best) :priced_via (:label best))
          data)))))

;; ── the declared facts and guards ───────────────────────────────────

(defderived priced-fact
  {:over [:est_cost_cents]
   :expr '(is-set (var :est_cost_cents))})

(def substitution-applies
  ;; one acceptance set drives the rendered picker enum, availability,
  ;; and enforcement: the ACCEPTED substitutions FROM this line's
  ;; ingredient. nil (no constraint) on a probe with no hooks.
  (guard {:name :substitution-applies
          :judges [:substitution_id]
          :reads [:substitution]
          :accepts (fn [row ctx]
                     (when-some [find' (:find ctx)]
                       (mapv :id
                             (find' :substitution
                                    {:from_ingredient_id
                                     (get-in row [:data :ingredient_id])
                                     :state :accepted}
                                    {:limit 100}))))
          :explain "No accepted substitution stands in for this line's ingredient."
          :remedies [:substitution/accept]}))

;; ── handlers ────────────────────────────────────────────────────────

(defhandler set-grams [row inp ctx]
  (update row :data
          #(price-line (assoc % :grams (:grams inp)
                              :est_cost_cents nil
                              :priced_via nil)
                       ctx false)))

(defhandler substitute-line [row inp ctx]
  (let [sub ((:read ctx) :substitution (:substitution_id inp))
        ratio ^java.math.BigDecimal (or (get-in sub [:data :ratio]) 1M)
        grams (max 1 (long (.setScale (.multiply (bigdec (get-in row [:data :grams]))
                                                 ratio)
                                      0 RoundingMode/HALF_UP)))]
    (update row :data
            #(price-line (assoc % :ingredient_id
                                (get-in sub [:data :to_ingredient_id])
                                :grams grams
                                :est_cost_cents nil
                                :priced_via nil)
                         ctx false))))

(defhandler reprice-line [row _inp ctx]
  (update row :data #(price-line % ctx true)))

(defn- line-on-create [row ctx]
  (update row :data #(price-line % ctx false)))

;; ── actions ─────────────────────────────────────────────────────────

(defaction set-grams-action
  {:from #{:on_recipe} :to :on_recipe
   :input [:map [:grams [:int {:min 1}]]]
   :edit {:prefill [:grams]}
   :safety overwrite
   :handler set-grams
   :display {:label "Set grams" :order 2}})

(defaction substitute
  {:from #{:on_recipe} :to :on_recipe
   :input [:map [:substitution_id {:kind :substitution} :waymark/ref]]
   :guards [substitution-applies]
   :safety {:idempotent true :reversible false :confirm false
            :one-way "The line becomes the stand-in; the next verdict is another substitution or a rematch of the pantry itself."}
   :handler substitute-line
   :display {:label "Substitute" :order 3}})

(defaction reprice
  {:from #{:on_recipe} :to :on_recipe
   ;; outcome depends on the price world OUTSIDE the row — natural
   ;; replay must never swallow a repeat, so: honestly non-idempotent
   :safety {:idempotent false :reversible false :confirm false}
   :handler reprice-line
   :display {:label "Reprice" :order 4
             :description "Refresh the estimate from today's price world"}})

(defresource meal-line
  {:kind :meal_line
   :states [:on_recipe :removed]
   :initial :on_recipe
   :terminal #{:removed}
   :summary "{data.grams} g {data.ingredient_name} · {data.meal_name}"
   :nav :secondary
   :schema [:map
            [:meal_id {:kind :meal :label :meal_name :filter #{:eq}
                       :pick {:state "on_list"}}
             :waymark/ref]
            [:meal_name {:optional true} [:maybe [:string {:max 200}]]]
            [:ingredient_id {:kind :ingredient :label :ingredient_name
                             :filter #{:eq} :pick {:state "active"}}
             :waymark/ref]
            [:ingredient_name {:optional true} [:maybe [:string {:max 200}]]]
            [:grams [:int {:min 1}]]
            ;; write-time estimate; promoted (:filter) because the
            ;; meal's SUM runs on it. Blank only when nothing prices.
            [:est_cost_cents {:optional true :filter #{:eq :range}
                              :x-display {:widget "money"
                                          :label "Est. cost"}}
             [:maybe [:int {:min 0}]]]
            ;; the accepted substitution the estimate priced through;
            ;; blank = priced as itself
            [:priced_via {:optional true} [:maybe [:string {:max 200}]]]
            [:priced {:optional true :derived priced-fact :filter #{:eq}}
             [:maybe :boolean]]]
   ;; the client states the ask; the estimate and the labels are the
   ;; engine's and the pricing law's
   :create-schema [:map
                   [:meal_id {:kind :meal :pick {:state "on_list"}}
                    :waymark/ref]
                   [:ingredient_id {:kind :ingredient
                                    :pick {:state "active"}}
                    :waymark/ref]
                   [:grams [:int {:min 1}]]
                   [:est_cost_cents {:optional true
                                     :x-display {:widget "money"}}
                    [:maybe [:int {:min 0}]]]]
   :on-create line-on-create
   :filterable {:state #{:eq :in}}
   :display {:title "{data.ingredient_name} — {data.meal_name}"}
   :deviations
   ["The pricing arithmetic (unit price × grams ÷ 100, ratio math) is handler code, not law — division and argmax sit outside the expression grammar, the boundary v9 drew with price_line; the declared laws are priced, the meal's sums, and the substitutions' own machine."]
   :flow
   [[:on_recipe :remove :removed
     {:one-way "The line leaves the recipe and its meal's totals; the row stays as history."
      :display {:label "Remove" :style :danger :order 9}}]]
   :actions
   {:set_grams set-grams-action
    :substitute substitute
    :reprice reprice}})
