# Spec — core and modules: the line waymark10 publishes along

**Thesis.** waymark10 is one artifact with one dependency set, and every
extension is sewn in by name at the seam it happens to touch:
`full-registry` hard-codes eight kinds, `router/handler` hard-requires nine
extension namespaces, `start!` hand-manages nine running surfaces. For us
that is fine — we are the only consumer. For anyone else it is a 60-MB
take-it-all. This spec draws the line between **core** and **modules**,
names the **four** things a module may contribute, and fixes the two rules
that keep the split from becoming a plugin system: **the law stays forms**,
and **the consumer assembles explicitly**.

## Epistemic status

The line is real work but it is not an invention: three of the four seams
already exist as one-off special cases, and this spec generalizes each.
`worksheet/kinds` is a when-declared enrollment written by hand;
`workqueue10.main/resources` is an app-opt-in enrollment written by hand;
`ui-assembly/fragments` is already the no-discovery rule stated out loud
("the numeric filename prefixes mirror the order, but THIS vector is
authoritative; the classpath directory is never listed").

What is *not* settled is whether anyone outside this repo wants this. The
artifact split (`waymark-db9.6`) is therefore deferred on purpose. Seams are
refactors with tests behind them; artifact boundaries are a promise, and a
promise made to nobody is just a maintenance bill.

## What exists

- **`server/engine.clj:79` `full-registry`** — the application's resources
  plus a literal list: definition, member, role, grant, approval-request,
  attachment, subscription, job, and `(worksheet/kinds resources)`. Three
  callers share it: the boot (`engine.clj:151`), the declaration-time check
  CLI (`check.clj:51`), and the migrate CLI (`workqueue10/main.clj:535`), so
  a plan covers exactly the kinds a boot would serve.
- **`server/worksheet.clj:747` `kinds`** — the when-declared precedent:
  "empty when no kind declares `:worksheet` (no round-trip, no extra kind)."
  Note the shape — it is a **function of the app's resources**, and it
  *synthesizes* its rdef from the declaring kinds' names.
- **`workqueue10/main.clj:304`** — the app-opt-in precedent: `capability`,
  `saved-view` and `dashboard/resources` ride in the app's own `resources`
  vector, indistinguishable from a domain kind.
- **`server/router.clj:1834`** — one literal route vector, `{:conflicts nil}`,
  linear, static routes shadowing the plural grammar by position. It requires
  seasons, presence, intents, collab, drafts, mirror, worksheet, openapi,
  ui-assembly, attachments, oidc-rp directly.
- **`server/engine.clj:208` `start!` / `:270` `stop!`** — the runtime map is
  built by hand and torn down by hand, in an order the comments defend
  ("after its readers, before the dispatcher it subscribes to").
  `server/coherence.clj:352` adds a second layer: the webhook deliverer and
  the clock sweeper are advisory-lock-elected singletons wired there.
- **`waymark10/test/conformance.clj`** — pure fns over one parsed wire
  document returning seqs of violation strings. A **library**, with no
  driver: each app suite hand-writes the same eight `deftest`s
  (`workqueue10/test/*/conformance_test.clj`, four copies).

So each of the four contribution types already has exactly one instance,
written longhand. The work is to write the noun.

## The core line

**Core is the law plus the one write path plus the things the law's own
vocabulary names.** Nothing here is optional; an engine without any of it
does not answer a waymark request.

**The law layer.** `expr` (the grammar), `machine`, `guards` (construction,
composites, and the built-ins `role`/`owner`/`feature-flag`), `types`
(verdicts, principal, the error taxonomy), `schema`, `resource` / `declare` /
`declaration` / `dsl` (the authored surface), `wire`, `fingerprint`,
`registry` with `checks` and `checks-assembly`. The supporting law
vocabulary rides along: `demand`, `groups`, `derived`, `summary`,
`server/predecessor`.

**The engine.** `server/invoke` (the one write path), `server/render` (the
envelope and the availability probe), `server/collections` (the query
grammar), `server/problems`, `server/surface` (declared composition — a
projection of edges the registry already knows, no kind of its own),
`server/maintainer` (derivations are law), `server/drafts` (a declared
`:draft` policy on an `:edit` action is law, and collab is a module *on top
of* drafts, not the other way round), `server/engine` and the core half of
`server/router`.

**Storage.** `server/store` — the protocol — plus `server/store/memory`, the
in-memory twin. The twin is core on purpose: it is what makes "depend on core
alone and boot an engine" true, and `batch_f_memory_test.clj` already proves
it holds the protocol. `server/store/migrate`'s planner is core; its
Postgres reads are the module's.

**Events.** `server/events` (the dispatcher and the SSE feed) and
`server/consumers` (durable log consumers). This is why **http-kit stays a
core dependency**: `events.clj` streams SSE through it. Modules do not get
to be the reason core carries a transport.

**The definition kind.** `server/definitions` and `server/judgment` — the law
lifecycle, `boot-revise!`, the `:propose` overlay, revision-N evaluation.

**Member, role, grant.** `server/members`, `server/roles`, `server/grants`.
These stay core, and the reason is not convenience:

> **Authorization is projected from law, and the law's vocabulary already
> names them.** `guards/role` (guards.clj:351) is a *core built-in* that
> mints `:requires-token "role:manager"`; `guards/owner` mints
> `"owner:<field>"`. The router's identity boundary reads
> `grants/bootstrap-visibility` and the members suspension gate *before any
> handler runs*. If roles were a module, a core guard would reference a
> module's kind, and an engine assembled without it would advertise a token
> nothing can mint — a drift of exactly the kind this framework exists to
> make impossible.

Grants are the same argument one level up: concealment (non-granted kinds and
actions 404) happens in core routing, and the scope grammar is judged against
the registry's own kind vocabulary. There is no honest version of "core, but
you may remove access."

**The conformance library.** `test/conformance`, `test/factories`,
`test/walker`, `test/db`. The suite that proves the framework's promises ships
with the framework, or the promises are marketing.

## The module inventory

| module | namespaces | contributes | heavy deps |
|---|---|---|---|
| postgres store | `server/store/postgres`, migrate's SQL half, `server/coherence` | — | next.jdbc, HikariCP, postgresql |
| oidc | `server/oidc`, `server/oidc-rp` | routes | buddy-sign |
| webhooks | `server/webhooks` | kind `:subscription`, elected runtime | — |
| jobs | `server/jobs` | kind `:job`, worker + orphan sweeper | — |
| attachments | `server/attachments` | kind `:attachment`, bytes routes, purge sweeper | — |
| worksheet | `server/worksheet`, `server/xlsx` | when-declared kind, 2 routes, an after-write! pass | — |
| mirror | `server/mirror` | discovery runtime, 1 route | — |
| realtime | `server/presence`, `server/curtain`, `server/intents`, `server/collab` | 6 routes, 4 runtime surfaces | — |
| seasons | `server/seasons` | 1 route | — |
| capabilities | `server/capabilities` | app-opt-in kind | — |
| dashboard | `dashboard`, `saved_view` | app-opt-in kinds | — |
| openapi | `server/openapi` | 1 route | — |
| generic ui | `server/ui-assembly` + `resources/waymark10/ui/*` | 2 routes | — |
| cli | `cli`, `client`, `session` | — (a separate main) | — |

Four entries deviate from the epic's list, deliberately:

- **openapi** is a module the epic did not name. `router.clj` hard-requires
  it; it is a derived overlay over the registry with no kind and no state, so
  it is the cheapest possible module and a good first proof of the seam.
- **drafts** is *not* in the realtime bundle, though `router.clj` requires
  them together. A `:draft` policy is a declared property of an `:edit`
  action — law. Live collab is a websocket over a draft row. The dependency
  runs one way only.
- **coherence** is filed under the postgres store, and it straddles. Its
  law-refresh consumer is a core need in any multi-process deploy; its
  role-election primitive is `pg_advisory_lock`. Recorded below as the
  spec's ugliest seam.
- **surface** stays core; **dashboard/saved_view** — the runtime-authored
  siblings, which are kinds — are the module.

## The four contributions

A module contributes **at most four things**. Not "at least" — the list is
closed, and a proposed module that needs a fifth is a proposal to change core.

### 1. Kinds — the enrollment table

`full-registry`'s literal list becomes a value:

```clojure
{:module :webhooks
 :kind   :subscription
 :enroll :always                       ; | :when-declared | :app-opt-in
 :kinds  (fn [resources] [webhooks/subscription])}
```

Three enrollment modes, each with a precedent already in the tree:

- **`:always`** — every engine assembled with this module gets the kind.
  (definition, member, role, grant, approval-request today.)
- **`:when-declared`** — a predicate over the *app's own* resources decides.
  The worksheet's rule verbatim: no app declares it, no kind appears.
- **`:app-opt-in`** — the app names the rdef in its own `resources` vector.
  `capability` today. The table's job here is only to *know the kind exists*,
  so `check` can warn on a redundant hand-enroll and the conformance driver
  knows the surface is on.

`:kinds` is a function of the resources, not a constant, because the
when-declared case *synthesizes* its rdef from the declaring kinds
(`worksheet/kinds` builds the target enum). That means the table's `:kind`
key is a **label**, not the payload — an honest wart, and the price of
keeping worksheet expressible.

### 2. Routes — the mounting seam

A module answers `(routes eng) → [reitit-route …]`, and `router/handler`
concatenates core routes with the enrolled modules'. Core keeps `.well-known`,
schemas, the collection/create/get/invoke/bulk/batch grammar, and the
envelope.

**The hazard is ordering, and it cannot be a plain concat.** The router is
linear with `{:conflicts nil}`, so `/api/:plural/-/worksheet` must precede
`/api/:plural/-/:action` or the worksheet route is shadowed by the bulk-action
grammar forever. The seam therefore has two buckets — `:static` (mounted
before the plural grammar) and `:plural` (mounted between the plural grammar's
prefix and its catch-alls) — and a module declares which. This is less pretty
than one list and it is the whole reason the seam is worth writing down.

*Built (`db9.3`), with two readings worth recording.* **The root is the UI
module's**, not core's: `/` has never been anything but the generic UI's
canonical address, and an engine assembled without that module has no page to
serve there — the 404 is the honest answer, and the same one the module
already gives when the asset is off the classpath. **A third ordinal proved
unnecessary**: `…/{action}/draft/collab` is one segment longer than the draft
it hangs off, so no core route can match it and `:plural` alone orders it.
Two buckets, and no topological sort.

### 3. Runtime surfaces — the lifecycle hooks

`start!`'s hand-built map becomes a seq the engine iterates:

```clojure
{:module  :webhooks
 :key     :webhooks-deliverer
 :after   [:dispatcher]
 :elected :webhooks-deliverer          ; advisory-lock singleton, or nil
 :start   (fn [eng ctx] …)
 :stop    (fn [handle] …)}
```

Two things the naive version gets wrong:

- **`:after` is load-bearing.** Start order matters (the curtain wants the
  dispatcher; presence and intents want the curtain) and stop order is its
  reverse with a defended exception already commented in `stop!`. The seq is
  ordered by declared dependency, not by module registration order.
- **`:elected` belongs to the hook, not to a subsystem.** Today
  `coherence/start!` reaches out and starts webhooks and the clock sweeper
  itself, under advisory-lock election. Election is a *property* of a running
  surface — "one holder per database" — and modeling it as a hook flag
  dissolves coherence's reach into two other modules.

An engine that never starts still pays nothing: the hooks are data until
`start!` walks them, which is the existing test-handler posture unchanged.

*Built (`db9.4`), with four readings worth recording.* **The coherence
straddle resolved in favour of the store protocol** — `elect-role!` /
`release-role!` are Storage methods now, `pg_advisory_lock` on Postgres and
an immediate self-election on the memory twin. That is resolution (a) from
the punt below, and the reason it beat (b) is that (b) leaves the twin
*advertising a surface it never runs*: a hook marked `:elected` would
silently not exist there. Asking the storage what it can do is the migrate
gate's own precedent, one level up. The advisory-lock keyspace moved intact
to `store/postgres/role-lock-key`, which is why `:elected` carries the
**role name** rather than a bare `true` — the name is the lock, and a hook
renamed is a lock renamed.

**Both orders derive from `:after`; neither is written down.** Start is a
stable topological sort (inventory order breaks ties, so reading the table
reads the start order), stop is that reversed. `stop!`'s hand-placed comment
— the curtain "after its readers, before the dispatcher it subscribes to" —
is now a *consequence*, pinned by a test rather than by a comment. The
rewrite changed exactly one position, recorded: mirror discovery used to be
stopped last, after the dispatcher, and nothing defended it (discovery
neither reads nor writes the dispatcher), so it now stops first like any
hook with no declared dependency.

**`:elected` dissolved the reach it was meant to dissolve.**
`server/coherence` no longer requires `server/webhooks` or
`server/maintainer`; it keeps the guarded law refresh — which is a **core**
hook, ordered `:after [:dispatcher]` — and the election primitive's public
spelling. `jobs` and `attachments` lost their `coherence` require too: their
sweepers are plain loops again, and the election that used to be baked into
each `start-…-sweeper!` is one word on a hook. The **jobs worker is
deliberately not elected** — it claims leases, so more processes is more
throughput.

**The runtime became a noun** (`server/runtime`): `surface`, `surfaces`,
`start-hooks!`, `stop-hooks!`. `surface` is the seam `db9.7` needs — a core
handler reaching a running module surface asks the runtime by key instead of
requiring the namespace, which is the same spelling the four remaining
reach-ins already use by hand. `db9.8`'s `[:surface hook]` conformance need
is judged against `runtime/surfaces`, and the two engine opts that were
select-key'd out of existence (`:orphan-sweep-ms`, `:purge-sweep-ms` — read
with defaults at the start site but never carried onto the engine) now
actually arrive.

