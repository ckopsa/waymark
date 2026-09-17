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
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
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
