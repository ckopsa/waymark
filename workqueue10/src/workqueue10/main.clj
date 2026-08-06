(ns workqueue10.main
  "The family work queue: every task-like row from the household's
  engines — choreplan10 chore runs, mealplan10 prep tasks, home
  assistant todos, google tasks — in ONE kind, prioritized against
  each other, for humans and agents alike, beside the named lists the
  pocket authorities keep them in (:task_list).

      make dev-queue    # serve on :8014 against workqueue10_dev
      clojure -M:dev    # the same, from workqueue10/

  Agent/tool surface at /api/.well-known/waymark.

  Env knobs: WORKQUEUE10_DSN (default the local :5433
  workqueue10_dev), WORKQUEUE10_PORT (default 8014),
  WORKQUEUE10_CHOREPLAN_URL / WORKQUEUE10_MEALPLAN_URL /
  WORKQUEUE10_HA_URL (the sources the confluence drinks from — each
  falls back to its in-memory fake source when unset, offline dev's
  default), WORKQUEUE10_PRINCIPAL (the x-waymark-principal pushes
  act as — default \"workqueue10\"), WORKQUEUE10_CHOREPLAN_TOKEN /
  _MEALPLAN_TOKEN (optional bearers), WORKQUEUE10_HA_TOKEN /
  _HA_UI_URL / _HA_LISTS / _HA_ZONE (the home assistant boundary:
  long-lived token, the browser-facing base for origin links, the
  comma-separated todo entity ids, the zone naive due datetimes
  parse in), WORKQUEUE10_GTASKS_CLIENT_ID / _CLIENT_SECRET /
  _REFRESH_TOKEN / _LISTS / _CAPTURE (the google tasks boundary: an
  OAuth refresh token carrying the tasks scope — the calendar's token
  does NOT — the comma-separated task list ids to mirror, EVERY list
  the account has when unsaid, and the list a capture lands in when
  the birth names none), WORKQUEUE10_RECONSENT_CLIENT_ID /
  _CLIENT_SECRET / _SCOPES (the reconsent door, waymark-kyg.2: the
  Web-application OAuth client the /auth/google doors consent
  through — the mint client pairs above must name this SAME client
  for a reconsented token to spend), WORKQUEUE10_FLICKR_URL (the household's
  media engine — the :media domain's first authority; unset falls
  back to the in-memory fake, the hub's noop source always wired
  beside it), WAYMARK10_DEPLOY_MODE,
  WAYMARK10_AUTO_MIGRATE=1 (dev only — production boots REFUSE on
  schema drift and name the plan), WAYMARK10_OIDC_* (the family
  IdP — waymark10.server.oidc/from-env names them; absent = the
  dev-header resolver, unchanged).

  Schema evolution: `make migrate-queue` prints the plan (migrate!,
  the :migrate alias); APPLY=1 executes it, DESTRUCTIVE=1
  additionally the state-rename UPDATEs."
  (:require [calendar10.oauth :as gcal-oauth]
            [calendar10.resources.event :as calendar]
            [calendar10.source :as gcal]
            [choreplan10.resources.chore :refer [chore]]
            [choreplan10.resources.chore-run :refer [chore-run]]
            [choreplan10.resources.day :refer [day day-board]]
            [mealplan10.main :as mealplan]
            [mealplan10.scraper :as scraper]
            [workqueue10.confluence :as conf]
            [workqueue10.reconsent :as reconsent]
            [workqueue10.resources.dwelling :refer [self journal]]
            [workqueue10.resources.letters :refer [letter]]
            [workqueue10.resources.media :refer [media-resource]]
            [workqueue10.resources.task :refer [task-resource]]
            [workqueue10.resources.task-list :refer [task-list-resource]]
            [workqueue10.resources.weather :refer [weather]]
            [workqueue10.sources.choreplan :as chores]
            [workqueue10.sources.flickr :as flickr]
            [workqueue10.sources.gtasks :as gtasks]
            [workqueue10.sources.homeassistant :as ha]
            [workqueue10.sources.hub :as hub]
            [workqueue10.sources.mealplan :as meals]
            [waymark10.dashboard :as dashboard]
            [workqueue10.connections :as connections :refer [connection]]
            [waymark10.dsl :refer [in-domain]]
            [waymark10.saved-view :refer [saved-view]]
            [waymark10.server.capabilities :refer [capability]]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.oidc-rp :as oidc-rp]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.types :as t]
            [waymark10.server.store.postgres :as pg])
  (:gen-class))

