(ns workqueue10.tickler-rank-test
  "The dropped pile is swept once, and the fridge ranks what came back
  (waymark-1uv.9, for waymark-iqa.13; the epic 'Ranked, not capped').

  Over the household's own registry and the live ring handler, with
  the authority's own fakes so a row can really be DROPPED: the sweep
  (`feed/sweep-dropped!`) sets a tickler aside for every let-go task
  and nothing else, under the engine's own hand, with the task's own
  title on it; a second sweep sets nothing aside twice; a marker a
  person set aside by their own hand stands FIRST on the fridge above
  every sweep-born one whatever its lift; three not-nows cool a marker
  below one nobody has put off; what the reader has been shown cools
  it too, off their own record; a second live marker over one row is
  refused at the door BY NAME, and a row the house let go may be set
  aside again; an agent's hand is not a person's.

  Assertions are order-independent (kaocha randomizes): the one
  deftest builds its own world in sequence, the member reads their own
  `scope \"mine\"` recipe with the fridge widened to ten, and nothing
  asserts on collection SIZE. The fixture's engine reads its clock off
  an atom so the backoff's dates can be walked past; the atom is nil —
  the real clock — until the test sets it, and it is put back in a
  `finally`.

  Needs a Postgres database; WAYMARK10_TEST_DSN names it.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.tickler-rank-test"
  (:require [calendar10.source :as gcal]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [workqueue10.sources.hub :as hub]
            [waymark10.server.engine :as engine]
            [waymark10.server.feed :as feed]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private tables
  ;; THE WHOLE FOLDED REGISTRY'S TABLES — outcome_test's rule, for its
  ;; reason: this engine boots every kind main/resources declares plus
  ;; what the module table enrols, so a fixture that dropped only its
  ;; own would boot into whatever shape another suite left behind.
  ["composition_requests" "outcome_pieces" "outcomes" "values" "people"
   "hypotheses"
   "tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "activities" "evening_plans" "evening_sessions"
   "contexts" "day_plans" "blocks" "spans"
   "letters" "weathers" "selves" "journals" "ticklers" "insights"
   "permission_slips" "saved_views" "dashboards" "dashboard_slots"
   "dwellings" "connections" "capabilities"
   "members" "roles" "grants" "approval_requests"
   "feed_recipes" "recipe_proposals" "feed_views" "feed_view_consents"
   "verdict_reasons"
   "attachments" "subscriptions" "jobs"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *todos* nil)

(def ^:private clock (atom nil))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          todos (conf/fake-source)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        ;; the household's whole registry over the authority's fakes
        ;; (feed-shape-test's posture), because only a mirror pull can
        ;; honestly DROP a task. :probe-reads mirrors production's
        ;; boot so the dedupe wall answers honestly in the envelope;
        ;; :suppress-mirror-refresh keeps the reads pure.
        (let [eng (engine/engine
                   {:storage st
                    :resources (main/resources
                                {"chore" (conf/fake-source)
                                 "meal" (conf/fake-source)
                                 "todo" todos
                                 "gtasks" (conf/fake-source)}
                                {"flickr" (conf/fake-source) "hub" (hub/source)}
                                (gcal/fake-calendar))
                    :probe-reads true
                    :now-fn (fn [] (or @clock (Instant/now)))
                    :suppress-mirror-refresh true})]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *todos* todos]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (outcome_test's idiom) ────────────────────────────

(defn- human [id] {"x-waymark-principal" id "x-waymark-actor-type" "human"})
(defn- as-agent [id] {"x-waymark-principal" id "x-waymark-actor-type" "agent"})

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

(defn- not-now! [who tid]
  (req :post (str "/api/ticklers/" tid "/-/not_now") {}
       (assoc (human who) "idempotency-key" (str (random-uuid)))))

(defn- let-go! [who tid]
  (req :post (str "/api/ticklers/" tid "/-/let_it_go") {} (human who)))

(defn- tickler [tid] (json (req :get (str "/api/ticklers/" tid) (human "colton"))))

(defn- feed-as [who & [query]]
  (json (req :get (str "/api/-/feed" (when query (str "?" query))) (human who))))

(defn- fridge-of [doc]
  (filterv #(= "tickler" (str (:kind %))) (:cards doc)))

(defn- fridge-ids [doc]
  (mapv #(last (str/split (str (:card_id %)) #"/")) (fridge-of doc)))

(defn- fridge-card [doc tid]
  (some #(when (str/ends-with? (str (:card_id %)) (str "/" tid)) %) (fridge-of doc)))

(defn- lift [card] (get-in card [:why :tickler :lift]))

(defn- says-of [card s]
  (some #(str/includes? (str %) s) (get-in card [:why :says])))

(defn- items-of [uri]
  (get-in (json (req :get uri (human "colton"))) [:data :items]))

(defn- task-by-title
  "A collection item is a summary line, not a document — so the title
  is matched on the summary, task_queue_test's own spelling."
  [title]
  (first (filter #(str/includes? (str (:summary %)) title)
                 (items-of "/api/tasks"))))

(defn- ticklers-over
  "Every OFFERED marker naming one subject, each read at its own
  address for its data. A subject id is not a filter the kind offers,
  so the items are walked; the collections here are a handful."
  [kind id]
  (filterv #(and (= kind (get-in % [:data :subject_kind]))
                 (= id (get-in % [:data :subject_id])))
           (map #(json (req :get (str (:self %)) (human "colton")))
                (items-of "/api/ticklers?state=offered"))))

(defn- index-of [ids x] (.indexOf ^java.util.List ids x))

;; ── the pile, swept once; the fridge, ranked ────────────────────────

(deftest the-dropped-pile-is-swept-once-and-the-fridge-ranks-what-came-back
  ;; the authority's side of the house: three todos it dropped, one it
  ;; still holds open
  (doseq [[k title status] [["todo.house/gutters" "Gutters — the neighbour did it" "dropped"]
                            ["todo.house/fence" "Fix the back fence" "dropped"]
                            ["todo.house/shed" "Clear out the shed" "dropped"]
                            ["todo.house/chisels" "Sharpen chisels" "open"]]]
    (conf/seed! *todos* k {:title title :status status :list_key "todo.house"}))
  (is (= 4 (mirror/discover! *eng* :task)))
  (let [who "colton"
        real-now (Instant/now)
        gutters (task-by-title "Gutters — the neighbour did it")
        fence (task-by-title "Fix the back fence")
        shed (task-by-title "Clear out the shed")
        chisels (task-by-title "Sharpen chisels")
        tid-of (fn [env] (last (str/split (str (:self env)) #"/")))]
    (is (every? some? [gutters fence shed chisels]))
    (try
      (testing "the pile is the three the authority let go, and nothing open"
        (let [pile (feed/dropped-pile {:eng *eng*})
              ids (into #{} (map (juxt :kind :id)) (:rows pile))]
          (is (false? (:reached-cap pile)))
          (is (contains? ids [:task (tid-of gutters)]))
          (is (contains? ids [:task (tid-of fence)]))
          (is (contains? ids [:task (tid-of shed)]))
          (is (not (contains? ids [:task (tid-of chisels)]))
              "an open task is do-now's business, never the fridge's")))
      (testing "one sweep sets every dropped row aside, under the engine's own
                hand, with the row's own title on it — and no cap"
        (let [{:keys [born standing refused]} (feed/sweep-dropped! *eng*)]
          (is (= 3 born) "three dropped, three markers")
          (is (= 0 standing))
          (is (= 0 refused)))
        (let [[m & more] (ticklers-over "task" (tid-of gutters))]
          (is (some? m) "the gutters carry a marker now")
          (is (empty? more) "…exactly one")
          (is (= "waymark10-tickler-sweep" (get-in m [:data :set_aside_by]))
              "set_aside_by says the sweep did it")
          (is (= "Gutters — the neighbour did it" (get-in m [:data :what]))
              "what is the subject's own label, denormalized at birth")
          (is (= (str "/api/tasks/" (tid-of gutters)) (get-in m [:data :subject_href])))
          (is (nil? (get-in m [:data :next_offer_at])) "unset means now"))
        (is (empty? (ticklers-over "task" (tid-of chisels)))))
      (testing "a second sweep sets nothing aside twice — the dedupe is a law,
                and the sweep asks before it knocks"
        (let [{:keys [born standing refused]} (feed/sweep-dropped! *eng*)]
          (is (= 0 born))
          (is (= 3 standing))
          (is (= 0 refused)))
        (is (= 1 (count (ticklers-over "task" (tid-of gutters))))))
      (let [a (tid-of (first (ticklers-over "task" (tid-of gutters))))
            b (tid-of (first (ticklers-over "task" (tid-of fence))))
            c (tid-of (first (ticklers-over "task" (tid-of shed))))]
        ;; the reader's own recipe: the fridge wide enough for all of
        ;; them, with the deployment's five numbers left standing
        (let [order (:order (:recipe (feed-as who)))
              wide (mapv #(if (= "ticklers" (str (:population %))) (assoc % :take 10) %)
                         order)
              made (req :post "/api/feed_recipes"
                        {:label "A wide fridge" :scope "mine" :order wide}
                        (human who))]
          (is (= 201 (:status made)) (pr-str (json made))))
        (testing "the recipe's five numbers ride the document, narrated"
          (let [doc (feed-as who)]
            (is (= {:overdue 1 :not_now 4 :cooled 2 :front_door 5 :age 1}
                   (get-in doc [:recipe :tickler_rank])))
            (is (str/includes? (str (get-in doc [:recipe :tickler_rank_says]))
                               "holds it 4"))))
        (testing "every swept marker cards, and each says the sweep set it aside"
          (let [doc (feed-as who "explain=1")
                ca (fridge-card doc a)]
            (is (every? #(some? (fridge-card doc %)) [a b c]) (pr-str (fridge-ids doc)))
            (is (false? (get-in ca [:why :tickler :own])))
            (is (true? (get-in ca [:why :tickler :front_door])))
            (is (= 0 (get-in ca [:why :tickler :not_now])))
            (is (= 5 (lift ca)) "a front-door row found this morning: 5, nothing else")
            (is (says-of ca "Ranked"))
            (is (says-of ca "sweep set this aside"))
            (is (says-of ca "Lift 5 in all"))
            (is (nil? (get-in ca [:why :tickler :seen])) "nobody is recording")))
        ;; a person's own marker over the open task, dated by hand and
        ;; then put off once, so its LIFT is below every swept one
        (let [mine-resp (req :post "/api/ticklers"
                             {:what "The chisels, one of these days"
                              :subject_kind "task" :subject_id (tid-of chisels)
                              :next_offer_at (str (.minusSeconds real-now 3600))}
                             (human who))
              mine (id-of mine-resp)]
          (is (= 201 (:status mine-resp)) (pr-str (json mine-resp)))
          (is (= 200 (:status (not-now! who mine))))
          (let [back (get-in (tickler mine) [:data :next_offer_at])]
            (is (= 1 (get-in (tickler mine) [:data :offer_count])))
            ;; a minute past its own not-now date: due again, and one
            ;; not-now on the record
            (reset! clock (.plusSeconds (Instant/parse (str back)) 60)))
          (testing "a person's own marker stands FIRST, above every sweep-born one,
                    with a lower lift — the hand is a tier, never a weight"
            (let [doc (feed-as who "explain=1")
                  ids (fridge-ids doc)
                  cm (fridge-card doc mine)]
              (is (= mine (first ids)) (pr-str ids))
              (is (true? (get-in cm [:why :tickler :own])))
              (is (= 1 (get-in cm [:why :tickler :not_now])))
              (is (= 1 (lift cm)) "5 for a front door, minus 4 for one not-now")
              (is (< (lift cm) (lift (fridge-card doc a))))
              (is (says-of cm "by their own hand"))
              (is (says-of cm "said not now to it 1 time"))))
          ;; three not-nows on B, then the clock walked to the day it
          ;; comes back: due again, and cooler than A for it
          (dotimes [_ 3] (is (= 200 (:status (not-now! who b)))))
          (let [back (get-in (tickler b) [:data :next_offer_at])]
            (is (= 3 (get-in (tickler b) [:data :offer_count])))
            (reset! clock (.plusSeconds (Instant/parse (str back)) 60)))
          (testing "offer_count 3 cools below offer_count 0, and the card says so"
            (let [doc (feed-as who "explain=1")
                  ids (fridge-ids doc)
                  ca (fridge-card doc a) cb (fridge-card doc b)]
              (is (every? #(some? (fridge-card doc %)) [a b mine]) (pr-str ids))
              (is (< (index-of ids a) (index-of ids b)) (pr-str ids))
              (is (= 3 (get-in cb [:why :tickler :not_now])))
              (is (= 0 (get-in cb [:why :tickler :overdue])) "a minute past is not a day")
              (is (= 2 (get-in cb [:why :tickler :age])) "two months on the pile by now")
              (is (= 7 (lift ca)) "5 for a front door + 2 for two months on the pile")
              (is (= -5 (lift cb)) "…minus 12 for three not-nows")
              (is (says-of cb "said not now to it 3 times"))
              (is (says-of cb "holding it 12"))
              (is (some? (get-in cb [:why :tickler :next_offer_at]))
                  "a marker with a date says which")
              (is (= mine (first ids)) "…and the person's own still stands first")
              (is (says-of (fridge-card doc mine) "past its own date"))))
          ;; the member turns their record on and has been shown A three
          ;; mornings running with nothing done
          (let [doc (feed-as who)
                day (str (:day doc))
                ca (fridge-card doc a)
                on (req :post "/api/feed_view_consents" {} (human who))]
            (is (= 201 (:status on)) (pr-str (json on)))
            (doseq [d (map #(str (.minusDays (java.time.LocalDate/parse day) (long %)))
                           [1 2 3])]
              (is (= 201 (:status (req :post "/api/feed_views"
                                       {:card_id (str (:card_id ca))
                                        :population "ticklers" :day d}
                                       (human who))))))
            (testing "the fridge reads the same view rows the contest does — three
                      days at cools_after 3 is one step, holding it 2"
              (let [doc' (feed-as who "explain=1")
                    ca' (fridge-card doc' a)]
                (is (= 3 (get-in ca' [:why :tickler :seen])))
                (is (= 1 (get-in ca' [:why :tickler :cooled])))
                (is (= 5 (lift ca')))
                (is (says-of ca' "1 step cooled, holding it 2"))
                (is (< (index-of (fridge-ids doc') a) (index-of (fridge-ids doc') b))
                    "still above three not-nows"))))
          (testing "a second live marker over one row is refused at the door BY
                    NAME — by a person's hand as by a sweep's"
            (let [again (req :post "/api/ticklers"
                             {:what "The fence, again"
                              :subject_kind "task" :subject_id (tid-of fence)}
                             (human who))]
              (is (= 409 (:status again)) (pr-str (json again)))
              (is (= "one-live-marker-per-subject" (str (:guard (json again)))))
              (is (str/includes? (str (:detail (json again))) (str b))
                  "the refusal names the marker already standing")))
          (testing "…and a row the house let go may be set aside again: that is
                    a new asking, not a duplicate"
            (is (= 200 (:status (let-go! who b))))
            (let [fresh (req :post "/api/ticklers"
                             {:what "The fence, once more"
                              :subject_kind "task" :subject_id (tid-of fence)}
                             (human who))]
              (is (= 201 (:status fresh)) (pr-str (json fresh)))
              (is (= 200 (:status (let-go! who (id-of fresh)))))))
          (testing "an agent's hand is not a person's: a marker an agent set aside
                    contends with the sweep's rather than standing above them"
            (is (= 200 (:status (let-go! who c))))
            (let [bot (req :post "/api/ticklers"
                           {:what "The shed, says the bot"
                            :subject_kind "task" :subject_id (tid-of shed)}
                           (as-agent "sweeper-bot"))
                  bid (id-of bot)]
              (is (= 201 (:status bot)) (pr-str (json bot)))
              (let [doc (feed-as who)
                    cb (fridge-card doc bid)]
                (is (some? cb) (pr-str (fridge-ids doc)))
                (is (false? (get-in cb [:why :tickler :own])))
                (is (= mine (first (fridge-ids doc)))))
              (is (= 200 (:status (let-go! who bid))))))
          ;; leave the house as found: every marker this test made, let go
          (doseq [tid [a mine]]
            (is (= 200 (:status (let-go! who tid)))))))
      (finally (reset! clock nil)))))
