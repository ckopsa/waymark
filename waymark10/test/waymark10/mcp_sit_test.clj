(ns waymark10.mcp-sit-test
  "The keyed sitter session (docs/spec-seat.md § 12, R-12.12 to
  R-12.16, and R-12.28): a person pastes a seat key into a Routine's
  instructions, the Routine's session presents it once, and THAT
  session — one of many wearing the same connector bearer — becomes
  the seat's sitter. Section 6 below is R-12.28: what that one answer
  carries, so the sitter's next call is its first invoke.

  The problem the feature answers, stated once: a person signed in
  through the claude.ai connector resolves to ONE delegate on ONE
  bearer, so a Routine's firing and an afternoon of chat are the same
  principal and cannot be told apart by credential. The key is what
  tells them apart, and the MCP session id is what it binds to.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: no database, no network. The shape is
  connector_door_test's, whose helpers this file re-spells rather than
  reaches for — they are private there, and four small fns are cheaper
  than a seam nobody else wants."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.resource :as r]
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

;; ── the connector door, as connector_door_test stands it up ─────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "sit-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "sit-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "sit-key"}}))

(defn- bearer [claims]
  {"authorization" (str "Bearer " (mint claims))})

(defn- fresh-engine
  "The house, with the fixture's meal kind — or with whatever kinds a
  deftest hands it, which is how the walk suite below adds its queue
  without moving anybody else's engine."
  ([] (fresh-engine [fx/meal]))
  ([resources]
   (engine/engine {:storage (memory/storage)
                   :resources resources
                   :oidc {:issuer issuer :audience audience :jwks jwks
                          :app-url "https://app.test/"
                          :delegate-clients {"connector" "Claude"}}})))

(defn- json [resp] (some-> (:body resp) wire/read-json))

;; the person at the keyboard, and the same person signing in THROUGH
;; the connector — the delegate every session of that tool resolves to
(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))
(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})
(def ^:private delegate-id "connector:colton")

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
  "c2VhdC1rZXktZm9yLXRoZS1yb3V0aW5l")

(defn- open-seat!
  "A person's seat over the fixture's meal kind, its schedule minted
  and its key offered — everything a Routine's instructions would
  already have."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "claude-sit-5" :display "Sit 5"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
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
    ;; the schedule is the engine's own row and the consumer mints it
    ;; on a started engine; a bare handler stands it up by hand, which
    ;; is the same call the consumer makes
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    {:seat seat :model model}))

