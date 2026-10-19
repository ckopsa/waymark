(ns dress_rehearsal.kinds.referrer
  "A referrer: a CPA who sends us clients. They receive each finished
  study for their client, and may be owed a referral fee."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource referrer
  {:kind :referrer
   :plural "referrers"
   :states [:active :retired]
   :initial :active
   :terminal #{:retired}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 120}]]
            [:firm {:optional true} [:maybe [:string {:max 120}]]]
            [:email {:optional true} [:maybe [:string {:max 200}]]]
            [:referral_fee_pct {:optional true} [:maybe [:int {:min 0 :max 100}]]]]
   :filterable {:state #{:eq :in}}
   :actions
   {:retire {:from #{:active} :to :retired
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A retired referrer stays on file; add them again to take new referrals."}}}})
