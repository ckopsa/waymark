(ns leftover-probe
  "The authoring probe's specimen (docs/waymark10-authoring-probe.md):
  a leftover in the fridge — eat it or toss it before it turns. NOT
  registered in mealplan10.main; lives off the classpath (dev/) and
  loads only under the probe's own alias:

      clojure -Sdeps '{:aliases {:probe {:extra-paths [\"dev\"]}}}' -M:probe

  Authored under probe rules: waymark10.dsl + waymark10.dev + error
  messages only, framework source off-limits. Every place that rule
  broke is a logged defect in the probe doc."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource leftover
  {:kind :leftover
   :initial :fridge
   :terminal #{:eaten :tossed}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 100}]]
            [:stored_on {:sort :default} :waymark/date]
            [:eat_by {:filter #{:eq :range}} :waymark/date]
            ;; the clock flips it — no write, no poll
            [:past_eat_by {:optional true :filter #{:eq}
                           :derived {:over [:eat_by :now]
                                     :expr '(< (var :eat_by)
                                               (date-of (var :now)))}}
             [:maybe :boolean]]]
   :deviations
   ["No :undo pointers — eaten and tossed are honest history, and nothing un-eats a leftover."]
   :flow
   [[:fridge :eat :eaten
     {:one-way "Eating a leftover records kitchen reality; nothing external changes."
      :display {:label "Eaten" :style :primary :order 1}}]
    [:fridge :toss :tossed
     {:confirm "The leftover goes in the trash; the record stays readable as history."
      :display {:label "Toss" :style :danger :order 9}}]]})
