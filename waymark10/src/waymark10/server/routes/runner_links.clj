(ns waymark10.server.routes.runner-links
  "The two doors a localfire stands on for a pull runner link (pull-mode
  epic 54360702, piece 1b): it asks for the link's fires, and it
  answers each one it ran.

      GET  /api/-/runner-links/{id}/fires
      POST /api/-/runner-links/{id}/fires/{fire}

  THE CREDENTIAL IS A HEADER, `Waymark-Runner-Token`, not a bearer:
  wrap-identity resolves every Authorization bearer as OIDC and refuses
  anything else before a handler runs, so the link's token rides its
  own header the way the seat and transcript keys ride theirs, and the
  require-auth gate (oidc-rp/wrap) lets these two paths through on it.
  The token is compared in constant time. A wrong or missing one, or a
  link nobody holds, is 401; the right token on a push link is 404.

  Nothing here logs: not the header, not the fire's text, which
  carries a one-time Key."
  (:require [clojure.string :as str]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.pull-fires :as pf]
            [waymark10.server.invoke :as inv]
            [waymark10.server.keys :as keys])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def token-header
  "The header a localfire carries its link's token in, as ring spells
  it (lower case)."
  "waymark-runner-token")

(def ^:dynamic *wait-ms*
  "How long a poll waits for a fire before it answers 204."
  25000)

(def ^:dynamic *step-ms*
  "How often a waiting poll looks again."
  1000)

(defn- now-of ^Instant [eng]
  (let [t ((:now-fn eng))]
    (if (instance? Instant t) t (Instant/now))))

(defn- link-row [eng id]
  (when (and id (contains? (inv/resources eng) :runner_link))
    (store/with-tx (:storage eng)
      (fn [tx] (store/load-row (:storage eng) tx :runner_link (str id) {})))))

(defn- pull-link!
  "The pull link `id` the request's token opens, or a problem: 401 for
  a wrong or missing token or no such link, 404 for a push link."
  [eng req id]
  (let [row (link-row eng id)]
    (when-not (and row (keys/equal? (get-in row [:data :fire_token])
                                    (get-in req [:headers token-header])))
      (throw (p/problem :unauthenticated 401 "Unauthenticated"
                        {:detail "The Waymark-Runner-Token header does not open this runner link."})))
    (when-not (rl/pull? row)
      (throw (p/problem :not-found 404 "Not found"
                        {:detail "This runner link is not a pull link."})))
    row))

(defn- fires-get
  "GET /api/-/runner-links/{id}/fires — the oldest fire nobody holds,
  leased to this localfire, or 204 when none comes within `*wait-ms*`.
  Every poll first stamps the link's `puller_seen_at`, at most once in
  `rl/seen-every` seconds (LP2): it is what keeps the link out of
  waiting."
  [eng]
  (fn [{{:keys [id]} :path-params :as req}]
    (pull-link! eng req id)
    (rl/seen! eng id (now-of eng))
    (let [deadline (+ (System/currentTimeMillis) (long *wait-ms*))]
      (loop []
        (if-some [f (rl/take-fire! eng id)]
          (router/json-response 200 {:id (:id f) :text (:text f)})
          (let [left (- deadline (System/currentTimeMillis))]
            (if (pos? left)
              (do (Thread/sleep (long (min left (long *step-ms*))))
                  (recur))
              {:status 204 :headers {}})))))))

(def not-started-reason-cap
  "The most characters a not-started answer's `reason` may carry."
  500)

(defn answer-of
  "The posted body as the Provider's four words: the push wire's 200
  body (`claude_code_session_id`, `claude_code_session_url`) is a
  started run; `{status, retry_after}` is a 429 throttle, a 400 paused
  Routine (transient) or a 401 bad link. `{started: false, reason}`,
  with or without a status, is the engine's own fifth word: the
  localfire cannot say the run started and will not start it twice
  (ticket 734b3ecd), so the fire closes as not started and nothing is
  paused or throttled. nil for anything else, a not-started body with
  no reason or a reason over `not-started-reason-cap` too."
  [body]
  (let [status (:status body)
        retry (some-> (:retry_after body) str not-empty)
        reason (:reason body)]
    (cond
      (false? (:started body))
      (when (and (string? reason) (not (str/blank? reason))
                 (<= (count reason) (long not-started-reason-cap)))
        {:not-started reason})

      (and (nil? status)
           (or (contains? body :claude_code_session_id)
               (contains? body :claude_code_session_url)))
      {:started (some-> (:claude_code_session_url body) str not-empty)
       :session-id (some-> (:claude_code_session_id body) str not-empty)}

      (= 429 status) {:throttled retry :status 429}
      (= 400 status) {:transient (sch/provider-note 400 nil) :status 400}
      (= 401 status) {:bad-link (sch/provider-note 401 nil) :status 401}
      :else nil)))

(defn- fire-post
  "POST /api/-/runner-links/{id}/fires/{fire} — what the localfire's
  run answered, landed through `rl/answer-fire!`. 404 when this link
  holds no such fire."
  [eng]
  (fn [{{:keys [id fire]} :path-params :as req}]
    (pull-link! eng req id)
    (let [answer (answer-of (router/read-body req))]
      (when-not answer
        (throw (p/problem :invalid 422 "Invalid body"
                          {:detail "Send the run's claude_code_session_id and claude_code_session_url, {\"status\": 401|400|429, \"retry_after\": n}, or {\"started\": false, \"reason\": \"...\"} with a reason of at most 500 characters."})))
      (when-not (some #(= (str fire) (:id %)) (pf/pending (:storage eng) (str id)))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "This runner link holds no such fire."})))
      (rl/answer-fire! eng fire answer)
      (router/json-response 200 {:id (str fire) :landed true}))))

(defn static
  "The two doors, for a module's `:static` bucket."
  [eng]
  [["/api/-/runner-links/:id/fires" {:get (fires-get eng)}]
   ["/api/-/runner-links/:id/fires/:fire" {:post (fire-post eng)}]])
