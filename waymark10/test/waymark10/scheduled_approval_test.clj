(ns waymark10.scheduled-approval-test
  "Approval at scheduling (docs/spec-scheduled-actions.md R-4.3, child
  4a): a call that would be held for a person is approved when it is
  scheduled, and its run needs no second tap. Memory storage, the real
  engine and a clock a test moves by hand. The wire boundary's effects
  are called as the router calls them."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :as r]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)
           (java.time Instant)))

(g/defguard the-person-says-when
  {:hold true
   :reads [:principal :held_call :within]
   :explain "Held for the person's tap. An agent finishes a chore only when its person said yes."}
  [row _inp ctx]
  (if (or (not= :agent (:type (:principal ctx)))
          (holds/approved-hold? ctx :approval_chore :finish (:id row)))
    (t/allow)
    (t/deny)))

(def ^:private chore
  "The row a scheduled call acts on. An agent's `finish` is a hold."
  (r/resource
   {:kind :approval_chore
    :plural "approval_chores"
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
              :guards [the-person-says-when]
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

(def ^:private t0 (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk
  "An agent that acts for Colton, and wears a grant."
  (assoc (t/principal {:id "approval-clerk" :type :agent}) :acts-for "colton"))

(defn- member! [eng id data]
  (inv/create! eng :member
               (merge {:display (str id) :actor_type "human"} data)
               {:principal scheduled/engine-actor :id id}))

(defn- world []
  (let [clock (atom t0)
        eng (engine/engine {:storage (memory/storage)
                            :resources [chore]
                            :now-fn (fn [] @clock)})]
    (member! eng "colton" {})
    (member! eng "approval-clerk" {:actor_type "agent" :acts_for "colton"})
    {:eng eng :clock clock}))

(defn- at! [clock s] (reset! clock (Instant/parse s)))

(defn- row-of [eng kind id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(defn- state-of [eng kind id] (some-> (row-of eng kind id) :state name))
(defn- why-of [eng id] (get-in (row-of eng :scheduled_action id) [:data :outcome_why]))
(defn- held-of [eng id] (get-in (row-of eng :scheduled_action id) [:data :held_call]))

(defn- held-calls [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (count (store/query-rows st tx :held_call {} {:limit 50}))))))

(defn- chore! [eng]
  (:id (:row (inv/create! eng :approval_chore {:title "Dishes"} {:principal person}))))

(defn- grant!
  "A grant over `finish` for the clerk, minted by its person and
  accepted → its id."
  [eng]
  (let [gid (str (:id (:row (inv/create! eng :grant
                                         {:audience (:id clerk)
                                          :scope [{:kind "approval_chore"
                                                   :actions ["finish"]}]}
                                         {:principal person}))))]
    (inv/invoke! eng :grant gid :accept {} {:principal clerk})
    gid))

(defn- worn
  "The clerk at the wire, under the grant it presents."
  [gid]
  {:principal clerk
   :grant {:id gid :action? (fn [_ _] true) :row? (fn [_ _] true)}})

(defn- effects
  "What the router does after a committed write on a scheduled action."
  [eng action out]
  (scheduled/after-write! eng (get (inv/resources eng) :scheduled_action) action out))

(defn- schedule!
  "The clerk schedules `finish` on a chore, for 12:05 unless told. → the
  row's id."
  ([eng c gid] (schedule! eng c gid "2026-10-01T12:05:00Z"))
  ([eng c gid run-at]
   (:id (:row (effects eng :create
                       (inv/create! eng :scheduled_action
                                    {:target {:kind "approval_chore" :action "finish"
                                              :id (str c)}
                                     :run_at run-at}
                                    (worn gid)))))))

(defn- decide!
  "A verdict on a held call, with the two effects the router owes it."
  [eng hid action body who]
  (let [rdef (get (inv/resources eng) :held_call)]
    (->> (inv/invoke! eng :held_call (str hid) action body
                      {:principal who :idempotency-key (str (random-uuid))})
         (held/after-allow! eng rdef action)
         (scheduled/after-write! eng rdef action))))

(defn- refused-by
  "The guard that refused `f`, or nil when it was not refused."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (some-> (:guard (ex-data e)) name keyword))))

(defn- log-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (vec (store/transitions st tx {:kind kind :resource-id id} {}))))))

;; ── the engine-door cases of R-8 item 4 ──────────────────────────────

