(ns waymark10.runtime-conformance-test
  "The conformance driver over a STARTED engine — the half of the
  suite that could not exist until the lifecycle seam did.

  Every other suite in this tree hands waymark10.test.suite an engine
  nobody called start! on, which is the posture that keeps a test
  handler free: the hooks are data, the :runtime atom stays nil, and
  every `[:surface k]` obligation SKIPS with the surface named. That
  is correct for an application suite and it leaves a hole exactly
  the size of the running surfaces — the webhook deliverer, the jobs
  worker, the attachments purge, the presence registry, the curtain.
  This namespace is where those obligations are paid.

  ── why here, and not behind an opt on the driver ──

  waymark-db9.8 had to decide how a started engine reaches the packs,
  and the two candidates were: a `:start-runtime?` opt on
  `suite/check!` (the driver starts and stops the engine around the
  run), or a framework-side suite that starts one itself and hands it
  over unchanged. This is the second, and the reasons are three.

  Starting is a decision about a PROCESS — ten threads, four
  advisory-lock elections, a dedicated LISTEN connection, a Hikari
  pool held open for the length of a suite — and a flag on an
  assertion helper is not where a process decision belongs. The
  driver already judges what it is handed: `:running-surfaces` is
  read when the ctx is built, so an application that wants its own
  surfaces proved wraps its own `check!` in start-runtime!/
  stop-runtime! and this driver needs no leg at all. And the
  obligations wait on bounds derived from the engine's OWN cadence
  opts — :purge-sweep-ms, :webhooks-poll-ms, :presence-heartbeat-ms —
  so an engine started with production intervals would make a suite
  wait forty-five seconds for one presence TTL. Choosing test-sized
  cadences and choosing to start are the same act, and they belong in
  the same fixture: this one.

  The constraint that shaped it: an engine that never starts must
  still pay nothing. It does. Nothing in this namespace is required
  by the driver, the packs or the engine, and the four application
  suites are byte-for-byte what waymark-db9.5 left them.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.runtime :as runtime]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.suite :as suite]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private window-closed
  "An always-denying guard with a sentence: core's
  advertisement=enforcement obligation needs at least one NARRATED
  refusal in the staging to have proved anything, and a kind whose
  every action is available proves it nowhere."
  (g/guard {:name :errand-window
            :explain "The errand window is closed."
            :check (fn [_ _ _] (t/deny))}))

(def ^:private errand
  "One application kind, carrying the two declarations the runtime
  obligations key off: a DEFERRING bulk door (so the jobs pack has a
  202 to mint and a worker to watch) and an ordinary create the
  walker can repeat (so the webhooks pack has an event to hear).
  Everything else is the smallest machine that still walks."
  (r/resource
   {:kind :rt_errand
    :plural "rt_errands"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions
    {:review {:from #{:open} :to :open
              :guards [window-closed]
              :safety {:idempotent true :reversible true :confirm false}}
     ;; the walk's own door: machine/path-to routes by non-bulk
     ;; transitions only, so a kind whose only way out of :open is a
     ;; fan-out door has an unreachable terminal state as far as the
     ;; staging is concerned — and core's first obligation says so
     :close {:from #{:open} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Done is done."}}
     :finish {:from #{:open} :to :done
              :bulk {:defer-over 2 :max-items 50}
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}}}}))

(def ^:private tables
  ["rt_errands" "jobs" "subscriptions" "attachments" "definitions"
   "members" "roles" "grants" "approval_requests"
   ;; the feed module's view door (waymark-8um.1): a leftover consent
   ;; row would have this fixture's engine recording before its
   ;; obligation says so
   "feed_views" "feed_view_consents"
   ;; …and the reason door's own rows (waymark-jfv.16): a leftover
   ;; reason would make `one-reason-per-verdict` answer about a
   ;; previous run's bundle
   "verdict_reasons"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_cursors"
   "waymark10_job_leases" "waymark10_drafts" "waymark10_observations"])

(def ^:private test-cadences
  "The engine's running surfaces, tuned to a test's clock. EVERY
  bounded wait in waymark10.test.packs is derived from one of these,
  so this map is the suite's whole time budget — read it, and you
  know what the obligations below can cost. The production defaults
  (60s purges, 15s heartbeats) are in server/engine's docstring, and
  the same obligations against them would take minutes: that is the
  argument for this fixture owning the start, spelled as data."
  {:events-poll-ms 200
   :law-refresh-debounce-ms 100
   :sweep-interval-ms 500
   :role-retry-ms 200
   :jobs-poll-ms 200
   :jobs-batch-size 5
   :orphan-sweep-ms 1000
   :purge-sweep-ms 200
   :webhooks-poll-ms 200
   :webhook-attempts 2
   :webhook-backoff-ms 5
   :webhook-timeout-ms 2000
   :presence-heartbeat-ms 300
   :curtain-ttl-ms 500
   ;; its own byte store: the purge obligation deletes files, and
   ;; batch_f_attachments_test empties the default directory whole
   :attachment-dir "target/attachments-runtime-conformance"})

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine (merge test-cadences
                                        {:storage st :resources [errand]}))]
          ;; the whole point of the namespace, in one line — and its
          ;; unwinding in the finally, because a suite that leaves a
          ;; worker and two elected roles running poisons every suite
          ;; that follows it in this JVM
          (engine/start-runtime! eng)
          (try
            (binding [*eng* eng *h* (engine/handler eng)]
              (f))
            (finally (engine/stop-runtime! eng))))
        (finally (pg/close! st))))))

