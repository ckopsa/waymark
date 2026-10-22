(ns waymark10.store-postgres-has-test
  "store/query-rows over Postgres: a where value {:has v} reads as
  array membership (data->'f' ?| ARRAY[v]), the read the frozen
  attachment guard leans on to find a listed ref. frozen-in-test pins
  it over the in-memory twin; this pins the SQL. Real Postgres."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private ana (t/principal {:id "ana" :type :human :display "Ana"}))

(def ^:private bundle
  (r/resource
   {:kind :ph_bundle
    :plural "ph_bundles"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:tags [:vector :string]]]
    :actions {:close {:from #{:open} :to :closed
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Closed is history."}}}}))

(def ^:private tables
  ["ph_bundles" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_observations"])

(defn- with-engine [f]
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st))))
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine {:storage st :resources [bundle]}))
      (finally (pg/close! st)))))

(defn- titles [eng where]
  (let [st (:storage eng)]
    (->> (store/with-tx st #(store/query-rows st % :ph_bundle where {:limit 100}))
         (map (comp :title :data))
         set)))

(deftest has-matches-a-row-whose-vector-holds-the-value
  (with-engine
    (fn [eng]
      (doseq [[title tags] [["both" ["red" "blue"]]
                            ["red-only" ["red"]]
                            ["none" []]]]
        (inv/create! eng :ph_bundle {:title title :tags tags} {:principal ana}))
      (testing "a row listing v matches; one that does not is excluded"
        (is (= #{"both"} (titles eng {:tags {:has "blue"}})))
        (is (= #{"both" "red-only"} (titles eng {:tags {:has "red"}}))))
      (testing "a value no row lists matches nothing"
        (is (= #{} (titles eng {:tags {:has "green"}}))))
      (testing "{:has v} composes with a state clause"
        (is (= #{"both" "red-only"}
               (titles eng {:state :open :tags {:has "red"}})))))))
