# Spec: document retention and evidence as declared law

Concept 4 of epic `2bb0aed8` (ticket `a225ecb9`). A spec only: no engine
code lands with it. Its last section is the build plan the mayor splits
into children, the way `docs/spec-calendar-time.md` (concept 2) and
`docs/spec-ledger-money.md` (concept 3) end. The owner's word for it
(2026-10-22): strong defaults, flexible later. The decisions below are
the mayor's statement of those defaults; this spec gives each one its
shape and does not reopen it.

> A clinic signs a patient's treatment note. Nobody can change it after
> that: a correction is a new addendum beside it. The note's `sign` door
> refuses while the consent form is missing, in one sentence naming the
> form. Seven years after the last visit, the sweep purges the patient:
> the name, the birth date and the scanned forms are gone, and the row
> still says who purged it and when.

Concepts 1 to 3 gave the law its rows, its time and its money. This
concept gives it the **papers**: a field that holds a document, a door
that wants one, a kind that is kept for a declared time, a purge that
really erases, and a state after which the row is frozen. The
rehearsals logged 11 workarounds of this concept. Each shape is in §9
with the construct that replaces it.

---

## 0 · Today

- **Attachments exist** (`server/attachments.clj`). An `attachment` row
  is the metadata (`name`, `media_type`, `size`, `sha256`) with the
  states `pending → stored → deleted`. The bytes ride `PUT/GET
  /api/attachments/{id}/bytes`; a successful PUT moves the row to
  `stored` through `mark_stored`, a system door no envelope shows.
  `delete` is a confirm door, and the metadata row stays as the record.
- **A row attaches by reference.** A `:waymark/ref` field may point at
  an `attachment` like any kind. Nothing says the field is a document,
  which media types it takes, or how many. Nothing checks the referenced
  row is `stored`, so a ref to a `pending` upload (no bytes yet) passes.
- **The purge sweep deletes bytes, not people.** `purge-deleted!`, run
  once per database under `:elected :attachments-purge`, removes the
  bytes of `deleted` attachments. It never touches the row that
  referenced them. A kind's own `purge` door is whatever its author
  wrote: in the clinic rehearsal it moved the patient to `purged` and
  left every field as it was.
