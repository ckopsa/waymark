# Spec — Google Tasks as a TaskSource

**Thesis.** The queue already drinks from four authorities through one protocol.
Google Tasks is a fifth, and the only genuinely new thing about it is that it is
the **first source with a real incremental feed** — which makes it the natural
proving ground for `waymark-8si`, the mirror-cursor un-punt.

## Epistemic status

Unlike the six capability specs, this is an *integration*: no framework
invention, mostly translation. It earns a spec because two halves are already
built and the seam between them is easy to get subtly wrong (etags and
deletions, below), and because it aligns with a stated direction — external
integration is a growth direction for waymark, and the mirror is the substrate.

## What exists

- `workqueue10/confluence.clj` — the `TaskSource` protocol:
  `source-discover`, `source-pull`, `source-pull-many`, `source-push`. Each
  source speaks canonical; the push translation is shared, and only a local
  "done" has anything to say.
- `workqueue10/sources/homeassistant.clj` — **the template.** A non-waymark
  external API, list-scoped identity (`entity/uid`), a whole-list absence read
  as unreachability rather than deletion, and date-only dues widened to the
  day's closing midnight UTC.
- `calendar10/oauth.clj` — and this is the unlock: it is a *general* OAuth 2.0
  refresh-token grant in the shape of `oidc/client-credentials-fn`, not a
  calendar-specific thing. Refresh token in a nomad var, access tokens derived,
  short-lived, and held only in an atom; a refusal throws so an un-mintable
  credential reads as one unreachable feed.
- `scripts/gcal-refresh-token.sh` — mints a refresh token through the consent
  flow. **Hardcodes `SCOPE=…/auth/calendar` at line 27**; see prerequisites.
- The day-end widening law, already written twice (`sources/choreplan.clj`,
  `sources/homeassistant.clj`).

## The API, verified

From the Tasks v1 reference:

- A task carries `id`, `title`, `notes`, `status` (`needsAction` | `completed`),
  `due`, `completed`, `updated` (RFC 3339), **`etag`**, `deleted`, `hidden`,
  `parent`, `position`, `webViewLink`.
- **`due` records date only** — "the time portion of the timestamp is discarded
  when setting this field." Time of day cannot be stored or retrieved.
- `tasks.list` accepts `updatedMin`, `showDeleted`, `showHidden`,
  `showCompleted`, `maxResults` (default 20, **max 100**), `pageToken`.
- **There is no `syncToken`.** Calendar has one; Tasks does not. `updatedMin`
  is the incremental mechanism.

## The mapping

| Google Task | canonical task doc |
|---|---|
| `title` | `:title` |
| `notes` | `:detail` |
| `due` (date-only) | `:due_at` — the day's closing midnight UTC, the third writing of the chore-source law |
| `status: needsAction` | `:status "open"` |
| `status: completed` | `:status "done"` |
| `deleted: true` | gone (`{:status 404}`) |
| `webViewLink` | `:source_ui_href` — a real hop to the Google UI |
| list + id | identity `"gtasks:<tasklist>/<taskid>"` |

`due` being date-only is a gift, not a limitation: it is exactly the shape
`chore_run` and the HA lists already have, so the queue's ranking law needs no
new case.

## Two things that are easy to get wrong

**Etags must carry the translation revision.** Google mints a real per-task
`etag`, so unlike Home Assistant we need no content hash for *change*
detection — but `sources/waymark.clj` records the lesson that a change to
*our own mapping* must also invalidate stored rows, which a remote etag will
never do. Compose: `etag = google-etag + translation-rev`. HA got this free by
content-hashing; here it is a deliberate line of code, and omitting it means a
mapping fix silently never reaches stored rows.

**Deletions need three parameters together.** `updatedMin` alone hides
deletions and hides completed-then-cleared tasks. The discover call must send
`showDeleted=true`, `showHidden=true`, and `showCompleted=true`, then translate
`deleted`/`hidden` into gone. Miss this and rows quietly stop being reconciled
rather than being marked gone — the failure mode that looks like nothing at all.

## The incremental feed, and why it matters beyond this source

Every existing source polls: the waymark sources re-GET a filtered collection,
HA re-POSTs the whole list set. Google Tasks offers `updatedMin`, so discover
can ask **"what changed since my cursor?"** — the change feed `waymark-8si`
wants and no current authority provides.

Recommended shape: store the cursor as the max `updated` seen, and re-query
from `cursor − overlap` (60s is ample) to absorb clock skew and same-second
writes; dedupe by id on the way in, which the mirror's per-row observe already
tolerates. Do **not** store `now` as the cursor — Google's clock, not ours,
stamps `updated`.

This is worth building *as* the `waymark-8si` pilot rather than before it: one
real cursor-bearing feed teaches the framework more than a design doc.

## Push

Only a local "done" travels, per the shared push-plan. `PATCH` the task with
`{"status": "completed"}` under the etag we hold; a mismatch lands the queue row
`conflicted`, exactly as the other sources do. This requires the **read-write**
scope — `https://www.googleapis.com/auth/tasks`, not `tasks.readonly`.

## Prerequisites

1. **A refresh token with the Tasks scope.** The stored one was minted for
   calendar alone. `scripts/gcal-refresh-token.sh` needs its `SCOPE` to become
   an argument (space-separated scopes), and the household must re-consent once
   to mint a token covering both. This is an operator step, not a code step,
   and it is the only thing here that cannot be done from a keyboard alone.
2. A nomad var for the new refresh token, and the `WORKQUEUE10_GTASKS_*` env
   trio matching the existing source conventions.
3. A configured list set (`WORKQUEUE10_GTASKS_LISTS`), mirroring HA's
   `WORKQUEUE10_HA_LISTS` posture — the queue mirrors chosen lists, never
   "everything the account can see."

## Recorded punts

- **Subtasks.** `parent`/`position` express a hierarchy the canonical task doc
  has no room for. Flatten: a subtask becomes a task, its parent's title
  prefixed into `:detail`. Revisit only if the household actually nests.
- **Ordering.** `position` is Google's manual sort. The queue ranks by its own
  hub-local `:priority` and due date; imported position is dropped, not
  half-honoured.
- **`assignmentInfo` and `links`.** Ignored in the first cut. Note that
  assignment here is Google's own notion and does **not** map to a waymark
  `:member` — resist the temptation until `waymark-z61` decides how addressing
  works.
- **Creation from the queue.** Capture-to-Google (a task born here, pushed
  there) is the `create-push` law and is deliberately out of the first cut;
  `"todo"` remains the capture tag.
- **Quotas.** Unmeasured. `maxResults` caps at 100, so a large list is several
  round trips per discover; the incremental cursor is what keeps that bounded.

## Effort

**Medium** — comparable to the HA source, minus the OAuth work (already done)
and plus the cursor. The prerequisite re-consent is the long pole in wall-clock
terms, not in effort.
