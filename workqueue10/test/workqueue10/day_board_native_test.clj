(ns workqueue10.day-board-native-test
  "The day board over NATIVE prep tasks — the meals fold's one
  behavioral change (waymark-bwu.2): the :prep member filters real
  prep_task states (pending/scheduled), no longer a mirror's
  status-as-data. Chore runs keep their half of the sheet; the
  cancelled and the future stay off it. The standalone choreplan
  suite keeps the runs-side story; THIS test owns the prep side now.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [calendar10.source :as gcal]
            [next.jdbc :as jdbc]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the world: the WHOLE domestic registry, one engine ──────────────

(def ^:private tables
  ["tasks" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (mirror/with-push
                   (engine/engine
                    {:storage st
                     :resources (main/resources
                                 {"chore" (conf/fake-source)
                                  "meal" (conf/fake-source)
                                  "todo" (conf/fake-source)}
                                 (gcal/fake-calendar))
                     :surfaces main/surfaces}))]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def colton-headers {"x-waymark-principal" "colton"})

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers colton-headers}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- created! [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (is (= 201 (:status resp)) (str plural ": " (:body resp)))
    (json resp)))

(defn- act! [self action body]
  (let [resp (req :post (str self "/-/" (name action)) body)]
    (is (= 200 (:status resp))
        (str self " " (name action) ": " (:status resp) " " (:body resp)))
    (json resp)))

;; ── the story ───────────────────────────────────────────────────────

(deftest the-day-board-joins-native-prep-tasks
  (let [plan (created! "plans" {:start_date "2026-07-20"})
        plan-id (last (str/split (:self plan) #"/"))
        prep! (fn [m] (created! "prep_tasks" (assoc m :plan_id plan-id)))
        ;; one pending for the day, one cancelled, one for later
        _ (prep! {:date "2026-07-21" :meal_name "Traeger brisket"
                  :task_type "prep" :assignee "housekeeper"
                  :due_at "2026-07-21T19:00:00Z"})
        gone (prep! {:date "2026-07-21" :meal_name "Coleslaw"
                     :task_type "prep" :assignee "housekeeper"
                     :due_at "2026-07-21T18:00:00Z"})
        _ (prep! {:date "2026-07-24" :meal_name "Tacos"
                  :task_type "thaw" :assignee "housekeeper"
                  :due_at "2026-07-24T08:00:00Z"})
        _ (act! (:self gone) :cancel nil)
        day (created! "days" {:date "2026-07-21"})
        day-id (last (str/split (:self day) #"/"))
        resp (req :get (str "/api/surfaces/day-board/" day-id))
        _ (is (= 200 (:status resp)) (:body resp))
        board (json resp)]
    (testing "prep: real states filter the sheet — the cancelled and
              the future stay off it"
      (is (= #{"prep · Traeger brisket (2026-07-21) · Pending"}
             (->> (get-in board [:members :prep :items])
                  (map :summary)
                  set))))))
