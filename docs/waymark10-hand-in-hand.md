# Waymark — the hand-in-hand story

The charter, the story, and the acceptance test that the migration
skipped. Companion documents: `waymark-spec.md` (whose first sentence
this document exists to defend), `waymark10-design.md` (the skeleton),
and the family-week story in `mealplan10/test` (the engine's dogfood,
which this document completes rather than replaces).

**Epistemic status.** Written after the migration, at the owner's
verdict, in the owner's words: the most important features were the
ones that let AI agents and humans work hand in hand, and the
migration treated them as periphery. The record supports the verdict.
The v7–v9 design docs — the deepest, most recent documents, the ones
the migration was planned from — are entirely about law; the
collaboration surfaces were so load-bearing that nobody ever wrote
them down, and what is not written down is what a migration loses.
The scar, recorded for every future version: **a migration reproduces
documented emphasis, not felt purpose.** This document is the felt
purpose, written down.

## The charter

Waymark is an affordance-oriented hypermedia format **for mixed
human/agent clients** — sentence one of the spec, and the sentence
that outranks every other. Everything else is in its service:

- **The law machinery is the trust substrate, not the point.** A
  family lets an agent plan its week only because the agent's power
  is legible (the grant reads in one sentence), scoped (to the task),
  mortal (24 hours by default), and judged (by a law that pilots and
  grandfathers instead of striking everyone at once). Fingerprints
  and overlays exist so that delegation is safe. They are the
  skeleton of trust.
- **The collaboration surfaces are the creature.** Presence, shared
  drafts, the acknowledge conversation, the confirm handoff, the
  follow — these are where hand-in-hand is *felt*. A version that
  strengthens the skeleton while thinning the creature has moved
  backward, whatever its test counts say.
- **The owner writes applications, and application code should be
  fun.** The research goal is a framework expressive enough that the
  owner *prefers* to write the application himself — where declaring
  a resource feels like designing, not transcribing. Delegation to
  an agent must be a choice, never a surrender. The division of
  labor follows: the framework is the agents' to build and the
  owner's to judge; the application is the owner's to write.
  **An owner forced down into framework internals to make his
  application pleasant is the framework failing this commitment** —
  the spelling experiments in the working tree are exactly that
  signal, honored as design direction and owed a proper landing so
  their author can climb back up to the application where the fun
  was supposed to be. The framework meets the app author with:
  errors at the declaration site that teach, a REPL that answers in
  the domain's own words, and room for personal idiom that the
  fingerprint proves harmless (two spellings, one law exists
  precisely so style can be *play* — in app code).

The test of any future change, twofold: *does it make the story below
shorter, warmer, or more trustworthy?* — and *would the owner still
rather write the next resource himself?* If delegation always wins,
the framework has failed, whatever its test counts say.

## The story

*Tuesday evening. Priya opens the week board. The agent — the family
calls it Sous — has a standing Tuesday task: draft next week.*

**1 — The knock.** Sous does not have access; it never keeps access
between tasks. It asks: *"Draft the week of the 21st — assign_meal,
finalize on plan; accept, update_recipe on meal."* Priya's screen
shows the ask the moment it lands — the task in one sentence, the
scope in one breath, the leash already stamped: *until tomorrow,
6:12 pm.* She reads all three and taps approve. Nothing about this
is ceremony; it is the whole security model, and it takes four
seconds.

**2 — Company.** A soft mark appears on the board: **● Sous is
here.** Priya taps its name and follows. Her screen breathes with
its attention — the meal list, the calendar, back to the plan. She
is not supervising a log; she is watching a colleague read.

**3 — Thinking out loud.** Sous is deciding. Before it acts, its
*intentions* surface to anyone following: *"considering — Tacos →
Tuesday"*, a dry-run's shadow, gone in a moment if abandoned. When it
acts, the day fills under her eyes and the ledger whispers the
transition. The difference between these two — the considering and
the doing — is the difference between watching someone think and
reading their diary afterward. Both are hers to see.

**4 — Four hands, one recipe.** The brisket needs work. Sous opens
the shared draft on `update_recipe`; Priya is already in it. Two
cursors in one prose field. She types *"Dad likes it smokier"*; Sous
reworks the rub into grams around her sentence without ever losing
it — edits converge, they do not fight, and each paragraph knows who
wrote it. When they act, the draft is consumed; the recipe is one
text with two authors, which is the point of every tool humans have
ever built together.

