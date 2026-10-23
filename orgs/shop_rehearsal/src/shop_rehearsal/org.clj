(ns shop_rehearsal.org
  "shop_rehearsal: a small online shop selling handmade ceramics, retail and wholesale."
  (:require [shop_rehearsal.kinds.product :refer [product]]
            [shop_rehearsal.kinds.kiln-batch :refer [kiln-batch]]
            [shop_rehearsal.kinds.order :refer [order]]
            [shop_rehearsal.kinds.payment :refer [payment]]
            [shop_rehearsal.kinds.shipping-label :refer [shipping-label]]
            [shop_rehearsal.kinds.return-request :refer [return-request]]
            [shop_rehearsal.kinds.refund :refer [refund]]
            [shop_rehearsal.kinds.breakage-claim :refer [breakage-claim]]
            [shop_rehearsal.kinds.wholesale-account :refer [wholesale-account]]
            [shop_rehearsal.kinds.invoice :refer [invoice]]
            [shop_rehearsal.kinds.wholesale-order :refer [wholesale-order]]
            [shop_rehearsal.kinds.monthly-close :refer [monthly-close]]
            [shop_rehearsal.kinds.tax-rate :refer [tax-rate]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :shop_rehearsal)

;; seconds a row may rest in a state its kind gives no :expected-age
(def default-expected-age 86400)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain {:default-expected-age default-expected-age :house-accounts {:made {:unit :count} :payables {:unit [:money "USD"]}
                     :supplier_costs {:unit [:money "USD"]} :sales {:unit [:money "USD"]} :checkout {:unit [:money "USD"]}
                     :processor_in {:unit [:money "USD"]} :fees {:unit [:money "USD"]}}}
             [product kiln-batch order payment shipping-label
              return-request refund breakage-claim
              wholesale-account invoice wholesale-order monthly-close tax-rate]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
