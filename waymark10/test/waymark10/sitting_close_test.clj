(ns waymark10.sitting-close-test
  "The end of a wake, reported (docs/spec-seat.md § 12.1, R-12.17).

  A keyed sitter session opens a sitting when it sits (R-12.15) and
  the router counts its transitions and refusals against that row —
  and until this door existed nothing ever closed it. The boot sweep
  abandoned it two cadences later, which records the ABSENCE of a bill
  rather than a bill, so a seat that was working cost nothing on
  paper. The thing that knows when a run ended is the harness, not the
  engine: the session stops, its hook sums the transcript's usage and
  posts it to `/api/-/sittings/close` with the seat's key in a header.

  What the door is, in one line: the seat's key is the whole
  credential, the counts are the whole body, and the close goes
  through the sitting's OWN `close` action (`inv/invoke!`) so the
  model's prices are read at that moment (R-10.4), the cost is written
  beside them, and the ending is in the transition log like every
  other.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: no database, no network. The shape is
  mcp_sit_test's, whose helpers this file re-spells rather than
  reaches for — they are private there by design, and a handful of
  small fns is cheaper than a seam nobody else wants. Every deftest
  builds its OWN engine and mints its own key: the suite's seed is
  random, and shared mutable state between two of these would be a
  test that passes in one order and not another."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.routes.seats :as seat-routes]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.wakes :as wakes]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

;; ── the connector door, as mcp_sit_test stands it up ────────────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "close-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "close-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "close-key"}}))

(defn- bearer [claims]
  {"authorization" (str "Bearer " (mint claims))})

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [fx/meal]
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))
(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- rpc
  "One JSON-RPC message at /api/-/mcp through the real handler."
  [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- tool
  "One tools/call, answering the parsed tool result."
  [h headers tool-name args]
  (let [resp (rpc h headers "tools/call" {:name tool-name :arguments args})]
    (assoc (get-in (json resp) [:result]) :status (:status resp))))

(defn- text-of [result]
  (str (get-in result [:content 0 :text])))

(defn- doc-of [result]
  (wire/read-json (text-of result)))

;; ── the office the key opens ────────────────────────────────────────

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "c2l0dGluZy1jbG9zZS1zZWF0LWtleS0wMQ")

(def ^:private prices
  "The four the fixture's model carries, named once so the expected
  cost is computed from the same numbers the close reads."
  {:input 3M :output 15M :cache_read 0.3M :cache_write 3.75M})

(defn- open-seat!
  "A person's seat over the fixture's meal kind, its schedule minted
  and its key offered — everything a Routine's instructions would
  already have."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "close-test-model" :display "Close 1"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok (:input prices)
                                  :price_output_per_mtok (:output prices)
                                  :price_cache_read_per_mtok (:cache_read prices)
                                  :price_cache_write_per_mtok (:cache_write prices)}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    {:name "meal-clerk"
                     :charter "Decide whether a meal belongs on the list."
                     :scope [{:kind "meal" :actions ["accept"]}]
                     :held_for [(:id model)]
                     :standing_ttl_seconds 604800
                     :cadence_seconds 3600
                     :budget_usd_per_week 5M
                     :sitting_budget_tokens 60000}
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    {:seat seat :model model}))

