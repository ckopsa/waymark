# Spec — the decision kind: the standalone verdict, declared

**Thesis.** Some decisions are not transitions on a domain row. "Approve this
purchase", "can Iris stay up for the game" — the decision *is* the thing, and
today an app that wants one either smuggles it through a domain state
(`pending_approval` bolted onto a chore) or copies `grants.clj`.
`approval_request` is already eighty percent of a generic verdict machine:
a question, a requester stamped by the engine, a four-eyes wall, an expiry, a
decision queue with its own default filter. Make that eighty percent a
**spelling** — a `:decision` declaration key that desugars into ordinary
states, actions and guards — and let `approval_request` become its first
instance.

## Epistemic status

The important claim is the modest one: **this is not a new mechanism.**
`:flow` already proved that a declaration key can generate a whole machine
(`resource.clj:846` `desugar-flow`, run first in `normalize-resource`'s thread
at `:1163`), and batch G fixed the invariant that makes such sugar safe —
*two spellings, one law*: a colocated, split, def'd or inline spelling of the
same declaration fingerprints identically, so a pure style refactor mints zero
revisions (`waymark10-design.md:1284-1294`). `:decision` desugars before
normalize, before the check battery, before the fingerprint. Downstream — the
router, the render probe, collections, OpenAPI, MCP, the conformance driver —
nothing learns a new noun.

Which means the acceptance criterion writes itself, and it is sharper than any
behavioural test: **after the rewrite, `approval_request`'s fingerprint hash is
unchanged.** If it moves, the sugar has changed the law and the generalization
failed.

What is *not* modest is everything around the machine. The machine is three
states and two actions and was never the hard part. The hard parts are the
decider's eligibility (which does not exist today), the post-commit effect
(hard-coded at one call site in the router), and the own-surface (a literal set
of kind names, read through three parallel `case` blocks and duplicated in the
test packs). Two of those three are in scope. The third is named and left.

## What exists

**`grants.clj:624-716` — `approval_request`.** States `[:offered :approved
:denied]`, initial `:offered`, terminal both leaves; `:default-filters {:state
"offered"}` and `:sortable "-created_at"` make it a decision *queue* with no
extra concept. Fields: `grant_id`, `task` (the question), `scope`,
`expires_at`, `requested_by`, `approved_by`, `note`. Actions `:approve`
(guards `someone-else-decides`, `grant-still-accepting`; handler
`stamp-approver`) and `:deny` (input `:note`, handler `record-verdict-note`).

Sorting the file by what would survive a second decision kind:

- **Generic, spelled as approval-specific code.** The question is literally
  `:task [:string {:min 1 :max 240}]`. `someone-else-decides` (`:592-597`) is
  generic no-self-decide hard-wired to the field name `:requested_by`.
  `:on-create` (`:684-700`) stamps the requester and defaults the TTL from
  `:grant-default-ttl-seconds`. `asks-are-paced` / `asks-are-few` (`:530-576`)
  are pacing and an open-ask cap with the kind, the field, the exemption and
  the query all literal. `stamp-approver` hard-codes `:approved_by`. The
  verdict vocabulary — `:approve`/`:deny`, `:approved`/`:denied`, the labels,
  the styles, the orders — is a literal at `:702-716`.
- **Genuinely grant-specific.** `scope-schema` and its four validators
  (`:234-351`), `requester-holds-the-grant` (`:501`), `grant-still-accepting`
  (`:599`), the `grant_id` field and link, and `approval-effects!` (`:725-773`),
  whose effect is "mint or extend a grant" rather than "apply the verdict".
- **Already reusable.** `past-expiry` (`:210-215`) — a pure `g/expr` on
  `data.expires_at` with `:becomes-available-at`. `requester-is-named`
  (`:518-523`). `guards/four-eyes` (`guards.clj:460`), which is `unless` over
  `(:actor-of ctx)` and is a *transition-history* wall, not a field wall.

**The three seams a second kind would break on.**

1. **Eligibility does not exist.** Four-eyes says "not you". It cannot say
   "a parent". `guards/role` mints a `role:parent` token and would compose
   fine — nothing has ever needed to compose them.
2. **The effect is one call site.** `router.clj:719` wraps *every* `inv/invoke!`
   in `grants/approval-effects!`, which no-ops for other kinds. It also rides
   the wire boundary, so an engine-internal invoke never fires it.
