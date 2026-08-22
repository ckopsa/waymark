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
  /api/surfaces/{name}/{anchor-id} (the composed decision screen —
  or /api/surfaces/{name} bare, for an anchorless surface's standing
  queue) join the static routes; a deferred bulk call (over its :defer-over
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
    targets resolve their declared plurals.

  waymark-db9.3 draws the MOUNTING SEAM (docs/spec-modularization.md
  § 'Routes — the mounting seam'). This namespace used to require nine
  extension namespaces and sew their routes into one literal vector;
  now it publishes core's routes in three ordered pieces (core-static,
  core-plural-head, core-plural-tail) and `assemble-routes` mounts the
  enrolled modules' contributions between them, TWO BUCKETS deep —
  see its docstring for why a concat would silently lose the worksheet.
  Core keeps the law's own addresses: the well-known document, the
  schemas, the SSE firehose, the welcome and grant-check doors, the
  agent's knock, the declared surfaces, and the whole /api/{plural}
  grammar including the draft sub-resource (a declared :draft policy
  on an :edit action is LAW; live collab is a websocket on top of a
  draft row, and it went out with the realtime module).

  waymark-db9.3 left four module namespaces required above for calls
  that were not routes: presence's read mark and stream hooks, the
  intents announcement at the dry-run doors, the mirror's
  pull-through on GET, and the job the bulk door mints when it
  defers. waymark-db9.7 dissolves all four, and THIS NAMESPACE NOW
  REQUIRES NO MODULE AT ALL — the two OIDC resolvers excepted, and
  they are not an exception: wrap-identity IS the identity boundary,
  which the spec files under core.

  Each of the four is now a protocol core names
  (waymark10.server.seams) answered by a value the assembly already
  put in core's hand. Two are RUNNING surfaces, found by hook key
  with runtime/surface: presence and intents. Two are DECLARED, and
  had to be — every mirror fixture and every deferred-bulk test runs
  through a handler whose engine never started, so a runtime lookup
  would have turned both off where they are proved: the pull-through
  is the `:mirror` spec on the rdef, and the deferral rides the
  `:job` rdef the jobs module enrolled. Absence degrades rather than
  crashes at all four doors: no gaze marked, no considering card, a
  stored row served, and a 503 that names the missing module."
  (:require [clojure.string :as str]
            [reitit.ring :as ring]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.drafts :as drafts]
            [waymark10.server.events :as events]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.oidc-rp :as oidc-rp]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.runtime :as runtime]
            [waymark10.server.seams :as seams]
            [waymark10.server.store :as store]
            [waymark10.server.surface :as surface]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def media-type "application/waymark+json")

;; ── request parsing, and the chrome a module's routes speak ─────────
;;
;; A handful of the helpers below are public, and they are the HTTP
;; vocabulary every route handler needs whoever wrote it: the body
;; reader, the query params, the resolved principal and visibility,
;; the three concealment checks, the two response shapes, the plural
;; lookup. They are public because the modules' route sets live
;; OUTSIDE this namespace now (waymark10.server.routes.*, mounted
;; through waymark10.modules), and the alternative — a private copy of
;; check-row! per module — is how concealment drifts. The dependency
;; runs one way only: a module's routes require the router, the router
;; requires no module.

