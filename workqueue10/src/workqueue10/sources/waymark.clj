(ns workqueue10.sources.waymark
  "The generic TaskSource over ANOTHER WAYMARK ENGINE — the cheapest
  boundary in the family (the MealplanFeed precedent): discover is a
  filtered collection GET whose query names the work worth queueing,
  pull is a row GET whose envelope already carries the engine's own
  etag (the authority mints real versions), and push rides the same
  If-Match fence every waymark write rides.

  What varies per authority is DATA, not code: the kind's collection
  path, the discover query, and row->task — the envelope→canonical
  translation (workqueue10.sources.choreplan / .mealplan supply
  them). The push translation is the confluence's shared push-plan:
  the source GETs its row, translates the state through the same
  row->task, and makes the one move the plan names — POST
  {row}/-/complete under If-Match, :noop with the fresh etag, or the
  throw that lands the queue row conflicted."
  (:require [clojure.string :as str]
            [workqueue10.confluence :as conf]
            [waymark10.wire :as wire])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defn- enc [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- self->id [self]
  (last (str/split (str self) #"/")))

(defn- request ^HttpRequest [{:keys [base headers]} method path extra]
  (let [b (-> (HttpRequest/newBuilder (URI/create (str base path)))
              (.timeout (Duration/ofSeconds 20)))]
    (doseq [[^String k ^String v] (merge headers extra)]
      (.header b k v))
    (.build (case method
              :get (.GET b)
              :post (.POST b (HttpRequest$BodyPublishers/noBody))))))

(defn- send!
  "One request → {:etag … :env …}; non-2xx throws with the status in
  ex-data (pull-many reads it to tell a gone row from a down feed);
  a connection failure throws raw — unreachable, as the protocol
  asks."
  [{:keys [^HttpClient client] :as src} method path & [extra-headers]]
  (let [resp (.send client (request src method path extra-headers)
                    (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        env (some-> ^String (.body resp) not-empty wire/read-json)]
    (when (>= status 400)
      (throw (ex-info (str "the source answered " status " for "
                           (name method) " " path)
                      {:status status :problem env})))
    {:etag (.orElse (.firstValue (.headers resp) "etag") nil)
     :env env}))

(def ^:private page-size 100)

(def translation-rev
  "The adapter's translation version, folded into every etag this
  source REPORTS (never the raw If-Match it presents upstream). The
  mirror's etag guard skips re-applying an unchanged document — but
  the document is upstream row × OUR translation, and the authority's
  etag can't see our code. Bump this when any row->task / with-origin
  output changes shape, and every stored row re-observes on its next
  pull instead of serving the old translation forever."
  "t2")

(defn- rev-etag [etag]
  (when etag (str etag "|" translation-rev)))

(defn with-origin
  "The canonical doc + where it came from: :source_href (the row's
  API envelope — a client's route) and :source_ui_href (the source
  engine's ui.html anchored on that row — the URL hash IS the
  resource href, so a person's tap lands in context). The client
  holds the base URL and the envelope holds :self; the translators
  stay pure."
  [base self task]
  (assoc task
         :source_href (str base self)
         :source_ui_href (str base "/api/-/ui#" self)))

(defrecord WaymarkSource [^HttpClient client base kind-path discover-query
                          row->task headers]
  conf/TaskSource
  (source-discover [this]
    (loop [n 1 acc []]
      (let [{:keys [env]} (send! this :get
                                 (str "/api/" kind-path "?" discover-query
                                      "&page%5Bsize%5D=" page-size
                                      "&page%5Bnumber%5D=" n))
            items (get-in env [:data :items])
            acc (into acc (map (comp self->id :self)) items)]
        (if (< (count items) page-size) acc (recur (inc n) acc)))))
  (source-pull [this id]
    (let [{:keys [env etag]} (send! this :get
                                    (str "/api/" kind-path "/" (enc id)))]
      [(with-origin base (:self env) (row->task env))
       (rev-etag (or etag (get-in env [:meta :etag])))]))
  (source-pull-many [this ids]
    (into {}
          (keep (fn [id]
                  (try [(str id) (conf/source-pull this id)]
                       (catch clojure.lang.ExceptionInfo e
                         ;; a gone row drops from the batch (the feed
                         ;; no longer carries it); anything else is
                         ;; the boundary's problem — rethrow
                         (when-not (= 404 (:status (ex-data e)))
                           (throw e))
                         nil))))
          ids))
  (source-push [this id document]
    ;; If-Match presents the RAW upstream etag (the fence is the
    ;; authority's); only what we report back to the mirror rides the
    ;; translation revision
    (let [{:keys [env etag]} (send! this :get
                                    (str "/api/" kind-path "/" (enc id)))
          etag (or etag (get-in env [:meta :etag]))]
      (case (conf/push-plan document (:status (row->task env)))
        :noop (rev-etag etag)
        :complete
        (let [{:keys [env etag]}
              (send! this :post
                     (str "/api/" kind-path "/" (enc id) "/-/complete")
                     {"if-match" etag})]
          (rev-etag (or etag (get-in env [:meta :etag]))))))))

(defn http-source
  "The real boundary over a running waymark engine.

  config: :url (the engine root), :kind-path (the collection segment,
  e.g. \"chore_runs\"), :discover-query (the filter naming the work
  worth queueing, e.g. \"state=due\"), :row->task (envelope →
  canonical doc), :principal (the x-waymark-principal the pushes act
  as — default \"workqueue10\"), :token (optional bearer)."
  [{:keys [url kind-path discover-query row->task principal token]}]
  (->WaymarkSource
   (-> (HttpClient/newBuilder)
       (.connectTimeout (Duration/ofSeconds 10))
       (.build))
   (str/replace (str url) #"/+$" "")
   kind-path
   discover-query
   row->task
   (cond-> {"x-waymark-principal" (or principal "workqueue10")
            "accept" "application/json"}
     token (assoc "authorization" (str "Bearer " token)))))
