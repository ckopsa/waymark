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
  degrades per-source, never queue-wide → and a task born HERE pushes
  out to whichever pocket authority it names, google's included,
  landing in the list it was told to land in and claiming back the
  identity that keeps the next discovery pass from minting it twice.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [calendar10.source :as gcal]
            [mealplan10.main]
            [next.jdbc :as jdbc]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [workqueue10.sources.gtasks :as gt]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Duration Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ;; the WHOLE folded registry's tables (waymark-bwu) — other suites
  ;; share this database and leave differently-shaped residue under
  ;; the same names; a fixture that drops less boots into drift
  ["tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *chores* nil)
(def ^:dynamic *meals* nil)
(def ^:dynamic *todos* nil)
(def ^:dynamic *gtasks* nil)
(def ^:dynamic *clock* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          chores (conf/fake-source)
          meals (conf/fake-source)
          todos (conf/fake-source)
          ;; google gets its own twin (the one that stands behind the
          ;; TRANSPORT), and no capture list: the google half of this
          ;; story is a birth that NAMES its list, which is the path
          ;; only the list-as-a-row work made expressible. Its
          ;; inventory starts bare so the list feed's own scene below
          ;; still counts what it means to count.
          gtasks (gt/fake-source)
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
                                                "todo" todos "gtasks" gtasks}
                                               (gcal/fake-calendar))
                                   :now-fn (fn [] @clock)}))]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *chores* chores
                    *meals* meals
                    *todos* todos
                    *gtasks* gtasks
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

