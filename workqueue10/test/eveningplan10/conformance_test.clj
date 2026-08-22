(ns eveningplan10.conformance-test
  "All three kinds handed to the waymark10 conformance DRIVER: the
  machine walks itself, and every obligation core owes — plus every
  obligation each enrolled module owes — is proved over the real ring
  handler. Until waymark-db9.5 that was seven deftests written out
  here and re-written in three sibling suites; now the suite is one
  call and the obligations live where their surface does
  (waymark10.test.packs). Mirrors mealplan10.conformance-test's
  shape; the registrations are the only domain-specific part:

  - evening_session's :create needs a real :plan_id (a ref with no
    acceptance set to guide generation — the same reason prep_task
    registers one for its own :plan_id).
  - evening_session's :preparing/:active/:complete states need
    window_minutes set (a :while-open field, not a create input)
    before :lock-in's schedule-fits guard allows it — the generic
    shortest-path walk can't discover \"call a self-loop editor
    first,\" so it's a hand-written state factory, same reason
    mealplan10's :plan/:grocery_list have one.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [eveningplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.dev :as dev]
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
  ["activities" "evening_plans" "evening_sessions"
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

(def kinds [:activity :evening_plan :evening_session])

;; ── the enrollment ──────────────────────────────────────────────────

(def ^:private plan-start "2026-01-06")

(defn- mk! [eng kind body]
  (:row (inv/create! eng kind body {:principal (fac/walker-principal)})))

(defn- step!
  "mealplan10's own step! never needed :if-match — none of its actions
  are fenced (that app doesn't use the :fields sugar's generated
  editors). evening_session's update_fields_in_* / update_support_in_*
  are, so this one supplies the current etag, same as dev/act!."
  ([eng kind id action] (step! eng kind id action nil))
  ([eng kind id action body]
   (let [adef (get-in (get (inv/resources eng) kind) [:actions action])
         opts (if (get-in adef [:safety :fence])
                {:principal (fac/walker-principal)
                 :if-match (inv/etag kind (str id)
                                     (:version (dev/row eng kind id)))}
                {:principal (fac/walker-principal)})]
     (inv/invoke! eng kind id action body opts))))

;; evening_session's :plan_id carries no acceptance set (a create
;; input can't be guided by the target's own enum the way a
;; :capacity/:one-of field can) — waymark9/mealplan10 register the
;; identical shape of example for prep_task's plan_id
(fac/example-input! :evening_session :create
  (fn [eng]
    (let [plan (mk! eng :evening_plan {:start_date plan-start
                                       :end_date "2026-01-10"})]
      {:plan_id (:id plan) :date plan-start})))

(fac/state-factory! :evening_session
  (fn [eng target]
    (let [plan (mk! eng :evening_plan {:start_date plan-start
                                       :end_date "2026-01-10"})
          s (mk! eng :evening_session {:plan_id (:id plan) :date plan-start})
          sid (:id s)]
      (case target
        :staged s
        (do (step! eng :evening_session sid :update_fields_in_staged
                   {:window_minutes 90})
            (let [res (step! eng :evening_session sid :lock-in)]
              (case target
                :preparing (:row res)
                :active (:row (step! eng :evening_session sid :start))
                :complete (do (step! eng :evening_session sid :start)
                              (:row (step! eng :evening_session sid :finish))))))))))

;; ── the whole suite ─────────────────────────────────────────────────

(deftest conformance
  (suite/check! {:engine *eng* :handler *h* :kinds kinds}))
