(ns workqueue10.factory-only-boot-test
  "FACTORY10_ONLY=1 (epic 00680418, self-host): the factory boots
  without the household. Each test empties the database, boots an
  engine with :auto-migrate over the kinds a boot serves, and reads
  the well-known: the factory-only boot lists the factory's kinds and
  the engine's seat and no household kind; the default boot still
  lists the household's.

  Needs a Postgres database; WAYMARK10_TEST_DSN names it.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.factory-only-boot-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]))

(defn- empty-database!
  "Every table in the schema, dropped: the boot starts from nothing."
  [st]
  (store/with-tx st
    (fn [tx]
      (doseq [{:keys [tablename]}
              (jdbc/execute! tx ["SELECT tablename FROM pg_tables WHERE schemaname = current_schema()"]
                             {:builder-fn rs/as-unqualified-lower-maps})]
        (jdbc/execute! tx [(str "DROP TABLE IF EXISTS \"" tablename "\" CASCADE")])))))

(defn- booted-kinds
  "The :kinds the well-known lists, from an engine booted over
  `resources` on an empty database."
  [resources]
  (let [st (pg/storage db/dsn)]
    (try
      (empty-database! st)
      (let [eng (engine/engine {:storage st
                                :resources resources
                                :auto-migrate true
                                :suppress-mirror-refresh true})
            resp ((engine/handler eng)
                  {:request-method :get :uri "/api/.well-known/waymark"
                   :headers {"x-waymark-principal" "colton"
                             "x-waymark-actor-type" "human"}})
            body (:body resp)]
        (is (= 200 (:status resp)))
        (set (:kinds (wire/read-json (if (string? body) body (slurp body))))))
      (finally (pg/close! st)))))

(defn- household? [k] (or (str/starts-with? k "chore") (str/starts-with? k "meal")))

(deftest a-factory-only-boot-serves-the-factory-and-no-household
  (let [kinds (booted-kinds (main/factory-only-resources))]
    (is (every? kinds ["ticket" "change" "repo_policy" "seat"])
        (pr-str (sort kinds)))
    (is (empty? (filter household? kinds))
        (pr-str (sort kinds)))))

(deftest the-factory-boot-serves-promises-and-writes-its-defaults-watched
  (let [st (pg/storage db/dsn)]
    (try
      (empty-database! st)
      (let [eng (engine/engine {:storage st
                                :resources (main/factory-only-resources)
                                :auto-migrate true
                                :suppress-mirror-refresh true})]
        (is (contains? (inv/resources eng) :promise))
        (is (pos? (live/default-promises! eng)) "the boot writes defaults")
        (let [ds (store/with-tx st #(live/default-promises eng %))]
          (is (seq ds))
          (is (every? #(= "watch" (:mode %)) (vals ds))
              "every default is born watched, none enforced")))
      (finally (pg/close! st)))))

(deftest both-boots-serve-the-amendment-kind
  (is (contains? (booted-kinds (main/factory-only-resources)) "amendment"))
  (is (contains? (booted-kinds (main/check-resources)) "amendment")))

(deftest the-default-boot-still-serves-the-household
  (let [kinds (booted-kinds (main/check-resources))]
    (is (every? kinds ["chore" "chore_run" "meal"])
        (pr-str (sort kinds)))))
