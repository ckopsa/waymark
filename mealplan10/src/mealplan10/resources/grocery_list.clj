(ns mealplan10.resources.grocery-list
  "The GroceryList resource: compiled by the AI from a finalized plan.

  The agent reads the plan's assigned meals and their recipes, then
  creates one list per plan (or per two-week stretch) in draft and
  fills it with add_item. finalize is guarded on the plan actually
  being finalized — a list can't get ahead of the plan it shops for.
  In ready the humans shop, checking items off; complete refuses while
  anything is unchecked.

  The item-shaped actions share one part scope; item_on_list /
  item_not_checked / item_checked are pure acceptance-set
  declarations; plan_id is a :waymark/ref.

  Spelled in the batch-H declaration style: the whole machine is
  :flow rows (so :states is not spelled — the rows name them), the
  draft-phase and shopping-phase item edits reading as rows of the
  states they serve, and finalize/reopen declare each other as :undo
  — the engine verifies the pointers, so \"honestly reversible\" is
  graph-checked instead of a comment. Recorded deviation, a sentence:
  the item edits keep :input (add_item's fields are optional, and
  :args admits only required arguments). One deliberate law revision
  rode this spelling (the batch-H candidate, taken): check/uncheck
  declare each other as :undo — each is the other's exact inverse
  (same :name input, the not-checked/checked guards fencing the
  no-ops), so :reversible false was the law understating the truth.
  The part scope, the plan_id filter, and the def'd shopping rollup
  still ride their entries (batch G).
  mealplan10.style-invariance-test pins this kind's fingerprint hash
  byte-identical across spellings, the old split spelling carrying
  the same revision.

  The money layer (pantry-prices era): items carry an optional
  pantry ref and an AI-stamped estimate — the client authors the
  cross-kind price judgment through add_item, the list owns only the
  arithmetic (the three derived totals below), and the plan sums the
  lists in the same commit.

  Recorded punt: the with_plan profile has no v10 spelling."
  (:require [waymark10.dsl :refer [defderived defguardfn defresource
                                   defhandler guard require-fact]]
            [waymark10.types :as t]))

;; ── guards ──────────────────────────────────────────────────────────

;; the recorded 8.0 §5 residue: a verdict that READS another kind's
;; state is not pure over (row, input, clock), so it stays code —
;; :reads [:plan] names the dependency honestly. The pure render probe
;; carries no :read and declines; every enforcement ctx carries it.
(defguardfn plan-is-planned
  {:reads [:plan]
   :explain "Finalize the meal plan first — the grocery list follows from it."
   :remedies [:plan/finalize]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [plan (read :plan (get-in row [:data :plan_id]))]
      (cond
        (nil? plan) (t/deny {:errors {:plan_id ["plan not found"]}})
        (contains? #{:planned :active} (:state plan)) (t/allow)
        :else (t/deny)))
    (t/allow)))

;; what's on the list: the rendered enum, the per-part availability,
;; and the enforcement, from one set
(def item-on-list
  (guard {:name :item-on-list
          :judges [:name]
          :accepts (fn [row] (mapv :name (get-in row [:data :items])))
          :explain "No item named '{name}' on this list."}))

;; a checked item drops out of check_item's admitted set — so the
;; button disappears from that row instead of staying clickable for a
;; no-op
(def item-not-checked
  (guard {:name :item-not-checked
          :judges [:name]
          :accepts (fn [row]
                     (into [] (keep #(when-not (:have %) (:name %)))
                           (get-in row [:data :items])))
          :explain "'{name}' is already checked off."}))

;; the mirror of item_not_checked: uncheck_item only admits rows that
;; are actually checked, so an accidental tap has a one-tap way back
(def item-checked
  (guard {:name :item-checked
          :judges [:name]
          :accepts (fn [row]
                     (into [] (keep #(when (:have %) (:name %)))
                           (get-in row [:data :items])))
          :explain "'{name}' isn't checked off yet."}))

;; the gate judges the stored rollup fact; hoisted so its :check fn
;; has one identity per process (a fresh g/require per boot would
;; fingerprint as a different guard)
(def all-checked-gate
  (require-fact :all_items_checked
                {:explain "Some items are still unchecked — check them off (or remove them) before closing the list."
                 :remedies [:grocery_list/check_item]}))

;; ── handlers ────────────────────────────────────────────────────────

(defhandler add-item [row inp _ctx]
  (update-in row [:data :items]
             (fn [items]
               (let [items (vec items)
                     at (first (keep-indexed
                                #(when (= (:name %2) (:name inp)) %1)
                                items))]
                 (if (some? at)
                   (update items at
                           (fn [it]
                             (-> it
                                 (assoc :quantity (or (:quantity inp)
                                                      (:quantity it))
                                        :category (or (:category inp)
                                                      (:category it))
                                        ;; the AI's cross-kind price
                                        ;; judgment: a re-add only
                                        ;; overwrites what it states
                                        :ingredient_id (or (:ingredient_id inp)
                                                           (:ingredient_id it))
                                        :est_cost_cents (or (:est_cost_cents inp)
                                                            (:est_cost_cents it)))
                                 (update :meals
                                         #(vec (distinct (into (vec %)
                                                               (:meals inp))))))))
                   (conj items {:name (:name inp)
                                :quantity (:quantity inp)
                                :category (:category inp)
                                :meals (vec (:meals inp))
                                :ingredient_id (:ingredient_id inp)
                                :est_cost_cents (:est_cost_cents inp)
                                :have false}))))))

