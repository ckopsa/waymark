# Spec — the usability battery: the declaration is the form

**Thesis.** Creating a saved view offers no hints. The right-gesture box wants
an action name; the card box wants a list of field names; the target box wants
a kind. The engine knows every one of those answers exhaustively — it *refuses*
the write when they are wrong, naming the very tokens it would have offered —
and the human is asked to remember them and type them into an empty rectangle.

The fix is not a better form. The form, the MCP tool description, the OpenAPI
body and the generated docs are all *projections of the declaration*, so the
cheapest place to make every client kinder at once is the declaration, and the
cheapest way to make a declaration kinder is to say out loud, once, where it is
unkind. Five policies, each an opinion with a fix in it, all of them read off
declarations and none of them touching a database.

## Epistemic status

Barely a mechanism, and deliberately. Everything the battery reads already
exists and is already read by something else:

- **`demand.clj`** — the effort vocabulary (`assent` / `selection` / `recall` /
  `composition`), already derived per action and already on the wire. This spec
  makes `field-class` and a `heavier?` comparison public, because the fix is
  per field and the gesture rule is a comparison; it invents no new class.
- **`checks.clj:200` `check-closure`** — a guard judging an input field must
  tell the client what the field wants, *or acknowledge the gap with `:open`*.
  Policy 1 is that acknowledgment re-read.
- **`x-display`** — `:label` and `:help` are already what the generic form
  renders above and below a field (`ui/170-forms.js:296`); `:help` had simply
  never been spelled anywhere in the codebase.
- **`checks.clj:612` `view-problems`** — the shared view battery both the
  declaration gate and the `saved_view` write gate run. Policy 5's refusal is
  already there; this spec pins it rather than adding it.
- **`check.clj:56` `report`** — the three-section report (enrollment, per-kind
  warnings, law scenarios). The battery is the fourth thing printed under a
  kind, and the first thing printed under an *enrolled* kind.

## Where the battery lives, and why not in `checks.clj`

`waymark10.checks` runs inside `defresource`, at import, and prints every
warning it finds on `*err*`. That is right for a battery of a dozen opinions
and wrong for a battery of a hundred: a boot, a test run and a REPL load would
each recite the whole fix-list, and a warning recited that often is a warning
nobody reads.

So `waymark10.usability` is a **separate namespace called only by the check
CLI**, where an author is asking the question on purpose. Nothing about the
import path changes; `make test10` and `make dev-queue` are as quiet as they
were. The cost, recorded: `waymark10.dev/explain` shows the gate's warnings and
not these, and a kind's `:waymark10/warnings` metadata does not carry them.

The battery is **opinions only**. It never throws and the exit code never reads
it — `make check-queue` prints `△ 30 kinds, 135 warnings` and exits 0. The one
refusal in scope was already a refusal (policy 5, below).

## The five policies

### 1 · Effort honesty — no recall where selection is possible

*An input field whose legal values the engine enumerates exhaustively must be a
picker, not a memory test.*

The check's proxy for "enumerable from the declaration" is the closure rule's
own escape hatch. `check-closure` is a **definition error** when a guard judges
a field that nothing constrains — unless the guard says `:open`, a sentence
acknowledging that it judges by a vocabulary the schema does not publish. Every
`:open` in this codebase says the same thing in different words:

> "The legal kind names are well-known's resources, one GET away; enumerating
> the registry into every scope form would duplicate it." — `grants.clj:245`

That is precisely the admission the policy re-raises as an opinion. A
guard-judged field whose demand class is `recall` or `composition` warns; the
same field carrying an `:enum`, a `:const` or an `x-ref` (class `selection`) is
silent, which is the whole point — the fix clears the warning.

```
[effort-honesty] the create door field :card is free text, but guard
composes-declared-primitives judges it against tokens the engine enumerates
exhaustively (its :open acknowledges the gap) — every client renders a blank
box where a picker belongs; give :card an :enum, a :kind ref, or a vocabulary
the schema can publish
```

