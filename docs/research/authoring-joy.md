# Authoring joy — where v10's hand loses to v9's, and what wins it back

Research findings for the third charter commitment
(`waymark10-hand-in-hand.md`): the owner is a coder and coding here
should be fun — "a spelling you wouldn't write for pleasure needs
work, however faithfully it normalizes."

**Epistemic status.** Written 2026-07-10 from three bodies of
evidence, in priority order: the owner's own uncommitted hand
(`defpart` and its spelling law, read as data about what the hand
wants); a cold side-by-side of `mealplan9/resources/plan.py` against
the committed `mealplan10/src/mealplan10/resources/plan.clj`; and
prior art from the Clojure DSLs that people write for pleasure.
Findings only — nothing here changes code, and every proposal below
is subject to the spelling law's rule 0: it enters the ledger when
recorded friction demands it, not before. This document is the
recording.

## 1. The owner's hand — what defpart is, and what it points at

`defpart` is the working tree's answer to a recorded reading failure:
the Day — one schema entry, a one-of group, a part scope, seven
placed actions — was smeared across `plan.clj` with no single home,
and the reader had to reassemble the concept. The spelling gathers
everything that operates on *one existing part* into one def'd plain
map (`:key :schema :one-of :actions`), cited on the entry it governs
(`[:days {:part day}]`), and `normalize-resource` projects it back
into the split spelling — vector form, part scope, `days/coverage`
group key, `:place`-stamped actions — byte-identical fingerprint,
pinned.

Three things about the direction, each louder than the mechanism:

- **It gathers by domain noun, not by framework key.** Batch G
  colocated law onto *fields*; defpart colocates law onto a
  *concept* — the Day, the item. The unit of authoring joy the hand
  is reaching for is the noun the family would say out loud.
- **It deletes what the structure already says.** Seven `:place
  :days` lines and the group's `:in [:days]` and its namespaced key
  are gone from the authored text — the citing entry supplies the
  binding, and the part never names its own field. The itch is not
  brevity in general; it is *never spelling what the shape already
  spells*.
- **The law came first.** `waymark10-spellings.md` was written the
  same day, before the sugar shipped: earned, pinned, replacing, the
  expansion one call away. The hand disciplines its own fun — which
  means the framework can afford to be generous with sugar, because
  the pin is a CI property and the ledger is a habit. Sugar is safe
  here in a way it is not in most codebases.

