(ns mealplan10.style-invariance-test
  "Two spellings, one law — the batch-G proof, extended through batch
  H, for the app that consumes the style. The six resource
  declarations now live in the batch-H spelling (:flow rows, :undo
  pointers, typed field words, over batch G's colocation); this suite
  keeps the OLD split spellings alive (constructed here byte-for-byte
  from the pre-G sources, sharing the namespaces' guard and handler
  objects so the imperative residue hashes as itself) and pins every
  kind's fingerprint hash byte-identical — a pure style refactor
  mints zero revisions. (One exception, deliberate and recorded:
  grocery_list's check/uncheck became honestly reversible with batch
  H — both spellings here carry that revision together.) The literal-hash pin at the bottom nails the
  hash values themselves for the kinds whose residue is all
  canonical-form (meal, prep_task, event), so no future respelling
  can move those fingerprints even if it rewrites this file's old
  spellings too; the bare-fn kinds' hashes bake in compilation order
  (callable-hash's recorded stopgap) and hold their proof in the
  shared-object pins instead.

  Sharing rules, inherited from waymark10.batch-g-invariance-test:
  - code guards and handlers hash by printed fn identity (unless
    defhandler/defguard minted a canonical form), so the old spelling
    must cite the very objects the new spelling uses;
  - g/require mints a fresh :check fn per call, so the grocery
    complete gate is the hoisted var, not a second g/require;
  - :on-create is not fingerprinted, but the shared object keeps the
    full normalized-map equality honest;
  - the event kind is Mirror-declared, so the pin wraps the SAME
    mirror/declaration weave (and the same adapter instance) around
    both spellings; the weave mints a fresh observe_external handler
    fn per call — identical canonical-form hash, distinct object —
    so event pins by hash (plus map equality modulo that handler)."
  (:require [clojure.test :refer [deftest is]]
            [mealplan10.event-source :as es]
            [mealplan10.resources.event :as event]
            [mealplan10.resources.grocery-list :as glist]
            [mealplan10.resources.meal :as meal]
            [mealplan10.resources.plan :as plan]
            [mealplan10.resources.prep-task :as ptask]
            [mealplan10.resources.rotation :as rotation]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.mirror :as mirror]))

(defn- hash-of-map [rmap]
  (fp/fingerprint-hash (r/fingerprint (r/normalize-resource rmap))))

(defn- hash-of-resource [res]
  (fp/fingerprint-hash (r/fingerprint res)))

(defn- pin!
  "The acceptance bar: the old split spelling normalizes to the very
  map the new spelling declares, and the fingerprint hashes are
  byte-identical."
  [kind old new]
  (is (= (r/normalize-resource old) (into {} new))
      (str (name kind) ": one normalized map, two spellings"))
  (is (= (hash-of-map old) (hash-of-resource new))
      (str (name kind) ": byte-identical fingerprint hashes")))

;; ── meal (old split spelling; handlers renamed apply-* in the ns) ────

(def old-meal
  {:kind :meal
   :states [:suggested :on_list :retired]
   :initial :suggested
   :terminal #{:retired}
   :shape 2
   :upcasts {1 meal/fold-theme}
   :summary "{data.name} · {state}"
   :nav :secondary
   :schema [:map
            [:name [:string {:min 1 :max 200}]]
            [:themes meal/theme-schema]
            [:recipe {:optional true
                      :x-display {:label "Recipe" :widget "prose"}}
             [:maybe [:string {:min 1 :max 8000}]]]
            [:prep_minutes {:optional true} [:maybe [:int {:min 0}]]]
            [:thaw_hours {:optional true} [:maybe [:int {:min 0}]]]
            [:servings {:optional true} [:maybe [:int {:min 1}]]]
            [:est_cost_cents {:optional true
                              :x-display {:widget "money"
                                          :label "Est. cost"}}
             [:maybe :int]]
            [:priced_ingredients {:optional true} [:maybe :int]]
            [:total_ingredients {:optional true} [:maybe :int]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :derived {:est_cost_cents meal/meal-est-cost
             :priced_ingredients meal/priced-ingredients
             :total_ingredients meal/total-ingredients}
   :owns [{:kind :meal_line :via :meal_id :on {:reprice :reprice}}]
   :links [{:rel :ingredients :owns :meal_line :embed true
            :badge :total_ingredients
            :where {:state "on_recipe"}
            :summary "The recipe's ingredient lines and what they cost"}]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:name :est_cost_cents] :default "name"}
   :display {:title "{data.name}"}
   :deviations
   ["accept_many stays a declared action — bulk has no flow-row spelling."
    "The on-list editors stay def'd actions — :fields would mint one all-optional writer, but apply-recipe writes conditionally and apply-themes dedupes: a different law under different names."
    "No :undo pointers — nothing here is declared reversible, and nothing walks retired back."
    "v10 summary templates carry no |join filter — the summary names the meal and its state only."
    "prep_minutes and thaw_hours carry no field defaults — the AI writes them with the recipe."]
   :actions
   {:accept {:from #{:suggested} :to :on_list
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Joining the meal list is low-stakes; Retire takes a meal off it again."}
             :display {:label "Add to meal list" :style :primary :order 1}}
    :accept_many {:from #{:suggested} :to :on_list
                  :bulk {:max-items 200 :defer-over 50}
                  :safety {:idempotent true :reversible false :confirm true
                           :consequence "Every selected suggestion joins the family meal list."}
                  :display {:label "Add selected to meal list" :style :primary}}
    :decline {:from #{:suggested} :to :retired
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Declining a suggestion is cheap — the AI can suggest it again any time."}
              :display {:label "No thanks" :order 2}}
    :update_recipe {:from #{:on_list} :to :on_list
                    :input [:map
                            [:recipe {:x-display {:label "Recipe"
                                                  :widget "prose"}}
                             [:string {:min 1 :max 8000}]]
                            [:prep_minutes {:optional true}
                             [:maybe [:int {:min 0}]]]
                            [:thaw_hours {:optional true}
                             [:maybe [:int {:min 0}]]]]
                    :edit {:prefill [:recipe :prep_minutes :thaw_hours]
                           :draft {:shared true :live true}}
                    :safety {:idempotent true :reversible false :confirm false}
                    :handler meal/apply-recipe
                    :display {:label "Update recipe" :order 2}}
    :update_themes {:from #{:on_list} :to :on_list
                    :input [:map [:themes meal/theme-schema]]
                    :edit {:prefill [:themes]}
                    :safety {:idempotent true :reversible false :confirm false}
                    :handler meal/apply-themes
                    :display {:label "Update themes" :order 3
                              :description "Retag the meal with every theme night it can serve"}}
    :retire {:from #{:on_list} :to :retired
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The meal leaves the family list and can no longer be assigned to plan days."}
             :display {:label "Retire" :style :danger :order 9}}
    :reprice {:from #{:on_list} :to :on_list
              :touches [{:kind :meal_line :action :reprice :may true}]
              :safety {:idempotent false :reversible false :confirm false}
              :display {:label "Reprice" :order 4
                        :description "Refresh every line's estimate from today's price world"}}}})

(deftest the-meal-fingerprint-survived-the-style-refactor
  (pin! :meal old-meal meal/meal))

;; ── rotation (old spelling: inline safety values) ───────────────────

(def old-rotation
  {:kind :rotation
   :states [:inactive :active]
   :initial :inactive
   :summary "{data.name} · {state}"
   :nav :secondary
   :schema [:map
            [:name [:string {:min 1 :max 100}]]
            [:themes [:vector {:min 1} [:string {:min 1 :max 50}]]]
            [:position [:int {:min 0}]]
            [:activated_at {:optional true} [:maybe :waymark/instant]]]
   :create-schema [:map
                   [:name {:optional true} [:maybe [:string {:min 1 :max 100}]]]
                   [:themes {:optional true}
                    [:maybe [:vector {:min 1} [:string {:min 1 :max 50}]]]]]
   :on-create @#'rotation/rotation-on-create
   :filterable {:state #{:eq :in}}
   :display {:title "{data.name}"}
   :actions
   {:activate {:from #{:inactive} :to :active
               :safety {:idempotent true :reversible true :confirm false}
               :handler rotation/activate-rotation
               :display {:label "Make active" :style :primary :order 1
                         :description "New plans draw Sunday themes from the most recently activated rotation"}}
    :deactivate {:from #{:active} :to :inactive
                 :safety {:idempotent true :reversible true :confirm false}
                 :display {:label "Deactivate" :order 4}}
    :add_theme {:from #{:active} :to :active
                :input rotation/theme-input
                :safety {:idempotent true :reversible false :confirm false}
                :handler rotation/add-theme
                :display {:label "Add theme" :style :primary :order 1}}
    :remove_theme {:from #{:active} :to :active
                   :input rotation/theme-input
                   :guards [rotation/not-last-theme]
                   :safety {:idempotent true :reversible false :confirm false}
                   :handler rotation/remove-theme
                   :display {:label "Remove theme" :order 2}}
    :advance {:from #{:active} :to :active
              :safety {:idempotent false :reversible false :confirm false}
              :handler rotation/advance-rotation
              :display {:label "Next theme" :order 3}}}})

(deftest the-rotation-fingerprint-survived-the-style-refactor
  (pin! :rotation old-rotation rotation/rotation))

;; ── plan (old split spelling: top-level derived/filterable/sortable/
;;    part-scopes, every action inline) ───────────────────────────────

(def old-plan
  {:kind :plan
   :states [:draft :planned :active :done :abandoned]
   :initial :draft
   :terminal #{:done :abandoned}
   :summary "Week of {data.start_date} · {data.weeks} wk · {state}"
   :schema [:map
            [:start_date {:x-display {:label "Start date"}} :waymark/date]
            [:weeks [:int {:min 1 :max 2}]]
            [:end_date {:optional true} [:maybe :waymark/date]]
            [:rotation_id {:optional true :kind :rotation}
             [:maybe :waymark/ref]]
            [:previous_plan {:optional true :kind :plan
                             :predecessor {:order :start_date}}
             [:maybe :waymark/ref]]
            [:total_days {:optional true} [:maybe :int]]
            [:undecided_days {:optional true} [:maybe :int]]
            [:all_days_covered {:optional true} [:maybe :boolean]]
            [:calendar_conflicts {:optional true} [:maybe :int]]
            [:has_conflicts {:optional true} [:maybe :boolean]]
            [:open_tasks {:optional true} [:maybe :int]]
            [:est_grocery_cost_cents {:optional true
                                      :x-display {:widget "money"
                                                  :label "Est. grocery cost"}}
             [:maybe :int]]
            [:priced_grocery_items {:optional true} [:maybe :int]]
            [:total_grocery_items {:optional true} [:maybe :int]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :create-schema [:map
                   [:start_date {:optional true} [:maybe :waymark/date]]
                   [:weeks {:optional true} [:maybe [:int {:min 1 :max 2}]]]
                   [:rotation_id {:optional true :kind :rotation}
                    [:maybe :waymark/ref]]
                   [:notes {:optional true :x-display {:widget "prose"}}
                    [:maybe [:string {:max 2000}]]]]
   :on-create @#'plan/plan-on-create
   :derived
   {:end_date {:over [:start_date :weeks]
               :expr '(+ (var :start_date) (days (- (* 7 (var :weeks)) 1)))}
    :total_days {:count {:owns :plan_day}}
    :undecided_days {:count {:owns :plan_day :where {:state #{"undecided"}}}}
    :all_days_covered {:over [:undecided_days]
                       :expr '(= 0 (var :undecided_days))
                       :explain "Every day needs a meal or an eating-out mark before finalizing."}
    :calendar_conflicts {:count {:related :calendar
                                 :where {:kind #{"blocking"}
                                         :state #{"fresh" "stale"}}}}
    :has_conflicts {:over [:calendar_conflicts]
                    :expr '(< 0 (var :calendar_conflicts))}
    :open_tasks {:count {:owns :prep_task
                         :where {:state #{"pending" "scheduled"}}}}
    :est_grocery_cost_cents {:sum {:owns :grocery_list
                                   :of :estimated_total_cents}}
    :priced_grocery_items {:sum {:owns :grocery_list :of :priced_items}}
    :total_grocery_items {:sum {:owns :grocery_list :of :total_items}}}
   :related {:calendar {:kind :event
                        :on [[:start_date :<= :date]
                             [:end_date :>= :date]]}}
   :owns [{:kind :prep_task :via :plan_id :on {:abandon :cancel}}
          {:kind :grocery_list :via :plan_id}
          {:kind :plan_day :via :plan_id}]
   :links [{:rel "calendar" :edge :calendar :badge :calendar_conflicts
            :summary "What the family already has planned"}
           {:rel "groceries" :owns :grocery_list :embed true
            :badge :total_grocery_items
            :summary "The week's grocery lists and what they'll cost"}
           {:rel "days" :owns :plan_day :embed true
            :badge :total_days
            :summary "The week's day decisions"}]
   :filterable {:state #{:eq :in}
                :start_date #{:eq :range}
                :end_date #{:eq :range}
                :has_conflicts #{:eq}
                :calendar_conflicts #{:eq :range}}
   :sortable {:fields [:start_date] :default "-start_date"}
   :display {:title "Meal plan — week of {data.start_date}"}
   :actions
   {:finalize {:from #{:draft} :to :planned
               :guards [plan/all-days-covered-gate plan/calendar-clear]
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Finalize plan" :style :primary :order 1}}
    :reopen {:from #{:planned} :to :draft
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Reopen" :order 2}}
    :begin {:from #{:planned} :to :active
            :guards [plan/plan-started]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Starting the week only reflects the calendar; nothing is lost and the plan stays editable through its days."}
            :display {:label "Start the week" :style :primary :order 1}}
    :complete {:from #{:active} :to :done
               :guards [plan/no-open-tasks]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Completing records a finished week; the plan remains readable as history."}
               :display {:label "Week done" :style :primary :order 1}}
    :abandon {:from #{:draft :planned :active} :to :abandoned
              :touches [{:kind :prep_task :action :cancel :may true}]
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The plan is discarded for good; its open prep tasks are cancelled; its days and any grocery list stay readable as records."}
              :display {:label "Abandon plan" :style :danger :order 9}}}})

(deftest the-plan-fingerprint-survived-the-style-refactor
  (pin! :plan old-plan plan/plan))

;; ── grocery_list (old spelling; the complete gate is the hoisted
;;    var — g/require mints a fresh :check fn per call). Carries the
;;    one deliberate law revision taken with batch H: check/uncheck
;;    became honestly reversible (mutual :undo in the new spelling),
;;    so BOTH spellings moved together — the pin proves the spellings
;;    agree on the revised law, not that the law never moved ─────────

(def old-grocery-list
  {:kind :grocery_list
   :states [:draft :ready :done]
   :initial :draft
   :terminal #{:done}
   :summary "Groceries · {state}"
   :schema [:map
            [:plan_id {:kind :plan} :waymark/ref]
            [:items [:vector
                     [:map
                      [:name [:string {:min 1 :max 200}]]
                      [:quantity {:optional true}
                       [:maybe [:string {:max 50}]]]
                      [:category {:optional true}
                       [:maybe [:string {:max 50}]]]
                      [:meals {:optional true}
                       [:maybe [:vector [:string {:max 200}]]]]
                      [:ingredient_id {:optional true :kind :ingredient}
                       [:maybe :waymark/ref]]
                      [:est_cost_cents {:optional true
                                        :x-display {:widget "money"
                                                    :label "Est. cost"}}
                       [:maybe [:int {:min 0}]]]
                      [:have {:optional true} [:maybe :boolean]]]]]
            [:all_items_checked {:optional true} [:maybe :boolean]]
            [:estimated_total_cents {:optional true
                                     :x-display {:widget "money"
                                                 :label "Est. total"}}
             [:maybe :int]]
            [:priced_items {:optional true} [:maybe :int]]
            [:total_items {:optional true} [:maybe :int]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :create-schema [:map
                   [:plan_id {:kind :plan} :waymark/ref]
                   [:notes {:optional true :x-display {:widget "prose"}}
                    [:maybe [:string {:max 2000}]]]]
   :on-create @#'glist/ensure-items
   :derived {:all_items_checked
             {:over [:items]
              :expr '(every [i (var :items)] (= (get i :have) true))
              :explain "Some items are still unchecked."}
             :estimated_total_cents
             {:over [:items]
              :expr '(sum [i (var :items)] (max (get i :est_cost_cents) 0))
              :explain "The priced items' estimates, summed."}
             :priced_items
             {:over [:items]
              :expr '(count [i (var :items)] (is-set (get i :est_cost_cents)))}
             :total_items
             {:over [:items]
              :expr '(count (var :items))}}
   :links [{:rel "plan" :kind :plan
            :href "/api/plans/{data.plan_id}"
            :summary "The meal plan this list shops for"}]
   :part-scopes {:items {:path :items :key :name}}
   :filterable {:state #{:eq :in}
                :plan_id #{:eq}
                :estimated_total_cents #{:eq :range}
                :priced_items #{:eq :range}
                :total_items #{:eq :range}}
   :display {:title "Grocery list"}
   :actions
   {:add_item {:from #{:draft} :to :draft
               :input [:map
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
               :safety {:idempotent true :reversible false :confirm false}
               :handler glist/add-item
               :display {:label "Add item" :style :primary :order 1}}
    :remove_item {:from #{:draft} :to :draft
                  :input glist/name-input :place :items
                  :guards [glist/item-on-list]
                  :safety {:idempotent true :reversible false :confirm false}
                  :handler glist/remove-item
                  :display {:label "Remove item" :order 2}}
    :finalize {:from #{:draft} :to :ready
               :guards [glist/plan-is-planned]
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Ready to shop" :style :primary :order 1}}
    :reopen {:from #{:ready} :to :draft
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Back to editing" :order 3}}
    :check_item {:from #{:ready} :to :ready
                 :input glist/name-input :place :items
                 :guards [glist/item-on-list glist/item-not-checked]
                 :safety {:idempotent true :reversible true :confirm false}
                 :handler glist/check-item
                 :display {:label "Check off" :style :primary :order 1}}
    :uncheck_item {:from #{:ready} :to :ready
                   :input glist/name-input :place :items
                   :guards [glist/item-on-list glist/item-checked]
                   :safety {:idempotent true :reversible true :confirm false}
                   :handler glist/uncheck-item
                   :display {:label "Uncheck" :order 2}}
    :complete {:from #{:ready} :to :done
               :guards [glist/all-checked-gate]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Completing records a finished shop; the list stays readable as history."}
               :display {:label "Shopping done" :order 2}}}})

(deftest the-grocery-list-fingerprint-survived-the-style-refactor
  (pin! :grocery_list old-grocery-list glist/grocery-list))

;; ── prep_task (old split spelling) ──────────────────────────────────

(def old-prep-task
  {:kind :prep_task
   :states [:pending :scheduled :done :cancelled]
   :initial :pending
   :terminal #{:done :cancelled}
   :summary "{data.task_type} · {data.meal_name} ({data.date}) · {state}"
   :nav :secondary
   :schema [:map
            [:plan_id {:kind :plan} :waymark/ref]
            [:date {:x-display {:label "Dinner date"}} :waymark/date]
            [:meal_name [:string {:min 1 :max 200}]]
            [:task_type [:enum "thaw" "prep" "cook"]]
            [:assignee {:optional true}
             [:maybe [:waymark/vocab {:open true}]]]
            [:due_at {:x-display {:label "When to start"}} :waymark/instant]
            [:overdue {:optional true} [:maybe :boolean]]
            [:duration_minutes {:optional true} [:maybe [:int {:min 0}]]]
            [:calendar_event_id {:optional true :kind :event
                                 :x-display {:hidden true}}
             [:maybe :waymark/ref]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 1000}]]]]
   :derived {:overdue {:over [:due_at :now]
                       :expr '(< (var :due_at) (var :now))}}
   :links [{:rel "plan" :kind :plan
            :href "/api/plans/{data.plan_id}"
            :summary "The meal plan this task serves"}]
   :filterable {:state #{:eq :in}
                :plan_id #{:eq}
                :date #{:eq :range}
                :task_type #{:eq :in}
                :assignee #{:eq}
                :due_at #{:after}
                :overdue #{:eq}}
   :sortable {:fields [:due_at] :default "due_at"}
   :display {:title "{data.task_type}: {data.meal_name}"}
   :deviations
   ["No :undo pointers — every edge here is honestly one-way or confirmed, and nothing walks a task back."
    "task_type carries no field default — the AI states it with each task."
    "The with_plan profile has no v10 spelling."]
   :actions
   {:schedule {:from #{:pending} :to :scheduled
               :input [:map [:event_id {:kind :event} :waymark/ref]]
               :safety {:idempotent true :reversible false :confirm true
                        :consequence "An event goes on the family calendar for this prep step."}
               :handler ptask/set-calendar-event
               :display {:label "Put on calendar" :style :primary :order 1}}
    :complete {:from #{:pending :scheduled} :to :done
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Marking a prep step done records kitchen reality; nothing external changes."}
               :display {:label "Done" :order 2}}
    :cancel {:from #{:pending :scheduled} :to :cancelled
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The task is dropped; any calendar event for it should be removed by hand."}
             :display {:label "Cancel" :style :danger :order 9}}}})

(deftest the-prep-task-fingerprint-survived-the-style-refactor
  (pin! :prep_task old-prep-task ptask/prep-task))

;; ── event (Mirror-declared: the pin wraps the same weave, and the
;;    same adapter instance, around both spellings) ───────────────────

(def old-event-app-map
  {:kind :event
   :summary "{data.title} · {data.date}"
   :nav :secondary
   :schema [:map
            [:title {:optional true}
             [:maybe [:string {:max 120}]]]
            [:date {:optional true} [:maybe :waymark/date]]
            [:kind {:optional true}
             [:maybe [:enum "blocking" "note"]]]]
   :filterable {:date #{:eq :range}
                :kind #{:eq :in}
                :state #{:eq :in}}
   :sortable {:fields [:date] :default "date"}
   :display {:title "{data.title} — {data.date}"}})

(deftest the-event-fingerprint-survived-the-style-refactor
  (let [adapter (es/fake-events)
        old (mirror/declaration old-event-app-map
                                {:adapter adapter
                                 :ttl-seconds event/ttl-seconds
                                 :discover-every event/discover-every})
        new (event/event-resource adapter)
        ;; the weave mints fresh observe_external / resolve_conflict
        ;; handler fns per call: same canonical-form hashes, distinct
        ;; objects — so map equality holds modulo those two handlers,
        ;; and the hash pin is exact
        drop-reminted (fn [m]
                        (-> m
                            (update-in [:actions :observe_external]
                                       dissoc :handler)
                            (update-in [:actions :resolve_conflict]
                                       dissoc :handler)))]
    (is (= (drop-reminted (r/normalize-resource old))
           (drop-reminted (into {} new)))
        "event: one normalized map, two spellings (modulo the re-minted sync handler object)")
    (is (= (hash-of-map old) (hash-of-resource new))
        "event: byte-identical fingerprint hashes")))

;; ── the batch-H pin: hashes as literals, where a literal is honest ──
;; The pins above prove old-split ≡ current spelling in-process; this
;; one nails the hash VALUES, captured before the batch-H respelling
;; (:flow rows, :undo pointers, typed field words), so no future
;; rewrite can drag both sides of an equality along with it. Only the
;; kinds whose imperative residue all carries a canonical printed
;; form (defhandler / the weave's minted forms) can be pinned this
;; way: a bare :accepts/:check fn hashes by printed object identity
;; (callable-hash's recorded stopgap), which bakes compilation order
;; into the hash — rotation, plan, and grocery_list therefore hold
;; their proof in the shared-object pins above, not in a literal.

(def the-canonical-hashes
  ;; meal and prep_task re-pinned 2026-07-12 (DX phase 5): recorded
  ;; deviations moved from docstring prose into the fingerprint-carried
  ;; :deviations vector — a deliberate advertisement-class law change.
  ;; event carries no deviations, so its hash is byte-identical to the
  ;; pre-deviations era (the empty-vector ≡ absent property).
  ;; meal and prep_task re-pinned again 2026-07-15 (pantry-prices
  ;; parity): meal gained the cost rollups, the meal_line owns edge +
  ;; reprice cascade (:touches advertised), and the embedded
  ;; ingredients link; prep_task's stale href-render punt sentence
  ;; left its :deviations (links render now) — deliberate revisions,
  ;; both spellings moved together above.
  ;; prep_task re-pinned 2026-07-20: the :assignee fact (open vocab,
  ;; :eq-filterable) — who does the step; the filter is the key
  ;; choreplan10's mirror feed discovers over. Both spellings moved
  ;; together above.
  {:meal      "ac2f3f372fdb779d61f1cc35cbf440c1bc6da00ef3e81aaf0fb20baa4ee375ec"
   ;; re-pinned 2026-07-24: :date gained :filter #{:eq :range} — the
   ;; day board's related join (one engine since waymark-bwu.2) needs
   ;; the promoted column; an intentional law change, not style drift
   :prep_task "5c1327a83e776803fa294dd8beb871e0c766a59d840628fa31aa7a8eae8d9463"
   :event     "77fba0a5a46b83a3594170a75e5f0614a9980a5f57cf76f90e5ac3e699b32805"})

(deftest the-canonical-residue-hashes-are-pinned-as-literals
  (is (= (:meal the-canonical-hashes) (hash-of-resource meal/meal)))
  (is (= (:prep_task the-canonical-hashes) (hash-of-resource ptask/prep-task)))
  (is (= (:event the-canonical-hashes)
         (hash-of-resource (event/event-resource (es/fake-events))))))
