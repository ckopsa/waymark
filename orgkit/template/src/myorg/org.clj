(ns myorg.org
  "myorg: the org's module. `resources` is the whole of what this org
  declares, stamped with its domain; the engine boots it, the
  conformance suite judges it. Nothing here is engine code.

  The namespace root is the org's name exactly, underscores included:
  an org named `two_words` is `two_words.org`, never `two-words.org`."
  (:require [myorg.kinds.item :refer [item]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :myorg)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain [item]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
