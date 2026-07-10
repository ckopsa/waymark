(ns waymark10.batch-a-fixtures
  "Batch-A acceptance fixtures: a link-bearing trio beside the
  phase-1 meal/plan pair. ba_project owns ba_ticket (open-tickets
  rollup — the materialized badge), ba_ticket relates to ba_day over
  promoted date columns (the edge-cited agenda link) and carries a
  templated parent link — one kind exercising all three link
  spellings, plus a recall-class action for the effort obligations."
  (:require [waymark10.resource :as r :refer [defresource defhandler]]))

(defresource ba-project
  {:kind :ba_project
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 100}]]
            [:open_tickets {:optional true} [:maybe :int]]]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   :owns [{:kind :ba_ticket :via :project_id}]
   :derived {:open_tickets {:count {:owns :ba_ticket
                                    :where {:state #{"open"}}}}}
   :links [{:rel :tickets :owns :ba_ticket :embed true
            :badge :open_tickets :summary "This project's tickets"}]
   :actions
   {:close {:from #{:open} :to :closed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A closed project keeps its history."}}}})

(defhandler set-points [row inp _ctx]
  (assoc-in row [:data :points] (:points inp)))

(defresource ba-ticket
  {:kind :ba_ticket
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 100}]]
            [:project_id {:kind :ba_project} :waymark/ref]
            [:due_date {:optional true} [:maybe :waymark/date]]
            [:points {:optional true} [:maybe :int]]]
   :filterable {:state #{:eq :in}
                :project_id #{:eq}
                :due_date #{:eq :range}}
   :sortable {:fields [:points] :default "-points"}
   :related {:same_day {:kind :ba_day :on [[:due_date := :date]]}}
   :links [{:rel :agenda :edge :same_day :badge :points
            :summary "Days sharing this due date"}
           {:rel :parent :href "/api/ba_projects/{data.project_id}"
            :kind :ba_project}]
   :actions
   {:estimate {:from #{:open} :to :open
               :input [:map [:points [:int {:min 0 :max 100}]]]
               :safety {:idempotent true :reversible true :confirm false}
               :handler set-points}
    :finish {:from #{:open} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Finished work is history."}}}})

(defresource ba-day
  {:kind :ba_day
   :states [:scheduled :past]
   :initial :scheduled
   :terminal #{:past}
   :summary "{data.date} · {state}"
   :schema [:map
            [:date :waymark/date]
            [:label {:optional true} [:maybe [:string {:max 50}]]]]
   :filterable {:state #{:eq :in} :date #{:eq :range}}
   :sortable {:fields [:date] :default "date"}
   :actions
   {:pass {:from #{:scheduled} :to :past
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "Days pass on their own."}}}})
