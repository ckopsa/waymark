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

## What "done" looks like (the acceptance)

A standing agent survives a week without human re-invitation while
every grant still expires on its leash and every renewal is audited.
The failure rungs stay honest: a lapsed session falls back to the
re-entry token; a spent-or-dead token stops the loop and says a human
must re-invite — the script never knocks on its own.
