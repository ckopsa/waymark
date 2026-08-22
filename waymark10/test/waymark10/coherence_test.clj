(ns waymark10.coherence-test
  "Multi-process coherence: the faithful simulation. TWO engine
  instances — separate storage pools, separate registry atoms — over
  one database in one JVM, exactly the shape of two server processes
  sharing Postgres. The stale-law window is demonstrated, then closed
  by the refresh (directly, and through the outbox-riding consumer);
  the mixed-code boundary is proved recorded (a refresh never mints
  law); the webhook deliverer and the clock sweeper are elected — one
  holder, clean takeover.

  The election tests drive the LIFECYCLE SEAM, not this namespace:
  since waymark-db9.4 `:elected` is a property of a module's runtime
  hook and the engine walks the hooks (engine/start-runtime!), so the
  faithful simulation of two processes is two started runtimes. That
  is also what they prove has not weakened — coherence no longer
  reaches into webhooks and maintainer to start them, and the roles
  are held exactly as they were.

  Collab locality is documented, not tested: rooms are process-local
  by design — edits persist through the shared draft rows so joiners
  on either process converge, but live frames do not cross processes
  (the remaining relay/2-adjacent punt, recorded in coherence.clj).

  Needs the waymark10_mp_test database (its own, never the suite's
  waymark10_test):
    WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:5433/waymark10_mp_test?user=ckopsa"
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.coherence :as coherence]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.runtime :as runtime]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["coh_gates" "coh_gizmos" "coh_reminders"
   "definitions" "members" "roles" "grants" "attachments"
   "subscriptions" "jobs"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_cursors"
   "waymark10_drafts" "waymark10_job_leases"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng
  "One process: its own pool, its own registry atom."
  [resources opts f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine (merge {:storage st :resources resources} opts)))
      (finally (pg/close! st)))))

