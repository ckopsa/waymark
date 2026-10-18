(ns localfire.pull
  "The pull wire (§13): localfire asks the engine for its fires instead
  of waiting to be sent them.

  A laptop behind a home router or a cafe's network cannot take the
  engine's POST, but it can open a connection out. So with `:pull` in
  the config localfire long-polls its runner link's fires, starts each
  one through the door a pushed fire goes through (`server/fire!`), and
  posts the answer back. A network error or a 5xx backs off, so a
  machine that sleeps or changes networks resumes on its own; a 401
  stops the loop, because no wait mends a wrong or revoked token.

  A pulled fire is journaled before its run starts and leaves the
  journal when the engine takes its answer (LP3), so a fire the engine
  hands out again — after a crash, a sleep or a failed answer — is
  answered from the journal and never run twice.

  The token rides its own header and nowhere else. Nothing here logs
  it, nor the fire's text, which carries a one-time Key; the journal
  holds neither."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [jsonista.core :as json]
            [localfire.people :as people]
            [localfire.server :as server])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.time Duration]))

(def token-header
  "The header the link's token rides in (R-13.3). Not an Authorization
  bearer: the engine reads every bearer as OIDC and refuses the rest."
  "Waymark-Runner-Token")

(def first-backoff-ms 1000)
(def max-backoff-ms 60000)

(defn next-backoff
  "The wait after a failed poll (R-13.5): `first-ms` after an answer,
  doubled after each failure, never more than `max-ms`."
  [prev first-ms max-ms]
  (if prev (min (long max-ms) (* 2 (long prev))) (long first-ms)))