(defonce fake-chores
  ;; module-default fake boundaries — tests script them, offline dev
  ;; and the declaration gate run over them
  (conf/fake-source))

(defonce fake-meals
  (conf/fake-source))

(defonce fake-todos
  (conf/fake-source))

(defonce fake-gtasks
  ;; google tasks gets its OWN twin rather than conf/fake-source: the
  ;; source is the queue's first cursor-bearing feed, and a fake that
  ;; stands behind the transport runs that cursor for real. It is
  ;; given a capture list because offline dev should be able to WRITE
  ;; the google half too, and "@default" is google's own spelling of
  ;; the account's own list — the fake creates it, so a birth that
  ;; names no list lands somewhere instead of 404ing into conflicted.
  (gtasks/fake-source {:capture "@default"}))

(defonce engine-ref
  ;; the stage-1 fold's late binding: in-process sources need the
  ;; engine that hosts them, and the engine's registry needs the
  ;; sources — start! delivers this between engine and serve
  (atom nil))

(defonce fake-flickr
  ;; the media authority's twin (the transport-standing kind, like
  ;; google's): the real source's cursor echo, kind filter and
  ;; translation all run; only the socket is missing. It rides the
  ;; same engine-ref audience rule the real boundary does, so
  ;; offline dev exercises the addendum's follow-the-row half too.
  (flickr/fake-source
   {:preferred-fn (flickr/engine-audience-fn {:engine-ref engine-ref})}))

(defn- ui-base []
  (or (System/getenv "WAYMARK10_OIDC_APP_URL") "http://localhost:8014"))

