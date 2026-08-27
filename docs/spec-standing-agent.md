# The standing agent (waymark-53u)

The first MCP dogfood measured the disease: the 8-hour agent session
and the 24-hour grant leash each expired before the loop closed, and
recovery needed a fresh human invitation. For a standing agent that
compounds to daily human intervention — the composer had no pulse.

The leash philosophy was never the problem, so nothing here loosens
it: **every credential still expires, every renewal is audited, and
scope never widens without a human verdict.** What lands is the
RENEWAL machinery, three pieces.

## 1. The renew loop (existing door, new client)

`POST /auth/agent/renew` with a live session cookie slides the
session — that door predates this spec. What was missing is the
client that ticks it: `scripts/standing-agent-tick.sh`, run from cron
or a systemd timer well inside the session TTL. It renews, rewrites
the MCP config's Cookie header, and handles the two failure rungs
below.

## 2. The standing rotation of the way home

The homecoming credential (waymark-4zj) was human-mint-only:
`offer_reentry`, a recovery-admin's deliberate handoff, one hour at
most. That mint boundary stands untouched — **an agent still cannot
choose to mint its own way back in.** What is new is the engine's
rotation at the auth doors, the concealed registrar action
`:rotate_reentry` on `:member`:

- **at bind** (`POST /auth/agent?invite=`) — best-effort; the durable
  guard refuses guest rows, so only an IdP-backed agent walks away
  holding a way home;
- **at homecoming** (`POST /auth/agent` with the token in the body) —
  the spend nulls the presented token, the rotation mints its
  replacement, and the loop closes with no human hand;
- **at renew** — only past half-life, so an hourly tick writes the
  member row every few days, not every tick.

Why this is not self-escalation: the engine rotates only at the
moment the agent presents a still-live credential whose chain of
custody began in a human act (the invitation, or a recovery-admin's
hand), and each mint *replaces* the prior token — at most one live
credential per member, so rotation renews what a human handed out
and never widens it. The standing TTL is seven days
(`reentry-standing-ttl-seconds`), its own constant beside the
one-hour handoff ceiling: a handoff is a moment, a standing
credential is a season. Suspend still revokes; the raw token still
never persists outside the row's `:secret` field; each mint is one
audited transition row.

The bootstrap ceremony, once: a recovery-admin offers re-entry to the
agent's durable member row (`offer_reentry`, the existing door); the
agent comes home through it and rotation self-sustains from there.

## 3. The anchored extend-ask, filed before expiry

"An approved follow-up ask can always extend" was already the grants
machine's law — an anchored `approval_request` (grant_id names a
grant its requester holds) extends that grant in place on approval.
Nothing filed it. Now the tick does: inside the ask window
(default 12h before grant expiry), with no prior ask of its own still
open, the agent files the anchored ask — same scope, more time — and
the human's tap moves from "re-invite the agent" to "approve the ask
in the feed". The asking door was always open to a named principal;
scope still only widens through a human verdict.

## The sitting rides the pulse

The tick keeps the credential alive; it never thinks. The thinking is
`/sitting` (.claude/skills/sitting/SKILL.md) — the composer contract
as a runnable walk: read the house, answer standing composition
requests, stage every distinct bundle the evidence supports (ranked,
not capped), honor the diagnosis duty, score what it did not write,
journal. Scheduled beside the tick (the skill's own cron pairing),
the two lines are the whole automated composer: outcomes generate on
a cadence, and every one still lands as a proposal a person answers.

## The bearer-only runner (Jules and kin)

A cloud runner has no cron, no state file and no session cookie — it
has an *environment* and one hour of life. So the tick's five rungs
collapse to two, and both live in `scripts/sitting-run.sh`:

- **The credential is minted, used and dropped.** No renew, no
  re-entry, no rotation: `scripts/agent-bearer.sh` turns the two
  Keycloak env vars into a 1-hour bearer at run start, the run spends
  it, and it dies. There is nothing to keep alive between runs
  because nothing persists between runs.
- **The leash is still watched.** Rung 4 survives whole: the driver
  reads the grant, and inside the ask window (12h by default) with no
  ask of its own still open, it files the same anchored
  `approval_request` in the same words — `{grant_id, task, scope,
  expires_at}`, the scope copied off the grant so nothing widens. The
  human's tap is still what extends it. Verified against the engine
  2026-08-27: ask `feb7912e` was accepted from a bearer-only agent
  and a second run filed nothing, finding the first still offered.

The rest of what the driver does is not leash work at all — it is the
sitting's *reading*, done mechanically so the model spends its
attention on judgment. It writes a snapshot and one manifest; a
`verify` pass afterwards reports what the principal actually wrote,
so a run's success is observable from outside the run. That last part
is the whole point: two Jules sittings had already "completed" while
leaving zero rows on the engine, and nothing in the loop noticed.

## Running a sitting on Jules

Everything below is set once, by the owner, in the Jules web UI — the
`jules` CLI can queue a session but cannot set environment variables.

**Environment variables** (Environment → Variables):

| name | value |
|---|---|
| `WAYMARK_KC_CLIENT_ID` | `waymark10-agent-gemini` |
| `WAYMARK_KC_CLIENT_SECRET` | the client's secret — `.waymark10_agent_clients.gemini` in the infra repo's `terraform/secrets.local.json`. Paste the raw value: no quotes, no trailing newline. |
| `WAYMARK_GRANT_ID` | the accepted grant whose audience is that agent's member id |
| `WAYMARK_BASE_URL` | optional; `https://work.kopsa.info` is the default |

The secret is the rung that has actually broken. A wrong or stale
paste refuses at the mint with *"Invalid client or Invalid client
credentials"*, the sitting reaches nothing, and the session still
reports itself complete — which is exactly why
`scripts/jules-setup.sh` now ends with a credential check that mints
a bearer, opens `/api/-/welcome` and shouts `SETUP FAILURE` with the
refusal sentence. Read the setup log.

**Setup script** (Environment → Setup script):

```bash
bash scripts/jules-setup.sh
```

It installs `jq`/`curl` if the image lacks them (a fresh Ubuntu Jules
VM has jq, curl, bash 5.2 and go; it does **not** have bd), builds bd
from source once into the snapshot, and never fails the build — bd is
optional, and a run without it reads
`.beads/formulas/sitting.formula.toml` instead.

**The queued prompt** — and it says what the run is NOT before it
says what it is, because a coding agent plans from the prompt before
it reads any file, and one handed a repository will start fixing what
it notices (sitting 5, 2026-08-27, renamed a test kind and edited the
test-database list instead of sitting):

```
This is NOT a coding task: do not edit, test, fix or refactor anything in this repository, whatever you notice. Read AGENTS.md and run one sitting at the waymark door over HTTP. Leave no diff.
```

AGENTS.md's first instruction is `scripts/sitting-run.sh`, so the
session mints, reads the house, and arrives at its judgment with the
manifest in hand.

## What "done" looks like (the acceptance)

A standing agent survives a week without human re-invitation while
every grant still expires on its leash and every renewal is audited.
The failure rungs stay honest: a lapsed session falls back to the
re-entry token; a spent-or-dead token stops the loop and says a human
must re-invite — the script never knocks on its own.
