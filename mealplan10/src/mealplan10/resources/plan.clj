(ns mealplan10.resources.plan
  "The MealPlan resource: one Tuesday-to-Tuesday week (or two, to save
  grocery trips), each day pre-themed from the weekly calendar.

  The lifecycle mirrors how the family actually plans:

  - draft: assign on-list meals to days (the guard holds each day to
    its theme; Sunday first needs a theme picked from the rotation),
    mark eating-out days, or override off-theme with an explicit
    confirm.
  - planned: every day is covered; the grocery list and prep tasks
    hang off this state.
  - active → done: the week runs its course.

  The day-shaped actions share one part scope declared once;
  date_in_plan / sunday_only / theme_in_rotation are pure
  acceptance-set declarations (the enum and the enforcement are the
  same set); references are :waymark/ref fields whose labels the
  engine maintains (meal_name and the two side-dish names).

  A day is covered by a meal OR by eating out, never both (design §5):
  the one-of group's :clears means setting one arm clears the other —
  the hand-written clearing that v2 repeated in three handlers is
  gone, and \"is this day covered?\" is the all_days_covered derived
  fact, not re-derived. The side slots ride the meal arm (fill
  detection still keys off meal_id) purely so eating-out clears them
  for free, same as meal_name. Two named side slots rather than an
  open list (design tradeoff): scalar fields directly on the day item
  sit at the one level the engine's label maintenance reaches.

  The family calendar is nobody's child — a recital doesn't belong to
  a meal plan, it OVERLAPS one (design 6.0 §1): the :calendar related
  edge joins stored boundaries on our side against the event's date on
  theirs, calendar_conflicts counts through it, and calendar_clear
  WARNS over the stored fact — a week with a recital in it finalizes
  with acknowledgment, not never.

  Recorded punts and deviations, each a sentence:
  - previous_plan resolves at create (batch E, design E7): the
    {:predecessor {:order :start_date}} entry property links each new
    plan to the newest plan starting at or before it — last week is
    data, not date arithmetic; the first plan's stays nil.
  - the :calendar link is declared (edge-cited, badge and all) but v10
    render serves no :links yet — the declaration is carried, the
    envelope's links stay the phase-3 punt.
  - the WeekBoard surface (waymark9 design 6.0 §4) is declared below
    and served at /api/surfaces/week-board/{plan-id} (phase 9b);
    spans still have no v10 spelling — a named punt.
  - v10 summary templates carry no |len filter; the summary names the
    week and its state.
  - cross-resource acceptance sets (theme_in_rotation, meal_fits_day)
    and the cross-resource code guards decline when ctx carries no
    :read/:find — the pure render probe advertises optimistically
    there; every enforcement ctx (and the conformance probe) carries
    the hooks, so enforcement and the walker hold the line."
  (:require [mealplan10.themes :as themes]
            [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defresource defhandler]]
            [waymark10.types :as t])
  (:import (java.time DayOfWeek Instant LocalDate ZoneOffset)))

;; ── guards ──────────────────────────────────────────────────────────
;; One acceptance set: the rendered enum, the per-part availability,
;; and the enforcement. There is no separate body to drift out of sync.

(def date-in-plan
  (g/guard {:name :date-in-plan
            :judges [:date]
            :accepts (fn [row] (mapv :date (get-in row [:data :days])))
            :explain "{date} is not a day of this plan."}))

(def day-has-meal
  (g/guard {:name :day-has-meal
            :judges [:date]
            :accepts (fn [row]
                       (into [] (keep #(when (some? (:meal_id %)) (:date %)))
                             (get-in row [:data :days])))
            :explain "Assign the main meal for {date} before adding a side dish."
            :remedies [:plan/assign_meal]}))

(def has-free-side-slot
  (g/guard {:name :has-free-side-slot
            :judges [:date]
            :accepts (fn [row]
                       (into []
                             (keep #(when (or (nil? (:side_dish_id %))
                                              (nil? (:second_side_dish_id %)))
                                      (:date %)))
                             (get-in row [:data :days])))
            :explain "{date} already has 2 side dishes — remove one first."
            :remedies [:plan/remove_side_dish]}))

