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

  The days are plan_day ROWS (mealplan10.resources.plan-day), born
  WITH the plan through the ctx birth door (design §24) — promoted
  from embedded data the moment each day earned its own machine:
  state = the decision (undecided → planned / eating_out), coverage
  = zero undecided children (the composed all_days_covered fact over
  the undecided_days count), and every day-shaped action lives on the
  day itself, its input finally free of the :date the part key used
  to demand. The one-of coverage group retired with the promotion
  (its reasoning is recorded on plan_day); one day per (plan, date)
  is a declared :unique the index enforces.

  The family calendar is nobody's child — a recital doesn't belong to
  a meal plan, it OVERLAPS one (design 6.0 §1): the :calendar related
  edge joins stored boundaries on our side against the event's date on
  theirs, calendar_conflicts counts through it, and calendar_clear
  WARNS over the stored fact — a week with a recital in it finalizes
  with acknowledgment, not never.

  Recipe coverage (waymark-m6j): a week could be built from meals
  with EMPTY recipes — zero meal_line rows — and the grocery compile
  would buy nothing for them, silently. The truth is stored, LIVE
  facts now: each day's recipe_lines (a :sum through plan_day's
  :meal identity join) and this plan's days_without_recipe (:count
  over owned planned days where recipe_lines = 0), both maintained
  by the engine along the meal_line → meal → plan_day → plan chain
  in the same call as any recipe write. recipes-attached judges the
  stored fact at finalize — calendar-clear's exact posture: a
  :warning whose sentence carries the count, so a hollow week
  finalizes with acknowledgment, never never. Considered and
  rejected, each for lying: (a) stamping meal_has_recipe onto
  plan_day in the assign handlers — an as-of-assign copy of
  meal.has_recipe that a recipe filled in (or emptied) LATER never
  flips: a second writer of one truth, drifting both directions, its
  staleness a standing deviation; (b) a live code guard walking days
  → meals over ctx :find at enforcement — truthful at the door but
  storing nothing, so the plan's own envelope could not show,
  filter, or badge the gap it warns about. The maintained facts are
  (b)'s liveness with (a)'s surface: one writer, no stamp, and the
  guard stays a declarative expr over stored data.

  The grocery rollups (est_grocery_cost_cents / priced_grocery_items
  / total_grocery_items) sum the week's LIVE lists — every state but
  discarded, spelled as the explicit allow-set on each :sum. Era 4's
  per-trip lists must all count (there is no \"the one active list\"),
  but a discarded mistake or superseded compile counts nothing; each
  list's own derived totals stay its one writer, and the plan only
  sums them.

  Spelled in the batch-H declaration style: the lifecycle is :flow
  rows, finalize/reopen and the side-dish pair declare each other as
  :undo (the engine verifies the pointers — \"honestly reversible\"
  is graph-checked instead of a comment), and abandon's three rows
  cite one def'd opts value. Recorded deviations, each a sentence:
  the day-shaped actions stay a plain group merged beside the flow
  (six rows of place/guards/handler opts would bury the table, and
  mark_eating_out's optional :where has no :args spelling anyway);
  :states stays spelled because the flow rows are not the whole
  machine; no guard adopts defguard — calendar-clear and plan-started
  alias their sentence placeholders ({n}, {start}), plan-started
  carries :becomes-available-at, and no-open-tasks carries :remedies,
  none of which the sentence-first sugar can mint. Each derived fact
  is still def'd and rides its own entry, filter/sort law rides the
  entries it governs (batch G). The law is unchanged —
  mealplan10.style-invariance-test pins this kind's fingerprint hash
  byte-identical to the split spelling.

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
    :read/:find — mealplan10 boots :probe-reads, so GET's render
    probe carries the hooks and folds the acceptance sets, while
    invoke-response envelopes stay the optimistic probe; every
    enforcement ctx (and the conformance probe) carries the hooks, so
    enforcement and the walker hold the line."
  (:require [mealplan10.themes :as themes]
            [waymark10.dsl :refer [defaction defderived defguardfn
                                   defresource defhandler expr-guard
                                   guard relation require-fact]]
            [waymark10.types :as t])
  (:import (java.time DayOfWeek Instant LocalDate ZoneOffset)))

;; ── guards ──────────────────────────────────────────────────────────
;; One acceptance set: the rendered enum, the per-part availability,
;; and the enforcement. There is no separate body to drift out of sync.

;; The design story's judgment call (design 6.0 appendix §1–§2): a
;; recital on taco night is worth a warning, not a wall — the verdict
;; is a tree over the stored facts.
(def calendar-clear
  (expr-guard {:name :calendar-clear
               :severity :warning
               :when '(not (data :has_conflicts))
               :explain "{n} calendar conflict(s) overlap this week — move or cancel them on the calendar itself, or acknowledge to finalize anyway."
               :vars {:n '(data :calendar_conflicts)}}))

;; the hollow-week warning (waymark-m6j): calendar-clear's posture
;; over the stored days_without_recipe fact — a plan whose meals have
;; no recipes finalizes with acknowledgment, not never. Nil-safe by
;; the grammar (an ordering claim over nil is false), so a
;; pre-backfill row warns nothing.
(def recipes-attached
  (expr-guard {:name :recipes-attached
               :severity :warning
               :when '(not (< 0 (data :days_without_recipe)))
               :explain "{n} planned day(s) have meals with no recipe — a grocery compile would buy nothing for them. Add the meals' ingredient lines or reassign those days, or acknowledge to finalize anyway."
               :vars {:n '(data :days_without_recipe)}
               :remedies [:meal_line/create :plan_day/assign_meal]}))

;; the clock gate as data; becomes-available-at stays a callable:
;; structured hope is scheduling garnish, not the verdict
(def plan-started
  (expr-guard {:name :plan-started
               :when '(<= (data :start_date) (date-of (now)))
               :explain "The plan starts {start}."
               :vars {:start '(data :start_date)}
               :becomes-available-at (fn [row] (get-in row [:data :start_date]))}))

;; the gate judges the stored fact; the refusal reason is one
;; declaration, never re-derived in a handler
(def all-days-covered-gate
  (require-fact :all_days_covered
                {:explain "Every day needs a meal or an eating-out mark before finalizing."
                 :remedies [:plan_day/assign_meal :plan_day/mark_eating_out]}))

;; the open-task rollup gate: the phase-6 count fact (the v10 spelling
;; of waymark9's Owns rollup + rollup_is — recorded: the {:rollups …}
;; edge spelling is subsumed by the count fact, one fact one writer)
(def no-open-tasks
  (expr-guard {:name :no-open-tasks
               :when '(= 0 (data :open_tasks))
               :explain "{n} prep task(s) are still open — finish or cancel them before closing the week."
               :vars {:n '(data :open_tasks)}
               :remedies [:prep_task/complete :prep_task/cancel]}))

;; ── handlers ────────────────────────────────────────────────────────

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
    ;; the week is born WITH its days: each one a plan_day row through
    ;; the ctx :create door (deferred during :on-create), landing right
    ;; after this row's own insert — pre-themed, date-ordered, counted
    ;; in the 201
    (doseq [d days]
      ((:create ctx) :plan_day {:plan_id (:id row)
                                :date (:date d)
                                :theme (:theme d)}))
    (update row :data assoc
            :start_date start :weeks weeks :rotation_id rid)))

;; ── derived facts, def'd — each rides its own schema entry ──────────

(defderived end-date
  {:over [:start_date :weeks]
   :expr '(+ (var :start_date) (days (- (* 7 (var :weeks)) 1)))})

;; the coverage rollup as a declared fact (design §2): one definition
;; — the finalize gate judges it and the refusal reason rides the
;; gate's declaration
(defderived total-days
  {:count {:owns :plan_day}})

(defderived undecided-days
  {:count {:owns :plan_day :where {:state #{"undecided"}}}})

(defderived all-days-covered
  ;; composed over the owned rows' states: covered = zero undecided —
  ;; the day's MACHINE is the coverage now (state = the decision)
  {:over [:undecided_days]
   :expr '(= 0 (var :undecided_days))
   :explain "Every day needs a meal or an eating-out mark before finalizing."})

;; the hollow-day count (waymark-m6j): planned days whose meal
;; carries zero recipe lines, read off the day's own stored
;; recipe_lines fact — live end to end (the maintainer chains
;; meal_line → meal → plan_day → here), so a recipe filled in after
;; assignment flips this in the same commit
(defderived days-without-recipe
  {:count {:owns :plan_day :where {:state #{"planned"}
                                   :recipe_lines #{0}}}})

;; facts over the relation (design 6.0 §2): the conflict count badges
;; the calendar link, filters the plan list, and feeds the finalize
;; warning; nobody re-joins the calendar in a handler
(defderived calendar-conflicts
  {:count {:related :calendar
           :where {:kind #{"blocking"}
                   :state #{"fresh" "stale"}}}})

;; the open-task rollup (design E4) as a phase-6 count fact
(defderived open-tasks
  {:count {:owns :prep_task
           :where {:state #{"pending" "scheduled"}}}})

;; the money rollups (pantry-prices era): the week's grocery cost,
;; summed over the owned LIVE lists' own derived totals — child
;; writes (a discard among them) flip these in the same commit. The
;; :where is the explicit allow-set (draft/ready/done — every state
;; but discarded, the meal-est-cost spelling): era 4's per-trip lists
;; must all sum, so there is no "the one active list" — but a
;; discarded mistake leaves the totals the moment its door closes.
(defderived est-grocery-cost-cents
  {:sum {:owns :grocery_list :where {:state #{"draft" "ready" "done"}}
         :of :estimated_total_cents}})

(defderived priced-grocery-items
  {:sum {:owns :grocery_list :where {:state #{"draft" "ready" "done"}}
         :of :priced_items}})

(defderived total-grocery-items
  {:sum {:owns :grocery_list :where {:state #{"draft" "ready" "done"}}
         :of :total_items}})

;; ── the actions ─────────────────────────────────────────────────────

(defresource plan
  {:kind :plan
   :states [:draft :planned :active :done :abandoned]
   :initial :draft
   :terminal #{:done :abandoned}
   :summary "Week of {data.start_date} · {data.weeks} wk · {state}"
   :schema [:map
            [:start_date {:filter #{:eq :range} :sort :default-desc
                          :x-display {:label "Start date"}}
             :waymark/date]
            [:weeks [:int {:min 1 :max 2}]]
            ;; the week's far boundary as a stored fact: derived from
            ;; our own fields, materialized into a promoted column —
            ;; exactly what lets it serve as the calendar edge's join key
            [:end_date {:optional true :derived end-date
                        :filter #{:eq :range}}
             [:maybe :waymark/date]]
            [:rotation_id {:optional true :kind :rotation}
             [:maybe :waymark/ref]]
            ;; period chaining (design E7, batch E): the engine
            ;; resolves the newest plan starting at or before this one
            ;; at create — last week is data, not date arithmetic
            [:previous_plan {:optional true :kind :plan
                             :predecessor {:order :start_date}}
             [:maybe :waymark/ref]]
            ;; the week's days are plan_day ROWS (state = the
            ;; decision); the counts below are their engine-maintained
            ;; shadow on the parent
            [:total_days {:optional true :derived total-days}
             [:maybe :int]]
            [:undecided_days {:optional true :derived undecided-days}
             [:maybe :int]]
            [:all_days_covered {:optional true :derived all-days-covered}
             [:maybe :boolean]]
            ;; the hollow-day count, surfaced and promoted
            ;; (waymark-m6j): the plan tells you it's hollow before
            ;; finalize warns about it
            [:days_without_recipe {:optional true
                                   :derived days-without-recipe
                                   :filter #{:eq :range}}
             [:maybe :int]]
            [:calendar_conflicts {:optional true :derived calendar-conflicts
                                  :filter #{:eq :range}}
             [:maybe :int]]
            ;; a one-liner fact stays inline: is-there-anything, over
            ;; the count fact above
            [:has_conflicts {:optional true :filter #{:eq}
                             :derived {:over [:calendar_conflicts]
                                       :expr '(< 0 (var :calendar_conflicts))}}
             [:maybe :boolean]]
            [:open_tasks {:optional true :derived open-tasks}
             [:maybe :int]]
            [:est_grocery_cost_cents {:optional true
                                      :derived est-grocery-cost-cents
                                      :x-display {:widget "money"
                                                  :label "Est. grocery cost"}}
             [:maybe :int]]
            [:priced_grocery_items {:optional true
                                    :derived priced-grocery-items}
             [:maybe :int]]
            [:total_grocery_items {:optional true
                                   :derived total-grocery-items}
             [:maybe :int]]
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
   ;; the relation the plan decision actually consults (design 6.0
   ;; §1): stored boundaries on our side against the event's date on
   ;; theirs; both directions promoted, which is what lets the
   ;; maintainer keep the facts honest in the same call as any event
   ;; write
   :related {:calendar {:kind :event
                        :on [[:start_date :<= :date]
                             [:end_date :>= :date]]}}
   ;; ownership edges (design E4): abandoning the plan cancels its
   ;; open prep tasks (cascade), the open-task count gates complete,
   ;; and the week's grocery lists roll their totals up here
   :owns [{:kind :prep_task :via :plan_id :on {:abandon :cancel}}
          {:kind :grocery_list :via :plan_id}
          ;; no :on — an abandoned plan's days stay readable exactly
          ;; as abandon's sentence promises (they are the record)
          {:kind :plan_day :via :plan_id}]
   ;; the edge-cited link (design 6.0 §1) — declared, checked, and
   ;; rendered with its badge
   :links [{:rel "calendar" :edge :calendar :badge :calendar_conflicts
            :summary "What the family already has planned"}
           {:rel "groceries" :owns :grocery_list :embed true
            :badge :total_grocery_items
            :summary "The week's grocery lists and what they'll cost"}
           {:rel "days" :owns :plan_day :embed true
            :badge :total_days
            :summary "The week's day decisions"}]
   :filterable {:state #{:eq :in}}
   :display {:title "Meal plan — week of {data.start_date}"}
   ;; the lifecycle as flow rows, each wearing its safety story —
   ;; finalize and reopen name each other as the verified way back,
   ;; and abandon's three origins cite one opts value (its rows must
   ;; agree on everything but :confirm, so agreement is citation)
   :flow
   (let [discard {:confirm "The plan is discarded for good; its open prep tasks are cancelled; its days and any grocery list stay readable as records."
                  ;; the cascade IS a touch — advertised (design §24)
                  :touches [{:kind :prep_task :action :cancel :may true}]
                  :display {:label "Abandon plan" :style :danger :order 9}}]
     [[:draft   :finalize :planned
       {:requires [all-days-covered-gate calendar-clear recipes-attached]
        :undo :reopen
        :display {:label "Finalize plan" :style :primary :order 1}}]
      [:planned :reopen   :draft
       {:undo :finalize
        :display {:label "Reopen" :order 2}}]
      [:planned :begin    :active
       {:requires [plan-started]
        :one-way "Starting the week only reflects the calendar; nothing is lost and the plan stays editable through its days."
        :display {:label "Start the week" :style :primary :order 1}}]
      [:active  :complete :done
       {:requires [no-open-tasks]
        :one-way "Completing records a finished week; the plan remains readable as history."
        :display {:label "Week done" :style :primary :order 1}}]
      [:draft   :abandon  :abandoned discard]
      [:planned :abandon  :abandoned discard]
      [:active  :abandon  :abandoned discard]])})

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
   :members [{:name :calendar :kind :event :related :calendar}
            {:name :days :owns :plan_day}]
   :showcase [:finalize]
   :attention {:has_conflicts true}})
