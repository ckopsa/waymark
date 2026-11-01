# Spec — the change: built from integrations, the Mirror, door subscriptions and leases

**Purpose.** This spec gives the change's next design. Today one
poll (the forge pass) and one sweeper (the merge pass) carry every
move a change makes after its seat submits. This spec moves those jobs
onto primitives the engine already has or already specifies: the
`integration` and `:on-event` (docs/spec-integrations.md §2–3), the
Mirror (`waymark10.server.mirror`), the door
receiver of a subscription (docs/spec-events.md §4.2), the lease
(docs/spec-leases.md) and the held call. It does not restate those
specs. It decides what is left: the split of the row, the job table,
the target of each job, the migration order and the requirement ids
the code pieces cite.

Ticket: d876fe6d, piece 1 of 11 of the change epic f29588bf. It
merges as a document, before any code piece is groomed, as
docs/spec-seat.md §22 did for the seat epic.

This spec is written in ASD-STE100 Simplified Technical English.
"Must" gives a requirement. "Can" gives a permission. "Is" gives a
fact. Requirement ids are R-C.n; a code piece cites the ids it builds.
Line numbers drift; find each function by name.

## 0. The scene

**Before.** A seat submits, and the change shows `#—` until a poll a
few minutes later sees the pull request. Checks go red, and nothing
happens until the next pass reads them. The house decides that a
change is held, but no row records when or why, and no seat can hear
it. A change gets stuck, and it sits in a list the mayor recites every
hour, because only a person can move it and nobody asks them.

**After.** GitHub tells the house the moment the pull request opens
or a check finishes. The change moves through its own doors, and each
hold, release and verdict is a transition that any seat can subscribe
to. A stuck change puts a held call in front of the owner with the
choices on it, and the mayor's hourly list is gone.

## 1. Decisions on record

### 1.1 Split the change and the pull request (owner, 2026-10-25)

**Decision.** `pull_request` is a new kind and a pure Mirror row: it
holds GitHub's truth and nothing else. `change` stays the house's
work row. A change follows its pull request through door
subscriptions.

**Why.** The Mirror's machine IS the sync machine (`fresh`, `stale`,
`unreachable`, `conflicted`); its pull-through, its discovery, its
advance doors and its heal all assume that. A change's machine is the
work (`open`, `submitted`, `failing`, `stuck`, `merged`, `closed`),
and its doors carry the ticket's handlers. Two machines need two rows.
Each row then keeps one writer per fact: GitHub (through the Mirror)
writes the pull request, and the house writes the change.

**What it rules out.** One kind with the Mirror's sync machine beside
a workflow machine. The engine has no second machine on a row, and the
change's doors would have to read sync state they do not own. It also
rules out the `ci_run`-style "mirror by hand" that `change` is today
(factory10.resources.change, "A MIRROR, NOT A FORM").

### 1.2 The poll stays as a heal until piece 11

**Decision.** Each piece moves one job onto its target and leaves the
old path running beside it. The old path must be idempotent against
the new one (a door the new path already walked refuses, or is a
self-loop that writes nothing). Piece 11 removes the change jobs from
the forge pass. Until then a lost webhook or a stopped consumer costs
one cadence, as today, and never a change.

### 1.3 Jobs that are not the change's stay in the forge pass

The base pass, the groom floor, the findings and film of an org plan,
the source note and the pass stamp read the forge but move no change.
This epic names them in the table (§2) and does not move them. A later
epic can.

## 2. Today, read at main

The forge pass is `factory10.sources.forge/pass!`. It is a call to the
kit's `factorykit.forge/pass!` with factory10's steps as functions
(docs/factory-kit.md, "The pass"). It runs every
`default-every-seconds` (300). The merge sweeper is
`factory10.bench/start-enrol-sweeper!`. It runs `enroll-unenrolled!`,
`merge-green!` and `ask-for-merges!` on its clock, and `merge-green!`
alone between beats when `house-wakes` holds a due repository
(`house-beat!`). One process per database holds each loop.

"Door" is a transition through `inv/invoke!` as
`factory10.mirror/source-principal`. "Maintenance write" is
`bench/mark-row!`: a data write with no transition and no log entry.
"Atom" is memory in the holder process; a restart loses it.