;; ── the surfaces this engine actually turns ─────────────────────────

(deftest the-started-engine-turns-every-surface-its-modules-declared
  (testing "ten hooks, and the one that legitimately did not run"
    (is (= #{:dispatcher :law-refresh :clock-sweeper
             :attachments-purge :webhooks-deliverer
             :jobs-worker :jobs-orphan-sweeper
             :curtain :presence :intents}
           (runtime/surfaces *eng*)))
    (is (nil? (runtime/surface *eng* :discovery))
        "no kind here declares a :mirror, so the mirror module's
         when-declared discovery starts nothing — the `:when` gate
         from waymark-db9.4, paying nothing for an unused module")
    (is (nil? (runtime/surface *eng* :tickler-sweeper))
        "no tickler kind here, so the feed module's sweep over the
         dropped pile starts nothing either (waymark-1uv.9) — the same
         `:when` gate, the second surface to wear it")))

;; ── the driver, over the started engine ─────────────────────────────

(deftest the-suite-proves-core-the-modules-and-the-running-surfaces
  (let [report (suite/check! {:engine *eng* :handler *h* :kinds [:rt_errand]})
        ran (into #{} (map :name) (suite/ran report))
        skipped (into {} (map (juxt :name :skipped)) (suite/skipped report))]
    (testing "every runtime obligation RAN — a surface obligation that
              quietly skipped is the no-coverage this seam exists to
              abolish, and check! alone would not have said so"
      (doseq [n [:attachments/purge-sweep
                 :webhooks/delivery-receipt
                 :jobs/worker-progress
                 :realtime/presence-ttl
                 :realtime/curtain-verdict-bound]]
        (is (contains? ran n)
            (str n " did not run; skipped for " (pr-str (get skipped n))))))
    (testing "so did the two the declaration alone earns — they run in
              every application suite too, started or not"
      (is (contains? ran :jobs/deferral-door))
      (is (contains? ran :jobs/deferral-seam)))
    (testing "and the ones that measure themselves measured something:
              an obligation that ran over zero cases is a green run
              that proved nothing"
      (is (pos? (suite/coverage report :jobs/deferral-door))
          "the fixture declares a deferring bulk door")
      (is (pos? (suite/coverage report :jobs/worker-progress))
          "and the worker was given one to drive")
      (is (pos? (suite/coverage report :webhooks/delivery-receipt))
          "and the deliverer was given an endpoint and an event")
      ;; waymark-0k4: the feed module enrols recipe_proposal :always,
      ;; so this obligation is owed by every engine that serves the
      ;; feed — and it is the only place the whole apply path is
      ;; walked from the wire (an agent stages, a member taps, the
      ;; RECIPE's transition names the member). A silent skip here
      ;; would be a green run over the bead's own sentence.
      (is (contains? ran :feed/staged-proposals)
          (str ":feed/staged-proposals did not run; skipped for "
               (pr-str (get skipped :feed/staged-proposals))))
      (is (pos? (suite/coverage report :feed/staged-proposals))
          "a member's tap landed the staged change")
      ;; waymark-8um.1: the same argument one law over. The feed module
      ;; enrols feed_view and feed_view_consent :always, so every
      ;; engine that serves the feed owes this obligation — and it is
      ;; the only place the whole view door is walked from the wire (a
      ;; member turns their own recording on, one card leaves one row,
      ;; a second person cannot file one under them, and a preview of a
      ;; recording member hands the previewer nothing to record with).
      (is (contains? ran :feed/view-events)
          (str ":feed/view-events did not run; skipped for "
               (pr-str (get skipped :feed/view-events))))
      (is (pos? (suite/coverage report :feed/view-events))
          "a card that was shown left exactly one row")
      ;; waymark-8um.2: law 6, and the cheapest of the three to skip by
      ;; accident — it needs no kind at all, only the route, so a silent
      ;; skip here would mean the door itself had gone missing.
      (is (contains? ran :feed/deal-again)
          (str ":feed/deal-again did not run; skipped for "
               (pr-str (get skipped :feed/deal-again))))
      ;; waymark-8um.3: law 5, and the one whose silence would be
      ;; hardest to notice — a contest that never ran looks exactly like
      ;; a contest that is inert, which is what it is SUPPOSED to look
      ;; like for a member who never turned the record on.
      (is (contains? ran :feed/formula)
          (str ":feed/formula did not run; skipped for "
               (pr-str (get skipped :feed/formula))))
      (is (pos? (suite/coverage report :feed/formula))
          "a card in a contested section was actually cooled and said so"))))
