(ns example.kinds.work-order
  "A work order: accepted work, scheduled for a crew, then done or
  cancelled. It names the request it came from by id."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-crew
  (refuse "A work order is done by a crew; name the crew first.")
  '(present? :crew))

(defresource work-order
  {:kind :work_order
   :plural "work_orders"
   :states [:scheduled :done :cancelled]
   :initial :scheduled
   :terminal #{:done :cancelled}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:request_id {:optional true :kind :request} [:maybe :waymark/ref]]
            [:crew {:optional true} [:maybe [:string {:max 80}]]]]
   :filterable {:state #{:eq :in} :crew #{:eq} :request_id #{:eq}}
   :actions
   {:complete {:from #{:scheduled} :to :done
               :guards [has-crew]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A done work order stays done; write a new one for more work."}}
    :cancel   {:from #{:scheduled} :to :cancelled
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A cancelled work order is let go; write a new one to do the work after all."}}}})
