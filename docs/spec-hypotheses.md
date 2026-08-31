# Spec — hypotheses: the profile as graded belief

**Thesis.** The house already holds the EVIDENCE layer and does not hold the
BELIEF layer. Rows carry addresses; an insight cites them
(`insight/cites-what-it-claims`); a sitting indexes what a person said and a
reading reads it back (`SITTING.md` § 1, `READING.md` § 1); an outcome is an
experiment the household taps or declines, and `verdict_reason` keeps the
word it declined with. What nothing holds is a **graded, decaying, auditable
belief about the household** — a claim with a number on it that moved because
of atoms you can name. `value` has exactly one bit of this
(`observed` / `declared`, waymark-jfv.10) and no arithmetic under it. This
spec adds the belief layer in three slices, each gated on the last proving
useful, and it adds it the way this house adds numbers: **as data a person
can read and argue with**, the way `crown_rank`'s six numbers landed
(`waymark10/server/feed.clj` § `default-crown-rank`, waymark-1uv.2).

The owner's end goal, stated from day one and restated on 2026-08-30: *the
house indexes his data into a PROFILE that tunes outcomes.* This is the
design record for that. No code is written by this bead.

## Epistemic status

Written 2026-08-30 against the tree at `ebae038`, from a design settled in
conversation the same day: a Bayesian evidence-atom architecture read against
what waymark already has. It carries four honest gaps.

1. **Nothing here has run.** Unlike `spec-threads.md`, which was verified
   against a live Gate before it was written, every number below is a
   **starting guess**. The likelihood ratios are priced by argument, not by
   observation, and the first thing the household will learn is that some of
   them are wrong. That is precisely why they land as data on a row a person
   edits rather than as constants in a function.
2. **The honest test is in the epic and is a stopping condition, not a
   milestone.** Slice 1's first *what moved this week* must contain something
   the owner did not already know. If it does not, the epic stops at slice 1
   and the typed fields stay as a better-indexed record. See § *Landing order,
   and the gate*.
3. **Coverage is bounded by consent and cannot be widened later without a new
   ruling.** An atom comes only from a run that read something through the
   Gate under a grant and cited it to a row. There is no corpus pass. This is
   an accepted price, not a gap to be closed — § *What is deliberately lost*.
4. **The framework's law vocabulary cannot express this arithmetic**, and this
   spec does not grow it. `waymark10.expr/ops` (docs/waymark10-vocabulary.md
   § 2) has `+ - *`, `min max abs`, and no division and no logarithm —
   deliberately. The posterior is therefore engine arithmetic in Clojure over
   declared numbers, not a `:derived {:expr …}` tree. See fork (f).

## What exists

Everything in slice 1 is a field on a kind that already ships, plus numbers
on a row that already carries numbers. Nothing here needs a new dependency
and slice 1 needs no new kind at all.

- **`workqueue10/src/workqueue10/resources/insight.clj`** — the finding: a
  sentence, `evidence` as a vector of `/api/<plural>/<id>` addresses,
  `offer_kind` / `offer_id` / `offer_action`, `published → taken | dismissed`,
  four eyes on `authored_by` (`:decider {:not {:field :authored_by}}`), and
  no pace wall since waymark-1uv.8. This is the atom's carrier. It already
  cites; what it does not do is say **what KIND of evidence** the citation is.
