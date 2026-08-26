(ns workqueue10.rank-tuning-test
  "The crown's rank is tunable through a staged proposal (waymark-1uv.5,
  option A of the epic 'Ranked, not capped'): an agent leashed to
  `recipe_proposal:create` proposes NUMBERS for the declared formula
  and cites what it read; a member reads the diff in the household's
  words and applies with one tap; the next feed read is answered by
  the new numbers; a proposal whose target's crown moved refuses on
  the fence; and the way back is a revise with the numbers the applied
  proposal's own row still carries.

  What belongs HERE rather than on the twin (recipe-proposal-test):
  the household's OWN registry over the live ring handler, so the
  leash is minted through the real grant door with the real scope a
  tuning agent needs — `recipe_proposal:create` plus read-only lines
  over `feed_view`, `verdict_reason` and `feed_recipe` — and the
  agent's reads under that leash are proved to answer, which is the
  half of the contract the spec's grant-scope block makes a claim
  about.

  Assertions are order-independent (kaocha randomizes, and the
  deftests share one DB): the test names its own principals and its
  own rows, reads the member's own `scope \"mine\"` recipe so no
  household row another test left behind is in the question, and
  never asserts on collection SIZE.

  Needs a Postgres database; WAYMARK10_TEST_DSN names it.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.rank-tuning-test"
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
   "letters" "weathers" "selves" "journals" "ticklers" "insights" "ranking_notes"
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
        ;; :probe-reads mirrors production's boot, so the staging walls
        ;; that read a row (the-staging-is-current) answer honestly in
        ;; the envelope; :suppress-mirror-refresh keeps the reads pure
        (let [eng (engine/engine {:storage st
                                  :resources (main/check-resources)
                                  :probe-reads true
                                  :suppress-mirror-refresh true})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (outcome_test's idiom) ────────────────────────────

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
(defn- guard-of [resp] (:guard (json resp)))
(defn- detail [resp] (str (:detail (json resp))))

(defn- invoke! [plural id action body headers]
  (req :post (str "/api/" plural "/" id "/-/" (name action))
       (or body {}) headers))

(defn- feed-as [who & [query]]
  (json (req :get (str "/api/-/feed" (when query (str "?" query))) (human who))))

(defn- etag-of [plural id who]
  (get-in (req :get (str "/api/" plural "/" id) (human who)) [:headers "ETag"]))

(def ^:private tuning-scope
  "THE GRANT SCOPE A TUNING AGENT NEEDS, exactly — the line
  docs/spec-outcome-menu.md's composer contract records for
  waymark-1uv.5. One write door, and it is the staging door; three
  read-only lines for what the proposal cites: exposure, the house's
  verdict words, and the recipe's current numbers. Never `feed_recipe`
  revise — that is the wall this whole surface exists to keep."
  [{:kind "recipe_proposal" :actions ["create"]}
   {:kind "feed_view" :actions []}
   {:kind "verdict_reason" :actions []}
   {:kind "feed_recipe" :actions []}])

(defn- leash!
  "An agent HOLDING the tuning scope, minted through the real grant
  door and accepted → the headers that present it."
  [id who]
  (let [hs {"x-waymark-principal" id "x-waymark-actor-type" "agent"}
        made (req :post "/api/grants" {:audience id :scope tuning-scope}
                  (human who))
        gid (id-of made)
        took (invoke! "grants" gid :accept nil hs)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (assert (= 200 (:status took)) (pr-str (json took)))
    (assoc hs "x-waymark-grant" gid)))

(deftest an-agent-proposes-the-crowns-numbers-and-a-member-applies-them
  (let [who "colton-tune"
        tuner (leash! "tuner-1uv5" who)
        ;; the member's own recipe row, at the deployment's numbers, so
        ;; the proposal has a row to stage against and the fence has a
        ;; version to hold
        order (:order (:recipe (feed-as who)))
        made (req :post "/api/feed_recipes"
                  {:label "Colton's own" :scope "mine" :order order}
                  (human who))
        rid (id-of made)
        before (feed-as who)]
    (is (= 201 (:status made)) (pr-str (json made)))
    (is (= rid (str (get-in before [:recipe :source :id]))))
    (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}
           (get-in before [:recipe :crown_rank])))

    (testing "under that leash the agent READS what a tuning proposal cites —
              exposure, the house's verdict words, the recipe's own numbers —
              and nothing here needed a write door"
      (doseq [uri [(str "/api/feed_views?member=" who)
                   "/api/verdict_reasons"
                   (str "/api/feed_recipes/" rid)]]
        (is (= 200 (:status (req :get uri tuner))) uri))
      ;; the ROW says nothing about the crown — a recipe that names no
      ;; numbers reads the deployment's, and the feed document fills
      ;; them in (recipe.crown_rank, which the composer's own
      ;; feed.preview_as line reads). A proposal staged against such
      ;; a row may leave current_crown_rank out, or spell the built-in's
      ;; numbers; the two are the same crown at the staging wall.
      (is (nil? (get-in (json (req :get (str "/api/feed_recipes/" rid) tuner))
                        [:data :crown_rank]))))

    (let [staged (req :post "/api/recipe_proposals"
                      {:proposal "Declared values are being passed over for observed ones"
                       :label "Colton's own"
                       :target_id rid
                       :evidence [(str "/api/feed_recipes/" rid)
                                  (str "/api/feed_views?member=" who)]
                       :current_order order
                       :order order
                       :current_crown_rank (get-in before [:recipe :crown_rank])
                       :crown_rank {:declared 12 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}}
                      tuner)]
      (testing "a citation with a query string is not an address — the
                evidence wall reads the shape, and a filtered listing is not a
                row anybody can follow to"
        (is (= 409 (:status staged)))
        (is (= "it-cites-what-it-read" (str (guard-of staged))))))

    (let [staged (req :post "/api/recipe_proposals"
                      {:proposal "Declared values are being passed over for observed ones"
                       :label "Colton's own"
                       :target_id rid
                       :evidence [(str "/api/feed_recipes/" rid)]
                       :current_order order
                       :order order
                       :current_crown_rank (get-in before [:recipe :crown_rank])
                       :crown_rank {:declared 12 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}}
                      tuner)
          pid (id-of staged)]
      (is (= 201 (:status staged)) (pr-str (json staged)))

      (testing "the member's feed carries the change as a decide card whose
                sentence says the numbers in the household's words"
        (let [card (some #(when (= (str "decide/recipe_proposal/" pid)
                                   (str (:card_id %))) %)
                         (:cards (feed-as who)))]
          (is (some? card))
          (is (= "decide" (str (:section card))))
          (is (str/includes? (str (:sentence card))
                             "The order itself is unchanged, line for line."))
          (is (str/includes? (str (:sentence card))
                             "lifts a bundle 12 instead of 10"))
          (is (str/includes? (str (:sentence card))
                             "a never-this line of thinking is held 12 instead of 8"))
          (is (= #{"apply" "decline"}
                 (set (map (comp name key) (:actions card)))))))

      (testing "the agent cannot answer its own proposal — under THIS leash the
                router's default deny conceals the door (create is the only
                write it holds); the four-eyes wall behind it is proved on the
                twin with a leash that covers apply"
        (is (= 404 (:status (invoke! "recipe_proposals" pid :apply nil tuner)))))

      (testing "the member applies, and the next read is answered by the new
                numbers — on the row, and in the sentence that narrates them"
        (let [applied (invoke! "recipe_proposals" pid :apply nil (human who))
              after (feed-as who)]
          (is (= 200 (:status applied)) (pr-str (json applied)))
          (is (= {:declared 12 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}
                 (get-in after [:recipe :crown_rank])))
          (is (str/includes? (str (get-in after [:recipe :crown_rank_says]))
                             "lifts a bundle 12"))
          (is (str/includes? (str (get-in after [:recipe :crown_rank_says]))
                             "12 for never this"))
          (is (= order (get-in after [:recipe :order]))
              "the order is untouched by a proposal that named only the crown")
          (is (= (get-in before [:recipe :formula]) (get-in after [:recipe :formula]))
              "and so is the contest")
          (is (= {:declared 12 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}
                 (get-in (json (req :get (str "/api/feed_recipes/" rid) (human who)))
                         [:data :crown_rank])))))

      (testing "a second proposal, staged against the tuned crown, refuses on
                the fence once the member has moved the numbers themselves"
        (let [again (req :post "/api/recipe_proposals"
                         {:proposal "And a little more"
                          :label "Colton's own"
                          :target_id rid
                          :evidence [(str "/api/feed_recipes/" rid)]
                          :current_order order
                          :order order
                          :current_crown_rank {:declared 12 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}
                          :crown_rank {:declared 20 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}}
                         tuner)
              sid (id-of again)
              moved (req :post (str "/api/feed_recipes/" rid "/-/revise")
                         {:label "Colton's own" :order order
                          :crown_rank {:declared 15 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}}
                         (assoc (human who) "if-match" (etag-of "feed_recipes" rid who)))
              stale (invoke! "recipe_proposals" sid :apply nil (human who))]
          (is (= 201 (:status again)) (pr-str (json again)))
          (is (= 200 (:status moved)) (pr-str (json moved)))
          (is (= 409 (:status stale)))
          (is (= "the-order-has-not-moved" (str (guard-of stale))))
          (is (str/includes? (detail stale) "ranks its crown differently now"))
          (is (= 15 (get-in (feed-as who) [:recipe :crown_rank :declared]))
              "the member's own edit is what the house reads")
          (is (= 200 (:status (invoke! "recipe_proposals" sid :decline nil (human who)))))))

      (testing "the way back is the numbers the applied proposal replaced, on its
                own row, through the recipe's own revise door"
        (let [row (json (req :get (str "/api/recipe_proposals/" pid) (human who)))
              was (get-in row [:data :current_crown_rank])
              back (req :post (str "/api/feed_recipes/" rid "/-/revise")
                        {:label "Colton's own" :order order :crown_rank was}
                        (assoc (human who) "if-match" (etag-of "feed_recipes" rid who)))
              restored (feed-as who)]
          (is (= "applied" (str (:state row))))
          (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1} was))
          (is (= 200 (:status back)) (pr-str (json back)))
          (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}
                 (get-in restored [:recipe :crown_rank])))
          (is (str/includes? (str (get-in restored [:recipe :crown_rank_says]))
                             "lifts a bundle 10")))))))
