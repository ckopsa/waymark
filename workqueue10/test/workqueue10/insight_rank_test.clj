(ns workqueue10.insight-rank-test
  "The findings' rank over real rows (waymark-1uv.8, the epic 'Ranked,
  not capped'), lived in over the REAL ring handler and the
  household's own registry — because the whole claim is that the
  insights line chooses WHICH findings a person reads by numbers the
  house can read, and that no wall at the create door does that job
  any more.

  What the scenarios already prove and this file does not repeat: the
  two shape walls and the four-eyes wall, judged with no database in
  the same breath as `make check-queue`. What only a live engine can
  answer is here:

  - a finding that IS a law-4 diagnosis — its next step a value's own
    affirmation — stands above a plain finding, and both cards say
    the arithmetic;
  - a line the house dismissed twice, once in a word, stands below a
    fresh one — the verdict record on the same offer is read, counted
    and quoted;
  - an agent publishes six findings in one day and every one is
    admitted; the rank, not a wall, decides what the page shows, and
    the line's take is still the floor;
  - the six numbers ride the document, narrated.

  THE FINDER IS LEASHED. An unleashed agent is answered 404 by the
  router's default deny (rank_tuning_test's sentence), which proves
  nothing about any wall; the finder here holds `insight:create` and
  `value:create`, minted through the real grant door.

  Assertions are order-independent (kaocha randomizes, and the
  deftests share one DB): the test names its own principals and its
  own rows, reads the member's own `scope \"mine\"` recipe so no
  household row another test left behind is in the question, and
  never asserts on collection SIZE.

  Needs a Postgres database; WAYMARK10_TEST_DSN names it.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.insight-rank-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]))