**5 — The ask.** Sous moves to finalize. The calendar gate warns —
*the recital overlaps Thursday* — and Sous does not push through,
because it cannot: the warning is a wall until a human acknowledges.
On Priya's screen the wall appears as a question addressed to her:
**"Sous wants to finalize. 1 calendar conflict (recital, Thursday).
Proceed?"** — the guard's own sentence, the agent's pending intent,
and her decision, in one card. She says yes; Sous acknowledges by
name; the plan is planned. The refusal machinery did not slow the
work down — it *was* the conversation.

**6 — Standing behind it.** Anything confirm-gated works the same
way, with the consequence sentence shown to her, never judged by the
agent. Anything done shows the undo when an honest reverse exists.
Every act carries the actor: *Sous (for Priya)* in the feed, forever.

**7 — Trust, inspected.** Later, curious, Priya opens Sous's member
page: what it did (the transitions, replayable under the law each was
judged by), what it can still do (the grant, and the clock running
out on it), a Follow button, and the week's asks — approved, denied,
expired. The whole relationship on one screen.

**8 — The leash ends.** At 6:12 the grant dies on its own. The
presence mark fades. What remains is the plan, the recipe with two
authors, and the record. *The power expires on schedule; the story of
what it did never does.*

## The audit — story beat → what exists → what's missing

| Beat | Built (and committed) | Missing |
| --- | --- | --- |
| 1 The knock | approval_request bootstrap, mint-on-approve, default 24h TTL, four-eyes, scope-in-a-sentence | the ask arriving LIVE on the approver's screen (it is a poll/visit today — asks should ride the events surface into the UI) |
| 2 Company | presence surface, viewing dots, Follow on member pages, presence-steered follow, concealment-projected | — (landed; needs the story's polish pass) |
| 3 Thinking out loud | dry-run exists; transitions feed the ledger | **intent frames** — landed (design §21): a dry-run IS a considering on `/api/-/intents`, TTL-evicted, abandoned or resolved by the real act; the presence discipline throughout. UI card still owed |
| 4 Four hands | relay/2 + proven OT, per-field authors, stale never loses words, draft consumed by the act | **cursors/selections in the UI** (the named batch-D punt); identity over the WS — landed (design §21): the one-time `?ticket=` names the join, authors carry real names, anonymous joins unchanged |
| 5 The ask | warning walls, acknowledge-by-name, the guard's sentence identical in advertisement and refusal | **the asking surface** — landed (design §21): a warning wall reports as an asking intent that lingers; the human answers on the same channel and the E1 retry releases the agent. Approver-screen card still owed |
| 6 Standing behind it | confirm gates with consequence, undo affordance, actor on every transition | agent display names ("Sous (for Priya)") — actor display exists on the wire; the ask/grant flow should carry it |
| 7 Trust inspected | member page, grant + expiry visible, per-actor history via follow, replay-history | one composed "relationship screen" (member + grants + asks + recent acts in one view — a surface declaration away) |
| 8 The leash ends | live expiry (stale grants scope to nothing), deterministic grant ids, audit trail durable | presence fade on expiry is automatic; nothing missing |

Three genuinely new mechanisms, all small, all following the presence
precedent (ephemeral, never law, concealment-projected): **intent
frames** (3), **the asking surface** (5 — likely the same channel:
an agent's pending gate is an intent that lingers until answered),
and **identity over the collab socket** (4). Everything else is
composition and polish of what exists.

## The admission test

The story above, executable: a two-client drive — one browser as
Priya, one CLI/client session as Sous — that walks all eight beats
end to end. Sous knocks; Priya's screen shows the ask without a
refresh; the follow tracks reading; an intent frame appears and
resolves into an act; both cursors touch one recipe and converge; the
finalize warning arrives on Priya's screen addressed to her and her
answer releases Sous; the undo shows; the member page tells the whole
story; the grant dies and the marks fade. **Green means the soul is
back. It joins `make test-mealplan10` as the second standing story,
and no future version ships without it.**

## What this document is not

It is not a repudiation of the law work. The story runs *on* the
skeleton: beat 1 is grants, beat 5 is guards, beat 7 is the
replayable law, beat 8 is live expiry. The failure was never that the
law was built — it is that the law was built *first, alone, and
called the point*. This document exists so the next reader knows the
order of importance, and so the next migration — of this project or
any other — asks the question the last one didn't: *what does
everyone here know that nobody wrote down?*