(defn- google-token-source
  "The row-first refresh-token read (waymark-kyg.2): the reconsent
  door's stored token wins at mint time, the named env var backstops
  it. Built over engine-ref so the sources — constructed before the
  engine boots — see the row the moment it exists. NOTE: a row token
  can revive a RUNNING real source at its next mint (that is the
  door's whole point); it makes a previously-fake source real only at
  next boot — no hot source swapping (recorded punt)."
  [env-var]
  (connections/google-refresh-token-fn engine-ref (System/getenv env-var)))

(defn sources
  "The confluence's tag → TaskSource map. The chore kinds live in
  THIS engine since the stage-1 fold (waymark-bwu.1) — their source
  is in-process unless WORKQUEUE10_CHOREPLAN_URL points at a separate
  engine (the pre-fold posture, kept for the transition). The rest go
  real when their URL is set, fake otherwise."
  []
  (let [principal (System/getenv "WORKQUEUE10_PRINCIPAL")]
    {"chore" (if-some [url (System/getenv "WORKQUEUE10_CHOREPLAN_URL")]
               (chores/http-source
                {:url url :principal principal
                 :token (System/getenv "WORKQUEUE10_CHOREPLAN_TOKEN")
                 ;; production's bearer: this engine's OWN client
                 ;; mints against the source's audience scope, fresh
                 ;; every hour (waymark-mvl); a static _TOKEN wins
                 :token-fn (oidc/outbound-token-fn "waymark-choreplan10")})
               (chores/engine-source
                {:engine-ref engine-ref :ui-base (ui-base)
                 :principal principal}))
     "meal" (if-some [url (System/getenv "WORKQUEUE10_MEALPLAN_URL")]
              (meals/http-source
               {:url url :principal principal
                :token (System/getenv "WORKQUEUE10_MEALPLAN_TOKEN")
                :token-fn (oidc/outbound-token-fn "waymark-mealplan10")})
              (meals/engine-source
               {:engine-ref engine-ref :ui-base (ui-base)
                :principal principal}))
     "todo" (if-some [url (System/getenv "WORKQUEUE10_HA_URL")]
              (ha/http-source
               {:url url
                :ui-url (System/getenv "WORKQUEUE10_HA_UI_URL")
                :token (System/getenv "WORKQUEUE10_HA_TOKEN")
                :lists (System/getenv "WORKQUEUE10_HA_LISTS")
                :zone (System/getenv "WORKQUEUE10_HA_ZONE")
                :capture-list (System/getenv "WORKQUEUE10_HA_CAPTURE")})
              fake-todos)
     ;; the google half of pocket capture — real when the mint CLIENT
     ;; PAIR is configured; the refresh token arrives row-first from
     ;; the reconsent door (env backstops it), and it must carry the
     ;; TASKS scope — the door's default consent asks for both
     "gtasks" (or (gtasks/from-env
                   #(System/getenv ^String %)
                   {:refresh-token-fn (google-token-source
                                       "WORKQUEUE10_GTASKS_REFRESH_TOKEN")})
                  fake-gtasks)}))

(defn media-sources
  "The MEDIA confluence's tag → source map — a second confluence over
  the same protocol, spec-media.md's move. flickr goes real when
  WORKQUEUE10_FLICKR_URL names the engine (fake otherwise, the
  every-boundary rule), and the hub — the noop authority — is always
  wired, so an authority-less row (the dinner recommendation) works
  from day one."
  []
  {"flickr" (or (flickr/from-env
                 {:preferred-fn (flickr/engine-audience-fn
                                 {:engine-ref engine-ref})})
                fake-flickr)
   "hub" (hub/source)})

(defonce fake-calendar
  ;; module-default fake boundary — tests script it, offline dev and
  ;; the declaration gate run over it
  (gcal/fake-calendar))

(defn calendar-adapter
  "The real Google Calendar when CALENDAR10_GOOGLE_* names a
  credential, the scriptable fake otherwise — the same
  real-when-configured rule every other boundary here follows."
  []
  (or (gcal/from-env
       (gcal-oauth/from-env
        #(System/getenv ^String %)
        {:refresh-token-fn (google-token-source
                            "CALENDAR10_GOOGLE_REFRESH_TOKEN")}))
      fake-calendar))

(defn resources
  "One domestic economics (waymark-bwu), across three domains: the
  queue's kind, the folded chore registry (chore, chore_run, day —
  bwu.1), the folded meal registry (bwu.2), and the calendar
  (waymark-6k5.2).

  The calendar's event kind comes from calendar10, NOT from
  mealplan/resources: it stopped being a meals concern when it became
  writable and got a domain of its own. mealplan/meal-resources is
  the ten kinds that are genuinely the meal plan's. prep_task is
  mealplan's NATIVE kind now; choreplan's HTTP mirror of it retired
  with bwu stage 2 — the day board joins the real rows.

  The queue's domain carries TWO kinds: the work itself, and the
  named lists some authorities keep it in. :task_list rides a second
  confluence over the SAME source map, narrowed by conf/list-sources
  to the sources that satisfy TaskListSource — google's task lists
  and home assistant's todo entities today, whatever declares the
  protocol tomorrow. The sources with no list concept (a chore run's,
  a prep task's) are simply absent from it.

  THE MEDIA DOMAIN is the confluence instantiated a second time
  (spec-media.md): one :media kind over its own tag → source map —
  flickr, the household's media engine, and the hub's noop source
  for the rows no catalog owns. Same protocol, same routing law,
  different canonical doc.

  srcs: the task confluence's tag → TaskSource map; media-srcs: the
  media confluence's (the two-arg arity fills it with the offline
  fakes — the pre-media call sites' shape, kept); adapter: the
  family calendar's event boundary (a MirrorAdapter AND
  MirrorCreateAdapter — this one is written to, not merely read)."
  ([srcs adapter]
   (resources srcs {"flickr" fake-flickr "hub" (hub/source)} adapter))
  ([srcs media-srcs adapter]
   (resources srcs media-srcs adapter nil))
  ([srcs media-srcs adapter report-fn]
   (-> (in-domain :queue [(task-resource (conf/confluence srcs report-fn))
                          (task-list-resource
                           (conf/list-confluence (conf/list-sources srcs)
                                                 report-fn))])
       (into (in-domain :media [(media-resource
                                 (conf/confluence media-srcs report-fn))]))
       (into (in-domain :chores [chore chore-run day]))
       (into (in-domain :meals (mealplan/meal-resources)))
       ;; the kind self-declares :domain :calendar; in-domain would
       ;; stamp the same token, and saying it here keeps the domains
       ;; legible in one place
       (into (in-domain :calendar (calendar/resources adapter)))
       ;; the composition kinds (waymark-rla, waymark-ggw): the two
       ;; framework kinds that let the family author what a developer
       ;; otherwise declares once per deploy — a saved_view, a dashboard
       ;; the anchorless surface's user-composed sibling. Opt-in, never
       ;; engine magic; dashboard-slot rides along because the parent's
       ;; :owns edge needs the child on the same engine. Left domainless
       ;; on purpose: composing a view isn't a domain of family life
       ;; beside queue/chores/meals/calendar, so these fold behind the
       ;; ⋯ menu rather than minting a top-level nav group of their own.
       ;; saved_view first so the collections merge (collections.clj)
       ;; has its kind hosted.
       ;; the capability registry (waymark-44h): the grantable
       ;; EXTERNAL powers this deployment names — enforcement lives
       ;; at Gate, the law lives here. Domainless like the
       ;; composition kinds, and for the same reason. The breaker
       ;; panel (waymark-kyg.1) sits beside them: infrastructure the
       ;; family reads when something is dark, not a domain of family
       ;; life.
       ;; the dwelling kinds (waymark-4zj.1): an agent's :self profile
       ;; and the shared :journal, one entry per row. Domainless like
       ;; the composition and capability kinds — an agent's continuity
       ;; and our shared history are the house's inner life, not a
       ;; domain of family logistics beside queue/chores/meals. Their
       ;; privacy is the framework's own: humans unscoped see all,
       ;; agents default-deny see nothing but their OWN (the
       ;; own-surface addition in waymark10.server.grants).
       ;; :weather (waymark-tti.1) sits beside them, domainless for the
       ;; same reason — the hearth thermometer is the house's inner
       ;; life, not a domain of family logistics. Household-SHARED,
       ;; not own-surface: humans see all, agents read via the normal
       ;; grant machinery.
       ;; :letter rides beside them (waymark-tti.3): the doorstep
       ;; shelf — addressed notes between inhabitants, two-party
       ;; own-surface (author OR recipient), never grantable.
       (into (into [saved-view capability connection self journal weather letter]
                   dashboard/resources)))))

