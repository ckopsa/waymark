(ns localfire.server
  "The routes (R-4.4) and the fire (§5).

  The server impersonates a Routine's fire endpoint and answers that
  wire and no other (§2). Two consequences shape everything here.
  First, the engine reads FOUR failures and puts each on the schedule
  row as a state and a sentence, so a condition the table names must
  never come back 5xx — the judgment of R-5.1 is a `cond` in one
  place, in one order. Second, the engine's fire times out in twenty
  seconds and a run takes minutes, so the 200 is answered and the
  process is started on a thread of its own (R-5.2).

  Seven routes is not enough to earn a router, so the dispatch is a
  `cond` on the path's own segments and the method."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [localfire.config :as config]
            [localfire.page :as page]
            [localfire.prompt :as prompt]
            [localfire.runs :as runs]
            [localfire.spawn :as spawn]
            [org.httpkit.server :as hk])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util UUID]
           [java.util.concurrent Executors ExecutorService
            ScheduledExecutorService ThreadFactory TimeUnit]))

;; ── answers ─────────────────────────────────────────────────────────

(defn- json-resp
  ([status body] (json-resp status body nil))
  ([status body headers]
   {:status  status
    :headers (merge {"content-type" "application/json"} headers)
    :body    (json/write-value-as-string body)}))

(defn- refusal
  "Every refusal carries one sentence in `detail` (R-5.1). The engine
  has its own sentence for 400, 401, 404 and 429 (`provider-note`);
  this one is what a person sees with curl, and what the engine puts
  on the row for a status it has no sentence for."
  ([status detail] (refusal status detail nil))
  ([status detail headers] (json-resp status {:detail detail} headers)))

(defn- html-resp
  ([body] (html-resp 200 body))
  ([status body]
   {:status status :headers {"content-type" "text/html; charset=utf-8"} :body body}))

;; ── the bearer (R-4.3) ──────────────────────────────────────────────

