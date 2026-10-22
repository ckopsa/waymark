# Spec: teams and outside parties as declared law

Concept 5 of epic `2bb0aed8` (ticket `e9c00713`). A spec only: no engine
code lands with it. Its last section is the build plan the mayor splits
into children, the way `docs/spec-calendar-time.md` (concept 2) and
`docs/spec-ledger-money.md` (concept 3) end. It supersedes the tabled
epic `d9988c6c`, which is closed as superseded once this spec merges.

> A landscaping owner books the Hendersons' patio for Thursday with the
> same crew already at the Parks' yard. Now the house refuses the booking
> the moment it is made, in one sentence naming the Parks' job and the
> free crew.

Concept 1 gave the law reads of other rows, concept 2 gave it time,
concept 3 gave it quantities, and concept 6 gave it the outside world as
systems. This spec gives it **people as law**: who is on which crew, who
holds which licence, who may not check their own work, whose tap a hold
waits for, which rows a crew lead sees, and how a carrier, an insurer or a
supplier acts on the one row that names them. Three rehearsals logged 10
workarounds of this concept (org_plan `16d15da4` shop, `e749a045` clinic,
`bba84cf0` landscape, concept "5"), and concept 1 left one more: *a hold
naming a specific person*. Their shapes are in §10 with the construct that
replaces each. The owner's word for this concept (2026-10-22) is **strong
defaults, flexible later**: every construct here has one default the
house gets without asking, and the options kept are where it bends later.

---

## 0 · Today

- **A member is a person or an agent.** The `member` kind
  (`server/members.clj`) has `actor_type` `[:enum "human" "agent"]`, a
  `display`, a `handle`, an optional `acts_for` and a `provenance` of
  `idp`, `invite` or `knock`. There is no third kind of actor: a carrier
  is either a person with a login or nobody.
- **Roles are names on the member row.** `member.roles` is a vector of
  role names, each refused unless a `role` row (`server/roles.clj`)
  registers it; the remedy is `:role/create`. A seed's cast declares
  `:roles` per member, and `seed/register-roles!` registers them before
  `enrol!` writes the members. A role is global: nothing says "a
  therapist *of this clinic's Tuesday team*".
- **There is no team.** No kind groups members. The landscape rehearsal's
  crews are an `:enum` on the job (`"crew_a" "crew_b"`), with one
  crew-lead seat that sees every crew's jobs, because nothing could tell
  the law which members a crew is.
- **A ref names a kind, not a person's standing.** A field is
  `:waymark/ref` with `{:kind …}`, or carries `:x-ref {:principal true}`
  for a principal. The write checks that the row exists. It does not
  check that the member referenced holds a role or is on a crew, so the
  clinic types the therapist, the signer and the cosigner as free text.
- **The law cannot ask who is acting.** A guard reads the row, the input
  and (concept 1) other rows. It has no reader for the actor, so "the
  packer may not also be the checker" is a sentence in a kind note.
- **A hold waits for a role, or for the agent's own person.**
  `(hold "…")` in `declare.clj` holds an agent's call as a `held_call`
  for the person it acts for; `(hold "…" :for :role "x")` waits for any
  member holding role x, the held call names it as `door.for_role`, and
  `an-approver-decides` refuses an Allow from a person without it
  (`role_hold_test`). A person's own call goes through. **No hold can name
  one member**, and a hold cannot read the row to find whose tap it is.
- **A grant admits rows by match.** A scope entry may carry
  `:filter {field value}`: equality on `state` or on a declared-filterable
  field, one filtered entry per kind, judged at the row and ANDed into the
  collection query (`grants.clj`). The value is a constant. Nothing admits
  "the rows whose crew I am on", so membership is copied into grants by
  hand and drifts.
- **Outside parties cannot act.** The rehearsals' carrier, supplier and
  insurer are prose. When the carrier says a parcel is lost, the mayor
  reads the e-mail and types the answer into the house. Concept 6's
  `integration` covers a *system* that signs its events; a person at a
  supplier with no system has no door.