(defn- initialize!
  "The handshake, answering [session-id response]."
  [h]
  (let [resp (rpc h (bearer colton) "initialize"
                  {:protocolVersion mcp/protocol-version
                   :capabilities {} :clientInfo {:name "routine" :version "0"}})]
    [(get-in resp [:headers "Mcp-Session-Id"]) resp]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

;; ── 1. the transport keeps a session ────────────────────────────────

(deftest initialize-answers-a-session-id-and-a-stateless-client-is-unchanged
  (let [h (engine/handler (fresh-engine))
        [sid resp] (initialize! h)]
    (is (= 200 (:status resp)))
    (is (string? sid))
    (is (<= 22 (count sid)) "128 bits, base64url, unpadded")
    (is (not (str/includes? sid "=")) "no padding to escape in a header")
    (testing "a second initialize is a second session"
      (is (not= sid (first (initialize! h)))))
    (testing "a client that sends no header is served exactly as before"
      (let [r (rpc h (bearer colton) "tools/list" nil)]
        (is (= 200 (:status r)))
        (is (seq (get-in (json r) [:result :tools])))))
    (testing "and a known id on a later message is fine"
      (is (= 200 (:status (rpc h (with-session sid) "tools/list" nil)))))))

(deftest an-unknown-session-id-answers-404
  (let [h (engine/handler (fresh-engine))
        resp (rpc h (with-session "not-a-session-this-engine-knows")
                  "tools/list" nil)]
    (is (= 404 (:status resp)))
    (is (str/includes? (str (:detail (json resp))) "initialize again")
        "MCP's own remedy for an unknown session")
    (testing "but an initialize carrying a stale id is answered, not refused —
              it IS how a client starts over"
      (let [r (rpc h (with-session "not-a-session-this-engine-knows")
                   "initialize" {:protocolVersion mcp/protocol-version})]
        (is (= 200 (:status r)))
        (is (string? (get-in r [:headers "Mcp-Session-Id"])))))))

;; ── 2. the tool is on the list, and says what it does ───────────────

(deftest waymark-sit-is-a-fixed-tool-and-the-instructions-name-it
  (let [h (engine/handler (fresh-engine))
        names (mapv :name (get-in (json (rpc h (bearer colton) "tools/list" nil))
                                  [:result :tools]))]
    (is (some #{"waymark_sit"} names))
    (is (= (count mcp/tools) (count names)))
    (testing "the connect-time instructions tell a firing to sit first"
      (is (str/includes? mcp/instructions "waymark_sit"))
      (is (str/includes? mcp/instructions "IF YOU WERE HANDED A SEAT KEY")))
    (testing "and the tool's own sentence says what binding costs and spares"
      (is (str/includes? mcp/sit-description "Call it once, first."))
      (is (str/includes? mcp/sit-description
                         "Your person's other sessions are untouched.")))))

;; ── 3. the refusals, each one sentence ──────────────────────────────

(deftest a-key-binds-nothing-without-a-session-a-person-or-a-seat
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        [sid _] (initialize! h)]
    (testing "a client that keeps no session is told why a key cannot bind it"
      (let [r (tool h (bearer colton) "waymark_sit" {:key a-key})]
        (is (true? (:isError r)))
        (is (str/includes? (text-of r) "This client keeps no MCP session"))
        (is (str/includes? (text-of r) "Mcp-Session-Id"))))

    (testing "a bare agent — nobody's tool — is refused, session or no session"
      ;; the dev headers are a bare handler's own credential: an agent
      ;; holding its own key, which is exactly NOT a delegate
      (let [agent-headers {"x-waymark-principal" "lone-agent"
                           "x-waymark-actor-type" "agent"
                           "mcp-session-id" sid}
            r (tool h agent-headers "waymark_sit" {:key a-key})]
        (is (true? (:isError r)))
        (is (str/includes? (text-of r) "A seat key binds a person's tool"))))

    (testing "a key no seat answers to is one uniform sentence"
      (let [r (tool h (with-session sid) "waymark_sit"
                    {:key "c2VhdC1rZXktbm9ib2R5LWhvbGRz"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r))
            "uniform: it does not say whether the key is malformed, stale
             or simply somebody else's")))))

;; ── 4. the bound session IS the sitter ──────────────────────────────

;; ── 3b. the delegate a LOCAL engine declares by header ──────────────
;;
;; R-12.14 without an IdP in front. A household running its own engine
;; configures no :oidc, so a fired run arrives as dev headers and
;; nothing else — a proxy states who the run is. If `dev-principal`
;; does not read `x-waymark-acts-for`, such an engine cannot produce a
;; delegate AT ALL: every seat key meets sit-not-a-delegate, and the
;; sentence blames the caller for a header the engine never read.
;;
;; THIS HAPPENED. The branch was dropped between 76c2363 and da24ebb
;; and nothing here failed, because every delegate in this file
;; arrives on a bearer. The bare-agent case above proves the refusal;
;; this proves the acceptance, and the two together pin the header.

(defn- delegate-headers
  "What a proxy in front of a local engine sends: the run's own name,
  the agent type, and the person it acts for. No bearer at all."
  ([] {"x-waymark-principal" "localfire-runner"
       "x-waymark-actor-type" "agent"
       "x-waymark-acts-for" "colton"})
  ([sid] (assoc (delegate-headers) "mcp-session-id" sid)))

(defn- init-as
  "The handshake under whatever credential `headers` carries."
  [h headers]
  (get-in (rpc h headers "initialize"
               {:protocolVersion mcp/protocol-version
                :capabilities {} :clientInfo {:name "routine" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(deftest a-delegate-declared-by-header-binds-the-key
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat]} (open-seat! eng)
        sid (init-as h (delegate-headers))
        sat (tool h (delegate-headers sid) "waymark_sit" {:key a-key})]

    (testing "x-waymark-acts-for is what makes a run a person's tool"
      (is (false? (:isError sat)) (text-of sat))
      (is (= "meal-clerk" (:seat (doc-of sat)))))

    (testing "the sitter it mints acts for the person the header named"
      (let [row (store/with-tx (:storage eng)
                  (fn [tx] (store/load-row (:storage eng) tx :member
                                           (seats/sitter-id seat) {})))]
        (is (= "colton" (get-in row [:data :acts_for])))))

    (testing "discover shows the person it acts for, before any key is spent"
      ;; localfire's credential check reads exactly this (R-4.6)
      (let [sid3 (init-as h (delegate-headers))
            d (tool h (delegate-headers sid3) "waymark_discover" {})]
        (is (= "colton" (get-in (doc-of d) [:principal :acts_for])))))

    (testing "the same run WITHOUT that one header is refused"
      (let [bare (dissoc (delegate-headers) "x-waymark-acts-for")
            sid2 (init-as h bare)
            d (tool h (assoc bare "mcp-session-id" sid2) "waymark_discover" {})
            r (tool h (assoc bare "mcp-session-id" sid2) "waymark_sit"
                    {:key a-key})]
        (is (= "agent" (get-in (doc-of d) [:principal :type])))
        (is (not (contains? (:principal (doc-of d)) :acts_for))
            "an agent acting for nobody shows no acts_for")
        (is (true? (:isError r)))
        (is (str/includes? (text-of r) "A seat key binds a person's tool")
            "one header is the whole difference between a delegate and
             an agent holding its own key")))))

(deftest a-key-binds-this-session-and-leaves-the-persons-others-alone
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-seat! eng)
        sitter-id (seats/sitter-id seat)
        [sid _] (initialize! h)
        sat (tool h (with-session sid) "waymark_sit" {:key a-key})
        answer (doc-of sat)]

    (testing "the sit answers the office, the sitter, the model and the leash"
      (is (false? (:isError sat)) (text-of sat))
      (is (= "meal-clerk" (:seat answer)))
      (is (= sitter-id (:sitter answer)))
      (is (= "claude-sit-5" (:model answer))
          "the seat's schedule names the model, and the claim is it")
      (is (string? (:grant answer)))
      (is (str/includes? (str (:note answer)) "waymark_get")))

    (testing "the sitter row was minted once, acting for the person"
      (let [row (store/with-tx (:storage eng)
                  (fn [tx] (store/load-row (:storage eng) tx :member
                                           sitter-id {})))]
        (is (some? row))
        (is (= "agent" (get-in row [:data :actor_type])))
        (is (= "colton" (get-in row [:data :acts_for])))
        (is (= "meal-clerk (seat)" (get-in row [:data :display])))))

    (testing "the minted grant cites the seat and carries no scope of its own"
      (let [g (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx :grant
                                         (str (:grant answer)) {})))]
        (is (= sitter-id (get-in g [:data :audience])))
        (is (= (:id seat) (str (get-in g [:data :seat]))))
        (is (empty? (get-in g [:data :scope])))))

    (testing "waymark_discover ON THIS SESSION is the sitter, in the seat"
      (let [doc (doc-of (tool h (with-session sid) "waymark_discover" {}))]
        (is (= sitter-id (get-in doc [:principal :id])))
        (is (= "meal-clerk" (get-in doc [:doors :ask :seat :name])))
        (is (= "active" (get-in doc [:doors :ask :seat :state])))))

    (testing "and WITHOUT the header the same person is still the delegate"
      (let [doc (doc-of (tool h (bearer colton) "waymark_discover" {}))]
        (is (= delegate-id (get-in doc [:principal :id])))
        (is (nil? (get-in doc [:doors :ask :seat]))
            "the delegate sits in no office — its chats are untouched")))

    (testing "a SECOND session of the same bearer is the delegate too"
      (let [[other _] (initialize! h)
            doc (doc-of (tool h (with-session other) "waymark_discover" {}))]
        (is (= delegate-id (get-in doc [:principal :id])))))

    (testing "sitting twice on one session is the same seat, not a second grant"
      (let [again (doc-of (tool h (with-session sid) "waymark_sit" {:key a-key}))]
        (is (= (:grant answer) (:grant again))
            "the standing seat grant is reused, never re-minted")
        (is (= (:sitting answer) (:sitting again))
            "and so is the open sitting")))

    ;; ── the router's own seat machinery, wearing the sitter ─────────
    (let [meal (:row (inv/create! eng :meal {:name "Soup" :themes []}
                                  {:principal person}))
          ;; the sit opened the sitting (R-12.15): nobody else opens one
          ;; for a keyed session, and the answer names it
          sitting (seats/open-sitting-for-grant eng (str (:grant answer)))
          _ (is (some? sitting) "waymark_sit opened the seat's sitting")
          _ (is (= (str (:id sitting)) (str (:sitting answer)))
                "and its answer names the sitting it opened")
          _ (is (= sitter-id (str (get-in sitting [:data :member])))
                "born as the sitter")
          counts (fn []
                   (let [row (store/with-tx (:storage eng)
                               (fn [tx] (store/load-row (:storage eng) tx
                                                        :sitting (:id sitting) {})))]
                     [(get-in row [:data :transitions])
                      (get-in row [:data :refusals])]))]
      (is (= [0 0] (counts)) "a sitting does not count its own birth")

      (testing "an invoke through the BOUND session moves the row as the sitter"
        (let [r (tool h (with-session sid) "waymark_invoke"
                      {:kind "meal" :id (:id meal) :action "accept"})]
          (is (false? (:isError r)) (text-of r))))

      (testing "the transition's actor is the seat, not the person's tool"
        (let [log (store/with-tx (:storage eng)
                    (fn [tx] (store/transitions (:storage eng) tx
                                                {:kind :meal :resource-id (:id meal)}
                                                {:limit 10 :newest-first true})))
              accepted (first (filter #(= :accept (:action %)) log))]
          (is (some? accepted))
          (is (= sitter-id (str (get-in accepted [:actor :id])))
              "the ledger reads one actor per office")))

      (testing "and it counted against the seat's open sitting"
        (is (= [1 0] (counts)))))))

;; ── 5. the session ends its own wake ────────────────────────────────

(deftest the-sitter-closes-its-own-sitting-through-the-bound-session
  ;; R-12.15 opened the row as the sitter and R-12.17 says the report
  ;; names the run that spent the tokens. The HTTP door
  ;; (/api/-/sittings/close) is one way that report arrives; THIS is
  ;; the other, and the one a Routine whose environment carries no
  ;; variables and no credential can actually take: the Stop hook
  ;; hands the counts back to the session, and the session closes its
  ;; own sitting through the connector it is already holding.
  ;;
  ;; Nothing in the engine was added for it. A sitting is its member's
  ;; (`:own-surface {:by :member :actions #{"create" "close"}}`), the
  ;; sitter IS the member, and the own-surface courtesy is a fact
  ;; about a NAMED principal rather than about a scope — so the seat
  ;; grant's empty scope neither grants this nor hides it.
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-seat! eng)
        sitter-id (seats/sitter-id seat)
        run "session_01TheRoutineFiringItself"
        [sid _] (initialize! h)
        sat (doc-of (tool h (with-session sid) "waymark_sit"
                          {:key a-key :session run}))
        sitting-id (str (:sitting sat))
        ;; one wake's usage, as a Stop hook sums it off the transcript
        counts {:input_tokens 8000 :output_tokens 2100
                :cache_read_tokens 45000 :cache_write_tokens 900 :turns 4}
        ;; the four the seat's model carries — read off the row, so the
        ;; expected bill is computed from the numbers the close reads
        prices {:input (get-in model [:data :price_input_per_mtok])
                :output (get-in model [:data :price_output_per_mtok])
                :cache_read (get-in model [:data :price_cache_read_per_mtok])
                :cache_write (get-in model [:data :price_cache_write_per_mtok])}
        sitting-row (fn []
                      (store/with-tx (:storage eng)
                        (fn [tx] (store/load-row (:storage eng) tx :sitting
                                                 sitting-id {}))))]

    (testing "the sit opened the row, paired with this run, as the sitter"
      (let [row (sitting-row)]
        (is (= :open (:state row)))
        (is (= sitter-id (str (get-in row [:data :member]))))
        (is (= run (get-in row [:data :harness_session]))
            "stamped at birth, not guessed at the close (R-12.15)")))

    (testing "the bound session SEES its own sitting, and the one door on it"
      (let [r (tool h (with-session sid) "waymark_get"
                    {:kind "sitting" :id sitting-id})
            env (doc-of r)]
        (is (false? (:isError r)) (text-of r))
        (is (= "open" (:state env)))
        (is (some? (get-in env [:actions :close]))
            "the own-surface advertises the door it names")
        (is (nil? (get-in env [:actions :abandon]))
            "and nothing else — abandon is the boot sweep's, not the sitter's")))

    (testing "a DIFFERENT session of the same person's tool sees no such row"
      ;; no key, no bind: it is the delegate, and a sitting is not the
      ;; delegate's — concealed, never refused by name
      (let [[other _] (initialize! h)
            r (tool h (with-session other) "waymark_invoke"
                    {:kind "sitting" :id sitting-id :action "close"
                     :input counts})]
        (is (true? (:isError r)))
        (is (= 404 (:status (doc-of r))))
        (is (= 2 (count (:content r)))
            "the concealed-door hint rides every not-found")
        (is (= :open (:state (sitting-row)))
            "and the wake it could not see is untouched")))

    (let [r (tool h (with-session sid) "waymark_invoke"
                  {:kind "sitting" :id sitting-id :action "close"
                   :input (assoc counts
                                 :note "Walked the queue; one meal accepted."
                                 :harness_session run)})
          env (doc-of r)]

      (testing "the sitter's own close goes through, on the session it bound"
        (is (false? (:isError r)) (text-of r))
        (is (= "closed" (:state env))))

      (testing "the counts, the turns, the note and the run are written down"
        (let [row (sitting-row)
              d (:data row)]
          (is (= :closed (:state row)))
          (is (= 8000 (:input_tokens d)))
          (is (= 2100 (:output_tokens d)))
          (is (= 45000 (:cache_read_tokens d)))
          (is (= 900 (:cache_write_tokens d)))
          (is (= 4 (:turns d)))
          (is (= "Walked the queue; one meal accepted." (:note d)))
          (is (= run (:harness_session d))
              "the birth stamp and the report name one run")
          (is (some? (:ended_at d)) "stamped by the close")

          (testing "and the bill is the model's prices at this moment (R-10.4)"
            (is (== (seats/cost-of counts prices) (:cost_usd d)))
            (is (== (:input prices) (get-in d [:prices :input])))
            (is (== (:output prices) (get-in d [:prices :output])))
            (is (== (:cache_read prices) (get-in d [:prices :cache_read])))
            (is (== (:cache_write prices) (get-in d [:prices :cache_write]))))))

      (testing "the ending is a real transition, and the SITTER made it"
        (let [log (store/with-tx (:storage eng)
                    (fn [tx] (store/transitions (:storage eng) tx
                                                {:kind :sitting
                                                 :resource-id sitting-id}
                                                {:limit 10 :newest-first true})))
              closed (first (filter #(= :close (:action %)) log))]
          (is (some? closed))
          (is (= sitter-id (str (get-in closed [:actor :id])))
              "one actor per office: the ledger reads the seat"))))))

(deftest a-linked-schedule-claims-the-seat-s-model-not-its-stale-copy
  ;; R-12.8 for a linked row: the copy is never pushed, so after the
  ;; seat steps down the schedule still names the model of the link.
  ;; The sitter's claim is the seat's own list, or the model wall
  ;; refuses the very seat the person just restated (production,
  ;; 2026-09-18: inbox-clerk halted model_not_held on its first run
  ;; after the step to its substitute).
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-seat! eng)
        next-model (:row (inv/create! eng :model
                                      {:name "claude-sit-4" :display "Sit 4"
                                       :vendor "anthropic" :tier "economy"
                                       :price_input_per_mtok 1M
                                       :price_output_per_mtok 5M
                                       :price_cache_read_per_mtok 0.1M
                                       :price_cache_write_per_mtok 1.25M}
                                      {:principal person}))
        sched (schedules/schedule-for-seat eng (:id seat))
        ;; the restate door is fenced: the seat moved once since its
        ;; birth (offer_key), so the fence wants the row's current etag
        current (store/with-tx (:storage eng)
                  (fn [tx] (store/load-row (:storage eng) tx :seat (:id seat) {})))]
    (inv/invoke! eng :schedule (:id sched) :link
                 {:fire_url "https://routines.example/fire/trig_test"
                  :token "a-fire-token-that-is-long-enough-1234"}
                 {:principal person})
    (inv/invoke! eng :seat (:id seat) :restate
                 {:charter "Decide whether a meal belongs on the list."
                  :mode "fired"
                  :scope [{:kind "meal" :actions ["accept"]}]
                  :substitute_drop []
                  :held_for [(:id next-model)]
                  :substitute_for []
                  :standing_ttl_seconds 604800
                  :cadence_seconds 3600
                  :sitting_idle_seconds 3600
                  :budget_usd_per_week 5M
                  :sitting_budget_tokens 60000
                  :rows_per_firing 20
                  :fire_interval_seconds 300
                  :note "Stepped down for the test: the copy still names the old model."}
                 {:principal person
                  :if-match (inv/etag :seat (:id seat) (:version current))})
    (testing "the linked row's copy is stale by construction"
      (is (= (str (:id model))
             (str (get-in (schedules/schedule-for-seat eng (:id seat))
                          [:data :model])))))
    (testing "the sit claims the seat's held_for, not the copy"
      (let [[sid _] (initialize! h)
            sat (tool h (with-session sid) "waymark_sit" {:key a-key})
            answer (doc-of sat)]
        (is (false? (:isError sat)) (text-of sat))
        (is (= "claude-sit-4" (:model answer)))))))

;; ── 6. the walk rides in the sit's answer (R-12.28) ─────────────────
;;
;; Measured on production: ten calls and 34 KB before the first
;; invoke, and every one of them a turn that read the whole prefix
;; again. The engine knew all of it at the sit — the seat row names
;; the walk and the charter, the sitter's grant names the rows it may
;; see, and a collection item IS an envelope minus data, so the doors
;; and their inputs are already on the page the query answers. What
;; follows pins what the sit now hands back, and that it hands back
;; exactly what the seat's own grant admits and nothing beside it.

(def ^:private drop-consequence
  "The sentence the confirm gate will want echoed back, spelled once
  so the declaration and the assertion cannot drift."
  "The post is dropped, and nobody reads it again.")

(def ^:private post
  "The queue this house walks: the smallest kind a seat may walk — it
  filters its own queue by state and sorts it oldest first — with one
  door that takes an input and one the confirm gate holds. `box` is
  filterable, so a seat's scope can be narrowed to one box and a post
  in another is a row the sitter may not see at all."
  (r/resource
   {:kind :post
    :plural "posts"
    :states [:queued :filed :dropped]
    :initial :queued
    :terminal #{:filed :dropped}
    :summary "{data.subject} · {state}"
    :schema
    [:map
     [:subject {:x-display {:label "What it is about"}}
      [:string {:min 1 :max 120}]]
     [:box {:x-display {:label "Which box"}} [:string {:min 1 :max 40}]]
     [:received_at {:x-display {:label "When it arrived"}} :waymark/instant]
     [:filed_in {:optional true :x-display {:label "Filed in"}}
      [:maybe [:string {:max 60}]]]]
    :filterable {:state #{:eq :in} :box #{:eq}}
    :default-filters {:state "queued"}
    :sortable {:fields [:received_at] :default "received_at"}
    :actions
    {:file {:from #{:queued} :to :filed
            :input [:map [:where [:string {:min 1 :max 60}]]]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A filed post keeps its history."}
            :handler (fn [row inp _ctx]
                       (assoc-in row [:data :filed_in] (:where inp)))}
     :drop {:from #{:queued} :to :dropped
            :safety {:idempotent true :reversible false :confirm true
                     :consequence drop-consequence}}}}))

(def ^:private walk-key
  "The walk clerk's own key — a second office, so the meal seat above
  keeps the key its own tests present."
  "c2VhdC1rZXktZm9yLXRoZS13YWxrLWNsZXJr")

(def ^:private walk-charter
  "Read each post and take the door it asks for.")

(defn- open-walk-seat!
  "A seat that WALKS the post queue, its key offered. Its scope is
  filtered to the house's own box, so a post in another box is a row
  outside the grant rather than a row the page merely did not reach."
  [eng extra]
  (let [model (:row (inv/create! eng :model
                                 {:name "claude-walk-5" :display "Walk 5"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    (merge {:name "post-clerk"
                            :charter walk-charter
                            :scope [{:kind "post"
                                     :actions ["file" "drop"]
                                     :filter {:box "house"}}]
                            :walk "post"
                            :held_for [(:id model)]
                            :standing_ttl_seconds 604800
                            :cadence_seconds 3600
                            :budget_usd_per_week 5M
                            :sitting_budget_tokens 60000}
                           extra)
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key walk-key}
                 {:principal person})
    seat))

(defn- post!
  "One real row in the queue, with the moment it arrived — the field
  this kind sorts its queue by."
  [eng subject box at]
  (:row (inv/create! eng :post {:subject subject :box box :received_at at}
                     {:principal person})))

(defn- sit-walk!
  "The walk clerk's sit, through the real door → [result answer]."
  [h]
  (let [[sid _] (initialize! h)
        r (tool h (with-session sid) "waymark_sit" {:key walk-key})]
    [r (doc-of r)]))

(defn- sitting-row
  "One sitting as the store holds it — the stamps no answer carries."
  [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :sitting (str id) {}))))

(defn- utf8-length [^String s]
  (alength (.getBytes s "UTF-8")))

(deftest the-sit-answers-the-seats-walk-with-every-rows-doors
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {})
        ;; three real rows: two in the house's box and one in another.
        ;; The one the grant hides is the OLDEST, so its absence is
        ;; the filter's doing and not the page's.
        theirs (post! eng "Somebody else's post" "other"
                      "2026-09-18T06:00:00Z")
        gas (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        note (post! eng "The school note" "house" "2026-09-18T08:00:00Z")
        [r answer] (sit-walk! h)
        walk (:walk answer)
        rows (:rows walk)]

    (testing "the sit answers, and the answer carries the walk"
      (is (false? (:isError r)) (text-of r))
      (is (= "post" (:kind walk)))
      (is (= walk-charter (:charter walk))
          "the charter rides the sit: the sitter never reads the seat row
           to learn what it is for"))

    (testing "the rows are the queue's own, oldest first"
      (is (= [(str (:id gas)) (str (:id note))] (mapv :id rows))
          "the kind's default sort is the order, and it is oldest first"))

    (testing "and a row the grant does not admit is ABSENT, never refused"
      (is (not (some #{(str (:id theirs))} (mapv :id rows))))
      (is (= 2 (:total walk))
          "the grant's filter narrows the count as it narrows the page"))

    (testing "each row carries its summary projection"
      (let [row (first rows)]
        (is (= "post" (:kind row)))
        (is (= "queued" (:state row)))
        (is (str/includes? (str (:summary row)) "The gas bill"))
        (is (= "The gas bill" (get-in row [:fields :subject])))
        (is (nil? (:data row))
            "a collection item carries the grid projection, not the whole
             document — the full row comes back from the first invoke")))

    (testing "and the doors that row affords, with the input each one takes"
      (let [doors (:doors (first rows))
            by-name (into {} (map (juxt :action identity)) doors)]
        (is (= ["drop" "file"] (mapv :action doors))
            "both doors this state opens, and nothing the grant withholds")
        (is (= "string"
               (get-in by-name ["file" :input :properties :where :type]))
            "the sitter reads the field and its type here, not from
             waymark_schema")
        (is (= ["where"] (get-in by-name ["file" :input :required])))))

    (testing "a confirm-gated door carries the sentence to echo back"
      (let [drop-door (->> (:doors (first rows))
                           (filter #(= "drop" (:action %)))
                           first)]
        (is (true? (:confirm drop-door)))
        (is (= drop-consequence (:acknowledge drop-door))
            "character for character: it is what waymark_invoke will
             compare against")))

    (testing "the note sends the sitter to the first invoke and nowhere else"
      (is (str/includes? (str (:note answer))
                         "invoke the door the charter chooses"))
      (is (str/includes? (str (:note answer)) "Do not call discover"))
      (is (not (str/includes? (str (:note answer)) "waymark_get"))))

    ;; bead waymark-fp62.11, R-4: a seat that SAYS a judgment gets a
    ;; `judgment` block beside its rows and one more sentence in the
    ;; note (judgment_walk_test pins that half). A seat that says none
    ;; gets exactly what it always got, and this is where that stays
    ;; true.
    (testing "a seat that says no judgment carries none, and is told of none"
      (is (not (contains? walk :judgment))
          "absent, not empty: the walk of a plain queue seat is unchanged")
      (is (not (str/includes? (str (:note answer)) "invoke judge"))))

    (testing "and the sit's own answer is on the sitting's served line"
      (let [row (store/with-tx (:storage eng)
                  (fn [tx] (store/load-row (:storage eng) tx :sitting
                                           (str (:sitting answer)) {})))
            line (get-in row [:data :served :waymark_sit])]
        (is (= 1 (long (:calls line))))
        (is (= (utf8-length (text-of r)) (long (:bytes line)))
            "R-10.6a at the one door that opens the wake it is counted on")
        (is (= [:waymark_sit] (keys (get-in row [:data :served])))
            "one line, and it is the tool that answered")))))

(deftest rows-per-firing-bounds-the-walk
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:rows_per_firing 1})
        gas (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        _ (post! eng "The school note" "house" "2026-09-18T08:00:00Z")
        [r answer] (sit-walk! h)]
    (is (false? (:isError r)) (text-of r))
    (is (= [(str (:id gas))] (mapv :id (get-in answer [:walk :rows])))
        "one row, and it is the oldest")
    (is (= 2 (get-in answer [:walk :total]))
        "the queue still says how many are waiting; the cap says how many
         this wake takes")))

(deftest two-runs-of-one-seat-walk-different-rows
  ;; Production, 2026-09-27: a groom woke code-seat and a fire named a
  ;; row 12 seconds later. Both sits answered the same first row, both
  ;; runs worked one branch, and the second row waited. A row an open
  ;; sitting of the seat was handed is that sitting's until it closes.
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:rows_per_firing 1})
        gas (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        note (post! eng "The school note" "house" "2026-09-18T08:00:00Z")
        sit-as! (fn [run]
                  (let [[sid _] (initialize! h)
                        r (tool h (with-session sid) "waymark_sit"
                                {:key walk-key :session run})]
                    [r (doc-of r)]))
        [r1 first-run] (sit-as! "run-wake")
        [r2 second-run] (sit-as! "run-fire")]
    (testing "both runs sit, each in its own sitting"
      (is (false? (:isError r1)) (text-of r1))
      (is (false? (:isError r2)) (text-of r2))
      (is (not= (:sitting first-run) (:sitting second-run))))
    (testing "the first run is handed the oldest row, the second the next"
      (is (= [(str (:id gas))] (mapv :id (get-in first-run [:walk :rows]))))
      (is (= [(str (:id note))] (mapv :id (get-in second-run [:walk :rows])))
          "the row the open sitting holds is not handed a second time"))
    (testing "the claim ends with the sitting"
      (inv/invoke! eng :sitting (str (:sitting first-run)) :abandon nil
                   {:principal seats/seats-actor})
      (let [[r third] (sit-as! "run-later")]
        (is (false? (:isError r)) (text-of r))
        (is (= [(str (:id gas))] (mapv :id (get-in third [:walk :rows])))
            "the closed sitting holds nothing, and the oldest row is back")))))

(deftest a-re-sit-is-handed-back-the-row-its-sitting-walks
  ;; Production, 2026-09-27 (sitting 003f87bc): a deploy dropped the
  ;; connector, the seat re-sat with the same key, seat and session,
  ;; and the reused sitting was handed the queue's NEW first row — a
  ;; different ticket, change and branch. The row a sitting walks is
  ;; its own until it leaves the queue.
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:rows_per_firing 1})
        gas (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        sit-as! (fn [run]
                  (let [[sid _] (initialize! h)
                        r (tool h (with-session sid) "waymark_sit"
                                {:key walk-key :session run})]
                    [r (doc-of r)]))
        walked (fn [sitting-id]
                 (get-in (store/with-tx (:storage eng)
                           (fn [tx] (store/load-row (:storage eng) tx :sitting
                                                    (str sitting-id) {})))
                         [:data :walked_rows]))
        [r1 first-sat] (sit-as! "run-dropped")
        ;; a row that arrived EARLIER sorts ahead of the one being walked
        early (post! eng "The early notice" "house" "2026-09-18T06:00:00Z")]
    (testing "the first sit is handed the oldest row"
      (is (false? (:isError r1)) (text-of r1))
      (is (= [(str (:id gas))] (mapv :id (get-in first-sat [:walk :rows]))))
      (is (= [(str (:id gas))] (walked (:sitting first-sat)))))
    (testing "a re-sit with the same session keeps the sitting and its row"
      (let [[r again] (sit-as! "run-dropped")]
        (is (false? (:isError r)) (text-of r))
        (is (= (:sitting first-sat) (:sitting again)))
        (is (= [(str (:id gas))] (mapv :id (get-in again [:walk :rows])))
            "the row it was walking, not the queue's new first row")
        (is (= [(str (:id gas))] (walked (:sitting again)))
            "and walked_rows names only the row the sit handed out")))
    (testing "a re-sit after its row left the queue is handed the next"
      (inv/invoke! eng :post (:id gas) :file {:where "bills"}
                   {:principal person})
      (let [[r later] (sit-as! "run-dropped")]
        (is (false? (:isError r)) (text-of r))
        (is (= (:sitting first-sat) (:sitting later)))
        (is (= [(str (:id early))] (mapv :id (get-in later [:walk :rows]))))
        (is (= [(str (:id early))] (walked (:sitting later))))))))

(deftest a-seat-that-walks-nothing-answers-no-walk-and-a-parked-one-no-seat
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat]} (open-seat! eng)
        [sid _] (initialize! h)
        answer (doc-of (tool h (with-session sid) "waymark_sit" {:key a-key}))]

    (testing "a seat with no walk answers no walk key at all"
      (is (not (contains? answer :walk)))
      (is (str/includes? (str (:note answer)) "waymark_get")
          "and its note still points at the seat row, where its charter is"))

    (testing "and once the person parks it, the key opens nothing"
      (inv/invoke! eng :seat (:id seat) :park nil {:principal person})
      (let [[other _] (initialize! h)
            r (tool h (with-session other) "waymark_sit" {:key a-key})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r)))))))

(deftest a-sitting-handed-no-rows-is-stamped-as-having-walked-nothing
  ;; An empty walk shows today only as an absent `walked_rows`, which a
  ;; sitting that walked nothing and a sitting the claim never wrote to
  ;; both carry — so a reader cannot count the idle wakes. The stamp
  ;; says which. Every row of this queue is held by the first run's open
  ;; sitting, the cheapest queue with nothing free in it.
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:rows_per_firing 1})
        gas (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        sit-as! (fn [run]
                  (let [[sid _] (initialize! h)]
                    (doc-of (tool h (with-session sid) "waymark_sit"
                                  {:key walk-key :session run}))))
        walked (sit-as! "run-wake")
        handed-nothing (sit-as! "run-fire")]
    (testing "the sitting handed the queue's row carries no stamp"
      (is (= [(str (:id gas))] (mapv :id (get-in walked [:walk :rows]))))
      (is (nil? (get-in (sitting-row eng (:sitting walked))
                        [:data :walked_nothing]))))
    (testing "the sitting whose every row was held is stamped"
      (is (empty? (get-in handed-nothing [:walk :rows])))
      (is (true? (get-in (sitting-row eng (:sitting handed-nothing))
                         [:data :walked_nothing]))))))

(deftest a-seat-at-a-wall-answers-no-rows
  ;; R-5.2's third wall, and the cheapest one to stand up: a seat
  ;; whose week's fuel is zero is at the wall from its first request.
  ;; The grant then scopes to NOTHING, so the queue is concealed from
  ;; its own sitter — absent, the way every unadmitted thing is.
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:budget_usd_per_week 0M})
        _ (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        [r answer] (sit-walk! h)]
    (is (false? (:isError r)) (text-of r))
    (is (= "post-clerk" (:seat answer)))
    (is (empty? (get-in answer [:walk :rows]))
        "no rows, and no refusal either: the sit answers what the seat's own
         grant admits, which behind a wall is nothing")
    ;; Production, 2026-09-27: a run fired past the wall read a bare
    ;; answer, found nothing, and reported the seat's grant as broken.
    ;; The sit now says which wall, in the fire door's own words.
    (testing "and it says the seat is halted, and why"
      (is (not (contains? answer :walk)) "no walk at all behind the wall")
      (is (= "budget" (get-in answer [:halted :wall])))
      (is (str/includes? (str (get-in answer [:halted :detail]))
                         "The week's fuel is spent"))
      (is (nil? (get-in answer [:halted :lifts_at]))
          "a budget of zero is lifted by no roll of the window, only by a person")
      (is (str/includes? (str (:note answer)) "halted"))
      (is (some? (:sitting answer))
          "the sitting still opens, so the run's Stop hook can close it"))))


;; ── 6b. the walk under its scope entry's filter (waymark-fp62.12) ───
;;
;; `post` above filters its own queue, which is the only kind a seat
;; could walk before this bead. `memo` declares NO default filter, so
;; its collection opens on every row it has ever held. What narrows
;; the walk is the scope entry, and the entry is also the leash: the
;; rows the seat may see are the rows it walks, and a row that takes
;; the exit door leaves both in the same commit.

(def ^:private memo
  "A queue that does not filter itself: no `:default-filters` at all.
  Two doors, and they name each other — `send` is the way out of the
  filter a seat walks, and `recall` is the way back in."
  (r/resource
   {:kind :memo
    :plural "memos"
    :states [:draft :sent]
    :initial :draft
    :terminal #{}
    :summary "{data.subject} · {state}"
    :schema
    [:map
     [:subject {:x-display {:label "What it is about"}}
      [:string {:min 1 :max 120}]]
     [:written_at {:x-display {:label "When it was written"}}
      :waymark/instant]]
    :filterable {:state #{:eq :in}}
    :sortable {:fields [:written_at] :default "written_at"}
    :actions
    {:send {:from #{:draft} :to :sent
            :safety {:idempotent true :reversible true :confirm false}
            :display {:label "Send" :style :primary :order 1}}
     :recall {:from #{:sent} :to :draft
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Recall" :order 2}}}}))

(def ^:private memo-key
  "The memo clerk's own key — a third office, so the two above keep
  the keys their own tests present."
  "c2VhdC1rZXktZm9yLXRoZS1tZW1vLWNsZXJr")

(def ^:private memo-charter
  "Send each memo the house has finished writing.")

(defn- open-memo-seat!
  "A seat whose scope entry NARROWS the memo queue to its drafts, and
  names the one door that takes a draft out of it."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "claude-memo-5" :display "Memo 5"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    {:name "memo-clerk"
                     :charter memo-charter
                     :scope [{:kind "memo" :actions ["send"]
                              :filter {:state "draft"}}]
                     :walk "memo"
                     :held_for [(:id model)]
                     :standing_ttl_seconds 604800
                     :cadence_seconds 3600
                     :budget_usd_per_week 5M
                     :sitting_budget_tokens 60000}
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key memo-key}
                 {:principal person})
    seat))

(defn- memo! [eng subject at]
  (:row (inv/create! eng :memo {:subject subject :written_at at}
                     {:principal person})))

(deftest the-walk-reads-its-kind-under-the-scope-entrys-filter
  (let [eng (fresh-engine [fx/meal memo])
        h (engine/handler eng)
        _ (open-memo-seat! eng)
        gas (memo! eng "The gas bill" "2026-09-18T07:00:00Z")
        note (memo! eng "The school note" "2026-09-18T08:00:00Z")
        [sid _] (initialize! h)
        r (tool h (with-session sid) "waymark_sit" {:key memo-key})
        walk (:walk (doc-of r))]

    (testing "a kind that declares no default filter is walked all the same"
      (is (false? (:isError r)) (text-of r))
      (is (= "memo" (:kind walk)))
      (is (= memo-charter (:charter walk)))
      (is (= [(str (:id gas)) (str (:id note))] (mapv :id (:rows walk)))
          "both drafts, oldest first")
      (is (= 2 (:total walk))
          "and the count is the collection's under the same filter"))

    (testing "the sitter is handed the one door the entry opens"
      (is (= ["send"] (mapv :action (:doors (first (:rows walk)))))))

    (testing "a row that walked the exit door is not in the next walk"
      (inv/invoke! eng :memo (str (:id gas)) :send nil {:principal person})
      (let [again (tool h (with-session sid) "waymark_sit" {:key memo-key})
            walk' (:walk (doc-of again))]
        (is (false? (:isError again)) (text-of again))
        (is (= [(str (:id note))] (mapv :id (:rows walk')))
            "the sent memo left the filter, so it left the walk")
        (is (= 1 (:total walk'))
            "and the queue's own count went with it")))

    (testing "and the row that left the filter left the seat's sight too"
      (let [got (tool h (with-session sid) "waymark_get"
                      {:kind "memo" :id (str (:id gas))})]
        (is (true? (:isError got))
            "one map says what the seat sees and what it walks")))))

;; ── 6c. a change in another repository withholds no ticket ──────────
;;
;; Ticket 0eba219c: `seats/stuck-walk-reasons` counts only a submitted,
;; stuck or live change whose `repository` is the ticket's `repo`. The
;; two kinds below are the least a ticket walk reads: a ticket naming
;; its repo, and a change born from it naming its own.

(def ^:private ticket-kind
  "A ticket that names the repository it asks a change of."
  (r/resource
   {:kind :ticket
    :plural "tickets"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "What it asks"}}
      [:string {:min 1 :max 120}]]
     [:repo {:x-display {:label "Which repository"}}
      [:string {:min 1 :max 120}]]]
    :filterable {:state #{:eq :in}}
    :sortable {:fields [:title] :default "title"}
    :actions
    {:close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A test kind: nothing reopens it."}
             :display {:label "Close" :style :primary :order 1}}}}))

(def ^:private change-kind
  "A change born from a ticket, in the repository it names."
  (r/resource
   {:kind :change
    :plural "changes"
    :states [:open :submitted :stuck]
    :initial :open
    :terminal #{:stuck}
    :summary "{data.repository} · {state}"
    :schema
    [:map
     [:born_from {:x-display {:label "Born from"}}
      [:string {:min 1 :max 120}]]
     [:repository {:x-display {:label "Which repository"}}
      [:string {:min 1 :max 120}]]]
    :filterable {:state #{:eq :in} :born_from #{:eq}}
    :sortable {:fields [:repository] :default "repository"}
    :actions
    {:submit {:from #{:open} :to :submitted
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A test kind: nothing takes a round back."}
              :display {:label "Submit" :style :primary :order 1}}
     :stall {:from #{:open :submitted} :to :stuck
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A test kind: nothing unsticks it."}
             :display {:label "Stall" :order 2}}}}))

(def ^:private ticket-key
  "The ticket seat's own key, a fourth office."
  "c2VhdC1rZXktZm9yLXRoZS10aWNrZXQtc2VhdA")

(defn- open-ticket-seat!
  "A seat that walks the open tickets."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "claude-ticket-5" :display "Ticket 5"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    {:name "ticket-builder"
                     :charter "Build what each open ticket asks for."
                     :scope [{:kind "ticket" :actions ["close"]
                              :filter {:state "open"}}]
                     :walk "ticket"
                     :held_for [(:id model)]
                     :standing_ttl_seconds 604800
                     :cadence_seconds 3600
                     :budget_usd_per_week 5M
                     :sitting_budget_tokens 60000}
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key ticket-key}
                 {:principal person})
    seat))

(deftest a-change-in-another-repository-withholds-no-ticket
  (let [eng (fresh-engine [fx/meal ticket-kind change-kind])
        h (engine/handler eng)
        _ (open-ticket-seat! eng)
        tk (:row (inv/create! eng :ticket {:title "Pin the repo rule"
                                           :repo "ckopsa/one"}
                              {:principal person}))
        tid (str (:id tk))
        change! (fn [repo door]
                  (let [row (:row (inv/create! eng :change
                                               {:born_from (str "ticket:" tid)
                                                :repository repo}
                                               {:principal person}))]
                    (inv/invoke! eng :change (str (:id row)) door nil
                                 {:principal person})
                    row))]
    (change! "ckopsa/other" :submit)
    (change! "ckopsa/other" :stall)

    (testing "a submitted and a stuck change in another repository count for nothing"
      (is (= {} (seats/stuck-walk-reasons eng "ticket"))))

    (testing "so the walk still hands the ticket"
      (let [[sid _] (initialize! h)
            r (tool h (with-session sid) "waymark_sit" {:key ticket-key})
            walk (:walk (doc-of r))]
        (is (false? (:isError r)) (text-of r))
        (is (= [tid] (mapv :id (:rows walk))))
        (is (empty? (:withheld walk)))))

    (testing "while a submitted change in the ticket's own repository withholds it"
      (change! "ckopsa/one" :submit)
      (is (= [tid] (keys (seats/stuck-walk-reasons eng "ticket")))))))

;; ── 7. the seat may be NAMED, and its chair's key opens it ──────────
;;
;; Bead waymark-fp62.7.23, R-4: one Routine stands for one MODEL, so
;; its session presents the model's key and its fire text names the
;; seat. The key alone still answers for a seat that holds one of its
;; own, which is R-12.12 unchanged.

(def ^:private chair-key
  "The CHAIR's key — the one a model's single Routine presents."
  "Y2hhaXIta2V5LWZvci10aGUtbW9kZWw")

(defn- seat-of
  "One more office on this house, held for whichever model it names."
  [eng nm extra]
  (:row (inv/create!
         eng :seat
         (merge {:name nm
                 :charter "Decide whether a meal belongs on the list."
                 :scope [{:kind "meal" :actions ["accept"]}]
                 :standing_ttl_seconds 604800
                 :cadence_seconds 3600
                 :budget_usd_per_week 5M
                 :sitting_budget_tokens 60000}
                extra)
         {:principal person})))

(deftest a-named-seat-sits-with-its-chairs-key
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-seat! eng)
        _ (inv/invoke! eng :model (:id model) :offer_key {:key chair-key}
                       {:principal person})
        ;; each sit on a session of its own: a bind is per session, and
        ;; a test that reused one would be asking a second question
        sit! (fn [args]
               (let [[sid _] (initialize! h)]
                 (tool h (with-session sid) "waymark_sit" args)))]

    (testing "the chair's key and the seat's NAME bind this session"
      (let [r (sit! {:key chair-key :seat "meal-clerk"})
            answer (doc-of r)]
        (is (false? (:isError r)) (text-of r))
        (is (= "meal-clerk" (:seat answer)))
        (is (= (seats/sitter-id seat) (:sitter answer)))
        (is (= "claude-sit-5" (:model answer))
            "the chair is the model the sitter claims")
        (is (string? (:grant answer)))))

    (testing "the seat's id names it too — a run handed one spells it exactly"
      (is (= "meal-clerk"
             (:seat (doc-of (sit! {:key chair-key :seat (:id seat)}))))))

    (testing "the seat's OWN key still binds it, named or not"
      (is (= "meal-clerk" (:seat (doc-of (sit! {:key a-key :seat "meal-clerk"})))))
      (is (= "meal-clerk" (:seat (doc-of (sit! {:key a-key}))))
          "R-12.12 unchanged: with no seat named the key answers for itself"))

    (testing "a key neither the seat nor its chair holds is the sentence it always was"
      (let [r (sit! {:key "c2VhdC1rZXktbm9ib2R5LWhvbGRz" :seat "meal-clerk"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r)))))

    (testing "and a name no seat answers to says exactly the same thing"
      (let [r (sit! {:key chair-key :seat "no-such-office"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r))
            "uniform: the door is not an oracle over the house's offices")))

    (testing "a chair's key does not open a seat that model is not the chair of"
      (let [other (:row (inv/create! eng :model
                                     {:name "claude-other-5" :display "Other 5"
                                      :vendor "anthropic" :tier "economy"
                                      :price_input_per_mtok 1M
                                      :price_output_per_mtok 5M
                                      :price_cache_read_per_mtok 0.1M
                                      :price_cache_write_per_mtok 1.25M}
                                     {:principal person}))
            _ (seat-of eng "note-clerk" {:held_for [(:id other)]})
            r (sit! {:key chair-key :seat "note-clerk"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r)))))

    (testing "and an interactive seat refuses a Routine's run, named or not (R-10.8)"
      (seat-of eng "training-chair" {:held_for [(:id model)]
                                     :mode "interactive"})
      (let [[sid _] (initialize! h)
            r (tool h {"x-waymark-principal" "lone-agent"
                       "x-waymark-actor-type" "agent"
                       "mcp-session-id" sid}
                    "waymark_sit" {:key chair-key :seat "training-chair"})]
        (is (true? (:isError r)))
        (is (= "The seat `training-chair` is an interactive seat. A person sits here."
               (text-of r)))))))

(deftest the-sit-tool-says-the-seat-may-be-named
  (let [sit (first (filter #(= "waymark_sit" (:name %)) (mcp/listing)))]
    (is (contains? (get-in sit [:inputSchema :properties]) :seat))
    (is (= ["key"] (get-in sit [:inputSchema :required]))
        "the seat is optional: a key that answers for one office needs no name")
    (is (str/includes? mcp/sit-description "pass it as `seat`"))
    (is (str/includes? mcp/instructions "as `seat`")
        "and the connect-time instructions tell a firing to pass it")))

;; ── 8. the key of ONE FIRING (R-12.37) ──────────────────────────────
;;
;; The two keys above are standing: a person mints one and pastes it
;; into a Routine. The meal planner's first firing (2026-09-21) showed
;; what that costs when the step is missed. The session read the
;; seat's name off the fire text and had nothing to sit with. So the
;; engine mints a key for each fire of a seat that has instructions,
;; the fire text carries it, and this door spends it.

(def ^:private fired-instructions
  "What the seat's row holds, and what the fire text puts at the head
  of every firing (R-12.33)."
  "Read the fire text and do what it says. Sit in the seat it names, then walk the rows the sit hands you.")

(defn- seat-row-of
  "The stored seat row, read off storage the way the engine reads it."
  [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :seat (str id) {}))))

(deftest a-firings-own-key-sits-one-time-and-then-answers-nothing
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [model]} (open-seat! eng)
        seat (seat-of eng "fired-clerk" {:held_for [(:id model)]
                                         :instructions fired-instructions})
        ;; the fire is what mints the key. The schedules consumer is
        ;; not running behind this bare handler, so the engine's own
        ;; call stands in for the line the consumer composes.
        key (seats/hold-fire-key! eng seat ((:now-fn eng)))
        sit! (fn [args]
               ;; each sit on a session of its own: a bind is per
               ;; session, and a test that reused one would be asking
               ;; a second question
               (let [[sid _] (initialize! h)]
                 (tool h (with-session sid) "waymark_sit" args)))]

    (testing "the fire minted 128 bits, and the row keeps the hash alone"
      (is (string? key))
      (is (<= 22 (count key)))
      (let [held (get-in (seat-row-of eng (:id seat)) [:data :fire_keys])]
        (is (= 1 (count held)))
        (is (= (seats/key-hash key) (str (:hash (first held)))))
        (is (not (str/includes? (pr-str held) key)))))

    (testing "the key and the seat the fire text named bind this session"
      (let [r (sit! {:key key :seat "fired-clerk"})
            answer (doc-of r)]
        (is (false? (:isError r)) (text-of r))
        (is (= "fired-clerk" (:seat answer)))
        (is (= (seats/sitter-id seat) (:sitter answer)))
        (is (string? (:grant answer)))
        (is (string? (:sitting answer))
            "and the sit opened the sitting this firing is counted against")))

    (testing "a SECOND sit with the same key is refused, in the uniform
              sentence: one key opens one sit"
      (let [r (sit! {:key key :seat "fired-clerk"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r)))))

    (testing "a firing's key answers for the seat it names, and for no
              seat when the call names none"
      (let [k (seats/hold-fire-key! eng seat ((:now-fn eng)))]
        (let [r (sit! {:key k})]
          (is (true? (:isError r)))
          (is (= "No seat answers this key." (text-of r))
              "the fire text names the seat on the line above the key"))
        (is (= "fired-clerk" (:seat (doc-of (sit! {:key k :seat "fired-clerk"}))))
            "and the same key still opens the seat it was minted for")))

    (testing "a key this seat never minted says what it always said"
      (let [r (sit! {:key "bm90LWEtbWludGVkLWtleS1hdC1hbGw" :seat "fired-clerk"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r)))))

    (testing "and a Routine whose prompt carries a chair key still sits"
      (inv/invoke! eng :model (:id model) :offer_key {:key chair-key}
                   {:principal person})
      (is (= "fired-clerk"
             (:seat (doc-of (sit! {:key chair-key :seat "fired-clerk"})))))
      (is (= "fired-clerk"
             (:seat (doc-of (sit! {:key chair-key :seat "fired-clerk"}))))
          "a standing key is spent by nothing, and it sits again"))))

