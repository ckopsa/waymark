(ns waymark10.mcp-schedule-test
  "`at` on waymark_invoke (docs/spec-scheduled-actions.md, child 3,
  R-7.2): the call is stored as a scheduled_action and not made. Memory
  storage, the real routes through the in-process door and a fixed
  clock. The run is child 1c's and is not exercised here."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)
           (java.time Instant)))

(def ^:private not-before-monday
  "A wall its scheduler expects to lift by the time."
  (g/guard {:name :not-before-monday
            :explain "Chores are scrubbed from Monday on."
            :check (fn [_row _inp _ctx] (t/deny))}))

(def ^:private chore
  "The row a scheduled call acts on."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}
     :scrub {:from #{:open} :to :done
             :guards [not-before-monday]
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Scrub" :order 3}}}}))

(def ^:private now (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [chore]
                  :now-fn (constantly now)}))

(defn- chore! [eng]
  (:id (:row (inv/create! eng :chore {:title "Dishes"} {:principal person}))))

(defn- tool [eng tool-name args]
  (mcp/call-tool eng (mcp/door eng) {:principal person} tool-name args))

(defn- doc [out] (wire/read-json (get-in out [:content 0 :text])))

(defn- chore-state [eng c]
  (:state (doc (tool eng "waymark_get" {:kind "chore" :id c}))))

(defn- scheduled-count
  "How many scheduled actions the person can see."
  [eng]
  (count (get-in (doc (tool eng "waymark_query" {:kind "scheduled_action"}))
                 [:data :items])))

(defn- later
  "`finish` on a chore at 08:30 the next morning in Denver, with `extra`
  laid over the call."
  [eng c extra]
  (tool eng "waymark_invoke"
        (merge {:kind "chore" :id c :action "finish"
                :at "2026-10-02T08:30" :zone "America/Denver"}
               extra)))

(deftest an-invoke-with-at-stores-the-call-and-moves-nothing
  (let [eng (fresh-engine)
        c (chore! eng)
        out (later eng c {})
        d (doc out)]
    (is (not (:isError out)) "an answer, not a refusal")
    (is (true? (:scheduled d)))
    (is (= "open" (chore-state eng c)) "the call was not made")
    (is (= 1 (scheduled-count eng)) "one scheduled row")
    (testing "the answer is the scheduled row"
      (let [row (doc (tool eng "waymark_get" {:kind "scheduled_action"
                                              :id (:scheduled_action d)}))]
        (is (= "scheduled" (:state row)))
        (is (= {:kind "chore" :action "finish" :id (str c)}
               (select-keys (get-in row [:data :target]) [:kind :action :id])))
        (is (= (:summary row) (:summary d)))))
    (testing "run_at is named in the given zone, and the rule is the default"
      (is (= "2026-10-02T08:30:00-06:00" (:run_at d)))
      (is (= "America/Denver" (:zone d)))
      (is (= "state" (:validity d))))
    (testing "the scheduler's fields ride the call"
      (let [d (doc (later eng c {:validity "strict" :grace_seconds 120}))
            row (doc (tool eng "waymark_get" {:kind "scheduled_action"
                                              :id (:scheduled_action d)}))]
        (is (= "strict" (:validity d)))
        (is (= 120 (get-in row [:data :grace_seconds])))))))

