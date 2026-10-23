(ns week_fixture.org
  "week_fixture: the org's module, the fixture `workqueue10.week-test`
  plays. A homeowner asks for a `job`, the office quotes it once it
  says its budget, and the owner's tap books it."
  (:require [waymark10.dsl :refer [in-domain]]
            [week_fixture.kinds.job :refer [job]]
            [week_fixture.kinds.patient :refer [patient visit]]
            [week_fixture.kinds.supply :refer [supply]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :week_fixture)

;; seconds a row may rest in a state its kind gives no :expected-age;
;; nil: such a state never ages
(def default-expected-age 86400)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain {:default-expected-age default-expected-age}
             [job patient visit supply]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
