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
| Kit | `factorykit/` | what every factory's kinds share: the change core, the repository policy core, the forge |
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
| Source doors | `adopt`, `adopt_submitted`, `observe`, `observe_submitted`, `fail`, `recover`, `merge`, `close` and `reopen`, behind `the-mirror-writes-this-row` |
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
4. Give the forge pass its hand (`change-pass!`, above) and call
   `sweep!` with an `ended` that says when an ask's work is over.
5. Sit code seats through the engine's sit, which mints the change
   beside the ask with `born_from` and reopens a closed one with no
   pull request when the ask is back in the queue.
6. Run the contract: a test that builds a hand on a fresh engine and
   expects `(failures new-hand)` to be `[]`. Take
   `factory10.change-contract-test` as the pattern.

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
later restates no row.

The guard refuses a model that acts for nobody. A factory names it on
its create door and on the three doors a person walks.

### What the extension gives

| Key | Meaning |
| --- | --- |
| `:policy` | the factory's own stated fields, as schema entries |
| `:takes` | the names of the offered fields it has |
| `:policy-order` | the order of the stated fields on the form; a field it does not name follows |
| `:engine` | the fields the engine writes and no form offers |
| `:actions` | its own doors, and for a core door the guards, the handler, the safety and the words |
| `:expected-age` and the other cracks law | its own; the core states none |
| every other key | passed through to `defresource` |

`build` writes `:schema` (the stated fields, then the engine's),
`:create-schema` (the stated fields), and the `restate` door's
`:input` and `:edit` prefill (the stated fields again). An extension
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
`check-verdict`, `red-conclusions`, `green-conclusions` and
`held-checks`: a check is held when the table gives it no verdict, so a
`stale` or `action_required` check is interrupted and not held. Two
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

### The change pass

`(change-pass! hand changes census log-fn refused)` turns every pull
request a poll answered into a row minted, a row adopted by its head
branch, a duplicate folded, or a row moved: observed, merged, closed or
reopened. It requires no engine and no factory10 namespace. The factory
gives it a hand, which is a map of the `change` kind's doors with the
factory's engine and system principal closed into them:

| key | what it is |
|---|---|
| `:find-change` | `(fn [change-id])` → the row with that id, or nil |
| `:branch-changes` | `(fn [repository head-branch limit])` → the rows on that branch |
| `:create!` | `(fn [fields])` → the row the create door minted |
| `:invoke!` | `(fn [row-id door input])` → the row after the door |
| `:forge-id-prefix` | what a `change_id` of this forge starts with (`"github:"` when absent) |
| `:move!` | optional `(fn [row doc])` → `[row moved?]`, for a factory that wraps `move-change!` |

The field lists and the door tables the pass reads (`change-create-fields`,
`change-observe-fields`, `observe-doors`, `adopt-doors`, `fold-states`,
`state-door`) are in the kit beside it. factory10's `change-pass!` and
`move-change!` call the kit with factory10's hand.

### What is not in the kit yet

The seam is: the kit takes what reads only the forge and the `change`
kind. factory10 keeps what reads its own policy, tickets or bench.

Still in `factory10.sources.forge`, and the kit's to take in later
changes:

- the poll itself, with the source's retry, commit and forget
  (`ForgeRetry`, `ForgeCommit`) and the rate-limit hold (`ForgeBudget`);
- the red runs (`ci_run`: mint, supersede, settle);
- fail, recover and stick on a head's verdict, with the re-read by
  number before a move;
- the census line.

factory10's own, and not for the kit: the landing verdict, the red base
and its tickets, the held log, the groom floor, the beat, the policy
source notes and the sweeps.
