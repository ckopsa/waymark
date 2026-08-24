(ns mealplan10.resources.plan-day
  "The PlanDay resource: one day of one week, its STATE the decision —
  undecided → planned (assign_meal) / eating_out (mark_eating_out),
  clear_day walks back. Promoted from the plan's embedded :days the
  moment the day earned its own machine (the promotion rule's second
  test): coverage was a one-of data group; now it is the lifecycle,
  the walk-back is a transition, and every day wears its own
  affordances, refusals, and history.

  The one-of group did NOT survive the promotion (recorded): with the
  eating_out bool gone (state is the fact), the :eating_out arm's
  only field is eating_out_where — an arm whose primary may honestly
  be absent never registers as filled, so :clears could not clear the
  meal arm. Exclusivity is the machine instead; each transition's
  handler nulls the departing state's facts, spelled once per edge
  (labels follow their refs — the engine nulls meal_name when meal_id
  nulls).

  Days are born WITH their plan (plan-on-create walks the week
  through the ctx birth door, design §24) and are the week's record
  ever after: no terminal state (the rotation precedent), no cascade
  from abandon — an abandoned plan's days stay readable exactly as
  its confirm sentence always promised. One day per (plan, date) is
  declared :unique — the index refuses the duplicate as a 409.

  Day edits follow the PLAN's phase (plan-editable): building in
  :draft, frozen while :planned (the reviewed plan), open again in
  :active — the live week bends around reality, which is what
  plan.begin's sentence always claimed. Every decision door reaches
  its state from every origin, the row's own included (waymark-1pq):
  assign_meal/assign_off_theme swap a planned day's meal in place and
  mark_eating_out re-marks where — the field-tested gap (swap =
  clear_day → assign, a briefly undecided day) is closed. Multi-origin
  doors carry no :undo (the inversion rule demands one exact origin);
  add/remove_side_dish are :planned self-loops and keep their honest
  pair. set_sunday_theme re-themes a rotating Sunday PRE-assignment
  only — a planned Sunday clears first.

  The meal identity join (waymark-m6j): :related :meal joins the
  promoted meal_id against the meal row's own id, and recipe_lines
  sums the meal's total_ingredients through it — a LIVE
  engine-maintained fact: a meal_line write reaches this row's
  stored count through the maintainer's meal_line → meal → plan_day
  chain in the same call, so the number never goes stale. The plan's
  days_without_recipe counts planned days where it reads 0, and its
  finalize warning judges that. Considered and rejected: stamping
  meal_has_recipe here at assign time (ctx :read the meal in the
  handler) — an as-of-assign copy of meal.has_recipe that a recipe
  filled in (or emptied) LATER never flips; a second writer of one
  truth, drifting both directions. An unassigned or eating-out day
  honestly reads 0 — no meal, no lines feeding the night."
  (:require [mealplan10.themes :as themes]
            [waymark10.dsl :refer [defaction defderived defguardfn
                                   defresource defhandler expr-guard
                                   guard]]
            [waymark10.types :as t])
  (:import (java.time DayOfWeek LocalDate)))

;; ── derived facts ───────────────────────────────────────────────────

;; the recipe truth behind this day's dinner (waymark-m6j): the
;; assigned meal's total_ingredients, read through the :meal identity
;; join — 0 while no meal is assigned (nil relates to nothing) and 0
;; for a hollow meal, which is exactly the count the plan's
;; days_without_recipe filters on. Live, one writer (the engine),
;; never an assign-time stamp.
(defderived recipe-lines
  {:sum {:related :meal :of :total_ingredients}})

(def overwrite
  {:idempotent true :reversible false :confirm false})

;; ── guards ──────────────────────────────────────────────────────────

;; the parent plan's phase gates every day edit (the promoted spelling
;; of "day actions are :from #{:draft}" — widened to the live week by
;; the family's own call)
(defguardfn plan-editable
  {:reads [:plan]
   :explain "Days change while the week is being built (draft) or lived (active) — a finalized plan under review is frozen."
   :remedies [:plan/reopen :plan/begin]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [plan (read :plan (get-in row [:data :plan_id]))]
      (cond
        (nil? plan) (t/deny {:errors {:plan_id ["plan not found"]}})
        (contains? #{:draft :active} (:state plan)) (t/allow)
        :else (t/deny)))
    (t/allow)))

;; the create door's own gate: a day belongs inside its plan's window
(defguardfn date-in-plan-range
  {:judges [:date]
   :reads [:plan]
   :explain "{date} is outside the plan's week(s)."}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (if-some [plan (read :plan (:plan_id inp))]
      (let [^LocalDate start (get-in plan [:data :start_date])
            ^LocalDate d (:date inp)
            days (* 7 (long (or (get-in plan [:data :weeks]) 1)))]
        (if (and d start
                 (not (.isBefore d start))
                 (.isBefore d (.plusDays start days)))
          (t/allow)
          (t/deny)))
      (t/deny {:errors {:plan_id ["plan not found"]}}))
    (t/allow)))

