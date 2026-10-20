(ns shop_rehearsal.kinds.kiln-batch
  "A kiln batch: pieces of one product the owner threw and fired
  together. Once unloaded, the sound pieces are added to the product's
  stock."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard names-product
  (refuse "A kiln batch adds stock to one product; name the product first.")
  '(present? :product_id))

(defguard counts-sound
  (refuse "Count the sound pieces out of the kiln before they go into stock.")
  '(present? :sound_pieces))

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
            [:fired_on {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :product_id #{:eq}}
   :decided {:firing "A batch may stay in the kiln for three days of firing and cooling; past that, the mayor tells the owner."}
   :expected-age {:firing 259200}
   :actions
   {:stock {:from #{:firing} :to :stocked
            :guards [names-product counts-sound]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A stocked batch has been counted into its product; fire a new batch for more."}
            :decided "The mayor stocks a batch on its own once the owner has counted its sound pieces."}
    :lose  {:from #{:firing} :to :lost
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A lost batch adds nothing to stock."}
            :decided "The mayor marks a batch lost on its own when the owner says the whole firing failed."}}})
