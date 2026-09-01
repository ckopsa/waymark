# Spec — undo: taking back your own last tap

**Thesis.** The owner's words, 2026-09-01:

> make insights and hypotheses have their affordances be undo-able so we can
> manage them with a stack.

A finding and a belief are the two kinds a person answers by TAPPING, in runs,
on a phone, between two other things. Every other kind in this house is worked
at — opened, read, typed into. These two are TRIAGED. And triage has a failure
mode nothing else here has: the thumb lands on the wrong chip, and the house
records a judgment nobody made.

The answer is not to slow the tap down. A confirm dialog on a card is the
feed's own law broken (*a card offers a decision, never a form*), and it makes
the honest taps cost what the wrong one costs. The answer is **undo**: the tap
stays instant, and for the next fifteen minutes the hand that made it may take
it back.

**And undo is not un-answering.** That distinction is the whole of this spec.
The house's standing ruling is that a dismissed or retired row has no door out
— *asking again is asking again, a NEW row, which the twin walls admit
precisely because the old one no longer stands* (spec-hypotheses.md § *Recorded,
for whoever comes next*). That ruling is about the ANSWER. This spec is about
the TAP: it says that a chip touched by one hand, within a quarter of an hour,
with nobody else having spoken since, may be asserted — by that same hand, on
the record, in a transition of its own — to have been a slip rather than a
judgment. Nothing is erased, nothing is un-said, and no second person's answer
is ever reversed.

## Epistemic status

**The mechanism is not invented here.** `:undo` is already a declared facet of
an action (`resource/verify-undo-pointers`, batch H delta 4), already carried
by `value.retire`/`value.restore`, `saved_view`, `feed_recipe`, `dashboard` and
the capability registry, and already read by two checks —
`checks/check-reversible` (a `:reversible true` action owes an unconditional
transition back) and `checks/deck-gesture-problems` (*a swipe is a snap
judgment — every gesture must have an honest undo*). The framework has been
saying for a while that a snap judgment owes a way back. The two kinds a
household actually snap-judges did not have one.

**What is new is the narrowing.** Every existing `:undo` pair is a plain
reversible edge: anyone who may `retire` a value may `restore` it, forever.
That posture is wrong for these two kinds, because their forward doors are
FOUR-EYED — the finder does not decide, the observer does not answer its own
reading — and an unnarrowed reverse would hand the house's answer back to
whoever asked for it. So the undo doors here carry a wall no existing undo
carries, and that wall is the substance of this spec.

**What is deliberately not attempted** is a general history rewrite. There is
no redo, no undo of an undo, no cherry-picking a transition out of the middle
of a row's log, and no undo of anything a second party has since acted on.

## The standing law this narrows, quoted

From spec-hypotheses.md § *Recorded, for whoever comes next*:

> **A dismissed or retired belief has no door out.** `value` has a `restore`;
> this kind does not, because asking again is asking again — a NEW hypothesis,
> which `not-a-second-belief` admits precisely because the old one no longer
> stands. A `restore` would be the house un-answering itself.

From `insight`'s own file, on the same posture one kind over:

> LIVE MEANS `published`. A taken finding was answered yes, a dismissed one
> answered no; both are terminal and both leave the feed, so neither stands in
> the way of asking again.

**Both stand.** What this spec adds is not a `restore`. A `restore` is
available forever, to anyone who holds the door, and says *the house has changed
its mind*. An `undo` is available for fifteen minutes, to exactly one principal,
and says *that hand did not mean to speak*. The two sentences are as different
as `dismiss` and `retire` are on the hypothesis kind — which the house already
split into two doors for exactly this reason: *a composer reading this log must
be able to tell them apart*.

## The six rules

### 1 · The window is fifteen minutes

Counted from the undone transition's own `at` to the engine's `now` at the undo
door. Outside it, the door refuses by name and says how long ago the tap was.

Fifteen because the stack is a **working set**, not a history. Three numbers
bound it:

- **Below**: a triage run is a minute or two of taps, and the affordance has to
  outlive the run, not the tap. Anything under five minutes would expire the
  first card while the last is still being read.
- **Above**: a person who cannot remember which chip they touched is not
  correcting a slip, they are changing their mind — and changing your mind has
  a door already (publish the finding again; mint the fresh hypothesis the
  twin-wall now admits).
- **The other clock**: the house acts on these answers. `server.belief/
  after-write` refolds within milliseconds; the composer's sitting and the
  reading run on scheduled timers hours apart. Fifteen minutes sits inside the
  smallest interval in which a SECOND party is likely to have read the answer
  and done something about it, which is what rule 3 is really protecting.