;; day-of-week has no expression op, so the verdict stays code — over
;; this row's own date, no cross-kind read
(defguardfn sunday-only
  {:explain "Only Sunday rotates; this day has a fixed weeknight theme."}
  [row _inp _ctx]
  (if (= DayOfWeek/SUNDAY
         (.getDayOfWeek ^LocalDate (get-in row [:data :date])))
    (t/allow)
    (t/deny)))

(def theme-in-rotation
  ;; the plan's linked rotation's themes, one hop further than before:
  ;; day → plan → rotation. nil (no constraint) when the chain breaks
  ;; or the probe carries no :read.
  (guard {:name :theme-in-rotation
          :judges [:theme]
          :reads [:plan :rotation]
          :accepts (fn [row ctx]
                     (when-some [read (:read ctx)]
                       (when-some [plan (read :plan (get-in row [:data :plan_id]))]
                         (when-some [rid (get-in plan [:data :rotation_id])]
                           (when-some [rotation (read :rotation rid)]
                             (vec (get-in rotation [:data :themes])))))))
          :explain "'{theme}' is not in the Sunday rotation. Add it there first."
          :remedies [:rotation/add_theme]}))

(defguardfn meal-is-listed
  {:judges [:meal_id] :reads [:meal]
   :explain "That meal is not on the family meal list yet. Accept a suggestion (or ask the AI for one) first."
   :remedies [:meal/accept]}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [meal (read :meal (:meal_id inp))]
      (if (and meal (= :on_list (:state meal)))
        (t/allow)
        (t/deny {:errors {:meal_id ["not an on-list meal"]}})))
    (t/allow)))

;; one acceptance set, two consumers: THIS day's picker offers exactly
;; the on-list meals that serve its night (a rotating Sunday binds
;; nothing), and the invoke enforces membership in the same set
(def meal-fits-day
  (guard {:name :meal-fits-day
          :judges [:meal_id]
          :reads [:meal]
          :accepts (fn [row ctx]
                     (when-some [find' (:find ctx)]
                       (let [theme (get-in row [:data :theme])]
                         (when (not= themes/rotating theme)
                           (into []
                                 (keep #(when (some #{theme}
                                                    (get-in % [:data :themes]))
                                          (:id %)))
                                 (find' :meal {:state :on_list}
                                        {:limit 500}))))))
          :explain "That meal doesn't serve this day's theme night. Pick the Sunday theme first if the day still rotates, or assign off-theme with confirmation."
          :remedies [:plan_day/set_sunday_theme :plan_day/assign_off_theme]}))

(def side-fits-day
  ;; the same admitted set, judging the side's field
  (guard {:name :side-fits-day
          :judges [:side_id]
          :reads [:meal]
          :accepts (fn [row ctx]
                     (when-some [find' (:find ctx)]
                       (let [theme (get-in row [:data :theme])]
                         (when (not= themes/rotating theme)
                           (into []
                                 (keep #(when (some #{theme}
                                                    (get-in % [:data :themes]))
                                          (:id %)))
                                 (find' :meal {:state :on_list}
                                        {:limit 500}))))))
          :explain "That side doesn't serve this day's theme night."
          :remedies [:plan_day/assign_off_theme]}))

(defguardfn side-is-listed
  {:judges [:side_id] :reads [:meal]
   :explain "That meal is not on the family meal list yet. Accept a suggestion (or ask the AI for one) first."
   :remedies [:meal/accept]}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [meal (read :meal (:side_id inp))]
      (if (and meal (= :on_list (:state meal)))
        (t/allow)
        (t/deny {:errors {:side_id ["not an on-list meal"]}})))
    (t/allow)))

(def has-free-side-slot
  ;; declarative now — the day's own fields, no date lookup
  (expr-guard {:name :has-free-side-slot
               :when '(or (not (is-set (data :side_dish_id)))
                          (not (is-set (data :second_side_dish_id))))
               :explain "This day already has 2 side dishes — remove one first."
               :remedies [:plan_day/remove_side_dish]}))

;; ── handlers: each edge nulls what it leaves ────────────────────────

(defhandler assign-day-meal [row inp _ctx]
  ;; meal_name is the engine's (ref label); leaving :eating_out nulls
  ;; its facts — sides survive an assign (same meal arm)
  (update row :data #(assoc % :meal_id (:meal_id inp)
                            :eating_out_where nil)))

(defhandler set-day-theme [row inp _ctx]
  (assoc-in row [:data :theme] (:theme inp)))

