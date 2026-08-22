(ns waymark10.test.packs
  "The conformance packs: what each module owes the suite, as data.

  waymark10.test.conformance is a LIBRARY — pure fns over one parsed
  wire document, each returning a seq of violation strings. Until
  waymark-db9.5 nothing selected from it: four application suites
  hand-wrote the same eight deftests and the framework's own promise
  ('an advertised surface the suite does not invoke should be
  impossible') held for resources and stopped at the module line.
  This namespace closes that. A pack is

      {:module :attachments
       :obligations
       [{:name  :attachments/byte-round-trip
         :needs #{[:kind :attachment] [:route :attachments]}
         :run   (fn [ctx] → seq of violation strings)}]}

  and it rides the module inventory as the `:pack` column
  (waymark10.modules), beside `:enrols` and `:routes`. The driver
  (waymark10.test.suite) runs core's pack plus the packs of every
  ENROLLED module, so disabling a module REMOVES its obligations
  rather than failing them.

  ── :needs, and why the label is not enough ──

  A module can contribute routes without kinds and kinds without
  routes: seasons/realtime/mirror/openapi/ui are routes-only,
  capabilities/dashboard are app-opt-in kinds with no routes at all.
  So an obligation keys off what the module ACTUALLY CONTRIBUTED to
  THIS engine, never off the label:

    [:kind  k] — the assembled registry serves kind k. For an
                 :app-opt-in kind this is the honest test of whether
                 the application's own resources vector opted in.
    [:route m] — module m handed this engine at least one route.
    [:surface h] — hook h is TURNING on this engine right now.

  An obligation whose needs are unmet does not run and is not a
  failure; the driver reports it as skipped, with the unmet need
  named, so a thin report is legible rather than mysterious.

  ── the running surfaces (waymark-db9.8) ──

  The third verb is the one that arrived last, and the obligations
  that use it are claims about a PROCESS: a delivery arrives at a
  third party, a queued job is leased and driven to its report,
  deleted bytes leave the disk on a schedule, a presence entry
  expires when its heartbeats stop, a drawn curtain is honored inside
  its declared bound. None of them starts anything — a pack that
  started its own worker would be the lifecycle seam wearing a test's
  clothes — and none of them sleeps a fixed span either. Each waits
  on the outcome, with a deadline derived from the ENGINE'S OWN
  cadence opt, so an engine started with production intervals waits
  production-long and an engine started for a test finishes in
  milliseconds. Who starts the engine is
  waymark10.test.suite's § 'who starts the engine'.

  Two obligations here are DELIBERATELY not surface-gated, and that
  is the same distinction waymark10.server.seams draws: the jobs
  deferral door is a property of a DECLARATION, not of a process —
  every deferred-bulk call in the tree 202s through an engine nobody
  started — so it is [:kind :job]-gated and runs in the app suites,
  where the wire it proves actually lives.

  ── what is NOT here ──

  The elected jobs ORPHAN sweeper, and live collab's OT. Both are
  recorded at their packs below, and neither is a gap in the seam:
  one is a race the worker in the same process owns, the other has
  no running surface to name.

  ── the shape of an obligation body ──

  `:run` receives the driver's ctx (waymark10.test.suite/context) and
  returns violation strings, or {:violations […] :covered n} when the
  obligation also wants to report HOW MUCH it exercised — the folded
  enums are the case: an app with no acceptance-set guard has nothing
  to check, and zero-checked is honest there and suspicious in
  mealplan10, which is the app's claim to make, not the framework's.

  Nothing here requires waymark10.test.factories, and that is
  deliberate: waymark10.modules requires this namespace (the column
  is a literal, not a lookup), so anything loaded here is loaded by
  every boot. The walking, the staging, the HTTP sugar and the
  throwaway receiver a delivery obligation needs all arrive through
  ctx from the driver, which lives in test scope where
  malli.generator, test.check and an http-kit server belong.

  The module namespaces required below (attachments, curtain, jobs,
  presence) add nothing to that load: waymark10.modules already
  requires every one of them for its own columns. What a pack asks
  of its module is only ever its module's own vocabulary — the
  worker's actor id, the curtain's verdict — never another's."
  (:require [clojure.string :as str]
            [waymark10.machine :as machine]
            [waymark10.server.attachments :as attachments]
            [waymark10.server.curtain :as curtain]
            [waymark10.server.jobs :as jobs]
            [waymark10.server.presence :as presence]
            [waymark10.server.router :as router]
            [waymark10.server.seams :as seams]
            [waymark10.test.conformance :as conf]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── ctx accessors ───────────────────────────────────────────────────
;; The driver builds these; a pack only reads them. Spelled out here
;; so the ctx contract is visible where the obligations are written.

(defn- rdef [ctx kind] ((:rdef ctx) kind))
(defn- json [ctx resp] ((:json ctx) resp))
(defn- req [ctx method uri & [body headers]]
  ((:req ctx) method uri body headers))
(defn- get-env [ctx kind id] ((:get-env ctx) kind id))
(defn- self-of [ctx kind id] ((:self-of ctx) kind id))
(defn- states-with-rows [ctx kind] ((:states-with-rows ctx) kind))
(defn- row-in-state [ctx kind state] ((:row-in-state ctx) kind state))
(defn- invoke-http [ctx kind id aname body & [opts]]
  ((:invoke ctx) kind id aname body opts))
(defn- declared-name [ctx kind wire-kw] ((:declared-name ctx) kind wire-kw))
(defn- input-for
  "A synthesized body for one action on one walked row, with the
  walker's honest skip beside it: {:body … :skip reason-or-nil}. The
  seeds are the ones the four suites used, kept so a staged walk lands
  on the same documents it always did."
  ([ctx kind action-def row] (input-for ctx kind action-def row 3))
  ([ctx kind action-def row seed]
   ((:input-for ctx) kind action-def row seed)))

(defn- action-def [ctx kind aname]
  (some-> (get-in (rdef ctx kind) [:actions aname]) (assoc :name aname)))

(defn- app-kinds [ctx] (:kinds ctx))
(defn- fresh-row [ctx kind state] ((:fresh-row ctx) kind state))
(defn- surface [ctx hook-key] ((:surface ctx) hook-key))
(defn- transitions [ctx kind id] ((:transitions ctx) kind id))
(defn- receiver! [ctx] ((:receiver! ctx)))

(defn- opt
  "One engine opt with its default — a running surface's declared
  cadence, which is what every deadline below is derived from. The
  engine's opts map is the engine's whole public tuning surface
  (server/engine's docstring enumerates it), so reading one is not
  reaching into an internal."
  [ctx k default]
  (get (:engine ctx) k default))

;; ── bounded waiting ─────────────────────────────────────────────────

(defn- await-value
  "Poll `f` every 25ms until it answers something other than nil or
  false, or `timeout-ms` passes; → that value, or nil.

  Every runtime obligation waits this way and none of them sleeps a
  fixed span. A sleep long enough to be reliable on a loaded machine
  is a sleep every green run pays; a sleep short enough to be quick
  is the flake. Waiting on the OUTCOME costs the fast run nothing and
  gives the slow one room, and the deadline it is given is always the
  surface's own declared cadence times a small factor — so a
  timeout's violation string can name the interval that was supposed
  to have fired."
  [timeout-ms f]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 25)
            (recur))))))

