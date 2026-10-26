(ns waymark10.localfire-pull-test
  "The pull wire end to end (pull-mode epic 54360702, ticket LP5):
  this engine on a real port over real Postgres, and localfire's real
  pull loop polling it. runner_links_test proves the engine's half
  against no localfire, and localfire's pull_test proves its half
  against a stub engine; this namespace runs the two together.

  Only the run itself is faked: localfire's spawner seam (R-9.1)
  records each start and ends at once, so no `claude` runs. The push
  case's provider is a stub server on the loopback.

  Real Postgres (WAYMARK10_TEST_DSN): a pull link refuses in-memory
  storage. localfire is on this classpath through waymark10's :test
  alias only."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [localfire.config :as lf-config]
            [localfire.pull :as pull]
            [localfire.server :as lf]
            [localfire.spawn :as spawn]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.pull-fires :as pf]
            [waymark10.server.routes.runner-links :as rlr]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration Instant]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["schedules" "seats" "models" "sittings" "definitions" "members" "roles"
   "grants" "approval_requests" "attachments" "subscriptions" "jobs"
   "runner_links" "runner_providers" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_cursors" "waymark10_drafts"
   "waymark10_pull_fires"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *base* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (assoc (engine/engine {:storage st :resources []})
                         ;; the real adapter: the push case POSTs for real
                         :fire-adapter (sch/routine-fire))
              srv (http/run-server (engine/handler eng)
                                   {:port 0 :legacy-return-value? false})]
          (try
            ;; a poll holds 25 seconds in production; a fraction here, so
            ;; a stopped loop lets go at once and the namespace stays short
            (with-redefs [rlr/*wait-ms* 300
                          rlr/*step-ms* 50]
              (binding [*eng* eng
                        *base* (str "http://127.0.0.1:" (http/server-port srv))]
                (f)))
            (finally (http/server-stop! srv))))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(def ^:private a-scope [{:kind "model" :actions ["retire"]}])

(def ^:private a-charter "Decide whether a message asks something of this house.")

(def ^:private the-instructions
  "Read the fire text and do what it says. Sit in the seat it names, then walk the rows the sit hands you.")

(def ^:private a-chair-url
  "https://api.anthropic.com/v1/claude_code/routines/trig_01LFCHAIR/fire")

(def ^:private a-chair-token "rk-test-lf-chair-0123456789abcdef")

(defn- model! [nm]
  (:id (:row (inv/create! *eng* :model
                          {:name nm :display nm :vendor "anthropic"
                           :tier "strong"
                           :price_input_per_mtok 3M
                           :price_output_per_mtok 15M
                           :price_cache_read_per_mtok 0.3M
                           :price_cache_write_per_mtok 3.75M}
                          {:principal elena}))))

(defn- seat! [nm held]
  (:id (:row (inv/create! *eng* :seat
                          {:name nm
                           :charter a-charter
                           :scope a-scope
                           :held_for (vec held)
                           :substitute_for []
                           :standing_ttl_seconds 604800
                           :cadence_seconds 3600
                           :budget_usd_per_week 5M
                           :sitting_budget_tokens 60000
                           :rows_per_firing 20
                           :instructions the-instructions}
                          {:principal elena}))))

(defn- drain!
  "One synchronous drain of the schedule consumer under a named cursor,
  called once before the writes it is about (schedules_test's reason)."
  [cname]
  (consumers/drain-consumer! *eng* cname (sch/consumer-fn *eng*)))

(defn- seated!
  "A seat whose chair is linked, its schedule pushed, and its fires sent
  through the one runner link `link`. → the seat's id."
  [cname nm link]
  (drain! cname)
  (let [chair (model! (str "claude-chair-" nm))
        _ (inv/create! *eng* :runner_link
                       {:provider "claude_routine" :fire_url a-chair-url
                        :fire_token a-chair-token
                        :pools [{:kind "model" :id (str chair)}]}
                       {:principal elena})
        seat-id (seat! nm [chair])]
    (drain! cname)
    (inv/invoke! *eng* :runner_link (str link) :join_pool
                 {:kind "schedule" :id (str (:id (sch/schedule-for-seat *eng* seat-id)))}
                 {:principal elena})
    seat-id))

(defn- sched-of [seat-id] (sch/schedule-for-seat *eng* seat-id))

(defn- seen-of
  "The pull link's `puller_seen_at`: when a localfire last polled it."
  [link-id]
  (let [st (:storage *eng*)]
    (store/with-tx st
      (fn [tx]
        (get-in (store/load-row st tx :runner_link (str link-id) {})
                [:data :puller_seen_at])))))

(defn- fire-seat!
  "A person's fire, with its own idempotency key, then the drain that
  sends it."
  [cname seat-id text]
  (inv/invoke! *eng* :seat (str seat-id) :fire {:text text}
               {:principal elena
                :idempotency-key (str "lf-pull-test:" (random-uuid))})
  (drain! cname))

(defn- retire! [seat-id]
  (inv/invoke! *eng* :seat (str seat-id) :retire nil {:principal elena}))

(defn- pull-link! [token]
  (str (:id (:row (inv/create! *eng* :runner_link
                               {:provider "localfire" :delivery "pull"
                                :fire_token token}
                               {:principal elena})))))

(defn- eventually
  "Ask `pred` every 50 ms until it answers truthy or `ms` pass. → its
  last answer."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

;; ── localfire, with its run faked ────────────────────────────────────

(def ^:private canned-stdout
  (json/write-value-as-string
   {"type" "result" "num_turns" 3 "total_cost_usd" 0.1234
    "usage" {"input_tokens" 11 "output_tokens" 22
             "cache_creation_input_tokens" 33 "cache_read_input_tokens" 44}
    "modelUsage" {"claude-sonnet-4-5" {"inputTokens" 11}}}))

(defn- fake-spawner
  "localfire's run seam (R-9.1): each start is recorded in `calls` and
  the process ends at once, with no `claude` behind it."
  [calls]
  (reify spawn/Spawner
    (start [_ argv dir _env]
      (swap! calls conj {:argv (vec argv) :dir (str dir)})
      (reify spawn/Handle
        (stdout [_] (io/input-stream (.getBytes ^String canned-stdout "UTF-8")))
        (stderr [_] (io/input-stream (.getBytes "" "UTF-8")))
        (await-exit [_ _ms] 0)
        (destroy [_] nil)
        (destroy-forcibly [_] nil)))))

(defn- tmpdir [prefix]
  (.toFile (Files/createTempDirectory (str prefix) (make-array FileAttribute 0))))

(defn- place!
  "The seat's place: four files, the hook executable (localfire R-5.3)."
  []
  (let [d (tmpdir "lf-place")]
    (spit (io/file d "CLAUDE.md") "# the seat's place\n")
    (spit (io/file d "inbox-feed.sh") "#!/bin/bash\nexit 0\n")
    (.mkdirs (io/file d ".claude" "hooks"))
    (spit (io/file d ".claude" "settings.json") "{}\n")
    (let [h (io/file d ".claude" "hooks" "sitting-close.sh")]
      (spit h "#!/bin/bash\nexit 0\n")
      (.setExecutable h true false))
    d))

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- localfire!
  "A localfire server with one routine, `sonnet`, and the fake run. →
  its state, with `:calls` and `:base`."
  []
  (let [port  (free-port)
        calls (atom [])
        cfg   (lf-config/normalize
               {:port       port
                :public-url (str "http://127.0.0.1:" port)
                :place      (str (place!))
                :runs-dir   (str (tmpdir "lf-runs"))
                :mcp        {:url "http://127.0.0.1:9/mcp"}
                :routines   {"sonnet" {:model "claude-sonnet-4-5"}}})
        st    (lf/start! {:config  cfg
                          :token   "the-token"
                          :spawner (fake-spawner calls)
                          :closer  (fn [_argv _dir _input] {:exit 0 :out "closed"})})]
    (assoc st :calls calls :base (str "http://127.0.0.1:" port))))

(defn- pull-of [link-id]
  {:engine-url *base* :link-id link-id :routine "sonnet"
   :token-from "localfire_pull_test"})

;; ── 1 · a fire waits for a puller, and runs when one connects ───────

(deftest a-fire-waits-for-a-puller-and-runs-when-one-connects
  (let [cn :lf-pull-waits
        token "rk-test-lf-pull-waits-0123456789abcdef"
        st (:storage *eng*)
        p (pull-link! token)
        seat-id (seated! cn "lf-waits-clerk" p)
        lfs (localfire!)
        running (atom nil)]
    (try
      (testing "no localfire has ever polled: nothing is queued and the schedule reads waiting"
        (fire-seat! cn seat-id "Walk the pool.")
        (is (nil? (seen-of p)))
        (is (empty? (pf/pending st p)))
        (is (str/includes? (str (get-in (sched-of seat-id) [:data :note])) "waiting"))
        (is (empty? @(:calls lfs)) "no run started"))

      (testing "the loop connects over HTTP, and the next fire runs on it once"
        (reset! running (pull/start! lfs (pull-of p) token))
        (is (eventually 10000 #(seen-of p)) "the loop's poll was recorded")
        (fire-seat! cn seat-id "Walk the pool.")
        ;; queueing already stamps `fired` with last_runner; the answer
        ;; is what writes the run's URL
        (is (eventually 10000 #(str/starts-with?
                                 (str (get-in (sched-of seat-id) [:data :last_run_url]))
                                 (str (:base lfs) "/runs/")))
            "the row's URL is the run localfire started")
        (is (= p (get-in (sched-of seat-id) [:data :last_runner])))
        (is (= 1 (count @(:calls lfs))) "exactly one run")
        (is (empty? (pf/pending st p)) "the fire left the queue"))
      (finally
        (some-> @running pull/stop!)
        (lf/stop! lfs)
        (retire! seat-id)))))

;; ── 2 · a loop dropped between take and answer ──────────────────────

(defn- take-by-hand!
  "One GET of the link's fires with its token, never answered: a
  localfire that took a fire and dropped before it posted back."
  [link-id token]
  (let [c (HttpClient/newHttpClient)
        r (-> (HttpRequest/newBuilder
               (URI/create (str *base* "/api/-/runner-links/" link-id "/fires")))
              (.timeout (Duration/ofSeconds 10))
              (.header pull/token-header token)
              (.GET)
              (.build))]
    (.statusCode (.send c r (HttpResponse$BodyHandlers/ofString)))))

;; Pending on LP3 (ticket 6dae6fec): a fire leased to a poll that never
;; answers waits out pf/lease-seconds before it is handed out again.
;; Drop the metadata when LP3 lands.
(deftest ^{:kaocha/pending true} a-fire-taken-and-dropped-runs-once-when-the-loop-returns
  (let [cn :lf-pull-drop
        token "rk-test-lf-pull-drop-0123456789abcdef"
        st (:storage *eng*)
        p (pull-link! token)
        seat-id (seated! cn "lf-drop-clerk" p)
        lfs (localfire!)
        running (atom nil)]
    (try
      (testing "a localfire takes the fire and drops before it answers"
        (rl/seen! *eng* p (Instant/now))
        (fire-seat! cn seat-id "Walk the pool.")
        (is (= 200 (take-by-hand! p token)))
        (is (empty? @(:calls lfs))))

      (testing "the loop comes back and the fire runs exactly once"
        (reset! running (pull/start! lfs (pull-of p) token))
        (is (eventually 20000 #(str/starts-with?
                                 (str (get-in (sched-of seat-id) [:data :last_run_url]))
                                 (str (:base lfs) "/runs/"))))
        (Thread/sleep 500)
        (is (= 1 (count @(:calls lfs))) "exactly one run")
        (is (empty? (pf/pending st p))))
      (finally
        (some-> @running pull/stop!)
        (lf/stop! lfs)
        (retire! seat-id)))))

;; ── 3 · a push link on the same engine still POSTs ──────────────────

(defn- provider-stub!
  "A fire endpoint on the loopback that records each POST and answers
  the routine API's 200."
  []
  (let [hits (atom [])
        srv (http/run-server
             (fn [req]
               (swap! hits conj {:auth (get-in req [:headers "authorization"])
                                 :body (wire/read-json (slurp (:body req)))})
               {:status 200
                :headers {"content-type" "application/json"}
                :body (wire/write-json {:type "routine_fire"
                                        :claude_code_session_id "session_stub"
                                        :claude_code_session_url "https://stub.test/session_stub"})})
             {:port 0 :legacy-return-value? false})]
    {:hits hits :srv srv
     :url (str "http://127.0.0.1:" (http/server-port srv) "/v1/routines/trig_stub/fire")}))

(deftest a-push-link-on-the-same-engine-still-posts-to-its-url
  (let [cn :lf-push
        token "rk-test-lf-push-0123456789abcdef"
        rcv (provider-stub!)
        push (str (:id (:row (inv/create! *eng* :runner_link
                                          {:provider "localfire"
                                           :fire_url (:url rcv)
                                           :fire_token token}
                                          {:principal elena}))))
        seat-id (seated! cn "lf-push-clerk" push)]
    (try
      (fire-seat! cn seat-id "Walk the push pool.")
      (is (= 1 (count @(:hits rcv))) "one POST reached the fire_url")
      (is (= (str "Bearer " token) (:auth (first @(:hits rcv)))))
      (is (string? (get-in (first @(:hits rcv)) [:body :text])))
      (let [row (sched-of seat-id)]
        (is (= push (get-in row [:data :last_runner])))
        (is (= "https://stub.test/session_stub" (get-in row [:data :last_run_url]))))
      (is (empty? (pf/pending (:storage *eng*) push)) "a push link holds nothing")
      (finally
        (http/server-stop! (:srv rcv))
        (retire! seat-id)))))
