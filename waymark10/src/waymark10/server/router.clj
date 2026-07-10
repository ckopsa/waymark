(ns waymark10.server.router
  "reitit-ring routes + middleware: the HTTP boundary. Handlers speak
  invoke!/create!/render; every refusal is a tagged ex-info the
  problem boundary projects to RFC 9457. Routes conflict by design
  (.well-known and schemas share the {plural}/{id} shape), so the
  router is linear with static routes first.

  Phase 7 widens the surface: GET {plural} is the real filtered/
  sorted/paged collection (waymark10.server.collections), POST
  {plural}/-/{action} is bulk, POST …/{action}/batch is batch, and
  …/{action}/draft carries the draft sub-resource (GET/PUT/DELETE).
  Query params URL-decode at parse, so the collection's own
  page[…]-carrying self hrefs round-trip.

  Phase 9a adds the IDENTITY boundary (wrap-identity, inside the
  problem boundary): the request's principal — the OIDC bearer
  resolver when the engine configures :oidc, the dev headers
  otherwise — and the grant visibility (X-Waymark-Grant) resolve ONCE
  and ride the request; the members suspension gate refuses a
  suspended member's every request 403 before any handler runs
  (authentication-adjacent gating — guards stay the only
  authorization concept, see waymark10.server.members). A scoped
  request sees only its granted surface: non-granted kinds and rows
  404, non-granted actions 404 (concealment). Recorded punt: the SSE
  routes are not projected — a scoped request gets 404 on them.
  Attachment bytes ride PUT/GET /api/attachments/{id}/bytes (static
  route, shadowing the plural grammar by position).

  Phase 9b: /api/openapi.json (the derived overlay) and
  /api/surfaces/{name}/{anchor-id} (the composed decision screen)
  join the static routes; a deferred bulk call (over its :defer-over
  threshold) mints a job and answers 202 with the job envelope and
  its Location; …/{action}/draft/collab upgrades to the live-collab
  websocket. Recorded: like SSE, the openapi/surface/collab routes
  answer a grant-scoped request 404 — projecting them is a punt.

  Batch A closes the depth/links punts:
  - GET {plural}/{id}?depth=summary serves the envelope minus
    data/parts (depth=full is the default; any other value is one
    422 naming the parameter).
  - GET {plural}?rows=none makes items the cheap stub (actions AND
    unavailable null — explicitly unknown). rows is stripped BEFORE
    the collection grammar parses, so page hrefs do not carry it
    (recorded: a pager that wants to stay cheap re-appends it).
  - a declared link with :embed true gains \"embedded\" on the full
    envelope only: the target collection, filtered by the link's own
    compiled href (the href and the inline items can never disagree),
    as envelope-minus-data items capped at EMBED-CAP (5, recorded).
    Loading happens HERE — render stays storage-free; a failed embed
    drops with a *err* warning, never the GET.
  - render ctx-opts gain :resources (the engine's kind map) so link
    targets resolve their declared plurals."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [waymark10.schema :as schema]
            [waymark10.server.attachments :as attachments]
            [waymark10.server.collab :as collab]
            [waymark10.server.collections :as collections]
            [waymark10.server.drafts :as drafts]
            [waymark10.server.events :as events]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.jobs :as jobs]
            [waymark10.server.members :as members]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.openapi :as openapi]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.surface :as surface]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

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

(defn- dev-principal [headers]
  (if-some [id (get headers "x-waymark-principal")]
    (t/principal {:id id
                  :roles (set (csv (get headers "x-waymark-roles")))
                  :type (let [at (some-> (get headers "x-waymark-actor-type")
                                         str/trim str/lower-case keyword)]
                          (if (contains? t/actor-types at) at :human))})
    t/anonymous))

(defn- principal-of
  "The request's principal, resolved once by wrap-identity; the dev
  headers remain the fallback for a bare handler in tests."
  [req]
  (or (:waymark10/principal req) (dev-principal (:headers req))))

(defn- visibility-of [req] (:waymark10/visibility req))

;; ── the visibility checks (phase 9a, concealment) ───────────────────

(defn- check-kind!
  "A scoped request addressing a non-granted kind: the collection does
  not exist."
  [req rdef]
  (when-some [vis (visibility-of req)]
    (when-not ((:kind? vis) (:kind rdef))
      (throw (p/not-found "collection" (:plural rdef))))))

(defn- check-row!
  "A scoped request addressing a non-granted kind or un-granted id:
  the row does not exist."
  [req rdef id]
  (when-some [vis (visibility-of req)]
    (when-not ((:row? vis) (:kind rdef) id)
      (throw (p/not-found (:kind rdef) id)))))

