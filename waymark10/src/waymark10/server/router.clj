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
  - a declared :embed link gains \"embedded\" on the full envelope
    only: the target collection, filtered by the link's own compiled
    href (the href and the inline items can never disagree) — the
    SAME query grammar (collections/parse-query) the real collection
    endpoint uses, so every embed is a paginated, filtered, sorted
    view (\"embedded\"/\"total\"/\"page\"), never a flat teaser.
    embed.<rel>.<param>=value on the PARENT's own GET overrides that
    view per link; a param the link's own href already fixes (an
    :owns/:edge join key — plan_id, say) is locked and refuses (422)
    if an override names it. Loading happens HERE — render stays
    storage-free; override/parse errors are real 422s (the client's
    own input, never silent), but a STORAGE failure once the query is
    valid stays best-effort: a *err* warning, never the GET.
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
            [waymark10.server.intents :as intents]
            [waymark10.server.invoke :as inv]
            [waymark10.server.jobs :as jobs]
            [waymark10.server.members :as members]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.oidc-rp :as oidc-rp]
            [waymark10.server.openapi :as openapi]
            [waymark10.server.presence :as presence]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.surface :as surface]
            [waymark10.server.ui-assembly :as ui-assembly]
            [waymark10.server.worksheet :as worksheet]
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
     ;; dry_run=1 is the full rehearsal; dry_run=partial judges only
     ;; what the caller provided (design §23) — anything else is a
     ;; real invoke, as ever
     :dry-run (case (get (query-params req) "dry_run")
                "1" true
                "partial" :partial
                nil)}))

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

;; ── the dry-run doors' shared chrome (design §23) ───────────────────

(defn- intents-running
  "The engine's intents registry when one is running — the automatic
  doors (dry-run, warning wall) report through it and never fail a
  request over its absence."
  [eng]
  (some-> (:runtime eng) deref :intents))