(defn- with-two
  "Two processes over the one database — the multi-process simulation."
  [resources opts f]
  (with-eng resources opts
    (fn [eng-a]
      (with-eng resources opts
        (fn [eng-b] (f eng-a eng-b))))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

;; ── fixtures ────────────────────────────────────────────────────────

(defn- gate-resource
  "A gated kind with :adoption :never — rows keep their birth law, so
  the promote grandfathers instead of restamping and the law each
  process serves is observable per row (definitions_test's pattern,
  suite-local)."
  [close-when]
  (r/resource
   {:kind :coh_gate
    :plural "coh_gates"
    :adoption :never
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:ticks {:optional true} [:maybe :int]]]
    :actions
    {:close {:from #{:open} :to :done
             :guards [(g/expr {:name :enough-ticks
                               :when close-when
                               :explain "Not enough ticks yet ({n})."
                               :vars {:n '(data :ticks)}})]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed gate is history."}}}}))

(def ^:private strict-close '(<= 3 (data :ticks)))
(def ^:private lenient-close '(<= 1 (data :ticks)))
(def ^:private strict-reason "Not enough ticks yet (2).")

(def ^:private gizmo
  (r/resource
   {:kind :coh_gizmo
    :plural "coh_gizmos"
    :states [:idle :spun]
    :initial :idle
    :terminal #{:spun}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 40}]]]
    :actions {:spin {:from #{:idle} :to :spun
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Spun is history."}}}}))

(def ^:private reminder
  (r/resource
   {:kind :coh_reminder
    :plural "coh_reminders"
    :states [:waiting :done]
    :initial :waiting
    :terminal #{:done}
    :summary "{data.note} · {state}"
    :schema [:map
             [:note [:string {:min 1 :max 60}]]
             [:due_on :waymark/date]
             [:overdue {:optional true} [:maybe :boolean]]]
    :derived {:overdue {:over [:due_on :now]
                        :expr '(<= (var :due_on) (date-of (var :now)))}}
    :actions {:finish {:from #{:waiting} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

;; ── readers ─────────────────────────────────────────────────────────

(defn- rdef [eng kind] (get (inv/resources eng) kind))

(defn- defs-of [eng kind]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/query-rows (:storage eng) tx :definition
                        {:target_kind (name kind)} {:limit 100}))))

(defn- def-row [eng kind rev]
  (first (filter #(= rev (get-in % [:data :revision])) (defs-of eng kind))))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

(defn- envelope [eng kind id]
  (let [rd (rdef eng kind)
        row (update (reload eng kind id) :data
                    #(schema/decode (:schema rd) %))]
    (render/envelope rd row {:now ((:now-fn eng))})))

(defn- problem-of [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- await-pred
  "Poll until (pred) is truthy or the timeout; returns the value."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

(defn- create-gate! [eng title ticks]
  (:row (inv/create! eng :coh_gate {:title title :ticks ticks}
                     {:principal elena})))

(defn- probe-close-reason
  "The probe half: close's unavailable reason on the envelope, nil
  when close is advertised."
  [eng id]
  (get-in (envelope eng :coh_gate id) ["unavailable" "close" "reason"]))

;; ── 1. the stale-law window, then the refresh (the point) ──────────

(deftest stale-law-window-then-refresh
  (fresh!)
  ;; deploy 0: the strict law is minted; a row lives under it
  (with-eng [(gate-resource strict-close)] {:deploy-mode :promote}
    (fn [eng] (create-gate! eng "g0" 2)))
  ;; the rolling deploy: the lenient code lands on BOTH processes
  ;; before anyone promotes — each holds revision 2 and serves the
  ;; current law from its stored trees
  (with-two [(gate-resource lenient-close)] {:deploy-mode :propose}
    (fn [eng-a eng-b]
      (testing "both processes hold: current 1, proposal 2"
        (doseq [eng [eng-a eng-b]]
          (is (= 1 (:current-law (rdef eng :coh_gate))))
          (is (= 2 (get-in (rdef eng :coh_gate) [:proposed-law :revision])))
          (is (contains? (:judgment-laws (rdef eng :coh_gate)) 1))))
      ;; a second principal promotes THROUGH A (four-eyes on :create)
      (inv/invoke! eng-a :definition (:id (def-row eng-a :coh_gate 2))
                   :promote nil {:principal elena})
      (is (= 2 (:current-law (rdef eng-a :coh_gate))))
      (is (= :grandfathered (:state (def-row eng-a :coh_gate 1)))
          "rev 1 keeps its rows (:adoption :never) — grandfathered, not superseded")
      (testing "the violation: B's slots still serve the retired law"
        (is (= 1 (:current-law (rdef eng-b :coh_gate))))
        (let [brow (create-gate! eng-b "gB" 2)
              arow (create-gate! eng-a "gA" 2)]
          (is (= 1 (:law-revision brow))
              "born through B under the retired current")
          (is (= 2 (:law-revision arow))
              "born through A under the promoted law")
          (testing "B's probe and enforcement judge under the old law"
            (is (= strict-reason (probe-close-reason eng-b (:id brow))))
            (let [p (problem-of #(inv/invoke! eng-b :coh_gate (:id brow)
                                              :close nil {:principal elena}))]
              (is (= 409 (:status p)))
              (is (= strict-reason (:detail p)))))
          (testing "the same ticks close through A under the new law"
            (is (= :done (:state (:row (inv/invoke! eng-a :coh_gate (:id arow)
                                                    :close nil
                                                    {:principal elena}))))))
          (testing "the refresh — the consumer's exact act, synchronous"
            (let [res (coherence/refresh! eng-b)]
              (is (true? (:refreshed? res))))
            (is (= 2 (:current-law (rdef eng-b :coh_gate))))
            (is (nil? (:proposed-law (rdef eng-b :coh_gate))))
            (is (contains? (:judgment-laws (rdef eng-b :coh_gate)) 1)
                "the grandfathered law still serves its rows from the store"))
          (testing "B serves the new law without a reboot: probe AND
                    enforcement"
            (let [brow2 (create-gate! eng-b "gB2" 2)]
              (is (= 2 (:law-revision brow2)))
              (is (nil? (probe-close-reason eng-b (:id brow2)))
                  "the probe advertises close under the lenient law")
              (is (= :done (:state (:row (inv/invoke! eng-b :coh_gate
                                                      (:id brow2) :close nil
                                                      {:principal elena})))))))
          (testing "the stale-born row repairs through adopt — B's
                    adopt target reads the refreshed slots"
            (let [{fixed :row} (inv/invoke! eng-b :coh_gate (:id brow)
                                            :adopt nil {:principal elena})]
              (is (= 2 (:law-revision fixed))))
            (is (= :done (:state (:row (inv/invoke! eng-b :coh_gate (:id brow)
                                                    :close nil
                                                    {:principal elena})))))))))))

;; ── 2. the consumer rides the outbox ────────────────────────────────

(deftest refresh-consumer-rides-the-outbox
  (fresh!)
  (with-eng [(gate-resource strict-close)] {:deploy-mode :promote}
    (fn [eng] (create-gate! eng "seed" 2)))
  (with-two [(gate-resource lenient-close)]
    {:deploy-mode :propose :events-poll-ms 100}
    (fn [eng-a eng-b]
      (let [d-b (events/dispatcher eng-b {:poll-ms 100})
            refresh (coherence/start-refresh! eng-b d-b {:debounce-ms 150})]
        (try
          (testing "the startup refresh re-adopts the hold, a no-op"
            (is (await-pred #(pos? @(:refreshes refresh)) 5000))
            (is (= 1 (:current-law (rdef eng-b :coh_gate)))))
          ;; the promote happens on A; B hears the definition-kind
          ;; transitions through its own dispatcher and converges —
          ;; debounced, without a reboot
          (inv/invoke! eng-a :definition (:id (def-row eng-a :coh_gate 2))
                       :promote nil {:principal elena})
          (is (await-pred #(= 2 (:current-law (rdef eng-b :coh_gate))) 10000)
              "B's slots converge on the promoted law")
          (testing "and B serves it"
            (let [row (create-gate! eng-b "gB" 2)]
              (is (= 2 (:law-revision row)))
              (is (nil? (probe-close-reason eng-b (:id row))))
              (is (= :done (:state (:row (inv/invoke! eng-b :coh_gate (:id row)
                                                      :close nil
                                                      {:principal elena})))))))
          (finally
            (coherence/stop-refresh! refresh)
            (events/stop! d-b)))))))

;; ── 3. the mixed-code boundary: a refresh never mints law ──────────

(deftest mixed-code-refresh-refuses-to-mint
  (fresh!)
  ;; the store's law is strict revision 1
  (with-eng [(gate-resource strict-close)] {:deploy-mode :promote}
    (fn [_eng]))
  ;; B keeps the OLD strict code (the process the rolling deploy has
  ;; not replaced yet); A boots the lenient code and deploys
  (with-eng [(gate-resource strict-close)] {:deploy-mode :promote}
    (fn [eng-b]
      (with-eng [(gate-resource lenient-close)] {:deploy-mode :propose}
        (fn [eng-a]
          (testing "while A holds: B's refresh must not withdraw the
                    live proposal it does not express"
            (let [res (coherence/refresh! eng-b)]
              (is (false? (:refreshed? res)))
              (is (some #(= :coh_gate (:kind %)) (:unsafe res))))
            (is (= :proposed (:state (def-row eng-a :coh_gate 2)))
                "the hold survives"))
          (inv/invoke! eng-a :definition (:id (def-row eng-a :coh_gate 2))
                       :promote nil {:principal elena})
          (testing "after the promote: B's resident code matches no
                    live revision — skip, warn, stay honestly stale"
            (let [res (coherence/refresh! eng-b)]
              (is (false? (:refreshed? res))))
            (is (= 1 (:current-law (rdef eng-b :coh_gate)))
                "stale — this process's replacement is the deploy's job")
            (is (= 2 (count (defs-of eng-b :coh_gate)))
                "no revision minted from a refresh, ever")))))))

;; ── 4. the webhook deliverer: one holder, clean takeover ───────────

(defn- receiver!
  "An in-process endpoint capturing every POST (webhooks_test's)."
  []
  (let [hits (atom [])
        server (http/run-server
                (fn [req]
                  (swap! hits conj {:headers (:headers req)
                                    :body (slurp (:body req))})
                  {:status 200 :headers {} :body ""})
                {:port 0 :legacy-return-value? false})]
    {:hits hits :server server
     :url (str "http://127.0.0.1:" (http/server-port server) "/hook")}))

(defn- delivery-count [rcv event-id]
  (count (filter #(= (str event-id) (get (:headers %) "x-waymark-event-id"))
                 @(:hits rcv))))

(defn- started
  "Two processes, both runtimes walked — the shape engine/start! puts
  a deployed process in, minus the http server."
  [engs]
  (doseq [eng engs] (engine/start-runtime! eng))
  nil)

(deftest webhook-deliverer-election-and-takeover
  (fresh!)
  (with-two [gizmo]
    {:webhook-attempts 2 :webhook-backoff-ms 5 :webhooks-poll-ms 200
     :events-poll-ms 100 :role-retry-ms 200 :law-refresh-debounce-ms 150}
    (fn [eng-a eng-b]
      (let [rcv (receiver!)]
        (started [eng-a eng-b])
        ;; hold the role handles: a stopped runtime empties its atom,
        ;; and the takeover assertion has to keep counting starts
        ;; across the process it just killed
        (let [role (fn [eng] (runtime/surface eng :webhooks-deliverer))
              role-a (role eng-a)
              role-b (role eng-b)
              held? (fn [r] @(:held? r))
              role-starts #(+ @(:starts role-a) @(:starts role-b))]
          (try
            (testing "exactly one process holds the deliverer role"
              (is (await-pred #(= 1 (count (filter held? [role-a role-b])))
                              5000))
              (is (= 1 (role-starts))))
            (let [{sub :row} (inv/create! eng-a :subscription
                                          {:url (:url rcv)
                                           :kinds ["coh_gizmo"]}
                                          {:principal elena})
                  {g1 :row} (inv/create! eng-b :coh_gizmo {:name "g1"}
                                         {:principal elena})
                  spin1 (get-in (inv/invoke! eng-b :coh_gizmo (:id g1) :spin nil
                                             {:principal elena})
                                [:transition :id])
                  consumer (str "webhook:" (:id sub))]
              (testing "the holder delivers; the other never doubles"
                (is (await-pred #(pos? (delivery-count rcv spin1)) 10000))
                ;; the cursor persisted past the delivery — the takeover
                ;; will replay nothing
                (is (await-pred
                     #(<= spin1 (or (store/with-tx (:storage eng-a)
                                      (fn [tx] (store/cursor-get
                                                (:storage eng-a) tx consumer)))
                                    0))
                     5000))
                (Thread/sleep 300)
                (is (= 1 (delivery-count rcv spin1))))
              (testing "kill the holder; the other acquires and resumes"
                (let [[dead survivor] (if (held? role-a)
                                        [eng-a role-b] [eng-b role-a])]
                  (engine/stop-runtime! dead)
                  (is (await-pred #(held? survivor) 5000)
                      "takeover within the retry interval")
                  (is (= 2 (role-starts))
                      "one initial start, one takeover — never two at once"))
                (let [{g2 :row} (inv/create! eng-a :coh_gizmo {:name "g2"}
                                             {:principal elena})
                      spin2 (get-in (inv/invoke! eng-a :coh_gizmo (:id g2)
                                                 :spin nil {:principal elena})
                                    [:transition :id])]
                  (is (await-pred #(pos? (delivery-count rcv spin2)) 10000)
                      "delivery resumed under the new holder")
                  (Thread/sleep 300)
                  (is (= 1 (delivery-count rcv spin2)))
                  (is (= 1 (delivery-count rcv spin1))
                      "the takeover replayed nothing"))))
            (finally
              (engine/stop-runtime! eng-a)
              (engine/stop-runtime! eng-b)
              (http/server-stop! (:server rcv)))))))))

;; ── 5. the clock sweeper: one holder sweeps the due flip ───────────

(deftest clock-sweeper-election
  (fresh!)
  (let [clock (atom (Instant/parse "2026-07-10T12:00:00Z"))]
    (with-two [reminder]
      {:now-fn (fn [] @clock) :sweep-interval-ms 100 :events-poll-ms 100
       :role-retry-ms 200 :law-refresh-debounce-ms 150}
      (fn [eng-a eng-b]
        (started [eng-a eng-b])
        (let [role-a (runtime/surface eng-a :clock-sweeper)
              role-b (runtime/surface eng-b :clock-sweeper)
              held? (fn [r] @(:held? r))
              starts #(+ @(:starts role-a) @(:starts role-b))]
          (try
            (testing "exactly one process runs a sweeper"
              (is (await-pred #(= 1 (count (filter held? [role-a role-b])))
                              5000))
              (is (= 1 (starts))))
            (let [{row :row} (inv/create! eng-a :coh_reminder
                                          {:note "water"
                                           :due_on "2026-07-12"}
                                          {:principal elena})
                  rid (:id row)]
              (is (false? (get-in row [:data :overdue])))
              (is (= (Instant/parse "2026-07-12T00:00:00Z")
                     (:next-flip-at (reload eng-a :coh_reminder rid))))
              (testing "the due flip is swept — by the one holder"
                (reset! clock (Instant/parse "2026-07-12T06:00:00Z"))
                (is (await-pred #(true? (get-in (reload eng-a :coh_reminder rid)
                                                [:data :overdue]))
                                10000))
                (let [r (reload eng-a :coh_reminder rid)]
                  (is (= 1 (:version r)) "maintenance, not a write")
                  (is (= (Instant/parse "2026-07-13T00:00:00Z")
                         (:next-flip-at r))
                      "the date's other bounding midnight remains — a
                      spurious candidate the next sweep clears (the
                      maintainer's recorded discipline)"))
                (is (= 1 (starts))
                    "still exactly one sweeper ever started")
                (is (= 1 (count (filter held? [role-a role-b]))))))
            (finally
              (engine/stop-runtime! eng-a)
              (engine/stop-runtime! eng-b))))))))
