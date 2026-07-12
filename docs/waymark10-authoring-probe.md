# The authoring probe — DX, verified

The hand-in-hand charter's commitment, turned into a test that can fail:

> **An owner forced down into framework internals to make his application
> pleasant is the framework failing this commitment.**
> — docs/waymark10-hand-in-hand.md, "The charter"

The usability roadmap's text-only comprehension probe (§4.1) validated the
*app user's* surface by driving it with only rendered words. This is the same
instrument pointed at the *framework author's* surface: author a small new
resource using only what the framework itself offers, and log every moment
the rule breaks. Companion machinery: `waymark10.dsl` (the one require),
`waymark10.dev` (the REPL that answers in the domain's words),
`waymark10.check` (`make check10`), the shipped clj-kondo exports, and the
declaration gate's path-carrying errors.

## The protocol

1. **Pick a small honest kind** — two or three states, one derived fact, one
   confirm gate. It must be real enough to want a clock or a ref.
2. **Author it under the rule:** `waymark10.dsl` + `waymark10.dev` + error
   messages + kondo findings + `docs/` only. **Framework source is
   off-limits.** Reading a *neighboring app resource* is allowed (that's
   culture, not internals) but gets logged as a lean.
3. **Drive it to done** in a scratch engine: create, act through every door,
   `why-not` a refusal, flip the derived fact, `explain` the result.
4. **Log every violation** as a defect naming the missing affordance:
   - a source dive into `waymark10/src/**` — defect, always;
   - a lean on a neighboring resource for *vocabulary* (not style) — defect;
   - an error message that didn't say what to do next — defect;
   - a REPL answer that had to be decoded rather than read — defect.
5. **File the log below.** The defect list is the next DX round's backlog —
   the probe is rerun after each round until a run comes back empty.

The rubric is the charter's own question: *would the owner still rather
write the next resource himself?*

### Template

| # | Moment | Expected | What happened | Defect? |
|---|--------|----------|---------------|---------|
| 1 | …      | …        | …             | …       |

---

## Run 1 — 2026-07-12, the `leftover` kind

Specimen: `mealplan10/dev/leftover_probe.clj` (off the app classpath;
loads under `clojure -Sdeps '{:aliases {:probe {:extra-paths ["dev"]}}}'
-M:probe`). Three states (`fridge → eaten | tossed`), a clock-flipped
`past_eat_by` fact, a confirm gate on `toss`, one recorded deviation.

Outcome: authored and driven to done in one sitting. The scratch loop —
`scratch!` with a held clock, `create!`, `why-not`, `act!`, `explain` —
answered in the declaration's own sentences throughout; the derived fact's
`next-flip-at` landed on the `eat_by` boundary without any prompting. The
run was **not** clean of leans, and one answer needed decoding:

| # | Moment | Expected | What happened | Defect? |
|---|--------|----------|---------------|---------|
| 1 | Writing `past_eat_by`'s law | Somewhere to look up the expression vocabulary and the clock spelling | The legal ops (`var`, `date-of`, `<`, …) and the ":now in `:over` binds the clock" convention exist only in `expr.clj` and in a *comment in prep_task.clj*. Leaned on the neighbor; a fresh author would have opened framework source. | **D1 — the expression vocabulary needs a reference page** (ops table + the `:over`/`:now` convention + one worked clock fact), generated or hand-kept under `docs/`. |
| 2 | Choosing entry properties (`:filter`, `:sort`, `:derived`, `:kind`) | The colocated-law key list, with meanings | Kondo and the declaration gate name a *wrong* key precisely, but nothing lists the *right* keys with what they mean; learned from neighbors again. | **D2 — a colocated-law reference** beside D1 (the four law keys + `:x-display` + ref props), or a `dev/vocab` REPL word that prints it. |
| 3 | Reading `act!`'s return | The transition, readable | The raw engine map — `#object[java.time.Instant …]`, digests, nils — correct but decoded, not read. | **D3 — `act!` (or a `dev/last-transition`) should project the transition the way `explain` projects the law:** actor, door, from→to, summary, one line. |
| 4 | `walk!` from a bare REPL | The docstring's boundary sentence | A raw `FileNotFoundException: clojure/test/check/generators` — the sentence lives in the docstring, not the error. | **D4 — catch the missing-dep case in `walk!` and re-throw with the boundary sentence** ("walk! needs test.check — run under -A:test"). |
| 5 | Import-time warnings during authoring | Warnings visible when I look | They print to `*err*` at load and scroll away; `explain` re-shows them (⚠), which saved the moment — but only because I knew to call it. | Half-defect — **D5: say "warnings ride the value; `(dev/explain x)` re-reads them" in the defresource refusal/warning footer**, so the recall affordance advertises itself. |

Score: the skeleton held — no source dive was *forced* for the machine,
the doors, the gate, or the drive loop. Both leans were **vocabulary**
lookups (D1, D2): the framework enforces a language it still doesn't
teach. That is the whole next round.

## Backlog seeded by run 1

1. **D1/D2** — one reference page (or `dev/vocab`): expression ops,
   colocated-law entry properties, the clock convention, each with one
   worked example. Highest leverage; both leans die.
2. **D3** — transition projection on `act!`'s return (or `dev/feed`).
3. **D4** — `walk!` re-throws with the boundary sentence.
4. **D5** — the warning footer advertises `dev/explain` as the re-reader.

Rerun the probe (a different small kind — something with a ref and a
cross-resource guard, to probe D1's guard half) after landing these.
