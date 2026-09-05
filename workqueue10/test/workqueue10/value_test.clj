(ns workqueue10.value-test
  "The value kind over the REAL ring handler and the household's own
  registry — as waymark-jfv.10 opened it, waymark-sfe permissioned it,
  and waymark-bug narrowed it again.

  WHAT waymark-bug DID, because it is what most of this file now
  proves: `observed` LEFT THIS KIND. It was a one-bit belief with no
  atoms and no arithmetic under it, and the hypotheses epic built the
  row that holds a belief properly, so the bit went there
  (docs/spec-hypotheses.md § 'What merges'). With no unaffirmed
  landing left, EVERY create is an affirmation — so the wall jfv.10
  took off this door came back, grantable, and the composer's own way
  to say *I think this house holds this value* is an intent hypothesis
  it may `restate` all it likes.

  What the scenarios already prove and this file does not repeat: who
  may reach which door, judged with no database at all in the same
  breath as `make check-queue`. What only a live engine can answer is
  here:

  - the BIRTH, which is now one landing for every hand — `declared`,
    affirmed in the same breath, with the writer stamped beside it;
  - THE DELEGATION (waymark-sfe): an agent holding a scope that names
    `value.create` writes the owner's law on his instruction, and the
    audit says which grant it acted under;
  - FOUR EYES, which no grant opens: the hand that wrote a row never
    answers it;
  - the ONE wording door, and that the observer's is gone with the
    state it lived in;
  - and `retire` / `restore` — the value the house stopped holding,
    and the way back.

  GONE WITH `observed`, AND RECORDED RATHER THAN QUIETLY DROPPED:
  `an-outcome-on-an-observed-value-says-so-on-its-card` proved jfv.10's
  own deciding requirement — a bundle asking for a Saturday on the
  strength of a guess says so where the person answering it is
  looking. There are no observed values to card, so the test has
  nothing to stage. `feed/value-standing`'s `:observed` arm is left
  standing and unreached on purpose: slice 3 of the hypotheses epic
  (waymark-4t9) repoints it at the intent hypothesis's POSTERIOR,
  which is the same sentence with a number in it, and deleting the arm
  now would leave that slice nothing to repoint.

  EVERY AGENT HERE HOLDS A LEASH, and that is not decoration.
  `packs/leash!` says it in the same words one kind over: an UNLEASHED
  agent is already answered 404 by the router's default deny, which
  proves nothing about any wall. So each agent below mints a grant
  over the `value` doors it is about to knock on and presents it — the
  refusals are then the kind's own law rather than the absence of a
  grant, and the allowances are the MCP door the owner's rulings were
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
   "hypotheses"
   "tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "activities" "evening_plans" "evening_sessions"
   "contexts" "day_plans" "blocks" "spans"
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
  present it. `packs/leash!`, narrowed to one kind — and, in the
  second arity, the WHOLE scope, which waymark-sfe needs: a filtered
  entry is the leash that admits some rows and not others."
  ([audience actions]
   (leash! audience [{:kind "value" :actions actions}] :scope))
  ([audience scope _]
   (let [hs (agent' audience)
         made (req :post "/api/grants"
                   {:audience audience :scope scope}
                   (human "colton-grants"))
         gid (when (= 201 (:status made)) (id-of made))
         took (when gid (invoke! "grants" gid :accept nil hs))]
     (is (= 201 (:status made)) (str "grant mint: " (json made)))
     (is (= 200 (:status took)) (str "grant accept: " (json took)))
     (assoc hs "x-waymark-grant" gid))))

(defn- under-grant
  "Which grant the log says a transition was made UNDER (waymark-sfe) —
  the actor's own `grant` key, absent on every unscoped write."
  [kind id action]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (some (fn [rec]
              (when (= action (:action rec)) (get-in rec [:actor :grant])))
            (store/transitions (:storage *eng*) tx
                               {:kind kind :resource-id id}
                               {:limit 20 :newest-first true})))))

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

;; ── 1. one birth, and it is an affirmation ──────────────────────────
;; jfv.10's birth BRANCHED — a person's create landed `declared`, an
;; agent's landed `observed` — and waymark-bug took the second landing
;; away with the state. So the hook no longer branches, and the wall
;; that used to be unnecessary here is necessary again: with nowhere
;; unaffirmed to land, writing a value IS declaring one. Grantable,
;; per waymark-sfe, which is what the agent below is holding.