(defn- id-of
  "The row id behind an envelope's :self — what a :waymark/ref holds."
  [env]
  (last (str/split (str (:self env)) #"/")))

(defn- list-by-title [title]
  (let [resp (req :get "/api/task_lists")]
    (is (= 200 (:status resp)) (:body resp))
    (or (first (filter #(str/includes? (str (:summary %)) title)
                       (get-in (json resp) [:data :items])))
        (throw (ex-info (str "no task list titled " title) {})))))

;; ── the story ───────────────────────────────────────────────────────

(deftest the-family-work-queue
  ;; Tuesday morning: both engines carry open work. The fakes hold
  ;; CANONICAL docs — they play the boundary post-translation.
  (conf/seed! *chores* "cr-dishes"
              {:title "Dishes" :assignee_name "colton"
               :due_at "2026-07-22T00:00:00Z" :status "open"
               :detail "load and run before bed"
               :source_href "https://rod.kopsa.info/api/chore_runs/cr-dishes"
               :source_ui_href "https://rod.kopsa.info/api/-/ui#/api/chore_runs/cr-dishes"})
  (conf/seed! *chores* "cr-mow"
              {:title "Mow the lawn" :assignee_name "colton"
               :due_at "2026-07-20T00:00:00Z" :status "open"})
  (conf/seed! *meals* "pt-thaw"
              {:title "thaw: Traeger brisket" :assignee_name "housekeeper"
               :due_at "2026-07-21T16:00:00Z" :status "open"
               :detail "move 2500g brisket to the fridge"})
  (conf/seed! *meals* "pt-brine"
              {:title "prep: Chicken brine" :assignee_name "colton"
               :due_at "2026-07-21T19:00:00Z" :status "open"})
  (conf/seed! *meals* "pt-slaw"
              {:title "prep: Coleslaw" :assignee_name "housekeeper"
               :due_at "2026-07-21T23:00:00Z" :status "dropped"})
  ;; the todo names the LIST it lives in — source-local, the way its
  ;; authority spells it; the confluence namespaces it into the
  ;; :task_list row's external id. It used to arrive as the string
  ;; "Woodworking" glued onto the head of :detail.
  (conf/seed! *todos* "todo.woodworking/uid-chisels"
              {:title "Sharpen chisels" :status "open"
               :list_key "todo.woodworking"})

  (testing "one discovery pass mints EVERY source's rows into the one kind"
    (is (= 6 (mirror/discover! *eng* :task)))
    (is (= 6 (count (items-of "")))))

  (testing "one queue, one filter grammar — across domains"
    (is (= 2 (count (items-of "?source=chore"))))
    (is (= 3 (count (items-of "?source=meal"))))
    (is (= 1 (count (items-of "?source=todo"))))
    (is (= 5 (count (items-of "?status=open"))))
    (is (= 3 (count (items-of "?assignee_name=colton"))))
    (is (= 1 (count (items-of "?overdue=true")))
        "only the lawn survived the weekend unmowed"))

  (testing "an assignee is a PERSON, not a string: the source's name
            resolves to the member whose handle it matches"
    (let [self (:self (task-by-title "Dishes"))]
      (is (nil? (get-in (json (req :get self)) [:data :assignee]))
          "nobody claims the handle 'colton' yet — the honest gap,
           beside the source's intact word")
      ;; the member the dev-header principal was auto-provisioned as:
      ;; born of an identity provider that never heard of the chore
      ;; board, so the handle is a deliberate act
      (act-fenced! "/api/members/colton" :set_handle {:handle "colton"})
      ;; a native target mints nothing — the queue's own beat heals it
      (mirror/discover! *eng* :task)
      (let [dishes (json (req :get self))
            ref (get-in dishes [:data :assignee])]
        (is (= "colton" (get-in dishes [:data :assignee_name]))
            "the source's word is kept whatever the match does")
        (is (some? ref) "…and it now names a member")
        (is (= 200 (:status (req :get (str "/api/members/" ref))))
            "the ref holds a member ROW id — it dereferences")
        (is (= 3 (count (items-of (str "?assignee=" ref))))
            "the queue filters by person now, not by spelling"))))

  (testing "the list a task belongs to is a ROW, not a prefix: the
            authority's own key lands beside a ref that resolves to
            the mirrored list — and the list's own discovery pass
            heals every task that observed before it existed"
    (let [self (:self (task-by-title "Sharpen chisels"))]
      (is (= "todo:todo.woodworking"
             (get-in (json (req :get self)) [:data :list_key]))
          "the confluence namespaced the source's own list key")
      (is (nil? (get-in (json (req :get self)) [:data :task_list]))
          "no list row exists yet — the honest gap, beside the intact key")

      (conf/seed-list! *todos* "todo.woodworking" {:title "Woodworking"})
      (is (= 1 (mirror/discover! *eng* :task_list)))

      (let [chisels (json (req :get self))
            ref (get-in chisels [:data :task_list])]
        (is (some? ref) "…and the mint healed the edge pointing at it")
        (let [list-row (json (req :get (str "/api/task_lists/" ref)))]
          (is (= "Woodworking" (get-in list-row [:data :title]))
              "the ref holds a task_list ROW id — it dereferences")
          (is (= "todo" (get-in list-row [:data :source]))
              "…and the list carries the same routing tag its tasks do")
          (is (= "Woodworking · todo" (:summary list-row))
              "the summary every surface renders in place of the token"))
        (is (= 1 (count (items-of (str "?task_list=" ref))))
            "the queue filters by LIST now, not by a prose prefix")
        (is (nil? (get-in chisels [:data :detail]))
            "…and :detail went back to being the household's own
             description, which this todo never had")))

    (testing "a source with no list concept leaves both unset"
      (let [dishes (json (req :get (:self (task-by-title "Dishes"))))]
        (is (nil? (get-in dishes [:data :list_key])))
        (is (nil? (get-in dishes [:data :task_list]))))))

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

  (testing "ranking is the RANKER role's: the binding is law, the
            holders are runtime data — mint the role, assign colton,
            and jack (auto-provisioned, role-less) is refused while
            complete stays his"
    (let [role-resp (req :post "/api/roles" {:name "ranker"}
                         {"idempotency-key" (str (random-uuid))})]
      (is (contains? #{200 201} (:status role-resp)) (:body role-resp)))
    ;; an auto-provisioned member row's ID is the principal's own id
    (let [member (json (req :get "/api/members/colton"))]
      (is (some? (:self member)) "colton auto-provisioned on his first request")
      (act-fenced! (:self member) :assign_roles {:roles ["ranker"]}))
    (let [jack-headers {"x-waymark-principal" "jack"}
          any-open (:self (task-by-title "Dishes"))
          read (req :get any-open nil jack-headers)
          resp (req :post (str any-open "/-/prioritize") {:priority 1}
                    (assoc jack-headers
                           "if-match" (get-in read [:headers "ETag"])))]
      (is (= 200 (:status read))
          "jack still reads the queue — only ranking is withheld")
      (is (= 409 (:status resp)))
      (is (= "Requires role 'ranker'." (:detail (json resp))))))

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
        :noop, never a change")
    (testing "…and a rank can be let go: the task rejoins the
              unranked tail"
      (let [dishes-self (:self (task-by-title "Dishes"))
            cleared (act! dishes-self :deprioritize)]
        (is (= "fresh" (:state cleared)))
        (is (nil? (get-in cleared [:data :priority])))
        (is (= "thaw: Traeger brisket · open"
               (:summary (first (items-of "?status=open"))))
            "the ranked task leads; Dishes fell back to the tail"))))

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
                {:title "cook: Rest the brisket" :assignee_name "colton"
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
                  {:title "Mow the lawn" :assignee_name "colton"
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

  (testing "two due affordances, one canonical fact: a day widens to
            its closing midnight; a clock time rides verbatim; both
            at once refuses"
    (let [env (json (req :post "/api/tasks"
                         {:title "Water the garden" :due_date "2026-07-23"}
                         {"idempotency-key" (str (random-uuid))}))]
      (is (= "2026-07-24T00:00:00Z" (get-in env [:data :due_at]))
          "the day became its closing midnight")
      (is (not (contains? (:data env) :due_date))
          "the birth-door field never persists"))
    (let [env (json (req :post "/api/tasks"
                         {:title "Call before the office closes"
                          :due_at "2026-07-23T22:30:00Z"}
                         {"idempotency-key" (str (random-uuid))}))]
      (is (= "2026-07-23T22:30:00Z" (get-in env [:data :due_at]))))
    (let [resp (req :post "/api/tasks"
                    {:title "Confused" :due_date "2026-07-23"
                     :due_at "2026-07-23T22:30:00Z"}
                    {"idempotency-key" (str (random-uuid))})]
      (is (= 409 (:status resp)))
      (is (= "Name one due — the day OR the clock time, not both; a day widens to its closing midnight."
             (:detail (json resp))))))

  (testing "CAPTURE TO GOOGLE: the second pocket authority takes
            births too — routed by the same tag, landing in a list
            named at the door, its identity claimed back"
    ;; the household's google list arrives the ordinary way first: a
    ;; row, discovered, before anything can point at it
    (gt/list! *gtasks* "L-errands" "Errands")
    (is (= 1 (mirror/discover! *eng* :task_list)))
    (let [errands (id-of (list-by-title "Errands"))
          woodworking (id-of (list-by-title "Woodworking"))
          resp (req :post "/api/tasks"
                    {:title "Buy sandpaper" :source "gtasks"
                     :task_list errands :due_date "2026-07-25"}
                    {"idempotency-key" (str (random-uuid))})
          env (json resp)]
      (is (contains? #{200 201} (:status resp)) (:body resp))
      (is (= "fresh" (:state env)))
      (is (= "gtasks" (get-in env [:data :source])))
      (is (= "gtasks:L-errands" (get-in env [:data :list_key]))
          "the birth stamped the authority's own key from the list row
           it points at — the two spellings agree from the first
           second, not from the first pull")

      (let [xid (get-in env [:data :external_id])
            [tasklist taskid] (gt/split-id (second (str/split (str xid) #":" 2)))]
        (is (str/starts-with? (str xid) "gtasks:L-errands/")
            "google minted, claim_external stamped — and the id
             carries the confluence's tag grammar, without which it
             would refuse at the routing seam")
        (let [task (gt/stored *gtasks* tasklist taskid)]
          (is (= "Buy sandpaper" (:title task))
              "the task exists in google — capture is real")
          (is (= "2026-07-25T00:00:00.000Z" (:due task))
              "the day the person named, in the date-only field google
               keeps — the closing-midnight instant stepped back to
               the day it closes")))

      (testing "…and the next discovery pass does NOT mint it again —
                the id a capture claimed is exactly the id discovery
                names, or the household would watch its own capture
                appear twice"
        (let [before (count (items-of "?source=gtasks"))]
          (is (= 1 before))
          (is (zero? (mirror/discover! *eng* :task)))
          (is (= before (count (items-of "?source=gtasks"))))))

      (testing "a clock time bound for google refuses and names the
                remedy: google keeps the DAY and discards the hour, so
                accepting 21:00 would mean the next pass silently
                rewrote it to the day's closing midnight"
        (let [refused (req :post "/api/tasks"
                           {:title "Call the plumber" :source "gtasks"
                            :task_list errands
                            :due_at "2026-07-25T21:00:00Z"}
                           {"idempotency-key" (str (random-uuid))})]
          (is (= 409 (:status refused)))
          (is (= (str "Google records a due DATE and throws the clock "
                      "time away, so name the day in due_date instead — "
                      "a time kept here would be silently rewritten to "
                      "that day's closing midnight on the next pass.")
                 (:detail (json refused))))))

      (testing "…while a MIDNIGHT due_at is indistinguishable from a
                date and passes untouched"
        (let [ok (req :post "/api/tasks"
                      {:title "Return the drill" :source "gtasks"
                       :task_list errands :due_at "2026-07-26T00:00:00Z"}
                      {"idempotency-key" (str (random-uuid))})]
          (is (contains? #{200 201} (:status ok)) (:body ok))
          (is (= "2026-07-26T00:00:00Z" (get-in (json ok) [:data :due_at])))))

      (testing "capturing into another authority's list is a category
                error, and the refusal says whose list it is"
        (let [refused (req :post "/api/tasks"
                           {:title "Wrong door" :source "gtasks"
                            :task_list woodworking}
                           {"idempotency-key" (str (random-uuid))})]
          (is (= 409 (:status refused)))
          (is (= (str "That list belongs to todo and this capture goes "
                      "to gtasks — a mirrored list rides only google's "
                      "own capture, and the lists any door may name are "
                      "the engine's native ones.")
                 (:detail (json refused)))))
        (testing "…in both directions: naming a google list on a todo
                  capture would be silently ignored by a source that
                  captures into its one configured entity"
          (let [refused (req :post "/api/tasks"
                             {:title "Wrong door again" :task_list errands}
                             {"idempotency-key" (str (random-uuid))})]
            (is (= 409 (:status refused)))
            (is (str/includes? (str (:detail (json refused)))
                               "That list belongs to gtasks and this capture goes to todo")))))))

  (testing "the waymark engines take no births — the schema refuses at
            the door, before any push"
    (let [resp (req :post "/api/tasks" {:title "Ghost chore" :source "chore"}
                    {"idempotency-key" (str (random-uuid))})]
      (is (= 422 (:status resp))
          "a chore is born of its own engine's law, never captured")))

  (testing "NATIVE LISTS: a source without an upstream — the list that
            lives only in this engine, beside the mirrors it never
            replaces"
    (let [resp (req :post "/api/task_lists"
                    {:title "Projects" :source "native"}
                    {"idempotency-key" (str (random-uuid))})
          env (json resp)]
      (is (contains? #{200 201} (:status resp)) (:body resp))
      (is (= "fresh" (:state env)))
      (is (= "native" (get-in env [:data :source])))
      (is (nil? (get-in env [:data :external_id]))
          "no upstream was fabricated — the row is the engine's own")
      (is (= "Projects · native" (:summary env))))

    (testing "the birth law holds both ways: a native list carries no
              external id…"
      (let [resp (req :post "/api/task_lists"
                      {:title "Sneaky" :source "native"
                       :external_id "todo:sneaky"}
                      {"idempotency-key" (str (random-uuid))})]
        (is (= 409 (:status resp)))
        (is (= (str "A native list is this engine's own and carries no "
                    "external id; a mirrored list names the identity "
                    "its authority keeps.")
               (:detail (json resp))))))

    (testing "…and a mirrored source still names one"
      (let [resp (req :post "/api/task_lists"
                      {:title "Halfway" :source "todo"}
                      {"idempotency-key" (str (random-uuid))})]
        (is (= 409 (:status resp)))))

    (testing "the mirror machinery never learns its name: resync walks
              the mirrored lists and the native row is not even a
              candidate — never rewritten, never gone-from-feed"
      (let [projects-self (:self (list-by-title "Projects"))
            stats (mirror/resync! *eng* :task_list)]
        (is (= 2 (:checked stats))
            "only the two mirrored lists (Woodworking, Errands) walked")
        (is (zero? (:gone stats))
            "absent from every feed, yet never counted gone")
        (is (zero? (mirror/discover! *eng* :task_list))
            "discovery has nothing to mint — the native row casts no id")
        (let [after (json (req :get projects-self))]
          (is (= "fresh" (:state after)))
          (is (= "Projects" (get-in after [:data :title])))
          (is (nil? (get-in after [:data :synced_at]))
              "nothing ever synced it — the gap renders honestly"))))

    (testing "a task parents onto the native list like any other — and
              the parent is the hub's own fact: the authority's next
              word does not wash it away"
      (let [projects (id-of (list-by-title "Projects"))
            env (json (req :post "/api/tasks"
                           {:title "Sand the bench" :task_list projects}
                           {"idempotency-key" (str (random-uuid))}))]
        (is (= "fresh" (:state env)) (pr-str env))
        (is (= "todo" (get-in env [:data :source]))
            "the capture still lands at its pocket authority")
        (is (= projects (get-in env [:data :task_list])))
        (is (nil? (get-in env [:data :list_key]))
            "a native list has no authority-side key to stamp")
        (is (= 1 (count (items-of (str "?task_list=" projects))))
            "the queue filters by the native list exactly as by a
             mirrored one")
        ;; the authority revises its side of the captured row; the
        ;; pull recomputes mirrored refs from :list_key — and keeps
        ;; its hands off a parent no key could ever name
        (let [local-id (second (str/split
                                (str (get-in env [:data :external_id]))
                                #":" 2))]
          (conf/seed! *todos* local-id
                      {:title "Sand the bench" :status "open"
                       :detail "80 grit, then 120"}))
        (tick! (Duration/ofMinutes 30))
        (let [again (json (req :get (:self env)))]
          (is (= "80 grit, then 120" (get-in again [:data :detail]))
              "the authority's change landed")
          (is (= projects (get-in again [:data :task_list]))
              "…and the native parent survived the recompute"))))))