(deftest a-fired-run-that-loses-its-bind-sits-again-in-its-own-sitting
  ;; R-12.16 and R-12.37: the bind lives in engine memory and dies with
  ;; a restart. The firing's key is spent at the first sit, but the
  ;; sitting it opened keeps the key's hash, so the same run — the same
  ;; harness session — re-sits THAT sitting while it is open, and the
  ;; key opens nothing else.
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [model]} (open-seat! eng)
        seat (seat-of eng "fired-clerk" {:held_for [(:id model)]
                                         :instructions fired-instructions})
        key (seats/hold-fire-key! eng seat ((:now-fn eng)))
        run "session_01TheRunADeployCut"
        sit! (fn [args]
               (let [[sid _] (initialize! h)]
                 [sid (tool h (with-session sid) "waymark_sit" args)]))
        restart! #(reset! (:mcp-sessions eng) {})
        [_ first-r] (sit! {:key key :seat "fired-clerk" :session run})
        first-sat (doc-of first-r)
        sitting-id (str (:sitting first-sat))
        sitting-row (fn []
                      (store/with-tx (:storage eng)
                        (fn [tx] (store/load-row (:storage eng) tx :sitting
                                                 sitting-id {}))))]

    (testing "the first sit opens a sitting that keeps the key's hash alone"
      (is (false? (:isError first-r)) (text-of first-r))
      (is (= (seats/key-hash key)
             (get-in (sitting-row) [:data :fire_key_hash])))
      (is (not (str/includes? (pr-str (sitting-row)) key))))

    (restart!)

    (testing "a different harness session is refused in the uniform sentence"
      (let [[_ r] (sit! {:key key :seat "fired-clerk" :session "session_other"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r)))))

    (testing "a re-sit that names no session is refused"
      (let [[_ r] (sit! {:key key :seat "fired-clerk"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r)))))

    (let [[sid r] (sit! {:key key :seat "fired-clerk" :session run})
          again (doc-of r)]
      (testing "the same key and session land on the SAME sitting and grant"
        (is (false? (:isError r)) (text-of r))
        (is (= sitting-id (str (:sitting again))))
        (is (= (:grant first-sat) (:grant again)))
        (when-some [k (get-in first-sat [:transcript :key])]
          (is (string? (get-in again [:transcript :key])))
          (is (not= k (get-in again [:transcript :key]))
              "with a fresh transcript key")))

      (testing "the key still opens no second sitting"
        (let [open (store/with-tx (:storage eng)
                     (fn [tx] (store/query-rows (:storage eng) tx :sitting
                                                {:seat (str (:id seat)) :state :open}
                                                {:limit 10})))]
          (is (= 1 (count open)))))

      (testing "the re-bound session closes its own sitting"
        (let [c (tool h (with-session sid) "waymark_invoke"
                      {:kind "sitting" :id sitting-id :action "close"
                       :input {:input_tokens 10 :output_tokens 5
                               :cache_read_tokens 0 :cache_write_tokens 0
                               :turns 1 :harness_session run}})]
          (is (false? (:isError c)) (text-of c))
          (is (= :closed (:state (sitting-row)))))))

    (restart!)

    (testing "after the sitting closed, the key answers nothing"
      (let [[_ r] (sit! {:key key :seat "fired-clerk" :session run})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r)))))))

