# Spec — the ticket: work, as a row

**Purpose.** This document gives the requirements for the `ticket`
kind in factory10: one ask of the software factory, as a row a person
or a seat writes, and the queue the code seat walks. It replaces the
beads tracker for this repository. The owner's ruling, 2026-09-26:
"we need a waymark native way to capture work." A tracker beside the
engine is a second representation of the same problem, which is the
one thing the framework exists to refuse.

This document is written in ASD-STE100 Simplified Technical English.
Technical names from the codebase keep their spelling: kind, door,
walk, seat, sitting, change, grant, scope. "Must" gives a requirement.
"Can" gives a permission. "Is" gives a fact.

## 1. The problem on record

The house tracked its work in beads: 444 issues, in a Dolt database
beside the repository, with a JSONL export the sync workflow carried.
Three facts, all recorded:

*The tracker was not a row.* A seat could not walk it, a grant could
not scope it, the feed could not card it, and the audit did not see
it. The code seat walked `task` rows in a native `task_list` instead
(docs/routines/code-seat.md), which is a second queue for the same
work.

*The structure the work had lived only in beads.* The export shows
238 parent-child edges, 83 blocks edges and 39 discovered-from edges.
`task` holds none of them, because `task` is a mirror over the
family's authorities and its lifecycle is the authority's word
(workqueue10.resources.task).

*The sync cost a person.* Every session pulled, exported and
committed the JSONL, and the workflow imported it on a schedule. A
row in the engine needs none of that.

## 2. Decisions

**D-1** The ticket is a kind of factory10, beside `change` and
`ci_run`. It is the day job's work, not the family's, so it is
`:nav :secondary` for change's reason.

**D-2** An epic is a ticket with children. There is no `epic` type.
The tree is the `parent` ref, and the `children` link walks it down.

**D-3** There is no `in_progress` state. A ticket is in progress
when a `change` born from it is open, which the engine already
holds. A status a model sets and forgets is a fact the house cannot
trust.

**D-4** READY is the default filter and not a state. The collection
under `state=open` is the queue. A ticket that waits on other
tickets is `blocked`; one that waits on a day is `deferred`. Both are
out of the queue by construction, so a walker never reads a ticket it
cannot work (spec-seat.md R-12.9).

**D-5** The endings take a sentence. `complete` and `drop` demand
`close_reason`. The record the next reader has is that sentence.

**D-6** Reopen is a person's door. A reopen corrects an ending, and a
seat that could reopen tickets could refill its own queue. The guard
is not grantable.

**D-7** The code seat walks `ticket`. `task` stays the household's.
A change minted for a ticket carries `ticket:<id>` in `born_from`,
and the merge completes the ticket with the engine's own hand.

**D-8** A ticket is groomed before it is picked up. The owner's
ruling, 2026-09-26. A ticket is born `draft`, and `groom` is a
person's door (or a delegate's, acting for a person): a model alone
is refused, and the wall is not grantable. A seat that could groom
could fill its own queue. `ungroom` sends a ticket back, and
`restate` serves `draft` alone, so a groomed statement is what the
seat builds and changing it is ungrooming it. `reopen` lands in
`draft`: an ending that was wrong is an ask to read again.

## 3. Requirements: the kind

**R-3.1** The engine must serve `ticket`, in
`factory10/src/factory10/resources/ticket.clj`, with the states
`draft`, `open`, `blocked`, `deferred`, `done` and `dropped`, initial
`draft`, and no terminal state.

**R-3.2** A ticket must have these fields.

| field | type | meaning |
|---|---|---|
| `title` | string, 1 to 200 | what needs doing, in one line. The pull request's title when a seat builds it. |
| `detail` | string, up to 20000, optional | the how, and what done looks like. A seat reads it before it reads code. |
| `type` | enum `bug`, `feature`, `task`, `chore`; default `task` | what kind of ask |
| `priority` | int 0 to 4, default 2 | the queue's order. 0 first. |
| `repo` | string, optional | the repository, as GitHub spells it. The seat's bench filter names the same one. |
| `parent` | ticket ref, optional | the larger ask this is a piece of |
| `found_in` | ticket ref, optional | the ticket whose work surfaced this one |
| `bead_id` | string, optional | the id this ask carried in beads |
| `blocked_by` | list of ticket refs, default empty | the tickets that must end first. Written by `block` and `unblock`. |
| `defer_until` | date, optional | the day a deferred ticket returns. Written by `defer` and `resume`. |
| `close_reason` | string, up to 480, optional | how it ended. Written by `complete`, `drop` and `reopen`. |

**R-3.3** The birth door must take `title`, `detail`, `type`,
`priority`, `repo`, `parent`, `found_in` and `bead_id`, and nothing
else. A ticket is born a draft.

**R-3.4** The default filter must be `state=open`, and the default
sort `priority`, lowest first.

**R-3.5** `:over` must name `done` accomplished and `dropped` let go,
and name `draft`, `blocked` and `deferred` in neither.

## 4. Requirements: the doors

**R-4.0** `groom` (draft → open) and `ungroom` (open → draft) take no
input. `a-person-or-their-delegate-grooms` refuses a model alone and
is not grantable; its scenarios are check-tier.

**R-4.1** `restate` (draft → draft) takes the four stated fields
again, whole, and prefills them. A groomed ticket is not restated: it
is ungroomed first.

