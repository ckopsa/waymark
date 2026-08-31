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
  beside it), WORKQUEUE10_GATE_URL (the household's Gate — the
  :thread domain's two rigs, tgram and messa, share ONE caller
  against it; unset falls back to the in-memory twin, so offline dev
  and the declaration gate never reach for the LAN) and
  WORKQUEUE10_GATE_CHAT_LIMIT (how many conversations a listing asks
  for — the window, default 40), WAYMARK10_DEPLOY_MODE,
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
            [eveningplan10.consumers :as evening-consumers]
            [eveningplan10.resources.activity :refer [activity]]
            [eveningplan10.resources.evening-plan :refer [evening-plan]]
            [eveningplan10.resources.evening-session :refer [evening-session]]
            [mealplan10.main :as mealplan]
            [mealplan10.scraper :as scraper]
            [workqueue10.confluence :as conf]
            [workqueue10.reconsent :as reconsent]
            [workqueue10.resources.dwelling :refer [self journal]]
            [workqueue10.resources.hypothesis :refer [hypothesis]]
            [workqueue10.resources.insight :refer [insight]]
            [workqueue10.resources.letters :refer [letter]]
            [workqueue10.resources.permission-slip :refer [permission-slip]]
            [workqueue10.resources.media :refer [media-resource]]
            [workqueue10.resources.composition-request
             :refer [composition-request]]
            [workqueue10.resources.outcome :refer [outcome outcome-piece]]
            [workqueue10.resources.person :refer [person]]
            [workqueue10.resources.task :refer [task-resource]]
            [workqueue10.resources.thread :refer [thread-resource]]
            [workqueue10.resources.tickler :refer [tickler]]
            [workqueue10.resources.task-list :refer [task-list-resource]]
            [workqueue10.resources.value :refer [value]]
            [workqueue10.resources.weather :refer [weather]]
            [workqueue10.sources.choreplan :as chores]
            [workqueue10.sources.flickr :as flickr]
            [workqueue10.sources.gate-chat :as gate-chat]
            [workqueue10.sources.gtasks :as gtasks]
            [workqueue10.sources.homeassistant :as ha]
            [workqueue10.sources.hub :as hub]
            [workqueue10.sources.mealplan :as meals]
            [workqueue10.sources.messa :as messa]
            [workqueue10.sources.tgram :as tgram]
            [waymark10.dashboard :as dashboard]
            [workqueue10.connections :as connections :refer [connection]]
            [waymark10.dsl :refer [in-domain]]
            [waymark10.saved-view :refer [saved-view]]
            [waymark10.server.capabilities :as cap :refer [capability]]
            [waymark10.server.engine :as engine]
            [waymark10.server.feed :as feed]
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