(def ^:private tables
  ;; THE WHOLE FOLDED REGISTRY'S TABLES — outcome_test's rule, for its
  ;; reason: this engine boots every kind main/check-resources
  ;; declares plus what the module table enrols, so a fixture that
  ;; dropped only its own would boot into whatever shape another
  ;; suite left behind.
  ["composition_requests" "outcome_pieces" "outcomes" "values" "people"
   "tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "activities" "evening_plans" "evening_sessions"
   "letters" "weathers" "selves" "journals" "ticklers" "insights"
   "permission_slips" "saved_views" "dashboards" "dashboard_slots"
   "connections" "capabilities"
   "members" "roles" "grants" "approval_requests"
   "feed_recipes" "recipe_proposals" "feed_views" "feed_view_consents"
   "verdict_reasons"
   "attachments" "subscriptions" "jobs"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors" "waymark10_job_leases"])

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
        ;; :probe-reads mirrors production's boot, so the citation
        ;; walls that consult the registry answer honestly in the
        ;; envelope; :suppress-mirror-refresh keeps the reads pure
        (let [eng (engine/engine {:storage st
                                  :resources (main/check-resources)
                                  :probe-reads true
                                  :suppress-mirror-refresh true})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (rank_tuning_test's idiom) ────────────────────────

(defn- human [id] {"x-waymark-principal" id "x-waymark-actor-type" "human"})

(defn- req
  ([method uri headers] (req method uri nil headers))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers (or headers {})}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp)
                           (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(defn- invoke! [plural id action body headers]
  (req :post (str "/api/" plural "/" id "/-/" (name action))
       (or body {}) headers))

(defn- feed-as [who & [query]]
  (json (req :get (str "/api/-/feed" (when query (str "?" query))) (human who))))

(defn- leash!
  "An agent HOLDING a scope, minted through the real grant door and
  accepted → the headers that present it."
  [id who scope]
  (let [hs {"x-waymark-principal" id "x-waymark-actor-type" "agent"}
        made (req :post "/api/grants" {:audience id :scope scope} (human who))
        gid (id-of made)
        took (invoke! "grants" gid :accept nil hs)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (assert (= 200 (:status took)) (pr-str (json took)))
    (assoc hs "x-waymark-grant" gid)))

(defn- publish!
  "One finding through its own create door, as the finder: the
  offered row's address is its citation, and the offer is that row's
  own action."
  [finder text kind id action]
  (let [self (str "/api/" kind "/" id)]
    (req :post "/api/insights"
         {:finding text :evidence [self]
          :offer_kind (subs kind 0 (dec (count kind)))
          :offer_id id :offer_action action :offer_href self}
         finder)))

(defn- insight-cards [doc]
  (filterv #(= "insight" (str (:kind %))) (:cards doc)))

(defn- insight-card [doc id]
  (some #(when (str/ends-with? (str (:card_id %)) (str "/" id)) %)
        (insight-cards doc)))

(defn- insight-ids [doc]
  (mapv #(last (str/split (str (:card_id %)) #"/")) (insight-cards doc)))

(defn- index-of ^long [ids id]
  (long (.indexOf ^java.util.List ids id)))

(defn- light-action-on
  "The lightest verb the reader's own feed card offers on a task —
  the pack's own way of naming an offer a card can honestly carry."
  [who tid]
  (let [card (some #(when (= (str "do_now/task/" tid) (str (:card_id %))) %)
                   (:cards (feed-as who)))]
    (some-> (first (sort (keys (:actions card)))) name)))

(deftest the-findings-are-ranked-not-capped-and-every-card-says-why
  (let [who "colton-insight"
        finder (leash! "finder-1uv8" who
                       [{:kind "insight" :actions ["create"]}
                        {:kind "value" :actions ["create"]}])
        ;; a value the finder OBSERVED — born observed (jfv.10), so its
        ;; own affirmation is the next step a diagnosis offers
        value (req :post "/api/values"
                   {:name "Evenings in the shop"
                    :says "Six weeks of evenings went to building things."
                    :loved ["the shop"]
                    :scope "household"}
                   finder)
        vid (id-of value)
        ;; three tasks, three plain offers: one for the plain finding,
        ;; one for the line the house will dismiss twice, one for the
        ;; six in a day
        task! (fn [title]
                (id-of (req :post "/api/tasks" {:title title} (human who))))
        t-plain (task! "Sweep the porch")
        t-line (task! "Call about the gutters")
        t-six (task! "Sort the garage shelf")
        act (light-action-on who t-plain)]
    (is (= 201 (:status value)) (pr-str (json value)))
    (is (= "observed" (str (:state (json value)))))
    (is (some? act) "the task's own card offers a verb light enough to tap")
    ;; the reader's own recipe: the insights line wide enough for every
    ;; finding below, with the deployment's six numbers left standing
    (let [order (:order (:recipe (feed-as who)))
          wide (mapv #(if (= "insights" (str (:population %))) (assoc % :take 10) %)
                     order)
          made (req :post "/api/feed_recipes"
                    {:label "A wide findings line" :scope "mine" :order wide}
                    (human who))
          rid (id-of made)]
      (is (= 201 (:status made)) (pr-str (json made)))

    (testing "the six numbers ride the document, narrated"
      (let [doc (feed-as who)]
        (is (= {:diagnosis 10 :declared 5 :cooled 2 :dismissed 3 :declined 2 :fresh 1}
               (get-in doc [:recipe :insight_rank])))
        (is (str/includes? (str (get-in doc [:recipe :insight_rank_says]))
                           "ranked, not capped"))
        (is (str/includes? (str (get-in doc [:recipe :insight_rank_says]))
                           "8 for never this"))))

    (let [d-resp (publish! finder "The shop has not been opened since June"
                           "values" vid "still_stands")
          p-resp (publish! finder "The porch has waited a month"
                           "tasks" t-plain act)
          d (id-of d-resp) p (id-of p-resp)]
      (is (= 201 (:status d-resp)) (pr-str (json d-resp)))
      (is (= 201 (:status p-resp)) (pr-str (json p-resp)))

      (testing "a diagnosis — the value's own affirmation offered — stands
                above a plain finding, and both cards say the arithmetic"
        (let [doc (feed-as who "explain=1")
              ids (insight-ids doc)
              cd (insight-card doc d) cp (insight-card doc p)
              lift #(get-in % [:why :insight :lift])
              says-of (fn [c s] (some #(str/includes? (str %) s) (get-in c [:why :says])))]
          (is (= #{d p} (set ids)) (pr-str ids))
          (is (< (index-of ids d) (index-of ids p)))
          (is (= "affirmation" (get-in cd [:why :insight :diagnosis])))
          (is (= "none" (get-in cp [:why :insight :diagnosis])))
          (is (= "observed" (get-in cd [:why :insight :value]))
              "the offered row IS the value, and it is still a guess")
          (is (nil? (get-in cp [:why :insight :value]))
              "a task serves no value, and the key says so by silence")
          (is (= 24 (lift cd)) "10 for a diagnosis + 14 days of freshness")
          (is (= 14 (lift cp)) "14 days of freshness and nothing else")
          (is (= 0 (get-in cd [:why :insight :dismissed])))
          (is (= 0 (get-in cd [:why :insight :days_old])))
          (is (= 14 (get-in cd [:why :insight :fresh_days])))
          (is (says-of cd "Ranked 1st of 2 among findings"))
          (is (says-of cd "It is a diagnosis"))
          (is (says-of cd "lifts it 10"))
          (is (says-of cp "plain finding"))
          (is (says-of cp "Lift 14 in all"))
          (is (says-of cp "outside the contest because they must appear")
              "the arm says the section's OTHER citizens are law 2's")
          (is (nil? (get-in cp [:why :seen]))
              "the contest's own key never rides an insight card")
          (is (says-of cp "you are not recording"))))

      ;; the house dismisses two findings on the same next step — the
      ;; first with a word — and the finder publishes a third
      (let [q1 (id-of (publish! finder "The gutters are still waiting (1)"
                                "tasks" t-line act))
            no1 (invoke! "insights" q1 :dismiss nil (human who))
            said (req :post "/api/verdict_reasons"
                      {:subject_kind "insight" :subject_id q1
                       :verdict "dismiss" :reason "wrong_time"}
                      (human who))
            q2 (id-of (publish! finder "The gutters are still waiting (2)"
                                "tasks" t-line act))
            no2 (invoke! "insights" q2 :dismiss nil (human who))
            q3-resp (publish! finder "The gutters are still waiting (3)"
                              "tasks" t-line act)
            q3 (id-of q3-resp)]
        (is (= 200 (:status no1)) (pr-str (json no1)))
        (is (= 201 (:status said)) (pr-str (json said)))
        (is (= 200 (:status no2)) (pr-str (json no2)))
        (is (= 201 (:status q3-resp)) "a third finding on a dismissed line is still admitted")

        (testing "a line dismissed twice, once in a word, stands below a fresh
                  finding — the verdict record on the same offer is read"
          (let [doc (feed-as who "explain=1")
                ids (insight-ids doc)
                c3 (insight-card doc q3)
                says-of (fn [c s] (some #(str/includes? (str %) s) (get-in c [:why :says])))]
            (is (= #{d p q3} (set ids)) (pr-str ids))
            (is (nil? (insight-card doc q1)) "an answered finding leaves the feed")
            (is (< (index-of ids p) (index-of ids q3)))
            (is (= 2 (get-in c3 [:why :insight :dismissed])))
            (is (= "wrong_time" (get-in c3 [:why :insight :declined])))
            (is (= 6 (get-in c3 [:why :insight :lift]))
                "14 fresh − 6 for two dismissals − 2 for wrong time")
            (is (says-of c3 "already dismissed 2 findings on this same next step"))
            (is (says-of c3 "holding it 6"))
            (is (says-of c3 "wrong time"))
            (is (says-of c3 "Lift 6 in all"))))

        ;; six in a day, one author, one offer
        (let [six (mapv (fn [n]
                          (publish! finder (str "The garage shelf, again (" n ")")
                                    "tasks" t-six act))
                        (range 1 7))
              six-ids (mapv id-of six)]
          (testing "an agent publishes six findings in a day and every one is
                    admitted — no wall on writing, the rank decides"
            (is (= [201 201 201 201 201 201] (mapv :status six))
                (pr-str (mapv json six))))

          (testing "…and the floor holds: the line shows every finding its take
                    reaches, ranked, and the six equals are placed by the seed"
            (let [doc (feed-as who)
                  ids (insight-ids doc)]
              (is (= (into #{d p q3} six-ids) (set ids)) (pr-str ids))
              (is (= 9 (get-in (insight-card doc d) [:why :of])))
              (is (= d (first ids)) "the diagnosis still stands first")
              (is (= q3 (last ids)) "the dismissed line still stands last")))

          (testing "…and at the deployment's take of two, the rank chooses which
                    two — the diagnosis and the freshest plain finding — and the
                    dismissed line is below the floor rather than refused"
            (let [narrow (mapv #(if (= "insights" (str (:population %))) (assoc % :take 2) %)
                               (:order (:recipe (feed-as who))))
                  etag (get-in (req :get (str "/api/feed_recipes/" rid) (human who))
                               [:headers "ETag"])
                  moved (req :post (str "/api/feed_recipes/" rid "/-/revise")
                             {:label "A narrow findings line" :order narrow}
                             (assoc (human who) "if-match" etag))
                  doc (feed-as who)
                  ids (insight-ids doc)]
              (is (= 200 (:status moved)) (pr-str (json moved)))
              (is (= 2 (count ids)))
              (is (= d (first ids)))
              (is (not (contains? (set ids) q3)))
              (is (= 9 (get-in (insight-card doc d) [:why :of]))
                  "nine were offered; the take chose two")))

          ;; leave the house as found: every live finding answered
          (doseq [id (into [d p q3] six-ids)]
            (is (= 200 (:status (invoke! "insights" id :dismiss nil (human who))))))))))))
