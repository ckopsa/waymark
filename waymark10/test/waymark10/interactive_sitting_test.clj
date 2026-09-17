(ns waymark10.interactive-sitting-test
  "The interactive sitting (docs/spec-seat.md R-10.8, § 12.3).

  THE SITTING DOES NOT CHANGE; THE WAIT DOES. A fired run is one
  prompt: it walks the queue, its Stop hook closes the sitting, and
  the bill is known the moment the run ends. A person's own session in
  the seat waits between turns — for hours — and the close comes when
  the person says so. Everything in this file falls out of that one
  difference:

  - R-10.8 · the MODE IS THE SEAT'S. A seat is `fired` or
    `interactive`, the sitting inherits it at birth and records the
    person behind the delegate, and a Routine's run — an agent with
    nobody behind it — is refused at an interactive seat by name.
    Nothing fires such a seat: its `fire` door refuses, the schedules
    consumer mints no row for it, and the wake consumer passes it by.
  - R-12.25 · the TALLY. The Stop hook of an interactive session
    raises one event per turn, so it posts the counts onto the OPEN
    sitting instead of closing it. Cumulative, so a replay is
    harmless, and the stamp it leaves is what the sweep reads: a
    sitting untallied for the seat's `sitting_idle_seconds` is closed
    with the last tally as its bill, and one that never tallied at all
    is abandoned — the absence of a bill, R-7.6's own posture.
  - R-12.27 · the RUNNING COST. Each tally is priced, so the week's
    wall can see a sitting that has not ended, and a sitting past its
    seat's `sitting_budget_tokens` meets a wall of its own.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: no database, no network. The shape is
  sitting_close_test's, whose helpers this file re-spells for the same
  reason it re-spelled mcp_sit_test's — they are private there by
  design. The clock is an ATOM the tests move, because idleness is a
  duration and nothing commits when a duration ends."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.schema :as schema]
            [waymark10.server.definitions :as defs]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.routes.seats :as rseats]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.wakes :as wakes]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)
           (java.time Instant)))

;; ── the connector door, as sitting_close_test stands it up ──────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "chair-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "chair-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "chair-key"}}))

(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- bearer [claims] {"authorization" (str "Bearer " (mint claims))})

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

(defn- clock
  "A movable now. Every engine in this file reads it, so a test that
  has to be an hour later says so instead of sleeping."
  []
  (atom (Instant/parse "2026-09-17T09:00:00Z")))

(defn- fresh-engine
  ([] (fresh-engine (clock)))
  ([at]
   (engine/engine {:storage (memory/storage)
                   :resources [fx/meal]
                   :now-fn (fn [] @at)
                   ;; keyed by KEYWORD: `adapter-for` reads the row's
                   ;; provider string as a keyword, the way every other
                   ;; suite wires its fake; a string key here is an
                   ;; adapter the consumer never finds
                   :schedule-adapters {:claude_routine
                                       (schedules/fake-scheduler)}
                   :oidc {:issuer issuer :audience audience :jwks jwks
                          :app-url "https://app.test/"
                          :delegate-clients {"connector" "Claude"}}})))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- tool [h headers tool-name args]
  (let [resp (rpc h headers "tools/call" {:name tool-name :arguments args})]
    (assoc (get-in (json resp) [:result]) :status (:status resp))))

(defn- text-of [result] (str (get-in result [:content 0 :text])))
(defn- doc-of [result] (wire/read-json (text-of result)))

