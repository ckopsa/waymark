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
12), `waymark-ts2` (the household kinds, 88), `waymark-0y7` (the enrolled
stragglers, 7), `waymark-9va` (mirror's bookkeeping in create forms, 1).
All five have landed; the tally reached **135 → 0** and the final
amendment below carries the accounting.

Every effort-honesty warning on that list wants the *same* missing spelling —
"the options for this field come from **there**, at runtime" — which is why
`waymark-8sg` is P1 and the rest wait behind it. That spelling landed with
`waymark-8sg`; see the amendment below.

## Amendment — the runtime vocabulary (`:x-options`, waymark-8sg)

The fix-list above named 21 effort-honesty warnings and **every one of
them wanted the same missing spelling**. Policy 1's warning always
offered three fixes — "an `:enum`, a `:kind` ref, or *a vocabulary the
schema can publish*" — and the third did not exist, so the policy's
first run was a to-do list nobody could work. This is that third fix.

### The complaint, stated exactly

An `:enum` publishes a vocabulary the **declaration** knows. Some
fields are judged against a vocabulary only the **running engine**
knows: the kinds it serves, one kind's data fields, its actions, its
declared views, its filter grammar. The guard for such a field escapes
`check-closure` with `:open` — *"the legal tokens are the registry's,
one GET away"* — and every client then draws a blank rectangle, and
the human types from memory the token the engine was about to refuse
them for misspelling.

### The spelling

An entry property, beside `:x-display`:

```clojure
[:card {:optional true
        :x-options {:from :fields :of :target :each true}
        :x-display {:label "Card fields"
                    :help  "Which of the target's own data fields a card shows, in the order given."}}
 [:maybe [:vector [:string {:min 1 :max 60}]]]]
```

| key | meaning |
|---|---|
| `:from` | which source — a key of `schema/option-sources` |
| `:of` | the sibling field naming the target kind, when the source is relative to one |
| `:each` | the field is a **list** and every item comes from the vocabulary |
| `:composes` | the value is **built** from the tokens rather than equal to one; `:query` is a `field=value&…` filter string, whose vocabulary is the legal names left of each `=` |

### The five sources, and why there is no sixth endpoint

`schema/option-sources` is closed, and every entry answers out of a
document **the client already holds**: the discovery root (which the
browser client caches and which is `waymark_discover`'s whole answer)
or one kind's published schema (which is `waymark_schema`'s answer).

| `:from` | href | `:at` |
|---|---|---|
| `:kinds` | `/api/.well-known/waymark` | `["kinds"]` |
| `:fields` | `/api/schemas/{of}` | `["properties"]` |
| `:actions` | `/api/.well-known/waymark` | `["resources" "{of}" "actions"]` |
| `:views` | `/api/.well-known/waymark` | `["resources" "{of}" "views"]` |
| `:filters` | `/api/.well-known/waymark` | `["resources" "{of}" "filters"]` |

**No route was minted.** A picker that costs a second endpoint is a
picker somebody turns off. The one wire change is additive and rides
an existing precedent: well-known's per-resource entry already carried
`actions` *"so building a grant scope needs no source read"*, and now
carries `views` and `filters` for the same reason and beside it.
`filters` is `checks/where-fields` — the same list `where-field?`
refuses by, extracted so the picker and the refusal cannot drift.

### What lands on the wire

`{of}` is filled twice: at **projection** time the hole is *renamed*
to the sibling field it draws from (`{of}` → `{target}`), and at
**fetch** time the client fills `{target}` with that field's current
value. `:at` then walks the fetched document — an array's elements are
the tokens, an object's keys are. (`:note` is prose a person reads and
wears the bare name instead of a hole.)

```json
"right": {
  "oneOf": [{"type": "string", "minLength": 1, "maxLength": 60}, {"type": "null"}],
  "x-options": {
    "from": "actions",
    "href": "/api/.well-known/waymark",
    "at":   ["resources", "{target}", "actions"],
    "of":   "target",
    "note": "the action names of the kind named in target"
  },
  "x-display": {
    "label": "Right gesture",
    "help":  "What a right swipe does to a deck card — one of the target's REVERSIBLE actions; a swipe is a snap judgment and owes a way back."
  }
}
```

### Advertisement, never law

