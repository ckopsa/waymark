(ns workqueue10.value-test
  "Values learned in the open (waymark-jfv.10), lived in over the REAL
  ring handler and the household's own registry.

  The owner overruled this kind's human-only wall in his own words:

    Discovering what you value and what you love to do is a process
    and there's nothing wrong with you learning what my values are
    and writing them to waymark — just so long as I can adjust them
    too.

  What the scenarios already prove and this file does not repeat: who
  may reach which door, judged with no database at all in the same
  breath as `make check-queue`. What only a live engine can answer is
  here, and it is most of the bead:

  - the BIRTH STATE, which is a hook rather than a wall — an agent's
    row lands `observed` and a member's lands `declared`, and the
    stamps that ride each;
  - the AFFIRMATION as a transition, with BOTH HANDS in the record:
    the agent on the create, the member on the tap;
  - the two wording doors, and that neither hand can reach the
    other's;
  - the OBSERVED CLAUSE on an outcome's card, which is the whole
    requirement that decided the affirmation machine — a bundle asking
    for a Saturday on the strength of a guess says so where the person
    answering it is looking;
  - and a reading the house answered: `dismiss` retires it, and
    `names-a-value` stops composing against it.

  EVERY AGENT HERE HOLDS A LEASH, and that is not decoration.
  `packs/leash!` says it in the same words one kind over: an UNLEASHED
  agent is already answered 404 by the router's default deny, which
  proves nothing about any wall. So each agent below mints a grant
  over the `value` doors it is about to knock on and presents it — the
  refusals are then the kind's own law rather than the absence of a
  grant, and the allowances are the MCP door the owner's ruling was
  actually about.

  Assertions are order-independent (kaocha randomizes, and the
  deftests share one DB): every test names its own principals and its
  own rows, and none asserts on collection SIZE.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.value-test"
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
  ;; the whole folded registry's tables — conformance_test's rule, and
  ;; outcome_test's list verbatim: this engine boots every kind
  ;; main/check-resources declares, so a fixture that dropped only its
  ;; own would boot into whatever shape another suite left behind.
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
        ;; the household's WHOLE registry over the offline fakes, and
        ;; this house's own feed recipe — the outcomes line has to be
        ;; in the order or the card sentence has nowhere to appear.
        ;; :probe-reads mirrors production's boot so a citation wall
        ;; judging against another kind's ROW answers honestly.
        (let [eng (engine/engine {:storage st
                                  :resources (main/check-resources)
                                  :feed main/feed-recipe
                                  :probe-reads true
                                  :suppress-mirror-refresh true})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (outcome_test's idiom) ────────────────────────────

(defn- human [id] {"x-waymark-principal" id "x-waymark-actor-type" "human"})
(defn- agent' [id] {"x-waymark-principal" id "x-waymark-actor-type" "agent"})

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
(defn- state-of [resp] (str (:state (json resp))))
(defn- guard-of [resp] (:guard (json resp)))
(defn- detail [resp] (str (:detail (json resp))))

(defn- invoke! [plural id action body headers]
  (req :post (str "/api/" plural "/" id "/-/" (name action))
       (or body {}) headers))

(defn- etag
  "The live etag, read the way a client would (dwelling_test's idiom).
  Both wording doors declare an `:edit`, and an `:edit` IMPLIES the
  fence — so a reword is the ordinary optimistic-lock dance."
  [plural id headers]
  (get-in (json (req :get (str "/api/" plural "/" id) headers)) [:meta :etag]))

(defn- word! [plural id action body headers]
  (invoke! plural id action body
           (assoc headers "if-match" (etag plural id headers))))

(defn- hands
  "Who the log says did what to a row — [action actor] pairs, read off
  the transitions the engine wrote rather than off anything the row
  says about itself."
  [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (into #{} (map (fn [rec] [(:action rec) (get-in rec [:actor :id])]))
            (store/transitions (:storage *eng*) tx
                               {:kind kind :resource-id id} {:limit 20})))))

(defn- leash!
  "One grant over the `value` doors an agent is about to knock on,
  minted by a member and accepted by the agent → the headers that
  present it. `packs/leash!`, narrowed to one kind."
  [audience actions]
  (let [hs (agent' audience)
        made (req :post "/api/grants"
                  {:audience audience
                   :scope [{:kind "value" :actions actions}]}
                  (human "colton-grants"))
        gid (when (= 201 (:status made)) (id-of made))
        took (when gid (invoke! "grants" gid :accept nil hs))]
    (is (= 201 (:status made)) (str "grant mint: " (json made)))
    (is (= 200 (:status took)) (str "grant accept: " (json took)))
    (assoc hs "x-waymark-grant" gid)))

(defn- write-value!
  "One value through the ordinary door, by whichever hand is handed in."
  [headers name' & [extra]]
  (req :post "/api/values"
       (merge {:name name'
               :scope "household"
               :says "Six weeks of Saturdays have a shop hour in them, and none of them were planned."
               :loved ["the shop"]}
              extra)
       headers))

;; ── 1. the birth state is a hook, not a wall ────────────────────────

(deftest an-agent-writes-what-it-observes-and-a-member-declares
  (let [sous (leash! "sous-birth" ["create"])
        observed (write-value! sous "time in the shop with the boys")
        declared (write-value! (human "colton-birth")
                               "making memories with the family")]
    (testing "an agent may write a value now — the wall came off this door"
      (is (= 201 (:status observed)) (str "refused: " (json observed))))
    (testing "and what it wrote is born OBSERVED, carrying its own hand"
      (is (= "observed" (state-of observed)))
      (is (= "sous-birth" (:written_by (fields observed))))
      (testing "with nobody's name on it yet — the affirmation is a person's word"
        (is (nil? (:affirmed_by (fields observed))))
        (is (nil? (:affirmed_at (fields observed))))))
    (testing "a member's own declaration lands DECLARED and affirmed in the same breath, because a person declaring one has already decided"
      (is (= 201 (:status declared)))
      (is (= "declared" (state-of declared)))
      (is (= "colton-birth" (:written_by (fields declared))))
      (is (= "colton-birth" (:affirmed_by (fields declared))))
      (is (some? (:affirmed_at (fields declared)))))
    (testing "and the summary says the standing out loud, which is why it is a state and not a stamp"
      (is (str/includes? (str (:summary (json observed))) "Observed"))
      (is (str/includes? (str (:summary (json declared))) "Declared")))))

;; ── 2. the affirmation, and both hands in the record ────────────────

(deftest an-agent-does-not-affirm-its-own-reading-and-the-owner-does
  (let [sous (leash! "sous-affirm" ["create" "still_stands"])
        v (write-value! sous "Saturday mornings with Jack")
        id (id-of v)
        itself (invoke! "values" id :still_stands nil sous)]
    (testing "THE ONE REMAINING WALL: an observer marking its own guess confirmed would speak in the owner's voice"
      (is (= 409 (:status itself)) (str "allowed: " (json itself)))
      (is (= "written-by-a-person" (guard-of itself)))
      (is (str/includes? (detail itself) "insight")))
    (let [tap (invoke! "values" id :still_stands nil (human "colton-affirm"))]
      (testing "the owner's tap is the whole affirmation — one state change, one stamp"
        (is (= 200 (:status tap)) (str "refused: " (json tap)))
        (is (= "declared" (state-of tap)))
        (is (= "colton-affirm" (:affirmed_by (fields tap))))
        (is (some? (:affirmed_at (fields tap))))
        (testing "and the hand that wrote it down is still on the row"
          (is (= "sous-affirm" (:written_by (fields tap))))))
      (testing "the record shows BOTH HANDS, which is what a transition buys that a stamp does not"
        (let [log (hands :value id)]
          (is (contains? log [:create "sous-affirm"]))
          (is (contains? log [:still_stands "colton-affirm"])))))))

;; ── 3. one wording door per hand ────────────────────────────────────

(deftest neither-hand-reaches-the-others-wording-door
  (let [sous (leash! "sous-word" ["create" "restate" "revise"])
        v (write-value! sous "the workbench, cleared")
        id (id-of v)
        restated (word! "values" id :restate
                        {:name "the workbench, cleared on Fridays"
                         :says "Narrowed: it is Fridays. Six of the last eight."
                         :loved ["the shop"]}
                        sous)]
    (testing "the observer corrects its own reading, and the row is still a guess afterwards"
      (is (= 200 (:status restated)) (str "refused: " (json restated)))
      (is (= "observed" (state-of restated)))
      (is (= "the workbench, cleared on Fridays" (:name (fields restated))))
      (is (nil? (:affirmed_by (fields restated)))))
    (testing "it may not REWORD, because rewording lands in declared — that is affirming, over words it did not choose"
      (let [r (word! "values" id :revise
                     {:name "the workbench, cleared, and it matters more than the queue"
                      :says "An agent's own promotion of its own guess."}
                     sous)]
        (is (= 409 (:status r)))
        (is (= "written-by-a-person" (guard-of r)))))
    (testing "and a person does not go through the observer's door — one hand, one door"
      (let [r (word! "values" id :restate
                     {:name "the workbench" :says "Reworded through the wrong door."}
                     (human "colton-word"))]
        (is (= 409 (:status r)))
        (is (= "only-the-observer-restates" (guard-of r)))
        (is (str/includes? (detail r) "Reword"))))
    (testing "the owner's own rewording CLAIMS it — the ruling read literally"
      (let [r (word! "values" id :revise
                     {:name "the workbench, cleared before Saturday"
                      :says "In his own words, which is what makes it the house's."
                      :loved ["the shop"]}
                     (human "colton-word"))]
        (is (= 200 (:status r)) (str "refused: " (json r)))
        (is (= "declared" (state-of r)))
        (is (= "colton-word" (:affirmed_by (fields r))))
        (testing "and the door the agent had is gone with the state it lived in"
          (let [again (word! "values" id :restate
                             {:name "back to a guess" :says "After the owner claimed it."}
                             sous)]
            (is (= 409 (:status again)))))))))

;; ── 4. the hole the ruling opened, closed at the create door ────────

(deftest a-private-value-is-not-an-observers-to-write
  (let [sous (leash! "sous-mine" ["create"])
        r (write-value! sous "building" {:scope "mine"})]
    (testing "the owner stamp is the WRITER's id, so an agent's \"mine\" value would be the agent's — and the person it is about could never adjust it"
      (is (= 409 (:status r)) (str "allowed: " (json r)))
      (is (= "a-private-value-is-a-persons-own" (guard-of r)))
      (is (str/includes? (detail r) "household")))
    (testing "the same words as the household's are fine, which is the fix the refusal names"
      (let [ok (write-value! sous "building")]
        (is (= 201 (:status ok)))
        (is (= "observed" (state-of ok)))))
    (testing "and a member's own private value is untouched by any of this"
      (let [mine (write-value! (human "colton-mine") "building, mine"
                               {:scope "mine"})]
        (is (= 201 (:status mine)))
        (is (= "declared" (state-of mine)))
        (is (= "colton-mine" (:owner (fields mine))))))))

;; ── 5. the observed clause, where the person answering is looking ───

(defn- stage-bundle!
  "One offered outcome with two pieces — the floor the population
  holds — against the value handed in."
  [who value-id goal]
  (let [o (id-of (req :post "/api/outcomes"
                      {:goal goal
                       :value_id value-id
                       :routing "It runs through the shop, which this house wrote down as something it loves."
                       :routes_through "the shop"
                       ;; the bundle's own value is what it SERVES;
                       ;; `composes-from-what-stands` (waymark-euj)
                       ;; subtracts it and asks whether anything the
                       ;; composer READ is still open. A fresh address
                       ;; in a collection this house serves is a row
                       ;; the wall cannot classify, and an
                       ;; unclassifiable row stands — the wall never
                       ;; guesses past what it can read.
                       :evidence [(str "/api/values/" value-id)
                                  (str "/api/tasks/01HZQ7VALREAD"
                                       (subs (str (random-uuid)) 0 8))]}
                      (human who)))]
    (doseq [n [1 2]]
      (req :post "/api/outcome_pieces"
           {:outcome_id o
            :says (str "Piece " n " — twenty minutes, already prepared")
            :form "create"
            :target_kind "task"
            :prepared {:title (str goal " — piece " n)}}
           (human who)))
    o))

(defn- card-for
  "The outcome card for one bundle, off the reader's own feed."
  [oid headers]
  (let [doc (json (req :get "/api/-/feed" headers))]
    (some #(when (str/ends-with? (str (:card_id %)) (str "/" oid)) %)
          (:cards doc))))

(deftest an-outcome-on-an-observed-value-says-so-on-its-card
  (let [sous (leash! "sous-card" ["create"])
        v (write-value! sous "unhurried Saturdays")
        vid (id-of v)
        goal "One Saturday afternoon in the shop, on a value nobody has affirmed"
        oid (stage-bundle! "composer-card" vid goal)
        card (card-for oid (human "reader-card"))]
    (testing "a bundle may be composed against an observed value — refusing would have made the ruling a permission with nothing behind it"
      (is (some? card) "the bundle did not card at all")
      (is (= "outcomes" (str (:section card)))))
    (testing "and the card SAYS the value is a reading rather than a law"
      (is (str/includes? (str (:sentence card))
                         "a value observed in your record, not yet affirmed"))
      (testing "with the ask attached rather than left as a shrug"
        (is (str/includes? (str (:sentence card))
                           "say whether it is yours before a week goes to it"))))
    (testing "the owner affirms, and the same bundle stops saying it — nothing about the outcome changed, only what is true about the value under it"
      (is (= 200 (:status (invoke! "values" vid :still_stands nil
                                   (human "colton-card")))))
      (let [after (card-for oid (human "reader-card"))]
        (is (some? after))
        (is (not (str/includes? (str (:sentence after)) "not yet affirmed")))
        (is (str/includes? (str (:sentence after)) "For unhurried Saturdays"))))))

;; ── 6. a reading the house answered ─────────────────────────────────

(deftest a-reading-the-house-answered-stops-being-composed-against
  (let [sous (leash! "sous-wrong" ["create"])
        v (write-value! sous "shipping more, on Sundays")
        id (id-of v)
        answered (invoke! "values" id :dismiss nil (human "colton-wrong"))]
    (testing "\"not one of ours\" is its own door, so the log tells a wrong reading apart from a value the house stopped holding"
      (is (= 200 (:status answered)) (str "refused: " (json answered)))
      (is (= "retired" (state-of answered)))
      (is (contains? (hands :value id) [:dismiss "colton-wrong"])))
    (testing "and no outcome may be staged against it — the same law names-a-value already held for a retired one"
      (let [r (req :post "/api/outcomes"
                   {:goal "A Sunday spent on a reading that was wrong"
                    :value_id id
                    :routing "It runs through nothing this house said it loves."
                    :evidence [(str "/api/values/" id)]}
                   (human "composer-wrong"))]
        (is (= 409 (:status r)) (str "allowed: " (json r)))
        (is (= "names-a-value" (guard-of r)))
        (is (str/includes? (detail r) "retired"))))
    (testing "restoring it brings it back as the house's own, not as a guess — a person reaching for that door has held it with his own hand"
      (let [back (invoke! "values" id :restore nil (human "colton-wrong"))]
        (is (= 200 (:status back)))
        (is (= "declared" (state-of back)))
        (is (= "colton-wrong" (:affirmed_by (fields back))))))))