(deftest the-sit-tool-says-the-key-may-come-from-the-fire-text
  (let [sit (first (filter #(= "waymark_sit" (:name %)) (mcp/listing)))]
    (is (str/includes? (str (get-in sit [:inputSchema :properties :key
                                         :description]))
                       "`Key:`")
        "the tool's own field says where a firing's key is")
    (is (str/includes? mcp/sit-description "`Key:`"))
    (is (str/includes? mcp/instructions "`Key:`")
        "and the connect-time instructions say it too")))
;; ── 9. the delegate a LOCAL engine can spell (dev headers) ──────────
;;
;; Everything above mints a bearer against a locally-generated IdP,
;; because that is how a delegate is made in production. A local
;; engine has no IdP: `dev-principal` is the whole of its identity
;; layer, and it had a spelling for the actor type and for the model
;; claim but none for `acts-for` — so no engine without an IdP in
;; front of it could produce a delegate, and the keyed sitter was the
;; one feature a person could not try on their own machine.
;;
;; `x-waymark-acts-for` closes that, beside the two spellings already
;; there. It is not a new authority: dev headers are already the whole
;; trust boundary of an engine that configures no :oidc, and this says
;; one more thing about the principal they already name.

(defn- local-engine
  "A house with NO :oidc — the local posture, where dev headers are
  the only identity there is."
  []
  (engine/engine {:storage (memory/storage) :resources [fx/meal]}))

(def ^:private dev-delegate
  "A tool's session, acting for the person at the keyboard."
  {"x-waymark-principal" "sandbox-tool"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private dev-bare
  "The same session with nobody behind it."
  {"x-waymark-principal" "sandbox-tool"
   "x-waymark-actor-type" "agent"})

(defn- dev-initialize!
  "The handshake under dev headers, answering the session id."
  [h headers]
  (get-in (rpc h headers "initialize"
                {:protocolVersion mcp/protocol-version
                 :capabilities {} :clientInfo {:name "sandbox" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(deftest a-dev-header-delegate-sits-with-the-key
  (let [eng (local-engine)
        h (engine/handler eng)
        {:keys [seat]} (open-seat! eng)
        sid (dev-initialize! h dev-delegate)
        result (tool h (assoc dev-delegate "mcp-session-id" sid)
                     "waymark_sit" {:key a-key})
        doc (doc-of result)]
    (testing "the key binds the session, and the seat's own grant comes back"
      (is (not (:isError result)) (text-of result))
      (is (= "meal-clerk" (:seat doc)))
      (is (= (seats/sitter-id seat) (:sitter doc)))
      (is (some? (:grant doc)) "the sit mints or reuses the seat's grant")
      (is (some? (:sitting doc)) "and opens the sitting it is counted against"))))

(deftest a-dev-header-session-with-nobody-behind-it-does-not-sit
  (let [eng (local-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        sid (dev-initialize! h dev-bare)
        result (tool h (assoc dev-bare "mcp-session-id" sid)
                     "waymark_sit" {:key a-key})]
    (testing "acts-for is what the door reads, not the actor type"
      (is (:isError result))
      (is (str/includes? (text-of result) "person")
          (text-of result)))))

;; ── 7. the sitter wears the roles its row holds ─────────────────────

(def ^:private engine-actor
  (t/principal {:id "test-engine" :type :system :display "Engine"}))

(defn- assign-roles!
  "assign_roles is fenced (it prefills :roles), so the write carries
  the row's current etag, read just before it."
  [eng member-id roles]
  (let [row (store/with-tx (:storage eng)
              (fn [tx] (store/load-row (:storage eng) tx :member member-id {})))]
    (inv/invoke! eng :member member-id :assign_roles {:roles roles}
                 {:principal engine-actor
                  :if-match (inv/etag :member member-id (:version row))})))

(deftest the-sitter-wears-the-roles-its-member-row-holds
  ;; gate! unions a member's roles onto the credential that arrives,
  ;; but a sitter never arrives: its principal is built from the seat
  ;; after the gate ran on the person's connector. A role assigned to
  ;; the seat's own `seat:<id>` row must still be worn, or every role
  ;; guard refuses the office.
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat]} (open-seat! eng)
        sitter-id (seats/sitter-id seat)
        [sid _] (initialize! h)
        _ (tool h (with-session sid) "waymark_sit" {:key a-key})
        roles-seen (fn []
                     (set (get-in (doc-of (tool h (with-session sid)
                                                "waymark_discover" {}))
                                  [:principal :roles])))]

    (testing "a sitter whose row holds no role wears none"
      (is (= #{} (roles-seen))))

    (inv/create! eng :role {:name "ranker"} {:principal engine-actor})
    (assign-roles! eng sitter-id ["ranker"])

    (testing "a role assigned to the sitter row after the sit is worn on the next call"
      (is (= #{"ranker"} (roles-seen))))

    (testing "and a sit after the assignment answers with it too"
      (let [[other _] (initialize! h)]
        (tool h (with-session other) "waymark_sit" {:key a-key})
        (is (= #{"ranker"}
               (set (get-in (doc-of (tool h (with-session other)
                                          "waymark_discover" {}))
                            [:principal :roles]))))))

    (assign-roles! eng sitter-id [])

    (testing "a role taken away is gone from the next call"
      (is (= #{} (roles-seen))))))

;; ── several sittings at once: each on its own row ─────────────────────
;;
;; A seat whose `max_open_sittings` is above one runs several sits at
;; once. The claim of a row is taken in the transaction that finds it
;; free (`seats/claim-rows-atomically!`), so two sits at the same
;; instant never walk one row, and a fire whose text names a row walks
;; that row, or the next free one when another sitting holds it.

(deftest two-sits-in-the-same-instant-never-get-the-same-row
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:rows_per_firing 1 :max_open_sittings 3})
        gas (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        note (post! eng "The school note" "house" "2026-09-18T08:00:00Z")
        sit-as! (fn [run]
                  (let [[sid _] (initialize! h)
                        r (tool h (with-session sid) "waymark_sit"
                                {:key walk-key :session run})]
                    [r (doc-of r)]))]
    (dotimes [i 5]
      (let [start (java.util.concurrent.CountDownLatch. 1)
            run! (fn [run]
                   (future (.await start) (sit-as! run)))
            a (run! (str "run-a-" i))
            b (run! (str "run-b-" i))
            _ (.countDown start)
            [ra da] @a
            [rb db] @b
            rows-a (mapv :id (get-in da [:walk :rows]))
            rows-b (mapv :id (get-in db [:walk :rows]))]
        (testing (str "round " i ": both sits answer, each in its own sitting")
          (is (false? (:isError ra)) (text-of ra))
          (is (false? (:isError rb)) (text-of rb))
          (is (not= (:sitting da) (:sitting db))))
        (testing (str "round " i ": no row is handed twice")
          (is (empty? (filter (set rows-a) rows-b)))
          (is (= #{(str (:id gas)) (str (:id note))} (into (set rows-a) rows-b))
              "and between them both rows are walked"))
        ;; the claims end with the sittings, so the next round races
        ;; for both rows again
        (doseq [s [(:sitting da) (:sitting db)]]
          (inv/invoke! eng :sitting (str s) :abandon nil
                       {:principal seats/seats-actor}))))))

(deftest a-fire-that-names-a-row-walks-that-row-or-the-next-free-one
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        seat (open-walk-seat! eng {:rows_per_firing 1 :max_open_sittings 3
                                   :instructions fired-instructions})
        gas (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        note (post! eng "The school note" "house" "2026-09-18T08:00:00Z")
        ;; a wake's text: the transition as JSON, naming the row
        text-of-wake (fn [row]
                       (wire/write-json {:kind "post" :id (str (:id row))
                                         :action "file" :from "queued"
                                         :to "queued"}))
        sit-fired! (fn [row run]
                     (let [k (seats/hold-fire-key! eng (seat-row-of eng (:id seat))
                                                   ((:now-fn eng))
                                                   (text-of-wake row))
                           [sid _] (initialize! h)
                           r (tool h (with-session sid) "waymark_sit"
                                   {:key k :seat "post-clerk" :session run})]
                       [r (doc-of r)]))]

    (testing "the key keeps the row the fire text named"
      (let [k (seats/hold-fire-key! eng (seat-row-of eng (:id seat))
                                    ((:now-fn eng)) (text-of-wake gas))]
        (is (= (str (:id gas))
               (seats/fire-key-row eng (seat-row-of eng (:id seat)) k)))
        (is (nil? (seats/fire-key-row
                   eng (seat-row-of eng (:id seat))
                   (seats/hold-fire-key! eng (seat-row-of eng (:id seat))
                                         ((:now-fn eng)) "Walk the queue.")))
            "a person's prose names no row")))

    (let [[r1 first-run] (sit-fired! note "run-note")]
      (testing "the fire named the newer row, and the sit hands that row alone"
        (is (false? (:isError r1)) (text-of r1))
        (is (= [(str (:id note))] (mapv :id (get-in first-run [:walk :rows])))
            "not the queue's oldest row: the row the run was sent to walk")))

    (let [[r2 second-run] (sit-fired! note "run-again")]
      (testing "a fire naming a row another sitting holds is handed the next
                free row, and told so"
        (is (false? (:isError r2)) (text-of r2))
        (is (= [(str (:id gas))] (mapv :id (get-in second-run [:walk :rows]))))
        (is (str/includes? (str (:note second-run)) (str (:id note))))
        (is (str/includes? (str (:note second-run))
                           "held by another open sitting"))))

    (let [[r3 third] (sit-fired! gas "run-late")]
      (testing "a run that finds every row held is handed none, and told to stop"
        (is (false? (:isError r3)) (text-of r3))
        (is (empty? (get-in third [:walk :rows])))
        (is (str/includes? (str (:note third)) "nothing for you to walk"))))))

(deftest a-re-sat-session-is-handed-the-row-only-when-no-other-sitting-holds-it
  ;; Ticket d7c854b3: a seat of several open sittings walks one row.
  ;; Sitting A is handed it and ends; a new sit under A's own
  ;; harness_session opens a NEW sitting. That sitting is handed the
  ;; row only while no other open sitting of the seat holds it
  ;; (`seats/claimed-rows`, `seats/unwalkable-rows`, e031e479).
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:rows_per_firing 1 :max_open_sittings 3})
        gas (post! eng "The gas bill" "house" "2026-09-18T07:00:00Z")
        gas-id (str (:id gas))
        sit-as! (fn [run]
                  (let [[sid _] (initialize! h)
                        r (tool h (with-session sid) "waymark_sit"
                                {:key walk-key :session run})]
                    [r (doc-of r)]))
        rows-of (fn [d] (mapv :id (get-in d [:walk :rows])))
        withheld-of (fn [d] (set (map :id (get-in d [:walk :withheld]))))
        abandon! (fn [d] (inv/invoke! eng :sitting (str (:sitting d)) :abandon nil
                                      {:principal seats/seats-actor}))
        [ra a] (sit-as! "run-a")
        [rb b] (sit-as! "run-b")]
    (testing "sitting A is handed the one row, and B, open beside it, none"
      (is (false? (:isError ra)) (text-of ra))
      (is (false? (:isError rb)) (text-of rb))
      (is (not= (:sitting a) (:sitting b)))
      (is (= [gas-id] (rows-of a)))
      (is (empty? (rows-of b)))
      (is (contains? (withheld-of b) gas-id)
          "the row A holds is withheld from B, not dropped from its sight"))
    (abandon! a)
    (let [[rc c] (sit-as! "run-a")]
      (testing "a re-sit under A's harness_session opens a new sitting"
        (is (false? (:isError rc)) (text-of rc))
        (is (not= (:sitting a) (:sitting c)))
        (is (not= (:sitting b) (:sitting c))))
      (testing "and is handed the row, since no open sitting holds it"
        (is (= [gas-id] (rows-of c))))
      (let [[rb2 b2] (sit-as! "run-b")]
        (testing "B's re-sit is still handed nothing: the new sitting holds it"
          (is (false? (:isError rb2)) (text-of rb2))
          (is (not= (:sitting c) (:sitting b2)))
          (is (empty? (rows-of b2)))
          (is (contains? (withheld-of b2) gas-id))))
      (abandon! c)
      (let [[rb3 b3] (sit-as! "run-b")
            [rd d] (sit-as! "run-a")]
        (testing "once the new sitting ends, B takes the row"
          (is (false? (:isError rb3)) (text-of rb3))
          (is (= [gas-id] (rows-of b3))))
        (testing "and the next re-sit under A's harness_session is handed none"
          (is (false? (:isError rd)) (text-of rd))
          (is (not= (:sitting c) (:sitting d)))
          (is (empty? (rows-of d)))
          (is (contains? (withheld-of d) gas-id)))))))

(deftest a-re-sit-reuses-its-own-sitting-not-the-grants-newest
  ;; Tickets 8c72fd3a and 5d4bbab6: every sitting of a seat shares the
  ;; seat's grant, so the grant's NEWEST open sitting is not this run's.
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:max_open_sittings 3})
        sit! (fn [sid args]
               (let [r (tool h (with-session sid) "waymark_sit"
                             (merge {:key walk-key} args))]
                 (is (false? (:isError r)) (text-of r))
                 (str (:sitting (doc-of r)))))
        [sid-b _] (initialize! h)
        [sid-a _] (initialize! h)
        b (sit! sid-b {:session "run-b"})
        c (sit! sid-a {:session "run-a"})]
    (testing "B's re-sit reuses sitting b while a newer c is open"
      (is (not= b c))
      (let [[sid-b2 _] (initialize! h)]
        (is (= b (sit! sid-b2 {:session "run-b"})))))
    (testing "a bound session gets its own sitting back"
      (is (= b (sit! sid-b {})))
      (is (= c (sit! sid-a {}))))
    (testing "two session-less re-sits do not both claim the same sibling"
      (let [[s1 _] (initialize! h)
            [s2 _] (initialize! h)
            d (sit! s1 {})
            e (sit! s2 {})]
        (is (not (contains? #{b c} d)))
        (is (not= d e))))))

(deftest a-session-less-re-sit-after-a-drop-reuses-its-claimed-sitting
  ;; Ticket 7496403e: the sit claims its sitting for its connector
  ;; session. A run that passes no `session` and reconnects on a NEW
  ;; connector session finds the claim held by the dropped one, which
  ;; lives until the session TTL; once that session is unheard past the
  ;; seat's idle limit the claim is free, and exactly one sit takes it.
  (let [eng (fresh-engine [fx/meal post])
        h (engine/handler eng)
        _ (open-walk-seat! eng {:max_open_sittings 3})
        sit! (fn [sid]
               (let [r (tool h (with-session sid) "waymark_sit" {:key walk-key})]
                 (is (false? (:isError r)) (text-of r))
                 (str (:sitting (doc-of r)))))
        unheard! (fn [sid]
                   (swap! (:mcp-sessions eng) update sid assoc :touched
                          (.minusSeconds (java.time.Instant/now) 7200)))
        [dropped _] (initialize! h)
        a (sit! dropped)]
    (testing "while the first session is heard from, a new one gets a fresh sitting"
      (let [[s1 _] (initialize! h)]
        (is (not= a (sit! s1)))))
    (unheard! dropped)
    (testing "once the dropped session is unheard past the idle limit, the re-sit gets the same sitting"
      (let [[s2 _] (initialize! h)]
        (is (= a (sit! s2)))
        (unheard! s2)))
    (testing "two concurrent session-less sits get that sitting once and one fresh sitting"
      (let [[s3 _] (initialize! h)
            [s4 _] (initialize! h)
            f3 (future (sit! s3))
            f4 (future (sit! s4))
            got [@f3 @f4]]
        (is (not= (first got) (second got)))
        (is (= 1 (count (filter #{a} got))))))))
