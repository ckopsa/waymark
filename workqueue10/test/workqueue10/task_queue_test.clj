(ns workqueue10.task-queue-test
  "The family's one queue, end to end over the ring handler:
  choreplan10 and mealplan10 (played by the scriptable fake sources —
  which run the SAME push-plan the real HTTP boundaries run) each
  carry open work → one discovery pass mints EVERY row into the one
  :task kind → the queue filters and cross-domain-ranks them in a
  single list (the thing the day-board's panes could never do) →
  prioritize is a hub-local write the authorities never hear as a
  change (:noop push, freshness for free) and :document :partial
  keeps every later pull's hands off the ranking → Done routes back
  to whichever engine owns the row → a task dropped upstream refuses
  with the guard's sentence → a refused push lands conflicted and
  resolve_conflict keep=local pushes it clean → a down source
  degrades per-source, never queue-wide.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Duration Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["tasks"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *chores* nil)
(def ^:dynamic *meals* nil)
(def ^:dynamic *todos* nil)
(def ^:dynamic *clock* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          chores (conf/fake-source)
          meals (conf/fake-source)
          todos (conf/fake-source)
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
                                   :resources (main/resources
                                               {"chore" chores "meal" meals
                                                "todo" todos})
                                   :now-fn (fn [] @clock)}))]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *chores* chores
                    *meals* meals
                    *todos* todos
                    *clock* clock]
            (f)))
        (finally (pg/close! st))))))

(defn- tick!
  "Advance the engine's clock."
  [^Duration d]
  (swap! *clock* (fn [^Instant t] (.plus t d))))

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
  (let [resp (req :get (str "/api/tasks" query))]
    (is (= 200 (:status resp)) (str query ": " (:body resp)))
    (get-in (json resp) [:data :items])))

