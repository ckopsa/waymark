# Moving a factory onto the kit

This guide is for a factory's mayor: the person, with the model
beside them, who moves a factory that grew its own `change`, ticket,
forge and repository policy onto `factorykit/` (epic 459ed2eb, step
6). It is written for colton-tools, and it holds for any factory
outside this repository. You follow it in order. Each step ends in a
check, and you go on only when the check is clean.

The reference is [`factory-kit.md`](factory-kit.md): what each core
holds, what an extension gives and what the builder refuses. This
guide says which of those you touch, in which order, and how you
know a step took. You should not need to read factory10's code.
Where this guide says "factory10 does", it is pointing at a pattern.
You do not have to copy it.

## 0. Before you start

- Read the factory's own house for cracks before you change
  anything: `GET /api/-/cracks` on the factory's engine, with a
  person's bearer. Save the answer. Every later check compares
  against it, so you can tell a crack the move made from one that
  was already there.
- List the factory's active seats: `GET /api/-/cracks/seats`. Step 7
  restates them, and this list is what they were.

## 1. Bump the waymark10 pin to a commit on main

colton-tools pins waymark10 at `b73053db`. That commit is not on
`main`: its branch split off around 2026-10-01 and was never merged.
Pin a commit that is on `main`. Take the newest one whose `gate`
check is green, and pin `factorykit` (step 2) at that SAME commit.

```clojure
;; deps.edn
waymark10/waymark10 {:git/url "https://github.com/ckopsa/waymark"
                     :git/sha "<a main commit>"
                     :deps/root "waymark10"}
```

What the bump brings, and what each one needs from the factory
before it goes quiet:

