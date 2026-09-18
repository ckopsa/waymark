(ns waymark10.server.mcp-client
  "The two MCP clients an `mcp_server` row can hold (docs/spec-mcp-
  servers.md R-3), and nothing about rows or grants.

  A client is one function, (fn [method params]) → the JSON-RPC
  :result, and it throws an `unreachable` problem when the server did
  not answer. Each client wears two functions as metadata:

  • `::close` stops it (a stdio client kills its process);
  • `::dead?` says whether the failure that just happened is the
    kind the row must go dark for. The http client answers true on
    every failure: a server that did not answer over the wire is
    dark until a person marks it live (R-8). The stdio client
    answers true only after `max-deaths` deaths inside one window,
    because a process that died once is restarted, not abandoned.

  THE HTTP CLIENT is the streamable-HTTP transport's simple half —
  POST one JSON-RPC message, read one response (JSON or a
  single-event SSE body). One session, opened lazily, reused,
  re-initialized once when the server answers that it expired (the
  transport's 404). It came here from gate_proxy.clj whole; the one
  addition is `headers-fn`, called on every request so an
  authorization header is read from the environment at call time and
  never held (R-9).

  THE STDIO CLIENT starts the command with ProcessBuilder, writes
  one JSON-RPC message per line on the process's stdin and reads one
  answer per line from its stdout. `initialize` and
  `notifications/initialized` go once per process. A call waits on a
  promise for its id, with a timeout; a call that times out kills
  the process, because a server that stopped answering is not one
  to keep talking to. When the process dies the pending calls fail,
  the death is counted ONCE however many callers saw it, and the next
  call starts the process again.
  The process's stderr is discarded and its environment is never
  printed.

  `with-timeout` bounds any client that has no timeout of its own
  (the in-process fake the tests register): the call runs on a
  future, and the caller gets `unreachable` when the future has not
  answered in time (R-12)."
  (:require [clojure.string :as str]
            [waymark10.server.problems :as p]
            [waymark10.wire :as wire])
  (:import (java.io BufferedReader BufferedWriter InputStreamReader
                    OutputStreamWriter)
           (java.lang ProcessBuilder ProcessBuilder$Redirect)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpRequest$Builder HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent TimeUnit)))

(set! *warn-on-reflection* true)

(def protocol-version "2025-06-18")

(def client-info {:name "waymark10" :version "10"})

;; ── the refusal ─────────────────────────────────────────────────────

(defn unreachable
  "The 502 every client throws when the server did not answer. The
  type keeps the name `gate-unreachable` the surface has always
  spoken, so a caller that reads it reads the same word."
  [detail]
  (p/problem :gate-unreachable 502 "Server unreachable"
             {:detail (str "The server did not answer this engine: " detail
                           " Nothing was read and nothing was done; the"
                           " grant was judged here either way.")}))

(defn dead?
  "Should the row go dark for the failure this client just had?"
  [client]
  (if-some [f (::dead? (meta client))] (boolean (f)) true))

(defn close!
  "Stop a client. A client with nothing to stop ignores it."
  [client]
  (when-some [f (::close (meta client))] (f))
  nil)

;; ── streamable HTTP, the simple half ────────────────────────────────

(defn- sse-answer
  "A response body that arrived as text/event-stream: the JSON-RPC
  answer is the last data: event carrying a result or an error."
  [body]
  (->> (str/split-lines (str body))
       (map str/trim)
       (filter #(str/starts-with? % "data:"))
       (keep #(try (wire/read-json (str/trim (subs % 5)))
                   (catch Exception _ nil)))
       (filter #(or (contains? % :result) (contains? % :error)))
       last))

(defn- post-message!
  [^HttpClient http ^String url session-id headers timeout-ms msg]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis (long timeout-ms)))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "application/json, text/event-stream"))
        builder (reduce (fn [^HttpRequest$Builder b [k v]]
                          (.header b (str k) (str v)))
                        builder headers)
        builder (if session-id
                  (.header ^HttpRequest$Builder builder
                           "mcp-session-id" (str session-id))
                  builder)
        req (.build (.POST ^HttpRequest$Builder builder
                           (HttpRequest$BodyPublishers/ofString
                            (wire/write-json msg))))
        resp (.send http req (HttpResponse$BodyHandlers/ofString))
        hs (.headers resp)
        ctype (.orElse (.firstValue hs "content-type") "")]
    {:status (.statusCode resp)
     :session-id (.orElse (.firstValue hs "mcp-session-id") nil)
     :answer (let [^String body (.body resp)]
               (cond
                 (str/blank? (str body)) nil
                 (str/includes? ctype "text/event-stream") (sse-answer body)
                 :else (try (wire/read-json body)
                            (catch Exception _ nil))))}))

