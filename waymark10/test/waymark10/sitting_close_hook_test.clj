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
  "A fired run's transcript: one sit, its answer, the `middle` lines,
  one more turn."
  [dir port & [middle]]
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
    (spit f (str (str/join "\n" (map wire/write-json
                                      (concat (butlast lines) middle [(last lines)])))
                 "\n"))
    f))

(defn- run-hook!
  "`args` follow the script's name; `extra` is laid over the scrubbed
  environment."
  [dir transcript & [hook {:keys [args extra]}]]
  (let [pb (ProcessBuilder. ^java.util.List (into ["bash" (str script)] args))
        env (.environment pb)]
    (doseq [k ["WAYMARK_SEAT_URL" "WAYMARK_SEAT_KEY" "http_proxy" "HTTP_PROXY"
               "https_proxy" "HTTPS_PROXY" "all_proxy" "ALL_PROXY"]]
      (.remove env k))
    (.put env "TMPDIR" (str dir))
    (doseq [[k v] extra] (.put env ^String k ^String v))
    (let [p (.start pb)
          out (future (slurp (.getInputStream p)))
          err (future (slurp (.getErrorStream p)))]
      (with-open [in (.getOutputStream p)]
        (.write in (.getBytes ^String (wire/write-json
                                       (merge {:session_id "h-1"
                                               :transcript_path (str transcript)
                                               :stop_hook_active false}
                                              hook))
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

(defn- sh! [& argv]
  (let [p (.start (ProcessBuilder. ^java.util.List (vec argv)))
        out (future (slurp (.getInputStream p)))
        err (future (slurp (.getErrorStream p)))]
    (.close (.getOutputStream p))
    {:exit (.waitFor p) :out @out :err @err}))

(deftest the-hook-parses
  (testing "bash reads the script"
    (let [{:keys [exit err]} (sh! "bash" "-n" (str script))]
      (is (zero? exit) err)))
  (testing "every program the script carries compiles"
    (let [programs (re-seq #"(?ms)^read -r -d '' (\w+) <<'PY'\n(.*?)^PY$"
                           (slurp script))]
      (is (= #{"UPLOAD" "SUM"} (set (map second programs))))
      (doseq [[_ nm src] programs]
        (let [{:keys [exit err]}
              (sh! "python3" "-c"
                   "import sys; compile(sys.argv[1], sys.argv[2], 'exec')"
                   src nm)]
          (is (zero? exit) (str nm ": " err)))))))

(deftest a-fired-runs-session-end-closes-nothing
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 200)
        door (str "http://127.0.0.1:" port "/api/-/sittings/close")]
    (try
      (doseq [[label extra] [["with no door in the environment" {}]
                             ["with the door in the environment"
                              {"WAYMARK_SEAT_URL" door}]]]
        (testing label
          (let [{:keys [exit out err]}
                (run-hook! dir (transcript! dir port)
                           {:hook_event_name "SessionEnd"}
                           {:args ["end"] :extra extra})]
            (is (zero? exit) err)
            (is (str/blank? out) "SessionEnd never holds"))))
      (is (empty? @seen) "the Stop closes a fired run; SessionEnd leaves it be")
      (finally (.stop server 0)))))

(deftest close-run-answers-one-status-line
  (doseq [[status line code] [[200 "closed s-1" 0]
                              [409 "already-closed s-1" 0]
                              [500 "failed s-1" 1]]]
    (testing (str "a close answered " status)
      (let [dir (temp-dir)
            [^HttpServer server port seen] (stub-door! status)]
        (try
          (let [{:keys [exit out err]}
                (run-hook! dir (transcript! dir port) nil
                           {:args ["close-run" "Lost by localfire."]})
                sent (first @seen)]
            (is (= code exit) err)
            (is (str/starts-with? out line) out)
            (is (= 1 (count @seen)))
            (is (= a-key (:key sent)))
            (is (= counts (select-keys (:body sent) (keys counts))))
            (is (= "Lost by localfire." (:note (:body sent)))
                "the caller's note stands in for the hook's"))
          (finally (.stop server 0)))))))

(def ^:private launch
  "A background agent launched mid-run, and the harness's answer."
  [{:type "assistant" :requestId "r-a"
    :message {:role "assistant"
              :content [{:type "tool_use" :id "tu-2" :name "Agent"
                         :input {:prompt "Map the hook." :run_in_background true}}]
              :usage {:input_tokens 10 :output_tokens 2
                      :cache_read_input_tokens 0
                      :cache_creation_input_tokens 0}}}
   {:type "user"
    :message {:role "user"
              :content [{:type "tool_result" :tool_use_id "tu-2"
                         :content [{:type "text"
                                    :text "Async agent launched successfully.\nagentId: ag-1"}]}]}}])

(def ^:private hand-back
  "The agent's task notification, and the turn it wakes."
  [{:type "user"
    :message {:role "user"
              :content "<task-notification>\n<task-id>ag-1</task-id>\n<status>completed</status>\n</task-notification>"}}
   {:type "assistant" :requestId "r-b"
    :message {:role "assistant"
              :content [{:type "text" :text "Read the report."}]
              :usage {:input_tokens 20 :output_tokens 4
                      :cache_read_input_tokens 0
                      :cache_creation_input_tokens 0}}}])

(deftest a-fired-run-closes-only-when-its-agents-have-handed-back
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 200)]
    (try
      (testing "a subagent's stop neither closes nor holds"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port launch)
                         {:hook_event_name "SubagentStop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (zero? (count @seen)))))
      (testing "a stop with the agent still out neither closes nor holds"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port launch)
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (zero? (count @seen)))))
      (testing "the stop after the hand-back closes once, with the whole run"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port (concat launch hand-back))
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (= 1 (count @seen)))
          (is (= {:input_tokens 135 :output_tokens 33 :cache_read_tokens 3000
                  :cache_write_tokens 400 :turns 4}
                 (select-keys (:body (first @seen)) (keys counts))))))
      (finally (.stop server 0)))))

