(ns landscape_rehearsal.kinds.booking
  "A booking: a crew at a customer's yard for a window. Its crew is a
  `team` row and its lead a member on that team (:in-team), so a lead
  off the crew is refused by lead-in-team. It holds its
  crew through :occupies, so the engine writes crew-is-free (no other
  booking or time off holds that crew then) and crew-is-working (the
  window is inside the house's calendar) on its create and reschedule
  doors. Its :rain_day door is that entry's bump: the engine moves the
  booking to the crew's next free window on a later business day, and
  one atomic bulk call filtered by crew and day moves a whole day's
  work. Film steps 1 and 2 (docs/spec-calendar-time.md §9) and the
  rain day are its scenarios."
  (:require [waymark10.dsl :refer [defresource defscenario]]
            [waymark10.resource :as r]))

(def ^:private safety
  {:idempotent true :reversible false :confirm false
   :one-way "A booking that has moved on stays moved; make a new booking for more work."})

;; the house works Monday to Friday, 8:00 to 17:00, and keeps Memorial Day
(def ^:private house
  {:zone "UTC"
   :workdays #{:mon :tue :wed :thu :fri}
   :hours {:from "08:00" :to "17:00"}
   :holidays [{:on "2027-05-31" :name "Memorial Day"}]})

;; the crews are teams: the Elm crew is Ana and Luis, the Oak crew Marco
(def ^:private people
  {:ana {:display "Ana Lopez"} :luis {:display "Luis Ortega"} :marco {:display "Marco Diaz"}})

(def ^:private crews
  {:elm {:name "The Elm crew" :members [:ana :luis]}
   :oak {:name "The Oak crew" :members [:marco]}})

(defscenario a-held-crew-is-not-booked-twice
  "The Elm crew is at the Parks' yard Thursday morning, so the Hendersons' patio is refused and the Oak crew is named free."
  {:kind :booking :attempt :create
   :as {:id "owner" :type :person}
   :at "2027-05-10T14:00:00Z"
   :calendar house
   :cast people :teams crews
   :given [{:kind :booking :state :scheduled
            :data {:customer_name "the Parks' yard" :crew "team:elm"
                   :starts_at "2027-05-13T08:00:00Z" :ends_at "2027-05-13T12:00:00Z"}}]
   :input {:customer_name "the Hendersons' patio" :crew "team:elm"
           :starts_at "2027-05-13T09:00:00Z" :ends_at "2027-05-13T11:00:00Z"}
   :expect {:refused :crew-is-free :because "The Oak crew is free."}})

(defscenario a-lead-off-the-crew-is-refused
  "Marco is on the Oak crew, so he does not lead the Elm crew's job at the Hendersons' patio, and the refusal names who is on the Elm crew."
  {:kind :booking :attempt :create
   :as {:id "owner" :type :person}
   :at "2027-05-10T14:00:00Z"
   :calendar house
   :cast people :teams crews
   :input {:customer_name "the Hendersons' patio" :crew "team:elm" :lead "marco"
           :starts_at "2027-05-13T09:00:00Z" :ends_at "2027-05-13T11:00:00Z"}
   :expect {:refused :lead-in-team
            :because "Marco Diaz is not on The Elm crew. The Elm crew is Ana Lopez and Luis Ortega."}})

(defscenario a-crew-is-not-booked-on-a-holiday
  "Monday 31 May is Memorial Day, so the Hendersons' patio is refused and the Elm crew's next free day is named."
  {:kind :booking :attempt :create
   :as {:id "owner" :type :person}
   :at "2027-05-10T14:00:00Z"
   :calendar house
   :cast people :teams crews
   :input {:customer_name "the Hendersons' patio" :crew "team:elm"
           :starts_at "2027-05-31T08:00:00Z" :ends_at "2027-05-31T12:00:00Z"}
   :expect {:refused :crew-is-working :because "Monday 31 May is Memorial Day."}})

(defscenario a-rain-day-skips-the-held-morning
  "It rains Monday 17 May and the Elm crew is at the Wus' lawn Tuesday morning, so the Parks' yard lands Tuesday at noon."
  {:kind :booking :attempt :rain_day
   :as {:id "owner" :type :person}
   :at "2027-05-17T07:00:00Z"
   :calendar house
   :cast people :teams crews
   :given [{:kind :booking :state :scheduled
            :data {:customer_name "the Wus' lawn" :crew "team:elm"
                   :starts_at "2027-05-18T08:00:00Z" :ends_at "2027-05-18T12:00:00Z"}}]
   :row {:state :scheduled
         :data {:customer_name "the Parks' yard" :crew "team:elm"
                :starts_at "2027-05-17T08:00:00Z" :ends_at "2027-05-17T12:00:00Z"}}
   :expect {:allowed true
            :lands {:crew "team:elm"
                    :starts_at "2027-05-18T12:00:00Z" :ends_at "2027-05-18T16:00:00Z"}}})

(defscenario a-rain-day-skips-the-holiday
  "It rains Friday 28 May, and Monday 31 May is Memorial Day, so the Hendersons' patio lands Tuesday 1 June."
  {:kind :booking :attempt :rain_day
   :as {:id "owner" :type :person}
   :at "2027-05-28T07:00:00Z"
   :calendar house
   :cast people :teams crews
   :row {:state :scheduled
         :data {:customer_name "the Hendersons' patio" :crew "team:elm"
                :starts_at "2027-05-28T08:00:00Z" :ends_at "2027-05-28T12:00:00Z"}}
   :expect {:allowed true
            :lands {:starts_at "2027-06-01T08:00:00Z" :ends_at "2027-06-01T12:00:00Z"}}})

(defresource booking
  {:kind :booking
   :plural "bookings"
   :states [:scheduled :in_progress :done :cancelled]
   :initial :scheduled
   :terminal #{:done :cancelled}
   :summary "{data.customer_name} · {state}"
   :schema [:map
            [:customer_name [:string {:min 1 :max 80}]]
            [:crew {:kind :team} :waymark/ref]
            [:lead {:optional true :kind :member :in-team '(data :crew)} [:maybe :waymark/ref]]
            [:starts_at :waymark/instant]
            [:ends_at :waymark/instant]]
   :filterable {:state #{:eq :in} :crew #{:eq} :starts_at #{:eq :range :after :before}
                :ends_at #{:eq}}
   ;; a crew member's grant is offered the bookings of their own crews
   :scope-default {:team-of {:field "crew"}}
   :occupies [{:resource :crew :from :starts_at :to :ends_at
               :in #{:scheduled :in_progress} :doors #{:create :reschedule}
               :bumps #{:rain_day}}]
   :explain {:crew-is-free (str "That crew is already at {b.customer_name} from {b.starts_at} "
                                "to {b.ends_at}. {free.name} is free.")}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:scheduled "A booking may stay scheduled for two weeks; past that, the mayor tells the owner."
             :in_progress "A booking may stay in progress for a day; past that, the mayor tells the owner."}
   :expected-age {:scheduled 1209600 :in_progress 86400}
   :actions
   {:reschedule {:from #{:scheduled} :to :scheduled
                 :input [:map [:starts_at :waymark/instant] [:ends_at :waymark/instant]]
                 :edit {:prefill [:starts_at :ends_at]}
                 :handler (r/field-writer [:starts_at :ends_at])
                 :safety {:idempotent true :reversible true :confirm false}}
    ;; the engine writes this door's handler: the crew's next free window
    ;; on a later business day, as long as the one it leaves
    :rain_day {:from #{:scheduled} :to :scheduled
               :bulk {:atomic true}
               :safety {:idempotent false :reversible true :confirm false}}
    :start {:from #{:scheduled} :to :in_progress :safety safety
            :decided "The mayor starts a booking on its own when the crew has arrived."}
    :finish {:from #{:scheduled :in_progress} :to :done :safety safety
             :decided "The mayor finishes a booking on its own once the crew has done the work."}
    :cancel {:from #{:scheduled} :to :cancelled :safety safety
             :decided "The mayor cancels a booking on its own when the customer calls it off, and says why."}}
   :scenarios [a-held-crew-is-not-booked-twice a-lead-off-the-crew-is-refused a-crew-is-not-booked-on-a-holiday
               a-rain-day-skips-the-held-morning a-rain-day-skips-the-holiday]})