3. **The own-surface is a literal.** `grants.clj:912` `own-kinds` is
   `#{"grant" "approval_request" "job" "capability" "self" "journal" "letter"}`,
   consulted by three hand-written `case` blocks (`own-row?` `:1081-1117`,
   `:action?` `:1146-1183`, `:ids-of` `:1223-1239`) and copied a fourth time in
   `test/packs.clj:1000`. A new decision kind is **invisible to its own
   requester** until all four are edited.

## The design

### `:decision` — one key, desugared before the law

```clojure
(defresource screen_time
  {:kind :screen_time
   :plural "screen_time_requests"
   :decision
   {:asks     :for_what                  ; the question field
    :by       :requested_by              ; stamped from the principal
    :decider  {:not :requested_by        ; four-eyes, by field
               :role "parent"}           ; …and eligibility
    :verdicts [{:name :allow  :to :allowed :label "Allow"}
               {:name :refuse :to :refused :label "Not tonight"
                :note :note}]
    :stamps   {:decided-by :decided_by}
    :expires  {:field :expires_at :default 14400 :max 86400}
    :pacing   {:limit 6 :per :hour :open-cap 3}
    :own-surface true}
   :schema [:map
            [:for_what [:string {:min 1 :max 240}]]
            [:minutes {:optional true} [:maybe :int]]]})
```

`desugar-decision` runs first in `normalize-resource`'s thread — ahead of
`desugar-flow`, since a decision *is* a flow — and projects:

| declared | projected |
|---|---|
| `:verdicts` | `:states`, `:initial :offered`, `:terminal`, the verdict actions with their `:to`, `:display` and optional `:note` input |
| `:asks` `:by` `:stamps` | the schema entries it owns, the `:create-schema` that omits the stamped ones, `:on-create` |
| `:decider` | the verdict actions' `:guards` — `four-eyes`-by-field composed with `guards/role` under `g/and` |
| `:expires` | the `expires_at` field, the TTL cap guard, the `:on-create` default |
| `:pacing` | the two create guards |
| always | `:default-filters {:state "offered"}`, `:sortable {:default "-created_at"}`, `:nav :system` unless spelled |

A key the author also spells directly wins and the sugar refuses to
double-declare, exactly as `:flow` refuses an action that is also in `:actions`
("one home per action", `resource.clj:872-877`). Everything projected is an
ordinary declaration value; nothing about the result says "decision".

### The decider gains an eligibility dimension

`:decider` takes `{:not <field>}`, `{:role <name>}`, `{:field <ref-field>}`,
or any combination, composed with `g/and`. `{:not :requested_by}` is a **field**
wall and not `guards/four-eyes`, which is a transition-history wall over
`(:actor-of ctx)` — the distinction matters because a decision row's requester
is stamped in `:on-create`, before any transition exists to be the actor of.
`someone-else-decides` already draws it correctly by hand; the sugar copies its
spelling, not `four-eyes`'.

`{:field :guardian}` is the shape "the person this row names decides it", which
is what a household actually means and what neither existing guard can say. It
composes over `:kind :member` refs the same way `chore.clj`'s `:assignee` does.

### The own-surface stops being a literal

