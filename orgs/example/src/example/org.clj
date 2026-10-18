(ns example.org
  "example: the org's module. `resources` is the whole of what this org
  declares, stamped with its domain; the engine boots it, the
  conformance suite judges it. Nothing here is engine code.

  A tiny landscaping office: a customer's `request` comes in, the
  office accepts or declines it, and accepted work becomes a
  `work_order` a crew does."
  (:require [example.kinds.request :refer [request]]
            [example.kinds.work-order :refer [work-order]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :example)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain [request work-order]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