(defn- await-gone?
  "The other half: poll until `f` answers falsey — an entry evicted, a
  file purged. True when it went, false when the deadline passed."
  [timeout-ms f]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (cond
        (not (f)) true
        (< deadline (System/currentTimeMillis)) false
        :else (do (Thread/sleep 25) (recur))))))

;; ── the shared collection obligation ────────────────────────────────

(defn- collection-violations
  "One kind's plural door: 200, and a conformant collection envelope.
  The obligation every enrolled kind owes the moment it is enrolled —
  a kind in the registry whose table was never migrated answers 500
  here, and a kind whose plural is shadowed by a module route answers
  the wrong document."
  [ctx kind]
  (let [resp (req ctx :get (str "/api/" (:plural (rdef ctx kind))))
        b (json ctx resp)]
    (if (not= 200 (:status resp))
      [(str (name kind) " collection: GET answered " (:status resp))]
      (conf/collection-envelope-violations b {:kind kind}))))

(defn- kind-collection
  "The enrolled-kind obligation, per module and kind. `:needs` is the
  kind itself, which is exactly right for the three enrollment modes
  at once: :always enrols it, :when-declared may not have, and
  :app-opt-in leaves it to the application's own vector — all three
  reduce to 'is it in the assembled registry'."
  [module kind]
  {:name (keyword (name module) (str (name kind) "-collection"))
   :needs #{[:kind kind]}
   :run (fn [ctx] (collection-violations ctx kind))})

;; ── the shared route obligation ─────────────────────────────────────

(defn- fill-params
  "A route template with every :param filled by a placeholder, so the
  assembled router can be asked what it would match. The values are
  deliberately boring — routing is what is under test, not the
  handlers behind it."
  [template]
  (str/replace template #":([A-Za-z0-9_-]+)"
               (fn [[_ p]] (case p "id" "1" "plural" "xs" "action" "a"
                                 "kind" "k" "name" "n" "x"))))

(defn- routes-mounted-violations
  "Every route this module handed the engine still ANSWERS AT ITS OWN
  ADDRESS in the assembled vector.

  This is db9.3's ordering hazard made checkable per module. The
  router runs {:conflicts nil} and matches linearly, so position IS
  the routing rule: `/api/{plural}/-/worksheet` mounted after
  `/api/{plural}/-/{action}` is not an error, it is a surface that
  silently stopped existing. A module that contributes a route and
  cannot be reached at it has been shadowed, and nothing else in the
  suite would say so."
  [ctx module]
  (let [{:keys [static plural]} (some #(when (= module (:module %)) %)
                                      (:route-sets ctx))
        templates (map first (concat static plural))
        matched (:match-template ctx)]
    (into []
          (keep (fn [template]
                  (let [path (fill-params template)
                        hit (matched path)]
                    (when (not= template hit)
                      (str (name module) ": " template " is mounted but "
                           path " matches " (pr-str hit)
                           " — the route is shadowed by position, which is"
                           " the router's only ordering rule")))))
          templates)))

(defn- routes-mounted [module]
  {:name (keyword (name module) "routes-mounted")
   :needs #{[:route module]}
   :run (fn [ctx] (routes-mounted-violations ctx module))})

;; ── core: the eight the applications used to hand-copy ──────────────

