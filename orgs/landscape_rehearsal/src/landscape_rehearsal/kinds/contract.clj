(ns landscape_rehearsal.kinds.contract
  "A contract: a season of visits for one customer and one crew. Its
  visits rule (:recurs) makes a visit every second Tuesday of the
  season, 14 days ahead, as the account manager's act. Film step 3
  (docs/spec-calendar-time.md §9) is its scenario."
  (:require [waymark10.dsl :refer [defresource defscenario]]))

(defscenario the-parks-are-visited-every-second-tuesday
  "The Parks' contract runs 1 April to 31 October, so its first visit is Tuesday 6 April and it makes fifteen."
  {:kind :contract :recurs :visits
   :row {:state :active
         :data {:customer_name "the Parks' yard" :crew_id "crew-elm"
                :season_starts_on "2027-04-01" :season_ends_on "2027-10-31"}}
   :calendar {:zone "UTC"
              :workdays #{:mon :tue :wed :thu :fri}
              :holidays [{:on "2027-05-31" :name "Memorial Day"}]}
   :expect {:occurrences {:first "2027-04-06" :last "2027-10-19" :count 15}}})

(defresource contract
  {:kind :contract
   :plural "contracts"
   :states [:active :ended]
   :initial :active
   :terminal #{:ended}
   :summary "{data.customer_name} · {state}"
   :schema [:map
            [:customer_name [:string {:min 1 :max 80}]]
            [:crew_id {:optional true :kind :crew} [:maybe :waymark/ref]]
            [:season_starts_on :waymark/date]
            [:season_ends_on :waymark/date]
            [:account_manager_id {:optional true} [:maybe [:string {:max 80}]]]]
   :filterable {:state #{:eq :in}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:active "A contract stays active for its season; the mayor tells the owner when the season has ended."}
   :expected-age {:active 31536000}
   :recurs [{:name :visits
             :makes :visit
             :every '(weeks 2)
             :on #{:tue}
             :from '(data :season_starts_on)
             :until '(data :season_ends_on)
             :while #{:active}
             :holidays :next
             :lead '(days 14)
             :owner '(data :account_manager_id)
             :input {:contract_id '(self)
                     :visit_on '(occurrence)
                     :crew_id '(data :crew_id)}}]
   :actions
   {:end {:from #{:active} :to :ended
          :safety {:idempotent true :reversible false :confirm false
                   :one-way "An ended contract makes no more visits; sign a new one for another season."}
          :decided "The mayor ends a contract on its own once its season is over."}}
   :scenarios [the-parks-are-visited-every-second-tuesday]})
