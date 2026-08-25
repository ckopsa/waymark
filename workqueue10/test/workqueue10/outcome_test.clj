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
  - the weekly cap, Monday to Monday, counting rows;
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
    did not land.

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
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]
            [workqueue10.resources.outcome :as outcome])
  (:import (java.time Instant)))

(def ^:private tables
  ;; THE WHOLE FOLDED REGISTRY'S TABLES — conformance_test's rule, and
  ;; the reason it exists: this engine boots every kind
  ;; main/check-resources declares, so a fixture that dropped only its
  ;; own three would boot into whatever shape another suite left
  ;; behind, and a promoted column added to a folded kind refuses at
  ;; boot with a storage-drift plan.
  ["outcome_pieces" "outcomes" "values" "people"
   "tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "activities" "evening_plans" "evening_sessions"
   "letters" "weathers" "selves" "journals" "ticklers" "insights"
   "permission_slips" "saved_views" "dashboards" "dashboard_slots"
   "connections" "capabilities"
   "members" "roles" "grants" "approval_requests"
   "feed_recipes" "recipe_proposals" "feed_views" "feed_view_consents"
   "attachments" "subscriptions" "jobs"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *eng* nil)
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

;; ── the household's own rows ────────────────────────────────────────

(defn- declare-value! [who name' loved]
  (json (req :post "/api/values"
             {:name name' :scope "household"
              :says "The boys will remember what we made together, not what I shipped."
              :loved loved}
             (human who))))

(defn- vid [v] (last (str/split (str (:self v)) #"/")))

(defn- stage-outcome!
  ([who value-id] (stage-outcome! who value-id {}))
  ([who value-id extra]
   (req :post "/api/outcomes"
        (merge {:goal "One Saturday afternoon in the shop with Jack, and a finished box"
                :value_id value-id
                :routing "It runs through the shop, which you said you love — the expensive part is already paid."
                :evidence [(str "/api/values/" value-id)]}
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

;; ── 3. the week, and where its boundary lives ───────────────────────

(deftest two-outcomes-a-week-per-composer-monday-to-monday
  (let [v (declare-value! "colton-cap" "a capped week" ["the shop"])
        who "composer-cap"]
    (is (= 201 (:status (stage-outcome! who (vid v)))))
    (is (= 201 (:status (stage-outcome! who (vid v)))))
    (let [third (stage-outcome! who (vid v))]
      (testing "the third is refused at the DOOR, so a composer has to rank"
        (is (= 409 (:status third)))
        (is (= "outcomes-are-few" (guard-of third))))
      (testing "and the refusal says when the allowance opens — the Monday, not a rolling week"
        (let [monday (store/utc-week-start (Instant/now))
              next-monday (.plusSeconds monday (* 86400 7))]
          (is (str/includes? (detail third) (str next-monday))))))
    (testing "the cap is per AUTHOR — a quiet composer is not silenced by a noisy one"
      (is (= 201 (:status (stage-outcome! "composer-cap-other" (vid v))))))))

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
    (testing "a recomposition staged before that floor is refused, by name"
      (let [r (stage-outcome! "composer-week" (vid v) {:supersedes o})]
        (is (= 409 (:status r)))
        (is (= "a-recomposition-waits-its-turn" (guard-of r)))))
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
