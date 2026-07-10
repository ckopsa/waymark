(ns waymark10.server.router
  "reitit-ring routes + middleware: the HTTP boundary. Handlers speak
  invoke!/create!/render; every refusal is a tagged ex-info the
  problem boundary projects to RFC 9457. Routes conflict by design
  (.well-known and schemas share the {plural}/{id} shape), so the
  router is linear with static routes first."
  (:require [clojure.string :as str]
            [reitit.ring :as ring]
            [waymark10.schema :as schema]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(def media-type "application/waymark+json")

;; ── request parsing ─────────────────────────────────────────────────

(defn- read-body
  "Parsed JSON body (keyword keys); empty/absent body → nil; broken
  JSON → 400 problem."
  [req]
  (let [b (:body req)
        s (cond (nil? b) nil
                (string? b) b
                :else (slurp b))]
    (when-not (or (nil? s) (str/blank? s))
      (try (wire/read-json s)
           (catch Exception e
             (throw (p/malformed-body (ex-message e))))))))

(defn- csv [s]
  (when s
    (into [] (comp (map str/trim) (remove str/blank?)) (str/split s #","))))

(defn- principal-of [headers]
  (if-some [id (get headers "x-waymark-principal")]
    (t/principal {:id id
                  :roles (set (csv (get headers "x-waymark-roles")))
                  :type (let [at (some-> (get headers "x-waymark-actor-type")
                                         str/trim str/lower-case keyword)]
                          (if (contains? t/actor-types at) at :human))})
    t/anonymous))

(defn- query-params [req]
  (into {}
        (keep (fn [kv]
                (let [[k v] (str/split kv #"=" 2)]
                  (when-not (str/blank? k) [k (or v "")]))))
        (some-> (:query-string req) (str/split #"&"))))

(defn- invoke-opts [req]
  (let [headers (:headers req)]
    {:principal (principal-of headers)
     :if-match (get headers "if-match")
     :idempotency-key (get headers "idempotency-key")
     :acknowledged (into #{} (map keyword) (csv (get headers "waymark-acknowledge")))
     :dry-run (= "1" (get (query-params req) "dry_run"))}))

;; ── responses ───────────────────────────────────────────────────────

(defn- json-response
  ([status body] (json-response status body "application/json" nil))
  ([status body ctype extra-headers]
   {:status status
    :headers (merge {"Content-Type" ctype} extra-headers)
    :body (wire/write-json body)}))

(defn- envelope-response [eng rdef row principal status extra-headers]
  (let [env (render/envelope rdef row {:principal principal
                                       :now ((:now-fn eng))
                                       :services (:services eng)})]
    (json-response status env media-type
                   (merge {"ETag" (get-in env ["meta" "etag"])} extra-headers))))

;; ── lookups ─────────────────────────────────────────────────────────

(defn- rdef-by-plural [eng plural]
  (or (some (fn [[_ r]] (when (= plural (:plural r)) r)) (inv/resources eng))
      (throw (p/not-found "collection" plural))))

(defn- decode-row [rdef row]
  (update row :data #(schema/decode (:schema rdef) %)))

(defn- load-decoded [eng rdef id]
  (let [st (:storage eng)
        raw (store/with-tx st #(store/load-row st % (:kind rdef) id {}))]
    (when-not raw (throw (p/not-found (:kind rdef) id)))
    (decode-row rdef raw)))

;; ── handlers ────────────────────────────────────────────────────────

(defn- well-known [eng]
  (fn [_req]
    (json-response
     200
     {:waymark "10"
      :kinds (vec (sort (map name (keys (inv/resources eng)))))
      :resources (into (sorted-map)
                       (map (fn [[k r]] [(name k) {:href (str "/api/" (:plural r))}]))
                       (inv/resources eng))})))

(defn- kind-schema [eng]
  (fn [{{:keys [kind]} :path-params}]
    (let [rdef (or (get (inv/resources eng) (keyword kind))
                   (throw (p/not-found "kind" kind)))]
      (json-response 200 (p/wire-value (schema/json-schema (:schema rdef)))))))

(defn- collection [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          st (:storage eng)
          rows (store/with-tx st #(store/query-rows st % (:kind rdef) {} {:limit 100}))
          ctx-opts {:principal (principal-of (:headers req))
                    :now ((:now-fn eng))
                    :services (:services eng)}
          items (mapv #(render/envelope-summary rdef (decode-row rdef %) ctx-opts)
                      rows)]
      (json-response 200
                     {:waymark "10"
                      :kind (str (name (:kind rdef)) "_collection")
                      :self (str "/api/" plural)
                      :data {:items items :total (count rows)}}
                     media-type nil))))

(defn- create [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          opts (invoke-opts req)
          {:keys [row]} (inv/create! eng (:kind rdef) (read-body req)
                                     (select-keys opts [:principal :acknowledged]))]
      (envelope-response eng rdef row (:principal opts) 201
                         {"Location" (str "/api/" plural "/" (:id row))}))))

(defn- get-one [eng]
  (fn [{{:keys [plural id]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          row (load-decoded eng rdef id)]
      (envelope-response eng rdef row (principal-of (:headers req)) 200 nil))))

(defn- invoke-action [eng]
  (fn [{{:keys [plural id action]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          opts (invoke-opts req)
          result (inv/invoke! eng (:kind rdef) id (keyword action)
                              (read-body req) opts)]
      (cond
        ;; stored replay: the first execution's bytes, verbatim
        (= :idempotency (:replayed? result))
        (let [hit (:response result)]
          {:status (:status hit)
           :headers {"Content-Type" (:media-type hit)}
           :body (:response hit)})

        (:valid? result)
        (json-response 200
                       (p/wire-value
                        (cond-> {:valid true}
                          (:warnings result)
                          (assoc :warnings (mapv p/prune (:warnings result)))))
                       media-type nil)

        :else
        (envelope-response eng rdef (:row result) (:principal opts) 200 nil)))))

;; ── events (SSE, phase 6) ───────────────────────────────────────────

(defn- events-dispatcher
  "The engine's running dispatcher — 503 on an engine that never
  started (documented pick over lazy-start: the operator owns the
  lifecycle; a test handler pays nothing)."
  [eng]
  (or (some-> (:runtime eng) deref :dispatcher)
      (throw (p/problem :events-unavailable 503 "Event stream unavailable"
                        {:detail (str "This engine is not started; the events "
                                      "dispatcher is not running.")}))))

(defn- last-event-id
  "SSE resume point: the Last-Event-ID header, or ?last_event_id=."
  [req]
  (some-> (or (get-in req [:headers "last-event-id"])
              (get (query-params req) "last_event_id"))
          str/trim
          parse-long))

(defn- resource-events [eng]
  (fn [{{:keys [plural id]} :path-params :as req}]
    (let [d (events-dispatcher eng)
          rdef (rdef-by-plural eng plural)]
      (events/sse-handler eng d {:resource [(:kind rdef) id]
                                 :since (last-event-id req)}
                          req))))

(defn- firehose-events [eng]
  (fn [req]
    (let [d (events-dispatcher eng)
          kinds (some->> (get (query-params req) "kinds") csv
                         (map keyword) set not-empty)]
      (events/sse-handler eng d {:kinds kinds
                                 :since (last-event-id req)}
                          req))))

;; ── the handler ─────────────────────────────────────────────────────

(defn- wrap-problems
  "The refusal boundary: tagged problems project to problem+json,
  storage version conflicts become 412, anything else is a 500 with
  the stack on *err*."
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (let [d (ex-data e)]
          (cond
            (p/problem? e) (p/->response e)

            (:waymark10/version-conflict d)
            (p/->response (p/version-conflict nil (select-keys d [:kind :id])))

            :else
            (do (binding [*out* *err*]
                  (println "waymark10 internal error:" (ex-message e))
                  (.printStackTrace e ^java.io.PrintWriter *err*))
                {:status 500
                 :headers {"Content-Type" "application/problem+json"}
                 :body (wire/write-json {:type "about:blank"
                                         :title "Internal error"
                                         :status 500})})))))))

(defn- not-found-handler [_req]
  (p/->response (p/problem :not-found 404 "Not found"
                           {:detail "No such route."})))

(defn handler
  "The ring handler: linear router (static routes shadow the plural
  grammar), problem boundary outermost."
  [eng]
  (-> (ring/ring-handler
       (ring/router
        [["/api/.well-known/waymark" {:get (well-known eng)}]
         ["/api/schemas/:kind" {:get (kind-schema eng)}]
         ["/api/-/events" {:get (firehose-events eng)}]
         ["/api/:plural" {:get (collection eng) :post (create eng)}]
         ["/api/:plural/:id" {:get (get-one eng)}]
         ["/api/:plural/:id/-/events" {:get (resource-events eng)}]
         ["/api/:plural/:id/-/:action" {:post (invoke-action eng)}]]
        {:conflicts nil})
       not-found-handler)
      wrap-problems))