| What arrives | What it does | What the factory declares |
| --- | --- | --- |
| The cracks audit (`GET /api/-/cracks`) | A static read over the declarations and the active seats. For each state a row can reach, it names the ways work stops with nobody told: `no_exit`, `cadence_only`, `person_only`, `unwoken`, `remedy_unheld`, `dead_wait`, `silent_person_exit` and `unwalked_value`. | Per kind: `:left-by` for each exit a source takes rather than a seat (the mirror, a sweep, a wait's ending), `:reached-by` for each arrival through another kind's door, and `:waits` for each thing a row waits on. Per seat: a door subscription (spec-events E-3.9) on the transition into each state the seat moves rows out of. |
| The live cracks pass | A scheduled pass over rows. It files a draft ticket, once, for each `aged`, `silent`, `bounce`, `refusal_loop` and `orphan` crack. An aged row wakes the one seat that holds it before any draft is filed. | `:expected-age` per kind and state, in seconds. A state that declares no age takes the default for its name: `open`, `submitted` and `in_review` get a day, and `blocked` gets seven days. A state with neither has no age. |
| `waiting_on` | Each row's envelope, and the summary from `waymark_get`, says who moves the row next: the next doors, the seats that hold them, the wakes that bring those seats, and a verdict line. | Nothing new. It reads the same law and seat rows the audit reads, so a quiet audit gives honest `waiting_on` lines. |
| The seat-change crack hold | A seat's create and an authoring seat's restate run the audit before and after the write. A write that adds a crack is stopped, and the refusal names the crack. When a person makes the write, each seat door that runs the audit (create, restate, retire and merge) gives a warning that they acknowledge (`opens-no-crack`) instead. An authoring seat's restate becomes a held call that a person taps. | Make the law quiet (this table) BEFORE you restate seats in step 7. Otherwise each restate stops on a crack the factory already had. |
| The read-only-seat fix | One kind may carry several filtered scope entries, so a seat can read a kind more widely than it acts on it. A seat sees the union of the filters, and a door opens only under an entry that names it. An entry with `:actions []` grants sight and no doors. | Nothing, until a seat needs to read rows it does not move. The judges in step 7 use this. |

**Check.** Run the factory's suites on the new pin. Then read
`GET /api/-/cracks` again: only the cracks the new audit names should
have changed. Each new one is a declaration from the table above
that the factory still owes.

## 2. Add the kit

```clojure
;; deps.edn, beside waymark10 and at the same sha
factorykit/factorykit {:git/url "https://github.com/ckopsa/waymark"
                       :git/sha "<the same main commit>"
                       :deps/root "factorykit"}
```

The kit's own `deps.edn` points at waymark10 with `:local/root
"../waymark10"`, which is the waymark10 in the same checkout. Your
top-level waymark10 coordinate wins, so use the same sha for both.
With two different shas, the kit runs against a waymark10 it was
never tested with.

**Check.** `(require 'factorykit.change 'factorykit.ask
'factorykit.forge 'factorykit.repo-policy)` loads in the factory's
REPL.

## 3. The change: `factorykit.change` and an extension

1. Split the factory's change declaration into two parts. One part
   is what the core already holds (see "What the core holds" in the
   reference). The other part is everything else, and that part
   becomes the extension.
2. Map today's fields. The core's fields are `change_id`,
   `repository`, `number`, `title`, `author`, `base_branch`,
   `head_branch`, `head_sha`, `draft`, `mergeable`, `files_changed`,
   `lines_added`, `lines_removed`, `touched_paths`, `labels`,
   `review_state`, `missing_checks`, `behind_base`,
   `base_compared_at`, `missing_checks_head`, `worktree_dirty`,
   `rounds`, `stall_reason`, `stalled_at`, `stalled_by`,
   `failing_checks`, `conflicts`, `rerun_head`, `rerun_note`,
   `landing_error`, `train_red_head`, `train_red`, `unadopted_since`,
   `adoption_note`, `url` and `born_from`. A factory field with the
   same meaning takes the core's name, and you write a data
   migration that renames the stored key. A field the core lacks
   goes into the extension's `:schema`. A field with a core name and
   a different meaning is renamed on the factory's side, because the
   builder refuses to define a core field twice.
3. Write the extension: `:kind :change`, `:initial :open`, the
   create door, the factory's own fields and doors, and `:handlers`
   for `merge`, `close`, `fail` and `recover`. Each of these four
   reads what the change was built for. If the factory's stall moves
   its ticket, also give handlers for `stall`, `unstick` and
   `unstick_submitted`, and call the kit's `stalled`, `unstalled` and
   `reopened` inside them for the row's part.
4. Declare it: `(defresource change (kit/build kit/core
   extension))`. A refusal at load says what the extension named
   twice.
5. What the factory gains:
   - `close_without_pr`: closes a change that never got a pull
     request, and moves no ask.
   - `reopen`: `closed` is no longer a tomb, and only `merged` is.
     Walk the factory's own close-and-reopen paths once by hand.
   - The PR-less sweep: call `sweep!` from the factory's clock, with
     an `ended` that says when an ask's work is over. Each change
     with no pull request whose ask has been out of the queue for a
     day is closed through `close_without_pr`.
6. The core's `:reached-by` names `{:kind :ticket :action :groom}`.
   If the factory's ask kind has another name, give your own
   `:reached-by` in the extension. The extension's entries replace
   the core's.

**Check.** Write a test that builds a hand for
`factorykit.change-contract` on a fresh engine and expects
`(failures new-hand)` to be `[]`. The namespace docstring names each
key of the hand, and `factory10.change-contract-test` shows one
built on a memory engine. Then read `GET /api/-/cracks`. There
should be no new crack on `change`.

## 4. The ask: `factorykit.ask` and an extension

1. The core is the seven states (`draft`, `open`, `in_review`,
   `blocked`, `deferred`, `done`, `dropped`), with no tomb. It also
   holds the machine of the queue doors: `groom`, `ungroom`,
   `return_to_draft`, `prioritize`, `block`, `unblock`, `defer`,
   `resume`, `shelve`, `drop` and `reopen`. Map the factory's own
   states onto these. A state with no counterpart is a field, such as
   a stage within `open`. It is not another state.
2. Write the extension: `:kind`, `:plural`, the schema with the
   factory's own fields, and the guards, handlers, safety and words
   of each core door. For colton-tools, the own fields are
   `ships_with`, `test_env` and `route`. The machine of each door,
   and groom's input, belong to the kit. Naming them is refused.
3. `:link`: name the doors the change link walks, over
   `link-defaults` (`:review-door :review`, `:return-door :return`,
   `:merged-door :complete`, `:close-change-door :close_without_pr`).
   Give `:change-kind` if the change kind has another name.
4. Each ending door's handler calls `release-the-waiters!`, so that
   an ask that ends releases the asks blocked on it.
5. Outside waits. Each wait that ends outside the house (a Jira
   transition, a Wiz finding, a deploy) is a `:waits` entry on the
   field that holds it. Like the change's wait on its checks, it
   names no kind, and its outcomes say what each ending does. Give
   the state it waits in an `:expected-age`, so the live pass files
   an aged note when the outside world goes quiet. Give the source
   that ends it a `:left-by` entry with `:by :wait` or `:by :mirror`,
   so the audit does not ask for a seat there.
6. Declare it: `(defresource ticket (ask/build ask/core
   extension))`.

**Check.** Write a test that builds a hand for
`factorykit.ask-contract` on a fresh engine and expects
`(failures new-hand)` to be `[]`. The namespace docstring names each
key of the hand, and `factory10.ask-contract-test` shows one built on
a memory engine. Then read `GET /api/-/cracks`. There should be no
new crack on the ticket kind. Last, walk one ask by hand: groom,
submit its change, merge it. The ask ends `done`.

## 5. The forge: Bitbucket behind `ForgeSource`

1. Implement `factorykit.forge/ForgeSource` for Bitbucket:
   `forge-poll`, `forge-log-tail`, `forge-calls`, `forge-checks` and
   `forge-base`. Add `ForgePull` (`forge-pull`), which the failing
   pass needs. `ForgeBudget` and `ForgeCommit` are
   optional. The protocol docstrings give the shape of each answer.
2. Answer each build as `{:check_name name :state raw}`, with
   Bitbucket's own word in `:state`. Do not translate it. Judge it
   with `(verdict {:forge :bitbucket :required … :advisory …}
   checks)` and keep no state set of your own.
3. `STOPPED` becomes `interrupted`: a build that died without a
   verdict. It gets a rerun and never counts as green. If the
   factory reads `STOPPED` as red or as failed today, that changes
   here. The build-state table (`resources/build-states.edn`) is the
   only place this reading lives.
4. Hand the pass its steps: `pass!` with the factory's own
   functions, as described in "The pass" in the reference.

**Check.** Write a test that calls `(conformance-failures judge-fn)`.
The `judge-fn` sends the Bitbucket cases through the adapter's own
path and hands the rest to `factorykit.forge/judge`. The test expects
`[]`. `factory10.forge-conformance-test` is the pattern. If Bitbucket
answers a state the table lacks, add it to both table files and add a
case to both conformance files in this repository, and bump the pin.

## 6. The repository policy: build from the base

1. The base holds two states (`active`, `retired`) and no tomb. It
   holds the stated fields `repository`, `branch_pattern`, `base`,
   `required_checks` and `rounds_per_change`, and three offered
   fields that the extension takes by name in `:takes`: `workspace`,
   `forge_repo` and `advisory_checks`. A Bitbucket factory wants
   `workspace`.
2. Give the factory's own stated fields as `:policy`, the fields the
   engine writes as `:engine`, and as `:fixed` the stated fields that
   are set once at create. The builder writes the create form,
   `restate`'s input and its prefill from that one list. Naming
   `:schema` or `:create-schema` is refused.
3. Put `a-person-or-their-delegate-states-the-policy` on the create
   and on `restate`, so that a seat cannot restate the policy it
   works under.
4. Declare it: `(defresource repo-policy (kit/build kit/core
   extension))`.

**Check.** Write a test that builds a hand for
`factorykit.repo-policy-contract` on a fresh engine, with the
extension's `:fixed` under `:fixed`, and expects `(failures new-hand)`
to be `[]`. `factory10.repo-policy-contract-test` shows one. Then
each live policy row reads back with the same values under the new
schema, and `GET /api/-/cracks` shows no new crack on `repo_policy`.

## 7. The seats

The kit ships seat templates as data in
`factorykit/resources/seats/`: `draft-judge`, `code-seat`,
`ops-seat` and `sitting-judge`. Each file is
`{:template {:params … :note …} :seat {…}}`. The `:seat` map is the
input to the seat kind's create door. Each `{{name}}` in it is a hole
that `:params` describes. Fill every hole before you create the
seat.

The code seat's charter and instructions are the ones this house
runs. The other three are written from the rules this house tuned on
2026-10-13:

- no search by title;
- full ids, never prefixes;
- the verdict input shape;
- `bench__read` with `offset` and `limit`;
- counts taken from the list the sit answered.

Compare each one with the live seat's row (`GET
/api/-/cracks/seats` in this house) before you trust it word for
word.

To create one in another house:

1. Read the file, replace each `{{hole}}`, and keep `:seat`.
2. Check that every kind its scope names exists in that house, under
   that name. For example, the ask kind may not be called `ticket`.
   Rename the kind in `:scope`, `:walk` and `:wakes`, the template's
   door subscriptions (the seat's `:wake_on` field is retired, 5e9203e2).
3. Set the house's own numbers: `cadence_seconds`,
   `budget_usd_per_week`, `sitting_budget_tokens` and
   `standing_ttl_seconds`. The templates carry this house's numbers.
4. A judge seat names a promoted judgment. Create and promote the
   judgment first, then fill its id into `{{judgment}}`.
5. Create the seat as a person: `waymark_invoke` with kind `seat`,
   action `create`, no id, and the filled map as `input`. If the
   audit warns that the seat opens a crack, read the crack. A crack
   that the step 1 to 6 declarations still owe is fixed there. Do
   not acknowledge it away.
6. Give the Routine that fires the seat the seat's name and its key.
   The instructions travel on the row, so the Routine's own prompt
   says only to read the fire text.

**Check.** `GET /api/-/cracks` lists no crack that it did not list
in step 0, apart from those already explained. Each seat's first
fired sitting closes with its row moved or a stall sentence a person
can act on.

## When you are done

- Every check above is clean, and the step 0 crack list is either
  gone or explained.
- The factory's change passes the change contract. Its forge passes
  the conformance cases. Its ask and policy load through the kit's
  builders.
- The live cracks pass has run once and filed nothing new.