- **Retention is the engine's own, and only for its record kinds.**
  `transcript` and `walk` keep their lines and frames for a seat's
  `transcript_days` (or the walk's days), then the sweep deletes them
  with `store/delete-rows!` and invokes `purge` on the row, which keeps
  its counts as the proof of what existed. That is the shape this
  concept wants, but it is hand-written in two namespaces and no app
  kind can declare it.
- **Time can say "seven years", and nothing acts on it.** Concept 2
  gives `(years 7)` and `:expected-age {:inactive {:until '(+ (data
  :last_visit_on) (years 7))}}`. That files an `aged` ticket for a
  person. It refuses nothing and erases nothing.
- **Nothing freezes a row.** An edit door is an action with `:edit`;
  it runs in whatever `:from` states it names. "Signed notes are not
  edited" is the author remembering to leave `:signed` out of every
  edit door's `:from`, and the clinic rehearsal forgot one.
- **`:owns`** names child kinds through a ref (`dashboard` owns
  `dashboard_slot` through `:dashboard_id`). It is how a correction can
  hang off a frozen row.

## 1 · The decision in one paragraph

**A document is a typed ref, a need for one is a derived guard, and
retention is a declared clock on a declared door.** The field word
`:waymark/document` is a ref to `attachment` with an optional `:accept`
and `:min`; who added it and when are read from the attachment's own
log, never stored twice (§2). `:evidence` on a door names the document
fields that must hold stored files before the door opens, and derives a
guard the way `:occupies` does (§3). `:retain` on a kind says how long
a row is kept, from which date or state, and which door ends it; it
derives a guard on that door and a sweep that walks due rows through
it (§4). That door, the **purge**, is legal erasure: it clears the
kind's declared personal fields, deletes the bytes of their documents,
and leaves the row and its log as an audited skeleton (§5).
`:frozen-in` names the states in which no door may change the data;
corrections go to a child kind through `:owns` (§6). Row-level read
rules (who may see a patient's documents at all) are concept 5's.

---

## 2 · Documents — `:waymark/document`

### Decision

```clojure
;; on :job (landscape)
[:photos  {:waymark/document {:accept ["image/*"] :min 2}}  [:vector :string]]
;; on :patient (clinic)
[:consent_form {:waymark/document {:accept ["application/pdf" "image/*"]}} :string]
```

- **The value is a ref, or a vector of refs, to `attachment`.** A
  scalar field holds one document; a vector holds several. The field
  word adds `:ref :attachment` to the field, so every reader of refs
  (labels, `follow`, the form's picker) works unchanged.
- **`:accept`** is a list of media types; a `type/*` entry takes the
  whole family. A write whose attachment's `media_type` is not accepted
  is refused by the field's schema check, naming the field, the file and
  the types it takes. Absent, every type is accepted.
- **`:min n`** is the count the field must reach to be *complete*. It
  is not a schema minimum: a job is created with no photos and gains
  them later. It is what `:evidence` (§3) checks. Absent, it is 1.
- **Who and when are the attachment's.** "Added by Dana on Tuesday" is
  the attachment row's `create` and `mark_stored` log entries. The
  envelope shows them beside the field (`added_by`, `added_at`, read
  from the log at render), and the row stores nothing but the ref. A
  field that tried to store them would drift from the log.
- **A document field is personal unless it says otherwise.** §5 erases
  it on purge. `{:personal false}` keeps it (a landscape job's photos
  of a patio are the business's record, not the customer's).

### Option kept: a `document` kind beside `attachment`

A kind of its own could carry a title, a category and an expiry. It is
kept for when a case needs one: today every one of those is a field on
the owning row (`consent_signed_on`), and a second kind would split one
file's story across two logs.

---

## 3 · Evidence — `:evidence`

### Decision

```clojure
;; on :job's complete door
:evidence {:photos {}}
;; on :note's sign door — a field on another row, through a ref
:evidence {:consent_form {:via :patient_id}}
```

`:evidence` on a door is a map of document fields to options. The
engine derives one guard per entry, `<field>-is-filed`, as a tree,
fingerprinted like any authored guard (concept 2's `:occupies`
rule). The guard is true when the field holds at least `:min`
attachments, every one of them `stored`:

```clojure
'(and (>= (count (data :photos)) 2)
      (every [a (data :photos)] (= (follow a :state) "stored")))
```

The guard does not re-judge the type. The type is judged when the
attachment is written to the field (§2), by every door that writes
it — a handler's, a create's and a worksheet import's alike. A law
revision that tightens a field's `:accept` is reported by the law
sweep as a `document_type_drift` finding naming each row that now
holds an unaccepted type.

- **`:via`** reads the field on the row a ref names (`(follow (data
  :patient_id) :consent_form)`), so the note's door can want the
  patient's form.
- **`:min`** overrides the field's for this door (a quote wants one
  photo, completion wants two).
- **The refusal names the missing document**: *This job needs 2 photos
  to be completed; it has 1.* / *The note cannot be signed: Ana Ruiz's
  consent form is not on file.* A `pending` attachment says so: *the
  consent form is still uploading.* The remedy is the field's own edit
  door, with the field named.
- **Unknown is pending.** On the render probe the attachment states are
  readable, so the guard is judged; where a `follow` crosses a grant
  that cannot see the row, it is concept 1's unknown, and pending.

---

## 4 · Retention — `:retain`

### Decision

```clojure
;; on :patient
:retain {:for  '(years 7)
         :from :last_visit_on      ; a date field, or a state: {:state :closed}
         :then :purge}
;; on :order (shop): "keep order and tax records seven years"
:retain {:for '(years 7) :from {:state :fulfilled} :then :purge}
```

- **`:for`** is a concept 2 period. **`:from`** is a date field, or a
  state whose entry the log dates. The due date is concept 2's `:until`
  arithmetic: `(+ (data :last_visit_on) (years 7))`, or the entry
  instant's date in the house's zone plus the period.
- **A null `:from` is not due.** A patient with no visit is kept and
  never swept, until the field is set.
- **`:then`** names the door that ends the row's keeping. It must be a
  `:purge` door (§5); the declaration check refuses any other.

### What the engine writes

1. **A guard on `:then`**, `retention-has-passed`, as a tree:
   `(>= (today) (+ (data :last_visit_on) (years 7)))`. The purge before
   the date refuses: *Ana Ruiz's records are kept until 14 March 2031.*
   A person cannot purge early. A legal order to erase early is a
   second door the author declares without `:retain`'s guard, held for
   a person's tap.
2. **The retention sweep**, beside the attachments' purge sweep and
   elected the same way (`:elected :retention`). Each pass, for each
   kind with `:retain`, it pages the rows whose due date has passed (a
   `:from` field must be `:filter`, which the check enforces) and
   invokes `:then` on each as the engine's `retention` actor. The log
   line says *purged by the 7-year retention of patient, due 14 March
   2031*. A refusal (a guard the author added) is logged and the row
   is tried on the next pass; it never stops the sweep.
3. **The due date in the envelope.** `meta.retained_until` shows the
   date, so the person reading a patient sees when it goes.

---

## 5 · Purge is legal erasure

### Decision

```clojure
:fields   {:name {:personal true} :born_on {:personal true} :phone {:personal true}}
:actions  {:purge {:from #{:inactive} :to :purged :purge true
                   :safety {:confirm true :reversible false}}}
```

A door with `:purge true` is the purge. When it commits, in its own
transaction:

- **Every `:personal` field's value is cleared** (set to nil; a
  required field is relaxed to optional in the `:purged` state by the
  declaration, which the check enforces). Document fields are personal
  by default (§2).
- **Every attachment those fields referenced is deleted**: its row
  moves to `deleted` through `delete`, and the existing purge sweep
  removes the bytes within a minute. An attachment that another live
  row still references is not deleted; it is unlinked from this row
  only, and the log says so.
- **The row stays, with its log, as an audited skeleton.** Its id,
  kind, state (`purged`), its non-personal fields and every transition
  remain. The purge's own log line records who, when, and the names of
  the fields it cleared, never their values.
- **The log keeps no copy.** A transition's `judgment` and input that
  carried a personal field's value are redacted by the purge (the value
  becomes `"‹purged›"`). This is the one write the engine makes to a
  past log entry, and it is the reason the purge is law and not a
  handler.