(def ^:private queued-hand-back
  "The agent's hand-back as the session's queue takes it in."
  {:type "queue-operation" :operation "enqueue"
   :content "<agent-message from=\"ag-1\">\nThe hook is mapped.\n</agent-message>"})

(def ^:private read-hand-back
  "The queue gives the hand-back up, and the turn it wakes."
  [{:type "queue-operation" :operation "dequeue"}
   {:type "user"
    :message {:role "user"
              :content "<agent-message from=\"ag-1\">\nThe hook is mapped.\n</agent-message>"}}
   {:type "assistant" :requestId "r-b"
    :message {:role "assistant"
              :content [{:type "text" :text "Read the report."}]
              :usage {:input_tokens 20 :output_tokens 4
                      :cache_read_input_tokens 0
                      :cache_creation_input_tokens 0}}}])

(deftest a-fired-run-closes-only-when-its-queue-is-read
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 200)]
    (try
      (testing "a stop with the agent's hand-back still queued neither closes nor holds"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port (concat launch [queued-hand-back]))
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (zero? (count @seen)))))
      (testing "a queued message holds the close with no launch behind it"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port [queued-hand-back])
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (zero? (count @seen)))))
      (testing "the stop after it was dequeued and answered closes once, with the whole run"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port (concat launch [queued-hand-back]
                                                           read-hand-back))
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (= 1 (count @seen)))
          (is (= {:input_tokens 135 :output_tokens 33 :cache_read_tokens 3000
                  :cache_write_tokens 400 :turns 4}
                 (select-keys (:body (first @seen)) (keys counts))))))
      (finally (.stop server 0)))))

(def ^:private absorbed-notification
  "The task notification's `content` in transcript ad132b38, cut as its
  enqueue and its remove both carry it."
  "<task-notification>\n<task-id>a4752f1271760cdc2</task-id>…")