(defn- staging-violations
  "Every reachable state of every application kind has a walked row.
  Not a promise about the framework so much as the PRECONDITION of
  every promise below it: an unreachable state is an unchecked state,
  and the walker's skip reason names the action and the registration
  that fixes it."
  [ctx]
  (into []
        (mapcat
         (fn [kind]
           (for [state (sort (machine/reachable-states (rdef ctx kind)))
                 :when (nil? (row-in-state ctx kind state))]
             (str (name kind) " → " (name state) " skipped: "
                  ((:skip-of ctx) kind state)))))
        (app-kinds ctx)))

(defn- envelope-shape-violations
  "The envelope on the wire: 200, the waymark media type, the reserved
  keys, an ETag that agrees with the body, and a version that does not
  move across an idle round-trip."
  [ctx]
  (into []
        (mapcat
         (fn [kind]
           (mapcat
            (fn [[state row]]
              (let [resp (get-env ctx kind (:id row))
                    env (json ctx resp)
                    where (str (name kind) "@" (name state))]
                (concat
                 (when (not= 200 (:status resp))
                   [(str where ": GET returned " (:status resp))])
                 (when (not= router/media-type ((:ctype ctx) resp))
                   [(str where ": Content-Type " ((:ctype ctx) resp))])
                 (conf/envelope-violations
                  env {:kind kind :state state
                       :etag-header (get-in resp [:headers "ETag"])
                       :law? true})
                 (let [again (json ctx (req ctx :get (:self env)))]
                   (when (not= (get-in env [:meta :version])
                               (get-in again [:meta :version]))
                     [(str where ": version changed across an idle "
                           "round-trip")])))))
            (states-with-rows ctx kind))))
        (app-kinds ctx)))

(defn- affordance-violations
  "Affordance completeness for two principals, plus the concealment
  half on the wire: an action render CONCEALED (a hide-flagged deny)
  must 404 when invoked by hand, or concealment was decoration."
  [ctx]
  (into []
        (mapcat
         (fn [kind]
           (mapcat
            (fn [[state row]]
              (mapcat
               (fn [[pname headers]]
                 (let [env (json ctx (req ctx :get (self-of ctx kind (:id row))
                                          nil headers))
                       where (str (name kind) "@" (name state)
                                  " as " (name pname))]
                   (concat
                    (conf/affordance-violations (rdef ctx kind) env)
                    (for [aname (conf/hidden-actions (rdef ctx kind) env)
                          :let [resp (req ctx :post
                                          (str (self-of ctx kind (:id row))
                                               "/-/" (name aname))
                                          nil headers)]
                          :when (not= 404 (:status resp))]
                      (str where ": hidden " (name aname) " answered "
                           (:status resp) ", expected 404")))))
               {:anonymous {} :walker (:walker-headers ctx)}))
            (states-with-rows ctx kind))))
        (app-kinds ctx)))

(defn- unavailable-honesty-violations
  "Advertisement equals enforcement: every narrated unavailable
  action, invoked for real, refuses 409 with the NARRATED SENTENCE as
  its detail. Reports :covered so the driver can say a suite whose
  staging never produced a single advertised refusal proved nothing
  here — which is a violation, not a shrug: all four application
  suites asserted it by hand."
  [ctx]
  (let [checked (volatile! 0)
        vs (into []
                 (mapcat
                  (fn [kind]
                    (mapcat
                     (fn [[state row]]
                       (let [env (json ctx (get-env ctx kind (:id row)))
                             where (str (name kind) "@" (name state))]
                         (concat
                          (conf/unavailable-violations env)
                          (mapcat
                           (fn [[wname entry]]
                             (let [aname (declared-name ctx kind wname)
                                   a (action-def ctx kind aname)
                                   {:keys [body skip]} (input-for ctx kind a row)]
                               (when-not skip
                                 (let [resp (invoke-http ctx kind (:id row)
                                                         aname body)
                                       b (json ctx resp)]
                                   (cond
                                     (= 200 (:status resp))
                                     (when (not= (name (:to a)) (:state b))
                                       [(str where "." (name aname)
                                             ": advertised unavailable but "
                                             "invoked 200 into " (:state b))])

                                     (not= 409 (:status resp))
                                     [(str where "." (name aname)
                                           ": advertised unavailable but "
                                           "invoking answered " (:status resp))]

                                     :else
                                     (do
                                       (vswap! checked inc)
                                       (concat
                                        (when (not= (:reason entry) (:detail b))
                                          [(str where "." (name aname)
                                                ": advertisement "
                                                (pr-str (:reason entry))
                                                " ≠ enforcement "
                                                (pr-str (:detail b)))])
                                        (when (str/includes? (str (:detail b)) "{")
                                          [(str where "." (name aname)
                                                ": problem detail holds an "
                                                "unresolved {placeholder}")]))))))))
                           (:unavailable env)))))
                     (states-with-rows ctx kind))))
                 (app-kinds ctx))]
    {:covered @checked
     :violations (cond-> vs
                   (and (seq (app-kinds ctx)) (zero? @checked))
                   (conj (str "no advertised refusal was enforced over the "
                              "wire — the staging never reached a narrated "
                              "unavailable, so advertisement=enforcement went "
                              "unproven")))}))