(defn- initialize! [h]
  (get-in (rpc h (bearer colton) "initialize"
               {:protocolVersion mcp/protocol-version
                :capabilities {} :clientInfo {:name "chair" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

;; ── the office ──────────────────────────────────────────────────────

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "aW50ZXJhY3RpdmUtc2l0dGluZy1rZXktMDE")

(def ^:private other-key
  "A second seat's key, for the deftests that open two."
  "aW50ZXJhY3RpdmUtc2l0dGluZy1rZXktMDI")

(def ^:private prices
  {:input 3M :output 15M :cache_read 0.3M :cache_write 3.75M})

(defn- add-model! [eng]
  (:row (inv/create! eng :model
                     {:name "chair-test-model" :display "Chair 1"
                      :vendor "anthropic" :tier "strong"
                      :price_input_per_mtok (:input prices)
                      :price_output_per_mtok (:output prices)
                      :price_cache_read_per_mtok (:cache_read prices)
                      :price_cache_write_per_mtok (:cache_write prices)}
                     {:principal person})))

(defn- seat-body [name' model extra]
  (merge {:name name'
          :charter "Decide whether a meal belongs on the list."
          :scope [{:kind "meal" :actions ["accept"]}]
          :held_for [(:id model)]
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 5M
          :sitting_budget_tokens 60000}
         extra))

(defn- open-seat!
  "A seat with its key offered — everything a person's shell would
  already have. `extra` carries the mode and whatever else the test
  is about; NO schedule is minted here, because an interactive seat
  has none and a fired one gets its own where that is the point."
  ([eng model] (open-seat! eng model {}))
  ([eng model extra]
   (let [seat (:row (inv/create! eng :seat
                                 (seat-body (str "chair-"
                                                 (or (:name extra) "one"))
                                            model (dissoc extra :name :key))
                                 {:principal person}))]
     ;; one key per seat, and the second seat of a test gets its own:
     ;; `seat-by-key` answers the seat that holds the key, and two
     ;; seats holding one key would be a test asking it to guess
     (inv/invoke! eng :seat (:id seat) :offer_key
                  {:key (or (:key extra) a-key)}
                  {:principal person})
     seat)))

(defn- meal!
  "One fresh row for the sitter to act on. FRESH every time on
  purpose: a second `accept` on an accepted meal would answer 409, and
  a 409 standing in for a wall is a test that passes for the wrong
  reason."
  [eng name']
  (:row (inv/create! eng :meal {:name name' :themes []}
                     {:principal person})))

(defn- row-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- refusal
  "The problem ex-data of a write that was refused, or nil."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

;; ── the two doors a hook posts to ───────────────────────────────────

(def ^:private counts
  "One turn's usage, as a Stop hook sums it off a transcript."
  {:input_tokens 12000 :output_tokens 3400
   :cache_read_tokens 90000 :cache_write_tokens 1500 :turns 7})

(defn- post!
  ([h uri body] (post! h uri {"waymark-seat-key" a-key} body))
  ([h uri headers body]
   (h {:request-method :post :uri uri :headers headers
       :body (if (string? body) body (wire/write-json body))})))

(defn- tally! [h body] (post! h "/api/-/sittings/tally" body))
(defn- close! [h body] (post! h "/api/-/sittings/close" body))

;; ── 1 · the mode is the seat's (R-10.8) ─────────────────────────────

(deftest a-seat-says-who-sits-in-it-and-fired-is-the-default
  (let [eng (fresh-engine)
        model (add-model! eng)
        quiet (open-seat! eng model {:name "fired" :key other-key})
        chair (open-seat! eng model {:name "chair" :mode "interactive"})]

    (testing "a seat that says nothing is the seat that was always there"
      (is (= "fired" (get-in (row-of eng :seat (:id quiet)) [:data :mode])))
      (is (false? (seats/interactive-seat? (row-of eng :seat (:id quiet))))))

    (testing "and one opened for a person says so"
      (is (= "interactive" (get-in (row-of eng :seat (:id chair)) [:data :mode])))
      (is (true? (seats/interactive-seat? (row-of eng :seat (:id chair))))))

    (testing "the idle limit is the seat's too, and an hour by default"
      (is (= 3600 (get-in (row-of eng :seat (:id chair))
                          [:data :sitting_idle_seconds]))))

    (testing "both fields are restatable, and prefilled so a form carries them"
      (let [rdef (get (inv/resources eng) :seat)
            restate (get-in rdef [:actions :restate])]
        (is (contains? (set (schema/entry-keys (:input restate))) :mode))
        (is (contains? (set (schema/entry-keys (:input restate)))
                       :sitting_idle_seconds))
        (is (contains? (set (get-in restate [:edit :prefill])) :mode))
        (is (contains? (set (get-in restate [:edit :prefill]))
                       :sitting_idle_seconds))))))

;; ── 2 · a Routine's run does not sit in the chair ───────────────────

(deftest an-interactive-seat-refuses-a-run-with-nobody-behind-it
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        chair (open-seat! eng model {:name "chair" :mode "interactive"})
        sid (initialize! h)]

    (testing "a bare agent holding the key is told what kind of seat this is"
      (let [r (tool h {"x-waymark-principal" "lone-agent"
                       "x-waymark-actor-type" "agent"
                       "mcp-session-id" sid}
                    "waymark_sit" {:key a-key})]
        (is (true? (:isError r)))
        (is (= (str "The seat `" (get-in chair [:data :name])
                    "` is an interactive seat. A person sits here.")
               (text-of r))
            "R-10.8's own sentence, and it names the seat the key opened")))

    (testing "the person's own session sits, and the answer carries the mode"
      (let [sat (tool h (with-session sid) "waymark_sit" {:key a-key})
            answer (doc-of sat)]
        (is (false? (:isError sat)) (text-of sat))
        (is (= "interactive" (:mode answer))
            "the hook learns the mode here and nowhere else")
        (is (string? (:sitting answer)))))

    (testing "a FIRED seat still admits both, exactly as it did"
      (let [eng2 (fresh-engine)
            h2 (engine/handler eng2)
            model2 (add-model! eng2)
            _ (open-seat! eng2 model2 {:name "fired"})
            sid2 (initialize! h2)
            r (tool h2 {"x-waymark-principal" "lone-agent"
                        "x-waymark-actor-type" "agent"
                        "mcp-session-id" sid2}
                    "waymark_sit" {:key a-key})]
        (is (true? (:isError r)))
        (is (str/includes? (text-of r) "A seat key binds a person's tool")
            "the general sentence, unchanged — a bare agent is still not a tool")
        (is (false? (:isError (tool h2 (with-session sid2) "waymark_sit"
                                    {:key a-key}))))))))

;; ── 3 · the sitting inherits the mode and records the person ────────

(deftest the-sitting-carries-the-seats-mode-and-the-person-in-the-chair
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        chair (open-seat! eng model {:name "chair" :mode "interactive"})
        sat (doc-of (tool h (with-session (initialize! h)) "waymark_sit"
                          {:key a-key}))
        row (row-of eng :sitting (str (:sitting sat)))]
    (is (= "interactive" (get-in row [:data :mode])))
    (is (= "colton" (get-in row [:data :person]))
        "the delegate's acts-for member, stamped by the engine")
    (is (= (str (:id chair)) (str (get-in row [:data :seat]))))
    (is (nil? (get-in row [:data :tallied_at])) "nothing tallied yet")

    (testing "and a FIRED seat's sitting says fired, with no person when a
              Routine's run opened it"
      (let [eng2 (fresh-engine)
            h2 (engine/handler eng2)
            model2 (add-model! eng2)
            _ (open-seat! eng2 model2 {:name "fired"})
            sat2 (doc-of (tool h2 (with-session (initialize! h2)) "waymark_sit"
                               {:key a-key}))]
        (is (= "fired" (:mode sat2)))
        (is (= "fired" (get-in (row-of eng2 :sitting (str (:sitting sat2)))
                               [:data :mode])))))))

;; ── 4 · the tally (R-12.25) ─────────────────────────────────────────

(deftest a-tally-writes-the-counts-the-stamp-and-the-running-cost
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        model (add-model! eng)
        chair (open-seat! eng model {:name "chair" :mode "interactive"})
        sat (doc-of (tool h (with-session (initialize! h)) "waymark_sit"
                          {:key a-key}))
        sitting-id (str (:sitting sat))
        resp (tally! h (assoc counts :note "Two corrections so far."))
        doc (json resp)]

    (testing "the door answers what the sitting has spent, still open"
      (is (= 200 (:status resp)))
      (is (= "10" (:waymark doc)))
      (is (= "sitting_tally" (:kind doc)))
      (is (= sitting-id (str (:sitting doc))))
      (is (= (str (:id chair)) (str (:seat doc))))
      (is (= "open" (:state doc)) "the whole point: the sitting goes on")
      (is (= 12000 (:input_tokens doc)))
      (is (= 7 (:turns doc)))
      (is (== (seats/cost-of counts prices) (:cost_usd doc)))
      (is (some? (:tallied_at doc))))

    (testing "the row carries the counts, the stamp and the running cost"
      (let [d (:data (row-of eng :sitting sitting-id))]
        (is (= 12000 (:input_tokens d)))
        (is (= 3400 (:output_tokens d)))
        (is (= 90000 (:cache_read_tokens d)))
        (is (= 1500 (:cache_write_tokens d)))
        (is (= 7 (:turns d)))
        (is (= "Two corrections so far." (:note d)))
        (is (== (seats/cost-of counts prices) (:cost_usd d)))
        (is (some? (:tallied_at d)))
        (is (nil? (:ended_at d)) "a tally does not end anything")
        (is (nil? (:prices d))
            "the prices are copied down beside the BILL, and the bill is
             the close's")))

    (testing "a second tally REPLACES the first — cumulative, never added"
      (reset! at (Instant/parse "2026-09-17T09:30:00Z"))
      (let [later {:input_tokens 30000 :output_tokens 9000
                   :cache_read_tokens 200000 :cache_write_tokens 4000
                   :turns 19}
            doc2 (json (tally! h later))
            d (:data (row-of eng :sitting sitting-id))]
        (is (= 30000 (:input_tokens d)))
        (is (= 19 (:turns d)))
        (is (== (seats/cost-of later prices) (:cost_usd doc2)))
        (is (= "2026-09-17T09:30:00Z" (str (:tallied_at d))))
        (is (= :open (:state (row-of eng :sitting sitting-id))))))

    (testing "and a replay of the same numbers writes the same numbers"
      (let [before (:data (row-of eng :sitting sitting-id))
            _ (tally! h (assoc counts :note "Two corrections so far."))
            after (:data (row-of eng :sitting sitting-id))]
        (is (= 12000 (:input_tokens after)))
        (is (not= (:input_tokens before) (:input_tokens after))
            "the newest report wins, which is what makes a replay harmless")))

    (testing "the close still ends it, and freezes the prices"
      (let [doc (json (close! h (assoc counts :note "Closed by the person.")))]
        (is (= "sitting_close" (:kind doc)))
        (is (= "closed" (:state doc)))
        (is (some? (get-in (row-of eng :sitting sitting-id) [:data :prices])))))))

;; ── 5 · the tally door's four answers ───────────────────────────────

(deftest the-tally-door-refuses-what-the-close-door-refuses
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model {:name "chair" :mode "interactive"})
        sat (doc-of (tool h (with-session (initialize! h)) "waymark_sit"
                          {:key a-key}))
        detail #(str (:detail (json %)))]

    (testing "a key no seat answers, and no key at all — one sentence, 404"
      (is (= 404 (:status (post! h "/api/-/sittings/tally"
                                 {"waymark-seat-key" "bm9ib2R5LWhvbGRzLXRoaXM"}
                                 counts))))
      (let [resp (post! h "/api/-/sittings/tally" {} counts)]
        (is (= 404 (:status resp)))
        (is (= "No seat answers this key." (detail resp)))))

    (testing "a report the door cannot cost — 422, naming the field"
      (let [resp (tally! h (assoc counts :output_tokens -3))]
        (is (= 422 (:status resp)))
        (is (str/includes? (detail resp) "output_tokens")))
      (let [resp (tally! h (dissoc counts :turns))]
        (is (= 422 (:status resp)))
        (is (str/includes? (detail resp) "turns")))
      (let [resp (tally! h (assoc counts :thinking_tokens 9))]
        (is (= 422 (:status resp)))
        (is (str/includes? (detail resp) "thinking_tokens"))))

    (testing "nothing was written by any of them"
      (is (zero? (get-in (row-of eng :sitting (str (:sitting sat)))
                         [:data :input_tokens]))))

    (testing "and a tally after the close is told the sitting is over"
      (is (= 200 (:status (close! h counts))))
      (let [resp (tally! h counts)]
        (is (= 409 (:status resp)))
        (is (str/includes? (detail resp) "has no open sitting"))))))

;; ── 6 · the running cost the week's wall can see (R-12.27) ──────────

(deftest the-week-counts-an-open-sittings-running-cost-and-the-wall-drops
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        model (add-model! eng)
        chair (open-seat! eng model {:name "chair"
                                     :mode "interactive"
                                     ;; pennies, so one honest tally
                                     ;; spends the week
                                     :budget_usd_per_week 0.50M
                                     :sitting_budget_tokens 10000000})
        sid (initialize! h)
        sat (doc-of (tool h (with-session sid) "waymark_sit" {:key a-key}))]

    (testing "before the tally the seat has spent nothing and works"
      (is (zero? (compare 0M (:spent (rseats/budget-of
                                      eng (row-of eng :seat (:id chair))
                                      @at)))))
      (is (false? (:isError (tool h (with-session sid) "waymark_invoke"
                                  {:kind "meal" :id (:id (meal! eng "Soup"))
                                   :action "accept"})))))

    (testing "a tally puts the running cost inside the window"
      (let [big {:input_tokens 200000 :output_tokens 30000
                 :cache_read_tokens 500000 :cache_write_tokens 20000
                 :turns 40}]
        (is (= 200 (:status (tally! h big))))
        (is (pos? (compare (:spent (rseats/budget-of
                                    eng (row-of eng :seat (:id chair)) @at))
                           0.50M))
            "the sitting has not ended, and the week can see it")))

    (testing "and the next request meets the wall, one turn late at most"
      (let [r (tool h (with-session sid) "waymark_invoke"
                    {:kind "meal" :id (:id (meal! eng "Stew")) :action "accept"})]
        (is (true? (:isError r))))
      (is (= "budget_reached"
             (get-in (row-of eng :seat (:id chair)) [:data :halt :reason]))
          "the router wrote the wall it met (R-7.7)"))

    (testing "the close is still served — a walled seat records what it spent"
      (is (= 200 (:status (close! h counts))))
      (is (= :closed (:state (row-of eng :sitting (str (:sitting sat)))))))))

;; ── 7 · a sitting past its own ceiling ──────────────────────────────

(deftest a-sitting-past-its-token-ceiling-meets-a-wall-of-its-own
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        model (add-model! eng)
        chair (open-seat! eng model {:name "chair"
                                     :mode "interactive"
                                     :budget_usd_per_week 1000M
                                     :sitting_budget_tokens 20000})
        sid (initialize! h)
        _ (doc-of (tool h (with-session sid) "waymark_sit" {:key a-key}))]

    (testing "under the ceiling the sitter works"
      (is (= 200 (:status (tally! h {:input_tokens 1000 :output_tokens 200
                                     :cache_read_tokens 3000
                                     :cache_write_tokens 100 :turns 3}))))
      (is (false? (:isError (tool h (with-session sid) "waymark_invoke"
                                  {:kind "meal" :id (:id (meal! eng "Pie"))
                                   :action "accept"})))))

    (testing "a tally past it walls the seat, and the sentence says why"
      (is (= 200 (:status (tally! h {:input_tokens 12000 :output_tokens 2000
                                     :cache_read_tokens 8000
                                     :cache_write_tokens 500 :turns 21}))))
      (is (true? (:isError (tool h (with-session sid) "waymark_invoke"
                                 {:kind "meal" :id (:id (meal! eng "Broth"))
                                  :action "accept"}))))
      (let [halt (get-in (row-of eng :seat (:id chair)) [:data :halt])]
        (is (= "sitting_budget_reached" (str (:reason halt))))
        (is (str/includes? (str (:detail halt)) "22500 of 20000"))
        (is (str/includes? (str (:detail halt)) "Close the sitting"))))

    (testing "the reason is one the seat's own halt vocabulary knows"
      (is (contains? seats/halt-reasons "sitting_budget_reached")))

    (testing "and closing the sitting lifts it: a new one opens fresh"
      (is (= 200 (:status (close! h counts))))
      (let [sid2 (initialize! h)]
        (is (false? (:isError (tool h (with-session sid2) "waymark_sit"
                                    {:key a-key}))))
        (is (false? (:isError (tool h (with-session sid2) "waymark_invoke"
                                    {:kind "meal" :id (:id (meal! eng "Toast"))
                                     :action "accept"}))))
        (is (nil? (get-in (row-of eng :seat (:id chair)) [:data :halt]))
            "the first request that passes clears it")))))

