(ns dress_rehearsal.org
  "dress_rehearsal: the org's module. `resources` is the whole of what
  this org declares, stamped with its domain; the engine boots it, the
  conformance suite judges it. Nothing here is engine code.

  A boutique R&D tax credit firm: a `prospect` is qualified and signs
  an engagement letter, each client gets one `study` per tax year that
  a specialist prepares and the principal signs off, and a delivered
  study is billed with an `invoice`."
  (:require [dress_rehearsal.kinds.prospect :refer [prospect]]
            [dress_rehearsal.kinds.study :refer [study]]
            [dress_rehearsal.kinds.invoice :refer [invoice]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :dress_rehearsal)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain [prospect study invoice]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
