(ns waymark10.same-door-test
  "`:to :same`: one door, allowed from several states, that keeps the
  row's state. The transition resolves it to the state the row stands
  in, so the log holds states; static analysis reads one s→s edge per
  from-state. Needs the waymark10_test database (make db10)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.law-words :as lw]
            [waymark10.machine :as machine]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]))

(def dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

(r/defhandler note-handler [row _inp _ctx]
  (update-in row [:data :notes] (fnil inc 0)))

(def lamp
  (r/resource
   {:kind :lamp
    :states [:lit :dim :off]
    :initial :lit
    :terminal #{:off}
    :summary "{data.label} · {state}"
    :schema [:map
             [:label [:string {:max 80}]]
             [:notes {:optional true} [:maybe :int]]]
    :actions
    {:note {:from #{:lit :dim} :to :same
            :safety {:idempotent false :reversible true :confirm false}
            :handler note-handler}
     :dim {:from #{:lit} :to :dim
           :safety {:idempotent true :reversible true :confirm false}}
     :brighten {:from #{:dim} :to :lit
                :safety {:idempotent true :reversible true :confirm false}}
     :switch_off {:from #{:lit :dim} :to :off
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "An off lamp is history."}}}}))

(def ^:dynamic *eng* nil)

(defn- with-engine [f]
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["lamps" "waymark10_transitions" "waymark10_idempotency"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (binding [*eng* (inv/engine {:storage st :resources [lamp]})]
        (f))
      (finally (pg/close! st)))))

(use-fixtures :once with-engine)

(def opts {:principal (t/principal {:id "colton" :display "Colton"})})

(defn- latest [id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (first (store/transitions (:storage *eng*) tx
                                {:kind :lamp :resource-id id}
                                {:newest-first true :limit 1})))))

(deftest the-declaration-reads-same-as-edges
  (testing "the checks accept it, and the action keeps :same"
    (is (= :same (get-in lamp [:actions :note :to]))))
  (testing "static analysis sees one s→s edge per from-state"
    (is (= #{[#{:dim} :dim] [#{:lit} :lit]}
           (into #{} (comp (filter #(= :note (:name %))) (map (juxt :from :to)))
                 (machine/edges-seq lamp))))
    (is (= :dim (:to (some #(when (= :note (:name %)) %)
                           (machine/transitions-from lamp :dim))))))
  (testing "the law says it stays"
    (is (str/includes? (lw/move-phrase lamp :note) "stays")))
  (testing "a state may not be named same"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/resource (assoc lamp :kind :lamp2 :states [:lit :dim :off :same]))))))

(deftest a-same-door-stays-in-each-state
  (let [id (:id (:row (inv/create! *eng* :lamp {:label "Desk"} opts)))]
    (testing "from lit it stays lit, and the log holds lit"
      (let [{:keys [row]} (inv/invoke! *eng* :lamp id :note nil
                                       (assoc opts :idempotency-key "note-lit"))]
        (is (= :lit (:state row)))
        (is (= 1 (get-in row [:data :notes])))
        (is (= :lit (:to-state (latest id))))))
    (testing "a rehearsal says it would land where the row stands"
      (is (= :lit (:to (inv/invoke! *eng* :lamp id :note nil (assoc opts :dry-run true))))))
    (inv/invoke! *eng* :lamp id :dim nil opts)
    (testing "from dim it stays dim, and the log holds dim"
      (let [{:keys [row]} (inv/invoke! *eng* :lamp id :note nil
                                       (assoc opts :idempotency-key "note-dim"))]
        (is (= :dim (:state row)))
        (is (= 2 (get-in row [:data :notes])))
        (let [rec (latest id)]
          (is (= :dim (:from-state rec)))
          (is (= :dim (:to-state rec))))))
    (testing "a rehearsal from dim says dim"
      (is (= :dim (:to (inv/invoke! *eng* :lamp id :note nil (assoc opts :dry-run true))))))))