;; ── 8 · the sweep under the wait (R-12.25, R-7.6) ───────────────────

(deftest the-sweep-closes-the-sitting-somebody-walked-away-from
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model {:name "chair"
                                 :mode "interactive"
                                 :sitting_idle_seconds 600})
        walked (doc-of (tool h (with-session (initialize! h)) "waymark_sit"
                             {:key a-key}))
        _ (is (= 200 (:status (tally! h counts))))]

    (testing "a sitting tallied a moment ago is nobody's business"
      (is (= {:abandoned 0 :closed 0}
             (select-keys (defs/sweep-seats! eng) [:abandoned :closed])))
      (is (= :open (:state (row-of eng :sitting (str (:sitting walked)))))))

    (testing "an hour later the sweep closes it with the last tally"
      (reset! at (Instant/parse "2026-09-17T10:00:00Z"))
      (is (= 1 (:closed (defs/sweep-seats! eng))))
      (let [row (row-of eng :sitting (str (:sitting walked)))
            d (:data row)]
        (is (= :closed (:state row)))
        (is (= 12000 (:input_tokens d)) "the counts the last tally reported")
        (is (= 7 (:turns d)))
        (is (= "Closed by the sweep after 600 seconds idle." (:note d)))
        (is (== (seats/cost-of counts prices) (:cost_usd d))
            "costed like every other bill, at the close's own prices")
        (is (some? (:prices d)))
        (is (some? (:ended_at d)))))

    (testing "and a second sweep has nothing left to end"
      (is (= {:abandoned 0 :closed 0}
             (select-keys (defs/sweep-seats! eng) [:abandoned :closed]))))))

