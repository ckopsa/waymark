(ns waymark10.runner-links-test
  "The runner link (runner-pool work, piece 1a): a person makes one,
  its token is in no read and no log, a restate without a token keeps
  the old one, and an agent's hand is refused.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:dynamic *eng* nil)

(def ^:private tables
  ["runner_links" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st :resources []})]
          (f))
        (finally (pg/close! st))))))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))

(def ^:private a-url "https://api.example.test/v1/routines/trig_01/fire")
(def ^:private a-token "sk-routine-first-token-0123456789")
(def ^:private a-second-token "sk-routine-second-token-9876543210")

(defn- row-of [id]
  (let [rdef (get (inv/resources *eng*) :runner_link)]
    (some->> (store/with-tx (:storage *eng*)
               (fn [tx]
                 (store/load-row (:storage *eng*) tx :runner_link (str id) {})))
             (inv/decode-row rdef))))

(defn- log-of [id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (store/transitions (:storage *eng*) tx
                         {:kind :runner_link :resource-id (str id)} {}))))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- restate! [id body principal]
  (let [row (row-of id)]
    (inv/invoke! *eng* :runner_link id :restate body
                 {:principal principal
                  :if-match (inv/etag :runner_link id (:version row))})))

(defn- make-link! [principal]
  (:row (inv/create! *eng* :runner_link
                     {:provider "claude_routine"
                      :fire_url a-url
                      :fire_token a-token}
                     {:principal principal})))

(deftest the-token-is-the-one-secret
  (is (= #{:fire_token} (schema/secret-fields (:schema rl/runner-link)))))

(deftest a-person-makes-a-link-and-its-token-is-never-read-back
  (let [link (make-link! colton)
        id (:id link)]
    (testing "the engine holds the token"
      (is (= "live" (name (:state (row-of id)))))
      (is (= a-token (get-in (row-of id) [:data :fire_token]))))
    (testing "no read carries it"
      (let [rdef (get (inv/resources *eng*) :runner_link)
            env (render/envelope rdef (row-of id)
                                 {:principal colton
                                  :now ((:now-fn *eng*))})]
        (is (not (contains? (get env "data") "fire_token")))
        (is (not (str/includes? (pr-str env) a-token)))))
    (testing "a restate without a token keeps the old one"
      (restate! id {:cap {:runs 5 :window_seconds 18000}} colton)
      (let [row (row-of id)]
        (is (= a-token (get-in row [:data :fire_token])))
        (is (= {:runs 5 :window_seconds 18000} (get-in row [:data :cap])))))
    (testing "a restate with a token replaces it"
      (restate! id {:token a-second-token} colton)
      (is (= a-second-token (get-in (row-of id) [:data :fire_token]))))
    (testing "an agent may not re-token"
      (is (= :a-person-writes-the-token
             (:guard (refusal #(restate! id {:token a-token} clerk)))))
      (is (= a-second-token (get-in (row-of id) [:data :fire_token]))))
    (testing "the log says what was done, without the value"
      (let [log (log-of id)]
        (is (some #(= :restate (:action %)) log))
        (is (not (str/includes? (pr-str log) a-token)))
        (is (not (str/includes? (pr-str log) a-second-token)))))
    (testing "retire and restore"
      (inv/invoke! *eng* :runner_link id :retire nil {:principal colton})
      (is (= "retired" (name (:state (row-of id)))))
      (inv/invoke! *eng* :runner_link id :restore nil {:principal colton})
      (is (= "live" (name (:state (row-of id))))))))

(deftest an-agent-does-not-make-a-link
  (is (= :a-person-makes-the-link
         (:guard (refusal #(make-link! clerk))))))