(defn- report-intent!
  "Best-effort: an intent frame is ephemeral company, never the
  request's fate — a failed report warns on *err* and the invoke
  answers untouched."
  [reg principal intent]
  (try
    (intents/report! reg principal intent)
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 intent report failed -" (ex-message e))))))

(defn- announce-considering!
  "Beat 3 at a dry-run door — single, batch, bulk, create alike: a
  valid FULL rehearsal is a considering, best-effort, named
  principals on a started engine only. The partial rehearsal is
  deliberately mute here: it fires at typing cadence, and company
  must never cost the work (§23, recorded)."
  [eng req self action result]
  (when-some [reg (intents-running eng)]
    (let [principal (principal-of req)]
      (when (not= (:id principal) (:id t/anonymous))
        (report-intent! reg principal
                        {:self self :action action
                         :status "considering"
                         :warnings (some->> (:warnings result)
                                            (mapv #(select-keys % [:name :reason])))})))))

(defn- dry-run-response
  "A dry-run's verdict, the invoke door's shape grown optional limbs:
  {:valid …} plus :warnings (full and partial), :judged/:awaiting
  (partial — always present there, even empty), and :verdicts
  (bulk/batch — per item)."
  [result]
  (json-response 200
                 (p/wire-value
                  (cond-> {:valid (boolean (:valid? result))}
                    (:warnings result)
                    (assoc :warnings (mapv p/prune (:warnings result)))
                    (:judged result) (assoc :judged (:judged result))
                    (:awaiting result) (assoc :awaiting (:awaiting result))
                    (:verdicts result)
                    (assoc :verdicts (mapv p/prune (:verdicts result)))))
                 media-type nil))

;; ── handlers ────────────────────────────────────────────────────────

(defn- scope-action-names
  "Every action-name string a scope entry for this kind may name in
  :actions — declared actions, generated field editors, and the
  create verb — exactly the vocabulary check-action! enforces.
  Exposed on well-known so building a grant/approval_request scope
  needs no source read: an invited agent sees the real strings, not
  a guess at hyphen-vs-underscore or at what a :while-open group
  generated."
  [rdef]
  (into (sorted-set)
        (map name)
        (concat (keys (:actions rdef)) (:create-action-names rdef))))

(defn- well-known [eng]
  (fn [req]
    (let [vis (visibility-of req)
          principal (principal-of req)
          resources (cond->> (inv/resources eng)
                      vis (filter (fn [[k _]] ((:kind? vis) k))))]
      (json-response
       200
       (cond-> {:waymark "10"
                :kinds (vec (sort (map (comp name key) resources)))
                :resources (into (sorted-map)
                                 (map (fn [[k r]]
                                        [(name k)
                                         (cond-> {:href (str "/api/" (:plural r))
                                                  :actions (vec (scope-action-names r))
                                                  ;; nav tier on the wire at last —
                                                  ;; the generic UI's client-side
                                                  ;; ENGINE_KINDS set retires
                                                  :nav (name (:nav r :primary))}
                                           (:domain r)
                                           (assoc :domain (name (:domain r))))]))
                                 resources)}
         ;; global navigation between the deployable's applications:
         ;; every distinct declared domain, sorted — present only when
         ;; some kind declares one, so single-domain wires are unchanged
         (some (comp :domain val) resources)
         (assoc :domains (->> resources
                              (keep (comp :domain val))
                              (map name) distinct sort vec))
         ;; the declared surfaces (phase 9b) — hidden from a scoped
         ;; request, whose surface routes 404 anyway
         (and (seq (:surfaces eng)) (nil? vis))
         (assoc :surfaces (surface/well-known-entry (:surfaces eng)))

         ;; who the engine resolved this request to — the UI's
         ;; signed-in identity; absent when anonymous
         (not= t/anonymous principal)
         (assoc :principal {:id (:id principal)
                            :display (or (:display principal) (:id principal))
                            :type (name (:type principal :human))
                            :roles (vec (sort (:roles principal)))}))))))

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

(defn- mark-read!
  "The implicit presence door (under a leash means watchable): a
  grant-scoped principal's successful GET marks its gaze on what it
  read — source \"read\", best-effort, never the read's fate. An
  unscoped read stays invisible, as ever: a human's casual curl
  paints no gaze."
  [eng req self]
  (when (visibility-of req)
    (when-some [reg (some-> (:runtime eng) deref :presence)]
      (try
        (presence/read! reg (principal-of req) self)
        (catch Exception e
          (binding [*out* *err*]
            (println "waymark10 presence read-mark failed:"
                     (ex-message e))))))))

(defn- collection [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-kind! req rdef)
          params (query-params req)
          rows (rows-of params)
          env (collections/envelope eng rdef (dissoc params "rows")
                                    (cond-> (render-opts eng req)
                                      rows (assoc :rows rows)))]
      (mark-read! eng req (str "/api/" plural))
      (json-response 200 env media-type nil))))

(defn- create [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-kind! req rdef)
          _ (check-action! req rdef (first (:create-action-names rdef)))
          opts (invoke-opts req)
          result (inv/create! eng (:kind rdef) (read-body req)
                              (select-keys opts [:principal :acknowledged
                                                 :idempotency-key :dry-run]))]
      (cond
        ;; the create door's rehearsal (§23): the verdict body, and —
        ;; full mode only — a considering card naming the COLLECTION
        ;; self (no row exists to name yet; one card per door,
        ;; recorded)
        (:valid? result)
        (do (when (true? (:dry-run opts))
              (announce-considering!
               eng req (str "/api/" plural)
               (name (first (:create-action-names rdef))) result))
            (dry-run-response result))

        (= :idempotency (:replayed? result))
        ;; the first execution's bytes, verbatim (phase 10: creates
        ;; honor a present key; the Location header is not stored —
        ;; the body's self carries the same href)
        (let [hit (:response result)]
          {:status (:status hit)
           :headers {"Content-Type" (:media-type hit)}
           :body (:response hit)})

        :else
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

(def ^:private embed-override-re
  "embed.<rel>.<param> — dot-namespaced, not bracket-nested: page[size]
  is one opaque literal parse-query already matches by =, not a real
  nesting grammar, so there's nothing to reuse there; dot needs a
  strictly simpler, unambiguous regex to peel a rel off the front."
  #"^embed\.([^.]+)\.(.+)$")

(defn- embed-overrides
  "Every embed.<rel>.<param>=value in the request's query params,
  grouped by rel: {rel {param value}}. The rel itself isn't validated
  here — splice-embeds refuses one naming a rel that isn't a declared,
  embeddable link."
  [req]
  (reduce-kv
   (fn [acc k v]
     (if-some [[_ rel param] (re-matches embed-override-re k)]
       (update acc rel assoc param v)
       acc))
   {}
   (query-params req)))

(defn- warn-embed-failed! [rel e]
  (binding [*out* *err*]
    (println "waymark10 embed failed for link" rel "-" (ex-message e))))

(defn- splice-embeds
  "Every declared :embed link of one FULL wired envelope gains
  \"embedded\"/\"total\"/\"page\"/\"columns\": the target collection,
  filtered by the link's own compiled href — parsed through the SAME
  collection grammar (collections/parse-query) the real collection
  endpoint uses, so the href and the inline items can never disagree,
  and an embed is a real paginated/filtered/sorted view, not a flat
  teaser. \"columns\" is the target's own query-input-schema (the same
  filter/sort vocabulary collections/envelope advertises at
  actions.query.input) — a client builds grid controls for this embed
  straight from the parent's own envelope, no second GET to the
  bare href.

  overrides ({rel {param value}}, from embed-overrides) merge into
  the href's own params before parse-query runs. A param already
  present in the href-derived params is locked (an :owns/:via or
  :edge/:on join key, e.g. plan_id) — an override naming one refuses
  (422), never silently overwritten, and the SAME locked keys are
  dropped from both \"columns\" (nothing to filter on when every row
  already shares one value) and every embedded item's \"fields\"
  (nothing to show when the value is baked into the link itself,
  already visible on the parent). A rel with no declared,
  embeddable link, or an override parse-query itself rejects (an
  unfilterable/unsortable field, a bad value, page[size] past the
  global cap), refuses the same way — the client's own input is never
  best-effort. Once the query is valid, the STORAGE read is
  best-effort: a failure there drops with a *err* warning, never the
  GET. Template (:href) links have no target rdef to load from and
  never embed."
  [eng rdef env ctx-opts overrides]
  (let [embeddable (filter :embed (:links rdef))
        embeddable-rels (into #{} (map (comp name :rel)) embeddable)]
    (doseq [rel (keys overrides)
            :when (not (contains? embeddable-rels rel))]
      (throw (p/schema-invalid
              :query {(str "embed." rel) ["not a declared embeddable link"]})))
    (reduce
     (fn [env ld]
       (let [rel (name (:rel ld))
             link (get-in env ["links" rel])
             target-kind (or (when-some [e (:edge ld)]
                               (get-in rdef [:related e :kind]))
                             (:owns ld))
             trdef (when target-kind (get (inv/resources eng) target-kind))]
         (if-not (and link trdef)
           env
           (let [href-q (second (str/split (str (get link "href")) #"\?" 2))
                 href-params (into {}
                                   (keep (fn [kv]
                                           (let [[k v] (str/split kv #"=" 2)]
                                             (when-not (str/blank? k)
                                               [(url-decode k) (url-decode (or v ""))]))))
                                   (when href-q (str/split href-q #"&")))
                 embed-decl (:embed ld)
                 limit (when (map? embed-decl) (:limit embed-decl))
                 max-limit (when (map? embed-decl) (:max-limit embed-decl))
                 rel-overrides (get overrides rel {})
                 _ (doseq [k (keys rel-overrides)
                           :when (contains? href-params k)]
                     (throw (p/schema-invalid
                             :query {(str "embed." rel "." k)
                                     ["is fixed by this link and cannot be overridden"]})))
                 params (cond-> (merge href-params rel-overrides)
                          (and limit (not (contains? rel-overrides "page[size]")))
                          (assoc "page[size]" (str limit)))
                 {:keys [conds sort page]} (collections/parse-query trdef params)
                 _ (when (and max-limit (> (:size page) max-limit))
                     (throw (p/schema-invalid
                             :query {(str "embed." rel ".page[size]")
                                     [(str "must be an integer 1.." max-limit)]})))
                 ;; every row of THIS embed already shares one value for
                 ;; a locked key (it's what the href is filtered to) —
                 ;; nothing to filter on, nothing worth a column either
                 locked-keys (set (keys href-params))
                 ;; the target's own filter/sort vocabulary, so a
                 ;; client builds grid controls for this embed without
                 ;; a second GET to its bare href — pure computation,
                 ;; so it lands even if the storage read below fails
                 env (assoc-in env ["links" rel "columns"]
                               (p/wire-value
                                (update (collections/query-input-schema trdef)
                                        :properties
                                        #(apply dissoc % locked-keys))))]
             (try
               (let [st (:storage eng)
                     [rows total]
                     (store/with-tx st
                       (fn [tx]
                         [(store/search-rows st tx (:kind trdef) conds
                                             {:order-by (:field sort)
                                              :desc (:desc sort)
                                              :limit (:size page)
                                              :offset (* (:size page) (dec (:number page)))})
                          (store/count-matching st tx (:kind trdef) conds)]))
                     items (mapv #(update (render/envelope-summary
                                           trdef (inv/decode-row trdef %) ctx-opts)
                                          "fields"
                                          (fn [flds] (apply dissoc flds locked-keys)))
                                 rows)]
                 (-> env
                     (assoc-in ["links" rel "embedded"] items)
                     (assoc-in ["links" rel "total"] total)
                     (assoc-in ["links" rel "page"]
                               {"size" (:size page) "number" (:number page)})))
               (catch Exception e
                 (warn-embed-failed! rel e)
                 env))))))
     env
     embeddable)))

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
                (splice-embeds eng rdef (render/envelope rdef row opts) opts
                               (embed-overrides req)))]
      (mark-read! eng req (str "/api/" plural "/" id))
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
          ;; the intent seams (ephemeral, never law): a dry-run IS a
          ;; considering and a warning wall IS an ask — announced only
          ;; for named principals on a started engine, after the
          ;; concealment checks above (a concealed row 404s first, so
          ;; no intent ever names it)
          self (str "/api/" plural "/" id)
          reg (intents-running eng)
          announce? (boolean (and reg (not= (:id (:principal opts))
                                            (:id t/anonymous))))
          result (try
                   (grants/approval-effects!
                    eng rdef (keyword action)
                    (inv/invoke! eng (:kind rdef) id (keyword action) body opts))
                   (catch Exception e
                     (let [d (ex-data e)]
                       ;; beat 5: the wall the agent hit becomes the
                       ;; question on the approver's screen — the
                       ;; guard's own sentence, lingering until
                       ;; answered, abandoned, or resolved by the
                       ;; acknowledged retry
                       (when (and announce?
                                  (= :warning-required (:waymark10/problem d)))
                         (report-intent! reg (:principal opts)
                                         {:self self :action action
                                          :status "asking"
                                          :question (:reason (first (:warnings d)))
                                          :warnings (mapv #(select-keys % [:name :reason])
                                                          (:warnings d))
                                          :acknowledge (:acknowledge d)}))
                       (throw e))))
          ;; beat 3: the dry-run's shadow — "considering — <action> on
          ;; <resource>", gone in a moment if abandoned. Only the FULL
          ;; rehearsal reports; the partial blur judge is mute (§23)
          _ (when (and announce? (true? (:dry-run opts)) (:valid? result))
              (report-intent! reg (:principal opts)
                              {:self self :action action
                               :status "considering"
                               :warnings (some->> (:warnings result)
                                                  (mapv #(select-keys % [:name :reason])))}))]
      (cond
        ;; stored replay: the first execution's bytes, verbatim
        (= :idempotency (:replayed? result))
        (let [hit (:response result)]
          {:status (:status hit)
           :headers {"Content-Type" (:media-type hit)}
           :body (:response hit)})

        (:valid? result)
        (dry-run-response result)

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
      (cond
        (:deferred result)
        ;; the phase-7 punt closes (phase 9b): an over-threshold call
        ;; mints a job and answers 202 — the envelope is the body, the
        ;; Location is where to watch it
        (let [{job :row} (jobs/enqueue! eng (:deferred result)
                                        (:principal opts))]
          (envelope-response eng (get (inv/resources eng) :job) job req 202
                             {"Location" (str "/api/jobs/" (:id job))}))

        ;; the bulk door's rehearsal (§23): per-item verdicts, and —
        ;; full mode, all-ok only — ONE considering card naming the
        ;; collection self (a card per id would deal a hand per
        ;; check, recorded)
        (contains? result :valid?)
        (do (when (and (true? (:dry-run opts)) (:valid? result))
              (announce-considering! eng req (str "/api/" plural)
                                     action result))
            (dry-run-response result))

        :else (report-response result)))))

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
          opts (invoke-opts req)
          result (inv/batch! eng (:kind rdef) id (keyword action) body opts)]
      ;; the batch door's rehearsal (§23): index-keyed verdicts, and —
      ;; full mode, all-ok only — the considering card names the row,
      ;; exactly as the single door's
      (if (contains? result :valid?)
        (do (when (and (true? (:dry-run opts)) (:valid? result))
              (announce-considering! eng req (str "/api/" plural "/" id)
                                     action result))
            (dry-run-response result))
        (report-response result)))))

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
      ;; identity over the socket: a browser WS cannot send the
      ;; headers wrap-identity reads, so a ?ticket= (minted by the
      ;; authenticated POST /api/-/collab-ticket) names the joiner. A
      ;; presented ticket that does not redeem refuses BEFORE the
      ;; upgrade — plain HTTP, never a half-open socket; no ticket
      ;; keeps the header/anonymous path exactly as before.
      (let [principal (if-some [tk (get (query-params req) "ticket")]
                        (or (collab/redeem-ticket! eng tk)
                            (throw (p/problem
                                    :collab-ticket-invalid 401 "Ticket invalid"
                                    {:detail (str "The ticket is unknown, expired or"
                                                  " already spent; mint a fresh one"
                                                  " (POST /api/-/collab-ticket).")})))
                        (principal-of req))]
        (collab/join eng rdef (keyword action) id principal req)))))

(defn- collab-ticket-mint
  "POST /api/-/collab-ticket: the authenticated session mints the
  one-time voucher its WebSocket join will present — the socket's
  identity rides the SAME resolved principal every other request
  carries."
  [eng]
  (fn [req]
    (json-response 200
                   (p/wire-value (collab/mint-ticket! eng (principal-of req)))
                   media-type nil)))

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
      ;; the implicit presence door: a per-resource subscription IS
      ;; presence — the engine already knows the principal and the
      ;; resource, so the stream registers on subscribe and drops on
      ;; disconnect (source \"stream\"). Anonymous streams mark nobody.
      (let [principal (principal-of req)
            reg (some-> (:runtime eng) deref :presence)
            self (str "/api/" plural "/" id)
            hooks (when (and reg (not= (:id principal) (:id t/anonymous)))
                    {:on-subscribe #(presence/stream-open! reg principal self)
                     :on-unsubscribe #(presence/stream-closed! reg principal self)})]
        (events/sse-handler eng d (merge {:resource [(:kind rdef) id]
                                          :since (last-event-id req)}
                                         hooks)
                            req)))))

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

;; ── presence (ephemeral, never law) ─────────────────────────────────

(defn- presence-registry
  "The engine's running presence registry — 503 on an engine that
  never started (the dispatcher's discipline)."
  [eng]
  (or (some-> (:runtime eng) deref :presence)
      (throw (p/problem :presence-unavailable 503 "Presence unavailable"
                        {:detail (str "This engine is not started; the "
                                      "presence registry is not running.")}))))

(defn- presence-stream
  "GET /api/-/presence: the where-they-look stream. Unlike the
  firehose, a scoped request is not 404'd — it gets the stream
  PROJECTED: only presences on selves its visibility could GET, the
  frames it may not see byte-level absent."
  [eng]
  (fn [req]
    (let [reg (presence-registry eng)]
      (presence/sse-handler eng reg
                            (presence/self-visible? eng (visibility-of req))
                            req))))

(defn- presence-report
  "POST /api/-/presence {self}: the explicit heartbeat for clients
  that only hold the firehose (the ported UI's case). A scoped
  principal's own reporting is always accepted."
  [eng]
  (fn [req]
    (let [reg (presence-registry eng)]
      (presence/report! reg (principal-of req) (:self (read-body req)))
      {:status 204 :headers {}})))

;; ── intents (ephemeral, never law) ──────────────────────────────────

(defn- intents-registry
  "The engine's running intents registry — 503 on an engine that
  never started (the dispatcher's discipline)."
  [eng]
  (or (some-> (:runtime eng) deref :intents)
      (throw (p/problem :intents-unavailable 503 "Intents unavailable"
                        {:detail (str "This engine is not started; the "
                                      "intents registry is not running.")}))))

(defn- intents-stream
  "GET /api/-/intents: the considering/asking stream. Like presence
  (and unlike the firehose), a scoped request is not 404'd — it gets
  the stream PROJECTED: only intents on selves its visibility could
  GET, the frames it may not see byte-level absent."
  [eng]
  (fn [req]
    (let [reg (intents-registry eng)]
      (intents/sse-handler eng reg
                           (presence/self-visible? eng (visibility-of req))
                           req))))

(defn- intents-report
  "POST /api/-/intents {self, action, question?}: the explicit door —
  a client surfacing a considering the router cannot see (or its own
  confirm gate as an ask, question = the consequence sentence). A
  principal's own reporting is always accepted, scoped or not."
  [eng]
  (fn [req]
    (let [reg (intents-registry eng)
          body (read-body req)]
      (intents/report! reg (principal-of req)
                       (select-keys body [:self :action :question]))
      {:status 204 :headers {}})))

(defn- intents-abandon
  "POST /api/-/intents/abandon {self, action}: the caller clears its
  own card — the considering that came to nothing, the ask it no
  longer stands behind."
  [eng]
  (fn [req]
    (let [reg (intents-registry eng)
          body (read-body req)]
      (intents/abandon! reg (principal-of req)
                        (select-keys body [:self :action]))
      {:status 204 :headers {}})))

(defn- intents-answer
  "POST /api/-/intents/answer {id, names?}: the human's yes on a
  pending ask — delivered back down the stream; the asker's retry
  still passes the guard through the E1 header. Concealment holds:
  an intent the answerer may not see is the same 404 as none."
  [eng]
  (fn [req]
    (let [reg (intents-registry eng)
          body (read-body req)]
      (intents/answer! reg (principal-of req)
                       (select-keys body [:id :names])
                       (presence/self-visible? eng (visibility-of req)))
      {:status 204 :headers {}})))

;; ── the welcome document (the invite link's destination) ───────────

(defn- welcome-doc
  "GET /api/-/welcome?invite=TOKEN: what the invite link the human
  hands an agent points at — the whole joining protocol as one wire
  document, readable cold. Token-gated, not authenticated (the token
  IS the secret); an unknown or already-spent token answers 404 and
  says nothing. No side effects: the token spends on the agent's
  first real request carrying X-Waymark-Invite — which can be the
  access request itself, so joining is one POST.

  :ask.vocabulary is the closing-the-loop link: well-known's
  per-kind :actions names the exact scope-entry strings this engine
  understands (declared actions, generated field editors, the create
  verb), so a scope gets built from wire data alone — no reading the
  resource declaration to learn a hyphen-vs-underscore convention or
  guess at what a :while-open group generated."
  [eng]
  (fn [req]
    (let [token (get (query-params req) "invite")
          member (or (members/invited-by-token eng token)
                     (throw (p/problem :not-found 404 "Not found"
                                       {:detail "No standing invitation."})))
          services (:services eng)
          default-ttl (long (:grant-default-ttl-seconds services 3600))
          max-ttl (long (:grant-max-ttl-seconds services 86400))]
      (json-response
       200
       {:waymark "10"
        :welcome (get-in member [:data :display])
        :bind {:header "X-Waymark-Invite"
               :token token
               :note (str "send this header on your FIRST request — it "
                          "binds this invitation to your principal id; "
                          "one use, then the link goes dark")
               :if_it_goes_wrong
               (str "binding spends the invite, not the ask: if the "
                    "request carrying this header fails validation, you "
                    "are ALREADY bound — retry the ask without the "
                    "invite header, as many times as it takes")
               :cautious_path
               (str "bind first with a harmless read — GET "
                    "/api/.well-known/waymark carrying the invite "
                    "header — then file the ask as a plain named "
                    "request; same end state, nothing rides on one shot")}
        :identity {:header "x-waymark-principal"
                   :note "your stable agent id — every act is recorded under it"
                   :actor_type "agent"}
        :ask {:href "/api/approval_requests"
              :method "POST"
              :body {:task "what you are here to do, one sentence"
                     :scope [{:kind "a kind name from the vocabulary"
                              :actions ["exact action-name strings from the vocabulary"]
                              :ids "optional — specific rows"
                              :fields "optional — {mode allow|deny, names […]}"}]
                     :expires_at "RFC3339 instant, optional"}
              :vocabulary
              {:href "/api/.well-known/waymark"
               :note (str "each kind's :actions there lists every string "
                          "a scope entry may name for that kind — the "
                          "exact spellings, generated field editors and "
                          "the create verb included; there is no "
                          "wildcard, so name each one. Reading "
                          "(GET/list) needs no action at all: a scope "
                          "entry with :actions [] grants read-only "
                          "sight of the kind (the key itself is "
                          "required). Name actions only for create and "
                          "state-changing acts.")}
              :ttl {:default_seconds default-ttl
                    :max_seconds max-ttl
                    :note "propose the shortest leash your task needs; unstated means the default"}}
        :then {:poll (str "GET /api/approval_requests — your own asks are "
                          "always visible to you; approval stamps grant_id")
               :grant {:header "X-Waymark-Grant"
                       :note (str "send the stamped grant_id on every "
                                  "request — it selects your approved "
                                  "scope; outside it, resources answer 404")}}
        :presence {:href "/api/-/presence"
                   :method "POST"
                   :body {:self "the resource href you are reading"}
                   :note (str "how you are SEEN, not what you may do — "
                              "ephemeral, never law. Under your grant "
                              "every successful GET already marks your "
                              "gaze where you read: a human following "
                              "you watches your attention move with no "
                              "extra work on your part. Beat this "
                              "endpoint only to say you LINGER — you "
                              "are still working on something you are "
                              "not re-reading (every ~10s keeps you "
                              "present; silence fades you out in ~45s). "
                              "The reference client (waymark10.client) "
                              "beats it for you on every read.")}
        :discovery "/api/.well-known/waymark"}))))

;; ── the generic UI (phase 10) ───────────────────────────────────────

(defn- mobile-ua?
  "A phone-shaped User-Agent. `Mobi` is the token every mobile
  browser ships (Android Chrome, iOS Safari, Firefox Mobile);
  iPad/Android keep tablets in the net."
  [req]
  (boolean (re-find #"(?i)mobi|android|iphone|ipad"
                    (get-in req [:headers "user-agent"] ""))))

(defn- ui-page
  "GET /api/-/ui (and /api/-/ui-lite): the envelope-driven generic UI
  — one self-contained page (vanilla JS, no external hosts) that
  renders whatever the wire declares: kinds from well-known,
  collections from the query grammar, envelopes as forms. A static
  asset, served to anyone — a scoped request's DATA stays projected
  by the API it drives. The full client (the waymark9 generic UI,
  ported to wire 10) assembles from resources/waymark10/ui/;
  ui_lite.html preserves the original phase-10 page.

  A mobile User-Agent gets the SAME page stamped <html data-ui=
  \"mobile\"> — one client, two shells; the page's own CSS/JS key the
  mobile chrome (bottom tab nav, card rows, sheet dialogs) off the
  stamp. ?ui=mobile|desktop overrides the sniff, and the page's ⋯
  menu links the switch.

  Takes the page as a STRING (or nil → 404) — the full client arrives
  pre-assembled from fragments by ui-assembly/assemble; ui_lite.html
  is still slurped whole at the call site."
  [_eng page]
  (let [mobile (some-> page
                       (str/replace-first
                        "<html lang=\"en\">"
                        "<html lang=\"en\" data-ui=\"mobile\">"))]
    (fn [req]
      (if page
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (if (case (get (query-params req) "ui")
                     "mobile"  true
                     "desktop" false
                     (mobile-ua? req))
                 mobile
                 page)}
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "The UI asset is not on the classpath."}))))))

;; ── the worksheet round-trip ────────────────────────────────────────

(defn- worksheet-get
  "GET /api/:plural/-/worksheet?<filters> — the filtered view as an
  xlsx download, for kinds declaring :worksheet. The same query
  grammar as the collection; pagination params are ignored (a
  worksheet is the whole subset)."
  [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)]
      (check-kind! req rdef)
      (worksheet/export eng rdef (query-params req)))))

(defn- worksheet-post
  "POST /api/:plural/-/worksheet — the edited workbook back, raw
  bytes in the body. The upload STAGES: it lands as a worksheet row
  (the engine's own kind) whose post-commit pass plans every line,
  so the 201 already carries the full report; revalidate / apply /
  discard are the row's own actions from there. ?filename= names the
  file for the record."
  [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (rdef-by-plural eng plural)
          _ (check-kind! req rdef)
          result (worksheet/stage!
                  eng rdef (:body req)
                  {:principal (principal-of req)
                   :filename (get (query-params req) "filename")})
          row (:row result)
          ws-rdef (get (inv/resources eng) :worksheet)]
      (envelope-response eng ws-rdef row req 201
                         {"Location" (str "/api/worksheets/" (:id row))}))))

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

            ;; a declared :unique refused at the index (design §24) —
            ;; the tx has already rolled back, same as version-conflict
            (:waymark10/unique-violation d)
            (p/->response (p/unique-conflict (:kind d) (:constraint d)))

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
  (bearer token via the engine's :oidc config, else the RP session
  cookie, else dev headers), the members suspension gate, and the
  grant visibility all resolve ONCE and ride the request — every
  handler reads the same resolved identity, never the raw headers.
  Sits inside the problem boundary so its refusals (401 bad token,
  403 suspended) project like any problem."
  [handler eng]
  (fn [req]
    (let [principal (or (oidc/resolve-principal (:oidc eng) (:headers req))
                        (oidc-rp/resolve-session (:oidc eng) req)
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
         ["/api/-/presence" {:get (presence-stream eng)
                             :post (presence-report eng)}]
         ["/api/-/intents" {:get (intents-stream eng)
                            :post (intents-report eng)}]
         ["/api/-/intents/abandon" {:post (intents-abandon eng)}]
         ["/api/-/intents/answer" {:post (intents-answer eng)}]
         ["/api/-/collab-ticket" {:post (collab-ticket-mint eng)}]
         ["/api/-/welcome" {:get (welcome-doc eng)}]
         ["/api/-/ui" {:get (ui-page eng (ui-assembly/assemble))}]
         ["/api/-/ui-lite" {:get (ui-page eng (some-> (io/resource "waymark10/ui_lite.html")
                                                      slurp))}]
         ["/api/attachments/:id/bytes" {:put (bytes-put eng)
                                        :get (bytes-get eng)}]
         ["/api/surfaces/:name/:id" {:get (surface-view eng)}]
         ["/api/:plural" {:get (collection eng) :post (create eng)}]
         ["/api/:plural/-/worksheet" {:get (worksheet-get eng)
                                      :post (worksheet-post eng)}]
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
