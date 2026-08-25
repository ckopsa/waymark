(ns workqueue10.feed-shape-test
  "THE WHOLE FEED, OVER A HOUSE THAT LOOKS LIKE THIS ONE
  (waymark-iqa.24, .15, .25, waymark-1zq).

  The feed shipped green. Then somebody read Colton's real feed
  through `preview_as` for the first time and found do-now holding
  three movies, a chore run skipped seventeen days earlier and a
  Google task that had been done for a month — and not one of the
  household's thirty-three open tasks, sixteen of them overdue. Below
  the seam, four shows the family is halfway through carded as
  memories with their full verb sets, and the fuel section
  congratulated the house on a grocery list it had thrown away.

  Every one of those is a shape a three-row smoke test cannot have.
  What was missing was never an assertion; it was a WORLD. So this
  file builds one — lopsided by kind the way a household is, half of
  it finished, some of it let go, a letter unopened on the shelf —
  and reads the feed's whole shape against it, through the ring
  handler, over the declarations production actually serves
  (`main/check-resources`) and the recipe production actually reads
  (`main/feed-recipe`).

  It is the regression net the smoke tests lacked. A change that
  makes any section start showing the wrong kind of row again fails
  here, by name, with the household's own sentence beside it.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.feed-shape-test"
  (:require [calendar10.source :as gcal]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [workqueue10.sources.hub :as hub]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.packs :as packs]
            [waymark10.test.suite :as suite]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ;; the WHOLE folded registry's tables (task_queue_test's rule): this
  ;; engine boots every kind main/resources declares, so a fixture
  ;; that drops less boots into another suite's residue
  ["tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "letters" "ticklers" "insights" "weathers" "permission_slips"
   "selves" "journals" "dwellings" "connections" "capabilities"
   "saved_views" "dashboards" "dashboard_slots"
   ;; the feed module's own pair (waymark-4yn, waymark-0k4): a stored
   ;; recipe would re-order this test's whole page and a leftover
   ;; offered proposal is a decide card, so both fall under the rule
   ;; above rather than beside it
   ;; …and the view door's pair (waymark-8um.1): a leftover recording
   ;; consent would put a `views.recording true` on a document this
   ;; test reads for its shape
   "feed_recipes" "recipe_proposals" "feed_views" "feed_view_consents"
   ;; …and the crown's three (waymark-jfv.4), for the same rule: an
   ;; outcome another suite left `offered` is a card ABOVE do-now on
   ;; this test's page, and the value it serves is what keeps it there
   "outcome_pieces" "outcomes" "values" "people"
   "activities" "evening_plans" "evening_sessions"
   "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

;; the world is built ONCE, by the fixture, because both deftests read
;; it and kaocha randomizes their order — a house that existed only
;; inside whichever test ran first is a fixture that proves whatever
;; the shuffle felt like
(declare seed-world!)

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *todos* nil)
(def ^:dynamic *chore-src* nil)
(def ^:dynamic *flickr* nil)

;; the day the preview was read, so "seventeen days ago" is a date and
;; not a mood
(def ^:private today (Instant/parse "2026-08-24T14:00:00Z"))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          todos (conf/fake-source)
          chore-src (conf/fake-source)
          flickr (conf/fake-source)]
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
                                 {"chore" chore-src "meal" (conf/fake-source)
                                  "todo" todos "gtasks" (conf/fake-source)}
                                 {"flickr" flickr "hub" (hub/source)}
                                 (gcal/fake-calendar))
                     ;; the recipe production reads, not the default:
                     ;; the household's own do-now line for the queue
                     :feed main/feed-recipe
                     :now-fn (constantly today)
                     ;; a GET on a staged stale row would heal it under
                     ;; the assertions (the conformance fixture's own
                     ;; note); production reads pull through
                     :suppress-mirror-refresh true}))]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *todos* todos
                    *chore-src* chore-src
                    *flickr* flickr]
            (seed-world!)
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (task_queue_test's idiom) ─────────────────────────

(def ^:private colton {"x-waymark-principal" "colton"})

(defn- req
  ([method uri] (req method uri nil colton))
  ([method uri body] (req method uri body colton))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers headers}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp)
                           (#(if (string? %) % (slurp %)))
                           wire/read-json))

