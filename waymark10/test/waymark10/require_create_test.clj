(ns waymark10.require-create-test
  "require-fact at CREATE (design §24 — the phase-2 promise in
  guards/require's own docstring, delivered): with no row to judge,
  the bound derived spec's law computes over the validated create
  input, so v9's create_guards = (require(\"distinct\"),) spells the
  same in v10 and self-substitution is unrepresentable at birth. An
  aggregate-spec require still allows at create (nothing to compute
  before the row exists). Real Postgres."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private pair
  (r/resource
   {:kind :rc_pair
    :plural "rc_pairs"
    :states [:suggested :accepted :retired]
    :initial :suggested
    :terminal #{:retired}
    :summary "{data.a} → {data.b} · {state}"
    :schema [:map
             [:a [:string {:min 1 :max 40}]]
             [:b [:string {:min 1 :max 40}]]
             [:distinct {:optional true
                         :derived {:over [:a :b]
                                   :expr '(not= (var :a) (var :b))}}
              [:maybe :boolean]]
             [:kids {:optional true
                     :derived {:count {:owns :rc_kid :where {:state #{"open"}}}}}
              [:maybe :int]]]
    :owns [{:kind :rc_kid :via :pair_id}]
    :create-guards [(g/require :distinct
                               {:explain "A thing cannot stand in for itself."})]
    :actions {:accept {:from #{:suggested} :to :accepted
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Accepted is acknowledged."}}
              :retire {:from #{:accepted :suggested} :to :retired
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Retired is history."}}}}))

(def ^:private kid
  (r/resource
   {:kind :rc_kid
    :plural "rc_kids"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:pair_id {:kind :rc_pair :filter #{:eq}} :waymark/ref]]
    :actions {:finish {:from #{:open} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

(def ^:private tables
  ["rc_pairs" "rc_kids" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_observations"])

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(deftest require-judges-the-create-input
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st))))
  (let [st (pg/storage db/dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [pair kid]})]
        (testing "the self-pair is unrepresentable at birth — 409"
          (let [e (try (inv/create! eng :rc_pair {:a "butter" :b "butter"}
                                    {:principal elena})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e))
            (is (re-find #"cannot stand in for itself" (ex-message e)))))
        (testing "the distinct pair is born, its fact materialized"
          (let [row (:row (inv/create! eng :rc_pair {:a "butter" :b "oil"}
                                       {:principal elena}))]
            (is (true? (get-in row [:data :distinct])))
            (is (= 0 (get-in row [:data :kids]))
                "the maintained aggregate starts honest"))))
      (finally (pg/close! st)))))
