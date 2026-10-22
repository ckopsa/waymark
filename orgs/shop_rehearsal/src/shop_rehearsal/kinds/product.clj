(ns shop_rehearsal.kinds.product
  "A ceramic piece the shop sells; its count on hand is what the storefront may sell."
  (:require [waymark10.dsl :refer [defguard defresource hold]]))

(defguard owner-retires
  (hold "A product leaves the storefront only on the owner's tap."))

(defresource product
  {:kind :product
   :plural "products"
   :states [:listed :retired]
   :initial :listed
   :terminal #{:retired}
   :summary "{data.name} · {data.on_hand} on hand · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 120}]]
            [:line [:enum "mug" "bowl" "planter"]]
            [:price :waymark/money]
            [:low_stock {:optional true} [:maybe :boolean]]]
   ;; a kiln batch posts into on_hand; paying an order reserves, shipping sells, a restocked return comes back
   :accounts {:on_hand  {:unit :count :never-below 0}
              :reserved {:unit :count :never-below 0}
              :sold     {:unit :count :never-below 0}}
   :conserves [:on_hand :reserved :sold]
   :derived {:low_stock {:over [:on_hand] :expr '(< (var :on_hand) 5)}}
   :filterable {:state #{:eq :in} :line #{:eq}}
   :decided {:listed "A product stays listed for as long as the owner makes it; when fewer than 5 are on hand, the mayor tells the owner so she can plan the next firing."}
   :expected-age {:listed 31536000}
   :actions
   {:retire {:from #{:listed} :to :retired
             :guards [owner-retires]
             :safety {:idempotent true :reversible false :confirm false :one-way "A retired product is off the storefront; list it again as a new product."}}}})