## 1 · The decision in one paragraph

**People are rows, standing is law, and the law reads the actor.** The
engine serves a `team` kind: a name, its member refs and the roles it
confers, so a crew or "the licensed therapists" is a row and not an enum.
A ref field may declare **who may be named** (`:role`, `:in-team`), and
the write is refused when the member named does not qualify. The law gains
three readers, `(actor)`, `(holds-role? m r)` and `(in-team? m t)`, and a
door option `:not-by` from which the engine writes the
separation-of-duties guard. A hold may be **addressed**: `:by (data
:therapist)` makes the held call wait on that member or that team, from
the law, and only their Allow opens it. A grant's scope entry may say
`:team-of`, so one crew-lead seat per crew sees and moves only that crew's
rows, live as membership changes; this is where row-level read rules live.
An **outside party** is a member of actor type `outside`, whose grant
admits only the doors on rows that name it, and who acts through a link or
through concept 6's inbound events, never with a full login. Three audit
findings ask the questions that lead a house to each.

---

## 2 · Teams — the `team` kind (decision 1)

### Decision

An engine-served kind in a `:teams` module, enrolled the way `:role` is
(`modules.clj`), so every house has it without declaring it:

```clojure
{:kind :team
 :states [:active :retired]
 :schema [:map
          [:name    [:string {:min 1 :max 60}]]          ; unique among active
          [:members [:vector (ref {:kind :member})]]      ; may be empty
          [:roles   {:optional true}
           [:vector [:string {:min 1 :max 40}]]]         ; registered role names
          [:note    {:optional true} [:maybe :string]]]
 :actions {:create … :edit … :retire … :restore …}}
```

- **`members`** are member refs, inside or outside (§7). A member may be
  on many teams. Adding or removing one is an `edit`, logged like any
  other, so "who was on Crew B on Thursday" is the log, not a guess.
- **`roles`** are role names the team **confers**: a member holds role r
  while on a team whose `roles` has r. "Licensed therapists" is a team
  conferring `pt`; leaving the team takes the role away in the same
  write. Each name is refused unless a `role` row registers it, as on the
  member row today.
- **The default door holders.** `create`, `edit`, `retire` and `restore`
  are held by the house's owner seat and by a member holding the
  registered role `team-admin`. A team is never deleted: `retire` keeps it
  readable for the rows and the log that name it, and a retired team
  confers nothing and admits nothing (§6).
- **Membership is data, not law.** Moving Luis from Crew A to Crew B is
  not a law revision (§9), and it takes effect at the next judgment.

### What a guard reads

Membership is read at the write: a guard judges the team as it stands in
the transaction that judges the door. The `judgment` a kind retains
records the member list it read, so the history of a refusal shows the
crew as it was then.

### Option kept: per-member positions

"Ana leads Crew B" could be a position inside the team (`members:
[{member, roles}]`). The default keeps one flat list and puts `crew-lead`
on Ana's member row, because the three rehearsals have no position that a
global role plus `in-team?` cannot say. If one appears, `members` grows
to maps without changing the readers' answers.

## 3 · Who may be named — ref options (decision 2)

### Decision

A member ref declares who qualifies, and the engine checks it at the
write, in the door's transaction:

```clojure
[:therapist (ref {:kind :member :role "pt"})]
[:crew      (ref {:kind :team})]
[:lead      (ref {:kind :member :in-team (data :crew)})]
[:cosigner  [:maybe (ref {:kind :member :role "supervising-pt"})]]
```

- **`:role r`**: the member named holds r, on their own row or through an
  active team that confers it.
- **`:in-team <expr>`**: the member named is on the team the expression
  names. The expression is a law expression over the row and the input,
  so `(data :crew)` reads the proposed crew when the same write changes
  it.
- **Both** may stand together, and both hold for every element of a
  `[:vector (ref …)]`.
- **The refusal names the evidence:** *"Dana Ruiz is not on Crew B. Crew
  B is Ana Lopez and Luis Ortega."* / *"Sam Lee does not hold pt."* Its
  remedy is the field's own picker, narrowed.
