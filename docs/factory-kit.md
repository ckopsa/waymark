# The factory kit

`factorykit/` holds what every factory's kinds share. A factory is an
engine that turns tickets into merged changes: `factory10/` in this
repository is one, and a factory outside it depends on the kit by
`:git/url` with `:deps/root "factorykit"`. factory10 reads it as a
local root (`factory10/deps.edn`), and the kit's tests run in the
`test-factory` job beside factory10's.

A kit namespace for a kind gives a `core` and a `build`. The core is data: the
part of a kind every factory has. `(build core extension)` answers the
map `waymark10.dsl/defresource` takes, where the extension is the
factory's own defresource map without the core's part. An extension
that names what the core already named is refused when the namespace
loads.

## The engine, the kit and the factories

Three layers, each depending only on the one below it:

| Layer | Where | What it holds |
| --- | --- | --- |
| Engine | `waymark10/` | the machine every kind runs on: declarations, doors, guards, the store, the sit and the bench |
| Kit | `factorykit/` | what every factory's kinds share: the ask core, the change core, the repository policy core, the forge |
| Factory | `factory10/`, or a repository of its own | its tickets, its own fields and doors, and the hands that close its engine into the kit's functions |

The kit names no factory. A factory names the kit and the engine, and
nothing in the engine names either.

## The change (`factorykit.change`)

A change row is one piece of work on its way to a merged pull
request: a seat's sit mints it beside an ask, the bench submits it,
and the forge's source moves it.

### What the core holds

| Part | The core's |
| --- | --- |
| Machine | `open`, `submitted`, `failing`, `stuck`, `merged` and `closed` |
| Tomb | `merged` alone: a closed change comes back through `reopen` |
| Index | one row for each `change_id` |
| Fields | the fields the bench, the stall and the source write, and `born_from`, the address of the ask the change was built for |
| Source doors | `fail`, `recover`, `merge`, `close` and `reopen`, behind `the-mirror-writes-this-row`; the facts and the identity are the `pull_request` row's, reached through `link` |
| Stall doors | `stall`, `unstick`, `unstick_submitted` and `rebranch` |
| PR-less doors | `close_without_pr`, and `reopen` back from `closed` |
| Cracks law | the wait on the checks in `submitted`, and the rest of `:waits`, `:left-by`, `:reached-by` and `:expected-age` |
| Sweep | `sweep!`, which closes through `close_without_pr` each change with no pull request whose work is over |

### What the extension gives

`(build core extension)` answers the map `defresource` takes.

| Key | Meaning |
| --- | --- |
| `:schema` | the factory's own fields, as a `[:map ...]` of entries with no properties |
| `:field-order` | another order for the fields; a field it does not name follows |
| `:unique` | its own indexes, after the core's |
| `:handlers` | a handler for a core door that has none: `merge`, `close`, `fail` and `recover` read what the change was built for, so a factory gives its own |
| `:actions` | its own doors, its create door among them |
| `:waits`, `:left-by`, `:reached-by`, `:expected-age` | its own law over the core's; two maps merge and its entries win |
| every other key | passed through to `defresource` |

`stall`, `unstick` and `unstick_submitted` take the kit's
`row-handlers` when `:handlers` names none, so a stall always says why
on the row. `close_without_pr` and `reopen` do as well. A factory
whose stall moves its ask gives its own handler, and calls `stalled`,
`unstalled` or `reopened` inside it for the row's part. An extension
that names `:states` or `:terminal`, a core field, a core door, or a
handler for a door that has the core's own is refused.

### The contract

`factorykit.change-contract` is the suite a factory runs against its
own change. `(failures new-hand)` runs each clause on a fresh hand and
answers the clauses the change broke; `[]` conforms. The clauses:

- the machine is the six states, and `merged` is the tomb;
- one row for each `change_id`: the index is declared, and a second
  mint of an id is refused where the store holds the index (the hand's
  `:indexes?`; a memory store holds none);
- `born_from` stands: a minted change keeps it, and the source's
  adoption, which writes the pull request's id, keeps it too;
- the wall: a person and a model acting for nobody are refused the
  source's doors, and the source walks them;