- **`workqueue10/src/workqueue10/resources/value.clj`** — the affirmation
  axis: `[:observed :declared :retired]`, `:initial :observed`, the summary
  rendering `{state}` so an unaffirmed row says so wherever it is cited, and
  the wording door split by hand (`revise` is a person's and claims the row,
  `restate` is the observer's and does not). The hypothesis kind is this
  kind's machine with a number under it — § *The hypothesis kind* copies it
  deliberately rather than inventing a second affirmation grammar.
- **`waymark10/src/waymark10/server/feed.clj`** — `default-crown-rank`,
  `crown-rank-of` (deployment defaults merged under the household's own),
  `crown-rank-words`, `crown-rank-diff`, `crown-rank-says`, `judged-lift`.
  This is the **numbers-as-data precedent the LR table follows to the
  letter**: six numbers on the recipe row, defaulted in the declaration,
  diffed in the household's own words, narrated on every card they touched.
  Its own comment states the law the LR table inherits: *a household can read
  six numbers, and the moment this needs a model it cannot read, somebody is
  building the thing the law forbids.*
- **`waymark10/src/waymark10/recipe_proposal.clj`** — the door a person
  applies numbers through. An agent may create a proposal and may not create
  a `feed_recipe`; `crown_rank` / `current_crown_rank` ride it beside
  `formula` and `order` (waymark-1uv.5), and `evidence` is required, so a
  number change proposed by a machine says what it read.
- **`waymark10/src/waymark10/ranking_note.clj`** — an agent's score and one
  sentence as DATA, subject named `{subject_kind, subject_id}`, four eyes via
  the subject kind's own `:own-surface :by`, one live note per row-and-author,
  **and no affirmation axis on purpose**. Its docstring draws the line this
  spec sits on the other side of: a judgment about which bundle deserves a
  Saturday is not a fact about the house a person could confirm. A hypothesis
  IS such a fact, which is why it gets the affirmation axis and a note does
  not.
- **`waymark10/verdict-reason`** (docs/spec-outcome-menu.md § *Built —
  jfv.16*, amended by waymark-hcr) — the four words a verdict may carry, per
  subject kind, `reason-sets` keyed by kind, read back by
  `feed/insight-record` at `reason-weights`. A decline with a word on it is
  already typed evidence about a plan; this spec generalises exactly that
  move to what a person SAYS rather than only to what they tap.
- **`docs/spec-threads.md`** — thread rows as addresses, and the rule that
  makes an atom citable: **bodies are never mirrored**. A fact found in a chat
  cites `/api/threads/<id>`, and the message it came from stays behind the
  Gate.
- **`READING.md` / `SITTING.md` / `docs/spec-standing-agent.md` § *Two runs***
  — the clerk/editor split (waymark-nl0). The clerk fills forms; the editor
  writes them. This spec's whole extraction posture is that split read once
  more: **classification is a form; belief is not**.
- **`docs/spec-outcome-menu.md` § *Ranked, not capped*** (waymark-1uv.1) —
  the machine may write without limit; the rank decides what reaches a
  person. A hypothesis is a rank on the house's own claims about itself and
  inherits that law whole: nothing here grows a pace wall.

## The evidence atom — four fields on `insight`, and an LR of 1 for silence

An **atom** is a published insight that says what kind of evidence it read.
No new kind: the finding already cites rows, already carries an author,
already has a birth instant and a verdict machine. Four optional fields turn
it into evidence with a weight.

```clojure
;; on insight's :schema — all four OPTIONAL, all four advertised with help
[:evidence_type {:optional true :filter #{:eq}}
 [:maybe [:enum "unprompted_mention" "solicited_praise" "question_asked"
                "specific_detail" "costly_action" "declined_invite"
                "statement_against_interest" "complaint_while_continuing"
                "minimal_response"]]]
[:solicited {:optional true :filter #{:eq}} [:maybe :boolean]]
[:cost      {:optional true :filter #{:eq}} [:maybe [:enum "none" "low" "high"]]]
[:episode   {:optional true :filter #{:eq}} [:maybe [:string {:max 120}]]]
```

**Untyped is LR 1, and that is the whole compatibility story.** Every finding
already published, and every finding a run writes without thinking about
belief, contributes exactly nothing to any posterior — it does not lower one
and it does not raise one. There is no backfill obligation, no migration of
existing rows, and no moment where the belief layer's arrival changes what a
standing finding means. A field left nil is a sentence, not a hole:
`spec-threads.md`'s `message_count_window` decided this shape already.

### The taxonomy, and what each type is priced for

The nine types are the nine ways a household member's words or deeds
distinguish *he means it* from *he is being pleasant*. The number beside each
is the **likelihood ratio**: how much more likely this observation is if the
claim is true than if it is false.

| type | LR | what it is, and why it is priced there |
|---|---|---|
| `costly_action` | **20** (`cost: high`) / **5** (`cost: low`) | he spent money, a Saturday, or social capital on it. The strongest thing a record can hold, because talk is free and this was not. `cost: none` is refused at the door — an action that cost nothing is not a costly action |
| `unprompted_mention` | **8** | he brought it up when nobody asked. Nearly all the diagnostic power in ordinary conversation lives here, which is why `solicited` exists to keep it honest |
| `statement_against_interest` | **6** | he said something that cost him to say — an admission, a preference that makes him look bad, a plan that inconveniences him |
| `specific_detail` | **4** | he knew the part number, the trail name, the year. Detail is expensive to fake and cheap to have when the thing is real |
| `question_asked` | **3** | he asked about it. Curiosity is weaker than a deed and much stronger than agreement |
| `complaint_while_continuing` | **3** | he grumbled and kept doing it. The complaint reads as negative and the CONTINUING is the evidence; this row exists so a naive reader does not score the grumble as a no |
| `solicited_praise` | **1.05** | we asked, he said yes. Almost worthless on purpose: politeness is the null hypothesis in a family, and pricing this at 2 would let a run manufacture belief by asking leading questions |
| `minimal_response` | **0.9** | we raised it and he said little. Weak evidence against, never strong: silence has a hundred causes and only one of them is disinterest |
| `declined_invite` | **0.2** | he was offered the thing and said no. The strongest ordinary evidence against, and the mirror of `costly_action` |

**`solicited` is a discount, not a tenth type.** True means *the household
asked first*, and the atom's log-odds contribution is scaled by
`solicited_discount` (default `0.25`) — an answer to a question you put in
somebody's mouth is a quarter of the evidence of the same words unprompted.
`solicited: true` on `unprompted_mention` is refused by name at the create
door (a contradiction in terms, and the refusal says which of the two words to
change). `solicited_praise` needs no discount: the type IS the discount.

**`cost` prices `costly_action` and is recorded-but-unpriced elsewhere.**
`low` / `high` choose between the two numbers above. On any other type the
field is stored and reads at 1 — declared rather than punted, because the
field is how the household will find out whether a cost-graded scale is worth
having for the other eight (§ *Recorded punts*).

**`episode` is the independence key**, and it is the field that stops
enthusiasm from reading as proof. Its shape is *the source plus the day*:
`thread/7fda11c6 2026-08-24`, `event/9c1e 2026-08-19`. Somebody who mentions
the same trip four times in one conversation has said one thing, warmly — see
the updater's rule 2. A typed atom with no episode is counted as its own
episode (the safe direction is *fewer* discounts, never a merge the record
cannot justify), and the driver's `verify` prints a gentle line rather than a
fault.

## The LR table as data

**The numbers live on the recipe row beside `crown_rank`**, defaulted in the
declaration, merged the way `crown-rank-of` merges — the household's own over
the deployment's — proposed through `recipe_proposal` and applied by a
person's tap.

```clojure
(def default-evidence-lr
  "What each kind of evidence is worth, as the odds multiplier a person can
   argue with. Read as a likelihood ratio: how much likelier this observation
   is if the claim is true than if it is false. 1 is silence."
  {:costly_action_high 20   :costly_action_low 5
   :unprompted_mention 8    :statement_against_interest 6
   :specific_detail 4       :question_asked 3
   :complaint_while_continuing 3
   :solicited_praise 1.05   :minimal_response 0.9
   :declined_invite 0.2
   ;; the two modifiers
   :solicited_discount 0.25
   ;; how a type forgets, in DAYS — see the updater's rule 3
   :half_life_costly_action 540
   :half_life_statement_against_interest 365
   :half_life_declined_invite 365
   :half_life_specific_detail 180
   :half_life_unprompted_mention 180
   :half_life_complaint_while_continuing 180
   :half_life_question_asked 90
   :half_life_solicited_praise 60
   :half_life_minimal_response 60
   ;; the walls on the arithmetic itself
   :episode_intensity 1.5   :log_odds_clamp 6})
```

**Why the recipe row and not a kind of its own.** These are the same class of
thing as `crown_rank`: numbers that decide what a person is shown, editable in
a form, diffable in the household's own words, and never a model. Putting
them anywhere else would mean a second staleness wall, a second diff
vocabulary and a second apply door for one map — and `recipe_proposal`'s own
docstring already carries the argument for why one door serves every set of
numbers on that row. The honest cost, recorded: **the recipe row stops being
only the feed's editorial frame and becomes the house's table of numbers a
person may argue with.** That widening is stated here so nobody discovers it
later as a surprise.

`evidence-lr-of` mirrors `crown-rank-of` exactly; `evidence-lr-diff` mirrors
`crown-rank-diff`, so a proposal that raises `unprompted_mention` from 8 to 12
says *"an unprompted mention now counts half again as much"* and not
`{:unprompted_mention 12}`. `evidence_lr` / `current_evidence_lr` ride
`recipe_proposal` beside `crown_rank` / `current_crown_rank`, and the same
staleness wall asks about both.

**Half-lives are per type because forgetting is per type.** A Saturday spent
building something is still evidence eighteen months later; a polite yes at
dinner is not evidence at all by autumn. One global decay would have made the
weakest evidence outlive its usefulness and the strongest evidence expire
before the household's own memory of it.

## The hypothesis kind

Slice 2. An **advertised belief, ranked below anything declared** — the epic's
wall, and the sentence that decides every fork below.

```clojure
{:kind :hypothesis :plural "hypotheses" :nav :secondary
 :states [:observed :affirmed :dismissed :retired] :initial :observed
 :summary "{data.claim} · {data.shape} · {state}"
 [:claim        string 1..240]      ; the sentence, in household words
 [:shape        enum "interest" "intent" "pattern" "relationship" "gap"]
 [:about        vector of /api/<plural>/<id> addresses]  ; a person, a value, a thread
 [:prior        decimal 0.02–0.5]   ; where the belief started, stated at birth
 [:posterior    decimal, DERIVED]   ; log-odds folded from the atoms — no door sets it
 [:atoms        vector of {insight_href, evidence_type, lr_applied, at}]  ; engine-written
 [:last_moved   instant]
 [:movement_7d  decimal, DERIVED]   ; the same fold with the clock moved back
 [:observed_by  principal]  [:affirmed_by …]  [:affirmed_at …]}
```

**`shape`, not `kind`.** `kind` is the row envelope's own word and a data
field wearing it would read as the row's type everywhere a card is rendered —
`spec-threads.md`'s `chat_kind` decided this and the reason transfers whole.

**`about` is a vector of ADDRESSES, not typed refs.** The claim is about
whatever the record holds — a person row, a value row, a thread, a task — and
the address is the one shape a citation may wear in this house
(`insight/row-address`). A typed ref would force one kind per hypothesis and
would refuse the interesting claims, which are exactly the ones that span two
kinds.

**The states are `value`'s machine with the arithmetic under it**, and every
door is jfv.10's door with its own reason restated:

| from | door | to | who |
|---|---|---|---|
| — | `observe` (create) | `observed` | a reading. Born observed, and it says so wherever it is cited — `value`'s `{state}` summary trick, for the same reason: a birth nobody's hand is on reads as a guess |
| `observed` | `restate` | `observed` | **the observer only** (`only-the-observer-restates`, jfv.10's mirror wall). The reading learns more and rewords its own guess; the row is still a guess when the handler is done |
| `observed` | `still_stands` | `affirmed` | **a person** (`written-by-a-person`), or an agent under a grant naming `hypothesis.still_stands` (`g/unless-granted`, waymark-sfe), never the row's own observer |
| `observed` / `affirmed` | `revise` | `affirmed` | a person. Rewording claims it, in one stroke — jfv.10's ruling read literally |
| `observed` | `dismiss` | `dismissed` | a person. *You read me wrong* — a different sentence from *this was true and is not now*, and the composer reading the log must be able to tell them apart |
| `affirmed` | `retire` | `retired` | a person. It was true and is not now |

**Nothing about the posterior changes with the state.** An affirmed
hypothesis still folds its atoms; the affirmation says *this house agrees the
claim is worth holding*, not *stop reading evidence*. What the state buys is
the crown's tier (§ *What merges*): a person's yes outranks a number,
always.

**No door sets `posterior`, `atoms`, `movement_7d` or `last_moved`.** They
are engine-written from the atoms, and the walls are structural: the fields
are absent from `:create-schema`, absent from every action `:input`, and the
updater writes them through the engine's own pass. A hypothesis is a
**cache of an arithmetic anyone can redo**; delete every posterior in the
store and one pass rebuilds them identically.

**`not-a-second-belief`**, a `(:find ctx)` create guard in `not-a-twin`'s
shape (waymark-8gc): a candidate whose `about` set overlaps a standing
hypothesis of the **same shape** is refused, naming that row's address and
the shared address. Two beliefs about the same thing cannot both move; they
split their evidence and neither says anything. Restate the standing one.

## The updater — three deterministic rules

The whole of the arithmetic, and it is small enough to check by hand, which
is the point. Given a hypothesis and its atoms:

**1. Log-odds addition.** `posterior_logodds = logit(prior) + Σ ln(LR_i)`,
where each atom's `LR_i` is the table's number for its type, `cost`-graded
for `costly_action`, and scaled by `solicited_discount` in log-odds when
`solicited` is true. Addition in log-odds is multiplication of odds, which is
Bayes with independent evidence and nothing else. `posterior` is stored as the
probability for reading and the log-odds for arithmetic. **Clamped at
±`log_odds_clamp`** (default 6, about 0.25%–99.75%): no finite pile of atoms
becomes certainty, because a belief that reaches certainty stops reading
evidence, and this house's posture is that the system proposes and never
believes.

**2. One count per episode, with a 1.5× intensity.** Atoms sharing
`(hypothesis, evidence_type, episode)` collapse to ONE contribution; if there
were two or more, the survivor's log-odds is multiplied by
`episode_intensity` (default 1.5) and no further — enthusiasm in a single
conversation is *warmth*, not four independent observations. A missing
`episode` is its own episode. This rule is what stops one excited evening from
looking like a month of consistent behaviour, and it is the reason `episode`
is a field rather than a derivation: only the run that read the source knows
what the source was.

**3. Decay by type, toward the prior.** Each atom's log-odds contribution is
multiplied by `2^(−age_days / half_life[type])`. As every atom decays its
contribution approaches zero, which in log-odds is LR 1, which leaves the
prior — so a hypothesis nothing has fed for two years does not *reverse*, it
**forgets**, which is the honest thing for a record to do about a person who
has changed. Decay is computed at read time from `at` on each atom; nothing
is rewritten nightly except the cached posterior.

**`movement_7d` is rule 1 twice.** The same fold with the clock set back
seven days, subtracted from the fold today. No stored history, no weekly
snapshot kind: the atoms carry `at`, and *what moved* is a question about the
same numbers asked twice. Slice 1 computes exactly this on the fly, with no
hypothesis rows at all — the atoms grouped by the rows they are ABOUT.

## What merges

Six things the house already does become inputs or outputs of this layer.
None of them is a new mechanism; each is an existing one pointed at the
belief.

| what exists today | what it becomes | the reason it merges rather than sits beside |
|---|---|---|
| **`value`'s `observed` state** (jfv.10) | an **intent hypothesis** with a posterior. Slice 2 migrates: every observed value gets a hypothesis whose claim is the value's `says`, and `value` keeps `declared` / `retired` | the observed state IS a one-bit belief with no arithmetic and no atoms. Two belief systems on one row is the outcome nobody wants; jfv.10's sections get dated ruling notes rather than edits, the reversal convention this file already uses |
| **diagnosis insights** (waymark-me9, `no-burial-without-a-diagnosis`) | a **declined atom** on the hypothesis the buried plan tested | a decline of a plan that was staged to test a belief is evidence about the belief, priced at `declined_invite`. Today that signal ends in prose the rank cannot read |
| **the contradiction probes** (waymark-63s: `stale-relative-date`, `far-event-names-a-task`, and the two the reading reads from the brief) | **gap queries** — an `interest` or `intent` hypothesis that its `pattern` sibling disagrees with | a contradiction between rows is exactly a gap between what was said and what was done, which is slice 3's whole section. The probes stop being four hand-written shapes and become one query over disagreeing posteriors |
| **the reading's HOUSE BRIEF** (`READING.md`, waymark-xnf/wfa) | gains **WHAT MOVED THIS WEEK**: the ten biggest `movement_7d` with their atoms listed under each | the brief is already the place a reading learns what it did not know, printed before any order and read whole. A separate screen would be a second place to look and would not be read |
| **a person mentioned in a thread** (`spec-threads.md`'s observed births, `person.clj`) | a **relationship hypothesis** about them | the roster already grows on its own from what a run read; who matters to this household is the same question one level up, and the atoms are the same thread rows |
| **the crown's `:declared` weight** (`feed/default-crown-rank`, `value-standing`) | reads the **posterior** of the value's intent hypothesis for the observed case; `declared` stays a **TIER** | a declared value is a person's word and no number may outrank it — `crown-key`'s own rule about *asked for*. What the posterior replaces is the flat treatment of every observed value as equally uncertain |

## What is deliberately lost

Four capabilities a system like this normally has, refused here on the
record, so they are not re-proposed.

1. **Full-corpus extraction. Coverage is bounded by consent.** An atom exists
   only because a run read something through the Gate, under a grant a person
   approved, and cited it to a row. There is no sweep of the mail, no index of
   every message, no background reader. This costs real coverage — the
   household says things nothing will ever read — and the price is accepted,
   because the alternative is a system whose reach nobody consented to and
   whose bodies would have to be mirrored to be re-read. `spec-threads.md`
   already paid this price for the thread kind and named it the thesis.
2. **Embedding-based entity resolution.** Two claims that mean the same thing
   in different words stay two rows until a **reading** merges them by
   `restate`. No vector index, no similarity threshold. `not-a-second-belief`
   compares ADDRESSES, exactly as `not-a-twin` does, and for the same recorded
   reason: a door that judged whether two claims *say the same thing* would be
   a door guessing, and its refusal could not name the offending row.
3. **Confidence-based routing.** No behaviour changes because a posterior
   crossed a threshold — nothing is auto-sent, auto-composed or auto-hidden at
   80%. What routes work in this house is **stakes**, which a person labels,
   and a tap. A number that silently changed what the machine did would be the
   ranking model law 6 exists to keep out.
4. **The system believing anything.** A hypothesis is a **proposal for an
   experiment**, and slice 3 makes that literal: a near-50% belief gets a
   cheap-test outcome. The house's own compression, from `ranking_note`'s
   docstring one kind over: an agent's number is *a nudge, never a verdict*.
   Nothing acts without a tap, and a hypothesis ranks below anything declared,
   always.

## The extraction-blind rule

**The clerk fills the types and never sees a posterior.**

The sitting classifies: when it indexes a fact a person said (`SITTING.md`
§ 1, the manifest's `candidate_facts`), it fills `evidence_type`, `solicited`,
`cost` and `episode` from the words in front of it, guided by examples in the
skill and the formula. That is a **form** — one row, one door, the material
inline — which is exactly what the clerk's run is for (waymark-nl0).

What the sitting never receives is **any belief the classification feeds**:
no posterior on its manifest, no `hypothesis` line in its leash, no movement
block. The reading reads those; the sitting does not.

**Why, in one sentence:** a classifier that can see the belief it is feeding
starts confirming it, and the LR table is worthless the moment the type is
chosen to move a number rather than to describe what was said. The separation
is not a courtesy — it is the only thing that makes the arithmetic mean
anything, because every likelihood ratio above assumes the type was assigned
by somebody who did not know what it would do.

Two consequences worth spelling out. **The reading may not retype an atom to
move a hypothesis** — it may dismiss a wrong finding (`insight.dismiss` with
a `verdict_reason`, `READING.md` § 8) and the dismissed atom leaves the fold,
which is a public act on the record with a word attached, where a quiet
retype would not be. And **no run scores a hypothesis its own atoms built**:
four eyes on `observed_by`, `ranking_note/not-your-own-row`'s idiom, so the
run that read the evidence is not the run that affirms what it means.

## The backfill rule

**One reading, over thread history, establishing priors — and it never stores
a body.**

A house with no atoms has no beliefs, and waiting for organic accumulation
means the first *what moved this week* is empty for a month, which fails the
epic's own test for the wrong reason. So slice 1 admits **one backfill
reading**: a run that walks the thread rows it can reach, reads their history
through the Gate under the ordinary sitting grant, and publishes typed
insights for the facts it finds.

The rules on it, all of which are existing law rather than new permission:

- **It reads; it never mirrors.** Bodies stay behind the Gate. What lands is
  a finding — one sentence — citing `/api/threads/<id>` and whatever rows it
  joined. `spec-threads.md`'s thesis, unamended.
- **Its atoms are dated by the SOURCE, not by the run.** An atom's `at` is
  when the thing was said, so decay reads the household's history correctly
  and a backfill does not manufacture a wall of fresh evidence. This is the
  single field a backfill gets that ordinary indexing does not, and it is a
  fact the run read rather than a fact it chose.
- **Episodes are the source and the day**, so a backfilled conversation
  collapses under rule 2 exactly as a live one does.
- **It is a READING**, not a sitting: joining rows nobody pointed at is the
  editor's work, and the clerk's ceiling forbids extras
  (`docs/spec-standing-agent.md` § *Two runs*).
- **Once, and said out loud.** The journal records the window it walked, so
  the next reading does not walk it again and the record says which beliefs
  began in history rather than in the week.

A hypothesis born from backfill states a `prior` and carries backfilled
atoms; both are visible on the row, so *this belief is old* and *this belief
is new* are different sentences a person can read.

## The forks, decided

| fork | decision | one-line reason |
|---|---|---|
| (a) where the belief lives | a **new `hypothesis` kind** in `workqueue10`, beside `value` and `insight` — not a field on `value`, not a framework kind | the five shapes are household vocabulary and a belief about a person is not a belief about a value; `ranking_note`'s "a kind, not a field" argument, one house over |
| (b) the type vocabulary's home | four **optional fields on `insight`**, not a new atom kind | the finding already cites, already has an author, already has an instant and a verdict — an atom kind would be an insight with a smaller vocabulary and a second place to look |
| (c) untyped atoms | **LR 1**, silently | compatibility is the whole answer: every standing finding keeps meaning what it meant, and no backfill is owed before slice 1 can run |
| (d) the LR table's home | the **recipe row beside `crown_rank`**, applied through `recipe_proposal` | same class of number, same diff vocabulary, same apply door, one staleness wall — and the honest cost (the recipe row widens past the feed) is stated above rather than discovered later |
| (e) the numbers themselves | **priced by argument, published as guesses** | the first thing the household learns is which are wrong, and a number a person can edit in a form is the only kind this house is allowed to hold (`default-crown-rank`'s own comment) |
| (f) how the posterior is computed | **engine arithmetic in Clojure**, cached on the row, never a `:derived {:expr …}` tree | `waymark10.expr/ops` has no division and no logarithm, deliberately (vocabulary § 2), and growing the law's vocabulary for one kind's arithmetic would put a model where a household can no longer read the law |
| (g) the posterior's door | **there is none** — no action takes it, it is absent from `:create-schema` and every `:input` | a hand-set posterior is an opinion wearing arithmetic's coat; the number is a cache of a fold anyone can redo from the atoms |
| (h) the field spelling `shape` | `shape`, not `kind` | `kind` is the row envelope's own word; `spec-threads.md`'s `chat_kind` decided this exact question |
| (i) `about` | a vector of **addresses**, not typed refs | the interesting claims span two kinds, and the address is the one citation shape this house has (`insight/row-address`) |
| (j) the states | **`value`'s machine**: observed → affirmed \| dismissed \| retired, with `restate` for the observer and `revise` for a person | one affirmation grammar in the house; jfv.10 already paid for the split-by-hand wording doors and its reasons transfer whole |
| (k) affirmation | **a person's**, grantable per waymark-sfe (`g/unless-granted`), never the observer's | the owner's 2026-08-28 ruling — *it doesn't make sense to disallow it, it just makes sense to permission it* — with four eyes kept on `observed_by` |
| (l) duplicate claims | **`not-a-second-belief`**, address overlap plus same shape, in `not-a-twin`'s shape | two beliefs about one thing split their evidence and neither moves; and an address comparison can name the row it refused, where a similarity score cannot |
| (m) episode independence | **one count per `(type, episode)`, 1.5× if repeated** | one excited evening is warmth, not four observations — and only the run that read the source can say what the episode was, so it is a field, not a derivation |
| (n) decay | **per type, half-life in days, toward the prior** | a Saturday spent is still evidence in a year and a polite yes is not evidence by autumn; one global rate gets both wrong |
| (o) the clamp | **±6 log-odds** | a belief that reaches certainty stops reading evidence, which is the failure mode this whole design exists to avoid |
| (p) extraction and belief | **split** — the clerk types, the reading reads posteriors, and the sitting's leash names no `hypothesis` | every LR above assumes the type was assigned by somebody who did not know what it would do |
| (q) backfill | **one reading, dated by the source, bodies never stored** | without it the first movements block is empty for a month and the epic's honest test fails for the wrong reason |
| (r) `movement_7d` | **recomputed, never stored per week** | the atoms carry `at`; *what moved* is the same fold asked twice, and a weekly snapshot kind would be a second history to keep true |
| (s) the gate between slices | **a fork, not a schedule** — see below | the epic's stopping condition is the only thing standing between this design and a belief system nobody asked for |

## Landing order, and the gate

```
slice 1 (waymark-2m2) ──GATE──▶ slice 2 (waymark-bug) ──▶ slice 3 (waymark-4t9)
typed evidence,                 the hypothesis kind,        gaps, experiments,
the LR table,                   the updater,                the crown reads
"what moved this week"          value loses observed        posteriors
computed on the fly
```

**Slice 1** adds four fields, one map of numbers, and one block in the
reading's brief. It stores no belief: the movements are computed on the fly by
grouping typed atoms by the rows they are about, folding per episode,
multiplying LRs and decaying by age. No new kind, no migration, no posterior
anywhere in the store. If the epic stops here, the house is left with a
better-typed record and nothing to unwind.

**THE GATE, recorded as this spec's most important fork:** *slice 2 and
beyond begin only after slice 1's movements have said something the owner did
not already know.* Not "after slice 1 ships" and not "after the block
renders" — after a real output contained a real surprise, and the owner said
so. The reason is the epic's own and it is the reason the slices exist: a
belief layer that only reflects back what the household already knows is
**expensive furniture**, and every wall in it costs something to maintain.
The bead text on waymark-57s writes this gate as standing in front of slice 3;
the beads' own dependency graph puts it in front of slice 2
(waymark-bug's `GATED:` line), and **slice 2 is the honest place for it** —
slice 2 is where the first stored belief and the first migration land, so it
is the first irreversible step. Slice 3 is gated behind slice 2 in turn.

The evidence the gate is answered with is one movements block, read by the
owner, with his sentence written into waymark-2m2. That sentence is the
deliverable; everything above it is machinery — jfv.5's walk, step 9, said
this first.

## Recorded punts

- **Value-fit by hypothesis link.** *Which of the household's values does this
  bundle actually serve, given what we believe?* — an outcome's
  `value_id` judged against the value's intent hypothesis rather than taken at
  the composer's word. Real, and deliberately not slice 3: it changes what the
  crown shows on the strength of a number, which is confidence routing wearing
  a friendlier name (§ *What is deliberately lost*, 3). Revisit only with a
  ruling, never as an implementation detail.
- **A composer digest row.** A stored *state of the profile* document the
  composer reads before a sitting instead of re-folding atoms. Punted because
  the fold is cheap and a digest is a cache with a staleness problem, and
  because a digest is exactly the artifact that would let a run read a belief
  it is about to feed — the extraction-blind rule's whole point.
- **Per-atom re-scoring UI.** A screen where a person adjusts one atom's
  weight by hand. It is the wrong door: the household's lever is the **table**
  (every atom of that type moves, arguable in one form, applied by a tap) or
  the **dismissal** (the finding leaves the fold with a word on the record).
  A per-atom slider is a thousand ungrounded numbers where the design has
  fourteen argued ones.
- **A cost-graded scale for the other eight types.** `cost` is stored and
  unpriced outside `costly_action`; whether *he drove an hour to ask a
  question* deserves its own number is a question observed atoms will answer
  and argument will not.
- **Hypotheses about the household as a whole** rather than about rows.
  *This family is happier in the winter* has no `about` set the address
  vocabulary can hold. Filed rather than smuggled: an `about` that may be
  empty is a claim nothing can check, and `cites-what-it-claims` has held that
  line since waymark-iqa.6.

## Effort

**Slice 1: small.** Four optional fields on a kind that ships, a map of
numbers beside `crown_rank` with `evidence-lr-of` / `evidence-lr-diff` copied
one field over, one block in the driver's brief, and skill/formula text
teaching the clerk to classify. Schema fingerprints move; the arithmetic is
forty lines and computes on the fly. The backfill reading is a run, not code.

**Slice 2: medium, and it is the whole cost of the epic.** A new kind with
six doors, an updater pass, atom links written on insight publish, the
migration of `value`'s observed rows, a conformance pack, and dated ruling
notes on jfv.10's sections. This is the slice the gate stands in front of, and
the reason it stands there.

**Slice 3: small-to-medium.** Two brief sections over rows that exist by
then, one new outcome field (`tests`, never `evidence` — a hypothesis a
bundle is testing is not a row it read), the crown's `:declared` weight
reading a posterior for the observed case, and the 63s probes re-scoped as
gap queries, which deletes more hand-written shapes than it adds.

The genuinely new thought in the whole epic is four sentences long: the
episode key, the per-type half-life, the extraction-blind split, and the
clamp. Everything else is `value`'s machine, `crown_rank`'s numbers,
`not-a-twin`'s wall and `insight`'s citation, instantiated once more.

## Built — slice 2, the hypothesis kind (2026-08-31, waymark-bug)

**The gate was answered.** Slice 1's first movements block ran against
the real house — six backfill passes, some fifty typed atoms, the
output in reading journal `0e244b1c` — the owner read it, and his
verdict was that slice 2 is what he wants. The `GATED:` line on
waymark-bug is lifted by that sentence and by nothing else;
§ *Landing order, and the gate* is satisfied as written.

Everything above this section is the design as it was written on
2026-08-30 and is not edited. What follows records what LANDED, where
it differs from the design, and what a person still has to do by hand.

### What landed

- **`workqueue10/src/workqueue10/resources/hypothesis.clj`** — the
  kind. `:states [:observed :affirmed :dismissed :retired]`,
  `:initial :observed`, `:terminal #{:dismissed :retired}`,
  `:nav :secondary`, summary `{data.claim} · {data.shape} · {state}`.
  Fields: `claim`, `shape`, `about`, `prior`, and the derived six —
  `posterior`, `posterior_log_odds`, `movement_7d`, `atom_count`,
  `atoms`, `last_moved` — plus `observed_by` / `affirmed_by` /
  `affirmed_at`. Doors: create, `restate` (the observer's), and
  `still_stands`, `revise`, `dismiss`, `retire`, each behind
  `the-answer-is-a-persons` — `g/unless-granted` with
  `:own-field :observed_by`, so a person passes, the observer never
  does whatever its grant says, and any other agent passes under a
  grant naming `hypothesis.<door>`. Walls:
  `a-belief-cites-what-it-is-about`,
  `a-prior-is-a-guess-not-a-conviction`, `not-a-second-belief`,
  `only-the-observer-restates`.
- **`waymark10/src/waymark10/belief.clj`** — the three rules, pure, no
  storage. `logit` / `probability`, `atom-log-odds`, `fold`, `clamp`,
  `posterior-log-odds`, `movement`, `belief`, plus the row-shaped half
  of the join: `atom-of`, `atoms-of`, `addresses-of`, `touched-by?`,
  `fold-one`, `cached`.
- **`waymark10/src/waymark10/server/belief.clj`** — the pass.
  `sweep-beliefs!` reads every hypothesis and every finding, folds
  each belief over the atoms that touch it, and writes through
  `store/update-data!` (the maintenance write: document and clock
  index only, version untouched, no transition).
  `start-belief-sweeper!` is the clock; the feed module's `:hooks`
  carries it, `:elected :belief-sweeper`,
  `:when server-belief/serves-hypotheses?`, default interval a day
  (`:belief-sweep-ms`).
- **`workqueue10/src/workqueue10/resources/value.clj`** — `observed`
  gone; see below.
- **`scripts/movements.jq` and `scripts/movements-fixture.sh`** —
  rule 2's key corrected, and the fixture regrown around it.
- **`scripts/sitting-run.sh`** — the brief reads the store, with the
  computed block as the fallback; and the extraction-blind rule
  enforced at one gate.
- **`waymark10/src/waymark10/test/packs.clj`** — `:feed/hypotheses`.
- **`waymark10/test/waymark10/belief_test.clj`** — the arithmetic,
  hand-computed in its docstring, no database.
- `READING.md`, `.claude/skills/reading/SKILL.md`,
  `.beads/formulas/reading.formula.toml` — the reading learns the
  kind. **Nothing in the sitting's materials names it**, which is
  § *The extraction-blind rule* kept rather than quoted.

### Where the build departed from the design, and why

Five departures, all of them small, all recorded here rather than
discovered later.

1. **Rule 2's key was already wrong in slice 1, and is corrected
   here.** `scripts/movements.jq` shipped grouping on the OCCASION
   alone, so a costly action and an unprompted mention in one evening
   folded to one number. The spec says `(hypothesis, evidence_type,
   episode)` in rule 2 and `(type, episode)` again in fork (m). The
   engine reads the spec's key and the jq was changed to agree,
   because a fallback that computes a different number from the store
   is the second opinion nobody can see. `movements-fixture.sh` grew a
   seventh atom so the 1.5× intensity is exercised at all — before the
   correction, every pair in that fixture folded, and the fixture said
   less than it meant.
2. **`prior`'s band is a GUARD, not a schema property.**
   `waymark10.schema`'s `:decimal` derives its generator from its own
   `:min`/`:max` with `(long …)`, so `{:min 0.02 :max 0.5}` would
   generate the single value `0` and the conformance walker would
   refuse every row it wrote. The schema holds `0..1` and
   `a-prior-is-a-guess-not-a-conviction` holds `0.02..0.5` with the
   sentence that names the fix. One usability warning
   (`effort-honesty`) rides it, acknowledged by the guard's `:open`
   and recorded in the kind's `:deviations`.
3. **The birth folds.** The design has the updater as a pass and says
   nothing about create. A hypothesis a backfill mints would then
   carry only its prior until the next night, which is the wrong
   answer for the run that is about to write twenty of them — so
   `born` runs the fold over the findings already in the store,
   weighed by `feed/default-evidence-lr` (a create ctx holds the
   write's transaction and no engine, so the recipe row is not
   reachable from there). The nightly pass reweighs by the
   HOUSEHOLD's table. The cost is one night of the deployment's
   numbers on a fresh row, and it is stated rather than hidden.
4. **`atoms` carries `episode` and `solicited` beyond the design's
   four fields.** The row's whole promise is that it is *a cache of an
   arithmetic anyone can redo*; without the occasion nobody can check
   rule 2, and without the discount flag `lr_applied` does not
   reconcile with the posterior. `lr_applied` is the table's
   cost-graded number BEFORE the discount and before any decay — the
   price, not the contribution.
5. **`posterior_log_odds` and `atom_count` are stored beside
   `posterior`.** Rule 1's own sentence asks for the first (*stored as
   the probability for reading and the log-odds for arithmetic*); the
   second is one integer that saves every reader a `count`.

### `value` loses `observed` — what changed, and the migration

**The kind.** `:states [:declared :retired]`, `:initial :declared`.
`restate` and `dismiss` are gone with the state they left from;
`revise` and `still_stands` leave from `declared` only; the wording
door no longer splits by hand. **The create door regained
`written-by-a-person`, grantable** — with no unaffirmed landing left,
every create is an affirmation, so an agent at that door would be
answering its own reading. The wall did not change its mind; the
states moved out from under it. The refusal names the composer's own
door by name: an intent hypothesis.

**The composer's grant scope changes.** jfv.10 widened it to
`value: ["create", "restate"]`. `restate` no longer exists, and
`create` is a walled door rather than an open one — so a composer that
should still write values needs a grant admitting `value.create`, and
for most deployments the honest answer is that it should not have one,
because the thing it wants to say is a hypothesis.

**`feed/value-standing`'s `:observed` arm is left standing and
unreached, deliberately.** Slice 3 (waymark-4t9) repoints it at the
intent hypothesis's posterior — the same sentence with a number in it
— and deleting the arm now would leave that slice nothing to repoint.
The card sentence *a value observed in your record, not yet affirmed*
therefore has no row that can reach it until then; `value_test`'s own
docstring records the coverage that moved a slice down the epic rather
than being abandoned.

### THE MIGRATION, AND EXACTLY WHAT THE OWNER'S TAP IS

**Nothing here has been applied. `make migrate-queue-prod` was not
run, and could not have been: it needs the LAN.** What follows is the
plan, in the order it must happen.

**Step 0 — the new table needs no tap.** `hypotheses` is a kind this
production database has never seen, and `store/ensure-kind!` runs
`CREATE TABLE IF NOT EXISTS` at boot BEFORE `migrate-gate!` snapshots.
A purely-new kind therefore creates its own table on the deploy that
serves it. `.github/workflows/image.yml`'s `plan` job may still show
the create step, in which case the `apply` job holds at the
`production-migrate` environment and **the tap is a reviewer approving
that environment** — the same tap every non-destructive schema move
has needed since image.yml landed.

**Step 1 — mint the hypotheses, BEFORE the state moves.** Every value
standing in `observed` becomes an intent hypothesis whose claim is the
value's `says`, citing the value's own address. This is a run, not
code: a READING, through the ordinary create door, under a grant
naming `hypothesis.create`. `scripts/value-observed-migration.sh`
prints the exact request bodies from the live collection and posts
nothing — read it, then feed it to the door. Do this first: after step
2 the state column no longer says which values were guesses (though an
empty `affirmed_by` still does, and the transition log always will).

**Step 2 — move the rows, and THIS is the destructive tap.** `value`
declares `:renames {:states {:observed :retired}}`, which does two
things: it keeps `migrate/assert-known-states!` from refusing the boot
outright, and it tells the planner to emit

```
[DESTRUCTIVE] rename-state values:
  UPDATE values SET state = 'retired', updated_at = now()
  WHERE state = 'observed'
  -- live rows occupy retired state observed → retired
```

`migrate/apply!` **skips every destructive step unless a person opts
in**, and `make migrate-queue-prod` is read-only by construction — it
refuses `APPLY=1` and `DESTRUCTIVE=1` with a message telling you to
run the statement by hand. So the owner's tap for step 2 is, in full:
read the plan with `make migrate-queue-prod` (needs the LAN), then run
that one UPDATE against the production database through
`nomad alloc exec -task postgres <alloc> psql -U workqueue -d
workqueue10 -c "…"`, exactly as the Makefile's own refusal spells it.

**`retired` rather than `declared`, and the choice is not neutral.** A
guess is not law. The row leaves the house's holding, keeps its whole
record, and comes back through `restore` — which lands in `declared`
and stamps the owner — if he reads the belief and agrees with it. The
alternative would have promoted every unanswered reading to this
household's law in one UPDATE, which is the exact thing jfv.10 built
the state to prevent.

**The order matters and the failure is survivable.** Step 2 before
step 1 loses nothing permanently: the migrated rows are the `retired`
values with no `affirmed_by`, and the transition log names every one.
Step 1 without step 2 is a house whose boot refuses until the rename
runs — which is `assert-known-states!` doing its job, and the refusal
names the fix.

**If production holds zero observed values, step 1 and step 2 are both
no-ops** and the plan prints an empty rename set. jfv.10 recorded that
the `values` table held zero rows in production at the time; nobody
has checked since, and this plan is written so that either answer is
safe.

### Recorded, for whoever comes next

- **The updater does not run on insight publish.** § *Effort* said
  *atom links written on insight publish*; the normative sections say
  the pass writes them and that *nothing is rewritten nightly except
  the cached posterior*. The pass and the birth fold cover it, and a
  cross-write from `insight`'s create handler would be the one
  cross-write no `:touches` could advertise (that namespace's own
  paragraph). Filed rather than built.
- **A dismissed or retired belief has no door out.** `value` has a
  `restore`; this kind does not, because asking again is asking again
  — a NEW hypothesis, which `not-a-second-belief` admits precisely
  because the old one no longer stands. A `restore` would be the house
  un-answering itself.
- **`insight` grew no field.** The link is the ADDRESS, both ways: a
  finding that cites a row the belief is about feeds it, and so does
  one that cites the belief's own address. That is what makes a
  backfill re-pass possible with no edit to any standing finding.
- **The sitting's manifest lost its movements block**, which slice 1
  had let ride. Defensible while the house stored no belief anywhere;
  not defensible now. § *The extraction-blind rule* is enforced at one
  gate in `scripts/sitting-run.sh`, where both the brief and the
  manifest read the same file.
