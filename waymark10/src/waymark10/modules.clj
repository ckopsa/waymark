(ns waymark10.modules
  "The module inventory: everything this artifact can serve beside the
  law, one entry each, and what each entry hands the engine.

  Until db9.2 this was a literal inside `full-registry` — eight kinds
  concat'd in by name, plus one hand-written special case for the
  worksheet. Until db9.3 the router carried the other half, nine
  extension namespaces required by name and sewn into one 26-route
  vector. This namespace is both literals turned into a value a
  reviewer can read on one screen and diff against yesterday's
  (docs/spec-modularization.md).

  An entry names a module and carries its contributions:

      {:module :worksheet
       :enrols [{:kind :worksheet :enroll :when-declared
                 :kinds worksheet/kinds}]
       :routes worksheet-routes/routes
       :hooks  []
       :pack   packs/worksheet}

  The spec closes the list of contributions at FOUR — kinds, routes,
  runtime surfaces, a conformance pack — and with `:hooks`
  (waymark-db9.4) the table is COMPLETE. It grows no further column;
  a module that needs a fifth kind of contribution is a proposal to
  change core, reviewed as one. An entry naming a module that
  contributes nothing at all is still worth its line, because `known`
  is what refuses an unknown label and what the conformance driver
  selects on.

  That closure was reviewed once and held (waymark-db9.7). The
  router's four remaining reach-ins wanted a `:read-through` /
  `:defer` column, and got protocols in `waymark10.server.seams`
  instead: a core handler asks a VALUE the assembly already handed it
  — a running surface, a declaration's own spec, an enrolled kind —
  rather than a column of opaque fns, which is the keyword→fn
  registry with better manners. See the spec's § 'The reach-ins'.

  ── kinds ──

  `:enrols` is a vector of enrollment entries. `:kinds` inside one is
  a FUNCTION of the application's own resources, never a constant
  rdef, because the worksheet SYNTHESIZES its declaration from the
  kinds that declare `:worksheet` — its create door's `:target` enum
  is baked from their names. That forces `:kind` down to a LABEL: the
  check, conformance selection and this table's warnings address a
  kind by it, never the payload. An honest wart, recorded in the
  spec, and the price of keeping the worksheet expressible.

  `:enroll` says who does the handing:

    :always        — the table enrols it in every engine assembled
                     with that module.
    :when-declared — the table enrols it when `:kinds`, asked of the
                     app's own resources, answers with something. The
                     worksheet's rule verbatim: no app declares it,
                     no kind appears.
    :app-opt-in    — the APP enrols it, by naming the rdef in its own
                     resources vector; these entries carry no `:kinds`
                     at all. The table's whole job here is to know the
                     kind exists, which is enough for `check` to stay
                     quiet about a legitimate opt-in, and for the
                     conformance driver to know the surface is on.

  ── routes ──

  `:routes` is `(fn [eng]) → {:module label :static [route …] :plural
  [route …]}`, and the two buckets are not decoration: the router runs
  `{:conflicts nil}` and matches linearly, so POSITION is the routing
  rule. `/api/{plural}/-/worksheet` mounted after
  `/api/{plural}/-/{action}` is not an error — it is a surface that
  silently stopped existing. `:static` mounts before the plural
  grammar, `:plural` inside it; see
  waymark10.server.router/assemble-routes.

  ── runtime surfaces ──

  `:hooks` is a vector of lifecycle hooks — the literal engine/start!
  used to build by hand and engine/stop! used to take apart by hand,
  turned into data the engine ITERATES
  (waymark10.server.runtime/start-hooks!):

      {:hook    :webhooks-deliverer      ; the runtime key it publishes
       :after   [:dispatcher]            ; start-order constraint
       :when    (fn [eng] …)             ; optional: run at all?
       :elected :webhooks-deliverer      ; optional: one per storage
       :start   (fn [eng running]) → handle
       :stop    (fn [handle])}

  `:start` is handed the surfaces already running, keyed by hook, so
  a surface that needs another names it in `:after` and reads it from
  that map — the curtain takes the dispatcher, presence and intents
  take the curtain. `:after` is the ONLY order anyone declares and
  both orders derive from it; see runtime/order.

  `:elected` is the ROLE NAME, and it is a property of a HOOK rather
  than of a subsystem: 'one holder per database' is a fact about a
  running surface, and saying it here is what let server/coherence
  stop reaching into webhooks and maintainer to start them itself.
  The engine elects through the STORAGE, so the in-memory twin runs
  the same surface as a plain start instead of dropping it.

  Opts are read off the engine at the start site with their defaults,
  exactly as the old literal read them — :events-poll-ms,
  :curtain-ttl-ms, :sweep-interval-ms and the rest — rather than each
  module growing an opts map of its own. One engine, one opts map.

  ── the conformance pack ──

  `:pack` is a PLAIN VALUE, not a function: {:module label
  :obligations [{:name … :needs #{…} :run (fn [ctx])} …]}, exactly as
  docs/spec-modularization.md § 'A conformance pack' spells it. It
  differs from `:routes` on purpose — a route closes over the engine
  and must be built per boot; an obligation takes the driver's ctx
  when it RUNS, so nothing about it depends on which engine is
  asking, and the column can stay something a reader can read. (The
  db9.3 handoff note suggested copying `route-sets` verbatim, fn and
  all; the spec's literal wins, and this paragraph is the record of
  the disagreement.)

  A pack keys off what its module ACTUALLY CONTRIBUTED, never off the
  label — that is what `:needs` is for, and why `packs` below hands
  back whole packs and lets the driver judge each obligation. The
  bodies live in waymark10.test.packs; requiring it from here is the
  price of the column being a literal rather than a lookup, and it
  costs one small test-library namespace at load. Worth recording for
  db9.6: that library reaches `server.store.migrate`, which reaches
  Postgres, so the artifact split will want the packs behind their own
  boundary.

  No discovery: this vector is authoritative and the classpath is
  never listed, for the reason `ui-assembly` already states out loud.
  `.well-known/waymark`, the openapi overlay and every definition row
  are projections of everything this engine serves; if that set were
  discovered rather than declared, `boot-revise!` would write a
  DIFFERENT law in a dev REPL than in a container with one extra jar
  on the path, silently.

  Nothing here runs at load beyond the `defresource` gates the
  required namespaces already run, and nothing here touches storage —
  `waymark10.check` reads this table with no database at all."
  (:require [waymark10.server.attachments :as attachments]
            [waymark10.server.belief :as server-belief]
            [waymark10.server.coherence :as coherence]
            [waymark10.server.curtain :as curtain]
            [waymark10.server.definitions :as defs]
            [waymark10.server.events :as events]
            [waymark10.server.grants :as grants]
            [waymark10.server.intents :as intents]
            [waymark10.server.jobs :as jobs]
            [waymark10.server.judgments :as judgments]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.members :as members]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.presence :as presence]
            [waymark10.server.roles :as roles]
            [waymark10.server.seats :as seats]
            [waymark10.server.routes.attachments :as attachment-routes]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.mcp-servers :as mcp-servers]
            [waymark10.server.routes.gate :as gate-routes]
            [waymark10.server.routes.law-sweep :as law-sweep-routes]
            [waymark10.server.routes.mcp :as mcp-routes]
            [waymark10.server.routes.mirror :as mirror-routes]
            [waymark10.server.routes.openapi :as openapi-routes]
            [waymark10.server.routes.realtime :as realtime-routes]
            [waymark10.server.routes.seasons :as seasons-routes]
            [waymark10.server.routes.seats :as seat-routes]
            [waymark10.server.routes.ui :as ui-routes]
            [waymark10.server.routes.worksheet :as worksheet-routes]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.wakes :as wakes]
            [waymark10.server.webhooks :as webhooks]
            [waymark10.server.worksheet :as worksheet]
            [waymark10.judgment :as judgment]
            [waymark10.verdict :as verdict]
            [waymark10.test.packs :as packs]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def inventory
  "Every module this artifact ships, in the order a reader should meet
  them: core first, then the kinds, then the surfaces.

  `:module :core` is the law's own vocabulary wearing the table's
  shape — `guards/role` is a core built-in minting `role:manager`
  tokens and `guards/owner` mints `owner:<field>`, so an engine that
  dropped the role or grant kind would advertise a token nothing can
  mint, the exact drift this framework exists to make impossible.
  Core is therefore never filtered out; see `selected`. Core's ROUTES
  are not here either: the router mounts them itself, because a
  waymark engine that does not answer them is not a waymark engine.

  Three entries carry nothing at all. The postgres store and the CLI
  are modules by DEPENDENCY, not by seam (the spec says so out loud);
  oidc's routes belong to the embedding app, and its two resolvers are
  the router's own identity boundary. They are listed because the
  inventory is the artifact's map, and a map with roads missing is
  worse than no map."
  [{:module :core
    :enrols [{:kind :definition :enroll :always
              :kinds (fn [_] [defs/definition])}
             {:kind :member :enroll :always
              :kinds (fn [_] [members/member])}
             {:kind :role :enroll :always
              :kinds (fn [_] [roles/role])}
             {:kind :grant :enroll :always
              :kinds (fn [_] [grants/grant])}
             {:kind :approval_request :enroll :always
              :kinds (fn [_] [grants/approval-request])}
             ;; the seat, the model, the sitting and the schedule
             ;; (docs/spec-seat.md, waymark-fp62.1) are core's too: the
             ;; grant — core's own — carries a typed ref to the seat,
             ;; and the seat to its schedule, so an engine assembled
             ;; from a module subset would refuse its own grant kind
             ;; without them (checks/refs). The office an agent sits
             ;; in is the law's vocabulary, the same way the grant is.
             {:kind :seat :enroll :always
              :kinds (fn [_] [seats/seat])}
             {:kind :model :enroll :always
              :kinds (fn [_] [seats/model])}
             {:kind :sitting :enroll :always
              :kinds (fn [_] [seats/sitting])}
             {:kind :schedule :enroll :always
              :kinds (fn [_] [schedules/schedule])}
             ;; the MCP server as a row (docs/spec-mcp-servers.md,
             ;; waymark-fp62.10): the external powers a grant names
             ;; reach the engine through a row's client, and a grant
             ;; is core's — so the row that holds the policy behind a
             ;; dotted scope entry is core's too, beside the seat.
             {:kind :mcp_server :enroll :always
              :kinds (fn [_] [mcp-servers/mcp-server])}
             ;; the held call (docs/spec-mcp-servers.md R-14,
             ;; waymark-fp62.10.2): a powers entry that says
             ;; `approval person` does not forward its call, it mints
             ;; one of these and waits for a person. Core's beside the
             ;; server row for the server row's own reason. The
             ;; policy that holds a call is a field of `powers`, so
             ;; the kind that holds it cannot be a module an engine
             ;; leaves out while still serving the policy that names
             ;; it. The row carries a typed ref to `sitting` too,
             ;; which is core's already.
             {:kind :held_call :enroll :always
              :kinds (fn [_] [held-calls/held-call])}
             ;; the judgment and the verdict (waymark-fp62.11) are
             ;; core's for the seat's own reason: the seat — core's —
             ;; carries a typed ref to the judgment it walks, so an
             ;; engine assembled from a module subset would refuse its
             ;; own seat kind without them (checks/refs). One law in
             ;; two kinds: `judgment`, the judge declared as a row, and
             ;; `verdict`, the row about a row that answers it. Both
             ;; together or neither: a judgment with no verdict kind is
             ;; a question nothing may answer, and a verdict with no
             ;; judgment kind is an answer under no question. They name
             ;; no application vocabulary — a judgment names its
             ;; subject as a kind TOKEN ({subject_kind, subject_id}),
             ;; which is what lets one pair of kinds serve every judge
             ;; in the house, the engine's own rows included (R-7).
             {:kind :judgment :enroll :always
              :kinds (fn [_] [judgment/judgment])}
             {:kind :verdict :enroll :always
              :kinds (fn [_] [verdict/verdict])}]
    ;; the three surfaces no waymark engine is a waymark engine
    ;; without: the outbox reader every other surface rides, the
    ;; law-refresh consumer (a core need in any multi-process
    ;; deploy — server/coherence's own half of the straddle), and
    ;; the clock sweeper, because derivations are law.
    :hooks [{:hook :dispatcher
             :start (fn [eng _]
                      (events/dispatcher
                       eng {:poll-ms (:events-poll-ms eng 2000)}))
             :stop events/stop!}
            {:hook :law-refresh
             :after [:dispatcher]
             :start (fn [eng running]
                      (coherence/start-refresh!
                       eng (:dispatcher running)
                       {:debounce-ms (:law-refresh-debounce-ms eng 1000)}))
             :stop coherence/stop-refresh!}
            {:hook :clock-sweeper
             :elected :clock-sweeper
             :start (fn [eng _]
                      (maintainer/start-sweeper!
                       eng {:interval-ms (:sweep-interval-ms eng 30000)}))
             :stop maintainer/stop-sweeper!}
            ;; the MCP servers' cadence (spec-mcp-servers R-4): every
            ;; live row's tools/list re-read on an interval, mirrored
            ;; through the discover door when the hash moved. Elected,
            ;; one holder per storage, because two engines discovering
            ;; the same row would write the same mirror twice.
            {:hook :mcp-discover
             :elected :mcp-discover
             :start (fn [eng _]
                      (mcp-servers/start-discover-sweeper!
                       eng {:interval-ms
                            (get-in eng [:services :mcp-servers :discover-ms]
                                    mcp-servers/default-discover-ms)}))
             :stop mcp-servers/stop-discover-sweeper!}
            ;; the held calls' expiry (R-14, R-7): nothing runs late.
            ;; Elected for the discover pass's reason: two engines
            ;; expiring the same row would walk the same door twice,
            ;; and the second walk is a refusal in the log for
            ;; nothing.
            {:hook :held-call-expiry
             :elected :held-call-expiry
             :start (fn [eng _]
                      (held-calls/start-expiry-sweeper!
                       eng {:interval-ms
                            (get-in eng [:services :held-calls :sweep-ms]
                                    300000)}))
             :stop held-calls/stop-expiry-sweeper!}]
    :pack packs/core}

   {:module :attachments
    :enrols [{:kind :attachment :enroll :always
              :kinds (fn [_] [attachments/attachment])}]
    :routes attachment-routes/routes
    :hooks [{:hook :attachments-purge
             :elected :attachments-purge
             :start (fn [eng _]
                      (attachments/start-purge-sweeper!
                       eng {:interval-ms (:purge-sweep-ms eng 60000)}))
             :stop attachments/stop-purge-sweeper!}]
    :pack packs/attachments}

   {:module :webhooks
    :enrols [{:kind :subscription :enroll :always
              :kinds (fn [_] [webhooks/subscription])}]
    ;; the deliverer rides the dispatcher as its wake signal, and it
    ;; is elected because two processes double-deliver: the
    ;; per-subscription cursor is shared and unguarded
    :hooks [{:hook :webhooks-deliverer
             :after [:dispatcher]
             :elected :webhooks-deliverer
             :start (fn [eng running]
                      (webhooks/start-deliverer!
                       eng (:dispatcher running)
                       {:poll-ms (:webhooks-poll-ms eng 2000)}))
             :stop webhooks/stop-deliverer!}]
    :pack packs/webhooks}

   {:module :jobs
    :enrols [{:kind :job :enroll :always
              :kinds (fn [_] [jobs/job])}]
    ;; the WORKER is not elected and must not be: it claims leases
    ;; (claim-or-steal on expiry), so every process may run one and
    ;; more of them is more throughput. The orphan SWEEP is elected —
    ;; re-queuing a dead worker's jobs twice is double work over the
    ;; same page.
    :hooks [{:hook :jobs-worker
             :start (fn [eng _]
                      (jobs/start-worker!
                       eng {:poll-ms (:jobs-poll-ms eng 1000)
                            :batch-size (:jobs-batch-size eng 10)}))
             :stop jobs/stop-worker!}
            {:hook :jobs-orphan-sweeper
             :elected :jobs-orphan-sweeper
             :start (fn [eng _]
                      (jobs/start-orphan-sweeper!
                       eng {:interval-ms (:orphan-sweep-ms eng 30000)}))
             :stop jobs/stop-orphan-sweeper!}]
    :pack packs/jobs}

   ;; the when-declared precedent, unchanged: worksheet/kinds is both
   ;; the predicate and the synthesis — it answers empty when no kind
   ;; declares :worksheet, and otherwise bakes the target enum from
   ;; the declaring kinds' names.
   {:module :worksheet
    :enrols [{:kind :worksheet :enroll :when-declared
              :kinds worksheet/kinds}]
    :routes worksheet-routes/routes
    :pack packs/worksheet}

   ;; app-opt-in: the rdefs ride the application's own resources
   ;; vector, indistinguishable from a domain kind. Listing them here
   ;; buys warnings and conformance selection, and nothing else —
   ;; which is little, but it is the truth about the surface.
   {:module :capabilities
    :enrols [{:kind :capability :enroll :app-opt-in}]
    :pack packs/capabilities}

   {:module :dashboard
    :enrols [{:kind :saved_view :enroll :app-opt-in}
             {:kind :dashboard :enroll :app-opt-in}
             {:kind :dashboard_slot :enroll :app-opt-in}]
    :pack packs/dashboard}

   ;; the seat's schedule (spec-seat.md §12): the means by which a
   ;; sitting is created, one row per seat, mirrored out to the
   ;; harness's own scheduler. `:always` for the seats module's reason
   ;; — a schedule names a cron, a model and a provider, and none of
   ;; that is any application's vocabulary — and for one more of its
   ;; own: seats.clj records that it had to hold `schedule` as an
   ;; opaque string because a typed ref refuses an engine whose target
   ;; kind is absent, and a kind enrolled in every engine is exactly
   ;; what makes that one line true again.
   ;;
   ;; Unlike :seats, this module RUNS things: the log consumer that
   ;; mirrors a seat out, and the read-back sweep that reports drift.
   ;; Both wear `:when` (the hook asks the REGISTRY, not this table)
   ;; and `:elected` for the webhook deliverer's reason — two
   ;; processes mirroring one seat write two copies at the provider,
   ;; and the consumer's cursor is shared and unguarded.
   {:module :schedules
    ;; the kind itself is core's (see :module :core); this module is
    ;; the two surfaces that RUN for it
    :hooks [{:hook :schedules-mirror
             :after [:dispatcher]
             :elected :schedules-mirror
             :when schedules/serving?
             :start (fn [eng running]
                      (schedules/start-mirror!
                       eng {:dispatcher (:dispatcher running)
                            :poll-ms (:events-poll-ms eng 2000)}))
             :stop schedules/stop-mirror!}
            {:hook :schedules-drift
             :elected :schedules-drift
             :when schedules/serving?
             :start (fn [eng _]
                      (schedules/start-drift-sweeper!
                       eng {:interval-ms
                            (:schedule-drift-ms
                             eng schedules/default-drift-interval-ms)}))
             :stop schedules/stop-drift-sweeper!}
            ;; R-12.22's third way a sitting begins: the transitions a
            ;; seat asked to be woken by. A second durable consumer on
            ;; its own cursor (`:wakes`), plus the tick that releases
            ;; the wakes the damper held — `:elected` and `:when` for
            ;; the two above's reasons, and `:after [:dispatcher]`
            ;; because a log consumer rides it as its wake signal.
            {:hook :wakes
             :after [:dispatcher]
             :elected :wakes
             :when schedules/serving?
             :start (fn [eng running]
                      (wakes/start-wakes!
                       eng {:dispatcher (:dispatcher running)
                            :poll-ms (:events-poll-ms eng 2000)
                            :tick-ms (:wake-tick-ms
                                      eng wakes/default-tick-ms)}))
             :stop wakes/stop-wakes!}
            ;; R-5 of waymark-fp62.11: the consequence a judgment
            ;; names, walked on the subject AFTER the verdict is said.
            ;; A third durable consumer on its own cursor
            ;; (`:judgments`), here beside the wake's because this is
            ;; the module that RUNS consumers — the two judgment kinds
            ;; are enrolled elsewhere, and `:when` is what keeps an
            ;; engine that does not serve them from running a thread
            ;; for nothing. `:elected` for the wake's own reason: two
            ;; processes walking one consequence open one door twice,
            ;; and the cursor is shared and unguarded.
            {:hook :judgments
             :after [:dispatcher]
             :elected :judgments
             :when judgments/serving?
             :start (fn [eng running]
                      (judgments/start-judgments!
                       eng {:dispatcher (:dispatcher running)
                            :poll-ms (:events-poll-ms eng 2000)}))
             :stop judgments/stop-judgments!}]}

   ;; routes only, from here down — and their packs are route-shaped
   ;; to match: an obligation needing [:route m] is skipped, never
   ;; failed, on an engine that left the module out.
   {:module :seasons :routes seasons-routes/routes :pack packs/seasons}

   ;; the realtime bundle's four surfaces, and the one place `:after`
   ;; is doing real work: the curtain is started ONCE and handed to
   ;; BOTH watching surfaces, so presence and intents read one member
   ;; row through one cache. Getting this order wrong is not a crash —
   ;; it is two caches, or none.
   {:module :realtime
    :routes realtime-routes/routes
    :hooks [{:hook :curtain
             :after [:dispatcher]
             :start (fn [eng running]
                      (curtain/start!
                       eng {:ttl-ms (:curtain-ttl-ms eng 2000)
                            :dispatcher (:dispatcher running)}))
             :stop curtain/stop!}
            {:hook :presence
             :after [:curtain]
             :start (fn [eng running]
                      (presence/start!
                       eng {:hb-ms (:presence-heartbeat-ms eng 15000)
                            :curtain (:curtain running)}))
             :stop presence/stop!}
            {:hook :intents
             :after [:dispatcher :curtain]
             :start (fn [eng running]
                      (intents/start! eng {:dispatcher (:dispatcher running)
                                           :curtain (:curtain running)}))
             :stop intents/stop!}]
    :pack packs/realtime}

   ;; the when-declared RUNTIME precedent, the shape `:when`
   ;; generalizes: an engine whose registry holds no mirror kind
   ;; starts no discovery daemon and pays nothing for the module.
   {:module :mirror
    :routes mirror-routes/routes
    :hooks [{:hook :discovery
             :when (fn [eng] (seq (mirror/mirror-kinds eng)))
             :start (fn [eng _] (mirror/start-discovery! eng))
             :stop mirror/stop-discovery!}]
    :pack packs/mirror}
   {:module :openapi :routes openapi-routes/routes :pack packs/openapi}
   {:module :ui :routes ui-routes/routes :pack packs/ui}

   ;; the MCP surface (waymark-4mk): a fixed tool list — the spec's
   ;; six, the batch lookup, and the two Gate power tools
   ;; (waymark-912p) — over whatever the caller's grant projects, the
   ;; list itself never moving with the leash; the first module built on these
   ;; seams rather than retrofitted onto them. It enrols no kind and
   ;; starts nothing — an engine that leaves it out simply has no
   ;; /api/-/mcp, which is what a deployment that does not want to be
   ;; agent-drivable should look like.
   {:module :mcp :routes mcp-routes/routes :pack packs/mcp}

   ;; the law sweep (waymark-442.3): one GET that answers "which live
   ;; rows does this proposal re-judge differently" before anybody
   ;; promotes it. The spec's original asked for a kind enrolled "only
   ;; on engines running :propose mode" — a verb this table does not
   ;; have and must not grow, because enrollment is what `check` and
   ;; the conformance driver select on and a runtime env var deciding
   ;; it is the exact silent drift the no-discovery paragraph forbids.
   ;; So: a module, propose-only as the DOOR's own state constraint
   ;; (`law-sweep/swept-from`), and an engine that does not want the
   ;; surface simply does not assemble it.
   {:module :law-sweep :routes law-sweep-routes/routes :pack packs/law-sweep}

   ;; the belief layer (waymark-bug): the nightly fold that keeps every
   ;; hypothesis's posterior equal to the arithmetic over its atoms.
   ;; Elected — two processes folding one store would write the same
   ;; numbers twice — and `:when`-gated on a hypothesis kind being
   ;; served, so an engine with no belief layer starts nothing. A DAY
   ;; rather than an hour, because decay is a per-day arithmetic against
   ;; half-lives measured in months. It enrols no kind and mounts no
   ;; route: the hypothesis kind is the application's.
   ;;
   ;; (This hook rode the `:feed` module until that module was retired
   ;; 2026-09 — the feed document, its recipe, the view record, the
   ;; ranking note, the quick reasons, the remark and the tickler sweep
   ;; all went with it.)
   {:module :belief
    :hooks [{:hook :belief-sweeper
             :elected :belief-sweeper
             :when server-belief/serves-hypotheses?
             :start (fn [eng _]
                      (server-belief/start-belief-sweeper!
                       eng {:interval-ms (:belief-sweep-ms eng 86400000)}))
             :stop server-belief/stop-belief-sweeper!}]
    :pack packs/belief}

   ;; the power door's hypermedia surface (waymark-q95): two bespoke
   ;; doors — GET /api/-/gate, the affordance document (the live
   ;; servers' mirrored tools ∩ the presented grant), and POST
   ;; /api/-/gate/{tool}, the grant-checked forward. It enrols NO kind
   ;; and starts nothing: the servers themselves are `mcp_server` rows
   ;; (core's, above), and the module holds only the grant judgment
   ;; (server/gate-proxy). An engine without it has no hypermedia door
   ;; to the powers; the MCP door's two power tools still answer.
   {:module :gate :routes gate-routes/routes}

   ;; the seat, the model and the sitting (docs/spec-seat.md, leg 1 of
   ;; waymark-fp62): the office an agent sits in, the price list its
   ;; model is on, and the record of what one wake cost. `:always`, and
   ;; for one reason — these three name no
   ;; application vocabulary at all. A seat's scope names whatever
   ;; kinds the house happens to serve, its model is an API identifier
   ;; and a price, and its sitting is four token counts; nothing in
   ;; them belongs to mealplan or to the queue. What they ARE is the
   ;; engine's own answer to "what did this cost, and can a cheaper
   ;; model hold it" — a question every deployment that lets an agent
   ;; act has, so there is nothing left to opt into.
   ;;
   ;; It starts nothing, and its two routes are the ledger (R-11.3a) —
   ;; the six answers about a seat over a window — and the door a
   ;; session's end reports its bill through (R-12.17), which is what
   ;; closes the sitting a keyed sitter opened. Both are a route and
   ;; nothing else. The other two halves of the wave land where they
   ;; belong rather than as a fifth column on this table: the boot
   ;; sweep (R-7.1/R-7.6) in boot-revise!, the seat resolve (R-5.2) in
   ;; the router.
   {:module :seats
    ;; the three kinds are core's (see :module :core); this module is
    ;; the ledger route beside them
    :routes seat-routes/routes}

   ;; named, contributing nothing through this seam
   {:module :postgres-store}
   {:module :oidc}
   {:module :cli}])

(def enrollment
  "The inventory's kind entries flattened, each stamped with the
  module that owns it — the shape the registry side of this namespace
  reads, and the shape db9.2 published. The inventory above is the
  source; this is a projection of it, so the two can never disagree."
  (into []
        (mapcat (fn [{:keys [module enrols]}]
                  (map #(assoc % :module module) enrols)))
        inventory))

(def known
  "The module labels this table names."
  (into #{} (map :module) inventory))

(defn selected
  "The inventory entries an engine assembling `modules` serves. nil —
  the only spelling any caller uses today — means every module in the
  table, the single-artifact posture this repo still ships. A named
  selection keeps `:core` regardless: core is not one of the things
  you may leave out.

  This is the ONE join point. Kinds (`enrolled`), warnings and route
  sets all filter through it, so core-never-dropped and
  unknown-label-refusal are had once and inherited — and the lifecycle
  hooks (db9.4) and conformance packs (db9.5) will inherit them by
  reading it too. Refuse an unknown label rather than silently serving
  less than the caller asked for."
  [modules]
  (if (nil? modules)
    inventory
    (let [wanted (set modules)
          unknown (sort (remove known wanted))]
      (when (seq unknown)
        (throw (t/definition-error
                (str "unknown module(s) " (vec unknown)
                     " — the enrollment table names "
                     (vec (sort known)))
                {:check :modules :unknown (vec unknown)})))
      (filterv #(or (= :core (:module %)) (contains? wanted (:module %)))
               inventory))))

(defn- contributed
  "The rdefs one enrollment entry hands the registry for these
  resources — nothing for :app-opt-in, where the app's own vector is
  the seam."
  [{:keys [enroll kinds]} resources]
  (if (and kinds (contains? #{:always :when-declared} enroll))
    (vec (kinds resources))
    []))

(defn enrolled
  "Every rdef the table itself adds beside the application's own —
  what `full-registry` used to spell as a concat."
  [resources modules]
  (into []
        (comp (mapcat :enrols)
              (mapcat #(contributed % resources)))
        (selected modules)))

(defn route-sets
  "Every assembled module's routes, asked of this engine, in table
  order — what `router/handler` used to spell as one literal vector.
  Each answer is {:module label :static [route …] :plural [route …]};
  the router mounts the two buckets in the two places they have to go
  and never learns which module any of them came from."
  [eng modules]
  (into [] (keep (fn [{f :routes}] (when f (f eng)))) (selected modules)))

(defn hooks
  "Every assembled module's lifecycle hooks, in table order — what
  engine/start! walks and engine/stop! unwinds
  (waymark10.server.runtime). The old spelling was one literal map in
  start! and one hand-ordered teardown in stop!; the docstring that
  enumerated them in prose is now this column.

  It reads `selected` like everything else, so a named selection
  starts exactly the surfaces it assembled — an engine without the
  realtime module runs no curtain and no presence registry, and its
  routes were already gone. Core's three (the dispatcher, the
  law-refresh consumer, the clock sweeper) are never droppable, for
  the reason `selected` never drops core.

  TABLE ORDER IS NOT START ORDER; `:after` is. The two agree today
  and the sort is stable, so reading down the inventory reads the
  start order — but the sort is what makes it true, not the reading."
  [modules]
  (into [] (mapcat :hooks) (selected modules)))

(defn packs
  "Every assembled module's conformance pack, in table order — what
  the conformance driver (waymark10.test.suite) runs, core's first.

  It reads `selected` like everything else, so an engine that names a
  subset gets exactly those packs plus core's, and an unknown label
  refuses here for the same reason it refuses everywhere. A module
  with no pack yet contributes none: the postgres store, oidc and the
  cli are modules by DEPENDENCY, and their obligations are held by
  the batch suites that address them directly.

  Whether an obligation inside a returned pack actually RUNS is not
  this table's judgment — a module's routes may be present while its
  kinds are absent, and vice versa. That is `:needs`, judged by the
  driver against what landed."
  [modules]
  (into [] (keep :pack) (selected modules)))

(defn warnings
  "Enrollment's own usability battery, run where the author looks
  (`waymark10.check`) and therefore with no storage and no engine.
  Two shapes, both about a resources vector that disagrees with this
  table:

    (a) the app declares a kind the table already enrols — a
        redundant hand-enroll. The registry refuses it a breath later
        with `one law per kind`; this says WHOSE kind it collided
        with, which is the part the author needs.

    (b) a :when-declared predicate fires but its module is not in the
        assembled selection — the app asked for a worksheet and the
        engine will never grow one. Silent today, since nobody names
        a selection yet; the gate is here so nobody has to notice
        later."
  [resources modules]
  (let [declared (into #{} (map :kind) resources)
        chosen (into #{} (map :module) (selected modules))]
    (into []
          (mapcat
           (fn [{:keys [module kind enroll] :as entry}]
             (concat
              (when (and (contains? declared kind)
                         (contains? chosen module)
                         (case enroll
                           :always true
                           :when-declared (seq (contributed entry resources))
                           false))
                [(str "kind " kind " is declared by the application, but the "
                      module " module already enrols it (" enroll ") — drop "
                      "it from your resources vector; one law per kind")])
              (when (and (= :when-declared enroll)
                         (not (contains? chosen module))
                         (seq (contributed entry resources)))
                [(str "a declaration asks for " kind ", but the " module
                      " module is not assembled — the ask is inert; add "
                      module " to :modules or drop the declaration")]))))
          enrollment)))