(defn- enc [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn fires-url [{:keys [engine-url link-id]}]
  (str engine-url "/api/-/runner-links/" (enc link-id) "/fires"))

(defn answer-body
  "What a fire's answer from `server/fire!` posts back (R-13.4): the
  push wire's 200 body for a started run, or the status the engine
  reads. A failure the engine has no word for is a 429 for a minute,
  so the fire is tried again."
  [{:keys [status id url retry-after]}]
  (case (long status)
    200 {:claude_code_session_id id :claude_code_session_url url}
    400 {:status 400}
    ;; a person's routine with no live token (R-12.8): the link is broken
    401 {:status 401}
    429 {:status 429 :retry_after (long (or retry-after 60))}
    {:status 429 :retry_after 60}))

(defn- log! [& parts]
  (binding [*out* *err*] (println (apply str parts))))

(defn- send!
  "A GET, or a POST of `body` as JSON, with the token in its header."
  ^HttpResponse [^HttpClient c ^String token ^String url body]
  (let [b (-> (HttpRequest/newBuilder (URI/create url))
              ;; the engine holds a poll about 25 seconds
              (.timeout (Duration/ofSeconds 60))
              (.header token-header token))
        b (if body
            (-> b
                (.header "content-type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (json/write-value-as-string body))))
            (.GET b))]
    (.send c (.build b) (HttpResponse$BodyHandlers/ofString))))

;; ── the journal (LP3) ───────────────────────────────────────────────

(defn journal-path
  "Where a loop's journal lives: the `:pull` map's `:journal`, or else
  one file under localfire's state dir. Each entry names its link, so
  the loops of several links share the file."
  [pull]
  (or (some-> (:journal pull) str not-empty)
      (str (or (some-> (System/getenv "XDG_STATE_HOME") not-empty)
               (str (or (System/getenv "HOME") (System/getProperty "user.home"))
                    "/.local/state"))
           "/localfire/pull-journal.edn")))

(def ^:private journal-lock (Object.))

(defn read-journal
  "The journal at `path`, {fire-id {:link link-id :answer body}}: every
  fire localfire took whose answer the engine has not taken. An entry
  without `:answer` is a fire whose run may or may not have started.
  {} when there is no file."
  [path]
  (let [f (io/file path)]
    (or (when (.exists f) (edn/read-string (slurp f))) {})))

(defn- journal!
  "Swap the journal at `path` through `f`, written whole and moved into
  place, so a crash leaves the old file or the new one."
  [path f & args]
  (locking journal-lock
    (let [file (io/file path)
          tmp  (io/file (str path ".tmp"))]
      (io/make-parents file)
      (spit tmp (pr-str (apply f (read-journal path) args)))
      (Files/move (.toPath tmp) (.toPath file)
                  ^"[Ljava.nio.file.CopyOption;"
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                          StandardCopyOption/ATOMIC_MOVE])))))

(def unknown-answer
  "The answer of a journaled fire localfire cannot vouch for: it stopped
  between journaling the fire and learning what its run answered. Not
  started, and a 400, which the engine reads as a transient miss."
  {:status  400
   :started false
   :reason  "localfire stopped before it knew whether the run started, and does not start a fire twice."})

(defn- answer!
  "Post `body` as fire `fid`'s answer, and drop the fire from the
  journal once the engine took it (a 2xx) or once no retry can land it
  (a 4xx but 401: a 404 says the engine holds no such fire). A 401, a
  5xx or a network error leaves it there for the next try. → the status."
  [c token pull fid body]
  (let [st (.statusCode (send! c token (str (fires-url pull) "/" (enc fid)) body))]
    (when (or (<= 200 st 299) (and (<= 400 st 499) (not= 401 st)))
      (journal! (journal-path pull) dissoc fid))
    st))

(defn- step-of
  "The loop's step once an answer's POST was answered `st`."
  [st]
  (cond (= 401 st)                         :unauthorized
        (or (<= 200 st 299) (<= 400 st 499)) :fire
        :else                              :error))

(defn- replay!
  "Answer every fire of this link the journal holds, with what its run
  answered or else `unknown-answer`; none of them runs again. → nil,
  or :error or :unauthorized when an answer did not land."
  [c token pull]
  (reduce (fn [worst [fid {:keys [answer]}]]
            (let [st (answer! c token pull fid (or answer unknown-answer))]
              (log! "localfire pull fire=" fid " answered from the journal, answer=" st)
              (case (step-of st)
                :unauthorized (reduced :unauthorized)
                :error        :error
                worst)))
          nil
          (filter #(= (:link-id pull) (:link (val %)))
                  (read-journal (journal-path pull)))))

(defn- take-one!
  "The journal's answers, then one poll and, when it brings a fire, the
  run and the answer: the fire is journaled before its run, and a fire
  the journal holds already is answered from it and not run. A failed
  answer stays journaled and backs off. → :fire, :empty, :unauthorized
  or :error."
  [c state pull token]
  (let [held (replay! c token pull)]
    (if (= :unauthorized held)
      held
      (let [resp (send! c token (fires-url pull) nil)
            st   (.statusCode resp)
            step (cond
                   (= 204 st) :empty
                   (= 401 st) :unauthorized
                   (not= 200 st) (do (log! "localfire pull poll answered " st) :error)
                   :else
                   (let [fire (json/read-value (str (.body resp)) json/keyword-keys-object-mapper)
                         fid  (str (:id fire))
                         path (journal-path pull)]
                     (if-let [{:keys [answer]} (get (read-journal path) fid)]
                       (let [back (answer! c token pull fid (or answer unknown-answer))]
                         (log! "localfire pull fire=" fid " is journaled, so it is answered and not run;"
                               " answer=" back)
                         (step-of back))
                       (let [_    (journal! path assoc fid {:link (:link-id pull)})
                             text (some-> (:text fire) str not-empty)
                             ans  (server/fire! state (:routine pull) (constantly text))
                             body (answer-body ans)
                             _    (journal! path assoc fid {:link (:link-id pull) :answer body})
                             back (answer! c token pull fid body)]
                         (log! "localfire pull fire=" fid " routine=" (:routine pull)
                               " status=" (:status ans) " answer=" back)
                         (step-of back)))))]
        (if (and (= :error held) (#{:fire :empty} step)) :error step)))))

(defn start!
  "Stand the pull loop up beside the server's `state` (§13), on a thread
  of its own. `:sleep`, `:first-ms` and `:max-ms` are the tests' seam
  on the backoff. → {:run :stop}: `:run` is the loop's future, which
  ends `:unauthorized` on a 401 and `:stopped` after `stop!`."
  ([state pull token] (start! state pull token {}))
  ([state pull token {:keys [sleep first-ms max-ms]
                      :or   {sleep    #(Thread/sleep (long %))
                             first-ms first-backoff-ms
                             max-ms   max-backoff-ms}}]
   (let [stop (atom false)
         c    (-> (HttpClient/newBuilder)
                  (.connectTimeout (Duration/ofSeconds 10))
                  (.build))
         run  (future
                (loop [backoff nil]
                  (if @stop
                    :stopped
                    (let [step (try (take-one! c state pull token)
                                    (catch Exception e
                                      (if @stop
                                        :stopped
                                        (do (log! "localfire pull poll failed: "
                                                  (.getSimpleName (class e)) " "
                                                  (ex-message e))
                                            :error))))]
                      (case step
                        :stopped      :stopped
                        :unauthorized (do (log! "localfire pull stopped: the engine answered 401,"
                                                " so the token in " (or (:token-from pull) (:token-env pull))
                                                " is wrong or revoked.")
                                          :unauthorized)
                        :error        (let [ms (next-backoff backoff first-ms max-ms)]
                                        (sleep ms)
                                        (recur ms))
                        (recur nil))))))]
     {:run run :stop stop})))

(defn stop!
  "Stop the loop. A run it started goes on, as a pushed one does."
  [{:keys [run stop]}]
  (reset! stop true)
  (future-cancel run))

;; ── a person's pull link (R-12.12) ──────────────────────────────────

(defn person-pull
  "The pull map and token the person's routine `nm` polls with, from the
  link they stored on /me, or nil when they stored none."
  [people engine-url nm]
  (when-let [{:keys [link-id token]} (people/routine-pull-link people nm)]
    {:pull  {:engine-url engine-url
             :link-id    link-id
             :routine    nm
             :token-from (str "the pull link stored for " nm)}
     :token token}))

(defn sync-person!
  "Stand the person's loop up again under the link they stored now, or
  leave it down when they removed it. A run a loop started goes on."
  [{:keys [state engine-url loops start opts]} nm]
  (locking loops
    (some-> (get @loops nm) stop!)
    (if-let [{:keys [pull token]} (person-pull (:people state) engine-url nm)]
      (swap! loops assoc nm (start state pull token opts))
      (swap! loops dissoc nm))))

(defn start-people!
  "One pull loop per person who stored a pull link (R-12.12), beside the
  server's `state`, polling `engine-url`. A link stored or removed on
  /me starts or stops its loop at once. A fire that finds the person's
  Claude token gone posts 401 (R-12.8). `:start` is the tests' seam on
  `start!`; the rest of `opts` goes to it. → the manager, for
  `stop-people!`."
  ([state engine-url] (start-people! state engine-url {}))
  ([state engine-url {:keys [start] :as opts}]
   (let [m {:state      state
            :engine-url engine-url
            :loops      (atom {})
            :start      (or start start!)
            :opts       (dissoc opts :start)}]
     (reset! (:on-pull (:people state)) #(sync-person! m %))
     (doseq [nm (people/pull-routines (:people state))]
       (sync-person! m nm))
     m)))

(defn stop-people! [{:keys [state loops]}]
  (reset! (:on-pull (:people state)) nil)
  (locking loops
    (doseq [l (vals @loops)] (stop! l))
    (reset! loops {})))