(defn- prose-violations [ctx]
  (into []
        (mapcat
         (fn [kind]
           (mapcat (fn [[_state row]]
                     (conf/prose-violations
                      (rdef ctx kind) (json ctx (get-env ctx kind (:id row)))))
                   (states-with-rows ctx kind))))
        (app-kinds ctx)))

(defn- input-schema-violations [ctx]
  (into []
        (mapcat
         (fn [kind]
           (mapcat (fn [[_state row]]
                     (conf/input-schema-violations
                      (json ctx (get-env ctx kind (:id row)))))
                   (states-with-rows ctx kind))))
        (app-kinds ctx)))

(defn- folded-enum-violations
  "Every acceptance-folded enum member the envelope advertises passes
  a dry run. An app with no acceptance-set guard advertises no enum
  and legitimately checks nothing, so this reports :covered and lets
  the application assert on it — mealplan10 does, the other three
  said in a comment that they had nothing to assert."
  [ctx]
  (let [checked (volatile! 0)
        vs (into []
                 (mapcat
                  (fn [kind]
                    (mapcat
                     (fn [[state row]]
                       (let [env (json ctx (get-env ctx kind (:id row)))
                             where (str (name kind) "@" (name state))]
                         (mapcat
                          (fn [{:keys [action field enum]}]
                            (let [aname (declared-name ctx kind action)
                                  a (action-def ctx kind aname)
                                  {:keys [body]} (input-for ctx kind a row 5)]
                              (when body
                                (for [member (take 5 enum)
                                      :let [_ (vswap! checked inc)
                                            resp (invoke-http
                                                  ctx kind (:id row) aname
                                                  (assoc body field member)
                                                  {:query "dry_run=1"})]
                                      :when (= 422 (:status resp))]
                                  (str where "." (name aname)
                                       ": advertised enum member "
                                       (pr-str member) " for " (name field)
                                       " was refused 422: " (:body resp))))))
                          (conf/folded-enums env))))
                     (states-with-rows ctx kind))))
                 (app-kinds ctx))]
    {:covered @checked :violations vs}))

