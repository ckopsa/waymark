(ns localfire.server-test
  "The routes and the fire (R-4.4, §5), from the outside, over the
  loopback.

  What this suite proves, in the spec's own order: the judgment of
  R-5.1 happens in that order and stops at the first that holds, and
  each refusal carries one sentence in `detail`; the 200 of R-5.2 has
  exactly the three fields the engine reads and is answered without
  waiting on the process; a body that is not a JSON object is 422
  (R-4.5); the argument vector is the one of R-5.4; the run directory
  is the place copied, with the hook still executable (R-5.3, R-8.4);
  and a pause is a file, so it survives a restart (R-4.4).

  No database, no network beyond the loopback, no `claude` binary: the
  spawner is the seam (R-9.1) and the fake here records the argument
  vector and the directory, writes a canned `stdout.json`, and ends
  when it is told to."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [localfire.config :as config]
            [localfire.runs :as runs]
            [localfire.server :as server]
            [localfire.spawn :as spawn])
  (:import [java.io File]
           [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration]))

;; ── the world ───────────────────────────────────────────────────────

(def canned-stdout
  (json/write-value-as-string
   {"type" "result" "num_turns" 3 "total_cost_usd" 0.1234
    "usage" {"input_tokens" 11 "output_tokens" 22
             "cache_creation_input_tokens" 33 "cache_read_input_tokens" 44}
    "modelUsage" {"claude-sonnet-4-5" {"inputTokens" 11}}}))

(defn fake-spawner
  "The seam's fake (R-9.1). `:calls` collects one map per start;
  `:gate` is a promise the process waits on, so a test can hold a
  routine's only slot open and ask for the 429."
  [{:keys [calls gate stdout exit]}]
  (reify spawn/Spawner
    (start [_ argv dir env]
      (swap! calls conj {:argv (vec argv) :dir (str dir) :env env})
      (let [done (promise)]
        (future (when gate @gate) (deliver done (or exit 0)))
        (reify spawn/Handle
          (stdout [_] (io/input-stream (.getBytes ^String (or stdout canned-stdout) "UTF-8")))
          (stderr [_] (io/input-stream (.getBytes "fake stderr\n" "UTF-8")))
          (await-exit [_ ms] (deref done ms nil))
          (destroy [_] (deliver done 143))
          (destroy-forcibly [_] (deliver done 137)))))))

(defn tmpdir ^File [prefix]
  (.toFile (Files/createTempDirectory (str prefix) (make-array FileAttribute 0))))

(defn make-place!
  "The place is three files (R-5.3, §3), and the hook is executable."
  ^File []
  (let [d (tmpdir "lf-place")]
    (spit (io/file d "CLAUDE.md") "# the seat's place\n")
    (.mkdirs (io/file d ".claude" "hooks"))
    (spit (io/file d ".claude" "settings.json") "{}\n")
    (let [h (io/file d ".claude" "hooks" "sitting-close.sh")]
      (spit h "#!/bin/bash\nexit 0\n")
      (.setExecutable h true false))
    d))

(defn free-port
  "An ephemeral port, taken and given back. The public URL must be
  known BEFORE the server stands, because it goes into every 200."
  []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn make-config [{:keys [port place runs-dir routines]}]
  (config/normalize
   {:port       port
    :public-url (str "http://127.0.0.1:" port)
    :place      (str place)
    :runs-dir   (str runs-dir)
    :mcp        {:name "waymark" :url "http://127.0.0.1:9/mcp"}
    :routines   (or routines {"sonnet" {:model "claude-sonnet-4-5"}})}))

(defn world
  "A server on an ephemeral port with a fake spawner. → the state,
  with `:base`, `:calls`, `:gate` and the two temporary directories."
  ([] (world {}))
  ([{:keys [routines runs-dir token gate exit]}]
   (let [place (make-place!)
         runs  (or runs-dir (tmpdir "lf-runs"))
         port  (free-port)
         calls (atom [])
         cfg   (make-config {:port port :place place :runs-dir runs
                             :routines routines})
         st    (server/start! {:config  cfg
                               :token   (or token "the-token")
                               :spawner (fake-spawner {:calls calls :gate gate
                                                       :exit exit})})]
     (assoc st :base (str "http://127.0.0.1:" port)
            :place place :runs runs :calls calls))))