- **The picker narrows.** The field's `x-ref` carries `role` and
  `in_team`, so a form and a `waymark_query` on `member` offer only the
  members who qualify (advertisement; the write is the law).
- **A member who stops qualifying** does not change the rows that already
  name them: the check is at the write. The next write that touches the
  field judges it again, and §9's audit has no finding for it; a house
  that wants "every open visit's therapist is still licensed" writes a
  concept 1 `none` over its rows.

The engine writes these as named guards, `<field>-role` and
`<field>-in-team`, so a history entry's `basis` names them like any other.

## 4 · The actor in law — readers and `:not-by` (decision 3)

### Readers

| Reader | Answers |
|---|---|
| `(actor)` | The member id the log will name as having acted. For an agent with `acts_for`, the agent; `(actor :acts-for)` is the person it acts for, or the actor when none. For a seat, the seat's sitter member when it has one, else nil. |
| `(holds-role? m r)` | True when member `m` holds role `r`, on their row or through an active team. |
| `(in-team? m t)` | True when member `m` is on active team `t`. |

Each takes expressions (`(holds-role? (actor) "checker")`, `(in-team?
(actor) (data :crew))`). They read through `ctx` once per invoke, are
nodes of the canonical tree, and a refusal names what they read: *"You
are not on Crew B."* They are pure on a scenario's cast (§8).

### `:not-by` — separation of duties

```clojure
:pack  {:from #{:open}   :to :packed  :stamps {:packed_by (actor)}}
:check {:from #{:packed} :to :checked :not-by [:packed_by]}
```

- **`:stamps {field (actor)}`** writes the actor's member id into a
  `(ref {:kind :member})` field in the door's own write. A stamped field
  is not in any door's input: no one types who packed.
- **`:not-by [f …]`** writes the guard *the actor, and the person the
  actor acts for, are not the member any of these fields names*. The
  refusal: *"Ana packed this order, so someone else checks it."* An
  agent acting for Ana is refused the same way.
- A field `:not-by` names must be a member ref; a `:not-by` field that no
  door stamps or takes as input is a declaration error.

## 5 · Addressed holds — `:by` (decision 4)

### Decision

```clojure
(hold "A visit note is signed by the visit's therapist." :by (data :therapist))
(hold "The crew confirms the day's route." :by (data :crew))
```

- **`:by <expr>`** names a member or a team, read from the row and the
  input when the call is judged.
- **Who is held.** With `:by`, every caller who is not the addressee (or,
  for a team, not on it) is held, a person as well as an agent: this is
  what *a hold naming a specific person* means. The addressee's own call
  goes through. Without `:by`, `hold` is what it is today.
- **`waits_on` comes from the law.** The `held_call` stores the
  addressee (`door.for_member` or `door.for_team`, beside today's
  `door.for_role`), and its `waiting_on.holder` names that member or
  team, so the in-flight read shows *"waiting on Dana Ruiz"* and wakes the
  seat or person who holds it.
- **Only the addressee opens it.** `an-approver-decides` refuses an Allow
  from anyone else, the caller's own person included, with *"Only Dana
  Ruiz, this visit's therapist, may allow this."* The replay runs the
  door's guards again, so a therapist changed meanwhile is judged then.
- **`:by` and `:for :role`** may stand together: the addressee must also
  hold the role, and a held call whose addressee does not is refused at
  the hold with the `[approver-unroled]` sentence (§9) as its reason.
- **An empty addressee** (`(data :therapist)` is nil) refuses the door:
  *"No therapist is named, so nobody can sign."* The remedy is the door
  that sets the field.

## 6 · Row-level reads — `:team-of` (decision 5)

### Decision

A grant's scope entry admits rows by the team a field names, resolved
live:

```clojure
{:kind "job" :actions ["start" "finish"] :team-of {:field "crew" :team "<Crew B id>"}}   ; a seat
{:kind "job" :actions ["start" "finish"] :team-of {:field "crew"}}                       ; a member: my teams
{:kind "visit" :team-of {:field "therapist"}}                                             ; a member field: on my teams
```

- **A team field** (`(ref {:kind :team})`) admits a row when it names the
  team given, or with no `:team`, any active team the grant's member is
  on.
- **A member field** admits a row when the member it names is on that
  team (or on one of the grant's member's teams).
- **Live.** It is judged at the row (`row?`) and ANDed into the collection
  query (`conds-of`) as a subquery over team membership, the way
  `:filter` is today, so a member added to Crew B sees Crew B's jobs at
  the next read, with no new grant. A retired team admits nothing.
- **One per kind**, like `:filter`, and the two may stand together
  (ANDed).
- **The crew-lead default.** The landscape rehearsal's one crew-lead seat
  for all crews becomes one seat per crew, each with `:team-of {:field
  "crew" :team …}`. A crew lead's attempt to move another crew's job is
  the ordinary not-found of a row outside the grant.

### This is where row-level read rules live

Concept 4 deferred row-level read rules here. The rule is a scope entry,
not a kind's law: the kind says what a row *is*, and the grant says who
may see it. A kind may declare a **`:scope-default`** (`{:team-of {:field
"crew"}}`), which an ask for that kind is offered, and which the
member-grant mint (`grants.clj`) uses when it mints a member's standing
grant. `:names-me` (§7) is the second rule of this family.

## 7 · Outside parties (decision 6)

### Decision

**An outside party is a member row** with `actor_type "outside"`, the
third value of the enum, labelled *"Someone outside — acts on the rows
that name them"*. It has a `display`, optional `roles` (`carrier`,
`insurer`, `supplier`), and it may be on a team ("our two suppliers").

- **Its grant admits only rows that name it.** A new scope filter,
  `:names-me [field …]`, admits a row when one of those member-ref fields
  names the grant's member. An outside member's grant is only
  `:names-me` entries, and the engine refuses to mint one that is not:
  *"An outside party's grant admits rows that name them, and this entry
  admits every job."*
- **Doors, not reads of the house.** Each entry lists its actions
  (`confirm_delivery`, `report_lost`, `approve_claim`). It never admits
  `member`, `team`, `grant` or any kind the module marks `:system`.
- **It acts through a link** or **through an event**, never with a full
  login:
  - **A link** is a single-member credential the house mints for the
    outside member (`provenance "link"`, the fourth value), sent from a
    door's effect (concept 6) or by hand. It opens a page of the rows that
    name them and the doors their grant admits, expires (default 30
    days), and is revoked by retiring the member. It cannot hold, Allow
    or approve anything not addressed to it by `:by`.
  - **An event** from a system the outside party uses runs as concept 6's
    `integration`, `on_behalf_of` the outside member, so the history says
    *"report_lost by Parcelco (via Shippo)"*.
- **The log names them.** A door an outside party runs is logged with
  their member as the actor, so `(actor)`, `:stamps` and `:not-by` work on
  them unchanged.
- **Rehearsal casts gain them.** The shop's cast gains `carrier` and
  `supplier`, the clinic's gains `insurer`, each `:type :outside`, and a
  clone's `acts_as_cast` seat may act as them, as it acts as any cast
  member today.

### Option kept: an outside party as an integration

A party with a signing system is concept 6's `integration`. When a party
has both (a carrier with webhooks and a claims clerk who answers by
link), the integration acts `on_behalf_of` the member, so one row names
the party in both paths.

---

## 8 · Honest on the probe, in rehearsal, in scenarios

- **The probe says who.** A `dry_run` of a door with `:not-by` or an
  addressed hold answers the sentence it would refuse or hold with, naming
  the member: *"Would wait on Dana Ruiz."*
- **`unavailable` says what would make it available**: *"Someone other
  than Ana checks this"*, *"Join Crew B"*, *"Dana Ruiz allows it"*.
