(ns waymark10.inbox-feed-test
  "The inbox feed, run as a Monitor runs it (docs/seat-inbox-feed.md):
  a batch prints at N events or SECONDS after its first, an urgent
  event prints it at once, a 401 ends the feed with one sentence, and
  the cursor file carries a restart on from the last batch.

  The door is a stub on a free port: it answers each request with the
  next answer the test gave it, then 401, so every run ends by itself.
  The script is the repository's own, run by bash."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [waymark10.wire :as wire])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

(def ^:private script
  (first (filter #(.isFile (io/file %))
                 ["../seat/inbox-feed.sh"
                  "seat/inbox-feed.sh"])))

(def ^:private a-key "aW5ib3gta2V5LTAx")

(def ^:private lapsed
  (str "The inbox key lapsed (the door answered 401): sit again, with the "
       "same session, and start this feed with the inbox the sit answers."))

(defn- event
  "One line of the door's answer: log event `n`, on a row whose id
  opens with `n` in eight digits."
  [n kind action & [more]]
  (merge {:kind kind
          :id (format "%08d-0000-4000-8000-000000000000" n)
          :action action
          :from "open"
          :to "done"
          :summary (str "row " n)
          :at "2026-10-04T00:00:00Z"
          :event n}
         more))

(defn- answer
  "A 200 that carries `events` and names `after` as the last event read."
  [after & events]
  {:status 200
   :after after
   :body (apply str (map #(str (wire/write-json %) "\n") events))})

(defn- stub-door!
  "→ [server url seen]. Each request takes the next of `answers`; when
  none is left the door answers 401. A request for `after=now` takes
  no answer: while one is left it is an empty 200 that names `now`, the
  door's newest event (0 unless given). `seen` holds each request: its
  key and its query."
  [answers & [now]]
  (let [left (atom (vec answers))
        seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/api/-/sittings/inbox"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              query (.getQuery (.getRequestURI ex))
                              now? (= "after=now" query)
                              [old _] (if now? [@left nil] (swap-vals! left #(vec (rest %))))
                              {:keys [status after body sleep]}
                              (or (when (seq old)
                                    (if now? {:status 200 :after (or now 0)} (first old)))
                                  {:status 401
                                   :body "{\"detail\": \"No open sitting answers this inbox key.\"}"})
                              bytes (.getBytes ^String (or body "") "UTF-8")]
                          (swap! seen conj
                                 {:key (.getFirst (.getRequestHeaders ex) "Waymark-Inbox-Key")
                                  :query query})
                          (when sleep (Thread/sleep (long sleep)))
                          (when after
                            (.add (.getResponseHeaders ex) "Waymark-Inbox-After" (str after)))
                          (if (zero? (alength bytes))
                            (.sendResponseHeaders ex (int status) -1)
                            (do (.sendResponseHeaders ex (int status) (long (alength bytes)))
                                (with-open [out (.getResponseBody ex)]
                                  (.write out bytes))))
                          (.close ex)))))
    (.start server)
    [server
     (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/api/-/sittings/inbox")
     seen]))

(defn- run-feed!
  "The script against `url`, with its cursor in the file `cursor`.
  `args` follow the url and the key."
  [url cursor & args]
  (let [pb (ProcessBuilder. ^java.util.List (into ["bash" (str script) url a-key] args))
        env (.environment pb)]
    (doseq [k ["http_proxy" "HTTP_PROXY" "https_proxy" "HTTPS_PROXY" "all_proxy" "ALL_PROXY"]]
      (.remove env k))
    (.put env "WAYMARK_INBOX_CURSOR" (str cursor))
    (.put env "WAYMARK_INBOX_RETRY" "1")
    (.put env "WAYMARK_INBOX_DOWN" "0")
    (let [p (.start pb)
          out (future (slurp (.getInputStream p)))
          err (future (slurp (.getErrorStream p)))
          _ (.close (.getOutputStream p))
          done? (.waitFor p 60 TimeUnit/SECONDS)]
      (when-not done? (.destroyForcibly p))
      {:exit (when done? (.exitValue p))
       :lines (str/split-lines @out)
       :err @err})))

(defn- cursor-file []
  (io/file (.toFile (Files/createTempDirectory "inbox-feed" (make-array FileAttribute 0)))
           "after"))

(deftest a-batch-prints-at-n-events
  (is (some? script) "the feed is in the repository")
  (let [[server url seen] (stub-door! [(answer 2
                                               (event 1 "ticket" "groom" {:actor_name "Ada"})
                                               (event 2 "ticket" "claim"))
                                       (answer 3 (event 3 "ticket" "complete"))])]
    (try
      (let [{:keys [exit lines]} (run-feed! url (cursor-file) "--batch" "3" "--every" "600")]
        (is (= [(str "3 events: ticket 00000001 groom open->done by Ada: row 1"
                     " | ticket 00000002 claim open->done: row 2"
                     " | ticket 00000003 complete open->done: row 3")
                lapsed]
               lines)
            "two answers make one line, and the name is there when the door gave it")
        (is (= 1 exit))
        (is (= ["after=now" "wait=25&after=0" "wait=25&after=2" "wait=25&after=3"]
               (mapv :query @seen))
            "each request goes on from the door's own cursor")
        (is (= #{a-key} (set (map :key @seen)))))
      (finally (.stop server 0)))))

(deftest an-urgent-event-prints-the-batch-at-once
  (let [[server url _] (stub-door! [(answer 3
                                            (event 1 "ticket" "claim")
                                            (event 2 "change" "stall")
                                            (event 3 "ticket" "claim"))])]
    (try
      (let [{:keys [lines]} (run-feed! url (cursor-file) "--batch" "10" "--every" "600")]
        (is (= [(str "2 events: ticket 00000001 claim open->done: row 1"
                     " | change 00000002 stall open->done: row 2")
                "1 event: ticket 00000003 claim open->done: row 3"
                lapsed]
               lines)
            "a stalled change does not wait for ten events"))
      (finally (.stop server 0))))
  (let [[server url _] (stub-door! [(answer 2
                                            (event 1 "ticket" "complete")
                                            (event 2 "change" "stall"))])]
    (try
      (let [{:keys [lines]} (run-feed! url (cursor-file) "--batch" "10" "--every" "600"
                                       "--urgent" "ticket:complete")]
        (is (= ["1 event: ticket 00000001 complete open->done: row 1"
                "1 event: change 00000002 stall open->done: row 2"
                lapsed]
               lines)
            "--urgent replaces the list: a kind and its action"))
      (finally (.stop server 0)))))

(deftest a-batch-prints-when-its-seconds-run-out
  (let [[server url _] (stub-door! (concat [(answer 1 (event 1 "ticket" "claim"))]
                                           (repeat 20 {:status 200 :after 1 :sleep 100})
                                           [(answer 2 (event 2 "ticket" "claim"))]))]
    (try
      (let [{:keys [lines]} (run-feed! url (cursor-file) "--batch" "10" "--every" "1")]
        (is (= ["1 event: ticket 00000001 claim open->done: row 1"
                "1 event: ticket 00000002 claim open->done: row 2"
                lapsed]
               lines)
            "the first event did not wait for the second"))
      (finally (.stop server 0)))))

(deftest a-401-ends-the-feed-with-the-sentence
  (let [[server url seen] (stub-door! [])
        cursor (cursor-file)]
    (try
      (let [{:keys [exit lines]} (run-feed! url cursor)]
        (is (= [lapsed] lines))
        (is (= 1 exit))
        (is (= 1 (count @seen)) "it does not ask again")
        (is (not (.exists cursor)) "a refusal moves no cursor"))
      (finally (.stop server 0)))))

(deftest a-503-is-waited-out-with-the-same-cursor
  (let [cursor (cursor-file)
        [server url seen] (stub-door! [(answer 1 (event 1 "ticket" "claim"))
                                       {:status 503}
                                       {:status 503 :body "{\"detail\": \"The engine is starting.\"}"}
                                       (answer 2 (event 2 "ticket" "claim"))])]
    (try
      (let [{:keys [exit lines]} (run-feed! url cursor "--batch" "1")]
        (is (= ["1 event: ticket 00000001 claim open->done: row 1"
                (str "The inbox door has been down for more than 0 seconds (it last answered 503): "
                     "the key is not refused, so the feed keeps waiting and asks again with the same cursor.")
                "1 event: ticket 00000002 claim open->done: row 2"
                lapsed]
               lines)
            "two 503s do not end the feed, and the line about them prints one time")
        (is (= 1 exit) "the 401 at the end is what ends it")
        (is (= ["after=now" "wait=25&after=0" "wait=25&after=1" "wait=25&after=1"
                "wait=25&after=1" "wait=25&after=2"]
               (mapv :query @seen))
            "each ask after a 503 carries the cursor of the last 200")
        (is (= "2" (str/trim (slurp cursor)))))
      (finally (.stop server 0)))))

(deftest no-cursor-begins-at-the-present
  (let [cursor (cursor-file)
        [server url seen] (stub-door! [(answer 41 (event 41 "ticket" "claim"))] 40)]
    (try
      (let [{:keys [lines]} (run-feed! url cursor "--batch" "1")]
        (is (= ["1 event: ticket 00000041 claim open->done: row 41" lapsed] lines)
            "nothing older than the start is printed")
        (is (= ["after=now" "wait=25&after=40" "wait=25&after=41"] (mapv :query @seen))
            "it asks for now one time, and tails from the door's newest event")
        (is (= "41" (str/trim (slurp cursor)))))
      (finally (.stop server 0)))))

(deftest the-cursor-survives-a-restart
  (let [cursor (cursor-file)]
    (let [[server url _] (stub-door! [(answer 9 (event 7 "ticket" "claim"))])]
      (try
        (let [{:keys [lines]} (run-feed! url cursor "--batch" "1")]
          (is (= ["1 event: ticket 00000007 claim open->done: row 7" lapsed] lines))
          (is (= "9" (str/trim (slurp cursor)))
              "the file holds the last event the door read"))
        (finally (.stop server 0))))
    (let [[server url seen] (stub-door! [])]
      (try
        (run-feed! url cursor "--batch" "1")
        (is (= ["wait=25&after=9"] (mapv :query @seen))
            "the second run goes on from the file")
        (finally (.stop server 0))))))
