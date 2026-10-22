(ns shop_rehearsal.kinds.monthly-close
  "A monthly close: every month the books must balance. What came in
  from the processor equals orders paid minus refunds minus fees."
  (:require [waymark10.dsl :refer [defresource defscenario]]))

(defresource monthly-close
  {:kind :monthly_close
   :plural "monthly_closes"
   :states [:open :balanced]
   :initial :open
   :terminal #{:balanced}
   :summary "{data.period_start} · {state}"
   :schema [:map
            [:period_start :waymark/date]
            [:period_end :waymark/date]]
   ;; nobody types a total: each side is summed from the month's postings
   :reconciles {:period '[(data :period_start) (data :period_end)]
                :sides '{:processor_in (balance [:house :processor_in])
                         :expected (- (- (balance [:all :order :paid])
                                         (balance [:all :order :refunded]))
                                      (balance [:house :fees]))}
                :tolerance '(money "0.00" "USD")}
   :filterable {:state #{:eq :in} :period_start #{:eq :range}}
   :decided {:open "A month is closed within ten days of its end; past that, the mayor tells the owner."}
   :expected-age {:open {:until '(+ (data :period_end) (days 10))}}
   :actions
   {:balance {:from #{:open} :to :balanced
              :safety {:idempotent true :reversible false :confirm false :one-way "A balanced month is closed; correct it with next month's close."}
              :decided "The mayor closes a month on its own once the processor's deposits equal orders paid minus refunds minus fees."}}})

(defscenario month-with-a-gap "A month whose processor deposits fall short of orders paid minus refunds minus fees does not balance."
  {:kind :monthly_close :attempt :balance
   :row {:state :open :data {:period_start "2026-10-01" :period_end "2026-11-01"}}
   :postings [[[:house :processor_in] "100.00 USD" "2026-10-12"] [[:order "o1" :paid] "120.00 USD" "2026-10-12"]
              [[:order "o1" :refunded] "0.00 USD" "2026-10-12"] [[:house :fees] "10.00 USD" "2026-10-12"]]
   :expect {:refused :reconciles}})
