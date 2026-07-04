# Usability roadmap: rules the framework should verify next

Where the human-usability program stands and where it goes. Companion to
`implementation-notes.md` (what exists) and `format.md` (the wire contract).

## The doctrine (established, four case studies deep)

Human usability became enforceable the moment we stopped treating it as taste
and started treating it as **honesty about what the server already knows**:

> Everything the server knows, the human must be able to see without
> decoding — and every usability requirement must be expressed as an
> obligation on server-emitted data, never as a guideline for clients.

Every shipped rule follows the same template, and every future rule should:

1. **The defect**, stated as what a human experiences (a date picker offering
   impossible dates; a blank that a guard will grade; a UUID where a name
   belongs).
2. **The mechanism** — a declaration that lets the server emit the missing
   knowledge (`admits`, `scope`, `x-display` reference hints).
3. **The verification**, at the earliest tier that can see the violation:
   - *import time* (`Resource.__init_subclass__`) — single-resource facts;
   - *assembly time* (Engine/registry) — cross-resource facts (`opaque_ref`);
   - *conformance time* (`pytest --waymark`) — rendered-surface and empirical
     facts (the schema-guard gap fuzzer);
   - *runtime* — facts only real traffic can reveal.
4. **The waiver** — every warning is either fixed by a mechanism or
   acknowledged in code (`waives=…`, `x-display {raw: true}`), never silently
   ignored.

The recurring tell that a rule is real: **fixing it for humans improved the
agent surface too** (tighter schemas, smaller tool inputs, honest per-part
tools). If a proposed rule helps only one audience, look harder.

Shipped so far: `admits` (document- and server-derivable acceptance sets →
schema enums), `scope`/`parts` (affordances on the item the user is looking
at, keys pre-bound, dependent picker params), `prefill` (edit forms open
holding current values, fenced by If-Match), server-side drafts
(`draft=True`: effort persists in the envelope, across devices, visible to
assisting agents), the `open_input` / `altitude` / `opaque_ref` /
`long_text` / `blank_edit` / `unfenced_edit` / `large_effort` warnings, and
the conformance `schema_guard_gap` / `prefill_truth` / `draft_protection`
tests.

---

## Tier 1 — import-time rules (single resource, cheap, next up)

### 1.1 Irreversible-without-confirm (`one_way_door`)

**Defect:** a button that cannot be undone and doesn't pause. The #1 trust
destroyer; currently `reversible=False, confirm=False` is legal and silent
(e.g. `plan.complete`, `grocery_list.complete` — defensible, but nobody was
made to say so).
**Mechanism:** none needed — safety fields already exist.
**Verification:** warn when an action declares `reversible=False,
confirm=False` and its effect leaves the current state (self-loops like
`assign_meal` are exempt: re-assignment is its own undo). Waive with
`waives=("one_way_door",)` for genuinely low-stakes transitions.
**Effort:** trivial; mostly deciding the self-loop/terminal exemptions.

### 1.2 Confirm must explain (`blind_confirm`)

