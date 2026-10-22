(ns myorg.kinds.item
  "An item: one piece of the org's work, open until it is done or
  dropped. Copy this file once per kind, rename it, and say each
  guard's law as a sentence a person can read."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-assignee
  (refuse "An item is done by someone; name who first.")
  '(present? :assignee))

;; A door that waits for the owner's tap is one line, its law optional
;; (with one, it holds only while the law is false). Refer `hold` from
;; waymark10.dsl and put the guard in that door's :guards:
;;   (defguard owner-drops (hold "An item is dropped only on the owner's tap."))

;; A law reads a field through a reference form: (data :k) is what the
;; row holds, (input :k) what this door's caller sent. A bare keyword
;; outside present? is refused. A related-row guard names other rows,
;; with exists or none (docs/orgkit.md, Writing a kind):
;;   (defguard no-twin
;;     (refuse "\"{o.title}\" is open already; finish it first.")
;;     '(none [o :item {:title (= (input :title)) :state (= "open")}]))
;; A door that takes arguments declares them, and a date is :waymark/date:
;;   :reschedule {:from #{:open} :to :open
;;                :input [:map [:title [:string {:min 1 :max 120}]]
;;                             [:due_on :waymark/date]] ...}
;; A value only the move knows (the day a letter was signed) is that
;; door's input, not a create field filled by an edit after: declare
;; it, write it with field-writer (require [waymark10.resource :as r]),
;; and guard on (input :k), since guards run before the write
;; (docs/orgkit.md, Door-time inputs):
;;   (defguard letter-signed
;;     (refuse "Nothing starts until the letter is signed; give the day, not one still to come.")
;;     '(<= (input :letter_signed_on) (today)))
;;   :engage {:from #{:qualified} :to :engaged
;;            :input [:map [:letter_signed_on :waymark/date]]
;;            :handler (r/field-writer [:letter_signed_on])
;;            :guards [letter-signed] ...}
;; A field named after a kind (request_id) or ending _by holds an id;
;; declare it, or the unref'd-ids check refuses it:
;;   [:request_id {:optional true :kind :request} [:maybe :waymark/ref]]
;;   [:checked_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]

(defresource item
  {:kind :item
   :plural "items"
   :states [:open :done :dropped]
   :initial :open
   :terminal #{:done :dropped}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:assignee {:optional true} [:maybe [:string {:max 80}]]]]
   :filterable {:state #{:eq :in} :assignee #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform's
   ;; decision class): how long a row may wait, and which final doors a
   ;; seat takes on its own. Write the person's own words here
   :decided {:open "An item may stay open for a week; past that, the mayor tells the owner."}
   :actions
   {:complete {:from #{:open} :to :done
               :guards [has-assignee]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A done item stays done; open a new one for more work."}
               :decided "The mayor marks an item done on its own once its assignee has done the work."}
    :drop     {:from #{:open} :to :dropped
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A dropped item is let go; open a new one to ask again."}
               :decided "The mayor drops an item on its own when the work is no longer wanted, and says why."}}})
