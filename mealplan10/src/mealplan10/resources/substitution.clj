(ns mealplan10.resources.substitution
  "The Substitution resource: a directed, family-approved stand-in
  claim — \"margarine can stand in for butter, ×0.8\". The complement
  of ingredient.absorb: absorb says same concept, different spelling;
  a substitution says different concept, acceptable stand-in.

  The AI proposes (suggested); the family accepts or declines.
  Pricing and shopping consume ACCEPTED rows only — a suggested
  substitution prices nothing. Retiring one stops future use but
  leaves already-stamped priced_via marks intact: an estimate that
  honestly said \"priced via Margarine\" stays honest history.

  ratio is grams of substitute per gram asked for (100 g butter ≈
  80 g oil → 0.8), exact decimal, > 0 — and distinct is REQUIRED AT
  CREATE: self-substitution is unrepresentable, the require gate
  judging the create input by the fact's own law (design §24)."
  (:require [waymark10.dsl :refer [defaction defderived defresource
                                   defhandler require-fact]]))

(def overwrite
  {:idempotent true :reversible false :confirm false})

(defderived distinct-fact
  {:over [:from_ingredient_id :to_ingredient_id]
   :expr '(not= (var :from_ingredient_id) (var :to_ingredient_id))
   :explain "An ingredient cannot substitute for itself."})

(def distinct-at-create
  (require-fact :distinct
                {:explain "An ingredient cannot substitute for itself."}))

(defhandler apply-sub-details [row inp _ctx]
  (cond-> row
    (some? (:ratio inp)) (assoc-in [:data :ratio] (:ratio inp))
    (some? (:context inp)) (assoc-in [:data :context] (:context inp))))

(defaction update-details
  {:from #{:accepted} :to :accepted
   :input [:map
           [:ratio {:optional true} [:maybe [:decimal {:gt 0 :max 100}]]]
           [:context {:optional true} [:maybe [:string {:max 200}]]]]
   :edit {:prefill [:ratio :context]}
   :safety overwrite
   :handler apply-sub-details
   :display {:label "Update details" :order 3}})

(defresource substitution
  {:kind :substitution
   :states [:suggested :accepted :retired]
   :initial :suggested
   :terminal #{:retired}
   :summary "{data.from_ingredient_name} → {data.to_ingredient_name} · ×{data.ratio} · {state}"
   :nav :secondary
   :schema [:map
            [:from_ingredient_id {:kind :ingredient
                                  :label :from_ingredient_name
                                  :filter #{:eq}
                                  :pick {:state "active"}
                                  :x-display {:label "Stands in for"}}
             :waymark/ref]
            [:from_ingredient_name {:optional true}
             [:maybe [:string {:max 200}]]]
            [:to_ingredient_id {:kind :ingredient
                                :label :to_ingredient_name
                                :filter #{:eq}
                                :pick {:state "active"}
                                :x-display {:label "The stand-in"}}
             :waymark/ref]
            [:to_ingredient_name {:optional true}
             [:maybe [:string {:max 200}]]]
            [:ratio {:optional true :default 1M
                     :x-display {:label "Grams per gram asked"}}
             [:maybe [:decimal {:gt 0 :max 100}]]]
            [:context {:optional true} [:maybe [:string {:max 200}]]]
            [:distinct {:optional true :derived distinct-fact}
             [:maybe :boolean]]]
   :create-guards [distinct-at-create]
   :filterable {:state #{:eq :in}}
   :display {:title "{data.from_ingredient_name} → {data.to_ingredient_name}"}
   :flow
   [[:suggested :accept :accepted
     {:one-way "Accepted substitutions start pricing lines and offering swaps; Retire stops them again."
      :display {:label "Accept" :style :primary :order 1}}]
    [:suggested :decline :retired
     {:one-way "Declining a suggestion is cheap — the AI can suggest it again any time."
      :display {:label "No thanks" :order 2}}]
    [:accepted :retire :retired
     {:one-way "Future pricing and swaps stop; estimates already stamped through this stand-in keep their honest priced_via mark."
      :display {:label "Retire" :style :danger :order 9}}]]
   :actions {:update_details update-details}})
