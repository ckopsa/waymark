(ns waymark10.fixtures
  "Phase-1 acceptance fixtures: meal and a trimmed plan, ported from
  mealplan9. These are the declarations the checks battery must pass
  and the deliberately-broken variants must fail — by name."
  (:require [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defresource defhandler]]
            [waymark10.types :as t]))

;; ── meal ────────────────────────────────────────────────────────────

(defresource meal
  {:kind :meal
   :states [:suggested :on_list :retired]
   :initial :suggested
   :terminal #{:retired}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 120}]]
            [:themes [:vector [:waymark/vocab {:open true}]]]
            [:recipe {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 8000}]]]]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   :actions
   {:accept {:from #{:suggested} :to :on_list
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Joining the meal list is low-stakes; retiring covers regret."}}
    :decline {:from #{:suggested} :to :retired
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The suggestion is discarded; the AI will not re-suggest it."}}
    :update_recipe {:from #{:on_list} :to :on_list
                    :input [:map [:recipe {:x-display {:widget "prose"}} [:string {:max 8000}]]]
                    :edit {:prefill [:recipe]
                           :draft {:shared true :live true}}
                    :safety {:idempotent true :reversible true :confirm false}}
    :retire {:from #{:on_list} :to :retired
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A retired meal keeps its history; re-adding is a new suggestion."}}}})

;; ── plan (trimmed: days coverage, calendar gate, prep rollup) ───────

(def date-in-plan
  (g/guard {:name :date-in-plan
            :judges [:date]
            :accepts (fn [row] (mapv :date (get-in row [:data :days])))
            :explain "{date} is not a day of this plan."}))

(def calendar-clear
  (g/expr {:name :calendar-clear
           :severity :warning
           :when '(not (data :has_conflicts))
           :explain "{n} calendar conflict(s) overlap this week."
           :vars {:n '(data :calendar_conflicts)}}))

(def plan-started
  (g/expr {:name :plan-started
           :when '(<= (data :start_date) (date-of (now)))
           :explain "The plan starts {start}."
           :vars {:start '(data :start_date)}}))

(defhandler assign-meal [row inp _ctx]
  (update-in row [:data :days]
             (fn [days]
               (mapv #(if (= (:date %) (:date inp))
                        (assoc % :meal_id (:meal_id inp))
                        %)
                     days))))

(defresource plan
  {:kind :plan
   :states [:draft :planned :active :done :abandoned]
   :initial :draft
   :terminal #{:done :abandoned}
   :summary "Week of {data.start_date} · {data.weeks} wk · {state}"
   :schema [:map
            [:start_date :waymark/date]
            [:weeks [:int {:min 1 :max 2}]]
            [:end_date {:optional true} [:maybe :waymark/date]]
            [:days [:vector
                    [:map
                     [:date :waymark/date]
                     [:theme {:optional true} [:maybe [:string {:max 50}]]]
                     [:meal_id {:optional true :kind :meal :label :meal_name}
                      [:maybe :waymark/ref]]
                     [:meal_name {:optional true} [:maybe [:string {:max 200}]]]
                     [:eating_out {:optional true} [:maybe :boolean]]]]]
            [:all_days_covered {:optional true} [:maybe :boolean]]
            [:has_conflicts {:optional true} [:maybe :boolean]]
            [:calendar_conflicts {:optional true} [:maybe :int]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :derived
   {:end_date {:over [:start_date :weeks]
               :expr '(+ (var :start_date) (days (- (* 7 (var :weeks)) 1)))}
    :all_days_covered {:over [:days]
                       :expr '(every [d (var :days)]
                                (or (is-set (get d :meal_id))
                                    (= (get d :eating_out) true)))
                       :explain "Every day needs a meal or an eating-out mark."}
    :has_conflicts {:over [:calendar_conflicts]
                    :expr '(< 0 (var :calendar_conflicts))}}
   :one-of {:days/coverage {:in [:days]
                            :arms {:meal [:meal_id :meal_name]
                                   :eating_out [:eating_out]}
                            :clears true}}
   :part-scopes {:days {:path :days :key :date}}
   :filterable {:state #{:eq :in}
                :start_date #{:eq :range}
                :has_conflicts #{:eq}}
   :sortable {:fields [:start_date] :default "-start_date"}
   :actions
   {:assign_meal {:from #{:draft} :to :draft
                  :place :days
                  :input [:map
                          [:date :waymark/date]
                          [:meal_id {:kind :meal} :waymark/ref]]
                  :guards [date-in-plan]
                  :safety {:idempotent true :reversible false :confirm false}
                  :handler assign-meal
                  :display {:label "Assign meal" :style :primary :order 1}}
    :finalize {:from #{:draft} :to :planned
               :guards [(g/require :all_days_covered
                                   {:remedies [:plan/assign_meal]})
                        calendar-clear]
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Finalize plan" :style :primary}}
    :reopen {:from #{:planned} :to :draft
             :safety {:idempotent true :reversible true :confirm false}}
    :begin {:from #{:planned} :to :active
            :guards [plan-started]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Starting the week reflects the calendar; reopening a started week is a new plan."}}
    :complete {:from #{:active} :to :done
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A completed week is history."}}
    :abandon {:from #{:draft :planned :active} :to :abandoned
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The plan and its prep tasks are discarded for good."}}}})
