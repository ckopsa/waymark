# Core tiers — the domainless kinds, sorted

**Status.** A scoping document, not code. It sorts every kind this repo
serves without a `:domain` into one of three tiers, maps each module's
edges, and ends with a proposal and the tickets it would take. Nothing
here changes what an engine serves today.

The kinds with a domain (`queue`, `chores`, `day`, `meals`, `calendar`,
`media`, `factory`, `vanilla_plus`) are already product-shaped and are
out of scope. The rest — 62 kinds, counted below — are "engine level"
today, and they are not one tier.

## What exists today

`docs/spec-modularization.md` draws the core line and
`waymark10/src/waymark10/modules.clj` turns it into a value:

- `modules/inventory` is one vector of entries, `{:module label :enrols
  [...] :routes ... :hooks [...] :pack ...}`. The contribution columns are
  closed at four — kinds, routes, running surfaces (hooks), conformance
  pack.
- An enrollment says who hands the kind over: `:always` (the table
  enrols it), `:when-declared` (the table enrols it when the app's
  resources ask for it — `worksheet`, `line_items`), `:app-opt-in` (the
  app names the rdef in its own resources vector; the table only knows
  the label, for warnings and conformance selection).
- `modules/selected` is the one join point. `nil` means the whole table;
  a named set keeps `:core` regardless and refuses an unknown label.
  `full-registry`, `route-sets`, `hooks` and `packs` all filter through
  it.
- `engine/start!` takes `:modules`, but **no caller passes it**: every
  engine assembles the whole table. No `org.edn` names modules.
- The `:core` entry is not "core primitives": it enrols 33 kinds, most
  of them there because a core kind carries a typed ref to them
  (`checks/refs` refuses a dangling ref), not because they are
  primitive. That is the back-edge problem this document maps.

So a module notion exists, and its selection seam works; what is
missing is a core that is actually small, dependencies between modules,
and any way for an org to choose.

## The boundary, enforced

The spine reads no factory kind (`ticket`, `change`, `ci_run`,
`repo_policy`) and no dwelling kind (`self`, `journal`, `letter`) by
name. What it needs of them it asks through a named hook
(`waymark10.modules.hooks`): the module's namespaces register it when
they load (`hooks/modules` names them), and an engine that did not
assemble the module — `:factory` and `:dwelling` in
`modules/inventory` — is answered the hook's documented default. The
default assembly still loads both.

The boundary is enforced by `scripts/core-tier-check.sh`, which the
`changes` job in `.github/workflows/tests.yml` runs: a `.clj` under
`waymark10/src` that names a module kind in a store call fails CI.
There is no grandfather list.

## The three tiers

Where a kind is enrolled today is in brackets: `[core]` the `:core`
entry, `[mod :x]` the `:x` entry, `[app]` the app's own resources vector
in `workqueue10.main/resources` with no inventory entry.

### 1. Core primitives — who may do what, and which agent is doing it

- `definition` [core] — the law itself; nothing is judged without it.
- `member` [core] — the login principal every verdict names.
- `role` [core] — `guards/role` mints `role:<name>` tokens; dropping it advertises a token nothing mints.
- `team` [mod :teams] — confers roles to a group, and the grant scope grammar names it (`server/scope.clj`); a role source the scope reads is core.
- `grant` [core] — concealment and every door's admission are judged against it.
- `approval_request` [core] — the one way a principal widens its own grant ("asking is the default").
- `capability` [app] — the registry of grantable external powers a grant's dotted scope entries name; today opt-in, which lets a grant name a token no row defines.
- `mcp_server` [core] — holds the policy behind a dotted scope entry and the client that forwards it.
- `secret` [core] — a power call names one by reference; the server row puts its value in.
- `held_call` [core] — `approval person` in a powers entry mints one; the policy that holds a call is a field of `powers`.
- `seat` [core] — the office an agent sits in; the grant carries a typed ref to it.
- `model` [core] — the agent a seat is sat by, and whose key opens it.
- `sitting` [core] — which agent is acting right now; every transition counts against one.
- `lease` [core] — who holds a row; a sitting's claim acquires it and its end releases it.
- `schedule` [core] — when a seat fires; the seat carries a typed ref to it.
- `domain` [core] — the seat carries a typed ref to it, and navigation and discover group by it.

