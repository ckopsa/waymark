(ns waymark10.modules-test
  "The module inventory: the two literals `full-registry` and
  `router/handler` used to carry, now one value. These tests hold the
  five things that made it worth moving — the enrolled set is
  unchanged, the worksheet still appears only when an application
  asks for it, a resources vector that fights the table is told so by
  name, the assembled route vector still puts the worksheet ahead of
  the bulk grammar it would otherwise vanish behind, and an engine
  assembled WITHOUT a module 404s that module's routes — and, since
  waymark-db9.5, that an engine assembled without a module owes NO
  OBLIGATIONS for it either. All of it with no database anywhere in
  sight.

  waymark-db9.7 adds the sixth: the router reaches no module by name
  at all, and each of the four doors it used to reach through
  degrades — rather than crashing — on an engine assembled without
  the module behind it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.modules :as modules]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.router :as router]
            [waymark10.server.runtime :as runtime]
            [waymark10.server.seams :as seams]
            [waymark10.server.store.memory :as memory]
            [waymark10.test.suite :as suite]
            [waymark10.wire :as wire]))

(defn- waymark-threads
  "Every live thread this framework named — the honest reading of
  'an engine that never starts pays nothing'."
  []
  (into #{}
        (comp (map #(.getName ^Thread %))
              (filter #(str/starts-with? % "waymark10-")))
        (keys (Thread/getAllStackTraces))))

(def ^:private asks-for-a-worksheet
  "The shape worksheet/kinds reads: a kind carrying :worksheet. Only
  :kind and :worksheet matter to the table, so this stays a map."
  {:kind :note
   :worksheet {:columns [{:field :title}]}})

(defn- enrolled-kinds [resources modules]
  (into #{} (map :kind) (modules/enrolled resources modules)))

(deftest the-table-enrols-exactly-what-the-literal-did
  ;; …plus the feed module's own pair. :feed_recipe (waymark-4yn) is
  ;; the feed's engine opt authored at runtime; :recipe_proposal
  ;; (waymark-0k4) is the exact change an agent may stage against it
  ;; and only a person may apply. Both enroll :always, because both
  ;; compose the FEED module's vocabulary rather than an
  ;; application's, so there is nothing left for an app to opt into.
  ;; …and its view door's pair (waymark-8um.1): :feed_view_consent is
  ;; the per-member switch, :feed_view the record it lets exist. They
  ;; enroll together or not at all — a record whose wall was in another
  ;; jar would be a door with no switch on it.
  ;; …and :verdict_reason (waymark-jfv.16): the four quick words a
  ;; SETTLED card offers after a decline lands. :always for the view
  ;; door's own reason — the chips are drawn by the feed's generic
  ;; screen off a door the feed DOCUMENT names, so a house that serves
  ;; the feed serves the way to say why.
  ;; …and :ranking_note (waymark-1uv.6): an agent's score and sentence
  ;; about a ranked row, the crown's sixth input. :always for the
  ;; reason kind's reason — it names no application vocabulary and
  ;; the rank that reads it is the feed module's own.
  ;; …and :remark (waymark-b4s): the thread's turn — words on any
  ;; subject with no verdict attached. :always for the reason kind's
  ;; reason again — {subject_kind, subject_id}, no application
  ;; vocabulary, and the conversation is about the cards this module
  ;; minted.
  (is (= #{:definition :member :role :grant :approval_request
           :attachment :subscription :job :feed_recipe :recipe_proposal
           :feed_view :feed_view_consent :verdict_reason :ranking_note
           :remark}
         (enrolled-kinds [] nil))))

(deftest app-opt-in-kinds-are-named-but-never-enrolled
  (testing "the table knows capability, saved_view and the dashboard pair"
    (is (= #{:capability :saved_view :dashboard :dashboard_slot}
           (into #{} (comp (filter (comp #{:app-opt-in} :enroll)) (map :kind))
                 modules/enrollment))))
  (testing "and hands the registry none of them — the app's vector does"
    (is (empty? (filter #{:capability :saved_view :dashboard :dashboard_slot}
                        (enrolled-kinds [] nil)))))
  (testing "so an app naming one is opting in, not colliding"
    (is (empty? (modules/warnings [{:kind :capability}] nil)))))

(deftest the-worksheet-appears-only-when-a-kind-asks-for-it
  (is (not (contains? (enrolled-kinds [] nil) :worksheet)))
  (is (contains? (enrolled-kinds [asks-for-a-worksheet] nil) :worksheet)))

(deftest a-selection-never-drops-core
  (testing "naming one module keeps the law's own vocabulary"
    (is (= #{:definition :member :role :grant :approval_request :job}
           (enrolled-kinds [] [:jobs]))))
  (testing "an unknown label refuses rather than serving less"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown module"
                          (modules/selected [:webhooks :telepathy])))))

(deftest a-redundant-hand-enroll-names-the-module-that-owns-the-kind
  (let [[w :as ws] (modules/warnings [{:kind :member}] nil)]
    (is (= 1 (count ws)))
    (is (re-find #":member is declared by the application" w))
    (is (re-find #":core module already enrols it" w)))
  (testing "the same for a when-declared kind the app also declares"
    (is (seq (modules/warnings [asks-for-a-worksheet {:kind :worksheet}] nil))))
  (testing "and the registry still refuses it, one law per kind"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one law per kind"
                          (engine/full-registry [{:kind :member}])))))

(deftest an-unassembled-module-makes-a-declaration-inert-and-says-so
  (let [[w :as ws] (modules/warnings [asks-for-a-worksheet] [:jobs])]
    (is (= 1 (count ws)))
    (is (re-find #"asks for :worksheet" w))
    (is (re-find #":worksheet module is not assembled" w)))
  (testing "silent while everything is assembled, which is today"
    (is (empty? (modules/warnings [asks-for-a-worksheet] nil)))))

(deftest the-table-stays-a-plain-readable-value
  (doseq [{:keys [module kind enroll kinds]} modules/enrollment]
    (is (keyword? module))
    (is (keyword? kind))
    (is (contains? #{:always :when-declared :app-opt-in} enroll))
    (is (if (= :app-opt-in enroll) (nil? kinds) (fn? kinds))
        (str kind ": :kinds is a fn of resources unless the app carries it")))
  (testing "and one entry per module, each contribution a fn or absent"
    (is (= (count modules/known) (count modules/inventory))
        "a module is named once; its contributions ride that one entry")
    (doseq [{:keys [module enrols routes hooks pack]} modules/inventory]
      (is (keyword? module))
      (is (or (nil? enrols) (vector? enrols)))
      (is (or (nil? routes) (fn? routes))
          (str module ": :routes is (fn [eng]) → route set, or absent"))
      (doseq [{:keys [hook after elected start stop] pred :when} hooks]
        (is (simple-keyword? hook)
            (str module ": a hook is named by the runtime key it publishes"))
        (is (or (nil? after) (vector? after)))
        (is (or (nil? elected) (keyword? elected))
            (str hook ": :elected is the ROLE NAME — the advisory-lock"
                 " keyspace, not a bare true"))
        (is (or (nil? pred) (fn? pred)))
        (is (fn? start) (str hook ": :start is (fn [eng running]) → handle"))
        (is (fn? stop) (str hook ": :stop is (fn [handle])")))
      (is (or (nil? pack)
              (and (map? pack)
                   (= module (:module pack))
                   (vector? (:obligations pack))))
          (str module ": :pack is a plain {:module :obligations} value,"
               " or absent"))
      (doseq [{:keys [name needs run]} (:obligations pack)]
        (is (qualified-keyword? name)
            (str module ": an obligation is named <module>/<promise>"))
        (is (fn? run) (str name ": :run is a fn of the driver ctx"))
        (doseq [[what _ :as need] needs]
          (is (contains? #{:kind :route :surface} what)
              (str name ": " (pr-str need)
                   " — a need asks for [:kind k], [:route module]"
                   " or [:surface hook]")))))))

;; ── the mounting seam (waymark-db9.3) ───────────────────────────────

(def ^:private eng
  "A booted engine over the in-memory twin — the routes are functions
  of an engine, and this is the cheapest true one."
  (delay (engine/engine {:storage (memory/storage) :resources []})))

(defn- core-only []
  (engine/engine {:storage (memory/storage) :resources [] :modules []}))

(defn- paths [route-sets]
  (mapv first (router/assemble-routes @eng route-sets)))

(deftest the-assembled-vector-serves-what-the-literal-served
  (let [ps (paths (modules/route-sets @eng nil))]
    (testing "every address the hand-written vector carried, still here"
      (is (= #{"/" "/api/.well-known/waymark" "/api/openapi.json"
               "/api/schemas/:kind" "/api/-/events" "/api/-/seasons"
               "/api/-/presence" "/api/-/intents" "/api/-/intents/abandon"
               "/api/-/intents/answer" "/api/-/collab-ticket"
               "/api/-/mirrors/:plural/:action" "/api/-/welcome" "/api/-/mcp"
               ;; the MCP door's OAuth discovery (waymark-kkx): RFC
               ;; 9728's root document and the path-inserted spelling
               "/.well-known/oauth-protected-resource"
               "/.well-known/oauth-protected-resource/api/-/mcp"
               "/api/-/feed"
               ;; the composer's diagnosis (waymark-8um.4): the feed's
               ;; second door, the feed's own three-segment shape
               "/api/-/diagnosis"
               ;; the Gate proxy's two doors (waymark-q95): the
               ;; affordance document and the grant-checked forward
               "/api/-/gate" "/api/-/gate/:tool"
               "/api/-/grant-check" "/agentInvite" "/api/-/agent-invite"
               "/api/-/ui" "/api/-/ui-lite" "/api/attachments/:id/bytes"
               "/api/definitions/:id/sweep"
               "/api/surfaces/:name" "/api/surfaces/:name/:id"
               "/api/:plural" "/api/:plural/-/worksheet"
               "/api/:plural/-/:action" "/api/:plural/:id"
               "/api/:plural/:id/-/events" "/api/:plural/:id/-/history"
               "/api/:plural/:id/-/:action"
               "/api/:plural/:id/-/:action/batch"
               "/api/:plural/:id/-/:action/draft"
               "/api/:plural/:id/-/:action/draft/collab"}
             (set ps))))
    (testing "and no address twice — one mount per route"
      (is (= (count ps) (count (set ps)))))))

(deftest position-is-the-routing-rule-so-the-buckets-are-load-bearing
  (let [ps (paths (modules/route-sets @eng nil))
        at (fn [p] (.indexOf ^java.util.List ps p))]
    (testing "the worksheet precedes the bulk grammar that would eat it"
      (is (< (at "/api/:plural/-/worksheet") (at "/api/:plural/-/:action"))))
    (testing "every static module route precedes the plural grammar"
      (doseq [p ["/api/-/seasons" "/api/-/presence" "/api/openapi.json"
                 "/api/attachments/:id/bytes"
                 ;; three segments under /api is /api/{plural}/{id}'s
                 ;; own shape: mounted later, the feed would be read
                 ;; as row "feed" of a collection named "-"
                 "/api/-/feed"
                 ;; …and the diagnosis beside it (waymark-8um.4), for
                 ;; the same reason: three segments, mounted early
                 "/api/-/diagnosis"
                 ;; the gate door shares the feed's shape and the
                 ;; same fate if mounted late (waymark-q95)
                 "/api/-/gate"
                 ;; four segments, and still static: /api/:plural/:id
                 ;; would not match it, but /api/definitions/{id} is a
                 ;; row address and the sweep is not a field of it
                 "/api/definitions/:id/sweep"
                 "/api/-/mirrors/:plural/:action"]]
        (is (< (at p) (at "/api/:plural"))
            (str p " would be read as a collection if it came later"))))
    (testing "and the plural bucket sits inside the grammar, not before it"
      (is (< (at "/api/:plural") (at "/api/:plural/-/worksheet"))))
    (testing "the history read precedes the invoke grammar that would
              shadow it (waymark-442.4)"
      (is (< (at "/api/:plural/:id/-/history")
             (at "/api/:plural/:id/-/:action"))))))

(deftest a-module-left-out-takes-its-routes-with-it
  (let [full (engine/handler @eng)
        core (engine/handler (core-only))
        get! (fn [h uri]
               (:status (h {:request-method :get :uri uri
                            :headers {"x-waymark-principal" "someone"}})))]
    (testing "assembled, the module doors answer"
      (is (= 200 (get! full "/api/-/seasons")))
      (is (= 200 (get! full "/api/openapi.json")))
      (is (= 200 (get! full "/api/-/feed")))
      (is (= 200 (get! full "/api/-/diagnosis")))
      (is (= 200 (get! full "/"))))
    (testing "left out, they are addresses nobody mounted — 404, not 405"
      (is (= 404 (get! core "/api/-/seasons")))
      (is (= 404 (get! core "/api/openapi.json")))
      (is (= 404 (get! core "/api/-/feed")))
      (is (= 404 (get! core "/api/-/diagnosis")))
      (is (= 404 (get! core "/"))))
    ;; the MCP door answers POST; its GET is the deliberate 405 that
    ;; says this server pushes nothing (the streaming punt, on the
    ;; wire). So it is asked the way it is meant to be asked — and
    ;; core-only, the address is nobody's, which is what a deployment
    ;; that does not want to be agent-drivable should look like.
    (let [post! (fn [h]
                  (:status (h {:request-method :post :uri "/api/-/mcp"
                               :headers {"x-waymark-principal" "someone"}
                               :body (wire/write-json
                                      {:jsonrpc "2.0" :id 1
                                       :method "tools/list"})})))]
      (testing "assembled, the MCP door answers JSON-RPC"
        (is (= 200 (post! full)))
        (is (= 405 (get! full "/api/-/mcp"))))
      (testing "left out, it is an address nobody mounted"
        (is (= 404 (post! core)))
        (is (= 404 (get! core "/api/-/mcp")))))
    (testing "and core answers the same either way"
      (is (= 200 (get! full "/api/.well-known/waymark")))
      (is (= 200 (get! core "/api/.well-known/waymark"))))))

(deftest a-selection-mounts-exactly-what-it-named
  (let [ps (set (paths (modules/route-sets @eng [:seasons])))]
    (is (contains? ps "/api/-/seasons"))
    (is (not (contains? ps "/api/-/presence")))
    (is (contains? ps "/api/:plural") "core's grammar is never optional")))

;; ── the lifecycle seam (waymark-db9.4) ──────────────────────────────

(defn- hook-order [modules]
  (mapv :hook (runtime/order (modules/hooks modules))))

(deftest the-hook-seq-is-the-literal-start-used-to-build
  (testing "every surface engine/start! hand-wired, in start order"
    (is (= [:dispatcher :law-refresh :clock-sweeper
            :attachments-purge :webhooks-deliverer
            :jobs-worker :jobs-orphan-sweeper
            :curtain :presence :intents
            :discovery
            ;; the feed module's two surfaces. :tickler-sweeper
            ;; (waymark-1uv.9) sweeps the dropped pile, gated on a
            ;; tickler kind being served; :belief-sweeper (waymark-bug)
            ;; refolds every hypothesis's posterior nightly, gated on a
            ;; hypothesis kind being served. Both are :elected — one
            ;; holder per storage — and both start LAST because they are
            ;; the module's own and nothing waits on them.
            :tickler-sweeper :belief-sweeper]
           (hook-order nil))))
  (testing "and no runtime key twice — a key names one surface"
    (let [ks (hook-order nil)]
      (is (= (count ks) (count (set ks)))))))

(deftest stop-order-is-derived-not-written
  (let [stop (vec (reverse (hook-order nil)))
        at (fn [k] (.indexOf ^java.util.List stop k))]
    (testing "the comment stop! used to carry by hand: the curtain
              stops after its readers, before the dispatcher it
              subscribes to"
      (is (< (at :presence) (at :curtain)))
      (is (< (at :intents) (at :curtain)))
      (is (< (at :curtain) (at :dispatcher))))
    (testing "and everything that rides the dispatcher lets go first"
      (is (< (at :webhooks-deliverer) (at :dispatcher)))
      (is (< (at :law-refresh) (at :dispatcher))))
    (testing "the dispatcher is last down, as it was first up"
      (is (= :dispatcher (peek stop))))))

(deftest hooks-read-the-same-selection-everything-else-reads
  (testing "a named selection starts what it assembled, and core"
    (is (= [:dispatcher :law-refresh :clock-sweeper
            :jobs-worker :jobs-orphan-sweeper]
           (hook-order [:jobs])))
    (is (empty? (filter #{:curtain :presence :intents} (hook-order [:jobs])))))
  (testing "an unknown label refuses here as everywhere"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown module"
                          (modules/hooks [:telepathy])))))

(deftest an-after-that-cycles-refuses-to-boot
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"cannot be ordered"
       (runtime/order [{:hook :a :after [:b]} {:hook :b :after [:a]}])))
  (testing "an :after naming a hook this assembly left out is vacuous"
    (is (= [:b] (mapv :hook (runtime/order [{:hook :b :after [:gone]}]))))))

(deftest the-walker-hands-each-hook-what-started-before-it
  (let [log (atom [])
        hook (fn [k after & [extra]]
               (merge {:hook k :after after
                       :start (fn [_ started]
                                (swap! log conj [:start k (set (keys started))])
                                {:handle k})
                       :stop (fn [h] (swap! log conj [:stop (:handle h)]))}
                      extra))
        eng {:storage (memory/storage) :runtime (atom nil)}
        ;; declared out of order on purpose: :after is the order,
        ;; table position is only the tiebreak
        hooks [(hook :c [:a :b]) (hook :a []) (hook :b [:a])
               (hook :skipped [] {:when (constantly false)})
               (hook :elect [] {:elected :a-role})]
        started (runtime/start-hooks! eng hooks)]
    (testing "a hook sees exactly the surfaces it declared it comes after"
      (is (= [[:start :a #{}]
              [:start :b #{:a}]
              [:start :c #{:a :b}]
              [:start :elect #{:a :b :c}]]
             @log)))
    (testing "a :when that says no contributes no key at all"
      (is (not (contains? started :skipped))))
    (testing "on the in-memory twin an elected hook is a plain start —
              nobody to contend with, so the surface still runs"
      (is (true? @(:held? (:elect started))))
      (is (= {:handle :elect} (:handle (:elect started)))))
    (reset! log [])
    (runtime/stop-hooks! eng hooks started)
    (testing "and stop is the start walk backwards, election included"
      (is (= [[:stop :elect] [:stop :c] [:stop :b] [:stop :a]] @log)))))

(deftest a-hook-that-throws-on-the-way-up-takes-the-runtime-with-it
  (let [stopped (atom [])
        hook (fn [k & [extra]]
               (merge {:hook k :after []
                       :start (fn [_ _] {:handle k})
                       :stop (fn [h] (swap! stopped conj (:handle h)))}
                      extra))
        eng {:storage (memory/storage) :runtime (atom nil)}
        hooks [(hook :a) (hook :b)
               (hook :boom {:start (fn [_ _] (throw (ex-info "no" {})))})]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"hook :boom failed to start"
                          (runtime/start-hooks! eng hooks)))
    (is (= [:b :a] @stopped) "half a runtime is not a posture we offer")))

(deftest an-engine-that-never-starts-pays-nothing
  (let [before (waymark-threads)
        e (engine/engine {:storage (memory/storage) :resources []})]
    (testing "the hooks are data until someone walks them"
      (is (nil? @(:runtime e)))
      (is (false? (runtime/started? e)))
      (is (nil? (runtime/surface e :dispatcher)))
      (is (empty? (runtime/surfaces e)))
      (is (empty? (remove before (waymark-threads)))
          "no surface thread was spawned for a handler-only engine"))
    (testing "and its SSE route still answers 503, not a crash"
      (is (= 503 (:status ((engine/handler e)
                           {:request-method :get :uri "/api/-/events"
                            :headers {"x-waymark-principal" "someone"}})))))))

;; ── the conformance packs (waymark-db9.5) ───────────────────────────

(defn- report-of
  "The driver's report for an engine over the in-memory twin and no
  application kinds at all: the module obligations are the whole
  story, which is exactly what these tests want to read."
  [eng]
  (suite/run (suite/context {:engine eng
                             :handler (engine/handler eng)
                             :kinds []})))

(defn- names-of [report] (into #{} (map :name) report))

(deftest packs-read-the-same-selection-everything-else-reads
  (testing "every module that owes obligations offers a pack"
    (is (= [:core :attachments :webhooks :jobs :worksheet :capabilities
            :dashboard :seasons :realtime :mirror :openapi :ui :mcp
            :law-sweep :feed]
           (mapv :module (modules/packs nil)))))
  (testing "a named selection keeps core and nothing it did not name"
    (is (= [:core :jobs] (mapv :module (modules/packs [:jobs])))))
  (testing "and an unknown label refuses here as everywhere"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown module"
                          (modules/packs [:telepathy])))))

(deftest a-module-left-out-takes-its-obligations-with-it
  (let [full (report-of @eng)
        without (report-of (engine/engine {:storage (memory/storage)
                                           :resources []
                                           :modules [:jobs]}))]
    (testing "assembled, the module's obligations are proved"
      (is (contains? (names-of (suite/ran full)) :attachments/byte-round-trip))
      (is (contains? (names-of (suite/ran full)) :seasons/routes-mounted)))
    (testing "left out, they are not FAILED — they are not there at all"
      (is (not (contains? (names-of without) :attachments/byte-round-trip)))
      (is (not (contains? (names-of without) :seasons/routes-mounted)))
      (is (contains? (names-of without) :jobs/job-collection)
          "the module it DID name still owes what it owes"))
    (testing "and the thinned suite is green, not silent"
      (is (seq (suite/ran without)))
      (is (empty? (suite/violations without))))))

(deftest an-obligation-whose-surface-is-absent-is-skipped-not-failed
  (let [report (report-of @eng)
        skipped (into {} (map (juxt :name :skipped)) (suite/skipped report))]
    (testing "capability rides the APP's resources vector, and this
              engine's is empty — so the module is assembled and its
              obligation still has nothing to address"
      (is (= [[:kind :capability]] (get skipped :capabilities/capability-collection))))
    (testing "same for the worksheet, whose kind is when-declared"
      (is (= [[:kind :worksheet]] (get skipped :worksheet/worksheet-collection))))
    (testing "and every RUNTIME obligation (waymark-db9.8) skips on an
              engine nobody started, naming the surface that would
              have had to be turning — this is the posture the four
              application suites are in, and the whole reason a
              [:surface k] need judges the process rather than the
              assembly"
      (is (= [[:surface :jobs-worker]] (get skipped :jobs/worker-progress)))
      (is (= [[:surface :webhooks-deliverer]]
             (get skipped :webhooks/delivery-receipt)))
      (is (= [[:surface :attachments-purge]]
             (get skipped :attachments/purge-sweep)))
      (is (= [[:surface :presence]] (get skipped :realtime/presence-ttl)))
      (is (= [[:surface :curtain]]
             (get skipped :realtime/curtain-verdict-bound))))
    (testing "while the two that ride the DECLARATION run anyway — the
              deferral door is a fact about what this engine enrolled,
              never about what it started (waymark-db9.7)"
      (is (nil? (get skipped :jobs/deferral-door)))
      (is (nil? (get skipped :jobs/deferral-seam))))
    (testing "a skip is never a violation"
      (is (empty? (suite/violations report))))))

;; ── the reach-in seam (waymark-db9.7) ───────────────────────────────

(def ^:private module-namespaces
  "The extension namespaces the spec's inventory names. oidc and
  oidc-rp are deliberately absent: wrap-identity IS the identity
  boundary, and the spec files it under core."
  '#{waymark10.server.presence waymark10.server.intents
     waymark10.server.curtain waymark10.server.collab
     waymark10.server.mirror waymark10.server.jobs
     waymark10.server.webhooks waymark10.server.attachments
     waymark10.server.worksheet waymark10.server.seasons
     waymark10.server.openapi waymark10.server.ui-assembly})

(deftest the-router-reaches-no-module-by-name
  (require 'waymark10.server.router)
  (let [required (into #{}
                       (map ns-name)
                       (vals (ns-aliases 'waymark10.server.router)))]
    (is (empty? (filter module-namespaces required))
        (str "a core handler naming a module namespace is what keeps "
             "that namespace on the classpath of an engine assembled "
             "without it"))))

(def ^:private errand
  "One app kind with a bulk door that DEFERS — the smallest
  declaration that reaches past core into a module."
  (r/resource
   {:kind :errand
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 60}]]]
    :actions
    ;; :finish is bulk-ONLY (a bulk action has no per-row form), so
    ;; the single-invoke rehearsal the intents card hangs off needs a
    ;; door of its own.
    {:finish {:from #{:open} :to :done
              :bulk {:defer-over 1 :max-items 10}
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}}
     :shelve {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Shelved is shelved."}}}}))

(defn- errand-engine [modules]
  (engine/engine (cond-> {:storage (memory/storage) :resources [errand]}
                   (some? modules) (assoc :modules modules))))

(defn- call!
  "One request through the ring handler, the parsed envelope on :doc
  (wire/read-json keywordizes, so every lookup below is a keyword)."
  [h method uri & {:keys [body query]}]
  (let [resp (h (cond-> {:request-method method :uri uri
                         :headers {"x-waymark-principal" "someone"
                                   "content-type" "application/json"}}
                  query (assoc :query-string query)
                  body (assoc :body (wire/write-json body))))]
    (assoc resp :doc (some-> (:body resp) wire/read-json))))

(defn- id-of
  "The row id an envelope's own self href ends in."
  [env]
  (some-> (:self env) (str/split #"/") last))

(deftest the-bulk-door-defers-through-the-kind-the-module-enrolled
  (testing "assembled, an over-threshold call still 202s with the job"
    (let [eng (errand-engine nil)
          resp (call! (engine/handler eng) :post "/api/errands/-/finish"
                      :body {:ids ["a" "b"]})]
      (is (= 202 (:status resp)) (str "wanted a 202: " (:body resp)))
      (is (= "job" (get-in resp [:doc :kind])))
      (is (str/starts-with? (get-in resp [:headers "Location"]) "/api/jobs/"))
      (testing "on an engine that never started — the deferral is a
                property of what was ENROLLED, never of a process"
        (is (false? (runtime/started? eng))))))
  (testing "left out, the call is told so rather than dying inside a
            create! for a kind nobody registered"
    (let [resp (call! (engine/handler (errand-engine [])) :post
                      "/api/errands/-/finish" :body {:ids ["a" "b"]})]
      (is (= 503 (:status resp)))
      (is (str/includes? (str (get-in resp [:doc :detail])) "jobs module")))))

(deftest the-live-surfaces-are-absent-not-fatal
  (doseq [[label eng] [["assembled but never started" (errand-engine nil)]
                       ["not assembled at all" (errand-engine [])]]]
    (testing label
      (let [h (engine/handler eng)]
        (is (nil? (runtime/surface eng :presence)))
        (is (nil? (runtime/surface eng :intents)))
        (testing "the read that would have marked a gaze answers plainly"
          (is (= 200 (:status (call! h :get "/api/errands")))))
        (let [created (call! h :post "/api/errands" :body {:title "sweep"})
              id (id-of (:doc created))]
          (is (= 201 (:status created)) (str "wanted a 201: " (:body created)))
          (is (= 200 (:status (call! h :get (str "/api/errands/" id)))))
          (testing "and the dry-run rehearses without a considering card"
            (let [resp (call! h :post (str "/api/errands/" id "/-/shelve")
                              :query "dry_run=1")]
              (is (= 200 (:status resp)))
              (is (true? (get-in resp [:doc :valid]))))))
        (testing "the per-resource stream still refuses for the ONE
                  reason it always did — no dispatcher, not no presence"
          (is (= 503 (:status (call! h :get "/api/errands/x/-/events")))))))))

;; The mirror's pull-through is the one door of the four that is
;; neither a route nor a running surface: it is a property of the
;; DECLARATION, and it fires on an engine that never started — which
;; is every mirror fixture in the tree, and the whole reason it could
;; not be a runtime lookup. The end-to-end GET is proved against real
;; storage in phase8_test (mirror-discovery-and-pull-through); what
;; belongs HERE, with no database in sight, is that the declaration
;; carries the door and that core dispatches on it.
(defrecord ^:private Feed [state]
  mirror/MirrorAdapter
  (discover [_] (keys @state))
  (pull [_ xid] (get @state xid))
  (pull-many [_ xids] (select-keys @state xids))
  (push [_ _ _] (throw (UnsupportedOperationException. "pull-only"))))

(defn- feed-kind [feed]
  (r/resource
   (mirror/declaration
    {:kind :feed_item
     :summary "{data.title}"
     :schema [:map [:title [:string {:min 1 :max 80}]]]}
    {:adapter feed :ttl-seconds 0})))

(deftest the-pull-through-is-the-declarations-own-door
  (let [eng (engine/engine
             {:storage (memory/storage)
              :resources [(feed-kind (->Feed (atom {})))]})
        spec (:mirror (get (inv/resources eng) :feed_item))]
    (testing "the value the declaration carries IS the read-through"
      (is (satisfies? seams/ReadThrough spec)))
    (testing "and core reaches the module's own refresh through it —
              a conflicted row serves as stored (leaving conflicted is
              a person's move, not the clock's), which is refresh!'s
              first branch and touches nothing"
      (let [row {:id "r1" :state :conflicted :data {:external_id "x1"}}]
        (is (= row (seams/pull-through spec eng
                                       (get (inv/resources eng) :feed_item)
                                       row)))))
    (testing "a kind that declares no mirror carries no door at all"
      (is (nil? (:mirror (get (inv/resources (errand-engine nil)) :errand)))))))

(deftest a-need-the-driver-does-not-understand-refuses
  (let [ctx (suite/context {:engine @eng :handler (engine/handler @eng)
                            :kinds []})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"unknown conformance need"
         (#'suite/run-obligation ctx :core {:name :core/invented
                                            :needs #{[:vibes :good]}
                                            :run (constantly [])})))))
