(ns vanilla_plus.org
  "vanilla_plus: the org's module. `resources` is the whole of what this
  org declares, stamped with its domain; the engine boots it, the
  conformance suite judges it. Nothing here is engine code.

  Vanilla Plus is the household's Classic+ for its private World of
  Warcraft 1.12.1 server: level 60 stays the cap and the world keeps
  growing. Each new piece of the world is one `content` row, from an
  idea through its design and its build in ckopsa/vanilla-plus to the
  live world."
  (:require [vanilla_plus.kinds.ask :refer [ask]]
            [vanilla_plus.kinds.content :refer [content]]
            [vanilla_plus.kinds.release :refer [release]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :vanilla_plus)

;; seconds a row may rest in a state its kind gives no :expected-age;
;; nil: such a state never ages
(def default-expected-age 604800)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain {:default-expected-age default-expected-age}
             [content release ask]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
