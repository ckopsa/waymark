(ns choreplan10.day-board-test
  "The housekeeper's screen, end to end over the ring handler: chores
  queue runs (one overdue, one due today, one for later), the meal
  planner's feed mints prep tasks (one pending today, one the cook
  cancelled, one for later), a run gets Done — and the day board
  composes exactly the still-actionable work due by the day: the
  overdue backlog and today's items, never the future, never the
  archive. The members' :where standing filters (the framework
  feature this board demanded) are what keep done runs and cancelled
  steps off the sheet.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [choreplan10.main :as main]
            [choreplan10.mirror.mealplan :as mp]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["chores" "chore_runs" "days" "prep_tasks"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *h* nil)
(def ^:dynamic *eng* nil)
(def ^:dynamic *feed* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (mp/fake-feed)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (mirror/with-push
                   (engine/engine {:storage st
                                   :resources (main/resources feed)
                                   :surfaces main/surfaces}))]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *feed* feed]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(def colton-headers {"x-waymark-principal" "colton"})

(defn- req
  ([method uri] (req method uri nil {}))
  ([method uri body] (req method uri body {}))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers (merge colton-headers headers)}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- created! [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (is (= 201 (:status resp)) (str plural ": " (:body resp)))
    (json resp)))

(defn- act!
  ([self action body] (act! self action body {}))
  ([self action body headers]
   (let [resp (req :post (str self "/-/" (name action)) body headers)]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- queue! [chore-self due]
  (act! chore-self :queue {:due_date due}
        {"idempotency-key" (str (random-uuid))}))

;; ── the story ───────────────────────────────────────────────────────

(deftest the-day-board-shows-the-still-actionable-day
  ;; the standing chores, and their queued runs: one slipped, one due
  ;; on the day, one queued for later in the week, one already done
  (let [vacuum (created! "chores" {:name "Vacuum" :cadence "weekly"})
        bathrooms (created! "chores" {:name "Bathrooms" :cadence "weekly"})
        _ (queue! (:self vacuum) "2026-07-19")      ; slipped — overdue
        _ (queue! (:self bathrooms) "2026-07-21")   ; the day's work
        _ (queue! (:self bathrooms) "2026-07-23")   ; later — not yet
        _ (queue! (:self vacuum) "2026-07-20")      ; done below — archive
        ;; complete the 07-20 vacuum run (queue! answers the CHORE's
        ;; envelope, so find the run by its due date)
        runs (get-in (json (req :get "/api/chore_runs?state=due"))
                     [:data :items])
        by-due (fn [d] (first (filter #(str/includes? (:summary %) d) runs)))]
    (act! (:self (by-due "2026-07-20")) :complete nil)

    ;; the kitchen's half: the planner holds one pending step for
    ;; the day, one the cook cancelled, one for later in the week
    (mp/seed! *feed* "pt-prep"
              {:meal_name "Traeger brisket" :date "2026-07-21"
               :task_type "prep" :assignee "housekeeper"
               :due_at "2026-07-21T19:00:00Z" :status "pending"})
    (mp/seed! *feed* "pt-gone"
              {:meal_name "Coleslaw" :date "2026-07-21"
               :task_type "prep" :assignee "housekeeper"
               :due_at "2026-07-21T18:00:00Z" :status "cancelled"})
    (mp/seed! *feed* "pt-later"
              {:meal_name "Tacos" :date "2026-07-24"
               :task_type "thaw" :assignee "housekeeper"
               :due_at "2026-07-24T08:00:00Z" :status "pending"})
    (is (= 3 (mirror/discover! *eng* :prep_task)))

    ;; the day sheet, and its board
    (let [day (created! "days" {:date "2026-07-21"})
          day-id (last (str/split (:self day) #"/"))
          resp (req :get (str "/api/surfaces/day-board/" day-id))
          _ (is (= 200 (:status resp)) (:body resp))
          board (json resp)
          member-summaries (fn [m] (->> (get-in board [:members m :items])
                                        (map :summary)
                                        set))]
      (testing "the surface names itself and carries the anchor whole"
        (is (= "day-board" (:name board)))
        (is (= "2026-07-21 · Open" (get-in board [:anchor :summary]))))

      (testing "runs: the overdue backlog and the day's work — never
                the future, never the archive"
        (is (= #{"Vacuum · 2026-07-19 · Due"
                 "Bathrooms · 2026-07-21 · Due"}
               (member-summaries :runs))))

      (testing "prep: the planner's still-open steps dated by the day
                — the cancelled and the future stay off the sheet"
        (is (= #{"prep · Traeger brisket (2026-07-21) · pending"}
               (member-summaries :prep))))

      (testing "the well-known document advertises the board"
        (is (= "/api/surfaces/day-board/{anchor-id}"
               (get-in (json (req :get "/api/.well-known/waymark"))
                       [:surfaces :day-board :href])))))))