(deftest the-caller-does-not-approve-its-own-schedule
  (let [{:keys [eng]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        hid (held-of eng id)
        call (:data (row-of eng :held_call hid))]
    (testing "a call that would be held is born proposed, and one held call asks about it"
      (is (= "proposed" (state-of eng :scheduled_action id)))
      (is (= 1 (held-calls eng)))
      (is (= "held" (state-of eng :held_call hid)))
      (is (= {:kind "scheduled_action" :action "arm" :id (str id)}
             (select-keys (:door call) [:kind :action :id])))
      (is (= "approval-clerk" (:caller call)))
      (is (= "colton" (:owner call))))
    (testing "the person reads the call and the time"
      (is (= (str "finish approval_chore " c " · 2026-10-01 12:05") (:shown call)))
      (is (str/includes? (str (:why call)) "Held for the person's tap")))
    (testing "the scheduler is the held call's caller, and the caller does not decide"
      (is (= :the-caller-does-not-decide
             (refused-by #(decide! eng hid :allow {} clerk))))
      (is (= "proposed" (state-of eng :scheduled_action id))))
    (testing "no hand at the wire arms it"
      (is (some? (try (inv/invoke! eng :scheduled_action id :arm {} (worn gid))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))))
      (is (= "proposed" (state-of eng :scheduled_action id))))))

(deftest allow-arms
  (let [{:keys [eng]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        hid (held-of eng id)]
    (decide! eng hid :allow {} person)
    (is (= "scheduled" (state-of eng :scheduled_action id)))
    (is (= "done" (state-of eng :held_call hid)))
    (is (= (str hid) (str (held-of eng id))))
    (is (= "open" (state-of eng :approval_chore c)) "the yes runs nothing before its time")))

(deftest refuse-cancels
  (let [{:keys [eng clock]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        hid (held-of eng id)]
    (decide! eng hid :refuse {:reason "Not this week."} person)
    (is (= "cancelled" (state-of eng :scheduled_action id)))
    (is (= "Not this week." (why-of eng id)))
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (is (= "open" (state-of eng :approval_chore c)))))

(deftest expiry-skips-with-the-sentence
  (let [sentence "Nobody approved this before its time."]
    (testing "the time passes first"
      (let [{:keys [eng clock]} (world)
            gid (grant! eng)
            c (chore! eng)
            id (schedule! eng c gid)]
        (at! clock "2026-10-01T12:05:20Z")
        (is (= {:ran 0 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
        (is (= "skipped" (state-of eng :scheduled_action id)))
        (is (= sentence (why-of eng id)))
        (is (= "open" (state-of eng :approval_chore c)))))
    (testing "the held call's day runs out first"
      (let [{:keys [eng clock]} (world)
            gid (grant! eng)
            c (chore! eng)
            id (schedule! eng c gid "2026-10-03T12:00:00Z")
            hid (held-of eng id)]
        (at! clock "2026-10-02T12:30:00Z")
        (is (= 1 (held/sweep-expired! eng)))
        (is (= "expired" (state-of eng :held_call hid)))
        (scheduled/sweep-due! eng)
        (is (= "skipped" (state-of eng :scheduled_action id)))
        (is (= sentence (why-of eng id)))))))

(deftest no-second-tap-at-run-at
  (let [{:keys [eng clock]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        hid (held-of eng id)]
    (decide! eng hid :allow {} person)
    (testing "a hand that names the row is no yes: the row is not running"
      (is (= :the-person-says-when
             (refused-by #(inv/invoke! eng :approval_chore c :finish {}
                                       (assoc (worn gid)
                                              :within {:kind :scheduled_action
                                                       :action :run
                                                       :id (str id)})))))
      (is (= "open" (state-of eng :approval_chore c))))
    (testing "the run walks the held door, and nobody is asked again"
      (at! clock "2026-10-01T12:05:20Z")
      (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
      (is (= "done" (state-of eng :scheduled_action id)) (pr-str (why-of eng id)))
      (is (= "done" (state-of eng :approval_chore c)))
      (is (= 1 (held-calls eng))))
    (testing "the target's history says whose hand, and that the clock moved it"
      (let [actor (:actor (last (log-of eng :approval_chore c)))]
        (is (= "approval-clerk" (:id actor)))
        (is (= (str id) (:scheduled actor)))))))

(deftest reschedule-asks-again
  (let [{:keys [eng clock]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        first-yes (held-of eng id)]
    (decide! eng first-yes :allow {} person)
    (effects eng :reschedule
             (inv/invoke! eng :scheduled_action id :reschedule
                          {:run_at "2026-10-01T12:10:00Z"} (worn gid)))
    (let [second-ask (held-of eng id)]
      (testing "the yes covered the old time, so the row waits on a new one"
        (is (= "proposed" (state-of eng :scheduled_action id)))
        (is (not= (str first-yes) (str second-ask)))
        (is (= "held" (state-of eng :held_call second-ask)))
        (is (str/ends-with? (str (get-in (row-of eng :held_call second-ask) [:data :shown]))
                            "2026-10-01 12:10")))
      (testing "the old time passes and nothing runs"
        (at! clock "2026-10-01T12:05:20Z")
        (scheduled/sweep-due! eng)
        (is (= "proposed" (state-of eng :scheduled_action id)))
        (is (= "open" (state-of eng :approval_chore c))))
      (testing "the new yes arms it, and it runs at the new time"
        (decide! eng second-ask :allow {} person)
        (is (= "scheduled" (state-of eng :scheduled_action id)))
        (at! clock "2026-10-01T12:10:20Z")
        (scheduled/sweep-due! eng)
        (is (= "done" (state-of eng :scheduled_action id)) (pr-str (why-of eng id)))
        (is (= "done" (state-of eng :approval_chore c)))))))

(deftest a-revoked-grant-still-skips-an-approved-row
  (let [{:keys [eng clock]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)]
    (decide! eng (held-of eng id) :allow {} person)
    (inv/invoke! eng :grant gid :revoke {} {:principal person})
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (is (= "skipped" (state-of eng :scheduled_action id)))
    (is (= "The grant this was scheduled under no longer admits it." (why-of eng id)))
    (is (= "open" (state-of eng :approval_chore c)))))

;; the power cases of R-8 item 4 (R-4.4, child 4b)

(def ^:private mail-powers
  "`email.read` runs at once, and `email.send` waits on a person."
  [{:power "email.read" :tools ["read"] :approval "none"}
   {:power "email.send" :tools ["send"] :approval "person"}])

(defn- fake-mail
  "An in-process MCP server. Every call lands on `log`, and `down?`
  breaks the wire under a call."
  [log down?]
  (fn [method params]
    (swap! log conj {:method method :params params})
    (when (and @down? (= "tools/call" method))
      (throw (client/unreachable "the fake is down.")))
    (case method
      "tools/list" {:tools [{:name "read" :inputSchema {:type "object"}}
                            {:name "send" :inputSchema {:type "object"}}]}
      "tools/call" {:content [{:type "text" :text "answered"}] :isError false})))

(defn- mail-world
  "`world`, with one live server row and the clerk wearing both of its
  powers. With `oidc` the engine answers the connector door, and its
  members are the door's to make."
  ([] (mail-world nil))
  ([oidc]
   (let [clock (atom t0)
         log (atom [])
         down? (atom false)
         fake (fake-mail log down?)
         eng (engine/engine
              (cond-> {:storage (memory/storage)
                       :resources [chore caps/capability]
                       :now-fn (fn [] @clock)
                       :services {:mcp-servers
                                  {:client-fn (fn [row]
                                                (when (= "emila" (get-in row [:data :name]))
                                                  fake))}}}
                oidc (assoc :oidc oidc)))]
     (when-not oidc
       (member! eng "colton" {})
       (member! eng "approval-clerk" {:actor_type "agent" :acts_for "colton"}))
     (doseq [token ["email.read" "email.send"]]
       (inv/create! eng :capability
                    {:token token
                     :description (str token " through a server row.")
                     :enforced_by "this engine's own power door"}
                    {:principal person}))
     (let [server (:row (inv/create! eng :mcp_server
                                     {:name "emila" :transport "http"
                                      :url "http://fake.invalid/mcp/"
                                      :powers mail-powers}
                                     {:principal person}))
           gid (str (:id (:row (inv/create! eng :grant
                                            {:audience (:id clerk)
                                             :scope [{:kind "email.read" :actions []}
                                                     {:kind "email.send" :actions []}]}
                                            {:principal person}))))]
       (inv/invoke! eng :grant gid :accept {} {:principal clerk})
       {:eng eng :clock clock :log log :down? down? :gid gid
        :server (str (:id server))}))))

(defn- send-later!
  "The clerk schedules one power call for 12:05, under the grant as the
  engine reads it. -> the row's id."
  [{:keys [eng gid]} tool extra]
  (:id (:row (effects eng :create
                      (inv/create! eng :scheduled_action
                                   (merge {:target {:tool tool}
                                           :input {:to "otto@example.test"
                                                   :why "The household asked."}
                                           :run_at "2026-10-01T12:05:00Z"}
                                          extra)
                                   {:principal clerk
                                    :grant (:grant (grants/visibility eng gid clerk))})))))

(defn- sent
  "What the fake was asked to call, past the create's one tools/list."
  [{:keys [log]}]
  (filterv #(= "tools/call" (:method %)) @log))

(defn- sent-tools [w] (mapv #(get-in % [:params :name]) (sent w)))

(deftest a-power-target-runs-under-state-and-under-strict
  (doseq [validity ["state" "strict"]]
    (testing validity
      (let [{:keys [eng clock] :as w} (mail-world)
            id (send-later! w "emila__read" {:validity validity})]
        (is (= "scheduled" (state-of eng :scheduled_action id)) "no hold, so nobody is asked")
        (is (zero? (held-calls eng)))
        (is (empty? (sent w)) "the call was not made")
        (at! clock "2026-10-01T12:05:20Z")
        (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
        (is (= "done" (state-of eng :scheduled_action id)) (pr-str (why-of eng id)))
        (is (= ["read"] (sent-tools w)))
        (is (str/includes? (str (why-of eng id)) "`emila__read`"))))))

(deftest a-power-target-is-checked-when-it-is-scheduled
  (let [{:keys [eng] :as w} (mail-world)]
    (testing "a tool that is not among the scheduler's powers"
      (is (= :the-door-would-take-it
             (refused-by #(send-later! w "emila__wire" {})))))
    (testing "a power is worn, and a person wears none"
      (is (= :the-door-would-take-it
             (refused-by #(inv/create! eng :scheduled_action
                                       {:target {:tool "emila__read"}
                                        :run_at "2026-10-01T12:05:00Z"}
                                       {:principal person})))))
    (testing "conditions are refused: a power tool has no row to read"
      (is (some? (try (send-later! w "emila__read" {:validity "conditions"
                                                    :conditions {:state "open"}})
                      nil
                      (catch clojure.lang.ExceptionInfo e e)))))
    (is (empty? (sent w)))))

(deftest strict-skips-when-the-servers-powers-moved
  (let [{:keys [eng clock server] :as w} (mail-world)
        strict (send-later! w "emila__read" {:validity "strict"})
        loose (send-later! w "emila__read" {})
        row (row-of eng :mcp_server server)]
    (inv/invoke! eng :mcp_server server :restate
                 {:powers (assoc-in mail-powers [0 :approval] "why")}
                 {:principal person
                  :if-match (inv/etag :mcp_server (:id row) (:version row))})
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (is (= "skipped" (state-of eng :scheduled_action strict)))
    (is (str/includes? (str (why-of eng strict)) "changed since this was scheduled"))
    (is (= "done" (state-of eng :scheduled_action loose)) (pr-str (why-of eng loose)))
    (is (= ["read"] (sent-tools w)) "only the `state` row was sent")))

(deftest a-power-that-waits-on-a-person-is-proposed-and-armed-by-allow
  (let [{:keys [eng clock] :as w} (mail-world)
        id (send-later! w "emila__send" {})
        hid (held-of eng id)
        call (:data (row-of eng :held_call hid))]
    (testing "the power's approval is person, so the row waits on a yes"
      (is (= "proposed" (state-of eng :scheduled_action id)))
      (is (= "held" (state-of eng :held_call hid)))
      (is (str/starts-with? (str (:shown call)) "emila__send "))
      (is (str/ends-with? (str (:shown call)) "2026-10-01 12:05"))
      (is (str/includes? (str (:why call)) "The household asked."))
      (is (empty? (sent w))))
    (testing "allow arms it, and nothing is sent before its time"
      (decide! eng hid :allow {} person)
      (is (= "scheduled" (state-of eng :scheduled_action id)))
      (is (empty? (sent w))))
    (testing "the run goes through the power door, and nobody is asked again"
      (at! clock "2026-10-01T12:05:20Z")
      (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
      (is (= "done" (state-of eng :scheduled_action id)) (pr-str (why-of eng id)))
      (is (= ["send"] (sent-tools w)))
      (is (= 1 (held-calls eng))))))

(deftest a-power-run-that-ends-unknown-fails-and-is-not-retried
  (testing "the wire drops under the call"
    (let [{:keys [eng clock down?] :as w} (mail-world)
          id (send-later! w "emila__read" {})]
      (reset! down? true)
      (at! clock "2026-10-01T12:05:20Z")
      (scheduled/sweep-due! eng)
      (is (= "failed" (state-of eng :scheduled_action id)))
      (is (str/includes? (str (why-of eng id)) "It may or may not have been sent"))
      (reset! down? false)
      (at! clock "2026-10-01T12:20:00Z")
      (is (= {:ran 0 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
      (is (= 1 (count (sent w))) "one attempt, and no second")))
  (testing "a stopped engine left the row running"
    (let [{:keys [eng clock] :as w} (mail-world)
          id (send-later! w "emila__read" {})]
      (at! clock "2026-10-01T12:05:20Z")
      (is (some? (scheduled/start! eng id)))
      (at! clock "2026-10-01T12:11:00Z")
      (is (= {:ran 0 :late 0 :recovered 1} (scheduled/sweep-due! eng)))
      (is (= "failed" (state-of eng :scheduled_action id)))
      (is (str/includes? (str (why-of eng id)) "It may or may not have been sent"))
      (is (empty? (sent w)) "it is never made again"))))

;; `at` on waymark_power, through the connector door: mcp_schedule_test's
;; helpers, spelled again because they are private there by design.

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private oidc
  {:issuer "https://idp.test/realms/home"
   :audience "scheduled-power-test"
   :jwks {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                        :kid "power-key" :alg "RS256" :use "sig")]}
   :app-url "https://app.test/"
   :delegate-clients {"connector" "Claude"}})

(defn- bearer
  "Colton's connector token. It outlives the engine's clock and the wall
  clock, whichever the door reads."
  []
  {"authorization"
   (str "Bearer "
        (jwt/sign {:iss (:issuer oidc) :aud (:audience oidc)
                   :sub "colton" :azp "connector" :name "Colton Kopsa"
                   :exp (+ (max (quot (System/currentTimeMillis) 1000)
                                (.getEpochSecond ^Instant t0))
                           600)}
                  (.getPrivate keypair)
                  {:alg :rs256 :header {:kid "power-key"}}))})

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- door-tool
  "One tool call through the real handler, on session `sid`."
  [h sid tool-name args]
  (let [resp (rpc h (assoc (bearer) "mcp-session-id" sid)
                  "tools/call" {:name tool-name :arguments args})]
    (get-in (some-> (:body resp) wire/read-json) [:result])))

(defn- doc [out] (wire/read-json (get-in out [:content 0 :text])))

(def ^:private a-key
  "128 bits of base64url: what a machine mints and no hand types."
  "c2NoZWR1bGVkLXBvd2VyLWF0LWtleS0wMDAx")

(defn- open-seat!
  "A seat whose scope names the mail's read power and no
  scheduled_action, with its key offered."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "power-test-model" :display "Power 1"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create! eng :seat
                                {:name "mail-one"
                                 :charter "Read the mail that is due."
                                 :scope [{:kind "approval_chore" :actions ["finish"]}
                                         {:kind "email.read" :actions []}]
                                 :held_for [(:id model)]
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal person}))]
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    seat))

(deftest at-on-waymark-power-answers-scheduled-and-counts-one-served-answer
  (let [{:keys [eng] :as w} (mail-world oidc)
        h (engine/handler eng)
        _ (open-seat! eng)
        sid (get-in (rpc h (bearer) "initialize"
                         {:protocolVersion mcp/protocol-version
                          :capabilities {}
                          :clientInfo {:name "power" :version "0"}})
                    [:headers "Mcp-Session-Id"])
        sat (door-tool h sid "waymark_sit" {:key a-key})
        sitting (str (:sitting (doc sat)))
        out (door-tool h sid "waymark_power"
                       {:tool "emila__read" :arguments {:uid "7"}
                        :at "2026-10-02T08:30" :zone "America/Denver"})
        d (doc out)
        served (get-in (row-of eng :sitting sitting)
                       [:data :served :waymark_power :calls])]
    (is (not (:isError sat)) (str (doc sat)))
    (is (not (:isError out)) (str d))
    (is (true? (:scheduled d)) "an answer, not a refusal")
    (is (= "scheduled" (state-of eng :scheduled_action (:scheduled_action d))))
    (is (empty? (sent w)) "the call was not made")
    (is (= 1 (long (or served 0)))
        "one served answer, on the sitting the session is bound to")))
