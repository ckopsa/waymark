(ns localfire.roundtrip-test
  "R-9.2: the round trip, which pins the wire from both sides.

  Every other test in this module asserts what THIS module believes
  the engine sends and reads. That is worth little on its own: the
  wire of §2 is the engine's, recorded in `schedules.clj`, and a
  belief about it can drift. So this one test stands the server on an
  ephemeral port and fires at it with `schedules/fire-routine` on
  `schedules/routine-fire` — the engine's OWN adapter, the same record
  a cloud firing goes through, with its own headers, its own twenty
  second timeout and its own reading of the answer.

  The four outcomes are the four rows of §2's failure table, and each
  is checked against the engine's own sentence for it
  (`schedules/provider-note`), because that sentence is what lands on
  the schedule row.

  waymark10 is on the classpath for the `:test` alias only (R-4.1);
  the server itself depends on nothing of the engine at run time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [localfire.server :as server]
            [localfire.server-test :as w]
            [localfire.spawn :as spawn]
            [waymark10.server.schedules :as sch]))

(defn- caught
  "Fire and return the ex-info the adapter threw, or nil."
  [adapter url token text]
  (try (sch/fire-routine adapter url token text) nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest the-engines-own-adapter-fires-at-this-server
  (let [gate    (promise)
        world   (w/world {:routines {"sonnet" {:model "claude-sonnet-4-5"
                                               :max-concurrent 1}}
                          :gate gate})
        adapter (sch/routine-fire)
        url     (str (:base world) "/fire/sonnet")
        text    (str "You sit in the seat `ci-classifier`.\n"
                     "Seat: ci-classifier\n"
                     "Key: sk-round-trip\n")]
    (try
      (testing "the right token answers :session-id and :session-url"
        (let [{:keys [session-id session-url]}
              (sch/fire-routine adapter url "the-token" text)]
          (is (string? session-id))
          (is (= session-id (str (java.util.UUID/fromString session-id))))
          (is (= (str (:base world) "/runs/" session-id) session-url))
          (testing "and that URL is the page the engine puts on the row"
            (is (= 200 (:status (w/GET session-url)))))))

      ;; the one slot of the routine is now held open by the fake
      ;; spawner's gate, which is what makes the 429 below reachable
      (testing "a wrong token throws with :status 401"
        (let [e (caught adapter url "not-the-token" text)]
          (is (some? e))
          (is (= 401 (:status (ex-data e))))
          (is (= "The Routine refused the token." (sch/provider-note 401 nil)))))

      (testing "an unknown routine throws with :status 404"
        (let [e (caught adapter (str (:base world) "/fire/no-such") "the-token" text)]
          (is (some? e))
          (is (= 404 (:status (ex-data e))))
          (is (= "No Routine answers the fire URL." (sch/provider-note 404 nil)))))

      (testing "a routine at its cap throws with :status 429 and :retry-after"
        (let [e (caught adapter url "the-token" text)]
          (is (some? e))
          (is (= 429 (:status (ex-data e))))
          (is (= "60" (:retry-after (ex-data e))))
          (is (= "The Routine has no free run. Try again after 60."
                 (sch/provider-note 429 (:retry-after (ex-data e)))))))

      (testing "a paused routine throws with :status 400, before the cap is read"
        (is (= 200 (:status (w/POST (str (:base world) "/routines/sonnet/pause")
                                    "the-token"))))
        (let [e (caught adapter url "the-token" text)]
          (is (some? e))
          (is (= 400 (:status (ex-data e))))
          (is (= "The Routine is paused at the provider." (sch/provider-note 400 nil)))))

      (testing "a fire with no text at all is a fire (the adapter sends {})"
        (is (= 200 (:status (w/POST (str (:base world) "/routines/sonnet/resume")
                                    "the-token"))))
        (deliver gate true)
        (is (w/wait-for #(zero? (server/running-count world "sonnet"))))
        (is (string? (:session-id (sch/fire-routine adapter url "the-token" nil)))))
      (finally
        (deliver gate true)
        (server/stop! world)))))

;; ── the credential check (R-4.6) ─────────────────────────────────────

(def ^:private delegate-json
  "discover's principal for a delegate's token"
  (json/write-value-as-string {"id" "localfire-claude:sub-1" "type" "agent"
                               "display" "Claude for P" "roles" []}))

(defn- probe-out [result]
  (json/write-value-as-string {"type" "result" "is_error" false "result" result}))

(deftest the-probe-passes-only-for-a-delegate
  (testing "a delegate's principal passes"
    (is (true? (:ok (server/judge-probe 0 (probe-out (str server/probe-pass-word " " delegate-json)) "")))))
  (testing "a person's own token answers, but no seat could sit on it"
    (let [human (json/write-value-as-string {"id" "sub-1" "type" "human"
                                             "display" "P" "roles" ["member"]})
          r (server/judge-probe 0 (probe-out (str server/probe-pass-word " " human)) "")]
      (is (false? (:ok r)))
      (is (= server/probe-not-delegate (:detail r)))))
  (testing "no principal, or the bare pass word, is not a delegate"
    (is (false? (:ok (server/judge-probe 0 (probe-out (str server/probe-pass-word " null")) ""))))
    (is (false? (:ok (server/judge-probe 0 (probe-out server/probe-pass-word) "")))))
  (testing "a failure still says why"
    (is (= "The MCP server answered 401."
           (:detail (server/judge-probe 1 (probe-out "The MCP server answered 401.") ""))))))

(defn- probe-spawner
  "A run goes to the suite's fake; the probe — the one start with no
  `--session-id` — passes or fails as `pass?` says."
  [pass? calls probes]
  (let [runs (w/fake-spawner {:calls calls})]
    (reify spawn/Spawner
      (start [_ argv dir env]
        (if (some #{"--session-id"} argv)
          (spawn/start runs argv dir env)
          (let [ok?  @pass?
                out  (json/write-value-as-string
                      (if ok?
                        {"type" "result" "is_error" false
                         "result" (str server/probe-pass-word " " delegate-json)}
                        {"type" "result" "is_error" true
                         "result" "The MCP server waymark answered 401: the credential expired."}))]
            (swap! probes conj (vec argv))
            (reify spawn/Handle
              (stdout [_] (io/input-stream (.getBytes ^String out "UTF-8")))
              (stderr [_] (io/input-stream (byte-array 0)))
              (await-exit [_ _] (if ok? 0 1))
              (destroy [_] nil)
              (destroy-forcibly [_] nil))))))))

(deftest a-failing-credential-check-answers-the-fire-as-throttled
  (let [pass?   (atom false)
        calls   (atom [])
        probes  (atom [])
        port    (w/free-port)
        cfg     (w/make-config {:port port :place (w/make-place!)
                                :runs-dir (w/tmpdir "lf-runs-check")})
        st      (server/start! {:config cfg :token "the-token" :check? true
                                :spawner (probe-spawner pass? calls probes)})
        base    (str "http://127.0.0.1:" port)
        url     (str base "/fire/sonnet")
        adapter (sch/routine-fire)]
    (try
      (testing "the check ran once at start, with the run's mcp.json shape"
        (is (= 1 (count @probes)))
        (is (some #{(server/probe-tool cfg)} (first @probes)))
        (is (some #{"--strict-mcp-config"} (first @probes)))
        (is (.isFile (server/probe-mcp-file (:runs-dir cfg)))))

      (testing "/healthz is 503 with the probe's detail"
        (let [r (w/GET (str base "/healthz"))]
          (is (= 503 (:status r)))
          (is (false? (get-in r [:json :ok])))
          (is (false? (get-in r [:json :credential :ok])))
          (is (string? (get-in r [:json :credential :checked_at])))
          (is (str/includes? (str (get-in r [:json :credential :detail])) "401"))))

      (testing "a fire answers 429 with Retry-After the seconds to the next check"
        (let [r (w/POST url "the-token")
              s (some-> (:retry-after r) parse-long)]
          (is (= 429 (:status r)))
          (is (some? s))
          (is (<= 1 s 600))
          (is (str/includes? (str (get-in r [:json :detail])) "credential"))
          (is (empty? @calls) "no run was started")))

      (testing "the bearer, the routine and the pause are judged first"
        (is (= 401 (:status (w/POST url "not-the-token"))))
        (is (= 404 (:status (w/POST (str base "/fire/no-such") "the-token")))))

      (testing "the engine's adapter throws with :status 429 and :retry-after"
        (let [e (try (sch/fire-routine adapter url "the-token" "Seat: s\nKey: k\n") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= 429 (:status (ex-data e))))
          (is (string? (:retry-after (ex-data e))))))

      (testing "POST /check needs the bearer"
        (is (= 401 (:status (w/POST (str base "/check") nil)))))

      (testing "when the probe passes again, the check says so and a fire answers 200"
        (reset! pass? true)
        (let [r (w/POST (str base "/check") "the-token")]
          (is (= 200 (:status r)))
          (is (true? (get-in r [:json :ok]))))
        (is (= 200 (:status (w/GET (str base "/healthz")))))
        (is (= 200 (:status (w/POST url "the-token"))))
        (is (w/wait-for #(zero? (server/running-count st "sonnet")))))
      (finally
        (server/stop! st)))))

(deftest a-failed-check-is-rechecked-on-a-backoff
  (let [pass?  (atom false)
        calls  (atom [])
        probes (atom [])
        port   (w/free-port)
        cfg    (w/make-config {:port port :place (w/make-place!)
                               :runs-dir (w/tmpdir "lf-runs-backoff")})
        cs     (:check-seconds cfg)
        st     (server/start! {:config cfg :token "the-token" :check? true
                               :spawner (probe-spawner pass? calls probes)})
        url    (str "http://127.0.0.1:" port "/fire/sonnet")]
    (try
      (testing "a failed probe schedules the next one 30 s out"
        (is (= (min 30 cs) (:next-in @(:credential st))))
        (let [r (w/POST url "the-token")
              s (some-> (:retry-after r) parse-long)]
          (is (= 429 (:status r)))
          (is (some? s))
          (is (<= 1 s 30))))

      (testing "a second failure doubles it"
        (is (= (min 60 cs) (:next-in (server/check-credential! st {:scheduled? true})))))

      (testing "a pass restores check-seconds"
        (reset! pass? true)
        (let [c (server/check-credential! st {:scheduled? true})]
          (is (true? (:ok c)))
          (is (zero? (:failures c)))
          (is (= cs (:next-in c)))))

      (testing "the backoff caps at check-seconds"
        (is (= [600 30 60 120 240 480 600 600]
               (mapv #(server/next-check-seconds 600 %) [0 1 2 3 4 5 6 30]))))

      (testing "the log names a transient failure apart from a refused one"
        (is (= "transient" (server/failure-kind "The MCP server waymark answered 503: no available server")))
        (is (= "transient" (server/failure-kind "The probe did not end within two minutes.")))
        (is (= "refused" (server/failure-kind "The MCP server waymark answered 401: the credential expired."))))
      (finally
        (server/stop! st)))))
