# Spec — thread: the household's conversations, as addresses

**Thesis.** The gap is not messages, it is ADDRESSES. A fact found in a chat
has nowhere to point: `cites-what-it-claims` means an insight cannot carry
it, so the commitments probe anchors on a person, the composer names the
source in prose, and the driver *guesses* which thread to read — it picked
one 1:1 conversation out of ten, and the Utah Kopsas group carried a
birthday invitation nobody had answered. The flickr move, made again:
**mirror the thread, never the messages.** Works, not files. A row per
conversation carrying timestamps, counts and names — and no word anybody
said.

## Epistemic status

Verified against the live Gate on 2026-08-28 (read-only, under the sitting
grant), which is the difference between this spec and spec-media.md's first
draft: every field below was read off a real answer, and the two honest
gaps were found rather than imagined.

1. **`messa` spoke no time at all — fixed at the rig, 2026-08-28.**
   `messa__threads` answered `"time": ""` for every thread, so the Google
   Messages half of this mirror carried a `last_message_at` of nil, could
   not rank by recency, and could never produce an arrival. The cause was
   a selector in the rig that matched nothing; messa now reads Google's
   own `mws-relative-timestamp` and publishes `last_message_at` as an
   absolute instant. What remains true: `messa__read_messages` still
   answers `"time": null` and `"age": null` for every message, and a
   label the rig cannot read is still nil rather than a guess. See "The
   messa gap".
2. **Neither rig takes a `since=`.** `tgram__list_chats` and
   `messa__threads` take exactly `{limit, why}`. There is no cursor to
   bear, and this spec does not invent one — see fork (e).

What is NOT verified: nothing here has run against a database. The schema
plan, the conformance obligations and the driver's degradation path are
declared and reviewed, and CI is the gate.

## What exists

- `workqueue10/confluence.clj` — `TaskSource`, `TaskListSource`, the fan
  helpers, the partial-tolerance posture, the breaker-panel reporting.
  Nothing in the fan is task-specific. A third protocol over the same
  helpers is instantiation, not invention — and `TaskListSource`'s own
  docstring already argues for the split: *a source should not have to
  answer a question its authority never asks*.
- `sources/flickr.clj` — the precedent this spec copies almost line for
  line: mirror works and not files, a translation that happens at the
  source boundary, an etag that is a content hash with a translation
  revision composed on, and a `pull-many` that IS the full listing because
  the authority has no per-item route, so absence is an observation.
- `resources/media.clj` / `resources/task_list.clj` — the mirrored kind's
  declaration, and specifically `task_list`'s **pull-only** posture: no
  local writes, no `:push-on-write`, no birth door.
- `resources/person.clj` — the roster, born `observed`, and the reason it
  exists: a composer read correct rows and invented the relationship
  between them.
- `waymark10/server/gate_proxy.clj` — the engine's Gate client. `rpc-of`
  is the seam; `tool-capability` already binds `tgram__list_chats` to
  `telegram.read` and `messa__threads` to `messages.read`.

## The canonical doc

One kind, two rigs, and email folders later on the same protocol.

```clojure
{:kind :thread :plural "threads" :nav :secondary
 [:title                 string]      ; the household's own word for the chat
 [:source                enum "tgram" "messa"]
 [:chat_kind             enum "direct" "group", maybe nil]
 [:status                enum "live" "dropped"]
 [:last_message_at       instant, maybe nil]   ; the cursor the DRIVER windows on
 [:message_count_window  int, maybe nil]       ; nil at both wired rigs — see below
 [:participant_names     vector of string]     ; the rig's own words
 [:participants          vector of ref → :person, :match :name]}
```

`external_id`, `external_etag`, `synced_at` and `conflict_reason` are NOT
declared: `mirror/declaration` weaves all four onto every mirrored kind, and
declaring them a second time would be two fields with one meaning. The
brief named them as fields; they arrive for free, which is the better
answer.

`:chat_kind` and not `:kind` — `kind` is the row envelope's own word, and a
data field wearing it would read as the row's type everywhere a card is
rendered.

### What a thread row NEVER carries

- **Bodies.** Obviously, and the whole point.
- **Previews and snippets.** `tgram__list_chats` answers
  `last_message_preview` (100 characters of the last message) and
  `messa__threads` answers `snippet`. Both are bodies wearing a shorter
  coat. They are dropped at the source boundary — the translation never
  puts them in a map that could be stored.
