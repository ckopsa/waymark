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

### The extension MERGES, and never appends (waymark-ycp)

The loop ate itself twice before this was written down. An anchored
approval used to CONCATENATE the ask's scope onto the grant's, and the
ask copies the grant's scope — so every renewal doubled the list. The
standing composer's grant reached 74 entries for ~20 kinds, with a
filtered `feed.preview_as` spelled several times over, and the ask door
refuses a capability filter-scoped twice ("only ONE entry may filter a
kind"). The next ask was refused; a refused ask is no ask; the leash
lapsed on its own clock with nobody asking (2026-08-28 18:15Z, again
2026-08-29 14:05Z), and every sitting was dark until a person
re-granted by hand.

The ruling, in both halves:

- **The engine.** On approval of an anchored ask the grant's scope
  becomes the ask's scope MERGED PER KIND with the one already stored —
  one entry per kind, actions unioned, ids unioned with openness
  absorbing (an entry naming no ids is the whole kind), `hashed`
  unioned because a tokenised field is never absorbed, and for the two
  narrowings that cannot be unioned — `filter` and `fields` — the last
  entry that SPELLS the key wins while silence INHERITS. A merge writes
  the leash down permanently, so it keeps the narrower reading; an ask
  that means to drop a filter says so out loud with an explicit null.
  The same fold runs on the stored scope, so a grant already carrying
  74 entries collapses to one per kind the first time an approval
  lands: the fix is also the heal.
- **The driver.** It folds the grant's scope the same way BEFORE
  filing, so a not-yet-healed grant still produces a fileable ask —
  keeping `actions` on every entry, empty list and all, because the
  field is required even when empty and a merge that drops it takes a
  422.

### A leash is short — and the ask says so itself

`asks-are-short` refuses anything past the house's grant-max-ttl of 24
hours, so the driver caps what it proposes at 24h minus a minute
(travel time for the request) whatever `WAYMARK_EXTEND_S` says. **The
daily human tap is the law working, not a bug to route around** — every
leash expires, and a person deciding once a day is the whole point of
the leash. What the machine owes is that the ask is always THERE to
tap.

So the ask can never be quiet. `grant_watch` in the manifest carries
`stands` (is an ask of ours open?) beside `inside_window` (does that
matter yet?); when the two disagree the `why` opens with **NO ASK
STANDS — a person must re-grant:** and carries the door's own refusal
sentence, and the run prints that as its FIRST LINE, above the title,
with the instant the grant dies. A refusal nobody read is what turned a
one-line bug into two dark days.

## The composer runs (retired)

The two scheduled composer runs that rode this pulse — the sitting (the
clerk, a small model on a ten-minute timer) and the reading (the editor,
a strong model morning and evening), with their driver, formulas, skills
and fixtures — were removed in 2026-09. The renewal machinery above is
engine-side and stays; nothing in it depended on those runs.

## What "done" looks like (the acceptance)

A standing agent survives a week without human re-invitation while
every grant still expires on its leash and every renewal is audited.
The failure rungs stay honest: a lapsed session falls back to the
re-entry token; a spent-or-dead token stops the loop and says a human
must re-invite — the script never knocks on its own.