The number lives in one place — `undo-window` in `waymark10.guards` — and the
refusal quotes it, so a household that wants a different quarter-hour changes
one long and every sentence follows.

### 2 · The same hand, and no grant widens it

An undo is lawful only for the principal whose id the undone transition's own
`actor` names. Not the row's author, not the row's decider field, not a person
with the same role: the actor of that exact log record.

This is what keeps four-eyes intact **in both directions**. The finder does not
decide — so the finder cannot undo the decision either, because the finder is
not the decider and never was the actor. The observer does not answer its own
reading — so the observer cannot undo the person's answer. And the person who
answered cannot undo the observer's create, because the create's actor was the
observer.

It is also why the undo doors carry no separate four-eyes wall of their own.
`the-finder-does-not-decide` on `insight.undo` would be a guard that refuses
nobody — the author can never be the actor of a verdict the author was walled
out of — and this house has a recorded position on those: *a guard that refuses
nobody is worse than no guard: it prints a wall's name into every decision
record it never stopped* (`resource/decider-guards`). The same-hand rule
subsumes it, and the record says so here instead.

### 3 · Only the last thing, and only where it began

Two halves, both about the stack.

**Only the newest transition on the row may be undone.** Undo pops; it does not
reach into the middle. If somebody else has written to the row since — a
restatement, a second verdict, an adopt — the last thing is theirs and the
refusal names it and its actor.

**And the undo must land exactly where that transition began.** The framework
already says this at declaration time: `verify-undo-pointers` refuses an
`:undo` pointer whose target does not *return exactly where it began*, and
refuses one on a multi-origin action outright, because *a multi-origin action
has no single honest reverse*. These doors are multi-origin on the OTHER side —
one `undo` takes back either verdict — so the rule is enforced at the door
instead: the undone transition's `from-state` must equal the state this door
lands in. A `revise` that departed from `affirmed` cannot be taken back by a
door that lands in `observed`, and the refusal says which state it came from.

**A consequence worth saying out loud: the stack is one deep per row.** The
undo is itself a transition (rule 5), so after it the newest record on the row
is the undo — which is not in any door's undoable set, so undoing an undo
refuses by name. The stack a person manages is a stack **across rows**: ten
cards triaged, any of them poppable once, each inside its own window. That is
the shape the ask wanted and it is the shape that costs nothing to be honest
about.

### 4 · Restoring re-faces the walls

A restored row re-enters the world, and the world may have moved. So the undo
doors do not merely flip a state back — the walls that guard the open state
stand on them:

- **`insight.undo`** re-faces `one-live-finding-per-offer`. Answering a finding
  is what frees its question, and a compiler may have published a fresh finding
  on the same next step off the same reading in the meantime. Un-answering the
  old one would put the house back to asking the same question twice, which is
  the exact thing that wall exists to prevent.
- **`hypothesis.undo` and `hypothesis.unretire`** re-face `not-a-second-belief`.
  A dismissed or retired belief blocks nothing, which is precisely how a
  reading is allowed to mint a fresh claim about the same rows; bringing the old
  one back would make two beliefs of one shape about one thing, splitting their
  atoms so neither says anything.

A refused undo **names the row now standing in the way**, in that wall's own
voice — the standing finding's address and the evidence they share, the
standing belief's claim and the address they overlap. The person reads a
sentence about the house, not about the door.

This is the rule with teeth: it means an undo is not guaranteed, and the
affordance must say so honestly rather than promising a way back it cannot
always open.

### 5 · The undo is recorded; nothing is erased

An undo is an ordinary transition. `meta.version` advances, the log gets a row
saying which door was walked, by whom, from where to where and under which law
revision, and the decision record keeps what its guards read. The undone
transition stays in the log exactly as it was. Reading a row's history, a
composer sees *dismissed by colton at 09:14, undone by colton at 09:16* — which
is more information than the tap that never happened would have left, not less.

Nothing about this is a rollback. The row's state moves; its data does not get
a previous version restored from anywhere, because none of the undoable doors
change data that the state does not already carry (see § *What is deliberately
not undoable*).

### 6 · Undoing a create is a tombstone: `withdrawn`

The one asymmetric case. Every other undo returns a row to a state it has
already been in; undoing a create has nowhere to return to, because before the
create there was no row.

