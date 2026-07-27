# Spec — capture to Google Tasks

**Thesis.** The queue mirrors ten Google lists and can add to none of them.
Close the punt [the source spec](spec-google-tasks.md) recorded: a task born in
the queue, pushed to Google, its identity claimed back. The framework law
already exists and the transport is already proven.

## Epistemic status

This is a punt closing, not a design. `create-push` is a declared mirror law
(`waymark10/server/mirror.clj`), `task` already declares `:create-push true`,
Home Assistant already does exactly this, and `POST /lists/{id}/tasks` was
exercised against the live API during the If-Match probe on 2026-07-26. What
remains is one adapter, one routing decision, and one honest refusal about
time-of-day.

It was right to leave out of the first cut — pull-and-complete is most of the
value — but ten visible lists with no way to add to them is a lopsided place to
stop, and the household noticed within a day.

## What exists

- **The law.** `MirrorCreateAdapter/push-create [document] → [external-id etag]`.
  A kind declaring `{:create-push true}` may be born locally through its
  ordinary `:create-schema` / `:create-guards` / `:on-create`, and the
  post-commit pass pushes the exported document as a CREATE: the authority
  mints the external id, and `claim_external` (a sync write) stamps identity and
  etag onto the row. **A failed create push lands `conflicted` with NO external
  id**; `resolve_conflict keep=local` re-pushes, `keep=remote` refuses loudly
  because a locally-minted row has no remote truth to keep. An unclaimed local
  birth is invisible to pull-through and resync — nothing external names it yet.
- **`task` is already a create-push kind** (`resources/task.clj:245-246`:
  `:push-on-write true`, `:create-push true`).
- **The capture precedent.** `sources/homeassistant.clj` implements
  `push-create` against `todo/add_item`, and `WORKQUEUE10_HA_CAPTURE`
  ("todo.inbox") names where a birth lands.
- **The door that refuses today**: `:create-schema`'s
  `[:source {:optional true} [:maybe [:enum "todo"]]]`, with `:on-create`
  defaulting anything unsaid to `"todo"`.

## Google's create is the easy one

Worth stating plainly, because it inverts the usual expectation. HA's
`push-create` has to snapshot the list, add the item, re-read the list, and
difference — because `todo/add_item` does not return what it created. It throws
on ambiguity when two adds race, and that throw is load-bearing.

`POST https://tasks.googleapis.com/tasks/v1/lists/{tasklist}/tasks` returns the
created task **with its `id` and `etag`**, verified live. So `push-create` is:
one POST, read two fields, return them. No differencing, no ambiguity window,
no race. The external id is assembled on the confluence's own grammar:
`gtasks:<tasklist>/<taskid>`.

## The design

**Routing.** The confluence routes by the `:source` tag, so the create door's
enum widens: `[:source {:optional true} [:maybe [:enum "todo" "gtasks"]]]`.
Unsaid still defaults to `"todo"` — capture should not change meaning for
anyone who never asked for Google. This is a law change on the create schema;
like the last two it should need no migration, and the plan must be checked
rather than assumed.

**Which list a capture lands in**, in two layers:

1. `WORKQUEUE10_GTASKS_CAPTURE` names a default list id — the HA precedent
   exactly, and it keeps capture to one field.
2. The create door gains an optional `:task_list` ref. This is only expressible
   because lists became rows; the picker labels them by title. Given, it wins;
   absent, the configured default applies; neither, and a `gtasks` capture
   refuses with a guard that says so.

A `:task_list` naming a list whose `:source` is not `"gtasks"` must refuse —
capturing into a Home Assistant list through the Google door is a category
error, and the guard's `:explain` should say which authority owns that list.

**The time-of-day problem, and why it must refuse.** Google's `due` records
date only — "the time portion of the timestamp is discarded" — confirmed live:
a task created with `2026-08-01T00:00:00.000Z` echoes back with the time
zeroed. So a clock-timed capture bound for Google cannot round-trip: the local
row would hold 14:00, Google would hold the date, and the **next discovery pass
would rewrite the local row to the day's closing midnight**, silently moving
the user's time by ten hours.

Accepting and flooring it locally would agree with Google but discard what the
person typed without saying so. So: **a create guard refuses a `gtasks`-bound
capture carrying a clock-timed `:due_at`, naming `:due_date` as the remedy.**
The door already offers both affordances and already refuses when both are
named (the `one-due` guard); this is its sibling. A midnight `:due_at` is
indistinguishable from a date and may pass.

## Probed live, 2026-07-26

Against a throwaway list, deleted afterwards:

| question | answer |
|---|---|
| does `tasks.insert` return the identity? | **yes** — `id`, `etag` and `webViewLink` in the create response |
| does `due` survive the insert? | **yes** — sent `2026-08-01T00:00:00.000Z`, echoed identically |
| does `POST` work against `@default`? | **yes** — so the capture list needs no hard-coded id |

So Google's side of the round trip is honest; the only arithmetic that needed
fixing was ours (see the write-direction note below).

## The write-direction due, which this spec originally missed

`day-end` stores a day-granular due as the day's **closing** midnight, so "due
Aug 1" lives as `2026-08-02T00:00:00Z`. Taking that instant's own date sends
Google Aug 2, and the first pull reads the deadline back a day late — silently,
and in the opposite direction from the discard this spec does warn about. The
write translation must step back before taking the date. Caught in review, not
by this document.

## Recorded punts

- **Moving a task between lists.** Google has a `move` endpoint; the queue has
  no verb for it. Out of scope — the list is set at birth and by the authority
  thereafter.
- **Subtasks and position.** Already punted by the source spec; a capture is
  always top-level, always at Google's default position.
- **Editing a captured task's title or notes** from the queue. Push still
  carries only Done, per the shared push-plan. Capture is a birth, not an edit
  channel.
- **A capture whose list was deleted upstream** between the pick and the push
  fails the POST and lands `conflicted` with no external id — the law's own
  path, and `keep=local` retries against a list that is still gone. Worth one
  sentence in the guard's prose rather than machinery.

## Tests this needs

Against the fake transport, not the network: a capture routed by `:source`
lands in the configured list; an explicit `:task_list` overrides it; a
non-gtasks `:task_list` refuses; a clock-timed `:due_at` refuses and names
`:due_date`; a successful push claims `external_id` on the confluence grammar
plus the etag; a refused push lands `conflicted` with no external id and
`keep=local` re-pushes; and — the duplicate-birth trap — a claimed row is NOT
re-minted by the next discovery pass that sees it.

## Effort

**Small-to-medium.** One `push-create`, one env knob, one enum widening, two
guards, and the tests above. The transport is proven and the law is written;
the only genuinely new prose is the two refusals.