(defn- collection-honesty-violations
  "The collection door for every application kind, plus the row
  affordances it advertises: an action offered on a list row that
  404s on that row's own address is a lie the grid would tell."
  [ctx]
  (into []
        (mapcat
         (fn [kind]
           (row-in-state ctx kind (:initial (rdef ctx kind)))
           (let [resp (req ctx :get (str "/api/" (:plural (rdef ctx kind))))
                 b (json ctx resp)]
             (concat
              (when (not= 200 (:status resp))
                [(str (name kind) " collection: GET " (:status resp))])
              (conf/collection-envelope-violations b {:kind kind})
              (mapcat
               (fn [item]
                 (for [wname (keys (:actions item))
                       :let [aname (declared-name ctx kind wname)
                             id (last (str/split (:self item) #"/"))
                             r (invoke-http ctx kind id aname nil
                                            {:query "dry_run=1"})]
                       :when (= 404 (:status r))]
                   (str (:self item) ": advertised " (name wname)
                        " answered 404 on its own row")))
               (take 3 (get-in b [:data :items])))))))
        (app-kinds ctx)))

(defn- replay-history-violations
  "Every logged edge is an edge ITS OWN stored revision declares."
  [ctx]
  (doseq [kind (app-kinds ctx)]
    (row-in-state ctx kind (:initial (rdef ctx kind))))
  (mapv pr-str (conf/replay-violations (:engine ctx))))

(defn- touches-violations
  "The blast radius declared is the blast radius logged. No
  application suite ever called this one — it had no driver to be
  selected by, which is precisely the drift this bead exists to make
  impossible."
  [ctx]
  (mapv pr-str (conf/touches-violations (:engine ctx))))

(def core
  "Core's pack: the obligations every waymark engine owes for its own
  resources, whatever modules ride along. These are the eight deftests
  the four application suites hand-copied — now written once, named,
  and selected by the same mechanism as every module's."
  {:module :core
   :obligations
   [{:name :core/staging :run staging-violations}
    {:name :core/envelope-shape :run envelope-shape-violations}
    {:name :core/affordances :run affordance-violations}
    {:name :core/unavailable-honesty :run unavailable-honesty-violations}
    {:name :core/token-prose :run prose-violations}
    {:name :core/input-schemas :run input-schema-violations}
    {:name :core/folded-enums :run folded-enum-violations}
    {:name :core/collections :run collection-honesty-violations}
    {:name :core/replay-history :run replay-history-violations}
    {:name :core/touches :run touches-violations}
    ;; the law's own vocabulary answers its own doors: member, role,
    ;; grant and approval_request are core kinds because core guards
    ;; mint tokens naming them (guards/role, guards/owner), so an
    ;; engine that serves them badly has broken authorization, not a
    ;; module.
    (kind-collection :core :definition)
    (kind-collection :core :member)
    (kind-collection :core :role)
    (kind-collection :core :grant)
    (kind-collection :core :approval_request)]})

;; ── the module packs ────────────────────────────────────────────────

(defn- attachment-round-trip
  "The bytes obligation: mint an attachment through the plural door,
  PUT bytes at the module's own address, GET them back. The module
  owns a kind AND a route and this is the one obligation that needs
  both, which is why its :needs names both."
  [ctx]
  (let [made (req ctx :post "/api/attachments"
                  {:name "conformance.txt" :media_type "text/plain"})]
    (if (not= 201 (:status made))
      [(str "attachments: POST /api/attachments answered "
            (:status made) ", not 201")]
      (let [aid (last (str/split (:self (json ctx made)) #"/"))
            sent "the bytes that came back are the bytes sent"
            put (req ctx :put (str "/api/attachments/" aid "/bytes") sent)
            got (req ctx :get (str "/api/attachments/" aid "/bytes"))]
        (conf/attachment-roundtrip-violations
         {:sent (.getBytes sent "UTF-8")
          :put-status (:status put)
          :put-env (json ctx put)
          :get-status (:status got)
          :get-ctype ((:ctype ctx) got)
          :got (.getBytes (str ((:text ctx) got)) "UTF-8")
          :media-type "text/plain"})))))

(defn- purge-sweep-violations
  "The purge sweeper, watched rather than called: bytes that landed
  through the module's own door leave the disk after the row is
  deleted, on the schedule the engine declared.

  The disk is the ONLY observable here and that is the point — the
  metadata row survives the purge (it is the audited record) and a
  deleted attachment's bytes 404 whether the file is there or not, so
  a wire-only obligation would pass against a sweeper that never ran.
  attachments/stored-bytes? exists for this sentence."
  [ctx]
  (let [made (req ctx :post "/api/attachments"
                  {:name "conformance-purge.txt" :media_type "text/plain"})]
    (if (not= 201 (:status made))
      [(str "attachments: POST /api/attachments answered "
            (:status made) ", not 201")]
      (let [aid (last (str/split (:self (json ctx made)) #"/"))
            eng (:engine ctx)
            interval (long (opt ctx :purge-sweep-ms 60000))
            bound (+ 15000 (* 4 interval))]
        (req ctx :put (str "/api/attachments/" aid "/bytes")
             "the bytes a purge is owed")
        (if-not (attachments/stored-bytes? eng aid)
          [(str "attachments: " aid " has no bytes on disk after its PUT —"
                " the purge obligation has nothing to watch vanish")]
          (let [del (invoke-http ctx :attachment aid :delete nil)]
            (concat
             (when (not= 200 (:status del))
               [(str "attachments: delete answered " (:status del)
                     ", not 200: " (:body del))])
             (when-not (await-gone? bound #(attachments/stored-bytes? eng aid))
               [(str "attachments: the deleted attachment's bytes were still"
                     " on disk " bound "ms later — the purge sweeper is"
                     " running and its interval is " interval "ms")]))))))))

(def attachments
  {:module :attachments
   :obligations
   [(routes-mounted :attachments)
    (kind-collection :attachments :attachment)
    {:name :attachments/byte-round-trip
     :needs #{[:kind :attachment] [:route :attachments]}
     :run attachment-round-trip}
    ;; the running half (waymark-db9.8): deleted bytes vanish on a
    ;; SCHEDULE, so the obligation needs the elected sweeper to be
    ;; turning — and needs the kind and the route too, because it
    ;; puts real bytes through the module's own door first.
    {:name :attachments/purge-sweep
     :needs #{[:kind :attachment] [:route :attachments]
              [:surface :attachments-purge]}
     :run purge-sweep-violations}]})

;; ── webhooks ────────────────────────────────────────────────────────

(defn- eventful-kind
  "An application kind the walker can actually create — the event
  source a delivery obligation needs. Reads the STAGED row (memoized
  by the driver, and core's pack has already walked it), so choosing
  the kind costs nothing and choosing a kind whose create is refused
  is impossible."
  [ctx]
  (first (filter #(row-in-state ctx % (:initial (rdef ctx %)))
                 (app-kinds ctx))))

(defn- delivery-receipt-violations
  "The deliverer's whole promise in one obligation: a subscription
  created over the wire hears a NEW event at a real endpoint, and a
  revoked one never hears another.

  Both halves need a third party, which is why the driver hands a
  throwaway receiver through ctx (waymark10.test.suite): a webhook
  that is not delivered over HTTP to somebody else's socket is not a
  webhook. The wait is bounded by the deliverer's own poll interval
  — it wakes on the dispatcher and polls as a backstop — and the
  revoked half waits the same span for a delivery that must NOT
  arrive."
  [ctx]
  (if-some [kind (eventful-kind ctx)]
    (let [rcv (receiver! ctx)]
      (try
        (let [made (req ctx :post "/api/subscriptions"
                        {:url (:url rcv)
                         :kinds [(name kind)]
                         :description "waymark10 conformance"})
              sub (json ctx made)
              sub-id (last (str/split (str (:self sub)) #"/"))
              poll-ms (long (opt ctx :webhooks-poll-ms 2000))
              bound (+ 15000 (* 5 poll-ms))]
          (if (not= 201 (:status made))
            {:covered 0
             :violations [(str "webhooks: POST /api/subscriptions answered "
                               (:status made) ", not 201: " (:body made))]}
            (let [_ (fresh-row ctx kind (:initial (rdef ctx kind)))
                  hit (await-value bound #(first @(:hits rcv)))
                  body (some-> hit :body wire/read-json)
                  revoked (invoke-http ctx :subscription sub-id :revoke nil)
                  heard (count @(:hits rcv))]
              {:covered 1
               :violations
               (vec
                (concat
                 (if (nil? hit)
                   [(str "webhooks: a fresh " (name kind) " reached no"
                         " subscribed endpoint within " bound "ms — the"
                         " deliverer is running and polls every "
                         poll-ms "ms")]
                   (concat
                    (when (not= (name kind) (str (:kind body)))
                      [(str "webhooks: the delivered body names kind "
                            (pr-str (:kind body)) ", not the subscribed "
                            (name kind))])
                    (when (nil? (:action body))
                      [(str "webhooks: the delivered body carries no :action"
                            " — it is not a transition frame: "
                            (pr-str body))])
                    (when (nil? (:self body))
                      [(str "webhooks: the delivered body names no :self —"
                            " the receiver cannot follow it back")])))
                 (if (not= 200 (:status revoked))
                   [(str "webhooks: revoke by the subscription's own owner"
                         " answered " (:status revoked) ", not 200: "
                         (:body revoked))]
                   (when (not= "revoked" (str (:state (json ctx revoked))))
                     [(str "webhooks: revoke left the subscription in "
                           (pr-str (:state (json ctx revoked))))]))
                 (let [_ (fresh-row ctx kind (:initial (rdef ctx kind)))]
                   (when (await-value (+ 2000 (* 2 poll-ms))
                                      #(when (< heard (count @(:hits rcv)))
                                         true))
                     [(str "webhooks: a REVOKED subscription heard another"
                           " event — revoked is terminal, and the endpoint"
                           " was promised silence")]))))})))
        (finally ((:stop! rcv)))))
    {:covered 0 :violations []}))

(def webhooks
  {:module :webhooks
   :obligations
   [(kind-collection :webhooks :subscription)
    {:name :webhooks/delivery-receipt
     :needs #{[:kind :subscription] [:surface :webhooks-deliverer]}
     :run delivery-receipt-violations}]})

;; ── jobs ────────────────────────────────────────────────────────────

(defn- deferring-doors
  "Every [kind action threshold] an application kind declares a
  deferring bulk door on (`:bulk {:defer-over n}`). A pack has no
  resources vector of its own, so what it can prove about deferral is
  exactly what the application declared — and an application that
  declares none is told so by :covered rather than by a green run."
  [ctx]
  (into []
        (mapcat (fn [kind]
                  (keep (fn [a]
                          (let [b (:bulk a)]
                            (when (and (map? b) (:defer-over b))
                              [kind (:name a) (long (:defer-over b))])))
                        (machine/actions-seq (rdef ctx kind)))))
        (app-kinds ctx)))

(defn- defer!
  "Push one bulk call past its threshold. The ids name rows that do
  not exist, deliberately: `invoke/bulk!` answers the deferral marker
  the moment the count clears the threshold and looks at no id at
  all, so the door costs no staging — and the worker obligation gets
  a job whose every item refuses honestly, which still exercises the
  claim, the batches and the report."
  [ctx kind aname threshold]
  (req ctx :post (str "/api/" (:plural (rdef ctx kind)) "/-/" (name aname))
       {:ids (mapv #(str "waymark10-conformance-absent-" %)
                   (range (inc threshold)))}
       (merge (:walker-headers ctx)
              {"idempotency-key" (str (random-uuid))})))

(defn- deferral-door-violations
  "The 202 path, with no worker anywhere: a bulk call over its
  declared `:defer-over` answers 202 with the job envelope and a
  Location to watch it at.

  NOT surface-gated, and that is the point (waymark-db9.7): deferral
  is a property of what this engine ENROLLED, never of what it
  started. Every deferred-bulk call in this tree 202s through a bare
  handler nobody called start! on, so an obligation that waited for a
  running worker would have proved the door in exactly none of the
  suites that use it."
  [ctx]
  (let [doors (deferring-doors ctx)]
    {:covered (count doors)
     :violations
     (into []
           (mapcat
            (fn [[kind aname threshold]]
              (let [resp (defer! ctx kind aname threshold)
                    b (json ctx resp)
                    where (str (name kind) "." (name aname))]
                (if (not= 202 (:status resp))
                  [(str where ": a bulk call of " (inc threshold) " ids over"
                        " its declared :defer-over " threshold " answered "
                        (:status resp) ", not the 202 the deferral door"
                        " owes it: " (:body resp))]
                  (concat
                   (when (not= "job" (str (:kind b)))
                     [(str where ": the 202 envelope names kind "
                           (pr-str (:kind b)) ", not the job kind the"
                           " module enrolled")])
                   (when-not (str/starts-with?
                              (str (get-in resp [:headers "Location"]))
                              (str "/api/" (:plural (rdef ctx :job)) "/"))
                     [(str where ": the 202 carries Location "
                           (pr-str (get-in resp [:headers "Location"]))
                           " — there is nowhere to watch the job")]))))))
           doors)}))

(defn- deferral-seam-violations
  "The `:job` rdef still CARRIES the mint core knocks on.

  A scar, not a formality (waymark-db9.7). The door lives in the
  rdef's METADATA — `declaration/top-level-keys` is closed, so the
  jobs module stamps `seams/with-deferral` on its own declaration —
  and metadata is exactly what a rebuild drops silently. A future
  law-refresh path that reassembled rdefs without re-running the
  module's `:kinds` fn would leave a registry that looks complete,
  serves the job kind, renders the 202's envelope, and answers 503
  to every deferral. This obligation is one deref, and it is the only
  thing in the suite that would notice."
  [ctx]
  (when (nil? (seams/deferral (rdef ctx :job)))
    [(str "the assembled :job rdef carries no seams/deferral door —"
          " the kind is enrolled but core's bulk grammar has nothing"
          " to defer to, and every over-threshold call would 503")]))

(defn- worker-progress-violations
  "The worker, watched: a job it never was told about goes from
  :queued to :completed on its own, its progress accounts for every
  item, and the edge that started it was walked by the WORKER'S
  actor.

  That last check is the lease made visible. `:start` is worker-only
  and hidden — invoked by hand over the wire it 404s — so a queued
  job that reached :running did so through a claimed lease and
  nothing else in this process could have moved it. The batches are
  the progress: `:jobs-batch-size` items at a time, the lease renewed
  between them, and `done` = `total` at the end whatever each item
  answered."
  [ctx]
  (if-some [[kind aname threshold] (first (deferring-doors ctx))]
    (let [resp (defer! ctx kind aname threshold)
          env (json ctx resp)
          total (inc threshold)
          poll-ms (long (opt ctx :jobs-poll-ms 1000))
          bound (+ 20000 (* 20 poll-ms))]
      (if (not= 202 (:status resp))
        {:covered 0
         :violations [(str "jobs: the deferred call answered "
                           (:status resp) ", not 202 — there is no job"
                           " to watch: " (:body resp))]}
        (let [job-self (str (:self env))
              job-id (last (str/split job-self #"/"))
              done (await-value
                    bound
                    (fn []
                      (let [e (json ctx (req ctx :get job-self))]
                        (when (= "completed" (str (:state e))) e))))
              report (get-in done [:data :report])
              started (first (filter #(= :start (:action %))
                                     (transitions ctx :job job-id)))]
          {:covered 1
           :violations
           (vec
            (if (nil? done)
              [(str "jobs: " job-self " never reached :completed within "
                    bound "ms — the worker is running and polls every "
                    poll-ms "ms; it is at "
                    (pr-str (:state (json ctx (req ctx :get job-self)))))]
              (concat
               (when (not= total (get-in done [:data :progress :done]))
                 [(str "jobs: the completed job's progress is "
                       (pr-str (get-in done [:data :progress]))
                       " — every one of its " total " items should be"
                       " accounted for")])
               (when (nil? report)
                 [(str "jobs: the completed job persisted no :report —"
                       " the artifact is the worker's last act before"
                       " :complete fires")])
               (when (and report (not= total (:total report)))
                 [(str "jobs: the report totals " (pr-str (:total report))
                       " of " total " items")])
               (when (and report
                          (not= total (+ (long (:succeeded report 0))
                                         (long (:refused report 0))
                                         (long (:failed report 0)))))
                 [(str "jobs: the report's outcomes do not add up to its"
                       " own total: " (pr-str report))])
               (when (nil? started)
                 [(str "jobs: the completed job logged no :start edge —"
                       " nothing claimed a lease on it")])
               (when (and started
                          (not= (:id jobs/worker-actor)
                                (get-in started [:actor :id])))
                 [(str "jobs: the :start edge was walked by "
                       (pr-str (get-in started [:actor :id]))
                       ", not the worker actor "
                       (pr-str (:id jobs/worker-actor)))]))))})))
    {:covered 0 :violations []}))

(def jobs
  {:module :jobs
   :obligations
   [(kind-collection :jobs :job)
    {:name :jobs/deferral-door
     :needs #{[:kind :job]}
     :run deferral-door-violations}
    {:name :jobs/deferral-seam
     :needs #{[:kind :job]}
     :run deferral-seam-violations}
    {:name :jobs/worker-progress
     :needs #{[:kind :job] [:surface :jobs-worker]}
     :run worker-progress-violations}]
   ;; NOT here, and recorded rather than pending: the elected ORPHAN
   ;; sweeper. Its promise — a dead claimant's :running job returns to
   ;; the queue — is only observable on a job no live worker will
   ;; steal first, and `run-once!` claims every queued AND running job
   ;; it can see. In production the two live in different processes
   ;; (the sweeper is elected, the worker deliberately is not); in one
   ;; started engine they race, and an obligation that owned the race
   ;; would be tuning dressed as a proof. batch_f_jobs_test drives the
   ;; sweep directly and under real election, in a process with no
   ;; worker in it — see waymark-db9.9.
   })

(def worksheet
  {:module :worksheet
   :obligations [(routes-mounted :worksheet)
                 (kind-collection :worksheet :worksheet)]})

(def capabilities
  {:module :capabilities
   :obligations [(kind-collection :capabilities :capability)]})

(def dashboard
  {:module :dashboard
   :obligations [(kind-collection :dashboard :saved_view)
                 (kind-collection :dashboard :dashboard)
                 (kind-collection :dashboard :dashboard_slot)]})

(def seasons  {:module :seasons  :obligations [(routes-mounted :seasons)]})
(def mirror   {:module :mirror   :obligations [(routes-mounted :mirror)]})
(def openapi  {:module :openapi  :obligations [(routes-mounted :openapi)]})
(def ui       {:module :ui       :obligations [(routes-mounted :ui)]})

;; ── realtime ────────────────────────────────────────────────────────

(defn- presence-ttl-violations
  "Presence is EPHEMERAL, and this is the sentence that means it: a
  heartbeat reaches the board, and then — with nobody beating again —
  three missed intervals take it off, on the registry's own clock.

  The board is read from the running registry's own snapshot rather
  than from the SSE stream: what is under test is the sweep, not the
  wire format, and a stream would need a reader thread to hold the
  connection open for the whole eviction window. The reporter is a
  principal of this obligation's own, never the walker, because the
  walker is GETting rows all through this suite and every one of
  those reads is itself a heartbeat (presence/read!) — an entry that
  keeps re-reporting can never be watched to expire."
  [ctx]
  (let [reg (surface ctx :presence)
        pid "waymark10-conformance-gazer"
        headers {"x-waymark-principal" pid "x-waymark-actor-type" "human"}
        hb (long (opt ctx :presence-heartbeat-ms 15000))
        on-board? (fn []
                    (some #(= pid (get-in % [:principal :id]))
                          (presence/snapshot reg (constantly true))))
        beat (req ctx :post "/api/-/presence" {:self "/api/members"} headers)]
    (if (not= 204 (:status beat))
      [(str "realtime: POST /api/-/presence answered " (:status beat)
            ", not the 204 the door owes any named principal: "
            (:body beat))]
      (concat
       (when-not (await-value (+ 5000 hb) on-board?)
         [(str "realtime: a heartbeat for " pid " never reached the"
               " presence board — the registry is running and the beat"
               " was accepted")])
       (when-not (await-gone? (+ 5000 (* 6 hb)) on-board?)
         [(str "realtime: " pid " was still on the board " (* 6 hb)
               "ms after its last heartbeat — three missed beats at "
               hb "ms evict, and the sweep runs on the same clock")])))))

(defn- curtain-verdict-violations
  "The curtain's verdict is BOUND: a draw committed over the wire is
  honored by the shared cache within its declared TTL, and so is the
  reopening.

  Two things ride on this one wait. The invalidation wire (the
  curtain's consumer on the events dispatcher) should make it
  immediate; the TTL is the backstop that holds when the wire is
  lost. Waiting the TTL out proves the BOUND — the promise the
  engine's :curtain-ttl-ms actually makes — without pretending to
  know which of the two delivered it, which is the honest claim for
  a suite that cannot cut a wire.

  The member is minted here and curtained by its own hand: the
  own-hand guard refuses system actors first, whatever roles they
  carry, so the walker cannot draw this curtain and no shortcut
  around the real door exists."
  [ctx]
  (let [cur (surface ctx :curtain)
        made (req ctx :post "/api/members"
                  {:display "Conformance Curtain" :actor_type "human"})]
    (if (not= 201 (:status made))
      [(str "realtime: POST /api/members answered " (:status made)
            ", not 201 — the curtain obligation has nobody to draw: "
            (:body made))]
      (let [mid (last (str/split (str (:self (json ctx made))) #"/"))
            headers {"x-waymark-principal" mid "x-waymark-actor-type" "human"}
            ttl (long (opt ctx :curtain-ttl-ms 2000))
            bound (+ 5000 (* 3 ttl))
            warm (curtain/curtained? cur mid)
            drew (invoke-http ctx :member mid :draw_curtain nil
                              {:headers headers})]
        (concat
         (when warm
           [(str "realtime: a member born with no curtain reads as"
                 " curtained — the lookup fails closed and every board"
                 " in this engine is silent")])
         (if (not= 200 (:status drew))
           [(str "realtime: draw_curtain by the member's own hand answered "
                 (:status drew) ", not 200: " (:body drew))]
           (concat
            (when-not (await-value bound #(curtain/curtained? cur mid))
              [(str "realtime: a committed draw was not honored within "
                    bound "ms — the invalidation wire should be immediate"
                    " and the " ttl "ms cache is the backstop")])
            (let [opened (invoke-http ctx :member mid :open_curtain nil
                                      {:headers headers})]
              (concat
               (when (not= 200 (:status opened))
                 [(str "realtime: open_curtain answered "
                       (:status opened) ", not 200: " (:body opened))])
               (when-not (await-gone? bound #(curtain/curtained? cur mid))
                 [(str "realtime: a committed OPEN was not honored within "
                       bound "ms — a curtain that cannot be reopened is a"
                       " one-way door, and the same bound governs both"
                       " directions")]))))))))))

(def realtime
  {:module :realtime
   :obligations
   [(routes-mounted :realtime)
    {:name :realtime/presence-ttl
     :needs #{[:surface :presence]}
     :run presence-ttl-violations}
    {:name :realtime/curtain-verdict-bound
     :needs #{[:kind :member] [:surface :curtain]}
     :run curtain-verdict-violations}]
   ;; NOT here, and it is not a pending: live collab's OT. Collab has
   ;; no lifecycle hook at all — its rooms are an atom on the engine
   ;; and its door is a websocket over a DRAFT row, which is core —
   ;; so there is no [:surface k] to gate an obligation on, and a
   ;; conformance driver that spoke websockets would be a second
   ;; client library. batch_d_ot_test and batch_d_collab_test hold it,
   ;; and the seam says why they must: a running surface is a fact
   ;; about a process, and collab is not one.
   })