| # | Job | Function | Reads | Writes | Through |
|---|---|---|---|---|---|
| J1 | The poll | kit `pass!` → `forge-poll` | the pull requests that moved since the cursor; rate limit (`ForgeBudget`) | the source's place (`ForgeCommit`) | atom / source state |
| J2 | Mint, adopt, move | done (plan 85b4462e): the change pass is deleted and the poll writes no `change` row; kit `move-change!` and `link-change!` remain for the link pass | — | — | — |
| J3 | Re-read the unknown | done (ticket 79b4b61a): deleted; the Mirror's `pull_request` refresh past TTL carries it, `head_moved` → `refresh_head` | — | — | — |
| J4 | Red runs | kit `runs-pass!` over `run-hand` | the failed check runs on a live head; the log tail | a `ci_run` at `red`; `supersede` on a run whose head moved | door |
| J5 | Labels | `label-pass!` | each `classified` `ci_run` with no label | a label at GitHub (`forge-label!`); `stamp_label` | wire + door |
| J6 | The verdict | the `change` kind's own doors on its pull request's `checks_red` and `checks_green` (plan 85b4462e, D2b); the kit's failing pass is deleted (D2c) | the checks on each `submitted`/`failing` head against the policy's required checks; `mergeable`; the landing; the runs (`forge-runs`) | `fail`, `recover`, `stick`; a re-run (`forge-rerun!`) with `rerun_head`/`rerun_note` | wire + door |
| J7 | Staleness | done (ticket a414339b): `observe` is deleted; the Mirror's `pull_request` refresh writes `behind_base` and `base_compared_at`, and `follow_facts` carries them to the change | — | — | — |
| J8 | Adoption note | done (ticket a414339b): `adopt` and `note_adoption` are deleted; `opened` → `link` gives a change its pull request | — | — | — |
| J9 | Reader sweep | `sweep-reader-changes!` | each `open` change no code seat can end, or abandoned; each `stuck` change whose stall built nothing | `close_without_pr` (`close-as-engine!`) | door |
| J10 | Source note | `source-note-pass!` | the poll's refusals per repository | `note_source` on `repo_policy` | door |
| J11 | Base | `base-pass!` → `base-move!` | the base head and its checks (`forge-base`) | `repo_policy` base fields; a fix `ticket` opened, noted, completed | door + maintenance write |
| J12 | Groom floor | `floor-pass!` | the open queue per policy | a draft `ticket` | door |
| J13 | Findings | `findings-pass!` | the org plan's artifact at its change's head | `org_plan` doors | door |
| J14 | Film | `film-pass!` | the take report | `first-week/land!` on `org_plan` | door |
| J15 | Stamp | `stamp-pass!` | the repositories the pass ran over | `forge_pass_at` on `repo_policy` | maintenance write |
| J16 | Census | `note-census!` | the census | the beat ticket's note | door |
| J17 | House merge | `merge-green!` | each `submitted` change with a number and a head; policies; `merge-holds`, `lane-holds`, `deploy-held`, `go-live-waits` | ONE rig `merge` call; `update_branch`; `org_plan` `head_moved` | wire (engine hand) + door |
| J18 | Refused heads | `merge-green!`'s `seen` | the rig's refusal | change id → refused head; `[:updated id]` | atom |
| J19 | Conflicts | `mark-conflicts!` | the rig's "not mergeable" | `mergeable: conflicted` on the change | maintenance write |
| J20 | The line | `mark-lines!` | the offered changes in order | `line_place`, `line_why`, `line_reason` on the change; the front on the policy | maintenance write |
| J21 | Merge ask | `ask-for-merges!` | each clean change a person merges; `held_since`; `merge_wait_seconds` | one merge ask (a held call); `waiting` | held call + atom |
| J22 | Wakes | `house-wakes`, `due-wakes!` | the mirror's moves per repository | the pending set; the debounce stamp | atom |

`change`'s `:handlers` (factory10.resources.change, `factory10s-own`)
are the ticket's part of the core doors: `merge` completes the ticket
it was born from; `close` sends the ticket back; `fail` returns it to
the seat; `recover` clears `failing_checks`; `stall` shelves the
ticket; `unstick` and `unstick_submitted` put it to work again;
`close_without_pr` says why it was let go; `reopen` clears what the
close wrote. These handlers stay. Only the hand that walks the door
changes.

