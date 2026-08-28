(ns workqueue10.outcome-test
  "The outcome and its pieces (waymark-jfv.3), lived in over the REAL
  ring handler and the household's own registry — because the whole
  claim of this kind is that a tap MATERIALIZES, through another
  kind's own door, under the tapper's own name, and none of that can
  be judged by a declaration-time world.

  What the scenarios already prove and this file does not repeat: the
  four-eyes wall and the agent wall on the decline doors, judged with
  no database at all in the same breath as `make check-queue`. What
  only a live engine can answer is here:

  - the citation walls against a REAL value row — the routing checked
    against what that value actually says it loves, and a retired
    value taking its outcomes with it;
  - no cap at the door (waymark-1uv.3): a composer stages three, four,
    five in a week and every one is admitted — the crown's rank, not a
    wall on writing, decides what the house is shown;
  - the tap itself: a task that lands with the MEMBER on its create
    transition and the piece carrying the address it landed at;
  - `make it so` fanning out over the pieces still offered and leaving
    a declined one exactly where it was — the partial accept, which is
    the epic's centrepiece;
  - the two declines meaning different things: `not this` leaves a
    piece DECLINED (the composition was wrong), `not this week` leaves
    the rest MOOT (the timing was wrong);
  - atomicity: a piece whose target refuses at the tap rolls the whole
    tap back, so no outcome ever reads accepted while a piece silently
    did not land;
  - the crown's rank over real rows (§ 16), and a recomposition staged
    before the house said it would hear it — admitted, cooled by every
    day it is early, and still on the page (§ 17, waymark-1uv.10);
  - an agent's score and sentence on a bundle (§ 18, waymark-1uv.6):
    one weighted input to the rank, the sentence quoted as the agent's,
    the composer refused on its own bundle, and the weight turned down
    to nothing.
  - the diagnosis duty (waymark-8um.4): the composer's document at
    `/api/-/diagnosis` reading exposure off real view rows and reasons
    off real reason rows, and `no-burial-without-a-diagnosis` standing
    in front of the floor — shown-and-declined refused without a
    diagnosis, never-shown recomposing freely, unknown said as unknown.
  - the tally (waymark-1uv.4): six staged bundles, two shown, and the
    document counting each lesson, every outcome carrying the rank's
    own reading of it as staged, the never-shown pile naming its lifts,
    and unknown staying unknown without opt-in.
  - the anti-twin wall (waymark-8gc, § 22): a bundle over a row a
    standing outcome already cites, refused by name; distinct evidence
    admitted; the person's own pull admitted over the overlap; a
    recomposition of a declined prior re-citing exactly what the prior
    cited; and two bundles serving one value both admitted, because a
    row's own value is what it serves rather than something it read.
  - the closed book (waymark-euj, § 23): a bundle whose every citation
    is a finished row refused by name and by the WORD each row is
    finished with, one standing citation enough to admit it, a past
    event judged by its own clock, the value-only citation refused as
    no reading at all — and, on the piece, a door the ROW has shut
    refused at staging in that door's own words while a door shut only
    against the composer's HAND stages, because a member is the one
    who taps.

  THE COMPOSER HERE IS A PERSON, and deliberately. The four-eyes wall
  is `g/not-the-field :composed_by` — it compares principal IDS, so a
  second human staging is exactly as walled as an agent would be, and
  the agent wall proper is proved by scenario where it costs nothing.
  Staging as an agent would need a grant minted through the approval
  door, which is `waymark-jfv.5`'s operational walk rather than this
  kind's law.

  Assertions are order-independent (kaocha randomizes, and the
  deftests share one DB): every test names its own principals and its
  own rows, and none asserts on collection SIZE.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.outcome-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.feed :as feed]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]
            [workqueue10.resources.outcome :as outcome])
  (:import (java.time Instant LocalDate)))

(def ^:private tables
  ;; THE WHOLE FOLDED REGISTRY'S TABLES — conformance_test's rule, and
  ;; the reason it exists: this engine boots every kind
  ;; main/check-resources declares, so a fixture that dropped only its
  ;; own three would boot into whatever shape another suite left
  ;; behind, and a promoted column added to a folded kind refuses at
  ;; boot with a storage-drift plan.
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
   "attachments" "subscriptions" "jobs"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private clock
  "The engine's clock, when a test needs to move it: nil is the real
  one. The rank tests (§ 16, § 17) pin it so `days_left` and `early`
  are whole numbers a test can name — the same instant at staging and
  at the read — and § 16 walks the house a week past a decline so its
  recomposition is ON TIME rather than early (the door no longer
  holds it either way, waymark-1uv.10; the numbers are what the walk
  is for). Each puts the clock back when done. Kaocha runs one
  namespace's tests sequentially, which is what makes an atom honest
  here."
  (atom nil))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        ;; the household's WHOLE registry over the offline fakes —
        ;; main/check-resources, the same declaration gate `make
        ;; check-queue` judges. It has to be the whole thing: a piece
        ;; materializes into `task` and `event`, and an engine holding
        ;; only the outcome kinds could not have judged the prepared
        ;; input against a door that was not there.
        ;;
        ;; :probe-reads mirrors production's boot (main/start!): the
        ;; render probe carries the read hooks, so a guard that judges
        ;; against a ROW of another kind — every citation wall here —
        ;; answers honestly in the envelope instead of advertising
        ;; optimistically. :suppress-mirror-refresh keeps the walker's
        ;; reads pure, the conformance fixture's own posture; no
        ;; with-push, so nothing here calls Google.
        (let [eng (engine/engine {:storage st
                                  :resources (main/check-resources)
                                  :probe-reads true
                                  :now-fn (fn [] (or @clock (Instant/now)))
                                  :suppress-mirror-refresh true})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (letters_test's idiom) ────────────────────────────

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
(defn- fields [resp] (:data (json resp)))
(defn- guard-of [resp] (:guard (json resp)))
(defn- detail [resp] (str (:detail (json resp))))

(defn- invoke! [plural id action body headers]
  (req :post (str "/api/" plural "/" id "/-/" (name action))
       (or body {}) headers))

(defn- creators
  "Who the log says created a row — the whole point of the materialize
  handler, read off the transitions the engine wrote rather than off
  anything the row says about itself."
  [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (into #{} (map (fn [rec] [(:action rec) (get-in rec [:actor :id])]))
            (store/transitions (:storage *eng*) tx
                               {:kind kind :resource-id id} {:limit 20})))))

(defn- under-grant
  "Which grant the log says a transition was made UNDER (waymark-sfe) —
  the actor's own `grant` key, absent on every unscoped write. Read
  off the audit trail rather than off anything the response claims, so
  what is proved is what a reader of history will see."
  [kind id action]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (some (fn [rec]
              (when (= action (:action rec)) (get-in rec [:actor :grant])))
            (store/transitions (:storage *eng*) tx
                               {:kind kind :resource-id id}
                               {:limit 20 :newest-first true})))))

;; ── the household's own rows ────────────────────────────────────────

