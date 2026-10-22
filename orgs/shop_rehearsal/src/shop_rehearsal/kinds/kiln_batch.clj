(ns shop_rehearsal.kinds.kiln-batch
  "Pieces of one product fired together; the sound ones go into its stock."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]
            [waymark10.resource :as r]))

(defguard names-product
  (refuse "A kiln batch adds stock to one product; name the product first.")
  '(is-set (input :product_id)))
(defguard counts-sound
  (refuse "Count the sound pieces out of the kiln before they go into stock.")
  '(is-set (input :sound_pieces)))

(defresource kiln-batch
  {:kind :kiln_batch
   :plural "kiln_batches"
   :states [:firing :stocked :lost]
   :initial :firing
   :terminal #{:stocked :lost}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:product_id {:optional true :kind :product} [:maybe :waymark/ref]]
            [:pieces [:int {:min 1}]]
            [:sound_pieces {:optional true} [:maybe [:int {:min 0}]]]
            ;; the clay supplier is an outside party; what its clay cost posts as a supplier cost on stock
            [:supplier {:optional true :kind :member :role "supplier"} [:maybe :waymark/ref]]
            [:clay_cost :waymark/money]
            [:fired_on {:optional true :record-only true} [:maybe :waymark/date]]]
   :filterable {:state #{:eq :in} :product_id #{:eq}}
   :decided {:firing "A batch may stay in the kiln for three days of firing and cooling; past that, the mayor tells the owner."}
   :expected-age {:firing 259200}
   :actions
   {:stock {:from #{:firing} :to :stocked
            :input [:map
                    [:product_id {:kind :product} :waymark/ref]
                    [:sound_pieces [:int {:min 0}]]]
            :handler (r/field-writer [:product_id :sound_pieces])
            :guards [names-product counts-sound]
            :posts '[{:move (input :sound_pieces) :from [:house :made] :to [:product (input :product_id) :on_hand]}
                     {:move (data :clay_cost) :from [:house :payables] :to [:house :supplier_costs]}]
            :safety {:idempotent true :reversible false :confirm false :one-way "A stocked batch has been counted into its product; fire a new batch for more."}
            :decided "The mayor stocks a batch on its own once the owner has counted its sound pieces."}
    :lose  {:from #{:firing} :to :lost
            :safety {:idempotent true :reversible false :confirm false :one-way "A lost batch adds nothing to stock."}
            :decided "The mayor marks a batch lost on its own when the owner says the whole firing failed."}}})

(defscenario not-stocked-without-sound-count "A fired batch is not stocked until its sound pieces are counted."
  {:kind :kiln_batch :attempt :stock :input {:product_id "mug"} :row {:state :firing :data {:title "October mug firing" :pieces 20}} :expect {:refused :counts-sound}})