### 4. A conformance pack

A module ships the obligations for its own surface:

```clojure
{:module :attachments
 :obligations
 [{:name :attachments/byte-round-trip
   :needs #{[:kind :attachment] [:route :attachments]}
   :run   (fn [ctx] (conf/attachment-roundtrip-violations …))}]}
```

The driver runs core obligations plus the packs of every **enrolled** module,
so disabling a module removes its obligations rather than failing them. This
extends the framework's central property — an advertised surface the suite
does not invoke should be impossible — from resources to extensions.

Note the size of this one honestly: `test/conformance.clj` today is a
library with **no driver at all**, and the four app suites each re-write the
same eight `deftest`s. Writing the driver is most of the work; the packs are
the easy half, and collapsing the four copies is the payoff.

*Built (`db9.5`), with four readings worth recording.* **The pack is a plain
value, not a function of the engine** — unlike `:routes`, which must close
over the engine at assembly. An obligation takes the driver's ctx when it
*runs*, so nothing about the column depends on who is asking, and the table
stays readable. **`:needs` is a set of pairs, not a flat pair**, and it is
the whole answer to "the label is not enough": seasons/realtime/mirror/
openapi/ui contribute routes and no kind, capabilities/dashboard contribute
an app-opt-in kind and no route, and the byte round-trip needs both at once.
`[:kind k]` also happens to be the honest test of an `:app-opt-in`
enrollment — did the application's own vector actually name it. **An unmet
need is a SKIP, reported with the need that was missing; an unrecognized need
throws** — a typo'd need that quietly disabled its obligation forever is
exactly the silent no-coverage this seam exists to abolish. **The four
duplicated suites are one `deftest` each** (`suite/check!`), plus what was
genuinely theirs: mealplan10 keeps the bulk report over its own
`accept_many`, and keeps — as its own claim, not the framework's — the
assertion that it has folded enums for the core obligation to have proved
something about.

