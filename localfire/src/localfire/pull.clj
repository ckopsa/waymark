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
                                                " so the token in " (:token-env pull)
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
