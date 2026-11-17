(ns vet_clinic.kinds.inquiry
  "An inquiry: one business inquiry emailed to the clinic. The replies
  seat reads it and sorts it: a fixed public fact is answered and sent
  on its own; pricing, quotes or estimates are drafted and wait for the
  owner's tap; a sick or injured animal is handed to staff as urgent,
  never answered with medical advice; anything the clinic need not
  answer is set aside."
  (:require [waymark10.dsl :refer [defguard defresource hold refuse]]
            [waymark10.resource :as r]))

(defguard has-reply
  (refuse "A reply is sent once it is written; give its text.")
  '(present? :reply))

(defguard owner-allows-pricing
  (hold "A reply that touches pricing, quotes or estimates is sent only on the owner's tap."))

;; for the first weeks every reply is read before it sends; the owner
;; lifts this hold once the replies have earned it
(defguard owner-reads-first
  (hold "For the first weeks, a reply of plain clinic facts is sent only on the owner's tap."))

(defresource inquiry
  {:kind :inquiry
   :plural "inquiries"
   :states [:new :awaiting_owner :urgent :sending :sent :with_staff :set_aside]
   :initial :new
   :terminal #{:sent :with_staff :set_aside}
   :summary "{data.subject} · {state}"
   :schema [:map
            [:subject [:string {:min 1 :max 200}]]
            [:sender_name {:optional true} [:maybe [:string {:max 120}]]]
            [:sender_email [:string {:min 3 :max 254}]]
            [:body {:x-display {:widget "prose"}} [:string {:min 1 :max 8000}]]
            [:reply {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 4000}]]]
            [:handoff_note {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]
            [:set_aside_reason {:optional true}
             [:maybe [:string {:max 480}]]]]
   :filterable {:state #{:eq :in} :sender_email #{:eq}}
   ;; the replies seat walks inquiries: its queue is the new ones
   :default-filters {:state "new"}
   ;; the owner's answer: 48 business hours, read as two business days
   :expected-age {:awaiting_owner {:business-days 2}
                  :urgent         {:business-days 2}
                  ;; a send is answered by the mail service within the hour
                  :sending        3600}
   ;; only the mail integration's answer leaves sending
   :left-by [{:in #{:sending} :by :engine :via :mail_sent :to :sent}]
   :decided {:awaiting_owner "Tell me when a pricing reply has waited 48 business hours on my approval."
             :urgent "Tell me when a hand-off has waited 48 business hours on staff."}
   :actions
   {:answer_facts
    {:from #{:new} :to :sending
     :input [:map [:reply [:string {:min 1 :max 4000}]]]
     :handler (r/field-writer [:reply])
     :guards [owner-reads-first]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A reply sent is in the sender's inbox; a correction is a new email."}
     :decided "Replies that state only fixed public facts (opening hours, address, directions, which services we offer, how to book) may go out on their own; for the first weeks the owner reads each one first."
     :effect {:power "mail__send"
              :input {:to '(data :sender_email)
                      :subject '(str "Re: " (data :subject))
                      :body '(data :reply)}
              :why "Email the reply to {sender_email}."
              :idempotency-key '(str "inquiry-reply:" (self))
              :lands :sent
              :fails-to :new}}
    :draft_pricing
    {:from #{:new} :to :awaiting_owner
     :input [:map [:reply [:string {:min 1 :max 4000}]]]
     :handler (r/field-writer [:reply])
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A drafted pricing reply waits for the owner; it is not sorted again."}
     :decided "The replies seat drafts a reply that touches pricing, quotes or estimates by itself; sending it is the owner's."}
    :send_pricing
    {:from #{:awaiting_owner} :to :sending
     :guards [has-reply owner-allows-pricing]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A reply sent is in the sender's inbox; a correction is a new email."}
     :effect {:power "mail__send"
              :input {:to '(data :sender_email)
                      :subject '(str "Re: " (data :subject))
                      :body '(data :reply)}
              :why "Email the pricing reply to {sender_email}."
              :idempotency-key '(str "inquiry-reply:" (self))
              :lands :sent
              :fails-to :awaiting_owner}}
    ;; the mail service's answer lands the reply as sent
    :mail_sent
    {:from #{:sending} :to :sent
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A reply sent is in the sender's inbox; a correction is a new email."}}
    :hand_to_staff
    {:from #{:new} :to :urgent
     :input [:map [:handoff_note [:string {:min 1 :max 2000}]]]
     :handler (r/field-writer [:handoff_note])
     :safety {:idempotent true :reversible false :confirm false
              :one-way "An urgent inquiry is the staff's; it is not answered by the replies."}
     :decided "The agent never gives medical advice: anything about a sick or injured animal is passed to staff as urgent rather than answered."}
    :staff_took
    {:from #{:urgent} :to :with_staff
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Staff have the animal's case; they answer the sender themselves."}}
    :set_aside
    {:from #{:new} :to :set_aside
     :input [:map [:set_aside_reason [:string {:min 1 :max 480}]]]
     :handler (r/field-writer [:set_aside_reason])
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A set-aside inquiry gets no reply; the sender may write again."}}}})
