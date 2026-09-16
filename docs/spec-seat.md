# Spec — the seat: a declaration, not a copied grant

**Thesis.** A standing agent holds its authority in a grant. The
grant's scope is a copy of an ask, and each extend-ask copies the
scope forward again. A copy drifts. The seat replaces the copy: the
application declares the seat's scope once, as data, beside its
kinds. A grant minted from a seat reads the declaration at each mint.
The declaration gate checks the seat before the engine serves it.

This document is written in ASD-STE100 Simplified Technical English.
Technical names from the codebase (grant, scope, leash, ask, anchor,
door, kind) keep their spelling.

## Epistemic status

The failure is on record three times. Each time, the stored scope
was the cause.

- `spec-standing-agent.md` (waymark-ycp): the extend-ask copied the
  grant's scope, and the approval appended it. One grant reached 74
  entries for 20 kinds. The ask door refused the next ask. The leash
  died twice with nobody asking.
- waymark-enx: slice 2 of the hypotheses work removed the action
  `value.restate`. The stored scope still named it. The driver copied
  the scope forward, and every extend-ask got a 409. The leash would
  have died silently at 2026-08-31T22:49Z.
- waymark-h6y: an ask filed with no `expires_at` gets a 30-minute
  default. The mint copies that value onto the grant. The person
  approves, and the access dies minutes later.

The framework's rule is that the declaration is the only source. The
law, the audit, the UI, and the agent surface all come from one
spelling. The grant's scope is the one piece of authority that does
not. It lives in a row. A row cannot fail the declaration gate. This
spec moves the seat's authority to where the gate can see it.

The essay this work answers (Yegge, "Seats and Sunsets", 2026-09-15)
gives the cost. An agent with no seat must derive its authority in
each session. An agent with a seat looks it up. Waymark already has
the lookup: `waymark_discover` carries `doors.ask.anchor`. What it
does not have is a durable answer to "what scope is this seat
permitted". That answer is the seat declaration.

## What exists

- `waymark10/src/waymark10/server/roles.clj`: the `:role` kind. A
  role is a name and a description. Its meaning lives in guards and
  in grants. A role carries no scope.
- `waymark10/src/waymark10/server/grants.clj`: the `:grant` kind
  `{audience, scope, expires_at}` and the `:approval_request` kind
  `{grant_id?, task, scope, expires_at}`. The scope schema is shared.
  Four create guards check a scope against the registry:
  `scope-names-real-kinds`, `scope-names-real-actions`,
  `scope-filters-are-filterable`, `scope-omits-private-kinds`.
- `approval-effects!` runs after the approve commits. An anchorless
  ask mints a grant with the ask's scope. An anchored ask extends the
  named grant, and `merge-scope` folds the two scopes to one entry
  per kind.
- `asks-are-short` caps an ask at `grant-max-ttl-seconds`, 24 hours.
  `reentry-standing-ttl-seconds` in `members.clj` is a second,
  longer ceiling: seven days for the standing re-entry credential.
- `scripts/standing-agent-tick.sh`, step 4: the driver reads the
  grant row, copies its scope, and files the extend-ask with that
  copy.
- `make check-queue` runs the declaration-time battery in
  `waymark10/src/waymark10/checks.clj` with no database. A
  declaration that fails the battery never serves a request.

## The design

### 1. The seat declaration

An application declares its seats beside its kinds. A seat is a map
with a name, a scope, and a leash policy. The scope uses the scope
schema that grants already use.

```clojure
(defseat composer
  {:name "composer"
   :description "Reads the feed and drafts outcomes for the household."
   :scope [{:kind "outcome" :actions ["create" "restate"]}
           {:kind "insight" :actions ["create"]}
           {:kind "feed" :actions [] :filter {:preview_as "composer"}}]
   ;; the longest leash an ask from this seat can request
   :standing-ttl-seconds 604800
   ;; a substitute sits in the seat with these doors removed
   :substitute {:drop [{:kind "insight" :actions ["create"]}]}})
```

The engine registers seats the way it registers kinds. The engine
options accept `:seats`, a map from seat name to declaration. The
`:members` option is the precedent.

### 2. The checks

The declaration battery gains one check, `check-seats`. It runs the
four scope guards against each seat at declaration time. A seat that
names a kind, an action, a filter field, or a private kind that the
registry does not declare fails the check. The failure names the seat
and the entry.

This is the whole point of the leg. When a kind retires an action,
the seat that names it fails `make check-queue`. The gate goes red on
the same push. Nobody finds out at 22:49Z from a dead leash.

`check-seats` also refuses:

- a `:standing-ttl-seconds` above `reentry-standing-ttl-seconds`;
- a `:substitute :drop` entry that the seat's scope does not contain;
- two seats with one name.

### 3. The ask names the seat

`:approval_request` gains one optional field, `seat`. An ask that
names a seat carries no scope of its own. The engine fills the ask's
`scope` from the declaration at create time, so the approver reads the
scope that the seat holds today. `requester-holds-the-grant` still
judges the anchor.

An ask that names a seat can request a leash up to the seat's
`:standing-ttl-seconds`. `asks-are-short` reads the seat's ceiling
for a seat ask and the 24-hour ceiling for a scope ask. An ask with
both `seat` and `scope` is refused at the door.

### 4. The mint reads the declaration

`:grant` gains one optional field, `seat`. `approval-effects!` reads
the seat declaration at approve time and mints or extends with that
scope. The approved ask's stored scope is the record of what the
person saw. The declaration is the source of what the grant holds.

If the declaration changed between the ask and the approval, the two
scopes differ. The effect then refuses the mint and writes a warning
that names the seat. The requester files a new ask. This keeps the
rule that the approver approves the scope shown.

An extend on a seat grant REPLACES the grant's scope with the seat's
scope. It does not merge. The merge fold stays for scope asks. A seat
grant has one source, so it has nothing to merge.

### 5. The driver files one line

Step 4 of `standing-agent-tick.sh` reads the grant's `seat` field.
When the field is set, the driver files
`{grant_id, seat, task, expires_at}` and no scope. The copy is gone.
When the field is empty, the driver keeps the current fold.

### 6. Discover carries the seat

`doors.ask` in `waymark_discover` gains `seat`: the seat name the
caller's grant carries, or null. The MCP instructions add one
sentence: "If doors.ask.seat is set, file the extend-ask with that
seat name and no scope."

### 7. The substitute

A seat ask can carry `substitute: true`. The engine mints the seat's
scope minus the `:substitute :drop` entries. The grant records
`substitute: true`. The actor on each transition already carries the
member; the model tier on the actor is the next leg, not this one.

## The forks, decided

**A seat is a declaration, not a role row.** A role row is data, and
data cannot fail `make check-queue`. The stored scope on the grant is
the failure this spec removes, so the seat cannot be stored the same
way. The `:role` kind keeps its job: a name a member holds, checked
at assignment.

**The mint refuses on a changed declaration.** The alternative is to
mint the current declaration and let the approver's view be stale.
That breaks "the requester's grant gains exactly the scope shown".
A refusal with a warning costs one more ask. A silent change costs
trust.

**The standing leash is a fork for the owner.** `spec-standing-agent`
records that the daily tap is the law working. The essay's position
is that trust in a seat is paid once and cached. This spec makes the
seat's ceiling a declared number with a hard cap of seven days. The
recommendation is seven days for the composer seat and 24 hours for
every scope ask. The owner decides the number per seat, in the
declaration, and the record shows the decision.

## Recorded punts

- The seat declaration is not yet fingerprinted as law. `check-seats`
  runs at boot and at `make check-queue`. A law revision for seats is
  a named follow-up.
- A seat lives in application code. A seat that a person writes in
  the UI, as a row, is not in this leg.
- The 30-minute default on an anchorless scope ask (waymark-h6y) is
  unchanged here. A seat ask defaults its leash to the seat's
  ceiling, which removes the trap for seat grants only.
- The `approval_request` and `grant` fingerprints move, because both
  schemas gain a field. The pinned hash in
  `waymark10.decision-sugar-test` must be updated with the change.

## What proves it

A test namespace `waymark10.seat-test` with these cases:

1. A seat that names a retired action fails `check-seats`. The
   failure names the seat and the entry.
2. An ask that names a seat is created with the seat's scope filled
   in. An ask with both `seat` and `scope` is refused.
3. Approval of a seat ask mints a grant with the seat's scope and the
   seat name stamped.
4. An extend on a seat grant replaces the scope. After the extend,
   the grant holds one entry per kind and exactly the seat's entries.
5. A declaration change between ask and approval refuses the mint
   and writes the warning.
6. A substitute ask mints the seat's scope minus the drop entries.
7. A seat ask can request up to the seat's ceiling. A scope ask is
   still capped at 24 hours.

The conformance suite must invoke every new door. `make check-queue`
must pass with the composer seat declared in `workqueue10`.

## Effort

**Small to medium.** The scope schema, the four scope guards, the
mint effect, and the discover door all exist. The new code is one
check, two optional fields, one branch in the effect, one branch in
the driver, and one sentence in the instructions. The migration adds
two nullable columns.