(defn- initialize!
  "The handshake, answering the session id."
  [h]
  (get-in (rpc h (bearer colton) "initialize"
                {:protocolVersion mcp/protocol-version
                 :capabilities {} :clientInfo {:name "routine" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

(defn- sit!
  "One firing sitting in the seat, answering the parsed value document.
  `session` is the harness's own run id when it has one (R-12.15)."
  ([h sid] (sit! h sid nil))
  ([h sid session]
   (doc-of (tool h (with-session sid) "waymark_sit"
                 (cond-> {:key a-key} session (assoc :session session))))))

;; ── the door under test ─────────────────────────────────────────────

(def ^:private close-uri "/api/-/sittings/close")

(defn- report!
  "The session-end hook's POST: the seat's key in the header, the
  counts in the body. No bearer, deliberately — the hook runs after
  the session that held one has ended."
  ([h body] (report! h {"waymark-seat-key" a-key} body))
  ([h headers body]
   (h {:request-method :post :uri close-uri :headers headers
       :body (if (string? body) body (wire/write-json body))})))

(def ^:private counts
  "One wake's usage, as a hook sums it off a transcript."
  {:input_tokens 12000 :output_tokens 3400
   :cache_read_tokens 90000 :cache_write_tokens 1500 :turns 7})

(defn- row-of
  "One stored row, raw — the document as the store holds it."
  [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- open-sittings
  "Every open sitting this seat holds, newest first."
  [eng seat]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/query-rows (:storage eng) tx :sitting
                        {:seat (str (:id seat)) :state :open}
                        {:limit 20 :newest-first true}))))

;; ── 1. the whole round trip ─────────────────────────────────────────

(deftest a-hook-closes-the-wake-it-opened-and-the-bill-is-recorded
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-seat! eng)
        sitter-id (seats/sitter-id seat)
        sid (initialize! h)
        sat (sit! h sid)
        sitting-id (str (:sitting sat))
        meal (:row (inv/create! eng :meal {:name "Soup" :themes []}
                                {:principal person}))]

    ;; one real transition under the seat's leash, so the frozen
    ;; counters have something to freeze (R-10.6)
    (is (false? (:isError (tool h (with-session sid) "waymark_invoke"
                                {:kind "meal" :id (:id meal) :action "accept"}))))

    (let [resp (report! h (assoc counts
                                 :note "Walked one meal; it belonged."
                                 :harness_session "session_01LxQRrdgCKxm6"))
          doc (json resp)]

      (testing "the door answers what the wake cost"
        (is (= 200 (:status resp)))
        (is (= "10" (:waymark doc)))
        (is (= "sitting_close" (:kind doc)))
        (is (= sitting-id (str (:sitting doc))))
        (is (= (str (:id seat)) (str (:seat doc))))
        (is (= "closed" (:state doc)))
        (is (= 12000 (:input_tokens doc)))
        (is (= 3400 (:output_tokens doc)))
        (is (= 90000 (:cache_read_tokens doc)))
        (is (= 1500 (:cache_write_tokens doc)))
        (is (= 7 (:turns doc)))
        (is (= 1 (:transitions doc)) "the meal the sitter accepted")
        (is (= 0 (:refusals doc))))

      (testing "and the cost is the model's prices at this moment (R-10.4)"
        (is (== (seats/cost-of counts prices) (:cost_usd doc))))

      (testing "the row itself is closed, with the counts and the run's id"
        (let [row (row-of eng :sitting sitting-id)
              d (:data row)]
          (is (= :closed (:state row)))
          (is (= 12000 (:input_tokens d)))
          (is (= 3400 (:output_tokens d)))
          (is (= 90000 (:cache_read_tokens d)))
          (is (= 1500 (:cache_write_tokens d)))
          (is (= 7 (:turns d)))
          (is (= "Walked one meal; it belonged." (:note d)))
          (is (= "session_01LxQRrdgCKxm6" (:harness_session d)))
          (is (some? (:ended_at d)) "stamped by the close")
          (is (= "hook" (:closed_by d)) "the hook's close, not the door's")
          (is (== (seats/cost-of counts prices) (:cost_usd d)))
          (is (= 1 (:transitions d)) "frozen")
          (is (= 0 (:refusals d)) "frozen")

          (testing "and the prices it was costed at are copied down"
            (is (== (:input prices) (get-in d [:prices :input])))
            (is (== (:output prices) (get-in d [:prices :output])))
            (is (== (:cache_read prices) (get-in d [:prices :cache_read])))
            (is (== (:cache_write prices) (get-in d [:prices :cache_write]))))

          (testing "the sitting is still the seat's and the model's"
            (is (= (str (:id seat)) (str (:seat d))))
            (is (= (str (:id model)) (str (:model d)))))))

      (testing "the ending is in the log, and the SITTER made it"
        (let [log (store/with-tx (:storage eng)
                    (fn [tx] (store/transitions (:storage eng) tx
                                                {:kind :sitting
                                                 :resource-id sitting-id}
                                                {:limit 10 :newest-first true})))
              closed (first (filter #(= :close (:action %)) log))]
          (is (some? closed) "the close is a real transition, not a patch")
          (is (= sitter-id (str (get-in closed [:actor :id])))
              "the hook acts for the office, so the ledger reads the office"))))))

;; ── 2. the key is the whole credential ──────────────────────────────

(deftest a-key-no-seat-answers-and-no-key-at-all-are-one-sentence
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        sid (initialize! h)
        sat (sit! h sid)]

    (testing "a key that matches nothing"
      (let [resp (report! h {"waymark-seat-key" "c2l0dGluZy1jbG9zZS1ub2JvZHk"}
                          counts)]
        (is (= 404 (:status resp)))
        (is (= "No seat answers this key." (:detail (json resp))))))

    (testing "and no header at all — the same sentence, never a 401"
      (let [resp (report! h {} counts)]
        (is (= 404 (:status resp)))
        (is (= "No seat answers this key." (:detail (json resp)))
            "uniform: the door is never an oracle over the house's offices")))

    (testing "neither touched the open sitting"
      (let [row (row-of eng :sitting (str (:sitting sat)))]
        (is (= :open (:state row)))
        (is (nil? (get-in row [:data :ended_at])))))))

;; ── 3. there is one ending, and the second report is told so ────────

(deftest a-second-report-after-the-close-is-refused-by-name
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        sid (initialize! h)
        sat (sit! h sid)]
    (is (= 200 (:status (report! h counts))))

    (let [resp (report! h counts)]
      (is (= 409 (:status resp)))
      (is (= "The seat `meal-clerk` has no open sitting."
             (:detail (json resp)))))

    (testing "and the closed row was written exactly once"
      (is (= :closed (:state (row-of eng :sitting (str (:sitting sat))))))
      (let [log (store/with-tx (:storage eng)
                  (fn [tx] (store/transitions (:storage eng) tx
                                              {:kind :sitting
                                               :resource-id (str (:sitting sat))}
                                              {:limit 10})))]
        (is (= 1 (count (filter #(= :close (:action %)) log)))
            "there is no second close, and the refusal is why")))))

;; ── 4. a report the door cannot cost ────────────────────────────────

(deftest a-malformed-report-is-refused-naming-the-field
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        sid (initialize! h)
        sat (sit! h sid)
        detail #(str (:detail (json %)))]

    (testing "a negative count"
      (let [resp (report! h (assoc counts :output_tokens -3))]
        (is (= 422 (:status resp)))
        (is (str/includes? (detail resp) "output_tokens"))
        (is (str/includes? (detail resp) "zero or more"))))

    (testing "a missing count"
      (let [resp (report! h (dissoc counts :cache_write_tokens))]
        (is (= 422 (:status resp)))
        (is (str/includes? (detail resp) "cache_write_tokens"))
        (is (str/includes? (detail resp) "required"))))

    (testing "a count that is not a whole number"
      (let [resp (report! h (assoc counts :turns "seven"))]
        (is (= 422 (:status resp)))
        (is (str/includes? (detail resp) "turns"))))

    (testing "a field this report does not carry"
      (let [resp (report! h (assoc counts :thinking_tokens 12))]
        (is (= 422 (:status resp)))
        (is (str/includes? (detail resp) "thinking_tokens"))))

    (testing "a body that is not JSON at all"
      (let [resp (report! h "{not json")]
        (is (= 422 (:status resp)))
        (is (str/includes? (detail resp) "body"))))

    (testing "and nothing was written — the sitting is still open"
      (let [row (row-of eng :sitting (str (:sitting sat)))]
        (is (= :open (:state row)))
        (is (zero? (:input_tokens (:data row))))))))

;; ── 5. the next firing opens a fresh wake ───────────────────────────

(deftest after-the-close-the-next-session-sits-in-a-new-sitting
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat]} (open-seat! eng)
        first-sat (sit! h (initialize! h))
        first-id (str (:sitting first-sat))]
    (is (= 200 (:status (report! h counts))))

    (let [next-sat (sit! h (initialize! h))
          next-id (str (:sitting next-sat))]

      (testing "a fresh session sitting on the same key opens a new sitting"
        (is (not= first-id next-id))
        (is (= :open (:state (row-of eng :sitting next-id))))
        (is (= (:grant first-sat) (:grant next-sat))
            "the standing seat grant is reused; only the sitting is new"))

      (testing "and the closed one is untouched by it"
        (let [row (row-of eng :sitting first-id)]
          (is (= :closed (:state row)))
          (is (= 12000 (get-in row [:data :input_tokens])))
          (is (zero? (get-in (row-of eng :sitting next-id)
                             [:data :input_tokens])))))

      (testing "the seat now holds exactly one open sitting"
        (is (= [next-id] (mapv #(str (:id %)) (open-sittings eng seat))))))))

;; ── 6. the run, named at birth (R-12.15) ────────────────────────────

(deftest a-session-that-names-its-run-is-paired-with-its-sitting
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        sat (sit! h (initialize! h) "session_aaaaaaaaaaaaaaaa")]
    (is (= "session_aaaaaaaaaaaaaaaa"
           (get-in (row-of eng :sitting (str (:sitting sat)))
                   [:data :harness_session]))
        "stamped at birth; the engine cannot derive which run this is")

    (testing "the same session sitting twice is still one sitting"
      (let [again (sit! h (initialize! h) "session_aaaaaaaaaaaaaaaa")]
        (is (= (:sitting sat) (:sitting again))
            "the run's own open sitting is reused, never re-minted")))))

;; ── 7. two runs of one seat at the same hour ────────────────────────

(deftest overlapping-runs-each-close-their-own-sitting
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat]} (open-seat! eng)
        ;; the scheduled firing, and a "Run now" during the same hour
        scheduled (sit! h (initialize! h) "session_scheduled_0001")
        run-now (sit! h (initialize! h) "session_runnow_000002")]

    (testing "a second run does not take over the first's sitting"
      (is (not= (:sitting scheduled) (:sitting run-now)))
      (is (= 2 (count (open-sittings eng seat)))))

    (testing "the report naming the second run closes the second"
      (let [resp (report! h (assoc counts
                                   :harness_session "session_runnow_000002"))]
        (is (= 200 (:status resp)))
        (is (= (str (:sitting run-now)) (str (:sitting (json resp))))))
      (is (= :closed (:state (row-of eng :sitting (str (:sitting run-now))))))
      (is (= :open (:state (row-of eng :sitting (str (:sitting scheduled)))))
          "the scheduled run is still going, and its hook has not fired"))

    (testing "a run that names nothing opens a sitting of its own"
      ;; the only open sitting under the grant is the scheduled run's,
      ;; and it is somebody else's — so this one is fresh, and bare
      (let [bare (sit! h (initialize! h))]
        (is (not= (:sitting scheduled) (:sitting bare)))
        (is (nil? (get-in (row-of eng :sitting (str (:sitting bare)))
                          [:data :harness_session])))

        (testing "and an unnamed report closes it rather than a named one"
          (let [resp (report! h counts)]
            (is (= 200 (:status resp)))
            (is (= (str (:sitting bare)) (str (:sitting (json resp))))))
          (is (= :open (:state (row-of eng :sitting
                                       (str (:sitting scheduled)))))
              "a stamped sitting waits for the hook that knows its name"))))))