(deftest dry-run-with-at-rehearses-the-check-and-writes-nothing
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "a call the door would take"
      (let [out (later eng c {:dry_run true})]
        (is (not (:isError out)) "the check's verdict")
        (is (nil? (:scheduled (doc out))) "and no scheduled answer")))
    (testing "a call the door would refuse"
      (let [out (later eng c {:dry_run true :action "scrub"})]
        (is (true? (:isError out)))
        (is (re-find #"Monday" (str (:detail (doc out)))))))
    (is (zero? (scheduled-count eng)) "neither wrote a row")
    (is (= "open" (chore-state eng c)))))

(deftest a-refused-scheduling-check-is-the-doors-own-refusal
  (let [eng (fresh-engine)
        c (chore! eng)
        out (later eng c {:action "scrub"})
        d (doc out)]
    (is (true? (:isError out)))
    (is (= 409 (:status d)))
    (is (re-find #"Chores are scrubbed from Monday on" (str (:detail d)))
        "the guard's sentence, as the door wrote it")
    (is (zero? (scheduled-count eng)) "what would be refused now is refused now")
    (testing "a refusal the scheduler expects to lift is passed over"
      (let [out (later eng c {:action "scrub"
                              :expect_refusals ["not-before-monday"]})]
        (is (not (:isError out)))
        (is (true? (:scheduled (doc out))))
        (is (= 1 (scheduled-count eng)))))))

(deftest bulk-with-at-is-refused
  (let [eng (fresh-engine)
        c (chore! eng)]
    (doseq [[label many] [["ids" {:ids [(str c)]}]
                          ["items" {:items [{:id (str c)}]}]]]
      (testing label
        (let [out (tool eng "waymark_invoke"
                        (merge {:kind "chore" :action "finish"
                                :at "2026-10-02T08:30:00-06:00"}
                               many))
              d (doc out)]
          (is (true? (:isError out)))
          (is (= 422 (:status d)))
          (is (re-find #"scheduled job" (str (:detail d)))))))
    (is (zero? (scheduled-count eng)))
    (is (= "open" (chore-state eng c)))))

(deftest conditions-are-refused-naming-child-5
  (let [eng (fresh-engine)
        c (chore! eng)]
    (doseq [[label extra] [["the rule" {:validity "conditions"}]
                           ["a condition" {:conditions {:state "open"}}]]]
      (testing label
        (let [out (later eng c extra)]
          (is (true? (:isError out)))
          (is (re-find #"child 5" (str (:detail (doc out))))))))
    (is (zero? (scheduled-count eng)))))

;; ── under a grant that names no scheduled_action (ticket 14c69581) ──

(def ^:private planner
  "A delegate: an agent that acts for Colton."
  (assoc (t/principal {:id "planner" :type :agent :display "Planner"})
         :acts-for "colton"))

(def ^:private stray
  "An agent nobody granted anything."
  (t/principal {:id "stray" :type :agent :display "Stray"}))

(defn- leash!
  "A grant minted to the planner over `scope`, and the session the
  planner then arrives with: the grant worn, as the connector door's
  delegate wears it."
  [eng scope]
  (inv/create! eng :grant {:audience "planner" :scope scope}
               {:principal grants/approvals-actor
                :id "grant-planner"
                :mint? true})
  {:principal planner :visibility (grants/worn-visibility eng planner)})

(defn- tool-as [eng session tool-name args]
  (mcp/call-tool eng (mcp/door eng) session tool-name args))

(defn- later-as
  "`later`, from `session`."
  [eng session c extra]
  (tool-as eng session "waymark_invoke"
           (merge {:kind "chore" :id c :action "finish"
                   :at "2026-10-02T08:30" :zone "America/Denver"}
                  extra)))

(deftest a-delegate-schedules-a-door-its-grant-admits
  (let [eng (fresh-engine)
        c (chore! eng)
        session (leash! eng [{:kind "chore" :actions ["finish"]}])
        out (later-as eng session c {})
        d (doc out)]
    (is (some? (:visibility session)) "the planner wears its grant")
    (is (not (:isError out)) (str d))
    (is (true? (:scheduled d)))
    (is (= "open" (chore-state eng c)) "the call was not made")
    (testing "the stamps are the caller's"
      (let [row (doc (tool eng "waymark_get" {:kind "scheduled_action"
                                              :id (:scheduled_action d)}))]
        (is (= "planner" (get-in row [:data :scheduler])))
        (is (= {:id "planner" :type "agent" :acts_for "colton"}
               (get-in row [:data :acts_as])))
        (is (= "grant-planner" (get-in row [:data :grant])))))))

(deftest a-door-the-grant-does-not-admit-is-refused-as-that-door-refuses
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "a grant that names another door of the kind"
      (let [session (leash! eng [{:kind "chore" :actions ["reopen"]}])
            out (later-as eng session c {})]
        (is (some? (:visibility session)) "the planner wears its grant")
        (is (true? (:isError out)))
        (is (re-find #"no door `finish` on `chore`" (str (:detail (doc out)))))))
    (testing "an agent that wears no grant has no door to schedule"
      (let [out (later-as eng {:principal stray
                               :visibility (grants/bootstrap-visibility eng stray)}
                          c {})]
        (is (true? (:isError out)))
        (is (re-find #"no door `finish` on `chore`" (str (:detail (doc out)))))))
    (is (zero? (scheduled-count eng)) "neither wrote a row")
    (is (= "open" (chore-state eng c)))))

(deftest a-delegate-lists-only-what-it-scheduled
  (let [eng (fresh-engine)
        c (chore! eng)
        session (leash! eng [{:kind "chore" :actions ["finish"]}])
        theirs (:scheduled_action (doc (later eng c {})))
        mine (:scheduled_action (doc (later-as eng session c {})))
        listed (get-in (doc (tool-as eng session "waymark_query"
                                     {:kind "scheduled_action"}))
                       [:data :items])]
    (is (some? theirs))
    (is (some? mine))
    (is (= 2 (scheduled-count eng)) "the person sees both")
    (is (= 1 (count listed)) "the delegate lists its own")
    (is (not (:isError (tool-as eng session "waymark_get"
                                {:kind "scheduled_action" :id mine}))))
    (is (true? (:isError (tool-as eng session "waymark_get"
                                  {:kind "scheduled_action" :id theirs})))
        "and the person's row is not there")))

;; ── counting: the seat's sitting, through the connector door ────────
;; mcp_served_test's helpers, spelled again because they are private
;; there by design.

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "schedule-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "schedule-key" :alg "RS256" :use "sig")]})

(defn- bearer
  "Colton's connector token. It outlives the engine's fixed clock and
  the wall clock, whichever the door reads."
  []
  {"authorization"
   (str "Bearer "
        (jwt/sign {:iss issuer :aud audience
                   :sub "colton" :azp "connector" :name "Colton Kopsa"
                   :exp (+ (max (quot (System/currentTimeMillis) 1000)
                                (.getEpochSecond ^Instant now))
                           600)}
                  (.getPrivate keypair)
                  {:alg :rs256 :header {:kid "schedule-key"}}))})

(defn- door-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [chore]
                  :now-fn (constantly now)
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- initialize! [h]
  (get-in (rpc h (bearer) "initialize"
               {:protocolVersion mcp/protocol-version
                :capabilities {} :clientInfo {:name "schedule" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- door-tool
  "One tool call through the real handler, on session `sid`."
  [h sid tool-name args]
  (let [resp (rpc h (assoc (bearer) "mcp-session-id" sid)
                  "tools/call" {:name tool-name :arguments args})]
    (get-in (some-> (:body resp) wire/read-json) [:result])))

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "bWNwLXNjaGVkdWxlLWF0LWtleS0wMDAwMDAx")

(defn- open-seat!
  "A seat whose scope admits `finish` on a chore and names no
  scheduled_action, with its key offered."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "schedule-test-model" :display "Schedule 1"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create! eng :seat
                                {:name "schedule-one"
                                 :charter "Finish the chores that are due."
                                 :scope [{:kind "chore" :actions ["finish"]}]
                                 :held_for [(:id model)]
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal person}))]
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    seat))

(defn- served-calls
  "How many answers the sitting was served under `tool-name`."
  [eng sitting-id tool-name]
  (let [row (store/with-tx (:storage eng)
              (fn [tx] (store/load-row (:storage eng) tx :sitting (str sitting-id) {})))]
    (long (or (get-in row [:data :served (keyword tool-name) :calls]) 0))))

(deftest an-at-call-is-one-served-answer-on-the-seats-sitting
  (let [eng (door-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        c (chore! eng)
        sid (initialize! h)
        sat (door-tool h sid "waymark_sit" {:key a-key})
        sitting (str (:sitting (doc sat)))
        out (door-tool h sid "waymark_invoke"
                       {:kind "chore" :id (str c) :action "finish"
                        :at "2026-10-02T08:30" :zone "America/Denver"})
        d (doc out)]
    (is (not (:isError sat)) (str (doc sat)))
    (is (not (:isError out)) (str d))
    (is (true? (:scheduled d)) "the seat's scope names no scheduled_action")
    (is (= "open" (chore-state eng c)) "the call was not made")
    (is (= 1 (served-calls eng sitting "waymark_invoke"))
        "one served answer, on the sitting the session is bound to")))
