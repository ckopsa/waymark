(ns shop_rehearsal.kinds.wholesale-order
  "A cafe's order on its account, invoiced net-30 once shipped."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]))

(defguard names-account
  (refuse "A wholesale order is placed on an active wholesale account; name it first.")
  '(present? :account_id))
;; stand-in: the cafe's standing is typed by whoever accepts the order,
;; not read from its account and invoices (concept 1)
(defguard in-good-standing
  (refuse "A cafe with an invoice more than 15 days past due gets no new wholesale order until it pays.")
  '(= (input :account_standing) "good"))
(defguard has-invoice
  (refuse "A shipped wholesale order is invoiced; name its invoice first.")
  '(is-set (input :invoice_id)))
(defn- one-way [s] {:idempotent true :reversible false :confirm false :one-way s})

(defresource wholesale-order
  {:kind :wholesale_order
   :plural "wholesale_orders"
   :states [:placed :accepted :shipped :declined]
   :initial :placed
   :terminal #{:shipped :declined}
   :summary "{data.cafe} · {data.total_cents}¢ · {state}"
   :schema [:map [:cafe [:string {:min 1 :max 120}]]
            [:account_id {:optional true :kind :wholesale_account} [:maybe :waymark/ref]]
            ;; stand-in: line items as text
            [:items {:x-display {:widget "prose"}} [:string {:min 1 :max 2000}]] [:total_cents [:int {:min 0}]]
            [:account_standing {:optional true} [:maybe [:enum "good" "past_due"]]]
            [:invoice_id {:optional true :kind :invoice} [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in} :account_id #{:eq} :cafe #{:eq}}
   :decided {:placed "A placed wholesale order is accepted or declined within two days; past that, the mayor tells the owner."
             :accepted "An accepted wholesale order ships within a week; past that, the mayor tells the owner."}
   :expected-age {:placed 172800 :accepted 604800}
   :actions
   {:accept  {:from #{:placed} :to :accepted :input [:map [:account_standing [:enum "good" "past_due"]]]
              :guards [names-account in-good-standing]
              :safety (one-way "An accepted wholesale order will be made and shipped.")
              :decided "The mayor accepts a wholesale order on its own when the cafe's account is active and no invoice of it is more than 15 days past due."}
    :decline {:from #{:placed} :to :declined
              :safety (one-way "A declined wholesale order is closed; the cafe orders again once it pays.")
              :decided "The mayor declines a wholesale order on its own when the cafe has an invoice more than 15 days past due, and says so."}
    :ship    {:from #{:accepted} :to :shipped :input [:map [:invoice_id {:kind :invoice} :waymark/ref]]
              :guards [has-invoice] :safety (one-way "A shipped wholesale order is owed on its invoice.")
              :decided "The mayor marks a wholesale order shipped on its own once its invoice is issued."}}})

(defscenario past-due-cafe-not-accepted "A cafe more than 15 days past due gets no new wholesale order."
  {:kind :wholesale_order :attempt :accept :input {:account_standing "past_due"} :row {:state :placed :data {:cafe "Corner Cup Cafe" :account_id "acct-1" :items "24 speckled mugs" :total_cents 38400}} :expect {:refused :in-good-standing}})
(defscenario not-shipped-without-invoice "An accepted wholesale order does not ship until its invoice is named."
  {:kind :wholesale_order :attempt :ship :input {} :row {:state :accepted :data {:cafe "Corner Cup Cafe" :account_id "acct-1" :items "24 speckled mugs" :total_cents 38400}} :expect {:refused :has-invoice}})
