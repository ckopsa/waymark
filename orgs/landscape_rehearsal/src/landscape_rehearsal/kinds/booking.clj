(ns landscape_rehearsal.kinds.booking
  "A booking: a crew at a customer's yard for a window. It holds its
  crew through :occupies, so the engine writes crew-is-free (no other
  booking or time off holds that crew then) and crew-is-working (the
  window is inside the house's calendar) on its create and reschedule
  doors. Film steps 1 and 2 (docs/spec-calendar-time.md §9) are its
  scenarios."
  (:require [waymark10.dsl :refer [defguard defresource defscenario hold]]))

;; the owner approves any quote over $10,000 before it goes out
(defguard big-quotes-wait
  (hold "A quote of $10,000 or more goes out only on the owner's tap.")
  '(< (input :quote_cents) 1000000))

(def ^:private safety
  {:idempotent true :reversible false :confirm false
   :one-way "A booking that has moved on stays moved; make a new booking for more work."})

;; the house works Monday to Friday, 8:00 to 17:00, and keeps Memorial Day
(def ^:private house
  {:zone "UTC"
   :workdays #{:mon :tue :wed :thu :fri}
   :hours {:from "08:00" :to "17:00"}
   :holidays [{:on "2027-05-31" :name "Memorial Day"}]})

(def ^:private crews
  [{:kind :crew :handle :elm :state :active :data {:name "The Elm crew"}}
   {:kind :crew :handle :oak :state :active :data {:name "The Oak crew"}}])

(defscenario a-held-crew-is-not-booked-twice
  "The Elm crew is at the Parks' yard Thursday morning, so the Hendersons' patio is refused and the Oak crew is named free."
  {:kind :booking :attempt :create
   :as {:id "owner" :type :person}
   :at "2027-05-10T14:00:00Z"
   :calendar house
   :given (conj crews
                {:kind :booking :state :scheduled
                 :data {:customer_name "the Parks' yard" :crew_id "{given/elm}"
                        :starts_at "2027-05-13T08:00:00Z" :ends_at "2027-05-13T12:00:00Z"}})
   :input {:customer_name "the Hendersons' patio" :crew_id "{given/elm}"
           :starts_at "2027-05-13T09:00:00Z" :ends_at "2027-05-13T11:00:00Z"}
   :expect {:refused :crew-is-free :because "The Oak crew is free."}})

(defscenario a-crew-is-not-booked-on-a-holiday
  "Monday 31 May is Memorial Day, so the Hendersons' patio is refused and the Elm crew's next free day is named."
  {:kind :booking :attempt :create
   :as {:id "owner" :type :person}
   :at "2027-05-10T14:00:00Z"
   :calendar house
   :given crews
   :input {:customer_name "the Hendersons' patio" :crew_id "{given/elm}"
           :starts_at "2027-05-31T08:00:00Z" :ends_at "2027-05-31T12:00:00Z"}
   :expect {:refused :crew-is-working :because "Monday 31 May is Memorial Day."}})

(defscenario the-owner-sends-a-big-quote
  "The Parks' $12,600 irrigation quote is $10,000 or more; the owner's own send goes out, where the mayor's would wait for that tap."
  {:kind :booking :attempt :send_quote
   :as {:id "owner" :type :person}
   :at "2027-05-11T14:00:00Z"
   :given crews
   :row {:state :scheduled
         :data {:customer_name "the Parks' yard" :crew_id "{given/oak}"
                :starts_at "2027-05-11T08:00:00Z" :ends_at "2027-05-11T12:00:00Z"}}
   :input {:quote_cents 1260000}
   :expect {:allowed true}})

(defresource booking
  {:kind :booking
   :plural "bookings"
   :states [:scheduled :in_progress :done :billed :cancelled]
   :initial :scheduled
   :terminal #{:billed :cancelled}
   :summary "{data.customer_name} · {state}"
   :schema [:map
            [:customer_name [:string {:min 1 :max 80}]]
            [:crew_id {:kind :crew} :waymark/ref]
            [:starts_at :waymark/instant]
            [:ends_at :waymark/instant]]
   :filterable {:state #{:eq :in} :crew_id #{:eq} :starts_at #{:eq} :ends_at #{:eq}}
   :occupies [{:resource :crew_id :from :starts_at :to :ends_at
               :in #{:scheduled :in_progress} :doors #{:create :reschedule}}]
   :explain {:crew-is-free (str "That crew is already at {b.customer_name} from {b.starts_at} "
                                "to {b.ends_at}. {free.name} is free.")}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:scheduled "A booking may stay scheduled for two weeks; past that, the mayor tells the owner."
             :in_progress "A booking may stay in progress for a day; past that, the mayor tells the owner."
             :done "A done booking is billed within two days; past that, the mayor tells the owner."}
   :expected-age {:scheduled 1209600 :in_progress 86400 :done 172800}
   :actions
   {:reschedule {:from #{:scheduled} :to :scheduled
                 :input [:map [:starts_at :waymark/instant] [:ends_at :waymark/instant]]
                 :edit {:prefill [:starts_at :ends_at]}
                 :safety {:idempotent true :reversible true :confirm false}}
    :send_quote {:from #{:scheduled} :to :scheduled
                 :input [:map [:quote_cents [:int {:min 1}]]]
                 :guards [big-quotes-wait]
                 :safety {:idempotent true :reversible true :confirm false}}
    :bill {:from #{:done} :to :billed
           :input [:map [:amount_cents [:int {:min 1}]]]
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A billed booking stays billed; credit the customer to change it."}
           :decided "The mayor bills the customer's balance on its own once the crew lead has marked the job done, due in 15 days."}
    :start {:from #{:scheduled} :to :in_progress :safety safety
            :decided "The mayor starts a booking on its own when the crew has arrived."}
    :finish {:from #{:scheduled :in_progress} :to :done :safety safety
             :decided "The mayor finishes a booking on its own once the crew has done the work."}
    :cancel {:from #{:scheduled} :to :cancelled :safety safety
             :decided "The mayor cancels a booking on its own when the customer calls it off, and says why."}}
   :scenarios [a-held-crew-is-not-booked-twice a-crew-is-not-booked-on-a-holiday
               the-owner-sends-a-big-quote]})