(defn presented-bearer
  "The token the caller presented, or nil."
  [req]
  (some-> (get-in req [:headers "authorization"])
          str
          (str/replace-first #"(?i)^bearer\s+" "")
          not-empty))

(defn token-ok?
  "Constant time (R-4.3). A comparison that returns early on the first
  wrong byte tells a caller how much of the token it guessed."
  [state req]
  (let [p (presented-bearer req)
        t (:token state)]
    (boolean
     (and p t
          (MessageDigest/isEqual (.getBytes ^String p StandardCharsets/UTF_8)
                                 (.getBytes ^String t StandardCharsets/UTF_8))))))

;; ── the run cap (R-5.1) ─────────────────────────────────────────────

(defn- claim!
  "Take one of the routine's slots for `id`, or answer false. It is one
  `swap!` because two fires arriving together must not both read a
  free slot."
  [running routine id cap]
  (let [after (swap! running
                     (fn [m]
                       (let [s (get m routine #{})]
                         (if (< (count s) (long cap))
                           (assoc m routine (conj s id))
                           m))))]
    (contains? (get after routine #{}) id)))

(defn- release! [running routine id]
  (swap! running update routine (fnil disj #{}) id))

(defn running-count [state routine]
  (count (get @(:running state) (str routine) #{})))

;; ── the run ─────────────────────────────────────────────────────────

(defn fire-argv
  "The argument vector of R-5.4, with the config's values in it.

  `--session-id` makes the session's id the one the engine already
  holds, so `CLAUDE_CODE_SESSION_ID` inside the session is the id the
  Routine prompt asks the session to echo and pass to the sit. The
  prompt is the FIRST argument, whole and unquoted — it is the run's
  entire payload and the server never cuts it."
  [cfg routine id prompt-text]
  ;; THE PROMPT COMES FIRST, right behind `-p`, and not last. Both
  ;; `--mcp-config` and `--allowedTools` are VARIADIC in Claude Code —
  ;; `<configs...>` and `<tools...>` — so each one eats every argument
  ;; that follows it. A prompt appended at the end is read as one more
  ;; allowed tool, the session is left with no prompt at all, and it
  ;; waits three seconds on a stdin nobody writes before it exits:
  ;;   Error: Input must be provided either through stdin or as a
  ;;   prompt argument when using --print
  ;; Ahead of both flags it is the positional the parser expects.
  (-> [(:claude cfg) "-p" (str prompt-text)
       "--session-id" (str id)
       "--model" (:model routine)
       "--output-format" "json"
       "--strict-mcp-config"
       "--mcp-config" (.getPath (runs/mcp-file (:runs-dir cfg) id))
       "--allowedTools"]
      (into (map str) (:allowed-tools cfg))))

(defn- log-run!
  "One line per fire (R-7.3): the routine, the run, the status. Not the
  token, not the argument vector, not the text."
  [routine id status]
  (println (str "localfire run routine=" routine " id=" id
                " status=" (name status))))

(defn execute-run!
  "Start the process, drain it, wait for it, record it.

  The two drains are threads of their own because a process whose pipe
  fills and is not read stops, and `claude -p` writes its whole result
  JSON at the end. R-5.5 is the `nil` from `await-exit`: signal, wait ten
  seconds, then destroy, and the record says `killed`.

  → the final record."
  [state {:keys [id argv dir max-run-seconds]}]
  (let [cfg      (:config state)
        runs-dir (:runs-dir cfg)
        handle   (spawn/start (:spawner state) argv dir {})
        drain    (fn [in f] (future (try (io/copy in f) (catch Exception _ nil))))
        d-out    (drain (spawn/stdout handle) (runs/stdout-file runs-dir id))
        d-err    (drain (spawn/stderr handle) (runs/stderr-file runs-dir id))
        first-wait (spawn/await-exit handle (* 1000 (long max-run-seconds)))
        [status exit]
        (if (some? first-wait)
          [(if (zero? (long first-wait)) :done :failed) first-wait]
          (do (spawn/destroy handle)
              (if-let [e (spawn/await-exit handle 10000)]
                [:killed e]
                (do (spawn/destroy-forcibly handle)
                    [:killed (spawn/await-exit handle 10000)]))))]
    (deref d-out 5000 nil)
    (deref d-err 5000 nil)
    (runs/finish! cfg id status exit)))

(defn- start-run!
  "R-5.2: the run goes on a thread of its own, after the answer is
  decided. Whatever happens to it, the routine's slot comes back."
  [state {:keys [id routine text]}]
  (let [cfg (:config state)
        nm  (:name routine)]
    (future
      (try
        (let [argv (fire-argv cfg routine id
                              (prompt/compose (:prompt routine) text))
              rec  (execute-run! state
                                 {:id id
                                  :argv argv
                                  :dir (runs/place-dir (:runs-dir cfg) id)
                                  :max-run-seconds (:max-run-seconds routine)})]
          (log-run! nm id (:status rec)))
        (catch Throwable t
          (runs/finish! cfg id :failed nil)
          (binding [*out* *err*]
            (println (str "localfire run routine=" nm " id=" id
                          " status=failed: " (ex-message t))))
          (log-run! nm id :failed))
        (finally
          (release! (:running state) nm id))))))

;; ── the credential (R-4.6) ────────────────────────────────────────────────
;;
;; A run reaches the engine with the person's stored OAuth credential
;; for the MCP door (R-8.2), and runs claude on the machine's own login.
;; When either expires, every run fails before its sit: the fire is
;; answered 200, a run is spent, and no sitting opens. So the server
;; probes both on a timer — one headless session that calls one cheap
;; Waymark read and exits — and while the probe fails, a fire answers
;; 429 with `Retry-After` the seconds until the next probe. The engine
;; reads 429 as throttled: its pool skips this link until then and
;; fires the next one, a cloud Routine.

(def probe-pass-word
  "What the probe answers when the read answered, and nothing else."
  "CREDENTIAL-OK")

(def ^:private probe-timeout-ms 120000)

(defn probe-dir ^java.io.File [runs-dir] (io/file (str runs-dir) "check"))
(defn probe-mcp-file ^java.io.File [runs-dir] (io/file (probe-dir runs-dir) "mcp.json"))

(defn probe-tool
  "The one tool the probe may call: the engine's cheapest read."
  [cfg]
  (str "mcp__" (get-in cfg [:mcp :name]) "__waymark_discover"))

(defn probe-argv
  "The probe's argument vector. The prompt comes first for the reason
  `fire-argv` gives, and the mcp.json has a run's shape, so the probe
  reaches the door exactly as a run does."
  [cfg]
  (let [tool (probe-tool cfg)]
    [(:claude cfg) "-p"
     (str "Call the tool " tool " once. If it answers, reply with the "
          "single word " probe-pass-word " and nothing else. If it fails "
          "or is not available, reply with one sentence that says why.")
     "--output-format" "json"
     "--strict-mcp-config"
     "--mcp-config" (.getPath (probe-mcp-file (:runs-dir cfg)))
     "--allowedTools" tool]))

(defn- clip [s]
  (let [s (str/trim (str s))]
    (if (> (count s) 300) (str (subs s 0 300) "…") s)))

(defn judge-probe
  "One probe's exit and output → `{:ok :detail}`. It passes only when
  the process exited 0, its result is not an error, and it said the
  pass word."
  [exit stdout stderr]
  (let [r    (try (json/read-value (str stdout) json/keyword-keys-object-mapper)
                  (catch Exception _ nil))
        said (some-> (when (map? r) (:result r)) clip not-empty)]
    (cond
      (nil? exit)
      {:ok false :detail "The probe did not end within two minutes."}

      (and (zero? (long exit)) (not (:is_error r)) (= probe-pass-word said))
      {:ok true :detail "The probe called the MCP door, and it answered."}

      :else
      {:ok false :detail (or said
                             (not-empty (clip stderr))
                             (str "The probe exited " exit "."))})))

(defn- probe!
  "Run one probe through the spawner → `{:ok :detail}`. It never throws."
  [cfg spawner]
  (try
    (let [dir (probe-dir (:runs-dir cfg))
          _   (.mkdirs dir)
          _   (runs/write-mcp! (probe-mcp-file (:runs-dir cfg)) (:mcp cfg))
          h   (spawn/start spawner (probe-argv cfg) dir {})
          out (future (try (slurp (spawn/stdout h)) (catch Exception _ "")))
          err (future (try (slurp (spawn/stderr h)) (catch Exception _ "")))
          ex  (spawn/await-exit h probe-timeout-ms)]
      (when (nil? ex)
        (spawn/destroy-forcibly h)
        (spawn/await-exit h 10000))
      (judge-probe ex (deref out 5000 "") (deref err 5000 "")))
    (catch Throwable t
      {:ok false :detail (str "The probe could not start: " (ex-message t))})))

(defn- credential-initial
  "Before any check. A server that does not check calls its credential
  good, so the fire judges as it always did."
  []
  (atom {:ok true :checked-at nil :next-at nil
         :detail "The credential has not been checked."}))

(defn check-credential!
  "Probe now and record `{ok, checked_at, detail}` on the state's
  `:credential`. `:scheduled?` says the timer ran it, so the next
  check is `:check-seconds` from now. → the credential's state."
  [{:keys [config spawner credential]} {:keys [scheduled?]}]
  (locking credential
    (let [r (probe! config spawner)]
      (swap! credential
             (fn [c]
               (cond-> (merge c r {:checked-at (runs/now-iso)})
                 scheduled?
                 (assoc :next-at (+ (System/currentTimeMillis)
                                    (* 1000 (long (:check-seconds config))))))))
      (println (str "localfire check ok=" (:ok r)))
      @credential)))

(defn- retry-after-seconds
  "The seconds until the next check, at least one; 60 when no check is
  scheduled."
  [c]
  (if-let [n (:next-at c)]
    (max 1 (long (Math/ceil (/ (- (long n) (System/currentTimeMillis)) 1000.0))))
    60))

(defn- credential-view
  "The credential as `/healthz` and `/check` answer it."
  [c]
  {:ok (boolean (:ok c)) :checked_at (:checked-at c) :detail (:detail c)})

(defn- start-check-timer!
  "Check every `:check-seconds`, on a daemon thread of its own."
  ^ScheduledExecutorService [state]
  (let [secs (long (get-in state [:config :check-seconds]))
        ex   (Executors/newSingleThreadScheduledExecutor
              (reify ThreadFactory
                (newThread [_ r]
                  (doto (Thread. ^Runnable r "localfire-check")
                    (.setDaemon true)))))]
    (.scheduleWithFixedDelay ex
                             ^Runnable (fn [] (try (check-credential! state {:scheduled? true})
                                                   (catch Throwable _ nil)))
                             secs secs TimeUnit/SECONDS)
    ex))

;; ── the body (R-4.5) ────────────────────────────────────────────────

(defn- read-json-object
  "The fire's body. The engine sends `{\"text\": …}` or `{}`. Anything
  that is not a JSON object is `::bad`, which R-4.5 answers 422 with
  one sentence; a body with no bytes at all is read as `{}`, because a
  fire with no text is a fire."
  [req]
  (let [s (some-> (:body req) slurp)]
    (if (str/blank? s)
      {}
      (let [v (try (json/read-value s json/keyword-keys-object-mapper)
                   (catch Exception _ ::bad))]
        (if (map? v) v ::bad)))))

;; ── the routes (R-4.4) ──────────────────────────────────────────────

(defn- handle-fire [state nm req]
  (let [cfg      (:config state)
        runs-dir (:runs-dir cfg)
        routine  (config/routine cfg nm)]
    ;; R-5.1: this order, and stop at the first that holds
    (cond
      (not (token-ok? state req))
      (refusal 401 "The bearer token is not this server's token.")

      (nil? routine)
      (refusal 404 "No routine of that name is on this server.")

      (runs/paused? runs-dir nm)
      (refusal 400 "The routine is paused on this server.")

      ;; R-4.6: throttled, so the engine's pool falls back until then
      (not (:ok @(:credential state)))
      (let [c @(:credential state)
            s (retry-after-seconds c)]
        (refusal 429 (str "The credential check failed: " (:detail c)
                          " Try again after " s " seconds.")
                 {"Retry-After" (str s)}))

      :else
      (let [id (str (UUID/randomUUID))]
        (if-not (claim! (:running state) nm id (:max-concurrent routine))
          (refusal 429 "The routine has no free run. Try again after 60 seconds."
                   {"Retry-After" "60"})
          (let [body (read-json-object req)]
            (if (= ::bad body)
              (do (release! (:running state) nm id)
                  (refusal 422 "The fire body must be a JSON object."))
              (let [text (some-> (:text body) str not-empty)]
                (try
                  ;; the record exists before the answer, so the URL the
                  ;; engine is about to write onto the row already opens
                  (runs/begin! cfg {:id id :routine nm :model (:model routine)
                                    :fire-text (prompt/redact-key text)})
                  (start-run! state {:id id :routine routine :text text})
                  (json-resp 200 {:type "routine_fire"
                                  :claude_code_session_id id
                                  :claude_code_session_url (config/run-url cfg id)})
                  (catch Throwable t
                    (release! (:running state) nm id)
                    (json-resp 500 {:detail (str "The run could not be prepared: "
                                                 (ex-message t))})))))))))))

(defn- handle-pause [state nm req pause?]
  (let [cfg (:config state)]
    (cond
      (not (token-ok? state req))
      (refusal 401 "The bearer token is not this server's token.")

      (nil? (config/routine cfg nm))
      (refusal 404 "No routine of that name is on this server.")

      :else
      (do (if pause?
            (runs/pause! (:runs-dir cfg) nm)
            (runs/resume! (:runs-dir cfg) nm))
          (json-resp 200 {:routine nm :paused (boolean pause?)})))))

(defn- handle-run-page [state id]
  (let [runs-dir (get-in state [:config :runs-dir])]
    (if-let [rec (runs/read-run-edn runs-dir id)]
      (html-resp
       (page/run-page {:id id
                       :run rec
                       :fire-text   (runs/slurp-file (runs/fire-file runs-dir id))
                       :stdout-text (runs/slurp-file (runs/stdout-file runs-dir id))
                       :stderr-tail (runs/tail (runs/stderr-file runs-dir id) 4000)}))
      (html-resp 404 (page/not-found-page id)))))

(defn handler [state]
  (fn [req]
    (let [parts  (vec (remove str/blank? (str/split (str (:uri req)) #"/")))
          method (:request-method req)]
      (cond
        (and (= :post method) (= 2 (count parts)) (= "fire" (first parts)))
        (handle-fire state (second parts) req)

        (and (= :post method) (= 3 (count parts))
             (= "routines" (first parts)) (= "pause" (nth parts 2)))
        (handle-pause state (second parts) req true)

        (and (= :post method) (= 3 (count parts))
             (= "routines" (first parts)) (= "resume" (nth parts 2)))
        (handle-pause state (second parts) req false)

        ;; the run pages carry no bearer check: they hold no secret
        ;; (R-7.3) and the engine's row links to them for a browser
        (and (= :get method) (= 2 (count parts)) (= "runs" (first parts)))
        (handle-run-page state (second parts))

        (and (= :get method) (= 1 (count parts)) (= "runs" (first parts)))
        (html-resp (page/runs-page (runs/list-runs (get-in state [:config :runs-dir]))))

        (and (= :get method) (= ["healthz"] parts))
        (let [c (credential-view @(:credential state))]
          (json-resp (if (:ok c) 200 503)
                     {:ok (:ok c)
                      :routines (vec (sort (keys (get-in state [:config :routines]))))
                      :credential c}))

        (and (= :post method) (= ["check"] parts))
        (if-not (token-ok? state req)
          (refusal 401 "The bearer token is not this server's token.")
          (let [c (credential-view (check-credential! state {}))]
            (json-resp (if (:ok c) 200 503) c)))

        :else
        (refusal 404 "No route of this server answers that path.")))))

;; ── standing and stopping ───────────────────────────────────────────

(defn start!
  "Stand the server up. `:spawner` is the seam (R-9.1): the tests give
  a fake, and `main` gives the real one. `:check?` runs the credential
  check (R-4.6) once now and then on its timer. → the state, with
  `:port`, which is the bound port even when the config asked for 0."
  [{:keys [config token spawner check?]}]
  (let [state {:config     config
               :token      token
               :spawner    (or spawner (spawn/process-spawner))
               :running    (atom {})
               :credential (credential-initial)}
        timer (when check?
                (check-credential! state {:scheduled? true})
                (start-check-timer! state))
        srv   (hk/run-server (handler state)
                             {:port (:port config)
                              :legacy-return-value? false})]
    (assoc state :http srv :port (hk/server-port srv) :check-timer timer)))

(defn stop!
  "Stop the server. The runs in flight are not waited on; the next
  start records them `lost` (R-5.6)."
  [state]
  (when-let [^ExecutorService t (:check-timer state)]
    (.shutdownNow t))
  (when-let [srv (:http state)]
    (hk/server-stop! srv {:timeout 100})))
