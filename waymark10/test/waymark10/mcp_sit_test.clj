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
         grant admits, which behind a wall is nothing")))