Sixteen kinds. Everything else is something an org could, in principle,
not have.

### 2. Engine modules — features built as kinds, an org could turn off

- `transcript`, `transcript_entry`, `sitting_call` [core] — module `transcripts`: what a sitting said and called; a record, not an authority.
- `judgment`, `verdict` [core] — module `judges`: a judge declared as a row and the row that answers it; both or neither.
- `notifier`, `notice_rule` [core] — module `notices`: how the engine tells a person.
- `invitation`, `walkthrough`, `quest` [core] — module `guided`: hand one step, or an ordered set, or a goal's found steps, to a person.
- `scheduled_action` [core] — module `scheduled`: a call stored for a time.
- `work_calendar` [core] — module `work-calendar`: working days and hours the law may read.
- `runner_link`, `runner_provider` [core] — module `runners`: provider endpoints a schedule may fire through ("nothing fires through it yet").
- `attachment` [mod :attachments] — module `attachments`: bytes beside rows, with purge and retention sweeps.
- `subscription` [mod :webhooks] — module `webhooks`: outbound delivery of the event log.
- `job` [mod :jobs] — module `jobs`: background work with a worker and an orphan sweep.
- `posting`, `line_items` [mod :ledger] — module `ledger`: money and spend as read-only rows the engine writes.
- `worksheet` [mod :worksheet] — module `worksheet`: synthesized when a kind declares `:worksheet`.
- `saved_view`, `dashboard`, `dashboard_slot` [mod :dashboard, app] — module `dashboard`: user-composed views.
- `goal`, `measure_reading` [mod :goals, app] — module `goals`: a measure's target and its readings as data.
- `promise` [mod :promises, app] — module `promises`: what must hold, as watched rows.
- `amendment`, `amendment_policy` [mod :amendments, app] — module `amendments`: how a house changes its own law.
- `integration`, `integration_event`, `effect_call` [mod :integrations] — module `integrations`: inbound events and outbound effects to other systems.
- `connection` [app] — module `connections`: the breaker panel, infrastructure a person reads when something is dark.

