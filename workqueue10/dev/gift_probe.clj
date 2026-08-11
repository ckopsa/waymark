(ns gift-probe
  "Probe run 3's specimen (docs/waymark10-authoring-probe.md): a gift
  from idea to given, authored in the :fields lifecycle-group dialect
  — the schema by phase, the editors and the conditional create gate
  all derived. Authored under probe rules from
  docs/waymark10-vocabulary.md §7; framework source off-limits. Off
  the app classpath (dev/); loads under the probe alias."
  (:require [waymark10.dsl :refer [defresource money one-of prose]]))

(defresource gift
  {:kind :gift
   :initial :idea
   :terminal #{:given :scrapped}
   :summary "{data.recipient} · {state}"
   :deviations
   ["A gift names its recipient at create — re-gifting to someone else is a new gift, not an edit."]
   :fields
   {:at-create  [[:recipient [:string {:min 1 :max 80}]]
                 [:occasion  (one-of :birthday :christmas :other)]]
    ;; an :other occasion says what it is — required exactly then
    :when       {:other [[:occasion_note [:string {:min 1 :max 120}]]]}
    :while-open [[:idea_notes (prose "Ideas" {:shared true})]]
    :open       #{:idea}
    :support    [[:budget (money :usd)]]}
   :flow
   [[:idea   :buy   :bought
     {:one-way "Buying is the gift's own record; returns are the store's business."
      :display {:label "Bought" :style :primary :order 1}}]
    [:bought :give  :given
     {:one-way "Giving records the moment; nothing external changes."
      :display {:label "Given" :style :primary :order 1}}]
    [:idea   :scrap :scrapped
     {:confirm "The idea is scrapped; the record stays readable."
      :display {:label "Scrap" :style :danger :order 9}}]]})