What it implies he wants next: more nouns with one home. The
candidates the current files volunteer — the *lifecycle* (states and
transitions are scattered across `:states`/`:initial`/`:terminal` and
every action's `:from`/`:to`; nobody can see the machine), the
*calendar concept* (an edge that today spans `:related`, `:links`,
two derived counts, and a guard — four homes for one relationship),
and the *create story* (`:create-schema` + `:on-create` +
`:predecessor`, three keys for one birth). None of these has recorded
friction yet; they are listed so that when the friction lands, the
ledger has its trigger ready.

## 2. The side-by-side — plan.py against plan.clj

Both files are good. The honest read is that the Python's pleasure is
concentrated in exactly one place — the decorated-method rhythm — and
the Clojure's losses are all *rhythm* losses, not semantic ones,
while its wins are structural and real.

### Where the Python breathes

**The decorator is a headline and the method is its paragraph.** One
verb, one syntactic unit, law over body, name said once:

```python
@action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
        input=AssignInput, place=days,
        guards=[date_in_plan, meal_fits_day],
        safety=Safety(idempotent=True, reversible=False, confirm=False),
        display=dict(label="Assign meal", style="primary", order=1))
async def assign_meal(self, inp: AssignInput, ctx: Ctx) -> None:
    await self._assign(inp, ctx)
```

The committed Clojure spells the same verb in three homes with three
names: `(defhandler assign-meal …)` two hundred lines up, `(defaction
assign-meal-action {… :handler assign-meal …})`, and the registration
`:assign_meal assign-meal-action` inside the actions map. Three
spellings of one name (`assign-meal`, `assign-meal-action`,
`:assign_meal`), and the reader jumps to assemble what the decorator
gives in one gaze. defpart cut the count from four homes to three; the
name-said-thrice residue remains.

**Blank lines are paragraphs; map literals are walls.** The Python
file's bodies read as prose paragraphs — a deleted-handler-work
comment where the engine took over ("`# coverage (OneOf) clears the
meal arm — not this handler`"), a blank line, the next thought. The
Clojure's `:actions` map inside the new `defpart day` is ~55 lines of
uniform 7-key maps with no paragraph breaks and a closing
`}}}}}` — legal to break with blank lines, but the house style never
does, so the eye tracks nesting depth instead of reading.

**Attribute paths read; `get-in` recites.** Everywhere a verdict or a
handler touches the row:

```python
accepts=lambda r: [d.date.isoformat() for d in r.data.days]
```

```clojure
:accepts (fn [row] (mapv :date (get-in row [:data :days])))
```

One is a sentence about days; the other is a sentence about map
navigation. Multiplied across five guards and six handlers per
resource, this is the largest per-line tax v10 pays. (The expression
language already solved this for law — `(data :start_date)` is terse
— but fn-shaped code never got the same courtesy.)

**Optionality is spelled twice, fifteen times.** Every optional
nullable field pays both halves:

```clojure
[:meal_name {:optional true} [:maybe [:string {:max 200}]]]
```

plan.clj spells this pair ~15 times. The Python pays a version of the
same tax (`str | None = Field(default=None)`) but it reads as a type
sentence, not as nested brackets around a repeated incantation.

### Where the Clojure wins

**No builder tax, no class ceremony, no hiding hacks.** The Python
needs a `StrEnum`, three `BaseModel` classes, five input classes, a
`model_validator` for day-building, and — the ugliest thing in either
file — a subclass that *hides inherited fields* to make the create
form:

```python
class PlanCreate(PlanData):
    previous_plan: SkipJsonSchema[str | None] = None
    days: SkipJsonSchema[list[DayPlan]] = Field(default_factory=list)
```

v10's `:create-schema` is just another literal that says what it
accepts. Honest, flat, no inheritance dance.

**Forms are law; the Python fakes homoiconicity with an operator
DSL.** waymark9 needed the whole `E` builder to make law diffable:

```python
expr=E.all(E.f("days"), E.it.meal_id.is_set() | E.it.eating_out.eq(True))
```

```clojure
:expr '(every [d (var :days)]
              (or (is-set (get d :meal_id))
                  (= (get d :eating_out) true)))