(def ^:private real-queue
  "Every queue-operation line of transcript ad132b38-3c0f-45ee-9a11-e7b7b3a090f1,
  by its seq, with long `content` cut to its opening. A dequeue carries
  no content; a remove carries its enqueue's."
  (into (sorted-map)
        (for [[seq- operation timestamp more]
              [[0 "enqueue" "2026-10-09T02:46:45.054Z"
                {:content "First, run `echo $CLAUDE_CODE_SESSION_ID`…"}]
               [1 "enqueue" "2026-10-09T02:46:45.063Z"
                {:content "<routine-fire-payload>…"}]
               [2 "dequeue" "2026-10-09T02:46:45.324Z" nil]
               [3 "dequeue" "2026-10-09T02:46:45.325Z" nil]
               [64 "enqueue" "2026-10-09T02:49:22.922Z"
                {:content "<agent-message from=\"a4752f1271760cdc2\">…"}]
               [69 "enqueue" "2026-10-09T02:49:25.663Z"
                {:content absorbed-notification}]
               [71 "dequeue" "2026-10-09T02:49:27.423Z" nil]
               [83 "remove" "2026-10-09T02:49:36.281Z"
                {:content absorbed-notification :reason "absorbed_mid_turn"
                 :commandUuid "…" :deliveryId "…"}]]]
          [seq- (merge {:type "queue-operation" :operation operation
                        :timestamp timestamp
                        :sessionId "3c70a8fa-1065-5566-af22-27fce34f1932"}
                       more)])))

(deftest the-queue-hold-reads-the-real-queue-lines
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 200)
        door (str "http://127.0.0.1:" port "/api/-/sittings/close")
        tallied (atom [])
        through (fn [seq-] (vals (subseq real-queue <= seq-)))]
    (.createContext server "/api/-/sittings/tally"
                    (reify HttpHandler
                      (handle [_ ex]
                        (swap! tallied conj (wire/read-json (slurp (.getRequestBody ex))))
                        (answer! ex 200 "{}"))))
    (try
      (testing "through seq 68, the file as the Stop read it: seq 64 alone holds the close"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port (through 68))
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (zero? (count @seen)))))
      (testing "and with the door in the environment that Stop tallies"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port (through 68))
                         {:hook_event_name "Stop"}
                         {:extra {"WAYMARK_SEAT_URL" door}})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (zero? (count @seen)))
          (is (= [counts] (map #(select-keys % (keys counts)) @tallied)))))
      (testing "through seq 83 and a last turn: nothing is queued, so the Stop closes"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port (through 83))
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (= 1 (count @seen)))
          (is (= counts (select-keys (:body (first @seen)) (keys counts))))
          (is (= 1 (count @tallied)) "the close is not a tally")))
      (finally (.stop server 0)))))

(defn- queue-line
  "One queue-operation line; a dequeue carries no content."
  ([operation] {:type "queue-operation" :operation operation})
  ([operation content] (assoc (queue-line operation) :content content)))

(defn- closes-after
  "How many closes a Stop sends after these transcript lines, in a place
  and at a door of its own."
  [lines]
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 200)]
    (try
      (let [{:keys [exit out err]}
            (run-hook! dir (transcript! dir port lines)
                       {:hook_event_name "Stop"})]
        (is (zero? exit) err)
        (is (str/blank? out))
        (count @seen))
      (finally (.stop server 0)))))

(deftest a-remove-takes-its-own-line-or-nothing
  (let [first- (queue-line "enqueue" "the first message")
        second- (queue-line "enqueue" "the second message")]
    (testing "a remove that matches no queued line takes nothing, and the Stop holds"
      (is (zero? (closes-after [first- (queue-line "remove" "another message")])))
      (is (zero? (closes-after [first- second-
                                (queue-line "remove" "another message")
                                (queue-line "dequeue")]))
          "the queue is as it was: one dequeue does not empty it")
      (is (= 1 (closes-after [first- second-
                              (queue-line "remove" "another message")
                              (queue-line "dequeue")
                              (queue-line "dequeue")]))))
    (testing "a matching remove takes its own entry, not the oldest"
      (is (zero? (closes-after [first- second-
                                (queue-line "remove" "the second message")]))
          "the first is still queued")
      (is (= 1 (closes-after [first- second-
                              (queue-line "remove" "the second message")
                              (queue-line "remove" "the first message")]))))
    (testing "a dequeue stays FIFO"
      (is (= 1 (closes-after [first- second-
                              (queue-line "dequeue")
                              (queue-line "remove" "the second message")])))
      (is (zero? (closes-after [first- second-
                                (queue-line "dequeue")
                                (queue-line "remove" "the first message")]))
          "the dequeue took the first, so the second is still queued"))))