The annotation says where the options are. **The guard remains the
whole of the enforcement, unchanged** — `composes-declared-primitives`
and `slot-composes-declared-primitives` were not touched, and nothing
`fingerprint-of` projects moved (entry properties are not in any
facet), so the framework kinds' hashes are byte-identical.

Three consequences follow from that one sentence, and they are the
design:

1. **The widget offers; it never cages.** The browser client draws a
   `datalist` plus a row of chips *beside the field's own text box* —
   not a `<select>`. A slot's legal `sv-<id>` is minted per row and no
   source can list it; a hard picker would make a legal write
   impossible, which is a worse failure than a blank box.
2. **The demand class stays honest.** `effort-honesty` reads
   `:x-options` off the projected property *directly* rather than
   teaching `demand/field-class` to call it `selection`. A `:where` a
   person still composes by hand is `composition` on the wire, picker
   or no picker. What the policy objects to is the engine **hiding** a
   vocabulary it holds, and that is exactly what is fixed.
3. **A misspelling is refused, not ignored.** `checks/check-options`
   is a definition error for an unknown `:from`, a relative source
   with no `:of`, an `:of` naming a field the same form does not
   declare, or a `:composes` grammar nobody speaks. Without it a typo
   is silent: the projection omits the annotation and the field goes
   back to being the blank rectangle this whole spelling exists to
   end.

### How each client learns the options

- **The generic browser client** (`ui/170-forms.js`): `xoptionsOf` →
  `optionTokens` fetches `href` (memoized per resolved href, so
  `:right`, `:left` and `:where` on one form share a single
  well-known read), walks `at`, and fills a `datalist` and a chip row.
  A chip writes the token *the way that field spells one* — a whole
  value, one entry of a comma list, or a `name=` appended to a filter
  string. Recipes with a hole re-run when the sibling changes; until
  it is answered the chip row reads *"answer target first — the
  options are the action names of the kind named in target"*.
- **An MCP agent**: `waymark_schema` serves the same published schema
  the browser reads, so the annotation arrives unchanged; its tool
  description now spells the recipe once (fetch `href`, walk `at`,
  fill `{holes}` from sibling arguments), and both documents a recipe
  can name are themselves MCP tool answers — `waymark_discover` and
  `waymark_schema`. An agent needs no route knowledge it did not
  already have.
- **`ui_lite.html`**, the single-file fallback, renders `:label`,
  `:help` and `:choices` and does **not** read `x-options` yet. Filed
  as a follow-up rather than done badly here.

### The composition kinds, cleared

`saved_view`, `dashboard` and `dashboard_slot` — the three kinds a
family authors at runtime, and the 27 warnings the battery's first run
filed against the framework's own forms — now report `✓`.

| kind | before | after | what did it |
|---|---|---|---|
| `saved_view` | 15 | 0 | `:x-options` on `:target :where :card :right :left` (both doors); prose on all eight fields; `:choices` for deck/feed; an `:examples` for `:description` |
| `dashboard_slot` | 9 | 0 | `:x-options` on `:target :where :view` (all three forms); prose spelled once in `slot-prose` and worn by row, create and revise; `:label-template "{data.label}"` |
| `dashboard` | 3 | 0 | prose spelled once in `dashboard-prose`; an `:examples` for `:description` |