(defn http-client
  "A JSON-RPC caller over streamable HTTP: (fn [method params]) → the
  :result. `headers-fn` answers the extra request headers on every
  call (nil for none). A JSON-RPC error or a transport failure
  surfaces as the 502 problem."
  ([url] (http-client url nil))
  ([url {:keys [headers-fn timeout-ms] :or {timeout-ms 30000}}]
   (let [http (-> (HttpClient/newBuilder)
                  (.connectTimeout (Duration/ofSeconds 5))
                  (.build))
         ;; :last is the kind of the last failure: :wire (the server
         ;; did not answer, or answered outside 2xx) is fatal for the
         ;; row; :rpc (a JSON-RPC error on a call that arrived) is not
         state (atom {:session nil :id 0 :last nil})
         next-id! #(:id (swap! state update :id inc))
         fail! (fn [kind detail]
                 (swap! state assoc :last kind)
                 (throw (unreachable detail)))
         raw! (fn [msg]
                (try (post-message! http url (:session @state)
                                    (when headers-fn (headers-fn))
                                    timeout-ms msg)
                     (catch Exception e
                       (fail! :wire (ex-message e)))))
         handshake! (fn []
                      (let [{:keys [status session-id answer]}
                            (raw! {:jsonrpc "2.0" :id (next-id!)
                                   :method "initialize"
                                   :params {:protocolVersion protocol-version
                                            :capabilities {}
                                            :clientInfo client-info}})]
                        (when (or (:error answer)
                                  (not (<= 200 (long status) 299)))
                          (fail! :wire
                                 (str "initialize answered " status " "
                                      (some-> (:error answer) :message))))
                        (swap! state assoc :session session-id)
                        (raw! {:jsonrpc "2.0"
                               :method "notifications/initialized"})))
         request! (fn [method params]
                    (raw! {:jsonrpc "2.0" :id (next-id!)
                           :method method :params params}))]
     (with-meta
       (fn rpc [method params]
         (when (nil? (:session @state)) (handshake!))
         (let [{:keys [status answer]} (request! method params)
               {:keys [status answer]} (if (= 404 (long status))
                                         (do (swap! state assoc :session nil)
                                             (handshake!)
                                             (request! method params))
                                         {:status status :answer answer})]
           (cond
             (:error answer)
             (fail! :rpc (str method " answered JSON-RPC error "
                              (get-in answer [:error :code]) ": "
                              (get-in answer [:error :message])))

             (not (<= 200 (long status) 299))
             (fail! :wire (str method " answered HTTP " status "."))

             :else (do (swap! state assoc :last nil)
                       (:result answer)))))
       {::dead? (fn [] (= :wire (:last @state)))
        ::close (fn [] (swap! state assoc :session nil))}))))

;; ── stdio ───────────────────────────────────────────────────────────

(defn- start-process!
  "Start the command. → {:proc :writer :reader}."
  [command args]
  (let [pb (ProcessBuilder. ^java.util.List (into [(str command)]
                                                  (map str) args))]
    (.redirectError pb ProcessBuilder$Redirect/DISCARD)
    (let [proc (.start pb)]
      {:proc proc
       :writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream proc)
                                                     StandardCharsets/UTF_8))
       :reader (BufferedReader. (InputStreamReader. (.getInputStream proc)
                                                    StandardCharsets/UTF_8))})))

