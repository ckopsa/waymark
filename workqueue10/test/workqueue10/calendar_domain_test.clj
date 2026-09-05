(ns workqueue10.calendar-domain-test
  "The calendar as its own domain, end to end over the ring handler
  (waymark-6k5.2): :calendar joins :queue/:chores/:meals in the
  well-known → an event is BORN here and the create-push mints it on
  the authority, which stamps the identity home → reschedule is a
  local write the post-commit pass carries back → a refused push
  lands conflicted, and resolve_conflict is the way out → and the
  meal plan still sees the calendar across the domain seam, which is
  the one thing moving the kind could quietly have broken.

  The calendar is played by calendar10's scriptable fake, which runs
  the same protocol the API v3 transport does.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [calendar10.source :as gcal]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mealplan10.main]
            [next.jdbc :as jdbc]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private tables
  ["tasks" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *cal* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          cal (gcal/fake-calendar)
          clock (atom (Instant/parse "2026-07-21T08:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        ;; with-push mirrors production wiring (main/start!): a local
        ;; write is nothing until the post-commit pass carries it
        (let [eng (mirror/with-push
                   (engine/engine
                    {:storage st
                     :resources (main/resources
                                 {"chore" (conf/fake-source)
                                  "meal" (conf/fake-source)
                                  "todo" (conf/fake-source)}
                                 cal)
                     :surfaces main/surfaces
                     :now-fn (fn [] @clock)}))]
          (binding [*eng* eng *h* (engine/handler eng) *cal* cal]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(def ^:private colton {"x-waymark-principal" "colton"})

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers colton}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

;; every helper answers the ENVELOPE (:self, :state, :data), the shape
;; the other suites read
(defn- created! [coll body]
  (let [resp (req :post (str "/api/" coll) body)]
    (is (= 201 (:status resp)) (str coll " create: " (:body resp)))
    (json resp)))

(defn- get! [self]
  (json (req :get self)))

(defn- act!
  "reschedule is edit-shaped, so the framework fences it: an honest
  client re-reads the row and presents its ETag. Unfenced doors ignore
  the header, so one helper serves both."
  ([self action] (act! self action nil))
  ([self action body]
   (let [etag (get-in (req :get self) [:headers "ETag"])
         [path query] (str/split (str self "/-/" (name action)) #"\?" 2)
         resp (*h* (cond-> {:request-method :post :uri path
                            :headers (cond-> colton
                                       etag (assoc "if-match" etag))}
                     query (assoc :query-string query)
                     body (assoc :body (wire/write-json body))))]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:body resp)))
     (json resp))))

;; ── the domain announces itself ─────────────────────────────────────

(deftest the-calendar-is-a-domain
  (let [well-known (json (req :get "/api/.well-known/waymark"))
        domains (set (:domains well-known))]
    (is (contains? domains "calendar")
        (str "the well-known's domains: " (pr-str domains)))
    (testing "and it joins the domains that were already there"
      ;; :evenings joined with the consolidation cleanup (waymark-26j);
      ;; :day with the day plan (waymark-i89n) — the household's one
      ;; engine, domain by domain
      (is (= #{"queue" "media" "chores" "meals" "calendar" "evenings" "day"}
             domains)))))

;; ── the birth: scheduling onto the family calendar ──────────────────

(deftest an-event-born-here-reaches-the-authority
  (let [before (gcal/pushes *cal*)
        event (created! "events" {:title "Parent-teacher conference"
                                  :all_day false
                                  :starts_at "2026-08-04T23:00:00Z"
                                  :ends_at "2026-08-04T23:30:00Z"
                                  :location "Room 12"})
        self (:self event)]
    (testing "the create-push reached the calendar"
      (is (= (inc before) (gcal/pushes *cal*))
          "one create, pushed by the post-commit pass"))

    (let [row (get! self)]
      (testing "and the authority's identity came home"
        (is (some? (get-in row [:data :external_id]))
            "claim_external stamps the id the calendar minted")
        (is (str/starts-with? (str (get-in row [:data :external_id])) "family:")
            "tagged by calendar, so a second calendar costs no migration"))
      (is (= "fresh" (:state row))
          "a create that lands is fresh, not conflicted"))

    (testing "the document the calendar stored is the one we authored"
      (let [xid (get-in (get! self) [:data :external_id])
            stored (gcal/stored *cal* xid)]
        (is (= "Parent-teacher conference" (:title stored)))
        (is (= "Room 12" (:location stored)))))))

(deftest a-birth-must-say-when
  (testing "an all-day event with no date is refused at the door"
    (let [resp (req :post "/api/events" {:title "Someday" :all_day true})]
      (is (= 409 (:status resp)) (:body resp))
      (is (str/includes? (str (:body resp)) "Say when")
          (str "the guard's own sentence: " (:body resp)))))
  (testing "a timed event with no start likewise"
    (let [resp (req :post "/api/events" {:title "Someday" :all_day false})]
      (is (= 409 (:status resp)) (:body resp)))))

(deftest an-end-before-its-start-is-refused
  (let [resp (req :post "/api/events" {:title "Backwards" :all_day true
                                       :date "2026-08-10"
                                       :end_date "2026-08-08"})]
    (is (= 409 (:status resp)) (:body resp))
    (is (str/includes? (str (:body resp)) "cannot end before it begins"))))

(deftest a-one-day-event-ends-the-day-it-starts
  (let [event (created! "events" {:title "Picnic" :all_day true
                                  :date "2026-08-08"})]
    (is (= "2026-08-08" (get-in event [:data :end_date]))
        "on-create closes the day rather than leaving a half-open row")
    (is (= "note" (get-in event [:data :kind]))
        "waymark's own additions do not claim the evening unless asked")
    (is (= "family" (get-in event [:data :calendar])))))

;; ── the local write, carried back ───────────────────────────────────

(deftest a-reschedule-reaches-the-calendar
  (let [event (created! "events" {:title "Dentist" :all_day true
                                  :date "2026-08-12"})
        self (:self event)
        xid (get-in (get! self) [:data :external_id])
        before (gcal/pushes *cal*)
        moved (act! self :reschedule {:all_day true :date "2026-08-13"
                                      :end_date "2026-08-13"})]
    (is (= "2026-08-13" (get-in moved [:data :date])))
    (is (= (inc before) (gcal/pushes *cal*)) "the move was pushed, not merely stored")
    (is (= "2026-08-13" (:date (gcal/stored *cal* xid)))
        "and the family's calendar carries the new day")
    (is (= "fresh" (:state moved)))))

(deftest a-reschedule-moves-only-what-it-names
  (let [event (created! "events" {:title "Haircut" :all_day false
                                  :starts_at "2026-08-14T16:00:00Z"
                                  :ends_at "2026-08-14T17:00:00Z"
                                  :location "Barber"})
        self (:self event)
        moved (act! self :reschedule {:all_day false
                                      :starts_at "2026-08-14T18:00:00Z"
                                      :ends_at "2026-08-14T19:00:00Z"})]
    (is (= "Barber" (get-in moved [:data :location]))
        "a move is not a re-authoring — the fields it does not name stand")
    (is (= "Haircut" (get-in moved [:data :title])))))

;; ── the failure a person has to resolve ─────────────────────────────

(deftest a-refused-push-lands-conflicted
  (let [event (created! "events" {:title "Book club" :all_day true
                                  :date "2026-08-20"})
        self (:self event)]
    (gcal/fail-pushes! *cal* "the event changed under our push")
    (try
      (let [moved (act! self :reschedule {:all_day true :date "2026-08-21"
                                          :end_date "2026-08-21"})]
        (is (= "conflicted" (:state moved))
            "the local document stands and the state tells the truth")
        (is (str/includes? (str (get-in moved [:data :conflict_reason]))
                           "under our push")
            "the adapter's own sentence renders, rather than a bare flag"))
      (finally (gcal/fail-pushes! *cal* false)))

    (testing "resolve_conflict keep=local pushes ours again"
      (let [resolved (act! self :resolve_conflict {:keep "local"})]
        (is (= "fresh" (:state resolved)))
        (is (= "2026-08-21" (get-in resolved [:data :date])))))))

;; ── cancel: only what we put there ──────────────────────────────────

(deftest we-may-cancel-what-we-scheduled
  (let [event (created! "events" {:title "Vet appointment" :all_day true
                                  :date "2026-09-01"})
        self (:self event)
        xid (get-in (get! self) [:data :external_id])]
    (is (true? (get-in event [:data :born_here]))
        "a local birth reaches on-create with no external_id yet — that is
         the marker, and :document :partial keeps a later sync from
         clearing it")

    (let [cancelled (act! self :cancel)]
      (is (true? (get-in cancelled [:data :cancelled])))
      (testing "and the event is off the family's calendar, not merely flagged"
        (is (not (some #{xid} (mirror/discover *cal*)))
            "the authority no longer carries it")
        (is (= :gone (get (mirror/pull-many *cal* [xid]) xid))
            "a deletion the feed reports as gone, like any other")))))

(deftest we-may-not-cancel-what-the-family-put-there
  ;; the whole point of the marker: an event discovered from the
  ;; family's own calendar is not ours to delete
  (gcal/seed! *cal* "family:theirs-2026-09-05"
              {:title "Grandma's birthday" :date "2026-09-05"
               :end_date "2026-09-05" :kind "blocking" :all_day true})
  (mirror/discover! *eng* :event)
  (let [found (->> (get-in (json (req :get "/api/events?page%5Bsize%5D=50"))
                           [:data :items])
                   (filter #(= "Grandma's birthday" (get-in % [:fields :title])))
                   first)
        self (:self found)]
    (is (some? self) "the discovered event is here")
    (is (not (true? (get-in (get! self) [:data :born_here])))
        "a discovery mint is born WITH its external_id, so it is not ours")

    (let [resp (req :post (str self "/-/cancel"))]
      (is (= 409 (:status resp)) (:body resp))
      (is (str/includes? (str (:body resp)) "came from the family's calendar")
          (str "the guard's own sentence: " (:body resp))))

    (testing "and the event is untouched on the calendar"
      (is (some #{"family:theirs-2026-09-05"} (mirror/discover *cal*))))))

;; ── the seam the move could have broken ─────────────────────────────

(deftest the-meal-plan-still-sees-the-calendar
  ;; plan cites :event through a date-containment predicate
  ;; (plan.clj:307). The kind now lives in another module and another
  ;; domain; the edge joins on the promoted :date, so it must not care.
  (created! "rotations" {})
  (let [plan (created! "plans" {:start_date "2026-08-25" :weeks 1})
        self (:self plan)]
    (is (= 0 (get-in (get! self) [:data :calendar_conflicts]))
        "a clear week starts clear")

    (testing "a blocking event discovered on the feed flips the plan"
      (gcal/seed! *cal* "family:recital-2026-08-26"
                  {:title "Piano recital" :date "2026-08-26"
                   :end_date "2026-08-26" :kind "blocking" :all_day true})
      (is (pos? (mirror/discover! *eng* :event))
          "discovery mints the occurrence into the :calendar domain")
      (let [env (get! self)]
        (is (= 1 (get-in env [:data :calendar_conflicts]))
            "the cross-domain relation still resolves and still counts")
        (is (true? (get-in env [:data :has_conflicts])))))))