(deftest every-value-is-born-the-houses-law
  (let [sous (leash! "sous-birth" ["create"])
        delegated (write-value! sous "time in the shop with the boys")
        declared (write-value! (human "colton-birth")
                               "making memories with the family")]
    (testing "a member's own declaration lands DECLARED and affirmed in the same breath, because a person declaring one has already decided"
      (is (= 201 (:status declared)) (str "refused: " (json declared)))
      (is (= "declared" (state-of declared)))
      (is (= "colton-birth" (:written_by (fields declared))))
      (is (= "colton-birth" (:affirmed_by (fields declared))))
      (is (some? (:affirmed_at (fields declared)))))
    (testing "and so does a delegate's, under a scope that names value.create — the owner's hand, lent (waymark-sfe)"
      (is (= 201 (:status delegated)) (str "refused: " (json delegated)))
      (is (= "declared" (state-of delegated))
          "there is no observed landing any more — that state went to the hypothesis kind")
      (is (= "sous-birth" (:written_by (fields delegated))))
      (is (= "sous-birth" (:affirmed_by (fields delegated)))
          "a create is an affirmation now, so the writer is the affirmer — which is exactly why the door is walled")
      (testing "and the history reads which grant it acted under"
        (is (= (get sous "x-waymark-grant")
               (under-grant :value (id-of delegated) :create)))))
    (testing "the summary still says the standing out loud, which is why it is a state and not a stamp"
      (is (str/includes? (str (:summary (json declared))) "Declared")))))

;; ── 2. the affirmation, and both hands in the record ────────────────

