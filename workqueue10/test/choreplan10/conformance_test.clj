(ns choreplan10.conformance-test
  "All four kinds handed to the waymark10 conformance DRIVER: the
  machine walks itself, and every obligation core owes — plus every
  obligation each enrolled module owes — is proved over the real ring
  handler. Until waymark-db9.5 that was seven deftests written out
  here and re-written in three sibling suites; now the suite is one
  call and the obligations live where their surface does
  (waymark10.test.packs). Mirrors eveningplan10.conformance-test's
  shape; the registrations are the only domain-specific part:

  - chore_run's :create needs a real :chore_id (a ref with no
    acceptance set to guide generation — the same reason
    evening_session registers one for its own :plan_id).
  - prep_task is a Mirror, so it registers the same pair paydesk's
    mirrors do: an external-identity create and a wire-shaped
    observe_external document (generation would invent non-JSON).
    Its :complete pushes through main's module fake feed, whose
    push treats a never-seeded doc as a pending task — the walker's
    rows push clean.
  - no state factories: no kind gates a transition behind a
    guard the walk can't satisfy, so the generic shortest-path walk
    reaches every state on its own.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [choreplan10.main :as main]
            [clojure.test :refer [deftest use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.test.suite :as suite]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ["chores" "chore_runs" "days" "prep_tasks"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(declare register-prep-task-examples!)

(use-fixtures :once
  (fn [f]
    ;; one JVM runs every suite since the consolidation cleanup
    ;; (waymark-26j), and mealplan10's conformance registers ITS
    ;; prep_task example (the native kind's) under the same registry
    ;; key — re-pin this suite's mirror-shaped example before walking
    (register-prep-task-examples!)
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        ;; :suppress-mirror-refresh — a Mirror breaks the walker's
        ;; reads-are-pure assumption (a GET on a staged stale row
        ;; would heal it to fresh under the assertions); production
        ;; reads pull through, only this fixture suppresses.
        ;; with-push mirrors production wiring (main/start!): the
        ;; prep_task kind's :complete pushes through the fake
        (let [eng (mirror/with-push
                   (engine/engine {:storage st
                                   :resources (main/check-resources)
                                   :suppress-mirror-refresh true}))]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def kinds [:chore :chore_run :day :prep_task])

;; ── the enrollment ──────────────────────────────────────────────────

(defn- mk! [eng kind body]
  (:row (inv/create! eng kind body {:principal (fac/walker-principal)})))

;; chore_run's :chore_id carries no acceptance set (a create input
;; can't be guided by the target's own enum the way a :cadence/:one-of
;; field can) — evening_session registers the identical shape of
;; example for its own :plan_id
(fac/example-input! :chore_run :create
  (fn [eng]
    (let [chore (mk! eng :chore {:name "Dishes" :cadence "daily"})]
      {:chore_id (:id chore) :due_date "2026-01-06"})))

(fac/example-input! :day :create
  (fn [_] {:date "2026-01-06"}))

;; the mirror pair (paydesk's mirrors register the same shape): an
;; external-identity create, and a wire-shaped observe_external
;; document — generation would invent non-JSON documents. A defn
;; (called at load AND from the fixture) because mealplan10's
;; conformance contests the [:prep_task :create] registry key with
;; the native kind's example — last registration wins, so each
;; suite re-pins its own before it walks.
(defn- register-prep-task-examples! []
  (fac/example-input! :prep_task :create
    (fn [_] {:external_id (str "walk-" (random-uuid))
             :status "pending"}))
  (fac/example-input! :prep_task :observe_external
    {:document {:meal_name "Traeger brisket"
                :date "2026-01-06"
                :task_type "prep"
                :assignee "housekeeper"
                :due_at "2026-01-06T12:00:00Z"
                :duration_minutes 40
                :notes "dice 300g onion; measure the rub"
                :status "pending"}
     :etag "conformance-etag-1"}))

(register-prep-task-examples!)

;; ── the whole suite ─────────────────────────────────────────────────

(deftest conformance
  (suite/check! {:engine *eng* :handler *h* :kinds kinds}))