- **The consequence sentence is derived**: *Ana Ruiz's name, birth date,
  phone and 3 documents are erased for good; the record that she was a
  patient stays.* The door is `confirm`, and an agent's purge is held
  for a person's tap, as a transcript's is today. The retention sweep
  is the one actor that purges without a tap, because the law it
  follows was itself declared by a person.
- **The state is terminal.** No door leaves `purged`.

### Option kept: delete the row

A hard delete (`store/delete-rows!`) is what a transcript's lines get.
It is kept for record kinds whose existence is not itself a fact
anyone must prove. A patient's, an order's and a note's is: the tax
authority asks that the order existed, not what the customer's phone
number was.

---

## 6 · Frozen states — `:frozen-in`

### Decision

```clojure
;; on :note
:frozen-in [:signed]
:owns [{:kind :note_addendum :via :note_id}]
```

- **In a frozen state, no door changes the row's data.** The check
  refuses a declaration with an `:edit` door whose `:from` includes a
  frozen state, and an action whose handler writes data from one. At
  runtime the invoke refuses any data change on a frozen row, so a
  handler cannot slip one through: *A signed note is not changed. Add
  an addendum instead.*
- **Transitions still run.** A signed note may be archived or purged;
  only its data stands still. A purge (§5) is the one exception, since
  erasure overrides the freeze.