(defn- created! [plural body & [headers]]
  (let [resp (req :post (str "/api/" plural) body (or headers colton))]
    (is (= 201 (:status resp)) (str plural " create: " (json resp)))
    (json resp)))

(defn- act! [self action & [body]]
  (let [resp (req :post (str self "/-/" (name action)) (or body {}))]
    (is (= 200 (:status resp))
        (str self " " (name action) ": " (:status resp) " " (json resp)))
    (json resp)))

(defn- id-of [env] (last (str/split (str (:self env)) #"/")))

(defn- feed! [& [headers]]
  (let [resp (req :get "/api/-/feed" nil (or headers colton))]
    (is (= 200 (:status resp)) (str "feed: " (json resp)))
    (json resp)))

(defn- cards [doc section]
  (filterv #(= section (str (:section %))) (:cards doc)))

(defn- kinds-in [doc section] (mapv (comp str :kind) (cards doc section)))

(defn- statuses-in [doc section kind]
  (into [] (comp (filter #(= kind (str (:kind %))))
                 (map #(str (get-in % [:fields :status]))))
        (cards doc section)))

;; ── the house, as it actually stands ────────────────────────────────

(defn- seed-world!
  "One household's Monday: a work queue with real weight in it, a
  media shelf with more rows than anything else (the shape that
  crowded do-now out), chores in all three of their resting places, a
  grocery list nobody used, a line item on a recipe, and a letter."
  []
  ;; ── the queue: thirteen open (five of them overdue), two done and
  ;; one the authority dropped. This is the pile that was invisible.
  (dotimes [i 13]
    (conf/seed! *todos* (str "todo.house/open-" i)
                {:title (str "Open errand " i)
                 :status "open"
                 :due_at (if (< i 5) "2026-08-08T00:00:00Z"
                             "2026-09-30T00:00:00Z")
                 :list_key "todo.house"}))
  (conf/seed! *todos* "todo.house/done-cardboard"
              {:title "Break down cardboard boxes" :status "done"
               :list_key "todo.house"})
  (conf/seed! *todos* "todo.house/done-lightbulb"
              {:title "Replace the hall bulb" :status "done"
               :list_key "todo.house"})
  (conf/seed! *todos* "todo.house/dropped-gutters"
              {:title "Gutters — the neighbour did it" :status "dropped"
               :list_key "todo.house"})
  (mirror/discover! *eng* :task)

  ;; ── the shelf: twenty rows, because a media catalogue outgrows a
  ;; work queue in a month. Four are over.
  (dotimes [i 16]
    (conf/seed! *flickr* (str "flickr-active-" i)
                {:title (str "Show " i) :medium "show"
                 :status (if (even? i) "active" "queued")}))
  (dotimes [i 3]
    (conf/seed! *flickr* (str "flickr-finished-" i)
                {:title (str "Film we finished " i) :medium "movie"
                 :status "finished"}))
  (conf/seed! *flickr* "flickr-abandoned-0"
              {:title "The one we gave up on" :medium "book"
               :status "abandoned"})
  (mirror/discover! *eng* :media)

  ;; ── the chores: one due, one done on Friday, one skipped a
  ;; fortnight and a half ago (the card that led the real feed)
  (let [chore (created! "chores" {:name "Bins" :cadence "weekly"})
        cid (id-of chore)
        due (created! "chore_runs" {:chore_id cid :due_date "2026-08-24"})
        done (created! "chore_runs" {:chore_id cid :due_date "2026-08-21"})
        skipped (created! "chore_runs" {:chore_id cid :due_date "2026-08-07"})]
    (act! (:self done) :complete)
    (act! (:self skipped) :skip))

  ;; ── the shopping: one list the family used, one it threw away
  (let [plan (created! "plans" {:start_date "2026-08-17" :weeks 1})
        discarded (created! "grocery_lists" {:plan_id (id-of plan)})]
    (act! (:self discarded) :discard))

  ;; ── a line item on a recipe, removed: terminal, secondary, and not
  ;; anybody's deed
  (let [meal (created! "meals" {:name "Carnitas tacos" :themes ["mexican"]})
        ingredient (created! "ingredients" {:name "Pork shoulder"})]
    (act! (:self meal) :accept)
    (act! (:self ingredient) :accept)
    (let [line (created! "meal_lines" {:meal_id (id-of meal)
                                       :ingredient_id (id-of ingredient)
                                       :grams 1400})]
      (act! (:self line) :remove)))

  ;; ── and the mail: one letter, addressed and unopened
  (req :get "/api/letters" nil colton)      ; colton becomes a member
  (created! "letters"
            {:to "colton" :title "The dispatch"
             :body "Four things the preview found, and what each one means."}
            {"x-waymark-principal" "quill" "x-waymark-actor-type" "agent"}))

;; ── the read ────────────────────────────────────────────────────────

(deftest the-feed-of-a-house-that-looks-like-this-one
  (let [doc (feed!)]

    (testing "do-now is the queue, and the queue is the next physical action"
      (let [do-now (cards doc "do_now")]
        (is (= 5 (count do-now)))
        (is (<= 2 (count (filter #(= "task" (str (:kind %))) do-now)))
            "the household's recipe dedicates two of the five slots to the
             queue; before this the queue had none of them, and thirty-three
             open tasks were a list nobody's morning ever reached")
        (is (every? #(seq (:actions %)) do-now)
            "a next action with no verb is a row on a list")))

    (testing "nothing whose work is OVER is a next action, however it ended"
      (let [do-now (cards doc "do_now")]
        (is (every? #{"open"} (statuses-in doc "do_now" "task"))
            "a task the authority calls done is not the next thing to do —
             the sync machine says fresh forever, so the STATUS is what
             says the work is over")
        (is (not-any? #{"finished" "abandoned"} (statuses-in doc "do_now" "media"))
            "and the film we finished is not tonight's film")
        (is (every? #{"due"} (into [] (comp (filter #(= "chore_run"
                                                        (str (:kind %))))
                                            (map #(str (:state %))))
                                   do-now))
            "the run still DUE is the morning's; the one done on Friday and
             the one skipped on the 7th are not, undo doors or not — un-skip
             is a real verb and it lives on the run's own screen")
        (is (not-any? #(= "skipped" (str (:state %))) do-now)
            "a fortnight-old skip led the real feed; it leads nothing now")))

    (testing "the mail reaches the shelf it was addressed to"
      (let [letter (first (filter #(= "letter" (str (:kind %)))
                                  (cards doc "decide")))]
        (is (some? letter)
            "an unopened letter belongs in decide — it is waiting on this
             reader and nobody else")
        (is (= "The dispatch" (get-in letter [:fields :title])))
        (is (some? (get-in letter [:actions :open :href]))
            "and the card's Open really opens: a card offering a door the
             guard would refuse is a dead end wearing a verb")))

    (testing "fuel is deeds — not endings, and not line items"
      (let [fuel (cards doc "fuel")]
        (is (not-any? #(= "grocery_list" (str (:kind %))) fuel)
            "the family threw that list away; congratulating them on it is
             the surface reading its own database rather than the house")
        (is (not-any? #(= "meal_line" (str (:kind %))) fuel)
            "an ingredient line is not a deed — :nav already says nobody
             navigates to one")
        (is (not-any? #(= "ingredient" (str (:kind %))) fuel))
        (is (every? #(contains? #{"chore_run" "task" "plan" "media" "day"
                                  "event" "grocery_list" "meal" "insight"
                                  "evening_plan" "evening_session"
                                  "chore" "task_list" "letter"}
                                (str (:kind %)))
                    fuel)
            "every fuel card is a front-door kind's row")))

    (testing "the archive is finished history and nothing else"
      (let [archive (cards doc "archive")]
        (is (seq archive) "an empty archive would prove nothing")
        (is (not-any? #{"active" "queued"} (statuses-in doc "archive" "media"))
            "four shows the family is halfway through carded as memories in
             the real feed — the archive matched on what MOVED and never
             asked the row where it stands now")
        (is (not-any? #{"open"} (statuses-in doc "archive" "task"))
            "and an open errand is do-now's business, page one or page four")
        (is (not-any? #(= "due" (str (:state %))) archive))))

    (testing "the seam still says that's everything, once"
      (is (= 1 (count (filter #(= "seam" (str (:card_id %))) (:cards doc)))))
      (is (= ["do_now" "decide" "fuel" "seam" "archive"]
             (into [] (distinct) (map #(str (:section %)) (:cards doc))))
          "the census is law, and this house exercises all of it"))))

(deftest every-card-says-why-it-is-here
  ;; The read that started waymark-iqa.29: a movie in do-now, and no
  ;; way to find out why. This asserts the four layers over the same
  ;; world — the recipe line the household wrote, the traits the kind
  ;; declares, the section's own bargain, and the day's draw.
  (let [plain (json (req :get "/api/-/feed" nil colton))
        spelled (json (req :get "/api/-/feed?explain=1" nil colton))
        card-of (fn [d kind section]
                  (first (filter #(and (= kind (str (:kind %)))
                                       (= section (str (:section %))))
                                 (:cards d))))]

    (testing "the recipe reads back, and the household's own two lines
              say what they are for"
      (let [lines (get-in plain [:recipe :lines])]
        (is (= (count (:order main/feed-recipe)) (count lines)))
        (is (every? #(seq (str (:says %))) lines))
        (is (some #(str/includes? (str (:says %)) "the work queue") lines)
            "the queue line is the household's own sentence, not the
             framework's fallback")
        (is (str/includes? (get-in plain [:recipe :guarantees])
                           "exactly one card is the seam"))))

    ;; the two do-now lines are found by their own SENTENCES rather
    ;; than by an index: waymark-jfv.4 put a section above them, and a
    ;; test that pinned line 0 was pinning a place on the page rather
    ;; than the line this house wrote
    (let [lines (get-in plain [:recipe :lines])
          line-saying (fn [s] (:line (first (filter #(str/includes?
                                                      (str (:says %)) s)
                                                    lines))))
          queue-line (line-saying "the work queue")
          rest-line (line-saying "three more")]

      (testing "a task in do-now cites the line the household dedicated to it"
        (let [c (card-of plain "task" "do_now")]
          (is (some? c))
          (is (= queue-line (:line (:why c)))
              "the queue's own two slots")
          (is (<= 1 (:rank (:why c)) (:of (:why c))))))

      (testing "and a media card in do-now cites the OTHER line, its :nav,
                and the :over that says a queued film is not over"
        (let [c (card-of spelled "media" "do_now")]
          (when c                     ; the seed may not draw one today
            (let [s (str/join " " (:says (:why c)))]
              (is (= rest-line (:line (:why c)))
                  "the general do-now line, never the queue's")
              (is (str/includes? s ":nav :primary"))
              (is (str/includes? s ":over reads its status field")
                  "the movie's own declaration, quoted — this is the sentence
                   the owner could not find anywhere")
              (is (str/includes? s "which is neither, so its work is not over"))
              (is (str/includes? s "verb light enough to tap"))
              (is (str/includes? s "seed")))))))

    (testing "and an archive card says it is below the seam because the
              work is over as the row stands NOW"
      (let [c (first (filter #(= "archive" (str (:section %)))
                             (:cards spelled)))]
        (is (some? c))
        (is (str/includes? (str/join " " (:says (:why c)))
                           "its work is over as the row stands now"))))))

(deftest the-packs-own-promises-hold-over-this-world
  ;; The obligations judge whatever an application declared, and until
  ;; now the only world they had ever judged was the conformance
  ;; walker's — a handful of rows, all of them fresh. These are the
  ;; six that READ (the two that MINT rows are conformance_test's, so
  ;; a tickler minted here could not become a card the shape test
  ;; above is counting). :feed/deal-again joined them with waymark-8um.2
  ;; and belongs here for the same reason the rest do: a draw is a
  ;; nonce in a query string, so it spins this world and leaves it
  ;; exactly as it found it.
  (let [ctx (suite/context {:engine *eng* :handler *h* :kinds [:task :media]})
        readers #{:feed/recipe-order :feed/day-stable :feed/projection
                  :feed/cursor-rolls :feed/archive-pages :feed/citations
                  :feed/deal-again}]
    (doseq [{:keys [name run]} (:obligations packs/feed)
            :when (contains? readers name)]
      (testing (str name)
        (let [out (run ctx)
              violations (if (map? out) (:violations out) out)]
          (is (empty? violations) (str/join "\n" violations)))))))
