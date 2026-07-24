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
            [waymark10.server.engine :as engine]
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

(defn- parse-body
  "The response body → its envelope, defensively: on a refusal status
  the body may be a proxy's PLAIN-TEXT error page ('Bad Gateway'), and
  a parse crash there would erase the status the caller needs
  (waymark-t6s — the raw exception killed the resync loop). A 2xx
  that will not parse still throws: a healthy engine always speaks
  JSON, so garbage is unreachable, never an empty feed."
  [status ^String body]
  (try (some-> body not-empty wire/read-json)
       (catch Exception e
         (when (< status 400)
           (throw (ex-info (str "the source answered " status
                                " with an unreadable body")
                           {} e)))
         nil)))

(defn- http-transport
  "The over-the-wire transport: (fn [method path extra]) →
  {:status :etag :env}. The bearer is asked for PER REQUEST — a
  token-fn that refreshes (oidc/client-credentials-fn) never goes
  stale in a header map. A connection failure throws raw —
  unreachable, as the protocol asks."
  [{:keys [url headers token-fn]}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))
        base (str/replace (str url) #"/+$" "")]
    (fn [method path extra]
      (let [b (-> (HttpRequest/newBuilder (URI/create (str base path)))
                  (.timeout (Duration/ofSeconds 20)))
            _ (doseq [[^String k ^String v]
                      (merge headers
                             (when token-fn
                               {"authorization" (str "Bearer " (token-fn))})
                             extra)]
                (.header b k v))
            req (.build (case method
                          :get (.GET b)
                          :post (.POST b (HttpRequest$BodyPublishers/noBody))))
            resp (.send client req (HttpResponse$BodyHandlers/ofString))
            status (.statusCode resp)]
        {:status status
         :etag (.orElse (.firstValue (.headers resp) "etag") nil)
         :env (parse-body status (.body resp))}))))

(defn- engine-transport
  "The in-process transport: the SAME envelope semantics, served by
  the engine's own ring handler — no socket, no bearer. Identity is
  the dev-header principal, legitimate again because the request
  never crosses the network edge (the require-auth gate lives in the
  http server's wrap, not in the handler). :engine-ref is an IDeref
  delivering the engine after boot; a call before delivery throws —
  unreachable, same as a source that is not up yet."
  [{:keys [engine-ref principal]}]
  (let [handler (delay (engine/handler @engine-ref))]
    (fn [method path extra]
      (when (nil? @engine-ref)
        (throw (ex-info "the engine is not booted yet" {})))
      (let [[uri q] (str/split (str path) #"\?" 2)
            resp (@handler
                  {:request-method method :uri uri :query-string q
                   :headers (merge {"x-waymark-principal"
                                    (or principal "workqueue10")
                                    "accept" "application/json"}
                                   extra)})
            status (:status resp)]
        {:status status
         :etag (or (get-in resp [:headers "ETag"])
                   (get-in resp [:headers "etag"]))
         :env (parse-body status (str (:body resp)))}))))

(defn- send!
  "One request through the transport → {:etag … :env …}; non-2xx
  throws with the status in ex-data (pull-many reads it to tell a
  gone row from a down feed)."
  [{:keys [transport]} method path & [extra-headers]]
  (let [{:keys [status etag env]} (transport method path extra-headers)]
    (when (>= status 400)
      (throw (ex-info (str "the source answered " status " for "
                           (name method) " " path)
                      {:status status :problem env})))
    {:etag etag :env env}))

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

(defrecord WaymarkSource [transport base kind-path discover-query
                          row->task]
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
          (map (fn [id]
                 (try [(str id) (conf/source-pull this id)]
                      (catch clojure.lang.ExceptionInfo e
                        ;; a 404 from an engine that ANSWERED is a
                        ;; gone row — the honest sentinel; anything
                        ;; else is the boundary's problem — rethrow
                        (if (= 404 (:status (ex-data e)))
                          [(str id) :gone]
                          (throw e))))))
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
          (rev-etag (or etag (get-in env [:meta :etag])))))))
  (source-create [_ _document]
    ;; chore runs and prep tasks are born of their own engines' law
    ;; (a chore's cadence, a plan's finalize) — the queue captures
    ;; nothing into them
    (throw (ex-info (str kind-path " accepts no births from the queue — "
                         "its own engine owns the making of its rows")
                    {}))))

(defn http-source
  "The real boundary over a running waymark engine.

  config: :url (the engine root), :kind-path (the collection segment,
  e.g. \"chore_runs\"), :discover-query (the filter naming the work
  worth queueing, e.g. \"state=due\"), :row->task (envelope →
  canonical doc), :principal (the x-waymark-principal the pushes act
  as — default \"workqueue10\"), :token (a STATIC bearer — tests and
  operator overrides; wins over :token-fn), :token-fn (a zero-arg
  refreshing bearer source, oidc/outbound-token-fn — production's
  spelling, waymark-mvl)."
  [{:keys [url kind-path discover-query row->task principal token
           token-fn]}]
  (->WaymarkSource
   (http-transport
    {:url url
     :headers {"x-waymark-principal" (or principal "workqueue10")
               "accept" "application/json"}
     :token-fn (or (some-> (not-empty (str (or token ""))) constantly)
                   token-fn)})
   (str/replace (str url) #"/+$" "")
   kind-path
   discover-query
   row->task))

(defn engine-source
  "The stage-1 fold's boundary (waymark-bwu.1): the source's kind
  lives in THIS engine, so the confluence drinks in-process — same
  discover/pull/push law, no HTTP, no bearer.

  config: :engine-ref (an IDeref delivering the engine after boot),
  :ui-base (the browser-facing base origin links anchor on — the
  app's own external URL), :kind-path :discover-query :row->task
  :principal as http-source."
  [{:keys [engine-ref ui-base kind-path discover-query row->task
           principal]}]
  (->WaymarkSource
   (engine-transport {:engine-ref engine-ref :principal principal})
   (str/replace (str ui-base) #"/+$" "")
   kind-path
   discover-query
   row->task))
