(ns dress_rehearsal.kinds.document-request
  "A document request: one record a study is waiting on. It is chased
  twice, then the principal is told."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource document-request
  {:kind :document_request
   :plural "document_requests"
   :states [:requested :chased_once :chased_twice :escalated :received :waived]
   :initial :requested
   :terminal #{:received :waived}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:study_id {:optional true :kind :study} [:maybe :waymark/ref]]
            [:category {:optional true}
             [:maybe [:enum "payroll" "contractors" "cloud_and_supplies" "engineering_records" "other"]]]
            [:channel {:optional true} [:maybe [:enum "email" "shared_folder" "upload_link"]]]
            [:due {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :study_id #{:eq} :category #{:eq}}
   :actions
   {:chase    {:from #{:requested} :to :chased_once
               :safety {:idempotent false :reversible false :confirm false
                        :one-way "The client has been reminded once."}}
    :chase_again {:from #{:chased_once} :to :chased_twice
                  :safety {:idempotent false :reversible false :confirm false
                           :one-way "The client has been reminded twice."}}
    :escalate {:from #{:chased_twice} :to :escalated
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "The principal now holds this request."}}
    :receive  {:from #{:requested :chased_once :chased_twice :escalated} :to :received
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A received document stays on file with its study."}}
    :waive    {:from #{:requested :chased_once :chased_twice :escalated} :to :waived
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A waived request is let go; ask again with a new one."}}}})