`:own-surface {:by :requested_by}` on the rdef (`true` means "by the
`:decision :by` field"). `grants/own-kinds` reads it off the **registry**
instead of a string set, and the three `case` blocks become one lookup. This
also kills the duplicate in `test/packs.clj:1000`, which is the second copy
that proves the first was a literal too long.

Two consequences worth stating: an app kind that is *not* a decision may
declare `:own-surface` and get the same courtesy; and the seven kinds in
today's literal each grow the declaration that already described them, so the
set is not removed, it is relocated to the seven places that own it.

### The effect moves into the handler

`approval-effects!` stops being a router wrapper and becomes what the verdict
action's handler does, through `ctx :invoke` and `ctx :create` — the handler's
declared cross-write door (`waymark10-design.md:3092-3118`), with `:touches`
advertising the blast radius the way `chore.clj`'s `queue-run` already does.

This is a deliberate behaviour change and the reason is the bug it removes: the
router wrapper fires only at the wire, so an internal `inv/invoke!` of
`:approve` mints no grant today. Moving it inside makes the effect a property
of the *action* rather than of the door it came through, which is this
framework's rule everywhere else. `access_flow_test`, `batch_b_mint_test` and
`reentry_door_test` are the witnesses; the idempotency-key convention
(`"approval-extend-|mint|accept-" + ask-id`) is preserved verbatim so a replay
still restamps the same grant.

`router.clj:719`'s `grants/approval-effects!` wrapper is then deleted — one
fewer core handler naming a module by namespace, which is
[spec-modularization](spec-modularization.md)'s whole errand.

### `approval_request`, rewritten as an instance

It keeps its kind name, its plural, its field names, its states, its action
names, its wire, and its hash. `scope`, `grant_id`, the four scope validators
and `requester-holds-the-grant`/`grant-still-accepting` stay hand-written in
`grants.clj` as the extra law this particular decision declares — which is the
proof that the sugar is a floor and not a ceiling.

## Recorded punts

- **"Screen time tonight" may not want this at all.** A decision kind is for a
  verdict the *house* records and reads. A verdict a *machine* enforces is
  already expressible: a dotted capability token (`screen_time.watch`) with
  `:filter` constraints and an expiry, introspected through
  `/api/-/grant-check` (`router.clj:1345-1377`), which is exactly what that
  machinery was built for and costs zero new code. Screen time is the second
  thing until something in the house needs to read it. Recorded because it is
  the epic's own example, and the honest answer changed on contact.
- **Verdict arity is two or more, never one.** A single-verdict decision is a
  task with a checkbox. The sugar refuses one, and refuses a verdict list with
  no terminal state.
- **The effect is a handler, not a registry.** A per-action post-commit effect
  table would be a keyword→fn registry arriving through the authored surface —
  the one door `spec-modularization.md` refuses to open, and `server/seams.clj`
  is the record of it being refused once already. Handlers are where effects
  live; if that is insufficient for some later kind, the answer is a protocol
  on a declared value, not a column.
- **Pacing rides no coordinator.** `:pacing` desugars into the same `ctx :find`
  guards `asks-are-paced` uses today, per-process and unshared, inheriting
  `grants.clj`'s own recorded punt about the unwired `:rate` hook. Declaring it
  does not make it distributed.
- **The UI still names one kind.** `120-nav-home.js:83`, `140-links-access.js:173`,
  `160-resource-surface.js:62` and `210-ledger.js:205` hard-code
  `approval_request`, as do the well-known `:doors :ask` block and the welcome
  document (`router.clj:391`, `:1233-1273`). A decision kind should advertise
  itself on well-known beside `:nav`/`:domain` and let the queue tab, the
  ledger refetch and the follow-the-requester behaviour derive — but that is a
  wire addition and a UI pass, and bundling it here would hide a law change
  inside a chrome change. Separate bead.
- **No decision *about* a decision.** Escalation, delegation, quorum, "two of
  three parents" — all inexpressible and all deliberately out. `:decider` is a
  guard; a guard is a wall, not a workflow. A house that needs quorum can
  declare three decision rows and a derived fact.

## Effort

**Medium–large,** and the distribution is lopsided. `desugar-decision` and the
new declaration key are small and testable against one invariant. The
own-surface de-literalization is four edits and a registry read. Moving the
effect into the handler is the risky third: it changes when a grant is minted,
and it is guarded only by suites that already exist. Do it last, alone, with
`approval_request`'s scenarios (see [law scenarios](spec-law-scenarios.md))
written first.

## Built (2026-08-24, waymark-442.6)

Landed as specified for two of the three seams, with the third deferred on a
finding the spec did not have. `:decision` and `:own-surface` are the two new
declaration keys; no new namespace, no new noun downstream.

### The acceptance criterion held

`approval_request`'s fingerprint hash after the respelling:

```
01ca868b7440b6c13c9e10260904eb82a18217f79b439134369fdb337496d9f3
```

— byte-identical to the hash it carried before the `:decision` key existed.
`waymark10.decision-sugar-test` keeps the pre-sugar spelling alive, asserts it
normalizes to the very map the sugared one declares, and pins that hash as a
literal so no future respelling can move it. The two four-eyes scenarios
`waymark-442.2` wrote pass unchanged; they were the regression harness and
they never noticed.

The one thing that made this possible is worth recording, because it is the
technique any future sugar will need: a guard minted by a factory hands the
fingerprint a bare closure, which hashes by printed object identity and drifts
every run. `guards/not-the-field` and `guards/is-the-field` build their
`:waymark10/form` **as data**, constructed to print exactly as the equivalent
`defguard` body would. So `grants/someone-else-decides` could become a call to
the factory — one definition of the field wall instead of two — without moving
a byte. Every other factory in `guards.clj` (`role`, `owner`, `feature-flag`,
`unless`) still hands over a bare fn; that is `callable-hash`'s recorded
stopgap, and it is wider than recorded (see waymark-j82: a `#()` inside a
`defguard` body carries a reader gensym into the canonical form, so
`:grant`'s hash moved during this bead for no change to its law).

### `:decision`, as landed

Close to the spec's shape. Four differences, each with a reason:

- **`:decider` walls are a VECTOR, not one `g/and` composite.** The spec said
  compose under `g/and`. A composite guard records opaque, and
  `spec-decision-record` keeps per-guard evidence — a folded four-eyes wall
  would leave the log a name with nothing behind it. An action's guard list is
  already a conjunction, so keeping the arms apart costs nothing.
- **Each wall carries its own sentence.** `{:not {:field :asked_by :name … 
  :explain …}}`; a `:name`/`:explain` spelled at the `:decider` level is
  shorthand for the one-wall case and refuses beside a second wall, because a
  sentence that would land on two different refusals is wrong on one of them.
- **`:expires :default` accepts `{:service :some-key :seconds <fallback>}`,**
  so a deployment-configured TTL rides the declaration rather than forcing a
  hand-written `:on-create` (which is what `approval_request` needed).
- **An `:on-create` spelled beside a `:decision` REFUSES** ("one home per
  hook"), rather than composing. Composing would mint a wrapper fn, and a
  wrapper fn is a hash that moves for nothing. Recorded as the sugar's own
  limit: a decision kind needing an extra birth stamp has no spelling yet.

`approval_request` rides the key for the states, the terminal pair, both
verdict actions, the four-eyes wall, the requester stamp, the default leash,
the schema entries the engine owns, the create model that omits them, the
queue's filter and sort, and its own-surface. It declines `:pacing` and
`:expires :max` and keeps those four create guards hand-written, because all
four need the ANCHORED-ASK EXEMPTION and that exemption is grant law, not
decision law — the floor-not-ceiling rule working rather than failing.

### The first house-read verdict

`workqueue10 :permission_slip` — somebody asks for leave, a grown-up answers,
the answer is the row. The spec's own recorded doubt drove the choice: screen
time wants a capability grant because a machine enforces it; a permission slip
has no enforcement point anywhere and never will, so the record IS the whole
mechanism. It is also the one household verdict with no domain row to smuggle
itself onto — every other one (a meal accepted, a substitution accepted, a
plan finalized, a product's match confirmed) rides a noun that already existed
for another reason.

Its whole law is one `:decision` key and three scenarios, all check-tier —
`make check-queue` judges them with no database. It is the first declaration
to use the eligibility dimension the spec named as missing: `{:not :asked_by}`
composed with `{:role "parent"}`, so a sibling is refused by the second wall
after passing the first.

### The own-surface

`grants/own-kinds` and its three `case` blocks are gone, along with the fourth
copy in `test/packs.clj` (which now reads `:own-surface-kinds` off the driver
ctx) and a fifth in the clj-kondo hook's key set. The seven kinds each grew
the sentence that always described them — including `:self`, `:journal` and
`:letter`, which are declared in **workqueue10**, so core stopped naming three
app kinds by string. `waymark10.presence-test`'s `:letter` fixture grew the
same sentence, which is exactly the failure mode the de-literalization was
supposed to expose: it used to inherit the walls by borrowing a name.

`:own-surface {:by […] :actions #{…} :all bool}`. `:by` is a vector of
branches because ownership is not always one-party; a branch is a field
keyword (pushed down as a query cond) or a PATH vector (filtered in memory —
`:job`'s requester rides as an object). `:all true` is the vocabulary posture
(`:capability`), kept as a separate key from an empty `:by` so that "owned by
nobody" and "owned by everybody" are not one typo apart.

### The effect seam was NOT opened — waymark-442.14

The spec's third seam is deferred, and the reason is a finding it did not
have: **the handler door cannot express the effect today.** `approval-effects!`
acts as a system actor and mints under a deterministic id; `ctx :invoke`
hardcodes the outer principal (and `grant`'s `:extend` carries
`approval-route-only`, whose whole check is `(= :system (:type principal))`),
and `ctx :create` takes no `:id`. Two widenings of the authored surface are
required, one of them a principal override — a security-surface change that
wants its own review, and the sibling of the door
[spec-modularization](spec-modularization.md) refuses.

Two further reasons to keep it separate, both recorded in the bead: folding
the effect into `stamp-approver` **moves approval_request's fingerprint**,
which would have made this bead's one clean proof unreadable; and no declared
decision kind is blocked by the wrapper, since it already no-ops for every
other kind and the first household instance needs no post-commit effect by
design. The spec's own instruction was "do it last, alone" — it gets its own
bead and its own commit.