(def surfaces
  "Both decision screens, one engine: the housekeeper's day board and
  the planner's week board."
  (into [day-board] mealplan/surfaces))

(defn check-resources
  "Zero-arg so the declaration gate needs no env — every kind over
  the offline fakes."
  []
  (resources {"chore" fake-chores "meal" fake-meals "todo" fake-todos
              "gtasks" fake-gtasks}
             {"flickr" fake-flickr "hub" (hub/source)}
             fake-calendar))

(defn- dsn []
  (or (System/getenv "WORKQUEUE10_DSN")
      "jdbc:postgresql://localhost:5433/workqueue10_dev?user=ckopsa"))

(defn- deploy-mode []
  (case (System/getenv "WAYMARK10_DEPLOY_MODE")
    "propose" :propose
    :promote))

(defn- ensure-capabilities!
  "The registry's boot seed (waymark-44h): the capabilities this
  deployment grants ride the code, ensured idempotently — created
  when absent, never overwritten; retire/restore stay the humans'
  doors."
  [eng]
  ;; verb granularity is what a human reasons about when approving:
  ;; read vs send/write per system — never per tool. Gate's gsd
  ;; family (todos/calendar) is deliberately ABSENT: waymark is the
  ;; task authority and mirrors those sources; a gsd capability
  ;; would leash an agent around the queue's own law.
  (doseq [{:keys [token] :as cap}
          (let [gate "gate-mcp (192.168.1.40:8100)"]
            [{:token "telegram.send"
              :description (str "Send a Telegram message through Gate — "
                                "the household's addressed-notice "
                                "transport, leashed per grant.")
              :enforced_by gate}
             {:token "telegram.read"
              :description "Read Telegram chats and messages through Gate."
              :enforced_by gate}
             {:token "messages.read"
              :description "Read text-message threads through Gate."
              :enforced_by gate}
             {:token "email.read"
              :description (str "Read email through Gate — folders, inbox, "
                                "search, messages, attachments.")
              :enforced_by gate}
             {:token "email.send"
              :description "Send email through Gate."
              :enforced_by gate}
             {:token "email.move"
              :description "File email through Gate — move messages and senders between folders."
              :enforced_by gate}
             {:token "ynab.read"
              :description "Read the budget through Gate — accounts, transactions, categories, months."
              :enforced_by gate}
             {:token "ynab.write"
              :description "Write the budget through Gate — create, update, split, approve transactions."
              :enforced_by gate}
             {:token "amazon.read"
              :description "Read Amazon through Gate — orders, search, product details, the cart."
              :enforced_by gate}
             {:token "amazon.cart"
              :description "Change the Amazon cart through Gate — add items, reset. Never places orders."
              :enforced_by gate}])]
    (when (empty? (store/with-tx (:storage eng)
                    (fn [tx] (store/query-rows (:storage eng) tx :capability
                                               {:token token} {:limit 1}))))
      (inv/create! eng :capability cap
                   {:principal (t/principal {:id "workqueue10-boot"
                                             :type :system
                                             :display "Boot seed"})}))))