**The stuck crack.** `stuck` is not an ending (`:over` does not name
it). Its only exits are `unstick` and `unstick_submitted`, a person's
or a delegate's. `:expected-age` gives it a day. The cracks audit
reads it as `person_only`, and no row tells the person: the mayor
reads the list aloud every hour.

**A stall that built nothing lets go** (ticket 35cc04ea). A change a
seat stalled with no pull request, zero rounds and a clean worktree is
closed through `close_without_pr` by the reader sweep (J9) on the next
pass, and at boot for the ones already standing. Its ticket is already
in draft with the stall's `why` as `shelved_because`, through the
`stall` subscription. A stall with a pull request or with edits stays
`stuck` for a person.

**A deferred ticket lets its empty change go** (ticket 54262331). The
`defer` handler closes, in the same transaction, each change born from
the ticket that is `open` or `stuck` with no pull request, zero rounds
and a clean worktree. The reader sweep (J9) closes such an open change
beside a deferred ticket at once, so the boot folds the ones already
standing. A shelve is the stall's fold above; a drop closes its
changes as an ending does.

**An epic is not claimed.** A ticket with children is not groomed
(`a-ticket-with-a-child-is-not-groomed`), so it never enters the
queue a seat claims from, and a ticket that gains a child closes its
stuck changes (`close-the-parents-stuck-changes!`).

## 3. The split

