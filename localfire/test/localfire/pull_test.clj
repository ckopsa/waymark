(ns localfire.pull-test
  "The pull wire (§13), against a stub engine on the loopback.

  What this suite proves (R-13.6): a fire comes by a poll that carries
  the token in `Waymark-Runner-Token` and not as a bearer, starts a run
  through the server's own fire door, and is answered with the push
  wire's session id and URL; the loop backs off, one second doubling,
  while the engine is unreachable and resumes when it is back, each wait
  jittered between half and the whole of its step; a 401 does not stop
  it, and a new token written to the token file is taken up without a
  restart; and the token is in no file and no log. And (LP3) a fire is
  journaled before its run: after a crash, or handed out again, it is
  answered from the journal and never run twice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [localfire.config :as config]
            [localfire.pull :as pull]
            [localfire.runs :as runs]
            [localfire.server :as server]
            [localfire.server-test :as w]
            [org.httpkit.server :as hk])
  (:import [java.io StringWriter]))

(def secret "pull-token-s3cret")

(defn stub-engine
  "The engine's two pull routes on `port`. `answers` is a queue of what
  the GET answers in turn, a fire map or a status; once it is empty the
  GET answers 204 after a short wait. `post-statuses` is a queue of what
  the POST answers in turn; once it is empty the POST answers 200.
  `seen` collects every request."
  ([port answers seen] (stub-engine port answers seen (atom [])))
  ([port answers seen post-statuses]
   (hk/run-server
    (fn [req]
      (let [body (some-> (:body req) slurp)]
        (swap! seen conj {:method        (:request-method req)
                          :uri           (:uri req)
                          :token         (get-in req [:headers "waymark-runner-token"])
                          :authorization (get-in req [:headers "authorization"])
                          :body          (when (seq body) (json/read-value body))}))
      (if (= :post (:request-method req))
        (if-let [s (first (first (swap-vals! post-statuses #(vec (rest %)))))]
          {:status s :body ""}
          {:status 200 :headers {"content-type" "application/json"} :body "{}"})
        (let [a (first (first (swap-vals! answers #(vec (rest %)))))]
          (cond
            (map? a) {:status 200 :headers {"content-type" "application/json"}
                      :body (json/write-value-as-string a)}
            (int? a) {:status a :body ""}
            :else    (do (Thread/sleep 50) {:status 204 :body ""})))))
    {:port port :legacy-return-value? false})))

(defn pull-cfg
  "A pull map on `port`, with a journal of its own under the temp dir."
  [port]
  {:engine-url (str "http://127.0.0.1:" port) :link-id "link-1"
   :routine "sonnet" :token-env "LF_PULL_TOKEN"
   :journal (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "lf-journal-" (random-uuid)) "pull-journal.edn"))})

(defn- posts [seen] (filter #(= :post (:method %)) @seen))

(defn- files-holding [dir s]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/includes? (slurp %) s))))

(deftest a-pulled-fire-runs-and-is-answered
  (let [port    (w/free-port)
        answers (atom [{:id "fire-1" :text "Seat: s\nKey: k-123"}])
        seen    (atom [])
        eng     (stub-engine port answers seen)
        world   (w/world)
        err     (StringWriter.)
        cfg     (pull-cfg port)
        p       (binding [*err* err] (pull/start! world cfg secret))]
    (try
      (is (w/wait-for #(seq (posts seen))))
      (let [poll   (first @seen)
            post   (first (posts seen))
            run-id (get-in post [:body "claude_code_session_id"])]
        (testing "the poll carries the token in its own header, not as a bearer"
          (is (= :get (:method poll)))
          (is (= "/api/-/runner-links/link-1/fires" (:uri poll)))
          (is (= secret (:token poll)))
          (is (nil? (:authorization poll))))
        (testing "the fire starts a run as a pushed fire does"
          (is (w/wait-for #(= 1 (count @(:calls world)))))
          (is (.isDirectory (runs/run-dir (:runs world) run-id)))
          (is (= (str (runs/place-dir (:runs world) run-id))
                 (:dir (first @(:calls world))))))
        (testing "the answer is the push wire's 200 body, under the same header"
          (is (= "/api/-/runner-links/link-1/fires/fire-1" (:uri post)))
          (is (= secret (:token post)))
          (is (string? run-id))
          (is (= (config/run-url (:config world) run-id)
                 (get-in post [:body "claude_code_session_url"]))))
        (testing "the token is in no file and no log"
          (is (w/wait-for #(= 1 (count @(:closes world)))))
          (is (empty? (files-holding (:runs world) secret)))
          (is (not (str/includes? (str err) secret)))
          (is (not (str/includes? (str err) "k-123"))))
        (testing "a fire whose answer landed leaves the journal empty"
          (is (w/wait-for #(= {} (pull/read-journal (:journal cfg)))))
          (is (empty? (files-holding (.getParentFile (io/file (:journal cfg))) "k-123")))))
      (finally
        (pull/stop! p)
        (hk/server-stop! eng)
        (server/stop! world)))))

(deftest a-crash-before-the-answer-lands-is-answered-from-the-journal
  (let [port  (w/free-port)
        cfg   (pull-cfg port)
        seen  (atom [])
        ;; the run starts, but the engine takes no answer before the lid closes
        eng   (stub-engine port (atom [{:id "fire-3" :text "before the lid closed"}])
                           seen (atom (vec (repeat 100 503))))
        world (w/world)
        quiet {:sleep (fn [_] (Thread/sleep 20))}
        p     (binding [*err* (StringWriter.)] (pull/start! world cfg secret quiet))]
    (try
      (is (w/wait-for #(seq (posts seen))) "the answer is tried and fails")
      (is (w/wait-for #(= 1 (count @(:calls world)))))
      ;; the crash: the loop and the engine it reached are gone
      (pull/stop! p)
      (hk/server-stop! eng)
      (let [held   (get (pull/read-journal (:journal cfg)) "fire-3")
            run-id (get-in held [:answer :claude_code_session_id])
            port2  (w/free-port)
            seen2  (atom [])
            eng2   (stub-engine port2 (atom []) seen2)
            p2     (binding [*err* (StringWriter.)]
                     (pull/start! world (assoc cfg :engine-url (str "http://127.0.0.1:" port2))
                                  secret quiet))]
        (try
          (is (= "link-1" (:link held)))
          (is (string? run-id) "the journal holds what the run answered")
          (is (w/wait-for #(seq (posts seen2))) "the restarted loop answers it first")
          (let [post (first (posts seen2))]
            (is (= "/api/-/runner-links/link-1/fires/fire-3" (:uri post)))
            (is (= run-id (get-in post [:body "claude_code_session_id"]))))
          (is (w/wait-for #(= {} (pull/read-journal (:journal cfg)))))
          (is (= 1 (count @(:calls world))) "and does not run it again")
          (finally
            (pull/stop! p2)
            (hk/server-stop! eng2))))
      (finally
        (pull/stop! p)
        (server/stop! world)))))

(deftest a-journaled-fire-handed-out-again-is-answered-not-run
  (let [port  (w/free-port)
        cfg   (pull-cfg port)
        _     (io/make-parents (io/file (:journal cfg)))
        ;; localfire stopped before it knew what fire-4's run answered;
        ;; fire-9 is another link's
        _     (spit (:journal cfg) (pr-str {"fire-4" {:link "link-1"}
                                            "fire-9" {:link "link-9"}}))
        seen  (atom [])
        ;; the first answer fails, and the lease has run out: fire-4 is handed out again
        eng   (stub-engine port (atom [{:id "fire-4" :text "again"}]) seen (atom [503]))
        world (w/world)
        p     (binding [*err* (StringWriter.)]
                (pull/start! world cfg secret {:sleep (fn [_] (Thread/sleep 20))}))]
    (try
      (is (w/wait-for #(= {"fire-9" {:link "link-9"}} (pull/read-journal (:journal cfg)))))
      (let [ps (posts seen)]
        (is (= 2 (count ps)) "tried from the journal, then answered when handed out")
        (is (every? #(= "/api/-/runner-links/link-1/fires/fire-4" (:uri %)) ps))
        (is (= 400 (get-in (last ps) [:body "status"])))
        (is (false? (get-in (last ps) [:body "started"]))))
      (is (empty? @(:calls world)) "never run")
      (finally
        (pull/stop! p)
        (hk/server-stop! eng)
        (server/stop! world)))))

(deftest a-dropped-engine-is-backed-off-and-resumed
  (let [port    (w/free-port)
        answers (atom [{:id "fire-2" :text "after the cafe"}])
        seen    (atom [])
        sleeps  (atom [])
        world   (w/world)
        p       (binding [*err* (StringWriter.)]
                  (pull/start! world (pull-cfg port) secret
                               {:sleep  (fn [ms] (swap! sleeps conj ms) (Thread/sleep 20))
                                :random (constantly 0.0)}))]
    (try
      ;; nothing listens on the port yet: the lid is closed
      (is (w/wait-for #(>= (count @sleeps) 3)))
      (is (= [500 1000 2000] (take 3 @sleeps)) "one second, doubling, the lowest draw halves it")
      (let [eng (stub-engine port answers seen)]
        (try
          (is (w/wait-for #(seq (posts seen))) "the waiting fire runs")
          (is (= "/api/-/runner-links/link-1/fires/fire-2" (:uri (first (posts seen)))))
          (finally (hk/server-stop! eng))))
      (finally
        (pull/stop! p)
        (server/stop! world)))))

(deftest ten-401s-do-not-end-the-loop
  (let [port    (w/free-port)
        seen    (atom [])
        sleeps  (atom [])
        eng     (stub-engine port (atom (vec (repeat 10 401))) seen)
        world   (w/world)
        err     (StringWriter.)
        p       (binding [*err* err]
                  (pull/start! world (pull-cfg port) secret
                               {:sleep (fn [ms] (swap! sleeps conj ms) (Thread/sleep 5))}))]
    (try
      (is (w/wait-for #(> (count @seen) 10)) "it asks again after the tenth")
      (is (not (realized? (:run p))) "the loop is still running")
      (is (every? #(<= 30000 % 60000) (take 10 @sleeps)) "each 401 waits at the jittered cap")
      (is (empty? @(:calls world)))
      (is (w/wait-for #(str/includes? (str err) "pulling again")) "a 204 after the 401s says it is back")
      (let [log (str err)]
        (is (= 1 (count (re-seq #"waiting for a new one" log))) "one line while refused")
        (is (str/includes? log "LF_PULL_TOKEN"))
        (is (not (str/includes? log secret))))
      (finally
        (pull/stop! p)
        (hk/server-stop! eng)
        (server/stop! world)))))

(defn- token-engine
  "An engine that takes only the token `good` holds: any other answers
  401, and the good one gets `fire` once, then 204s."
  [port good fire seen]
  (let [given (atom false)]
    (hk/run-server
     (fn [req]
       (let [tok (get-in req [:headers "waymark-runner-token"])]
         (swap! seen conj {:method (:request-method req) :uri (:uri req) :token tok})
         (cond
           (not= @good tok) {:status 401 :body ""}
           (= :post (:request-method req))
           {:status 200 :headers {"content-type" "application/json"} :body "{}"}
           (compare-and-set! given false true)
           {:status 200 :headers {"content-type" "application/json"}
            :body (json/write-value-as-string fire)}
           :else (do (Thread/sleep 50) {:status 204 :body ""}))))
     {:port port :legacy-return-value? false})))

(deftest a-rotated-token-is-taken-up-without-a-restart
  (let [port  (w/free-port)
        old   "old-pull-s3cret"
        f     (doto (java.io.File/createTempFile "lf-pull" ".token") (.deleteOnExit))
        _     (spit f (str old "\n"))
        seen  (atom [])
        eng   (token-engine port (atom secret) {:id "fire-3" :text "rotated"} seen)
        world (w/world)
        err   (StringWriter.)
        cfg   (assoc (pull-cfg port) :token-file (str f))
        p     (binding [*err* err]
                (pull/start! world cfg #(config/pull-token cfg)
                             {:sleep (fn [_] (Thread/sleep 20))}))]
    (try
      (is (w/wait-for #(>= (count @seen) 3)) "it keeps asking after a 401")
      (is (every? #(= old (:token %)) @seen))
      (spit f (str secret "\n"))
      (is (w/wait-for #(seq (posts seen))) "the new token is taken up")
      (is (= "/api/-/runner-links/link-1/fires/fire-3" (:uri (first (posts seen)))))
      (is (= secret (:token (first (posts seen)))))
      (is (not (realized? (:run p))))
      (is (w/wait-for #(str/includes? (str err) "pulling again")))
      (let [log (str err)]
        (is (= 1 (count (re-seq #"waiting for a new one" log))) "one line while refused")
        (is (str/includes? log (str f)))
        (is (not (str/includes? log old)))
        (is (not (str/includes? log secret))))
      (finally
        (pull/stop! p)
        (hk/server-stop! eng)
        (server/stop! world)))))

(deftest backoff-waits-are-jittered
  (is (= 500 (pull/jittered 1000 0.0)))
  (is (= 1000 (pull/jittered 1000 0.9999999)))
  (is (= 30000 (pull/jittered 60000 0.0)))
  (is (= 60000 (pull/jittered 60000 0.9999999)))
  (is (every? #(<= 500 % 1000) (repeatedly 1000 #(pull/jittered 1000 (Math/random)))))
  (is (every? #(<= 30000 % 60000) (repeatedly 1000 #(pull/jittered 60000 (Math/random))))))

(deftest the-answer-names-what-the-engine-reads
  (is (= {:claude_code_session_id "r" :claude_code_session_url "u"}
         (pull/answer-body {:status 200 :id "r" :url "u"})))
  (is (= {:status 429 :retry_after 7} (pull/answer-body {:status 429 :retry-after 7})))
  (is (= {:status 400} (pull/answer-body {:status 400 :detail "paused"})))
  (is (= {:status 429 :retry_after 60} (pull/answer-body {:status 500 :detail "no"})))
  (is (= [1000 2000 4000 8000 16000 32000 60000 60000]
         (take 8 (rest (iterate #(pull/next-backoff % 1000 60000) nil))))))

(deftest the-pull-config-holds-no-secret
  (let [base {:port 0 :public-url "http://x" :place (str (w/make-place!))
              :runs-dir "/tmp/lf-runs" :mcp {:url "http://m"}
              :routines {:sonnet {:model "claude-sonnet-4-5"}}}]
    (is (nil? (:pull (config/normalize base))))
    (is (= {:engine-url "https://e" :link-id "l" :routine "sonnet"
            :token-env "LOCALFIRE_PULL_TOKEN"}
           (:pull (config/normalize
                   (assoc base :pull {:engine-url "https://e/" :link-id "l"
                                      :routine :sonnet})))))
    (testing ":token-file sits beside :token-env, and wins when both are set"
      (let [f (doto (java.io.File/createTempFile "lf-pull" ".token") (.deleteOnExit))
            p (:pull (config/normalize
                      (assoc base :pull {:engine-url "https://e" :link-id "l" :routine "sonnet"
                                         :token-env "LF_NONE" :token-file (str f)})))]
        (is (= (str f) (:token-file p)))
        (is (nil? (config/pull-token p)) "an empty file is no token")
        (spit f "t-1\n")
        (is (= "t-1" (config/pull-token p)))
        (spit f "t-2")
        (is (= "t-2" (config/pull-token p)) "read again on each call")
        (is (not (str/includes? (config/pull-token-source p) "t-2")))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not under :routines"
                          (config/normalize
                           (assoc base :pull {:engine-url "https://e" :link-id "l"
                                              :routine "opus"}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not hold the link's token"
                          (config/normalize
                           (assoc base :pull {:engine-url "https://e" :link-id "l"
                                              :routine "sonnet" :token "t"}))))))