(defhandler mark-day-eating-out [row inp _ctx]
  ;; leaving :planned nulls the whole meal arm, sides included; the
  ;; engine nulls the labels as their refs null
  (update row :data #(assoc % :eating_out_where (:where inp)
                            :meal_id nil
                            :side_dish_id nil
                            :second_side_dish_id nil)))

(defhandler clear-day [row _inp _ctx]
  ;; back to undecided: no facts survive but the theme (as ever)
  (update row :data #(assoc % :meal_id nil
                            :side_dish_id nil
                            :second_side_dish_id nil
                            :eating_out_where nil)))

(defhandler add-day-side [row inp _ctx]
  (update row :data
          (fn [d]
            (cond
              (or (= (:side_dish_id d) (:side_id inp))
                  (= (:second_side_dish_id d) (:side_id inp))) d
              (nil? (:side_dish_id d)) (assoc d :side_dish_id (:side_id inp))
              :else (assoc d :second_side_dish_id (:side_id inp))))))

(defhandler remove-day-side [row inp _ctx]
  (update row :data
          (fn [d]
            (cond
              (= (:side_dish_id d) (:side_id inp))
              (assoc d :side_dish_id nil)
              (= (:second_side_dish_id d) (:side_id inp))
              (assoc d :second_side_dish_id nil)
              :else d))))

;; ── actions ─────────────────────────────────────────────────────────

;; one input value, two doors (assign_meal and assign_off_theme), so
;; one spelling of the prose — the off-theme door's own sentence lives
;; on its :consequence, where the departure from the theme belongs
(def meal-input [:map [:meal_id {:kind :meal :pick {:state "on_list"}
                                 :x-display
                                 {:label "Dinner"
                                  :help "What the family eats that night, picked from the meals already on the list."}}
                       :waymark/ref]])