(defn- task-by-title [title]
  (or (first (filter #(str/includes? (str (:summary %)) title) (items-of "")))
      (throw (ex-info (str "no task titled " title) {}))))

(defn- act!
  ([self action] (act! self action nil))
  ([self action body]
   (let [resp (req :post (str self "/-/" (name action)) body)]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- act-fenced!
  "An edit-shaped write rides the fence: read the row, present its
  ETag."
  [self action body]
  (let [etag (get-in (req :get self) [:headers "ETag"])
        resp (req :post (str self "/-/" (name action)) body
                  {"if-match" etag})]
    (is (= 200 (:status resp))
        (str self " " (name action) ": " (:status resp) " " (:body resp)))
    (json resp)))

(defn- refuse! [self action body status]
  (let [resp (req :post (str self "/-/" (name action)) body)]
    (is (= status (:status resp))
        (str self " " (name action) " answered " (:status resp)
             ", wanted " status ": " (:body resp)))
    (json resp)))

(defn- source-status [fake id]
  (get-in @(:state fake) [:docs id :status]))

;; ── the story ───────────────────────────────────────────────────────

(deftest the-family-work-queue
  ;; Tuesday morning: both engines carry open work. The fakes hold
  ;; CANONICAL docs — they play the boundary post-translation.
  (conf/seed! *chores* "cr-dishes"
              {:title "Dishes" :assignee "colton"
               :due_at "2026-07-22T00:00:00Z" :status "open"
               :detail "load and run before bed"
               :source_href "https://rod.kopsa.info/api/chore_runs/cr-dishes"
               :source_ui_href "https://rod.kopsa.info/api/-/ui#/api/chore_runs/cr-dishes"})
  (conf/seed! *chores* "cr-mow"
              {:title "Mow the lawn" :assignee "colton"
               :due_at "2026-07-20T00:00:00Z" :status "open"})
  (conf/seed! *meals* "pt-thaw"
              {:title "thaw: Traeger brisket" :assignee "housekeeper"
               :due_at "2026-07-21T16:00:00Z" :status "open"
               :detail "move 2500g brisket to the fridge"})
  (conf/seed! *meals* "pt-brine"
              {:title "prep: Chicken brine" :assignee "colton"
               :due_at "2026-07-21T19:00:00Z" :status "open"})
  (conf/seed! *meals* "pt-slaw"
              {:title "prep: Coleslaw" :assignee "housekeeper"
               :due_at "2026-07-21T23:00:00Z" :status "dropped"})
  (conf/seed! *todos* "todo.woodworking/uid-chisels"
              {:title "Sharpen chisels" :status "open"
               :detail "Woodworking"})

  (testing "one discovery pass mints EVERY source's rows into the one kind"
    (is (= 6 (mirror/discover! *eng* :task)))
    (is (= 6 (count (items-of "")))))

  (testing "one queue, one filter grammar — across domains"
    (is (= 2 (count (items-of "?source=chore"))))
    (is (= 3 (count (items-of "?source=meal"))))
    (is (= 1 (count (items-of "?source=todo"))))
    (is (= 5 (count (items-of "?status=open"))))
    (is (= 3 (count (items-of "?assignee=colton"))))
    (is (= 1 (count (items-of "?overdue=true")))
        "only the lawn survived the weekend unmowed"))

  (testing "the mirrored facts landed standalone, source stamped"
    (let [dishes (json (req :get (:self (task-by-title "Dishes"))))]
      (is (= "fresh" (:state dishes)))
      (is (= "chore" (get-in dishes [:data :source])))
      (is (= "open" (get-in dishes [:data :status])))
      (is (= "2026-07-22T00:00:00Z" (get-in dishes [:data :due_at])))
      (is (= "load and run before bed" (get-in dishes [:data :detail])))
      (testing "…and the way back: the origin link, an external hop to
                the engine that owns the row"
        (is (= {:href "https://rod.kopsa.info/api/-/ui#/api/chore_runs/cr-dishes"
                :external true
                :summary "The row this task mirrors, at the engine that owns it"}
               (select-keys (get-in dishes [:links :origin])
                            [:href :external :summary]))))))

  (testing "a source that stamps no href relates to nothing — the
            origin link omits, never renders broken"
    (let [mow (json (req :get (:self (task-by-title "Mow the lawn"))))]
      (is (nil? (get-in mow [:links :origin])))))

  (testing "cross-domain ranking: a meal task and a chore, prioritized
            AGAINST each other in one ordered list"
    (act-fenced! (:self (task-by-title "thaw: Traeger brisket"))
                 :prioritize {:priority 1})
    (act-fenced! (:self (task-by-title "Dishes"))
                 :prioritize {:priority 2})
    (is (= ["thaw: Traeger brisket · open" "Dishes · open"]
           (mapv :summary (take 2 (items-of "?status=open"))))
        "priority is the default sort; the unranked tail rides behind")
    (is (= "open" (source-status *meals* "pt-thaw"))
        "prioritize is the hub's own fact — the authority heard a
        :noop, never a change"))

  (testing "Done routes to whichever engine owns the row"
    (let [done (act! (:self (task-by-title "Dishes")) :complete)]
      (is (= "fresh" (:state done)))
      (is (= "done" (get-in done [:data :status])))
      (is (= "done" (source-status *chores* "cr-dishes"))))
    (act! (:self (task-by-title "thaw: Traeger brisket")) :complete)
    (is (= "done" (source-status *meals* "pt-thaw")))
    (act! (:self (task-by-title "Sharpen chisels")) :complete)
    (is (= "done" (source-status *todos* "todo.woodworking/uid-chisels"))))

  (testing "a task the source dropped refuses with the guard's sentence"
    (let [p (refuse! (:self (task-by-title "prep: Coleslaw"))
                     :complete nil 409)]
      (is (= "A task the source dropped does not complete — the authority already let it go."
             (:detail p)))))

  (testing "a refused push lands conflicted with the adapter's words;
            a person resolves, and keep=local pushes it clean"
    (let [brine-self (:self (task-by-title "prep: Chicken brine"))]
      (conf/fail-pushes! *meals* "the planner is mid-revision")
      (let [res (act! brine-self :complete)]
        (is (= "conflicted" (:state res)))
        (is (= "done" (get-in res [:data :status]))
            "the local document stands — the queue's truth is not lost")
        (is (= "the planner is mid-revision"
               (get-in res [:data :conflict_reason]))))
      (is (= "open" (source-status *meals* "pt-brine"))
          "the authority never heard the refused push")
      (conf/fail-pushes! *meals* false)
      (let [resolved (act! brine-self :resolve_conflict {:keep "local"})]
        (is (= "fresh" (:state resolved)))
        (is (= "done" (source-status *meals* "pt-brine"))))))

  (testing "a down source degrades per-source: the other engine's work
            still discovers"
    (conf/down! *chores* true)
    (conf/seed! *meals* "pt-rest"
                {:title "cook: Rest the brisket" :assignee "colton"
                 :due_at "2026-07-22T01:00:00Z" :status "open"})
    (is (= 1 (mirror/discover! *eng* :task))
        "the meal row minted; the chore feed just skipped a pass")
    (conf/down! *chores* false))

  (testing "the :partial guarantee: an upstream change pulls through,
            and the hub's ranking survives it untouched"
    (let [mow-self (:self (task-by-title "Mow the lawn"))]
      (act-fenced! mow-self :prioritize {:priority 3})
      ;; the authority revises its side of the row (new etag)…
      (conf/seed! *chores* "cr-mow"
                  {:title "Mow the lawn" :assignee "colton"
                   :due_at "2026-07-20T00:00:00Z" :status "open"
                   :detail "bag the clippings this time"})
      ;; …and the next read past the TTL pulls it through
      (tick! (Duration/ofMinutes 10))
      (let [mow (json (req :get mow-self))]
        (is (= "fresh" (:state mow)))
        (is (= "bag the clippings this time" (get-in mow [:data :detail]))
            "the authority's change landed")
        (is (= 3 (get-in mow [:data :priority]))
            "the pull kept its hands off the hub's own fact"))))

  (testing "a row the source DELETED drops — the :on-gone policy: out
            of the open queue, kept as record, never a ghost"
    (let [mow-self (:self (task-by-title "Mow the lawn"))
          open-before (count (items-of "?status=open"))]
      (conf/remove! *chores* "cr-mow")
      (mirror/resync! *eng* :task)
      (let [mow (json (req :get mow-self))]
        (is (= "fresh" (:state mow))
            "a deletion observed is freshness, not an outage")
        (is (= "dropped" (get-in mow [:data :status])))
        (is (= 3 (get-in mow [:data :priority]))
            "the record stands — only the declared patch moved"))
      (is (= (dec open-before) (count (items-of "?status=open")))
          "the ghost left the worklist")
      (testing "…and completing it refuses with the guard's sentence"
        (let [p (refuse! mow-self :complete nil 409)]
          (is (= "A task the source dropped does not complete — the authority already let it go."
                 (:detail p)))))))

  (testing "CAPTURE: a task born at the queue, pushed to the authority
            that will own it — the uid claimed back"
    (let [resp (req :post "/api/tasks" {:title "Buy sandpaper"}
                    {"idempotency-key" (str (random-uuid))})
          env (json resp)]
      (is (contains? #{200 201} (:status resp)) (:body resp))
      (is (= "fresh" (:state env)))
      (is (= "todo" (get-in env [:data :source]))
          "unsaid, the birth defaults to the capture authority")
      (is (= "open" (get-in env [:data :status])))
      (let [xid (get-in env [:data :external_id])
            local-id (second (str/split (str xid) #":" 2))]
        (is (str/starts-with? (str xid) "todo:cap-")
            "the authority minted, claim_external stamped")
        (is (= "open" (source-status *todos* local-id))
            "the todo exists at the authority — capture is real")
        (testing "…and the captured task completes like any other"
          (act! (:self env) :complete)
          (is (= "done" (source-status *todos* local-id)))))))

  (testing "a refused birth lands conflicted with NO external id;
            keep=local retries the create once the source recovers"
    (conf/fail-pushes! *todos* "the list is mid-migration")
    (let [resp (req :post "/api/tasks" {:title "Oil the door hinge"}
                    {"idempotency-key" (str (random-uuid))})
          env (json resp)]
      (is (= "conflicted" (:state env)))
      (is (nil? (get-in env [:data :external_id])))
      (is (= "the list is mid-migration"
             (get-in env [:data :conflict_reason])))
      (conf/fail-pushes! *todos* false)
      (let [resolved (act! (:self env) :resolve_conflict {:keep "local"})]
        (is (= "fresh" (:state resolved)))
        (is (some? (get-in resolved [:data :external_id])))
        (is (= "open" (source-status *todos*
                       (second (str/split
                                (get-in resolved [:data :external_id])
                                #":" 2))))))))

  (testing "the waymark engines take no births — the schema refuses at
            the door, before any push"
    (let [resp (req :post "/api/tasks" {:title "Ghost chore" :source "chore"}
                    {"idempotency-key" (str (random-uuid))})]
      (is (= 422 (:status resp))
          "a chore is born of its own engine's law, never captured"))))
