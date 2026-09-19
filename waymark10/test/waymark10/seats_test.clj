(ns waymark10.seats-test
  "The seat, the model and the sitting (docs/spec-seat.md § 16): the
  acceptance cases this leg can answer with the three kinds alone.

  What is HERE: the scope honesty gate at both doors (case 1), the
  person wall (case 22), the ladder's note (case 19), the retired-model
  refusal (case 13), the charter's cap (case 20), the close that costs
  a sitting from the model's prices AT THAT MOMENT and the reprice that
  does not move it (case 14), the merge's fold (case 7's first half),
  and the concealed pair the router writes a halt through.

  What is NOT here, and why: every case that needs `grant.seat` —
  the seat resolve, the substitute, the ask's three shapes, the
  budget wall, the counters as the ROUTER drives them — waits on the
  field wave two adds. The seam those cases will drive is exercised
  here as a function call (`open-sitting-for-grant`, `bump-counter!`,
  `seat-halt!`, `seat-clear-halt!`), which is what this leg owns.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:dynamic *eng* nil)

(def ^:private tables
  ["seats" "models" "sittings" "grants" "approval_requests" "members"
   "roles" "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st :resources []})]
          (f))
        (finally (pg/close! st))))))

;; ── the hands ───────────────────────────────────────────────────────

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))
;; the owner's connector: an :agent the identity gate marked :acts-for
;; (oidc.clj, spec-connector-door § 3) — the mark rides OUTSIDE
;; t/principal's closed shape, so it is assoc'd the way the gate does it
(def ^:private delegate
  (assoc (t/principal {:id "waymark10-connector-claude:colton"
                       :type :agent
                       :display "Claude for Colton"})
         :acts-for "colton"))

;; ── readers ─────────────────────────────────────────────────────────

(defn- row-of [kind id]
  (let [rdef (get (inv/resources *eng*) kind)]
    (some->> (store/with-tx (:storage *eng*)
               (fn [tx]
                 (store/load-row (:storage *eng*) tx kind (str id) {})))
             (inv/decode-row rdef))))

