(ns dayplan10.conformance-test
  "All four kinds handed to the waymark10 conformance DRIVER: the
  machine walks itself, and every obligation core owes — plus every
  obligation each enrolled module owes — is proved over the real ring
  handler. Mirrors eveningplan10.conformance-test's shape; the
  registrations are the only domain-specific part:

  - context's :create carries windows the schema can only call
    five-character strings; the guard wants HH:MM pairs in order, so a
    generated body would refuse — a hand-written example, with a
    fresh name each time because names are :unique.
  - day_plan's :create is :unique over (member, date), and the
    walker's seeded generation would mint the same body twice — a
    fresh member per call.
  - block's :create wants a plan that exists and is open
    (on-an-open-days-plan reads :day_plan), so the example stages one.
  - span's :create wants a block and its plan (labels ride the refs),
    and a window clear of anything the plan already holds — the
    example stages both and opens late in the evening.

  The conformance-tier scenarios span.clj, day_plan.clj and block.clj
  declare (every span door reads the plan's other spans) are proved
  here too, through the HTTP door, by the :core/law-scenarios
  obligation.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [dayplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.test.suite :as suite]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ["contexts" "day_plans" "blocks" "spans"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources)})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def kinds [:context :day_plan :block :span])

;; ── the enrollment ──────────────────────────────────────────────────

(def ^:private plan-date "2026-01-06")   ; a Tuesday — a workday

(defn- mk! [eng kind body]
  (:row (inv/create! eng kind body {:principal (fac/walker-principal)})))

(fac/example-input! :context :create
  (fn [_]
    {:name (str "Walked context " (random-uuid))
     :default_shapes ["workday"]
     :default_spans [{:from "09:00" :to "12:00"} {:from "13:00" :to "17:00"}]
     :default_order 1}))

(fac/example-input! :day_plan :create
  (fn [_]
    {:date plan-date :member (str (random-uuid))}))

;; a block by hand: a plan of its own (so nothing the walker minted
;; elsewhere shares its windows) and a context, no windows — spans are
;; the walk's to add
(fac/example-input! :block :create
  (fn [eng]
    (let [plan (mk! eng :day_plan {:date plan-date :member (str (random-uuid))})
          context (mk! eng :context {:name (str "Walked block context " (random-uuid))
                                     :default_shapes ["off"]
                                     :default_spans [{:from "19:00" :to "21:00"}]
                                     :default_order 5})]
      {:plan_id (:id plan) :context_id (:id context)})))

;; a span by hand: its block's plan is a workday plan whose walked
;; contexts occupy the daytime, so the example opens late
(fac/example-input! :span :create
  (fn [eng]
    (let [plan (mk! eng :day_plan {:date plan-date :member (str (random-uuid))})
          context (mk! eng :context {:name (str "Walked span context " (random-uuid))
                                     :default_shapes ["off"]
                                     :default_spans [{:from "19:00" :to "21:00"}]
                                     :default_order 5})
          block (mk! eng :block {:plan_id (:id plan) :context_id (:id context)})]
      {:block_id (:id block) :plan_id (:id plan)
       :starts_at (str plan-date "T22:00:00Z")
       :ends_at (str plan-date "T23:00:00Z")})))

;; ── the whole suite ─────────────────────────────────────────────────

(deftest conformance
  (suite/check! {:engine *eng* :handler *h* :kinds kinds}))