- **Corrections are children.** The remedy of the refusal is the
  `create` door of a kind the frozen kind `:owns`, with the ref filled.
  The envelope lists the addenda beside the note, so a reader sees the
  correction without the original changing.
- **A frozen row's documents are frozen too**: an attachment it
  references cannot be deleted while the row is in a frozen state
  (except by the row's own purge).

---

## 7 · Honest on the probe, in rehearsal, in scenarios

Concept 1's rule holds: **unknown is pending, never refused.**

| Where | `<field>-is-filed` | `retention-has-passed` | frozen check |
|---|---|---|---|
| Render probe | judged (attachment state is a row read) | judged with the `:calendar` hook; else pending | judged |
| `dry_run`, invoke | judged in full | judged in full | judged |
| law-sweep | judged | judged | judged |
| scenarios, check tier | `:given` attachments with a state | `:at` and `:calendar` (concept 2) | judged |

A scenario gives attachments as rows, with no bytes:

```clojure
{:kind :note :attempt :sign
 :given [{:kind :attachment :id "f1" :state "pending" :data {:name "consent.pdf" :media_type "application/pdf"}}
         {:kind :patient :id "p1" :data {:consent_form "f1"}}]
 :row {:state "draft" :data {:patient_id "p1"}}
 :expect {:refused :consent_form-is-filed :because "still uploading"}}
```

A purge scenario expects the skeleton: `:expect {:state "purged"
:cleared [:name :born_on :phone] :deleted-attachments 3}`.

---

## 8 · Fingerprint and audit

**Fingerprint.** `:evidence`, `:retain` and `:frozen-in` are law
facets, since each refuses writes or moves rows. Their derived guards
hash as their trees. `:personal` on a field and `:purge true` on a
door are law (they decide what is erased). `:accept` is law (it refuses
a write); `:min` is law through `:evidence`.

**Audit findings,** in the declaration battery (`checks.clj`, `make
check-queue`):

| Finding | Level | Says | Remedy | Question |
|---|---|---|---|---|
| `[evidence-in-prose]` | warning | A door's `:explain`, description or a guard's sentence names a document (photo, form, consent, signature, receipt…) and the door has no `:evidence`. Only a kind that declares a `:waymark/document` field is judged: a kind with none has nowhere to file a paper, and its receipts and forms are the engine's own. | Declare the document field and name it in `:evidence`. | "Must {door} wait for the {document}?" |
| `[retention-undeclared]` | info | A kind holds `:personal` fields or document fields, or is named in a rehearsal's retention note, and has no `:retain`. | Declare `:retain`, or `:retain :forever` to say it is kept on purpose. | "How long must a {kind} be kept, and from when?" |
| `[purge-unheld]` | error | A `:purge true` door that an agent's grant can run without a person's tap, or a `:retain :then` that is not a purge door. | Hold the door for a person's tap (the sweep is exempt), or point `:then` at the purge. | — |
| `[purge-summary]` | error | A kind with a `:purge true` door whose `:summary` reads a field the purge clears, and no `:retain {:data true}` — its old log summaries could not be redacted. | Declare `:retain {:data true}`. | — |
| `[frozen-edited]` | error | An `:edit` door's `:from` includes a `:frozen-in` state. | Take the state out, or add a child kind for corrections. | — |
| `[retain-unfiltered]` | error | `:retain :from` names a field that is not `:filter`. | Declare it filterable, as the sweep pages by it. | — |

---

## 9 · Migration — the 11 workarounds

The ticket names the shapes; it does not give the count per shape, and
this spec does not guess one. Step 9 counts them against the logs.

| Shape | Replaced by |
|---|---|
| Landscape `job.photos` as free text, checked with `present?` | §2 `:waymark/document` with `:accept ["image/*"]`, and §3 `:evidence` on `complete` |
| Clinic consent and privacy-notice dates as text | §2 document fields for the forms (the date is the attachment's), concept 2 dates where a date is the fact |
| "Keep order and tax records seven years", undeclared | §4 `:retain {:for (years 7) …}` on the shop's order |
| The clinic purge that erases nothing | §5 `:purge true` with `:personal` fields |
| Signed notes not frozen | §6 `:frozen-in [:signed]` and a `note_addendum` kind |

---

## 10 · The film

1. The clinician signs a treatment note for Ana Ruiz before her consent
   form is uploaded. The refusal: *The note cannot be signed: Ana
   Ruiz's consent form is not on file.* (`consent_form-is-filed`). The
   form is uploaded; the note signs.
2. The clinician tries to fix a typo in the signed note. The refusal:
   *A signed note is not changed. Add an addendum instead*, with the
   addendum's create door as the remedy. The addendum appears under
   the note.
3. The landscaper marks the Hendersons' patio complete with one photo.
   The refusal: *This job needs 2 photos to be completed; it has 1.*
4. On a demo clone, the scenario clock moves seven years past Ana's
   last visit. The sweep purges her. Her envelope shows `purged`, no
   name, no birth date, no forms, and the history line: *purged by the
   7-year retention of patient, due 14 March 2031.* Her signed notes
   remain as skeletons, since their own `:retain` follows hers.

---

## 11 · Not in this spec

- Who may read a patient's documents at all: row-level read rules are
  concept 5 (teams).
- Production byte stores (S3, presigned URLs): attachments keep their
  local directory, a named punt of `attachments.clj`.
- Document expiry ("the insurance card expires"): a concept 2 date
  field and guard on the owning row.
- Electronic signature as cryptography. "Signed" is a state and a log
  line here, and `:frozen-in` is what makes it hold.
- A cascade from one kind's `:retain` to another's: each kind declares
  its own, reading the parent's date through a field.

---

## 12 · Build plan

Each step is one ticket and one pull request, in order, each under 800
changed lines. Steps 1 to 5 add the language; nothing in an app moves
until step 7.

1. **`:waymark/document`.** The field word, the ref to `attachment`,
   `:accept` in the schema check with its refusal, `:min`, `added_by`
   and `added_at` read from the attachment log at render. Test: an
   unaccepted type refuses; the envelope shows who added a file.
2. **`:evidence`.** The derived `<field>-is-filed` trees, `:via`, the
   refusal sentences (missing, too few, still uploading), the edit-door
   remedy, `[evidence-in-prose]`. Test: a hand-written twin has the
   same fingerprint.
3. **`:frozen-in`.** The declaration check (`[frozen-edited]`), the
   invoke-time refusal of a data change, the `:owns` remedy, the frozen
   attachment rule.
4. **`:purge` and `:personal`.** Clearing fields, deleting and
   unlinking attachments, the log redaction, the derived consequence
   sentence, the hold for an agent, `[purge-unheld]`. Test: after a
   purge no personal value remains in the row or its log.
5. **`:retain` and the sweep.** The derived `retention-has-passed`
   guard, the elected sweep beside `attachments-purge`,
   `meta.retained_until`, `[retention-undeclared]`,
   `[retain-unfiltered]`. Depends on concept 2 steps 1 and 3.
6. **Scenarios.** `:given` attachments, purge expectations. Test: the
   §7 example passes at the check tier.
7. **Clinic.** Patient, note and addendum take `:personal`,
   `:retain`, `:purge`, `:frozen-in` and `:evidence`; film steps 1, 2
   and 4 are its acceptance walk.
8. **Landscape and shop.** `job.photos` as a document with evidence on
   `complete` (film step 3); the shop's order takes `:retain`.
9. **Survey.** Re-read the three rehearsal logs, check §9's count of
   11, and file one ticket for each workaround these steps did not
   retire.