(defn- log-of [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (store/transitions (:storage *eng*) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- refusal
  "The problem ex-data of a write that was refused, or nil when it was
  served — {:guard … :detail … :status …}."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

;; ── the shapes a test starts from ───────────────────────────────────

(def ^:private good-scope
  [{:kind "model" :actions ["retire"]}])

(defn- seat-body [name' extra]
  (merge {:name name'
          :charter "Decide whether a message asks something of this house."
          :scope good-scope
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 5M
          :sitting_budget_tokens 60000}
         extra))

(defn- restate-body [extra]
  (merge {:charter "Decide whether a message asks something of this house."
          :scope good-scope
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 5M
          :sitting_budget_tokens 60000}
         extra))

(defn- open-seat!
  ([name'] (open-seat! name' {}))
  ([name' extra]
   (:row (inv/create! *eng* :seat (seat-body name' extra)
                      {:principal colton}))))

(defn- restate!
  "A restate carries the fence an :edit implies, so the caller hands
  over the etag an honest client would have read off the row."
  ([id body] (restate! id body colton))
  ([id body principal]
   (let [row (row-of :seat id)]
     (inv/invoke! *eng* :seat id :restate body
                  {:principal principal
                   :if-match (inv/etag :seat id (:version row))}))))

(defn- add-model!
  ([name' tier] (add-model! name' tier {}))
  ([name' tier prices]
   (:row (inv/create! *eng* :model
                      (merge {:name name'
                              :display name'
                              :vendor "anthropic"
                              :tier tier
                              :price_input_per_mtok 3M
                              :price_output_per_mtok 15M
                              :price_cache_read_per_mtok 0.3M
                              :price_cache_write_per_mtok 3.75M}
                             prices)
                      {:principal colton}))))

;; ── case 1 · a scope names what this engine actually serves ─────────

(deftest a-scope-entry-that-names-no-action-is-refused-at-both-doors
  (testing "at create, with the entry named"
    (let [p (refusal #(inv/create!
                       *eng* :seat
                       (seat-body "scope-liar"
                                  {:scope [{:kind "model" :actions ["explode"]}]})
                       {:principal colton}))]
      (is (= :scope-names-real-actions (:guard p)))
      (is (str/includes? (str (:detail p)) "explode")
          "the refusal spells the entry that failed, not 'invalid scope'")
      (is (str/includes? (str (:detail p)) "model")
          "…and the kind it failed on")))
  (testing "and at restate, on a seat that was born honest"
    (let [seat (open-seat! "scope-honest")
          p (refusal #(restate! (:id seat)
                                (restate-body
                                 {:scope [{:kind "nosuchkind"
                                           :actions ["retire"]}]})))]
      (is (= :scope-names-real-kinds (:guard p)))
      (is (str/includes? (str (:detail p)) "nosuchkind"))
      (is (= good-scope (get-in (row-of :seat (:id seat)) [:data :scope]))
          "and the stored scope did not move"))))

(deftest a-walk-names-a-kind-whose-queue-filters-itself
  ;; bead waymark-fp62.6.3.10, R-12.32: the guard asked for a default
  ;; filter over STATE, which refused every kind that keeps its
  ;; lifecycle in a data field — `task` is one, and a seat must be
  ;; able to walk it. It now asks for ONE default filter, whichever
  ;; field it names, because what a walk needs is a collection that
  ;; opens on the work waiting rather than on the whole table.
  (testing "a kind that declares no default filter at all is refused"
    (let [p (refusal #(open-seat! "walks-the-whole-table" {:walk "model"}))]
      (is (= :walk-names-a-kind-in-scope (:guard p)))
      (is (str/includes? (str (:detail p)) "no default filter at all")
          "and the sentence says what a walk needs, which is a queue
           that filters itself")))
  (testing "a kind the scope does not open is refused before that"
    (let [p (refusal #(open-seat! "walks-what-it-cannot-see"
                                  {:walk "sitting"}))]
      (is (= :walk-names-a-kind-in-scope (:guard p)))
      (is (str/includes? (str (:detail p)) "scope")))))

;; ── case 22 · a seat is a person's office ───────────────────────────

(deftest a-seat-is-opened-by-a-person-and-not-by-an-agent
  (testing "a person's create is served"
    (let [row (open-seat! "opened-by-a-person")]
      (is (= :active (:state row)))
      (is (= "opened-by-a-person" (get-in row [:data :name])))
      (is (= 20 (get-in row [:data :rows_per_firing]))
          "the declared default filled the walk's cap")
      (is (= [] (get-in row [:data :held_for]))
          "and an empty held_for means any model may sit")))
  (testing "an agent's is refused by the person wall"
    (let [p (refusal #(inv/create! *eng* :seat (seat-body "opened-by-an-agent" {})
                                   {:principal clerk}))]
      (is (= :a-person (:guard p)))
      (is (nil? (row-of :seat "opened-by-an-agent")))))
  (testing "a delegate's — a person signed in through a tool — is served"
    ;; spec-seat.md § 17, ruled 2026-09-17: the connector resolves to
    ;; an :agent that the identity gate marked :acts-for, and the
    ;; person behind the mark is the person the wall always meant
    (let [row (:row (inv/create! *eng* :seat (seat-body "opened-through-a-tool" {})
                                 {:principal delegate}))]
      (is (= :active (:state row)))
      (is (= "opened-through-a-tool" (get-in row [:data :name])))))
  (testing "an agent that merely CLAIMS the mark in its id is not a delegate"
    (let [p (refusal #(inv/create! *eng* :seat (seat-body "opened-by-a-pretender" {})
                                   {:principal (t/principal {:id "waymark10-connector-claude:colton"
                                                             :type :agent
                                                             :display "Claude for Colton"})}))]
      (is (= :a-person (:guard p))
          "the mark is the gate's assoc, not a spelling of the id"))))

;; ── case 20 · the charter's cap is the priming budget ───────────────

(deftest a-charter-longer-than-the-priming-budget-is-refused
  (let [long-charter (apply str (repeat 1201 "x"))]
    (testing "at create"
      (let [p (refusal #(inv/create! *eng* :seat
                                     (seat-body "long-winded"
                                                {:charter long-charter})
                                     {:principal colton}))]
        (is (= 422 (:status p)))
        (is (contains? (:errors p) :charter))))
    (testing "and at restate"
      (let [seat (open-seat! "terse")
            p (refusal #(restate! (:id seat)
                                  (restate-body {:charter long-charter})))]
        (is (= 422 (:status p)))
        (is (contains? (:errors p) :charter))))))

;; ── cases 19 and 13 · the ladder's step is a record ─────────────────

(deftest a-step-on-the-ladder-carries-its-note
  (let [frontier (add-model! "step-frontier" "frontier")
        strong (add-model! "step-strong" "strong")
        seat (open-seat! "stepper" {:held_for [(:id frontier)]})]
    (testing "a restate that changes held_for with no note is refused"
      (let [p (refusal #(restate! (:id seat)
                                  (restate-body {:held_for [(:id strong)]})))]
        (is (= :step-carries-a-note (:guard p)))
        (is (= [(:id frontier)]
               (get-in (row-of :seat (:id seat)) [:data :held_for]))
            "and the seat is still held for the model it was")))
    (testing "everything else about the seat moves without one"
      (restate! (:id seat) (restate-body {:held_for [(:id frontier)]
                                          :cadence_seconds 7200}))
      (is (= 7200 (get-in (row-of :seat (:id seat)) [:data :cadence_seconds]))))
    (testing "a restate that changes it WITH a note is served, and the log
              holds the note — a transition input, no column added"
      (restate! (:id seat)
                (restate-body {:held_for [(:id strong)]
                               :cadence_seconds 7200
                               :note "Down a rung: five sittings read, the
                                      dismissals held."}))
      (is (= [(:id strong)]
             (get-in (row-of :seat (:id seat)) [:data :held_for])))
      (let [step (last (filter #(= :restate (:action %))
                               (log-of :seat (:id seat))))]
        (is (str/includes? (str (get-in step [:inputs :note])) "Down a rung")
            "the step's reason is on record in the log's inputs")))
    (testing "a restate naming a RETIRED model is refused, and names it"
      (inv/invoke! *eng* :model (:id frontier) :retire nil {:principal colton})
      (let [p (refusal #(restate! (:id seat)
                                  (restate-body
                                   {:held_for [(:id frontier)]
                                    :cadence_seconds 7200
                                    :note "back up a rung"})))]
        (is (= :held-for-active-models (:guard p)))
        (is (str/includes? (str (:detail p)) (:id frontier)))))))

;; ── case 14 · what a sitting cost is what it cost ───────────────────

(defn- a-grant-for [audience]
  (:row (inv/create! *eng* :grant
                     {:audience audience :scope good-scope}
                     {:principal colton})))

(deftest a-close-costs-the-sitting-at-the-prices-of-that-moment
  (let [model (add-model! "priced" "strong")
        seat (open-seat! "book-keeper")
        grant (a-grant-for "clerk")
        sitting (:row (inv/create! *eng* :sitting
                                   {:seat (:id seat)
                                    :model (:id model)
                                    :grant (:id grant)}
                                   {:principal clerk}))]
    (testing "the birth stamps the member, the clock and the two counters"
      (is (= :open (:state sitting)))
      (is (= "clerk" (get-in sitting [:data :member])))
      (is (some? (get-in sitting [:data :started_at])))
      (is (= 0 (get-in sitting [:data :transitions])))
      (is (= 0 (get-in sitting [:data :refusals]))))
    (testing "the close computes the cost and writes the prices it used"
      (inv/invoke! *eng* :sitting (:id sitting) :close
                   {:input_tokens 1000000
                    :output_tokens 200000
                    :cache_read_tokens 500000
                    :cache_write_tokens 100000
                    :turns 4
                    :note "Walked nine messages; two became tasks."}
                   {:principal clerk})
      (let [closed (row-of :sitting (:id sitting))]
        (is (= :closed (:state closed)))
        (is (zero? (compare (bigdec "6.525")
                            (get-in closed [:data :cost_usd])))
            "3.00 in + 3.00 out + 0.15 cache-read + 0.375 cache-write")
        (is (zero? (compare 3M (get-in closed [:data :prices :input]))))
        (is (zero? (compare 15M (get-in closed [:data :prices :output]))))
        (is (zero? (compare 0.3M (get-in closed [:data :prices :cache_read]))))
        (is (zero? (compare 3.75M (get-in closed [:data :prices :cache_write]))))
        (is (some? (get-in closed [:data :ended_at])))))
    (testing "and a reprice afterwards does not move one byte of it"
      (inv/invoke! *eng* :model (:id model) :reprice
                   {:price_input_per_mtok 30M
                    :price_output_per_mtok 150M
                    :price_cache_read_per_mtok 3M
                    :price_cache_write_per_mtok 37.5M}
                   {:principal colton
                    :if-match (inv/etag :model (:id model)
                                        (:version (row-of :model (:id model))))})
      (is (zero? (compare 30M (get-in (row-of :model (:id model))
                                      [:data :price_input_per_mtok])))
          "the model moved")
      (let [closed (row-of :sitting (:id sitting))]
        (is (zero? (compare (bigdec "6.525") (get-in closed [:data :cost_usd])))
            "the bill did not")
        (is (zero? (compare 3M (get-in closed [:data :prices :input]))))))))

;; ── R-10.6 · the seam the router counts through ─────────────────────

(deftest the-open-sitting-is-found-by-its-grant-and-counts-up
  (let [model (add-model! "counted" "economy")
        seat (open-seat! "counter")
        grant (a-grant-for "clerk")
        sitting (:row (inv/create! *eng* :sitting
                                   {:seat (:id seat)
                                    :model (:id model)
                                    :grant (:id grant)}
                                   {:principal clerk}))]
    (testing "one lookup by grant finds the open sitting"
      (is (= (:id sitting) (:id (seats/open-sitting-for-grant *eng* (:id grant)))))
      (is (nil? (seats/open-sitting-for-grant *eng* "grant-nobody-holds"))))
    (testing "the counters go up, and nothing is logged for them"
      (is (= 1 (seats/bump-counter! *eng* (:id sitting) :transitions)))
      (is (= 2 (seats/bump-counter! *eng* (:id sitting) :transitions)))
      (is (= 1 (seats/bump-counter! *eng* (:id sitting) :refusals)))
      (let [row (row-of :sitting (:id sitting))]
        (is (= 2 (get-in row [:data :transitions])))
        (is (= 1 (get-in row [:data :refusals]))))
      (is (= 1 (count (log-of :sitting (:id sitting))))
          "the create, and no transition per count — the counter must not
           cost more log than the thing it counts"))
    (testing "a closed sitting counts nothing more, and the grant stops
              resolving to it"
      (inv/invoke! *eng* :sitting (:id sitting) :close
                   {:input_tokens 10 :output_tokens 10
                    :cache_read_tokens 0 :cache_write_tokens 0 :turns 1}
                   {:principal clerk})
      (is (nil? (seats/bump-counter! *eng* (:id sitting) :transitions)))
      (is (= 2 (get-in (row-of :sitting (:id sitting)) [:data :transitions]))
          "the counts froze at the close")
      (is (nil? (seats/open-sitting-for-grant *eng* (:id grant)))))))

;; ── R-7.7 · the halt the router writes, and its idempotence ─────────

(deftest a-halt-is-written-once-and-cleared-once
  (let [seat (open-seat! "walled")]
    (testing "the first request at a wall writes the halt"
      (is (true? (seats/seat-halt! *eng* (:id seat) "budget_reached"
                                   "5.00 of 5.00 spent since Monday")))
      (let [row (row-of :seat (:id seat))]
        (is (= "budget_reached" (get-in row [:data :halt :reason])))
        (is (some? (get-in row [:data :halt :since])))
        (is (str/includes? (str (get-in row [:data :halt :detail])) "5.00"))
        (is (= :active (:state row))
            "a halt is not a state — park and unpark stay the person's")))
    (testing "a second request at the SAME wall writes nothing more"
      (is (false? (seats/seat-halt! *eng* (:id seat) "budget_reached" "again")))
      (is (= 1 (count (filter #(= :mark_halted (:action %))
                              (log-of :seat (:id seat)))))
          "one alert, not one per request"))
    (testing "the first request that passes clears it, and only the first"
      (is (true? (seats/seat-clear-halt! *eng* (:id seat))))
      (is (nil? (get-in (row-of :seat (:id seat)) [:data :halt])))
      (is (false? (seats/seat-clear-halt! *eng* (:id seat))))
      (is (= 1 (count (filter #(= :clear_halt (:action %))
                              (log-of :seat (:id seat)))))))
    (testing "and both are the engine's own hand — a person is 404, because
              the doors are concealed rather than merely refused"
      (let [p (refusal #(inv/invoke! *eng* :seat (:id seat) :mark_halted
                                     {:reason "budget_reached"}
                                     {:principal colton}))]
        (is (= 404 (:status p)))))))

;; ── § 6 · the merge folds to one entry per kind ─────────────────────

(deftest a-merge-folds-the-scopes-and-closes-the-source
  (let [into (open-seat! "the-wider-office"
                         {:scope [{:kind "model" :actions ["reactivate"]}
                                  {:kind "sitting" :actions ["close"]}]
                          :standing_ttl_seconds 3600
                          :budget_usd_per_week 2M})
        source (open-seat! "the-folded-office"
                           {:scope [{:kind "model" :actions ["retire"]}]
                            :standing_ttl_seconds 604800
                            :budget_usd_per_week 9M})]
    (testing "a merge into itself is refused, and so is one into a tomb"
      (is (= :merge-target-is-active
             (:guard (refusal #(inv/invoke! *eng* :seat (:id source) :merge
                                            {:into (:id source)}
                                            {:principal colton}))))))
    (testing "the fold lands on `into`: one entry per kind, the larger ttl
              and the larger budget"
      (inv/invoke! *eng* :seat (:id source) :merge {:into (:id into)}
                   {:principal colton})
      (let [wider (row-of :seat (:id into))
            scope (get-in wider [:data :scope])]
        (is (= 2 (count scope)) "two kinds, two entries — never appended")
        (is (= #{"model" "sitting"} (set (map :kind scope))))
        (let [model-entry (first (filter #(= "model" (:kind %)) scope))]
          (is (= #{"reactivate" "retire"} (set (:actions model-entry)))
              "the two seats' actions on one kind, unioned into one entry"))
        (is (= 604800 (get-in wider [:data :standing_ttl_seconds]))
            "the larger ttl survives")
        (is (zero? (compare 9M (get-in wider [:data :budget_usd_per_week])))
            "and the larger budget")
        (is (= :active (:state wider)))))
    (testing "the source closes, naming where its work went"
      (let [folded (row-of :seat (:id source))]
        (is (= :merged (:state folded)))
        (is (= (:id into) (get-in folded [:data :merged_into])))))
    (testing "and `into`'s own history says its authority grew"
      (is (= 1 (count (filter #(= :absorb (:action %))
                              (log-of :seat (:id into)))))))
    (testing "nobody reaches absorb by hand — it is the merge's door"
      (let [p (refusal #(inv/invoke! *eng* :seat (:id into) :absorb
                                     {:scope good-scope
                                      :substitute_drop []
                                      :standing_ttl_seconds 3600
                                      :budget_usd_per_week 1M}
                                     {:principal colton}))]
        (is (= 404 (:status p)))))))

;; ── the levers that cost nothing ────────────────────────────────────

(deftest park-is-the-cheap-lever-and-unpark-is-the-way-back
  (let [seat (open-seat! "parkable")]
    (inv/invoke! *eng* :seat (:id seat) :park nil {:principal colton})
    (is (= :parked (:state (row-of :seat (:id seat)))))
    (testing "an agent cannot pull it back either"
      (is (= :a-person
             (:guard (refusal #(inv/invoke! *eng* :seat (:id seat) :unpark nil
                                            {:principal clerk}))))))
    (inv/invoke! *eng* :seat (:id seat) :unpark nil {:principal colton})
    (is (= :active (:state (row-of :seat (:id seat))))
        "and the scope it had is the scope it has — no grant moved")))

(deftest one-spelling-per-seat-and-one-row-per-model
  (open-seat! "only-once")
  (is (= :one-seat-spelling
         (:guard (refusal #(inv/create! *eng* :seat (seat-body "only-once" {})
                                        {:principal colton})))))
  (add-model! "only-one-row" "economy")
  (let [p (refusal #(add-model! "only-one-row" "frontier"))]
    (is (= :one-model-spelling (:guard p)))
    (is (str/includes? (str (:detail p)) "reactivate")
        "and the refusal names the door that exists instead")))

;; ── R-12.12 · the sitter key, and the fence around it ───────────────

(def ^:private a-key
  "What a machine mints for 128 bits, base64url — never what a hand
  types, which is the whole of why the engine does not generate it."
  "c2l0dGVyLWtleS1vbmUtaGVyZQ")

(def ^:private another-key "c2l0dGVyLWtleS10d28taGVyZQ")

(def ^:private lookup-key
  "The lookup test's own pair: it asserts that a key answers ONE seat,
  so it must never share a key with a test that leaves a keyed seat
  active behind it — the seed decides which deftest runs first."
  "c2l0dGVyLWtleS1sb29rdXAtbWluZQ")

(def ^:private lookup-other-key "c2l0dGVyLWtleS1sb29rdXAtdGhlaXJz")

(def ^:private kept-key
  "The restate test's own key: the seat it leaves behind stays active
  and keyed, and seat-by-key reads every active seat, so a key shared
  with the lookup test would answer for two seats under the seed that
  runs this file's tests in the other order."
  "c2l0dGVyLWtleS1rZXB0LWhlcmU")

(def ^:private planted-key
  "Its own key, so the fence's assertions do not depend on which
  deftest in this file ran first."
  "c2l0dGVyLWtleS1wbGFudGVkLWhlcmU")

(defn- key-of
  "The stored key, read off the row rather than off any projection —
  the projections are what must not show it."
  [id]
  (get-in (row-of :seat id) [:data :sitter_key]))

(deftest a-person-offers-the-seat-a-key-and-nothing-renders-it
  (let [seat (open-seat! "keyed-office")]
    (testing "the field is :secret, which is what conceals it everywhere"
      (is (contains? (schema/secret-fields (:schema seats/seat)) :sitter_key)))

    (testing "a person's offer stores the key"
      (inv/invoke! *eng* :seat (:id seat) :offer_key {:key a-key}
                   {:principal colton})
      (is (= a-key (key-of (:id seat)))))

    (testing "and the envelope does not carry it, for anybody"
      (let [rdef (get (inv/resources *eng*) :seat)
            row (row-of :seat (:id seat))
            env (fn [who] (render/envelope rdef row
                                           {:principal who
                                            :now ((:now-fn *eng*))}))]
        (doseq [[who p] [["the person whose office it is" colton]
                         ["a bare agent" clerk]
                         ["the anonymous" t/anonymous]]]
          (testing who
            (is (not (contains? (get (env p) "data") "sitter_key"))
                "a :secret field is absent from the document, not blanked")))))

    (testing "a second offer REPLACES the first — at most one live key"
      (inv/invoke! *eng* :seat (:id seat) :offer_key {:key another-key}
                   {:principal colton})
      (is (= another-key (key-of (:id seat)))))

    (testing "a delegate — the person's own tool — may offer one too"
      (inv/invoke! *eng* :seat (:id seat) :offer_key {:key a-key}
                   {:principal delegate})
      (is (= a-key (key-of (:id seat)))))

    (testing "a bare agent may not: the office is the person's"
      (let [p (refusal #(inv/invoke! *eng* :seat (:id seat) :offer_key
                                     {:key another-key}
                                     {:principal clerk}))]
        (is (= :a-person (:guard p)))
        (is (= a-key (key-of (:id seat))) "and the key did not move")))

    (testing "revoke_key clears it, and a bare agent cannot pull that lever either"
      (is (= :a-person
             (:guard (refusal #(inv/invoke! *eng* :seat (:id seat) :revoke_key
                                            nil {:principal clerk})))))
      (inv/invoke! *eng* :seat (:id seat) :revoke_key nil {:principal colton})
      (is (nil? (key-of (:id seat)))))))

(deftest the-sitter-key-is-never-written-by-hand
  (testing "a create carrying sitter_key is refused, and names the fence"
    (let [p (refusal #(inv/create! *eng* :seat
                                   (seat-body "planted-at-birth"
                                              {:sitter_key planted-key})
                                   {:principal colton}))]
      (is (= :key-not-written-by-hand (:guard p)))
      (is (nil? (seats/seat-by-key *eng* planted-key))
          "no seat was born holding it")))
  (testing "and a restate carrying it is refused on a seat born honest"
    (let [seat (open-seat! "planted-later")
          p (refusal #(restate! (:id seat)
                                (restate-body {:sitter_key planted-key})))]
      (is (= :key-not-written-by-hand (:guard p)))
      (is (nil? (key-of (:id seat))) "and the seat holds no key")))
  (testing "a restate that carries no key leaves the offered one alone"
    (let [seat (open-seat! "restated-around-its-key")]
      (inv/invoke! *eng* :seat (:id seat) :offer_key {:key kept-key}
                   {:principal colton})
      (restate! (:id seat) (restate-body {:cadence_seconds 7200}))
      (is (= kept-key (key-of (:id seat)))
          "restate states the office again; the credential is not part of it"))))

(deftest seat-by-key-answers-the-one-seat-and-nobody-else
  (let [mine (open-seat! "key-lookup-mine")
        theirs (open-seat! "key-lookup-theirs")]
    (inv/invoke! *eng* :seat (:id mine) :offer_key {:key lookup-key}
                 {:principal colton})
    (inv/invoke! *eng* :seat (:id theirs) :offer_key {:key lookup-other-key}
                 {:principal colton})
    (is (= (:id mine) (:id (seats/seat-by-key *eng* lookup-key))))
    (is (= (:id theirs) (:id (seats/seat-by-key *eng* lookup-other-key))))
    (testing "a key nobody holds answers nil, and so does a blank one"
      (is (nil? (seats/seat-by-key *eng* "c2l0dGVyLWtleS1ub2JvZHktaGFz")))
      (is (nil? (seats/seat-by-key *eng* "")))
      (is (nil? (seats/seat-by-key *eng* nil))
          "nil must never match the seats that hold no key"))
    (testing "a revoked key answers nothing"
      (inv/invoke! *eng* :seat (:id mine) :revoke_key nil {:principal colton})
      (is (nil? (seats/seat-by-key *eng* lookup-key))))
    (testing "and a parked seat is not an active one"
      (inv/invoke! *eng* :seat (:id theirs) :park nil {:principal colton})
      (is (nil? (seats/seat-by-key *eng* lookup-other-key))))))
