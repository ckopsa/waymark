(ns shop_rehearsal.kinds.wholesale-account
  "A wholesale account: a cafe that buys on net-30 invoices. The owner
  approves every wholesale account."
  (:require [waymark10.dsl :refer [defguard defresource hold]]))

(defguard owner-approves
  (hold "Every wholesale account waits for the owner's approval."))

(defresource wholesale-account
  {:kind :wholesale_account
   :plural "wholesale_accounts"
   :states [:applied :active :declined :closed]
   :initial :applied
   :terminal #{:declined :closed}
   :summary "{data.cafe} · {state}"
   :schema [:map
            [:cafe [:string {:min 1 :max 120}]]
            [:contact {:optional true} [:maybe [:string {:max 200}]]]
            [:billing_state {:optional true} [:maybe [:string {:min 2 :max 2}]]]
            [:terms_days [:int {:min 1 :max 90}]]]
   :filterable {:state #{:eq :in} :cafe #{:eq}}
   :decided {:applied "A cafe's application waits a week for the owner; past that, the mayor reminds her."
             :active "An active account stays open while the cafe buys; after a year with no order, the mayor asks the owner whether to close it."}
   :expected-age {:applied 604800 :active 31536000}
   :actions
   {:approve {:from #{:applied} :to :active
              :guards [owner-approves]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An active account may place wholesale orders on net-30 terms."}}
    :decline {:from #{:applied} :to :declined
              :guards [owner-approves]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined cafe applies again with a new application."}}
    :close   {:from #{:active} :to :closed
              :guards [owner-approves]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A closed account places no more wholesale orders."}}}})
