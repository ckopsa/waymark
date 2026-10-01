# Spec — demo clones

**Thesis.** A walk meant for the public must not be recorded on a
working engine, and a demo engine that lives for months rots and
becomes one more thing to guard. So the demo engine is a clone: the
clone door's machine, booted on a seed instead of a snapshot, reachable
from one person's browser for a few hours, and gone after. The walk
leaves as a file before the clone dies.

Parent: epic 7cd2a3f7 in ckopsa/waymark-doors (the clone door). This
document is the design pass ticket 661bdd7b asks for, and it files no
code.

## Epistemic status

A design, read against ckopsa/waymark as it stands on 2026-10-01. The
owner decided the same day that the demo engine is EPHEMERAL: it is
spun up when needed, and there is never a long-lived demo.kopsa.info.
That decision is taken as given here.

One half of this was read and one half was not. The engine's side
(identity, storage, the engine's name, the walk kinds, the UI) was read
in this repository and is cited below. The clone door's side lives in
ckopsa/waymark-doors, which this pass could not open. What is said
about the bug clone (one walled Nomad job, TTL 2 h by default and 12 h
at most, a reaper, `up` and `down`) is restated from the epic as the
ticket gives it. Section 4 therefore states the wall as rules a demo
clone must meet, and the waymark-doors children check them against the
bug clone's own code.

## What exists

- **The requirement.** `docs/spec-guided-follow.md` § 4: "Marketing
  walks are recorded on a demo engine." Redaction bounds a recording by
  what its recorder could see, and on work.kopsa.info that is still
  real tickets, repositories and people.
- **The walk kinds.** `walk` and `walk_frame`
  (`waymark10/src/waymark10/server/walks.clj`): `recording → sealed →
  purged`, with `export` on a sealed walk answering `waymark-walk/1`.
  ● Record in the UI is ticket bf90e4a8.
- **The engine's name.** `server/engine.clj` takes the `:name` option,
  else `WAYMARK_ENGINE_NAME`, else `"waymark"`. The well-known document
  answers it and a walk's export header carries it as `"engine"`. The
  comment there already says why: "so a demo engine's walk says it is
  one."
- **The dev identity.** `wrap-identity` (`server/router.clj`) resolves
  a principal from a bearer token, else the RP session cookie, else the
  dev headers: `x-waymark-principal`, with `x-waymark-roles` and
  `x-waymark-actor-type` beside it. With no `WAYMARK10_OIDC_*` in the
  environment the first two answer nothing and the dev headers are the
  identity. The UI already carries this: the shell has a "dev
  principal" box (`ui/050-shell.html`) and `ui/100-core.js` sends its
  value as `x-waymark-principal` on every request.
- **Two storages.** Postgres, and the in-memory twin
  (`server/store/memory.clj`), which keeps the full Storage protocol
  over one atom for tests and the REPL. The deployed boot
  (`workqueue10/src/workqueue10/main.clj`) knows only Postgres: it
  reads `WORKQUEUE10_DSN`, and `WAYMARK10_AUTO_MIGRATE=1` lets it build
  the schema on an empty database.
- **Fakes for every outside service.** The same boot falls back to an
  in-process fake for each source whose environment is unset (Home
  Assistant, Gate, Google Tasks, Flickr, the calendar). Its connection
  descriptors then say `fake` and not `real`.
- **Seeding through the doors.** `ensure-capabilities!` in the same
  file is the precedent: a boot step that calls `inv/create!` as a
  `:system` principal, once, and never overwrites.
- **The clone door** (waymark-doors, not read): `up(commit)` starts an
  engine at a commit as one walled Nomad job on a restored production
  dump, and a person's tap admits that restore. It is reachable only
  through the door. `down` and the reaper end it.

The gap: there is no seed, `up` has no way to ask for one, and a clone
has no route a browser can reach.

## 1. Seed, not snapshot

`up(commit, seed: "demo")` boots the engine on a seed. It restores no
dump, it reads no production data, and it needs **no person's tap**:
the tap on a bug clone guards the production snapshot, and a seeded
clone holds none. `seed` and a snapshot are exclusive. An `up` that
names both is refused, and so is a seed name the commit does not carry.

### Where the seed lives

`waymark10/resources/waymark10/demo/seed.edn`, in ckopsa/waymark, as
the ticket proposes. It is a classpath resource, so it rides the image
built for the commit and is **versioned with the code it seeds**. This
is the reason it does not live in waymark-doors: a clone is an engine
at a commit, and a seed kept elsewhere would name kinds, fields and
doors that the commit does not serve. The door passes only the seed's
name. The engine at that commit owns what the name means.

### What it holds

Enough for a walk to look like a working engine, and no more:

- **Two households**, each with three or four members. A household is
  a set of members and not a kind: the engine has no household row, so
  the seed's cast names each member's household and the seed file
  keeps each household's tickets together. The members have demo names
  that are plainly invented, a display name and an actor type. One
  member in each household is an agent that acts for a person of that
  household, because the walks show an agent leading a person.
- **Tickets in every state the ticket kind declares but `in_review`**,
  two or three in each, with titles a stranger can read. Each one
  reached its state by the doors, so its history page shows the moves.
  `in_review` is out because only a change's `submit` reaches it, and
  `submit` needs a bench. A demo clone has none.
- **One held call**, waiting for a person's tap. The loader mints it
  with `held-calls/hold!` as the engine. That is the door the power
  door uses, and it is the one step that is not an ordinary invoke: no
  hand at the wire may create a held call. The seed file names the
  step `:hold`.
- **One open invitation** (spec-guided-follow § 3), addressed to the
  member the person will sign in as.
- Nothing else. No connection, no credential, no seat, no schedule, no
  Routine link.

No row may be copied from a working engine, in whole or in part. The
seed is written by hand and reviewed as text.

### The file

The seed is a cast and an ordered list of steps:

```clojure
{:seed "demo" :version 1
 :cast  {:ada  {:id "ada"  :display "Ada Example" :type :human
                :household "Harbour House"}
         :plan {:id "plan" :display "Planner"     :type :agent
                :household "Harbour House" :acts-for :ada}}
 :steps [{:as :ada  :kind :ticket :create {:title "…"} :ref :t1}
         {:as :ada  :kind :ticket :on :t1 :action "groom"}
         {:as :plan :kind :ticket :on :t1 :action "…"
          :input {:due [:days 3]}}
         {:hold {:tool "…" :caller [:cast :plan] :why "…"} :ref :h1}]}
```

`:ref` names the row a step created, and `:on` and `[:ref :t1]` point
back to it, because ids are minted at load. `[:self :t1]` is the same
row as a path, which is how an invitation names it, and `[:cast :ada]`
is a cast member's id. `:hold` is the held call's step. A date is written relative
to the boot (`[:days 3]`, `[:days -2]`), so the seed never goes stale.

### How it is loaded

**Through the doors, not through storage.** A boot step, after the
engine is built and before the server listens, walks the steps and
makes each one an ordinary `inv/create!` or invoke, with the cast
member named by `:as` as the principal. The reasons:

- **The law checks the seed.** Schemas and guards judge every step. A
  seed that has drifted from the code refuses at boot and in CI, and
  it does not seed rows the engine could never have written.
- **Each row has a real history.** The transition log, derived fields
  and events are written by the same code that writes them in
  production. A walk that opens a ticket's history shows real moves,
  made by named demo members.
- **Storage writes would bypass exactly that.** They are faster, and
  they would need a second, hand-kept description of every kind's
  stored shape.

The cost is time (some tens of invokes at boot) and one limit: every
transition is stamped at boot time, so every history reads "a moment
ago". Backdating is a punt.

The loader takes its switch from the environment: `WAYMARK10_SEED=demo`.
It refuses, and the boot exits non-zero, unless both of these hold:

- the engine's name begins with `demo-`;
- no IdP is configured (`:oidc` is absent).

These make a seed on a working engine impossible by construction: a
production engine is not named `demo-…` and has an IdP. A step the law
refuses also ends the boot. `up` then reports the failure and tears
the clone down. A half-seeded engine is never served.

The boot step in `workqueue10.main` asks for one thing more:
`FACTORY10=1`. The seed's tickets are rows of a factory kind, so a
demo engine serves the factory kinds. A boot with `WAYMARK10_SEED` set
and `FACTORY10` unset refuses with a sentence.

If the engine already holds a member row from the seed's cast, the
seed was applied before (a restarted task on the same database). The
loader then skips the whole seed and says so.

### Postgres or the in-memory twin

**Postgres**, an empty database beside the engine in the same job,
with `WAYMARK10_AUTO_MIGRATE=1`.

| | Postgres | in-memory twin |
|---|---|---|
| boot path | the one production runs, unchanged | `main.clj` has none: a new branch to write and keep |
| fidelity | what the walk shows is what the product does | faithful by intent, but some tables are not kept (`mcp_sessions`), and filters are interpreted, not run |
| cost per clone | one more task, its memory, and a migrate at boot | nothing beyond the engine |
| a restarted task | keeps its rows and the walk being recorded | loses everything, the walk included |
| the clone door | already runs a database for a bug clone | a second shape of job |

A demo walk is a claim about the product, so it is recorded on the
storage the product runs on. The twin's saving is one database task
for a few hours, on a machine that already affords a bug clone's
restored dump. The twin is the right answer the day demo clones are
started by the dozen, and that is a punt.

## 2. The person's browser reaches it

A bug clone is reachable only through the door. A demo clone serves its
UI to one person for its lifetime. This is the second of the two
changes to the machine.

### The route

**A hostname, not a path.** The UI and every `self` it follows are
absolute paths under `/api/…` and `/`, so a path prefix would have to
be rewritten into every document the engine answers. A hostname needs
nothing from the engine.

- The hostname is `<short id>-<token>.<the edge's clone zone>`, served
  behind the edge under one wildcard name and certificate. The zone is
  the waymark-doors child's to name.
- `<short id>` is the clone's id, eight characters. `<token>` is at
  least 80 random bits, minted at `up`, and it is the secret part.
- The engine's name is `demo-<short id>` and does not hold the token.
  An export names the engine, and it must not name the way in.
- `up` answers the URL to its caller and to nobody else. It is not
  listed, not linked from any working engine, and not written to a
  row.
- **The route is part of the job.** It is the job's own service
  registration at the edge, and not a record kept beside it. When
  `down` or the reaper stops the job the registration goes with it and
  the hostname answers nothing. There is no second thing to clean up
  and so nothing to leak.

The route carries the UI, the API and the SSE streams (events,
presence, intents), because ● Record needs all three.

### Signing in

**The dev principal header.** The clone runs with no `WAYMARK10_OIDC_*`,
so `wrap-identity` falls through to the dev headers. The person types a
cast member's id into the UI's dev principal box and is that member.
`up` answers the cast (id and display name) beside the URL.

This is acceptable here, and only here, for four reasons:

- **There is nothing real to reach.** Every row is seeded and public by
  intent. The header guards nothing because there is nothing to guard.
- **There is nothing to reach from it.** The clone is walled (section
  4): no outside service, no credential, no route out. Being any
  member of a demo household gives a visitor a demo household.
- **A real sign-in would spoil the walk.** The family IdP would put a
  real person's id and display name into the recording's cast. A demo
  walk's cast must be demo members.
- **It is short-lived and unlisted.** The hostname's token is the
  gate, and it dies within twelve hours.

It must be said plainly: **anyone who holds the URL is anyone in the
clone.** That is the design, and it is why the loader refuses to seed
an engine that has an IdP or a name that is not `demo-…`, and why the
door refuses a route on a clone that restored a snapshot.

## 3. The walk leaves before the clone dies

The person signs in as a cast member, presses ● Record (ticket
bf90e4a8), does the walk, seals it and exports it. The export is a
download in the person's own browser. That download is the only thing
that leaves a demo clone.

- **The header names the clone.** `up` sets
  `WAYMARK_ENGINE_NAME=demo-<short id>`. The engine already puts its
  name in the export header, so the file reads `"engine":
  "demo-3f9a1c2e"`. A reader can tell a demo walk from a real one by
  the prefix, and can tell two demo sessions apart by the id. The
  engine needs no change for this.
- **The cast is the seed's.** The export's aliases and display names
  come from the seeded members, so no real name can be in the file.
- **An unsealed walk is lost at the reaper.** So is a sealed walk that
  was not exported, and so is every row. The reaper does not wait,
  does not seal and does not export: a clone that could delay its own
  death is a clone that lives. `retention_days` means nothing here.
- **The UI warns on the TTL.** `up` sets `WAYMARK_ENGINE_EXPIRES_AT`
  (an instant). The engine answers it in the well-known document
  beside its name. When it is present the UI shows a banner on every
  screen: "Demo engine. It ends at 16:40 and takes everything with
  it." The banner turns to a warning at 15 minutes, and again at 5.
  While a walk is `recording`, or `sealed` and not yet exported in this
  browser, the warning names it: "Your walk is not exported. Seal and
  export it now." A working engine sets no expiry and shows no banner.
- **The TTL is chosen at `up`.** The default is 2 h and the most is
  12 h, as for a bug clone. This spec adds no way to extend it: the
  expiry is in the engine's environment and is true for the life of
  the job. A person who needs longer exports and starts another.

## 4. Walled as a bug clone is

A demo clone meets every rule of the bug clone's wall. As the epic
states them:

- **Every outside service is on the wall.** The job carries no
  credential and no URL for any of them: Gate, Home Assistant, Google,
  Flickr, GitHub, the bench, the harness's scheduler. Inside the
  engine each source therefore runs its in-process fake, and its
  connection descriptor says `fake`.
- **No route out.** The job's network admits no egress, so a
  misconfigured source fails closed and does not reach a real service.
- **No Routine links.** No seat in the clone can fire. The seed holds
  no seat, schedule or runner link, and the wall would stop the call
  if it did.
- **TTL, reaper, `down`.** Unchanged.

**The seed's held call ends at the wall.** The seed holds one call
that waits on a person, `mail__send`. A held tool call is answered by
the `approver` role, so the seed gives Ada that role. When the job
names its wall (`WAYMARK_WALL_URL`), the loader makes one `mcp_server`
row, `mail`, at that address, with the power the held call names. A
person's Allow is then forwarded to the wall, the wall answers its
sentence, and the held call ends `failed` with that sentence on it,
which a walk can show. With no `WAYMARK_WALL_URL` (a test, or a clone
with no wall task) the row is not made, and Allow ends `failed` with
the engine's own sentence that no server answers to the tool. Neither
path leaves the clone.

The differences, all of them:

| | bug clone | demo clone |
|---|---|---|
| data | a restored production dump | the seed, and nothing from production |
| a person's tap at `up` | required, for the dump | none |
| route in | the door only | the door, and one hostname for a browser |
| identity | as the door presents it | the dev principal header |
| engine name | the door's | `demo-<short id>` |
| expiry shown | no | `WAYMARK_ENGINE_EXPIRES_AT`, in the UI |

The route in is the one loosening, and it is safe only because of the
first row: the clone holds no production data. So the door enforces
the pairing in both directions. **A route is given only to a seeded
clone, and a seeded clone never restores a snapshot.** A clone with
production rows and a browser route is the one combination this design
must make impossible.

## 5. The children

Each child is one PR under the bench's ceiling, except the experiment.
Children 1 and 2 are in ckopsa/waymark and depend on nothing. Children
3 and 4 are in ckopsa/waymark-doors, and 3 needs 1 merged, because
`up` can only seed a commit that carries the seed.

1. *The seed and its loader* (ckopsa/waymark). Covers
   `waymark10/resources/waymark10/demo/seed.edn`, a loader
   (`waymark10.server.seed`) that walks the steps through the doors,
   its refusals, and the boot step in `workqueue10.main` behind
   `WAYMARK10_SEED`. Tests in a new `waymark10.seed-test`, over the
   in-memory twin and a toy kind:
   `a-seed-loads-through-the-doors-and-leaves-a-history`,
   `a-step-the-law-refuses-ends-the-boot`,
   `a-seed-refuses-an-engine-not-named-demo`,
   `a-seed-refuses-an-engine-with-an-idp`,
   `a-seeded-engine-is-not-seeded-twice`, and
   `a-relative-date-is-counted-from-the-boot`. One more where the
   seed's own kinds are loaded, in `workqueue10`'s suite:
   `the-demo-seed-loads-and-reaches-every-ticket-state-but-in-review`.
   That test is what keeps the seed true as the kinds change. It
   leaves `in_review` out for section 1's reason: a demo clone has no
   bench, so no change is submitted.
2. *The engine says when it ends, and the UI warns* (ckopsa/waymark).
   Covers `WAYMARK_ENGINE_EXPIRES_AT` in the well-known document and
   the banner of section 3. Tests in `waymark10.ui-test`:
   `ui-shows-no-banner-on-an-engine-without-an-expiry`,
   `ui-warns-before-a-demo-engine-ends`, and
   `ui-names-an-unexported-walk-in-the-warning`.
3. *`seed` on clone-mcp's `up`* (ckopsa/waymark-doors). Covers the
   argument, the empty database, the environment (`WAYMARK10_SEED`,
   `WAYMARK_WALL_URL`, which is the wall's address and is where the
   seed's held call is forwarded (section 4),
   `WAYMARK_ENGINE_NAME=demo-<short id>`, `WAYMARK_ENGINE_EXPIRES_AT`,
   `WAYMARK10_AUTO_MIGRATE=1`, `FACTORY10=1` because the seed's
   tickets are factory kinds, no `WAYMARK10_OIDC_*`), and the failed
   seed that tears the clone down. Tests:
   `up-with-a-seed-restores-no-snapshot`,
   `up-with-a-seed-asks-no-tap`,
   `up-with-a-seed-and-a-snapshot-is-refused`,
   `up-with-a-seed-the-commit-lacks-is-refused`,
   `a-seeded-clone-is-named-demo`, and
   `a-seeded-clone-carries-no-credential`.
4. *The browser route* (ckopsa/waymark-doors). Covers the hostname,
   its token, the registration that is part of the job, and the URL
   and cast in `up`'s answer. Tests:
   `a-demo-clone-answers-on-its-hostname`,
   `a-bug-clone-is-given-no-route`,
   `the-engine-name-does-not-hold-the-route-token`,
   `the-route-is-gone-when-the-job-stops`, and
   `a-demo-clone-has-no-route-out`.
5. *The experiment: one marketing walk, recorded end to end.* It is
   not a PR. A person runs `up(commit, seed: "demo")`, opens the URL,
   signs in as a cast member, records a walk in which the seeded agent
   invites them to a step, seals, exports, and lets the clone die. It
   passes when the file's header says `demo-…`, its cast holds only
   seeded names, the hostname answers nothing afterwards, and the file
   plays in a working engine's replay view. It needs children 1, 3 and
   4, and ● Record. What it finds becomes tickets: the seed's thin
   places first.

### Later, its own child: the standalone player

A static page that plays a `waymark-walk/1` file with no engine behind
it. It is what lets a demo walk be put on a public page. It is not
needed to record one, so it is not in the list above. What it needs:

- **The documents, in the file.** A `move` frame carries a `self`
  path, and a view that follows it reads that row from a live engine.
  A player with no engine cannot. The export must carry, for each
  `self` a frame visits, the envelope as the exporter saw it at that
  moment, and the discovery document once. This is a format change
  (`waymark-walk/2`, or an optional `docs` block in `/1`), and it is
  the first thing the child must decide. The export's rules still
  hold for it: aliases for principals, no origin, re-redaction under
  the exporter.
- **The follow view's frame code, with no network.** The code in
  `200-events-follow.js` that applies `ui` and `move` frames, built so
  that every read resolves from the file and a write is impossible,
  not merely unused.
- **One self-contained page.** The UI's own CSS and the render code it
  needs, bundled, with no call to any origin. The file arrives by
  drop, by file picker, or by `?walk=<url>` on the same origin.
- **A player's controls.** Play, pause, scrub, speed, and a caption
  that shows the header: the title, the date and the engine's name.
- **A version rule.** A file records the UI of one commit and the
  player is another. The header should name the commit, and the
  player says so when it meets a frame it cannot render.

## Punts

- **The in-memory twin for demo clones.** Section 1. Revisit when the
  cost of a Postgres per clone is felt.
- **Backdated history.** A seeded row's moves all happened at boot. A
  walk that needs "opened last Tuesday" needs a clock the loader can
  set, and the engine has none for writes.
- **More than one seed.** `seed` is a name so that there can be
  others (`demo-meals`, `demo-factory`). This spec writes one.
- **Extending a demo clone.** Section 3. Export and start another.
- **Several people in one clone.** The route does not prevent it, and
  a two-person walk may want it. Nothing here is designed for it: the
  token is one secret, shared by whoever is told it.
- **A hosted place for exported walks.** Where the marketing files
  live is the player's question, not the clone's.