(deftest a-sitting-that-never-tallied-is-abandoned-not-billed
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model {:name "chair"
                                 :mode "interactive"
                                 :sitting_idle_seconds 600})
        sat (doc-of (tool h (with-session (initialize! h)) "waymark_sit"
                          {:key a-key}))]
    (reset! at (Instant/parse "2026-09-17T10:00:00Z"))
    (is (= 1 (:abandoned (defs/sweep-seats! eng))))
    (let [row (row-of eng :sitting (str (:sitting sat)))]
      (is (= :abandoned (:state row)))
      (is (nil? (get-in row [:data :cost_usd]))
          "R-7.6's posture: the absence of a bill, not a zero one"))))

;; ── 9 · nothing fires an interactive seat ───────────────────────────

(deftest the-fire-door-refuses-an-interactive-seat-by-name
  (let [eng (fresh-engine)
        model (add-model! eng)
        chair (open-seat! eng model {:name "chair" :mode "interactive"})
        p (refusal #(inv/invoke! eng :seat (:id chair) :fire
                                 {:text "Look at the morning post."}
                                 {:principal person
                                  :idempotency-key "fire-the-chair"}))]
    (is (= :not-interactive (:guard p)))
    (is (str/includes? (str (:detail p))
                       "A person sits here; nothing fires it")
        "R-10.8's sentence, and it says what the seat IS rather than 409")

    (testing "and a fired seat is refused for the reason it always was —
              the link, not the mode"
      (let [quiet (open-seat! eng model {:name "fired" :key other-key})
            p2 (refusal #(inv/invoke! eng :seat (:id quiet) :fire nil
                                      {:principal person
                                       :idempotency-key "fire-the-quiet"}))]
        (is (= :linked-for-fire (:guard p2)))))))