(defn- bash-call
  "A Bash call, in the background or not, and the harness's answer."
  [background? answer]
  [{:type "assistant" :requestId "r-c"
    :message {:role "assistant"
              :content [{:type "tool_use" :id "tu-3" :name "Bash"
                         :input (cond-> {:command "sleep 90"}
                                  background? (assoc :run_in_background true))}]
              :usage {:input_tokens 10 :output_tokens 2
                      :cache_read_input_tokens 0
                      :cache_creation_input_tokens 0}}}
   {:type "user"
    :message {:role "user"
              :content [{:type "tool_result" :tool_use_id "tu-3"
                         :content [{:type "text" :text answer}]}]}}])

(def ^:private bash-hand-back
  "The background command's task notification, and the turn it wakes."
  [{:type "user"
    :message {:role "user"
              :content "<task-notification>\n<task-id>bx7k2q9</task-id>\n<status>completed</status>\n</task-notification>"}}
   {:type "assistant" :requestId "r-d"
    :message {:role "assistant"
              :content [{:type "text" :text "The wait is over."}]
              :usage {:input_tokens 20 :output_tokens 4
                      :cache_read_input_tokens 0
                      :cache_creation_input_tokens 0}}}])

(deftest a-fired-run-closes-only-when-its-background-commands-have-handed-back
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 200)
        launch (bash-call true "Command running in background with ID: bx7k2q9. Output is being written to: /tmp/bx7k2q9.output")]
    (try
      (testing "a stop with the command still out neither closes nor holds"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port launch)
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (zero? (count @seen)))))
      (testing "the stop after the notification closes once"
        (let [{:keys [exit out err]}
              (run-hook! dir (transcript! dir port (concat launch bash-hand-back))
                         {:hook_event_name "Stop"})]
          (is (zero? exit) err)
          (is (str/blank? out))
          (is (= 1 (count @seen)))))
      (finally (.stop server 0)))))

(deftest a-foreground-command-does-not-hold-the-close
  (let [dir (temp-dir)
        [^HttpServer server port seen] (stub-door! 200)]
    (try
      (let [{:keys [exit out err]}
            (run-hook! dir (transcript! dir port (bash-call false "done ID: 42"))
                       {:hook_event_name "Stop"})]
        (is (zero? exit) err)
        (is (str/blank? out))
        (is (= 1 (count @seen))))
      (finally (.stop server 0)))))

(def ^:private seat-settings
  (first (filter #(.isFile (io/file %))
                 ["../seat/.claude/settings.json"
                  "seat/.claude/settings.json"])))

(deftest the-seat-place-tells-the-seat-its-session-id-at-start
  (let [hooks (:hooks (wire/read-json (slurp seat-settings)))
        command-of (fn [event] (-> hooks event first :hooks first :command))
        close "$CLAUDE_PROJECT_DIR/.claude/hooks/sitting-close.sh"]
    (testing "the closing hooks still run sitting-close.sh"
      (is (= close (command-of :Stop)))
      (is (= close (command-of :SubagentStop)))
      (is (= (str close " end") (command-of :SessionEnd))))
    (testing "SessionStart answers the stdin's session id as additionalContext"
      (let [p (.start (ProcessBuilder. ^java.util.List
                                       ["bash" "-c" (command-of :SessionStart)]))
            out (future (slurp (.getInputStream p)))
            err (future (slurp (.getErrorStream p)))]
        (with-open [in (.getOutputStream p)]
          (.write in (.getBytes ^String (wire/write-json
                                         {:session_id "s-42"
                                          :hook_event_name "SessionStart"
                                          :source "startup"})
                                "UTF-8")))
        (is (zero? (.waitFor p)) @err)
        (let [said (:hookSpecificOutput (wire/read-json @out))]
          (is (= "SessionStart" (:hookEventName said)))
          (is (= "Your session id is s-42; pass it as `session` to waymark_sit."
                 (:additionalContext said))))))))