(defn- connection-descriptors
  "The breaker panel's inventory (waymark-kyg.1): one entry per wired
  authority, real-or-fake judged by the same env gates sources/
  media-sources/calendar-adapter judge by — said HERE so the panel
  can say out loud when a boundary quietly fell back to its twin.
  chore and meal are real either way: the in-process fold is the
  authority, not a stand-in. The hub is the noop authority — a
  permanently live breaker, wired so the inventory is whole."
  []
  {"chore" {:mode "real"}
   "meal" {:mode "real"}
   "todo" {:mode (if (System/getenv "WORKQUEUE10_HA_URL") "real" "fake")}
   ;; the google pair: real when the mint CLIENT PAIR is set — the
   ;; same judgment gtasks/from-env and gcal-oauth/from-env make now
   ;; that the refresh token arrives row-first at mint time; a real
   ;; source with no token anywhere reads dark with an honest error,
   ;; which is exactly the state the reconsent door fixes
   "gtasks" {:provider "google"
             :mode (if (and (System/getenv "WORKQUEUE10_GTASKS_CLIENT_ID")
                            (System/getenv "WORKQUEUE10_GTASKS_CLIENT_SECRET"))
                     "real" "fake")}
   "flickr" {:mode (if (System/getenv "WORKQUEUE10_FLICKR_URL")
                     "real" "fake")}
   "hub" {:mode "real"}
   "calendar" {:provider "google"
               :mode (if (and (System/getenv "CALENDAR10_GOOGLE_CLIENT_ID")
                              (System/getenv "CALENDAR10_GOOGLE_CLIENT_SECRET"))
                       "real" "fake")}})