(defn stdio-client
  "A JSON-RPC caller over a child process's stdin and stdout:
  (fn [method params]) → the :result.

  `timeout-ms` bounds one call. `max-deaths` inside `window-ms` is
  when `dead?` starts answering true; the row's caller marks the row
  dark then, and `close!` kills what is left."
  [{:keys [command args timeout-ms window-ms max-deaths]
    :or {timeout-ms 30000 window-ms 900000 max-deaths 3}}]
  (let [state (atom {:proc nil :writer nil :reader nil
                     :pending {} :id 0 :deaths []})
        now-ms #(System/currentTimeMillis)
        deaths-in-window (fn []
                           (let [floor (- (now-ms) (long window-ms))]
                             (count (filter #(>= (long %) floor)
                                            (:deaths @state)))))
        fail-pending! (fn [why]
                        (let [pending (:pending @state)]
                          (swap! state assoc :pending {})
                          (doseq [[_ prom] pending]
                            (deliver prom {:failed why}))))
        death-lock (Object.)
        ;; ONE DEATH PER PROCESS (R-3). The end of the process, a
        ;; write that finds a broken pipe and a call that times out
        ;; are three views of the SAME death, and they race: the read
        ;; loop sees the EOF while the writer is still flushing to it.
        ;; So the death is claimed under a lock — the claimant takes
        ;; the process out of the state and counts it, and every other
        ;; view finds it gone and counts nothing. Counting each view
        ;; made three deaths out of two, and the row went dark one
        ;; death early. `proc` names the process the caller watched;
        ;; nil means whichever is current.
        died! (fn [proc why]
                (let [victim (locking death-lock
                               (let [cur (:proc @state)]
                                 (when (and cur
                                            (or (nil? proc)
                                                (identical? proc cur)))
                                   (swap! state assoc :proc nil :writer nil
                                          :reader nil)
                                   (swap! state update :deaths conj (now-ms))
                                   cur)))]
                  (when victim
                    (.destroyForcibly ^Process victim)
                    (fail-pending! why))))
        read-loop! (fn [^BufferedReader reader ^Process proc]
                     (try
                       (loop []
                         (when-some [line (.readLine reader)]
                           (when-not (str/blank? line)
                             (when-some [msg (try (wire/read-json line)
                                                  (catch Exception _ nil))]
                               (when-some [id (:id msg)]
                                 (when-some [prom (get-in @state [:pending (str id)])]
                                   (swap! state update :pending dissoc (str id))
                                   (deliver prom msg)))))
                           (recur)))
                       (catch Exception _ nil))
                     ;; EOF or a read failure: the process is gone
                     (died! proc "the process ended"))
        send! (fn [msg]
                (let [{:keys [^BufferedWriter writer ^Process proc]} @state]
                  (when (nil? writer)
                    (throw (unreachable "the process is not running.")))
                  (try
                    (locking writer
                      (.write writer (wire/write-json msg))
                      (.write writer "\n")
                      (.flush writer))
                    (catch Exception e
                      (died! proc (ex-message e))
                      (throw (unreachable (str "the process did not take the"
                                               " message: " (ex-message e))))))))
        ask! (fn [method params]
               (let [id (str (:id (swap! state update :id inc)))
                     prom (promise)]
                 (swap! state assoc-in [:pending id] prom)
                 (send! {:jsonrpc "2.0" :id id :method method :params params})
                 (let [answer (deref prom (long timeout-ms) ::timeout)]
                   (cond
                     (identical? ::timeout answer)
                     (do (swap! state update :pending dissoc id)
                         (died! (:proc @state) "a call timed out")
                         (throw (unreachable (str method " did not answer in "
                                                  timeout-ms " ms; the process"
                                                  " was stopped."))))

                     (:failed answer)
                     (throw (unreachable (str method " was lost: "
                                             (:failed answer))))

                     (:error answer)
                     (throw (unreachable (str method " answered JSON-RPC error "
                                             (get-in answer [:error :code]) ": "
                                             (get-in answer [:error :message]))))

                     :else (:result answer)))))
        ensure-running! (fn []
                          (when (nil? (:proc @state))
                            (let [{:keys [^Process proc reader writer]}
                                  (try (start-process! command args)
                                       (catch Exception e
                                         (swap! state update :deaths conj (now-ms))
                                         (throw (unreachable
                                                 (str "the command did not start: "
                                                      (ex-message e))))))]
                              (swap! state assoc :proc proc :reader reader
                                     :writer writer :pending {})
                              (doto (Thread. ^Runnable #(read-loop! reader proc)
                                             (str "waymark10-mcp-stdio-" command))
                                (.setDaemon true)
                                (.start))
                              (ask! "initialize" {:protocolVersion protocol-version
                                                  :capabilities {}
                                                  :clientInfo client-info})
                              (send! {:jsonrpc "2.0"
                                      :method "notifications/initialized"}))))]
    (with-meta
      (fn rpc [method params]
        (ensure-running!)
        (ask! method params))
      {::dead? (fn [] (>= (deaths-in-window) (long max-deaths)))
       ;; the close takes the process out of the state FIRST, under
       ;; the same lock: the read loop's EOF then finds it gone and
       ;; counts no death, because a client somebody closed did not
       ;; die on the wire
       ::close (fn []
                 (let [victim (locking death-lock
                                (let [cur (:proc @state)]
                                  (swap! state assoc :proc nil :writer nil
                                         :reader nil)
                                  cur))]
                   (when victim
                     (.destroyForcibly ^Process victim)
                     (.waitFor ^Process victim 2 TimeUnit/SECONDS)))
                 (fail-pending! "the client was closed"))})))

;; ── the timeout every client wears ──────────────────────────────────

(defn with-timeout
  "The same client, with every call bounded by `timeout-ms`: a call
  that has not answered in time throws `unreachable`, and the caller
  is free even when the server is hung. The client's own metadata
  rides along, so `dead?` and `close!` still reach it."
  [client timeout-ms]
  (let [timed-out (atom false)
        inner-dead? (::dead? (meta client))]
    (with-meta
      (fn rpc [method params]
        (let [f (future (try {:ok (client method params)}
                             (catch Throwable t {:threw t})))
              out (deref f (long timeout-ms) ::timeout)]
          (cond
            (identical? ::timeout out)
            (do (reset! timed-out true)
                (throw (unreachable (str method " did not answer in "
                                         timeout-ms " ms."))))

            (contains? out :threw)
            (do (reset! timed-out false)
                (throw (:threw out)))

            :else
            (do (reset! timed-out false)
                (:ok out)))))
      (assoc (meta client)
             ::dead? (fn []
                       (or @timed-out
                           (if inner-dead? (boolean (inner-dead?)) true)))))))
