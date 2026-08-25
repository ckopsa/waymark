(ns waymark10.test.suite
  "The conformance driver: one call proves core plus every enrolled
  module.

  Before waymark-db9.5 there was no driver at all. waymark10.test.
  conformance was a library of violation fns and each application
  suite hand-wrote the same eight deftests over it — four copies of
  the staging atom, the request sugar, the ETag-aware invoke, the
  walked-row cache, and the eight assertions, differing only in which
  kinds they named. Four copies is four chances to drift, and a
  framework whose whole thesis is 'the advertised surface and the
  enforced surface are the same value' cannot spend its proof that
  way.

  So: an application hands over an engine, a handler and the kinds it
  declares, and gets back a REPORT.

      (deftest conformance
        (suite/check! {:engine *eng* :handler *h* :kinds kinds}))

  ── what runs ──

  Core's pack, then the pack of every module the engine ASSEMBLED
  (waymark10.modules/packs reads the same `selected` that the kinds
  and the routes read, so core is never dropped and an unknown label
  still refuses). Within a pack, an obligation runs only when its
  `:needs` are met by what the module actually contributed to THIS
  engine — the kind is in the registry, the route is in the assembled
  vector, the runtime surface is turning. Disabling a module REMOVES
  its obligations; it never fails them, and a pack for a module
  nobody assembled never runs.

  Three need verbs, and the third is not like the other two.
  `[:kind k]` and `[:route module]` ask the ASSEMBLY what it holds;
  `[:surface hook]` (waymark-db9.4) asks the PROCESS what it started,
  because the obligations that want it are claims about a surface
  turning — webhook delivery timing, the jobs worker's lease and
  progress, presence TTL, the curtain's verdict bound. An
  application that hands over an unstarted engine, which is every app
  suite today, skips them with the surface named.

  ── who starts the engine (waymark-db9.8) ──

  NOBODY HERE, and that is the decision. This driver gained no
  `:start-runtime?` opt when the runtime obligations were written
  (waymark-db9.8): it judges the engine it is HANDED, and starting
  one is a fact about a process — ten threads, four advisory-lock
  elections, a dedicated LISTEN connection — that belongs to whoever
  owns the process. An application that wants its own surfaces proved
  starts the runtime around its own `check!` call, in the fixture
  where it already decides such things, and this namespace changes
  not at all; `:running-surfaces` below is read when the ctx is
  built, so a started engine simply arrives with its surfaces on. The
  framework drives the runtime obligations itself, over an engine
  whose sweep cadences are test-sized
  (waymark10.runtime-conformance-test) — because the bounded waits
  those obligations use derive from the engine's OWN cadence opts,
  and choosing those opts is the same act as choosing to start.
  waymark10.server.engine/start-runtime! is unchanged and the app
  suites pay exactly nothing, which was the constraint.

  An obligation that throws is reported as a violation, not as an
  aborted run: one broken module must not hide the other twelve.

  ── the ctx ──

  Everything the four suites used to hand-write is built here once and
  handed to the packs as functions: the walker's headers, the JSON
  request sugar, the honest-client POST (a fresh idempotency key when
  the action is not idempotent, the current ETag when it is fenced),
  the memoized walked row per kind × state, the synthesized input with
  its honest skip, and the assembled router asked what it would match.
  A pack reads ctx; it never reaches for an engine internal.

  This namespace is where waymark10.test.factories is required —
  malli.generator and test.check live behind that door. The PACKS
  deliberately do not require it, because waymark10.modules requires
  THEM."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [org.httpkit.server :as http]
            [reitit.core :as reitit]
            [reitit.ring :as ring]
            [waymark10.machine :as machine]
            [waymark10.modules :as modules]
            [waymark10.server.invoke :as inv]
            [waymark10.server.router :as router]
            [waymark10.server.runtime :as runtime]
            [waymark10.server.store :as store]
            [waymark10.test.conformance :as conf]
            [waymark10.test.factories :as fac]
            [waymark10.types :as types]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(def walker-headers
  "The conformance principal: a SYSTEM actor named walker. A client,
  never a backdoor — a role-gated path still needs a registered
  example or a factory."
  {"x-waymark-principal" "walker" "x-waymark-actor-type" "system"})

;; ── the ctx ─────────────────────────────────────────────────────────

(defn context
  "Build the driver's context. Required: :engine and :handler (the
  ring handler the application actually serves) and :kinds, the
  application's OWN kinds — the ones core's obligations walk. The
  framework's enrolled kinds are not in that vector; each owning
  module's pack speaks for them.

  Optional :seed pins the walk (97, the seed all four suites used)."
  [{:keys [engine handler kinds seed] :or {seed 97}}]
  (let [rdef (fn [kind] (get (inv/resources engine) kind))
        self-of (fn [kind id] (str "/api/" (:plural (rdef kind)) "/" id))
        req (fn [method uri & [body headers]]
              (handler (cond-> {:request-method method
                                :uri uri
                                :headers (or headers walker-headers)}
                         body (assoc :body (if (string? body)
                                             body
                                             (wire/write-json body))))))
        text (fn [resp]
               (when-some [b (:body resp)]
                 (if (string? b) b (slurp b))))
        json (fn [resp] (some-> (:body resp) wire/read-json))
        get-env (fn [kind id] (req :get (self-of kind id)))
        staged (atom {})
        walk (fn [kind state]
               (let [k [kind state]]
                 (or (get @staged k)
                     (let [out (fac/walk-to-state engine kind state {:seed seed})]
                       (swap! staged assoc k out)
                       out))))
        row-in-state (fn [kind state]
                       (let [out (walk kind state)]
                         (when-not (:skip out) out)))
        ;; the staged walk is memoized on purpose — one row per kind ×
        ;; state, reused by every obligation. A runtime obligation
        ;; sometimes needs the opposite: a row that did not exist a
        ;; moment ago, because the EVENT of its creation is what the
        ;; surface under test is supposed to notice (the webhook
        ;; deliverer's). So this walk is never cached and never
        ;; reuses a seed.
        fresh-seed (atom (+ (long seed) 1000))
        fresh-row (fn [kind state]
                    (let [out (fac/walk-to-state engine kind state
                                                 {:seed (swap! fresh-seed inc)})]
                      (when-not (:skip out) out)))
        route-sets (modules/route-sets engine (:modules engine))
        reitit-router (ring/router (router/assemble-routes engine route-sets)
                                   {:conflicts nil})]
    {:engine engine
     :handler handler
     :kinds (vec kinds)
     :modules (:modules engine)
     :walker-headers walker-headers

     :rdef rdef
     :self-of self-of
     :req req
     :json json
     :text text
     :ctype (fn [resp] (get-in resp [:headers "Content-Type"]))
     :get-env get-env

     :row-in-state row-in-state
     :fresh-row fresh-row
     :skip-of (fn [kind state] (get-in (walk kind state) [:skip :reason]))
     :states-with-rows (fn [kind]
                         (for [state (sort (machine/reachable-states (rdef kind)))
                               :let [row (row-in-state kind state)]
                               :when row]
                           [state row]))

     :declared-name
     (fn [kind wire-kw]
       (or (some #(when (= wire-kw (conf/wire-name (:name %))) (:name %))
                 (machine/actions-seq (rdef kind)))
           wire-kw))

     ;; a CREATE's input, the way :input-for is an action's
     ;; (waymark-jfv.4). An outcome piece carries `prepared` — the
     ;; body its target's own create door will take — and the honest
     ;; body to stage is the one the walk itself would have sent,
     ;; through the same generator, rather than a hand-written map
     ;; that happens to fit today's schema.
     :create-body
     (fn [kind s] (fac/create-body engine kind {:seed s}))

     :input-for
     (fn [kind action-def row s]
       (let [body (fac/synthesize-input engine (rdef kind) action-def row
                                        (fac/probe-ctx engine) {:seed s})]
         {:body body
          :skip (when (and (nil? body) (:input action-def)) (fac/skip-reason))}))

     :invoke
     (fn [kind id aname body {:keys [headers query]}]
       (let [a (some-> (get-in (rdef kind) [:actions aname]) (assoc :name aname))
             etag (when (get-in a [:safety :fence])
                    (get-in (get-env kind id) [:headers "ETag"]))
             hs (merge walker-headers
                       (when (and a (not (get-in a [:safety :idempotent])))
                         {"idempotency-key" (str (random-uuid))})
                       (when etag {"if-match" etag})
                       headers)]
         (handler (cond-> {:request-method :post
                           :uri (str (self-of kind id) "/-/" (name aname))
                           :headers hs}
                    query (assoc :query-string query)
                    body (assoc :body (wire/write-json body))))))

     ;; the transitions one row logged, newest first — the audit
     ;; trail as a ctx accessor, so a pack that must read WHO moved a
     ;; row (the jobs worker's claim is the case) asks the driver
     ;; rather than reaching into the engine's storage itself.
     :transitions
     (fn [kind id]
       (let [st (:storage engine)]
         (store/with-tx st
           (fn [tx] (store/transitions st tx {:kind kind :resource-id id} {})))))

     :registry-kinds (set (keys (inv/resources engine)))
     ;; the kinds a named principal sees its OWN rows of with no grant
     ;; — read off the registry, exactly as grants/visibility reads it
     ;; (spec-decision-kind seam 2). The concealment packs need it to
     ;; pick a kind that really IS hidden from a narrow leash, and
     ;; before this key they carried a fourth hand-written copy of
     ;; core's literal set: a decision kind declared by an app made
     ;; that copy quietly wrong, and the pack passed by testing the
     ;; wrong kind
     :own-surface-kinds (into #{}
                              (keep (fn [[k rdef]]
                                      (when (:own-surface rdef) k)))
                              (inv/resources engine))
     ;; what is actually TURNING on this engine — empty for the
     ;; unstarted handler every app suite hands over, which is why a
     ;; [:surface k] need skips there instead of failing
     :running-surfaces (runtime/surfaces engine)
     ;; …and the HANDLE behind one of those keys, for the obligations
     ;; whose observable is the surface's own value rather than the
     ;; wire (the presence board, the curtain's verdict). nil on an
     ;; engine that never started, which is why every one of them is
     ;; [:surface k]-gated first.
     :surface (fn [hook-key] (runtime/surface engine hook-key))
     ;; a throwaway HTTP endpoint the module under test can be pointed
     ;; at — the webhook deliverer's third party. It lives HERE and
     ;; not in the packs because packs.clj is loaded by every boot
     ;; (waymark10.modules requires it) and a boot has no business
     ;; loading a test receiver; and because http-kit is already
     ;; core's transport, so nothing new arrives on the path.
     :receiver!
     (fn []
       (let [hits (atom [])
             server (http/run-server
                     (fn [req]
                       (swap! hits conj {:body (some-> (:body req) slurp)})
                       {:status 200 :headers {} :body ""})
                     {:port 0 :legacy-return-value? false})]
         {:hits hits
          :url (str "http://127.0.0.1:" (http/server-port server) "/hook")
          :stop! (fn [] (http/server-stop! server))}))
     :route-sets route-sets
     :route-modules (into #{}
                          (keep (fn [{:keys [module static plural]}]
                                  (when (seq (concat static plural)) module)))
                          route-sets)
     :match-template (fn [path]
                       (:template (reitit/match-by-path reitit-router path)))}))

;; ── selection ───────────────────────────────────────────────────────

(defn- satisfied?
  "Is one `:needs` entry met by what this engine actually assembled?
  An unrecognized need refuses outright rather than quietly skipping
  its obligation forever — a typo'd need is exactly the silent
  no-coverage this bead exists to abolish."
  [ctx [what x :as need]]
  (case what
    :kind (contains? (:registry-kinds ctx) x)
    :route (contains? (:route-modules ctx) x)
    ;; [:surface hook-key] — judged against what is RUNNING, not what
    ;; was declared (waymark-db9.4). The other two verbs ask the
    ;; assembly a question; this one asks the process, because the
    ;; obligations that need it — webhook delivery timing, the jobs
    ;; worker's lease and orphan requeue, presence TTL, the curtain's
    ;; verdict bound — are claims about a surface that is turning.
    ;; An engine handed to the driver unstarted (every app suite
    ;; today) has none, so they SKIP with the surface named rather
    ;; than a pack starting a worker of its own, which would be the
    ;; lifecycle seam wearing a test's clothes.
    :surface (contains? (:running-surfaces ctx) x)
    (throw (types/definition-error
            (str "unknown conformance need " (pr-str need)
                 " — a pack may ask for [:kind k], [:route module]"
                 " or [:surface hook]")
            {:check :conformance-pack :need need}))))

(defn- run-obligation [ctx module {:keys [name needs run]}]
  (let [unmet (vec (remove #(satisfied? ctx %) needs))]
    (if (seq unmet)
      {:module module :name name :skipped unmet}
      (let [out (try (run ctx)
                     (catch Throwable e
                       {:violations
                        [(str name " threw " (.getName (class e)) ": "
                              (ex-message e))]}))
            {:keys [violations covered]} (if (map? out)
                                           out
                                           {:violations out})]
        (cond-> {:module module :name name :violations (vec violations)}
          covered (assoc :covered covered))))))

(defn run
  "Every obligation this engine owes: core's pack plus the packs of
  the modules it assembled, each obligation's `:needs` judged against
  what actually landed. Returns a report — one map per obligation,
  either {:module :name :violations […] :covered n} or {:module :name
  :skipped [unmet-needs]}."
  [ctx]
  (into []
        (mapcat (fn [{:keys [module obligations]}]
                  (map #(run-obligation ctx module %) obligations)))
        (modules/packs (:modules ctx))))

(defn ran
  "The obligations that actually ran — needs met."
  [report]
  (filterv :violations report))

(defn skipped
  "The obligations whose module contributed nothing they could
  address. Not failures: an engine without attachments owes no
  attachment obligations."
  [report]
  (filterv :skipped report))

(defn coverage
  "How much one obligation exercised, when it reports it. The folded
  enums are the case that needs it: zero checked is honest in an app
  with no acceptance-set guard and a hole in an app that has one, and
  only the app knows which it is."
  [report obligation]
  (or (some #(when (= obligation (:name %)) (:covered %)) report) 0))

(defn violations
  "Every violation in the report, each prefixed by the obligation that
  found it — the whole red run on one screen."
  [report]
  (into []
        (mapcat (fn [{:keys [name violations]}]
                  (map #(str "[" name "] " %) violations)))
        report))

;; ── the application's whole suite ───────────────────────────────────

(defn check!
  "Run the driver and assert, one `testing` block per obligation so a
  red run names the broken promise and its module. Returns the report,
  so an application that wants to assert on COVERAGE (mealplan10 does,
  for its folded enums) can go on to do so.

  The first assertion is that anything ran at all: a driver that
  selected nothing would be the most comfortable failure in this
  codebase, and the least honest."
  [opts]
  (let [report (run (context opts))]
    (t/is (seq (ran report))
          "the driver selected at least one obligation to prove")
    (doseq [{:keys [name violations]} (ran report)]
      (t/testing (str name)
        (t/is (empty? violations) (str/join "\n" violations))))
    report))
