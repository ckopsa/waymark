# The factory kit

`factorykit/` is the deps root a factory is composed from. factory10
reads it as a local root (`factory10/deps.edn`), and its tests run in
the `test-factory` job beside factory10's.

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
factory10's policy has no advisory field yet, so its `check-verdict`
passes none.

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