Concealed doors are exempt: a `:hide`-flagged guard means no person ever meets
the form (grant's `:extend` is minted by the approval effect).

### 2 · Mandatory display prose — labels, hints, and prose for enum values

*Every field a human is asked to fill carries the household's own word for it,
and every typed field carries a sentence saying what belongs there.*

Two warnings at most per door, because a fix-list reads as a to-do per **form**
and a per-field spelling of the same two facts is a wall nobody works through.

- **No `:x-display :label`.** The generic client titles the wire token when
  nothing else is offered (`prep_minutes` → "Prep Minutes"), which is a
  courtesy and not a label; an MCP tool description shows the agent the bare
  token either way. The MCP spec recorded this same forcing function from the
  agent side — tool descriptions read badly wherever `:display` was written
  lazily.
- **No `:x-display :help` on a typed demand.** A `selection` is
  self-explaining once labeled; a `recall` or `composition` field is a memory
  test until somebody says what belongs in it. Scoped further to fields that
  actually render as an empty rectangle: a boolean, a number, an instant or a
  date arrives at a real control — a checkbox, a spinner, a calendar — and a
  label answers it.
- **Bare enum tokens.** `:x-display {:choices {"local" "Ours — the version
  stored here"}}` maps the wire token to the sentence a person reads. Both
  clients now render it (`ui/170-forms.js`, `ui_lite.html`); without it a
  select offers spelling and the human guesses.

```
[display-prose] action revise renders without prose — no :x-display :label on
[:label :description], and no :help sentence on the typed demand(s)
[:label :target :card :right :left :description]: a field with neither is a
bare wire token in the form and an unexplained argument in the MCP tool; give
each an :x-display {:label … :help …}
```

**The create door counts as a door.** The complaint that filed this policy was
about a *create* form, and `waymark_invoke` treats create as an action. This is
also what makes the numbers large: a kind that spells no `:create-schema`
offers its whole data schema at create, engine-maintained bookkeeping included.
That is not a misreading — it is the create form as it really renders.

### 3 · Composition scaffolding — a blank textarea has something in it

*An action or create door demanding `composition` hands the caller something to
start from.* Three spellings count:

| spelling | what it gives |
|---|---|
| `:default` on the composing entry | the form prefills it |
| `:examples ["…"]` on the entry | rides as standard JSON-Schema `examples`; agents read it, the textarea wears the first as a placeholder |
| `:edit {:prefill [that field]}` | the template — the document's current prose as the starting text |

`:examples` is new and is one line in `schema.clj`'s `entry-x-props`. Unlike
`:default`, nothing is ever *applied* from it: an example is a starting point
offered, never a value assumed.

The create door has no document to prefill from, which is exactly why the
policy bites there and nowhere else in this codebase — every composing *action*
in workqueue10 already prefills.

### 4 · Gesture duties — a swipe is short, cheap and undoable

*An action bound to a deck gesture owes three things the same swipe cannot ask
twice:* a **short label** (16 characters — the chip sits under a thumb beside
its twin), a demand of **at most `selection`** (a swipe collects a decision,
never a form), and a **way back** — reversible, idempotent, or confirm-gated.

Reversibility is already law, not opinion: `checks.clj:585`
`deck-gesture-problems` refuses a declared view whose gesture binds a
non-reversible action, and the same battery judges a `saved_view` row at write
time. The policy states the duty in full anyway, because a duty split across
two documents is a duty nobody can read.

```
[gesture-duties] view :triage binds :right to finish, whose demand is recall —
a swipe collects a decision, never a form; bind a gesture to an action of
effort selection or less, and leave the typing to the row's own screen
```

Silent across the whole household today: `chore_run`'s `:triage` deck binds two
assent-effort, reversible, two-word actions. Proved by test rather than by
fix-list.

### 5 · Card completeness — a row can name itself

*A nav-visible kind must be able to name one of its rows.*

`:summary` is already mandatory (`check-summary-template` refuses a declaration
without one), so what is left is the **label**: the short name a ref picker, a
card and a link badge show. The engine defaults it to `{data.name}` when the
schema declares a `:name` field (`invoke.clj:427` `label-of`); a `:primary` or
`:secondary` kind with neither a `:label-template` nor a `:name` labels its rows
with a raw id everywhere it is referenced.

**The refusal half already existed.** The bead asked for a `saved_view`'s chosen
`:card` fields to be validated against the target kind's schema as a *create
guard* naming the field. That is
`saved-view/composes-declared-primitives` → `checks/view-problems`, running at
create *and* revise, answering:

> `:card names [:priority], not data field(s) of the schema`

— and, for a prose field with no teaser flag, naming that too. Nothing was
added; `saved_view_test`'s `refuse` block already pins it through the HTTP door
(409, `":card names"`). Recorded here because an unwitnessed law is a law
waiting to be deleted.

## The check reports the framework's own kinds now

`spec-law-scenarios.md` closed with a punt: *"`waymark10.check` judges the
application's own declarations only… Widening `check` to the full registry
would make every app's gate report core's law, which is a decision about what
`check` is for and deserves its own line rather than a side effect of this
one."*

This is that line, and it is narrower than the punt feared. `report` now also
walks `modules/enrolled` and prints a row for an enrolled kind **only when the
usability battery has something to say about it** — no scenarios, no
deviations, no gate warnings. `grant`, `member`, `role`, `approval_request`,
`definition`, `attachment`, `subscription` and `job` are the forms every
application serves and no application wrote, and they were invisible from the
one place an author looks. A row reads `grant (enrolled) — 2 warnings`.

`waymark-442.8` — *"`waymark10.check` judges the full registry's scenarios,
not only the app's own"* — is untouched and now has a narrow precedent to
argue from rather than a blank page.

## What the framework's own sugar had to fix

A policy that flags the framework's own generated spellings is a bug in one of
them, because the declaration's author cannot clear a warning about a field
they never wrote. Two reconciliations landed, both on the sugar's side:

- **`resource.clj` `verdict-action`** — the `:decision` sugar's generated note
  input carried no prose. It now generates `:x-display {:label … :help …}` from
  the note field's own name. `input_schema` is in the fingerprint's
  advertisement family, so this moves no law.
- **`mirror.clj`** — `resolve_conflict`'s generated `:keep` enum is the one
  human door on the sync machine and rode as two bare tokens; it now carries a
  `:help` sentence and `:choices` prose. `bookkeeping-schema`'s `:synced_at` and
  `:conflict_reason` ride *every* mirrored kind and carry labels and hints now.

One reconciliation was **rejected** and is recorded so it is not retried:
exempting `:x-display {:raw true}` entries as "engine-maintained". The
long-text check reads `:raw` as *"a display shape IS declared — raw text"*, and
`saved_view`'s `:where` wears it for exactly that reason while being the most
hand-authored field in the framework. A marker that means two things cannot
carry an exemption.

## The first run — the fix-list this spec exists to produce

`make check-queue` over workqueue10 (30 app kinds + 8 enrolled), exit 0:

| policy | warnings |
|---|---|
| effort-honesty | 21 |
| display-prose | 91 |
| composition-scaffolding | 13 |
| gesture-duties | 0 |
| card-completeness | 10 |
| **total** | **135** |

The framework's own kinds, which the bead named as the initial fix-list:

| kind | warnings | shape |
|---|---|---|
| `saved_view` | 15 | 10 effort-honesty (`:target :where :card :right :left`, twice — create and revise), 4 display-prose, 1 composition-scaffolding |
| `dashboard_slot` | 9 | 6 effort-honesty (`:target :where :view`, twice), 2 display-prose, 1 card-completeness |
| `dashboard` | 3 | 2 display-prose, 1 composition-scaffolding |
| `member` (enrolled) | 7 | effort-honesty on `:roles`, display-prose across the create door and five actions |
| `definition` (enrolled) | 3 | display-prose, including an eleven-field create door |
| `grant` (enrolled) | 2 | effort-honesty on `:scope` (create), display-prose |
| `approval_request` (enrolled) | 2 | effort-honesty on `:scope`, display-prose |
| `role` (enrolled) | 2 | effort-honesty on `:name`, display-prose |
| `subscription` (enrolled) | 2 | display-prose |
| `attachment`, `job`, `capability` | 1 each | display-prose |

**They are not fixed here, on purpose.** The battery's first run against the
framework's own forms *is* this spec's proof; clearing the list in the same
change would delete the evidence and hide which policy earned which warning.
The fix-list is filed as follow-up beads, one per kind-cluster:
`waymark-8sg` (the composition kinds, 27), `waymark-7rw` (the access kinds,
12), `waymark-ts2` (the household kinds, 88), `waymark-9va` (mirror's
bookkeeping in create forms).

Every effort-honesty warning on that list wants the *same* missing spelling —
"the options for this field come from **there**, at runtime" — which is why
`waymark-8sg` is P1 and the rest wait behind it.

## Recorded punts

- **`effort-honesty` cannot see an unguarded free-text field.** Its whole
  signal is the `:open` acknowledgment, so a field nobody guards — a kind name
  typed into a plain string with no guard behind it — passes silently. The
  honest fix is a declaration spelling for "the options for this field come
  from *there*", which is a feature (a runtime vocabulary source), not a check.
- **No policy reads the registry.** Each judges one declaration alone, because
  the battery runs where `check.clj` runs — before any engine. `saved_view`'s
  `:card` cannot be validated at declaration time for that reason; it is
  validated at write time instead, by the guard policy 5 pins.
- **`:choices` prose is unvalidated against the enum.** A `:choices` map with a
  token the enum never declares is silently ignored by both clients. Cheap to
  check; deliberately not checked yet, because the first thing a battery of
  opinions should not grow is a battery of opinions about itself.
- **Coverage is counted per policy, never enforced.** There is no waiver token
  (`:waives`) for these five. If one of them turns out to be wrong somewhere it
  should be *narrowed*, not waived — a warning with an escape hatch stops being
  read as an opinion and starts being read as a chore.
