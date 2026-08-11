(ns choreplan10.prep-task-mirror-test
  "The housekeeper's kitchen slice, end to end over the ring handler:
  mealplan10 (played by the scriptable fake feed — which runs the
  SAME push-plan translation the real HTTP boundary runs) finalizes
  a week → discovery mints the housekeeper's tasks and fills them
  through the batch pull → the worklist filters by status and
  task_type, with :overdue derived locally → marking a step Done
  pushes the planner's own :complete back through the boundary → a
  step the cook cancelled refuses with the guard's sentence → a
  refused push lands the row conflicted with the adapter's words,
  and resolve_conflict keep=local pushes it clean once the feed
  recovers.

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
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["chores" "chore_runs" "prep_tasks"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *feed* nil)
(def ^:dynamic *clock* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (mp/fake-feed)
          clock (atom (Instant/parse "2026-07-21T08:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        ;; with-push mirrors production wiring (main/start!): the
        ;; local Done is nothing until the post-commit pass pushes it
        (let [eng (mirror/with-push
                   (engine/engine {:storage st
                                   :resources (main/resources feed)
                                   :now-fn (fn [] @clock)}))]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *feed* feed
                    *clock* clock]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (the family-week pattern) ─────────────────────────

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

(defn- items-of [query]
  (let [resp (req :get (str "/api/prep_tasks" query))]
    (is (= 200 (:status resp)) (str query ": " (:body resp)))
    (get-in (json resp) [:data :items])))

(defn- act!
  ([self action] (act! self action nil))
  ([self action body]
   (let [resp (req :post (str self "/-/" (name action)) body)]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- refuse! [self action body status]
  (let [resp (req :post (str self "/-/" (name action)) body)]
    (is (= status (:status resp))
        (str self " " (name action) " answered " (:status resp)
             ", wanted " status ": " (:body resp)))
    (json resp)))

(defn- feed-status [xid]
  (get-in @(:state *feed*) [:docs xid :status]))

;; ── the story ───────────────────────────────────────────────────────

(deftest the-housekeeper-kitchen-slice
  ;; Sunday: the plan finalized upstream; the AI minted the week's
  ;; tasks with the housekeeper's name on the prep half
  (mp/seed! *feed* "pt-thaw"
            {:meal_name "Traeger brisket" :date "2026-07-21"
             :task_type "thaw" :assignee "housekeeper"
             :due_at "2026-07-21T16:00:00Z"
             :notes "move 2500g brisket to the fridge" :status "pending"})
  (mp/seed! *feed* "pt-prep"
            {:meal_name "Traeger brisket" :date "2026-07-21"
             :task_type "prep" :assignee "housekeeper"
             :due_at "2026-07-21T19:00:00Z" :duration_minutes 40
             :notes "measure 60g rub; trim the fat cap" :status "pending"})
  (mp/seed! *feed* "pt-slaw"
            {:meal_name "Coleslaw" :date "2026-07-21"
             :task_type "prep" :assignee "housekeeper"
             :due_at "2026-07-21T23:00:00Z" :status "cancelled"})

  (testing "discovery mints the feed's rows, filled through the batch pull"
    (is (= 3 (mirror/discover! *eng* :prep_task)))
    (is (= 3 (count (items-of "")))))

  (testing "the worklist's filters: the planner's state as data, and
            the locally derived overdue"
    (is (= 2 (count (items-of "?status=pending"))))
    (is (= 1 (count (items-of "?task_type=thaw"))))
    (is (empty? (items-of "?overdue=true"))))

  (let [thaw-self (:self (first (items-of "?task_type=thaw")))
        thaw (json (req :get thaw-self))]
    (testing "the mirrored facts landed standalone — no join, no refs"
      (is (= "fresh" (:state thaw)))
      (is (= "pending" (get-in thaw [:data :status])))
      (is (= "Traeger brisket" (get-in thaw [:data :meal_name])))
      (is (= "2026-07-21T16:00:00Z" (get-in thaw [:data :due_at])))
      (is (false? (get-in thaw [:data :overdue])))))

  (testing "Done pushes the planner's own complete through the boundary"
    (let [prep-self (:self (first (items-of "?task_type=prep&status=pending")))
          done (act! prep-self :complete)]
      (is (= "fresh" (:state done)))
      (is (= "done" (get-in done [:data :status])))
      ;; the authority heard about it — the translation ran
      (is (= "done" (feed-status "pt-prep")))))

  (testing "a step the cook cancelled refuses with the guard's sentence"
    (let [slaw-self (:self (first (items-of "?status=cancelled")))
          p (refuse! slaw-self :complete nil 409)]
      (is (= "A step the cook cancelled does not complete — the plan already let it go."
             (:detail p)))))

  (testing "a refused push lands conflicted with the adapter's words;
            a person resolves, and keep=local pushes it clean"
    (let [thaw-self (:self (first (items-of "?task_type=thaw")))]
      (mp/fail-pushes! *feed* "the planner is mid-revision")
      (let [res (act! thaw-self :complete)]
        (is (= "conflicted" (:state res)))
        (is (= "done" (get-in res [:data :status]))
            "the local document stands — the kitchen's truth is not lost")
        (is (= "the planner is mid-revision"
               (get-in res [:data :conflict_reason]))))
      (is (= "pending" (feed-status "pt-thaw"))
          "the authority never heard the refused push")
      (mp/fail-pushes! *feed* false)
      (let [resolved (act! thaw-self :resolve_conflict {:keep "local"})]
        (is (= "fresh" (:state resolved)))
        (is (= "done" (get-in resolved [:data :status])))
        (is (nil? (get-in resolved [:data :conflict_reason])))
        (is (= "done" (feed-status "pt-thaw")))))))