- **Unread counts.** DECIDED, and the closest call here.
  `tgram__list_chats` answers `unread_count`, and it is tempting: it looks
  like a fact about the conversation. It is not. It is a fact about the
  owner's *phone* — it moves when he opens the app and nothing was said —
  so mirroring it would churn every row on every pass, make "this thread
  moved" ambiguous (did somebody speak, or did somebody read?), and publish
  to every grant-holder how much the household has left unanswered. Three
  reasons, one verdict: not stored.
- **Sender ids.** Not needed; see "The row set is the sender directory".

### `message_count_window`: declared, and nil at both rigs

Neither listing answers a message count, and the only way to compute one is
to *read the messages* — which is exactly the thing this kind exists not to
do. So the field is declared and left nil, and the gap renders. It is
declared rather than punted because an email-folder source (the next one)
answers a count for free, and a field added later is a migration where a
field left nil is a sentence.

### `:status`, and what an ending means here

`live` and `dropped`, and **a thread is never done**. There is no
accomplishment in a conversation — no state a chat reaches where the
household has finished it — so the kind declares:

```clojure
:over {:field :status :accomplished #{} :let-go #{"dropped"}}
```

An empty `:accomplished` is the honest half of `:over`: this kind has
endings, and none of them is a deed. `:on-gone {:set {:status "dropped"}}`
— the listing ANSWERED and the thread was absent from it, which is a
household that stopped talking somewhere (or a rig whose window rolled
past). The row keeps serving; nothing is deleted; the address an old
insight cites stays an address.

### No push, structurally

`ThreadSource` **has no push method**. Not a method that throws — no method.
The queue mirrors the house's conversations; it does not write them, and
Gate's `telegram.send` is a leashed capability a *person* approves, never a
sync pass's business. `:push-on-write` is unsaid, so the framework never
tries, and the kind declares no actions at all. This is `task_list`'s
posture with the reason restated: a list is a shape its authority owns, and
so is a conversation.

### Participants → persons

`participant_names` is the raw truth the rig spoke; `participants` is its
resolvable projection — `[:vector :waymark/ref]` with
`{:kind :person :external-key :participant_names :match :name}`, the
framework's `:many` external-keyed ref (`mirror/resolve-external-refs`).
The vector of names is always whole; the vector of refs is the subset the
roster can name. The gap renders, exactly as `:assignee` earned.

`:match :name` requires `person`'s `:name` to be `:eq`-filterable, which it
was not; this spec's diff adds `:filter #{:eq}` to it. Person's `relation`
deliberately carries no filter (exact-match on free prose is a trap) and
`name` is the opposite case: a name is precisely what an external system
hands you to match on.

Where the names come from:

| rig | direct | group |
|---|---|---|
| `tgram` | `type: "user"` → one participant, the chat `title` (which IS the person's name — verified: "Wellesley Kopsa", "Carson Kopsa") | `type: "group"` → **no participants**; the listing exposes none, and this spec does not read messages to find them |
| `messa` | a `name` with no comma → one participant, the name | a `name` that is a comma-joined roster ("Amy Shumway, Calista Shumway, …, Wellesley Kopsa, (304) 482-6884") → each part a participant |

Group participants only when the rig exposes them, and messa is the one
that does — as a side effect of naming its group threads after everybody in
them.

### The observed birth

A participant name the roster does not hold is **born `observed`**, with the
name as its `:name` and `"somebody this house exchanges messages with"` as
its `:relation`. That is `person`'s own law: an agent may write down
somebody it found in the record, the row says `observed` wherever it is
cited, and only a person's tap makes it the house's. The roster grows on
its own and nobody is told who their people are.

The birth is narrow on purpose — a name is a birth candidate only when it
**looks like a person's name**: it must be letters, marks, spaces and the
punctuation a name actually carries, at least two characters and at most
eighty. No digits, and no symbols. That rules out `41646`,
`(743) 222-5699`, `(304) 482-6884` and `Bros. 🧠` — the shortcodes, the
payroll robots and the group titles (an emoji is a symbol, not a letter).
The bot rule is separate and lives one level up, at the chat filter, where
the rig's own `username` says so. A name that fails either test still lands
in `participant_names` whole; it simply mints nobody.

**No handle field on `person`.** The brief said "the sender id as handle",
and this spec declines: `person` has no `handle`, adding one would be a
second identity system only one source could ever fill, and it is not
needed — see below. If name-matching proves too loose in the field, the fix
is one keyword: add `person.handle` and change `:match :name` to
`:match :handle`, which is exactly the shape `media.audience` already uses
against `member`.

### The row set is the sender directory

`tgram__get_messages` answers `sender_id` and `sender_name: null` — ids
only, no names, which is the wall the driver kept hitting. But a DIRECT
chat's `external_id` **is** the peer's telegram id (`5061625694` is the
Wellesley chat and the Wellesley sender both). So the mirrored row set
answers the question the rig will not: a `sender_id` seen in a group is
looked up against the direct threads, and the title found there is the
name. No sender id is stored, and no name is learned that the rig did not
already hand us as a chat title.

### Bots do not mirror

A tgram chat whose `username` ends in `bot` is a notification channel, not a
household conversation. Filtered at the source, flickr's `mirrored-kinds`
rule with a different noun: intent, not inventory. The driver already
excluded them by hand; the rule belongs where the row is minted.

## How the sources reach Gate

Through `gate-proxy/rpc-of` — the engine's own Gate client and its seam —
called ONCE at wiring time in `main`, with the resulting rpc handed to both
sources. One MCP session for the thread confluence, reused across passes,
re-initialized on expiry by the client itself.

**Past `invoke-for`, deliberately, and this is the decision to record.**
`invoke-for` judges a *caller's* grant. A sync pass has no caller: there is
no principal, no request, no `X-Waymark-Grant` — the mirror runs on the
engine's own clock, exactly as gtasks runs on a refresh token and flickr on
LAN reach. Handing the pass a synthetic grant would be inventing a
principal so a check could pass, which is honouring nothing (the same
sentence `gate_proxy` uses about refusing a filtered grant). So the sources
call `rpc "tools/call"` directly, and the leash lands where it belongs:

- The engine holds Gate's reach — one host, one LAN, the network backstop
  unchanged.
- The **rows** are grant-projected like any kind, so what an agent may see
  of the household's conversations is judged at the door it reads them
  from.
- And the mirror can never widen anyone's sight past what a thread row
  shows, because the translation drops the body before the document exists.

## The wire

| | tgram | messa |
|---|---|---|
| listing | `tgram__list_chats {limit}` | `messa__threads {limit}` |
| identity | `id` (numeric, stable across renames) | `hash` ("d0d1123a") |
| title | `title` | `name` |
| kind | `type`: user \| group | commas in `name` |
| time | `last_message_date` "2026-08-26 17:03:21+00:00" | `time` — **always `""`** |
| dropped | `last_message_preview`, `unread_count`, `username` | `snippet` |

Both answer one JSON object per row as separate MCP content parts AND as
`structuredContent.result`, an array. The sources read
`structuredContent.result` and fall back to parsing the content parts —
the array is the rig's own structure and the parts are its rendering.

There is no per-thread route on either rig, so — flickr's shape exactly —
**`pull-many` IS the full listing**: one call, absence answered `:gone`.
`:resync-every` rides that batch.

### The messa gap

Recorded loudly because it changed what the driver could do: messa threads
carried `last_message_at` nil, so they never ranked by recency and never
became arrivals. They were still worth mirroring — they are ADDRESSES,
which is the thesis, and a commitment found in the Kathy Peppas thread has
somewhere to point.

**Closed 2026-08-28, and it did fill with no change here** — the sentence
above was a prediction and it held. The rig was scraping `.timestamp,
.date`, which matches nothing in Google Messages' DOM; it now reads the
`mws-relative-timestamp` element and resolves the relative label ("9:05
AM", "Yesterday", "Wed", "Aug 12") to an instant in the phone's zone.
`sources/messa.clj` reads that field and canonicalizes it; `time` stays
the human label and stays forbidden from the document.

Two residues, because the gap narrowed rather than vanished. A date-only
label resolves to **midday**, so a messa row older than today is accurate
to the day and no finer — good enough for a seven-day window, not for
ordering two threads on the same old day. And a label the rig cannot read
answers **null, never a guess**: such a thread still carries nil, still
never ranks, and still never arrives.

## The forks, decided

| fork | decision | one-line reason |
|---|---|---|
| (a) kind name | `thread` | the household's word; `chat` names the rig, `conversation` names the messages |
| (b) protocol | a new `ThreadSource` (discover/pull/pull-many) beside `TaskSource` | `TaskListSource`'s own argument: a source with no push and no birth should not implement two throws, and a confluence that declares no `MirrorCreateAdapter` refuses births *structurally* |
| (c) two near-identical confluence records | keep both | nine lines each, and each names its own kind in the sentence a person reads when a write reaches it; a parameterized record would hide which kind refused what |
| (d) push | none, no method at all | the queue does not write the household's conversations; `telegram.send` is a person's leashed capability, not a pass's |
| (e) cursor | **none** — the listing is the window | neither rig takes a `since=`; what flickr's cursor buys (a small delta) the listing gives for free at ≤40 rows, and `last_message_at` on the ROW is the cursor that actually matters — it is what the driver windows on |
| (f) `unread_count` | not stored | a fact about the phone, not the conversation: it churns on reading, it makes "moved" ambiguous, and it publishes what the house has not answered |
| (g) previews / snippets | not stored | bodies in a shorter coat |
| (h) `message_count_window` | declared, nil at both rigs | filling it means reading the messages, which is the thing this kind exists not to do |
| (i) participant linking | `:many` external-keyed ref, `:match :name` | the framework already has the shape; the raw names stay whole beside the resolvable projection |
| (j) `person.handle` | not added | a second identity system one source could fill; the direct chat's own `external_id` already IS the handle, and the heal is one keyword |
| (k) observed births | yes, from the source's translation, best-effort | the bead's "the roster grows on its own"; the alternative is a second pass with a second cadence for one write |
| (l) births are narrow | letters only — no digits, no symbols | otherwise the roster fills with shortcodes and payroll robots |
| (m) bot chats | not mirrored | intent, not inventory — flickr's line |
| (n) grant | the sources call `rpc` past `invoke-for` | a sync pass has no caller; the leash lands on the rows and on the engine's LAN reach |
| (o) `:nav` | `:secondary`, no population | a conversation is not a thing to do; a card for it would be the feed manufacturing work |
| (p) `synced_at` / `external_etag` | not declared | `mirror/declaration` weaves them; two fields with one meaning is worse than none |
| (q) domain | domainless, beside `person` | who the house talks to is not a domain of logistics beside queue/chores/meals |

## What the driver does with it (waymark-36s, deliverable 3)

`scripts/sitting-run.sh` reads `/api/threads` into the snapshot as
`chat_threads` (the derived remark-thread list already owns the name
`threads`), and then:

1. **Thread selection is mechanical.** `gate_chat_history` picks from ROWS,
   not from a heuristic: every thread whose `last_message_at` falls inside
   the seven-day window, newest first, capped at `WAYMARK_THREADS` (4),
   **groups included**. That is the whole bug fixed — the old code took the
   single most-recently-active 1:1 chat that matched a roster companion's
   name, which is how a group carrying an unanswered invitation was never
   read.
2. **The commitments probe anchors on the thread row.** `subject` becomes
   `/api/threads/<id>`; the evidence is the thread plus the person rows it
   names. A commitment said in a chat finally cites the chat.
3. **A thread that moved is an arrival.** `last_message_at` later than the
   run's watermark → "something was said in <title>", with a read work
   order. Arrivals are otherwise creations (waymark-dgh); this is the second
   thing a clock can see, and it is declared beside the unanswered-turn arm
   for the same reason: it is still owed an answer.
4. **Media-only messages render `[picture]`** with date and sender, instead
   of vanishing as an empty line. Verified: a picture is `text: ""` at both
   rigs.
5. **It degrades honestly.** Until the kind is deployed, `/api/threads`
   404s, the snapshot holds `[]`, and the driver falls back to the old
   companion heuristic with a manifest line saying so — `thread_selection`
   reads `"rows"` or `"heuristic (the thread kind is not served yet)"`.
   "We could not tell" and "there was nothing" stay different sentences.

## Recorded punts

- **Email folders as threads.** The obvious third source (`emila__folders`),
  and the one that would fill `message_count_window`. Left out because two
  rigs prove the protocol and a third is a day's work with no new thought.
- **A thread's messages, ever.** Not a punt so much as the thesis. Bodies
  stay behind Gate under a grant a person approved.
- **Group participants at tgram.** The rig exposes none; reading messages
  to infer them would cross the line this kind draws.
- **The messa clock.** Above. Healable at the rig with no change here.
- **Name collisions.** Two people named "Kevin Kopsa" resolve to whichever
  row the indexed read answers first. Visible, not resolved — spec-media's
  merge-law posture: write the law against observed collisions, not
  imagined ones.
- **A thread's own reply door.** "Answer this in the chat" is a
  `telegram.send` capability and a human's approval; it does not become an
  action on this kind just because the row is here.

## Effort

**Small-to-medium.** The kind is `task_list` with different fields; the
sources are flickr with a simpler wire and no cursor; the confluence is
twenty lines over helpers that already exist. The genuinely new thought is
three sentences long — the unread-count verdict, the row-set-as-directory
trick, and reaching Gate past `invoke-for` because a sync pass has no
caller. The driver change is the larger half, and it deletes more heuristic
than it adds law.