;; ── 8. closed_by names the hand, and filters by it ──────────────────

(deftest a-closed-by-filter-answers-only-the-closes-of-that-hand
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-seat! eng)
        sat (sit! h (initialize! h))
        hook-id (str (:sitting sat))
        _ (is (= 200 (:status (report! h counts))))
        ;; a direct `close` invoke, as a person's own door makes it
        door-id (str (:id (:row (inv/create! eng :sitting
                                             {:seat (str (:id seat))
                                              :model (str (:id model))
                                              :grant (str (:grant sat))}
                                             {:principal person}))))
        closed-by #(some-> (row-of eng :sitting %) :data :closed_by name)]
    (inv/invoke! eng :sitting door-id :close counts {:principal person})

    (testing "each close names its own hand"
      (is (= "hook" (closed-by hook-id)))
      (is (= "door" (closed-by door-id)) "a direct close is the door's"))

    (testing "?closed_by=hook answers the hook's close and not the door's"
      (let [resp (h {:request-method :get :uri "/api/sittings"
                     :query-string "closed_by=hook"
                     :headers (bearer {:sub "colton" :name "Colton Kopsa"})})
            ids (mapv #(str (or (:id %) (last (str/split (str (:self %)) #"/"))))
                      (get-in (json resp) [:data :items]))]
        (is (= 200 (:status resp)))
        (is (= [hook-id] ids))))))

;; ── 9. the close judges the sitting: outcome and flags (fad586b7) ───

(def ^:private a-sat
  "The document of a closed sitting that sat, took its turns and moved
  nothing: the base every fixture below is cut from."
  {:closed_by "door" :turns 20 :refusals 0 :cancelled_runs 0
   :served {:waymark_sit {:calls 1 :bytes 4000}}})

(deftest each-outcome-has-one-fixture-and-the-first-match-wins
  (let [t0 (java.time.Instant/parse "2026-10-01T10:00:00Z")
        t1 (.plusSeconds t0 60)
        t2 (.plusSeconds t0 120)
        refused (assoc a-sat :last_refusal
                       {:type "guard-refused" :guard "still-open" :at (str t1)})
        health #(seats/sitting-health %1 %2 [])
        outcome (comp :outcome health)]
    (is (= "submitted" (outcome refused [{:kind :change :action :submit :at t0}]))
        "a submit wins over a refusal that came after it")
    (is (= "stalled" (outcome a-sat [{:kind :change :action :stall :at t0}])))
    (is (= "never_sat" (outcome {:closed_by "missed" :missed true
                                 :turns 0 :served {}}
                                nil))
        "a missed row")
    (is (= "never_sat" (outcome (assoc a-sat :turns 0 :served {}) [])))
    (is (= "refused_out" (outcome refused [{:kind :meal :action :accept :at t0}])))
    (is (= "idle" (outcome refused [{:kind :meal :action :accept :at t2}]))
        "a transition after the refusal means it was not refused out")
    (is (= "cut_short" (outcome (assoc a-sat :closed_by "hook" :turns 9) [])))
    (is (= "idle" (outcome (assoc a-sat :closed_by "hook" :turns 10) [])))
    (is (= "idle" (outcome a-sat [])))
    (is (= "idle" (outcome a-sat [{:kind :meal :action :submit :at t0}]))
        "only a change's submit counts")

    (testing "a refused_out sitting names the law that refused it"
      (is (= ["refused:guard-refused" "refused_by:still-open"]
             (:flags (health refused [])))))))

