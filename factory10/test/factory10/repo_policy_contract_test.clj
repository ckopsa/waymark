(ns factory10.repo-policy-contract-test
  "factory10's repo_policy keeps the kit's policy contract
  (`factorykit.repo-policy-contract`, ticket 50fd9aba). Each clause
  gets a memory engine of its own from `dev/scratch!`. factory10 names
  no `:fixed` field: every stated field is restated.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is]]
            [factory10.main :as main]
            [factory10.resources.repo-policy :refer [repo-policy]]
            [factorykit.repo-policy-contract :as contract]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def ^:private hands
  {:person (t/principal {:id "colton" :display "Colton Kopsa"})
   :model (t/principal {:id "a-model" :type :agent})})

(defn- a-hand []
  (let [eng (dev/scratch! (vec (main/resources)))
        row (fn [id]
              (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx :repo_policy (str id) {}))))]
    {:declaration repo-policy
     :fixed []
     :mint! (fn [hand fields]
              (:row (inv/create! eng :repo_policy fields {:principal (hands hand)})))
     :row row
     :walk! (fn [hand id door input]
              (:row (inv/invoke! eng :repo_policy (str id) door input
                                 {:principal (hands hand)
                                  :if-match (inv/etag :repo_policy (str id)
                                                      (:version (row id)))})))}))

(deftest factory10s-policy-keeps-the-kits-contract
  (is (= [] (contract/failures a-hand))))
