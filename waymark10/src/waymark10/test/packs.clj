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

  The module namespaces required below (attachments, curtain, feed,
  jobs, presence) add nothing to that load: waymark10.modules already
  requires every one of them for its own columns (the feed through
  its routes namespace, which is the same load). What a pack asks
  of its module is only ever its module's own vocabulary — the
  worker's actor id, the curtain's verdict — never another's."
  (:require [clojure.string :as str]
            [waymark10.demand :as demand]
            [waymark10.machine :as machine]
            [waymark10.scenario :as scenario]
            [waymark10.schema :as schema]
            [waymark10.server.attachments :as attachments]
            [waymark10.server.capabilities :as cap]
            [waymark10.server.curtain :as curtain]
            [waymark10.server.feed :as feed]
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

(defn- create-body
  "The body the walk itself would send at one kind's create door —
  the driver's own generator, as data (waymark-jfv.4)."
  [ctx kind seed]
  ((:create-body ctx) kind seed))

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

(defn- decision-record-violations
  "Why it was ALLOWED, on the record (spec-decision-record): a
  retained kind's committed transitions name the guards that judged
  them and the verdict each returned, a kind that declares no
  retention records nothing, and the stored names agree with the
  basis DERIVED from the row's law revision.

  Runs over the whole log the walk just wrote, like replay and
  touches — the two halves of the same audit, and the reason this one
  needs no staging of its own: whatever the driver drove, it drove
  through the write path under judgment."
  [ctx]
  (let [{:keys [violations covered]}
        (conf/decision-record-violations (:engine ctx))]
    {:violations (mapv pr-str violations) :covered covered}))

(defn- history-doc-violations
  "One row's /-/history, graded against its own promises
  (waymark-442.4, docs/spec-time-travel.md tiers 1-2): the log is
  served newest first, every transition says which of the two answers
  it is giving, and the basis says how the law of the day reached it.

  The retention agreement is the load-bearing line. `evidence` may say
  `recorded` only for a kind that declared `:retain {:judgment true}`,
  and a kind that declared none may never say anything but
  `not_retained` — the default-off posture proved from the wire rather
  than from the column."
  [ctx kind]
  (let [rd (rdef ctx kind)
        row (row-in-state ctx kind (:initial rd))]
    (when row
      (let [retains? (boolean (get-in rd [:retain :judgment]))
            self (self-of ctx kind (:id row))
            resp (req ctx :get (str self "/-/history"))
            d (:data (json ctx resp))
            ts (vec (:transitions d))
            ats (mapv #(str (:at %)) ts)]
        (if (not= 200 (:status resp))
          [(str "history: GET " self "/-/history answered " (:status resp)
                ", not 200")]
          (into []
                (remove nil?)
                (concat
                 [(when-not (seq ts)
                    (str "history: " self " has been written to and its log"
                         " is empty — every write in this engine is logged"))
                  (when-not (seq (:notes d))
                    (str "history: " self " reported no notes — the derived"
                         " basis, the retention posture and the tier-3 punt"
                         " are what keep this document from being read as"
                         " the whole past"))
                  (when (not= (count ts) (:scanned d))
                    (str "history: " self " scanned " (pr-str (:scanned d))
                         " but served " (count ts) " transitions"))
                  (when (not= ats (vec (reverse (sort ats))))
                    (str "history: " self " served its transitions out of"
                         " order — the log reads newest first"))]
                 (map (fn [t]
                        (let [ev (str (:evidence t))
                              law (str (get-in t [:basis :law]))]
                          (cond
                            (not (contains? #{"recorded" "before_the_record"
                                              "not_retained"} ev))
                            (str "history: a transition of " self " reports"
                                 " evidence " (pr-str ev) " — the tiers are"
                                 " recorded, before_the_record and"
                                 " not_retained")

                            (and (= "recorded" ev) (not retains?))
                            (str "history: " self " reports recorded evidence"
                                 " for " (pr-str (:action t)) " on a kind that"
                                 " declares no :retain {:judgment true} —"
                                 " retention is off by default and paid for"
                                 " by declaration")

                            (and (not retains?) (not= "not_retained" ev))
                            (str "history: " self " reports evidence "
                                 (pr-str ev) " on a kind that retains nothing")

                            (and (= "recorded" ev) (nil? (:judgment t)))
                            (str "history: " self " reports recorded evidence"
                                 " for " (pr-str (:action t)) " and carries no"
                                 " judgment object")

                            (and (some? (:basis t))
                                 (not (contains? #{"stored" "resident" "engine"
                                                   "unrecoverable"} law)))
                            (str "history: a transition of " self " reports"
                                 " law " (pr-str law) " — the tiers are"
                                 " stored, resident, engine and unrecoverable")

                            (and (some? (:basis t))
                                 (not= (:law_revision t)
                                       (get-in t [:basis :revision])))
                            (str "history: a transition of " self " is stamped"
                                 " revision " (pr-str (:law_revision t))
                                 " and its basis answers for "
                                 (pr-str (get-in t [:basis :revision]))))))
                      ts))))))))

(defn- history-violations
  "The transition log, read as history on every kind this engine
  serves. Core, not a module: the log is core storage, and tier 1's
  other half is a query parameter on the core row read that no module
  could have contributed."
  [ctx]
  (into [] (mapcat #(history-doc-violations ctx %)) (app-kinds ctx)))

(defn- touches-violations
  "The blast radius declared is the blast radius logged. No
  application suite ever called this one — it had no driver to be
  selected by, which is precisely the drift this bead exists to make
  impossible."
  [ctx]
  (mapv pr-str (conf/touches-violations (:engine ctx))))

;; ── the declared policy tests (waymark-442.2) ───────────────────────

(defn- scenario-headers
  "The scenario's declared principal, as the wire spells it. Roles
  ride the CSV header the router already reads, so a scenario about a
  role-gated door needs no member row staged for it."
  [s]
  (let [p (:as s)]
    (cond-> {"x-waymark-principal" (str (:id p))
             "x-waymark-actor-type" (name (:type p))}
      (seq (:roles p))
      (assoc "x-waymark-roles" (str/join "," (sort (:roles p)))))))

(defn- token->keyword
  "One affordance token back off the wire: \"approval_request.create\"
  → :approval_request/create (problems/wire-value renders a
  namespaced keyword with a dot)."
  [tok]
  (let [s (str tok)
        i (.indexOf s ".")]
    (if (neg? i) (keyword s) (keyword (subs s 0 i) (subs s (inc i))))))

(defn- stage-declared-row
  "One row a scenario WROTE DOWN, made real: created through its own
  plural door as the walker, then driven along the machine's shortest
  path to the state the scenario named. → {:id …} or {:error …}.

  The walker stages; the scenario's own principal only ever ATTEMPTS.
  Staging as the declared principal would prove a different sentence
  (that this person may create the setup), and a scenario that wants
  to say that says it as its own attempt."
  [ctx kind state data]
  (if-some [rd (rdef ctx kind)]
    (let [made (req ctx :post (str "/api/" (:plural rd)) (or data {}))]
      (if-not (#{200 201} (:status made))
        {:error (str "the " (name kind) " it names could not be created ("
                     (:status made) ": " (pr-str ((:text ctx) made)) ")")}
        (let [id (last (str/split (str (:self (json ctx made))) #"/"))]
          (if-some [path (machine/path-to rd state)]
            (loop [steps path]
              (if-some [step (first steps)]
                (let [r (invoke-http ctx kind id (:name step) nil)]
                  (if-not (= 200 (:status r))
                    {:error (str "walking that " (name kind) " to " (name state)
                                 " stopped at " (name (:name step)) " ("
                                 (:status r) ": " (pr-str ((:text ctx) r)) ")")}
                    (recur (rest steps))))
                {:id id}))
            {:error (str (name state) " is unreachable from "
                         (name (:initial rd)) " by declared transitions")}))))
    {:error (str "this engine serves no kind " kind)}))

(defn- wire-verdict
  "The verdict a CLIENT sees, read off the envelope — which is the
  whole reason this tier exists. 2xx is the allowance; a 409
  guard-refused names its guard, its reason and its remedies; a 409
  wrong-state is the reserved :out-of-state denier. Anything else is
  unreadable as a verdict and says so, concealment included: a
  hide-flagged guard answers 404 and never names itself, so no
  scenario can pin one through the door."
  [ctx resp]
  (let [b (json ctx resp)
        status (:status resp)
        type' (str (:type b))]
    (cond
      (<= 200 status 299) {:warned []}

      (str/ends-with? type' "guard-refused")
      {:refused (keyword (:guard b))
       :reason (:detail b)
       :remedies (mapv token->keyword (:remedies b))}

      (str/ends-with? type' "wrong-state")
      {:refused scenario/out-of-state
       :reason (:detail b)
       :remedies []}

      (= 404 status)
      {:unreadable (str "the door answered 404 — a hide-flagged guard conceals"
                        " rather than narrates, so no scenario can name it here")}

      :else
      {:unreadable (str "the door answered " status " ("
                        (or (:title b) "no problem document") "), which is"
                        " neither an allowance nor a refusal")})))

(defn- run-scenario
  "One conformance-tier scenario, staged and attempted through the
  real HTTP door."
  [ctx rdef' s]
  (let [staged (reduce (fn [_ gv]
                         (let [out (stage-declared-row ctx (:kind gv) (:state gv)
                                                       (:data gv))]
                           (if (:error out) (reduced out) nil)))
                       nil (:given s))]
    (if (:error staged)
      (scenario/violation s {:unreadable (str "could not be staged: " (:error staged))})
      (let [hs (scenario-headers s)
            resp (if (scenario/create-door? rdef' (:attempt s))
                   (req ctx :post (str "/api/" (:plural rdef')) (or (:input s) {}) hs)
                   (let [subject (stage-declared-row ctx (:kind s)
                                                     (get-in s [:row :state])
                                                     (get-in s [:row :data]))]
                     (if (:error subject)
                       ::unstaged
                       (invoke-http ctx (:kind s) (:id subject) (:attempt s)
                                    (:input s) {:headers hs}))))]
        (if (= ::unstaged resp)
          (scenario/violation
           s {:unreadable "the row it describes could not be staged through its own door"})
          (scenario/violation s (wire-verdict ctx resp)))))))

(defn- law-scenario-violations
  "The declared policy, proved through the door: every scenario the
  registry's kinds declare that the check tier could NOT judge for
  free — one that stages :given rows, or one whose law reaches for
  storage. The check tier already paid for the rest (waymark10.check),
  and re-running them here would be the same evaluator answering the
  same question twice.

  Reports :covered, so an application can tell zero-scenarios-declared
  from zero-scenarios-run: which of those is honest is the app's claim
  to make, not the framework's."
  [ctx]
  (let [work (for [kind (sort (:registry-kinds ctx))
                   :let [rd (rdef ctx kind)]
                   s (:scenarios rd)
                   :when (not (scenario/check-tier? rd s))]
               [rd s])]
    {:violations (into [] (keep (fn [[rd s]] (run-scenario ctx rd s))) work)
     :covered (count work)}))

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
    ;; the audit's other half: replay proves the edge was legal, this
    ;; one proves WHY it was allowed — and proves that a kind which
    ;; declared no retention paid no bytes for the privilege
    {:name :core/decision-record :run decision-record-violations}
    ;; …and the audit's third face: the log READ, as the wire serves
    ;; it. Replay proves the edge was legal and the record proves why
    ;; it was allowed; this one proves a client can ask, and that what
    ;; comes back says which of the two answers it is giving
    {:name :core/history :run history-violations}
    ;; the POLICY's own obligation: the packs above prove the
    ;; machinery, this one proves what the household actually
    ;; declared. Core, not a module — a scenario judges core's law
    ;; with core's evaluator, and there is no fifth column on the
    ;; inventory for it
    {:name :core/law-scenarios :run law-scenario-violations}
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

;; ── mcp ─────────────────────────────────────────────────────────────
;;
;; The MCP surface owes four promises beyond its routes being mounted,
;; and every one of them is proved END TO END: a JSON-RPC message goes
;; in at /api/-/mcp and a tool result comes out, because a unit test of
;; the tool layer would prove the half that was never in doubt. The
;; grant this pack mints, accepts and revokes is core's own kind, so
;; these obligations hold on an engine with no application kinds at all
;; — which is exactly the engine an integrator assembles first.

(def ^:private mcp-tool-names
  #{"waymark_discover" "waymark_schema" "waymark_query"
    "waymark_get" "waymark_invoke" "waymark_history"})

(defn- mcp-rpc
  "One JSON-RPC message at the MCP door, as whichever principal the
  headers name. → the parsed body."
  [ctx headers method params]
  (json ctx (req ctx :post "/api/-/mcp"
                 (cond-> {:jsonrpc "2.0" :id 1 :method method}
                   params (assoc :params params))
                 headers)))

(defn- mcp-call
  "One tools/call, unwrapped to what the MODEL would see: whether the
  tool refused, and the JSON it handed back."
  [ctx headers tool args]
  (let [body (mcp-rpc ctx headers "tools/call"
                      {:name tool :arguments (or args {})})
        out (:result body)
        text (get-in out [:content 0 :text])]
    {:refused? (boolean (:isError out))
     :rpc-error (:error body)
     :text text
     :value (when text (try (wire/read-json text) (catch Exception _ nil)))}))

(defn- id-of [self] (last (str/split (str self) #"/")))

(defn- mint-grant!
  "A grant over one kind, offered to one audience — core's own door,
  used as a fixture. → its id, or nil when the mint refused (which is
  itself reported by the obligation that asked)."
  [ctx audience kind]
  (let [made (req ctx :post "/api/grants"
                  {:audience audience
                   :scope [{:kind (name kind) :actions []}]})]
    (when (= 201 (:status made))
      (id-of (:self (json ctx made))))))

(defn- mcp-handshake-violations
  "initialize answers a protocol version, declares the tools
  capability, and hands over instructions that say the untrusted-input
  sentence out loud — the spec's prompt-injection punt is a punt about
  MITIGATION, never about saying so."
  [ctx]
  (let [r (:result (mcp-rpc ctx nil "initialize"
                            {:protocolVersion "2025-06-18"
                             :capabilities {}
                             :clientInfo {:name "conformance" :version "1"}}))
        instructions (str (:instructions r))]
    (cond-> []
      (not= "2025-06-18" (:protocolVersion r))
      (conj (str "initialize: a version this server supports must be echoed, got "
                 (pr-str (:protocolVersion r))))
      (nil? (:tools (:capabilities r)))
      (conj "initialize: the tools capability is not declared")
      (nil? (:serverInfo r))
      (conj "initialize: no serverInfo — a client cannot name what it connected to")
      (not (str/includes? (str/lower-case instructions) "untrusted"))
      (conj (str "initialize: the instructions never call row data untrusted "
                 "input — the one sentence the spec asks this surface to carry"))
      ;; an unknown revision must not be a refusal: MCP's next release
      ;; would otherwise be a waymark outage
      (nil? (:protocolVersion
             (:result (mcp-rpc ctx nil "initialize"
                               {:protocolVersion "2099-01-01"}))))
      (conj "initialize: an unknown protocol version was refused rather than negotiated"))))

(defn- mcp-six-tools-violations
  "tools/list is the six, and stays the six. The whole design decision
  is here: the tool list does NOT grow with the law, so an engine with
  fifty kinds advertises exactly what an engine with one does."
  [ctx]
  (let [tools (:tools (:result (mcp-rpc ctx nil "tools/list" nil)))
        names (into #{} (map :name) tools)]
    (cond-> []
      (not= mcp-tool-names names)
      (conj (str "tools/list: expected exactly " (vec (sort mcp-tool-names))
                 ", got " (vec (sort names))))
      (some #(not= "object" (get-in % [:inputSchema :type])) tools)
      (conj "tools/list: every tool needs an object inputSchema a client can fill")
      (not= (count tools) (count names))
      (conj "tools/list: a tool name is advertised twice"))))

(defn- mcp-concealment-violations
  "The product thesis, checked: the surface an agent discovers is its
  GRANT'S PROJECTION. A leash over one kind is minted, accepted by its
  audience, and worn — and the kinds that leash never named are not
  refused to the agent, they are ABSENT from discover, which is
  router.clj's posture inherited rather than re-implemented."
  [ctx]
  ;; `own` is READ OFF THE REGISTRY (spec-decision-kind seam 2), never
  ;; enumerated here: a kind whose own-surface rides every named
  ;; principal's request is not concealed by a narrow leash and would
  ;; make this probe test nothing.
  (let [own (:own-surface-kinds ctx #{})
        granted (or (first (app-kinds ctx)) :role)
        hidden (first (sort (remove #(or (= granted %) (own %))
                                    (:registry-kinds ctx))))
        audience "mcp-scope-probe"]
    (if (nil? hidden)
      []
      (if-some [gid (mint-grant! ctx audience granted)]
        (let [as-audience {"x-waymark-principal" audience
                           "x-waymark-actor-type" "agent"}
              accepted ((:invoke ctx) :grant gid :accept {} {:headers as-audience})
              scoped (assoc as-audience "x-waymark-grant" gid)]
          (if (not= 200 (:status accepted))
            [(str "mcp: the audience could not accept its own grant ("
                  (:status accepted) ") — the concealment probe never got a leash")]
            (let [{:keys [refused? value]} (mcp-call ctx scoped "waymark_discover" {})
                  kinds (set (:kinds value))]
              (cond-> []
                refused?
                (conj (str "waymark_discover refused a scoped agent: " (:text value)))
                (not (contains? kinds (name granted)))
                (conj (str "waymark_discover: the granted kind " (name granted)
                           " is missing from a leash that names it — got "
                           (vec (sort kinds))))
                (contains? kinds (name hidden))
                (conj (str "waymark_discover: " (name hidden)
                           " is not in this grant and must be ABSENT, not"
                           " refused — concealment is the router's posture"
                           " and MCP inherits it"))))))
        [(str "mcp: minting a grant over " (name granted)
              " refused — the concealment probe has no leash to wear")]))))

(defn- mcp-confirm-violations
  "The dangerous verb's price. `grant.revoke` declares safety.confirm
  with a consequence sentence, so the sentence is read off the row's
  own envelope — through waymark_get, as an agent would — and echoing
  it is what makes the call run. Echoing something else does not, and
  neither does echoing nothing: the gate compares exactly, because a
  gate that accepts a paraphrase is a gate a model talks its way
  through."
  [ctx]
  (if-some [gid (mint-grant! ctx "mcp-confirm-probe"
                             (or (first (app-kinds ctx)) :role))]
    (let [env (:value (mcp-call ctx nil "waymark_get" {:kind "grant" :id gid}))
          sentence (get-in env [:actions :revoke :display :description])
          invoke (fn [args] (mcp-call ctx nil "waymark_invoke"
                                      (merge {:kind "grant" :id gid
                                              :action "revoke"} args)))]
      (if (str/blank? (str sentence))
        [(str "mcp: grant " gid " advertises no consequence sentence for revoke"
              " — there is no confirm gate to prove")]
        (let [bare (invoke {})
              wrong (invoke {:acknowledge "yes, do it"})
              echoed (invoke {:acknowledge sentence})]
          (cond-> []
            (not (:refused? bare))
            (conj "waymark_invoke ran a safety.confirm action with no acknowledge")
            (not (str/includes? (str (:text bare)) sentence))
            (conj (str "waymark_invoke's confirm refusal does not carry the "
                       "sentence it is asking for — an agent cannot echo what "
                       "it was not told: " (:text bare)))
            (not (:refused? wrong))
            (conj (str "waymark_invoke accepted " (pr-str "yes, do it")
                       " for a consequence sentence — the gate must compare"
                       " exactly, or it is decoration"))
            (:refused? echoed)
            (conj (str "waymark_invoke refused the exact consequence sentence: "
                       (:text echoed)))
            (and (not (:refused? echoed))
                 (not= "revoked" (:state (:value echoed))))
            (conj (str "waymark_invoke echoed the sentence but the row is "
                       (pr-str (:state (:value echoed))) ", not revoked"))))))
    ["mcp: minting a grant refused — the confirm gate has nothing to prove on"]))

(defn- mcp-refusal-violations
  "A refusal is TOOL OUTPUT, and it is the ENGINE'S refusal. The guard
  narrated on the envelope as unavailable must be the guard's sentence
  the tool hands the model — same words, not a re-narration, and never
  a bare protocol error, because an agent learns from the first and
  nothing from the second."
  [ctx]
  (if-some [gid (mint-grant! ctx "mcp-refusal-probe"
                             (or (first (app-kinds ctx)) :role))]
    (let [env (:value (mcp-call ctx nil "waymark_get" {:kind "grant" :id gid}))
          narrated (get-in env [:unavailable :expire :reason])
          {:keys [refused? value rpc-error]}
          (mcp-call ctx nil "waymark_invoke"
                    {:kind "grant" :id gid :action "expire"})]
      (cond-> []
        (str/blank? (str narrated))
        (conj (str "grant " gid " narrates no unavailable expire — the "
                   "refusal obligation has nothing to compare against"))
        rpc-error
        (conj (str "waymark_invoke answered a JSON-RPC error where a refusal "
                   "belonged: " (pr-str rpc-error)))
        (not refused?)
        (conj "waymark_invoke ran a guard-refused action and called it success")
        (and (seq (str narrated)) (not= narrated (:detail value)))
        (conj (str "the tool's refusal " (pr-str (:detail value))
                   " is not the guard's own sentence " (pr-str narrated)))
        (nil? (:status value))
        (conj "the tool's refusal is not a problem document — no status")))
    ["mcp: minting a grant refused — the refusal obligation has no row"]))

(defn- mcp-obligation [name' run]
  {:name name' :needs #{[:route :mcp]} :run run})

(def mcp
  {:module :mcp
   :obligations
   [(routes-mounted :mcp)
    (mcp-obligation :mcp/handshake mcp-handshake-violations)
    (mcp-obligation :mcp/six-tools mcp-six-tools-violations)
    (mcp-obligation :mcp/grant-projected-discovery mcp-concealment-violations)
    (mcp-obligation :mcp/confirm-gate mcp-confirm-violations)
    (mcp-obligation :mcp/refusals-are-the-engines mcp-refusal-violations)]})

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

;; ── law sweep (waymark-442.3) ───────────────────────────────────────
;;
;; What this obligation can prove and what it cannot, said plainly. It
;; cannot stage a HOLD: a hold is two boots of two different codebases
;; against one database, and a conformance driver has one process and
;; one classpath. So the availability-drift proof — a guard tree that
;; moved, a row that flips — lives in waymark10.law-sweep-test, which
;; boots twice on purpose (the batch-C overlay suite's own shape).
;;
;; What lives HERE is the door's contract, which is exactly the half
;; that must hold on every engine that assembles the module: a row
;; that is not a proposal REFUSES rather than reporting nonsense, a
;; row that is one reports a well-formed sweep, and a definition that
;; does not exist is a 404 like any other row. Every app suite runs in
;; :promote mode, so the refusal branch is the one they exercise —
;; and that branch is the one a proposer hits by mistake.

(defn- sweep-report-violations
  "One sweep document, graded against its own promises: the header
  agrees with the row it swept, the totals agree with the findings,
  every finding names one of the four classes, and an availability
  finding names an action the kind actually declares."
  [ctx self b]
  (let [d (:data b)
        findings (:findings d)
        by-class (frequencies (map :class findings))
        kind (keyword (:target_kind d))
        declared (into #{} (map (comp name key))
                       (:actions (rdef ctx kind)))]
    (into []
          (remove nil?)
          (concat
           [(when (not= "law_sweep" (:kind b))
              (str "law-sweep: " self "/sweep answered kind "
                   (pr-str (:kind b)) ", not law_sweep"))
            (when-not (seq (:notes d))
              (str "law-sweep: " self "/sweep reported no notes — the"
                   " snapshot, the principal and the adoption posture are"
                   " what keep the findings from being read as totality"))
            (when (< (:of d 0) (:scanned d 0))
              (str "law-sweep: " self "/sweep scanned " (:scanned d)
                   " of " (:of d) " rows, which is more than there are"))
            (when-not (= (boolean (:truncated d))
                         (< (:scanned d 0) (:of d 0)))
              (str "law-sweep: " self "/sweep says truncated="
                   (pr-str (:truncated d)) " over " (:scanned d) " of "
                   (:of d) " rows"))
            (when (not= (count findings)
                        (reduce + 0 (vals (:totals d))))
              (str "law-sweep: " self "/sweep totals " (pr-str (:totals d))
                   " do not add up to " (count findings) " findings"))]
           (map (fn [[c n]]
                  (when (not= n (get-in d [:totals (keyword c)]))
                    (str "law-sweep: " self "/sweep counted " n " "
                         c " findings but totalled "
                         (pr-str (get-in d [:totals (keyword c)])))))
                by-class)
           (map (fn [f]
                  (cond
                    (not (contains? #{"schema" "availability" "state" "derivation"}
                                    (str (:class f))))
                    (str "law-sweep: a finding of class " (pr-str (:class f))
                         " — the report's classes are schema, availability,"
                         " state and derivation")

                    (and (= "availability" (str (:class f)))
                         (not (contains? declared
                                         (str (get-in f [:detail :action])))))
                    (str "law-sweep: an availability finding names action "
                         (pr-str (get-in f [:detail :action])) " on "
                         (:target_kind d) ", which declares "
                         (pr-str (vec (sort declared))))))
                findings)))))

(defn- law-sweep-violations
  "Every definition row this engine holds, asked what promoting it
  would do."
  [ctx]
  ;; the first page, and deliberately no query string: the driver's
  ;; `req` puts the uri where reitit reads a PATH, so a `?` here would
  ;; not narrow the page — it would stop matching the route. One page
  ;; of definition rows is enough for a door contract; every row on it
  ;; owes the same answer.
  (let [coll (json ctx (req ctx :get "/api/definitions"))
        items (get-in coll [:data :items])
        missing (req ctx :get "/api/definitions/no-such-definition/sweep")]
    (into (if (= 404 (:status missing))
            []
            [(str "law-sweep: the sweep door answered " (:status missing)
                  " for a definition that does not exist, not 404")])
          (mapcat
           (fn [item]
             (let [self (:self item)
                   row (json ctx (req ctx :get self))
                   proposal? (contains? #{"proposed" "piloted"} (str (:state row)))
                   resp (req ctx :get (str self "/sweep"))
                   b (json ctx resp)]
               (cond
                 (and (not proposal?) (not= 409 (:status resp)))
                 [(str "law-sweep: " self " is in state " (pr-str (:state row))
                       " and its sweep door answered " (:status resp)
                       ", not 409 — a sweep compares a PROPOSAL to the served"
                       " law, and there is no proposal here")]

                 (not proposal?) []

                 (not= 200 (:status resp))
                 [(str "law-sweep: " self " is a proposal and its sweep door"
                       " answered " (:status resp) ": " (pr-str ((:text ctx) resp)))]

                 :else (sweep-report-violations ctx self b))))
           items))))

(def law-sweep
  {:module :law-sweep
   :obligations
   [(routes-mounted :law-sweep)
    {:name :law-sweep/proposal-or-refusal
     :needs #{[:route :law-sweep] [:kind :definition]}
     :run law-sweep-violations}]})

;; ── feed ────────────────────────────────────────────────────────────
;;
;; The feed owes four promises beyond its route being mounted, and
;; they are the four laws of docs/spec-feed.md read as questions a
;; wire document can answer: is the order the CENSUS's, is the seam a
;; real element, is the day's order stable, and — the one that costs —
;; does a reader without a grant on a kind never see its card. That
;; last is judged from TWO principals through the same door, because a
;; per-member world is not a claim one principal can check.

(defn- feed-doc
  "One feed read, as whichever principal the headers name."
  [ctx headers & [query]]
  (let [resp ((:handler ctx) (cond-> {:request-method :get
                                      :uri "/api/-/feed"
                                      :headers (or headers
                                                   (:walker-headers ctx))}
                               query (assoc :query-string query)))]
    {:status (:status resp) :doc (json ctx resp) :resp resp}))

(defn- feed-cards [doc] (vec (:cards doc)))

(defn- feed-recipe-order-violations
  "The census is law, and both halves of it are checked: what the DOOR
  answered (sections top to bottom, one seam, every card naming its
  origin row), and what the recipe CHECKS refuse (an unknown
  population, a missing seam, a bottomless section that is not last).
  The second half is pure — no engine, no rows — which is why it can
  be asked here rather than only at a boot nobody watched."
  [ctx]
  (let [{:keys [status doc resp]} (feed-doc ctx nil)
        cards (feed-cards doc)
        seams (filterv #(= "seam" (:card_id %)) cards)
        ranks (into [] (comp (map :section)
                             (map (fn [s] [s (.indexOf ^java.util.List
                                                       (mapv name feed/census)
                                                       (str s))])))
                    cards)
        refuses? (fn [recipe]
                   (try (feed/check-recipe! recipe) false
                        (catch clojure.lang.ExceptionInfo e
                          (boolean (:waymark10/definition-error (ex-data e))))))]
    (cond-> []
      (not= 200 status)
      (conj (str "feed: the door answered " status " to a named principal: "
                 (pr-str ((:text ctx) resp))))

      (not= 1 (count seams))
      (conj (str "feed: exactly one card is the seam, found " (count seams)
                 " — a feed with no seam never finishes, and a feed with two"
                 " says 'that's everything' twice"))

      (and (= 1 (count seams))
           (not= (.indexOf ^java.util.List (mapv :card_id cards) "seam")
                 (long (:above (first seams)))))
      (conj (str "feed: the seam says " (pr-str (:above (first seams)))
                 " cards are above it, and " (.indexOf ^java.util.List
                                                       (mapv :card_id cards)
                                                       "seam")
                 " are"))

      (some (fn [[s r]] (neg? (long r))) ranks)
      (conj (str "feed: a card names section "
                 (pr-str (first (keep (fn [[s r]] (when (neg? (long r)) s))
                                      ranks)))
                 ", which is not in the census " (pr-str (mapv name feed/census))))

      (not= (mapv second ranks) (vec (sort (mapv second ranks))))
      (conj (str "feed: the sections are out of census order — "
                 (pr-str (into [] (distinct) (mapv first ranks)))
                 " against " (pr-str (mapv name feed/census))
                 ". The census is law, so this is a typo, not a preference"))

      (some (fn [c] (and (not= "seam" (:card_id c))
                         (or (str/blank? (str (:self c)))
                             (not= (:card_id c)
                                   (str (:section c) "/" (:kind c) "/"
                                        (last (str/split (str (:self c)) #"/")))))))
            cards)
      (conj (str "feed: a card's id is section/kind/id over its OWN row — a"
                 " card that invented an identity is a card the client cannot"
                 " key on"))

      (not (refuses? (assoc feed/default-recipe :order
                            [{:section :do_now :population :telepathy :take 1}
                             {:seam true}])))
      (conj "feed: a recipe naming an unregistered population was accepted")

      (not (refuses? (assoc feed/default-recipe :order
                            [{:section :do_now :population :next_actions :take 1}])))
      (conj "feed: a recipe with no seam was accepted")

      (not (refuses? (assoc feed/default-recipe :order
                            [{:section :archive :population :events :take 1
                              :bottomless true}
                             {:seam true}])))
      (conj (str "feed: a recipe whose bottomless section is not last was"
                 " accepted — a section that never ends can have nothing"
                 " below it"))

      (not (refuses? (assoc feed/default-recipe :order
                            [{:section :fuel :population :events :take 1}
                             {:section :do_now :population :next_actions :take 1}
                             {:seam true}])))
      (conj "feed: a recipe with fuel above do-now was accepted")

      (not (refuses? (assoc feed/default-recipe :order
                            [{:section :do_now :population :next_actions :take 1}
                             {:seam true} {:seam true}])))
      (conj "feed: a recipe carrying two seams was accepted"))))

(defn- feed-day-stable-violations
  "Two reads, one day, one order. The seed is a hash over (salt,
  member, local date) and nothing is stored, so this is the whole of
  'stable within a day' — and if it ever fails, the thing that failed
  is determinism, not caching.

  Laws v3 moved what this is measured against (waymark-8um.2):
  stability is per DRAW, and the daily order is the default draw. This
  obligation is the claim about that default one, unchanged, and it is
  still the claim a reader who never taps depends on. `:feed/deal-again`
  makes the same claim about a draw a person asked for."
  [ctx]
  (let [a (:doc (feed-doc ctx nil))
        b (:doc (feed-doc ctx nil))]
    (cond-> []
      (str/blank? (str (:seed a)))
      (conj "feed: the document carries no seed — the day's order came from
             somewhere this surface will not name")

      (not= (:seed a) (:seed b))
      (conj (str "feed: two reads by one member on one day answered two seeds, "
                 (pr-str (:seed a)) " and " (pr-str (:seed b))))

      (not= (mapv :card_id (feed-cards a)) (mapv :card_id (feed-cards b)))
      (conj (str "feed: two reads by one member on one day answered different"
                 " cards, in this order:\n  " (pr-str (mapv :card_id (feed-cards a)))
                 "\n  " (pr-str (mapv :card_id (feed-cards b)))))

      (not= (:day a) (:day b))
      (conj "feed: two reads straddled a day boundary — rerun"))))

(defn- feed-projection-violations
  "The fourth law, from the wire and from TWO principals: every card is
  grant-projected through the reader's own surface. A leash over one
  kind is minted, accepted by its audience and worn; the kinds that
  leash never named are not refused to the reader, they are ABSENT.

  This is the law the feed could have skipped — `routes/law_sweep.clj`
  refuses a scoped caller outright and says so — and the one it may
  not, because per-member worlds is the whole surface."
  [ctx]
  (let [own (:own-surface-kinds ctx #{})
        granted (or (first (app-kinds ctx)) :role)
        audience "feed-scope-probe"
        allowed (into #{(name granted)} (map name) own)]
    (if-some [gid (mint-grant! ctx audience granted)]
      (let [as-audience {"x-waymark-principal" audience
                         "x-waymark-actor-type" "agent"}
            accepted ((:invoke ctx) :grant gid :accept {} {:headers as-audience})
            unscoped (:doc (feed-doc ctx nil))
            scoped (feed-doc ctx (assoc as-audience "x-waymark-grant" gid))
            seen (into #{} (comp (remove #(= "seam" (:card_id %)))
                                 (map (comp str :kind)))
                       (feed-cards (:doc scoped)))
            leaked (sort (remove allowed seen))]
        (cond-> []
          (not= 200 (:status accepted))
          (conj (str "feed: the audience could not accept its own grant ("
                     (:status accepted) ") — the projection probe never got"
                     " a leash"))

          (not= 200 (:status scoped))
          (conj (str "feed: a grant-scoped reader was answered "
                     (:status scoped) " — the feed PROJECTS for a scoped"
                     " caller rather than refusing one; that exit is the"
                     " sweep's, and spec-feed's fourth law takes it away"))

          ;; on an engine with application kinds the feed must have
          ;; SOMETHING to say, or this obligation is passing by having
          ;; nothing to conceal. On a bare core engine it genuinely has
          ;; nothing — the archive draws from the household's own kinds
          ;; and there are none — and demanding a card there would be
          ;; demanding that machinery be news.
          (and (seq (app-kinds ctx))
               (empty? (remove #(= "seam" (:card_id %)) (feed-cards unscoped))))
          (conj (str "feed: an unscoped reader saw no card at all on an engine"
                     " declaring " (count (app-kinds ctx)) " kinds, so the"
                     " projection probe had nothing to conceal"))

          (seq leaked)
          (conj (str "feed: a reader whose leash names " (name granted)
                     " was shown cards of " (vec leaked)
                     " — an ungranted kind is ABSENT from a feed, never"
                     " narrowed and never refused"))))
      [(str "feed: minting a grant over " (name granted)
            " refused — the projection obligation has no leash to wear")])))

(defn- feed-cursor-violations
  "The cursor is opaque, it serves the ARCHIVE only, and a cursor from
  another day is refused rather than honoured. Serving yesterday's seed
  today would be a second definition of 'stable within a day', and the
  409's sentence is what tells a client to read from the top instead of
  retrying forever."
  [ctx]
  (let [{:keys [doc]} (feed-doc ctx nil)
        stale (feed/encode-cursor {:day "1999-01-01" :seed (:seed doc)
                                   :offset 0})
        rolled (feed-doc ctx nil (str "cursor=" stale))
        garbage (feed-doc ctx nil "cursor=not-a-cursor")
        next-href (get-in doc [:links :next :href])
        page2 (when next-href
                (feed-doc ctx nil (second (str/split next-href #"\?" 2))))]
    (cond-> []
      (not= 409 (:status rolled))
      (conj (str "feed: a cursor from another day answered " (:status rolled)
                 ", not 409 — the feed rolls at midnight and never serves"
                 " yesterday's order"))

      (and (= 409 (:status rolled))
           (not (str/includes? (str (get-in rolled [:doc :detail])) "roll")))
      (conj (str "feed: the stale-cursor refusal never says the feed rolled: "
                 (pr-str (get-in rolled [:doc :detail]))))

      (not= 422 (:status garbage))
      (conj (str "feed: a cursor this engine never minted answered "
                 (:status garbage) ", not 422 — a token that quietly answered"
                 " page one would make deep paging look like a loop"))

      (and page2 (not= 200 (:status page2)))
      (conj (str "feed: following links.next answered " (:status page2)))

      (and page2 (some #(= "seam" (:card_id %)) (feed-cards (:doc page2))))
      (conj "feed: a cursor page re-served the seam — the seam happens once")

      (and page2 (some #(not= "archive" (str (:section %)))
                       (feed-cards (:doc page2))))
      (conj (str "feed: a cursor page carried "
                 (pr-str (into [] (comp (map (comp str :section))
                                        (distinct)
                                        (remove #{"archive"}))
                               (feed-cards (:doc page2))))
                 " — above the seam the feed is finite and done, and"
                 " re-serving it is the duplication the epic forbids")))))

(def ^:private archive-walk-pages
  "How deep the archive walk goes. 'Bottomless' means stateless and
  LONG, never infinite, so a walk that must terminate is the honest
  shape of this obligation: eight pages is far past anything a person
  scrolls in one sitting and still a bound, which is the same trade
  every cap in this engine makes."
  8)

(defn- feed-archive-page
  "One archive page, by the cursor a `links.next` href carries."
  [ctx href]
  (feed-doc ctx nil (second (str/split (str href) #"\?" 2))))

(defn- feed-archive-pages-violations
  "waymark-iqa.5's own obligation: the archive pages ARBITRARILY DEEP
  without serving one card twice within a day.

  Three claims, and the middle one is the reason this obligation
  exists rather than the first:

  1. **No card_id repeats, however deep the walk.** The cursor is an
     `:offset` into a seeded ordering, and the offset counts
     CANDIDATES WALKED rather than cards shown. Those differ exactly
     when a candidate renders no card — a row retired between the
     population's scan and the read, or one this reader's grant
     conceals — and an offset that counted cards would re-serve the
     head of the next page as the tail of this one. That is the one
     thing an archive may not do, and it is invisible until somebody
     pages past a concealed row.
  2. **The walk is deterministic.** The same cursor, followed twice,
     answers the same cards. If it ever does not, what failed is the
     seed — the archive would be a live scan re-rolled per request,
     and every page boundary would be a coin flip.
  3. **The tail is honest.** A walk that runs out drops `links.next`,
     because a surface that pretends to be infinite lies once, at the
     bottom, to whoever scrolled the furthest.

  And the fuel half, which is stateless for the same reason: two reads
  of one day answer the same fuel cards. `:feed/day-stable` asserts it
  for the document as a whole; this asserts it where the aggregate
  populations live, because a `cleared` card is a fold over the log and
  a fold that drifted would drift here first.

  It reports `:covered`, because an engine whose archive fits on one
  page has proved nothing about depth and should say so."
  [ctx]
  (let [{:keys [doc]} (feed-doc ctx nil)
        first-next (get-in doc [:links :next :href])
        walk (loop [href first-next pages [doc] n 0]
               (if (or (nil? href) (>= n archive-walk-pages))
                 {:pages pages :stopped-early (some? href)}
                 (let [{:keys [status doc]} (feed-archive-page ctx href)]
                   (if (not= 200 status)
                     {:pages pages :bad status}
                     (recur (get-in doc [:links :next :href])
                            (conj pages doc) (inc n))))))
        pages (:pages walk)
        walked (dec (count pages))
        ids (into [] (comp (mapcat feed-cards) (map :card_id)) pages)
        dupes (into [] (comp (remove #(= "seam" (key %)))
                             (keep (fn [[id n]] (when (> (long n) 1) id))))
                    (frequencies ids))
        twice (when first-next (:doc (feed-archive-page ctx first-next)))
        fuel (fn [d] (mapv :card_id
                           (filterv #(= "fuel" (str (:section %)))
                                    (feed-cards d))))
        again (:doc (feed-doc ctx nil))]
    {:covered (if (pos? walked) 1 0)
     :violations
     (cond-> []
       (:bad walk)
       (conj (str "feed: page " (inc walked) " of the archive answered "
                  (:bad walk) " — links.next is the door's own href and"
                  " following it is the only way down"))

       (seq dupes)
       (conj (str "feed: the archive served " (pr-str (vec (take 5 dupes)))
                  " more than once inside one day, across " (count pages)
                  " page(s) — the cursor's offset counts CANDIDATES walked,"
                  " not cards shown, precisely so a candidate that renders"
                  " no card (retired, or concealed from this reader) does"
                  " not push the next page back over this one"))

       (and (some? twice) (not= (mapv :card_id (feed-cards twice))
                                (mapv :card_id (feed-cards (second pages)))))
       (conj (str "feed: one cursor followed twice answered two pages —"
                  " the archive is a SEEDED ordering over a bounded set,"
                  " and a page that is a live scan re-rolled per request"
                  " makes every boundary a coin flip"))

       (and (not (:stopped-early walk)) (not (:bad walk))
            (get-in (last pages) [:links :next]))
       (conj (str "feed: the walk ran out of cards and the last page still"
                  " carries links.next — bottomless means stateless and"
                  " LONG, never infinite, and a surface that pretends"
                  " otherwise lies once, at the bottom, to whoever"
                  " scrolled the furthest"))

       (not= (fuel doc) (fuel again))
       (conj (str "feed: two reads of one day answered two fuel sections, "
                  (pr-str (fuel doc)) " and " (pr-str (fuel again))
                  " — the cleared queue and the streak are folds over the"
                  " log, and a fold that drifted within a day would make"
                  " the whole recipe something other than static data")))}))

(defn- feed-deal-again-violations
  "Law 6, from the wire: **the person spins; the system never spins for
  them** (waymark-8um.2). A `draw` is a nonce a person's tap mints; it
  joins the seed; and what comes back is a fresh order that is exactly
  as stable as the day's.

  Six claims, and the second is the one the whole amendment rests on:

  1. **A draw draws differently.** The seed under a draw is not the
     day's seed. (The ORDER may coincide on a house holding two cards,
     and this obligation refuses to lie about that — it claims the
     seed, which is the mechanism, and it reports whether the order
     moved rather than demanding that it did.)
  2. **A draw is stable, with honest pages.** The same draw read twice
     answers the same seed and the same cards, and `links.next`
     continues THE SAME DRAW — page two of a spin is page two of that
     spin, never of the daily order and never of a fresh one. A feed
     that re-rolled between pages would be the slot machine the epic
     refuses, wearing a cursor.
  3. **The daily order is the default draw, untouched.** A read with no
     parameter answers the day's own seed whether or not anybody has
     dealt again — the draw is in the address and nowhere else, so
     there is nothing to reset and no state to leak between readers.
  4. **The document says a person dealt again**, in the household's own
     words, and names the draw. A surface that quietly reordered itself
     would be the one thing law 6 forbids even when a person asked.
  5. **A mangled draw is refused by name**, rather than quietly
     answering the daily order — a client handed the same order twice
     would conclude that dealing again does not work.
  6. **One read, one draw.** A cursor from one draw beside a different
     `draw` parameter is refused rather than guessed at."
  [ctx]
  (let [daily (:doc (feed-doc ctx nil))
        drawn (feed-doc ctx nil "draw=packA1")
        a (:doc drawn)
        b (:doc (feed-doc ctx nil "draw=packA1"))
        other (:doc (feed-doc ctx nil "draw=packB2"))
        after (:doc (feed-doc ctx nil))
        ids (fn [d] (mapv :card_id (feed-cards d)))
        next-href (get-in a [:links :next :href])
        page2 (when next-href
                (feed-doc ctx nil (second (str/split (str next-href) #"\?" 2))))
        garbage (feed-doc ctx nil "draw=not%20a%20draw")
        crossed (when-some [c (some-> next-href (str/split #"cursor=") second)]
                  (feed-doc ctx nil (str "draw=packB2&cursor=" c)))]
    (cond-> []
      (not= 200 (:status drawn))
      (conj (str "feed: dealing again answered " (:status drawn)
                 " — a draw is a READ, and the person spinning is the one"
                 " thing law 6 permits"))

      (= (:seed daily) (:seed a))
      (conj (str "feed: a draw answered the DAY's seed — the nonce never"
                 " reached the hash, so the tap changed nothing"))

      (= (:seed a) (:seed other))
      (conj "feed: two different draws answered one seed")

      (not= "packA1" (str (:draw a)))
      (conj (str "feed: the document names its draw " (pr-str (:draw a))
                 " and the request asked for \"packA1\" — a spin the"
                 " document will not name is a spin a reader cannot"
                 " bookmark, share or come back to"))

      (some? (:draw daily))
      (conj (str "feed: a read with no draw named one (" (pr-str (:draw daily))
                 ") — the day's own order is the ABSENCE of a draw, not a"
                 " draw with a name"))

      (not= (:seed a) (:seed b))
      (conj "feed: one draw read twice answered two seeds")

      (not= (ids a) (ids b))
      (conj (str "feed: one draw read twice answered two orders:\n  "
                 (pr-str (ids a)) "\n  " (pr-str (ids b))
                 " — a draw is as stable as the day, or it is a slot"
                 " machine with a nonce"))

      (not= (:seed daily) (:seed after))
      (conj (str "feed: the daily order changed after somebody dealt again"
                 " — a draw lives in the address, and a read that carries"
                 " none is the day's own order for everybody, always"))

      (not= (ids daily) (ids after))
      (conj "feed: dealing again moved the DAILY order underneath it")

      (and next-href (not (str/includes? (str next-href) "draw=packA1")))
      (conj (str "feed: links.next under a draw dropped it: "
                 (pr-str next-href)))

      (and page2 (not= 200 (:status page2)))
      (conj (str "feed: page two of a draw answered " (:status page2)))

      (and page2 (not= "packA1" (str (:draw (:doc page2)))))
      (conj (str "feed: page two of a draw came back as draw "
                 (pr-str (:draw (:doc page2)))
                 " — a cursor carries its own draw so that a page"
                 " continues the spin it came from"))

      (not (some #(str/includes? (str %) "dealt again") (:notes a)))
      (conj (str "feed: nothing in the document says a person dealt again."
                 " The notes are: " (pr-str (vec (:notes a)))))

      (not= 422 (:status garbage))
      (conj (str "feed: a draw this door cannot spell answered "
                 (:status garbage) ", not 422 — a mangled nonce quietly"
                 " answering the daily order reads as deal-again being"
                 " broken"))

      (and crossed (not= 422 (:status crossed)))
      (conj (str "feed: a cursor from one draw beside another draw's"
                 " parameter answered " (:status crossed)
                 " — one read, one draw, and neither half of a request"
                 " that disagrees with itself gets to be guessed at")))))

(def ^:private above-seam
  "The sections the census puts ABOVE the caught-up line, read off
  `feed/census` rather than spelled here.

  Two obligations below need a subject row that is still OPEN — a
  marker over a finished row retires at offer time, exactly as it
  should, which would make them fail for being right — and above the
  seam is where the open rows are. It was the literal
  `#{\"do_now\" \"decide\"}`, twice, until waymark-jfv.4 put a section
  on top of both; a set that had stayed a literal would have judged
  the crown as though it were history and picked a subject from
  somewhere else without saying so."
  (into #{} (comp (take-while #(not= :seam %)) (map name)) feed/census))

(defn- feed-row-cards
  "Every card of one feed answer that stands for a row — the seam has
  no verbs and no screen, and it is the one element here that is not a
  projection of anything."
  [doc]
  (into [] (remove #(= "seam" (:card_id %))) (feed-cards doc)))

(defn- feed-citation-violations
  "No publication without citation, turned on the feed's own editorial
  choices (waymark-iqa.29). The owner found a movie in do-now and
  could not learn why; the four layers that put it there — a framework
  predicate, a declared trait, a recipe line and the day's seed — now
  say so on the wire, and this is the claim that they say the TRUTH.

  Five things, and the third is the one that matters:

  1. The recipe reads back NARRATED — every line has a sentence, and
     the four assembly checks' guarantees are one sentence beside it.
  2. Every card carries a `why` naming a line the recipe actually
     holds, and a place inside a draw it was actually part of.
  3. THE CITATION MATCHES THE LAYER THAT ADMITTED IT. A card's line
     must agree with the card about its section and its population,
     and a line dedicated to particular `:kinds` must never be cited
     by a card of another kind. A citation that named the wrong line
     would be worse than none: it would be a confident wrong answer to
     the one question this whole bead exists to answer.
  4. `?explain=1` is a READ FLAG — the same cards, in the same order,
     with sentences added. That is the law a client leans on when it
     fetches the citation late and lines it up by `card_id`, so it is
     asserted rather than assumed.
  5. The sentences quote the DECLARATION. A card whose population
     reads `:over` cites `:over` by name — spelled or unspelled — and
     every citation ends at the seed rather than at an opinion."
  [ctx]
  (let [{:keys [doc]} (feed-doc ctx nil)
        spelled (:doc (feed-doc ctx nil "explain=1"))
        recipe (:recipe doc)
        lines (vec (:lines recipe))
        by-line (into {} (map (juxt :line identity)) lines)
        cards (feed-cards doc)
        rows (feed-row-cards spelled)
        reads-over? #{"next_actions" "cleared" "streaks" "finished" "memories"}
        said (fn [c] (str/join " " (:says (:why c))))]
    {:covered (boolean (seq rows))
     :violations
     (cond-> []
       (empty? lines)
       (conj (str "feed: the document carries no narrated recipe — the order"
                  " was hard-coded where no reader would ever see it, which"
                  " is the half of waymark-iqa.29 that is not about any one"
                  " card"))

       (some #(str/blank? (str (:says %))) lines)
       (conj (str "feed: a recipe line narrates nothing: "
                  (pr-str (first (filter #(str/blank? (str (:says %))) lines)))))

       (str/blank? (str (:guarantees recipe)))
       (conj (str "feed: the narrated recipe carries no guarantees sentence —"
                  " the four assembly checks run at the build site and a"
                  " reader has no other way to learn that they did"))

       (some #(nil? (:why %)) cards)
       (conj (str "feed: a card carries no why at all: "
                  (pr-str (:card_id (first (filter #(nil? (:why %)) cards))))))

       (some #(nil? (get by-line (:line (:why %)))) cards)
       (conj (str "feed: a card cites a recipe line the narrated recipe does"
                  " not hold — "
                  (pr-str (map :card_id
                               (filter #(nil? (get by-line (:line (:why %))))
                                       cards)))))

       :always
       (into
        (comp (keep (fn [c]
                      (let [l (get by-line (:line (:why c)))]
                        (cond
                          (nil? l) nil

                          (not= (str (:section c)) (str (:section l)))
                          (str "feed: card " (:card_id c) " is in section "
                               (pr-str (str (:section c))) " and cites line "
                               (:line (:why c)) ", which is "
                               (pr-str (str (:section l))))

                          (not= (str (:population c)) (str (:population l)))
                          (str "feed: card " (:card_id c) " names population "
                               (pr-str (str (:population c)))
                               " and cites a line whose population is "
                               (pr-str (str (:population l))))

                          (and (seq (:kinds l))
                               (not (contains? (set (map str (:kinds l)))
                                               (str (:kind c)))))
                          (str "feed: card " (:card_id c) " is a "
                               (str (:kind c)) " and cites a line dedicated to "
                               (pr-str (vec (map str (:kinds l))))
                               " — a citation that named the wrong layer is a"
                               " confident wrong answer to the one question"
                               " this document exists to answer")

                          (not (<= 1 (long (:rank (:why c) 0))
                                   (long (:of (:why c) 0))))
                          (str "feed: card " (:card_id c) " was drawn "
                               (pr-str (:rank (:why c))) " of "
                               (pr-str (:of (:why c)))
                               " — a place outside its own draw")

                          :else nil)))))
        (feed-row-cards doc))

       (not= (mapv :card_id (feed-cards doc)) (mapv :card_id (feed-cards spelled)))
       (conj (str "feed: ?explain=1 answered different cards — it is a READ"
                  " FLAG and the day's order is the day's order, which is"
                  " exactly what lets a client fetch the citation late and"
                  " line it up by card_id:\n  "
                  (pr-str (mapv :card_id (feed-cards doc))) "\n  "
                  (pr-str (mapv :card_id (feed-cards spelled)))))

       (some #(empty? (:says (:why %))) rows)
       (conj (str "feed: ?explain=1 left a card with no sentences: "
                  (pr-str (:card_id (first (filter #(empty? (:says (:why %)))
                                                   rows))))))

       :always
       (into
        (comp (keep (fn [c]
                      (let [s (said c)]
                        (cond
                          ;; prose counts from one, the wire's index from
                          ;; zero — and a citation that opened on the
                          ;; wrong line would be citing the wrong layer
                          (not (str/includes?
                                s (str "Recipe line "
                                       (inc (long (:line (:why c) 0))))))
                          (str "feed: card " (:card_id c) "'s citation opens "
                               (pr-str (first (:says (:why c))))
                               " and it cites line " (:line (:why c)))

                          (and (reads-over? (str (:population c)))
                               (not (str/includes? s ":over")))
                          (str "feed: card " (:card_id c) " was admitted by "
                               (str (:population c)) ", which reads the kind's"
                               " :over, and its citation never names it: "
                               (pr-str s))

                          (not (str/includes? s "seed"))
                          (str "feed: card " (:card_id c) "'s citation never"
                               " reaches the seed — the draw is the last of"
                               " the four layers and the only one a reader"
                               " cannot see for themselves")

                          :else nil)))))
        rows))}))

(defn- feed-light-violations
  "The first law, read off the wire: no entry in any card's `actions`
  demands more than a selection, and every `heavier` entry is a
  well-formed pointer at the ROW's own screen.

  A card that offered a composition would be a keyboard where the
  epic promised a thumb; a `heavier` entry pointing at the action's
  own href would be the door the partition exists to move, wearing a
  link's clothes."
  [cards]
  (into []
        (mapcat
         (fn [c]
           (concat
            (keep (fn [[aname e]]
                    (when (demand/heavier? (str (:effort e)) feed/card-ceiling)
                      (str "feed: card " (:card_id c) " offers "
                           (name aname) " at effort " (pr-str (:effort e))
                           " — a card may only offer ≤ " feed/card-ceiling
                           ", and anything heavier links to the row's own"
                           " screen instead")))
                  (:actions c))
            (mapcat
             (fn [h]
               (cond
                 (str/blank? (str (:name h)))
                 [(str "feed: card " (:card_id c) " carries a heavier entry"
                       " naming no action: " (pr-str h))]

                 (not (demand/heavier? (str (:effort h)) feed/card-ceiling))
                 [(str "feed: card " (:card_id c) " put " (:name h)
                       " (effort " (pr-str (:effort h)) ") in heavier —"
                       " a verb that FITS under the thumb belongs in actions,"
                       " where it can be tapped")]

                 (str/blank? (str (:label h)))
                 [(str "feed: card " (:card_id c) "'s heavier entry "
                       (:name h) " carries no label — a link a person"
                       " cannot read is a link they will not follow")]

                 (str/includes? (str (:href h)) "/-/")
                 [(str "feed: card " (:card_id c) "'s heavier entry "
                       (:name h) " points at " (pr-str (:href h))
                       " — that is the ACTION's door, and a heavier entry is"
                       " a place to GO rather than a verb to fire")]

                 (not= (feed/screen-of (str (:self c))) (:href h))
                 [(str "feed: card " (:card_id c) "'s heavier entry "
                       (:name h) " points at " (pr-str (:href h))
                       " rather than the row's own screen "
                       (pr-str (feed/screen-of (str (:self c)))))]

                 :else nil))
             (:heavier c))))
         cards)))

(defn- feed-concealed-violations
  "The two-principal half, and the one the ORDER of the projection
  buys: an action a grant conceals appears in NEITHER list.

  `mint-grant!` mints `:actions []` — read-only sight of one kind, the
  leash core's own door hands out — so the audience holds the kind and
  no verb of it at all. Every card it reads must therefore carry an
  empty `actions` AND an empty `heavier`. If `heavier` were built from
  the declaration rather than from the surviving map, this is exactly
  where a concealed door would reappear as a link, and concealment
  would have become narration."
  [ctx]
  (let [granted (or (first (app-kinds ctx)) :role)
        audience "feed-verb-probe"]
    (if-some [gid (mint-grant! ctx audience granted)]
      (let [as-audience {"x-waymark-principal" audience
                         "x-waymark-actor-type" "agent"}
            accepted ((:invoke ctx) :grant gid :accept {} {:headers as-audience})
            scoped (feed-doc ctx (assoc as-audience "x-waymark-grant" gid))
            cards (feed-row-cards (:doc scoped))]
        (cond-> []
          (not= 200 (:status accepted))
          (conj (str "feed: the audience could not accept its own grant ("
                     (:status accepted) ") — the concealment probe never"
                     " got a leash"))

          (some #(seq (:actions %)) cards)
          (conj (str "feed: a reader whose leash names no action was shown "
                     (pr-str (into [] (comp (mapcat (comp keys :actions))
                                            (map name) (distinct))
                                   cards))
                     " in actions — a sight-only grant confers sight, not"
                     " doors"))

          (some #(seq (:heavier %)) cards)
          (conj (str "feed: a reader whose leash names no action was shown "
                     (pr-str (into [] (comp (mapcat :heavier) (map :name)
                                            (distinct))
                                   cards))
                     " in heavier — heavier is drawn from the SURVIVORS of"
                     " the projection, so a concealed door may not reappear"
                     " there as a link"))))
      [(str "feed: minting a grant over " (name granted)
            " refused — the concealment obligation has no leash to wear")])))

(defn- feed-origin-violations
  "The third half: a verb invoked FROM a card carries the feed's origin
  into the audit trail, with no new column.

  One assent-effort card verb is invoked for real under
  `Idempotency-Key: feed/<day>/<card_id>/<nonce>`, and the row's own
  transition log is then read for the key. `invoke/finish!` stamps a
  present key whether or not the action is idempotent, which is the
  whole mechanism — so if this fails, what failed is the convention's
  spelling or that stamp, and the metric behind it (`feed/
  actions-from-feed`) is reading a column that nothing fills.

  It reports `:covered`, because an engine whose feed happens to offer
  no one-tap verb proves nothing here and should say so rather than
  pass quietly.

  The candidate must MOVE the row: an idempotent verb whose declared
  :to is the row's current state natural-replays — 200, envelope
  unchanged, no transition appended — so the key would land nowhere
  and the probe would blame the convention for the replay door doing
  its job (waymark-iqa.12). Only a verb whose effect leaves the
  card's own state proves the stamp."
  [ctx cards day]
  (let [nonce (subs (str (random-uuid)) 0 8)
        candidate (first (for [c cards
                               [wname e] (:actions c)
                               :when (and (= "assent" (str (:effort e)))
                                          (some? (get-in e [:effect :to]))
                                          (not= (str (get-in e [:effect :to]))
                                                (str (:state c))))]
                           [c wname]))]
    (if-not candidate
      {:covered 0 :violations []}
      (let [[c wname] candidate
            kind (keyword (:kind c))
            id (id-of (:self c))
            aname (declared-name ctx kind wname)
            key' (feed/origin-key day (str (:card_id c)) nonce)
            resp (invoke-http ctx kind id aname nil
                              {:headers {"idempotency-key" key'}})
            landed (when (= 200 (:status resp))
                     (some #(when (= key' (:idempotency-key %)) %)
                           (transitions ctx kind id)))
            parsed (feed/origin-of key')]
        {:covered (if landed 1 0)
         :violations
         (cond-> []
           (not= 200 (:status resp))
           (conj (str "feed: invoking " (name aname) " from card "
                      (:card_id c) " answered " (:status resp)
                      " — a card verb is an ordinary invoke through the"
                      " row's own action href: " (pr-str (json ctx resp))))

           (and (= 200 (:status resp)) (nil? landed))
           (conj (str "feed: a verb invoked with " (pr-str key')
                      " left no transition carrying that key — the origin"
                      " rides the Idempotency-Key column, and"
                      " actions-from-the-feed is reading a column nothing"
                      " filled"))

           (nil? parsed)
           (conj (str "feed: " (pr-str key') " is the convention's own"
                      " spelling and feed/origin-of does not recognize it"))

           (and parsed (not= [(str (:section c)) (str (:kind c)) id]
                             [(:section parsed) (:kind parsed) (:id parsed)]))
           (conj (str "feed: the origin key parses to " (pr-str parsed)
                      " but the card is " (pr-str [(:section c) (:kind c) id])
                      " — section, kind and id come back out of the audit"
                      " trail with no join, or they come back wrong")))}))))

(defn- feed-verbs-are-light-violations
  "waymark-iqa.3, whole: the ≤-selection rule as the READ-TIME
  PROJECTION it is, plus the origin convention that makes
  actions-from-the-feed a number somebody can ask for.

  There is nothing to prove at declaration time and no battery to
  extend — a kind's composition actions are legitimate on the row's
  own screen, so a declaration-time check would have to refuse law
  that is correct. The projection IS the enforcement, and this is
  where it is judged: on a live answer, from the wire, through two
  principals."
  [ctx]
  (let [{:keys [doc]} (feed-doc ctx nil)
        cards (feed-row-cards doc)
        origin (feed-origin-violations ctx cards (str (:day doc)))]
    {:covered (:covered origin)
     :violations (into (feed-light-violations cards)
                       (concat (feed-concealed-violations ctx)
                               (:violations origin)))}))

;; ── the tickler (waymark-iqa.4) ─────────────────────────────────────
;;
;; The epic's two sentences, proved over the wire against whatever
;; tickler an application declared: a not-now returns LATER, NOT
;; TOMORROW, and a let-go item NEVER RETURNS. Neither is judgeable at
;; declaration time — the first is a handler's arithmetic and the
;; second is a query's silence — and scenario.clj is explicit that a
;; scenario never writes, so the writing half lives here. The check
;; tier keeps the halves it can: workqueue10's tickler declares three
;; scenarios and `make check-queue` judges them with no database.
;;
;; This obligation judges the LAW and never the schedule. How far a
;; house pushes its first not-now is the house's to declare (a week
;; in workqueue10); that it is further out than TOMORROW is the
;; epic's, and that is what is asserted.

(defn- tickler-row [ctx id]
  (json ctx (get-env ctx :tickler id)))

(defn- make-tickler!
  "One tickler over a named subject, through its own create door."
  [ctx what subject-kind subject-id]
  (let [resp (req ctx :post (str "/api/" (:plural (rdef ctx :tickler)))
                  {:what what
                   :subject_kind (name subject-kind)
                   :subject_id (str subject-id)})]
    {:status (:status resp) :doc (json ctx resp)}))

(defn- tickler-card [doc id]
  (some #(when (= (str "decide/tickler/" id) (str (:card_id %))) %)
        (feed-cards doc)))

(defn- feed-tickler-violations
  "A dropped thing comes back, backs off when it is put off, and stops
  for good when it is let go — from the wire, in that order.

  The subject is read off the feed's own first card ABOVE THE SEAM, so
  the marker points at a row this engine really serves and really has
  not finished: `set-aside?` is the retirement rule and a fixture that
  ignored it would be a fixture testing nothing. do-now and decide are
  the sections whose rows are still open by construction — waymark-
  iqa.5's fuel and archive cards are finished work, and a marker over
  one of those would retire at offer time exactly as it should, which
  would make this obligation fail for being right. The second marker
  names a row that does not exist, which is the same rule from the
  other side — a marker may outlive its subject, and when it does it
  says nothing rather than carding a ghost.

  It reports `:covered`, because an engine whose feed has no row card
  at all has nothing to set aside and should say so rather than pass
  quietly."
  [ctx]
  (let [{:keys [doc]} (feed-doc ctx nil)
        subject (first (remove #(or (= "tickler" (str (:kind %)))
                                    (not (above-seam (str (:section %)))))
                               (feed-row-cards doc)))]
    (if-not subject
      {:covered 0 :violations []}
      (let [day (str (:day doc))
            skind (keyword (str (:kind subject)))
            sid (id-of (:self subject))
            made (make-tickler! ctx "The porch railing, one of these days"
                                skind sid)
            id (some-> (:self (:doc made)) id-of)
            ghost (make-tickler! ctx "A row this house no longer has"
                                 skind "01HZZZZZZZZZZZZZZZZZZZZZZZ")
            ghost-id (some-> (:self (:doc ghost)) id-of)
            offered (:doc (feed-doc ctx nil))
            card (when id (tickler-card offered id))
            verbs (set (map (comp name key) (:actions card)))
            not-now (declared-name ctx :tickler :not_now)
            let-go (declared-name ctx :tickler :let_it_go)
            pushed (when (and id card)
                     (invoke-http ctx :tickler id not-now nil
                                  {:headers {"idempotency-key"
                                             (feed/origin-key
                                              day (str (:card_id card))
                                              (subs (str (random-uuid)) 0 8))}}))
            after (when (= 200 (:status pushed)) (tickler-row ctx id))
            when-back (some-> (get-in after [:data :next_offer_at]) str)
            tomorrow (str (.plusSeconds ^java.time.Instant
                                        ((:now-fn (:engine ctx)))
                                        (* 86400 1)))
            backed-off (:doc (feed-doc ctx nil))
            gone (when (= 200 (:status pushed))
                   (invoke-http ctx :tickler id let-go nil))
            after-go (when (= 200 (:status gone)) (tickler-row ctx id))
            again (when (= 200 (:status gone))
                    (invoke-http ctx :tickler id not-now nil))]
        {:covered (if (= 200 (:status gone)) 1 0)
         :violations
         (cond-> []
           (not= 201 (:status made))
           (conj (str "feed: creating a tickler over " (name skind) " " sid
                      " answered " (:status made) " — the tickler's own"
                      " create door is how a set-aside item is born: "
                      (pr-str (:doc made))))

           (and (= 201 (:status made)) (nil? card))
           (conj (str "feed: a tickler created with no next_offer_at did not"
                      " reach the feed — unset means NOW, and a marker on"
                      " the fridge that the fridge does not show is a"
                      " someday/maybe list nobody reads. Cards: "
                      (pr-str (mapv :card_id (feed-cards offered)))))

           (and card (not= "decide" (str (:section card))))
           (conj (str "feed: the tickler card is in section "
                      (pr-str (:section card)) " — a tickler is something"
                      " to DECIDE, and the census puts it there"))

           (and card (not (contains? verbs (name not-now))))
           (conj (str "feed: the tickler card offers " (pr-str (sort verbs))
                      " and not " (pr-str (name not-now))
                      " — 'not now' is the verdict the whole surface exists"
                      " for, and it must be under the thumb"))

           (and card (not (contains? verbs (name let-go))))
           (conj (str "feed: the tickler card offers " (pr-str (sort verbs))
                      " and not " (pr-str (name let-go))
                      " — an item you cannot let go of is guilt with a"
                      " scroll bar"))

           (and ghost-id (tickler-card offered ghost-id))
           (conj (str "feed: a tickler naming a row this engine does not"
                      " serve was carded anyway — a marker may outlive its"
                      " subject, and when it does it retires AT OFFER TIME"
                      " rather than asking about a ghost"))

           (and card (not= 200 (:status pushed)))
           (conj (str "feed: 'not now' from the card answered "
                      (:status pushed) ": " (pr-str (json ctx pushed))))

           (and after (not= 1 (get-in after [:data :offer_count])))
           (conj (str "feed: one 'not now' left offer_count "
                      (pr-str (get-in after [:data :offer_count]))
                      " — the household record IS the count ('I said"
                      " not-now twice' is a fact the house keeps, not a"
                      " thing one person half-remembers)"))

           (and after (or (str/blank? when-back) (neg? (compare when-back tomorrow))))
           (conj (str "feed: 'not now' set the next offer to "
                      (pr-str when-back) ", which is not past " tomorrow
                      " — a not-now returns LATER, NOT TOMORROW; a tickler"
                      " that came back in the morning is a nag, and a"
                      " household learns to dismiss a nag unread"))

           (and after (tickler-card backed-off id))
           (conj "feed: a tickler that was just put off is still on the feed
                  — the backoff is the read-side query, so a pushed-out
                  marker is simply not a candidate")

           (and (= 200 (:status pushed)) (not= 200 (:status gone)))
           (conj (str "feed: 'let it go' answered " (:status gone) ": "
                      (pr-str (json ctx gone))))

           (and after-go (not= "let_go" (str (:state after-go))))
           (conj (str "feed: after 'let it go' the marker is in state "
                      (pr-str (:state after-go)) " — letting go is terminal"))

           (and again (not= 409 (:status again)))
           (conj (str "feed: 'not now' on a let-go tickler answered "
                      (:status again) ", not 409 — a let-go item never"
                      " returns, and the machine itself is what refuses"
                      " the question")))}))))

;; ── the insight: no finding without a citation and an offer ─────────
;;
;; waymark-iqa.6's obligation, and the pack's new LAST one for the
;; same reason `:feed/ticklers` sits below the counting obligations:
;; it MINTS rows, and a minted finding is a card. It mints more than
;; any other obligation does, because the daily cap is only provable
;; by filling it.
;;
;; TWO PRINCIPALS, and they are not decoration. The four-eyes wall is
;; the whole of 'it only ever offers', so the author has to be
;; somebody other than the reader or the law cannot be watched doing
;; its work: the finder publishes, the walker reads the feed and
;; answers, and the finder's own attempt to answer is refused by name.

;; A FRESH FINDER EVERY RUN, and it is not hygiene — it is the only
;; way to ask the cap a question. A daily cap counts ROWS, so unlike
;; the in-process pacing atoms it survives a restart: an obligation
;; with a fixed author id would spend the allowance on its first run
;; and be refused at its first create on every run after, in a suite
;; whose fixture is under no obligation to drop that table. A new
;; author has a new allowance by the law's own shape, which is exactly
;; what the claim below wants to watch fill.
(defn- finder-headers []
  {"x-waymark-principal" (str "conformance-finder-"
                              (subs (str (random-uuid)) 0 8))
   "x-waymark-actor-type" "agent"})

(defn- make-insight!
  "One finding, through its own create door, as the finder."
  [ctx hs body]
  (let [resp (req ctx :post (str "/api/" (:plural (rdef ctx :insight)))
                  body hs)]
    {:status (:status resp) :doc (json ctx resp)}))

(defn- refused-guard
  "The guard a 409 named, or nil for anything that is not a guard
  refusal — the same read `wire-verdict` makes, at one claim's scale.
  Takes the {:status :doc} shape the makers here answer."
  [{:keys [status doc]}]
  (when (and (= 409 status)
             (str/ends-with? (str (:type doc)) "guard-refused"))
    (keyword (str (:guard doc)))))

(defn- insight-card [doc id]
  (some #(when (= (str "decide/insight/" id) (str (:card_id %))) %)
        (feed-cards doc)))

(defn- feed-insight-violations
  "A finding cites what it read, offers something the house can tap,
  runs out after three a day, and is answered by somebody other than
  whoever found it — from the wire, in that order.

  The subject is the feed's own first row card ABOVE THE SEAM, the
  same fixture `:feed/ticklers` uses and for the same reason: the
  offer has to name a row this engine really serves and really has not
  finished, because `feed/set-aside?` retires a finding whose offer is
  over. The citation is that row's address too — a real one, so the
  registry consult in `cites-what-it-claims` is answering about
  something rather than about nothing.

  The ≤-selection door is proved with the card's OWN `heavier` entry
  when it has one: `.3`'s partition already named a verb of that kind
  that is too heavy for a thumb, and offering it is the one place in
  the tree where that rule refuses at a door rather than moving a
  button. A kind with no heavy verb skips that claim rather than
  inventing one.

  It reports `:covered`, because an engine with no insight kind, or a
  feed with no row card at all, has nothing to find and should say so
  rather than pass quietly."
  [ctx]
  (let [{:keys [doc]} (feed-doc ctx nil)
        subject (first (remove #(or (= "insight" (str (:kind %)))
                                    (not (above-seam (str (:section %)))))
                               (feed-row-cards doc)))]
    (if-not subject
      {:covered 0 :violations []}
      (let [day (str (:day doc))
            hs (finder-headers)
            skind (str (:kind subject))
            self (str (:self subject))
            sid (id-of self)
            light (some-> (first (sort (keys (:actions subject)))) name)
            heavy (some-> (first (:heavier subject)) :name str not-empty)
            offer {:offer_kind skind :offer_id sid :offer_href self}
            finding (fn [text extra]
                      (merge {:finding text :evidence [self]} offer extra))
            ;; 1. no citation, no publish
            uncited (make-insight!
                     ctx hs (dissoc (finding "Nothing is behind this one" nil)
                                    :evidence))
            ;; 2. a citation this house cannot follow
            unfollowable (make-insight!
                          ctx hs
                          (assoc (finding "This cites a house we do not live in" nil)
                                 :evidence
                                 ["/api/nowheres/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]))
            ;; 3. no offered action, no publish
            offerless (make-insight!
                       ctx hs {:finding "An observation with no next step"
                               :evidence [self]})
            ;; 4. an offer heavier than a tap — the one declaration-time
            ;; ≤-selection door, proved with the card's own heavy verb
            too-heavy (when heavy
                        (make-insight! ctx hs
                                       (finding "This one wants a keyboard"
                                                {:offer_action heavy})))
            ;; 5. THE CAP, discovered rather than known. HOW MANY a
            ;; house allows is the house's to declare — the obligation
            ;; judges the LAW (that there IS a ceiling, and that the
            ;; one past it is refused by name) exactly as
            ;; `:feed/ticklers` judges the backoff without pinning the
            ;; schedule. The bound is a bound and not a cap: a house
            ;; that would publish twenty findings a day has not
            ;; declared one.
            filled (when light
                     (loop [n 1 out []]
                       (let [r (make-insight!
                                ctx hs
                                (finding (str "The house has not looked at this"
                                              " in a while, and here is the"
                                              " next step (" n ")")
                                         {:offer_action light}))
                             out (conj out r)]
                         (if (and (= 201 (:status r)) (<= n 20))
                           (recur (inc n) out)
                           out))))
            landed (filterv #(= 201 (:status %)) filled)
            over (peek filled)
            mine (into #{} (keep #(some-> (:self (:doc %)) id-of)) landed)
            ;; the walker's own feed: the finder's findings are the
            ;; walker's to answer, and the finder's own are not.
            ;; WHICHEVER of them the day's order put on the page is the
            ;; one answered — the recipe's `:take` is smaller than the
            ;; cap on purpose, and hash(seed ‖ card_id) decides which
            ;; two of three a member reads today. An obligation that
            ;; insisted on the FIRST one published would be asserting a
            ;; ranking the third law forbids.
            offered (:doc (feed-doc ctx nil))
            card (first (filter #(and (= "insight" (str (:kind %)))
                                      (contains? mine (id-of (:self %))))
                                (feed-cards offered)))
            first-id (some-> (:self card) id-of)
            finder-feed (:doc (feed-doc ctx hs))
            take' (declared-name ctx :insight :take)
            self-answer (when first-id
                          (invoke-http ctx :insight first-id take' nil
                                       {:headers hs}))
            taken (when card
                    (invoke-http ctx :insight first-id take' nil
                                 {:headers {"idempotency-key"
                                            (feed/origin-key
                                             day (str (:card_id card))
                                             (subs (str (random-uuid)) 0 8))}}))
            after (when (= 200 (:status taken))
                    (:doc (feed-doc ctx nil)))]
        {:covered (if (= 200 (:status taken)) 1 0)
         :violations
         (cond-> []
           (not= :cites-what-it-claims (refused-guard uncited))
           (conj (str "feed: a finding with no evidence answered "
                      (:status uncited) " — no citation, no publish is the"
                      " compiler's first wall, and a claim nobody can check"
                      " is a claim nobody should act on: "
                      (pr-str (:doc uncited))))

           (not= :cites-what-it-claims (refused-guard unfollowable))
           (conj (str "feed: a finding citing /api/nowheres/… answered "
                      (:status unfollowable) " — an address naming a"
                      " collection this house does not serve is not a"
                      " citation, it is a shape: " (pr-str (:doc unfollowable))))

           (not= :offers-something-light (refused-guard offerless))
           (conj (str "feed: a finding with no offered action answered "
                      (:status offerless) " — every insight carries the one"
                      " physical next step, and one that only observes is a"
                      " notification: " (pr-str (:doc offerless))))

           (and heavy
                (not= :offers-something-light (refused-guard too-heavy)))
           (conj (str "feed: a finding offering " skind "." heavy
                      " answered " (:status too-heavy) " — that verb is on"
                      " the card's own `heavier` list, so the house already"
                      " says it does not fit under a thumb; the offer door"
                      " is the one place that rule refuses rather than"
                      " moves a button: " (pr-str (:doc too-heavy))))

           (and light (empty? landed))
           (conj (str "feed: not one well-formed finding could be published"
                      " — the cap is a ceiling and not a floor: "
                      (pr-str (mapv :doc filled))))

           (and over (not= :insights-are-capped (refused-guard over)))
           (conj (str "feed: " (count landed) " findings landed and the next"
                      " answered " (:status over) " — a hard daily cap is"
                      " what makes a compiler RANK rather than dump, and a"
                      " surface that can be filled is a surface that will"
                      " be: " (pr-str (:doc over))))

           (and (seq mine) (nil? card))
           (conj (str "feed: " (count mine) " findings were published and not"
                      " one of them reached the feed. Cards: "
                      (pr-str (mapv :card_id (feed-cards offered)))))

           (and card (not= "decide" (str (:section card))))
           (conj (str "feed: the insight card is in section "
                      (pr-str (:section card)) " — a finding is something to"
                      " DECIDE, and the census puts it there"))

           (and card (not (contains? (set (map (comp name key) (:actions card)))
                                     (name take'))))
           (conj (str "feed: the insight card offers "
                      (pr-str (sort (map (comp name key) (:actions card))))
                      " and not " (pr-str (name take'))
                      " — both answers are note-free precisely so both stay"
                      " under the thumb; a verdict with a note is a `recall`"
                      " demand and split-verbs moves it to `heavier`"))

           (and card (str/blank? (str (get-in card [:links :offer :href]))))
           (conj (str "feed: the insight card carries no offer link — the"
                      " offer is an ADDRESS rather than a trigger, and a"
                      " card that cannot reach it is a finding with nowhere"
                      " to go: " (pr-str (:links card))))

           (and first-id (insight-card finder-feed first-id))
           (conj (str "feed: the finder's own finding is on the finder's own"
                      " feed — the four-eyes wall means an author is"
                      " structurally incapable of accepting it, so carding"
                      " it there would be offering a door that answers 409"))

           (and self-answer (not= 409 (:status self-answer)))
           (conj (str "feed: the finder answering its own finding got "
                      (:status self-answer) ", not 409 — 'it only ever"
                      " offers' is a wall the compiler cannot walk through,"
                      " not a policy it is trusted to keep"))

           (and self-answer
                (= 409 (:status self-answer))
                (not= :the-finder-does-not-decide
                      (refused-guard {:status 409
                                      :doc (json ctx self-answer)})))
           (conj (str "feed: the finder's own attempt was refused by "
                      (pr-str (:guard (json ctx self-answer)))
                      ", not the four-eyes wall"))

           (and card (not= 200 (:status taken)))
           (conj (str "feed: 'do it' from the card answered "
                      (:status taken) ": " (pr-str (json ctx taken))))

           (and after (insight-card after first-id))
           (conj (str "feed: an answered finding is still on the feed —"
                      " taken and dismissed are terminal, so an answered"
                      " insight leaves by construction")))}))))

;; ── the preview (waymark-iqa.23) ────────────────────────────────────
;;
;; The capability whose enforcement point is this engine, judged the
;; only way it can be: from THREE identities through one door. A
;; member to be previewed, a previewer holding the grant, and the
;; previewed member reading their own feed as the answer key — because
;; "the preview shows exactly what the member sees" is not a claim one
;; principal can check, and a preview computed for the CALLER would
;; pass every single-principal assertion anybody would think to write.

(defn- get-with-query
  "One GET carrying a query string. `req` has no query half — it never
  needed one — and `feed-doc` has been reaching past it into
  `(:handler ctx)` since waymark-iqa.2; this is that same reach, named
  once so the third caller does not open it a third time."
  [ctx uri query]
  ((:handler ctx) {:request-method :get :uri uri :query-string query
                   :headers (:walker-headers ctx)}))

(defn- ensure-preview-capability!
  "The registry row, ensured. A capability is a ROW — the ask machinery
  judges a dotted token against the ACTIVE registry — so an obligation
  that minted the grant without minting the row would be watching the
  scope guard refuse and calling it the feed's answer. Idempotent by
  the same query the deployment's boot seed uses, so a suite run twice
  against one database does not accumulate rows."
  [ctx]
  (let [existing (json ctx (get-with-query ctx "/api/capabilities"
                                           (str "token="
                                                cap/feed-preview-as-token)))]
    (if (pos? (long (get-in existing [:data :total] 0)))
      true
      (= 201 (:status (req ctx :post "/api/capabilities"
                           cap/feed-preview-as))))))

(defn- mint-preview-grant!
  "One `feed.preview_as` grant, offered to `audience` and accepted by
  it. `member` nil is the UNFILTERED grant the door refuses — minted
  on purpose, because 'an unfiltered grant is refused at the door' is
  a claim about the door and not about the mint, and the mint has to
  succeed for the door to get its turn."
  [ctx audience member]
  (let [made (req ctx :post "/api/grants"
                  {:audience audience
                   :scope [(cond-> {:kind cap/feed-preview-as-token
                                    :actions []}
                             member (assoc :filter {:member member}))]})]
    (when (= 201 (:status made))
      (let [gid (id-of (:self (json ctx made)))
            hs {"x-waymark-principal" audience
                "x-waymark-actor-type" "agent"}]
        (when (= 200 (:status ((:invoke ctx) :grant gid :accept {}
                               {:headers hs})))
          {:gid gid :headers (assoc hs "x-waymark-grant" gid)})))))

(defn- feed-preview-violations
  "waymark-iqa.23, whole: the grant opens the door, the door answers
  the MEMBER's feed, the document says so out loud, and the previewer
  is still only themselves.

  Five claims, and the third is the one the other four exist to
  protect:

  1. **The capability is the door.** The same previewer, same member,
     with the grant header dropped, is refused — and the refusal NAMES
     `feed.preview_as`, because capabilities are words and an agent
     that reads the sentence knows how to ask.
  2. **The filter is the constraint.** A grant naming the token with
     no filter is refused (absent-means-any is not this door's
     reading), and a grant filtered to one member refuses a request
     for another.
  3. **The preview is the member's own read, card for card.** Asserted
     against the member reading their own feed through the same door,
     in the same second — not against a shape, not against a count.
     The previewer's OWN feed is compared too: it holds one narrow
     capability grant and nothing else, so if the preview ever turned
     out to be the caller's feed wearing a stamp, these two would
     match and the member's would not.
  4. **It is never silent.** `preview.of`, `preview.by`, the summary
     and a note, all four.
  5. **The verbs are the member's, and only the member's.** A verb
     rendered on a previewed card is invoked BY THE PREVIEWER at the
     member's own door, and must not land: the router judges the
     actual caller, whose leash names one capability and no kind at
     all. This is the reason the verbs may render at all, so it is
     proved rather than asserted in a docstring.

  It reports `:covered`, because an engine whose previewed member has
  no card carrying a verb has not watched claim 5 happen and should
  say so rather than pass quietly."
  [ctx]
  (if-not (ensure-preview-capability! ctx)
    [(str "feed: the " cap/feed-preview-as-token " capability row could not"
          " be created — a dotted token no active registry row carries"
          " refuses at the ask, so the preview obligation has no power to"
          " grant")]
    (let [tag (subs (str (random-uuid)) 0 8)
          made (req ctx :post (str "/api/" (:plural (rdef ctx :member)))
                    {:display (str "preview-subject-" tag)
                     :actor_type "human"})
          member (some-> (:self (json ctx made)) id-of)
          as-member {"x-waymark-principal" member}
          previewer (str "feed-preview-probe-" tag)
          unfiltered-probe (str "feed-preview-wide-" tag)
          held (when member (mint-preview-grant! ctx previewer member))
          wide (mint-preview-grant! ctx unfiltered-probe nil)
          q (str "preview_as=" member)
          ;; the answer key and the answer, read in that order
          theirs (when member (feed-doc ctx as-member))
          preview (when held (feed-doc ctx (:headers held) q))
          own (when held (feed-doc ctx (:headers held)))
          bare (when held (feed-doc ctx (dissoc (:headers held)
                                                "x-waymark-grant") q))
          stranger (when held
                     (feed-doc ctx (:headers held)
                               "preview_as=01HZZZZZZZZZZZZZZZZZZZZZZZ"))
          too-wide (when wide
                     (feed-doc ctx (:headers wide) q))
          ids (fn [r] (mapv :card_id (feed-cards (:doc r))))
          doc (:doc preview)
          notes (str/join " " (map str (:notes doc)))
          ;; claim 5: a verb off a previewed card, fired by the previewer
          verb (first (for [c (feed-row-cards doc)
                            [aname _] (:actions c)]
                        [c aname]))
          poked (when verb
                  (let [[c aname] verb]
                    (invoke-http ctx (keyword (:kind c)) (id-of (:self c))
                                 (declared-name ctx (keyword (:kind c)) aname)
                                 nil {:headers (:headers held)})))]
      {:covered (if poked 1 0)
       :violations
       (cond-> []
         (nil? member)
         (conj (str "feed: the preview obligation could not mint a member to"
                    " preview (" (:status made) "): " (pr-str (json ctx made))))

         (and member (nil? held))
         (conj (str "feed: a " cap/feed-preview-as-token " grant filtered to"
                    " {member " (pr-str member) "} could not be minted and"
                    " accepted — the registry row exists, so what refused is"
                    " the scope machinery the capability rides"))

         (nil? wide)
         (conj (str "feed: an UNFILTERED " cap/feed-preview-as-token
                    " grant could not be minted — it must mint and be"
                    " refused at the DOOR, which is where the decision"
                    "'absent does not mean any member' actually lives"))

         (and preview (not= 200 (:status preview)))
         (conj (str "feed: a previewer wearing an accepted, correctly"
                    " filtered grant was answered " (:status preview) ": "
                    (pr-str (:doc preview))))

         (and preview (= 200 (:status preview)) theirs
              (not= (ids preview) (ids theirs)))
         (conj (str "feed: the preview is not the member's own feed.\n  "
                    "preview: " (pr-str (ids preview)) "\n  theirs:  "
                    (pr-str (ids theirs))
                    "\nThe preview must be computed FOR the member, through"
                    " the member's own sight, by the same code path — a"
                    " second definition of what a member can see is correct"
                    " on the day it is written and wrong ever after"))

         (and preview (= 200 (:status preview)) own theirs
              (not= (ids preview) (ids theirs))
              (= (ids preview) (ids own)))
         (conj (str "feed: the preview is the PREVIEWER's own feed wearing"
                    " a stamp — every card matches the caller's and none"
                    " matches the member's"))

         (and doc (not= member (str (get-in doc [:preview :of :id]))))
         (conj (str "feed: the document says it previews "
                    (pr-str (get-in doc [:preview :of])) " and the request"
                    " named " (pr-str member) " — a preview that will not"
                    " say whose feed it is is an impersonation with better"
                    " manners"))

         (and doc (not= previewer (str (get-in doc [:preview :by :id]))))
         (conj (str "feed: the document says it is read by "
                    (pr-str (get-in doc [:preview :by])) ", not by "
                    (pr-str previewer)))

         (and doc (not (str/includes? (str (:summary doc)) "PREVIEW")))
         (conj (str "feed: the summary does not say PREVIEW: "
                    (pr-str (:summary doc)) " — the stamp has to survive a"
                    " client that renders one line"))

         (and doc (not (str/includes? notes cap/feed-preview-as-token)))
         (conj (str "feed: no note names " cap/feed-preview-as-token
                    " — the reader of a previewed document must be able to"
                    " learn what it is from the document: " (pr-str notes)))

         (and bare (not= 403 (:status bare)))
         (conj (str "feed: the same previewer with no grant presented was"
                    " answered " (:status bare) ", not 403 — the capability"
                    " IS the door, and a preview that worked without one"
                    " would make the grant decoration"))

         (and bare (= 403 (:status bare))
              (not (str/includes? (str (get-in bare [:doc :detail]))
                                  cap/feed-preview-as-token)))
         (conj (str "feed: the refusal never names the capability: "
                    (pr-str (get-in bare [:doc :detail]))
                    " — an agent that cannot learn what to ask for cannot"
                    " ask"))

         (and stranger (not= 403 (:status stranger)))
         (conj (str "feed: a grant filtered to " (pr-str member)
                    " previewed a DIFFERENT member and was answered "
                    (:status stranger) " — the filter is the constraint this"
                    " enforcement point interprets, and a constraint that"
                    " admits everybody is not one"))

         (and too-wide (not= 403 (:status too-wide)))
         (conj (str "feed: an UNFILTERED " cap/feed-preview-as-token
                    " grant previewed a member and was answered "
                    (:status too-wide) " — absent-means-any is exactly the"
                    " grant a tired human approves without reading, and the"
                    " whole value of a capability over a role is that the"
                    " approval names the thing"))

         (and poked (= 200 (:status poked)))
         (conj (str "feed: the previewer invoked " (name (second verb))
                    " on the previewed card " (pr-str (:card_id (first verb)))
                    " and it LANDED — a previewed card's verbs are the"
                    " member's doors rendered for truth, and the router"
                    " judges the actual caller at every one of them; a"
                    " preview that could also ACT would be impersonation"
                    " with a stamp on it")))})))

;; ── the recipe is a row (waymark-4yn) ───────────────────────────────
;;
;; The bead's whole claim, over the wire, in one walk: a household
;; changes the order it reads in WITHOUT A DEPLOY, the document says
;; which recipe answered, a mid-day revise lands on the next read, an
;; AGENT cannot write one, and a recipe that would not assemble is
;; refused at the door rather than at a boot nobody watched.
;;
;; The seam's sentence is the observable, and it is chosen because it
;; is the one recipe field that appears VERBATIM on a card: the seam
;; card is the recipe's own words, so "the feed changed" needs no
;; inference about which rows a population happened to hold today.

(defn- recipe-source [doc] (get-in doc [:recipe :source]))

(defn- seam-sentence [doc]
  (some #(when (= "seam" (str (:card_id %))) (str (:sentence %)))
        (feed-cards doc)))

(defn- make-recipe!
  "One recipe, through its own create door, as whoever the headers
  name."
  [ctx body & [hs]]
  (let [resp (req ctx :post (str "/api/" (:plural (rdef ctx :feed_recipe)))
                  body hs)]
    {:status (:status resp) :doc (json ctx resp)}))

(defn- mint-recipe-grant!
  "A leash over feed_recipe that NAMES the write doors, offered to one
  composer. The wall this obligation is about is not concealment — an
  agent holding no grant is already answered 404 by the router's
  default deny, which proves nothing about the recipe — so the agent
  is given exactly the grant a careless human might approve, and the
  refusal it then meets is the one the bead is about."
  [ctx audience]
  (let [made (req ctx :post "/api/grants"
                  {:audience audience
                   :scope [{:kind "feed_recipe" :actions ["create" "revise"]}]})]
    (when (= 201 (:status made)) (id-of (:self (json ctx made))))))

(defn- reseam
  "The house's current order with one word changed — the seam's own
  sentence. `recipe.order` is the document's copy of the order in the
  EDITOR's shape (the create-from-current affordance), so this is
  literally what a person does: read the order you have, edit one
  line, post it back."
  [doc sentence]
  (mapv (fn [l] (cond-> l (= "seam" (str (:section l)))
                        (assoc :sentence sentence)))
        (get-in doc [:recipe :order])))

(defn- feed-recipe-violations
  [ctx]
  (let [before (:doc (feed-doc ctx nil))
        was (seam-sentence before)
        tag (subs (str (random-uuid)) 0 8)
        first-words (str "That's the house, caught up · " tag)
        second-words (str "Everything the house had, and that is all · " tag)
        made (make-recipe! ctx {:label (str "Conformance order " tag)
                                :scope "household"
                                :order (reseam before first-words)})
        id (some-> (:self (:doc made)) id-of)
        after (when id (:doc (feed-doc ctx nil)))
        revise (declared-name ctx :feed_recipe :revise)
        retire (declared-name ctx :feed_recipe :retire)
        revised (when id
                  (invoke-http ctx :feed_recipe id revise
                               {:label (str "Conformance order " tag)
                                :order (reseam before second-words)}))
        mid-day (when (= 200 (:status revised)) (:doc (feed-doc ctx nil)))
        ;; the third law's wall: a composer HOLDING A RECIPE-WRITE
        ;; GRANT may still not edit the frame it is composed into
        composer (str "conformance-composer-" tag)
        as-composer {"x-waymark-principal" composer
                     "x-waymark-actor-type" "agent"}
        gid (mint-recipe-grant! ctx composer)
        took (when gid ((:invoke ctx) :grant gid :accept {}
                        {:headers as-composer}))
        leashed (when (= 200 (:status took))
                  (assoc as-composer "x-waymark-grant" gid))
        by-agent (when leashed
                   (make-recipe! ctx {:label (str "Findings first " tag)
                                      :scope "household"
                                      :order (reseam before "Caught up.")}
                                 leashed))
        ;; and the four assembly checks, at the door rather than the boot
        twice (make-recipe! ctx {:label (str "Twice caught up " tag)
                                 :scope "household"
                                 :order (conj (vec (reseam before "Caught up."))
                                              {:section "seam"
                                               :sentence "Really, caught up."})}
                            nil)
        ;; …and the way back, before anything below reads a feed again
        gone (when id (invoke-http ctx :feed_recipe id retire nil))
        reverted (when (= 200 (:status gone)) (:doc (feed-doc ctx nil)))]
    {:covered (if (= 200 (:status gone)) 1 0)
     :violations
     (cond-> []
       (not= "built-in" (str (:source (recipe-source before))))
       (conj (str "feed: with no recipe row stored, the document's"
                  " recipe.source says " (pr-str (recipe-source before))
                  " — the built-in IS the household default until somebody"
                  " deliberately overrides it, and a document that will not"
                  " say which recipe answered cannot explain itself"))

       (empty? (get-in before [:recipe :order]))
       (conj (str "feed: recipe.order is empty — the order in the EDITOR's"
                  " shape is the create-from-current affordance, and without"
                  " it a first edit starts from a blank rectangle"))

       (not= 201 (:status made))
       (conj (str "feed: creating a household recipe answered "
                  (:status made) ": " (pr-str (:doc made))
                  " — a house that cannot change its own order without a"
                  " deploy is the bead"))

       (and after (not= first-words (seam-sentence after)))
       (conj (str "feed: the stored recipe's seam says "
                  (pr-str first-words) " and the feed answered "
                  (pr-str (seam-sentence after))
                  " — the row is the order, or it is decoration"))

       (and after (not= "household" (str (:source (recipe-source after)))))
       (conj (str "feed: a household row answered and the stamp says "
                  (pr-str (recipe-source after))))

       (and after id (not= id (str (:id (recipe-source after)))))
       (conj (str "feed: the stamp names recipe " (pr-str (:id (recipe-source after)))
                  " and the row that answered is " (pr-str id)
                  " — the stamp is what makes a mid-day edit visible"))

       (and after (nil? (:version (recipe-source after))))
       (conj (str "feed: the stamp carries no version: "
                  (pr-str (recipe-source after))
                  " — id and version together are what let a reader tell"
                  " which EDIT of the order they are looking at"))

       (and revised (not= 200 (:status revised)))
       (conj (str "feed: revising the recipe answered " (:status revised)
                  ": " (pr-str (json ctx revised))))

       (and mid-day (not= second-words (seam-sentence mid-day)))
       (conj (str "feed: a revise landed and the next read still says "
                  (pr-str (seam-sentence mid-day))
                  " — the recipe is resolved per READ and never cached,"
                  " because an editor whose change shows up tomorrow is"
                  " an editor that feels broken"))

       (nil? leashed)
       (conj (str "feed: a feed_recipe write grant could not be minted and"
                  " accepted for a composer (" (pr-str gid) ", accept "
                  (pr-str (:status took)) ") — the third law's wall is"
                  " untested without one, because an UNLEASHED agent is"
                  " already answered 404 by concealment and that proves"
                  " nothing about the recipe"))

       (= 201 (:status by-agent))
       (conj (str "feed: an AGENT holding a feed_recipe write grant created"
                  " one — a composer that can rewrite the order it is read"
                  " in is a ranking model editing its own editorial frame,"
                  " which is the one backdoor this whole surface exists to"
                  " keep shut. A grant is not supposed to be enough."))

       (and by-agent (not= :written-by-a-person (refused-guard by-agent)))
       (conj (str "feed: a leashed agent's create was refused by "
                  (pr-str (refused-guard by-agent)) " (" (:status by-agent)
                  "), not by the actor-type wall: " (pr-str (:doc by-agent))
                  " — a refusal that does not say WHY cannot point at the"
                  " lawful path, which is to publish an insight and let a"
                  " member answer it"))

       (and by-agent
            (not (str/includes? (str (:detail (:doc by-agent))) "insight")))
       (conj (str "feed: the agent refusal never names the lawful path: "
                  (pr-str (:detail (:doc by-agent)))))

       (= 201 (:status twice))
       (conj (str "feed: a recipe carrying two seams was STORED — the four"
                  " assembly checks moved to the doors, so a feed that says"
                  " 'that's everything' twice must refuse at the write and"
                  " never at the read"))

       (and (not= 201 (:status twice))
            (not= :the-assembly-checks-pass (refused-guard twice)))
       (conj (str "feed: a two-seam recipe was refused by "
                  (pr-str (refused-guard twice)) " (" (:status twice) "): "
                  (pr-str (:doc twice))))

       (and id (not= 200 (:status gone)))
       (conj (str "feed: retiring the recipe answered " (:status gone)
                  " — the way back is the row's own doors, and a tuning"
                  " that cannot be undone is not tuning"))

       (and reverted (not= "built-in" (str (:source (recipe-source reverted)))))
       (conj (str "feed: the recipe was retired and the stamp still says "
                  (pr-str (recipe-source reverted))))

       (and reverted was (not= was (seam-sentence reverted)))
       (conj (str "feed: after the retire the seam says "
                  (pr-str (seam-sentence reverted)) " and the deployment's"
                  " own order says " (pr-str was)
                  " — retiring the override is how a house goes back")))}))

;; ── the staged proposal (waymark-0k4) ───────────────────────────────
;;
;; The bead's whole claim, over the wire, in one walk: an AGENT that
;; may not write the feed's order stages an EXACT change to it, the
;; household reads the diff on a decide card, one tap applies it — and
;; the transition on the recipe names THE MEMBER WHO TAPPED. Then the
;; three walls that make that safe rather than merely convenient: the
;; stager cannot answer its own proposal, no agent may answer anybody
;; else's, and a proposal staged against an order that has since moved
;; refuses instead of writing over the top.
;;
;; THREE PRINCIPALS, and none of them is decoration. The composer
;; stages (holding a leash that covers proposals and NOT recipes); a
;; second agent stands in for the two-agent house; and a MEMBER — a
;; human actor type, not the walker's system one — is the only one who
;; can land the write, which is the sentence the actor proof reads.

(defn- composer-headers [tag]
  {"x-waymark-principal" (str "conformance-composer-" tag)
   "x-waymark-actor-type" "agent"})

(defn- member-headers [tag]
  {"x-waymark-principal" (str "conformance-member-" tag)
   "x-waymark-actor-type" "human"})

(defn- leash!
  "One grant over one kind's named doors, offered to an agent and
  accepted by it → the headers that present it, or nil when the mint
  or the acceptance refused.

  Every claim below about what an agent may not do is made by an agent
  HOLDING a leash, because an unleashed agent is already answered 404
  by the router's default deny and that proves nothing about any wall
  — 4yn's own obligation says it in the same words, one kind over. And
  that the recipe_proposal mint SUCCEEDS is itself half a claim: the
  kind is grantable (not one of the private own-surface trio), which
  it may be precisely because holding the grant confers no power over
  the feed's order at all."
  ([ctx audience kind actions]
   (leash! ctx audience [{:kind (name kind) :actions actions}]))
  ;; …and the whole SCOPE, for a leash that has to cover more than one
  ;; kind: waymark-jfv.4's composer stages a bundle and its pieces,
  ;; which is two create doors on two kinds, and a request presents
  ;; exactly one X-Waymark-Grant.
  ([ctx audience scope]
   (let [hs {"x-waymark-principal" audience "x-waymark-actor-type" "agent"}
         made (req ctx :post "/api/grants"
                   {:audience audience :scope scope})
         gid (when (= 201 (:status made)) (id-of (:self (json ctx made))))
         took (when gid ((:invoke ctx) :grant gid :accept {} {:headers hs}))]
     (when (= 200 (:status took)) (assoc hs "x-waymark-grant" gid)))))

(defn- stage-proposal!
  "One proposal, through its own create door, as whoever the headers
  name."
  [ctx body hs]
  (let [resp (req ctx :post (str "/api/" (:plural (rdef ctx :recipe_proposal)))
                  body hs)]
    {:status (:status resp) :doc (json ctx resp)}))

(defn- proposal-card [doc id]
  (some #(when (= (str "decide/recipe_proposal/" id) (str (:card_id %))) %)
        (feed-cards doc)))

(defn- newest-actor
  "Who moved this row last, read off the audit trail rather than off
  anything the write reported about itself. This is the whole actor
  proof: `ctx :invoke` hands the inner write the OUTER principal, so a
  recipe changed by an applied proposal must carry the member's name
  here — and would carry the composer's, or the engine's, if the
  cross-write had gone through a system actor the way the approvals
  effect does."
  [ctx kind id]
  ;; the log is newest-LAST — store/transitions' own contract
  (get-in (last (vec (transitions ctx kind id))) [:actor :id]))

(defn- create-actor
  "Who the log says CREATED this row — the create transition's actor
  rather than the newest one's, which is a different question the
  moment the kind is MIRRORED: a task pushed to its authority the
  instant it lands carries a sync transition on top, and
  `newest-actor` would then answer about the mirror instead of about
  the person whose tap made the row (waymark-jfv.4 found this from
  the other side)."
  [ctx kind id]
  (some (fn [t] (when (= :create (keyword (name (:action t))))
                  (get-in t [:actor :id])))
        (vec (transitions ctx kind id))))

(defn- feed-proposal-violations
  [ctx]
  (let [tag (subs (str (random-uuid)) 0 8)
        before (:doc (feed-doc ctx nil))
        source (recipe-source before)
        target (when (not= "built-in" (str (:source source)))
                 (str (:id source)))
        current (get-in before [:recipe :order])
        composer (get (composer-headers tag) "x-waymark-principal")
        as-composer (composer-headers tag)
        as-member (member-headers tag)
        ;; TWO LEASHES, ONE COMPOSER. One over the proposal doors it is
        ;; meant to walk through, one over the recipe doors it is not —
        ;; both the sort a household might carelessly approve, and the
        ;; claim worth making is that the second buys nothing at all.
        ;; (Two grants rather than one because a request presents one
        ;; X-Waymark-Grant; the composer is the same principal either
        ;; way, which is the whole point.)
        leashed (leash! ctx composer :recipe_proposal
                        ["create" "apply" "decline"])
        recipe-leash (leash! ctx composer :feed_recipe ["create" "revise"])
        ;; a SECOND agent, leashed to answer, standing in for the house
        ;; that runs two of them
        other (leash! ctx (str "conformance-other-" tag) :recipe_proposal
                      ["apply" "decline"])
        words (str "Everything the house had, and that is all · " tag)
        proposal-body (fn [order]
                        (cond-> {:proposal (str "The seam should say what this"
                                                " house says (" tag ")")
                                 :label (str "Proposed order " tag)
                                 :evidence [(str "/api/feed_recipes/dummy-" tag)]
                                 :current_order current
                                 :order order}
                          target (assoc :target_id target)))
        staged (when leashed
                 (stage-proposal! ctx (proposal-body (reseam before words))
                                  leashed))
        pid (some-> (:doc staged) :self id-of)
        ;; the agent wall, unchanged and re-proved from the same
        ;; principal in the same breath: the composer that CAN stage a
        ;; change still cannot write the recipe itself
        direct (when recipe-leash
                 (make-recipe! ctx {:label (str "Composer's own " tag)
                                    :scope "household"
                                    :order (reseam before words)}
                               recipe-leash))
        ;; who sees it, and who does not
        member-feed (when pid (:doc (feed-doc ctx as-member)))
        composer-feed (when pid (:doc (feed-doc ctx as-composer)))
        card (when pid (proposal-card member-feed pid))
        verbs (when card (set (map (comp name key) (:actions card))))
        apply' (declared-name ctx :recipe_proposal :apply)
        decline (declared-name ctx :recipe_proposal :decline)
        ;; the two walls on the answer
        self-answer (when pid
                      (invoke-http ctx :recipe_proposal pid apply' nil
                                   {:headers leashed}))
        agent-answer (when (and pid other)
                       (invoke-http ctx :recipe_proposal pid apply' nil
                                    {:headers other}))
        ;; …and the tap itself, as a person
        applied (when card
                  (invoke-http ctx :recipe_proposal pid apply' nil
                               {:headers as-member}))
        row (when (= 200 (:status applied))
              (json ctx (req ctx :get (str "/api/recipe_proposals/" pid))))
        landed (some-> (get-in row [:data :applied_to]) id-of)
        after (when landed (:doc (feed-doc ctx nil)))
        actor (when landed (newest-actor ctx :feed_recipe landed))
        ;; THE STALE REFUSAL. A second proposal staged against what the
        ;; house reads NOW, then the order moved out from under it by a
        ;; member's own revise — which is exactly the race the diff a
        ;; person read would otherwise be lying about.
        second-body (when after
                      {:proposal (str "And once more, differently (" tag ")")
                       :label (str "Proposed order again " tag)
                       :evidence [(str "/api/feed_recipes/" landed)]
                       :current_order (get-in after [:recipe :order])
                       :target_id landed
                       :order (reseam after (str "Caught up, again · " tag))})
        second-staged (when (and second-body leashed)
                        (stage-proposal! ctx second-body leashed))
        second-id (some-> (:doc second-staged) :self id-of)
        revise (declared-name ctx :feed_recipe :revise)
        moved (when second-id
                (invoke-http ctx :feed_recipe landed revise
                             {:label (str "Moved on " tag)
                              :order (reseam after (str "Moved on · " tag))}
                             {:headers as-member}))
        stale (when (= 200 (:status moved))
                (invoke-http ctx :recipe_proposal second-id apply' nil
                             {:headers as-member}))
        ;; …and the way back, before anything else reads a feed
        declined (when second-id
                   (invoke-http ctx :recipe_proposal second-id decline nil
                                {:headers as-member}))
        retire (declared-name ctx :feed_recipe :retire)
        gone (when landed (invoke-http ctx :feed_recipe landed retire nil))]
    {:covered (if (= 200 (:status applied)) 1 0)
     :violations
     (cond-> []
       (nil? leashed)
       (conj (str "feed: a recipe_proposal grant could not be minted and"
                  " accepted for a composer — recipe_proposal is meant to"
                  " be GRANTABLE (it is not one of the private"
                  " own-surface kinds), and it may be precisely because"
                  " holding the grant confers no power over the feed's"
                  " order; a kind an agent cannot be leashed to is a"
                  " staging door no MCP composer can reach"))

       (nil? recipe-leash)
       (conj (str "feed: a feed_recipe write grant could not be minted and"
                  " accepted for the same composer — the wall this"
                  " obligation is about is not concealment, so an"
                  " UNLEASHED agent's 404 would prove nothing"))

       (nil? other)
       (conj (str "feed: a second agent could not be leashed to the"
                  " proposal's answer doors — the two-agent house is where"
                  " a four-eyes wall alone would not have been enough"))

       (and leashed (not= 201 (:status staged)))
       (conj (str "feed: an AGENT holding a recipe_proposal grant staged a"
                  " change and got " (:status staged) ": "
                  (pr-str (:doc staged))
                  " — an agent may prepare an exact revision even though it"
                  " may not write one; that asymmetry IS the bead"))

       (and direct (= 201 (:status direct)))
       (conj (str "feed: the same composer wrote a feed_recipe directly —"
                  " the staging door is a way to ASK, never a way around"
                  " the wall, and a proposal kind that opened one would"
                  " have undone waymark-4yn instead of completing it"))

       (and direct (not= :written-by-a-person (refused-guard direct)))
       (conj (str "feed: the composer's direct recipe write was refused by "
                  (pr-str (refused-guard direct)) " (" (:status direct)
                  "), not by the actor-type wall: " (pr-str (:doc direct))))

       (and pid (nil? card))
       (conj (str "feed: a staged proposal did not reach the member's"
                  " feed — a change nobody is shown is a change nobody can"
                  " answer. Cards: "
                  (pr-str (mapv :card_id (feed-cards member-feed)))))

       (and card (not= "decide" (str (:section card))))
       (conj (str "feed: the proposal card is in section "
                  (pr-str (:section card)) " — a staged change is something"
                  " to DECIDE, and the census puts it there"))

       (and card (str/blank? (str (:sentence card))))
       (conj (str "feed: the proposal card says nothing about what changes"
                  " — the diff IS the card, and a verdict button over a"
                  " summary line is a person agreeing to a title"))

       (and card (not (str/includes? (str (:sentence card)) words)))
       (conj (str "feed: the card's sentence never mentions the one thing"
                  " that changes (" (pr-str words) "): "
                  (pr-str (:sentence card))
                  " — a diff that does not name the change is not a diff"))

       (and card (not (contains? verbs (name apply'))))
       (conj (str "feed: the proposal card offers " (pr-str (sort verbs))
                  " and not " (pr-str (name apply'))
                  " — both answers are note-free precisely so both stay"
                  " under the thumb; a verdict with a note is a `recall`"
                  " demand and split-verbs moves it to `heavier`"))

       (and card (not (contains? verbs (name decline))))
       (conj (str "feed: the proposal card offers " (pr-str (sort verbs))
                  " and not " (pr-str (name decline))
                  " — a change you cannot say no to is not a proposal"))

       (and pid (proposal-card composer-feed pid))
       (conj (str "feed: the composer's own proposal is on the composer's"
                  " own feed — the four-eyes wall means a stager is"
                  " structurally incapable of answering it, so carding it"
                  " there would be offering a door that answers 409"))

       (and self-answer (not= 409 (:status self-answer)))
       (conj (str "feed: the composer applying its own proposal got "
                  (:status self-answer) ", not 409 — an agent that could"
                  " stage a change AND tap it through would be writing the"
                  " feed's order under a member's roof"))

       (and self-answer (= 409 (:status self-answer))
            (not= :the-proposer-does-not-decide
                  (refused-guard {:status 409 :doc (json ctx self-answer)})))
       (conj (str "feed: the composer's own attempt was refused by "
                  (pr-str (:guard (json ctx self-answer)))
                  ", not the four-eyes wall"))

       (and agent-answer
            (not= :a-person-answers
                  (refused-guard {:status (:status agent-answer)
                                  :doc (json ctx agent-answer)})))
       (conj (str "feed: a SECOND agent applying somebody else's proposal"
                  " was answered " (:status agent-answer) " / "
                  (pr-str (:guard (json ctx agent-answer)))
                  " — a house running two agents must not be a house where"
                  " one stages and the other taps"))

       (and card (not= 200 (:status applied)))
       (conj (str "feed: a member's tap on Apply answered "
                  (:status applied) ": " (pr-str (json ctx applied))))

       (and (= 200 (:status applied)) (nil? landed))
       (conj (str "feed: the proposal applied and stamped no applied_to —"
                  " the citation is what makes the audit readable from"
                  " either row: " (pr-str (:data row))))

       (and after (not= words (seam-sentence after)))
       (conj (str "feed: the proposal applied and the next feed read still"
                  " says " (pr-str (seam-sentence after))
                  " — the tap IS the write, and a proposal that moved its"
                  " own row without moving the order applied nothing"))

       (and actor (not= (get as-member "x-waymark-principal") actor))
       (conj (str "feed: the recipe's own transition names " (pr-str actor)
                  " and the member who tapped is "
                  (pr-str (get as-member "x-waymark-principal"))
                  " — the apply invokes the recipe's own door AS THE"
                  " ACCEPTING MEMBER, so the audit says a person wrote"
                  " this. An engine or composer actor here would mean the"
                  " household's assent had been laundered into a system"
                  " write"))

       (and second-staged (not= 201 (:status second-staged)))
       (conj (str "feed: a second proposal staged against the order the"
                  " house now reads answered " (:status second-staged) ": "
                  (pr-str (:doc second-staged))))

       (and stale (not= 409 (:status stale)))
       (conj (str "feed: a proposal whose target was revised out from"
                  " under it applied anyway (" (:status stale)
                  ") — the diff a person read describes the world they"
                  " read it in, and applying over somebody else's edit"
                  " would make the tap mean something it never said"))

       (and stale (= 409 (:status stale))
            (not= :the-order-has-not-moved
                  (refused-guard {:status 409 :doc (json ctx stale)})))
       (conj (str "feed: the stale proposal was refused by "
                  (pr-str (:guard (json ctx stale)))
                  ", not by the staleness wall"))

       (and second-id (not= 200 (:status declined)))
       (conj (str "feed: declining the leftover proposal answered "
                  (:status declined) " — a change you cannot say no to is"
                  " not a proposal"))

       (and landed (not= 200 (:status gone)))
       (conj (str "feed: retiring the applied recipe answered "
                  (:status gone) " — the way back is the recipe's own"
                  " doors, and a change that cannot be undone is not"
                  " tuning")))}))

;; ── the view door (waymark-8um.1) ───────────────────────────────────

(defn- post-row!
  "One row through its own create door, as whoever the headers name."
  [ctx kind body hs]
  (let [resp (req ctx :post (str "/api/" (:plural (rdef ctx kind))) body hs)]
    {:status (:status resp) :doc (json ctx resp)}))

(defn- feed-views-of
  "How many view rows this house holds for one member, off the
  collection's own filtered read."
  [ctx member hs]
  (long (get-in (json ctx (get-with-query
                           ctx (str "/api/" (:plural (rdef ctx :feed_view)))
                           (str "member=" member)))
                [:data :total] 0)))

(defn- feed-view-violations
  "The seventh law's second half, from the wire: the GET writes
  nothing, the SCREEN reports through its own door, per member and by
  choice, and a preview leaves no trace on the previewed member.

  Six claims, in the order a household would meet them:

  1. **Off is the default and it is not a suggestion.** A member who
     has said nothing reads `views.recording false` and a POST to the
     view door is REFUSED BY NAME — not accepted quietly, not dropped.
  2. **The switch is the member's own and it works.** One consent row,
     created by the member, and the next feed read says so.
  3. **The record is the member's, stamped by the engine.** The body
     names nobody; the row comes back naming the poster.
  4. **A card counts once a day.** The same card reported twice leaves
     one row, refused by name the second time — the exposure is the
     fact, not the impression count.
  5. **Nobody files a view under somebody else.** A second person
     posting a view that names the first is refused, and this is the
     wall that makes the preview exclusion structural rather than
     polite.
  6. **A preview hands the previewer nothing to record with.** The
     previewed member is RECORDING at this point, and the previewed
     document still reads `views.recording false` — so the screen has
     no beacon to send, and the previewer could not attribute one
     anyway (claim 5).

  …and then it puts the switch back, because an obligation that left a
  member recording would leave every later read of this engine writing
  rows nobody asked for."
  [ctx]
  (let [tag (subs (str (random-uuid)) 0 8)
        made (req ctx :post (str "/api/" (:plural (rdef ctx :member)))
                  {:display (str "view-door-subject-" tag)
                   :actor_type "human"})
        member (some-> (:self (json ctx made)) id-of)
        as-member {"x-waymark-principal" member}
        ;; a SECOND person, unscoped like the first: the wrong-member
        ;; wall is about the BODY, so it says the same thing to whoever
        ;; wrote it — and written as an agent it would have met the
        ;; router's own 404 and proved concealment instead of law
        other (str "view-door-other-" tag)
        as-other {"x-waymark-principal" other "x-waymark-actor-type" "human"}
        before (when member (:doc (feed-doc ctx as-member)))
        day (str (:day before))
        card (first (remove #(= "seam" (str (:card_id %)))
                            (feed-cards before)))
        card-id (str (:card_id card))
        population (str (:population card))
        body (fn [extra] (merge {:card_id card-id :population population
                                 :day day}
                                extra))
        shut (when card (post-row! ctx :feed_view (body {}) as-member))
        switch (when member
                 (post-row! ctx :feed_view_consent {} as-member))
        switch-id (some-> (:doc switch) :self id-of)
        on (when (= 201 (:status switch)) (:doc (feed-doc ctx as-member)))
        posted (when (and card (= 201 (:status switch)))
                 (post-row! ctx :feed_view (body {}) as-member))
        row (when (= 201 (:status posted))
              (json ctx (req ctx :get (str "/api/"
                                           (:plural (rdef ctx :feed_view))
                                           "/" (id-of (:self (:doc posted))))
                             nil as-member)))
        twice (when (= 201 (:status posted))
                (post-row! ctx :feed_view (body {}) as-member))
        theirs (when card (post-row! ctx :feed_view
                                     (body {:member member}) as-other))
        ;; the preview, read while the member IS recording. The
        ;; capability is a ROW, so an engine that serves no registry
        ;; cannot be previewed at all and this leg simply does not
        ;; happen — it is not in `:needs`, because the other five
        ;; claims are owed by every engine that serves the feed and a
        ;; missing registry must not take them down with it.
        previewer (str "view-door-previewer-" tag)
        registry? (ensure-preview-capability! ctx)
        held (when (and member registry?)
               (mint-preview-grant! ctx previewer member))
        preview (when held
                  (:doc (feed-doc ctx (:headers held)
                                  (str "preview_as=" member))))
        kept (when (= 201 (:status posted)) (feed-views-of ctx member as-member))
        ;; and the switch goes back where it was found
        stop (declared-name ctx :feed_view_consent :stop)
        stopped (when switch-id
                  (invoke-http ctx :feed_view_consent switch-id stop nil
                               {:headers as-member}))
        after (when (= 200 (:status stopped))
                (:doc (feed-doc ctx as-member)))
        shut-again (when (and card (= 200 (:status stopped)))
                     (post-row! ctx :feed_view (body {}) as-member))]
    {:covered (if (= 201 (:status posted)) 1 0)
     :violations
     (cond-> []
       (nil? member)
       (conj (str "feed: the view-door obligation could not mint a member ("
                  (:status made) "): " (pr-str (json ctx made))))

       (and before (nil? (:views before)))
       (conj (str "feed: the feed document carries no `views` key — the"
                  " screen has no way to know whether it may report what it"
                  " showed, and a client left to guess that would guess"))

       (and before (:views before) (true? (get-in before [:views :recording])))
       (conj (str "feed: a member who has never said anything reads"
                  " views.recording true — the switch is OFF for everybody"
                  " until each person turns their own on, and a default that"
                  " is not off is not a choice"))

       (and shut (not= 409 (:status shut)))
       (conj (str "feed: a view posted with the switch OFF answered "
                  (:status shut) ": " (pr-str (:doc shut))
                  " — the door must refuse, out loud, rather than lean on"
                  " the screen's manners"))

       (and shut (= 409 (:status shut))
            (not= :the-member-turned-this-on (refused-guard shut)))
       (conj (str "feed: the switched-off view was refused by "
                  (pr-str (:guard (:doc shut)))
                  ", not by the wall that names the switch"))

       (and switch (not= 201 (:status switch)))
       (conj (str "feed: a member could not turn their own recording on ("
                  (:status switch) "): " (pr-str (:doc switch))
                  " — the switch is the member's own hand and there is no"
                  " other hand that reaches it"))

       (and on (not (true? (get-in on [:views :recording]))))
       (conj (str "feed: the switch is on and the feed document still reads"
                  " views.recording " (pr-str (get-in on [:views :recording]))
                  " — the screen reads this key and nothing else"))

       (and card (= 201 (:status switch)) (not= 201 (:status posted)))
       (conj (str "feed: a view posted with the switch ON answered "
                  (:status posted) ": " (pr-str (:doc posted))))

       (and row (not= member (str (get-in row [:data :member]))))
       (conj (str "feed: the view row names "
                  (pr-str (get-in row [:data :member]))
                  " and the member who posted it is " (pr-str member)
                  " — `member` is ENGINE-STAMPED, and a row that could name"
                  " somebody else is a row that can frame them"))

       (and row (not= card-id (str (get-in row [:data :card_id]))))
       (conj (str "feed: the view row remembers card "
                  (pr-str (get-in row [:data :card_id])) " and the card it"
                  " was posted for was " (pr-str card-id) " — the card id is"
                  " kept WHOLE because it is the name the audit trail"
                  " already uses when a verb is fired from a card"))

       (and twice (not= 409 (:status twice)))
       (conj (str "feed: the same card reported twice on one day answered "
                  (:status twice)
                  " — one row per member per card per day; an exposure is a"
                  " fact and an impression count is not"))

       (and kept (not= 1 kept))
       (conj (str "feed: the house holds " kept " view rows for a member who"
                  " was shown one card — the door's own dedupe is what keeps"
                  " a high-volume table bounded"))

       (and theirs (not= 409 (:status theirs)))
       (conj (str "feed: one person filed a view under ANOTHER member and"
                  " the door answered " (:status theirs) ": "
                  (pr-str (:doc theirs))
                  " — this is the wall that makes 'a preview never counts'"
                  " structural rather than a promise a client keeps"))

       (and theirs (= 409 (:status theirs))
            (not= :a-view-is-your-own (refused-guard theirs)))
       (conj (str "feed: the foreign view was refused by "
                  (pr-str (:guard (:doc theirs)))
                  ", not by the wall about whose view it is"))

       (and registry? (nil? held))
       (conj (str "feed: a " cap/feed-preview-as-token " grant could not be"
                  " minted for the view-door obligation — this engine holds"
                  " the registry row, so what refused is the scope"
                  " machinery, and the preview half of this law cannot be"
                  " watched without it"))

       (and preview (true? (get-in preview [:views :recording])))
       (conj (str "feed: a PREVIEW of a recording member reads"
                  " views.recording true — a previewer's screen would then"
                  " beacon about somebody else's page, and the one thing"
                  " this door must never do is file a preview under the"
                  " member being previewed"))

       (and switch-id (not= 200 (:status stopped)))
       (conj (str "feed: the member could not stop their own recording ("
                  (:status stopped) ") — a switch that only goes one way is"
                  " a trap rather than a choice"))

       (and after (true? (get-in after [:views :recording])))
       (conj (str "feed: the recording was stopped and the feed still reads"
                  " views.recording true"))

       (and shut-again (not= 409 (:status shut-again)))
       (conj (str "feed: a view posted after the member stopped recording"
                  " answered " (:status shut-again)
                  " — off has to mean off the moment it is said")))}))

;; ── the crown: an outcome and its pieces (waymark-jfv.4) ────────────
;;
;; The one section above do-now, and the one card in this document
;; that is AUTHORED rather than projected. What a wire document can
;; answer about it is the whole of this obligation: does a staged
;; bundle reach the household on top, carrying its pieces as
;; sub-elements with their OWN one-tap verbs; does a tap on one of
;; those pieces write a real row under the MEMBER's name with the
;; feed's origin on the transition; does a decline settle only itself;
;; and does the grant conceal a piece exactly as it conceals a card.
;;
;; TWO PRINCIPALS AND A THIRD LOOKING ON, and none of them decoration.
;; A COMPOSER (an agent, leashed to two create doors and nothing else)
;; stages the bundle; a MEMBER (a human actor type) is the only one
;; who can answer any part of it; and a THIRD reader holds a leash
;; over the bundle's kind and NOT over its pieces', which is how
;; concealment of the new `pieces` key gets watched rather than
;; asserted.
;;
;; IT MINTS THE MOST ROWS OF ANY OBLIGATION HERE — a value, a bundle,
;; three pieces and the two work rows two of those pieces become —
;; which is why it sits below every obligation that counts cards. It
;; ends where it began: the bundle accepted, every piece answered, so
;; the population retires it by construction and the engine it hands
;; on is the engine it found.

(defn- piece-target
  "The kind a piece will become, read off the declaration's own enum
  rather than named here. `outcome_piece/materializable` is a DECLARED
  set that grows by law revision, and an obligation that spelled
  `task` would be a second opinion about which kinds a composer may
  birth."
  [ctx]
  (let [rd (rdef ctx :outcome_piece)
        form (schema/field-schema (or (:create-schema rd) (:schema rd))
                                  :target_kind)]
    (when (and (vector? form) (= :enum (first form)))
      (first (filter #(rdef ctx (keyword %)) (rest form))))))

(defn- outcome-card
  "The card standing for one bundle, found by its ROW rather than by a
  section name: which band the line sits in is the household's recipe
  to say, and the claim that it is `outcomes` is made separately and
  out loud."
  [doc id]
  (some #(when (and (= "outcome" (str (:kind %)))
                    (= (str id) (id-of (:self %)))) %)
        (feed-cards doc)))

(defn- piece-of
  "One piece of a carded bundle, by the id it stands for."
  [card id]
  (some #(when (= (str id) (id-of (:self %))) %) (:pieces card)))

(defn- feed-outcome-violations
  [ctx]
  (let [tag (subs (str (random-uuid)) 0 8)
        as-member (member-headers tag)
        member (get as-member "x-waymark-principal")
        composer (get (composer-headers tag) "x-waymark-principal")
        loved (str "the shop " tag)
        ;; 1. THE VALUE, and a person declares it. `written-by-a-person`
        ;; stands at this door, so the composer could not have written
        ;; what its own outcome is about to cite.
        value (req ctx :post (str "/api/" (:plural (rdef ctx :value)))
                   {:name (str "Making things with the boys " tag)
                    :says (str "The evenings that are worth remembering are"
                               " the ones somebody built something in.")
                    :loved [loved]
                    :scope "household"}
                   as-member)
        vid (when (= 201 (:status value)) (id-of (:self (json ctx value))))
        vself (when vid (str "/api/" (:plural (rdef ctx :value)) "/" vid))
        ;; 2. THE LEASH: two create doors, no verdict door anywhere.
        ;; That the mint succeeds is half a claim on its own — an
        ;; outcome is grantable at the MCP door precisely because
        ;; holding the grant confers no power over the household's
        ;; Saturday.
        leashed (leash! ctx composer
                        [{:kind "outcome" :actions ["create"]}
                         {:kind "outcome_piece" :actions ["create"]}])
        target (piece-target ctx)
        stage (fn [kind body]
                (let [resp (req ctx :post
                                (str "/api/" (:plural (rdef ctx kind)))
                                body leashed)]
                  {:status (:status resp) :doc (json ctx resp)}))
        goal (str "One Saturday afternoon in the shop with the boys " tag)
        staged (when (and vid leashed)
                 (stage :outcome
                        {:goal goal
                         :value_id vid
                         :routing (str "It runs through " loved ", which this"
                                       " house wrote down as something it"
                                       " loves — so the expensive part,"
                                       " getting started, is already paid.")
                         :routes_through loved
                         :evidence [vself]}))
        oid (some-> (:doc staged) :self id-of)
        ;; 3. THE PIECES. Three, so the partial accept is a real shape
        ;; rather than a story: one declined, one taken by its own tap,
        ;; and one left for the bundle's own verb to take.
        piece (fn [n]
                (when (and oid target)
                  (stage :outcome_piece
                         {:outcome_id oid
                          :says (str "Piece " n " of " tag
                                     " — twenty minutes, already prepared")
                          :target_kind target
                          :prepared (create-body ctx (keyword target)
                                                 (+ 4100 (long n)))})))
        pieces (into [] (keep piece) [1 2 3])
        pids (mapv #(some-> (:doc %) :self id-of) pieces)
        ;; 4. WHO SEES IT. The member does; the composer must not (the
        ;; four-eyes wall means carding it to its stager would offer
        ;; doors that answer 409); and a reader leashed to the BUNDLE
        ;; and not to its pieces reads a bundle with no pieces at all.
        mine (:doc (feed-doc ctx as-member))
        theirs (:doc (feed-doc ctx leashed))
        half (leash! ctx (str "conformance-halfsighted-" tag)
                     [{:kind "outcome" :actions []}])
        halfdoc (when half (:doc (feed-doc ctx half)))
        card (when oid (outcome-card mine oid))
        day (str (:day mine))
        verbs (when card (set (map (comp name key) (:actions card))))
        pverbs (fn [i] (when-some [p (piece-of card (nth pids i nil))]
                         (set (map (comp name key) (:actions p)))))
        take' (declared-name ctx :outcome_piece :take)
        not-this (declared-name ctx :outcome_piece :not_this)
        make-it-so (declared-name ctx :outcome :make_it_so)
        origin (fn [c] (feed/origin-key day (str (:card_id c))
                                        (subs (str (random-uuid)) 0 8)))
        ;; 5. THE DECLINE, from the piece's own chip: it settles ITSELF
        ;; and leaves the rest of the bundle standing.
        pc1 (when card (piece-of card (nth pids 0 nil)))
        declined (when pc1
                   (invoke-http ctx :outcome_piece (nth pids 0) not-this nil
                                {:headers (assoc as-member
                                                 "idempotency-key"
                                                 (origin pc1))}))
        ;; 6. THE TAP, and it is the WRITE. The piece's own key rides
        ;; it, so actions-from-the-feed counts the piece rather than
        ;; attributing it to the bundle.
        pc2 (when card (piece-of card (nth pids 1 nil)))
        key2 (when pc2 (origin pc2))
        took (when pc2
               (invoke-http ctx :outcome_piece (nth pids 1) take' nil
                            {:headers (assoc as-member
                                             "idempotency-key" key2)}))
        stamped (when (= 200 (:status took))
                  (some #(when (= key2 (:idempotency-key %)) %)
                        (transitions ctx :outcome_piece (nth pids 1))))
        parsed (when key2 (feed/origin-of key2))
        after-take (when (= 200 (:status took))
                     (json ctx (get-env ctx :outcome_piece (nth pids 1))))
        landed (some-> (get-in after-take [:data :materialized]) id-of)
        actor (when (and landed target)
                (create-actor ctx (keyword target) landed))
        ;; 7. AND THE BUNDLE'S OWN VERB, which takes what is still
        ;; offered — the third piece — inside one transaction.
        still (when oid (:doc (feed-doc ctx as-member)))
        card' (when oid (outcome-card still oid))
        accepted (when card'
                   (invoke-http ctx :outcome oid make-it-so nil
                                {:headers (assoc as-member
                                                 "idempotency-key"
                                                 (origin card'))}))
        third (when (= 200 (:status accepted))
                (json ctx (get-env ctx :outcome_piece (nth pids 2))))
        gone (when (= 200 (:status accepted))
               (:doc (feed-doc ctx as-member)))]
    {:covered (if (= 200 (:status took)) 1 0)
     :violations
     (cond-> []
       (not= 201 (:status value))
       (conj (str "feed: a person could not declare a value (" (:status value)
                  "): " (pr-str (json ctx value))
                  " — nothing in this section means anything until the house"
                  " has said what it cares about"))

       (nil? leashed)
       (conj (str "feed: an outcome/outcome_piece create grant could not be"
                  " minted and accepted for a composer — both kinds are"
                  " meant to be GRANTABLE at the MCP door, and they may be"
                  " precisely because holding the grant confers no power"
                  " over the household's Saturday; a kind an agent cannot"
                  " be leashed to is a staging door no composer can reach"))

       (nil? target)
       (conj (str "feed: outcome_piece declares no target_kind this engine"
                  " serves — the materializable set is a DECLARED enum, and"
                  " a piece that could become nothing is a button that"
                  " never lands"))

       (and leashed vid (not= 201 (:status staged)))
       (conj (str "feed: an AGENT holding an outcome grant staged a bundle"
                  " and got " (:status staged) ": " (pr-str (:doc staged))
                  " — the composer may PREPARE the household's week even"
                  " though it may not decide any of it"))

       (and oid target (not= 3 (count (filter #(= 201 (:status %)) pieces))))
       (conj (str "feed: staging three pieces answered "
                  (pr-str (mapv :status pieces)) ": "
                  (pr-str (mapv :doc pieces))
                  " — a bundle is two to five pieces, and the prepared"
                  " input is judged at STAGING against the very door it"
                  " will knock on"))

       (and oid (nil? card))
       (conj (str "feed: a staged bundle never reached the member's feed —"
                  " a week nobody is shown is a week nobody can answer."
                  " Cards: " (pr-str (mapv :card_id (feed-cards mine)))))

       (and card (not= "outcomes" (str (:section card))))
       (conj (str "feed: the outcome card is in section "
                  (pr-str (:section card)) " — the census puts the crown on"
                  " top, and " (pr-str (mapv name feed/census)) " is law"))

       (and card
            (not (every? #(= "outcomes" (str (:section %)))
                         (take-while #(not= (str (:card_id card))
                                            (str (:card_id %)))
                                     (feed-cards mine)))))
       (conj (str "feed: something that is not an outcome is ABOVE the"
                  " bundle — " (pr-str (mapv :card_id (feed-cards mine)))
                  " — and a crown below anything is a section out of"
                  " census order"))

       (and card (str/blank? (str (:sentence card))))
       (conj (str "feed: the outcome card says nothing on its own behalf —"
                  " the value it serves and the routing that makes it cheap"
                  " to start are the whole claim, and a summary line naming"
                  " the row cannot say either"))

       (and card (not (str/includes? (str (:sentence card)) loved)))
       (conj (str "feed: the card's sentence never cites the loved activity"
                  " the bundle routes through (" (pr-str loved) "): "
                  (pr-str (:sentence card))))

       (and card (not= 3 (count (:pieces card))))
       (conj (str "feed: the bundle carded with "
                  (count (:pieces card)) " pieces, not 3 — the pieces ARE"
                  " the consent, and a bundle that cannot show which part"
                  " is which is a single yes/no wearing a list's clothes"))

       (and card (not (contains? verbs (name make-it-so))))
       (conj (str "feed: the bundle offers " (pr-str (sort verbs))
                  " and not " (pr-str (name make-it-so))
                  " — both of the bundle's verdicts are note-free precisely"
                  " so both stay under a thumb"))

       (and card (pverbs 0) (not (contains? (pverbs 0) (name not-this))))
       (conj (str "feed: a piece offers " (pr-str (sort (pverbs 0)))
                  " and not " (pr-str (name not-this))
                  " — a decline that cannot name WHICH piece teaches the"
                  " composer nothing, which is the whole reason the pieces"
                  " are rows"))

       (and card (pverbs 0)
            (some #(demand/heavier? (str (:effort %)) feed/card-ceiling)
                  (vals (:actions (piece-of card (nth pids 0 nil))))))
       (conj (str "feed: a piece verb is heavier than "
                  feed/card-ceiling " — the ≤-selection rule is the same"
                  " projection for a sub-element as for a card, and a piece"
                  " that wanted a keyboard would not be one tap"))

       (and card (not= (str "outcomes/outcome_piece/" (nth pids 0 nil))
                       (str (:card_id (piece-of card (nth pids 0 nil))))))
       (conj (str "feed: a piece's card_id is "
                  (pr-str (:card_id (piece-of card (nth pids 0 nil))))
                  " — a piece verb rides the origin key like any other card"
                  " verb, so the piece needs an id of its own or the audit"
                  " trail will read the tap as the bundle's"))

       (and oid (outcome-card theirs oid))
       (conj (str "feed: the bundle carded to its own COMPOSER — the"
                  " four-eyes wall means the stager is structurally"
                  " incapable of answering any part of it, so this is a"
                  " door that answers 409 offered to the one principal it"
                  " will never open for"))

       (and half oid (nil? (outcome-card halfdoc oid)))
       (conj (str "feed: a reader leashed to the bundle's kind was shown no"
                  " bundle at all — this half of the claim exists so the"
                  " other half means something"))

       (and half oid (seq (:pieces (outcome-card halfdoc oid))))
       (conj (str "feed: a reader whose leash names the bundle and NOT its"
                  " pieces was shown "
                  (count (:pieces (outcome-card halfdoc oid)))
                  " pieces — a piece is projected through the same three"
                  " gates the card is, and one this grant does not confer"
                  " is ABSENT rather than narrowed"))

       (and pc1 (not= 200 (:status declined)))
       (conj (str "feed: 'not this' on a piece answered " (:status declined)
                  ": " (pr-str (json ctx declined))))

       (and pc2 (not= 200 (:status took)))
       (conj (str "feed: 'yes' on a piece answered " (:status took) ": "
                  (pr-str (json ctx took))
                  " — the tap IS the write, and it goes through the"
                  " target's own door inside the same transaction"))

       (and (= 200 (:status took)) (nil? stamped))
       (conj (str "feed: a piece verb invoked with " (pr-str key2)
                  " left no transition carrying that key — a piece tap is a"
                  " card verb, and actions-from-the-feed is reading a"
                  " column nothing filled"))

       (and key2 (not= [(str "outcomes") "outcome_piece" (str (nth pids 1))]
                       [(:section parsed) (:kind parsed) (:id parsed)]))
       (conj (str "feed: a piece's origin key parses to " (pr-str parsed)
                  " — the section, the kind and the id come back out of the"
                  " audit trail with no join, or they come back wrong"))

       (and (= 200 (:status took)) (str/blank? (str landed)))
       (conj (str "feed: the taken piece names no materialized row — the"
                  " address the tap wrote is the only way the household can"
                  " follow what it just agreed to"))

       (and landed (not= member (str actor)))
       (conj (str "feed: the row a piece materialized carries "
                  (pr-str actor) " on its create transition, not "
                  (pr-str member)
                  " — ctx :create hands the inner write the OUTER"
                  " principal, and a row minted under a system actor or"
                  " under the composer's name would be the household"
                  " agreeing to something somebody else signed"))

       (and (= 200 (:status took)) card'
            (some #(= "declined" (str (:state %)))
                  [(piece-of card' (nth pids 1 nil))]))
       (conj (str "feed: taking one piece moved another — the pieces are"
                  " separately tappable precisely so one answer is one"
                  " row"))

       (and card' (nil? (piece-of card' (nth pids 2 nil))))
       (conj (str "feed: after one decline and one tap the bundle no longer"
                  " shows the piece still on offer — a partial accept is"
                  " the state of the piece rows at the moment of the tap,"
                  " and the card has to show what is left"))

       (and card' (not= 200 (:status accepted)))
       (conj (str "feed: 'make it so' answered " (:status accepted) ": "
                  (pr-str (json ctx accepted))))

       (and third (not= "taken" (str (:state third))))
       (conj (str "feed: after 'make it so' the piece still on offer is "
                  (pr-str (:state third))
                  " — the bundle's verb takes the pieces STILL OFFERED,"
                  " through their own doors, in one transaction"))

       (and gone oid (outcome-card gone oid))
       (conj (str "feed: an answered bundle is still on the feed — accepted"
                  " is terminal, so it leaves by construction rather than"
                  " by a query remembering to exclude it")))}))

(defn- feed-obligation [name' run]
  {:name name' :needs #{[:route :feed]} :run run})

(def feed
  {:module :feed
   :obligations
   [(routes-mounted :feed)
    (feed-obligation :feed/recipe-order feed-recipe-order-violations)
    (feed-obligation :feed/day-stable feed-day-stable-violations)
    (feed-obligation :feed/projection feed-projection-violations)
    (feed-obligation :feed/cursor-rolls feed-cursor-violations)
    (feed-obligation :feed/archive-pages feed-archive-pages-violations)
    ;; law 6, and it reads only (waymark-8um.2): a draw is a nonce in a
    ;; query string, so this obligation spins the feed as often as it
    ;; likes and leaves nothing behind — the engine it hands on is the
    ;; engine it found, which is why it sits among the readers rather
    ;; than below with the writers.
    (feed-obligation :feed/deal-again feed-deal-again-violations)
    ;; the last of the readers: every card cites the layer that
    ;; actually admitted it, and the recipe reads back narrated
    ;; (waymark-iqa.29). It reads TWICE — once plain, once with
    ;; ?explain=1 — and one of its claims is that those two reads
    ;; answer the same cards, which is the law a client leans on when
    ;; it fetches a citation late.
    (feed-obligation :feed/citations feed-citation-violations)
    ;; THE WRITERS GO LAST, and in this order for one reason each.
    ;; :verbs-are-light invokes a card verb for real, so the origin
    ;; convention is proved by the audit trail rather than by a
    ;; docstring; every obligation whose answer a finished row would
    ;; move reads above it. :ticklers goes BELOW it — deliberately,
    ;; not by append — because it is the only obligation that MINTS
    ;; rows, and a minted marker is a card: run above, its two
    ;; ticklers would be in the deck :day-stable counts, :projection
    ;; sizes and :verbs-are-light picks a verb from.
    (feed-obligation :feed/verbs-are-light feed-verbs-are-light-violations)
    {:name :feed/ticklers
     :needs #{[:route :feed] [:kind :tickler]}
     :run feed-tickler-violations}
    ;; …and :insights below IT, for the same reason one turn further:
    ;; it is the obligation that mints the MOST rows (the only honest
    ;; way to watch a daily cap fill is to fill it), and a feed with
    ;; three fresh findings in the decide section is a feed the two
    ;; ticklers above would have had to share with them.
    {:name :feed/insights
     :needs #{[:route :feed] [:kind :insight]}
     :run feed-insight-violations}
    ;; …and the crown below even that (waymark-jfv.4), for the same
    ;; reason once more: it mints more rows than any obligation here —
    ;; a value, a bundle, three pieces and the work rows two of those
    ;; pieces become — and its bundle cards ABOVE every other card on
    ;; the page, which is one more thing every obligation ahead of it
    ;; would have had to share a deck with. It ends with the bundle
    ;; accepted and every piece answered, so the population retires it
    ;; and the engine it hands on is the engine it found.
    {:name :feed/outcomes
     :needs #{[:route :feed] [:kind :value]
              [:kind :outcome] [:kind :outcome_piece]}
     :run feed-outcome-violations}
    ;; …and the preview LAST, for the third reason in the same
    ;; sequence: it is the only obligation that mints a MEMBER, and a
    ;; new member is a new row on a nav kind — one more card every
    ;; obligation above would have had to share its deck with. It also
    ;; reads a feed as somebody who has never read one, which is a
    ;; cleaner answer key at the bottom of the run than the top.
    {:name :feed/preview-as
     :needs #{[:route :feed] [:kind :capability] [:kind :member]}
     :run feed-preview-violations}
    ;; …and the recipe LAST of all (waymark-4yn), for a fourth reason
    ;; in the same sequence and the strongest of them: it is the only
    ;; obligation that changes the ORDER ITSELF. Every claim above is
    ;; about a feed read under the deployment's own recipe, and one
    ;; run above this one would be reading a feed this obligation
    ;; wrote. It retires its row before it returns, so the engine it
    ;; leaves behind is the engine it found.
    {:name :feed/recipe-is-a-row
     :needs #{[:route :feed] [:kind :feed_recipe]}
     :run feed-recipe-violations}
    ;; …and the staged proposal after even that (waymark-0k4), for the
    ;; same fourth reason taken one step further: it changes the order
    ;; too, and it changes it THROUGH the recipe's own door under a
    ;; member's name, which is a thing every claim above would rather
    ;; not have happening underneath it. It ends where it began — the
    ;; recipe it wrote retired, the proposal it left declined — so the
    ;; engine it hands on is the engine it found.
    {:name :feed/staged-proposals
     :needs #{[:route :feed] [:kind :feed_recipe] [:kind :recipe_proposal]}
     :run feed-proposal-violations}
    ;; …and the view door last of all (waymark-8um.1), for two reasons
    ;; in the same sequence. It MINTS A MEMBER, like the preview
    ;; obligation above it — one more row on a nav kind that every
    ;; obligation ahead of it would have had to share a deck with — and
    ;; it is the only obligation that WRITES ABOUT A READ: it turns a
    ;; member's recording on, posts view rows against whatever cards
    ;; that engine happens to be serving, and turns it off again. Run
    ;; higher, its rows would be in the transition log the archive
    ;; folds. It leaves the switch stopped, so the engine it hands on
    ;; is the engine it found.
    {:name :feed/view-events
     :needs #{[:route :feed] [:kind :member]
              [:kind :feed_view] [:kind :feed_view_consent]}
     :run feed-view-violations}]
   ;; Every obligation spec-feed § 'Where the law is proved' names is
   ;; here now, each having landed with the bead that landed the
   ;; mechanism it judges rather than ahead of it.
   })