;; ── 10 · no schedule is minted for a seat nothing fires ─────────────

(deftest the-schedules-consumer-mints-nothing-for-an-interactive-seat
  (let [eng (fresh-engine)
        adapters (schedules/adapters-of eng)
        fake (get adapters (keyword schedules/default-provider))
        model (add-model! eng)
        chair (open-seat! eng model {:name "chair" :mode "interactive"})
        quiet (open-seat! eng model {:name "fired" :key other-key})
        birth (fn [seat] {:kind :seat :action :create
                          :resource-id (str (:id seat))
                          :id (str "t-" (:id seat))})]

    (schedules/handle-transition! eng adapters (birth chair))
    (testing "no row, and nothing asked of the provider"
      (is (nil? (schedules/schedule-for-seat eng (:id chair))))
      (is (zero? (:creates (schedules/counts fake)))))

    (schedules/handle-transition! eng adapters (birth quiet))
    (testing "a fired seat gets its row and its copy, exactly as before"
      (is (some? (schedules/schedule-for-seat eng (:id quiet))))
      (is (= 1 (:creates (schedules/counts fake)))))

    (testing "and a seat restated INTO the chair ends the copy it had"
      ;; the restate states the office WHOLE, from the same literal the
      ;; create used — `held_for` unchanged, so no ladder note is owed
      (let [row (row-of eng :seat (:id quiet))]
        (inv/invoke! eng :seat (:id quiet) :restate
                     (-> (seat-body "fired" model {:mode "interactive"})
                         (dissoc :name :key))
                     {:principal person
                      :if-match (inv/etag :seat (str (:id quiet))
                                          (:version row))}))
      (schedules/handle-transition! eng adapters
                                    {:kind :seat :action :restate
                                     :resource-id (str (:id quiet))
                                     :id "t-restate"})
      (is (= 1 (:deletes (schedules/counts fake))))
      (is (= :ended (:state (schedules/schedule-for-seat eng (:id quiet))))))))