- a change with no pull request whose ask has left the queue for a day
  is swept to `closed`, and the ask keeps its state; an hour is not a
  day;
- `close_without_pr` lands in `closed` and `reopen` leaves it, a
  person cannot walk `reopen`, and a closed change with no pull
  request is `open` again, its rounds at zero, when its ask is groomed
  again.

The hand is the factory's engine closed into plain functions; the
namespace's docstring names each key. factory10 builds it on a memory
engine in `factory10.change-contract-test`, where the ask is a
ticket and the regroom is the person's `groom` and then the sit's
own reopen.

### How another factory adopts the change core

The steps colton-tools' mayor follows:

1. Depend on the kit: `factorykit/factorykit {:git/url
   "https://github.com/ckopsa/waymark" :git/sha "..." :deps/root
   "factorykit"}` in `deps.edn`, beside the engine.
2. Write the extension: `:kind :change`, `:initial :open`, the create
   door, the factory's own fields in `:schema`, and in `:handlers` its
   `merge`, `close`, `fail` and `recover`. A factory with an ask to
   move names `stall`, `unstick` and `unstick_submitted` too.
3. Declare the kind: `(defresource change (kit/build kit/core
   extension))`. A refusal at load says what the extension named
   twice.
4. Give the failing and link passes their hand (`move-change!` and
   `link-change!`, below) and call
   `sweep!` with an `ended` that says when an ask's work is over.
5. Sit code seats through the engine's sit, which mints the change
   beside the ask with `born_from` and reopens a closed one with no
   pull request when the ask is back in the queue.
6. Run the contract: a test that builds a hand on a fresh engine and
   expects `(failures new-hand)` to be `[]`. Take
   `factory10.change-contract-test` as the pattern.

## The ask (`factorykit.ask`)

An ask is the ticket a factory's queue holds: groomed, built in a
change, reviewed and landed. factory10's `ticket` is
`(ask/build ask/core factory10s-own)`.

### What the core holds