(defhandler remove-item [row inp _ctx]
  ;; removing an absent item is a no-op, so retries stay replay-safe
  (update-in row [:data :items]
             (fn [items] (vec (remove #(= (:name %) (:name inp)) items)))))

(defn- set-have [row inp have?]
  (update-in row [:data :items]
             (fn [items]
               (mapv #(if (= (:name %) (:name inp)) (assoc % :have have?) %)
                     items))))

(defhandler check-item [row inp _ctx] (set-have row inp true))
(defhandler uncheck-item [row inp _ctx] (set-have row inp false))

(defn- ensure-items [row _ctx]
  (update-in row [:data :items] #(vec (or % []))))

;; ── the declaration ─────────────────────────────────────────────────

(def name-input [:map [:name [:string {:min 1 :max 200}]]])

;; the shopping rollup as a declared fact (design §2): complete's gate
;; and its rendered reason are one definition
(defderived all-items-checked
  {:over [:items]
   :expr '(every [i (var :items)] (= (get i :have) true))
   :explain "Some items are still unchecked."})

;; the money rollups (pantry-prices era): what the week will cost, as
;; law over the embedded items. The (max … 0) wraps the nil-dodge —
;; sum nils wholesale on a nil addend, max drops nils, and est ≥ 0 by
;; schema, so an unpriced item counts 0 exactly as v9's where=is_set
(defderived estimated-total-cents
  {:over [:items]
   :expr '(sum [i (var :items)] (max (get i :est_cost_cents) 0))
   :explain "The priced items' estimates, summed."})

(defderived priced-items
  {:over [:items]
   :expr '(count [i (var :items)] (is-set (get i :est_cost_cents)))})

(defderived total-items
  {:over [:items]
   :expr '(count (var :items))})

(defresource grocery-list
  {:kind :grocery_list
   :initial :draft
   :terminal #{:done}
   :summary "Groceries · {state}"
   :schema [:map
            [:plan_id {:kind :plan :filter #{:eq}} :waymark/ref]
            [:items {:part-scope {:key :name}}
             [:vector
              [:map
               [:name [:string {:min 1 :max 200}]]
               [:quantity {:optional true}
                [:maybe [:string {:max 50}]]]
               [:category {:optional true}
                [:maybe [:string {:max 50}]]]
               [:meals {:optional true}
                [:maybe [:vector [:string {:max 200}]]]]
               ;; the pantry link and the AI-stamped estimate: the
               ;; client authors the cross-kind price judgment; the
               ;; list owns only the arithmetic
               [:ingredient_id {:optional true :kind :ingredient}
                [:maybe :waymark/ref]]
               [:est_cost_cents {:optional true
                                 :x-display {:widget "money"
                                             :label "Est. cost"}}
                [:maybe [:int {:min 0}]]]
               [:have {:optional true} [:maybe :boolean]]]]]
            [:all_items_checked {:optional true :derived all-items-checked}
             [:maybe :boolean]]
            ;; promoted (:filter): the plan's sums run on these
            [:estimated_total_cents {:optional true
                                     :derived estimated-total-cents
                                     :filter #{:eq :range}
                                     :x-display {:widget "money"
                                                 :label "Est. total"}}
             [:maybe :int]]
            [:priced_items {:optional true :derived priced-items
                            :filter #{:eq :range}}
             [:maybe :int]]
            [:total_items {:optional true :derived total-items
                           :filter #{:eq :range}}
             [:maybe :int]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   ;; the create form: pick the plan; items arrive via add_item
   :create-schema [:map
                   [:plan_id {:kind :plan} :waymark/ref]
                   [:notes {:optional true :x-display {:widget "prose"}}
                    [:maybe [:string {:max 2000}]]]]
   :on-create ensure-items
   :links [{:rel "plan" :kind :plan
            :href "/api/plans/{data.plan_id}"
            :summary "The meal plan this list shops for"}]
   :filterable {:state #{:eq :in}}
   :display {:title "Grocery list"}
   ;; the whole machine as rows: the draft phase (build the list),
   ;; the shopping phase (check things off), and the doors between —
   ;; the self-loop item edits mint the idempotent-overwrite safety,
   ;; every input keyed by :name, the part-scoped rows citing
   ;; :place :items
   :flow
   [[:draft :add_item     :draft
     {:input [:map
              [:name [:string {:min 1 :max 200}]]
              [:quantity {:optional true} [:maybe [:string {:max 50}]]]
              [:category {:optional true} [:maybe [:string {:max 50}]]]
              [:meals {:optional true}
               [:maybe [:vector [:string {:max 200}]]]]
              [:ingredient_id {:optional true :kind :ingredient
                               :pick {:state "active"}}
               [:maybe :waymark/ref]]
              [:est_cost_cents {:optional true
                                :x-display {:widget "money"
                                            :label "Est. cost"}}
               [:maybe [:int {:min 0}]]]]
      :handler add-item
      :display {:label "Add item" :style :primary :order 1}}]
    [:draft :remove_item  :draft
     {:input name-input :place :items
      :requires [item-on-list]
      :handler remove-item
      :display {:label "Remove item" :order 2}}]
    [:draft :finalize     :ready
     {:requires [plan-is-planned]
      :undo :reopen
      :display {:label "Ready to shop" :style :primary :order 1}}]
    [:ready :check_item   :ready
     {:input name-input :place :items
      :requires [item-on-list item-not-checked]
      :undo :uncheck_item
      :handler check-item
      :display {:label "Check off" :style :primary :order 1}}]
    [:ready :uncheck_item :ready
     {:input name-input :place :items
      :requires [item-on-list item-checked]
      :undo :check_item
      :handler uncheck-item
      :display {:label "Uncheck" :order 2}}]
    [:ready :reopen       :draft
     {:undo :finalize
      :display {:label "Back to editing" :order 3}}]
    [:ready :complete     :done
     {:requires [all-checked-gate]
      :one-way "Completing records a finished shop; the list stays readable as history."
      :display {:label "Shopping done" :order 2}}]]})
