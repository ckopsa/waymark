(ns waymark10.fixtures
  "Phase-1 acceptance fixtures: meal and a trimmed plan, ported from
  mealplan9. These are the declarations the checks battery must pass
  and the deliberately-broken variants must fail — by name.

  Spelled in the batch-G declaration style: field-scoped law
  colocated on the schema entries it governs, the actions worth
  naming def'd, one safety value named and cited. The law is
  unchanged — normalization projects this spelling onto the same map
  as the split spelling, and batch_g_invariance_test pins both kinds'
  fingerprint hashes byte-identical to it."
  (:require [waymark10.declare :refer [defaction defderived]]
            [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defresource defhandler]]
            [waymark10.types :as t]))

;; a named safety value: reference is explicit declaration — the map
;; is spelled once, in full, and cited by name, never inferred
(def routine
  {:idempotent true :reversible true :confirm false})

;; ── meal ────────────────────────────────────────────────────────────

(defaction update-recipe
  {:from #{:on_list} :to :on_list
   :input [:map [:recipe {:x-display {:widget "prose"}} [:string {:max 8000}]]]
   :edit {:prefill [:recipe]
          :draft {:shared true :live true}}
   :safety routine
   :handler (fn [row inp _ctx]
              (assoc-in row [:data :recipe] (:recipe inp)))})

(defresource meal
  {:kind :meal
   :states [:suggested :on_list :retired]
   :initial :suggested
   :terminal #{:retired}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name {:sort :default} [:string {:min 1 :max 120}]]
            [:themes [:vector [:waymark/vocab {:open true}]]]
            [:recipe {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 8000}]]]]
   :filterable {:state #{:eq :in}}
   :actions
   {:accept {:from #{:suggested} :to :on_list
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Joining the meal list is low-stakes; retiring covers regret."}}
    :decline {:from #{:suggested} :to :retired
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The suggestion is discarded; the AI will not re-suggest it."}}
    :update_recipe update-recipe
    :retire {:from #{:on_list} :to :retired
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A retired meal keeps its history; re-adding is a new suggestion."}}}})

;; ── plan (trimmed: days coverage, calendar gate, prep rollup) ───────

(def date-in-plan
  ;; the :accepts carries its own printed form (waymark-j82): an
  ;; acceptance fn is fingerprinted exactly like a :check, and a
  ;; formless one would file its law under its address and warn — the
  ;; fixtures load with zero warnings, and mean it
  (g/guard {:name :date-in-plan
            :judges [:date]
            :accepts (with-meta
                       (fn [row] (mapv :date (get-in row [:data :days])))
                       {:waymark10/form
                        '(fn [row] (mapv :date (get-in row [:data :days])))})
            :explain "{date} is not a day of this plan."}))

(defn calendar-clear-guard
  "The calendar gate with its law as a parameter — phase 5's admission
  test flips exactly this expression leaf."
  [when-form]
  (g/expr {:name :calendar-clear
           :severity :warning
           :when when-form
           :explain "{n} calendar conflict(s) overlap this week."
           :vars {:n '(data :calendar_conflicts)}}))

(def calendar-clear
  (calendar-clear-guard '(not (data :has_conflicts))))

(def plan-started
  (g/expr {:name :plan-started
           :when '(<= (data :start_date) (date-of (now)))
           :explain "The plan starts {start}."
           :vars {:start '(data :start_date)}}))

;; hoisted so its :check fn has ONE identity per process: two boots of
;; the same declaration must fingerprint identically, and a code
;; guard's stopgap hash is its printed fn object
(def all-days-covered-gate
  (g/require :all_days_covered {:remedies [:plan/assign_meal]}))

(defhandler assign-meal [row inp _ctx]
  (update-in row [:data :days]
             (fn [days]
               (mapv #(if (= (:date %) (:date inp))
                        (assoc % :meal_id (:meal_id inp))
                        %)
                     days))))

;; the derived facts, def'd — each lands on its own schema entry below
(defderived end-date
  {:over [:start_date :weeks]
   :expr '(+ (var :start_date) (days (- (* 7 (var :weeks)) 1)))})

(defderived all-days-covered
  {:over [:days]
   :expr '(every [d (var :days)]
            (or (is-set (get d :meal_id))
                (= (get d :eating_out) true)))
   :explain "Every day needs a meal or an eating-out mark."})

(defderived has-conflicts
  {:over [:calendar_conflicts]
   :expr '(< 0 (var :calendar_conflicts))})

(defaction assign-meal-action
  {:from #{:draft} :to :draft
   :place :days
   :input [:map
           [:date :waymark/date]
           [:meal_id {:kind :meal} :waymark/ref]]
   :guards [date-in-plan]
   :safety {:idempotent true :reversible false :confirm false}
   :handler assign-meal
   :display {:label "Assign meal" :style :primary :order 1}})

;; an action group: the plan's closing pair, merged into :actions
(def closing-actions
  {:complete {:from #{:active} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A completed week is history."}}
   :abandon {:from #{:draft :planned :active} :to :abandoned
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The plan and its prep tasks are discarded for good."}}})

(defn- plan-map [calendar-gate]
  {:kind :plan
   :states [:draft :planned :active :done :abandoned]
   :initial :draft
   :terminal #{:done :abandoned}
   :summary "Week of {data.start_date} · {data.weeks} wk · {state}"
   :schema [:map
            [:start_date {:filter #{:eq :range} :sort :default-desc}
             :waymark/date]
            [:weeks [:int {:min 1 :max 2}]]
            [:end_date {:optional true :derived end-date}
             [:maybe :waymark/date]]
            [:days {:part-scope {:key :date}}
             [:vector
              [:map
               [:date :waymark/date]
               [:theme {:optional true} [:maybe [:string {:max 50}]]]
               [:meal_id {:optional true :kind :meal :label :meal_name}
                [:maybe :waymark/ref]]
               [:meal_name {:optional true} [:maybe [:string {:max 200}]]]
               [:eating_out {:optional true} [:maybe :boolean]]]]]
            [:all_days_covered {:optional true :derived all-days-covered}
             [:maybe :boolean]]
            [:has_conflicts {:optional true :derived has-conflicts :filter #{:eq}}
             [:maybe :boolean]]
            [:calendar_conflicts {:optional true} [:maybe :int]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :one-of {:days/coverage {:in [:days]
                            :arms {:meal [:meal_id :meal_name]
                                   :eating_out [:eating_out]}
                            :clears true}}
   :filterable {:state #{:eq :in}}
   :actions
   (merge {:assign_meal assign-meal-action
           :finalize {:from #{:draft} :to :planned
                      :guards [all-days-covered-gate calendar-gate]
                      :safety routine
                      :display {:label "Finalize plan" :style :primary}}
           :reopen {:from #{:planned} :to :draft
                    :safety routine}
           :begin {:from #{:planned} :to :active
                   :guards [plan-started]
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "Starting the week reflects the calendar; reopening a started week is a new plan."}}}
          closing-actions)})

(defn plan-resource
  "The plan declaration, optionally with a different calendar-gate law
  ({:calendar-when form}) — the single-leaf judgment flip the phase-5
  admission test deploys."
  [& [{:keys [calendar-when]}]]
  (r/resource
   (plan-map (if calendar-when
               (calendar-clear-guard calendar-when)
               calendar-clear))))

(def plan (plan-resource))
