(ns workqueue10.person-test
  "The roster, lived in (waymark-jfv.11), over the REAL ring handler
  and the household's own registry.

  THE CAST IS INVENTED, and the invention is load-bearing rather than
  coy: this house's own roster is production data, written through the
  ordinary create door after a deploy by the family in their own words.
  A fixture carrying the real one would be this kind's own bug — a
  roster nobody typed is a roster somebody guessed. Odell is a
  grandfather, Bram is Odell's CNA, Marta is a contractor, and Nessa is
  a caregiver who left.

  What the scenarios already prove and this file does not repeat: who
  may reach which door. What only a live engine can answer is here, and
  it is most of the bead:

  - the BIRTH STATE, which is a hook rather than a wall — an agent's
    row lands `observed` and a member's lands `current`, and the stamps
    that ride each;
  - `through` as a CHECKED ref, both arms: a dangling one refused, a
    row relating through ITSELF refused, and the good one carrying the
    engine's maintained `through_name` beside it;
  - the departure the whole bead was filed on — a caregiver an agent
    found in the record who has ALREADY LEFT, marked past straight out
    of `observed` without the owner having to affirm a lie on the way;
  - and `outcome/names-a-person`, all three arms, which is the wall the
    miscomposition would have hit: nobody there, an unanswered guess,
    and somebody who is gone.

  EVERY AGENT HERE HOLDS A LEASH, and that is not decoration.
  `packs/leash!` says it in the same words one kind over: an UNLEASHED
  agent is already answered 404 by the router's default deny, which
  proves nothing about any wall.

  Assertions are order-independent (kaocha randomizes, and the deftests
  share one DB): every test names its own principals and its own rows,
  and none asserts on collection SIZE.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.person-test"
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
  ;; value_test's list plus this bead's one new table: this engine
  ;; boots every kind main/check-resources declares, so a fixture that
  ;; dropped only its own would boot into whatever shape another suite
  ;; left behind.
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
        ;; the household's WHOLE registry over the offline fakes.
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

;; ── request sugar (value_test's idiom) ──────────────────────────────

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
  Both correcting doors declare an `:edit`, and an `:edit` IMPLIES the
  fence — so a correction is the ordinary optimistic-lock dance."
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
  "One grant over the `person` doors an agent is about to knock on,
  minted by a member and accepted by the agent → the headers that
  present it. `packs/leash!`, narrowed to one kind."
  [audience actions]
  (let [hs (agent' audience)
        made (req :post "/api/grants"
                  {:audience audience
                   :scope [{:kind "person" :actions actions}]}
                  (human "colton-grants"))
        gid (when (= 201 (:status made)) (id-of made))
        took (when gid (invoke! "grants" gid :accept nil hs))]
    (is (= 201 (:status made)) (str "grant mint: " (json made)))
    (is (= 200 (:status took)) (str "grant accept: " (json took)))
    (assoc hs "x-waymark-grant" gid)))

(defn- write-person!
  "One person through the ordinary door, by whichever hand is handed
  in."
  [headers name' relation & [extra]]
  (req :post "/api/people"
       (merge {:name name' :relation relation} extra)
       headers))

;; ── 1. the birth state is a hook, not a wall ────────────────────────

(deftest an-agent-writes-down-who-it-found-and-a-member-says-who-they-are
  (let [sous (leash! "sous-birth" ["create"])
        observed (write-person! sous "Bram" "the CNA who comes on Tuesdays")
        named (write-person! (human "colton-birth") "Odell" "grandfather"
                             {:born "1943-06-02"})]
    (testing "an agent may write down somebody it found — a composer that may not will keep inventing them instead"
      (is (= 201 (:status observed)) (str "refused: " (json observed))))
    (testing "and what it wrote is born OBSERVED, carrying its own hand"
      (is (= "observed" (state-of observed)))
      (is (= "sous-birth" (:written_by (fields observed))))
      (testing "with nobody's name on it yet — who is family is the family's word"
        (is (nil? (:affirmed_by (fields observed))))
        (is (nil? (:affirmed_at (fields observed))))))
    (testing "a member's own row lands CURRENT and answered in the same breath, because somebody writing a person down has already decided who they are"
      (is (= 201 (:status named)))
      (is (= "current" (state-of named)))
      (is (= "colton-birth" (:written_by (fields named))))
      (is (= "colton-birth" (:affirmed_by (fields named))))
      (is (some? (:affirmed_at (fields named)))))
    (testing "born is a DATE and it round-trips as one — an age would have been true the morning it was typed and quietly wrong every morning after"
      (is (= "1943-06-02" (str (:born (fields named))))))
    (testing "and the summary says the standing out loud, which is why it is a state and not a stamp"
      (is (str/includes? (str (:summary (json observed))) "Observed"))
      (is (str/includes? (str (:summary (json named))) "Current")))))

;; ── 2. the answer, and both hands in the record ─────────────────────

(deftest an-agent-does-not-say-who-is-family-and-the-owner-does
  (let [sous (leash! "sous-answer" ["create" "still_with_us"])
        p (write-person! sous "Bram" "the CNA who comes on Tuesdays")
        id (id-of p)
        itself (invoke! "people" id :still_with_us nil sous)]
    (testing "THE WALL THIS KIND IS FOR: an observer answering its own guess would be telling the owner who his people are"
      (is (= 409 (:status itself)) (str "allowed: " (json itself)))
      (is (= "only-a-person-says-who-we-know" (guard-of itself)))
      (is (str/includes? (detail itself) "insight")))
    (let [tap (invoke! "people" id :still_with_us nil (human "colton-answer"))]
      (testing "the owner's tap is the whole answer — one state change, one stamp"
        (is (= 200 (:status tap)) (str "refused: " (json tap)))
        (is (= "current" (state-of tap)))
        (is (= "colton-answer" (:affirmed_by (fields tap))))
        (is (some? (:affirmed_at (fields tap))))
        (testing "and the hand that wrote them down is still on the row"
          (is (= "sous-answer" (:written_by (fields tap))))))
      (testing "the record shows BOTH HANDS, which is what a transition buys that a stamp does not"
        (let [log (hands :person id)]
          (is (contains? log [:create "sous-answer"]))
          (is (contains? log [:still_with_us "colton-answer"])))))))

;; ── 3. one correcting door per hand ─────────────────────────────────

(deftest neither-hand-reaches-the-others-correcting-door
  (let [sous (leash! "sous-word" ["create" "restate" "revise"])
        p (write-person! sous "Bram" "a CNA")
        id (id-of p)
        restated (word! "people" id :restate
                        {:name "Bram" :relation "a CNA, three afternoons a week"}
                        sous)]
    (testing "the observer corrects its own reading, and the row is still a guess afterwards"
      (is (= 200 (:status restated)) (str "refused: " (json restated)))
      (is (= "observed" (state-of restated)))
      (is (= "a CNA, three afternoons a week" (:relation (fields restated))))
      (is (nil? (:affirmed_by (fields restated)))))
    (testing "it may not put it in the house's words, because that door lands in current — answering, over a sentence about somebody's life it did not get to write"
      (let [r (word! "people" id :revise
                     {:name "Bram" :relation "one of the boys"}
                     sous)]
        (is (= 409 (:status r)))
        (is (= "only-a-person-says-who-we-know" (guard-of r)))))
    (testing "and a person does not go through the observer's door — one hand, one door"
      (let [r (word! "people" id :restate
                     {:name "Bram" :relation "corrected through the wrong door"}
                     (human "colton-word"))]
        (is (= 409 (:status r)))
        (is (= "only-the-observer-corrects" (guard-of r)))
        (is (str/includes? (detail r) "Put it in your words"))))
    (testing "the owner's own correction CLAIMS them — jfv.10's ruling read across"
      (let [r (word! "people" id :revise
                     {:name "Bram" :relation "Grandpa's CNA"}
                     (human "colton-word"))]
        (is (= 200 (:status r)) (str "refused: " (json r)))
        (is (= "current" (state-of r)))
        (is (= "colton-word" (:affirmed_by (fields r))))
        (testing "and the door the agent had is gone with the state it lived in"
          (let [again (word! "people" id :restate
                             {:name "Bram" :relation "back to a guess"}
                             sous)]
            (is (= 409 (:status again)))))))))

;; ── 4. through, checked at both ends ────────────────────────────────

(deftest a-relation-runs-through-somebody-this-house-actually-holds
  (let [colt (human "colton-through")
        rod (id-of (write-person! colt "Odell" "grandfather"))
        dangling (write-person! colt "Bram" "a CNA"
                                {:through_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9C0"})]
    (testing "a chain that names nobody is refused where it was written, not discovered by a plan built on it"
      (is (= 409 (:status dangling)) (str "allowed: " (json dangling)))
      (is (= "relates-through-somebody-here" (guard-of dangling)))
      (is (str/includes? (detail dangling) "/api/people")))
    (let [bram (write-person! colt "Bram" "Odell's CNA" {:through_id rod})
          bid (id-of bram)]
      (testing "and the good one lands, carrying the engine's maintained name beside the reference so a card reads without a join"
        (is (= 201 (:status bram)) (str "refused: " (json bram)))
        (is (= rod (str (:through_id (fields bram)))))
        (is (= "Odell" (str (:through_name (fields bram))))))
      (testing "nobody relates to a house through themselves"
        (let [r (word! "people" bid :revise
                       {:name "Bram" :relation "Odell's CNA" :through_id bid}
                       colt)]
          (is (= 409 (:status r)) (str "allowed: " (json r)))
          (is (= "relates-through-somebody-here" (guard-of r)))
          (is (str/includes? (detail r) "this row itself"))))
      (testing "and dropping it really clears — the correcting doors overwrite the authored surface wholesale"
        (let [r (word! "people" bid :revise
                       {:name "Bram" :relation "our own CNA now"} colt)]
          (is (= 200 (:status r)) (str "refused: " (json r)))
          (is (nil? (:through_id (fields r))))
          (is (nil? (:through_name (fields r)))))))))

;; ── 5. the departure the bead was filed on ──────────────────────────

(deftest somebody-an-agent-found-has-already-left
  (let [sous (leash! "sous-gone" ["create"])
        p (write-person! sous "Nessa" "the CNA on the cleaning rotation")
        id (id-of p)
        gone (invoke! "people" id :now_past nil (human "colton-gone"))]
    (testing "straight out of OBSERVED, with no lie on the way: making the owner affirm her as current before marking her past would have written one for a whole transaction"
      (is (= 200 (:status gone)) (str "refused: " (json gone)))
      (is (= "past" (state-of gone)))
      (is (= "colton-gone" (:affirmed_by (fields gone))))
      (is (contains? (hands :person id) [:now_past "colton-gone"])))
    (testing "the summary says it, so anybody reading a list learns what the rotation never heard"
      (is (str/includes? (str (:summary (json gone))) "Past")))
    (testing "and she comes back as this house's own, not as a guess — a person reaching for that door has held them again with his own hand"
      (let [back (invoke! "people" id :restore nil (human "colton-gone"))]
        (is (= 200 (:status back)))
        (is (= "current" (state-of back)))
        (is (= "colton-gone" (:affirmed_by (fields back))))))))

(deftest a-name-that-was-never-a-person-is-answered-on-its-own-door
  (let [sous (leash! "sous-wrong" ["create"])
        p (write-person! sous "the sitter" "somebody the compiler misread a chore note into")
        id (id-of p)
        answered (invoke! "people" id :dismiss nil (human "colton-wrong"))]
    (testing "\"not somebody we know\" is its own door, so the log tells a bad guess apart from a staffing change — which is the difference a composer has to be able to read"
      (is (= 200 (:status answered)) (str "refused: " (json answered)))
      (is (= "past" (state-of answered)))
      (let [log (hands :person id)]
        (is (contains? log [:dismiss "colton-wrong"]))
        (is (not (contains? log [:now_past "colton-wrong"])))))))

;; ── 6. the wall the miscomposition would have hit ───────────────────

(defn- a-held-value!
  "One declared value for an outcome to serve, by the owner's own hand."
  [who name']
  (id-of (req :post "/api/values"
              {:name name'
               :scope "household"
               :says "Written by hand so an outcome has something to serve."
               :loved ["the shop"]}
              (human who))))

(defn- compose!
  "One outcome through the ordinary create door."
  [who value-id goal extra]
  (req :post "/api/outcomes"
       (merge {:goal goal
               :value_id value-id
               :routing "It runs through the shop, which this house wrote down as something it loves."
               :routes_through "the shop"
               ;; the value is what the bundle SERVES;
               ;; `composes-from-what-stands` (waymark-euj) subtracts
               ;; it and asks whether anything the composer READ is
               ;; still open. A fresh address in a collection this
               ;; house serves names a row the wall cannot classify,
               ;; and an unclassifiable row stands.
               :evidence [(str "/api/values/" value-id)
                          (str "/api/tasks/01HZQ7PERREAD"
                               (subs (str (random-uuid)) 0 8))]}
              extra)
       (human who)))

(deftest a-plan-may-not-name-a-companion-the-roster-cannot-support
  (let [sous (leash! "sous-companion" ["create"])
        vid (a-held-value! "colton-companion" "making things with the boys")
        nobody (compose! "composer-c1" vid
                         "A Saturday in the shop with somebody nobody wrote down"
                         {:companion_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9C1"})]
    (testing "THE MISCOMPOSITION'S OWN WALL: a companion is a checked reference to this house's roster rather than a name in a sentence nothing reads"
      (is (= 409 (:status nobody)) (str "allowed: " (json nobody)))
      (is (= "names-a-person" (guard-of nobody)))
      (is (str/includes? (detail nobody) "/api/people")))
    (let [guess (id-of (write-person! sous "Bram" "one of the boys"))
          on-a-guess (compose! "composer-c2" vid
                               "A Saturday in the shop on the strength of a guess"
                               {:companion_id guess})]
      (testing "an OBSERVED person is refused, and this is where jfv.10's widening honestly stops: an agent may write a person down, and if it could then compose against its own unanswered reading the wall would be paper"
        (is (= 409 (:status on-a-guess)) (str "allowed: " (json on-a-guess)))
        (is (= "names-a-person" (guard-of on-a-guess)))
        (is (str/includes? (detail on-a-guess) "observed"))
        (is (str/includes? (detail on-a-guess) "still with us"))))
    (let [left (id-of (write-person! (human "colton-companion") "Nessa"
                                     "the CNA on the cleaning rotation"))]
      (is (= 200 (:status (invoke! "people" left :now_past nil
                                   (human "colton-companion")))))
      (let [r (compose! "composer-c3" vid
                        "An afternoon built around somebody who has left"
                        {:companion_id left})]
        (testing "and a PAST person is refused with the relation and the finding in the sentence — a rotation still naming somebody who left is a staffing change, not a cadence problem"
          (is (= 409 (:status r)) (str "allowed: " (json r)))
          (is (= "names-a-person" (guard-of r)))
          (is (str/includes? (detail r) "past"))
          (is (str/includes? (detail r) "the CNA on the cleaning rotation"))
          (is (str/includes? (detail r) "finding")))))))

(deftest a-plan-with-a-companion-this-house-holds-lands-and-says-who
  (let [vid (a-held-value! "colton-ok" "making things with the boys, together")
        who (id-of (write-person! (human "colton-ok") "Marta" "contractor"))
        o (compose! "composer-ok" vid
                    "An hour walking the basement with the contractor"
                    {:companion_id who})]
    (testing "the ordinary case: somebody this house currently holds, and the outcome lands"
      (is (= 201 (:status o)) (str "refused: " (json o)))
      (is (= who (str (:companion_id (fields o))))))
    (testing "and the engine keeps their name beside the reference, the same label doctrine value_name already wears — so a card reads who it is with without a second lookup"
      (is (= "Marta" (str (:companion_name (fields o))))))
    (testing "naming nobody is allowed and is the common case — a door that demanded a companion would teach the composer to invent one, which is the bug"
      (let [alone (compose! "composer-alone" vid
                            "An hour on the paperwork, which is nobody's afternoon but his"
                            {})]
        (is (= 201 (:status alone)) (str "refused: " (json alone)))
        (is (nil? (:companion_id (fields alone))))))))
