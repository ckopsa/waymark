(ns waymark10.unique-index-test
  "Declared :unique reaches storage (design §24, the plan_day demand):
  kind-projection emits one UNIQUE index per group over the promoted
  generated columns; the migrate planner reconciles it like any
  declared index (virgin create, drift add, undeclared drop); and the
  index's refusal surfaces as an honest 409 problem, never a 500.
  Real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world: one booking per (room, night) ────────────────────────

(defn- booking-map [& {:keys [unique?] :or {unique? true}}]
  (cond-> {:kind :ux_booking
           :plural "ux_bookings"
           :states [:held :released]
           :initial :held
           :terminal #{:released}
           :summary "{data.room} · {data.night} · {state}"
           :schema [:map
                    [:room {:filter #{:eq}} [:string {:min 1 :max 20}]]
                    [:night {:filter #{:eq :range}} :waymark/date]]
           :actions {:release {:from #{:held} :to :released
                               :safety {:idempotent true :reversible false
                                        :confirm false
                                        :one-way "Released is history."}}}}
    unique? (assoc :unique [[:room :night]])))

(def ^:private booking (r/resource (booking-map)))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(def ^:private tables
  ["ux_bookings" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_observations"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- live-indexes [st]
  (store/with-tx st
    (fn [tx]
      (into #{}
            (map :pg_indexes/indexname)
            (jdbc/execute! tx ["SELECT indexname FROM pg_indexes
                                WHERE tablename = 'ux_bookings'"])))))

;; ── 1. the projection and the planner ───────────────────────────────

(deftest unique-index-projects-and-reconciles
  (testing "the projection names the index"
    (is (contains? (:indexes (store/kind-projection booking))
                   "ux_ux_bookings_room_night"))
    (is (str/includes? (get (:indexes (store/kind-projection booking))
                            "ux_ux_bookings_room_night")
                       "CREATE UNIQUE INDEX")))
  (fresh!)
  (let [st (pg/storage db/dsn)]
    (try
      (testing "a virgin apply! creates it"
        (let [steps (migrate/plan st [booking])]
          (migrate/apply! st steps {:destructive? false})
          (is (contains? (live-indexes st) "ux_ux_bookings_room_night"))))
      (testing "a live table missing it draws an :add-index step"
        (store/with-tx st
          (fn [tx] (jdbc/execute! tx ["DROP INDEX ux_ux_bookings_room_night"])))
        (let [steps (migrate/plan st [booking])]
          (is (some #(and (= :add-index (:kind %))
                          (str/includes? (:sql %) "ux_ux_bookings"))
                    steps))
          (migrate/apply! st steps {:destructive? false})
          (is (contains? (live-indexes st) "ux_ux_bookings_room_night"))))
      (testing "an undeclared :unique reconciles away under drop-derived?"
        (let [steps (migrate/plan st [(r/resource (booking-map :unique? false))])]
          (is (some #(and (= :drop-index (:kind %))
                          (str/includes? (:sql %) "ux_ux_bookings"))
                    steps))))
      (finally (pg/close! st)))))

;; ── 2. the honest 409 ───────────────────────────────────────────────

(deftest a-duplicate-refuses-as-a-conflict-problem
  (fresh!)
  (let [st (pg/storage db/dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [booking]})
            h (engine/handler eng)
            hdrs {"x-waymark-principal" "elena"
                  "content-type" "application/json"}
            post #(h {:request-method :post :uri "/api/ux_bookings"
                      :headers hdrs :body (wire/write-json %)})
            body {:room "cabin" :night "2026-08-01"}]
        (is (= 201 (:status (post body))))
        (let [resp (post body)
              problem (wire/read-json (:body resp))]
          (is (= 409 (:status resp)) "the index refusal is a conflict, not a 500")
          (is (str/includes? (str (:detail problem)) "already exists")))
        (testing "the released row still blocks — uniqueness is the
                  row's identity, not its state (a design fact worth
                  pinning: release then rebook the same night refuses)"
          (let [rows (store/with-tx st
                       (fn [tx] (store/query-rows st tx :ux_booking
                                                  {} {:limit 5})))]
            (inv/invoke! eng :ux_booking (:id (first rows)) :release nil
                         {:principal elena})
            (is (= 409 (:status (post body)))))))
      (finally (pg/close! st)))))