(def side-input
  ;; the SIDE's meal id — named for what it is, not the day's main
  ;; meal slot (recorded deviation from v9's meal_id spelling)
  [:map [:side_id {:kind :meal :pick {:state "on_list"}
                   :x-display
                   {:label "Side dish"
                    :help "What goes beside the main — also a meal from the list, and a night holds at most two."}}
         :waymark/ref]])

(defaction assign-meal
  {:from #{:undecided :eating_out :planned} :to :planned
   :input meal-input
   :edit {:prefill [:meal_id]}
   :guards [plan-editable meal-fits-day]
   ;; multi-origin (undecided, eating_out, OR the swap's own :planned):
   ;; the inversion rule allows no :undo — clear_day is the honest,
   ;; unverified way back
   :safety {:idempotent true :reversible false :confirm false
            :one-way "Assigning covers the day (a planned day swaps its meal in place); Clear day walks it back to undecided."}
   :handler assign-day-meal
   :display {:label "Assign meal" :style :primary :order 1}})

(defaction assign-off-theme
  {:from #{:undecided :eating_out :planned} :to :planned
   :input meal-input
   :edit {:prefill [:meal_id]}
   :guards [plan-editable meal-is-listed]
   :safety {:idempotent true :reversible false :confirm true
            :consequence "The day gets a meal that does not match its theme night."}
   :handler assign-day-meal
   :display {:label "Assign off-theme" :order 5}})

(defaction set-sunday-theme
  {:from #{:undecided} :to :undecided
   :input [:map [:theme {:x-display
                         {:label "Sunday theme"
                          :help "The kind of food this Sunday takes — one of the themes on the plan's rotation, like Italian or Asian."}}
                 [:string {:min 1 :max 50}]]]
   :edit {:prefill [:theme]}
   :guards [plan-editable sunday-only theme-in-rotation]
   :safety overwrite
   :handler set-day-theme
   :display {:label "Pick Sunday theme" :order 2}})

(defaction mark-eating-out
  {:from #{:undecided :planned :eating_out} :to :eating_out
   :input [:map [:where {:optional true
                         :x-display
                         {:label "Where"
                          :help "The restaurant or the house you're eating at — leave it blank when the night is out but the place isn't settled."}}
                 [:maybe [:string {:max 120}]]]]
   :guards [plan-editable]
   :safety {:idempotent true :reversible false :confirm false
            :one-way "Eating out covers the day (any planned meal steps aside); Clear day walks it back."}
   :handler mark-day-eating-out
   :display {:label "Eating out" :order 3}})

(defaction clear-day-action
  {:from #{:planned :eating_out} :to :undecided
   :guards [plan-editable]
   :safety {:idempotent true :reversible false :confirm false
            :one-way "The day returns to undecided; assign or mark eating out to cover it again."}
   :handler clear-day
   :display {:label "Clear day" :order 4}})

(defaction add-side-dish
  {:from #{:planned} :to :planned
   :input side-input
   :guards [plan-editable has-free-side-slot side-fits-day side-is-listed]
   :safety {:idempotent true :reversible true :confirm false}
   :undo :remove_side_dish
   :handler add-day-side
   :display {:label "Add side dish" :order 6}})

(defaction remove-side-dish
  {:from #{:planned} :to :planned
   :input side-input
   :guards [plan-editable]
   :safety {:idempotent true :reversible true :confirm false}
   :undo :add_side_dish
   :handler remove-day-side
   :display {:label "Remove side dish" :order 7}})

(defresource plan-day
  {:kind :plan_day
   :states [:undecided :planned :eating_out]
   :initial :undecided
   ;; no :terminal: the machine cycles for as long as the plan lives
   ;; (the rotation precedent); an abandoned plan's days simply rest
   :summary "{data.date} · {state}"
   ;; a day is one night's dinner decision, and the family knows it by
   ;; the night — meal_name would read blank on every undecided day,
   ;; which is most of them while the week is being built
   :label-template "Dinner on {data.date}"
   :nav :secondary
   :schema [:map
            [:plan_id {:kind :plan :filter #{:eq}} :waymark/ref]
            [:date {:filter #{:eq :range} :sort :default} :waymark/date]
            [:theme {:optional true} [:maybe [:string {:min 1 :max 50}]]]
            ;; promoted (:filter): the identity join below rides the
            ;; indexed column, and "which days serve this meal" is a
            ;; real query
            [:meal_id {:optional true :kind :meal :label :meal_name
                       :filter #{:eq}
                       :pick {:state "on_list"}}
             [:maybe :waymark/ref]]
            [:meal_name {:optional true} [:maybe [:string {:max 200}]]]
            [:side_dish_id {:optional true :kind :meal
                            :label :side_dish_name
                            :pick {:state "on_list"}}
             [:maybe :waymark/ref]]
            [:side_dish_name {:optional true} [:maybe [:string {:max 200}]]]
            [:second_side_dish_id {:optional true :kind :meal
                                   :label :second_side_dish_name
                                   :pick {:state "on_list"}}
             [:maybe :waymark/ref]]
            [:second_side_dish_name {:optional true}
             [:maybe [:string {:max 200}]]]
            [:eating_out_where {:optional true :x-display {:label "Where"}}
             [:maybe [:string {:max 120}]]]
            ;; the recipe truth, surfaced and promoted (waymark-m6j):
            ;; ?state=planned&recipe_lines=0 IS the hollow-day query
            [:recipe_lines {:optional true :derived recipe-lines
                            :filter #{:eq :range}}
             [:maybe :int]]]
   ;; the identity join (waymark-m6j): this day's meal, as a declared
   ;; edge — what lets recipe_lines read the meal's counted truth and
   ;; lets the maintainer chase a meal_line write back to this row
   :related {:meal {:kind :meal :on [[:meal_id := :id]]}}
   ;; the birth door supplies these; a hand-made day states the same
   :create-schema [:map
                   [:plan_id {:kind :plan
                              :x-display
                              {:label "Week"
                               :help "The meal-plan week this night belongs to."}}
                    :waymark/ref]
                   [:date {:x-display
                           {:label "Night"
                            :help "The calendar day this dinner decision covers — it has to fall inside the plan's week."}}
                    :waymark/date]
                   [:theme {:optional true
                            :x-display
                            {:label "Theme night"
                             :help "The night's kind of food — Tuesday is Mexican, Friday is pizza, Saturday is BBQ; a Sunday stays 'rotating' until somebody picks from the rotation."}}
                    [:maybe [:string {:min 1 :max 50}]]]]
   :create-guards [date-in-plan-range]
   :unique [[:plan_id :date]]
   :filterable {:state #{:eq :in}}
   :display {:title "{data.date}"}
   :deviations
   ["The side-dish input field is side_id, not v9's meal_id — it names the SIDE's meal, and the day already owns a meal_id slot it would falsely mirror."
    "assign_meal/mark_eating_out/clear_day carry no :undo — multi-origin doors have no honest reverse (the inversion rule); clear_day is the acknowledged way back."
    "assign_meal/assign_off_theme/mark_eating_out re-enter their own state (waymark-1pq): a decided day re-decides in place — swapping the meal or the restaurant never routes through undecided; the swap keeps the day's sides (same meal arm), which the one-way sentence owns."
    "The one-of coverage group retired with the promotion: with the eating_out bool gone (state is the fact), the eating-out arm's primary may honestly be absent, so :clears could never fire — each edge's handler nulls what it leaves instead."]
   :links [{:rel "plan" :kind :plan
            :href "/api/plans/{data.plan_id}"
            :summary "The week this day belongs to"}]
   :actions
   {:assign_meal assign-meal
    :assign_off_theme assign-off-theme
    :set_sunday_theme set-sunday-theme
    :mark_eating_out mark-eating-out
    :clear_day clear-day-action
    :add_side_dish add-side-dish
    :remove_side_dish remove-side-dish}})
