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

  Spelled in the batch-G declaration style: the part scope and the
  plan_id filter ride their entries, the shopping rollup is a def'd
  derived fact on its own entry, the item-shaped actions are one
  group, and the two repeated safety values are named. The law is
  unchanged — mealplan10.style-invariance-test pins this kind's
  fingerprint hash byte-identical to the split spelling.

  Recorded punts: the with_plan profile and the href link render have
  no v10 spelling — the link declaration is carried."
  (:require [waymark10.declare :refer [defderived]]
            [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defresource defhandler]]
            [waymark10.types :as t]))

;; ── guards ──────────────────────────────────────────────────────────

;; the recorded 8.0 §5 residue: a verdict that READS another kind's
;; state is not pure over (row, input, clock), so it stays code —
;; :reads [:plan] names the dependency honestly. The pure render probe
;; carries no :read and declines; every enforcement ctx carries it.
(g/defguard plan-is-planned
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
  (g/guard {:name :item-on-list
            :judges [:name]
            :accepts (fn [row] (mapv :name (get-in row [:data :items])))
            :explain "No item named '{name}' on this list."}))

;; a checked item drops out of check_item's admitted set — so the
;; button disappears from that row instead of staying clickable for a
;; no-op
(def item-not-checked
  (g/guard {:name :item-not-checked
            :judges [:name]
            :accepts (fn [row]
                       (into [] (keep #(when-not (:have %) (:name %)))
                             (get-in row [:data :items])))
            :explain "'{name}' is already checked off."}))

;; the mirror of item_not_checked: uncheck_item only admits rows that
;; are actually checked, so an accidental tap has a one-tap way back
(def item-checked
  (g/guard {:name :item-checked
            :judges [:name]
            :accepts (fn [row]
                       (into [] (keep #(when (:have %) (:name %)))
                             (get-in row [:data :items])))
            :explain "'{name}' isn't checked off yet."}))

;; the gate judges the stored rollup fact; hoisted so its :check fn
;; has one identity per process (a fresh g/require per boot would
;; fingerprint as a different guard)
(def all-checked-gate
  (g/require :all_items_checked
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
                                                      (:category it)))
                                 (update :meals
                                         #(vec (distinct (into (vec %)
                                                               (:meals inp))))))))
                   (conj items {:name (:name inp)
                                :quantity (:quantity inp)
                                :category (:category inp)
                                :meals (vec (:meals inp))
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

;; named safety values: each spelled once, in full, and cited by name
;; — never inferred
(def routine
  ;; honestly reversible: finalize/reopen are each other's way back
  {:idempotent true :reversible true :confirm false})
(def overwrite
  ;; an idempotent item edit — nothing to confirm, and the mirror
  ;; action (or writing the item again) is the practical way back
  {:idempotent true :reversible false :confirm false})

;; the item-shaped action group: every input keyed by :name, the
;; part-scoped ones citing :place :items — merged into :actions
;; beside the list's lifecycle
(def item-actions
  {:add_item {:from #{:draft} :to :draft
              :input [:map
                      [:name [:string {:min 1 :max 200}]]
                      [:quantity {:optional true} [:maybe [:string {:max 50}]]]
                      [:category {:optional true} [:maybe [:string {:max 50}]]]
                      [:meals {:optional true}
                       [:maybe [:vector [:string {:max 200}]]]]]
              :safety overwrite
              :handler add-item
              :display {:label "Add item" :style :primary :order 1}}
   :remove_item {:from #{:draft} :to :draft
                 :input name-input :place :items
                 :guards [item-on-list]
                 :safety overwrite
                 :handler remove-item
                 :display {:label "Remove item" :order 2}}
   :check_item {:from #{:ready} :to :ready
                :input name-input :place :items
                :guards [item-on-list item-not-checked]
                :safety overwrite
                :handler check-item
                :display {:label "Check off" :style :primary :order 1}}
   :uncheck_item {:from #{:ready} :to :ready
                  :input name-input :place :items
                  :guards [item-on-list item-checked]
                  :safety overwrite
                  :handler uncheck-item
                  :display {:label "Uncheck" :order 2}}})

(defresource grocery-list
  {:kind :grocery_list
   :states [:draft :ready :done]
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
               [:have {:optional true} [:maybe :boolean]]]]]
            [:all_items_checked {:optional true :derived all-items-checked}
             [:maybe :boolean]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   ;; the create form: pick the plan; items arrive via add_item
   :create-schema [:map
                   [:plan_id {:kind :plan} :waymark/ref]
                   [:notes {:optional true :x-display {:widget "prose"}}
                    [:maybe [:string {:max 2000}]]]]
   :on-create ensure-items
   ;; carried declaration; envelope link render is v10's phase-3 punt
   :links [{:rel "plan" :kind :plan
            :summary "The meal plan this list shops for"}]
   :filterable {:state #{:eq :in}}
   :display {:title "Grocery list"}
   :actions
   (merge item-actions
          {:finalize {:from #{:draft} :to :ready
                      :guards [plan-is-planned]
                      :safety routine
                      :display {:label "Ready to shop" :style :primary :order 1}}
           :reopen {:from #{:ready} :to :draft
                    :safety routine
                    :display {:label "Back to editing" :order 3}}
           :complete {:from #{:ready} :to :done
                      :guards [all-checked-gate]
                      :safety {:idempotent true :reversible false :confirm false
                               :one-way "Completing records a finished shop; the list stays readable as history."}
                      :display {:label "Shopping done" :order 2}}})})
