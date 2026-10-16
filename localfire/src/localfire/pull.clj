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

  The token rides its own header and nowhere else. Nothing here logs
  it, nor the fire's text, which carries a one-time Key."
  (:require [jsonista.core :as json]
            [localfire.people :as people]
            [localfire.server :as server])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
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

(defn- take-one!
  "One poll and, when it brings a fire, the run and the answer. →
  :fire, :empty, :unauthorized or :error."
  [c state pull token]
  (let [resp (send! c token (fires-url pull) nil)
        st   (.statusCode resp)]
    (cond
      (= 204 st) :empty
      (= 401 st) :unauthorized
      (not= 200 st) (do (log! "localfire pull poll answered " st) :error)
      :else
      (let [fire (json/read-value (str (.body resp)) json/keyword-keys-object-mapper)
            fid  (str (:id fire))
            text (some-> (:text fire) str not-empty)
            ans  (server/fire! state (:routine pull) (constantly text))
            back (.statusCode (send! c token (str (fires-url pull) "/" (enc fid))
                                     (answer-body ans)))]
        (log! "localfire pull fire=" fid " routine=" (:routine pull)
              " status=" (:status ans) " answer=" back)
        (if (= 401 back) :unauthorized :fire)))))

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
