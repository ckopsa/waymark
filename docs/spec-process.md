# Spec — `:process`: the workflow as a resource

**Thesis.** The owner's question, 2026-09-14:

> One thing I noticed we are missing is the idea of workflows. A process
> that touches multiple resources in a cohesive way. Like sagas almost in
> microservices. How would we represent that in waymark?

The answer the house already gives, in the worksheet framing
(`docs/waymark10-next.md`) and in three landed instances, is: **a workflow is
a resource.** A row with its own machine, staged, then applied through the
target kinds' own declared doors, so their guards, their audit log and their
push all still hold. `outcome.make_it_so`, `recipe_proposal`, and
`plan-on-create`'s plan_day births are each a hand-written instance. What was
missing was the noun — a declaration the engine could project the steps from,
so that *what the steps are*, *where a row stands in them*, *what reverses
step two*, and *which doors the whole story reaches* are facts the engine
holds rather than facts a handler's author remembers.

A microservice saga exists because there is no shared transaction. Waymark
has one — the cross-write doors `ctx :invoke` and `ctx :create` run the
target's full per-item algorithm inside the outer write's transaction, under
the outer principal (design §24) — so a process here is simpler than a saga in
the common case and needs a saga's compensation only when its steps
deliberately span transactions. The key spells both.

## Epistemic status

**The modest claim first: this key invents no mechanism.** It is the
`:decision` key's own argument (`docs/spec-decision-kind.md`), applied one kind
of machine over: `:process` desugars into ordinary `:states`, `:actions`,
handlers, schema entries and `:touches`, ahead of `:decision` and `:flow`,
before the check battery and before the fingerprint. Nothing downstream — the
router, the render probe, collections, OpenAPI, MCP, the conformance driver —
learns a new noun. Each projected door's handler makes one call through a
cross-write door that already existed.

**What is new is what is derived.** Three things a hand-written process has to
get right by care are now functions of the step data:

- `:touches` on every projected door comes from the steps, so the
  blast-radius advertisement cannot lie by omission (§9 of the vocabulary);
- the handler's stateable identity (`:waymark10/form`) is built from the step
  DATA, so the fingerprint moves exactly when a step does and never with the
  load (the waymark-j82 discipline, kept);
- the cross-kind facts — the target door exists, takes the input the step
  gives it, is neither bulk nor fenced, the undo departs from where the do
  landed — are checked by the assembly battery where every kind is known.

**What is deliberately not attempted.** No branches, loops or conditions: a
step names a declared door and binds fields to its input with `(data …)`
reads of its own row and `(now)`, nothing else. A process that needs a branch
is two processes, or a guard on the target door. No log-consumer
choreography: the dayplan spec moved births out of a consumer and into the
parent's own create, and this spec keeps that posture — the process row is the
orchestrator, and the owns cascade stays the only other cross-kind mechanism.

## The declaration

```clojure
(defresource plan_rollover
  {:kind :plan_rollover
   :process
   {:mode  :durable                              ; or :atomic (the default)
    :binds {:plan_id :plan}                      ; the subjects, as refs — create input
    :steps [{:name :close
             :do   [:plan :plan_id :finalize]    ; invoke the row plan_id points at
             :undo [:plan :plan_id :reopen]}     ; the door that reverses it
            {:name  :compile
             :do    [:grocery_list :create {:plan_id (data :plan_id)}]
             :binds :list_id                     ; the born id, stamped on this row
             :undo  [:grocery_list :list_id :discard]}
            {:name  :open_next
             :do    [:plan :create {:previous_plan (data :plan_id)}]
             :binds :next_plan_id
             :pivot true}]}})                    ; irreversible from here on
```

`:process` is a closed map — `:binds`, `:steps`, `:mode` — and a step is a
closed map — `:name`, `:do`, `:binds`, `:undo`, `:pivot`, `:display`. A
`:do` is `[kind id-field action input?]` (invoke the target row the named
ref field of THIS row points at) or `[kind :create input?]` (birth a target
row). An `:undo` is `[kind id-field action]` and takes no input: a
compensation is a plain reverse, never a re-ask. Input values are scalar
literals or law forms over the expression vocabulary reading `(data :field)`
of the process row and `(now)`; `input`, `var` and `it` refuse at the def
site.

## What projects, from what

