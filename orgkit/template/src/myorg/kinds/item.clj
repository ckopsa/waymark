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