(defn assert-reconsent-client-pairing!
  "Refuse to boot on the household misconfig that silently breaks
  capture (waymark-kyg.2, finding #6 review): a refresh token minted
  through the reconsent door's OAuth client spends ONLY at that same
  client. If the door names a client id and a google source's MINT
  pair names a DIFFERENT one, a reconsented token could never spend
  (Google 400 invalid_grant) AND it would shadow a working env token.
  A silent break of the very capability the door exists to repair
  deserves a boot failure, not a warning."
  ([] (assert-reconsent-client-pairing! #(System/getenv ^String %)))
  ([env]
   (when-some [door (not-empty (str (env "WORKQUEUE10_RECONSENT_CLIENT_ID")))]
     (doseq [[label var] [["gtasks" "WORKQUEUE10_GTASKS_CLIENT_ID"]
                          ["calendar" "CALENDAR10_GOOGLE_CLIENT_ID"]]]
       (when-some [mint (not-empty (str (env var)))]
         (when-not (= door mint)
           (throw (ex-info
                   (str "reconsent client mismatch: "
                        "WORKQUEUE10_RECONSENT_CLIENT_ID (" door ") differs "
                        "from " var " (" mint ") — a token reconsented "
                        "through the door could never spend at the " label
                        " source's mint client (Google 400 invalid_grant), "
                        "and would shadow a working env token. Point BOTH at "
                        "the same Web-application OAuth client, or unset one.")
                   {:reconsent-client door :mint-client mint :source label}))))))))

(defonce ^:private dev (atom nil))

(defn start!
  "Boot and serve. Returns the engine."
  []
  (assert-reconsent-client-pairing!)
  (let [storage (pg/storage (dsn))
        ;; with-push: task declares :push-on-write, and engine boot
        ;; does not auto-wire the post-commit push pass (the recorded
        ;; seam in mirror/with-push) — the embedding wraps
        eng (mirror/with-push
             (engine/engine {:storage storage
                             :resources (resources
                                         (sources)
                                         (media-sources)
                                         (calendar-adapter)
                                         (connections/fan-reporter engine-ref))
                             ;; the calendar's adapter is no confluence,
                             ;; so its health arrives kind-level through
                             ;; the mirror's own pass hook
                             :report-pass (connections/pass-reporter
                                           engine-ref {:event "calendar"})
                             :surfaces surfaces
                             :deploy-mode (deploy-mode)
                             ;; the render probe carries the read hooks
                             ;; (waymark-1pq): this is the boot prod
                             ;; actually runs since the fold, so the
                             ;; discovery enums must fold HERE — the
                             ;; standalone mealplan10 boot opting in
                             ;; alone left prod trial-and-409
                             :probe-reads true
                             ;; dev-only, and only when asked: production
                             ;; posture is refuse-on-drift
                             :auto-migrate (= "1" (System/getenv
                                                   "WAYMARK10_AUTO_MIGRATE"))
                             ;; the family IdP, when deployed says so
                             ;; (WAYMARK10_OIDC_*); absent env = the
                             ;; dev-header resolver, unchanged
                             :oidc (oidc/from-env)
                             ;; the hashed disposition's salt
                             ;; (waymark-rci) — a real secret in
                             ;; production; absent = the dev constant
                             :services {:field-hash-salt
                                        (System/getenv
                                         "WAYMARK10_FIELD_HASH_SALT")}}))
        ;; the in-process sources' late binding: delivered BEFORE
        ;; start! wakes the discovery runner
        _ (reset! engine-ref eng)
        _ (ensure-capabilities! eng)
        _ (connections/ensure-connections! eng (connection-descriptors))
        port (or (some-> (System/getenv "WORKQUEUE10_PORT") parse-long) 8014)
        ;; the reconsent door composes OUTSIDE oidc-rp's wrap — comp
        ;; applies rightmost first, so the door's routes answer before
        ;; the require-auth gate can judge them (the door carries its
        ;; own session check and 401)
        server (engine/start! eng port
                              {:wrap-handler
                               (comp (reconsent/wrap eng)
                                     (oidc-rp/wrap-handler eng))})]
    (reset! dev {:engine eng :server server :storage storage})
    (println (str "workqueue10: http://localhost:" port
                  "/api/.well-known/waymark"))
    eng))

(defn stop! []
  (when-some [{:keys [engine server storage]} @dev]
    (engine/stop! engine server)
    (pg/close! storage)
    (reset! dev nil)))

(defn -main [& _]
  (start!)
  @(promise))

;; ── the migrate CLI (make migrate-queue) ────────────────────────────

(defn migrate!
  "Print the schema plan for this app's full registry against
  WORKQUEUE10_DSN; APPLY=1 executes it, DESTRUCTIVE=1 additionally
  the state-rename UPDATEs (otherwise destructive steps are skipped
  and said so). Exits 0 on an empty plan or a fully applied one, 1
  while steps remain — scriptable as a deploy gate."
  [& _]
  (let [storage (pg/storage (dsn))]
    (try
      (let [reg (engine/full-registry (resources (sources)
                                                 (media-sources)
                                                 (calendar-adapter)))
            steps (migrate/plan storage (vals (:kinds reg)))]
        (if (empty? steps)
          (println "workqueue10: storage matches the declarations — empty plan.")
          (do
            (println (str "workqueue10: " (count steps) " migration step(s):"))
            (doseq [s steps] (println " " (migrate/describe s)))
            (if (= "1" (System/getenv "APPLY"))
              (let [destructive? (= "1" (System/getenv "DESTRUCTIVE"))
                    {:keys [applied skipped]}
                    (migrate/apply! storage steps {:destructive? destructive?})]
                (println (str "applied " (count applied) " step(s)."))
                (when (seq skipped)
                  (println (str "SKIPPED " (count skipped)
                                " destructive step(s) — re-run with DESTRUCTIVE=1:"))
                  (doseq [s skipped] (println " " (migrate/describe s)))
                  (System/exit 1)))
              (do (println "dry run — APPLY=1 executes (DESTRUCTIVE=1 includes state renames).")
                  (System/exit 1))))))
      (finally
        (pg/close! storage)
        (shutdown-agents)))))

;; ── the scrape CLI (the :scrape alias) ──────────────────────────────

(defn scrape!
  "One pass of the stale-price scraper (mealplan10.scraper) over THIS
  engine — the consumer product.clj promised, run as a job. Boots
  exactly as start! does, minus the server and the runtime; no
  :auto-migrate, so a drifted schema REFUSES the pass and names the
  plan (production posture — migrate! is the fix). The loop drives
  the engine's own ring handler in-process (the engine-transport
  precedent), fetches with the real HTTP client, and prints the
  honest report. SCRAPE_LIMIT / SCRAPE_DELAY_MS override politeness
  (default 40 fetches, 3000 ms apart)."
  [& _]
  (let [storage (pg/storage (dsn))]
    (try
      (let [eng (mirror/with-push
                 (engine/engine {:storage storage
                                 :resources (resources (sources)
                                                       (media-sources)
                                                       (calendar-adapter))
                                 :surfaces surfaces
                                 :deploy-mode (deploy-mode)
                                 :oidc (oidc/from-env)
                                 :services {:field-hash-salt
                                            (System/getenv
                                             "WAYMARK10_FIELD_HASH_SALT")}}))
            _ (reset! engine-ref eng)
        _ (ensure-capabilities! eng)
            io (scraper/handler-io {:handler (engine/handler eng)
                                    :principal "scraper"})]
        (scraper/run! {:find (:find io)
                       :invoke (:invoke io)
                       :fetch scraper/fetch
                       :limit (some-> (System/getenv "SCRAPE_LIMIT")
                                      parse-long)
                       :delay-ms (some-> (System/getenv "SCRAPE_DELAY_MS")
                                         parse-long)}))
      (finally
        (pg/close! storage)
        (shutdown-agents)))))