;; ── 11 · the wake consumer passes the chair by ──────────────────────

(deftest the-wake-consumer-never-wakes-an-interactive-seat
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        chair (open-seat! eng model
                          {:name "chair"
                           :mode "interactive"
                           :wake_on [{:kind "meal" :actions ["create"]}]})
        ;; a schedule and a link BY HAND: the point is that the
        ;; consumer never reaches them, not that they are missing
        sched (schedules/ensure-schedule! eng (row-of eng :seat (:id chair)))
        _ (inv/invoke! eng :schedule (:id sched) :link
                       {:fire_url "https://provider.test/fire"
                        :token "tok-chair-0123456789"}
                       {:principal person})
        ;; an open sitting, so a seat the consumer DID see would have
        ;; its match remembered as `wake_pending` rather than fired
        _ (doc-of (tool h (with-session (initialize! h)) "waymark_sit"
                        {:key a-key}))
        meal (meal! eng "Chowder")]

    (wakes/handle-transition! eng (atom nil)
                              {:kind :meal :action :create
                               :resource-id (str (:id meal))
                               :id "t-meal"
                               :from-state nil :to-state :proposed})

    (testing "nothing fired and nothing was remembered"
      (let [row (row-of eng :schedule (:id sched))]
        (is (nil? (get-in row [:data :wake_pending]))
            "a seat in the cache would have remembered this match")
        (is (nil? (get-in row [:data :wake_fired_at]))))
      (is (empty? (filter #(= :fire (:action %))
                          (store/with-tx (:storage eng)
                            (fn [tx]
                              (store/transitions (:storage eng) tx
                                                 {:kind :seat
                                                  :resource-id (str (:id chair))}
                                                 {:limit 20})))))))))
