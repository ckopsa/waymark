(ns mealplan10.style-invariance-test
  "Two spellings, one law — the batch-G proof, for the app that
  consumes the style. The six resource declarations now live in the
  colocated/def'd spelling; this suite keeps the OLD split spellings
  alive (constructed here byte-for-byte from the pre-G sources,
  sharing the namespaces' guard and handler objects so the imperative
  residue hashes as itself) and pins every kind's fingerprint hash
  byte-identical — a pure style refactor mints zero revisions.

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
   :schema [:map
            [:name [:string {:min 1 :max 200}]]
            [:themes meal/theme-schema]
            [:recipe {:optional true
                      :x-display {:label "Recipe" :widget "prose"}}
             [:maybe [:string {:min 1 :max 8000}]]]
            [:prep_minutes {:optional true} [:maybe [:int {:min 0}]]]
            [:thaw_hours {:optional true} [:maybe [:int {:min 0}]]]
            [:servings {:optional true} [:maybe [:int {:min 1}]]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   :display {:title "{data.name}"}
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
             :display {:label "Retire" :style :danger :order 9}}}})

(deftest the-meal-fingerprint-survived-the-style-refactor
  (pin! :meal old-meal meal/meal))

;; ── rotation (old spelling: inline safety values) ───────────────────

(def old-rotation
  {:kind :rotation
   :states [:inactive :active]
   :initial :inactive
   :summary "{data.name} · {state}"
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
            [:days [:vector
                    [:map
                     [:date :waymark/date]
                     [:theme {:optional true}
                      [:maybe [:string {:min 1 :max 50}]]]
                     [:meal_id {:optional true :kind :meal :label :meal_name}
                      [:maybe :waymark/ref]]
                     [:meal_name {:optional true} [:maybe [:string {:max 200}]]]
                     [:side_dish_id {:optional true :kind :meal
                                     :label :side_dish_name}
                      [:maybe :waymark/ref]]
                     [:side_dish_name {:optional true}
                      [:maybe [:string {:max 200}]]]
                     [:second_side_dish_id {:optional true :kind :meal
                                            :label :second_side_dish_name}
                      [:maybe :waymark/ref]]
                     [:second_side_dish_name {:optional true}
                      [:maybe [:string {:max 200}]]]
                     [:eating_out {:optional true} [:maybe :boolean]]
                     [:eating_out_where {:optional true
                                         :x-display {:label "Where"}}
                      [:maybe [:string {:max 120}]]]]]]
            [:all_days_covered {:optional true} [:maybe :boolean]]
            [:calendar_conflicts {:optional true} [:maybe :int]]
            [:has_conflicts {:optional true} [:maybe :boolean]]
            [:open_tasks {:optional true} [:maybe :int]]
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
    :all_days_covered {:over [:days]
                       :expr '(every [d (var :days)]
                                     (or (is-set (get d :meal_id))
                                         (= (get d :eating_out) true)))
                       :explain "Every day needs a meal or an eating-out mark before finalizing."}
    :calendar_conflicts {:count {:related :calendar
                                 :where {:kind #{"blocking"}
                                         :state #{"fresh" "stale"}}}}
    :has_conflicts {:over [:calendar_conflicts]
                    :expr '(< 0 (var :calendar_conflicts))}
    :open_tasks {:count {:owns :prep_task
                         :where {:state #{"pending" "scheduled"}}}}}
   :related {:calendar {:kind :event
                        :on [[:start_date :<= :date]
                             [:end_date :>= :date]]}}
   :owns [{:kind :prep_task :via :plan_id :on {:abandon :cancel}}]
   :links [{:rel "calendar" :edge :calendar :badge :calendar_conflicts
            :summary "What the family already has planned"}]
   :part-scopes {:days {:path :days :key :date}}
   :one-of {:days/coverage
            {:in [:days]
             :arms {:meal [:meal_id :meal_name
                           :side_dish_id :side_dish_name
                           :second_side_dish_id :second_side_dish_name]
                    :eating_out [:eating_out :eating_out_where]}
             :clears true}}
   :filterable {:state #{:eq :in}
                :start_date #{:eq :range}
                :end_date #{:eq :range}
                :has_conflicts #{:eq}
                :calendar_conflicts #{:eq :range}}
   :sortable {:fields [:start_date] :default "-start_date"}
   :display {:title "Meal plan — week of {data.start_date}"}
   :actions
   {:assign_meal {:from #{:draft} :to :draft
                  :input plan/assign-input :place :days
                  :guards [plan/date-in-plan plan/meal-fits-day]
                  :safety {:idempotent true :reversible false :confirm false}
                  :handler plan/assign-meal
                  :display {:label "Assign meal" :style :primary :order 1}}
    :assign_off_theme {:from #{:draft} :to :draft
                       :input plan/assign-input :place :days
                       :guards [plan/date-in-plan plan/meal-is-listed]
                       :safety {:idempotent true :reversible false
                                :confirm true
                                :consequence "The day gets a meal that does not match its theme night."}
                       :handler plan/assign-meal
                       :display {:label "Assign off-theme" :order 5}}
    :set_sunday_theme {:from #{:draft} :to :draft
                       :input [:map
                               [:date :waymark/date]
                               [:theme [:string {:min 1 :max 50}]]]
                       :place :days
                       :guards [plan/date-in-plan plan/sunday-only
                                plan/theme-in-rotation]
                       :safety {:idempotent true :reversible false
                                :confirm false}
                       :handler plan/set-sunday-theme
                       :display {:label "Pick Sunday theme" :order 2}}
    :mark_eating_out {:from #{:draft} :to :draft
                      :input [:map
                              [:date :waymark/date]
                              [:where {:optional true
                                       :x-display {:label "Where"}}
                               [:maybe [:string {:max 120}]]]]
                      :place :days
                      :guards [plan/date-in-plan]
                      :safety {:idempotent true :reversible false
                               :confirm false}
                      :handler plan/mark-eating-out
                      :display {:label "Eating out" :order 3}}
    :clear_day {:from #{:draft} :to :draft
                :input plan/day-input :place :days
                :guards [plan/date-in-plan]
                :safety {:idempotent true :reversible false :confirm false}
                :handler plan/clear-day
                :display {:label "Clear day" :order 4}}
    :add_side_dish {:from #{:draft} :to :draft
                    :input plan/side-dish-input :place :days
                    :guards [plan/date-in-plan plan/day-has-meal
                             plan/has-free-side-slot plan/meal-fits-day
                             plan/meal-is-listed]
                    :safety {:idempotent true :reversible true :confirm false}
                    :handler plan/add-side-dish
                    :display {:label "Add side dish" :order 6}}
    :remove_side_dish {:from #{:draft} :to :draft
                       :input plan/side-dish-input :place :days
                       :guards [plan/date-in-plan]
                       :safety {:idempotent true :reversible true
                                :confirm false}
                       :handler plan/remove-side-dish
                       :display {:label "Remove side dish" :order 7}}
    :finalize {:from #{:draft} :to :planned
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
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The plan is discarded for good; its open prep tasks are cancelled; its days and any grocery list stay readable as records."}
              :display {:label "Abandon plan" :style :danger :order 9}}}})

(deftest the-plan-fingerprint-survived-the-style-refactor
  (pin! :plan old-plan plan/plan))

;; ── grocery_list (old spelling; the complete gate is the hoisted
;;    var — g/require mints a fresh :check fn per call) ───────────────

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
                      [:have {:optional true} [:maybe :boolean]]]]]
            [:all_items_checked {:optional true} [:maybe :boolean]]
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
              :explain "Some items are still unchecked."}}
   :links [{:rel "plan" :kind :plan
            :summary "The meal plan this list shops for"}]
   :part-scopes {:items {:path :items :key :name}}
   :filterable {:state #{:eq :in}
                :plan_id #{:eq}}
   :display {:title "Grocery list"}
   :actions
   {:add_item {:from #{:draft} :to :draft
               :input [:map
                       [:name [:string {:min 1 :max 200}]]
                       [:quantity {:optional true} [:maybe [:string {:max 50}]]]
                       [:category {:optional true} [:maybe [:string {:max 50}]]]
                       [:meals {:optional true}
                        [:maybe [:vector [:string {:max 200}]]]]]
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
                 :safety {:idempotent true :reversible false :confirm false}
                 :handler glist/check-item
                 :display {:label "Check off" :style :primary :order 1}}
    :uncheck_item {:from #{:ready} :to :ready
                   :input glist/name-input :place :items
                   :guards [glist/item-on-list glist/item-checked]
                   :safety {:idempotent true :reversible false :confirm false}
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
   :schema [:map
            [:plan_id {:kind :plan} :waymark/ref]
            [:date {:x-display {:label "Dinner date"}} :waymark/date]
            [:meal_name [:string {:min 1 :max 200}]]
            [:task_type [:enum "thaw" "prep" "cook"]]
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
            :summary "The meal plan this task serves"}]
   :filterable {:state #{:eq :in}
                :plan_id #{:eq}
                :task_type #{:eq :in}
                :due_at #{:after}
                :overdue #{:eq}}
   :sortable {:fields [:due_at] :default "due_at"}
   :display {:title "{data.task_type}: {data.meal_name}"}
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