`test/envelope_obligations.clj` was folded into `test/conformance.clj` in
the same bead, as that file's own docstring had asked. One library, one home.

*Built (`db9.8`), the runtime half — and the decision it turned on.*

**Nobody starts an engine on the driver's behalf.** The bead's open
question was how a started engine reaches the packs, and the two candidates
were an opt-in leg on `suite/check!` (the driver starts and stops the engine
around the surface obligations) or a framework-side suite that starts one
itself and hands it over unchanged. It is the second
(`waymark10/test/waymark10/runtime_conformance_test.clj`), for three
reasons. Starting is a decision about a **process** — ten threads, four
advisory-lock elections, a dedicated LISTEN connection — and a flag on an
assertion helper is not where a process decision belongs. The driver already
judges what it is handed: `:running-surfaces` is read when the ctx is built,
so an application that wants its own surfaces proved wraps its own `check!`
in `start-runtime!`/`stop-runtime!` and the driver needs no leg at all. And
every runtime obligation waits on a bound derived from the engine's **own**
cadence opt, so an engine started with production intervals would make a
suite wait 45s for one presence TTL — choosing test-sized cadences and
choosing to start are the same act, and they belong in the same fixture. The
constraint held: an engine that never starts still pays nothing, and the
four application suites are byte-for-byte what `db9.5` left them.