(def sunday-only
  (g/guard {:name :sunday-only
            :judges [:date]
            :accepts (fn [row]
                       (into []
                             (keep #(when (= DayOfWeek/SUNDAY
                                             (.getDayOfWeek ^LocalDate (:date %)))
                                      (:date %)))
                             (get-in row [:data :days])))
            :explain "Only Sunday rotates; {date} has a fixed weeknight theme."}))

(def theme-in-rotation
  ;; the linked rotation's themes — resolved through ctx :read so the
  ;; theme field offers real choices; nil (no constraint) when no
  ;; rotation is linked, or when the probe carries no :read
  (g/guard {:name :theme-in-rotation
            :judges [:theme]
            :reads [:rotation]
            :accepts (fn [row ctx]
                       (when-some [read (:read ctx)]
                         (when-some [rid (get-in row [:data :rotation_id])]
                           (when-some [rotation (read :rotation rid)]
                             (vec (get-in rotation [:data :themes]))))))
            :explain "'{theme}' is not in the Sunday rotation. Add it there first."
            :remedies [:rotation/add_theme]}))

;; the recorded 8.0 §5 residue: a verdict that READS (the input's ref,
;; another kind's state) is not pure over (row, input, clock), so it
;; stays code — :reads [:meal] names the dependency honestly
(g/defguard meal-is-listed
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

;; One relation, two consumers (design §5): each bound day's picker
;; offers exactly the meals that serve its night (a rotating Sunday
;; binds nothing), and the invoke enforces membership in the same
;; tuple set.
(def meal-fits-day
  (g/relation
   {:name :meal-fits-day
    :judges [:meal_id :date]
    :reads [:meal]
    :accepts (fn [row ctx]
               (when-some [find (:find ctx)]
                 (let [meals (find :meal {:state :on_list} {:limit 500})]
                   (set (for [d (get-in row [:data :days])
                              :when (not= themes/rotating (:theme d))
                              m meals
                              :when (some #(= (:theme d) %)
                                          (get-in m [:data :themes]))]
                          [(:id m) (:date d)])))))
    :explain "That meal doesn't serve {date}'s theme night. Pick the Sunday theme first if the day still rotates, or assign off-theme with confirmation."
    :remedies [:plan/set_sunday_theme :plan/assign_off_theme]}))

;; The design story's judgment call (design 6.0 appendix §1–§2): a
;; recital on taco night is worth a warning, not a wall — the verdict
;; is a tree over the stored facts.
(def calendar-clear
  (g/expr {:name :calendar-clear
           :severity :warning
           :when '(not (data :has_conflicts))
           :explain "{n} calendar conflict(s) overlap this week — move or cancel them on the calendar itself, or acknowledge to finalize anyway."
           :vars {:n '(data :calendar_conflicts)}}))

;; the clock gate as data; becomes-available-at stays a callable:
;; structured hope is scheduling garnish, not the verdict
(def plan-started
  (g/expr {:name :plan-started
           :when '(<= (data :start_date) (date-of (now)))
           :explain "The plan starts {start}."
           :vars {:start '(data :start_date)}
           :becomes-available-at (fn [row] (get-in row [:data :start_date]))}))

;; the gate judges the stored fact; the refusal reason is one
;; declaration, never re-derived in a handler
(def all-days-covered-gate
  (g/require :all_days_covered
             {:explain "Every day needs a meal or an eating-out mark before finalizing."
              :remedies [:plan/assign_meal :plan/mark_eating_out]}))

;; the open-task rollup gate: the phase-6 count fact (the v10 spelling
;; of waymark9's Owns rollup + rollup_is — recorded: the {:rollups …}
;; edge spelling is subsumed by the count fact, one fact one writer)
(def no-open-tasks
  (g/expr {:name :no-open-tasks
           :when '(= 0 (data :open_tasks))
           :explain "{n} prep task(s) are still open — finish or cancel them before closing the week."
           :vars {:n '(data :open_tasks)}
           :remedies [:prep_task/complete :prep_task/cancel]}))

;; ── handlers ────────────────────────────────────────────────────────

(defn- update-day [row date f]
  (update-in row [:data :days]
             (fn [days]
               (mapv #(if (= (:date %) date) (f %) %) days))))

(defhandler assign-meal [row inp _ctx]
  ;; meal_name is the engine's to maintain (ref label, design §4);
  ;; coverage (one-of) clears the eating-out arm — neither is handler
  ;; work
  (update-day row (:date inp) #(assoc % :meal_id (:meal_id inp))))

(defhandler set-sunday-theme [row inp _ctx]
  (update-day row (:date inp) #(assoc % :theme (:theme inp))))

(defhandler mark-eating-out [row inp _ctx]
  ;; coverage (one-of) clears the meal arm, side slots included — not
  ;; this handler
  (update-day row (:date inp) #(assoc % :eating_out true
                                      :eating_out_where (:where inp))))

(defhandler clear-day [row inp _ctx]
  (update-day row (:date inp)
              #(assoc % :meal_id nil :meal_name nil
                      :side_dish_id nil :side_dish_name nil
                      :second_side_dish_id nil :second_side_dish_name nil
                      :eating_out nil :eating_out_where nil)))

(defhandler add-side-dish [row inp _ctx]
  ;; side names are the engine's to maintain (labeled refs) — same
  ;; machinery as meal_name
  (update-day row (:date inp)
              (fn [d]
                (cond
                  ;; already a side of this day — idempotent no-op
                  (or (= (:side_dish_id d) (:meal_id inp))
                      (= (:second_side_dish_id d) (:meal_id inp))) d
                  (nil? (:side_dish_id d)) (assoc d :side_dish_id (:meal_id inp))
                  :else (assoc d :second_side_dish_id (:meal_id inp))))))

(defhandler remove-side-dish [row inp _ctx]
  (update-day row (:date inp)
              (fn [d]
                (cond
                  (= (:side_dish_id d) (:meal_id inp))
                  (assoc d :side_dish_id nil :side_dish_name nil)
                  (= (:second_side_dish_id d) (:meal_id inp))
                  (assoc d :second_side_dish_id nil :second_side_dish_name nil)
                  :else d))))

;; ── create ──────────────────────────────────────────────────────────

(defn- next-tuesday ^LocalDate [^LocalDate today]
  ;; DayOfWeek: Monday=1 … Sunday=7; the coming Tuesday, today if it is
  (.plusDays today (long (mod (- 2 (.getValue (.getDayOfWeek today))) 7))))

(defn- build-days [^LocalDate start weeks]
  (vec (for [i (range (* 7 (long weeks)))]
         (let [d (.plusDays start (long i))]
           {:date d :theme (themes/weekday-theme d)}))))

(defn- pre-theme
  "Each rotating Sunday takes the rotation's next theme, walking the
  list from position."
  [days rotation]
  (let [ts (vec (get-in rotation [:data :themes]))
        pos (long (get-in rotation [:data :position] 0))]
    (if (empty? ts)
      days
      (loop [in days out [] sundays 0]
        (if-some [d (first in)]
          (if (= themes/rotating (:theme d))
            (recur (rest in)
                   (conj out (assoc d :theme (nth ts (mod (+ pos sundays)
                                                          (count ts)))))
                   (inc sundays))
            (recur (rest in) (conj out d) sundays))
          out)))))

(defn- plan-on-create
  "A blank start date means the coming Tuesday; a blank rotation_id
  means the most recently activated active rotation; each rotating
  Sunday is pre-themed from it, walking the list from position. With
  no rotation at all, Sundays stay rotating and the old flow
  (set_sunday_theme first) still applies. previous_plan arrives
  already resolved — the engine's predecessor step runs before this
  hook (batch E, design E7)."
  [row ctx]
  (let [today (.toLocalDate (.atOffset ^Instant (:now ctx) ZoneOffset/UTC))
        start (or (get-in row [:data :start_date]) (next-tuesday today))
        weeks (or (get-in row [:data :weeks]) 1)
        rid (or (get-in row [:data :rotation_id])
                (let [active ((:find ctx) :rotation {:state :active}
                              {:limit 100})]
                  (when (seq active)
                    (:id (apply max-key
                                (fn [r]
                                  (if-some [^Instant at (get-in r [:data :activated_at])]
                                    (.toEpochMilli at)
                                    0))
                                active)))))
        rotation (when rid ((:read ctx) :rotation rid))
        days (cond-> (build-days start weeks)
               rotation (pre-theme rotation))]
    (update row :data assoc
            :start_date start :weeks weeks :rotation_id rid :days days)))

;; ── the declaration ─────────────────────────────────────────────────

(def day-input [:map [:date :waymark/date]])

(def assign-input
  [:map
   [:date :waymark/date]
   [:meal_id {:kind :meal} :waymark/ref]])

(def side-dish-input
  ;; same picker as the main meal: a side must also be an on-list meal
  ;; that serves the night's theme
  [:map
   [:date :waymark/date]
   [:meal_id {:kind :meal} :waymark/ref]])

(defresource plan
  {:kind :plan
   :states [:draft :planned :active :done :abandoned]
   :initial :draft
   :terminal #{:done :abandoned}
   :summary "Week of {data.start_date} · {data.weeks} wk · {state}"
   :schema [:map
            [:start_date {:x-display {:label "Start date"}} :waymark/date]
            [:weeks [:int {:min 1 :max 2}]]
            ;; the week's far boundary as a stored fact: derived from
            ;; our own fields, materialized into a promoted column —
            ;; exactly what lets it serve as the calendar edge's join key
            [:end_date {:optional true} [:maybe :waymark/date]]
            [:rotation_id {:optional true :kind :rotation}
             [:maybe :waymark/ref]]
            ;; period chaining (design E7, batch E): the engine
            ;; resolves the newest plan starting at or before this one
            ;; at create — last week is data, not date arithmetic
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
   ;; the create form: only the decisions a human actually makes —
   ;; days derive from the theme calendar, so they never appear;
   ;; blank start date = the coming Tuesday, blank rotation = the
   ;; active one
   :create-schema [:map
                   [:start_date {:optional true} [:maybe :waymark/date]]
                   [:weeks {:optional true} [:maybe [:int {:min 1 :max 2}]]]
                   [:rotation_id {:optional true :kind :rotation}
                    [:maybe :waymark/ref]]
                   [:notes {:optional true :x-display {:widget "prose"}}
                    [:maybe [:string {:max 2000}]]]]
   :on-create plan-on-create
   :derived
   {:end_date {:over [:start_date :weeks]
               :expr '(+ (var :start_date) (days (- (* 7 (var :weeks)) 1)))}
    ;; the coverage rollup as a declared fact (design §2): one
    ;; definition — the finalize gate judges it and the refusal reason
    ;; rides the gate's declaration
    :all_days_covered {:over [:days]
                       :expr '(every [d (var :days)]
                                     (or (is-set (get d :meal_id))
                                         (= (get d :eating_out) true)))
                       :explain "Every day needs a meal or an eating-out mark before finalizing."}
    ;; facts over the relation (design 6.0 §2): the conflict count
    ;; badges the calendar link, filters the plan list, and feeds the
    ;; finalize warning; nobody re-joins the calendar in a handler
    :calendar_conflicts {:count {:related :calendar
                                 :where {:kind #{"blocking"}
                                         :state #{"fresh" "stale"}}}}
    :has_conflicts {:over [:calendar_conflicts]
                    :expr '(< 0 (var :calendar_conflicts))}
    ;; the open-task rollup (design E4) as a phase-6 count fact
    :open_tasks {:count {:owns :prep_task
                         :where {:state #{"pending" "scheduled"}}}}}
   ;; the relation the plan decision actually consults (design 6.0
   ;; §1): stored boundaries on our side against the event's date on
   ;; theirs; both directions promoted, which is what lets the
   ;; maintainer keep the facts honest in the same call as any event
   ;; write
   :related {:calendar {:kind :event
                        :on [[:start_date :<= :date]
                             [:end_date :>= :date]]}}
   ;; one ownership edge, two consumers (design E4): abandoning the
   ;; plan cancels its open prep tasks (cascade), and the open-task
   ;; count above gates complete
   :owns [{:kind :prep_task :via :plan_id :on {:abandon :cancel}}]
   ;; the edge-cited link (design 6.0 §1) — declared and checked;
   ;; envelope link rendering stays v10's phase-3 punt
   :links [{:rel "calendar" :edge :calendar :badge :calendar_conflicts
            :summary "What the family already has planned"}]
   ;; the one place per-day placement is declared (design §3)
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
                  :input assign-input :place :days
                  :guards [date-in-plan meal-fits-day]
                  :safety {:idempotent true :reversible false :confirm false}
                  :handler assign-meal
                  :display {:label "Assign meal" :style :primary :order 1}}
    :assign_off_theme {:from #{:draft} :to :draft
                       :input assign-input :place :days
                       :guards [date-in-plan meal-is-listed]
                       :safety {:idempotent true :reversible false
                                :confirm true
                                :consequence "The day gets a meal that does not match its theme night."}
                       :handler assign-meal
                       :display {:label "Assign off-theme" :order 5}}
    :set_sunday_theme {:from #{:draft} :to :draft
                       :input [:map
                               [:date :waymark/date]
                               [:theme [:string {:min 1 :max 50}]]]
                       :place :days
                       :guards [date-in-plan sunday-only theme-in-rotation]
                       :safety {:idempotent true :reversible false
                                :confirm false}
                       :handler set-sunday-theme
                       :display {:label "Pick Sunday theme" :order 2}}
    :mark_eating_out {:from #{:draft} :to :draft
                      :input [:map
                              [:date :waymark/date]
                              [:where {:optional true
                                       :x-display {:label "Where"}}
                               [:maybe [:string {:max 120}]]]]
                      :place :days
                      :guards [date-in-plan]
                      :safety {:idempotent true :reversible false
                               :confirm false}
                      :handler mark-eating-out
                      :display {:label "Eating out" :order 3}}
    :clear_day {:from #{:draft} :to :draft
                :input day-input :place :days
                :guards [date-in-plan]
                :safety {:idempotent true :reversible false :confirm false}
                :handler clear-day
                :display {:label "Clear day" :order 4}}
    :add_side_dish {:from #{:draft} :to :draft
                    :input side-dish-input :place :days
                    :guards [date-in-plan day-has-meal has-free-side-slot
                             meal-fits-day meal-is-listed]
                    :safety {:idempotent true :reversible true :confirm false}
                    :handler add-side-dish
                    :display {:label "Add side dish" :order 6}}
    :remove_side_dish {:from #{:draft} :to :draft
                       :input side-dish-input :place :days
                       :guards [date-in-plan]
                       :safety {:idempotent true :reversible true
                                :confirm false}
                       :handler remove-side-dish
                       :display {:label "Remove side dish" :order 7}}
    :finalize {:from #{:draft} :to :planned
               :guards [all-days-covered-gate calendar-clear]
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Finalize plan" :style :primary :order 1}}
    :reopen {:from #{:planned} :to :draft
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Reopen" :order 2}}
    :begin {:from #{:planned} :to :active
            :guards [plan-started]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Starting the week only reflects the calendar; nothing is lost and the plan stays editable through its days."}
            :display {:label "Start the week" :style :primary :order 1}}
    :complete {:from #{:active} :to :done
               :guards [no-open-tasks]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Completing records a finished week; the plan remains readable as history."}
               :display {:label "Week done" :style :primary :order 1}}
    :abandon {:from #{:draft :planned :active} :to :abandoned
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The plan is discarded for good; its open prep tasks are cancelled; its days and any grocery list stay readable as records."}
              :display {:label "Abandon plan" :style :danger :order 9}}}})

;; ── the week's decision surface (phase 9b, waymark9 mealplan9's
;;    WeekBoard) ──────────────────────────────────────────────────────

(def week-board
  "The composition the finalize decision consults, declared once: the
  plan anchor with the family calendar co-present through the declared
  :calendar edge, finalize showcased, has_conflicts nominated for the
  dashboard's attention. Served read-only at
  /api/surfaces/week-board/{plan-id}."
  {:name :week-board
   :anchor :plan
   :members [{:name :calendar :kind :event :related :calendar}]
   :showcase [:finalize]
   :attention {:has_conflicts true}})