(defonce fake-gate
  ;; ONE scriptable Gate behind both thread rigs — the twin stands at
  ;; the TRANSPORT (flickr's kind), so offline dev and the declaration
  ;; gate run the real listing read, the real filters and the real
  ;; translation, with only the socket missing. Empty until something
  ;; scripts it: an unlisted rig discovers nothing, which is the
  ;; honest offline shape.
  (gate-chat/fake-state))

;; both twins ride the same engine-ref birth-fn the real boundaries
;; do, so offline dev exercises the observed-birth half too
(defonce fake-tgram
  (tgram/fake-source fake-gate
                     {:birth-fn (gate-chat/roster-birth-fn
                                 {:engine-ref engine-ref})}))
(defonce fake-messa
  (messa/fake-source fake-gate
                     {:birth-fn (gate-chat/roster-birth-fn
                                 {:engine-ref engine-ref})}))

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

(defn thread-sources
  "The THREAD confluence's tag → ThreadSource map — the confluence
  instantiated a third time (docs/spec-threads.md): one :thread kind
  over the household's conversations, from Telegram and from the
  phone's texts. Both rigs answer through Gate, so BOTH share ONE
  caller — gate-proxy's own client, built once here, which is the
  session reuse the door asks for and the reason this is not two
  transports.

  Real when WORKQUEUE10_GATE_URL names the Gate (the every-boundary
  rule; Gate publishes a deployment default, but a source that went
  real by default would have offline dev and the declaration gate
  reaching for the LAN), the shared in-memory twin otherwise."
  []
  (if-some [url (some-> (System/getenv "WORKQUEUE10_GATE_URL") str not-empty)]
    (let [rpc (gate-chat/rpc {:url url})
          limit (some-> (System/getenv "WORKQUEUE10_GATE_CHAT_LIMIT")
                        parse-long)
          birth-fn (gate-chat/roster-birth-fn {:engine-ref engine-ref})
          cfg {:rpc-fn rpc :limit limit :birth-fn birth-fn}]
      {"tgram" (tgram/source cfg)
       "messa" (messa/source cfg)})
    {"tgram" fake-tgram "messa" fake-messa}))

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
  "One domestic economics (waymark-bwu), across the household's
  domains: the queue's kind, the folded chore registry (chore,
  chore_run, day — bwu.1), the folded meal registry (bwu.2), the
  calendar (waymark-6k5.2), and the folded evening registry
  (activity, evening_plan, evening_session — waymark-26j, the last
  standalone app).

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

  THE THREAD DOMAIN is the confluence instantiated a THIRD time
  (docs/spec-threads.md), over a protocol of its own: :thread, the
  household's conversations as ADDRESSES — one row per chat from
  Telegram and the phone's texts, carrying titles, times and names
  and never a word anybody said. Domainless like :person, and for
  the same family reason: who this house talks to is not a domain of
  logistics beside queue/chores/meals.

  srcs: the task confluence's tag → TaskSource map; media-srcs: the
  media confluence's; thread-srcs: the thread confluence's (the
  narrower arities fill both with the offline fakes — the pre-media
  and pre-thread call sites' shapes, kept); adapter: the family
  calendar's event boundary (a MirrorAdapter AND MirrorCreateAdapter
  — this one is written to, not merely read)."
  ([srcs adapter]
   (resources srcs {"flickr" fake-flickr "hub" (hub/source)} adapter))
  ([srcs media-srcs adapter]
   (resources srcs media-srcs adapter nil))
  ([srcs media-srcs adapter report-fn]
   (resources srcs media-srcs {"tgram" fake-tgram "messa" fake-messa}
              adapter report-fn))
  ([srcs media-srcs thread-srcs adapter report-fn]
   (-> (in-domain :queue [(task-resource (conf/confluence srcs report-fn))
                          (task-list-resource
                           (conf/list-confluence (conf/list-sources srcs)
                                                 report-fn))])
       (into (in-domain :media [(media-resource
                                 (conf/confluence media-srcs report-fn))]))
       (into (in-domain :chores [chore chore-run day]))
       (into (in-domain :meals (mealplan/meal-resources)))
       ;; the evening fold (waymark-26j): the last standalone app's
       ;; three kinds join the one engine — the activity shelf, the
       ;; plan, and its sessions; the plan-sessions consumer registers
       ;; in start!, against the running dispatcher
       (into (in-domain :evenings [activity evening-plan evening-session]))
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
       ;; :permission_slip rides last (waymark-442.6): the house's own
       ;; governance — somebody asks for leave, a grown-up answers,
       ;; and the answer is the row. Domainless for the family reason
       ;; the others are: asking permission is not a domain of family
       ;; logistics beside queue/chores/meals, it is how the house
       ;; decides. Own-surface (the asker reads their own answers)
       ;; and the framework's first declared decision kind — every
       ;; state, action and wall it has is projected from one
       ;; :decision key.
       ;; :tickler rides last (waymark-iqa.4): the note on the dropped
       ;; pile — a marker naming {kind, id} anywhere in the house, a
       ;; date it comes back on, and three one-tap answers. Domainless
       ;; for the same family reason the others are: what the
       ;; household is putting off is not a domain of logistics beside
       ;; queue/chores/meals, it is how the house carries what it has
       ;; not done. A KIND and not a field on task, because task is
       ;; :push-on-write and a "not now" must never call Google Tasks
       ;; (docs/spec-feed.md fork (b)); household-wide, because
       ;; abandoned media and unrun chores are the same pile.
       ;; :insight rides last (waymark-iqa.6): the one card in the feed
       ;; that is not a row the household already had — a finding, its
       ;; citations, and the one physical next step, published by a
       ;; leashed agent at the MCP door and answered by a member.
       ;; Domainless for the same family reason the rest are: what the
       ;; house has NOTICED about itself is not a domain of logistics
       ;; beside queue/chores/meals. The compiler is not in the tree
       ;; and must not be (docs/spec-feed.md § 'The compiler
       ;; contract'); this kind is the engine's half of its leash.
       ;; :value rides last (waymark-jfv.2): what this house actually
       ;; CARES about, written down by the person who cares about it —
       ;; "making memories with the family", "Grandpa is cared for",
       ;; "building", each beside the activities he loves. Domainless
       ;; for the same family reason the rest are, and the clearest
       ;; case of it: what a household values is not a domain of
       ;; logistics beside queue/chores/meals, it is what the
       ;; logistics are FOR. Hand-written rather than a :decision — a
       ;; value is long-lived law, not a one-shot verdict — and
       ;; :nav :secondary on purpose, because a permanently-open row
       ;; on a :primary kind would card in do-now forever and get
       ;; congratulated as a deed when it retired. Declared is law;
       ;; learned is evidence that files asks: an agent may not touch
       ;; a value at any door, and petitions it by publishing an
       ;; insight whose one next step is the value's own
       ;; "these still stand" (docs/spec-outcome-menu.md § 'The value
       ;; kind').
       ;; :outcome and :outcome_piece ride last (waymark-jfv.3): the
       ;; composed bundle and the pieces it is made of — the goal in a
       ;; value's own terms, the routing citation, the rows the
       ;; composer read, and two to five concrete units each already
       ;; prepared to the shape its own door will take. Domainless for
       ;; the same family reason value is: what this week COULD hold
       ;; is not a domain of logistics beside queue/chores/meals, it is
       ;; the arbitrage the logistics are for. Two kinds and not one
       ;; because a verdict carrying a selection is inexpressible in
       ;; the card grammar — so consent is per piece, one thumb at a
       ;; time, and a decline can name WHICH part was wrong. The
       ;; composer only proposes: every verdict on both kinds is
       ;; walled against the principal that staged it and against
       ;; agents in general, and materialization happens under the
       ;; accepting member's own name through the target kinds' own
       ;; create doors (docs/spec-outcome-menu.md § 'The outcome and
       ;; its pieces').
       ;; :person rides last (waymark-jfv.11): the roster — who is who
       ;; in this house, so plans stop guessing. It exists because a
       ;; composer read correct rows and invented the relationship
       ;; between them, pairing a woodworking Saturday with a caregiver
       ;; as though he were a son. Their everyday name, how they
       ;; relate in the owner's own words, who they relate THROUGH, when
       ;; they were born if the house knows, and whether they are in
       ;; this family's life now or were — current and past as STATES,
       ;; because a CNA who leaves is a transition worth a record.
       ;; Domainless for the same family reason value is: who this
       ;; household's people are is not a domain of logistics beside
       ;; queue/chores/meals, it is who the logistics are ABOUT. NOT
       ;; members: a member is a login principal and most of these
       ;; people will never log in. :nav :secondary and no population,
       ;; for value's reason — a roster is not a thing to do. What
       ;; reads it is `outcome/names-a-person` at the composer's create
       ;; door (docs/spec-outcome-menu.md § 'Built — jfv.11').
       ;; :composition_request rides last (waymark-jfv.20): the
       ;; person's own pull — "compose me another" — one tap, born by
       ;; a person and never by an agent, standing a week, answered by
       ;; the one outcome that cites it. It was born to get a person's
       ;; own pull past the weekly cap on the machine's initiative
       ;; (8um law 6, applied to composition), and outlived the cap
       ;; (waymark-1uv.3) as the crown rank's first tier: a bundle
       ;; that answers a request stands above every one nobody asked
       ;; for. Domainless for value's reason, and
       ;; :nav :system for outcome's: a request is neither work nor a
       ;; decision, and a card for it would be the feed manufacturing
       ;; a thing to answer. The crown carries it instead
       ;; (docs/spec-outcome-menu.md § 'Built — jfv.20').
       ;; :thread rides last (waymark-36s): the household's
       ;; conversations as ADDRESSES — a row per chat, so a fact found
       ;; in a text has somewhere to point, the sitting's thread
       ;; selection reads ROWS instead of guessing, and a thread whose
       ;; last word moved is an arrival. Domainless beside :person for
       ;; the same family reason, and :nav :secondary for value's: a
       ;; conversation is not a thing to do, and a permanently-open
       ;; row on a primary kind would card in do-now forever. Mirror
       ;; the THREAD, never the messages — titles, times and names;
       ;; bodies stay behind Gate under a capability a person approved.
       ;; :hypothesis rides last (waymark-bug, docs/spec-hypotheses.md):
       ;; the belief layer, and the house's first row that carries a
       ;; NUMBER about the people in it. A claim in the household's own
       ;; words, one of five shapes, the rows it is about, where the
       ;; guess started — and a posterior no door can set, folded from
       ;; the typed findings whose citations overlap what the claim is
       ;; about. It sits beside :value and :insight for the family
       ;; reason those two do: what this house believes about itself is
       ;; not a domain of logistics beside queue/chores/meals, it is
       ;; what the logistics are ABOUT. Hand-written rather than a
       ;; :decision — a belief is long-lived and keeps moving, not a
       ;; one-shot verdict — and :nav :secondary for value's reason. Its
       ;; machine is value's, on purpose: observed → affirmed, with
       ;; `restate` for the reading that noticed it and `revise` for the
       ;; person whose house it is about. A REALLY IMPORTANT WALL RIDES
       ;; IT: the run that read the evidence can never answer what it
       ;; means (four eyes on `observed_by`, and no grant opens that
       ;; arm), because every likelihood ratio in the table assumes the
       ;; evidence was typed by somebody who did not know what it would
       ;; do.
       (into (into [saved-view capability connection self journal weather letter
                    permission-slip tickler insight value outcome outcome-piece
                    person composition-request hypothesis
                    (thread-resource (conf/thread-confluence thread-srcs
                                                             report-fn))]
                   dashboard/resources)))))

(def surfaces
  "Both decision screens, one engine: the housekeeper's day board and
  the planner's week board."
  (into [day-board] mealplan/surfaces))

(def feed-recipe
  "This household's feed order (waymark-iqa.24). The default recipe
  with ONE line added, and the line is the whole point.

  The first read of the real feed found do-now holding three movies
  and a chore run somebody skipped a fortnight ago, and not one of
  the thirty-three open tasks — sixteen of them overdue, a brake
  booster and a caregiving cluster and an insurance policy among
  them. The framework's spread now keeps any one kind from crowding
  the others out by sheer count, and that alone would have let the
  queue in. This says the rest out loud: in THIS house the queue is
  what the morning is for, so two of do-now's five slots are the
  queue's before anything else is considered.

  It is static data and it ranks nothing. The mixer's claim is total,
  so the second line never re-offers a task the first one named — the
  two lines are disjoint by construction, and a house with an empty
  queue simply reads a do-now of five other things.

  BOTH LINES SAY WHAT THEY ARE FOR (waymark-iqa.29). `:says` is the
  household's own sentence for a recipe line, and the feed's narrated
  recipe reads it back to whoever asks why a card is here. A line
  without one narrates itself perfectly well; these two earn theirs,
  because *the queue comes first* is a decision this house made and
  not a shape the framework would have inferred."
  (assoc feed/default-recipe
         :order
         (into []
               (mapcat (fn [e]
                         (if (= :next_actions (:population e))
                           [(assoc e :take 2 :kinds [:task]
                                   :says (str "Do now, first two slots: the"
                                              " work queue. In this house the"
                                              " queue is what the morning is"
                                              " for, so two cards are the"
                                              " queue's before anything else"
                                              " is considered."))
                            (assoc e :take 3
                                   :says (str "Do now, three more: anything"
                                              " else the house goes to and"
                                              " has not finished — a chore"
                                              " run, a film, an errand — one"
                                              " kind at a time so no pile"
                                              " crowds the others out."))]
                           [e])))
               (:order feed/default-recipe))))

(defn check-resources
  "Zero-arg so the declaration gate needs no env — every kind over
  the offline fakes."
  []
  (resources {"chore" fake-chores "meal" fake-meals "todo" fake-todos
              "gtasks" fake-gtasks}
             {"flickr" fake-flickr "hub" (hub/source)}
             {"tgram" fake-tgram "messa" fake-messa}
             fake-calendar
             nil))

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
             {:token "notes.read"
              :description (str "Read Google Keep notes through Gate — "
                                "list, search, and single-note reads.")
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
              :enforced_by gate}
             ;; the one row here Gate does not stand in front of
             ;; (waymark-iqa.23): the power granted is THIS engine's
             ;; feed route, so waymark holds the data and the law
             ;; both. Seeded like the rest because a capability is a
             ;; ROW and an ask naming a token no row carries refuses
             ;; at the door — the registry is the vocabulary's clock.
             cap/feed-preview-as])]
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
   ;; the two thread rigs, both behind ONE Gate caller — so they are
   ;; real or fake together, and the panel says which by the same env
   ;; gate thread-sources judges by. NO :provider: that key advertises
   ;; a /auth/<provider>/reconsent door, and Gate's credential is
   ;; Gate's own — there is nothing here for a person to re-consent to
   "tgram" {:mode (if (System/getenv "WORKQUEUE10_GATE_URL") "real" "fake")}
   "messa" {:mode (if (System/getenv "WORKQUEUE10_GATE_URL") "real" "fake")}
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
                                         (thread-sources)
                                         (calendar-adapter)
                                         (connections/fan-reporter engine-ref))
                             ;; the calendar's adapter is no confluence,
                             ;; so its health arrives kind-level through
                             ;; the mirror's own pass hook
                             :report-pass (connections/pass-reporter
                                           engine-ref {:event "calendar"})
                             :surfaces surfaces
                             ;; the household's own feed order — the
                             ;; recipe is an engine opt, read once at
                             ;; the route's build site (waymark-iqa.24)
                             :feed feed-recipe
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
                                     (oidc-rp/wrap-handler eng))})
        ;; the evening fold's durable consumer (waymark-26j): a plan's
        ;; sessions appear no matter who created the plan — registered
        ;; against the RUNNING engine, like eveningplan10's own boot did
        consumer (evening-consumers/register! eng)]
    (reset! dev {:engine eng :server server :storage storage
                 :consumer consumer})
    (println (str "workqueue10: http://localhost:" port
                  "/api/.well-known/waymark"))
    eng))

(defn stop! []
  (when-some [{:keys [engine server storage consumer]} @dev]
    (when consumer (evening-consumers/stop! consumer))
    (engine/stop! engine server)
    (pg/close! storage)
    (reset! dev nil)))

(defn -main [& _]
  (start!)
  ;; WAYMARK10_WATCH=1 (make dev-queue): reload-on-save — changed
  ;; sources under src/ load-file, then stop!/start!
  (when (= "1" (System/getenv "WAYMARK10_WATCH"))
    ((requiring-resolve 'waymark10.dev/watch!)
     {:restart! (fn [] (stop!) (start!))}))
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
                                                 (thread-sources)
                                                 (calendar-adapter)
                                                 nil))
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
                                                       (thread-sources)
                                                       (calendar-adapter)
                                                       nil)
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
