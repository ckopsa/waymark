(ns waymark10.batch-g-invariance-test
  "Two spellings, one law — the proof. The fixtures now live in the
  colocated/def'd style; this suite keeps the OLD split spellings
  alive (constructed here, sharing the fixtures' guard and handler
  objects so the imperative residue hashes as itself) and pins both
  kinds' fingerprint hashes byte-identical. Then the property: any
  small declaration rendered colocated and split normalizes to the
  same map and the same fingerprint — a pure style refactor mints
  zero revisions."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [waymark10.fingerprint :as fp]
            [waymark10.fixtures :as fx]
            [waymark10.resource :as r :refer [defhandler]]))

(defn- hash-of [rmap]
  (fp/fingerprint-hash (r/fingerprint (r/normalize-resource rmap))))

;; ── the old split spellings, byte-for-byte from the pre-G fixtures ──

;; the meal's update_recipe handler, re-minted with the identical
;; canonical form the fixture's inline (fn …) captured — the
;; fingerprint hashes the form, never the object
(defhandler update-recipe [row inp _ctx]
  (assoc-in row [:data :recipe] (:recipe inp)))

(def old-meal
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
                    :safety {:idempotent true :reversible true :confirm false}
                    :handler update-recipe}
    :retire {:from #{:on_list} :to :retired
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A retired meal keeps its history; re-adding is a new suggestion."}}}})

;; guards and the assign handler are the fixtures' own objects: a code
;; guard without a stateable form hashes by printed fn identity, so the
;; comparison must share instances — exactly what a style refactor does
(def old-plan
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
                  :guards [fx/date-in-plan]
                  :safety {:idempotent true :reversible false :confirm false}
                  :handler fx/assign-meal
                  :display {:label "Assign meal" :style :primary :order 1}}
    :finalize {:from #{:draft} :to :planned
               :guards [fx/all-days-covered-gate fx/calendar-clear]
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Finalize plan" :style :primary}}
    :reopen {:from #{:planned} :to :draft
             :safety {:idempotent true :reversible true :confirm false}}
    :begin {:from #{:planned} :to :active
            :guards [fx/plan-started]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Starting the week reflects the calendar; reopening a started week is a new plan."}}
    :complete {:from #{:active} :to :done
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A completed week is history."}}
    :abandon {:from #{:draft :planned :active} :to :abandoned
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The plan and its prep tasks are discarded for good."}}}})

(deftest the-meal-fingerprint-survived-the-style-refactor
  (is (= (hash-of old-meal)
         (fp/fingerprint-hash (r/fingerprint fx/meal)))
      "byte-identical hashes: split and colocated/def'd meal are one law"))

(deftest the-plan-fingerprint-survived-the-style-refactor
  (is (= (r/normalize-resource old-plan) (into {} fx/plan))
      "the normalized maps are the same value")
  (is (= (hash-of old-plan)
         (fp/fingerprint-hash (r/fingerprint fx/plan)))
      "byte-identical hashes: split and colocated/def'd plan are one law"))

;; ── the property: any small declaration, both ways ──────────────────

(def ^:private field-pool [:alpha :beta :gamma])

(def gen-field-law
  "Per-field law: maybe filterable, maybe sortable (possibly claiming
  the default), maybe the derived fact's input."
  (gen/hash-map
   :filter (gen/elements [nil #{:eq} #{:eq :in} #{:eq :range}])
   :sort (gen/elements [nil true :default :default-desc])))

(def gen-declaration
  (gen/let [nfields (gen/choose 1 3)
            laws (gen/vector gen-field-law 3)
            derived? gen/boolean
            scoped? gen/boolean]
    (let [fields (vec (take nfields field-pool))
          ;; at most one default: later claims soften to a plain mark
          laws (first
                (reduce (fn [[acc seen] law]
                          (let [claim? (#{:default :default-desc} (:sort law))]
                            [(conj acc (if (and claim? seen)
                                         (assoc law :sort true)
                                         law))
                             (or seen (boolean claim?))]))
                        [[] false]
                        (take nfields laws)))
          law-of (zipmap fields laws)]
      {:fields fields
       :law law-of
       :derived (when derived?
                  {:total {:over [(first fields)]
                           :expr (list '+ (list 'var (first fields)) 1)}})
       :scoped? scoped?})))

(defn- render
  "One abstract declaration → a resource map, colocated or split."
  [{:keys [fields law derived scoped?]} style]
  (let [colocated? (= style :colocated)
        entry (fn [f]
                (let [{:keys [filter sort]} (law f)
                      props (when colocated?
                              (cond-> {}
                                filter (assoc :filter filter)
                                sort (assoc :sort sort)))]
                  (if (seq props) [f props :int] [f :int])))
        schema (cond-> (into [:map] (map entry) fields)
                 derived (conj [:total {:optional true} [:maybe :int]])
                 scoped? (conj (if colocated?
                                 [:items {:part-scope {:key :slot}}
                                  [:vector [:map [:slot :int]]]]
                                 [:items [:vector [:map [:slot :int]]]])))
        filterable (into {}
                         (keep (fn [f] (when-some [ops (:filter (law f))]
                                         [f ops])))
                         fields)
        sorted (filterv #(:sort (law %)) fields)
        default (some (fn [f]
                        (case (:sort (law f))
                          :default (name f)
                          :default-desc (str "-" (name f))
                          nil))
                      fields)]
    (cond-> {:kind :gadget
             :states [:open :closed]
             :initial :open
             :terminal #{:closed}
             :summary "{state}"
             :schema schema
             :actions {:close {:from #{:open} :to :closed
                               :safety {:idempotent true :reversible false
                                        :confirm false
                                        :one-way "Closing is cheap."}}}}
      (and (not colocated?) derived) (assoc :derived derived)
      (and colocated? derived)
      (assoc :schema (mapv (fn [e]
                             (if (and (vector? e) (= :total (first e)))
                               [:total {:optional true :derived (:total derived)}
                                [:maybe :int]]
                               e))
                           schema))
      (and (not colocated?) (seq filterable)) (assoc :filterable filterable)
      (and (not colocated?) (seq sorted))
      (assoc :sortable (cond-> {:fields sorted}
                         default (assoc :default default)))
      (and (not colocated?) scoped?)
      (assoc :part-scopes {:items {:path :items :key :slot}}))))

(defspec a-style-refactor-mints-zero-revisions 100
  (prop/for-all [decl gen-declaration]
    (let [split (r/normalize-resource (render decl :split))
          coloc (r/normalize-resource (render decl :colocated))]
      (and (= split coloc)
           (= (fp/fingerprint-hash (r/fingerprint split))
              (fp/fingerprint-hash (r/fingerprint coloc)))))))