The obligations, each `[:surface …]`-gated plus whatever it truly needs:
`attachments/purge-sweep` (bytes that landed through the module's own door
leave the **disk** after the row is deleted — the metadata row survives the
purge and a deleted attachment's bytes 404 either way, so a wire-only
version would pass against a sweeper that never ran, and
`attachments/stored-bytes?` became public for that one sentence),
`webhooks/delivery-receipt` (a subscription hears a new event at a real
endpoint the driver stands up, and a **revoked** one never hears another),
`jobs/worker-progress` (a queued job reaches `:completed` with every item
accounted for, and the `:start` edge was walked by the worker's actor —
which is the lease made visible, since `:start` is worker-only and 404s on
the wire), `realtime/presence-ttl` (a heartbeat reaches the board and three
missed intervals take it off), `realtime/curtain-verdict-bound` (a draw
committed over the wire, by the member's own hand, is honored inside the
declared TTL — and so is the reopening). No obligation sleeps: each waits on
the outcome against a deadline the surface's own interval implies.

Two obligations are deliberately **not** surface-gated, `db9.7`'s
recommendation taken verbatim: `jobs/deferral-door` (the 202 path with no
worker anywhere — deferral is a property of what was enrolled, and every
deferred-bulk call in this tree runs through an engine nobody started) and
`jobs/deferral-seam`, one deref asserting the assembled `:job` rdef still
carries `seams/deferral`. That second one is a **scar**: the door lives in
metadata, which is exactly what a rebuild drops silently, and a law-refresh
path that reassembled rdefs without re-running the module's `:kinds` fn
would leave a registry that looks complete and 503s every deferral.