| declared          | projected                                                     |
|-------------------|---------------------------------------------------------------|
| `:steps`, durable | `:staged → <step>_done → … → :done`; one door per step, from the previous landing to its own; `roll_back` from every landing before the pivot to `:rolled_back`; `abandon` from `:staged` to `:abandoned` |
| `:steps`, atomic  | `:staged → :done` through one `run` door; `abandon`             |
| `:binds`          | one required `:waymark/ref` entry per field, the create input   |
| step `:binds`     | one optional `:waymark/ref` entry per field, stamped by the step, absent from the create schema |
| every door        | `:touches` from the steps (the roll-back's every entry `:may`, because it reaches a different set from each origin); the handler's form from the step data; a `:one-way` sentence naming the door it runs |
| always            | `:filterable` over state, newest-first sort, a default summary  |

Every projection fills a blank: a hand-spelled schema entry wins over its
generated twin, and a `:summary`, `:sortable` or `:create-schema` the author
wrote stands. Two things are refused rather than merged: `:states`, `:initial`
or `:terminal` beside the key (*the steps are the machine*), and `:decision`
beside it (*one machine per kind*). An action also named in `:actions` is the
one-home-per-action refusal `:flow` and `:decision` already make. The
normalized `:process` map stays on the declaration for the assembly battery;
`fingerprint-of` names no facet for it, so it hashes through the doors it
projected and nowhere else.

## The two modes

**Atomic** is `outcome.make_it_so`'s rule, generalized: one tap, one
transaction, every step in order through the cross-write doors. A refusal
anywhere — a target's guard, a missing row, a wrong state — throws the
target's own problem out of the handler, and the write's transaction rolls
every inner write back with it. The process does not read `accepted` while a
piece silently did not land, and no compensation exists to spell: `:undo` and
`:pivot` refuse in this mode, because they would describe a partial
completion that cannot occur.

**Durable** is for steps that cross a human tap or an external system. Each
step is its own door and its own logged transition; the row's state names the
last step done, so a person, an agent, or the generic UI reads exactly where
the process stands and which door is next. A refused step refuses only that
door — the row stays where it was, with the target's sentence — and the next
tap tries again. Compensation is real here, and it is one door: `roll_back`
walks the completed steps newest-first through each step's `:undo`, in one
transaction, and lands the row in `:rolled_back`. Which steps completed is
read from the row's state, never from a counter.

The **pivot** is the saga's own word for the first step that cannot be
undone. `roll_back` is offered from every landing *before* it and from none
at or after it, and the def-site check holds the declaration to the
consequence: every step a roll-back could reach must spell its `:undo`, or the
declaration refuses naming the step and the two ways out (declare the undo
door, or mark the first irreversible step `:pivot`). A durable process with
no pivot and no undos has exactly one legal shape — a single step — which is
also the honest one.

## The checks, in two tiers

**At the def site** (`waymark10.process/desugar`, single-kind): the closed
key sets; snake-case step names, unique, and none wearing a projected door's
name (`run`, `roll_back`, `abandon`, `create`); the `:do`/`:undo` grammar;
an invoked or undone id field that some `:binds`, step `:binds` or `:schema`
entry supplies; a birth with an undo binding the born id the undo will need;
the input vocabulary; undo coverage before the pivot; at most one pivot; no
pivot or undo in atomic mode.

**At assembly** (`checks_assembly/check-process`, every kind known): the
target kind is registered; the id field is a `:waymark/ref` at that kind;
the action exists and is neither bulk (a collection affordance) nor fenced
(the process holds no etag — the cascade runner's rule); a door that takes
no input is not sent one, and a door with required input is not sent none;
a birth targets a kind whose create door is named `:create`; and an undo
departs from where its do landed — the create's `:initial` for a birth, the
action's `:to` for an invoke — so the compensation is honest the same way
`verify-undo-pointers` keeps a plain `:undo` honest.

`check-touches` then reads the derived touches like any other, and the
conformance library's `touches-violations` holds every logged run to them by
correlation id: each inner write wears the step's correlation id, so a
process reads as one story in the log.

## Recorded boundaries, each a sentence

- **A step door takes no input.** What the steps need rides the process
  row's own data, declared at create; a step that wanted to ask the tapper a
  question is a `:decision`, not a step.
- **Roll-back is one transaction.** A `:compensating` state with per-undo
  transitions was considered and not taken: a compensation that could itself
  half-land would need a compensation, and the honest answer to an undo door
  that refuses is the refusal, with the row still where it was.
- **A birth is undone through the born row's own door**, by the field the
  step bound — never by "deleting" it. Nothing in this house deletes.
- **A birth reaches only a kind whose create door is named `:create`.** The
  cross-birth door already births under the target's own create-action name;
  the touch the sugar advertises has to name it too, and `:create` is the
  one name it can know without the registry. A kind with a renamed create
  door is a named follow-up, not a silent mismatch.
- **The projected doors are `:one-way`**, each with a sentence, because
  `check-reversible` asks a reversible door for an unconditional edge back to
  its origin and `roll_back` lands in `:rolled_back`. The sentence says
  whether roll-back can still undo the step's effect.
- **`:within` is not projected.** A target door that should open only for
  this process declares `:reads [:within]` itself, exactly as
  `composition_request.answer` does; the sugar cannot know which of a
  target's doors want that wall.

## The proof

`waymark10/test/waymark10/process_sugar_test.clj`, against the in-memory
storage twin: the durable and atomic projections (states, doors, derived
touches, the ref entries and the create schema, every handler stateable);
the fingerprint a function of the steps (same steps, same hash; a moved step
input, a moved hash); a durable walk that moves the plan through its own
door as the tapping principal under the step's correlation id, births the
list and binds its id, seals at the pivot, and refuses roll-back past it;
roll-back newest-first through the declared undo doors; a refused inner
write refusing the step and landing nothing; an atomic run landing all or
none; the touches promise held by `touches-violations`; and the def-site and
assembly refusal sentences, each one.

## Named follow-ups

- **Respell `outcome.make_it_so` through `:process :atomic`** (waymark-rwpl) — the proof
  the `:decision` key gave with `approval_request`, whose hash did not move.
  Outcome's pieces are per-part rows with their own verdict doors, so the
  respelling is a real design pass, not a mechanical one; it is filed as a
  bead rather than done here.
- **Advertise the steps on the envelope** (waymark-0zjk). The normalized `:process` rides
  the declaration; the render layer does not yet project it, so a client
  reads the process's shape from its states and doors (which is complete)
  rather than from a `steps` block (which would be nicer).
- **A renamed create door as a birth target** (waymark-wz4s).