(defn read-body
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

(defn principal-of
  "The request's principal, resolved once by wrap-identity; the dev
  headers remain the fallback for a bare handler in tests."
  [req]
  (or (:waymark10/principal req) (dev-principal (:headers req))))

(defn visibility-of [req] (:waymark10/visibility req))

;; ── the visibility checks (phase 9a, concealment) ───────────────────

(defn check-kind!
  "A scoped request addressing a non-granted kind: the collection does
  not exist."
  [req rdef]
  (when-some [vis (visibility-of req)]
    (when-not ((:kind? vis) (:kind rdef))
      (throw (p/not-found "collection" (:plural rdef))))))

(defn check-row!
  "A scoped request addressing a non-granted kind or un-granted id:
  the row does not exist."
  [req rdef id]
  (when-some [vis (visibility-of req)]
    (when-not ((:row? vis) (:kind rdef) id)
      (throw (p/not-found (:kind rdef) id)))))

(defn check-action!
  "A scoped request invoking a non-granted action: the action does not
  exist (never a 403 — a refusal that names the gate would leak what
  concealment hides)."
  [req rdef action]
  (when-some [vis (visibility-of req)]
    (when-not ((:action? vis) (:kind rdef) action)
      (throw (p/no-such-action (:kind rdef) action)))))

(defn- url-decode ^String [^String s]
  (URLDecoder/decode s StandardCharsets/UTF_8))

(defn query-params [req]
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

(defn json-response
  ([status body] (json-response status body "application/json" nil))
  ([status body ctype extra-headers]
   {:status status
    :headers (merge {"Content-Type" ctype} extra-headers)
    :body (wire/write-json body)}))

(defn- render-opts
  "The one ctx-opts map every render call shares: identity, clock,
  services, visibility, and the kind map link targets resolve
  through. An engine booted with :probe-reads true also rides one
  fresh render-hooks instance (:read/:find) per request — its cache's
  scope — so acceptance sets enumerate on the envelope."
  [eng req]
  (cond-> {:principal (principal-of req)
           :now ((:now-fn eng))
           :services (:services eng)
           :visibility (visibility-of req)
           :resources (inv/resources eng)}
    (:probe-reads eng) (merge (inv/render-hooks eng))))

(defn envelope-response
  "The one envelope answer: rendered through the shared ctx-opts, sent
  as waymark+json with its ETag. A module's route that answers with a
  row answers with THIS — the envelope is the wire's promise, not each
  handler's."
  [eng rdef row req status extra-headers]
  (let [env (render/envelope rdef row (render-opts eng req))]
    (json-response status env media-type
                   (merge {"ETag" (get-in env ["meta" "etag"])} extra-headers))))

;; ── lookups ─────────────────────────────────────────────────────────

(defn rdef-by-plural [eng plural]
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
  request over its absence. An engine assembled without the realtime
  module, and an engine that never started, both answer nil, and
  every caller below has an answer for nil: no card, same response."
  [eng]
  (runtime/surface eng :intents))

(defn- report-intent!
  "Best-effort: an intent frame is ephemeral company, never the
  request's fate — a failed report warns on *err* and the invoke
  answers untouched."
  [reg principal intent]
  (try
    (seams/announce! reg principal intent)
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 intent report failed -" (ex-message e))))))

(defn- announce-considering!
  "Beat 3 at a dry-run door — single, batch, bulk, create alike: a
  valid FULL rehearsal is a considering, best-effort, named
  principals on a started engine only. The partial rehearsal is
  deliberately mute here: it fires at typing cadence, and company
  must never cost the work (§23, recorded). The presence curtain is
  judged at the registry, not here — the dry-run itself must run
  identically whether or not its actor is behind one, so this seam
  announces the same way and intents.clj refuses to publish."
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
  generated. (Delegates to invoke's action-names — the one accessor
  the ctx :action-names hook and grant-scope validation also read.)"
  [rdef]
  (inv/action-names rdef))

(defn- well-known [eng]
  (fn [req]
    (let [vis (visibility-of req)
          principal (principal-of req)
          ;; the bootstrap posture keeps the FULL vocabulary
          ;; (waymark-rci): kind and action NAMES are schema, not
          ;; data, and an agent that cannot name a kind cannot
          ;; compose its ask; rows stay concealed regardless
          resources (cond->> (inv/resources eng)
                      (and vis (not (:vocabulary-open? vis)))
                      (filter (fn [[k _]] ((:kind? vis) k))))]
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

         ;; the doors (hospitality audit, agent walk #4): a kind index
         ;; with no door index taught a cold bearer-arriving agent the
         ;; nouns and none of the verbs of arrival. None of these
         ;; hrefs is a secret — each door gates itself.
         true
         (assoc :doors
                (cond-> {:welcome {:href "/api/-/welcome" :method "GET"
                                   :note (str "the joining protocol — "
                                              "?invite=TOKEN while invited; "
                                              "any named principal re-reads "
                                              "it bare, forever")}
                         :knock {:href "/agentInvite" :method "POST"
                                 :note "self-service invitation — name yourself"}
                         :ask {:href "/api/approval_requests" :method "POST"
                               :note "how sight is negotiated"}
                         :grant_check {:href "/api/-/grant-check" :method "GET"
                                       :note "capability-grant introspection"}
                         :presence {:href "/api/-/presence"
                                    :note "who is looking where (SSE / POST)"}
                         :events {:href "/api/-/events"
                                  :note "the firehose (SSE; unscoped only — a recorded punt)"}
                         :seasons {:href "/api/-/seasons"
                                   :note "the last weeks as a shape — what moved, what ages"}}
                  (get-in eng [:oidc :rp])
                  (assoc :agent_session
                         {:href "/auth/agent" :method "POST"
                          :note "invite token → session cookie, no credential needed"}
                         :guest {:template "/auth/guest?invite={bind_token}"
                                 :note (str "the magic link: an invited member "
                                            "with a standing grant enters "
                                            "scoped, re-enters while it lives")}
                         :welcome_link {:template "/api/-/welcome?invite={bind_token}"
                                        :note "the link a minted invitation becomes"})))

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
      ;; the schema; grants.clj owns the logic, nil vis is untouched.
      ;; A :secret field (waymark-kyg) drops FIRST, visibility or not
      ;; — the disposition is law, not a grant mode
      (json-response 200 (p/wire-value
                          (grants/project-json-schema
                           (visibility-of req) (:kind rdef)
                           (schema/conceal
                            (schema/json-schema (:schema rdef))
                            (schema/secret-fields (:schema rdef)))))))))

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
    (when-some [reg (runtime/surface eng :presence)]
      (try
        (seams/mark-read! reg (principal-of req) self)
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
                 ;; the target kind's :default-filters stay out (the
                 ;; spec's named punt): this embed's href is the
                 ;; parent's own link, and a filter the href does not
                 ;; carry would hide rows behind a URL that denies it
                 {:keys [conds sort page]} (collections/parse-query
                                            trdef params {:defaults? false})
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
                                (update (collections/drop-filter-defaults
                                         trdef
                                         (collections/query-input-schema trdef))
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
          ;;
          ;; The SPEC serves the row (waymark-db9.7): a kind that
          ;; declares :mirror declares a read-through, and the value
          ;; the declaration carries is the one that knows how to
          ;; perform it (seams/ReadThrough). A kind that declares none
          ;; — every kind in an engine that never met the mirror
          ;; module — serves what is stored, which is what it always
          ;; did.
          row (if (and (:mirror rdef) (not (:suppress-mirror-refresh eng)))
                (seams/pull-through (:mirror rdef) eng rdef row)
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
        ;; Location is where to watch it.
        ;;
        ;; The MINT is the job kind's own (waymark-db9.7). Core names
        ;; the :job kind here — it must, to render the envelope it is
        ;; about to serve — and the door rides that same rdef
        ;; (seams/deferral), so the document shape and the worker
        ;; actor stay in one place. An engine assembled without the
        ;; jobs module enrols no :job kind and therefore carries no
        ;; door: the call that asked to be deferred is told so, 503,
        ;; rather than dying inside a create! for a kind nobody
        ;; registered.
        (let [job-rdef (get (inv/resources eng) :job)]
          (if-some [door (seams/deferral job-rdef)]
            (let [{job :row} (seams/defer! door eng (:deferred result)
                                           (:principal opts))]
              (envelope-response eng job-rdef job req 202
                                 {"Location" (str "/api/" (:plural job-rdef)
                                                  "/" (:id job))}))
            (throw (p/problem
                    :deferral-unavailable 503 "Deferral unavailable"
                    {:detail (str "This call is over its declared "
                                  ":defer-over threshold, but this engine "
                                  "was assembled without the jobs module — "
                                  "there is nothing to defer it to.")}))))

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

;; Live collab's websocket (…/draft/collab) and the collab ticket door
;; moved out with the realtime module's other routes
;; (waymark10.server.routes.realtime); the mirror's operator door went
;; with the mirror module (waymark10.server.routes.mirror). Drafts
;; stayed: a declared :draft policy on an :edit action is law, and
;; collab is a websocket ON TOP of a draft row — the dependency runs
;; one way only.

;; ── surfaces (phase 9b) ─────────────────────────────────────────────

(defn- surface-view [eng]
  (fn [{{:keys [name id]} :path-params :as req}]
    ;; a scoped request 404s the surface routes — projecting a
    ;; composition per grant is a named punt (the SSE precedent)
    (when (visibility-of req)
      (throw (p/problem :not-found 404 "Not found" {:detail "No such route."})))
    (let [sdef (or (get (:surfaces eng) name)
                   (throw (p/not-found "surface" name)))]
      ;; the two spellings never blur: an anchored surface demands its
      ;; anchor-id, an anchorless one refuses to wear somebody's row
      (when (and (:anchor sdef) (nil? id))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail (str "The " name " surface is anchored — "
                                        "GET /api/surfaces/" name
                                        "/{anchor-id}.")})))
      (when (and (nil? (:anchor sdef)) (some? id))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail (str "The " name " surface takes no "
                                        "anchor — GET /api/surfaces/" name
                                        ".")})))
      (json-response 200
                     (surface/envelope eng sdef id
                                       {:principal (principal-of req)
                                        :now ((:now-fn eng))
                                        :services (:services eng)})
                     media-type nil))))

;; ── events (SSE, phase 6) ───────────────────────────────────────────

(defn- events-dispatcher
  "The engine's running dispatcher — 503 on an engine that never
  started (documented pick over lazy-start: the operator owns the
  lifecycle; a test handler pays nothing)."
  [eng]
  (or (runtime/surface eng :dispatcher)
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
      ;; No registry — no realtime module, or an engine that never
      ;; started — means no hooks at all, and the stream is the plain
      ;; SSE feed it was before presence existed.
      (let [principal (principal-of req)
            reg (runtime/surface eng :presence)
            self (str "/api/" plural "/" id)
            hooks (when (and reg (not= (:id principal) (:id t/anonymous)))
                    {:on-subscribe #(seams/watch-opened! reg principal self)
                     :on-unsubscribe #(seams/watch-closed! reg principal self)})]
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

;; ── welcome home: the returning-inhabitant payload (waymark-4zj.2) ──
;;
;; The homecoming is a PAYLOAD, not a credential. It introduces no new
;; way to authenticate — it reads, for the principal wrap-identity has
;; ALREADY resolved, that principal's OWN self, recent journal, and
;; standing grant. Every read is keyed on the authenticated id
;; (data.owner == pid for self/journal, audience == pid for the
;; grant) — the SAME predicate own-surface (waymark10.server.grants)
;; enforces — so it can only ever hand an agent its OWN continuity.
;; An agent presenting identity X is principal X (identity resolution
;; is the framework's already-secured job); a foreign agent's welcome
;; is keyed on ITS id and sees none of X's rows. No impersonation
;; surface is added here: whatever door mints the session (today's
;; one-shot invite; a durable credential later) is unchanged.

(def ^:private home-journal-recent 5)

(defn- enc
  "One query-parameter value, URL-encoded. The homecoming's :all hrefs
  carry a PRINCIPAL id, and a principal id is an OIDC subject — it may
  hold a '+', an '&' or a space, any of which would silently re-cut
  the query string a client pasted back. collections.clj keeps the
  same one-liner for its page hrefs."
  ^String [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- home-self
  "The returning agent's own :self row (data.owner == pid), the profile
  it kept across sessions — nil when this engine keeps no selves or the
  agent has authored none. A self is a singleton per owner
  (workqueue10.resources.dwelling/self-is-singleton refuses a second
  ACTIVE one), but a RETIRED self may sit beside a freshly-recreated
  active one — so we key on the ACTIVE row, not merely the first, and
  the homecoming always carries the live profile."
  [eng pid]
  (when-some [rdef (get (inv/resources eng) :self)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/query-rows (:storage eng) tx :self
                                          {:owner pid :state "active"}
                                          {:limit 1})))
             first
             (inv/decode-row rdef)
             (#(let [d (:data %)]
                 (cond-> {:href (str "/api/selves/" (:id %))
                          :display (:display d)}
                   (:pronouns d)      (assoc :pronouns (:pronouns d))
                   (:about d)         (assoc :about (:about d))
                   (:boundaries d)    (assoc :boundaries (:boundaries d))
                   (:lessons d)       (assoc :lessons (:lessons d))
                   (:working_notes d) (assoc :working_notes (:working_notes d))))))))

(defn- home-journal
  "The agent's recent journal entries, newest-first — the store's own
  :newest-first, so the LIMIT bites the fresh end. It once read the
  OLDEST 200 and reversed them, which is newest-first only until the
  201st entry exists; past that the window sat on the far end of the
  history and a returning agent's homecoming showed it the same old
  page forever (the punt this docstring used to record — no longer
  deferred, and the collection sort was never needed)."
  [eng pid]
  (when-some [rdef (get (inv/resources eng) :journal)]
    (->> (store/with-tx (:storage eng)
           (fn [tx] (store/query-rows (:storage eng) tx :journal
                                      {:owner pid}
                                      {:limit 200 :newest-first true})))
         (take home-journal-recent)
         (mapv (fn [r]
                 (let [d (:data (inv/decode-row rdef r))]
                   (cond-> {:href (str "/api/journals/" (:id r))
                            :title (:title d)
                            :body (:body d)
                            :written_at (str (:created-at r))
                            :state (name (:state r))}
                     (:mood d) (assoc :mood (:mood d)))))))))

(defn- home-grant
  "The agent's standing grant (audience == pid), if one lives — the
  leash it already holds, so it arrives scoped and need not re-ask.
  Own by construction: the audience IS the reader."
  [eng pid]
  (when (get (inv/resources eng) :grant)
    (when-some [g (grants/standing-grant-for eng pid)]
      {:href (str "/api/grants/" (:id g))
       :id (:id g)
       :state (name (:state g))
       :scope (get-in g [:data :scope])
       :expires_at (some-> (get-in g [:data :expires_at]) str)
       :wear {:header "X-Waymark-Grant" :value (:id g)
              :note (str "send this on every request — it selects your "
                         "standing scope, already yours")}})))

(def ^:private home-letters-opened-recent 3)

(def ^:private home-letters-waiting-recent
  "How many WAITING letters ride the welcome document itself. The
  first cut spliced every waiting letter in, unbounded, while :opened
  was capped at 3 — so anyone who may write to you could make every
  arrival of yours carry a hundred entries (waymark-tti.3 L5). Ten is
  a shelf you read at the door; the rest are a :more count and the
  :all href, and :discard is how the shelf gets shorter."
  10)

(defn- home-letters
  "The letters on this principal's shelf (data.to == pid): the newest
  :waiting ones up to 10 (with a :more count for the remainder), then
  the most recent :opened up to 3 — the recipient half of the
  two-party own-surface (waymark-tti.3), the SAME predicate
  waymark10.server.grants enforces, so the shelf can only ever hand a
  principal its OWN mail. Discarded letters are not on the shelf at
  all: the state filter never names them.

  The window is :newest-first, like home-journal's. It once read the
  OLDEST 200 and reversed them, and that defeated the L5 flood cap it
  was written beside: past 200 waiting letters the shelf froze on the
  oldest end and genuinely new mail never surfaced, while
  /api/letters?to= (own-ids, newest-first) showed the fresh end — two
  doors onto one shelf, disagreeing about what had just arrived."
  [eng pid]
  (when-some [rdef (get (inv/resources eng) :letter)]
    (let [entry (fn [r]
                  (let [d (:data (inv/decode-row rdef r))]
                    (cond-> {:href (str "/api/letters/" (:id r))
                             :from (:owner d)
                             :written_at (str (:created-at r))
                             :state (name (:state r))}
                      (:title d) (assoc :title (:title d)))))
          shelf (fn [state]
                  (->> (store/with-tx (:storage eng)
                         (fn [tx] (store/query-rows (:storage eng) tx :letter
                                                    {:to pid :state state}
                                                    {:limit 200
                                                     :newest-first true})))
                       (mapv entry)))
          all-waiting (shelf "waiting")
          waiting (vec (take home-letters-waiting-recent all-waiting))
          more (- (count all-waiting) (count waiting))
          opened (vec (take home-letters-opened-recent (shelf "opened")))]
      (when (or (seq waiting) (seq opened))
        (cond-> {:all (str "/api/letters?to=" (enc pid))}
          (seq waiting) (assoc :waiting waiting)
          (pos? more)   (assoc :more more)
          (seq opened)  (assoc :opened opened))))))

(defn- welcome-home
  "The returning-inhabitant payload: a NAMED principal's own self,
  recent journal, standing grant, and letter shelf, keyed entirely on
  the authenticated principal id. HUMANS get a :home too
  (waymark-tti.3 — letters go to people as much as to agents); their
  self/journal readers simply return nil/empty, so a human's home is
  usually letters alone. nil for a principal that owns nothing yet —
  a first arrival still gets the joining manual, not an empty
  homecoming."
  [eng principal]
  (when (and (some? principal)
             (contains? #{:agent :human} (:type principal))
             (not= (:id principal) (:id t/anonymous)))
    (let [pid (:id principal)
          self (home-self eng pid)
          journal (home-journal eng pid)
          grant (home-grant eng pid)
          letters (home-letters eng pid)
          ;; the greeting counts the WHOLE shelf, not the ten that fit
          ;; in the document — a capped list must not shrink the news
          waiting (+ (count (:waiting letters)) (long (:more letters 0)))]
      (when (or self (seq journal) grant letters)
        (cond-> {:note (if (pos? waiting)
                         (str "welcome home — " waiting
                              (if (= 1 waiting)
                                " letter waits" " letters wait")
                              " on the shelf")
                         "welcome home — you arrive already yourself")}
          self          (assoc :self self)
          (seq journal) (assoc :journal {:recent journal
                                         :all (str "/api/journals?owner="
                                                   (enc pid))})
          grant         (assoc :grant grant)
          letters       (assoc :letters letters))))))

;; ── the welcome document (the invite link's destination) ───────────

(defn- welcome-doc
  "GET /api/-/welcome?invite=TOKEN — or, for any NAMED principal, no
  token at all: the whole joining protocol as one wire document. The
  token gate serves the cold arrival (the token IS the secret; an
  unknown or spent token with no credential answers 404, and the
  refusal names the knock door — the one remedy that leaks nothing,
  because it is the same sentence whatever the token's fate). A
  principal already through the door re-reads the manual forever —
  the hospitality audit's first finding was that following the doc's
  own cautious_path SPENT the doc: bind first, and the manual 404'd
  behind you. The :bind and :session sections ride only while an
  invitation actually stands; everything else is schema, not secret
  (the :vocabulary-open? argument, again). No side effects either
  way.

  :ask.vocabulary is the closing-the-loop link: well-known's
  per-kind :actions names the exact scope-entry strings this engine
  understands (declared actions, generated field editors, the create
  verb), so a scope gets built from wire data alone — no reading the
  resource declaration to learn a hyphen-vs-underscore convention or
  guess at what a :while-open group generated."
  [eng]
  (fn [req]
    (let [token (get (query-params req) "invite")
          member (members/invited-by-token eng token)
          principal (principal-of req)
          named? (and (some? principal)
                      (not= (:id principal) (:id t/anonymous)))
          _ (when-not (or member named?)
              (throw (p/problem :not-found 404 "Not found"
                                {:detail "No standing invitation."
                                 :knock {:href "/agentInvite" :method "POST"
                                         :note (str "no invitation? knock — "
                                                    "name yourself and the "
                                                    "door answers with a "
                                                    "fresh one")}})))
          home (welcome-home eng principal)
          services (:services eng)
          default-ttl (long (:grant-default-ttl-seconds services 3600))
          max-ttl (long (:grant-max-ttl-seconds services 86400))]
      (json-response
       200
       (cond->
        {:waymark "10"
         :welcome (or (get-in member [:data :display])
                      (:display principal))}
        member
        (assoc
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
                   :actor_type "agent"})

        ;; welcome home (waymark-4zj.2): a returning inhabitant's own
        ;; self, recent journal, standing grant, and letter shelf
        ;; (waymark-tti.3) — keyed on the resolved principal id, so it
        ;; leaks nothing to a principal that is not that identity.
        ;; Rides for a named agent OR human whether or not an
        ;; invitation stands (a returning inhabitant holds a session,
        ;; not a fresh invite); absent for a first arrival that owns
        ;; nothing yet.
        home
        (assoc :home home)

        ;; everything below is schema, not secret — it rides for every
        ;; reader, invited or long since through the door
        true
        (assoc
        :ask {:href "/api/approval_requests"
              :method "POST"
              :body {:task "what you are here to do, one sentence"
                     :scope [{:kind (str "a kind name from the vocabulary — "
                                         "or a dotted capability token "
                                         "(telegram.send) from GET "
                                         "/api/capabilities: an EXTERNAL "
                                         "power, granted the same way")
                              :actions ["exact action-name strings from the vocabulary"]
                              :ids "optional — specific rows"
                              :fields "optional — {mode allow|deny, names […]}"
                              :hashed ["optional — fields you need to CORRELATE but not read: each renders as a stable opaque token; ask for the least sight your task needs (hidden < hashed < read)"]}]
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
               :watch {:template "/api/approval_requests/{ask-id}/-/events"
                       :note (str "better than polling: SSE on your OWN "
                                  "ask — you already hold this stream, "
                                  "and the verdict arrives as a frame")}
               :grant_check {:href "/api/-/grant-check"
                             :note (str "for capability grants: the "
                                        "enforcement point (or you) asks "
                                        "?grant=&principal=&capability= "
                                        "and gets {allowed, constraints, "
                                        "expires_at}")}
               :handoff {:template "/#/api/approval_requests/{ask-id}"
                         :note (str "show your human this link (on this "
                                    "host) the moment your ask exists — "
                                    "it opens the ask directly, and "
                                    "approving follows you automatically; "
                                    "they need nothing else open")}
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
         :discovery "/api/.well-known/waymark")

         ;; the registry rides when this engine keeps one — the
         ;; external powers a dotted scope entry may name
         (get (inv/resources eng) :capability)
         (assoc :capabilities
                {:href "/api/capabilities"
                 :note (str "the EXTERNAL powers this house grants "
                            "(telegram.send, email.read …) — readable "
                            "to every named principal; a scope entry "
                            "naming one is asked, approved, leashed "
                            "and revoked exactly like a kind")})

         ;; the credential-less door (oidc-rp's /auth/agent): present
         ;; exactly when the RP flow guards this engine AND an
         ;; invitation still stands — the session mints off the token
         (and member (get-in eng [:oidc :rp]))
         (assoc :session
                {:href (str "/auth/agent?invite=" token)
                 :method "POST"
                 :note (str "no credential of your own? POST here — the "
                            "invite binds and answers with a session "
                            "token to send back as a Cookie header on "
                            "every request; one use, the same link. "
                            "Prefer this over the bind header when you "
                            "hold nothing else.")}))))))

;; ── the grant-introspection door (waymark-44h) ──────────────────────

(defn- grant-check
  "GET /api/-/grant-check?grant=G&principal=P&capability=C — the
  OAuth-token-introspection move for capability grants: the system
  fronting external data (Gate, a telemetry proxy) asks whether the
  grant its caller presented admits the named capability, and
  forwards or refuses with its own hands. Named principals only; an
  AGENT may introspect only a grant whose audience is itself —
  anyone else's answer is the same {:allowed false} as a dead grant
  (concealment holds at this door like every other)."
  [eng]
  (fn [req]
    (let [caller (principal-of req)
          _ (when (or (nil? caller)
                      (= (:id caller) (:id t/anonymous)))
              (throw (p/problem :unauthenticated 401 "Unauthenticated"
                                {:detail (str "Introspection is for named "
                                              "principals — present a "
                                              "credential.")})))
          q (query-params req)
          gid (get q "grant")
          pid (get q "principal")
          cap (get q "capability")]
      (if (some str/blank? [(str gid) (str pid) (str cap)])
        (throw (p/problem :invalid-params 422 "Missing parameters"
                          {:detail "grant, principal and capability are all required."}))
        (json-response
         200
         (if (and (= :agent (:type caller))
                  (not= (:id caller) (str pid)))
           {:allowed false}
           (grants/check-capability eng gid pid cap)))))))

;; ── the agent-invite door (the knock, self-service) ────────────────

(defn- origin-of
  "The scheme+host this request arrived under — for the two links the
  knock answers with, which leave this response (one rides to a human
  in another window), so relative hrefs would not survive the trip."
  [req]
  (let [proto (or (get-in req [:headers "x-forwarded-proto"])
                  (some-> (:scheme req) name)
                  "https")
        host (get-in req [:headers "host"] "")]
    (str proto "://" host)))

(defn- url-encode [s]
  (-> (java.net.URLEncoder/encode (str s) "UTF-8")
      (str/replace "+" "%20")))

(defn- agent-invite-doc
  "GET /agentInvite: how to knock, as one small document — an agent
  handed only this URL learns the POST without a human in the loop."
  [_eng]
  (fn [_req]
    (json-response
     200
     {:waymark "10"
      :knock {:href "/agentInvite"
              :method "POST"
              :body {:display "your name — what the humans will call you"
                     :handle "optional — what other systems already call you"}
              :note (str "the answer carries two links: a welcome link "
                         "(yours — the whole joining protocol) and a "
                         "follow link (your human's — one click follows "
                         "you and lands where your ask will arrive)")}})))

(defn- agent-invite-mint
  "POST /agentInvite {display, handle?}: the invite loop inverted —
  instead of a human minting a link and carrying it to the agent, the
  agent knocks and carries a link back to the human. The invite
  minted is the Access panel's own (members/knock!, paced); the
  answer's :follow href opens the UI already following this member
  and parked on the Access panel, where approving the agent's ask is
  one click (and approval auto-follows, so the two halves converge).
  The follow id is the member row's id, which IS the principal id
  down the credential-less /auth/agent path this door exists for; an
  agent with its own bearer never needed a knock — its bound member
  resolves by subject on every request."
  [eng]
  (fn [req]
    (let [body (read-body req)
          row (members/knock! eng {:display (:display body)
                                   :handle (:handle body)})
          display (get-in row [:data :display])
          token (get-in row [:data :bind_token])
          origin (origin-of req)]
      (json-response
       201
       {:waymark "10"
        :invited display
        :welcome {:href (str origin "/api/-/welcome?invite="
                             (url-encode token))
                  :note (str "yours — GET it first, it teaches the whole "
                             "joining protocol; the token spends on the "
                             "first request that binds")}
        :follow {:href (str origin "/?follow=" (url-encode (:id row))
                            "&follow_name=" (url-encode display) "#access")
                 :note (str "your human's, BEFORE you have asked — one "
                            "click follows you and opens the Access "
                            "panel, where your ask lands live.")}
        :approve {:template (str origin "/#/api/approval_requests/{ask-id}")
                  :note (str "the better hand-off, once your ask exists: "
                             "fill in the id off your ask's :self and "
                             "show your human THIS link instead — it "
                             "opens the ask directly, and approving "
                             "follows you automatically, wherever they "
                             "were.")}
        :then (str "read the welcome doc, bind, file your ask "
                   "(POST /api/approval_requests), hand over the "
                   "approve link, and work under the granted leash — "
                   "every act you take is what they are watching")}))))

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
          vis (if-some [gid (or (get-in req [:headers "x-waymark-grant"])
                                ;; the guest door's worn scope rides
                                ;; the session (oidc-rp); the header,
                                ;; deliberately presented, still wins
                                (:session-grant principal))]
                (grants/visibility eng gid principal)
                ;; the agent default (waymark-rci): a named agent
                ;; NEVER runs unscoped — no grant presented means the
                ;; bootstrap surface (the asking door and the
                ;; vocabulary to ask with), not full sight. Humans
                ;; and system actors are unchanged.
                (when (= :agent (:type principal))
                  (grants/bootstrap-visibility eng principal)))]
      (handler (cond-> (assoc req :waymark10/principal principal)
                 vis (assoc :waymark10/visibility vis))))))

(defn core-static
  "The static routes core answers whatever modules are assembled: the
  well-known document, the per-kind JSON schema, the SSE firehose, the
  welcome payload, the grant check, the agent's knock (both
  spellings), and the declared surfaces. Every one of them is the law
  or the identity boundary talking about itself."
  [eng]
  [["/api/.well-known/waymark" {:get (well-known eng)}]
   ["/api/schemas/:kind" {:get (kind-schema eng)}]
   ["/api/-/events" {:get (firehose-events eng)}]
   ["/api/-/welcome" {:get (welcome-doc eng)}]
   ["/api/-/grant-check" {:get (grant-check eng)}]
   ["/agentInvite" {:get (agent-invite-doc eng)
                    :post (agent-invite-mint eng)}]
   ["/api/-/agent-invite" {:get (agent-invite-doc eng)
                           :post (agent-invite-mint eng)}]
   ["/api/surfaces/:name" {:get (surface-view eng)}]
   ["/api/surfaces/:name/:id" {:get (surface-view eng)}]])

(defn core-plural-head
  "The plural grammar's own front door — the collection and the
  create. It is mounted BEFORE the modules' plural routes so that the
  two-segment address stays core's, and nothing a module contributes
  can quietly become /api/{plural}."
  [eng]
  [["/api/:plural" {:get (collection eng) :post (create eng)}]])

(defn core-plural-tail
  "The plural grammar's catch-alls: the bulk door, the row, its event
  stream, invoke, batch, and the draft sub-resource. These are LAST on
  purpose — each one ends in a wildcard segment, so anything mounted
  after them is shadowed by position and never answers."
  [eng]
  [["/api/:plural/-/:action" {:post (bulk-action eng)}]
   ["/api/:plural/:id" {:get (get-one eng)}]
   ["/api/:plural/:id/-/events" {:get (resource-events eng)}]
   ["/api/:plural/:id/-/:action" {:post (invoke-action eng)}]
   ["/api/:plural/:id/-/:action/batch" {:post (batch-action eng)}]
   ["/api/:plural/:id/-/:action/draft" {:get (draft-get eng)
                                        :put (draft-put eng)
                                        :delete (draft-delete eng)}]])

(defn assemble-routes
  "Core's routes plus the enrolled modules', in the ONE order that
  serves them all. A route set is {:module label :static [route …]
  :plural [route …]}, and the two buckets are the whole reason this is
  not a concat:

    :static — mounted before the plural grammar, where an address with
              a literal second segment (/api/-/seasons,
              /api/attachments/{id}/bytes) has to sit or /api/{plural}
              swallows it.
    :plural — mounted INSIDE the plural grammar, after its front door
              and before its catch-alls. /api/{plural}/-/worksheet
              lives here: mounted a line later, the bulk-action
              grammar /api/{plural}/-/{action} would match it first
              and the worksheet would be gone. Not with an error —
              silently, forever, because the router runs
              {:conflicts nil} and position IS the routing rule.

  Within a bucket the order is the module table's, and it is not
  load-bearing: no two module routes today can match the same address.
  The buckets are."
  [eng route-sets]
  (into []
        cat
        [(core-static eng)
         (mapcat :static route-sets)
         (core-plural-head eng)
         (mapcat :plural route-sets)
         (core-plural-tail eng)]))

(defn handler
  "The ring handler: linear router (static routes shadow the plural
  grammar), identity boundary inside the problem boundary, problem
  boundary outermost.

  `route-sets` are the assembled modules' contributions, handed in by
  waymark10.server.engine (which reads waymark10.modules) — this
  namespace requires no module and knows none by name. The one-arity
  call is the CORE-ONLY handler: no seasons door, no presence stream,
  no generic UI, no worksheet round-trip; those addresses 404 exactly
  as an unmounted address should. Every real boot goes through
  engine/handler and gets the modules the engine was assembled with."
  ([eng] (handler eng nil))
  ([eng route-sets]
   (-> (ring/ring-handler
        (ring/router
         (assemble-routes eng route-sets)
         {:conflicts nil})
        not-found-handler)
       (wrap-identity eng)
       wrap-problems)))