Two runtime promises are **not** obligations, recorded rather than pending.
The elected **jobs orphan sweeper**: its promise is only observable on a job
no live worker will steal first, and `run-once!` claims every queued and
running job it can see — in production the two live in different processes
(the sweeper is elected, the worker deliberately is not), in one started
engine they race, and an obligation that owned that race would be tuning
dressed as a proof (`waymark-db9.9`; `batch_f_jobs_test` drives the sweep
directly, under real election, in a process with no worker). And **collab
OT**: collab has no lifecycle hook at all — its rooms are an atom on the
engine and its door is a websocket over a draft row, which is core — so
there is no `[:surface k]` to gate it on, and a driver that spoke websockets
would be a second client library.

The driver grew four ctx accessors and no new verb: `:surface` (the handle
behind a running hook key), `:fresh-row` (an *uncached* walk, because the
event of a row's creation is what the deliverer is supposed to notice),
`:transitions` (who moved a row), and `:receiver!` (a throwaway http-kit
endpoint — a webhook not delivered to somebody else's socket is not a
webhook). The receiver lives in the driver rather than the packs because
`packs.clj` is loaded by every boot, and a boot has no business loading a
test receiver.

## The reach-ins

The four contributions are what a module HANDS the engine. This section
is the other direction: the places a **core handler reaches into a
module**, which the mounting seam exposed rather than created.

After `db9.3`, `router.clj` still required four extension namespaces and
none of them for a route — presence's read mark and its two stream
hooks, the intents announcement at the dry-run doors, the mirror's
pull-through on a stale GET, and the job the bulk door mints when it
defers. That require, not the route vector, was what kept
`waymark10.server.presence` on the classpath of an engine assembled
without the realtime module.

*Built (`db9.7`), and the shape is one sentence: **core names a
protocol; a value the assembly already put in core's hand implements
it**.* The protocols live in `server/seams`, a namespace that requires
nothing. Finding the implementation is type dispatch, never a lookup in
a table somebody filled at load — which is the forms-only rule's
argument applied one level out from the guard path.

Four readings worth recording.

**Two of them are running surfaces and two are not, and the difference
is load-bearing.** `Gaze` and `Considering` are implemented by the
presence and intents registries, reached with `runtime/surface` — the
handle the module's own lifecycle hook published, nil on an engine
without the module and nil on one that never started. But
`ReadThrough` and `Deferring` are called on engines that never start:
every mirror fixture in the tree GETs a mirrored row and every
deferred-bulk test 202s through a bare `engine/handler`. A runtime
lookup would have turned both off in exactly the suites that prove
them. They are properties of a **declaration**, not of a process — a
kind that declares `:mirror` declares a pull-through, and the `:job`
kind is what a deferred bulk call becomes — so each rides the
declaration the module minted: `mirror/declaration`'s own `:mirror`
value, and the `:job` rdef's metadata.

**Metadata, for the job kind, because `declaration/top-level-keys` is
closed on purpose.** Growing that list so a module could park a
function on an rdef would be the plugin registry arriving through the
authored surface. Metadata is where this framework already keeps what
is true about a declaration but is not law (`defhandler`'s
`:waymark10/form`, `r/resource`'s `:waymark10/warnings`), it survives
`registry/assemble`'s merge, and it is invisible to the fingerprint —
which is the point: the mint is not law.

**No fifth column, and the alternatives are recorded rather than
implied.** A `:read-through` / `:defer` column on the module entry was
the obvious move and is what `db9.3` guessed at; it was refused because
the contribution list is closed and because a column of opaque fns is
the keyword→fn registry with better manners. Inlining `jobs/enqueue!`
into the router was refused for the opposite reason: it would put the
job document's six fields and the worker actor's *identity string* in
core, and a core namespace carrying a module's vocabulary is the drift
this framework exists to make impossible. What core still names is the
`:job` KIND — it must, to render the 202's envelope — and nothing else.

**Absence degrades at all four doors, and the fourth is new.** No
registry means no gaze marked and no considering card, which was
already the posture (both were best-effort by construction) and is now
the contract. A kind with no `:mirror` serves what is stored. And a
bulk call over its `:defer-over` threshold on an engine with no jobs
module now answers **503 naming the module** instead of dying inside a
`create!` for a kind nobody registered — the one place this seam
improved a behavior rather than preserving it.

## The forms-only rule

**Extensions may add kinds, routes, runtime surfaces and guard
*constructors*. Nothing may put an opaque function into the guard path.**

The mechanism this protects is exact. `fingerprint/guard-fp` stores an
expression guard's `:when` as a **wire tree** — "the stored tree IS the law;
check is nil" — and `callable-hash` hashes the imperative residue by its
`:waymark10/form` metadata, with a bare `fn` hashing by printed identity only
"as a stopgap the checks will later refuse in strict mode."

So a registry of module-supplied predicates keyed by keyword — the shape every
plugin system reaches for — costs three things at once:

1. **The diff gate.** `classify-diff` degrades every change touching that
   guard to `:code-or-shape`; `:propose` mode's data-law overlay stops
   applying.
2. **Judgment.** `server/judgment` serves a stored revision by reading its
   fingerprint back into an evaluable form. An opaque fn does not read back;
   the grandfathered law becomes unservable.
3. **The law sweep** (`docs/spec-law-sweep.md`) loses its substrate — it
   works only because both sides of the comparison are addressable values.

The permitted spelling is the one core already uses: `guards/role` is a
*function that returns a guard map*, evaluated at declaration time, and what
lands in the rdef is data. A module may export as many of those as it likes.
The law's *vocabulary* — `expr`'s operators — grows only by a change to core,
reviewed as a change to core.

## Explicit assembly — no discovery

The consumer names their modules in their own `main.clj`. No classpath scan,
no self-registering namespace, no `:extend` metadata harvested at load. The
precedent is stated already, in `ui-assembly`: *this vector is authoritative;
the classpath directory is never listed.*

The reason is not taste. `.well-known/waymark`, the openapi overlay, every
fingerprint and every definition row is a projection of *everything this
engine serves*. If that set is discovered rather than declared, it differs
between a dev REPL and a container with one extra jar on the path — and then
`boot-revise!` writes a **different law** in the two places, silently. The
engine's full surface has to be a value a reviewer can read on one screen and
diff against yesterday's. Discovery trades that away for an import statement.

Concretely: `engine/engine` gains `:modules [m/webhooks m/jobs …]`, each a
plain map carrying the four contributions above. The migrate CLI and
`waymark10.check` take the same vector, which is what keeps the three
`full-registry` callers in agreement.

## Sequencing

Build the seams now, inside the single artifact: `db9.2` (enrollment table),
`db9.3` (routes), `db9.4` (lifecycle), then `db9.5` (packs, which keys off
`.2`'s table). All three of the first seams touch `engine.clj`; land them one
at a time. `db9.7` follows the four, and is the one that finishes what `.3`
started: with the reach-ins dissolved, a module's namespace leaves the
classpath with its module. `db9.8` closes the epic by paying `.5`'s
deferred half — the obligations that need a surface to be *turning* —
and it needed `.4`'s hook seq before it could name one.

`db9.6` — the artifact split, `waymark10-core` plus module artifacts in the
monorepo/`deps.edn` shape ring and reitit use — **waits for a real external
user**, and specifically for one who wants core *without Postgres*. That is
the boundary that pays first (next.jdbc, HikariCP and the driver are the bulk
of the dependency tree, and the memory twin already proves core can run
without them). Artifact boundaries are cheap to add once the seams exist and
expensive to redraw once published; there is no version of this we lose by
waiting.

## Recorded punts

- ~~**Coherence straddles the line and this spec does not fix it.**~~
  *Settled at `db9.4`*, resolution (a): `elect-role!`/`release-role!` are
  Storage protocol methods, and the law-refresh consumer is a core lifecycle
  hook. Resolution (b) — coherence no-oping on a non-migratable storage —
  was refused because it leaves the memory twin advertising a surface it
  never runs.
- ~~**Routes were the easy half of `router.clj`'s module knowledge.**~~
  *Settled at `db9.7`*: `waymark10.server.router` now requires **no module
  namespace at all**, the two OIDC resolvers excepted — and they are not an
  exception, since `wrap-identity` **is** the identity boundary. Dropping the
  realtime, mirror or jobs module now drops its namespace from the classpath
  too, which is the boundary `db9.6` would have to publish. See
  § 'The reach-ins' below for how each of the four resolved.
- **Grant projection is not inherited.** `router.clj` records that the SSE,
  openapi, surface and collab routes 404 a grant-scoped request rather than
  projecting. A module's routes inherit that posture by default. The seam
  does not make projection easier; it just stops making it worse.
- **The enrollment table cannot express a migration.** Turning a module on
  for an app that has been running without it means new tables, and the
  migrate gate refuses the boot with a plan — correctly, and with no help
  from this table.
- **No per-module wire version.** The wire format is `"10"` whatever modules
  are enrolled. A module that wanted to add an envelope key would be a core
  change wearing a module's clothes; refuse it.
- **The CLI and the generic UI are modules by dependency, not by seam.**
  Neither contributes kinds or lifecycle; the UI contributes two routes and
  a resource directory, the CLI is a separate main. They split cleanly at
  `db9.6` and need nothing from `.2`–`.5`.
- **The pack column drags the conformance library into every boot.**
  `waymark10.modules` requires `waymark10.test.packs` because the column
  is a literal, not a lookup — and that library reaches
  `server/store/migrate`, which reaches Postgres. Harmless in the single
  artifact; a boundary to draw at `db9.6`, where "core without Postgres"
  is the whole point. Recorded, not fixed. `db9.8` made the shape
  plainer without making it worse: the runtime obligations ask their own
  modules for their own vocabulary (the worker's actor id, the curtain's
  verdict, the attachment's disk), so `packs.clj` now requires four
  module namespaces — every one of which `modules.clj` already required
  for its other columns, so nothing new reaches the classpath. At
  `db9.6` the packs split with their modules, one pack namespace each,
  and this line goes away.
- **App-opt-in enrollment is barely a contribution.** For `capability` and
  `saved_view` the module's whole job is exporting a var the app puts in a
  vector. Listing them in the table buys warnings and conformance selection,
  and nothing else. That is enough, but it is not much.

## Effort

**Medium** for `db9.2`, `.3` and `.4` — each is a data shape plus one
mechanical rewrite of a literal, with the existing suites as the proof.
`.3` carries the ordering hazard and `.4` the coherence question, so neither
is a pure refactor. **Medium–large** for `db9.5`: the pack shape is small,
the driver does not exist yet, and four app suites converge on it.