;; ── a client ────────────────────────────────────────────────────────

(def ^:private http (HttpClient/newHttpClient))

(defn- answer [resp]
  {:status      (.statusCode resp)
   :body        (.body resp)
   :retry-after (some-> (.headers resp) (.firstValue "retry-after") (.orElse nil))
   :json        (try (json/read-value (.body resp) json/keyword-keys-object-mapper)
                     (catch Exception _ nil))})

(defn POST
  ([url token] (POST url token "{}"))
  ([url token body]
   (let [b (-> (HttpRequest/newBuilder (URI/create url))
               (.timeout (Duration/ofSeconds 10))
               (.header "content-type" "application/json")
               ;; the engine sends these two; the server ignores them (R-4.5)
               (.header "anthropic-beta" "experimental-cc-routine-2026-04-01")
               (.header "anthropic-version" "2023-06-01"))]
     (when token (.header b "authorization" (str "Bearer " token)))
     (answer (.send http (-> b (.POST (HttpRequest$BodyPublishers/ofString (str body)))
                             (.build))
                    (HttpResponse$BodyHandlers/ofString))))))

(defn GET [url]
  (answer (.send http (-> (HttpRequest/newBuilder (URI/create url))
                          (.timeout (Duration/ofSeconds 10))
                          (.GET) (.build))
                 (HttpResponse$BodyHandlers/ofString))))

(defn wait-for [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 200) false
          :else (do (Thread/sleep 25) (recur (inc n))))))

;; ── the refusals, in the order of R-5.1 ─────────────────────────────

(deftest refuses-in-the-order-of-r-5-1
  (let [w (world {:routines {"sonnet" {:model "claude-sonnet-4-5"}}})]
    (try
      (testing "401 first: no bearer at all, and a wrong bearer"
        (doseq [r [(POST (str (:base w) "/fire/sonnet") nil)
                   (POST (str (:base w) "/fire/sonnet") "wrong")]]
          (is (= 401 (:status r)))
          (is (string? (get-in r [:json :detail])))
          (is (str/ends-with? (get-in r [:json :detail]) "."))))

      (testing "401 wins over 404: a wrong bearer on an unknown routine"
        (is (= 401 (:status (POST (str (:base w) "/fire/nobody") "wrong")))))

      (testing "404 next: the right bearer, no routine of that name"
        (let [r (POST (str (:base w) "/fire/nobody") "the-token")]
          (is (= 404 (:status r)))
          (is (= "No routine of that name is on this server."
                 (get-in r [:json :detail])))))

      (testing "400 next: the routine paused"
        (is (= 200 (:status (POST (str (:base w) "/routines/sonnet/pause") "the-token"))))
        (let [r (POST (str (:base w) "/fire/sonnet") "the-token")]
          (is (= 400 (:status r)))
          (is (= "The routine is paused on this server." (get-in r [:json :detail]))))

        (testing "404 still wins over 400 for an unknown routine"
          (is (= 404 (:status (POST (str (:base w) "/fire/nobody") "the-token")))))

        (testing "resume lets it fire again"
          (is (= {:routine "sonnet" :paused false}
                 (:json (POST (str (:base w) "/routines/sonnet/resume") "the-token"))))
          (is (= 200 (:status (POST (str (:base w) "/fire/sonnet") "the-token"))))))

      (testing "pause and resume refuse an unknown routine with 404, a bad token with 401"
        (is (= 404 (:status (POST (str (:base w) "/routines/nobody/pause") "the-token"))))
        (is (= 401 (:status (POST (str (:base w) "/routines/sonnet/pause") "wrong")))))
      (finally (server/stop! w)))))