(The ticket's guess put `lease` here; it is core, because the sitting —
core — acquires and releases it on every claim. It put `posting` among
the undeclared domains; it is the `ledger` module, because the engine's
own seat and domain post spend to it, not a household's books.)

### 3. Undeclared domains — a product living in core

- `scene`, `take`, `film_rule` [core] — domain `film`: a demo written as shots, its performances and the rules a good film is scored by.
- `walk`, `walk_frame` [core] — domain `film`: the recorded walk a film is rendered from; scenes are performed as walks.
- `self`, `journal`, `letter` [app] — domain `dwelling`: an agent's profile, the shared history and the notes between inhabitants.
- `value`, `hypothesis`, `insight` [app] — domain `belief`: what the house cares about, what it believes about itself, and what it has noticed; they cite one another.
- `person`, `thread` [app] — domain `roster`: who the house's people are and the conversations it has with them.
- `inbox_item` [app] — domain `queue`: unanswered mail whose leaves are born as `task` rows; it is the queue's doorstep.

No live kind is retired. The kinds that were (`tickler`, `outcome`,
`outcome_piece`, `composition_request`, `activity`, `evening_plan`,
`evening_session`, `permission_slip`) are already gone from the
resources vector.

**Count.** 16 core + 32 module + 14 undeclared-domain = 62 kinds, each
in exactly one tier.

## Modules: their kinds, what they need, and the back-edges

A back-edge is a core kind (or core code path) that depends on the
module. It is what blocks turning the module off: `checks/refs` refuses
a typed ref to a kind the engine does not serve, so today each of these
is pinned into `:core`.

| Module | Kinds | Depends on | Back-edge from core |
|---|---|---|---|
| `transcripts` | transcript, transcript_entry, sitting_call | sitting, seat | **yes** — the seat carries the transcript policy and the sit answers the upload key |
| `judges` | judgment, verdict | seat (a judgment names its subject as a kind token) | **yes** — the seat carries a typed ref to the judgment it walks |
| `notices` | notifier, notice_rule | mcp_server, held_call, member | **yes** — the held call's consumer tells a person through the notifier |
| `guided` | invitation, walkthrough, quest | grant, member | soft — a grant may name the door that creates an invitation; a token, not a ref |
| `scheduled` | scheduled_action | held_call | **yes** — `waymark_invoke`'s `at` argument is served by core invoke |
| `work-calendar` | work_calendar | — | **yes** — the `:calendar` hook rides every invoke ctx |
| `runners` | runner_link, runner_provider | schedule, secret | **yes** — the seat's derivation reads `[:schedule :model :runner_link]` |
| `attachments` | attachment | — | none from core; `walk` (domain `film`) stores its export as one |
| `webhooks` | subscription | the event log (core) | none |
| `jobs` | job | — | none |
| `ledger` | posting, line_items | seat, domain, sitting | **yes** — the seat and the domain declare `:on-read` spend accounts every sitting's usage posts to |
| `worksheet` | worksheet | the declaring kinds | none |
| `dashboard` | saved_view, dashboard, dashboard_slot | — | none (the collections merge expects saved_view first, an ordering, not a ref) |
| `goals` | goal, measure_reading | member | **yes** — `server/org.clj` seeds a goal for the owner |
| `promises` | promise | — | none |
| `amendments` | amendment, amendment_policy | seat, promise, definition | none (the edge runs module → core and module → promises) |
| `integrations` | integration, integration_event, effect_call | the app's law (scope_wanted) | none |
| `connections` | connection | — | none |

Eight modules have a back-edge. Until each is broken — the ref made
optional and its reader degrading when the kind is absent — those
modules cannot leave the `:core` entry, and an engine of "core only" is
the 33-kind `:core` entry it is today.

## Proposal

**A module's declaration.** The inventory entry keeps its four
contribution columns and gains two descriptive keys, both data:

```clojure
{:module :guided
 :needs  #{}                      ; other modules it requires
 :doc    "Hand a step, a set of steps or a goal to a person."
 :enrols [{:kind :invitation  :enroll :always :kinds (fn [_] [invitations/invitation])}
          {:kind :walkthrough :enroll :always :kinds (fn [_] [walkthroughs/walkthrough])}
          {:kind :quest       :enroll :always :kinds (fn [_] [quests/quest])}]
 :pack   packs/guided}
```

`selected` closes the selection over `:needs` and refuses a selection
whose closure names an unknown label, exactly as it refuses one today.
The `:core` entry shrinks to the sixteen primitives; a check fails the
build when a core kind carries a required ref into a non-core module.

**Turning one on.** An org names its modules in its own `org.edn`:

```clojure
{:org     :example
 :modules #{:guided :scheduled :ledger :attachments :webhooks}}
```

The org's boot passes the set to `engine/start!` as `:modules`. Absent
means what it means today — the whole table — so no existing org
changes until it opts in. The undeclared domains become ordinary
domains, declared by the org beside `queue` and `meals`.

**What a fresh agent sees.** `waymark_discover` lists the sixteen core
kinds plus the enabled modules' kinds; a kind of a module not enabled is
absent, the same way an ungranted kind is. Discover gains a `modules`
block — each enabled module's label, `:doc` and kinds — so an agent can
say "this house has no webhooks" rather than inferring it from a
missing kind. For the example above that is 16 + 8 kinds instead of
62, before any domain.

## Follow-up tickets

1. Break the eight core back-edges: make the seat's refs to judgment,
   runner_link and the transcript policy optional, move spend accounts
   off the seat and domain into the ledger, and let the held call, the
   `at` argument, the `:calendar` hook and the org's goal seed degrade
   when their module is absent.
2. Split the `:core` inventory entry into the sixteen primitives plus
   the modules named above, add `:needs` and `:doc`, close `selected`
   over `:needs`, and add the check that refuses a core → module ref.
3. Read `:modules` from `org.edn` at boot and pass it to
   `engine/start!`; absent stays the whole table.
4. Declare the undeclared domains: stamp `film`, `dwelling`, `belief`,
   `roster` and `queue` on the fourteen kinds of tier 3.
5. Add the `modules` block to `waymark_discover` (and
   `GET /api/-/discover`).
