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
            [waymark10.server.attachments :as attachments]
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
  is determinism, not caching."
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

(defn- feed-row-cards
  "Every card of one feed answer that stands for a row — the seam has
  no verbs and no screen, and it is the one element here that is not a
  projection of anything."
  [doc]
  (into [] (remove #(= "seam" (:card_id %))) (feed-cards doc)))

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

  The subject is read off the feed's OWN first card, so the marker
  points at a row this engine really serves and really has not
  finished: `set-aside?` is the retirement rule and a fixture that
  ignored it would be a fixture testing nothing. The second marker
  names a row that does not exist, which is the same rule from the
  other side — a marker may outlive its subject, and when it does it
  says nothing rather than carding a ghost.

  It reports `:covered`, because an engine whose feed has no row card
  at all has nothing to set aside and should say so rather than pass
  quietly."
  [ctx]
  (let [{:keys [doc]} (feed-doc ctx nil)
        subject (first (remove #(= "tickler" (str (:kind %)))
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
     :run feed-tickler-violations}]
   ;; NOT here, and named rather than pending: :feed/archive-pages
   ;; (waymark-iqa.5 brings the populations that make deep paging
   ;; worth walking). A spec-feed § 'Where the law is proved'
   ;; obligation, belonging to the bead that lands the mechanism.
   })