**The row does not vanish, and it does not reuse `dismissed`.** Both kinds gain
a new terminal state, `withdrawn`, reached by a `withdraw` door from the open
state. `withdraw` needs no licence to depart a terminal state (§ *The one
framework change*) because it departs the OPEN one; what it needs is the
same-hand rule, which on a create means the row is still exactly as its author
left it.

Why not vanish: the house does not delete rows it has served, the log already
names the create, and an id that answered 200 and then answers 404 is a lie
about what happened.

Why not `dismissed`: because that is the house's own answer, and this is not an
answer at all. The hypothesis kind already split `dismiss` from `retire` on
exactly this argument — *"you read me wrong" and "that was true and is not now"
are different sentences, and the composer reading this log must be able to tell
them apart*. *"Nobody ever read this and its author took it back"* is a third
sentence, and folding it into the first would corrupt three readers at once:

- the feed's rank, which holds a fresh finding DOWN when a DISMISSED prior
  exists on the same offer (waymark-1uv.8) — a withdrawal is not a household
  no and must not weigh;
- `verdict_reason`, whose four quick words are about a claim the house judged;
- any reading counting what this household turns down.

Every state predicate in the tree is an ALLOW-LIST — `belief/live-atom-states`
is `#{:published :taken}`, `belief/standing-belief-states` is
`#{:observed :affirmed}`, `insight/live-state` is `"published"`,
`hypothesis/standing-states` is `#{"observed" "affirmed"}` — so `withdrawn`
falls out of every one of them correctly with no edit to any of them. That is
not luck; it is the payoff of having written them as allow-lists, and it is the
strongest argument for a new state over a reused one.

## The own-surface question, answered narrowly

The brief asked whether undo of one's OWN action rides the own-surface courtesy
the way `create` does, and warned not to widen the still-open ungated-create
question (waymark-enx) by accident. **The ruling has two halves, and they are
different because the two doors are different acts.**

**An undo of an ANSWER requires exactly the authority the answer required.** No
courtesy, no waiver. `hypothesis.undo` and `hypothesis.unretire` carry
`the-answer-is-a-persons` under their own names, so:

- a person passes, as ever;
- the row's own observer is refused, grant or no grant — the `:own-field
  :observed_by` arm, which no grant opens;
- any other agent passes only under a grant naming `hypothesis.undo` or
  `hypothesis.unretire` **by that name**. A scope admitting `hypothesis.dismiss`
  does NOT admit `hypothesis.undo`. Delegating the answer and delegating the
  power to take an answer back are two delegations, and the owner makes them
  separately or not at all.

`insight.undo` needs no such wall: its forward doors are walled by
`the-finder-does-not-decide`, which is a field wall rather than a person wall,
and the same-hand rule already means only the member who decided may undo.

**An undo of your own CREATE rides the own-surface courtesy, exactly as the
create did.** `insight.withdraw` and `hypothesis.withdraw` join `create` on
each kind's own-surface action set, so the agent that wrote a row may knock on
the door that takes it back without presenting a grant.

This widens nothing, and the reason is worth keeping:

- the row it withdraws is one that agent WROTE, minutes ago;
- rule 3 means nobody else has touched it since — the create is still the newest
  transition on it — so no second party's work is being undone;
- rule 6 means the outcome is a tombstone, never a deletion;
- and the whole act is *unpublishing what you just published*, which cannot
  reach further than the publish already reached.

It also **partially heals waymark-br7v**: an author looking at its own finding
saw an empty `actions` map, because the four-eyes wall correctly refuses it both
verdicts and there was nothing else on the row for it to do. Now there is
exactly one thing — take back what you just wrote, while it is still yours to
take back — which is the honest content of that empty map.

**waymark-enx stays open and is not touched.** That question is whether an
ungranted agent should be able to CREATE at all. Nothing here answers it in
either direction: `withdraw` is available only to a principal that already
created the row, so on any future day when the create door narrows, this door
narrows with it automatically and by construction.

## What is deliberately NOT undoable

**Letters.** They were MAILED. `letter` has `open` and `discard` on the
recipient's own surface and no way back from either, and that is correct: undo
can take back a judgment this house made about its own record, and it cannot
un-deliver. A letter withdrawn after the reading opened it would be the house
editing somebody else's inbox.

**The wording doors — `hypothesis.restate`, `hypothesis.revise`,
`journal.amend`, `remark.reword`.** Three reasons, and the first is the
framework's own:

1. `verify-undo-pointers` refuses an `:undo` on a multi-origin action, because
   *a multi-origin action has no single honest reverse*. `revise` departs from
   both `observed` and `affirmed`; `amend` from both `written` and `amended`.
   `restate` is a self-loop, and the framework exempts self-loops from the
   one-way check for the reason that settles this: *re-doing is its own undo*.
2. **A wording's way back is another wording, and the door is already there,
   with the prior text prefilled into it.** These doors are `:record true`, so
   what they used to say lives in the transition log where an amendment history
   belongs.
3. Restoring words would need the row to carry its own previous values — a
   `claim_before`, an `about_before` — which is a second copy of the truth in
   the document, kept fresh by every door, for a case a form already covers.

**The engine's own writes.** `adopt` (a law restamp), the maintenance writes
`server.belief` and `server.maintainer` make, and anything the `:system` actor
does. A maintenance write logs no transition at all, so there is nothing to
pop; an adopt is the engine moving a row's law and is not a hand's tap.

**An undo.** Rule 3's consequence, said as a rule so it is findable: there is no
redo. If you undo and then meant the tap after all, tap it again — the door you
came back to is the door you left from, and it is open.

## The doors

### `insight`

| door | from → to | undoes | walls |
|---|---|---|---|
| `undo` | `taken`\|`dismissed` → `published` | `take`, `dismiss` | `only-your-own-last-tap`, `one-live-finding-per-offer` |
| `withdraw` | `published` → `withdrawn` | `create` | `only-your-own-last-tap` |

`take` and `dismiss` each declare `:undo :undo`, which is the framework's own
way of saying reversible and which `verify-undo-pointers` checks: each departs
from exactly `published`, which is exactly where `undo` lands.

### `hypothesis`

| door | from → to | undoes | walls |
|---|---|---|---|
| `undo` | `affirmed`\|`dismissed` → `observed` | `still_stands`, `dismiss` | `the-answer-is-a-persons`, `only-your-own-last-tap`, `not-a-second-belief` |
| `unretire` | `retired` → `affirmed` | `retire` | `the-answer-is-a-persons`, `only-your-own-last-tap` |
| `withdraw` | `observed` → `withdrawn` | `create` | `only-your-own-last-tap` |

`unretire` is a second door rather than a second origin of `undo` because `:to`
is a static keyword and, more to the point, because undoing a retirement must
land in `affirmed`. A retired belief was affirmed by a person before it was
retired; a door that took it back to `observed` would erase that person's yes
in the name of correcting a slip, which is the opposite of an undo.

`revise` carries no `:undo` pointer and never will — it is multi-origin, and §
*What is deliberately NOT undoable* says why. `undo` will refuse to take one
back at runtime too, by rule 3's second half: a `revise` from `affirmed` did not
begin where this door lands.

### The one framework change beyond the wall

`checks/check-terminal-no-exit` is flat and absolute: **no action departs a
terminal state**. It has no waiver, and every existing `:undo` pair in the tree
lives outside its reach by declaring `:terminal #{}` — `value`, `saved_view`,
`feed_recipe`, `dashboard`, the capability registry. `letter` says the same
thing in the other vocabulary: *`:opened` and `:discarded` are RESTING states,
not tombs*.

**That answer is wrong here, and taking it would have been the expensive
mistake.** A taken finding IS closed history — `feed/open?`, the archive's gate,
`seasons`' "which action finishes something", the envelope's own
`effect: {terminal: true}` and `collections`' state surface all read
`(:terminal rdef)`, and every one of them still wants to say yes about a taken
finding. Dropping `taken` and `dismissed` out of the set to buy a door would
have changed five readers' answers to buy one, and `:over` — the household's own
word for what an ending MEANS — cannot carry all five, because those readers ask
the machine rather than the vocabulary.

So the check grows a **named waiver** instead, `:allow-undo`, on the
`:allow-dead` precedent it sits beside: a set of action names licensed to leave
a tomb. The exception is narrow and it is about what the door SAYS rather than
what it does — an undo does not continue a story, it asserts the last sentence
was never spoken — and a companion check, `check-allow-undo`, holds it to that
shape:

- the name must be a real action of this kind;
- it must LAND somewhere still open, because an undo puts a row back where it
  can be acted on and a door between two tombs is a reclassification wearing an
  undo's name;
- and it must actually depart a terminal state, or the waiver outlives the door
  it was written for and nobody can audit it.

Like `:allow-dead` it is a declaration-time waiver rather than fingerprinted
law, and for the same reason: the DOOR it licenses lives in `:actions` and
hashes there, so the law a revision would be about is already in the hash.