- **Scenarios** (`defscenario`) gain `:teams` beside `:cast`:

  ```clojure
  :cast  {:ana {:roles ["crew-lead"]} :luis {} :dana {} :parcelco {:type :outside :roles ["carrier"]}}
  :teams {:crew-b {:members [:ana :luis]} :therapists {:members [:dana] :roles ["pt"]}}
  ```

  and `:as :parcelco` acts as the outside member. The readers are pure on
  that data, so a scenario needs no store.
- **The seed** registers the teams' roles, enrols the cast, then writes
  the teams, in that order.

## 9 · Fingerprint and audit (decision 7)

**Fingerprint.** The ref options, `(actor)`, `(holds-role? …)`,
`(in-team? …)`, `:stamps`, `:not-by` and `:by` are law facets: a change is
a revision. `team` rows, their members and roles are data. A grant's
`:team-of` and `:names-me` are grant data, as `:filter` is today.

**Audit findings,** in the declaration battery (`checks.clj`, `make
check-queue`). Each has its sentence, its remedy and one plain question:

| Finding | Level | Says | Remedy | Question |
|---|---|---|---|---|
| `[actor-in-prose]` | warning | A string or enum field, or a kind note, names a person by standing ("therapist", "signer", "cosigner", "crew", "lead", "carrier", "insurer", "supplier", `crew_*` enum values). | Make it a member ref with `:role` or `:in-team`, or a team ref; an outside party is an `outside` member. | "Who is the {field}, and what makes someone allowed to be one?" |
| `[hold-unaddressed]` | info | A `hold` with neither `:by` nor `:for :role`, on a kind whose rows carry a member or team ref. | Address it: `:by (data :{field})`. | "Whose tap should this wait for: yours, or the {field}'s?" |
| `[approver-unroled]` | warning | A `hold … :by (data :f)` where `f` declares no `:role` or `:in-team`, so anyone named may approve. | Give `f` the role an approver must hold. | "Who is allowed to approve this, and what makes them allowed?" |

---

## 10 · Migration — the 10 workarounds and concept 1's hold

The ticket gives the total (10) and the shapes, not the count per app or
per shape, and this spec does not guess one. Step 14 counts them against
the logs.

| Shape | Replaced by |
|---|---|
| Landscape crews as an `:enum` on the job | §2 a `team` row per crew; the job's `crew` is `(ref {:kind :team})` |
| One crew-lead seat that sees every crew's jobs | §6 one seat per crew with `:team-of` |
| A crew member assigned who is not on the job's crew | §3 `(ref {:kind :member :in-team (data :crew)})` |
| "The same crew already at the Parks' yard" with no crew to name | §2 teams, read by a concept 1 `exists` over that day's jobs on the crew, which names the Parks' job and, from the `team` rows, the free crew |
| Clinic therapist as free text | §3 `(ref {:kind :member :role "pt"})`, with "licensed therapists" a team conferring `pt` |
| Clinic signer and cosigner as free text | §3 refs with `:role`; §5 `:by (data :signer)` |
| The checker may be the packer (shop) | §4 `:stamps` and `:not-by` |
| Carrier unable to act; the mayor transcribes delivery and claims | §7 `outside` member with `:names-me`, through a link or concept 6's event |
| Supplier unable to act; the mayor transcribes confirmations | §7 the same |
| Insurer unable to act; the mayor transcribes approvals | §7 the same, and concept 6's remittance `on_behalf_of` the insurer |
| Concept 1's *a hold naming a specific person* | §5 `:by` |

---

## 11 · The film

The landscaping showcase's first half:

1. The owner books the Hendersons' patio for Thursday on **Crew B**. Crew
   B is a row: Ana Lopez and Luis Ortega. The house refuses at once:
   *"Crew B is at the Parks' yard on Thursday. Crew A is free."* The
   owner picks Crew A, and the job's lead picker offers only Crew A.
2. Thursday morning, Ana's crew-lead seat reads its day. It sees Crew B's
   two jobs, not the Hendersons'. Marco joined Crew A last week; his phone
   shows the Hendersons' job without anybody re-granting him.