(deftest refuses-429-at-the-run-cap
  (let [gate (promise)
        w    (world {:routines {"sonnet" {:model "claude-sonnet-4-5" :max-concurrent 1}}
                     :gate gate})]
    (try
      (is (= 200 (:status (POST (str (:base w) "/fire/sonnet") "the-token"))))
      (is (wait-for #(= 1 (count @(:calls w)))))
      (let [r (POST (str (:base w) "/fire/sonnet") "the-token")]
        (is (= 429 (:status r)))
        (is (= "60" (:retry-after r)))
        (is (= "The routine has no free run. Try again after 60 seconds."
               (get-in r [:json :detail]))))
      (testing "the slot comes back when the run ends"
        (deliver gate true)
        (is (wait-for #(zero? (server/running-count w "sonnet"))))
        (is (= 200 (:status (POST (str (:base w) "/fire/sonnet") "the-token")))))
      (finally (deliver gate true) (server/stop! w)))))

(deftest refuses-422-for-a-body-that-is-not-an-object
  (let [w (world)]
    (try
      (doseq [body ["[1,2,3]" "\"hello\"" "17" "not json at all"]]
        (let [r (POST (str (:base w) "/fire/sonnet") "the-token" body)]
          (is (= 422 (:status r)) body)
          (is (= "The fire body must be a JSON object." (get-in r [:json :detail])))))
      (testing "and the refused fire took no slot"
        (is (zero? (server/running-count w "sonnet"))))
      (testing "an empty body is a fire with no text, not a refusal"
        (is (= 200 (:status (POST (str (:base w) "/fire/sonnet") "the-token" "")))))
      (finally (server/stop! w)))))

;; ── the 200 and the run ─────────────────────────────────────────────

(deftest fire-answers-the-wire-and-starts-the-run
  (let [w    (world)
        text "instructions\nSeat: ci-classifier\nKey: sk-secret-abc\n"
        r    (POST (str (:base w) "/fire/sonnet") "the-token"
                   (json/write-value-as-string {"text" text}))]
    (try
      (testing "R-5.2: exactly the three fields the engine reads"
        (is (= 200 (:status r)))
        (is (= #{:type :claude_code_session_id :claude_code_session_url}
               (set (keys (:json r)))))
        (is (= "routine_fire" (get-in r [:json :type])))
        (let [id (get-in r [:json :claude_code_session_id])]
          (is (= id (str (java.util.UUID/fromString id))))
          (is (= (str (:base w) "/runs/" id)
                 (get-in r [:json :claude_code_session_url])))

          (is (wait-for #(= 1 (count @(:calls w)))))
          (let [{:keys [argv dir]} (first @(:calls w))]
            (testing "R-5.4: the argument vector, prompt last"
              (is (= ["claude" "-p"
                      "--session-id" id
                      "--model" "claude-sonnet-4-5"
                      "--output-format" "json"
                      "--strict-mcp-config"
                      "--mcp-config" (.getPath (runs/mcp-file (:runs w) id))
                      "--allowedTools" "mcp__waymark__*" "Bash(echo *)"]
                     (vec (butlast argv))))
              (is (str/includes? (last argv) "<routine-fire-payload>"))
              (is (str/includes? (last argv) "Key: sk-secret-abc")))

            (testing "R-5.3: the run runs in its own copy of the place"
              (is (= (.getPath (runs/place-dir (:runs w) id)) dir))
              (is (.isFile (io/file dir "CLAUDE.md")))
              (is (.isFile (io/file dir ".claude" "settings.json")))
              (is (.canExecute (io/file dir ".claude" "hooks" "sitting-close.sh")))
              (is (= {"mcpServers" {"waymark" {"type" "http"
                                               "url" "http://127.0.0.1:9/mcp"}}}
                     (json/read-value (slurp (runs/mcp-file (:runs w) id)))))))

          (testing "R-7.1: the record, with the key withheld in fire.txt"
            (is (wait-for #(= :done (:status (runs/read-run-edn (:runs w) id)))))
            (let [rec (runs/read-run-edn (:runs w) id)]
              (is (= "sonnet" (:routine rec)))
              (is (= "claude-sonnet-4-5" (:model rec)))
              (is (= 0 (:exit rec)))
              (is (string? (:started-at rec)))
              (is (string? (:ended-at rec))))
            (let [fire (slurp (runs/fire-file (:runs w) id))]
              (is (str/includes? fire "Seat: ci-classifier"))
              (is (str/includes? fire "Key: <withheld>"))
              (is (not (str/includes? fire "sk-secret-abc"))))
            (is (= 3 (get (json/read-value (slurp (runs/stdout-file (:runs w) id)))
                          "num_turns")))
            (is (str/includes? (slurp (runs/stderr-file (:runs w) id)) "fake stderr")))

          (testing "R-7.2: the run page, and R-4.4's list"
            (let [p (GET (str (:base w) "/runs/" id))]
              (is (= 200 (:status p)))
              (is (str/includes? (:body p) "<title>run "))
              (is (str/includes? (:body p) "claude-sonnet-4-5"))
              (is (str/includes? (:body p) "num_turns"))
              (is (str/includes? (:body p) "total_cost_usd"))
              (is (str/includes? (:body p) "cache_read_input_tokens"))
              (is (str/includes? (:body p) "modelUsage"))
              (is (str/includes? (:body p) "Key: &lt;withheld&gt;"))
              (is (not (str/includes? (:body p) "<script"))))
            (let [l (GET (str (:base w) "/runs"))]
              (is (= 200 (:status l)))
              (is (str/includes? (:body l) (str "/runs/" id)))))

          (testing "an unknown run is 404, not a stack trace"
            (is (= 404 (:status (GET (str (:base w) "/runs/no-such-run"))))))))
      (finally (server/stop! w)))))

(deftest healthz-names-the-routines
  (let [w (world {:routines {"sonnet" {:model "s"} "haiku" {:model "h"}}})]
    (try
      (let [r (GET (str (:base w) "/healthz"))]
        (is (= 200 (:status r)))
        (is (= {:ok true :routines ["haiku" "sonnet"]} (:json r))))
      (testing "a path no route answers is 404 with a sentence"
        (is (= 404 (:status (GET (str (:base w) "/nothing"))))))
      (finally (server/stop! w)))))

;; ── the pause is a file (R-4.4) ─────────────────────────────────────

(deftest a-pause-survives-a-restart
  (let [runs (tmpdir "lf-runs-shared")
        w1   (world {:runs-dir runs})]
    (is (= {:routine "sonnet" :paused true}
           (:json (POST (str (:base w1) "/routines/sonnet/pause") "the-token"))))
    (is (.isFile (runs/paused-file runs "sonnet")))
    (server/stop! w1)
    (let [w2 (world {:runs-dir runs})]
      (try
        (testing "the second server reads the file and still refuses"
          (is (= 400 (:status (POST (str (:base w2) "/fire/sonnet") "the-token")))))
        (testing "resume removes the file"
          (is (= 200 (:status (POST (str (:base w2) "/routines/sonnet/resume") "the-token"))))
          (is (not (.isFile (runs/paused-file runs "sonnet"))))
          (is (= 200 (:status (POST (str (:base w2) "/fire/sonnet") "the-token")))))
        (finally (server/stop! w2))))))

;; ── the restart names what it did not see end (R-5.6) ───────────────

(deftest a-restart-names-the-runs-in-flight-lost
  (let [runs (tmpdir "lf-runs-lost")
        gate (promise)
        w    (world {:runs-dir runs :gate gate})
        id   (get-in (POST (str (:base w) "/fire/sonnet") "the-token")
                     [:json :claude_code_session_id])]
    (is (wait-for #(= :running (:status (runs/read-run-edn runs id)))))
    (server/stop! w)
    (testing "R-5.6: `running` with no end becomes `lost`, and nothing restarts"
      (is (= 1 (runs/mark-lost! runs)))
      (let [rec (runs/read-run-edn runs id)]
        (is (= :lost (:status rec)))
        (is (string? (:ended-at rec))))
      (is (zero? (runs/mark-lost! runs))))
    (deliver gate true)))