| Part | What |
| --- | --- |
| Machine | the seven states `draft open in_review blocked deferred done dropped`, `draft` initial, no tomb |
| Queue doors | `groom` (with its priority input, 0 to 3), `ungroom`, `return_to_draft`, `prioritize`, `block`, `unblock`, `defer`, `resume`, `shelve`, `drop`, `reopen`: their `:from` and `:to` |
| Link doors | the review and return doors, under the names the extension's `:link` gives, with their wall `only-its-change-moves-it` |
| Law | `:waits` on `blocked_by`; `:left-by` for the review door (the change's submit), the merged door (the forge's mirror), `unblock` and `return_to_draft` (a blocker's ending); `:expected-age` open 12 h, in review 6 h, blocked 7 d |
| Functions | `release-the-waiters!`, the link's `submitted!`, `returned!`, `merged!` and `close-its-changes!` |

`draft`, `deferred`, `done` and `dropped` have no age (`unaged`):
a draft waits on a groom, a deferred ask on its `defer_until`, and the
endings are over.

### What the extension gives

- `:kind`, `:plural`, the schema and every other door of its own;
- the guards, handlers, safety and words of the core doors (their
  machine and groom's input are the kit's, and naming them is refused);
- `:link`: which door each moment of the change link walks, over
  `link-defaults`;
- the law it adds or overrides: a `:waits` entry by its `:on`, a
  `:left-by` entry by its `:via`, an `:expected-age` by its state. An
  entry the core has is replaced; a new one follows the core's.
  `:reached-by` is the extension's own.

factory10 adds one wait, `merge_after`, and takes the rest of the law
from the core. The kit's `factorykit.ask-test` checks that a built ask
gives every state outside `unaged` an expected age and an exit.

## The repository policy (`factorykit.repo-policy`)

A repo_policy row says what submit means in one repository. A person
states it, and the engine writes beside it what the bench and the
forge did with it.

### What the core holds

| Part | The core's |
| --- | --- |
| Machine | `active` and `retired`, born `active`, and no tomb |
| Index | one row for each `repository` |
| Stated fields | `repository`, `branch_pattern`, `base`, `required_checks`, `rounds_per_change` |
| Offered fields | `forge`, `workspace`, `forge_repo`, `advisory_checks` |
| Doors | the machine of `restate`, `retire`, `restore` and `note_source` |
| Guard | `a-person-or-their-delegate-states-the-policy` |

An offered field is on a factory's policy only when the extension's
`:takes` names it. All four are optional, so a factory that takes one
later restates no row. A factory may tighten one it takes, never widen
it.

`branch_pattern` has exactly one `*` and no space.

The guard refuses a model that acts for nobody. A factory names it on
its create door and on the three doors a person walks.

### What the extension gives

| Key | Meaning |
| --- | --- |
| `:policy` | the factory's own stated fields, as schema entries |
| `:takes` | the offered fields it has: a name takes one as offered; `[name {:required true :schema … :x-display …}]` tightens it — `:required` drops `:optional`, `:schema` stands in for the offered schema, `:x-display` merges over the offered words — and `build` refuses a `:schema` that admits what the offered one refuses (a longer `:max`, another type) |
| `:fixed` | stated fields `restate` does not take: a person states them once, at create |
| `:policy-order` | the order of the stated fields on the form; a field it does not name follows |
| `:engine` | the fields the engine writes and no form offers |
| `:actions` | its own doors, and for a core door the guards, the handler, the safety and the words |
| `:expected-age` and the other cracks law | its own; the core states none. Per-state expected ages are this law key, which the builder passes on, and no stated field |
| every other key | passed through to `defresource` |

`build` writes `:schema` (the stated fields, then the engine's),
`:create-schema` (the stated fields), and the `restate` door's
`:input` and `:edit` prefill (the stated fields again, but for the
`:fixed` ones). An extension
that names `:states`, `:initial`, `:terminal`, `:schema`,
`:create-schema`, a core door's `:from`, `:to` or `:undo`, or
`restate`'s `:input` or `:edit` is refused.

`(statement core extension)` answers the stated fields alone, for a
handler that must know which fields a restatement replaces.

### factory10's extension

`factory10.resources.repo-policy` takes no offered field. Its own
stated fields are the clone URL, the size ceiling, the merge (who
merges, the method, the strategy, the train and the waits), the
deploy check, the groom floor, the formatter, the deny list, the test
workflow, the check step, the hosted workflows and the orientation
document. Its engine fields are the enrolment, the forge pass, the
base branch's state, the deploy wait and the merge line. Its two
hidden doors, `mark_enrolled` and `note_base`, are its own.

## The forge

`factorykit.forge` holds what every forge adapter shares: one protocol,
one build-state table and one conformance suite.

### The protocol

`ForgeSource` is the seam between a forge and the factory's rows. An
adapter answers the factory's own documents through six verbs:

| Verb | Answers |
| --- | --- |
| `forge-poll` | the pull requests and checks that moved, with head and base |
| `forge-log-tail` | the tail of one failed job's log |
| `forge-label!` | nothing; it pushes one label, the only write |
| `forge-calls` | how many calls the last poll made |
| `forge-checks` | the build states on one head |
| `forge-base` | the head of a base branch and the build states on it |

A pull request whose head branch a row already names is adopted by
that branch, not minted again. `factory10.sources.forge` keeps the
protocol under its old names (`forge/ForgeSource`, `forge/forge-poll`),
which are the kit's own objects.

### The build-state table

`factorykit/resources/build-states.edn` maps each forge's raw state to
one of four verdicts. `build-states.json` beside it has the same
content for a reader that is not Clojure (the waymark-bench rig).

| Verdict | GitHub | Bitbucket |
| --- | --- | --- |
| green | `success`, `neutral`, `skipped` | `SUCCESSFUL` |
| red | `failure`, `timed_out` | `FAILED` |
| pending | `queued`, `in_progress` | `INPROGRESS` |
| interrupted | `cancelled`, `stale`, `action_required` | `STOPPED` |

An interrupted build died without a verdict. It is a rerun: it is
never green, and it never recovers a failing change. A finished state
the table does not name has no verdict, so it is never green either.

To change a reading, change the EDN file and the JSON file together.
`factorykit.forge-test` fails when the two differ.

The table reads a check on a head. factory10 reads it in
`check-verdict`, `red-conclusions` and `green-conclusions`: a check
waits when the table gives it no verdict, so a `stale` or
`action_required` check is interrupted and does not wait. Two
readers leave the table on purpose:

- `factory10.sources.forge/interrupted-conclusions` judges a JOB inside
  a run, not a check. The table reads a timed out check as red, and that
  red is what sends the pass to the run's jobs. There the question is
  whose fault the red is. A job the forge timed out is a runner that
  froze, so `job-verdict` reads it as interrupted and the pass reruns
  it one time.
- `factory10.bench/red-deploys` is the table's red states and its
  interrupted states together, and `startup_failure` beside them. The
  house reruns no deploy, so a deploy check that was cancelled did not
  land and holds the line as a red one does. `startup_failure` is a
  workflow run's conclusion. No check run carries it, so the table does
  not name it.

### The verdict

`(verdict {:forge :required :advisory} checks)` reads the checks on one
head against a policy:

- red, with the names, when every required check finished and one is red;
- green when every required check finished green;
- interrupted, with the names, when none is red and one is interrupted;
- nil while a required check is pending or has no run.

Of several runs of one check name, the newest speaks. A policy that
names no required check requires every check on the head.

`:advisory` names the checks that count only when red (a security scan
that may fail a change but must not hold one). A red advisory check is
named beside the required reds. A pending, interrupted or absent one
holds nothing. The policy declares the names; the kit builds none in.
factory10's `check-verdict` passes none. Its policy takes none of the
kit's offered fields, and `advisory_checks` would be a new field on the
form a person states every policy with. A person decides that, so it is
a ticket of its own and not a change to the reading.

### The conformance suite

`factorykit/resources/forge-conformance.edn`, with its JSON copy, is a
list of cases. Each gives raw statuses and required checks and the
answer every adapter must give. A case asks one of three questions:

| `:asks` | Reads | Expects |
| --- | --- | --- |
| `"verdict"` | `:forge`, `:required`, `:advisory`, `:checks` | `{:verdict :names}` or nil |
| `"state"` | `:forge`, `:check` | one verdict word or nil |
| `"behind"` | `:behind_by` | true or false |

`(conformance-failures judge-fn)` runs the cases against one judge and
answers the ones it got wrong; an empty answer conforms. factory10 runs
them in `factory10.forge-conformance-test`.

### How a Bitbucket adapter plugs in

1. Implement `ForgeSource` for Bitbucket. In `forge-checks` and
   `forge-base`, answer each build as `{:check_name name :state raw}`,
   with Bitbucket's own word in `:state` (`SUCCESSFUL`, `FAILED`,
   `STOPPED`, `INPROGRESS`). Do not translate it.
2. Judge with `(verdict {:forge :bitbucket :required … :advisory …}
   checks)`. Keep no state set of your own.
3. Add a test that calls `conformance-failures` with a judge that sends
   the Bitbucket cases through your adapter's own path, and expects
   `[]`.
4. When Bitbucket has a state the table lacks, add it to both table
   files and add a case for it to both conformance files.

### The change rows

The poll writes no `change` row: the change pass that minted, adopted
and folded rows from it is gone (plan 85b4462e). `(move-change! hand
row doc)` moves a row that is already here to what the forge says now:
merged, closed or reopened, → `[row moved?]`. `(link-change!
hand row log-fn)` links a row with a number to its `pull_request` row.
The failing pass and the factory's link pass use them. They require no
engine and no factory10 namespace. The factory gives them a hand, which
is a map of the `change` kind's doors with the factory's engine and
system principal closed into them:

| key | what it is |
|---|---|
| `:invoke!` | `(fn [row-id door input])` → the row after the door |
| `:find-pull-request` | `(fn [repository number])` → the `pull_request` row's id, or nil; without it `link-change!` links nothing |

The field list and the door tables they read (`change-observe-fields`,
`adopt-states`, `state-door`) are in the kit beside them.

### The pass

`(pass! config)` is one pass of a forge (ticket 75db2ef8). It polls the
source, holds the pass when the source's rate limit is spent
(`ForgeBudget`), and stores the source's place or sends it back
(`ForgeCommit`). It writes no row for a polled pull request. It requires no engine and no factory10 namespace.
docs/spec-change.md (§2) tables each job this pass and the merge
sweeper carry today, and says which primitive carries each one next
and in what order the pass gives them up. The factory's own steps go
in as functions:

| key | what it is |
|---|---|
| `:source` | the `ForgeSource` |
| `:census` | the census the pass starts from |
| `:log-fn` | takes the parts of one line |
| `:poll-failed` | optional `(fn [e])`: the poll threw |
| `:before-changes` | optional `(fn [census poll])` → census, before the place is stored |
| `:after-changes` | optional `(fn [census poll])` → census, after the place is stored |
| `:runs` | optional: the `ci_run` kind's doors, as `runs-pass!` takes them; the red runs run after `:after-changes` |
| `:after-runs` | optional `(fn [census poll])` → census, after the red runs |
| `:failing` | optional: the failing hand, as `failing-pass!` takes it; the failing pass runs after `:after-runs` |
| `:after-failing` | optional `(fn [census poll])` → census, after the failing pass |
| `:always` | optional `(fn [census poll held-until])` → census, run whether the pass ran, was held or threw |
| `:report` | optional `(fn [census poll])`, given the census last, in place of the census line |
| `:note` | optional `(fn [line])` → line, given the census line before it is logged |

Without `:report`, the pass logs `(census-line census poll)`: one line
of counts, the same for every factory. `fresh-census` is the census a
factory starts from, every count at zero.

factory10's `pass!` is a call to it, with factory10's hand and its own
steps.

### The red runs

`(runs-pass! runs source poll census log-fn)` is the red runs of one
pass (ticket efff5d2e): every failed check the poll carried, and every
one the hand's `:live-reds` adds, becomes a `ci_run` row at red, once;
the red runs of a head that moved walk `supersede`, and so does every
red run whose change is merged or closed. `pass!` runs it when the
config names `:runs`. The `ci_run` hand:

| key | what it is |
|---|---|
| `:find-run` | `(fn [run-id])` → the `ci_run` row with that run id, or nil |
| `:find-change` | `(fn [change-id])` → the `change` row with that `change_id`, or nil |
| `:change-by-id` | `(fn [row-id])` → the `change` row with that id, or nil |
| `:reds` | `(fn [where limit])` → up to `limit` red `ci_run` rows matching `where`, oldest first |
| `:create!` | `(fn [fields])` → the row the create door minted |
| `:invoke!` | `(fn [row-id door input])` → the row after the door |
| `:red-conclusions` | the conclusions that mint a red run (GitHub's reds when absent) |
| `:live-reds` | optional `(fn [checks])` → the red check documents the poll did not carry |

factory10 hands it `run-hand`, whose `:live-reds` reads the heads of the
live changes of its active policies.

### The failing pass

`(failing-pass! failing source census log-fn)` is fail, recover and
stick on a head's verdict (ticket 58c51a5d): every live change of a
repository with an active policy has its head read by the hand, and
`failing-move` names at most one door. Before the door, the pull
request is read again by number (`ForgePull`, `forge-moved!`): a merge
or a new head the poll's window missed moves the row instead. `pass!`
runs it when the config names `:failing`. The failing hand:

| key | what it is |
|---|---|
| `:changes` | `(fn [])` → the live `change` rows |
| `:policies` | `(fn [])` → the active policy rows, each naming `:repository` |
| `:rounds-of` | `(fn [policy])` → the rounds the policy gives a change |
| `:read` | `(fn [row policy head census])` → `{:verdict :census :conflicts}`, the factory's own reading of one head |
| `:move!` | `(fn [row doc])` → truthy when the row moved to what the forge says now |
| `:invoke!` | `(fn [row-id door input])` → the row after the door |

factory10 hands it `failing-hand`, whose `:read` is its landing
verdict, its held log and its re-run of an interrupted head.

### What is not in the kit yet

The seam is: the kit takes what reads only the forge and the `change`
kind. factory10 keeps what reads its own policy, tickets or bench.

The pass, the red runs, the failing pass and the census line are all
the kit's now.

factory10's own, and not for the kit: the landing verdict, the red base
and its tickets, the held log, the groom floor, the beat, the policy
source notes and the sweeps.