(defn- declare-value! [who name' loved]
  (json (req :post "/api/values"
             {:name name' :scope "household"
              :says "The boys will remember what we made together, not what I shipped."
              :loved loved}
             (human who))))

(defn- vid [v] (last (str/split (str (:self v)) #"/")))

(defn- a-row-read
  "One address a bundle says it READ, minted fresh per staging
  (waymark-euj). Two walls now care what an evidence list holds and
  they want opposite things from a fixture: `not-a-twin` wants no two
  bundles sharing a row, and `composes-from-what-stands` wants at
  least one row that is not finished. A fresh address satisfies both —
  the collection is one this house serves (which is all
  `cites-what-it-read` judges) and the row is not there, which is the
  arm the standing wall deliberately leaves open: it never guesses
  past what it can read, so a row it cannot classify stands.

  A bundle's OWN value does not do this job, and that is the wall's
  point — value_id already says what the bundle serves, so citing it
  under `evidence` is not a reading."
  []
  (str "/api/tasks/01HZQ7READ"
       (str/upper-case (subs (str (random-uuid)) 0 8))))

(defn- stage-outcome!
  ([who value-id] (stage-outcome! who value-id {}))
  ([who value-id extra]
   (req :post "/api/outcomes"
        (merge {:goal "One Saturday afternoon in the shop with Jack, and a finished box"
                :value_id value-id
                :routing "It runs through the shop, which you said you love — the expensive part is already paid."
                :evidence [(str "/api/values/" value-id) (a-row-read)]}
               extra)
        (human who))))

(defn- stage-piece! [who outcome-id says target prepared]
  (req :post "/api/outcome_pieces"
       {:outcome_id outcome-id :says says
        :form "create"
        :target_kind target :prepared prepared}
       (human who)))

;; ── 1. staging: the stamps are the engine's ─────────────────────────

(deftest a-bundle-stages-and-every-stamp-is-the-engines
  (let [v (declare-value! "colton-stage" "making memories with the family"
                          ["the shop" "building with the boys"])
        made (stage-outcome! "composer-stage" (vid v)
                             {:routes_through "the shop"})
        d (fields made)]
    (testing "the outcome lands"
      (is (= 201 (:status made)))
      (is (= "offered" (:state (json made)))))
    (testing "who composed it is stamped from the principal, never the body"
      (is (= "composer-stage" (:composed_by d))))
    (testing "the leash is the engine's — a week, and not the caller's to name"
      (let [good-until (Instant/parse (str (:good_until d)))
            expected (.plusSeconds (Instant/now)
                                   (* 86400 (long outcome/leash-days)))]
        (is (< (Math/abs (- (.getEpochSecond good-until)
                            (.getEpochSecond expected)))
               120))))
    (testing "the value's own words ride along, copied by the engine"
      (is (= "making memories with the family" (:value_name d))))
    (testing "and the chain starts at nought"
      (is (= 0 (:declined_count d))))
    (testing "a composer naming somebody else as the author does not get away with it"
      (let [forged (stage-outcome! "composer-stage" (vid v)
                                   {:composed_by "colton-stage"})]
        (is (not= 201 (:status forged)))))))

;; ── 2. the citation walls, against real values ──────────────────────

(deftest a-routing-cites-what-the-value-actually-says-it-loves
  (let [v (declare-value! "colton-route" "building" ["the shop"])
        bad (stage-outcome! "composer-route" (vid v)
                            {:routes_through "the tanning bed"})]
    (testing "an invented loved activity is refused, and the refusal names the legal words"
      (is (= 409 (:status bad)))
      (is (= "routes-through-something-loved" (guard-of bad)))
      (is (str/includes? (detail bad) "the shop")))
    (testing "the value's own spelling passes"
      (is (= 201 (:status (stage-outcome! "composer-route" (vid v)
                                          {:routes_through "the shop"})))))
    (testing "naming NO routing is allowed on purpose — some outcomes route through nothing anybody loves"
      (is (= 201 (:status (stage-outcome! "composer-route2" (vid v))))))))

(deftest retiring-a-value-is-how-the-house-stops-hearing-about-it
  (let [v (declare-value! "colton-retire" "the old shed" ["tinkering"])
        id (vid v)]
    (is (= 200 (:status (invoke! "values" id :retire nil
                                 (human "colton-retire")))))
    (let [refused (stage-outcome! "composer-retire" id)]
      (is (= 409 (:status refused)))
      (is (= "names-a-value" (guard-of refused)))
      (is (str/includes? (detail refused) "retired")))))

;; ── 3. no cap at the door: the rank, not the wall, decides ──────────
;;
;; The inverse of the test that stood here from jfv.3 to 1uv.2
;; (`two-outcomes-a-week-per-composer-monday-to-monday`). The owner's
;; ruling: *it makes more sense to just rank them.* A composer stages
;; as many as it likes in a week and every one is admitted; what the
;; house is SHOWN is the crown's rank's business (§ 16), and the door
;; has no opinion about how much indexing a week may hold.

(deftest a-composer-stages-without-limit-and-the-rank-decides-what-shows
  (let [v (declare-value! "colton-uncapped" "an uncapped week" ["the shop"])
        who "composer-uncapped"
        staged (vec (repeatedly 5 #(stage-outcome! who (vid v))))]
    (testing "three, four, five in one week — every one is admitted, none meets a pace wall"
      (is (= [201 201 201 201 201] (mapv :status staged))
          (pr-str (mapv (comp :detail json) staged)))
      (is (= 5 (count (distinct (map id-of staged))))))
    (testing "and nothing about the door names a week: no guard in the envelope's refusals is a pace"
      (let [env (json (req :get (str "/api/outcomes/" (id-of (first staged)))
                           (human "colton-uncapped")))]
        (is (= "offered" (:state env)))))
    (testing "a second composer is equally unbounded — there is no allowance to be quiet inside of"
      (is (= 201 (:status (stage-outcome! "composer-uncapped-other" (vid v))))))))

;; ── 4. staging validation: the door it will knock on ────────────────

(deftest a-piece-is-judged-by-the-door-it-will-knock-on
  (let [v (declare-value! "colton-fit" "a fitted bundle" ["the shop"])
        o (id-of (stage-outcome! "composer-fit" (vid v)))]
    (testing "a body the task door would take stages"
      (is (= 201 (:status (stage-piece! "composer-fit" o
                                        "Cut the stock to length"
                                        "task" {:title "Cut the box stock"})))))
    (testing "a field the target has never heard of is refused HERE, not at the tap"
      (let [r (stage-piece! "composer-fit" o "Put it on the calendar"
                            "event" {:title "Shop afternoon"
                                     :when "Saturday at 2"})]
        (is (= 409 (:status r)))
        (is (= "the-prepared-input-fits-the-door" (guard-of r)))))
    (testing "and so is a value the target's own schema could not decode"
      (let [r (stage-piece! "composer-fit" o "Put it on the calendar"
                            "event" {:title "Shop afternoon"
                                     :starts_at "Saturday at 2"})]
        (is (= 409 (:status r)))
        (is (= "the-prepared-input-fits-the-door" (guard-of r)))))
    ;; waymark-jfv.9 INVERTED THIS ONE, and the inversion is the bead.
    ;; jfv.3 asserted 422 here: `target_kind` was a closed enum, so a
    ;; kind outside it never reached a guard at all. The owner ruled
    ;; that a piece may name anything and that what he needs is to
    ;; inspect the impact — so a governance kind is now REACHABLE, its
    ;; own create model judges the body, and the wall that used to be
    ;; the enum is the engine's sentence on the row plus that kind's
    ;; own guards under the member's hand.
    (testing "a governance kind is no longer walled out — its own door judges the body"
      (let [r (stage-piece! "composer-fit" o "Grant yourself something"
                            "grant" {:audience "me"})]
        (is (= 409 (:status r)))
        (is (= "the-prepared-input-fits-the-door" (guard-of r)))
        (is (str/includes? (str (detail r)) "grant's own create door"))))
    (testing "a kind this house does not serve at all is still nothing"
      (let [r (stage-piece! "composer-fit" o "Reach for a kind nobody has"
                            "unicorn" {:title "Nope"})]
        (is (= 409 (:status r)))
        (is (= "the-prepared-input-fits-the-door" (guard-of r)))
        (is (str/includes? (str (detail r)) "no such kind"))))
    (testing "six pieces is a week rather than an afternoon"
      (dotimes [n 4]
        (is (= 201 (:status (stage-piece! "composer-fit" o
                                          (str "Piece " n) "task"
                                          {:title (str "Step " n)})))))
      (let [r (stage-piece! "composer-fit" o "One too many" "task"
                            {:title "One too many"})]
        (is (= 409 (:status r)))
        (is (= "a-bundle-is-small" (guard-of r)))))))

;; ── 5. the tap IS the write, and it is the member's own ─────────────

(deftest one-tap-materializes-under-the-tappers-own-name
  (let [v (declare-value! "colton-tap" "a real Saturday" ["the shop"])
        o (id-of (stage-outcome! "composer-tap" (vid v)))
        p (id-of (stage-piece! "composer-tap" o
                               "Cut the stock Friday evening"
                               "task" {:title "Cut the box stock to length"}))
        took (invoke! "outcome_pieces" p :take nil (human "colton-tap"))
        d (:data (json took))]
    (testing "the piece is taken and says where it landed"
      (is (= 200 (:status took)))
      (is (= "taken" (:state (json took))))
      (is (= "colton-tap" (:decided_by d)))
      (is (str/starts-with? (str (:materialized d)) "/api/tasks/")))
    (let [tid (last (str/split (str (:materialized d)) #"/"))
          task (req :get (str "/api/tasks/" tid) (human "colton-tap"))]
      (testing "a real task landed, with the prepared title"
        (is (= 200 (:status task)))
        (is (= "Cut the box stock to length" (:title (fields task)))))
      (testing "and the MEMBER's name is on its create transition — not the composer's, not a system actor's"
        (is (contains? (creators :task tid) [:create "colton-tap"]))
        (is (not-any? (fn [[_ who]] (= "composer-tap" who))
                      (creators :task tid)))))
    (testing "a second tap makes no second task — the verdict door IS the idempotency boundary, which is exactly why no deterministic key is minted for the inner write"
      ;; the row is already terminal, so the engine's natural replay
      ;; hands back the answer it gave the first time rather than
      ;; running the handler again: same address, one task
      (let [again (invoke! "outcome_pieces" p :take nil (human "colton-tap"))]
        (is (= 200 (:status again)))
        (is (= (:materialized d) (:materialized (:data (json again)))))))))

;; ── 6. the partial accept, which is the whole centrepiece ───────────

(deftest decline-the-ones-you-do-not-want-then-make-it-so
  (let [v (declare-value! "colton-part" "an afternoon, partly" ["the shop"])
        o (id-of (stage-outcome! "composer-part" (vid v)))
        keep' (id-of (stage-piece! "composer-part" o "Cut the stock"
                                   "task" {:title "Cut the stock (kept)"}))
        drop' (id-of (stage-piece! "composer-part" o "Buy a new blade"
                                   "task" {:title "Buy a blade (declined)"}))
        _ (invoke! "outcome_pieces" drop' :not_this nil (human "colton-part"))
        made (invoke! "outcomes" o :make_it_so nil (human "colton-part"))]
    (testing "the bundle reads accepted"
      (is (= 200 (:status made)))
      (is (= "accepted" (:state (json made))))
      (is (= "colton-part" (:decided_by (:data (json made))))))
    (testing "the piece still on offer materialized"
      (let [r (req :get (str "/api/outcome_pieces/" keep') (human "colton-part"))]
        (is (= "taken" (:state (json r))))
        (is (str/starts-with? (str (:materialized (fields r))) "/api/tasks/"))))
    (testing "the declined one stayed declined and made nothing"
      (let [r (req :get (str "/api/outcome_pieces/" drop') (human "colton-part"))]
        (is (= "declined" (:state (json r))))
        (is (nil? (:materialized (fields r))))))
    (testing "and with everything answered there is nothing left for make it so to do"
      (let [o2 (id-of (stage-outcome! "composer-part2" (vid v)))
            only (id-of (stage-piece! "composer-part2" o2 "The only one"
                                      "task" {:title "The only one"}))]
        (invoke! "outcome_pieces" only :not_this nil (human "colton-part"))
        (let [r (invoke! "outcomes" o2 :make_it_so nil (human "colton-part"))]
          (is (= 409 (:status r)))
          (is (= "something-is-still-on-offer" (guard-of r))))))))

;; ── 7. the two declines mean different things ───────────────────────

(deftest not-this-week-moots-the-rest-and-says-when-to-come-back
  (let [v (declare-value! "colton-week" "a wrong week" ["the shop"])
        o (id-of (stage-outcome! "composer-week" (vid v)))
        a (id-of (stage-piece! "composer-week" o "Cut the stock" "task"
                               {:title "Cut the stock (mooted)"}))
        b (id-of (stage-piece! "composer-week" o "Sand it" "task"
                               {:title "Sand it (mooted)"}))
        said (invoke! "outcomes" o :not_this_week nil (human "colton-week"))
        d (:data (json said))]
    (testing "the outcome is declined"
      (is (= 200 (:status said)))
      (is (= "declined" (:state (json said)))))
    (testing "every still-offered piece is MOOT and not DECLINED — the timing was wrong, not the composition"
      (doseq [p [a b]]
        (is (= "moot" (:state (json (req :get (str "/api/outcome_pieces/" p)
                                         (human "colton-week"))))))))
    (testing "and the house says when it is willing to hear this recomposed — the tickler's first step"
      (is (= 1 (:declined_count d)))
      (let [floor (Instant/parse (str (:not_before d)))]
        (is (pos? (compare floor (Instant/now))))))
    ;; waymark-8um.4 MOVED THIS CLAIM ONE WALL BACK, and the move is the
    ;; bead: a declined outcome was SHOWN — a person read the card and
    ;; answered it — so the composer's duty fires before the date does.
    ;; The floor's own sentence is proved in § 16, behind a diagnosis.
    (testing "a recomposition staged the next morning hears about the diagnosis first, not the date"
      (let [r (stage-outcome! "composer-week" (vid v) {:supersedes o})]
        (is (= 409 (:status r)))
        (is (= "no-burial-without-a-diagnosis" (guard-of r)))
        (is (str/includes? (detail r) "shown and declined"))))
    (testing "and so is one that supersedes an outcome nobody has answered yet"
      (let [live (id-of (stage-outcome! "composer-week2" (vid v)))
            r (stage-outcome! "composer-week2" (vid v) {:supersedes live})]
        (is (= 409 (:status r)))
        (is (= "a-recomposition-waits-its-turn" (guard-of r)))))))

;; ── 8. atomicity, and the stale answer ──────────────────────────────

(deftest a-refusal-inside-rolls-the-whole-tap-back
  ;; The world moves between staging and the tap, and the answer is
  ;; that the TARGET'S OWN create guards judge at the tap rather than
  ;; a second staleness oracle in the outcome. `task/one-due` is that
  ;; law standing in for the moved world: the prepared body is
  ;; perfectly SHAPED (both due fields are optional), so staging
  ;; admits it, and the task's own guard is what refuses when somebody
  ;; taps — inside the transaction, so nothing lands and nothing reads
  ;; accepted.
  (let [v (declare-value! "colton-atom" "an atomic afternoon" ["the shop"])
        o (id-of (stage-outcome! "composer-atom" (vid v)))
        good (id-of (stage-piece! "composer-atom" o "Cut the stock" "task"
                                  {:title "Cut the stock (rolled back)"}))
        bad (id-of (stage-piece! "composer-atom" o "Call the lumber yard"
                                 "task" {:title "Call the yard"
                                         :due_at "2026-09-05T17:00:00Z"
                                         :due_date "2026-09-05"}))
        made (invoke! "outcomes" o :make_it_so nil (human "colton-atom"))]
    (testing "the tap is refused by the TARGET's own law, not by a second opinion about it"
      (is (= 409 (:status made)))
      (is (= "one-due" (guard-of made))))
    (testing "and nothing landed: the outcome is still on the fridge, both pieces still offered"
      (is (= "offered" (:state (json (req :get (str "/api/outcomes/" o)
                                          (human "colton-atom"))))))
      (doseq [p [good bad]]
        (is (= "offered" (:state (json (req :get (str "/api/outcome_pieces/" p)
                                            (human "colton-atom"))))))))
    (testing "the household's way out is two taps: not this on the stale piece, make it so again"
      (is (= 200 (:status (invoke! "outcome_pieces" bad :not_this nil
                                   (human "colton-atom")))))
      (let [again (invoke! "outcomes" o :make_it_so nil (human "colton-atom"))]
        (is (= 200 (:status again)))
        (is (= "accepted" (:state (json again))))))))

;; ── 9. the composer reads what it staged, and nothing else ──────────

(deftest the-composer-sees-its-own-bundles-and-no-doors-on-them
  (let [v (declare-value! "colton-see" "a readable bundle" ["the shop"])
        o (id-of (stage-outcome! "composer-see" (vid v)))
        own (req :get (str "/api/outcomes/" o) (human "composer-see"))]
    (testing "the stager reads its own row — a composer that could not see how it was answered would stage it again tomorrow"
      (is (= 200 (:status own))))
    (testing "and is offered no verdict on it: the four-eyes wall means every one of those doors answers 409"
      (is (not-any? #{"make_it_so" "not_this_week"}
                    (map (comp str :name) (:actions (json own))))))))

;; ── 10. the impact line, and the rows that predate it ───────────────
;;
;; The owner's own discomfort (waymark-jfv.17): *I'm not yet
;; comfortable using the crown because I'm not sure what impact the
;; actions will have.* What the pack proves over the wire is that the
;; line reaches the card and names the row. What only a live engine
;; can answer is the OTHER half of the law — that the line is written
;; at STAGING onto the row, and that a piece staged before the law
;; existed still reads one, because both paths run one function.

(defn- clear-impact!
  "Strip the stored line off a piece row, in place — the only way to
  manufacture the four pieces that were already on offer in
  production when this law landed. It writes DATA and nothing else:
  no transition, no version bump, so the row that comes back out is
  as close to a pre-jfv.17 row as this tree can make one."
  [id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (let [row (store/load-row (:storage *eng*) tx :outcome_piece id {})]
        (store/update-data! (:storage *eng*) tx :outcome_piece id
                            (dissoc (:data row) "impact" :impact)
                            nil)))))

(deftest the-engine-says-what-the-tap-will-do
  (let [v (declare-value! "colton-impact" "an inspectable Saturday"
                          ["the shop"])
        o (id-of (stage-outcome! "composer-impact" (vid v)))
        p (id-of (stage-piece! "composer-impact" o
                               "Cut the stock Friday evening"
                               "task" {:title "Cut the box stock to length"}))
        q (id-of (stage-piece! "composer-impact" o
                               "Put the afternoon on the calendar"
                               "task" {:title "Book the shop afternoon"}))
        d (fields (req :get (str "/api/outcome_pieces/" p)
                       (human "colton-impact")))]
    (testing "the line is on the ROW, written at staging"
      (is (not (str/blank? (str (:impact d))))))
    (testing "and it is the ENGINE's sentence: it names the row the tap would create"
      (is (str/includes? (str (:impact d)) "Cut the box stock to length")))
    (testing "…says the verb the household will actually tap"
      (is (str/starts-with? (str (:impact d)) "Yes will create one task")))
    (testing "…names the mirrored consequence, because a task lands twice"
      (is (str/includes? (str (:impact d)) "mirror")))
    (testing "…and closes the door on everything else"
      (is (str/includes? (str (:impact d)) "Nothing else.")))
    (testing "no composer can write it, and no composer can overwrite it"
      ;; `impact` is out of the create model, so a body carrying one is
      ;; refused by the door's own closed-errors rather than quietly
      ;; ignored — which is what makes 'the engine's reading' a fact
      ;; about the row instead of a promise about the composer
      (let [forged (req :post "/api/outcome_pieces"
                        {:outcome_id o :says "A piece with its own story"
                         :form "create"
                         :target_kind "task" :prepared {:title "Forged"}
                         :impact "Yes will create nothing at all."}
                        (human "composer-impact"))]
        (is (= 422 (:status forged)))))
    (testing "a piece staged BEFORE this law reads its line at the read, with no backfill"
      (clear-impact! q)
      (let [bare (fields (req :get (str "/api/outcome_pieces/" q)
                              (human "colton-impact")))
            feed' (json (req :get "/api/-/feed" (human "colton-impact")))
            card (some #(when (and (= "outcome" (str (:kind %)))
                                   (str/ends-with? (str (:self %)) o)) %)
                       (:cards feed'))
            piece (some #(when (str/ends-with? (str (:self %)) q) %)
                        (:pieces card))]
        (is (str/blank? (str (:impact bare)))
            "the row really has no stored line")
        (is (some? piece) "the piece is on the card")
        (is (str/includes? (str (:impact piece)) "Book the shop afternoon")
            "and the card carries the same sentence, derived at the read")))
    (testing "the bundle states the union its own verb would take"
      (let [feed' (json (req :get "/api/-/feed" (human "colton-impact")))
            card (some #(when (and (= "outcome" (str (:kind %)))
                                   (str/ends-with? (str (:self %)) o)) %)
                       (:cards feed'))]
        (is (some? card))
        (is (str/includes? (str (:impact card)) "Make it so = all 2 pieces"))))))

;; ── 11. the open piece (waymark-jfv.9) ──────────────────────────────
;;
;; The owner's ruling, verbatim: *a piece can do whatever it wants,
;; but I just need to be able to inspect the impact — what it's
;; actually going to do.* jfv.3's closed enum came off, and what
;; stands in its place is four things a declaration-time world cannot
;; judge: the engine's sentence about a door it read off the registry,
;; the version fence, and the two halves of the safety story — a
;; four-eyes wall on the TARGET holding at the tap, and a governance
;; door the member's own hand may lawfully walk through.

(defn- stage-invoke-piece!
  [who outcome-id says kind target-id action prepared]
  (req :post "/api/outcome_pieces"
       {:outcome_id outcome-id :says says
        :form "invoke" :target_kind kind
        :target_id (str target-id) :target_action (name action)
        :prepared (or prepared {})}
       (human who)))

(defn- make-task! [who title]
  (id-of (req :post "/api/tasks" {:title title} (human who))))

(deftest an-invoke-piece-moves-the-row-it-names
  (let [v (declare-value! "colton-inv" "an open piece" ["the shop"])
        o (id-of (stage-outcome! "composer-inv" (vid v)))
        t (make-task! "colton-inv" "Cut the box stock to length")
        p (stage-invoke-piece! "composer-inv" o
                               "Mark the stock cut once Friday is done"
                               "task" t "complete" {})
        d (fields p)]
    (testing "it stages: the form is on the row, explicit, and so is the door it names"
      (is (= 201 (:status p)))
      (is (= "invoke" (:form d)))
      (is (= "task" (:target_kind d)))
      (is (= "complete" (:target_action d)))
      (is (= t (:target_id d))))
    (testing "and the engine wrote what the tap will do — the door by its OWN label, the row by its own name"
      (is (str/includes? (str (:impact d)) "\"Done\" door"))
      (is (str/includes? (str (:impact d)) "Cut the box stock to length"))
      (is (str/includes? (str (:impact d)) "already stands"))
      (is (str/includes? (str (:impact d)) "Nothing else.")))
    (testing "and stamped the version it was staged against — the fence's own half"
      (is (some? (:target_version d))))
    (let [took (invoke! "outcome_pieces" (id-of p) :take nil
                        (human "colton-inv"))
          after (fields took)]
      (testing "the tap takes the piece and cites the row it moved"
        (is (= 200 (:status took)))
        (is (= "taken" (:state (json took))))
        (is (= (str "/api/tasks/" t) (str (:materialized after)))))
      (testing "the TARGET really moved, through its own door"
        (is (= "done" (:status (fields (req :get (str "/api/tasks/" t)
                                            (human "colton-inv")))))))
      (testing "…and the MEMBER is on that transition, never the composer"
        (is (contains? (creators :task t) [:complete "colton-inv"]))
        (is (not-any? #(= "composer-inv" (second %)) (creators :task t)))))))

(deftest the-fence-refuses-a-target-that-moved
  ;; recipe_proposal's `the-order-has-not-moved` generalized past one
  ;; kind. The framework's own `:if-match` is consulted only when the
  ;; TARGET action declares a fence, which `complete` does not — so
  ;; without this wall a stale tap would have gone through without a
  ;; word, and the sentence the household read would have described a
  ;; world that no longer existed.
  (let [v (declare-value! "colton-fence" "a fenced piece" ["the shop"])
        o (id-of (stage-outcome! "composer-fence" (vid v)))
        t (make-task! "colton-fence" "Call the lumber yard")
        p (id-of (stage-invoke-piece! "composer-fence" o
                                      "Mark the call made"
                                      "task" t "complete" {}))]
    (testing "somebody moves the target between staging and the tap"
      (is (= 200 (:status (invoke! "tasks" t :complete nil
                                   (human "colton-fence"))))))
    (let [took (invoke! "outcome_pieces" p :take nil (human "colton-fence"))]
      (testing "the tap refuses BY NAME rather than writing over the top"
        (is (= 409 (:status took)))
        (is (= "the-target-has-not-moved" (guard-of took))))
      (testing "and the refusal names the drift — both versions and where the row stands now"
        (is (str/includes? (detail took) (str "/api/tasks/" t)))
        (is (str/includes? (detail took) "was at v"))
        (is (str/includes? (detail took) "is at v")))
      (testing "nothing landed: the piece is still on offer, and the way out is two taps"
        (is (= "offered" (:state (json (req :get (str "/api/outcome_pieces/" p)
                                            (human "colton-fence"))))))
        (is (= 200 (:status (invoke! "outcome_pieces" p :not_this nil
                                     (human "colton-fence")))))))))

(deftest a-four-eyes-wall-on-the-target-holds-at-the-tap
  ;; HALF ONE OF THE RULING'S SAFETY STORY. Governance doors are not
  ;; walled off any more — and they do not need to be, because the tap
  ;; IS a person's hand and the target's own guards judge it as that
  ;; person, in the transaction. `outcome/the-composer-does-not-decide`
  ;; is `g/not-the-field`, the very guard `desugar-decision` mints for
  ;; every `:decision` kind's `:decider`, so this is the same wall an
  ;; approval_request wears — proved on the door this file can stage
  ;; both sides of.
  (let [v (declare-value! "colton-4e" "four eyes, still" ["the shop"])
        his (id-of (stage-outcome! "colton-4e" (vid v)))
        o (id-of (stage-outcome! "composer-4e" (vid v)))
        p (stage-invoke-piece! "composer-4e" o
                               "Set the bundle you staged yourself aside"
                               "outcome" his "not_this_week" {})]
    (testing "the piece stages — no enum stands in front of a governance door any more"
      (is (= 201 (:status p))))
    (let [took (invoke! "outcome_pieces" (id-of p) :take nil
                        (human "colton-4e"))]
      (testing "and the TARGET's own four-eyes wall refuses the wrong hand, at the tap"
        (is (= 409 (:status took)))
        (is (= "the-composer-does-not-decide" (guard-of took))))
      (testing "nothing moved: the target is still offered and so is the piece"
        (is (= "offered" (:state (json (req :get (str "/api/outcomes/" his)
                                            (human "colton-4e"))))))
        (is (= "offered" (:state (json (req :get (str "/api/outcome_pieces/"
                                                      (id-of p))
                                            (human "colton-4e"))))))))))

(deftest a-value-is-affirmed-through-a-piece
  ;; HALF TWO. The mirror image, and it is the half that makes the
  ;; first one mean something: the member's own tap affirming his own
  ;; value THROUGH a piece is lawful, because it is his hand. A wall
  ;; that had refused this would have been a capability wall wearing a
  ;; safety argument, which is exactly what the ruling took down.
  (let [v (declare-value! "colton-aff" "unhurried Saturdays, affirmed"
                          ["the shop"])
        vid' (vid v)
        o (id-of (stage-outcome! "composer-aff" vid'))
        p (stage-invoke-piece! "composer-aff" o
                               "Say whether this one is still yours"
                               "value" vid' "still_stands" {})]
    (testing "a piece may name a value's own affirmation door"
      (is (= 201 (:status p)))
      (is (str/includes? (str (:impact (fields p))) "already stands"))
      (is (str/includes? (str (:impact (fields p)))
                         "unhurried Saturdays, affirmed")))
    (let [took (invoke! "outcome_pieces" (id-of p) :take nil
                        (human "colton-aff"))]
      (testing "and the member's tap WORKS — written-by-a-person passes, because a person is tapping"
        (is (= 200 (:status took)))
        (is (= "taken" (:state (json took)))))
      (testing "the value carries the member's own affirmation, not the composer's"
        (let [after (fields (req :get (str "/api/values/" vid')
                                 (human "colton-aff")))]
          (is (= "colton-aff" (:affirmed_by after)))
          (is (some? (:affirmed_at after))))))))

(deftest a-piece-may-not-half-approve-an-ask
  ;; The one door an OPEN piece is refused, and it is not a wall about
  ;; authority. `grants/approval-effects!` mints the approved ask's
  ;; grant post-commit, at the router's boundary; a tap fired from
  ;; inside a handler never reaches out there, so the ask would go
  ;; terminal and the leash it was FOR would never exist. The set is
  ;; read off `grants/wire-boundary-effects`, so waymark-442.14 empties
  ;; it and this refusal disappears with it.
  (let [v (declare-value! "colton-half" "no half answers" ["the shop"])
        o (id-of (stage-outcome! "composer-half" (vid v)))
        r (stage-invoke-piece! "composer-half" o "Approve the leash"
                               "approval_request"
                               "01HZQ7Y7F2R3W4V5X6Y7Z8A9B8" "approve" {})]
    (is (= 409 (:status r)))
    (is (= "the-door-carries-its-own-effect" (guard-of r)))
    (is (str/includes? (detail r) "on its own screen"))))

;; ── 15. the person's pull (waymark-jfv.20) ──────────────────────────
;;
;; The owner's own sentence: *I want to be able to just keep requesting
;; outcomes.* A request is a person's consent given in advance; it was
;; born to get past the cap on the machine's initiative and outlived
;; it (waymark-1uv.3) as the crown rank's first tier. What only a live
;; engine can answer is here: the request moving to answered INSIDE
;; the outcome's own staging (the `:within` seam, waymark-jfv.20's one
;; framework growth), a second citation meeting that state, the aim
;; being honoured — and nothing counting, before or after the
;; citation.

(defn- leash!
  "An agent HOLDING a grant over the request kind's named doors — the
  pack's own idiom, for its own reason: an unleashed agent is already
  answered 404 by the router's default deny, and that proves nothing
  about any wall. → the headers that present the leash."
  ([id actions]
   (leash! id [{:kind "composition_request" :actions actions}] :scope))
  ;; …and the whole SCOPE, for a leash that covers more than one kind
  ;; (waymark-8um.4's composer reads two records and writes three
  ;; kinds) — the pack's own second arity, one register over
  ([id scope _]
   (let [hs {"x-waymark-principal" id "x-waymark-actor-type" "agent"}
         made (req :post "/api/grants"
                   {:audience id :scope scope}
                   (human "colton-leash"))
         gid (id-of made)
         took (invoke! "grants" gid :accept nil hs)]
     (assert (= 201 (:status made)) (pr-str (json made)))
     (assert (= 200 (:status took)) (pr-str (json took)))
     (assoc hs "x-waymark-grant" gid))))

(defn- ask!
  ([who] (ask! who nil))
  ([who value-id]
   (req :post "/api/composition_requests"
        (cond-> {} value-id (assoc :value_id value-id))
        (human who))))

(defn- request-row [rid who]
  (json (req :get (str "/api/composition_requests/" rid) (human who))))

(deftest a-persons-request-is-answered-in-the-same-stroke-and-nothing-counts
  (let [v (declare-value! "colton-pull" "a pulled week" ["the shop"])
        who "composer-pull"
        asked (ask! "colton-pull")
        rid (id-of asked)]
    (testing "the request lands with the asker's name and a week's leash, neither the caller's to give"
      (is (= 201 (:status asked)))
      (is (= "offered" (:state (json asked))))
      (is (= "colton-pull" (:requested_by (fields asked))))
      (is (some? (:good_until (fields asked)))))
    (is (= 201 (:status (stage-outcome! who (vid v)))))
    (is (= 201 (:status (stage-outcome! who (vid v)))))
    (testing "a third, uncited, is admitted too — no door counts a composer's week (waymark-1uv.3)"
      (is (= 201 (:status (stage-outcome! who (vid v))))))
    (let [cited (stage-outcome! who (vid v) {:request_id rid})
          oid (id-of cited)]
      (testing "the one citing the person's request is admitted and carries the citation"
        (is (= 201 (:status cited)))
        (is (= rid (:request_id (fields cited)))))
      (testing "and the request reads answered, naming the outcome, in the same stroke"
        (let [r (request-row rid "colton-pull")]
          (is (= "answered" (:state r)))
          (is (= oid (:answered_by (:data r))))))
      (testing "the answer transition carries the composer's hand — the staging answered it, not a tap and not a system actor"
        (is (contains? (creators :composition_request rid) [:answer who])))
      (testing "a second outcome citing the same request is refused — one request, one outcome"
        (let [again (stage-outcome! who (vid v) {:request_id rid})]
          (is (= 409 (:status again)))
          (is (= "the-request-is-open" (guard-of again)))
          (is (str/includes? (detail again) "already answered"))))
      (testing "and an uncited fifth is admitted like every other — the citation bought a tier in the rank, not a pass through a door"
        (is (= 201 (:status (stage-outcome! who (vid v)))))))))

(deftest a-request-that-names-a-value-admits-only-an-outcome-serving-it
  (let [aim (declare-value! "colton-aim" "the aimed value" ["the shop"])
        other (declare-value! "colton-aim" "some other value" ["the shop"])
        asked (ask! "colton-aim" (vid aim))
        rid (id-of asked)
        wrong (stage-outcome! "composer-aim" (vid other) {:request_id rid})]
    (testing "the aim's own words ride the request, copied by the engine"
      (is (= "the aimed value" (:value_name (fields asked)))))
    (testing "an outcome serving another value is refused, and the refusal names the aim"
      (is (= 409 (:status wrong)))
      (is (= "the-request-is-open" (guard-of wrong)))
      (is (str/includes? (detail wrong) (vid aim))))
    (testing "and the request is untouched by the refusal — a refused staging answers nothing"
      (is (= "offered" (:state (request-row rid "colton-aim")))))
    (testing "the one that serves it is admitted"
      (is (= 201 (:status (stage-outcome! "composer-aim" (vid aim)
                                          {:request_id rid})))))
    (testing "and a request aimed at a value this house does not hold is refused at its own door"
      (let [bad (ask! "colton-aim" "01HZQ7Y7F2R3W4V5X6Y7Z8A9ZZ")]
        (is (= 409 (:status bad)))
        (is (= "aims-at-a-value-this-house-holds" (guard-of bad)))))))

(deftest an-agent-mints-a-request-only-under-a-scope-that-says-so
  ;; waymark-sfe, the owner's ruling of 2026-08-28: the pull is the
  ;; household's, and it is DELEGABLE. Law 6 is intact because the
  ;; scope below exists only where a person approved an
  ;; approval_request — so what the delegate files is the person's own
  ;; pull, on the person's instruction.
  (testing "a scope that does not name the create door conceals it — an agent's own initiative reaches nothing"
    (let [reading-only (leash! "composer-mint-blind" [])
          r (req :post "/api/composition_requests" {} reading-only)]
      (is (= 404 (:status r)) (str "not concealed: " (json r)))))
  (testing "a scope that NAMES composition_request.create admits the ask"
    (let [delegate (leash! "composer-mint" ["create"])
          made (req :post "/api/composition_requests" {} delegate)]
      (is (= 201 (:status made)) (str "refused: " (json made)))
      (testing "and the history says under WHICH grant it was filed"
        (is (= (get delegate "x-waymark-grant")
               (under-grant :composition_request (id-of made) :create)))))))

(deftest a-verdict-is-grantable-but-four-eyes-is-not
  ;; The whole of waymark-sfe on the verdict doors, over the wire:
  ;; concealed without the scope, admitted with it, refused on the
  ;; composer's own row whatever it holds, and the audit says which
  ;; grant answered.
  (let [v (declare-value! "colton-sfe" "making memories with the family"
                          ["the shop"])
        composer "composer-sfe"
        mine (id-of (stage-outcome! composer (vid v)))
        theirs (id-of (stage-outcome! composer (vid v)))]
    (testing "an agent whose scope does not name the verdict sees no such door"
      (let [blind (leash! "delegate-sfe-blind" [{:kind "outcome" :actions []}] :scope)]
        (is (= 404 (:status (invoke! "outcomes" mine :not_this_week nil blind))))))

    (testing "THE COMPOSER'S OWN ROW is refused even holding the scope — four eyes, and no grant opens it"
      (let [staged-it (leash! composer
                              [{:kind "outcome" :actions ["not_this_week"]}] :scope)
            r (invoke! "outcomes" mine :not_this_week nil staged-it)]
        (is (= 409 (:status r)) (str "allowed: " (json r)))
        ;; the-composer-does-not-decide stands FIRST and answers first
        (is (= "the-composer-does-not-decide" (guard-of r)))))

    (testing "another agent, under a scope that names the door, declines on the household's instruction"
      (let [delegate (leash! "delegate-sfe"
                             [{:kind "outcome" :actions ["not_this_week"]}] :scope)
            r (invoke! "outcomes" mine :not_this_week nil delegate)]
        (is (= 200 (:status r)) (str "refused: " (json r)))
        (is (= "declined" (:state (json r))))
        (testing "and the transition reads 'under grant-…'"
          (is (= (get delegate "x-waymark-grant")
                 (under-grant :outcome mine :not_this_week))))))

    (testing "a FILTERED scope admits only the rows it names"
      (let [narrow (leash! "delegate-sfe-narrow"
                           [{:kind "outcome" :actions ["not_this_week"]
                             :filter {:composed_by composer}}] :scope)
            other (leash! "delegate-sfe-other"
                          [{:kind "outcome" :actions ["not_this_week"]
                            :filter {:composed_by "somebody-else"}}] :scope)]
        (is (= 404 (:status (invoke! "outcomes" theirs :not_this_week nil other)))
            "outside the filter the row does not exist at all")
        (is (= 200 (:status (invoke! "outcomes" theirs :not_this_week nil narrow)))
            "inside it, the same tap lands")))))

(deftest nothing-but-a-staging-answers-a-request-over-the-wire
  (let [rid (id-of (ask! "colton-wire"))
        body {:outcome_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B2"}
        person (invoke! "composition_requests" rid :answer body
                        (human "colton-wire"))
        agent (invoke! "composition_requests" rid :answer body
                       (leash! "composer-wire" ["answer"]))]
    (testing "a person's own tap does not answer their request — the way out is the leash, not a hand"
      (is (= 409 (:status person)))
      (is (= "answered-by-a-composition" (guard-of person))))
    (testing "and neither does an agent's post — a request answered with no outcome behind it would be the pull burned"
      (is (= 409 (:status agent)))
      (is (= "answered-by-a-composition" (guard-of agent))))
    (testing "the row is untouched"
      (is (= "offered" (:state (request-row rid "colton-wire")))))
    (testing "and the door renders refused on the row's own envelope — nobody taps it, and the envelope says so rather than offering it"
      (let [env (json (req :get (str "/api/composition_requests/" rid)
                           (human "colton-wire")))]
        (is (not-any? #(= "answer" (str (:name %))) (:actions env)))))))

(deftest the-crown-carries-the-pull
  (let [who "colton-crown"
        v (declare-value! who "a crowned week" ["the shop"])
        rid (id-of (ask! who))
        doc (json (req :get "/api/-/feed" (human who)))]
    (testing "the feed's document carries the crown key at all"
      (is (map? (:crown doc))))
    (testing "the reader's standing request rides the crown, with no verb on it"
      (is (some #(str/ends-with? (str (:self %)) rid)
                (get-in doc [:crown :standing]))))
    (testing "and the crown says so in words"
      (is (str/includes? (str (get-in doc [:crown :says])) "asked")))
    ;; a bundle staged by somebody else, with two pieces, cards for
    ;; this reader — and the chip STILL rides (waymark-1uv.3): asking
    ;; means 'rank mine first', and the page does not decide when the
    ;; person is allowed to want
    (let [o (id-of (stage-outcome! "composer-crown" (vid v)))]
      (is (= 201 (:status (stage-piece! "composer-crown" o "Cut the stock"
                                        "task" {:title "Cut the box stock (crown)"}))))
      (is (= 201 (:status (stage-piece! "composer-crown" o "Glue it up"
                                        "task" {:title "Glue up the box (crown)"}))))
      (let [doc' (json (req :get "/api/-/feed" (human who)))]
        (testing "with a bundle on offer the crown is not empty and the ask rides anyway"
          (is (false? (get-in doc' [:crown :empty])))
          (is (= "POST" (get-in doc' [:crown :ask :method])))
          (is (str/includes? (str (get-in doc' [:crown :says])) "asked")))
        (testing "but the standing request is still said"
          (is (some #(str/ends-with? (str (:self %)) rid)
                    (get-in doc' [:crown :standing]))))
        (testing "and an answered request leaves the standing list"
          (is (= 201 (:status (stage-outcome! "composer-crown2" (vid v)
                                              {:request_id rid}))))
          (let [doc'' (json (req :get "/api/-/feed" (human who)))]
            (is (not-any? #(str/ends-with? (str (:self %)) rid)
                          (get-in doc'' [:crown :standing])))
            (testing "and with nothing standing and a bundle on offer, the chip offers the next one in the crown's own words"
              (is (= "POST" (get-in doc'' [:crown :ask :method])))
              (is (str/includes? (str (get-in doc'' [:crown :says]))
                                 "first in the crown")))))
        ;; leave the house as found
        (is (= 200 (:status (invoke! "outcomes" o :not_this_week nil (human who)))))))))

;; § 19's helper, used three sections early (waymark-8um.4)
(declare publish-diagnosis!)

;; ── 16. the crown's rank (waymark-1uv.2) ────────────────────────────
;;
;; The crown chooses WHICH bundles fill its slots by a formula the
;; house can read, and every crown card says what placed it. Three
;; bundles over one member: one nobody asked for, one that answers the
;; member's own request, one that recomposes a line of thinking the
;; house said NEVER THIS about — and then, after the member turns their
;; record on, the first one again, three mornings cold. The reader's
;; own recipe row widens the crown so every one of them cards, which is
;; also the field proving the four numbers ride the row.

(defn- feed-as [who & [query]]
  (json (req :get (str "/api/-/feed" (when query (str "?" query))) (human who))))

(defn- crown-of [doc]
  (filterv #(= "outcome" (str (:kind %))) (:cards doc)))

(defn- crown-card [doc oid]
  (some #(when (str/ends-with? (str (:card_id %)) (str "/" oid)) %) (crown-of doc)))

(defn- crown-ids [doc]
  (mapv #(last (str/split (str (:card_id %)) #"/")) (crown-of doc)))

(defn- two-pieces! [who oid tag]
  (doseq [n [1 2]]
    (is (= 201 (:status (stage-piece! who oid (str "Piece " n " " tag) "task"
                                      {:title (str "Rank piece " n " " tag)}))))))

(deftest the-crown-ranks-what-it-shows-and-every-card-says-why
  (let [switch (atom nil)
        who "colton-rank"
        v (declare-value! who "a ranked week" ["the shop"])
        ;; a line of thinking the house turned down, IN WORDS
        x (id-of (stage-outcome! "composer-rank-x" (vid v)))
        declined (invoke! "outcomes" x :not_this_week nil (human who))
        said (req :post "/api/verdict_reasons"
                  {:subject_kind "outcome" :subject_id x
                   :verdict "not_this_week" :reason "never_this"}
                  (human who))
        real-now (Instant/now)]
    (is (= 200 (:status declined)) (pr-str (json declined)))
    (is (= 201 (:status said)) (pr-str (json said)))
    (try
      ;; a week and a day on: the decline's date has passed, so the
      ;; recomposition is ON TIME (early 0 — the door would admit it
      ;; either way since waymark-1uv.10; § 17 is the early case), and
      ;; everything staged from here is live at the same clock the
      ;; feed reads
      (reset! clock (.plusSeconds real-now (* 86400 8)))
      (let [a (id-of (stage-outcome! "composer-rank-a" (vid v)))
            y-resp (stage-outcome! "composer-rank-y" (vid v)
                                   {:supersedes x
                                    ;; the duty before the date (waymark-8um.4)
                                    :diagnosis_id (id-of (publish-diagnosis! "composer-rank-y" x (vid v) nil))})
            y (id-of y-resp)
            rid (id-of (ask! who))
            b (id-of (stage-outcome! "composer-rank-b" (vid v) {:request_id rid}))]
        (is (= 201 (:status y-resp)) (pr-str (json y-resp)))
        (two-pieces! "composer-rank-a" a "a")
        (two-pieces! "composer-rank-y" y "y")
        (two-pieces! "composer-rank-b" b "b")
        ;; the reader's own recipe: the crown wide enough for all of
        ;; them, with the deployment's four numbers left standing
        (let [order (:order (:recipe (feed-as who)))
              wide (mapv #(if (= "outcomes" (str (:section %))) (assoc % :take 10) %)
                         order)
              made (req :post "/api/feed_recipes"
                        {:label "A wide crown" :scope "mine" :order wide}
                        (human who))]
          (is (= 201 (:status made)) (pr-str (json made))))
        (let [doc (feed-as who "explain=1")
              ids (crown-ids doc)
              ca (crown-card doc a) cy (crown-card doc y) cb (crown-card doc b)
              lift #(get-in % [:why :crown :lift])
              says-of (fn [c s] (some #(str/includes? (str %) s) (get-in c [:why :says])))]
          (testing "the recipe's five numbers ride the document, narrated"
            (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}
                   (get-in doc [:recipe :crown_rank])))
            (is (str/includes? (str (get-in doc [:recipe :crown_rank_says]))
                               "8 for never this")))
          (testing "all three card, and the declined one does not"
            (is (= #{a y b} (set ids)) (pr-str ids))
            (is (nil? (crown-card doc x))))
          (testing "the bundle answering the member's own request stands first,
                    above the uncited ones — asked-for is a tier, not a weight"
            (is (= b (first ids)))
            (is (true? (get-in cb [:why :crown :asked])))
            (is (false? (get-in ca [:why :crown :asked])))
            (is (says-of cb "You asked for another")))
          (testing "a fresh line of thinking stands above one the house said
                    NEVER THIS about, and both cards say the numbers"
            (is (< (.indexOf ^java.util.List ids a) (.indexOf ^java.util.List ids y)))
            (is (= 17 (lift ca)) "10 for a declared value + 7 days left")
            (is (= 9 (lift cy)) "…minus 8 for never this, and nothing for early — the date has passed")
            (is (= 0 (get-in cy [:why :crown :early])))
            (is (= 1 (get-in cy [:why :crown :turned_down])))
            (is (nil? (get-in cy [:why :crown :not_before])) "on time, so no date to quote")
            (is (nil? (get-in ca [:why :crown :early])) "a fresh line has nothing to be early for")
            (is (says-of cy "has passed"))
            (is (= "never_this" (get-in cy [:why :crown :declined])))
            (is (nil? (get-in ca [:why :crown :declined])))
            (is (= "declared" (get-in ca [:why :crown :value])))
            (is (= 7 (get-in ca [:why :crown :days_left])))
            (is (says-of cy "never this"))
            (is (says-of cy "holding it 8"))
            (is (says-of ca "Lift 17 in all"))
            (is (says-of ca "Ranked")))
          (testing "nobody is recording, so nothing about seeing rides the why —
                    the contest's own inert posture, at the crown"
            (is (nil? (get-in ca [:why :crown :seen])))
            (is (says-of ca "you are not recording")))
          ;; the member turns their record on and has been shown A three
          ;; mornings running with nothing done
          (let [day (str (:day doc))
                on (req :post "/api/feed_view_consents" {} (human who))]
            (is (= 201 (:status on)) (pr-str (json on)))
            (reset! switch (id-of on))
            (doseq [d (map #(str (.minusDays (java.time.LocalDate/parse day) (long %)))
                           [1 2 3])]
              (is (= 201 (:status (req :post "/api/feed_views"
                                       {:card_id (str (:card_id ca))
                                        :population "outcomes" :day d}
                                       (human who))))))
            (let [doc' (feed-as who "explain=1")
                  ca' (crown-card doc' a)]
              (testing "the crown reads the same view rows the contest does —
                        three days at cools_after 3 is one step, holding it 2"
                (is (= 3 (get-in ca' [:why :crown :seen])))
                (is (= 1 (get-in ca' [:why :crown :cooled])))
                (is (= 15 (lift ca')))
                (is (says-of ca' "1 step cooled, holding it 2")))
              (testing "…and the order is still the rank's: asked, fresh, never"
                (is (= [b a y] (crown-ids doc'))))
              (testing "and the floor held throughout — every bundle on offer is
                        on the page, the rank only chose the order"
                (is (= 3 (count (crown-ids doc')))))))
          ;; leave the house as found: the three bundles declined
          (doseq [oid [a y b]]
            (is (= 200 (:status (invoke! "outcomes" oid :not_this_week nil (human who))))))))
      (finally
        ;; the record goes back off (waymark-8um.4's rule: *exposure
        ;; unknown* is only claimable while nobody is recording)
        (when @switch (invoke! "feed_view_consents" @switch :stop nil (human who)))
        (reset! clock nil)))))

;; ── 17. the early recomposition: cooled, never refused ──────────────
;;
;; waymark-1uv.10. The house said not this week and meant it until a
;; date; the composer recomposes the next morning. Until this bead
;; the create door refused that (`a-recomposition-waits-its-turn`'s
;; third arm); now the row is written — it is the diagnosis, and the
;; diagnosis is the composer's work order — and the crown's rank
;; holds it back by every day it is early, on the card, in words. The
;; floor and the person's dated verdict meet here and the answer is
;; recorded in the spec: cool, never hide.

(deftest an-early-recomposition-is-admitted-and-cooled-not-refused
  (let [who "colton-early"
        v (declare-value! who "an early week" ["the shop"])
        real-now (Instant/now)]
    (try
      ;; one clock for the decline, the staging and the read, so the
      ;; days are whole numbers a test can name
      (reset! clock real-now)
      (let [x (id-of (stage-outcome! "composer-early-x" (vid v)))
            declined (invoke! "outcomes" x :not_this_week nil (human who))
            not-before (str (:not_before (:data (json declined))))
            ;; the DUTY BEFORE THE DATE (waymark-8um.4, landed after
            ;; this test was written): a declined prior was shown, so
            ;; its recomposition names a diagnosis first — and only
            ;; then is the date the rank's business rather than the
            ;; door's
            dx (id-of (publish-diagnosis! "composer-early-y" x (vid v) nil))
            y-resp (stage-outcome! "composer-early-y" (vid v)
                                   {:supersedes x :diagnosis_id dx})
            y (id-of y-resp)
            a (id-of (stage-outcome! "composer-early-a" (vid v)))]
        (is (= 200 (:status declined)) (pr-str (json declined)))
        (testing "the door admits the recomposition the same morning — the date is the rank's, not the door's"
          (is (= 201 (:status y-resp)) (pr-str (json y-resp)))
          (is (= 1 (:declined_count (fields y-resp)))))
        (two-pieces! "composer-early-y" y "early-y")
        (two-pieces! "composer-early-a" a "early-a")
        (let [order (:order (:recipe (feed-as who)))
              wide (mapv #(if (= "outcomes" (str (:section %))) (assoc % :take 10) %)
                         order)
              made (req :post "/api/feed_recipes"
                        {:label "A wide crown" :scope "mine" :order wide}
                        (human who))]
          (is (= 201 (:status made)) (pr-str (json made))))
        (let [doc (feed-as who "explain=1")
              ids (crown-ids doc)
              cy (crown-card doc y) ca (crown-card doc a)
              says-of (fn [c s] (some #(str/includes? (str %) s) (get-in c [:why :says])))]
          (testing "both stand on the page — cooled, never hidden — and the early one below the fresh one"
            (is (some #{y} ids) (pr-str ids))
            (is (some #{a} ids) (pr-str ids))
            (is (< (.indexOf ^java.util.List ids a) (.indexOf ^java.util.List ids y))))
          (testing "the card says how early it is, quotes the date, and the numbers add up"
            (is (= 7 (get-in cy [:why :crown :early])) "a week early, to the day")
            (is (= 1 (get-in cy [:why :crown :turned_down])))
            (is (= not-before (get-in cy [:why :crown :not_before])))
            (is (= 3 (get-in cy [:why :crown :lift])) "10 declared + 7 left − 14 for 7 days early")
            (is (= 17 (get-in ca [:why :crown :lift])))
            (is (says-of cy "7 days early"))
            (is (says-of cy "holding it 14"))
            (is (says-of cy (subs not-before 0 10)))
            (is (says-of cy "The floor still holds")))
          (testing "a fresh line carries none of the schedule's keys — nothing to be early for"
            (is (not (contains? (get-in ca [:why :crown]) :early)))
            (is (not (contains? (get-in ca [:why :crown]) :turned_down)))))
        ;; leave the house as found
        (doseq [oid [a y]]
          (is (= 200 (:status (invoke! "outcomes" oid :not_this_week nil (human who)))))))
      (finally (reset! clock nil)))))

;; ── 18. the agent's score and sentence: one input, quoted as the agent's ──
;;
;; waymark-1uv.6, option M of the epic. An agent that read a bundle
;; and cited it writes a `ranking_note` — a score, 0 to 1, and one
;; sentence — and the crown reads the score as one more weighted
;; number (`:judged`, 1 by default) while the card quotes the sentence
;; under the agent's name, beside the engine's numbers and never inside
;; the engine's impact line. The walls proved here are the ones no
;; scenario can arrange: the composer scoring its OWN bundle (the
;; four-eyes wall, read off the subject kind's own :own-surface), a
;; second live note by the same author, an uncited score, a person
;; writing one, and the weight turned down to nothing.

(defn- leash-scope!
  "An agent HOLDING a whole scope, minted through the real grant door
  and accepted → the headers that present it (rank_tuning_test's
  idiom, one kind over)."
  [id scope]
  (let [hs {"x-waymark-principal" id "x-waymark-actor-type" "agent"}
        made (req :post "/api/grants" {:audience id :scope scope}
                  (human "colton-leash"))
        gid (id-of made)
        took (invoke! "grants" gid :accept nil hs)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (assert (= 200 (:status took)) (pr-str (json took)))
    (assoc hs "x-waymark-grant" gid)))

(defn- etag-of [plural id who]
  (get-in (req :get (str "/api/" plural "/" id) (human who)) [:headers "ETag"]))

(deftest an-agents-score-is-one-input-and-its-sentence-is-quoted-as-the-agents
  (let [who "colton-judge"
        v (declare-value! who "a judged week" ["the shop"])
        real-now (Instant/now)]
    (try
      (reset! clock real-now)
      (let [;; THE SCORING AGENT is also a composer here, on purpose:
            ;; it stages bundle A itself, so the four-eyes wall has a
            ;; row to refuse it on, and it scores bundle B, which a
            ;; different hand staged
            cairn (leash-scope! "cairn-judge"
                                [{:kind "outcome" :actions ["create"]}
                                 {:kind "outcome_piece" :actions ["create"]}
                                 {:kind "ranking_note" :actions ["create"]}])
            a-resp (req :post "/api/outcomes"
                        {:goal "One Saturday in the shop, staged by the judge itself"
                         :value_id (vid v)
                         :routing "It runs through the shop, which you said you love."
                         :evidence [(str "/api/values/" (vid v))
                                    (a-row-read)]}
                        cairn)
            a (id-of a-resp)
            b (id-of (stage-outcome! "composer-judge-b" (vid v)))
            said "This is the outcome he has been circling for a month."
            note (fn [headers subject score evidence]
                   (req :post "/api/ranking_notes"
                        {:subject_kind "outcome" :subject_id subject
                         :score score :says said :evidence evidence}
                        headers))]
        (is (= 201 (:status a-resp)) (pr-str (json a-resp)))
        (doseq [n [1 2]]
          (is (= 201 (:status (req :post "/api/outcome_pieces"
                                   {:outcome_id a :says (str "Judge piece " n)
                                    :form "create" :target_kind "task"
                                    :prepared {:title (str "Judge piece " n)}}
                                   cairn)))))
        (two-pieces! "composer-judge-b" b "judge-b")

        (testing "the agent reads the bundle it is about to score — the visibility
                  half of the scoring scope is a read-only line over the kind"
          (is (= 200 (:status (req :get (str "/api/outcomes/" b) cairn)))))

        (testing "the composer's OWN bundle is refused by name — the note door reads
                  the outcome kind's own :own-surface, so a composer cannot rank its
                  own staging first"
          (let [own (note cairn a 1M [(str "/api/outcomes/" a)])]
            (is (= 409 (:status own)) (pr-str (json own)))
            (is (= "not-your-own-row" (str (guard-of own))))
            (is (str/includes? (detail own) "your own"))))

        (testing "a score with nothing behind it is an opinion, and a listing is
                  not an address"
          (let [bare (note cairn b 0.9M [])
                query (note cairn b 0.9M [(str "/api/outcomes?state=offered")])]
            (is (= 409 (:status bare)))
            (is (= "cites-what-it-read" (str (guard-of bare))))
            (is (= 409 (:status query)))
            (is (= "cites-what-it-read" (str (guard-of query))))))

        (testing "a person does not write one — a person's answer is a verdict"
          (let [mine (note (human who) b 0.9M [(str "/api/outcomes/" b)])]
            (is (= 409 (:status mine)))
            (is (= "a-judgment-is-an-agents" (str (guard-of mine))))))

        (let [scored (note cairn b 0.9M [(str "/api/outcomes/" b)])
              nid (id-of scored)]
          (testing "the agent scores the other composer's bundle, and the stamp is
                    the engine's"
            (is (= 201 (:status scored)) (pr-str (json scored)))
            (is (= "cairn-judge" (:judged_by (fields scored))))
            (is (= "live" (:state (json scored)))))

          (testing "one live note per row and author — the second is refused and
                    told to restate"
            (let [again (note cairn b 0.4M [(str "/api/outcomes/" b)])]
              (is (= 409 (:status again)))
              (is (= "one-live-note-per-row-and-author" (str (guard-of again))))
              (is (str/includes? (detail again) "Restate"))))

          ;; the reader's own recipe: the crown wide enough for both,
          ;; the deployment's six numbers left standing
          (let [order (:order (:recipe (feed-as who)))
                wide (mapv #(if (= "outcomes" (str (:section %))) (assoc % :take 10) %)
                           order)
                made (req :post "/api/feed_recipes"
                          {:label "A judged crown" :scope "mine" :order wide}
                          (human who))
                rid (id-of made)
                says-of (fn [c s] (some #(str/includes? (str %) s) (get-in c [:why :says])))]
            (is (= 201 (:status made)) (pr-str (json made)))

            (let [doc (feed-as who "explain=1")
                  ids (crown-ids doc)
                  ca (crown-card doc a) cb (crown-card doc b)
                  j (get-in cb [:why :crown :judged])]
              (testing "two otherwise equal bundles: the scored one sits above the
                        other, one place up at the default weight (relative order —
                        another test's bundle may share the crown)"
                (is (some #{a} ids) (pr-str ids))
                (is (some #{b} ids) (pr-str ids))
                (is (< (.indexOf ^java.util.List ids b) (.indexOf ^java.util.List ids a)))
                (is (= 18 (get-in cb [:why :crown :lift])) "10 declared + 7 left + 1 for a 0.9")
                (is (= 17 (get-in ca [:why :crown :lift]))))
              (testing "the card carries the agent's word as {score, by, says} under
                        the agent's name, and the unscored card carries no such key"
                (is (== 0.9 (double (:score j))) (pr-str j))
                (is (= "cairn-judge" (:by j)))
                (is (= said (:says j)))
                (is (not (contains? (get-in ca [:why :crown]) :judged))))
              (testing "asked why, the card quotes the sentence AS THE AGENT'S — and
                        the engine's impact line never carries it"
                (is (says-of cb "cairn-judge scores this 0.9"))
                (is (says-of cb said))
                (is (says-of cb "lifting it 1"))
                (is (not (str/includes? (str (:impact cb)) said)))
                (is (not-any? #(str/includes? (str (:impact %)) said) (:pieces cb)))
                (is (str/includes? (str (get-in doc [:recipe :crown_rank_says]))
                                   "a score of 1 lifts it 1"))))

            (testing "the agent restates — the same row, a new score — and the crown
                      reads the new word: 0.2 holds it one, so the other stands first"
              (let [re (req :post (str "/api/ranking_notes/" nid "/-/restate")
                            {:score 0.2M :says "On a second read, not this week."}
                            (assoc cairn "if-match"
                                   (get-in (req :get (str "/api/ranking_notes/" nid) cairn)
                                           [:headers "ETag"])))
                    doc (feed-as who "explain=1")
                    ids (crown-ids doc)
                    cb (crown-card doc b)]
                (is (= 200 (:status re)) (pr-str (json re)))
                (is (< (.indexOf ^java.util.List ids a) (.indexOf ^java.util.List ids b)))
                (is (= 16 (get-in cb [:why :crown :lift])))
                (is (== 0.2 (double (get-in cb [:why :crown :judged :score]))))
                (is (says-of cb "holding it 1"))
                (is (says-of cb "On a second read"))))

            (testing "weight 0 makes it inert — the word is still quoted, the
                      number it moved is nothing, and the recipe's sentence stops
                      mentioning a judgment nobody weighs"
              (let [moved (req :post (str "/api/feed_recipes/" rid "/-/revise")
                              {:label "A judged crown" :order wide
                               :crown_rank {:judged 0}}
                              (assoc (human who) "if-match" (etag-of "feed_recipes" rid who)))
                    doc (feed-as who "explain=1")
                    ca (crown-card doc a) cb (crown-card doc b)]
                (is (= 200 (:status moved)) (pr-str (json moved)))
                (is (= 17 (get-in cb [:why :crown :lift])))
                (is (= 17 (get-in ca [:why :crown :lift])))
                (is (= "cairn-judge" (get-in cb [:why :crown :judged :by])))
                (is (says-of cb "weighs an agent's judgment 0"))
                (is (not (str/includes? (str (get-in doc [:recipe :crown_rank_says]))
                                        "may score it")))))

            (testing "an agent does not dismiss; a person does — and the card then
                      ranks without the word at all"
              (let [by-agent (invoke! "ranking_notes" nid :dismiss nil cairn)
                    by-person (invoke! "ranking_notes" nid :dismiss nil (human who))
                    doc (feed-as who "explain=1")
                    cb (crown-card doc b)]
                ;; the create-only leash conceals the door (404) — the
                ;; agent wall itself is the check-tier scenario's
                (is (contains? #{404 409} (:status by-agent)) (pr-str (json by-agent)))
                (is (= 200 (:status by-person)) (pr-str (json by-person)))
                (is (= "dismissed" (:state (json by-person))))
                (is (= who (:dismissed_by (fields by-person))))
                (is (not (contains? (get-in cb [:why :crown]) :judged)))))

            (testing "after a dismissal the same agent may judge the row again — a
                      dismissal answers a note, not the agent"
              (let [fresh (note cairn b 0.7M [(str "/api/outcomes/" b)])]
                (is (= 201 (:status fresh)) (pr-str (json fresh)))))))

        ;; leave the house as found: both bundles declined
        (doseq [oid [a b]]
          (is (= 200 (:status (invoke! "outcomes" oid :not_this_week nil (human who)))))))
      (finally (reset! clock nil)))))

;; ── 19. no burial without a diagnosis (waymark-8um.4) ───────────────
;;
;; Law 4, lived in. What the scenarios cannot reach — every arm of the
;; wall is about a PRIOR ROW, and a scenario's literal input can only
;; cite a dangling one — and what the pack proves from one world (the
;; document's shape, the refusal by name, the duty before the date) is
;; widened here to the arms only a controlled world can arrange: an
;; EXPIRED prior, shown or never shown, with the clock moved by
;; rewriting its leash rather than by waiting a week (`clear-impact!`'s
;; own trick, one field over); a member recording, a member not; a
;; diagnosis the house dismissed; and a leash that names neither
;; record.
;;
;; EVERY TEST THAT TURNS A RECORD ON TURNS IT OFF, in a `finally`, for
;; the pack's own reason: the deftests share one database in a random
;; order, and *exposure unknown* is only claimable while nobody is
;; recording.

(defn- today [] (str (LocalDate/now)))

(defn- consent! [who]
  (let [r (req :post "/api/feed_view_consents" {} (human who))]
    (assert (= 201 (:status r)) (pr-str (json r)))
    (id-of r)))

(defn- stop! [who cid]
  (when cid (invoke! "feed_view_consents" cid :stop nil (human who))))

(defn- view!
  "A member's screen reports the crown card for one outcome, today."
  [who oid]
  (req :post "/api/feed_views"
       {:card_id (str "outcomes/outcome/" oid) :population "outcomes"
        :day (today)}
       (human who)))

(defn- diagnosis
  ([who] (diagnosis who nil))
  ([who oid] (json (req :get (str "/api/-/diagnosis"
                                  (when oid (str "?outcome=" oid)))
                        (if (map? who) who (human who))))))

(defn- diagnosed [doc oid]
  (some #(when (str/ends-with? (str (:self %)) (str oid)) %) (:outcomes doc)))

(defn- lapse!
  "Move the clock past an outcome's leash by rewriting `good_until` to
  the past — data only, no transition, no version bump — so `expire`
  is answered by the engine's own wall against the real clock."
  [oid]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (let [row (store/load-row (:storage *eng*) tx :outcome oid {})]
        (store/update-data! (:storage *eng*) tx :outcome oid
                            (assoc (dissoc (:data row) :good_until)
                                   "good_until" "2020-01-01T00:00:00Z")
                            nil)))))

(defn- expire! [who oid]
  (let [r (invoke! "outcomes" oid :expire nil (human who))]
    (assert (= 200 (:status r)) (pr-str (json r)))
    r))

(defn- publish-diagnosis!
  "The composer's diagnosis — an insight, law 4's own word — citing
  the prior outcome and offering the value's own still_stands: the one
  tap that separates *the value is wrong* from *the plan was wrong*."
  [who prior value-id evidence]
  (req :post "/api/insights"
       {:finding (str "Shown and turned down — the Saturday was right and"
                      " the first step was too big; recompose smaller")
        :evidence (or evidence [(str "/api/outcomes/" prior)])
        :offer_kind "value" :offer_id value-id :offer_action "still_stands"
        :offer_href (str "/api/values/" value-id)}
       (if (map? who) who (human who))))

(defn- reason! [who oid word]
  (req :post "/api/verdict_reasons"
       {:subject_kind "outcome" :subject_id oid
        :verdict "not_this_week" :reason word}
       (human who)))

(deftest the-diagnosis-reads-what-the-house-did-with-a-bundle
  (let [member "colton-dx"
        composer "composer-dx"
        v (declare-value! member "a diagnosed Saturday" ["the shop"])
        o (id-of (stage-outcome! composer (vid v)))
        other (id-of (stage-outcome! "composer-dx-other" (vid v)))
        cid (atom nil)]
    (try
      (is (= 201 (:status (stage-piece! composer o "Cut the stock" "task"
                                        {:title "Cut the stock (dx)"}))))
      (is (= 201 (:status (stage-piece! composer o "Glue it up" "task"
                                        {:title "Glue it up (dx)"}))))
      (testing "the document carries the duty, and the composer's own bundle — nobody else's"
        (let [doc (diagnosis composer)
              mine (diagnosed doc o)]
          (is (str/includes? (str (:duty doc)) "never shown is not a verdict"))
          (is (some? mine))
          (is (nil? (diagnosed doc other)))
          (is (= "offered" (:answered mine)))
          (is (= "still_offered" (:lesson mine)))
          (is (false? (:diagnosis_needed mine)))
          (testing "a human reads unscoped — nothing is withheld"
            (is (true? (get-in doc [:reads :exposure])))
            (is (true? (get-in doc [:reads :reasons])))
            (is (not (true? (get-in mine [:exposure :withheld])))))
          (testing "and with no screen having reported it, the exposure is unknown or nought — never a guess"
            (is (or (false? (get-in mine [:exposure :known]))
                    (= 0 (get-in mine [:exposure :mornings])))))))
      (reset! cid (consent! member))
      (is (= 201 (:status (view! member o))))
      (testing "one member, one morning, and the exposure says exactly that"
        (let [mine (diagnosed (diagnosis composer) o)]
          (is (true? (get-in mine [:exposure :known])))
          (is (= 1 (get-in mine [:exposure :mornings])))
          (is (= [{:member member :mornings 1}] (get-in mine [:exposure :by])))
          (is (some #{member} (get-in mine [:exposure :measured])))))
      (is (= 200 (:status (invoke! "outcomes" o :not_this_week nil (human member)))))
      (is (= 201 (:status (reason! member o "wrong_time"))))
      (testing "declined with a word: the lesson, the floor, the reason"
        (let [mine (diagnosed (diagnosis composer) o)]
          (is (= "declined" (:answered mine)))
          (is (= member (:answered_by mine)))
          (is (= "shown_and_declined" (:lesson mine)))
          (is (true? (:diagnosis_needed mine)))
          (is (= 1 (:declined_count mine)))
          (is (some? (:not_before mine)))
          (is (= [{:verdict "not_this_week" :reason "wrong_time" :said_by member}]
                 (mapv #(select-keys % [:verdict :reason :said_by]) (:reasons mine))))
          (is (str/includes? (str (:says mine)) "wrong_time"))
          (is (str/includes? (str (:says mine)) "diagnosis to write"))))
      (testing "?outcome=<id> asks about one"
        (let [doc (diagnosis composer o)]
          (is (= 1 (count (:outcomes doc))))
          (is (some? (diagnosed doc o)))))
      (testing "the pieces ride along, in the states the decline left them"
        (is (= #{"moot"} (into #{} (map :state)
                               (:pieces (diagnosed (diagnosis composer) o))))))
      (finally (stop! member @cid)))))

(deftest the-duty-fires-before-the-date
  (let [member "colton-duty"
        composer "composer-duty"
        v (declare-value! member "a declined week" ["the shop"])
        q (id-of (stage-outcome! composer (vid v)))]
    (is (= 200 (:status (invoke! "outcomes" q :not_this_week nil (human member)))))
    (testing "a recomposition with no diagnosis hears about the diagnosis — by name, pointing at the document"
      (let [r (stage-outcome! composer (vid v) {:supersedes q})]
        (is (= 409 (:status r)))
        (is (= "no-burial-without-a-diagnosis" (guard-of r)))
        (is (str/includes? (detail r) (str "/api/-/diagnosis?outcome=" q)))
        (is (str/includes? (detail r) "diagnosis_id"))))
    (testing "a diagnosis that is not there"
      (let [r (stage-outcome! composer (vid v)
                              {:supersedes q
                               :diagnosis_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9ZZ"})]
        (is (= 409 (:status r)))
        (is (= "no-burial-without-a-diagnosis" (guard-of r)))
        (is (str/includes? (detail r) "no insight"))))
    (testing "a diagnosis about something else"
      (let [elsewhere (publish-diagnosis! composer q (vid v) [(str "/api/values/" (vid v))])]
        (is (= 201 (:status elsewhere)))
        (let [r (stage-outcome! composer (vid v)
                                {:supersedes q :diagnosis_id (id-of elsewhere)})]
          (is (= 409 (:status r)))
          (is (= "no-burial-without-a-diagnosis" (guard-of r)))
          (is (str/includes? (detail r) "does not cite")))))
    (testing "a diagnosis with nothing to diagnose"
      (let [i (publish-diagnosis! composer q (vid v) nil)]
        (is (= 201 (:status i)))
        (let [r (stage-outcome! composer (vid v) {:diagnosis_id (id-of i)})]
          (is (= 409 (:status r)))
          (is (= "no-burial-without-a-diagnosis" (guard-of r)))
          (is (str/includes? (detail r) "supersedes nothing")))
        (testing "…and once the duty is done, no wall is left — the date is the rank's cooling input, not the door's (waymark-1uv.10), so the recomposition is admitted and its chain's count carries down"
          (let [r (stage-outcome! composer (vid v)
                                  {:supersedes q :diagnosis_id (id-of i)})]
            (is (= 201 (:status r)) (pr-str (json r)))
            (is (= 1 (:declined_count (fields r))))
            ;; leave the house as found
            (is (= 200 (:status (invoke! "outcomes" (id-of r) :not_this_week nil
                                         (human member)))))))
        (testing "the recomposition's link to its diagnosis is declared beside supersedes"
          (is (= #{"supersedes" "diagnosis"}
                 (into #{} (comp (map :rel) (filter #{"supersedes" "diagnosis"}))
                       (:links outcome/outcome)))))))))

(deftest never-shown-is-not-a-verdict
  (let [member "colton-ns"
        composer "composer-ns"
        v (declare-value! member "an unseen week" ["the shop"])
        cid (consent! member)]
    (try
      (let [p (id-of (stage-outcome! composer (vid v)))]
        (lapse! p)
        (expire! member p)
        (testing "somebody was recording and never had it on screen: never shown, nothing owed"
          (let [mine (diagnosed (diagnosis composer) p)]
            (is (= "expired" (:answered mine)))
            (is (true? (get-in mine [:exposure :known])))
            (is (= 0 (get-in mine [:exposure :mornings])))
            (is (some #{member} (get-in mine [:exposure :measured])))
            (is (= "never_shown" (:lesson mine)))
            (is (false? (:diagnosis_needed mine)))
            (is (str/includes? (str (:says mine)) "rank"))))
        (testing "and the wall does not fire — this taught the composer about the rank, not the house"
          (let [r (stage-outcome! composer (vid v) {:supersedes p})]
            (is (= 201 (:status r)))
            (is (= p (:supersedes (fields r)))))))
      (finally (stop! member cid)))))

(deftest shown-and-passed-over-teaches-like-a-decline
  (let [member "colton-po"
        composer "composer-po"
        v (declare-value! member "a passed-over week" ["the shop"])
        cid (consent! member)]
    (try
      (let [p (id-of (stage-outcome! composer (vid v)))]
        (is (= 201 (:status (view! member p))))
        (lapse! p)
        (expire! member p)
        (testing "on a recording screen and left to lapse: the lesson"
          (let [mine (diagnosed (diagnosis composer) p)]
            (is (= "shown_and_passed_over" (:lesson mine)))
            (is (true? (:diagnosis_needed mine)))
            (is (= 1 (get-in mine [:exposure :mornings])))))
        (testing "the wall fires, and names the lapse"
          (let [r (stage-outcome! composer (vid v) {:supersedes p})]
            (is (= 409 (:status r)))
            (is (= "no-burial-without-a-diagnosis" (guard-of r)))
            (is (str/includes? (detail r) "left to lapse"))))
        (testing "a diagnosis the house dismissed is not a diagnosis"
          (let [k (publish-diagnosis! composer p (vid v) nil)]
            (is (= 201 (:status k)))
            (is (= 200 (:status (invoke! "insights" (id-of k) :dismiss nil (human member)))))
            (let [r (stage-outcome! composer (vid v)
                                    {:supersedes p :diagnosis_id (id-of k)})]
              (is (= 409 (:status r)))
              (is (= "no-burial-without-a-diagnosis" (guard-of r)))
              (is (str/includes? (detail r) "dismissed")))))
        (testing "a standing diagnosis citing the prior admits the recomposition — no floor stands behind an expiry"
          (let [i (publish-diagnosis! composer p (vid v) nil)
                r (stage-outcome! composer (vid v)
                                  {:supersedes p :diagnosis_id (id-of i)})]
            (is (= 201 (:status i)))
            (is (= 201 (:status r)))
            (is (= (id-of i) (:diagnosis_id (fields r))))
            (testing "and the prior's diagnosis reads the chain from its side"
              (let [mine (diagnosed (diagnosis composer) p)]
                (is (= [(str "/api/insights/" (id-of i))]
                       (mapv :diagnosis (:superseded_by mine)))))))))
      (finally (stop! member cid)))))

(deftest exposure-nobody-recorded-is-unknown-not-nought
  ;; claimable only while nobody is recording — every sibling here
  ;; stops its own switch on the way out
  (let [member "colton-unk"
        composer "composer-unk"
        v (declare-value! member "an unrecorded week" ["the shop"])
        p (id-of (stage-outcome! composer (vid v)))]
    (lapse! p)
    (expire! member p)
    (testing "expired, and no member who could have seen it was recording"
      (let [mine (diagnosed (diagnosis composer) p)]
        (is (false? (get-in mine [:exposure :known])))
        (is (str/includes? (str (get-in mine [:exposure :says])) "unknown"))
        (is (= "unknown" (:lesson mine)))
        (is (false? (:diagnosis_needed mine)))))
    (testing "and the wall reads what the record holds and never guesses past it"
      (is (= 201 (:status (stage-outcome! composer (vid v) {:supersedes p})))))))

(deftest the-records-are-withheld-from-a-leash-that-does-not-name-them
  (let [member "colton-leash"
        v (declare-value! member "a leashed reading" ["the shop"])
        blind (leash! "composer-blind"
                      [{:kind "outcome" :actions ["create"]}] :scope)
        sighted (leash! "composer-sighted"
                        [{:kind "outcome" :actions ["create"]}
                         {:kind "feed_view" :actions []}
                         {:kind "verdict_reason" :actions []}] :scope)
        stage (fn [hs] (req :post "/api/outcomes"
                            {:goal "A leashed Saturday" :value_id (vid v)
                             :routing "It runs through the shop."
                             :evidence [(str "/api/values/" (vid v))
                                        (a-row-read)]}
                            hs))
        b (id-of (stage blind))
        s' (id-of (stage sighted))]
    (testing "a leash naming neither record reads its outcome with both halves withheld, by name"
      (let [doc (diagnosis blind)
            mine (diagnosed doc b)]
        (is (some? mine))
        (is (false? (get-in doc [:reads :exposure])))
        (is (false? (get-in doc [:reads :reasons])))
        (is (true? (get-in mine [:exposure :withheld])))
        (is (str/includes? (str (get-in mine [:exposure :says])) "feed_view"))
        (is (nil? (:reasons mine)))))
    (testing "the two read grants the records were designed for admit them"
      (let [doc (diagnosis sighted)
            mine (diagnosed doc s')]
        (is (true? (get-in doc [:reads :exposure])))
        (is (true? (get-in doc [:reads :reasons])))
        (is (not (true? (get-in mine [:exposure :withheld]))))
        (is (vector? (:reasons mine)))))
    (testing "and neither composer reads the other's bundle"
      (is (nil? (diagnosed (diagnosis blind) s')))
      (is (nil? (diagnosed (diagnosis sighted) b))))))

;; ── 20. the tally: shown-and-declined teaches, never-shown does not ──
;;
;; (waymark-1uv.4) Since the cap left, a composer may stage many and
;; see few shown. One composer, six bundles: one that lapsed before
;; anybody was recording; then, under a recording member, five of
;; which two reached the screen — one declined, one left to lapse —
;; and three never on it. The clock is pinned so every calendar input
;; is a whole number, and moved a week on so the engine's own wall
;; answers `expire`.

(deftest the-tally-counts-exposure-and-never-shown-names-its-lift
  (let [member "colton-tally"
        composer "composer-tally"
        v (declare-value! member "a tallied week" ["the shop"])
        t0 (Instant/now)
        cid (atom nil)]
    (try
      ;; one bundle answered by the clock before anybody was recording
      (reset! clock t0)
      (let [u (id-of (stage-outcome! composer (vid v)))]
        (lapse! u)
        (expire! member u)
        ;; a minute on: the member turns their record on, and the
        ;; composer stages five at one instant, a week's leash each
        (reset! clock (.plusSeconds t0 60))
        (reset! cid (consent! member))
        (let [[a b c d e :as staged] (vec (repeatedly 5 #(id-of (stage-outcome! composer (vid v)))))]
          (is (= 5 (count (set staged))))
          (is (= 201 (:status (view! member a))))
          (is (= 201 (:status (view! member b))))
          (is (= 200 (:status (invoke! "outcomes" a :not_this_week nil (human member)))))
          ;; a week and a day on, the four unanswered lapse
          (reset! clock (.plusSeconds t0 (* 86400 8)))
          (doseq [o [b c d e]] (expire! member o))
          (let [doc (diagnosis composer)
                t (:tally doc)]
            (testing "the tally counts each lesson, over every outcome the document folds"
              (is (= {:shown_and_declined 1 :shown_and_passed_over 1 :never_shown 3
                      :unknown 1 :accepted 0 :still_offered 0}
                     (:lessons t)))
              (is (= 6 (:outcomes t)))
              (is (= 2 (:owing t)))
              (is (= 2 (count (filter :diagnosis_needed (:outcomes doc))))))
            (testing "and says in one sentence which diagnosis is the house's and which the rank's"
              (is (str/includes? (str (:says t)) "HOUSE"))
              (is (str/includes? (str (:says t)) "RANK"))
              (is (str/includes? (str (:says t)) "never_shown")))
            (testing "the crown's numbers in force ride the tally, with the recipe they came from and its take"
              (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}
                     (get-in t [:crown :crown_rank])))
              (is (= 2 (get-in t [:crown :take])))
              (is (some? (get-in t [:crown :recipe]))))
            (testing "the never-shown pile names each lift, highest first"
              (is (= #{c d e} (into #{} (map #(last (str/split (str (:self %)) #"/")))
                                    (:never_shown t))))
              (is (= [17 17 17] (mapv :lift (:never_shown t)))))
            (testing "every never-shown outcome carries the rank's own inputs and the lift, as staged"
              (doseq [o [c d e]]
                (let [mine (diagnosed doc o)
                      r (:rank mine)]
                  (is (= "never_shown" (:lesson mine)))
                  (is (false? (:diagnosis_needed mine)))
                  (is (= 17 (:lift r)) "10 for a declared value + 7 days left as staged")
                  (is (= "declared" (:value r)))
                  (is (= 7 (:days_left r)))
                  (is (false? (:asked r)))
                  (is (= 0 (:seen r)))
                  (is (nil? (:declined r)) "no word on a line nobody turned down")
                  (is (nil? (:early r)) "a fresh line has nothing to be early for")
                  (is (= 6 (:of r)))
                  (is (<= 1 (:place r) 6))
                  (is (str/includes? (str (:says r)) "lift 17"))
                  (is (str/includes? (str (:says r)) "declared, lifting it 10"))
                  (is (str/includes? (str (:says r)) "7 days left"))
                  (is (str/includes? (str (:says r)) "rank's verdict, not the house's")))))
            (testing "the shown ones read the same lift — the rank did not choose between these, the seed did"
              (is (= "shown_and_declined" (:lesson (diagnosed doc a))))
              (is (= "shown_and_passed_over" (:lesson (diagnosed doc b))))
              (is (= 17 (get-in (diagnosed doc a) [:rank :lift])))
              (is (= 17 (get-in (diagnosed doc b) [:rank :lift]))))
            (testing "the places are the crown's own order over the six, one each"
              (is (= (set (range 1 7))
                     (into #{} (map #(get-in % [:rank :place])) (:outcomes doc)))))
            (testing "unknown stays unknown without opt-in: the lapse before anybody recorded"
              (let [mine (diagnosed doc u)]
                (is (= "unknown" (:lesson mine)))
                (is (false? (get-in mine [:exposure :known])))
                (is (not-any? #(= (str u) (last (str/split (str (:self %)) #"/")))
                              (:never_shown t)))
                (is (integer? (get-in mine [:rank :lift])) "the rank reads it all the same"))))))
      (finally
        (reset! clock nil)
        (stop! member @cid)))))

;; ── 21. the iterate loop: keep the outcome, rework the plan (waymark-9j2) ──
;;
;; THE CHURCH EXAMPLE, END TO END. The owner reads the Sunday
;; breakfast-and-park-walk bundle and says the walk clashes with 9am
;; church and it is too hot later — the goal is right, the PLAN is
;; wrong. Before this bead that critique had nowhere to land: the three
;; verbs are accept, defer, expire, and `supersedes` wants a decline
;; first. Now `iterate` keeps the outcome standing and files the note as
;; a thread turn; the composer withdraws the wrong piece with `rework`,
;; stages a cooler-and-later one, and commits the round with the
;; outcome's own `rework`; the owner taps the revised bundle. The
;; outcome never left the fridge.

(defn- remarks-on
  "The outcome's own thread, oldest-first (the remark kind's default
  sort). The collection scopes the remarks to this subject and its
  items are envelope SUMMARIES — data-less, and `says` is prose the
  grid column set omits — so each turn is read back at its own address
  for the full envelope the assertions want (`:data :said_by`,
  `:data :says`)."
  [who oid]
  (->> (get-in (json (req :get (str "/api/remarks?subject_kind=outcome"
                                    "&subject_id=" oid)
                          (human who)))
               [:data :items])
       (mapv #(json (req :get (:self %) (human who))))))

(deftest the-iterate-loop-reworks-the-plan-in-place
  (let [member   "colton-church"
        composer "composer-church"
        v (declare-value! member "unhurried Sundays with the family"
                          ["cooking with a podcast on" "the shop"])
        o (id-of (req :post "/api/outcomes"
                      {:goal "A slow Sunday: breakfast in, then time outside together"
                       :value_id (vid v)
                       :routing "It runs through cooking with a podcast on, which you said you love."
                       :routes_through "cooking with a podcast on"
                       :evidence [(str "/api/values/" (vid v))
                                  (a-row-read)]}
                      (human composer)))
        breakfast (id-of (stage-piece! composer o "Pancakes, podcast on"
                                       "task" {:title "Make Sunday pancakes"}))
        walk (id-of (stage-piece! composer o "A walk in the park after"
                                  "task" {:title "Park walk, Sunday"}))]

    (testing "before any iterate, the composer cannot rework a piece — there is no open invitation"
      (let [r (invoke! "outcome_pieces" walk :rework nil (human composer))]
        (is (= 409 (:status r)))
        (is (= "the-parent-invited-a-rework" (guard-of r)))))

    (testing "the owner iterates: the outcome STAYS offered and the note joins its thread"
      (let [note (str "Sunday breakfast is fine, but the park walk clashes"
                      " with 9am church, and it is too hot for a walk later.")
            r (invoke! "outcomes" o :iterate {:says note} (human member))]
        (is (= 200 (:status r)))
        (is (= "offered" (:state (json r))) "iterate keeps the outcome on the fridge")
        (is (some? (:iterate_requested_at (fields r))))
        (is (nil? (:reworked_at (fields r))) "no rework has happened yet")
        (let [thread (remarks-on member o)]
          (is (= 1 (count thread)) "the note is one turn on the outcome's thread")
          (is (= member (get-in (first thread) [:data :said_by])) "in the owner's own voice")
          (is (str/includes? (get-in (first thread) [:data :says]) "9am church")))))

    (testing "THIS outcome's own composer may not iterate it, grant or no grant — four eyes (waymark-sfe)"
      ;; the wall answers only an agent that reaches it. An unrelated
      ;; agent is default-DENY and 404s on an outcome it cannot see,
      ;; which proves nothing about any wall — so leash the COMPOSER
      ;; over the kind's own `iterate` door: authorization admits the
      ;; reach, the scope names the very action, and
      ;; `a-person-answers` refuses it anyway because it staged the row.
      (let [staged-it (leash! composer
                              [{:kind "outcome" :actions ["iterate"]}] :scope)
            r (invoke! "outcomes" o :iterate {:says "re-plan"} staged-it)]
        (is (= 409 (:status r)))
        (is (= "a-person-answers" (guard-of r)))
        (is (str/includes? (detail r) "four eyes")
            "the refusal says which of the two walls it is")))

    (testing "a scope that does not name the door conceals it — never a narrated refusal"
      ;; (the granted delegate that DOES reach a verdict door gets its
      ;; own deftest below — it must not add a turn to this thread)
      (let [elsewhere (leash! "delegate-elsewhere"
                              [{:kind "outcome" :actions ["not_this_week"]}] :scope)]
        (is (= 404 (:status (invoke! "outcomes" o :iterate {:says "no"} elsewhere))))))

    (testing "only the composer that staged a piece may rework it — the owner cannot"
      (let [r (invoke! "outcome_pieces" walk :rework nil (human member))]
        (is (= 409 (:status r)))
        (is (= "only-its-composer-reworks" (guard-of r)))))

    (testing "the composer withdraws the walk in place — reworked, not declined, not moot"
      (let [r (invoke! "outcome_pieces" walk :rework nil (human composer))]
        (is (= 200 (:status r)))
        (is (= "reworked" (:state (json r))))))

    (let [creek (id-of (stage-piece! composer o
                                     "A shaded creek trail at 8am, before the heat"
                                     "task" {:title "Creek trail, Sunday 8am"}))]
      (testing "a replacement piece stands — reworked pieces do not spend a bundle slot"
        (is (some? creek))
        (let [offered (get-in (json (req :get
                                         (str "/api/outcome_pieces?outcome_id=" o
                                              "&state=offered")
                                         (human member)))
                              [:data :items])]
          (is (= #{breakfast creek}
                 (into #{} (map #(last (str/split (str (:self %)) #"/"))) offered))
              "only breakfast and the creek trail are on offer; the walk is withdrawn")))

      (testing "the composer commits the round: the plan version bumps and it replies on the thread"
        (let [note (str "Moved nothing but the walk — swapped it for the shaded"
                        " creek trail at 8am, before the heat and clear of church.")
              r (invoke! "outcomes" o :rework {:says note} (human composer))]
          (is (= 200 (:status r)))
          (is (= "offered" (:state (json r))) "still on the fridge, now reworked")
          (is (some? (:reworked_at (fields r))))
          (is (= 1 (:plan_revision (fields r))))
          (let [thread (remarks-on member o)]
            (is (= 2 (count thread)) "the owner's note and the composer's reply")
            (is (= composer (get-in (last thread) [:data :said_by]))
                "the thread's last word is the composer's — the work order is answered"))))

      (testing "the card says the plan was reworked, in the engine's own words"
        (let [row {:data (fields (req :get (str "/api/outcomes/" o) (human member)))}
              says (#'feed/outcome-says row false)]
          (is (str/includes? says "Reworked from your note"))
          (is (str/includes? says "plan v1"))))

      (testing "the invitation is closed: a further rework waits for a fresh iterate"
        (let [r (invoke! "outcome_pieces" breakfast :rework nil (human composer))]
          (is (= 409 (:status r)))
          (is (= "the-parent-invited-a-rework" (guard-of r))))
        (let [r (invoke! "outcomes" o :rework {:says "again"} (human composer))]
          (is (= 409 (:status r)))
          (is (= "the-outcome-invited-this-rework" (guard-of r)))))

      (testing "the owner taps the revised bundle — it takes the two on offer, the walk lands nothing"
        (let [made (invoke! "outcomes" o :make_it_so nil (human member))]
          (is (= 200 (:status made)))
          (is (= "accepted" (:state (json made)))))
        (doseq [p [breakfast creek]]
          (let [r (req :get (str "/api/outcome_pieces/" p) (human member))]
            (is (= "taken" (:state (json r))))
            (is (str/starts-with? (str (:materialized (fields r))) "/api/tasks/"))))
        (let [r (req :get (str "/api/outcome_pieces/" walk) (human member))]
          (is (= "reworked" (:state (json r))) "the withdrawn walk stays withdrawn")
          (is (nil? (:materialized (fields r))) "and made nothing"))))))

;; ── 22. not a twin: one standing bundle per evidence row (8gc) ───────
;;
;; The anti-twin law lived in SITTING.md and in the manifest's own
;; heading ("Already standing — NEVER twin one of these") and no door
;; enforced it, so a sitting that staged the same bundle twice was
;; admitted twice. `not-a-twin` is that law at the create door, and
;; only a live engine can judge it: the wall's whole question is what
;; ANOTHER row cites, and a declaration-time scenario holds one
;; literal input over an empty store.
;;
;; Four claims, and the last is the one worth stating out loud: a
;; row's own value address is subtracted from both sides before the
;; sets meet, so two bundles serving one value are still both
;; admitted. Otherwise this wall would have quietly restored the cap
;; waymark-1uv.3 removed — one standing outcome per value — which is
;; the opposite of what the anti-twin law is for.

(defn- ev
  "An evidence address, minted per claim so two deftests sharing this
  database never twin each other's bundles by accident.
  `cites-what-it-read` judges the SHAPE of an address — a collection
  this house serves — and never whether the row is there, which is
  what makes a literal address the right fixture for a wall about
  overlap; the declaration's own `a-composed-outcome` cites one
  exactly like it."
  [tag]
  (str "/api/tasks/01HZQ7TWIN" tag))

(deftest a-twin-of-a-standing-bundle-is-refused-and-the-refusal-names-it
  (let [v (declare-value! "colton-twin" "a week worth composing once"
                          ["the shop"])
        shared (ev "SHARED0001")
        first' (stage-outcome! "composer-twin-a" (vid v)
                               {:evidence [(str "/api/values/" (vid v))
                                           shared]})
        oid (id-of first')]
    (is (= 201 (:status first')) (detail first'))
    (testing "a second bundle over the same row — and from ANOTHER composer, because a twin is a twin whoever wrote it — is refused, naming the standing address and the shared one"
      (let [r (stage-outcome! "composer-twin-b" (vid v)
                              {:evidence [shared (ev "TWINOTHER1")]})]
        (is (= 409 (:status r)))
        (is (= "not-a-twin" (guard-of r)))
        (is (str/includes? (detail r) (str "/api/outcomes/" oid)))
        (is (str/includes? (detail r) shared))
        (is (str/includes? (detail r) "offered"))))
    (testing "evidence nobody has composed over is admitted — this is a wall on duplication, never on how much a week may hold"
      (is (= 201 (:status (stage-outcome! "composer-twin-b" (vid v)
                                          {:evidence [(ev "TWINDISTIN")]})))))
    (testing "and two bundles serving the same value, each citing only that value, are BOTH admitted: a row's own value is what it serves, not something it read"
      (is (= 201 (:status (stage-outcome! "composer-twin-c" (vid v)))))
      (is (= 201 (:status (stage-outcome! "composer-twin-c" (vid v))))))))

(deftest an-accepted-bundle-still-speaks-for-the-rows-it-cites
  (let [member "colton-twin-acc"
        v (declare-value! member "a Saturday already said yes to" ["the shop"])
        shared (ev "ACCEPTED01")
        oid (id-of (stage-outcome! "composer-acc" (vid v)
                                   {:evidence [shared]}))
        _ (stage-piece! "composer-acc" oid "Cut the stock" "task"
                        {:title "Cut the stock (an accepted bundle)"})
        made (invoke! "outcomes" oid :make_it_so nil (human member))]
    (testing "the house said yes"
      (is (= 200 (:status made)))
      (is (= "accepted" (:state (json made)))))
    (testing "a bundle over the row the accepted one cites is a twin too, and the sentence says which state it stands in"
      (let [r (stage-outcome! "composer-acc-2" (vid v) {:evidence [shared]})]
        (is (= 409 (:status r)))
        (is (= "not-a-twin" (guard-of r)))
        (is (str/includes? (detail r) "accepted"))))
    (testing "…but the recomposition that NAMES it is not a twin of itself — whether a prior may be replaced is the two recomposition walls' question, and this one stays out of it"
      (let [r (stage-outcome! "composer-acc" (vid v)
                              {:evidence [shared] :supersedes oid})]
        (is (= 201 (:status r)) (detail r))))))

(deftest a-persons-pull-is-admitted-over-a-row-a-standing-bundle-cites
  (let [member "colton-twin-pull"
        composer "composer-twin-pull"
        v (declare-value! member "a pulled Saturday" ["the shop"])
        shared (ev "PULLED0001")
        oid (id-of (stage-outcome! composer (vid v) {:evidence [shared]}))
        rid (id-of (ask! member))]
    (is (some? oid))
    (testing "uncited, the second bundle over that row is a twin"
      (let [r (stage-outcome! composer (vid v) {:evidence [shared]})]
        (is (= 409 (:status r)))
        (is (= "not-a-twin" (guard-of r)))))
    (testing "the same body citing the person's own request is admitted — the pull is this wall's one exemption: he is holding the standing one and asked anyway"
      (let [r (stage-outcome! composer (vid v)
                              {:evidence [shared] :request_id rid})]
        (is (= 201 (:status r)) (detail r))
        (is (= rid (:request_id (fields r))))))))

(deftest a-recomposition-of-a-declined-prior-recites-its-evidence
  (let [member "colton-twin-recompose"
        composer "composer-twin-recompose"
        v (declare-value! member "a Saturday worth trying twice"
                          ["the shop"])
        shared (ev "RECOMPOSE1")
        prior (id-of (stage-outcome! composer (vid v) {:evidence [shared]}))]
    (is (= 200 (:status (invoke! "outcomes" prior :not_this_week nil
                                 (human member)))))
    (let [i (publish-diagnosis! composer prior (vid v) nil)
          again (stage-outcome! composer (vid v)
                                {:evidence [shared]
                                 :supersedes prior
                                 :diagnosis_id (id-of i)})]
      (is (= 201 (:status i)) (detail i))
      (testing "the recomposition re-cites exactly what the prior cited and is admitted — a declined prior does not STAND, so there is nothing here to twin, and no exemption had to be written for it"
        (is (= 201 (:status again)) (detail again))))))

;; ── 23. composed from what stands (waymark-euj) ──────────────────────
;;
;; A sitting on 2026-08-28 staged "Sacrament talk drafted and ready for
;; August 23" — five days after the 23rd — citing ONE row: a mirrored
;; task whose status said `done`. Its journal said, verbatim, "to
;; satisfy the floor requirement, I staged an outcome". There is no
;; floor. Every wall above it passed: the address was real, the value
;; was held, and no standing bundle cited that task precisely BECAUSE
;; it was finished.
;;
;; Two walls land here, and only a live engine can judge either — the
;; whole question of both is what ANOTHER row says about itself.
;;
;; `composes-from-what-stands` asks whether anything the composer read
;; is still open, reading each kind's own word for "finished": task's
;; declared `:over` (status done/dropped), the clock for an event, the
;; state vocabularies this file already keeps for a value, a person and
;; an outcome. A row it cannot classify STANDS — which is what makes
;; every other deftest in this namespace still legal, and it is the
;; deliberate half: the wall never guesses past what it can read.
;;
;; `the-door-is-open-now` asks the ENGINE whether the button a piece is
;; staged behind is there, through the same `render/action-availability`
;; the envelope's own actions/unavailable partition is built from — and
;; stands down when the refusal is about the composer's HAND, because
;; the hand at the tap is a member's.

(defn- done-task! [who title]
  (let [t (make-task! who title)]
    (is (= 200 (:status (invoke! "tasks" t :complete nil (human who))))
        "the fixture task did not complete")
    t))

(defn- past-event! [who title]
  (let [ends (.minusSeconds (Instant/now) (long (* 3600 24)))
        starts (.minusSeconds ends 3600)]
    (id-of (req :post "/api/events"
                {:title title :all_day false
                 :starts_at (str starts) :ends_at (str ends)}
                (human who)))))

(deftest a-bundle-whose-every-citation-is-finished-is-refused-by-name
  (let [member "colton-stands"
        composer "composer-stands"
        v (declare-value! member "a week that is still ahead" ["the shop"])
        done (done-task! member "Draft the talk for the 23rd")
        open' (make-task! member "Cut the box stock to length")
        done-href (str "/api/tasks/" done)]
    (testing "THE SPECIMEN: the only row it read is a task the house finished, and the refusal names the row and the word it is finished with"
      (let [r (stage-outcome! composer (vid v) {:evidence [done-href]})]
        (is (= 409 (:status r)) (detail r))
        (is (= "composes-from-what-stands" (guard-of r)))
        (is (str/includes? (detail r) done-href))
        (is (str/includes? (detail r) "is done"))))
    (testing "ONE standing row is enough — this is a wall on composing out of a closed book, never a demand that every citation be live"
      (is (= 201 (:status (stage-outcome! composer (vid v)
                                          {:evidence [done-href
                                                      (str "/api/tasks/" open')]})))
          "a finished row beside an open one is reading, not a closed book"))
    (testing "an event is judged by its own clock rather than by a machine state it does not have"
      (let [past (past-event! member "The rehearsal, last night")
            r (stage-outcome! composer (vid v)
                              {:evidence [(str "/api/events/" past)]})]
        (is (= 409 (:status r)) (detail r))
        (is (= "composes-from-what-stands" (guard-of r)))
        (is (str/includes? (detail r) "ended"))))
    (testing "and a row this house has not got STANDS: the wall refuses what it can read is finished, and never guesses past that"
      (is (= 201 (:status (stage-outcome! composer (vid v))))))))

(deftest serving-a-value-is-not-the-same-act-as-reading-the-house
  (let [v (declare-value! "colton-onlyvalue"
                          "a value with nothing read behind it"
                          ["the shop"])]
    (testing "the bundle's own value is subtracted first, exactly as not-a-twin subtracts it — so citing it alone is not a reading, and the sentence says which"
      (let [r (stage-outcome! "composer-onlyvalue" (vid v)
                              {:evidence [(str "/api/values/" (vid v))]})]
        (is (= 409 (:status r)) (detail r))
        (is (= "composes-from-what-stands" (guard-of r)))
        (is (str/includes? (detail r) "value_id already said that"))))))

(deftest a-piece-behind-a-door-the-row-has-shut-is-refused-at-staging
  ;; `make_it_so` is the door this file can shut without touching
  ;; anybody's roles: an outcome with no pieces still on offer would
  ;; be a tap that landed nothing while the row read accepted, and
  ;; `something-is-still-on-offer` says so. The pieceless bundle is
  ;; staged by SOMEBODY ELSE, so the four-eyes wall in front of that
  ;; door never fires — this deftest is about a door the ROW has shut,
  ;; and the hand is the next one's subject.
  (let [member "colton-shut"
        composer "composer-shut"
        v (declare-value! member "a bundle with nothing left to take"
                          ["the shop"])
        pieceless (id-of (stage-outcome! member (vid v)))
        o (id-of (stage-outcome! composer (vid v)))]
    (testing "the piece is refused at staging, and the sentence is the DOOR'S OWN — not one written at this wall"
      (let [r (stage-invoke-piece! composer o
                                   "Take the bundle he has been sitting on"
                                   "outcome" pieceless "make_it_so" {})]
        (is (= 409 (:status r)) (detail r))
        (is (= "the-door-is-open-now" (guard-of r)))
        (is (str/includes? (detail r) (str "/api/outcomes/" pieceless)))
        (is (str/includes? (detail r) "nothing left for Make it so to do"))))
    (testing "give that bundle a piece and the same staging is admitted — the wall reads the row NOW, not a rule about the kind"
      (is (= 201 (:status (stage-piece! member pieceless "Cut the stock"
                                        "task"
                                        {:title "Cut the stock (shut door)"}))))
      (is (= 201 (:status (stage-invoke-piece!
                           composer o
                           "Take the bundle he has been sitting on"
                           "outcome" pieceless "make_it_so" {})))))))

(deftest an-open-door-stages-and-a-create-piece-is-never-asked
  (let [member "colton-open-door"
        composer "composer-open-door"
        v (declare-value! member "an open door" ["the shop"])
        o (id-of (stage-outcome! composer (vid v)))
        t (make-task! member "Call the lumber yard about the maple")]
    (testing "a piece against a door that IS open stages"
      (is (= 201 (:status (stage-invoke-piece! composer o "Mark the call made"
                                               "task" t "complete" {})))))
    (testing "and a create piece has no target row at all, so this wall never asks"
      (let [r (stage-piece! composer o "Buy the sandpaper" "task"
                            {:title "Buy 120-grit sandpaper"})]
        (is (= 201 (:status r)))
        (is (= "create" (:form (fields r))))
        (is (nil? (:target_id (fields r))))))))

(deftest the-specimens-piece-is-not-what-this-wall-catches-and-the-record-says-so
  ;; AN HONEST RECORD OF A CLAIM THIS WALL DOES NOT MAKE. The specimen
  ;; staged a piece that PRIORITIZED the finished task, and the bead
  ;; expected `the-door-is-open-now` to refuse it. It does not, and
  ;; both halves of the reason are declarations rather than opinions:
  ;;
  ;; 1. `task.prioritize` leaves from the SYNC states, and a done task
  ;;    is `fresh`. Nothing in task's declaration shuts its rank on a
  ;;    finished row — the rank is hub-local and the lifecycle is data
  ;;    (`:over {:field :status …}`), and the two never meet. If the
  ;;    household wants that door shut, the wall belongs on task's own
  ;;    declaration, where every reader of that kind would see it
  ;;    (waymark-tgy).
  ;; 2. The one guard that DOES refuse here is `role:ranker`, and it
  ;;    declares `:reads [:principal]` — it is about the composer's
  ;;    hand, and the hand at the tap is a member's. A wall that
  ;;    refused a piece because the COMPOSER could not tap it would
  ;;    refuse the household its own Saturday.
  ;;
  ;; So the specimen is caught one level up, by `composes-from-what-
  ;; stands` on the BUNDLE — which is where it should be caught: the
  ;; bug was composing from a closed book, and the piece was only the
  ;; symptom. Recorded here rather than left as a surprise.
  (let [member "colton-specimen"
        composer "composer-specimen"
        v (declare-value! member "the specimen, re-run" ["the shop"])
        done (done-task! member "Sacrament talk, drafted (already done)")
        bundle (stage-outcome! composer (vid v)
                               {:evidence [(str "/api/tasks/" done)]})]
    (testing "the BUNDLE is refused, which is the specimen's actual fault"
      (is (= 409 (:status bundle)))
      (is (= "composes-from-what-stands" (guard-of bundle))))
    (testing "and a piece prioritizing that same finished task still stages — the door is genuinely open, and saying otherwise would be a second opinion about task's own law"
      (let [o (id-of (stage-outcome! composer (vid v)))
            r (stage-invoke-piece! composer o "Rank the finished talk"
                                   "task" done "prioritize" {:priority 3})]
        (is (= 201 (:status r)) (detail r))))))