**One sugar change rides with it.** `:undo` joins `verdict-keys`, so a verdict
projected by the `:decision` sugar may name the door that takes it back exactly
as a hand-written action may. It is a pointer, not a mechanism —
`verify-undo-pointers` checks it against the graph and strips it — so a verdict
that does not spell it projects byte-identically to before.

**And one thing the pointer forces.** `t/safety` refuses `:one-way` beside
`:reversible`, and it is right to: a door with a way back is not a one-way door.
So the `:one-way` sentences on `take`, `dismiss`, `still_stands`, `dismiss` and
`retire` are gone rather than reworded, and what each of them said that is still
true moved into `:display :description`, where the card reads it anyway — with
the window named in the sentence.

### The wall

One guard factory, in the framework, worn by all five doors:
`waymark10.guards/only-your-own-last-tap`, parameterized by the actions this
door takes back, the window, and the state it restores. It reads the row's own
transition log through a new ctx hook (`:last-transition`, the sibling of
`:actor-of` that answers *what was the last thing done here, by whom, and
when*), declares `:reads [:transitions :principal :now]`, and — like every
world-reading wall in this house — **advertises optimistically when no hook is
in scope**, because the write path always consults.

It is one guard with four sentences rather than four guards, following
`offers-something-light`'s precedent: the four refusals are one law read at four
different rows of the same log record, and a caller who has to learn which of
four names to look up has learned nothing. The refusals interpolate `{problem}`
and each names the fix.

## Where the arithmetic re-runs

**Insight paths need no new wiring at all.** `server.belief/after-write` rides
the engine's `:maintain` seam and keys on the KIND, not the action: *a committed
write on an `insight` refolds exactly the beliefs that finding's citations
reach*. `undo` and `withdraw` are committed writes on an `insight`, so an
un-dismissed finding refolds the beliefs it feeds back UP, and a withdrawn one
refolds them back DOWN, post-commit, each row in its own transaction, with no
edit to that namespace. This is waymark-2ozr's seam collecting a dividend it was
not built for, which is the sign it was cut in the right place.

**Hypothesis paths fold in their own transaction**, as that kind's own doors
always have. `undo` and `unretire` run `fold-now`, which is what `revise` and
`restate` run — the state changed and the atoms did not, so the fold is
unchanged in value and re-run for the same reason those doors re-run it: a
belief's cached number is a cache of an arithmetic anyone can redo, and a door
that left it unrefreshed would make the freshness depend on which door was
walked. `withdraw` runs it too, and its result is the honest one: a withdrawn
belief is not standing, so nothing reads its posterior any more, and the row
keeps the last true number it had.

`withdraw` on a belief runs no fold, and that is not an oversight: the fold is a
pure function of `(table, prior, atoms, now)` and the state is not one of its
four arguments, so re-running it would only advance the decay clock on a row
nothing reads any more. A withdrawn belief keeps the last true number it had.

The hypothesis kind is still deliberately NOT refolded by the `:maintain` seam —
*a maintenance write chaining off a maintenance write is the loop this seam has
no visited set for* — and nothing here changes that.

**And un-affirming takes the stamp back with the state.** `hypothesis.undo`
clears `affirmed_by` and `affirmed_at`, because a row reading `observed` while
still carrying *affirmed by colton* would have the summary line and the
document disagreeing about the one thing this kind exists to keep straight —
and `summary/render` has no conditional, so `{state}` would go on saying one
thing where the fields said another. Unconditional and safely so: `dismiss`
departs only `observed`, so a dismissed belief never carried them, and
`still_stands` is the only other edge this door takes back. `unretire` clears
nothing — a retired belief WAS affirmed by a person before it was retired, and
taking that yes away in the name of correcting a slip is the opposite of an
undo.

## The UI half

**Shipped separately** (waymark-qmo6, second PR), because the engine half is
already a reviewable diff on its own and a UI fragment is read by a different
pair of eyes. What follows is what it is for.

An **undo stack panel**, in page memory only, at the bottom of the screen.

- Every action tap the UI performs on an `insight` or a `hypothesis` that the
  server answered 2xx pushes an entry: the row's kind, id and label, the door
  that was walked, the door that takes it back, and the moment.
- The panel shows the last few, newest first, each with the row's own sentence
  and one **Undo** button.