3. The paving stones are late. The supplier opens the link the order sent
   and taps **Delayed to Friday**. The history says *"delay by Stoneyard
   (outside), from their link"*. Nobody transcribed it.

The film is step 11's acceptance walk, in a clone, with the supplier
acting as its cast member.

---

## 12 · Not in this spec

- **Shifts and availability.** "Luis is off on Fridays" is concept 2's
  calendar on the member, not membership.
- **Nested teams.** A team's `members` are members; a team of teams waits
  for a rehearsal that needs it.
- **Outside parties signing in.** An outside member who wants a login
  becomes an inside one by a person's act; the link never upgrades.
- **Re-judging existing rows** when a member leaves a team or loses a
  role (§3). A house that wants it writes a concept 1 query.
- **Delegation between members** ("Dana signs for Sam this week") beyond
  today's `acts_for`.

## 13 · Build plan

Each step is one ticket and one pull request, in order, each under the
800-line ceiling. Steps 1 to 6 are the inside house, 7 and 8 are outside
parties, and no app moves until step 11. Every step is in
`ckopsa/waymark` unless it names another repository.

1. **Engine — the `team` kind.** The `:teams` module, the kind, its four
   doors and their default holders, the unique active name, roles checked
   against the registry, `team-admin` registered. Test: a retired team
   confers nothing.
2. **Engine — the readers.** `(actor)`, `(actor :acts-for)`,
   `(holds-role? …)` (row roles plus team roles), `(in-team? …)`; canonical
   nodes; the judgment records what they read. Test: a role conferred by a
   team is lost by leaving it.
3. **Engine — ref options.** `:role` and `:in-team` on member refs, the
   `<field>-role` and `<field>-in-team` guards, the evidence sentence,
   the narrowed `x-ref`. Test: a vector ref refuses its one unqualified
   element by name.
4. **Engine — `:stamps` and `:not-by`.** The stamped field kept out of
   input, the derived guard over actor and `acts_for`, the declaration
   error for an unwritten field. Test: Ana's agent cannot check what Ana
   packed.
5. **Engine — addressed holds.** `hold … :by`, `door.for_member` and
   `door.for_team`, `waiting_on` from the law, `an-approver-decides`
   refusing everyone else, the empty-addressee refusal, `:by` with
   `:for :role`. Test: a person's own call is held when it is not theirs
   to make; the addressee's goes through.
6. **Engine — `:team-of`.** The scope entry, `row?` and `conds-of`, the
   member-field form, `:scope-default` and the member-grant mint. Test: a
   member added to a team reads its rows on the next query.
7. **Engine — outside members.** `actor_type "outside"`, `provenance
   "link"`, `:names-me`, the mint refusal for any other entry, the link
   credential, its page and expiry. Test: an outside member cannot read a
   row that does not name them, nor Allow an unaddressed hold.
8. **Engine — outside parties through events.** Concept 6's
   `integration` acting `on_behalf_of` an outside member; the history
   line. Test: the logged actor is the member, via the integration.
9. **Engine — casts and scenarios.** `:teams` in a seed and in
   `defscenario`, `:type :outside` cast members, `acts_as_cast` for them,
   the seed's order.
10. **Engine — the audit.** The three findings of §9 in `checks.clj`, with
    their questions.
11. **Engine — landscape.** Crews as teams, the job's `crew` and `lead`
    refs, one crew-lead seat per crew with `:team-of`, the supplier as an
    outside member; §11's three steps are its acceptance walk.
12. **Engine — clinic.** Therapist, signer and cosigner as role refs,
    "licensed therapists" as a team, the note's addressed hold, the
    insurer as an outside member.
13. **Engine — shop.** `:stamps`/`:not-by` on pack and check, the carrier
    and the supplier as outside members. Each of 11–13 with a scenario.
14. **Survey.** Re-read the three rehearsal logs, check §10 against the
    10 and concept 1's hold, and file one ticket for each workaround these
    steps did not retire.