**Defect:** a confirm dialog that asks "are you sure?" without saying what
happens. `abandon` confirms but carries no `display.description`.
**Rule:** `confirm=True` requires `display.description` stating the
consequence (and SHOULD mention `effect.emits` side effects — "an email will
be sent").
**Verification:** import-time warn; conformance can additionally assert the
generic UI dialog renders the description.
**Effort:** trivial.

### 1.3 Action-bar budget (`crowded_state`)

**Defect:** seven buttons, two of them blue. Humans scan by visual hierarchy;
the server controls it via `display.style`/`order`/`group`.
**Rule:** per (resource, state): at most one `style: primary` among
renderable actions; more than N actions (start at 5) requires `display.order`
on all and `display.group` on the overflow; `style: danger` implies
`confirm=True`.
**Verification:** import-time for the static parts (danger⇒confirm, order
coverage); conformance for the per-state primary-uniqueness (availability is
state- and principal-dependent — walk the state factories and count primaries
in each rendered doc).
**Effort:** small. Note `plan:draft` currently renders `assign_meal`
(primary) + `finalize` (primary) in the same state — this rule would have
flagged it before scoping moved `assign_meal` into parts.

### 1.4 Decision-shaped booleans (`fork_as_field`)

**Defect:** `approve(condition_ok: bool)` — a form field that is secretly a
fork in the road, where `false` earns a 409. The human filled out a form and
got scolded; the honest shape is two actions (`approve` / `reject`), which
this app *also* has, making the boolean redundant.
**Rule:** warn when a guard denies based on a boolean input field and a
sibling transition to a different state exists from the same source state —
the boolean is probably a missing action.
**Verification:** import-time heuristic (guard `input_fields` ∩ boolean
fields, plus machine-graph lookup). Expect waivers; the finding is usually
right but the fix is a redesign.
**Effort:** small check, opinionated message.

---

## Tier 2 — assembly-time rules (cross-resource, like `opaque_ref`)

### 2.1 Vocabulary consistency (`mixed_tongue`)

**Defect:** "Cancel order" here, "Abandon plan" there, "Remove" and "Delete"
for the same intent; `cancelled` and `canceled` in different state machines.
Humans build a mental dictionary per app; every synonym is a page of it torn
out.
**Rule:** across the registry — same action name ⇒ same `display.label`
casing/verb; state tokens sharing a name ⇒ same `display.state.label`; flag
near-duplicate labels for different action names (Levenshtein or a small
synonym list: cancel/abandon/remove/delete).
**Verification:** assembly-time warn, listing the conflicting declarations
side by side. Waiver: a registry-level `vocabulary` allowlist.
**Effort:** small; the interesting work is keeping the near-duplicate
heuristic quiet enough to trust.

### 2.2 Referential integrity of references (`dangling_ref`)

**Defect:** now that `meal_id` renders as a navigable link, a stale id
renders as a link to a 404 — worse than the raw token was.
**Rule:** every value in a field declared `widget: "resource"` must resolve.
**Verification:** conformance — walk factory states, collect declared
reference fields (the `opaque_ref` machinery already finds them), `GET` each
target, assert 200/`hidden-404`. Runtime — the transition log could flag
writes that orphan references (deferred; needs the report CLI, see 4.2).
**Effort:** small conformance test; reuses everything.

### 2.3 References become first-class (`Ref[kind]`)

Not a rule — the mechanism that retires three heuristics. A typed reference
field on Data/input models:

```python
meal_id: Ref["meal"] | None = Field(default=None, label_field="meal_name")
```

generates the `x-display` reference hint (data render), the picker widget
(input render), the `links` entry where sensible, and gives `dangling_ref` /
`opaque_ref` a declaration to trust instead of a `{kind}_id` naming
convention. The lexical heuristic stays as the lint that tells you to use
`Ref`.
**Effort:** medium (pydantic custom type + schema generation touchpoints);
highest leverage item in this tier.

---

## Tier 3 — conformance rules (rendered-surface and empirical)

### 3.1 Prose hygiene (`token_prose`)

**Defect (live, unfixed):** `plan.py:120` — "Meal {meal_id} is not on the
meal list" renders a UUID into a sentence a human must read. Same class:
state tokens or snake_case leaking into `reason` / `detail` / labels.
**Rule:** human-facing strings (`summary`, `unavailable.reason`, problem
`detail`, all `display` strings) MUST NOT contain UUIDs, `snake_case` tokens,
or unresolved `{placeholders}`; SHOULD end reasons with sentence punctuation.
**Verification:** conformance — the suite already renders every state and
provokes every refusal; add regex assertions over the collected strings.
Import-time can pre-check `else_` templates for id-shaped vars (a `{x_id}`
placeholder in prose is suspect — say the *name*, or use `remedies`).
**Fix pattern:** guards deny with names, not ids (`meal_matches_theme`
already does it right: "'Tacos al pastor' is mexican; …").
**Effort:** small; immediately finds a real bug.

### 3.2 Summary quality (`orientation`)

**Defect (live, unfixed):** `return_workflow.py:67` — `summary = "Return
{id} · order {data.order_id} · {state.label}"` is a sentence made of tokens.
The summary is the one string every list row, link preview, and event feed
shows; it must orient, not identify.
**Rule:** summary templates MUST NOT interpolate `{id}` or `{…_id}` fields
(reference the referent's denormalized name instead); summaries MUST differ
across states (render each factory state, assert pairwise distinct — a
summary that never changes isn't summarizing); stays within the 140-char
budget (already enforced).
**Verification:** import-time for the template shape; conformance for
state-distinctness.
**Effort:** small. Will force `Return`/`Shipment` to denormalize something
human (which order? say the total or the customer) — that friction is the
rule working.

### 3.3 Errors are exits, not walls (`dead_end_error`)

**Defect:** §7.2 promises every error answers "what would a competent person
do next," and guards can declare `remedies` — but nothing verifies the
rendered problem carries them, and nothing checks a 409's `actions` are
non-empty when remedies exist.
**Rule:** a guard refusal whose guard declares `remedies` must surface them
in the problem document as follow-able affordances; per-field `errors` keys
must be real schema fields (a misspelled key means the inline validation
highlights nothing).
**Verification:** conformance — the transition-truth tests already provoke
refusals; extend assertions. The `errors`-keys check catches typos like
`errors={"rotation_id": …}` on an input that has no `rotation_id` field
(live in `theme_in_rotation`, worth checking).
**Effort:** small.

### 3.4 Someone can always act (`stranded_state`)

**Defect:** a state that is structurally live (has transitions — the machine
checker guarantees it) but *practically* dead for every principal because
guards hide/deny everything. A human lands on a page with zero buttons and no
explanation.
**Rule:** for every non-terminal factory state, at least one principal
profile sees at least one action or one `unavailable` entry with
`becomes_available` — silence is never the whole story.
**Verification:** conformance walk across `wm.principals`.
**Effort:** small; the fixture machinery exists.

### 3.5 Cross-field constraints advertised (`silent_relation`)

**Defect:** the schema-guard gap fuzzer deliberately skips multi-field pure
guards (start/end ranges, sum limits), so "end before start" still surfaces
as a post-hoc 409 instead of form guidance.
**Mechanism:** `relates=("start_date", "<=", "end_date")` on the guard →
rendered as `x-display.relation` on both fields; the generic UI sets
`min`/`max` attributes between the inputs; dry-run remains the enforcement.
**Verification:** extend the gap fuzzer — a pure two-field guard with a
declared relation must accept every sample satisfying it; one *without* a
declaration that refuses >50% of schema-valid pairs is a gap.
**Effort:** medium; the first mechanism since `admits` that adds wire
vocabulary.

---

## Tier 4 — beyond static verification (the honest hard part)

### 4.1 The text-only comprehension probe (`waymark check --judge`)

The reason LLM-usability is easy to validate is the closed loop: agent +
envelope + task. The equivalent loop for humans: drive the generic UI with a
model that sees **only rendered visible text** — no JSON, no schemas — and
give it the tasks the agent conformance completes via `mcp_tools()`.
**Invariant:** any task completable through the tool projection must be
completable through the rendered UI using only visible words. Where the
text-only judge stalls, the transcript names the missing label or reason.
Opt-in tier (tokens, nondeterminism): `waymark check --judge`, reported like
warnings, never a CI gate by default. This is the only check in the program
that can catch *comprehension* failures — everything above catches
*information* failures.

### 4.2 Regret telemetry (`waymark usability-report`)

The transition log already records `actor.type ∈ {human, agent, system}`
per transition — a built-in A/B instrument nobody is reading. Ship a report:

- **dry-run failure rate per field**, human vs agent → confusing forms;
- **409/412 rate per action** → stale UIs, unclear preconditions;
- **invoke-then-reverse within N minutes** ("regret") → actions that needed
  `confirm` or better descriptions;
- **unavailable-viewed → attempted anyway** (via problem logs) → reasons that
  don't read as reasons.

The defining signal: **agents succeed where humans stumble ⇒ the semantics
are fine and the presentation isn't** — a usability defect by definition,
detected in production without a single opinion. Needs: a problem/dry-run
log (transitions alone miss failed attempts) and the report CLI.

### 4.3 Localization coverage (the standing §12 deferral)

Every rule above hardens *English* prose. The i18n story (extract-messages,
per-locale conformance coverage of `summary`/reasons/labels) multiplies each
prose check across locales. Sequence it after `token_prose`/`orientation` so
there are fewer strings to extract twice.

---

## Suggested order

| # | Item | Tier | Why now |
|---|---|---|---|
| 1 | `token_prose` + `orientation` (3.1, 3.2) | conformance | two live bugs already found; smallest effort-to-truth ratio |
| 2 | `one_way_door` + `blind_confirm` (1.1, 1.2) | import | trivial, high trust value |
| 3 | `dead_end_error` + `stranded_state` (3.3, 3.4) | conformance | closes §7.2's unverified promise |
| 4 | `Ref[kind]` + `dangling_ref` (2.3, 2.2) | assembly | retires heuristics, adds integrity |
| 5 | `mixed_tongue` + `crowded_state` (2.1, 1.3) | assembly/import | polish the whole surface at once |
| 6 | `silent_relation` (3.5) | conformance | last structural gap in error prevention |
| 7 | judge + telemetry (4.1, 4.2) | runtime | after the static net is tight, measure what it can't see |

The through-line, worth writing into the spec when any of this is upstreamed:
a guard firing on information the server had at render time is a usability
defect; a token shown where a name was known is a usability defect; a refusal
without an exit is a usability defect. All three are detectable precisely
because Waymark makes servers *declare* what they know — the checks just hold
the declarations to account.
