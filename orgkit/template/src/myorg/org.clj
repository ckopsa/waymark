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

;; seconds a row may rest in a state its kind gives no :expected-age;
;; nil: such a state never ages
(def default-expected-age 86400)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain {:default-expected-age default-expected-age}
             [item]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
