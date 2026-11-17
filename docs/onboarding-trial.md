# The onboarding trial

The onboarding epic is judged by one count: the steps an owner takes,
and the refusals an agent meets, between a fresh house and a live org.
The trial measures it the same way each time, on a clone, with no
person present (ticket b5518453).

## The brief

Riverside Vet Clinic answers the inquiries people email it.

- Fixed facts (opening hours, the address, which animals it sees) are
  answered on their own, with no one asked.
- Anything about a price waits for the owner: the agent drafts the
  reply, and the owner says yes or changes it.
- Every inquiry gets a reply within 48 business hours.

The owner types this brief into the house's first plan, as a person
would.

## Who does what

**The agent** (the onboarding seat and the code seats it hires) turns
the brief into a plan's tree of parts, builds the org in `trial/orgs`
on the bench, submits it, reads its checks and findings, and takes
the plan to `go_live`. When the findings name something only the
owner can decide (the seed records one: who answers a pricing
inquiry), the agent asks it as a business question.

**The owner** writes the brief, agrees to the plan, answers the
business question, and allows each held call that waits on a person.
The owner is the cast member `owner` of the seed (`trial-owner`), and
every one of these steps is taken as that member, through the clone's
own cast (`as`), never through the mayor.

## What done looks like

The plan is live, and one rehearsed inquiry of each kind ends
correctly: a fixed-fact inquiry is answered with no one asked, a
pricing inquiry waits for the owner and is then sent, and neither is
left unanswered past 48 business hours.

## The seed

`waymark10/resources/waymark10/onboarding-trial/seed.edn` names the
factory seed under `:extends`, so the loader walks every factory step
with the trial's params: `trial` true, the owner `trial-owner`, the
repository `trial/orgs` and the model `claude-opus-5`. A boot's
`WAYMARK10_SEED_PARAMS` may override any of them.

Its `:walled` names `bench` and `mail`. Booted with a wall
(`WAYMARK_WALL_URL`), the factory's `bench` and `mail` rows are made at
the wall's address, with the trial's approvals (writes go out on a
reason). The wall answers their calls from `:rehearsal :effects`:
prepare, read, find, edit, edit_many and check answer clean; submit
answers a head sha; feedback answers green checks on that head; merge
answers merged; create_repo answers the repository; artifact answers
the findings document with the one business question; mail send
answers sent. Nothing leaves the clone.

The seed's steps need the factory's boot rows (domain `factory`), so
the clone boots factory-only (`FACTORY10_ONLY=1`), as a factory house
does.

## How to run it

1. **Clone up.** From the home house, bring up a clone at a commit
   with seed `onboarding-trial` and a wall.
2. **Clone call.** As the clone's cast member `owner`, write the brief
   above on the plan `house` (`write_brief`). The onboarding seat
   wakes and works. Each time the plan or a held call waits on the
   owner, take that step as `owner` through clone call. Do it until
   the plan is live, then send one rehearsed inquiry of each kind.
3. **The count.** Inside the clone, beside its engine:

   ```bash
   bash scripts/onboarding-trial            # the boot's owner
   bash scripts/onboarding-trial trial-owner
   ```

   It reads the clone's database (`WORKQUEUE10_DSN`) and prints one
   JSON document:

   - `owner_moves`: each move made as the owner after the seed loaded,
     with its kind, row, door, from and to state.
   - `held_calls`: each held call, its tool, its state and who allowed
     it (`allowed_by`).
   - `refusals`: each refusal an agent met, from the sittings'
     `refusal_log`: its sitting, seat, guard, remedies and door.
   - `approval_requests`: each ask, with its state.
   - `totals`: `owner_steps`, `held_calls`,
     `held_calls_allowed_by_owner`, `refusals` and
     `approval_requests`.

   The seed's own steps walk as the owner too; the count starts after
   the seed's completion marker, so they are not counted.

The trial is done when the home mayor brought the clone up at a
commit, ran it with no person's tap, and read the count.
