(ns waymark10.sitting-close-hook-test
  "The Stop hook, run as the harness runs it (spec-seat.md R-12.17): a
  fired run whose environment carries no door closes its own sitting
  with the transcript key its sit answered, and holds the stop only
  when that close is refused.

  The door is a stub on a free port: the transcript answers that it
  holds everything, and the close answers what the test says and
  remembers what it was sent. The script is the repository's own,
  run by bash with the hook's JSON on stdin."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.wire :as wire])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private script
  (first (filter #(.isFile (io/file %))
                 ["../.claude/hooks/sitting-close.sh"
                  ".claude/hooks/sitting-close.sh"])))

(def ^:private a-key "dHJhbnNjcmlwdC1rZXktMDE")

(defn- answer! [^HttpExchange ex status ^String body]
  (let [bytes (.getBytes body "UTF-8")]
    (.sendResponseHeaders ex (int status) (long (alength bytes)))
    (with-open [out (.getResponseBody ex)]
      (.write out bytes))))

(defn- stub-door!
  "→ [server port seen]. `seen` holds each close: its key and body."
  [close-status]
  (let [seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/api/-/sittings/transcript"
                    (reify HttpHandler
                      (handle [_ ex]
                        (slurp (.getRequestBody ex))
                        (answer! ex 200 "{\"held\": 1000000}"))))
    (.createContext server "/api/-/sittings/close"
                    (reify HttpHandler
                      (handle [_ ex]
                        (swap! seen conj
                               {:key (.getFirst (.getRequestHeaders ex)
                                                "Waymark-Transcript-Key")
                                :body (wire/read-json (slurp (.getRequestBody ex)))})
                        (answer! ex close-status
                                 "{\"detail\": \"No seat answers this key.\"}"))))
    (.start server)
    [server (.getPort (.getAddress server)) seen]))

(defn- transcript!
  "A fired run's transcript: one sit, its answer, one more turn."
  [dir port]
  (let [f (io/file dir "run.jsonl")
        sat (wire/write-json
             {:sitting "s-1" :mode "fired"
              :transcript {:url (str "http://127.0.0.1:" port
                                     "/api/-/sittings/transcript")
                           :key a-key}})
        lines [{:type "assistant" :requestId "r-1"
                :message {:role "assistant"
                          :content [{:type "tool_use" :id "tu-1"
                                     :name "mcp__Waymark__waymark_sit"
                                     :input {:key "c2VhdC1rZXktMDE"}}]
                          :usage {:input_tokens 100 :output_tokens 20
                                  :cache_read_input_tokens 3000
                                  :cache_creation_input_tokens 400}}}
               {:type "user"
                :message {:role "user"
                          :content [{:type "tool_result" :tool_use_id "tu-1"
                                     :content [{:type "text" :text sat}]}]}}
               {:type "assistant" :requestId "r-2"
                :message {:role "assistant"
                          :content [{:type "text" :text "Done."}]
                          :usage {:input_tokens 5 :output_tokens 7
                                  :cache_read_input_tokens 0
                                  :cache_creation_input_tokens 0}}}]]
    (spit f (str (str/join "\n" (map wire/write-json lines)) "\n"))
    f))

(defn- run-hook! [dir transcript]
  (let [pb (ProcessBuilder. ^java.util.List ["bash" (str script)])
        env (.environment pb)]
    (doseq [k ["WAYMARK_SEAT_URL" "WAYMARK_SEAT_KEY" "http_proxy" "HTTP_PROXY"
               "https_proxy" "HTTPS_PROXY" "all_proxy" "ALL_PROXY"]]
      (.remove env k))
    (.put env "TMPDIR" (str dir))
    (let [p (.start pb)
          out (future (slurp (.getInputStream p)))
          err (future (slurp (.getErrorStream p)))]
      (with-open [in (.getOutputStream p)]
        (.write in (.getBytes ^String (wire/write-json
                                       {:session_id "h-1"
                                        :transcript_path (str transcript)
                                        :stop_hook_active false})
                              "UTF-8")))
      {:exit (.waitFor p) :out @out :err @err})))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "sitting-hook" (make-array FileAttribute 0))))

(def ^:private counts
  {:input_tokens 105 :output_tokens 27 :cache_read_tokens 3000
   :cache_write_tokens 400 :turns 2})

(deftest a-fired-run-closes-its-own-sitting-with-the-transcript-key
  (is (some? script) "the hook is in the repository")
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 200)]
    (try
      (let [{:keys [exit out err]} (run-hook! dir (transcript! dir port))]
        (is (zero? exit) err)
        (is (str/blank? out) "a closed sitting does not hold the stop")
        (is (= 1 (count @seen)))
        (is (= a-key (:key (first @seen))))
        (is (= counts (select-keys (:body (first @seen)) (keys counts)))
            "the hook's exact counts"))
      (finally (.stop server 0)))))

(deftest a-refused-close-holds-the-stop-once
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 404)]
    (try
      (let [{:keys [exit out]} (run-hook! dir (transcript! dir port))]
        (is (zero? exit))
        (is (= 1 (count @seen)))
        (testing "today's hold, with the id and the counts"
          (is (str/includes? out "\"decision\": \"block\""))
          (is (str/includes? out "s-1"))))
      (finally (.stop server 0)))))
