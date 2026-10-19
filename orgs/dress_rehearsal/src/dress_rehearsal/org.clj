(ns dress_rehearsal.org
  "dress_rehearsal: an R&D tax credit study firm. `resources` is the
  whole of what this org declares, stamped with its domain; the engine
  boots it, the conformance suite judges it. Nothing here is engine code."
  (:require [dress_rehearsal.kinds.referrer :refer [referrer]]
            [dress_rehearsal.kinds.engagement :refer [engagement]]
            [dress_rehearsal.kinds.study :refer [study]]
            [dress_rehearsal.kinds.document-request :refer [document-request]]
            [dress_rehearsal.kinds.invoice :refer [invoice]]
            [dress_rehearsal.kinds.assumption :refer [assumption]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :dress_rehearsal)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain [referrer engagement study document-request invoice assumption]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