`make check-queue` over workqueue10: **135 → 108 warnings**, exit 0,
the app kinds untouched. The remaining fix-lists —
`waymark-7rw` (access kinds), `waymark-ts2` (household kinds),
`waymark-9va` (mirror's bookkeeping) — inherit the spelling: `grant`'s
and `approval_request`'s `:scope` want `{:from :kinds}` and
`{:from :actions}`, and `role`'s `:name` wants a source of its own.

## Amendment — the access kinds, and where a vocabulary is *not* (waymark-7rw)

`grant`, `approval_request`, `role` and `member` were the second fix-list,
and the four of them are the reason this section exists: two of the four
took the runtime-vocabulary spelling and **two of them refused it**, for
reasons the policy had never had to say out loud. `make check-queue`
over workqueue10: **108 → 94 warnings**, exit 0.

| kind | before | after | what did it |
|---|---|---|---|
| `grant` | 2 | 0 | `:x-options` inside `:scope`'s item map; prose on all three fields; a `:scope` `:examples` |
| `approval_request` | 2 | 0 | the same item map; prose on `:grant_id`/`:scope` in its own schema and on `:task`/`:expires_at` **through the decision sugar** |
| `role` | 2 | 0 | prose + `:examples`; policy 1 narrowed — no picker, and the refusal is why |
| `member` | 7 | 0 | prose on the create door and four actions, `:choices` for `actor_type`/`provenance`, `:examples` for `:roles`; policy 1 narrowed |
| `permission_slip` | 1 | 0 | nothing of its own — the sugar's new prose cleared it, which is the altitude working |

### `:x-options` reaches inside a list of entries, and needed nothing new

A scope is not a token. It is a **list of entries**, each naming a kind,
that kind's actions, and the rows, fields and filter that narrow them —
so the vocabulary belongs to the entry's *parts*, not to the list. The
question the bead was filed with was whether the spelling needed a new
capability for that. It did not:

```clojure
(def scope-schema
  [:vector
   [:map
    [:kind    {:x-options {:from :kinds}}                       [:string …]]
    [:actions {:x-options {:from :actions :of :kind :each true}} [:vector [:string …]]]
    [:hashed  {:x-options {:from :fields  :of :kind :each true}} [:maybe [:vector …]]]
    …]])
```

An item's fields **are each other's siblings**, so `:of` resolves inside
the entry a person is filling in, and `schema/annotate` already walked
into item maps, so the recipe lands in `items.properties` where a client
meets it. What grew is not the spelling but the two READERS:
`checks/check-options` now validates item maps as surfaces of their own
(reusing `check-long-text`'s `data.{field}[]` naming), and
`usability/effort-honesty` counts a list as advertised when its ITEMS
advertise.

The `:of` refusal is what keeps this honest rather than merely
convenient. A field one level *further* in — `:fields {:mode … :names …}`,
or an `:args` entry's `:names` — would want the kind named two maps up,
and `check-options` refuses that, because no client can fill a hole from
outside the entry in front of the person. Those fields carry a help
sentence instead. So does `:filter`: its vocabulary is the legal **keys
of an object**, and the recipe's two composition words (`:each` for a
token list, `:composes :query` for a `field=value&…` string) both
describe a value *built* from tokens. Calling a map either one would be
a lie about the grammar.

### Policy 1, narrowed: a guard that reads ROWS is not withholding a schema

`role`'s `:name` and `member`'s `:roles` both wanted a source, and
giving either one would have been a fake. The narrowing is that policy 1
asks its question of guards that judge against the **declaration**, and
stays quiet about guards that judge against **rows** — the signal being
a `:storage` or kind keyword in the guard's own `:reads`
(`usability/declaration-reads`, guards.clj's own vocabulary). Every one
of `:x-options`' five sources is a projection of declarations answering
out of a document a client already holds; rows are the collection's
business, and the two shapes here are the argument:

- **the collision test** — `role`'s `one-spelling` reads `:role` to
  refuse a name already taken. The rows it *could* list are exactly the
  illegal answers; a chip row of them is a worse form than a blank box,
  not a better one. What the field owed was the naming convention said
  out loud, an `:examples`, and the refusal that names the collision.
- **the registry test** — `member`'s `roles-registered` reads `:role` to
  judge role names. Here the rows *are* the legal answers, but they live
  behind a scoped, paged collection GET that a one-hop recipe cannot
  honestly promise, and the guard already names the door where a missing
  one is **made** (`:remedies [:role/create]`). That door plus a
  sentence is the affordance.

This is a narrowing and not a waiver, which is the rule the punt list
below sets for these five: there is no `:waives` token, and a policy that
turns out to be wrong somewhere gets a smaller question, not an escape
hatch. What both fields still owe is prose, which policy 2 demands and
now gets. The row-backed source is **filed** (waymark-90k), not faked.

### The decision sugar says the human words for the entries it owns

`approval_request` is a `:decision` kind: its `:task` and `:expires_at`
are projected by `resource/desugar-decision`, not written by any author.
Re-declaring them in `:schema` to add a label would move the entry ORDER
and with it the kind's fingerprint — *to say a sentence*. So the sugar
carries the prose itself, in the generic voice a decision of any subject
can wear, exactly as it already did for a verdict's `:note`:

```clojure
:asks    {:field :task    :x-display {:label "What you need it for" :help "…"}}
:expires {:field :expires_at :default {…} :x-display {:label "Good until" :help "…"}}
```

The map spelling of `:asks` and `:expires` already existed for `:max`
and `:default`; it now takes an `:x-display` that wins key by key over
the sugar's own. `permission_slip`, which spells neither, cleared its
last warning without being touched — the proof that the fix landed at
the right altitude.

### The hashes, and one honest drift

`approval_request`'s fingerprint is pinned as a literal in
`decision-sugar-test` and is **byte-identical**
(`01ca868b…`), as is `role`'s. `grant`'s and `member`'s **moved**, and
not because of anything on this page: they carry `#()` reader-gensym
guards (`scope-names-real-kinds`, `roles-registered`,
`reentry-token-is-fresh`) whose `callable-hash` shifts when *any* form
is added to a namespace loaded before them. Proved by bisection: with
only the declaration files changed, both hashes are byte-identical;
adding a single `(defn- probe [x] x)` to `resource.clj` and nothing else
moves them. That is **waymark-j82**, filed before this work and not
fixed by it.

## Amendment — the household kinds, and the word a `:fields` row lacked (waymark-ts2)

The third and largest fix-list: 88 warnings at filing across the meal,
chore, evening, queue, media and calendar kinds. This is **application of
policy, not new policy** — no policy was narrowed, no source was added,
and the one refusal in the battery was never approached. `make
check-queue` over workqueue10: **94 → 8 warnings**, exit 0.

| kind | before → after | kind | before → after |
|---|---|---|---|
| `plan_day` | 8 → 0 | `chore_run` | 3 → 0 |
| `evening_session` | 8 → 0 | `evening_plan` | 3 → 0 |
| `product` | 6 → 0 | `media` | 3 → 0 |
| `chore` | 5 → 0 | `plan` | 3 → 0 |
| `ingredient` | 5 → 0 | `rotation` | 3 → 0 |
| `meal` | 5 → 0 | `substitution` | 3 → 0 |
| `prep_task` | 5 → 0 | `day` | 2 → 0 |
| `activity` | 4 → 0 | `event` | 2 → 0 |
| `grocery_list` | 4 → 0 | `journal` | 2 → 0 |
| `meal_line` | 4 → 0 | `self` | 2 → 0 |
| `capability` | 1 → 0 | `weather` | 2 → 0 |
| `letter` | 1 → 0 | `task` | 1 → 0 |
| | | `task_list` | 2 → **1** |

(`permission_slip` had already cleared through the decision sugar in
waymark-7rw; `connection` never warned.)

The remaining 8 are seven enrolled-kind warnings (`definition` 3,
`subscription` 2, `attachment` 1, `job` 1 — the framework forms no bead
has claimed yet) and **one deliberate hold-out**: `task_list`'s
`[:conflict_reason]` composition-scaffolding. That field is mirror
bookkeeping riding onto a create door, and papering it with an
`:examples` in the declaration would hide the thing the battery found.
It stays standing for **waymark-9va**, at the sugar.

149 `:label`s and 131 `:help` sentences landed, plus `:choices` on 14
enums, 14 `:examples` for composition doors, and 9 `:label-template`s.
Every one of them is `:x-display` / `:examples` / `:label-template` —
advertisement, in no fingerprint facet. **No `:x-options` was added and
none was needed:** the household's fix-list carried zero effort-honesty
warnings, because no household guard escapes closure with `:open`. The
runtime-vocabulary spelling is a framework-form problem, and the two
earlier fix-lists were where it lived.

### The finding: a `:fields` row had no slot to say anything in

The battery's own sentence — *"give each an `:x-display {:label … :help
…}`"* — was, for three of the household's kinds, **an instruction that
could not be followed**. `chore_run`, `day` and `evening_session` spell
their schemas as `:fields` lifecycle groups, and a group row is strictly
`[field (word …)]`: no properties slot, by construction. `prose` alone
took a label, and only a label, because it happened to want one.

So the fix landed on the word channel rather than on the row grammar —
one function, `declare/described`, which merges an `:x-display` map into
whatever the word already carries:

```clojure
[:capacity (described (one-of :high_focus :low_focus :exhausted)
                      {:label "What you've got in you"
                       :choices {"high_focus" "Sharp — …" …}})]
[:notes (described (prose "How it went") {:help "Written after, not before — …"})]
```

It composes with **every** word, including the ones that already declare
a widget, and it touches the malli form not at all — a described word and
a bare one compile to the same schema and the same fingerprint. The row
grammar stays two-element, which is what the `:fields` docstring promises
and what `parse-field-rows` refuses to bend.

This is a small framework growth in a bead billed as prose, and it is
recorded as such. The alternative was an app-local workaround in three
declarations, which the house rule (*grow the framework, not the
workarounds*) exists to prevent.

### Two things the battery is right about and one it is not asking

- **`substitution`'s create door** demands `:from_ingredient_name` and
  `:to_ingredient_name` — the very label copies its two `_id` refs
  maintain. `meal_line` carries the identical pair and is silent, because
  it declares a `:create-schema` that omits them. That difference is the
  evidence: a declaration gap, not a prose gap. Prose was written that
  says the field is carried for reading, and the real fix — a
  `:create-schema` — is filed as **waymark-ts2.1**, because the create
  facet *is* fingerprinted and deserves its own revision.
- **`display-prose` cannot see an OPTIONAL enum's bare tokens.** The
  clause reads `:enum` off the top-level projected property, and
  `[:maybe [:enum …]]` publishes as `anyOf` with a null branch. So
  `media`'s `:medium` was flagged and `media`'s `:source` was not, for a
  reason no person meets in the form — both render a select of wire
  tokens. `blank-box?` already unwraps that branch three functions
  earlier. Filed as **waymark-0ee.1**; every affected field here got its
  `:choices` anyway.

### The hashes: ten movers, and one of them was never touched

`make test10` twice and `make test-queue`: green. The style-invariance
suite's literal pins (`meal`, `prep_task`, `event`) are **byte-identical**,
which is the proof that entry properties and `:label-template` sit in no
facet.

Ten kinds' fingerprints nonetheless moved: `grocery_list`, `ingredient`,
`meal_line`, `media`, `permission_slip`, `plan`, `plan_day`, `product`,
`rotation`, `task`. **`permission_slip` was not edited by this work at
all**, which is the whole argument. Bisected the way waymark-7rw
bisected: a baseline `git worktree` at HEAD, hashes computed over
`check-resources` in both trees, and then the *framework* patch alone —
one `defn` in `declare.clj`, one alias in `dsl.clj`, every application
file left at HEAD — applied to the baseline. It moved **exactly the same
ten kinds**. That is **waymark-j82**, unfixed and now measured across a
whole application rather than one kind; the twenty kinds that held still
are the ones whose imperative residue is entirely canonical-form.

The style-invariance suite needed the prose carried into its *old*
spellings too, and that is correct rather than a chore: the suite pins
that two spellings are one declaration, and a declaration that says
something in one spelling and not the other is genuinely two.

## Amendment — the create door is not the row (waymark-9va), and the last seven words (waymark-0y7)

The two beads that close the burndown. One is structural and one is
prose, and they land together because between them `make check-queue`
reads:

```
✓ 30 kinds, 0 warnings, 11 scenarios judged
```

### waymark-9va — the mirror declares its own exclusion, once

The battery's sentence was true and its subject should not have existed:

```
task_list — 1 warning
[composition-scaffolding] the create door demands composition in
[:conflict_reason] with nothing to start from
```

`:conflict_reason` is written by the sync, read by a person, and asked
of nobody. It reached the create door by the plainest route in the
framework: a kind that spells no `:create-schema` offers its whole data
schema at create (`collections.clj`, `invoke.clj`), and a mirror's data
schema is the author's declaration **plus the weave**. So the weave had
put a field on a form and then the form asked for it.

The fix is at the sugar, where the fields come from, rather than in
the declaration that inherited them — the altitude argument the
decision sugar won in waymark-7rw. `mirror/declaration` now names its
own bookkeeping in two halves and synthesizes the birth door from the
second:

```clojure
(def ^:private sync-written-fields
  #{:external_etag :synced_at :conflict_reason})

;; in declaration's let, beside the woven data-schema:
create-schema (or (:create-schema rmap)
                  (into [:map]
                        (remove (every-pred
                                 vector?
                                 (comp sync-written-fields first)))
                        (rest data-schema)))
```

and then `(assoc :schema data-schema :create-schema create-schema …)`.

**`:external_id` is deliberately not in that set, and the omission is
the whole distinction.** Identity is a **claim a local birth may
honestly make** — `task_list`'s native-XOR-mirrored pairing law
(`identity_paired`) is spelled at exactly that door, and a `:local-rows`
kind's mirrored births name the upstream they mirror there or nowhere.
Sync state is a **record only a pass can write**. Dropping all four
would have been the tidier set and the wrong one: it would have deleted
a birth law to satisfy a display warning.

Three things made this safe to do at the sugar rather than kind by kind:

- **Discovery mints never noticed.** A mint validates against the full
  `:schema` by construction (`invoke/create!` with `:mint? true`, whose
  docstring already said why), so `{:external_id id}` alone still lands.
  This was the open question on the bead; the answer was already law.
- **An author's `:create-schema` still wins outright** — the fill-a-blank
  rule the decision sugar's own synthesized create model follows.
- **The create facet is `defaults` only** (`fingerprint.clj`), and none
  of the three carries a default, so the exclusion is hash-silent. The
  three mirrors whose door actually changed — `task_list`, `event`,
  `prep_task` — hold their fingerprints byte-for-byte.

The refusal grew a matching half: a mirror that spells its own
`:create-schema` may not name the three either (`:create-push` keeps
the stricter rule that bars all four). The convention is now total —
**no mirror's create door carries sync state, synthesized or
declared** — which is the trade this bead was asked to record: one set
and one `or` in the weave, in exchange for a rule that can no longer be
forgotten in a declaration.

### waymark-0y7 — the four framework kinds nobody had claimed

`definition` 3, `subscription` 2, `attachment` 1, `job` 1: the warnings
left over when waymark-ts2 cleared the household, on enrolled forms no
application declares and every application serves. Pure prose — 31
`:label`s, 19 `:help` sentences, `:choices` on two enums
(`definition`'s `:diff_class`, and `subscription`'s `:delivery_policy`,
which the battery **cannot see** for waymark-0ee.1's reason and which
got its prose anyway), one `:examples`. No new defn, in any of the four
files.

Two of these kinds hold engine-stamped fields on a human create door —
`attachment`'s `:size` and `:sha256` (the upload route writes them),
`subscription`'s `:failure_reason` (the deliverer does). Their prose
**says so** rather than pretending the form is asking, which is the
line waymark-0ee drew for `synced_at`. The structural fix that
waymark-9va just made for mirrors — a `:create-schema` that omits what
only the engine writes — applies to them word for word and is filed as
**waymark-0y7.1**; it is a create-facet change on enrolled kinds and
deserves its own revision rather than a ride on a prose bead.

### The burndown, end to end

| bead | fix-list | warnings |
|---|---|---|
| `waymark-8sg` | the composition kinds — and `:x-options`, the spelling policy 1 was asking for | 27 |
| `waymark-7rw` | the access kinds — and the decision sugar's own words | 12 |
| `waymark-ts2` | the household kinds — and `declare/described`, the word a `:fields` row lacked | 88 |
| `waymark-0y7` | the enrolled stragglers | 7 |
| `waymark-9va` | mirror bookkeeping on the create door | 1 |
| | **135 → 0** | |

Every policy is at zero, including the two that were zero on the first
run (`gesture-duties`) and the two that took framework growth to reach
it. Three of the five beads grew a spelling rather than writing prose,
which is the ratio the house rule predicts and the reason the fix-list
was worth publishing before it was worked.

### The hashes: the same ten movers, and a sharper reason

`make check-queue` (0), `make test10` twice, `make test-queue`: green,
203 + 695 tests.

**waymark-0y7 moved nothing at all** — all 30 kinds byte-identical,
which is the third independent confirmation that `:x-display` and
`:examples` sit in no facet.

**waymark-9va moved ten**, and they are *precisely* waymark-ts2's ten:
`grocery_list`, `ingredient`, `meal_line`, `media`, `permission_slip`,
`plan`, `plan_day`, `product`, `rotation`, `task`. Bisected by
neutering the patch in place — the exclusion set emptied to `#{}`,
every added form and docstring left standing, so the law is a no-op and
the bytes are not. **The same ten moved.** The mirrors whose create
door the real patch actually changed moved in neither run.

The mechanism is one step sharper than waymark-j82 records. That bead
blames the **reader** gensym counter for `#()` inside a `defguard` body,
which is true. But these ten hash by `callable-hash` over a **bare
`fn`**, and a bare fn's `pr-str` is

```
#object[mealplan10.resources.plan$fn__12873 0x7698a3d9 "…@7698a3d9"]
```

— a compiler-assigned class number and a JVM identity hash, *both* of
them functions of everything compiled before them. That is why the
sensitivity is to **bytes anywhere earlier in the load order**, not to
lambdas: `mirror.clj` is required by the app resource namespaces and so
compiles ahead of them, while `definitions.clj`, `jobs.clj`,
`webhooks.clj` and `attachments.clj` are pulled in by the engine
afterwards and could not move an app kind if they tried. waymark-j82's
fix (canonicalizing the captured form) closes the `#()` half; the bare-fn
half needs the `defhandler`/`defguard` conversion its own audit note
already names.

### The witness that had to move

`waymark10.check-test` asserted, since waymark-0ee, that *some*
enrolled kind still warns — `grant` first, then `definition`. Clearing
the last one turns that into a red test, which is exactly right and
exactly why the assertion should not simply be re-pointed: it had made
a passing test out of an unfixed fix-list, and it re-filed itself every
time the list drained. It now asserts the two things it was always
about:

- **the tally is zero** and no enrolled kind takes a row — the standing
  guard that goes red the day a framework form lands unkind again;
- **the reach** is read off a wordless probe enrolled by fiat
  (`with-redefs` over `modules/enrolled`), so what is under test is the
  widening machinery rather than somebody's unfinished prose.

## Recorded punts

- **`effort-honesty` cannot see an unguarded free-text field.** Its whole
  signal is the `:open` acknowledgment, so a field nobody guards — a kind name
  typed into a plain string with no guard behind it — passes silently. The
  honest fix named here — a declaration spelling for "the options for this
  field come from *there*" — **is built** (`:x-options`, the amendment above,
  waymark-8sg). What is still punted is the *check*: the battery reads the
  spelling where a guard makes it ask, and still cannot ask about a field no
  guard judges. That wants a policy of its own, not a wider policy 1.
- **`:x-options` names DECLARED views, not saved ones.** `:views` reads
  well-known, which is a projection of declarations and reads no storage; the
  collection envelope's `views` merges active `saved_view` rows on top and
  mints their `sv-<id>` wire names. So a slot's `:view` picker offers the
  declared tokens and the help sentence names the `sv-<id>` form for the rest.
  Closing that costs a storage read in the discovery root, which is a decision
  about what well-known *is* and deserves its own line.
- **`ui_lite.html` does not draw the picker.** The single-file fallback client
  renders `:label`, `:help` and `:choices` and ignores `x-options`. It is a
  fallback; the annotation degrades to the text box it always was.
- **No source reads ROWS.** `member`'s `:roles` wants the active role names,
  which exist and are enumerable and live in a collection — behind a scoped,
  paged GET whose items are envelopes, not tokens. A sixth source would need a
  per-item pluck the recipe has no word for, and would make the picker's
  freshness and scoping a thing the five declaration-projection sources never
  had to think about. Filed as waymark-90k. Until then the field carries the
  sentence and the guard carries the refusal.
- **A list of entries is still a JSON textarea.** The generic client resolves
  an item recipe's `{of}` hole against the entries a person has ALREADY typed
  and offers the union as chips that insert at the caret — real help, and not
  the per-entry form the scope deserves. `:examples` rides as the placeholder
  so it does not open blank. A structured editor for vector-of-map fields is a
  client bead (waymark-vz4), not a declaration one.
- **`:x-options` cannot name the KEYS of an object, nor a hole two maps up.**
  A scope's `:filter` and a field-spec's `:names` both have a real runtime
  vocabulary and got a help sentence instead of a recipe — the first because
  `:each` and `:composes :query` both describe a value *built* from tokens and
  a map is neither, the second because `:of` names a SIBLING and the kind is
  one map further out. Filed as waymark-3ox; both walls are deliberate, and
  both are cheap to remove for a second field that wants them.
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