**R-4.2** `prioritize` (open → open) takes `priority` and prefills it.

**R-4.3** `block` (draft, open or blocked → blocked) takes
`blocked_by`, one to fifty refs, and REPLACES the list. It is one-way:
`unblock` lands in `open`, because stating what a ticket waits on is
part of grooming it. The guard
`the-blockers-are-open-and-not-itself` refuses a blocker that is the
ticket itself, that is not a ticket, or that has ended, and names
which.

**R-4.4** `unblock` (blocked → open) clears the list.

**R-4.5** `defer` (open → deferred) takes `defer_until`. `resume`
(deferred → open) clears it.

**R-4.6** `complete` (draft or open → done) and `drop` (draft or
open → dropped) take `close_reason`, one sentence. Both are one-way:
`reopen` lands in `draft`. A blocked or deferred ticket is unblocked
or resumed first. The guard
`children-are-finished` refuses either while a child is `open`,
`blocked` or `deferred`, and names how many.

**R-4.7** `reopen` (done or dropped → draft) clears `close_reason`.
`only-a-person-reopens` refuses every agent hand and is not grantable.
It reads nothing else, so its scenarios are check-tier; a child
reopened under an ended parent is the person's next tap (§ 8).

**R-4.8** The birth guard `the-parent-is-open-at-birth` refuses a
parent that is not a ticket or has ended.

**R-4.9** Every guard that refuses in words names its remedy. The
three that read other tickets — the birth's parent check, the
blockers check, and the children check on the endings — allow on a ctx with no `:read` or
`:find` hook, which is the render probe's posture (change's bench
walls).

**R-4.10** Nothing in the kind names a seat (spec-seat.md R-6).

## 5. Requirements: the import

**R-5.1** `scripts/beads-import.sh` must read a beads JSONL export
and make one ticket for each issue, through the doors and only the
doors: the create door, then `block` and `complete`. Every ticket is
born a draft, so the import starts no seat on anything; a person
grooms tickets into the queue afterwards. A deferred bead stays a
draft with its date in `detail`.

**R-5.2** Births go parents first, by the depth of the beads id, so a
child's `parent` resolves at the door. Endings go children first, so
a parent's `complete` finds its children ended.

**R-5.3** The import keeps: `title`; `detail` as the description with
the design, acceptance and notes under their own headings; `type`
from the issue type (epic and molecule → feature); `priority`;
`parent` from the parent-child edge; `found_in` from the
discovered-from edge; `bead_id`; the close reason as the ending's
sentence.

**R-5.4** A blocker that had closed in beads holds nothing and is
left out. A closed parent with an open child stays open, and the
door's refusal is printed: the record is what it is.

**R-5.5** The import is idempotent by `bead_id`. A run cut short is
resumed by running it again.

**R-5.6** After the import lands, `.beads/` is retired from the
repository, and CLAUDE.md's beads section is replaced by one that
says where the work lives: `/api/tickets`, and the connector's
`waymark_query` on `ticket`.

## 6. Requirements: the seat

**R-6.1** The code seat's `walk` is `ticket`. Its scope entry is
`{"kind": "ticket", "actions": ["complete"], "filter": {"repo": …}}`.

**R-6.2** The change born from a walked ticket carries `ticket:<id>`
in `born_from`. The merge completes the ticket with the engine's hand
and the sentence `Merged: <change_id>.`
(factory10.resources.change/complete-the-task-it-was-born-from).

**R-6.2a** The seat's sit reads the queue under `state=open`, so a
draft never reaches a worktree. Grooming is the one tap between an
ask and a build.

**R-6.3** A seat can create a ticket. A seat that finds work it must
not do files a ticket with `found_in` naming the ticket it was
working, which is what the reopen refusal tells it to do.

## 7. Acceptance

1. `make check-factory` passes with the kind in the registry: the
   usability battery holds nothing against it, every guard that
   speaks names its remedy, and the three check-tier scenarios hold.
2. `factory10.ticket-test` proves the envelope: the doors at each
   state for a seat, a person and the engine; the three walls that
   read other tickets, over a fake hook; the shape the walker reads.
3. The import runs against a dev engine on the 444-issue export, and
   the ledger says how many were born, blocked, deferred, completed,
   and held open by a door.
4. The code seat fires on a ticket, mints a change with
   `ticket:<id>`, submits, and the merge completes the ticket.

## 8. Recorded punts

- **Reopen under an ended parent.** `reopen` lands in `draft` and
  does not read the parent. A guard that did would take the door's scenarios out of
  the check tier, and the person-wall is the law the door is graded
  by. The birth door refuses the same shape, so the only way to
  reach it is a person's own tap, and the person's next tap fixes it.

- **Automatic unblock.** When a blocker ends, the tickets it blocked
  stay `blocked` until a person or a sweep unblocks them. The honest
  fix is a sweep that reads `blocked_by` and walks `unblock` when
  every blocker has ended, with the engine's hand. It is a bead of
  its own — a ticket, now.
- **Automatic resume.** A deferred ticket returns when a person
  resumes it. The same sweep can read `defer_until` against the
  clock.
- **A `changes` link.** `/api/changes?born_from=ticket:<id>` would
  show the ticket's pull requests. `born_from` is not filterable on
  `change` today.
- **Comments.** A ticket's conversation is a `thread` on the row,
  which exists (workqueue10); nothing here declares it.
