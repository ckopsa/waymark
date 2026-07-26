# Waymark 10 — what to build next

Six specs, written 2026-07-26 against the tree at `82f1829`. They share a
framing worth stating once, because it is what makes them *these* six rather
than a feature list.

## The framing

The worksheet is the reference feature here, and it is not "Excel." Strip the
codec away and it is:

> a boundary crossing modeled as a resource — staged as a row with its own
> machine, planned before it is applied, then applied through the target kind's
> **own declared actions**, so guards, audit and push all still hold.

Waymark's peculiar bet is that **the declaration is the only source**: the law,
the audit, the UI, the agent surface, and now the filter picker all derive from
one spelling. Every spec below is an unexercised consequence of that bet. None
of them requires a new dependency; four require no new storage at all.

## The six

| spec | one line | effort |
|---|---|---|
| [law sweep](spec-law-sweep.md) | which live rows does this law change break? | M |
| [time travel](spec-time-travel.md) | as-of reads, judged by the law of the day | S / M |
| [MCP surface](spec-mcp-surface.md) | any agent drives any engine, no glue | S–M |
| [refusal → plan](spec-refusal-plan.md) | a denial that names the path out | S |
| [traversal](spec-traversal.md) | one hop over a declared ref | M |
| [addressed notice](spec-addressed-notice.md) | the queue reaches a person | M |

## And one integration

[Google Tasks as a TaskSource](spec-google-tasks.md) (M) is a different animal —
no framework invention, mostly translation — but it is listed here because it is
the first authority to offer a **real incremental feed** (`updatedMin`, no
`syncToken`), which makes it the natural pilot for `waymark-8si`. The OAuth half
is already built and is not calendar-specific: `calendar10/oauth.clj` is a
general refresh-token grant.

## Ranking, and the reasoning

**Build first: the law sweep.** It is the only capability here that no other
engine can offer, and it is not ambition — it falls out of modelling law as
data, which waymark already paid for. `:propose` currently holds a diff nobody
can see the consequences of; the sweep is the missing half of a posture that
already ships.

**Highest reach per line: MCP.** Six tools over routes that already exist.
The risk is owning a second protocol's compatibility surface, not the code.

**Nearly free, and sitting there: time travel, tiers 1 and 2.** The log is
already written; nothing queries it. Tier 3 (data as-of) is a real decision
about bytes and is the reason that spec exists.

**Best small win: refusal → plan.** Days, not weeks, and it converts every wall
an agent hits into a task list. It also yields a declaration lint for free.

**Hold: traversal and addressed notice.** Both are good. Neither has a
complaint behind it yet. The worksheet earned its existence because paydesk's
assignment workflow *demanded* it; that is the bar. Traversal risks becoming a
query language; notice risks becoming a notification product. Ship them when a
real question or a missed chore forces the issue.

## Dependencies worth knowing

- **Time travel tier 2 and the law sweep share their whole mechanism** —
  `judgment.clj` serving a stored revision's guard trees. Build either and the
  other gets cheaper.
- **Addressed notice is blocked on the vocab assignees** (`waymark-aqj`,
  `waymark-i6i`). "Notify the assignee" is only expressible where the assignee
  is a ref to `:member` — which is true of `chore` and `task` today, and of
  nothing else.
- **MCP and refusal → plan compose.** A plan is most valuable to the caller
  least able to guess: an agent.
- **Traversal generalises the ref filter picker** shipped in `5ed1539` — a hop
  renders as a column, with no second UI concept.

## What is deliberately absent

Ideas considered and rejected, so they are not re-proposed:

- **Undo.** Already built — `:undo` pointers are declared, `:reversible` is
  derived from them, and the render layer resolves them by state.
- **Dry run.** Already built — `invoke!` honours `:dry-run` and `:dry-run
  :partial`, and `create!`/`bulk!`/`batch!` honour it too.
- **A second export codec** (CSV/TSV on the worksheet seam). Genuinely cheap and
  genuinely useful, but it is a feature, not a capability — it needs no spec,
  only an afternoon.
- **Replay-based history.** Rejected on the record in the time-travel spec:
  handlers reach the `ctx :create` door and push across process boundaries, so
  they are not replayable and should not be promised to be.