```

The Clojure one is a quoted form that looks like the language it
lives in, prints as itself, and fingerprints as itself. This is the
win the rewrite was for, and on the page it is real.

**Def-site errors that teach.** `defaction`/`defderived`/`defpart`
refuse at their own line, in sentences with the reason and the rule
("a part action's place IS the citing entry"). The Python validates
at import, at class-assembly distance.

**Colocation.** Derived facts ride their entries
(`[:end_date {:derived end-date} …]`); filter and sort law ride the
fields they govern. The Python gathers `filterable(...)`/
`sortable(...)` into top-level builder calls away from their fields.

**The tree at hand.** The whole declaration is one EDN-able value;
`normalize-resource` shows any spelling's expansion. The Python class
cannot print itself as its own law.

### The diagnosis, in one sentence

v10 loses to v9 exactly at the **verb seam** — one action's law,
name, and body are one syntactic unit in Python and three named homes
in Clojure — and at the **texture level** (bracket walls, path
recitation, the double-spelled optional); it wins everywhere the
declaration is *data* (schema, create, expressions, colocation,
def-site refusal, the one canonical value), and the batch-G field
note already said the honest thing: the wins are locality, never
brevity — line counts went *up* in the style rewrite, and that was
fine.

One correction to the intuition that framed this research: the
resource files *do* already use the `;; ── section ──` bars — plan.clj
has guards / handlers / create / actions / declaration sections. The
missing paragraphing is *inside* the big literals, not between them.

## 3. Prior art — what makes a Clojure data DSL a pleasure

Seven transferable principles, from the DSLs people write for fun
(hiccup, reitit, malli, HoneySQL, fulcro's defsc, meander) and the
one that teaches by negative example (re-frame's registration):

1. **Positional until it hurts.** `[:div.card "hi"]`, `[:map [:date
   :waymark/date]]` — the common case rides position; property maps
   appear only for the exceptional. Waymark's schema entries already
   live this; its action maps do not — seven named keys even when
   most carry the house default.
2. **Grade cost by rarity.** malli's `:int` → `[:int {:min 1}]` →
   `[:map …]` ladder: frequent things terse, rare things verbose.
   Named safety values (`routine`, `overwrite`) already apply this;
   the optional-nullable pair is the loudest place it isn't applied
   yet.
3. **The DSL should look like its subject.** hiccup looks like HTML;
   HoneySQL maps are written in SQL clause order; a reitit route tree
   looks like the URL space. A waymark lifecycle should *look like a
   state machine* somewhere — today no spelling and no tool draws it.
4. **Macro headline, data body — and destructuring is free
   vocabulary.** fulcro's `defsc` earns its keep by putting the
   name and the shape on line one and letting the body flow;
   its prop destructuring (`{:day/keys [date theme]}`) declares the
   data shape in the argument vector. Clojure gives waymark this for
   free in every `fn` and `defhandler` — the house style just never
   uses it.
5. **Register by reference, not by side effect.** re-frame's
   `reg-event-db` global registry is the cautionary tale: hard to
   find, hard to reload, invisible to the compiler. v10's
   def-plain-values-and-cite discipline is already the right answer —
   keep it, and fix the name-said-thrice residue *within* it, never
   by adding a registry.
6. **Blank lines and alignment are part of the grammar.** The
   pleasant big literals in the wild (HoneySQL queries, deps.edn,
   reitit tables) are paragraphed with blank lines and aligned
   values. EDN permits both everywhere; a style that blesses them
   turns walls into stanzas at zero mechanism cost.
7. **A pleasure DSL ships its own inspector.** malli has `explain`
   and `mu/to-map-syntax`; reitit routers answer `match-by-path` at
   the REPL; macroexpand-1 is a cultural habit. The spelling law's
   rule 3 ("the expansion one call away") already names this as law —
   what is missing is the *rendered, joyful* version of the call.

## 4. Proposals, ordered by joy-per-effort

| # | Proposal | Size | Sugar-pure? |
|---|----------|------|-------------|
| 1 | Paragraph the literals; destructure new handlers | S | whitespace: yes, trivially · handler respell: **no** (see flag) |
| 2 | `try-guard` / `expand` REPL one-liners | S | read-only |
| 3 | Fingerprint diff, rendered as sentences | S | read-only |
| 4 | Wire-named `defaction` + `:actions` citation vector | S | yes — pin required |
| 5 | The live dev loop: nREPL, reset, the diff as narrator | M | no law touched |
| 6 | `(describe plan)` — machine art, fields, verbs | M | read-only |
| 7 | The `?` optional-entry spelling | M | yes — pin required |
| 8 | Part-shaped handlers | M/L | **NO — a mechanism, flagged** |

### 4.1 Paragraph the literals; destructure new handlers (S)

Pure style, no mechanism. Blank lines between entries of `:actions`
and `:schema` literals (legal EDN, invisible to the value, therefore
invariant with no pin needed), and argument destructuring in new
handlers:

```clojure
;; before
(defhandler assign-meal [row inp _ctx]
  (update-day row (:date inp) #(assoc % :meal_id (:meal_id inp))))

;; after — the input's shape declared where the Python put its type
(defhandler assign-meal [row {:keys [date meal_id]} _ctx]
  (update-day row date #(assoc % :meal_id meal_id)))
```

**The honest flag:** whitespace and key order are free, but
*respelling an existing handler or guard fn body is not* — the
fingerprint hashes the fn's canonical printed form, so a style edit
to a `defhandler` body or a guard's `:accepts` fn mints a revision by
design. "Style is play" holds for the declaration maps; play stops at
the fn boundary. Adopt destructuring for new code and for handlers
already being touched; do not sweep it.

### 4.2 `try-guard` and `expand` one-liners (S)

Read-only wrappers over what exists (`guards/evaluate`,
`normalize-resource`), returning data with the guard's own sentence
rendered:

```clojure
(g/try-guard date-in-plan {:data {:days [{:date d1} {:date d2}]}}
             {:date d9})
;; => {:verdict :deny
;;     :reason  "2026-07-19 is not a day of this plan."
;;     :accepts [d1 d2]
;;     :remedies []}

(r/expand plan)          ; normalize-resource, pretty-printed —
                         ; rule 3's "tree at hand" as a habit,
                         ; not just a fallback
```

Detail in §5. Invariance: nothing to pin — no law is expressible
here.

### 4.3 The fingerprint diff, rendered (S)

`diff-fingerprints` and `classify-diff` already exist as data; what
is missing is the sentence layer:

```clojure
(fp/explain-diff old new)
;; law changed: data-law (overlayable — no revision ceremony)
;;   changed  :actions :finalize :guards   calendar-clear severity
;;   added    :filterable :end_date
```

This is the tool that makes "did my refactor mint a revision?" a
one-liner instead of a hash comparison — the two-spellings-one-law
invariant made *felt* at the REPL. Read-only.

### 4.4 Wire-named `defaction` + citation vector (S, pinned sugar)

Today `defaction` keywordizes the def symbol only to label its own
errors; the wire name lives solely in the registration map key —
hence three names for one verb. Let the def name *be* the wire name
(underscores are legal in symbols), stamp it as metadata (invisible
to `=` and to the fingerprint), and let `:actions` accept a vector of
def'd actions that normalize projects to the canonical map:

```clojure
;; before — the name said three times
(defhandler assign-meal [row inp _ctx] …)
(defaction assign-meal-action {… :handler assign-meal …})
:actions {:assign_meal assign-meal-action
          :finalize    {…}}

;; after — said once, cited twice
(defaction assign_meal {… :handler assign-meal …})
:actions [assign_meal assign_off_theme finalize reopen begin
          complete abandon]
```

Pure sugar: the vector projects to exactly the map the split spelling
writes, name collisions refuse at normalize, and the metadata stamp
never reaches the fingerprint. Pin: the vector and map registrations
of the same actions normalize to the same map and hash. Rule 2 cost:
converting every authored `:actions` map in mealplan10 in the same
change — mechanical.

### 4.5 The live dev loop (M, no law)

The largest gap found is not a spelling at all. `make dev10` runs
`clojure -M:dev` → `-m mealplan10.main`: a cold JVM, no nREPL port,
no reload, no reset — every declaration edit is a full restart, while
waymark7's `make dev` has had `uvicorn --reload` all along. The
`waymark10` `:dev` alias even declares `:extra-paths ["dev"]` for a
`dev/` directory that does not exist. Because declarations are plain
values, the fix is small and all pleasure:

- an nREPL server in dev mode, and a `dev/user.clj` with `(reset)` —
  re-require the resource namespaces, rebuild the registry, swap the
  engine under the running server (defonce'd);
- **the diff as narrator**: on each reset, run `explain-diff` (4.3)
  against the previous fingerprints and print what law just changed —
  *"plan: data-law, 1 path — :actions :finalize :guards (overlayable)"*
  — every save answers "what did I just do to the law" in the law's
  own classification;
- `tap>` the normalized maps so Portal users get the tree for free
  (everything here is already data — no adapter needed, which is the
  Clojure win compounding).

### 4.6 `(describe plan)` (M, read-only)

The rendered declaration — spec in §5. The state machine as ASCII
art answers principle 3 without minting any spelling: the machine
becomes *visible* before any `:machine` sugar is earned.

### 4.7 The `?` optional-entry spelling (M, pinned sugar)

Kill the double spell. A `?`-suffixed entry key projects to exactly
the split pair, the `?` stripped before the schema compiles (it never
reaches the wire, so the snake_case token law is untouched):

```clojure
;; before
[:meal_name {:optional true} [:maybe [:string {:max 200}]]]
[:meal_id {:optional true :kind :meal :label :meal_name}
 [:maybe :waymark/ref]]

;; after
[:meal_name? [:string {:max 200}]]
[:meal_id? {:kind :meal :label :meal_name} :waymark/ref]
```

~15 sites in plan.clj alone. Pure sugar with one edge to hold: the
spelling must project to *optional key + nullable value* always —
a field wanting one without the other keeps the split spelling
(rule 2 exempts it as the normal form). Pin: `?` and split forms
normalize and hash identically; the published JSON Schema unchanged.

### 4.8 Part-shaped handlers (M/L — a mechanism, not sugar)

Every placed handler in both v9 and v10 performs the same dance: find
the addressed part, update it, put it back (`_day(r, inp.date)` /
`update-day`). The engine already knows the scope and the key — a
part action's handler could receive *the part* and return *the part*:

```clojure
;; before — row-shaped, the dance by hand
(defhandler assign-meal [row inp _ctx]
  (update-day row (:date inp) #(assoc % :meal_id (:meal_id inp))))

;; after — part-shaped: the day in, the day out
(defhandler assign-meal [day {:keys [meal_id]} _ctx]
  (assoc day :meal_id meal_id))
```

This is the single biggest hand-feel win available — it deletes the
`update-day`/`_day` helper from every resource with parts, forever —
and it is **not fingerprint-invariant**: the handler's canonical form
is the law, and a part-shaped form is a different form. It is a
*mechanism* (a second handler calling convention, declared — e.g. the
convention rides `defpart`, whose actions are part-shaped by
definition), and converting an existing resource mints a revision
honestly. Per the spelling law's own closing sentence: mechanisms are
versions, not sugar. Worth it — flagged so it is chosen with eyes
open, ideally bundled into the next change that touches those
handlers' law anyway.

### Not yet earned: a `:machine` spelling

The scattered `:from`/`:to` pairs mean no one can *see* the
lifecycle — but no reading failure is recorded yet, and `describe`'s
ASCII machine may dissolve the itch on the read side for free. If it
doesn't — if the *authoring* hand still wants to write arrows — that
is the ledger entry, and the spelling would need the rule-2 sweep
(from/to leave every action in the same change). Wait for the
friction; the tool comes first.

## 5. The describe/REPL spec

What exists today, honestly: def-site refusals that teach
(`defaction`/`defpart` sentences), the checks' named refusals
(`plan [:tokens] …`), usability warnings on `*err*` at `defresource`,
422s/409s on the wire that answer "what would a competent person do
next", the affordance-following CLI (whose `print-doc`/`print-actions`
are the only human-rendered views in the codebase), and
`diff-fingerprints`/`classify-diff` as data. What is missing is the
*domain-words REPL*: nothing renders a declaration, a machine, or a
verdict for the author's eyes.

One namespace — `waymark10.describe` — everything a pure function of
the **normalized** map (so every spelling describes identically:
rule 3 with a nice face), everything returning data that carries a
rendered string, printed at the terminal and `tap>`-able for Portal:

**`(describe plan)`** — the whole declaration as a page:

```
plan — Week of {data.start_date} · {data.weeks} wk · {state}

  draft ──finalize──▶ planned ──begin──▶ active ──complete──▶ done
    ▲                    │
    └──────reopen────────┘        draft/planned/active
                                    ──abandon──▶ abandoned

fields
  start_date   date     filter eq,range · sort default-desc
  end_date     date?    derived end-date · filter eq,range
  days         [day]    part (key :date) · 7 placed actions
  all_days_covered bool? derived — gates finalize
  …

parts
  day (key :date) — coverage: meal | eating_out (clears)
    assign_meal, assign_off_theme, set_sunday_theme,
    mark_eating_out, clear_day, add_side_dish, remove_side_dish

guards
  date-in-plan        judges date     "{date} is not a day of this plan."
  meal-fits-day       judges meal_id,date  reads meal
  calendar-clear      WARNS           "{n} calendar conflict(s)…"
  …
```

**`(describe plan :assign_meal)`** — one verb: from/to, guards with
their sentences and remedies, input fields, safety in words
("idempotent overwrite, no confirm"), display.

**`(expand plan)`** — `normalize-resource`, pretty-printed with the
projected keys highlighted (what the spelling added, where it went) —
the spelling ledger's rule 3 as a one-word habit.

**`(try-guard g row)` / `(try-guard g row inp ctx)`** — evaluate one
guard against an in-memory row, render the verdict with the guard's
own explain, the accepts set, remedies, becomes-available. The fake
ctx defaults to no `:read`/`:find` (the honest probe) and accepts
stubs: `(try-guard theme-in-rotation row {:read {[:rotation rid] {…}}})`.

**`(try-action plan :assign_meal row inp)`** — the full dry-run
pipeline (state gate, input validation, every guard) without a
server or a store: the row in, the would-be row and every verdict
out, refusals rendered as their wire problems would read. The 422
becomes something you *play with* before it exists.

**`(diff plan plan')`** — 4.3's rendered fingerprint diff, and the
same function the dev loop's reset narrator (4.5) calls.

The composition target, spelled as a feeling: edit a guard's explain,
save, and the terminal says *"plan: data-law, 1 path (overlayable)"*;
type `(try-action plan :finalize row {})` and read the calendar
warning's exact sentence; type `(describe plan)` and see the machine.
The framework answering in the domain's own words, four seconds after
the thought — the same four seconds beat 1 of the story gives Priya.

## 6. What cuts against the charter's assumptions

Recorded because the charter asks for evidence, not agreement:

- **The wall is half syntax, half silence.** The charter names "the
  defresource wall" as the failing spelling, but the sharpest
  joy-gap found is the feedback loop: dev10 boots a cold JVM with no
  REPL port, no reload, no describe, no rendered diff. waymark7 had a
  hot loop; v10 does not. Several proposals above buy more joy per
  effort than any spelling change could.
- **"Style can be play" stops at the fn boundary.** The fingerprint
  hashes handler and guard-fn canonical forms — by design, and
  rightly — so respelling a fn body is a revision, not play. The
  two-spellings-one-law freedom covers declarations only. Worth
  saying out loud in the spelling law's doc so nobody discovers it as
  a betrayal.
- **v9 is not uniformly the pleasure baseline.** Its joy is
  concentrated at the decorated verb; its data half carries genuine
  ugliness v10 already fixed (the `SkipJsonSchema` field-hiding hack,
  the enum ceremony, builders far from their fields, the whole `E`
  operator DSL faking what forms do natively). "Losing to waymark9"
  is true at one seam, not overall — and the fix list is
  correspondingly narrow.
- **Line counts go up, and that was already accepted.** The batch-G
  field note recorded it: the wins are locality, not brevity. Any
  proposal sold on character count alone (the `?` spelling comes
  closest) should be adopted for the *repetition* it removes, not the
  lines — repetition is what the hand resents, per the defpart
  evidence.
- **One assumption in this research's own framing was wrong:** the
  resource files *do* use the `;; ── ──` section bars. The missing
  breath is inside the literals, between the entries.
