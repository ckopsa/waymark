(ns myorg.kinds.item
  "An item: one piece of the org's work, open until it is done or
  dropped. Copy this file once per kind, rename it, and say each
  guard's law as a sentence a person can read."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-assignee
  (refuse "An item is done by someone; name who first.")
  '(present? :assignee))

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
   :actions
   {:complete {:from #{:open} :to :done
               :guards [has-assignee]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A done item stays done; open a new one for more work."}}
    :drop     {:from #{:open} :to :dropped
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A dropped item is let go; open a new one to ask again."}}}})