(defn- check-action!
  "A scoped request invoking a non-granted action: the action does not
  exist (never a 403 — a refusal that names the gate would leak what
  concealment hides)."
  [req rdef action]
  (when-some [vis (visibility-of req)]
    (when-not ((:action? vis) (:kind rdef) action)
      (throw (p/no-such-action (:kind rdef) action)))))

(defn- url-decode ^String [^String s]
  (URLDecoder/decode s StandardCharsets/UTF_8))

(defn- query-params [req]
  (into {}
        (keep (fn [kv]
                (let [[k v] (str/split kv #"=" 2)]
                  (when-not (str/blank? k)
                    [(url-decode k) (url-decode (or v ""))]))))
        (some-> (:query-string req) (str/split #"&"))))

(defn- invoke-opts [req]
  (let [headers (:headers req)]
    {:principal (principal-of req)
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

(defn- render-opts
  "The one ctx-opts map every render call shares: identity, clock,
  services, visibility, and the kind map link targets resolve
  through."
  [eng req]
  {:principal (principal-of req)
   :now ((:now-fn eng))
   :services (:services eng)
   :visibility (visibility-of req)
   :resources (inv/resources eng)})

(defn- envelope-response [eng rdef row req status extra-headers]
  (let [env (render/envelope rdef row (render-opts eng req))]
    (json-response status env media-type
                   (merge {"ETag" (get-in env ["meta" "etag"])} extra-headers))))

;; ── lookups ─────────────────────────────────────────────────────────

(defn- rdef-by-plural [eng plural]
  (or (some (fn [[_ r]] (when (= plural (:plural r)) r)) (inv/resources eng))
      (throw (p/not-found "collection" plural))))

(defn- load-decoded [eng rdef id]
  (let [st (:storage eng)
        raw (store/with-tx st #(store/load-row st % (:kind rdef) id {}))]
    (when-not raw (throw (p/not-found (:kind rdef) id)))
    ;; inv/decode-row: coercion AND the shape fold (phase 8 upcasts)
    (inv/decode-row rdef raw)))

;; ── handlers ────────────────────────────────────────────────────────

(defn- well-known [eng]
  (fn [req]
    (let [vis (visibility-of req)
          resources (cond->> (inv/resources eng)
                      vis (filter (fn [[k _]] ((:kind? vis) k))))]
      (json-response
       200
       (cond-> {:waymark "10"
                :kinds (vec (sort (map (comp name key) resources)))
                :resources (into (sorted-map)
                                 (map (fn [[k r]] [(name k) {:href (str "/api/" (:plural r))}]))
                                 resources)}
         ;; the declared surfaces (phase 9b) — hidden from a scoped
         ;; request, whose surface routes 404 anyway
         (and (seq (:surfaces eng)) (nil? vis))
         (assoc :surfaces (surface/well-known-entry (:surfaces eng))))))))

(defn- kind-schema [eng]
  (fn [{{:keys [kind]} :path-params :as req}]
    (let [rdef (or (get (inv/resources eng) (keyword kind))
                   (throw (p/not-found "kind" kind)))]
      (when-some [vis (visibility-of req)]
        (when-not ((:kind? vis) (:kind rdef))
          (throw (p/not-found "kind" kind))))
      ;; batch B (flagged router seam): the published schema view
      ;; projects per grant field modes — a redacted field is not in
      ;; the schema; grants.clj owns the logic, nil vis is untouched
      (json-response 200 (p/wire-value
                          (grants/project-json-schema
                           (visibility-of req) (:kind rdef)
                           (schema/json-schema (:schema rdef))))))))

(defn- rows-of
  "The collection's rows= parameter: absent → full item summaries;
  \"none\" → the cheap stub (null actions). Anything else is a 422."
  [params]
  (when-some [r (get params "rows")]
    (if (= "none" r)
      :none
      (throw (p/schema-invalid :query {"rows" ["must be \"none\""]})))))

(defn- collection [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-kind! req rdef)
          params (query-params req)
          rows (rows-of params)
          env (collections/envelope eng rdef (dissoc params "rows")
                                    (cond-> (render-opts eng req)
                                      rows (assoc :rows rows)))]
      (json-response 200 env media-type nil))))

(defn- create [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-kind! req rdef)
          _ (check-action! req rdef (first (:create-action-names rdef)))
          opts (invoke-opts req)
          result (inv/create! eng (:kind rdef) (read-body req)
                              (select-keys opts [:principal :acknowledged
                                                 :idempotency-key]))]
      (if (= :idempotency (:replayed? result))
        ;; the first execution's bytes, verbatim (phase 10: creates
        ;; honor a present key; the Location header is not stored —
        ;; the body's self carries the same href)
        (let [hit (:response result)]
          {:status (:status hit)
           :headers {"Content-Type" (:media-type hit)}
           :body (:response hit)})
        (let [row (:row result)]
          (envelope-response eng rdef row req 201
                             {"Location" (str "/api/" plural "/" (:id row))}))))))

(defn- depth-of
  "The GET's depth= parameter: absent/\"full\" → the whole envelope;
  \"summary\" → envelope minus data/parts. Anything else is a 422."
  [req]
  (let [d (get (query-params req) "depth")]
    (cond
      (or (nil? d) (= "full" d)) :full
      (= "summary" d) :summary
      :else (throw (p/schema-invalid
                    :query {"depth" ["must be \"summary\" or \"full\""]})))))

(def embed-cap
  "How many target items an :embed true link inlines (recorded cap:
  embedding is an invitation to co-present, not a bulk export — the
  href is the whole answer)."
  5)

(defn- splice-embeds
  "The :embed true links of one FULL wired envelope gain \"embedded\":
  the target collection filtered by the link's own compiled href —
  parsed back through the collection grammar, so the href and the
  inline items can never disagree — as envelope-minus-data items,
  capped. Best-effort: a failed embed drops with a *err* warning,
  never the GET. Template (:href) links have no target rdef to load
  from and never embed."
  [eng rdef env ctx-opts]
  (reduce
   (fn [env ld]
     (let [rel (name (:rel ld))
           link (get-in env ["links" rel])
           target-kind (or (when-some [e (:edge ld)]
                             (get-in rdef [:related e :kind]))
                           (:owns ld))
           trdef (when target-kind (get (inv/resources eng) target-kind))]
       (if-not (and (:embed ld) link trdef)
         env
         (try
           (let [q (second (str/split (str (get link "href")) #"\?" 2))
                 params (into {}
                              (keep (fn [kv]
                                      (let [[k v] (str/split kv #"=" 2)]
                                        (when-not (str/blank? k)
                                          [(url-decode k) (url-decode (or v ""))]))))
                              (when q (str/split q #"&")))
                 {:keys [conds sort]} (collections/parse-query trdef params)
                 st (:storage eng)
                 rows (store/with-tx st
                        (fn [tx]
                          (store/search-rows st tx (:kind trdef) conds
                                             {:order-by (:field sort)
                                              :desc (:desc sort)
                                              :limit embed-cap
                                              :offset 0})))
                 items (mapv #(render/envelope-summary
                               trdef (inv/decode-row trdef %) ctx-opts)
                             rows)]
             (assoc-in env ["links" rel "embedded"] items))
           (catch Exception e
             (binding [*out* *err*]
               (println "waymark10 embed failed for link" rel "-"
                        (ex-message e)))
             env)))))
   env
   (filter :embed (:links rdef))))

(defn- get-one [eng]
  (fn [{{:keys [plural id]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-row! req rdef id)
          depth (depth-of req)
          row (load-decoded eng rdef id)
          ;; the mirror's pull-through seam (phase 8): a stale mirrored
          ;; row refreshes from its adapter on read, system actor.
          ;; :suppress-mirror-refresh is the walker-scoped conformance
          ;; seam (waymark9's _suppress_mirror_refresh): a Mirror breaks
          ;; the walker's reads-are-pure assumption, so ONLY a
          ;; conformance fixture sets it — production reads pull through
          row (if (and (:mirror rdef) (not (:suppress-mirror-refresh eng)))
                (mirror/refresh! eng rdef row)
                row)
          opts (render-opts eng req)
          env (if (= :summary depth)
                (render/envelope-summary rdef row opts)
                (splice-embeds eng rdef (render/envelope rdef row opts) opts))]
      (json-response 200 env media-type
                     {"ETag" (get-in env ["meta" "etag"])}))))

(defn- invoke-action [eng]
  (fn [{{:keys [plural id action]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-row! req rdef id)
          _ (check-action! req rdef (keyword action))
          body (read-body req)
          ;; batch B (flagged router seams, both owned by grants.clj):
          ;; a grant-denied argument answers the unknown-field 422
          ;; before invoke runs (dry-run included), and a committed
          ;; approve on an approval_request extends its grant
          ;; post-commit (system actor; a no-op for everything else)
          _ (grants/check-args! (visibility-of req) rdef (keyword action) body)
          opts (invoke-opts req)
          result (grants/approval-effects!
                  eng rdef (keyword action)
                  (inv/invoke! eng (:kind rdef) id (keyword action) body opts))]
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
        (envelope-response eng rdef (:row result) req 200 nil)))))

;; ── bulk, batch and drafts (phase 7) ────────────────────────────────

(defn- report-response
  "A bulk/batch result: a stored replay serves the first execution's
  bytes verbatim, a fresh report renders like any envelope."
  [result]
  (if (= :idempotency (:replayed? result))
    (let [hit (:response result)]
      {:status (:status hit)
       :headers {"Content-Type" (:media-type hit)}
       :body (:response hit)})
    (json-response 200 (:report result) media-type nil)))

(defn- bulk-action [eng]
  (fn [{{:keys [plural action]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-kind! req rdef)
          _ (check-action! req rdef (keyword action))
          body (read-body req)
          ;; batch B (flagged): grant-denied args 422 here too — the
          ;; bulk body minus its ids is the per-item input
          _ (grants/check-args! (visibility-of req) rdef (keyword action)
                                (dissoc body :ids))
          opts (invoke-opts req)
          result (inv/bulk! eng (:kind rdef) (keyword action) body opts)]
      (if-some [d (:deferred result)]
        ;; the phase-7 punt closes (phase 9b): an over-threshold call
        ;; mints a job and answers 202 — the envelope is the body, the
        ;; Location is where to watch it
        (let [{job :row} (jobs/enqueue! eng d (:principal opts))]
          (envelope-response eng (get (inv/resources eng) :job) job req 202
                             {"Location" (str "/api/jobs/" (:id job))}))
        (report-response result)))))

(defn- batch-action [eng]
  (fn [{{:keys [plural id action]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-row! req rdef id)
          _ (check-action! req rdef (keyword action))
          body (read-body req)
          ;; batch B (flagged): each batch input answers the same 422
          ;; a denied arg draws on a single invoke
          _ (doseq [inp (:inputs body)]
              (grants/check-args! (visibility-of req) rdef (keyword action) inp))
          opts (invoke-opts req)]
      (report-response
       (inv/batch! eng (:kind rdef) id (keyword action) body opts)))))

(defn- draft-view-response [view]
  (json-response 200 (p/wire-value view) media-type nil))

(defn- draft-get [eng]
  (fn [{{:keys [plural id action]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)]
      (check-row! req rdef id)
      (check-action! req rdef (keyword action))
      (draft-view-response
       (drafts/fetch eng rdef id (keyword action) (principal-of req))))))

(defn- draft-put [eng]
  (fn [{{:keys [plural id action]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)]
      (check-row! req rdef id)
      (check-action! req rdef (keyword action))
      (draft-view-response
       (drafts/save! eng rdef id (keyword action) (read-body req)
                     (principal-of req))))))

(defn- draft-delete [eng]
  (fn [{{:keys [plural id action]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)]
      (check-row! req rdef id)
      (check-action! req rdef (keyword action))
      (drafts/discard! eng rdef id (keyword action) (principal-of req))
      {:status 204 :headers {}})))

;; ── live collab (websockets, phase 9b) ──────────────────────────────

(defn- draft-collab [eng]
  (fn [{{:keys [plural id action]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)]
      (check-row! req rdef id)
      (check-action! req rdef (keyword action))
      (collab/join eng rdef (keyword action) id (principal-of req) req))))

;; ── surfaces (phase 9b) ─────────────────────────────────────────────

(defn- surface-view [eng]
  (fn [{{:keys [name id]} :path-params :as req}]
    ;; a scoped request 404s the surface routes — projecting a
    ;; composition per grant is a named punt (the SSE precedent)
    (when (visibility-of req)
      (throw (p/problem :not-found 404 "Not found" {:detail "No such route."})))
    (let [sdef (or (get (:surfaces eng) name)
                   (throw (p/not-found "surface" name)))]
      (json-response 200
                     (surface/envelope eng sdef id
                                       {:principal (principal-of req)
                                        :now ((:now-fn eng))
                                        :services (:services eng)})
                     media-type nil))))

;; ── the OpenAPI overlay (phase 9b) ──────────────────────────────────

(defn- openapi-doc [eng]
  (fn [req]
    ;; the document names every kind; a scoped request gets the
    ;; concealment answer
    (when (visibility-of req)
      (throw (p/problem :not-found 404 "Not found" {:detail "No such route."})))
    (json-response 200 (openapi/document eng))))

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
      (check-row! req rdef id)
      (events/sse-handler eng d {:resource [(:kind rdef) id]
                                 :since (last-event-id req)}
                          req))))

(defn- firehose-events [eng]
  (fn [req]
    ;; the firehose spans kinds; projecting it per grant is a named
    ;; punt — a scoped request gets the concealment answer
    (when (visibility-of req)
      (throw (p/problem :not-found 404 "Not found" {:detail "No such route."})))
    (let [d (events-dispatcher eng)
          kinds (some->> (get (query-params req) "kinds") csv
                         (map keyword) set not-empty)]
      (events/sse-handler eng d {:kinds kinds
                                 :since (last-event-id req)}
                          req))))

;; ── the generic UI (phase 10) ───────────────────────────────────────

(defn- ui-page
  "GET /api/-/ui: the envelope-driven generic UI — one self-contained
  page (vanilla JS, no external hosts) that renders whatever the
  wire declares: kinds from well-known, collections from the query
  grammar, envelopes as forms. A static asset, served to anyone —
  a scoped request's DATA stays projected by the API it drives."
  [_eng]
  (let [page (some-> (io/resource "waymark10/ui.html") slurp)]
    (fn [_req]
      (if page
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body page}
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "The UI asset is not on the classpath."}))))))

;; ── attachment bytes (phase 9a) ─────────────────────────────────────

(defn- attachment-rdef [eng id]
  (or (get (inv/resources eng) :attachment)
      (throw (p/not-found :attachment id))))

(defn- bytes-put [eng]
  (fn [{{:keys [id]} :path-params :as req}]
    (let [rdef (attachment-rdef eng id)]
      (check-row! req rdef id)
      (let [result (attachments/put-bytes! eng id (:body req))]
        (envelope-response eng rdef (:row result) req 200 nil)))))

(defn- bytes-get [eng]
  (fn [{{:keys [id]} :path-params :as req}]
    (check-row! req (attachment-rdef eng id) id)
    (attachments/get-bytes eng id)))

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

(defn- wrap-identity
  "The identity boundary (phase 9a), judgment-style: the principal
  (bearer token via the engine's :oidc config, else dev headers), the
  members suspension gate, and the grant visibility all resolve ONCE
  and ride the request — every handler reads the same resolved
  identity, never the raw headers. Sits inside the problem boundary
  so its refusals (401 bad token, 403 suspended) project like any
  problem."
  [handler eng]
  (fn [req]
    (let [principal (or (oidc/resolve-principal (:oidc eng) (:headers req))
                        (dev-principal (:headers req)))
          ;; batch B (flagged): a presented invite token rides into the
          ;; gate — first authenticated sight binds an invited member
          principal (members/gate! eng principal
                                   (get-in req [:headers "x-waymark-invite"]))
          vis (when-some [gid (get-in req [:headers "x-waymark-grant"])]
                (grants/visibility eng gid principal))]
      (handler (cond-> (assoc req :waymark10/principal principal)
                 vis (assoc :waymark10/visibility vis))))))

(defn handler
  "The ring handler: linear router (static routes shadow the plural
  grammar), identity boundary inside the problem boundary, problem
  boundary outermost."
  [eng]
  (-> (ring/ring-handler
       (ring/router
        [["/api/.well-known/waymark" {:get (well-known eng)}]
         ["/api/openapi.json" {:get (openapi-doc eng)}]
         ["/api/schemas/:kind" {:get (kind-schema eng)}]
         ["/api/-/events" {:get (firehose-events eng)}]
         ["/api/-/ui" {:get (ui-page eng)}]
         ["/api/attachments/:id/bytes" {:put (bytes-put eng)
                                        :get (bytes-get eng)}]
         ["/api/surfaces/:name/:id" {:get (surface-view eng)}]
         ["/api/:plural" {:get (collection eng) :post (create eng)}]
         ["/api/:plural/-/:action" {:post (bulk-action eng)}]
         ["/api/:plural/:id" {:get (get-one eng)}]
         ["/api/:plural/:id/-/events" {:get (resource-events eng)}]
         ["/api/:plural/:id/-/:action" {:post (invoke-action eng)}]
         ["/api/:plural/:id/-/:action/batch" {:post (batch-action eng)}]
         ["/api/:plural/:id/-/:action/draft" {:get (draft-get eng)
                                              :put (draft-put eng)
                                              :delete (draft-delete eng)}]
         ["/api/:plural/:id/-/:action/draft/collab" {:get (draft-collab eng)}]]
        {:conflicts nil})
       not-found-handler)
      (wrap-identity eng)
      wrap-problems))
