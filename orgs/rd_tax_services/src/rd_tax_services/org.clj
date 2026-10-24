(ns rd_tax_services.org
  "rd_tax_services: the org's module. `resources` is the whole of what
  this org declares, stamped with its domain; the engine boots it, the
  conformance suite judges it. Nothing here is engine code.

  A boutique R&D tax credit firm: a `prospect` is qualified and engaged
  once the engagement letter is signed, each client gets one `study` per
  tax year that the principal signs off before it goes to the client's
  `referring_cpa`, the fee is an `invoice`, and the firm's best guesses
  (fee, team, tools, reminder schedule) are `assumption`s the principal
  confirms."
  (:require [rd_tax_services.kinds.assumption :refer [assumption]]
            [rd_tax_services.kinds.invoice :refer [invoice]]
            [rd_tax_services.kinds.prospect :refer [prospect]]
            [rd_tax_services.kinds.referring-cpa :refer [referring-cpa]]
            [rd_tax_services.kinds.study :refer [study]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :rd_tax_services)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain [prospect study invoice referring-cpa assumption]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
