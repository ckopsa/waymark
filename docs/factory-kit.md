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

### What is not in the kit yet

The polling pass (the system principal, the source-writes guard, and
adopt, observe, fail, recover, merge and close) is still in
`factory10.sources.forge`. It writes factory10's own kinds and reads
factory10's policy, so lifting it is its own change.
