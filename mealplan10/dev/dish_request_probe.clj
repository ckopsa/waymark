(ns dish-request-probe
  "Probe run 2's specimen (docs/waymark10-authoring-probe.md): a
  family member requests a dish — a real ref to a meal, a label the
  engine maintains, and a cross-resource guard that only queues a
  dish whose meal is actually on the list. Authored under probe
  rules: waymark10.dsl + waymark10.dev + docs/waymark10-vocabulary.md
  + error messages only, framework source off-limits. Off the app
  classpath (dev/); loads under the probe alias:

      clojure -Sdeps '{:aliases {:probe {:extra-paths [\"dev\"]}}}' -M:probe"
  (:require [waymark10.dsl :refer [defresource defguardfn]]
            [waymark10.types :as t]))

;; the verdict reads another kind's state, so it stays code, its
;; dependency declared — the vocabulary page's §6 contract verbatim
(defguardfn meal-on-list
  {:reads [:meal]
   :explain "That meal isn't on the family list yet — accept it there first."
   :remedies [:meal/accept]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [meal (read :meal (get-in row [:data :meal_id]))]
      (if (and meal (= :on_list (:state meal)))
        (t/allow)
        (t/deny)))
    (t/allow)))   ; the pure render probe carries no :read

(defresource dish-request
  {:kind :dish_request
   :initial :open
   :terminal #{:served :dropped}
   :summary "{data.meal_name} for {data.requested_by} · {state}"
   :schema [:map
            [:meal_id {:kind :meal :label :meal_name :filter #{:eq}}
             :waymark/ref]
            [:meal_name {:optional true} [:maybe [:string {:max 200}]]]
            [:requested_by {:sort :default} [:string {:min 1 :max 80}]]]
   :deviations
   ["A request names one dish — trading up to a different meal is a new request, not an edit."]
   :flow
   [[:open   :queue :queued
     {:requires [meal-on-list]
      :one-way "Queuing marks the dish for an upcoming plan; dropping the request stays open."
      :display {:label "Queue it" :style :primary :order 1}}]
    [:queued :serve :served
     {:one-way "Serving records dinner reality; nothing external changes."
      :display {:label "Served" :order 2}}]
    [:open   :drop  :dropped
     {:confirm "The request is dropped; the requester can always ask again."
      :display {:label "Drop" :style :danger :order 9}}]
    [:queued :drop  :dropped
     {:confirm "The request is dropped; the requester can always ask again."
      :display {:label "Drop" :style :danger :order 9}}]]})