(deftest an-agent-does-not-answer-its-own-row-and-the-owner-does
  (let [sous (leash! "sous-affirm" ["create" "still_stands"])
        v (write-value! sous "Saturday mornings with Jack")
        id (id-of v)
        itself (invoke! "values" id :still_stands nil sous)]
    (testing "FOUR EYES, AND NO GRANT OPENS IT: the leash names still_stands and the wall refuses anyway, because this agent wrote the row"
      (is (= 409 (:status itself)) (str "allowed: " (json itself)))
      (is (= "written-by-a-person" (guard-of itself)))
      (is (str/includes? (detail itself) "four eyes")))
    (testing "and the refusal names the composer's own door, which since waymark-bug is the hypothesis"
      (is (str/includes? (detail itself) "HYPOTHESIS")))
    (let [tap (invoke! "values" id :still_stands nil (human "colton-affirm"))]
      (testing "the owner's tap says he read what was brought him and it stands — one stamp, no state change"
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

;; ── 2b. the affirmation is GRANTABLE (waymark-sfe) ──────────────────
;;
;; The owner ruled a second time on 2026-08-28: "The whole reason we
;; have the access controls we have is so that I can ask you to do what
;; I want when I want. It doesn't make sense to disallow it, it just
;; makes sense to permission it." So the wall above became a
;; permission: a delegate holding a scope that NAMES `value.still_stands`
;; says the owner's yes on the owner's instruction, and the audit says
;; which grant it said it under. What did not move is four eyes — the
;; hand that WROTE the row still never answers it — which is what
;; section 2 above now proves in its own words.

(deftest a-delegate-under-a-scope-that-names-the-door-affirms
  (let [writer (leash! "sous-sfe-writer" ["create"])
        v (write-value! writer "an hour in the shop before the house wakes")
        id (id-of v)]
    (testing "an agent whose scope does not name the door sees no such door — concealment, never a narrated refusal"
      (let [blind (leash! "sous-sfe-blind" ["create"])]
        (is (= 404 (:status (invoke! "values" id :still_stands nil blind))))))
    (testing "one that does name it lands the affirmation, and the row becomes the house's"
      (let [delegate (leash! "sous-sfe-delegate" ["still_stands"])
            r (invoke! "values" id :still_stands nil delegate)]
        (is (= 200 (:status r)) (str "refused: " (json r)))
        (is (= "declared" (state-of r)))
        (is (= "sous-sfe-delegate" (:affirmed_by (fields r))))
        (is (= "sous-sfe-writer" (:written_by (fields r)))
            "the hand that noticed it is still on the row")
        (testing "and the history reads 'under grant-…'"
          (is (= (get delegate "x-waymark-grant")
                 (under-grant :value id :still_stands))))))))

(deftest a-filtered-scope-admits-only-the-rows-it-names
  ;; the `:filter` half of a scope entry, judged at the row: rows
  ;; minted after the grant land inside the leash the moment they
  ;; match, and rows outside it do not exist at all.
  (let [ours (leash! "sous-sfe-ours" ["create"])
        theirs (leash! "sous-sfe-theirs" ["create"])
        inside (id-of (write-value! ours "the workbench, cleared before Saturday"))
        outside (id-of (write-value! theirs "a podcast while the sauce reduces"))
        narrow (leash! "sous-sfe-narrow"
                       [{:kind "value" :actions ["still_stands"]
                         :filter {:written_by "sous-sfe-ours"}}]
                       :scope)]
    (testing "outside the filter, the row does not exist"
      (is (= 404 (:status (invoke! "values" outside :still_stands nil narrow)))))
    (testing "inside it, the same tap lands"
      (let [r (invoke! "values" inside :still_stands nil narrow)]
        (is (= 200 (:status r)) (str "refused: " (json r)))
        (is (= "declared" (state-of r)))))))

;; ── 3. one wording door, and the observer's is gone ─────────────────
;; jfv.10 SPLIT the wording door by hand because `:to` is a static
;; keyword and one door cannot land in two states: a shared `revise`
;; would have had an agent's own rewording land in `declared`, which
;; is the observer affirming its own guess. With `observed` gone there
;; is one landing and therefore one door. What a reading corrects now
;; is a `hypothesis`, on a row that carries a number instead of the
;; law.

(deftest the-wording-door-is-the-owners-and-the-observers-is-gone
  (testing "a SCOPE cannot even name the observer's door any more, and that
            is the strongest way the door's absence can be watched: the
            grant machine validates a scope entry against the kind's own
            declared actions, so jfv.10's own composer leash
            (`value: [\"create\", \"restate\"]`) is refused at the mint"
    (let [made (req :post "/api/grants"
                    {:audience "sous-gone"
                     :scope [{:kind "value" :actions ["restate"]}]}
                    (human "colton-grants"))]
      (is (not= 201 (:status made))
          (str "a scope named a door this kind does not have and the grant"
               " door minted it anyway: " (json made)))))
  (let [sous (leash! "sous-word" ["create" "revise"])
        v (write-value! sous "the workbench, cleared")
        id (id-of v)]
    (testing "and no door answers `restate` on the row either"
      (let [gone (word! "values" id :restate
                        {:name "the workbench, cleared on Fridays"
                         :says "Narrowed: it is Fridays. Six of the last eight."
                         :loved ["the shop"]}
                        sous)]
        (is (contains? #{404 405} (:status gone))
            (str "a door that should not exist answered: " (json gone)))))
    (testing "and the agent may not REWORD either, because rewording is the owner putting his own words on his own law"
      (let [r (word! "values" id :revise
                     {:name "the workbench, cleared, and it matters more than the queue"
                      :says "An agent's own rewriting of the owner's law."}
                     sous)]
        (is (= 409 (:status r)) (str "allowed: " (json r)))
        (is (= "written-by-a-person" (guard-of r)))))
    (testing "the owner's own rewording lands, and stamps him"
      (let [r (word! "values" id :revise
                     {:name "the workbench, cleared before Saturday"
                      :says "In his own words, which is the only way these words are ever written."
                      :loved ["the shop"]}
                     (human "colton-word"))]
        (is (= 200 (:status r)) (str "refused: " (json r)))
        (is (= "declared" (state-of r)))
        (is (= "colton-word" (:affirmed_by (fields r))))
        (is (= "sous-word" (:written_by (fields r)))
            "the hand that first wrote it down is still on the row")))))

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
        (is (= "declared" (state-of ok)))))
    (testing "and a member's own private value is untouched by any of this"
      (let [mine (write-value! (human "colton-mine") "building, mine"
                               {:scope "mine"})]
        (is (= 201 (:status mine)))
        (is (= "declared" (state-of mine)))
        (is (= "colton-mine" (:owner (fields mine))))))))

;; ── 5. gone with the state — see the ns docstring ───────────────────
;; `an-outcome-on-an-observed-value-says-so-on-its-card` and its two
;; helpers (`stage-bundle!`, `card-for`) lived here and left with
;; waymark-bug: there are no observed values to stage a bundle
;; against. The requirement they proved has not been abandoned — it
;; moved a slice down the epic, where the card sentence reads the
;; intent hypothesis's POSTERIOR instead of a one-bit state
;; (waymark-4t9, docs/spec-hypotheses.md § 'What merges', the crown
;; row).

;; ── 6. a value the house stopped holding ────────────────────────────
;; `dismiss` left with `observed` — it was the door for *you read us
;; wrong*, which is a sentence about a guess, and guesses live on the
;; hypothesis kind now (`hypothesis.dismiss` says it there, and
;; `hypothesis.retire` says the other one). What is left here is
;; `retire`, which always meant the second sentence about a value the
;; house actually declared, and `restore`, which is also the way back
;; for a value the belief migration retired.

(deftest a-value-the-house-retired-stops-being-composed-against
  (let [id (id-of (write-value! (human "colton-wrong") "shipping more, on Sundays"))
        answered (invoke! "values" id :retire nil (human "colton-wrong"))]
    (testing "retiring is a person's own door and the log keeps whose hand it was"
      (is (= 200 (:status answered)) (str "refused: " (json answered)))
      (is (= "retired" (state-of answered)))
      (is (contains? (hands :value id) [:retire "colton-wrong"])))
    (testing "and no outcome may be staged against it — names-a-value has always held this"
      (let [r (req :post "/api/outcomes"
                   {:goal "A Sunday spent on a value this house let go"
                    :value_id id
                    :routing "It runs through nothing this house said it loves."
                    :evidence [(str "/api/values/" id)]}
                   (human "composer-wrong"))]
        (is (= 409 (:status r)) (str "allowed: " (json r)))
        (is (= "names-a-value" (guard-of r)))
        (is (str/includes? (detail r) "retired"))))
    (testing "restoring it holds it again, and holding it again is affirming it"
      (let [back (invoke! "values" id :restore nil (human "colton-wrong"))]
        (is (= 200 (:status back)))
        (is (= "declared" (state-of back)))
        (is (= "colton-wrong" (:affirmed_by (fields back))))))))
