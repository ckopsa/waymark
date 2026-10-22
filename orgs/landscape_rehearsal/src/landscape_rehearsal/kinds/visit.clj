(ns landscape_rehearsal.kinds.visit
  "A visit: one date of a contract's season, made by the contract's
  visits rule. After it is made it is an ordinary row. Its photos are
  the business's record, kept as documents: :finish waits for two of
  them stored (:evidence), the documents step 8 of the film."
  (:require [waymark10.dsl :refer [defresource defscenario]]))

(def ^:private safety
  {:idempotent true :reversible false :confirm false
   :one-way "A visit done or skipped stays so; the contract makes the next one."})

(defn- photographed [n]
  {:given (vec (for [i (range 1 (inc n))]
                 {:kind :attachment :handle (keyword (str "p" i)) :state :stored
                  :data {:name (str "yard-" i ".jpg") :media_type "image/jpeg"}}))
   :row {:state :planned
         :data {:contract_id "contract-parks" :visit_on "2027-04-06"
                :photos (vec (for [i (range 1 (inc n))] (str "{given/p" i "}")))}}})

(defscenario a-visit-with-one-photo-is-not-finished
  "The Parks' visit has one photo of the yard, so it is not finished: it needs two."
  (merge {:kind :visit :attempt :finish
          :expect {:refused :photos-is-filed :because "This visit needs 2 photos to be finished; it has 1."}}
         (photographed 1)))

(defscenario a-visit-with-two-photos-is-finished
  "The Parks' visit has two photos of the yard, both stored, so it is finished."
  (merge {:kind :visit :attempt :finish :expect {:allowed true}}
         (photographed 2)))

(defresource visit
  {:kind :visit
   :plural "visits"
   :states [:planned :done :skipped]
   :initial :planned
   :terminal #{:done :skipped}
   :summary "{data.visit_on} · {state}"
   :schema [:map
            [:contract_id {:kind :contract} :waymark/ref]
            [:visit_on :waymark/date]
            [:crew {:optional true :kind :team} [:maybe :waymark/ref]]
            [:photos {:optional true :waymark/document {:accept ["image/*"] :min 2 :personal false}}
             [:vector :waymark/ref]]]
   :filterable {:state #{:eq :in} :contract_id #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:planned "A visit is planned 14 days ahead; three weeks unvisited, the mayor tells the owner."}
   :expected-age {:planned 1814400}
   :actions
   {:finish {:from #{:planned} :to :done :safety safety
             :evidence {:photos {}}
             :decided "The mayor finishes a visit on its own once the crew has been."}
    :skip {:from #{:planned} :to :skipped :safety safety
           :decided "The mayor skips a visit on its own when the customer asks, and says why."}}
   :scenarios [a-visit-with-one-photo-is-not-finished a-visit-with-two-photos-is-finished]})