**R-C.1** A kind `pull_request` must be declared with the Mirror
(`{:mirror {:adapter … :ttl-seconds … :discover-every …}}` through
`waymark10.server.mirror`'s `declaration` helper). Its machine is the
sync machine and nothing else. Its `external_id` is
`<owner>/<name>#<number>` and is unique.

**R-C.2** These facts must move from `change` to `pull_request`, and
the Mirror's adapter alone writes them:

| Field | On `pull_request` |
|---|---|
| `repository`, `number`, `url` | identity; `external_id` is built from the first two |
| `title`, `author`, `draft`, `labels` | as GitHub says |
| `base_branch`, `head_branch`, `head_sha` | as GitHub says |
| `mergeable`, `review_state` | as GitHub says |
| `files_changed`, `lines_added`, `lines_removed`, `touched_paths` | as GitHub says |
| `missing_checks`, `missing_checks_head`, `behind_base`, `base_compared_at` | read at the head (J7) |
| `gh_state` (new) | `open`, `merged` or `closed`: GitHub's state is data here, because the machine is the sync machine |
| `checks` (new) | the required checks' conclusions at `head_sha`, and `checks_verdict`: `pending`, `green` or `red` |
| `merged_at`, `closed_at` (new) | as GitHub says |

**R-C.3** These stay on `change`, written by the house's doors only:
`change_id`, `born_from`, `repository`, `base_branch`, `head_branch`
(the bench's branch), `author` (the seat), `title`, `rounds`,
`worktree_dirty`, `stall_reason`, `stalled_at`, `stalled_by`,
`failing_checks`, `conflicts`, `landing_error`, `rerun_head`,
`rerun_note`, `train_red`, `train_red_head`, `unadopted_since`,
`held_*`, `green_head`, `last_round_kind`, `branch`,
`line_place`, `line_why`, `line_reason`, `lane_released`,
`superseded_by`, `domain`, `requested_by`.

**R-C.4** `change` must get a field `pull_request`: the id of its
`pull_request` row, or nil before the pull request opens. It is set
by one hidden door, `link` (R-C.11), and never by `observe`. A
`pull_request` row has at most one change; the field is unique.

**R-C.5** `change`'s `number`, `head_sha` and `mergeable` must stay
readable on the change while any reader needs them (the summary,
`:label-template`, `merge-green!`, the seat's feedback). From piece 6
they are computed from the linked `pull_request`, not stored. Until
then J2 keeps writing the stored copy.

**R-C.6** A pull request with no change (a person's pull request in a
repository with an active policy) must be a `pull_request` row and no
`change`. Today J2 mints a `change` for it. From piece 6 it does not;
a `pull_request` with no change is GitHub's work, not the house's.

**R-C.7** Which change doors become subscription receivers:

| Change door | Heard on `pull_request` |
|---|---|
| `link` (new; replaces `adopt` for identity) | `opened`, matched by `repository` + `head_branch` |
| `merge` | `merged` |
| `close` | `closed` |
| `reopen` | `reopened` |
| `fail` | `checks_red` (R-C.9), from `submitted` |
| `stick` | `checks_red`, from `submitted` on the last round |
| `recover` | `checks_green`, from `failing` |
| `supersede`, `fold` | stay the house's (the merge handler of a sibling), not the pull request's |

`observe` on `change` is retired at piece 11: its facts live on
`pull_request` (R-C.2).

## 4. The target

### 4.1 Inbound: the GitHub integration

**R-C.8** One `integration` row, `github`, must receive GitHub's
webhooks (`pull_request`, `pull_request_review`, `check_run`,
`check_suite`). `pull_request`'s `:on-event` entries must name a
refresh door, not a write of the payload: the event says "this moved",
and the Mirror's adapter reads the truth. So the Mirror stays the one
writer, and an event that arrives out of order costs nothing.

- `pull_request.*` matches `external_id` from `repository.full_name`
  and `number`.
- `check_run.completed` and `check_suite.completed` match `head_sha`
  and `repository`; more than one row is `ambiguous` by the
  integration spec, which is correct for a head two pull requests share.
- `pull_request.opened` for an id no row holds must mint the row.
  `:on-event` today finds a row and never mints. The entry must name
  the Mirror's discovery for one id (`:discover true`), which mints
  through `discover!`'s ordinary create and fills it by pull. This is
  the one widening of `:on-event` this epic needs.

### 4.2 The Mirror's advance doors

**R-C.9** `pull_request` must declare `:advances` doors, so a
subscriber reads a sentence and not an etag move:

| Door | Opens when the pulled document … |
|---|---|
| `opened` | is a mint by an event or by discovery of an open pull request whose `head_branch` some change names |
| `head_moved` | carries a `head_sha` other than the stored one |
| `checks_red` | has `checks_verdict` `red` at `head_sha`, and the stored one was not |
| `checks_green` | has `checks_verdict` `green` at `head_sha`, and the stored one was not |
| `merged` | has `gh_state` `merged` and the stored one was not |
| `closed` | has `gh_state` `closed` and the stored one was `open` |
| `reopened` | has `gh_state` `open` and the stored one was `closed` |

A `:field` entry opens a door on a later instant. These doors open on
a moved value: an `:advances` entry may give `:when`, a predicate
`(fn [stored pulled] …)` over the stored row's data and the pulled
document, beside or instead of `:field` (both given, both must hold).
The doors open after `observe_external`, and only on a changed pull.
A discovery mint opens no advance door except one whose entry says
`:on-mint true` — `opened` — and the mint rule of `:advances` stands
for the rest. The advance beat reads `:field` alone; a `:when` is
judged on the whole pulled document.

**R-C.10** The verdict rule of J6 must move to the adapter: required
checks from the repository's active policy, a dead run (cancelled,
timed out, failed before a test step) is `pending` and not `red`, and
`conflicted` is `red` as `merge-conflict`. The landing failure
(`landing:<step>`) is the bench's fact, not GitHub's; it stays a
change door (`fail`, walked by the submit's own handler).

### 4.3 Door subscriptions

**R-C.11** The door receiver (spec-events E-3.3) must be opened for
`change`'s `link`, `merge`, `close`, `reopen`, `fail`, `recover` and
`stick`. Today a door receiver names one fixed row and only
`seat.fire`. These entries are one per kind, not per row, so the
receiver must take `id_from`: a path in the moved row (`pull_request`)
or a match (`{:pull_request (self)}` on `change`) that names the target
row. No target row is a `:skip` (E-3.8), not a refusal.

**R-C.12** These subscriptions must be managed: the factory declares
them with the kind, the engine restates them on boot, and their owner
is the `github` integration principal, so E-3.4's grant check reads
that principal's grant. A person's or a seat's move on a change is
logged as theirs; the subscription's move is logged as
`integration:github` through the door it heard.

**R-C.13** Every door R-C.11 names must be idempotent against the
old path. A `merge` the poll already walked must refuse with
`wrong_state` (a `:skip` on the subscription); a `fail` on a head the
change already failed at must write nothing.

**R-C.14** Every hold the house decides must be a transition on the
change, so a seat can subscribe to it: `hold` (with `held_reason`:
`merge_holds`, `lane`, `deploy`, `go_live`, `person`), `release`, and
`line` (place and why). They are self-loops on `submitted` and
replace J19's and J20's maintenance writes. A line that did not move
walks no door.

### 4.4 Outbound: row-recorded asks

The engine's own hand calls the rig. Each call is recorded on the row
by a hidden door that only the engine can walk, with the head it asked
at. A restart reads the row and does not ask a second time for the
same head.

**Decision (the owner, Oct 26).** These doors are not `:effect` doors.
An `:effect` door calls a power through an integration's grant
(`effect_runner.clj`). The rig's `bench__merge` and `update_branch`
are named by no powers entry (`factory10/src/factory10/bench.clj`), so
no grant can admit them, and they must stay out of every grant. The
spec can revisit this only when something other than the engine must
merge.

**R-C.15** The house's merge must be the hidden, engine-only change
door `merge_asked` (ticket 1ed59295): a self-loop on `submitted`. The
merge pass calls the rig, then walks `merge_asked` with
`merge_asked_head` (the head it asked at) and, for a refusal that a
new pass cannot fix, `merge_refused` (why) and `merge_refused_as` (the
refusal name). The change stays `submitted`; GitHub's `merged` event
walks `merge`. J18's `seen` atom is thus the row's `merge_refused`
field, and the park survives a restart. The park counts only while
`merge_asked_head` is the change's head.

**R-C.16** `update_branch` (J17) and the re-run (J6) must be hidden,
engine-only change doors of the same shape (ticket a1a133ca):
`update_asked` writes `updated_head`, and `rerun_asked` writes
`rerun_head`. The engine asks again only when the head moved. The
label push (J5) is already a door: `label-pass!` (forge.clj) walks
`stamp_label` on `ci_run`.

### 4.5 Leases

**R-C.17** The front of each lane's merge line is read from the
ledger, not held as a lease: it is the change whose last `line`
transition placed it 1 (`line_place`, with `line_why` and
`line_reason`). `merge-lines` and `front-of` compute it each pass.
No lease is held, for two reasons. `line-marks` gives place 1 once
per lane (ticket 55d584f2), and lanes are not rows, so one lease per
repository cannot hold the fronts. And `work-lines!` picks the front
with `front-of` after the pass's merge answers, so a lease would be a
stale second copy that a change gone red in the same pass keeps
holding. The spec may revisit this if lanes become rows.

**R-C.18** The sweeper's election (one process per database) stays a
job lease, as today. It is not this epic's.

### 4.6 Held calls

**R-C.19** The merge ask (J21) must be a held call raised by a change
door, `ask_merge`, from `submitted`, keyed by head. Its wait starts at
the transition, so `held_since` and the `waiting` atom go. A person's
yes lets the engine ask the rig to merge (R-C.15), recorded on
`merge_asked`.

**R-C.20** `stick` must raise one held call to the repository's owner
(the policy's person) with the choices on it: `unstick_submitted`,
`unstick`, `close_without_pr` (no pull request) or `close` (one). The
call carries `failing_checks` and the last round's sentence. Its
answer is the person's own door, walked as them. The cracks audit then
reads `stuck` as `person_told`, and the mayor's hourly stuck list
must be removed in the same piece.

### 4.7 The job table, again

| # | Job | Target primitive | Door it moves |
|---|---|---|---|
| J1 | The poll | integration `:on-event` (R-C.8); Mirror discovery as heal | `pull_request` refresh |
| J2 | Mint, adopt, move | Mirror discovery + advance doors (R-C.9) + door subscriptions (R-C.11) | `change` `link`, `merge`, `close`, `reopen` |
| J3 | Re-read the unknown | Mirror refresh past TTL — done (ticket 79b4b61a) | `pull_request` refresh |
| J4 | Red runs | stays (ci_run is not this epic's); its trigger becomes `checks_red` | `ci_run` create, `supersede` |
| J5 | Labels | stays a door (R-C.16) | `ci_run` `stamp_label` |
| J6 | The verdict | adapter rule (R-C.10) + advance doors + subscriptions; re-run as an engine-only row-recorded ask (R-C.16) | `change` `fail`, `recover`, `stick`, `rerun_asked` |
| J7 | Staleness | Mirror refresh (R-C.2) | `pull_request` refresh |
| J8 | Adoption note | `opened` → `link` | `change` `link` |
| J9 | Reader sweep | stays | `close_without_pr` |
| J10–J16 | Not the change's (§1.3) | stay | — |
| J17 | House merge | engine-only row-recorded asks (R-C.15, R-C.16) | `change` `merge_asked`, `update_asked` |
| J18 | Refused heads | the ask recorded on the row | `merge_asked_head`, `merge_refused`, `merge_refused_as` fields |
| J19 | Conflicts | Mirror (`mergeable` on `pull_request`) | — |
| J20 | The line | doors (R-C.14); the front is `line` place 1 per lane (R-C.17) | `change` `hold`, `release`, `line` |
| J21 | Merge ask | held call (R-C.19) | `change` `ask_merge` |
| J22 | Wakes | door subscriptions on `pull_request` | the subscribing door |

## 5. Migration order

Each piece is one ticket under f29588bf. Each names what keeps working
while both paths run.

1. **This spec.**
2. **`pull_request` kind and adapter** (R-C.1, R-C.2). The adapter
   reads through the `ForgeSource` verbs the forge pass already uses.
   Discovery covers the repositories with an active policy.
   *Both paths:* nothing reads the new rows yet; the forge pass is
   unchanged.
3. **The link and the backfill** (R-C.4, R-C.5). `change.pull_request`
   and its `link` door. A one-time pass mints a `pull_request` by
   `discover!` for each change with a `number`, and links it; J2 links
   each change it mints or adopts from then on. A change with no number
   stays unlinked until its pull request opens.
   *Both paths:* the stored `number`, `head_sha` and `mergeable` stay
   written by J2.
4. **The GitHub integration** (R-C.8). Events refresh `pull_request`
   within seconds, and `opened` mints.
   *Both paths:* the forge pass still moves every change; only the
   `pull_request` rows are faster.
5. **The door receiver widened** (R-C.11, R-C.12, R-C.13) and the
   `:advances` predicate (R-C.9). Engine work, with its own tests.
   *Both paths:* no factory subscription exists yet.
6. **The change follows its pull request**: subscriptions for
   `opened` → `link`, `merged` → `merge`, `closed` → `close`,
   `reopened` → `reopen` (R-C.7), and R-C.5's computed fields and
   R-C.6.
   *Both paths:* J2 still runs; on a linked change its state doors
   refuse `wrong_state` because the subscription walked them first,
   and J2 still mints an unlinked change.
7. **The verdict** (R-C.10): `checks_red` → `fail`/`stick`,
   `checks_green` → `recover`.
   *Both paths:* J6 still runs and finds nothing to move on a change
   the subscription moved.
   *Landed, first half (ticket 04b740e6):* `pull_request` carries
   `checks_verdict`, `failing_checks` and `checks_head`, and
   `checks_red` and `checks_green` open on a changed pull. No
   subscription walks them yet; the change-side half (ticket
   995570f0) does.
8. **The asks on the row** (R-C.15, R-C.16): merge, update, re-run.
   The label push is already a door.
   *Both paths:* `merge-green!` still calls the rig, and records each
   ask on the row; the recorded head keeps a restart from asking
   twice. *Landed:* `merge_asked` (ticket 1ed59295). `update_asked`
   and `rerun_asked`: ticket a1a133ca.
9. **The line as doors** (R-C.14, R-C.17). J18–J20's atoms and
   maintenance writes go; the front is each lane's `line` place 1,
   computed each pass by `merge-lines` and `front-of`.
   *Both paths:* `merge-green!` still chooses the order; it writes it
   through `line` and `hold`.
10. **The held calls** (R-C.19, R-C.20). The merge ask and the stuck
    call; the mayor's hourly stuck list is removed.
    *Both paths:* `ask-for-merges!` raises through `ask_merge` until
    this piece removes it.
11. **The poll retires its change jobs.** J2, J3, J6, J7 and J8 leave
    the forge pass; `observe` on `change` is retired. The Mirror's
    `:discover-every` (hourly) and its TTL are the heal. J4, J9 and
    J10–J16 stay in the pass.

## 6. Not in this spec

- `ci_run` stays as it is, a mirror by hand. Its red runs keep their
  classifier and their seat.
- The org plan's findings and film, the base, the floor and the stamp
  (§1.3).
- The bench's own doors (`submit`, `discard`, `stall`, `rebranch`) and
  the rig. They do not change.
- A forge other than GitHub. The adapter speaks `ForgeSource`, so a
  second forge is a second adapter and a second integration.
