# Spec — refusal → plan

**Thesis.** A guard already names *a* remedy. It does not name a *path*. Turn
`:remedies` from a pointer into a plan: "you cannot do this because X — here
are the three actions, in order, that would make it possible."

## Epistemic status

Small feature, disproportionate effect, and only possible here. Guards are
declared data (`guards.clj`), so the remedy graph is walkable at runtime. In a
system where authorization is code, this feature cannot be built at all — you
cannot ask a function what would make it return true.

For a human it is a nicer error message. For an agent it is the difference
between giving up and finishing, which is why it belongs in the same breath as
[the MCP surface](spec-mcp-surface.md).

## What exists

From `guards.clj`, the declared keys that matter here:

- `:explain` — required, non-blank, `{var}` placeholders resolved from the deny
  verdict's `:vars` then a `:vars-fn` garnish.
- `:remedies` — **affordance tokens (`:kind/action`) that would change the
  verdict.** Already declared, already on the wire, already unused beyond
  display.
- `:open` — the acknowledged sentence for a judged field advertising nothing.
- `:becomes-available-at` — an instant/date after which the verdict flips on
  its own.
- `:severity` — `:refuse` | `:warning`, the latter acknowledgable.

And `members.clj` shows the pattern in the wild:

```clojure
:remedies [:role/create]
:open "The active roles are the roles collection, one query away…"
```

That guard knows precisely what would unblock the caller. Nothing walks it.

## The design

A planner: **breadth-first over the remedy graph, bounded and cycle-checked.**

Given a refused `(kind, id, action)`:

1. Probe it. Collect the denying guards (the render probe already does this).
2. For each denying guard, read `:remedies`. Each token is a `(kind, action)`.
3. For each remedy, probe *it* — is that action itself available to this
   principal, on some row or as a create? If yes, it is a **step**. If no,
   recurse on its own denying guards.
4. Stop at depth 3, on a repeat token (cycle), or when a branch is fully
   available.

Output rides the refusal envelope beside the existing `explain`:

```json
{"refused": "queue",
 "because": "No active role named manager — register the role first",
 "plan": [{"step": 1, "affordance": "role/create",
           "available": true, "effort": "recall",
           "why": "registers the manager role the assignment names"},
          {"step": 2, "affordance": "member/assign_roles",
           "available": false, "blocked_by": "step 1",
           "effort": "selection"}],
 "plan_status": "complete"}
```

`effort` comes from `demand.clj` verbatim, so the caller learns not just what
to do but how much typing it costs — a one-click assent step and a
compose-an-essay step should not look alike to a planner.

`plan_status` is `complete` (every step available or blocked only by an earlier
step), `partial` (a branch hit the depth bound), or `none` (the denying guard
declares no remedies — which is itself a finding worth surfacing to authors).

**A declaration check falls out of this.** Once plans exist, a guard that
refuses with no `:remedies` and no `:open` is a dead end a person cannot escape.
That is exactly the shape `checks.clj` already lints for elsewhere: add
`[remedies]` to the usability warnings and the framework starts teaching its
own authors.

## Recorded punts

- **Remedies that need a row that does not exist.** `:role/create` names a kind
  and an action, not a target. The plan names the affordance; it does not
  pre-fill it. Honest and sufficient.
- **`:reads`-heavy guards.** A guard reading `:storage` is probed against
  today's rows, so a plan is advice, not a promise. Same caveat as the
  [law sweep](spec-law-sweep.md); state it once, in the envelope.
- **`{:any [...]}` composites** advertise nothing, so a plan under an OR can
  only name the composite. The alternative — exploding every arm — leaks law
  the declaration deliberately hid.
- **No auto-execution.** The planner never invokes. An agent that wants the
  steps run asks for them one at a time, through the doors, with the audit that
  implies. This is a boundary, not a phase-two.

## Effort

**Small.** The probe exists, the graph is declared, the output is three keys on
an envelope that already renders refusals. The bound and the cycle check are
the whole algorithm.

## Built — the `[remedies]` declaration check (waymark-fp62.2.1)

The check this document proposed in one sentence ("a guard that refuses
with no `:remedies` and no `:open` is a dead end a person cannot escape")
is built. The planner above is not; this is the part that makes the
planner's input complete.

**What landed.**

- `checks/dead-end?` — a guard that refuses IN WORDS and names no way out.
  A `:hide` guard answers 404 and narrates nothing, so it owes nothing. A
  `:warning` guard is acknowledgable, and acknowledging is its way out.
- `checks/denier-leaves` — the walk the census needs. `g/iter-leaves`
  answers a SCHEMA question and stops at an `:any`, because an OR
  advertises nothing. A refusal is the other question: `g/evaluate` under
  an `:any` hands the caller the first denying ARM, so the arm is what
  must carry the way out.
- `checks/census` — `{:guards :remedies :open :waived :dead-ends :stale
  :unmatched}` over a set of declarations.
- `usability/remedy-warning` — one sentence per dead end, naming the kind,
  the door, the guard and the sentence the caller reads.
- `waymark10/resources/waymark10/remedies-waivers.edn` — the dead ends
  that existed on the day the check landed. A waiver names a guard, or a
  name PREFIX for the guards a parameterized builder mints, and the bead
  that will clear it. `waymark10.check` refuses a waiver that waives
  nothing, so the list only shrinks.
- `waymark10.check` prints the dead ends, then the census line, and exits
  1 on a stale waiver or on a `:remedies` token naming a door no kind
  declares.

**The conformance obligation.** The suite walks every guard on every
fixture kind and reads the refusal body of the ones it can drive. The
obligation is an EQUALITY rather than a presence: the body says exactly
what the guard declared, so a body that DROPPED a declared remedy fails.
A fixture guard that refuses with nothing to do next is named in the
suite, because a fixture that exists to prove a different obligation is
not a debt the framework owes.

**Recorded gap.** `problems/guard-refused` carries `:remedies` and
`:becomes-available` and does NOT carry `:open`, so the `:open` half of
the obligation is latent: it reads the body correctly and no fixture
guard declares an `:open` yet. Two lines close it — the denier map in
`invoke/deny-outcome` and `invoke/create-guard-pass`, and the problem
constructor in `problems/guard-refused`.
