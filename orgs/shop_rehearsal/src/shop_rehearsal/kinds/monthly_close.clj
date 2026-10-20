(ns shop_rehearsal.kinds.monthly-close
  "A monthly close: every month the books must balance. What came in
  from the processor equals orders paid minus refunds minus fees."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard books-balance
  (refuse "The books balance only when what came in from the processor equals orders paid minus refunds minus fees.")
  '(= (data :processor_in_cents) (- (data :orders_paid_cents) (data :refunds_cents) (data :fees_cents))))

(defresource monthly-close
  {:kind :monthly_close
   :plural "monthly_closes"
   :states [:open :balanced]
   :initial :open
   :terminal #{:balanced}
   :summary "{data.month} · {state}"
   :schema [:map
            [:month [:string {:min 7 :max 7}]]
            ;; each total typed from the processor's statement and the
            ;; month's rows, not summed by the engine (concept 3)
            [:processor_in_cents [:int]]
            [:orders_paid_cents [:int {:min 0}]]
            [:refunds_cents [:int {:min 0}]]
            [:fees_cents [:int {:min 0}]]
            [:sales_tax_cents {:optional true} [:maybe [:int {:min 0}]]]]
   :filterable {:state #{:eq :in} :month #{:eq}}
   :decided {:open "A month is closed within ten days of its end; past that, the mayor tells the owner."}
   :expected-age {:open 864000}
   :actions
   {:balance {:from #{:open} :to :balanced
              :guards [books-balance]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A balanced month is closed; correct it with next month's close."}
              :decided "The mayor closes a month on its own once the processor's deposits equal orders paid minus refunds minus fees."}}})