(deftest the-flags-explain-the-outcome
  (let [flags #(set (:flags (seats/sitting-health %1 [] %2)))
        tests #(assoc-in a-sat [:served :bench__test] {:calls % :bytes 900})
        walked (assoc a-sat :walked_rows ["t1"])
        earlier (fn [& outcomes]
                  (mapv #(hash-map :walked_rows ["t1"] :outcome %) outcomes))]
    (is (= #{} (flags a-sat [])))

    (testing "test_thrash: more than 6 bench.test calls, or a cancelled run"
      (is (contains? (flags (tests 13) []) "test_thrash"))
      (is (not (contains? (flags (tests 6) []) "test_thrash")))
      (is (contains? (flags (assoc a-sat :cancelled_runs 1) []) "test_thrash")))

    (testing "read_heavy: the read bytes, or what was dropped"
      (is (contains? (flags (update a-sat :served assoc
                                    :bench__read {:calls 9 :bytes 100000}
                                    :bench__find {:calls 3 :bytes 50001})
                            [])
                     "read_heavy"))
      (is (not (contains? (flags (assoc-in a-sat [:served :bench__read]
                                           {:calls 9 :bytes 150000})
                                 [])
                          "read_heavy")))
      (is (contains? (flags (assoc-in a-sat [:served :waymark_power]
                                      {:calls 1 :bytes 1000 :dropped 2501})
                            [])
                     "read_heavy")
          "2501 dropped is over half of the 5000 served"))

    (testing "rewalk: two earlier sittings walked the row and did not submit"
      (is (contains? (flags walked (earlier "idle" "stalled")) "rewalk"))
      (is (not (contains? (flags walked (earlier "idle" "submitted")) "rewalk")))
      (is (not (contains? (flags walked (earlier "idle")) "rewalk"))))

    (testing "over_budget and refusals_high"
      (is (contains? (flags (assoc a-sat :cost_usd 3.01M) []) "over_budget"))
      (is (not (contains? (flags (assoc a-sat :cost_usd 3M) []) "over_budget")))
      (is (contains? (flags (assoc a-sat :refusals 5) []) "refusals_high"))
      (is (not (contains? (flags (assoc a-sat :refusals 4) []) "refusals_high"))))))

(deftest a-close-stamps-the-outcome-and-the-query-filters-by-it
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        sid (initialize! h)
        sitting-id (str (:sitting (sit! h sid)))
        meal (:row (inv/create! eng :meal {:name "Soup" :themes []}
                                {:principal person}))
        ids-of (fn [query]
                 (let [resp (h {:request-method :get :uri "/api/sittings"
                                :query-string query
                                :headers (bearer {:sub "colton"
                                                  :name "Colton Kopsa"})})]
                   (is (= 200 (:status resp)) query)
                   (mapv #(str (or (:id %) (last (str/split (str (:self %)) #"/"))))
                         (get-in (json resp) [:data :items]))))]
    (is (false? (:isError (tool h (with-session sid) "waymark_invoke"
                                {:kind "meal" :id (:id meal) :action "accept"}))))
    (is (= 1 (seats/add-cancelled-run! eng sitting-id "r1")))
    (is (= 200 (:status (report! h counts))))

    (testing "a hook's close after seven turns, with no submit, is cut short"
      (let [data (:data (row-of eng :sitting sitting-id))]
        (is (= "cut_short" (some-> (:outcome data) name)))
        (is (= ["test_thrash"] (vec (:flags data))))))

    (testing "the collection filters by the outcome and by a flag"
      (is (= [sitting-id] (ids-of "outcome=cut_short")))
      (is (= [] (ids-of "outcome=idle")))
      (is (= [sitting-id] (ids-of "flags=test_thrash"))))))

(deftest the-backfill-judges-each-unjudged-sitting-once
  (let [eng (fresh-engine)
        {:keys [seat model]} (open-seat! eng)
        now (java.time.Instant/now)
        born! (fn [minutes-ago data]
                (let [started (.minusSeconds now (* 60 (long minutes-ago)))]
                  (store/with-tx (:storage eng)
                    (fn [tx]
                      (str (:id (inv/insert-quiet!
                                 eng tx :sitting
                                 (merge {:seat (str (:id seat))
                                         :model (str (:id model))
                                         :member (seats/sitter-id seat)
                                         :mode seats/default-mode
                                         :started_at started
                                         :ended_at (.plusSeconds started 60)
                                         :input_tokens 0 :output_tokens 0
                                         :cache_read_tokens 0
                                         :cache_write_tokens 0
                                         :turns 12 :transitions 0 :refusals 0
                                         :served {:waymark_sit {:calls 1 :bytes 4000}}
                                         :closed_by "door"}
                                        data)
                                 {:principal seats/seats-actor
                                  :state :closed})))))))
        first-walk (born! 180 {:walked_rows ["t1"]})
        second-walk (born! 120 {:walked_rows ["t1"]})
        third-walk (born! 60 {:walked_rows ["t1"]})
        missed (born! 30 {:closed_by "missed" :missed true :turns 0 :served {}})
        old (born! (* 8 24 60) {:walked_rows ["t1"]})
        health #(let [data (:data (row-of eng :sitting %))]
                  [(some-> (:outcome data) name) (some-> (:flags data) vec)])]
    (is (= 4 (seats/backfill-health! eng)))

    (testing "each sitting of the last week is judged, the oldest first"
      (is (= ["idle" []] (health first-walk)))
      (is (= ["idle" []] (health second-walk)))
      (is (= ["idle" ["rewalk"]] (health third-walk))
          "the third walk of one ticket without a submit")
      (is (= ["never_sat" []] (health missed))))

    (testing "a sitting older than the window is left alone"
      (is (= [nil nil] (health old))))

    (testing "a second pass writes nothing"
      (is (= 0 (seats/backfill-health! eng)))
      (is (= ["idle" ["rewalk"]] (health third-walk))))))

;; ── seat health 2: the seat's own rollup (ticket 64a835b4) ──────────

(def ^:private ten-outcomes
  "Ten sittings, OLDEST first: six submitted, two cut short, one refused
  out and one that never sat."
  ["submitted" "submitted" "cut_short" "submitted" "never_sat"
   "submitted" "submitted" "refused_out" "submitted" "cut_short"])

(deftest a-seats-health-is-counted-over-its-sittings
  (let [at (java.time.Instant/parse "2026-10-01T12:00:00Z")
        sat (fn [hours-ago outcome]
              (let [started (.minusSeconds at (* 3600 (long hours-ago)))]
                {:outcome outcome
                 :cost_usd 0.5M
                 :flags (if (= "refused_out" outcome)
                          ["refusals_high" "refused:conflict"
                           "refused_by:not-parked"]
                          [])
                 :started_at started
                 :ended_at (.plusSeconds started 60)}))
        ;; newest first, as the rollup is handed them
        ten (vec (map-indexed #(sat (inc %1) %2) (reverse ten-outcomes)))
        health (seats/seat-health ten 3 at)]
    (testing "six submits of ten is a rate of 0.6, and each outcome is counted"
      (is (= 10 (:sittings health)))
      (is (= {:submitted 6 :stalled 0 :never_sat 1 :refused_out 1
              :cut_short 2 :idle 0}
             (:outcomes health)))
      (is (== 0.6 (:submit_rate health))))

    (testing "the cost is the sum, and it is divided by the submits and the merges"
      (is (== 5 (:cost_usd health)))
      (is (== 0.833333M (:cost_per_submit health)))
      (is (= 3 (:merged_prs health)))
      (is (== 1.666667M (:cost_per_merge health))))

    (testing "a count for each flag, and a refusal's type is no key"
      (is (= {:refusals_high 1 :refused 1
              (keyword "refused_by:not-parked") 1}
             (:flags health))))

    (testing "the newest sitting and the newest submit are named"
      (is (= "cut_short" (:last_outcome health)))
      (is (= (str (:ended_at (second ten))) (:last_submit_at health)))
      (is (= (str at) (:computed_at health))))

    (testing "a seat with no judged sitting divides by nothing"
      (let [none (seats/seat-health [] 0 at)]
        (is (= 0 (:sittings none)))
        (is (nil? (:submit_rate none)))
        (is (nil? (:cost_per_submit none)))
        (is (nil? (:cost_per_merge none)))
        (is (nil? (:last_outcome none)))))))

(deftest a-close-rolls-the-seats-health-and-the-window-slides
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-seat! eng)
        seat-id (str (:id seat))
        now (java.time.Instant/now)
        born! (fn [minutes-ago outcome]
                (let [started (.minusSeconds now (* 60 (long minutes-ago)))]
                  (store/with-tx (:storage eng)
                    (fn [tx]
                      (inv/insert-quiet!
                       eng tx :sitting
                       {:seat seat-id
                        :model (str (:id model))
                        :member (seats/sitter-id seat)
                        :mode seats/default-mode
                        :started_at started
                        :ended_at (.plusSeconds started 60)
                        :input_tokens 0 :output_tokens 0
                        :cache_read_tokens 0
                        :cache_write_tokens 0
                        :turns 12 :transitions 0 :refusals 0
                        :served {:waymark_sit {:calls 1 :bytes 4000}}
                        :closed_by "door"
                        :cost_usd 0.5M
                        :outcome outcome
                        :flags []}
                       {:principal seats/seats-actor
                        :state :closed})))))
        _ (doall (map-indexed #(born! (- 200 (* 10 (long %1))) %2) ten-outcomes))
        health #(:health (:data (row-of eng :seat seat-id)))
        rolled (seats/roll-health! eng seat-id)]
    (testing "ten sittings, six of them submitted, are a rate of 0.6"
      (is (= 10 (:sittings rolled)))
      (is (== 0.6 (bigdec (:submit_rate rolled))))
      (is (= [6 0 1 1 2 0]
             (mapv #(get-in rolled [:outcomes (keyword %)]) seats/outcomes)))
      (is (== 5 (bigdec (:cost_usd rolled))))
      (is (= 0 (:merged_prs rolled)) "this engine serves no change kind")
      (is (= "cut_short" (:last_outcome rolled))))

    (testing "the seat row carries what was counted"
      (is (= 10 (:sittings (health))))
      (is (== 0.6 (bigdec (:submit_rate (health))))))

    (testing "the eleventh close slides the window: the oldest submit leaves it"
      (let [sid (initialize! h)]
        (sit! h sid)
        (is (= 200 (:status (report! h counts))))
        (let [slid (health)]
          (is (= 10 (:sittings slid)))
          (is (= 5 (get-in slid [:outcomes :submitted])))
          (is (= 3 (get-in slid [:outcomes :cut_short])))
          (is (== 0.5 (bigdec (:submit_rate slid))))
          (is (= "cut_short" (:last_outcome slid))))))

    (testing "one read of the seat collection answers the seat's health"
      (let [resp (h {:request-method :get :uri "/api/seats"
                     :headers (bearer {:sub "colton" :name "Colton Kopsa"})})
            item (first (get-in (json resp) [:data :items]))]
        (is (= 200 (:status resp)))
        (is (= 10 (get-in item [:fields :health :sittings])))))))

;; ── seat health 3: a breach is told (ticket 698f6818) ───────────────

(def ^:private groomers-ticket
  "The groomers' ticket, as much of factory10's as a filing needs
  (judgment_walk_test's own cut)."
  (r/resource
   {:kind :ticket
    :plural "tickets"
    :states [:draft :dropped]
    :initial :draft
    :terminal #{:dropped}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title {:x-display {:label "Title"}} [:string {:min 1 :max 200}]]
             [:detail {:optional true :x-display {:label "Detail"}}
              [:maybe [:string {:max 20000}]]]
             [:type {:x-display {:label "Type"}} [:string {:min 1 :max 20}]]
             [:priority {:x-display {:label "Priority"}} [:int {:min 0 :max 4}]]
             [:repo {:optional true :x-display {:label "Repo"}}
              [:maybe [:string {:max 140}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:drop {:from #{:draft} :to :dropped
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Dropped is dropped."}
            :display {:label "Drop" :order 1
                      :description "Let this ticket go"}}}}))

(defn- ticket-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [fx/meal groomers-ticket]
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

(defn- tickets [eng]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx :ticket {} {:limit 50}))))

(defn- close-as!
  "One sitting of `seat`, born closed and already judged, that started
  `minutes-ago`; then the rollup a close asks for. `extra` is laid over
  the sitting's document. → the health written."
  [eng seat model minutes-ago outcome & [extra]]
  (let [started (.minusSeconds (java.time.Instant/now)
                               (* 60 (long minutes-ago)))]
    (store/with-tx (:storage eng)
      (fn [tx]
        (inv/insert-quiet!
         eng tx :sitting
         (merge {:seat (str (:id seat))
                 :model (str (:id model))
                 :member (seats/sitter-id seat)
                 :mode seats/default-mode
                 :started_at started
                 :ended_at (.plusSeconds started 60)
                 :input_tokens 0 :output_tokens 0
                 :cache_read_tokens 0
                 :cache_write_tokens 0
                 :turns 12 :transitions 0 :refusals 0
                 :served {:waymark_sit {:calls 1 :bytes 4000}}
                 :closed_by "door"
                 :cost_usd 0.5M
                 :outcome outcome
                 :flags []}
                extra)
         {:principal seats/seats-actor
          :state :closed})))
    (seats/roll-health! eng (str (:id seat)))))

(deftest the-alert-rules-are-judged-over-the-window
  (let [at (java.time.Instant/parse "2026-10-01T12:00:00Z")
        sat (fn [outcome & flags] {:outcome outcome :flags (vec flags)})
        ;; newest first, as the rollup is handed them
        breach (fn [alerts sittings]
                 (seats/health-breach alerts
                                      (seats/seat-health sittings 0 at)
                                      sittings))]
    (testing "a run is counted from the newest sitting back"
      (is (nil? (breach nil [(sat "cut_short") (sat "cut_short")])))
      (is (= "cut_short_run"
             (breach nil [(sat "cut_short") (sat "cut_short") (sat "cut_short")])))
      (is (nil? (breach nil [(sat "submitted") (sat "cut_short") (sat "cut_short")
                             (sat "cut_short")]))
          "one submit ends the run"))

    (testing "a flag that rides enough sittings in a row is a breach"
      (is (= "flag_run:rewalk"
             (breach nil [(sat "submitted" "rewalk") (sat "idle" "rewalk")])))
      (is (nil? (breach nil [(sat "submitted" "test_thrash")
                             (sat "submitted" "test_thrash")])))
      (is (= "flag_run:test_thrash"
             (breach nil (repeat 3 (sat "submitted" "test_thrash"))))))

    (testing "the submit rate is judged only once the window holds six"
      (let [third [(sat "submitted") (sat "idle") (sat "idle")]]
        (is (nil? (breach nil (vec (take 5 (cycle third))))))
        (is (= "submit_rate_below" (breach nil (vec (take 6 (cycle third))))))))

    (testing "a seat states its own rules, and the rest keep their defaults"
      (is (= "refused_out_run" (breach {:refused_out_run 1} [(sat "refused_out")])))
      (is (nil? (breach {:never_sat_any false} [(sat "never_sat")])))
      (is (= "never_sat_any" (breach {:cut_short_run 5} [(sat "never_sat")]))))))

(deftest two-refused-out-closes-file-one-ticket-and-a-third-files-none
  (let [eng (ticket-engine)
        {:keys [seat model]} (open-seat! eng)
        seat-id (str (:id seat))
        breach #(get-in (row-of eng :seat seat-id) [:data :health :breach])]
    (testing "one refused_out close is no run yet"
      (close-as! eng seat model 40 "refused_out")
      (is (nil? (breach)))
      (is (empty? (tickets eng))))

    (testing "the second files one draft ticket, and the seat row names it"
      (close-as! eng seat model 30 "refused_out")
      (let [[ticket :as filed] (tickets eng)]
        (is (= 1 (count filed)))
        (is (= "draft" (name (:state ticket))))
        (is (= "seat meal-clerk health breach: refused_out_run"
               (get-in ticket [:data :title])))
        (is (= 1 (get-in ticket [:data :priority])))
        (is (= "ckopsa/waymark" (get-in ticket [:data :repo])))
        (is (str/includes? (get-in ticket [:data :detail]) "refused_out"))
        (is (str/includes? (get-in ticket [:data :detail])
                           (str "/api/seats/" seat-id)))
        (is (= "refused_out_run" (:rule (breach))))
        (is (= (str (:id ticket)) (:ticket (breach))))))

    (testing "the third is the same breach, and files none"
      (close-as! eng seat model 20 "refused_out")
      (is (= 1 (count (tickets eng))))
      (is (= "refused_out_run" (:rule (breach)))))

    (testing "a seat that asked for no breaker has none"
      (is (nil? (get-in (row-of eng :seat seat-id) [:data :breaker_open]))))

    (testing "a window that recovers clears the breach and files nothing"
      (close-as! eng seat model 10 "submitted")
      (is (nil? (breach)))
      (is (= 1 (count (tickets eng)))))))

(deftest a-never-sat-close-files-a-ticket
  (let [eng (ticket-engine)
        {:keys [seat model]} (open-seat! eng)
        rolled (close-as! eng seat model 10 "never_sat")
        [ticket :as filed] (tickets eng)]
    (is (= 1 (count filed)))
    (is (= "seat meal-clerk health breach: never_sat_any"
           (get-in ticket [:data :title])))
    (is (= (str (:id ticket)) (get-in rolled [:breach :ticket])))))

(deftest an-open-breaker-caps-the-wakes-to-one-sitting-until-it-is-closed
  (let [eng (ticket-engine)
        {:keys [model]} (open-seat! eng)
        seat (:row (inv/create!
                    eng :seat
                    {:name "breaker-clerk"
                     :charter "Decide whether a meal belongs on the list."
                     :scope [{:kind "meal" :actions ["accept"]}]
                     :held_for [(:id model)]
                     :standing_ttl_seconds 604800
                     :cadence_seconds 3600
                     :budget_usd_per_week 5M
                     :sitting_budget_tokens 60000
                     :max_open_sittings 3
                     :health_alerts {:cut_short_run 1}
                     :health_breaker true}
                    {:principal person}))
        seat-id (str (:id seat))
        stored #(row-of eng :seat seat-id)]
    (testing "a healthy seat wakes as many sittings as it states"
      (close-as! eng seat model 30 "submitted")
      (is (nil? (get-in (stored) [:data :breaker_open])))
      (is (= 3 (wakes/max-open-of (stored)))))

    (testing "a breach opens the breaker, and the stated number stands"
      (close-as! eng seat model 20 "cut_short")
      (is (true? (get-in (stored) [:data :breaker_open])))
      (is (= 3 (get-in (stored) [:data :max_open_sittings])))
      (is (= 1 (wakes/max-open-of (stored))))
      (is (str/includes? (get-in (first (tickets eng)) [:data :detail])
                         "breaker_open")))

    (testing "close_breaker restores the wakes"
      (inv/invoke! eng :seat seat-id :close_breaker {} {:principal person})
      (is (nil? (get-in (stored) [:data :breaker_open])))
      (is (= 3 (wakes/max-open-of (stored)))))

    (testing "the same breach at the next close does not open it again"
      (close-as! eng seat model 10 "cut_short")
      (is (nil? (get-in (stored) [:data :breaker_open])))
      (is (= 1 (count (tickets eng)))))))

;; ── seat health 4: rows wait outside the grant (ticket fd930ff1) ────
;;
;; A seat whose walk comes up empty while rows of the kind it walks wait
;; outside its scope entry's filter is told nothing of them, and its
;; person is: the rule is on the seat row, the count in the ticket.

(def ^:private post
  "mcp_sit_test's queue, as much of it as a walk needs: it filters its
  own queue by state, and `box` is filterable, so a scope entry narrows
  a seat to one box and a post in another is outside its grant."
  (r/resource
   {:kind :post
    :plural "posts"
    :states [:queued :filed]
    :initial :queued
    :terminal #{:filed}
    :summary "{data.subject} · {state}"
    :schema
    [:map
     [:subject {:x-display {:label "What it is about"}}
      [:string {:min 1 :max 120}]]
     [:box {:x-display {:label "Which box"}} [:string {:min 1 :max 40}]]
     [:received_at {:x-display {:label "When it arrived"}} :waymark/instant]]
    :filterable {:state #{:eq :in} :box #{:eq}}
    :default-filters {:state "queued"}
    :sortable {:fields [:received_at] :default "received_at"}
    :actions
    {:file {:from #{:queued} :to :filed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A filed post keeps its history."}}}}))

(defn- post-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [fx/meal groomers-ticket post]
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

(defn- open-post-seat!
  "A seat that walks the post queue under its scope entry's filter: the
  house's own box and no other. Its key is offered."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "close-test-model" :display "Close 1"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok (:input prices)
                                  :price_output_per_mtok (:output prices)
                                  :price_cache_read_per_mtok (:cache_read prices)
                                  :price_cache_write_per_mtok (:cache_write prices)}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    {:name "post-clerk"
                     :charter "Read each post and file it."
                     :scope [{:kind "post" :actions ["file"]
                              :filter {:box "house"}}]
                     :walk "post"
                     :held_for [(:id model)]
                     :standing_ttl_seconds 604800
                     :cadence_seconds 3600
                     :budget_usd_per_week 5M
                     :sitting_budget_tokens 60000}
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    {:seat seat :model model}))

(defn- post! [eng subject box]
  (:row (inv/create! eng :post {:subject subject :box box
                                :received_at "2026-09-18T07:00:00Z"}
                     {:principal person})))

(deftest the-walked-nothing-rule-wants-the-run-and-the-hidden-rows
  (let [at (java.time.Instant/parse "2026-10-01T12:00:00Z")
        nothing (fn [outcome] {:outcome outcome :flags [] :walked_nothing true})
        breach (fn [sittings hidden]
                 (seats/health-breach nil
                                      (seats/seat-health sittings 0 at)
                                      sittings
                                      hidden))]
    (is (= "walked_nothing_run" (breach (vec (repeat 3 (nothing "idle"))) 5)))
    (is (nil? (breach (vec (repeat 3 (nothing "idle"))) 0))
        "a queue that hides no row is only an empty queue")
    (is (nil? (breach (vec (repeat 2 (nothing "idle"))) 5))
        "two are no run yet")
    (is (= "walked_nothing_run"
           (breach [(nothing "cut_short") (nothing "idle") (nothing "cut_short")] 1))
        "a hook closes such a wake within a few turns")
    (is (nil? (breach [(nothing "idle") (nothing "idle") {:outcome "idle" :flags []}]
                      5))
        "an idle sitting that was handed rows is not of the run")))

(deftest a-seat-that-walks-nothing-beside-hidden-rows-breaches-and-its-person-is-told
  (let [eng (post-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-post-seat! eng)
        seat-id (str (:id seat))
        breach #(get-in (row-of eng :seat seat-id) [:data :health :breach])
        hidden-ids (mapv #(str (:id (post! eng (str "Street post " %) "street")))
                         (range 5))
        nothing! #(close-as! eng seat model % "idle" {:walked_nothing true})]

    (testing "the sitting's own answers carry no trace of the rows its grant hides"
      (let [sat (sit! h (initialize! h))
            sitting (row-of eng :sitting (:sitting sat))]
        (is (empty? (get-in sat [:walk :rows])))
        (is (true? (get-in sitting [:data :walked_nothing])))
        (is (= "The queue held no rows under the walk's filter and the seat's grant."
               (get-in sitting [:data :walked_nothing_why])))
        (is (not-any? #(str/includes? (pr-str sat) %) hidden-ids))
        (is (not (str/includes? (pr-str sat) "outside its grant")))))

    (testing "two wakes that walked nothing are no run yet"
      (nothing! 40)
      (nothing! 30)
      (is (nil? (breach)))
      (is (empty? (tickets eng))))

    (testing "the third is a breach, and the ticket tells the count and the kind"
      (nothing! 20)
      (let [[ticket :as filed] (tickets eng)
            detail (str (get-in ticket [:data :detail]))]
        (is (= "walked_nothing_run" (:rule (breach))))
        (is (= 1 (count filed)))
        (is (= "seat post-clerk health breach: walked_nothing_run"
               (get-in ticket [:data :title])))
        (is (str/starts-with?
             detail
             "post-clerk walked nothing 3 times while 5 queued posts sat outside its grant (box filter or scope)."))
        (is (not-any? #(str/includes? detail %) hidden-ids)
            "no row ids, only the count and the kind")
        (is (= (str (:id ticket)) (:ticket (breach))))))

    (testing "the seat row names the rule, and neither the count nor the rows"
      (is (= #{:rule :at :ticket} (set (keys (breach))))))

    (testing "a wake that submits ends the run and clears the breach"
      (close-as! eng seat model 10 "submitted")
      (is (nil? (breach)))
      (is (= 1 (count (tickets eng)))))))

(deftest a-seat-that-walks-nothing-with-no-hidden-rows-raises-no-breach
  (let [eng (post-engine)
        {:keys [seat model]} (open-post-seat! eng)
        seat-id (str (:id seat))
        filed (post! eng "Street post" "street")]
    ;; a post that left the queue waits for nobody, in whichever box
    (inv/invoke! eng :post (str (:id filed)) :file nil {:principal person})
    (doseq [minutes-ago [40 30 20]]
      (close-as! eng seat model minutes-ago "idle" {:walked_nothing true}))
    (is (nil? (get-in (row-of eng :seat seat-id) [:data :health :breach])))
    (is (empty? (tickets eng)))))

;; ── the key check door (docs/spec-seat.md § 16) ─────────────────────

(def ^:private verify-uri "/api/-/seats/verify")

(def ^:private a-secret "inbox-signing-secret-01")

(defn- subscribe!
  "The calling service's subscription, with the secret it proves
  itself by."
  [eng]
  (:row (inv/create! eng :subscription
                     {:url "https://inbox.test/events" :secret a-secret}
                     {:principal person})))

(defn- verify!
  "The service's POST: its own secret in the header, the seat key it
  was handed in the body."
  ([h key] (verify! h {"waymark-subscription-secret" a-secret} key))
  ([h headers key]
   (h {:request-method :post :uri verify-uri :headers headers
       :body (wire/write-json {:key key})})))

(deftest a-service-asks-whether-a-seat-key-is-live-and-nothing-opens
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat]} (open-seat! eng)
        _ (subscribe! eng)
        resp (verify! h a-key)]
    (testing "a live key answers the seat's name and nothing else"
      (is (= 200 (:status resp)))
      (is (= {:live true :seat "meal-clerk"} (json resp))))
    (testing "and the ask opened no sitting"
      (is (empty? (open-sittings eng seat))))))

(deftest an-unknown-key-and-a-revoked-key-answer-alike
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat]} (open-seat! eng)
        _ (subscribe! eng)
        unknown (verify! h "c2l0dGluZy1jbG9zZS1ub2JvZHk")]
    (is (= 200 (:status unknown)))
    (is (= {:live false :seat nil} (json unknown)))
    (inv/invoke! eng :seat (:id seat) :revoke_key nil {:principal person})
    (let [revoked (verify! h a-key)]
      (is (= 200 (:status revoked)))
      (is (= (:body unknown) (:body revoked))
          "uniform: a revoked key reads as a key nobody ever held"))))

(deftest the-key-check-answers-only-a-service-it-knows
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        sub (subscribe! eng)]
    (testing "no secret, and a secret no subscription holds"
      (doseq [headers [{} {"waymark-subscription-secret" "not-the-secret-01"}]]
        (let [resp (verify! h headers a-key)]
          (is (= 401 (:status resp)))
          (is (= "No active subscription answers this secret."
                 (:detail (json resp)))))))
    (testing "a key is required"
      (is (= 422 (:status (verify! h "")))))
    (testing "a paused subscription is not a caller"
      (inv/invoke! eng :subscription (:id sub) :pause nil {:principal person})
      (is (= 401 (:status (verify! h a-key)))))))

(deftest the-key-check-knows-a-service-by-the-secret-row-it-names
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        {sec :row} (inv/create! eng :secret {:name "INBOX_TEST_SECRET"}
                                {:principal person})
        ref (str (:id sec))
        {sub :row} (inv/create! eng :subscription
                                {:url "https://inbox.test/events" :secret ref}
                                {:principal person})
        value "inbox-row-value-01"
        ask (fn [secret]
              (verify! h {"waymark-subscription-secret" secret} a-key))]
    (testing "a row that holds no value matches nothing"
      (is (= 401 (:status (ask ref))))
      (is (= 401 (:status (ask value)))))
    (inv/invoke! eng :secret ref :replace {:value value}
                 {:principal person
                  :if-match (inv/etag :secret ref (:version sec))})
    (testing "the row's value is the service's secret"
      (let [resp (ask value)]
        (is (= 200 (:status resp)))
        (is (= {:live true :seat "meal-clerk"} (json resp)))))
    (testing "the reference is not the secret"
      (is (= 401 (:status (ask ref)))))
    (testing "a literal subscription beside it answers as before"
      (subscribe! eng)
      (is (= 200 (:status (verify! h a-key))))
      (is (= 200 (:status (ask value)))))
    (testing "the subscription keeps the reference, never the value"
      (is (= ref (get-in (row-of eng :subscription (str (:id sub)))
                         [:data :secret]))))))

(deftest the-key-check-is-rate-limited-per-caller
  (let [;; one clock minute, whatever the wall clock does
        eng (assoc (fresh-engine)
                   :now-fn (constantly (java.time.Instant/parse
                                        "2026-10-03T12:00:30Z")))
        h (engine/handler eng)
        _ (open-seat! eng)
        _ (subscribe! eng)]
    (with-redefs [seat-routes/verify-per-minute 2]
      (let [answers (mapv (fn [_] (verify! h a-key)) (range 3))]
        (is (= [200 200 429] (mapv :status answers)))
        (is (= "30" (get-in (peek answers) [:headers "Retry-After"])))))))
