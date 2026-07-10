(ns mealplan10.batch-e-recurrence-test
  "Batch E at the dogfood level: a weekly recital seeded on the
  FakeEvents calendar (through the real expander) discovers into one
  event row PER OCCURRENCE, and plan conflicts flip on exactly the
  weeks an occurrence lands — the EXDATE'd week stays clear. The same
  plans prove the previous_plan wiring: each new week links the one
  before it, over the wire. Needs the batch-E database:
  WAYMARK10_TEST_DSN=…waymark10_ext_test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mealplan10.event-source :as es]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world (the family-week fixture, batch-E database) ───────────

(def ^:private tables
  ["meals" "rotations" "plans" "grocery_lists" "prep_tasks" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *feed* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (es/fake-events)
          clock (atom (Instant/parse "2026-07-08T12:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources feed)
                                  :surfaces main/surfaces
                                  :now-fn (fn [] @clock)})]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *feed* feed]
            (f)))
        (finally (pg/close! st))))))

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers {"x-waymark-principal" "priya"}}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- created! [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (is (= 201 (:status resp)) (str plural " create: " (:body resp)))
    (json resp)))

(defn- id-of [env] (last (str/split (:self env) #"/")))

;; ── the recital recurs; the plans feel the right weeks ──────────────

(deftest recurring-recital-and-chained-plans
  ;; Wednesdays 7-15, 7-22, 7-29 — except the 22nd is EXDATE'd
  ;; (the recital hall is closed that week)
  (let [ids (es/seed-recurring! *feed* "uid-recital"
                                {:title "Piano recital"
                                 :kind "blocking"
                                 :dtstart "2026-07-15"
                                 :rrule "FREQ=WEEKLY;COUNT=3"
                                 :exdates ["2026-07-22"]
                                 :from "2026-07-01" :to "2026-10-01"})]
    (is (= ["uid-recital@2026-07-15" "uid-recital@2026-07-29"] ids))

    (testing "discovery mints one event row per occurrence"
      (is (= 2 (mirror/discover! *eng* :event)))
      (let [page (json (req :get "/api/events?kind=blocking&sort=date"))
            items (get-in page [:data :items])]
        (is (= 2 (get-in page [:data :total])))
        (is (= ["Piano recital · 2026-07-15" "Piano recital · 2026-07-29"]
               (mapv :summary items))
            "each occurrence is its own dated resource")))

    ;; three Tuesday-to-Tuesday weeks: only the recital weeks conflict
    (let [w1 (created! "plans" {:start_date "2026-07-14" :weeks 1})
          w2 (created! "plans" {:start_date "2026-07-21" :weeks 1})
          w3 (created! "plans" {:start_date "2026-07-28" :weeks 1})
          fresh #(json (req :get (:self %)))]
      (testing "conflicts flip on exactly the occurrence weeks"
        (is (= 1 (get-in (fresh w1) [:data :calendar_conflicts])))
        (is (true? (get-in (fresh w1) [:data :has_conflicts])))
        (is (= 0 (get-in (fresh w2) [:data :calendar_conflicts]))
            "the EXDATE'd week stays clear — the series did not smear")
        (is (false? (get-in (fresh w2) [:data :has_conflicts])))
        (is (= 1 (get-in (fresh w3) [:data :calendar_conflicts])))
        (is (true? (get-in (fresh w3) [:data :has_conflicts]))))

      (testing "previous_plan chains at create (design E7, over the wire)"
        (is (nil? (get-in w1 [:data :previous_plan]))
            "the first week follows nothing")
        (is (= (id-of w1) (get-in w2 [:data :previous_plan])))
        (is (= (id-of w2) (get-in w3 [:data :previous_plan])))))))
