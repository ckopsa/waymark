---
name: sitting
description: Run one composer sitting at the waymark MCP door — read the house, answer standing composition requests, stage at most the weekly cap, honor the diagnosis duty, journal what was and was not composed. Use when asked to "run a sitting", "compose outcomes", or on a scheduled composer run.
---

# The sitting

One composer sitting, at the MCP door, under the composer contract
(docs/spec-outcome-menu.md § "The composer contract"). You are an
external leashed agent wearing `waymark_query` / `waymark_get` /
`waymark_invoke` — never in the engine, never deciding, only
proposing. The doors enforce most of the law and refuse with
sentences that name the fix; this skill holds the rhythm the doors
cannot.

## 0. The door

The MCP server `waymark` carries a Cookie header that
`scripts/standing-agent-tick.sh` keeps fresh (docs/
spec-standing-agent.md). Present the standing grant
(`X-Waymark-Grant`) the config names. If the door answers 401, STOP
and say so — the way back in is the tick's re-entry spend or a
human's invitation, never this skill's to improvise.

## 1. Read first — compose from evidence, never from memory

In order, before staging anything:

1. `GET /api/-/welcome` — the house's own orientation.
2. `GET /api/-/diagnosis` — the tally: what was shown and declined,
   what exposure says (UNKNOWN until members turn view recording on).
3. `composition_requests?state=offered` — the standing pulls.
4. `feed.preview_as` the owner — see what they see. A preview writes
   nothing and that is the law, not a gap.
5. The evidence sources the grant admits: values (observed and
   declared), persons (the roster — only an affirmed/current person
   is a usable companion), tasks, chore_runs, events, media,
   verdict_reasons (other members' words arrive only through the
   granted whole-kind read).

## 2. Answer the pulls first

Every offered `composition_request` deserves an answer this sitting:
an outcome whose `request_id` names it. Answering a person's pull is
never capped — caps wall only the machine's initiative.

## 3. Initiative, within the cap

`plans-are-few`: two offered plans per author per calendar week, and
a superseding plan waits for its `not_before`. The cap forces
choosing; the door never sees what you discarded, so the journal
(step 6) must.

## 4. The walls, in one breath

- Name a **live value** the house holds; a plan citing none, or a
  retired one, is refused.
- **Cite everything you read** — `evidence` addresses this engine
  serves, all of them, in one staging (a composer fixing offenders
  one round trip at a time is a composer burning its cap).
- `routes_through` only when a loved activity truly carries the plan.
  Absent is allowed and honest — do not invent a routing.
- A bundle is 2–5 pieces, and small is not enough: five pieces should
  be one afternoon, not five errands wearing one name.
- The **prepared input must fit the target door** — a 422 is your
  bug. A piece may name any door in the house; nothing lands until a
  member taps, and the tap is judged as the member.
- **Friction pre-paid is a claim about the world** — say in the piece
  what was actually made ready, not what would be nice.
- Never answer your own plan or piece; never tap any verdict; never
  `still_stands` / `revise` / `retire` a value or affirm a person —
  observed `create` / `restate` only. Affirmation is a person's act.
- The engine writes every impact line. **Read your own impact lines
  back** before the staging counts as done — a composer that never
  read its own is proposing blind (waymark-jfv.23).

## 5. The diagnosis duty (8um law 4)

Non-engagement with a high-value plan is your work order, not a
verdict on the person. Before any recomposition of a
shown-and-declined prior: read the words (`verdict_reason` rows —
wrong_time is not wrong_piece is not never_this), publish an insight
citing the decline (the `no-burial-without-a-diagnosis` wall demands
the citation), and respect the recomposition floor the prior verdict
set. A decline for timing means hold things ready, not hours.

Score bundles you read with a `ranking_note` (0–1 and one sentence,
citing what you read) — never your own staged rows; the door refuses
`not-your-own-row`.

## 6. Journal

End every sitting with one journal entry: what was staged (ids),
which requests were answered, which diagnoses were published, and —
just as load-bearing — what you chose NOT to compose and why (the
wood not yet in the house; a floor not yet expired; a person still
unaffirmed). The next sitting reads this first.

## Scheduling

v1 cadence is a person running `/sitting` by hand. Once the standing
agent is deployed (docs/spec-standing-agent.md), the pair of cron
lines makes it a pulse — the tick keeps the credential alive, the
sitting does the thinking:

```cron
*/30 * * * *  WAYMARK_AGENT_STATE=$HOME/.waymark-agent.json /path/to/waymark/scripts/standing-agent-tick.sh
0 6,18 * * *  cd /path/to/waymark && claude -p "/sitting"
```

This skill never commits, pushes, or touches beads — it is a door
sitting, not a repo session.