- An entry ages out of the panel when its window expires — a ticking count of
  the minutes left, and then the entry is gone. Nothing persists: a reload
  clears the stack, which is honest, because the stack is a memory of what this
  hand just did and not a record of anything.
- Tapping **Undo** invokes the door. On 2xx the entry leaves and the surface
  behind it re-renders. On a refusal **the wall's own sentence is shown in
  place, on the entry**, and the entry stays until dismissed — because a refused
  undo is the most important thing on the screen: it is the house saying another
  row now stands in the way, and naming it.

Deliberately not built: session persistence, a keyboard chord, a redo, and any
undo of an action the server did not confirm.

## Conformance

- **Every new door is on the declared machine**, so it is already owed
  `:core/staging` (a walked path reaches `withdrawn`), `:core/affordances`,
  `:core/unavailable-honesty`, `:core/token-prose`, `:core/replay-history` and
  `:core/decision-record` with no new obligation written — which is the payoff
  of the doors being declarations rather than endpoints.
- **`checks/check-reversible`** now has something to check on these kinds: an
  action declaring `:reversible true` owes an unconditional transition back, and
  `:undo :undo` stamps that flag on both insight verdicts.
- **Scenarios, and what that tier structurally cannot say.** The undo doors'
  walls read the transition log, so every scenario on them is CONFORMANCE tier
  by the tier rule (`:reads ⊆ offline-reads` fails on `:transitions`) — staged
  for real and attempted through the HTTP door.

  **And exactly one undo claim per kind is stageable there**, which cost a red
  gate to learn and is the kind of thing worth writing down rather than
  rediscovering. The conformance walker stages every row as ITSELF, and both
  kinds stamp their four-eyes field from the acting principal at birth —
  `desugar-decision`'s `authored_by`, `hypothesis/born`'s `observed_by`, both
  unconditional, because *a caller who could write it could hand the answer to
  itself*. So a walker-staged row is the walker's own, and the walker is refused
  at every answering door: `taken`, `dismissed`, `affirmed` and `retired` are
  states this tier **cannot reach**. The verdict scenarios that already existed
  never noticed, because their own guards are pure functions of the principal
  and the check tier judges them offline, staging nothing at all.

  What IS stageable is the tombstone, because `withdraw` is walled on the hand
  that CREATED the row and the walker is that hand — so each kind carries one
  conformance scenario proving `withdrawn` is a tomb with no door out. Each
  gets its own subject row, for the reason `a-typed-fact-may-be-left-untyped`
  already records: a scenario that stages a row pays the create door in full,
  and the dedupe walls refuse it off whatever the allow-create scenarios left
  standing.
- **Everything else is proved over the ring handler**, in
  `workqueue10.undo-test` — the same posture `insight`'s own file records for
  `one-live-finding-per-offer` (*proved by `workqueue10.insight-rank-test`,
  where a first finding can actually stand*). A scenario holds one literal row
  over a store it did not build; these claims need a story: one hand answering
  and then taking the answer back, the log keeping both taps, the wall naming
  the row now in the way, the ungranted author's withdrawal, and the belief
  following the finding back.
- **Two behaviours the suite pinned that are worth naming here**, because both
  are the engine being right rather than the test being clever. Re-invoking
  `undo` on a row already back in the open state is a **natural replay** — 200,
  no second transition — so *there is no redo* is proved by what the envelope
  refuses rather than by a status code: the `unavailable` entry for the other
  way-back door reads *the last thing done here was undo, and this door does not
  take that back*. And an agent whose leash names one door and reaches for
  another meets **404 concealment, not a narrated refusal** — which is the
  grant-scope ruling made mechanical: a scope naming `hypothesis.dismiss`
  admits `hypothesis.undo` so little that the door is not even there.

## Recorded punts

- **No redo.** Rule 3's consequence. If it is ever wanted, it is a `redo` door
  with its own window and its own name, never `undo` twice.
- **The window is not per-household data.** It is a `def` in the framework, not
  a field on the feed recipe. Making it tunable is a recipe-row change like the
  evidence table, and it should wait until somebody wants a different number for
  a reason.
- **No undo across a fold that a second party read.** Rule 4 catches the case
  where another ROW now blocks the restore; it does not catch the case where a
  reading has already published a brief quoting the answer. That is not
  detectable from the row and the log, and pretending to detect it would be
  worse than the honest window.
- **`value` is untouched.** It has a `restore` already, forever and for anyone
  who may declare — a different sentence, deliberately, and the kind whose rows
  are the owner's law is the last place to add a fifteen-minute clock.
