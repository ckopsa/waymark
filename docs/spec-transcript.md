# Spec — the transcript: what a sitting said

**Purpose.** This document gives the requirements for the transcript
of a sitting: the record of what the model read, said, and called
while it sat. The sitting (spec-seat.md section 10) records what a
wake cost. It does not record what the wake did. A seat that audits
other seats, the mayor first, cannot read a run today. The owner's
ruling, 2026-09-27: "I need you to be able to audit the sessions, so
we need a tool for you, not me."

This document is written in ASD-STE100 Simplified Technical English.
Technical names from the codebase keep their spelling: seat, sitter,
sitting, door, kind, grant, scope, hook, sweep. "Must" gives a
requirement. "Can" gives a permission. "Is" gives a fact.

## 1. The problem on record

On 2026-09-27 the mayor was asked to audit the last runs of two
Routines, `code-seat` and `opus-5`. The mayor found the runs. It
could read their metadata: the model, the token counts, the cost, and
the status. It could not read one word of what the runs said. Four
facts, all recorded:

*The provider does not give the transcript to a session.* The
Routines surface lists runs and reads their metadata. The tool that
reads a run's events is not offered to a cloud session, and no
setting turns it on.

*The record we have is too small for an audit.* The `opus-5` run
ended 35 seconds after it started, with 1,768 output tokens. The
sitting says what that cost. It does not say why the run stopped.
The journal says what the sitter chose to write. An audit needs what
the sitter did, and the sitter is the party being audited.

*The seat's place is public.* The first design pushed each
transcript to a branch of the repository the Routine clones. That
repository is `ckopsa/waymark-seat`, and it is public. A transcript
holds the charter, every tool answer, and every row the sitter read.
It must not be public.

*The run's shell cannot reach the engine.* The cloud environment's
network policy refused `work.kopsa.info` on 2026-09-27. This is why
the hook's first path (R-12.17) is not used in the cloud, and why the
second path hands the counts to the model.

## 2. Decisions

**D-1. The engine holds the transcript, and not a repository.** The
engine already holds the sitting, the grant, and the scope. A
transcript that is a row is private by grant, is found from its
sitting in one read, and is read through the connector the auditor
already holds. A repository needs a second repository on every
Routine, and it lets any run that can push rewrite any other run's
file.

**D-2. The hook sends the transcript. The model never relays it.** A
transcript is megabytes. A record that goes through the model that is
audited is not a record. The model cannot carry it, and it must not.

**D-3. The sit answers a key for this one sitting.** The hook has no
credential in the cloud, and the environment must hold none
(CLAUDE.md of the seat's place). The hook already reads the sitting's
id from the sit's answer in the transcript (R-12.17). It reads the
transcript key from the same answer. The key can do one thing: add
lines to this sitting's transcript, for a short time.

**D-4. The transcript is append-only, and each line is chained.** The
key is in the model's context, because the sit's answer is. A model
can therefore post lines of its own. It cannot remove or change a
line that the engine holds, and a line that it adds before the hook
breaks the chain that the hook sends next. The design does not stop a
forgery. It makes a forgery visible. Section 10 records the stronger
option and why it waits.

**D-5. Each line is a row.** An audit asks questions of a transcript:
which calls failed, which tool the run called most, what it read
before it stopped. A row per line makes each question one
`waymark_query`. A blob makes each question a download and a parse
that the connector cannot do.

**D-6. The reader is a grant.** A sitter does not see a transcript
by default. A grant whose scope names the kind reads it, and that
grant can be a seat's own. The owner's ruling, 2026-09-27: a guard
that keeps a seat from its own transcripts is not necessary now
(section 13).

**D-7. The seat's place keeps three files.** The upload is a new
step in `sitting-close.sh`. No file is added, so the sync workflow's
count and the note in the seat's `CLAUDE.md` do not change.

## 3. Requirements: the kinds

**R-3.1** The engine must serve a framework kind `transcript` in
`seats.clj`, with states `open`, `sealed`, and `purged`. `sealed` and
`purged` are terminal for the lines. The row stays after a purge.

**R-3.2** A transcript must have these fields. The engine writes all
of them. No door takes them as input.

| field | type | meaning |
|---|---|---|
| `sitting` | sitting ref, unique | the sitting this transcript records |
| `seat` | seat ref | copied from the sitting at birth |
| `model` | model ref | copied from the sitting at birth |
| `mode` | enum `fired`, `interactive` | copied from the sitting at birth |
| `harness_session` | string | the harness's own id, from the first upload |
| `run_session` | string, optional | the provider's id for the run, from the first upload (R-6.4) |
| `files` | list | one entry for each file: `name`, `lines`, `bytes`, `chain` |
| `lines` | int | the lines held, over all files |
| `bytes` | int | the bytes held, over all files |
| `uploads` | int | the accepted posts |
| `redactions` | map | the count of each redaction class, from the hook (R-7.3) |
| `engine_redactions` | int | the redactions the engine made that the hook did not (R-7.4) |
| `first_at`, `last_at` | instant | the first and the last accepted post |
| `sealed_at` | instant, optional | when the sweep sealed the row |
| `gap` | string, optional, up to 240 | one sentence when the record is not whole (R-9.3) |
| `key_hash` | string, secret | the SHA-256 of the live key (R-4.1) |

**R-3.3** The engine must serve a framework kind `transcript_entry`.
One entry is one line of one file of the harness's transcript. It
must have these fields.

| field | type | meaning |
|---|---|---|
| `transcript` | transcript ref | the transcript |
| `sitting`, `seat` | refs | copied, so each is a filter |
| `file` | string | `main`, or `agent-{id}` for a subagent |
| `seq` | int | the line's place in its file, from 0 |
| `at` | instant, optional | the line's own timestamp |
| `type` | string | the line's `type`: `user`, `assistant`, `system`, and others |
| `tool` | string, optional | the tool name of a `tool_use`, and of the `tool_use` that a `tool_result` answers |
| `tool_use_id` | string, optional | the pairing id |
| `is_error` | bool | true for a `tool_result` that the harness marked as an error |
| `usage` | map, optional | the four token counts of an assistant line |
| `text` | string, up to 4,000 | the line's readable text: the message, the tool input, or the tool answer |
| `raw` | string | the whole line after redaction, up to `transcript_line_max_bytes` |
| `raw_truncated` | int, optional | the bytes cut from `raw`, when it was cut |
| `chain` | string | the chain hash to this line (R-5.3) |

The 4,000-character cap on `text` is the cap spec-seat.md holds for a
message. `transcript_line_max_bytes` is an engine option, and its
default is 65,536.

**R-3.4** The engine must derive `tool`, `tool_use_id`, `is_error`,
`usage`, and `text` from `raw` when it writes the entry. The hook
sends lines only. The engine does not trust a field that the hook
could have written.

**R-3.5** `transcript_entry` must be filterable by `sitting`, `seat`,
`file`, `type`, `tool`, `is_error`, and `at` after. Its default sort
is `file`, then `seq`. Its summary projection is `file`, `seq`, `at`,
`type`, `tool`, `is_error`, and the first 300 characters of `text`.
`raw` rides in `waymark_get` only.

**R-3.6** The engine must give birth to the transcript when it gives
birth to the sitting, in the same commit. A sitting that sat and sent
nothing then has a transcript with no lines, and the seal writes the
gap (R-9.3). An absent transcript and a lost transcript read
differently.

**R-3.7** The seat must have a field `keep_transcripts`, an enum
`none`, `fired`, `all`. The default is `fired`. At `none`, the sit
gives birth to no transcript and answers no key. At `fired`, only a
fired sitting keeps one. At `all`, an interactive sitting keeps one
too. An interactive sitting holds a person's own words, so it is kept
only when the person says so on the seat.

## 4. Requirements: the key

**R-4.1** Each `waymark_sit` that opens or reuses a sitting must mint
a transcript key. The key is 128 bits of machine randomness,
base64url, as the key of a firing is (R-12.37). The engine writes the
SHA-256 of the key on the transcript as `key_hash`, and it replaces
the hash that was there. The key of the last sit is the only key that
answers. The hook reads the last sit's answer (R-12.17), so the two
agree.

**R-4.2** The sit's answer must carry `transcript`, a map with two
keys. `url` is the absolute address of the door (R-5.1), made from
the engine's public origin. `key` is the key. The hook needs the
address because the cloud environment holds no `WAYMARK_SEAT_URL`.

**R-4.3** The engine must never render the key again. It must not
write the key in a transition's recorded inputs, in a log line, or in
any answer but the sit's. R-12.11 gives the posture for a credential
the engine holds.

**R-4.4** The key stops answering at the seal (R-9.1). The seal
drops `key_hash`.

**R-4.5** The key also closes its sitting. The close door
(spec-seat.md R-12.17) takes it in the header
`Waymark-Transcript-Key` when no seat key is presented: the key names
the transcript, the transcript names its sitting, and the door closes
that sitting. A fired run's hook holds no other credential, so this
is how it closes without a model turn. A key that answers no open
transcript gets the close's uniform 404.

## 5. Requirements: the door

**R-5.1** The engine must serve `POST /api/-/sittings/transcript`,
beside the close and the tally, in `routes/seats.clj`. It is
anonymous, as the close is. The credential is the header
`Waymark-Transcript-Key`, and not a bearer, for the reason R-12.17
gives.

**R-5.2** The body is JSON, and it can be sent with
`Content-Encoding: gzip`.

| key | meaning |
|---|---|
| `harness_session` | the harness's id for the session |
| `run_session` | the provider's id for the run, or absent |
| `file` | `main`, or `agent-{id}` |
| `from` | the `seq` of the first line in this post |
| `prior` | the chain hash of the line before `from`, or 64 zeros when `from` is 0 |
| `lines` | the lines, in order, each one a string, after redaction |
| `redactions` | the count of each redaction class in these lines |

One post must carry not more than 4 MiB of lines before compression.
The hook sends a long file in more than one post.

**R-5.3** The chain is SHA-256 over text. The chain before the first
line is 64 zeros. The chain to line n is the SHA-256 of the chain to
line n−1, one newline, and line n, as UTF-8, in lower-case hex. The
hook and the engine compute it the same way. The file's `chain` on
the transcript is the chain to its last held line.

**R-5.4** The door must accept a post only when `from` is the number
of lines held for that file and `prior` is that file's `chain`. A
post that starts at a line the engine already holds, with lines that
match, is a replay: the engine skips the lines it holds and appends
the rest. The hook therefore resends a whole file with no harm.

**R-5.5** The door gives these answers, in this order of cost.

- 404 with `No transcript answers this key.` when the key is wrong,
  absent, or dropped. The sentence is uniform, as R-12.14 makes it.
- 422 when the body is malformed.
- 409 with ``The transcript of sitting `{id}` is sealed.`` after the
  seal.
- 409 with `held` and `chain` for the file when `from` is past the
  held count. The hook sends again from `held`.
- 409 when a line does not match a held line, or `prior` does not
  match. The engine keeps the lines it holds. It writes the `gap`:
  ``File `{file}` diverged at line {n}.`` It accepts no more lines for
  that file. This is how a forgery shows (D-4).
- 413 when the post is over 4 MiB, or when the transcript is over
  `transcript_max_bytes` (R-5.6).
- 200 with `held` and `chain` for the file, and `lines` and `bytes`
  for the transcript.

**R-5.6** `transcript_max_bytes` is an engine option, and its default
is 32 MiB. Past it, the engine still writes each entry, with its
derived fields and its `text`, and with no `raw`. It writes the gap:
``Raw lines were not kept past {n} MiB.`` The shape of the run is
still whole.

**R-5.7** Each accepted post is one maintenance write on the
transcript and one insert for each new entry, in one commit. No
transition is recorded for each post. A post adds nothing to the
sitting's `transitions` or `refusals` (spec-seat.md R-10.6), because it is not the
sitter's work.

## 6. Requirements: the hook

**R-6.1** `sitting-close.sh` must upload on every Stop event and on
the `SessionEnd` event, before it does anything else. The upload runs
when `stop_hook_active` is set too. In a fired run the second Stop
event comes after the model's close, so that upload carries the last
lines.

**R-6.2** The hook finds the last `waymark_sit` answer in the main
transcript that carries `transcript`, and it reads `url` and `key`
from it. When no answer carries it, the hook uploads nothing and
says nothing. The hook rides in every session of the seat's place,
and a session that did not sit has nothing to send.

**R-6.3** The hook sends the main transcript and each subagent
transcript beside it, as R-12.17 finds them. It keeps the held count
of each file in a state file under `$TMPDIR`, keyed by the sitting,
and it sends from there. A 409 with `held` corrects the count. A lost
state file costs one resend, and R-5.4 makes it harmless.

**R-6.4** The hook sends `run_session` from the environment variable
`CLAUDE_CODE_REMOTE_SESSION_ID` when the harness sets it. That is the
provider's id for the run, and it is the id that the Routines surface
lists for each firing.

**R-6.5** The upload must not fail the session and must not delay it
much. All the posts of one event share a limit of 20 seconds. Each
refusal and each network failure writes one line on the error stream
and ends the upload. The close and the tally then run as they do
today.

**R-6.6** The hook must redact each line before it computes the
chain and before it sends the line (section 7).

## 7. Requirements: redaction

**R-7.1** The hook must replace each secret with `[redacted:{class}]`.
The classes are these.

| class | what |
|---|---|
| `seat-key` | the `key` input of each `waymark_sit` call |
| `fire-key` | the value of the `Key:` line of a fire text (R-12.37) |
| `transcript-key` | the `key` inside the sit's `transcript` answer |
| `env` | the value of each environment variable whose name ends in `KEY`, `TOKEN`, `SECRET`, or `PASSWORD`, and of `WAYMARK_SEAT_URL`, when the value is 8 characters or more |
| `pattern` | `ghp_`, `gho_`, `ghs_`, `github_pat_`, `sk-ant-`, `xoxb-`, `xoxp-`, `AKIA` tokens, `Bearer` tokens, and PEM private key blocks |

**R-7.2** A redaction must keep the line valid JSON. The hook
redacts inside the parsed line and writes it again. It does not cut
the text of the raw line.

**R-7.3** The hook reports the count of each class in each post. The
engine adds the counts into `redactions`.

**R-7.4** The engine must redact a second time. It must apply the
`pattern` class. It must also hash each base64url string of 22
characters in the line and compare the hash with the seat's live
`fire_keys`, its `sitter_key`, and the transcript's `key_hash`. A
match is redacted as `seat-key`. The engine adds its own count into
`engine_redactions`. A count above zero is a defect in the hook, and
the ledger shows it.

**R-7.5** The chain is over the lines the hook sent, after the hook's
redaction. The engine's second redaction changes `raw` and `text`,
and it does not change `chain`. So a line the engine redacted still
verifies against the hook's chain.

## 8. Requirements: reading

**R-8.1** `transcript` and `transcript_entry` are not own-surface. A
sitter does not see them by default. The owner sees all of them.

**R-8.2** A grant reads them when its scope names the kind with the
action `read`. A `filter` on `seat` narrows the entry. The mayor's
seat reads them through a scope entry that a person approves, as any
other kind.

**R-8.3** A seat's scope can admit its own seat's transcripts. No
guard refuses it. The mayor audits its own sittings as it audits the
other seats' (D-6).

**R-8.4** The sitting's envelope must link its transcript, and the
transcript's envelope must link its sitting and the query of its
entries. The auditor goes from a sitting to what it said in one
step.

**R-8.5** The bytes the MCP door serves from these kinds count on the
reader's own sitting, under the tool that served them (spec-seat.md R-10.6a). An
audit has a cost, and the ledger shows it.

## 9. Requirements: the seal, the gap, and the purge

**R-9.1** The sweep must seal an `open` transcript when its sitting
has been `closed` or `abandoned` for `transcript_grace_seconds`. That
is an engine option, and its default is 600. The seal writes
`sealed_at` and drops `key_hash`. The grace is for the last Stop of a
fired run, which comes after the close (R-6.1).

**R-9.2** A sealed transcript takes no line, from any key.

**R-9.3** The seal must write `gap` when the record is not whole and
no gap is written yet. With no lines: `No transcript was received.`
With lines, and no post after the sitting ended: `The last post came
before the sitting ended.` An audit then knows that a record is
missing, and it knows which part.

**R-9.4** The seat must have a field `transcript_days`, an int. The
default is 30. The sweep must purge the entries of a sealed
transcript when `sealed_at` is older than that. The purge deletes the
entries and moves the row to `purged`. The row keeps `files`, with
each file's line count and chain, so the record says what existed and
proves which lines they were.

**R-9.5** The transcript must have a door `purge` (sealed → purged)
for a person. It has `safety.confirm`, with the consequence: `Every
line of this transcript is deleted. The counts and the chain stay.`
No grant can hold it.

## 10. The environment, and the trade

**R-10.1** The upload needs the run's shell to reach the engine. The
cloud environment that runs the Routines must allow
`work.kopsa.info` in its network access. This is a setting of the
environment, which a person changes. It is not a secret, and it is
not the key. Until the host is allowed, every fired transcript seals
with `No transcript was received.`, and the audit sees the reason.

**R-10.2** Allowing the host does not change the close. The first
path of R-12.17 still needs `WAYMARK_SEAT_URL`, and the environment
still holds none.

**The trade.** The key is in the model's context (D-4). A model that
wants to hide what it did can post false lines first. It cannot
delete a line the engine holds, and the hook's next post then breaks
the chain, so the gap says `diverged`. It cannot post after the seal.
The stronger design gives the hook a credential that the model never
sees: a header that the environment's proxy adds after the request
leaves the container, as R-12.17 describes for the seat's key. That
needs a stored credential on the environment, and every Routine's run
uses that environment. The owner's rule in the mayor's `CLAUDE.md`
keeps seat credentials out of it. This document takes detection now.
The proxy header is recorded as a punt.

## 11. What this closes in spec-seat.md

- **The exact pairing of a fire to its sitting** (section 18, R-12.23).
  The transcript carries `run_session`, the provider's id for the run.
  The fire answers that id. The pair is one filter.
- **Turn-level cost inside a sitting** (section 18). Each assistant
  entry carries its `usage`. The cost of one turn, or of one
  correction, is a sum over entries.
- **The closing call's own tokens** (section 18). They are still not
  on the bill. They are now on the record, in the entries after the
  close.

## 12. Acceptance

1. A fired sitting that sits, works, and closes has a sealed
   transcript whose `main` file has the same line count as the
   harness's file, and whose chain the hook's chain matches.
2. A second post of the same lines changes nothing, and it answers
   200.
3. A post that starts past the held count answers 409 with `held`,
   and the hook's next post from `held` is accepted.
4. A post whose line 3 differs from the held line 3 answers 409. The
   gap names the file and line 3. The held lines do not change.
5. A post with a wrong key, a dropped key, or no key answers the same
   404 sentence.
6. A second sit mints a second key. The first key answers 404.
7. A post after the seal answers 409 with the sealed sentence.
8. A line that carries a `waymark_sit` key, a fire `Key:` line, and a
   `ghp_` token is held with three redactions, and the chain verifies.
9. A line that carries a live `sitter_key` the hook missed is held
   redacted, and `engine_redactions` is 1.
10. A sitting that sent nothing seals with `No transcript was
    received.`
11. The mayor's grant, with `transcript_entry` read filtered to the
    code seat, finds each failed tool call of one sitting with one
    `waymark_query`.
12. A seat at `keep_transcripts` `none` answers no `transcript` at the
    sit, and no transcript row is born.
13. An interactive sitting at the default `fired` answers no
    `transcript`.
14. The purge, by the sweep or by the person's door, deletes the
    entries. The row keeps each file's line count and chain.
15. `sitting-close.sh` with the host refused finishes the close path
    as today, with one line on the error stream.

## 13. Recorded punts

- The proxy header that keeps the credential from the model (section
  10). It waits on a stored credential that no Routine run can find.
- A file store for `raw` outside the database. The attachments store
  (`server/attachments.clj`) is a local directory, and its own punts
  name a production store. The rows are enough at the house's volume.
- A view that renders a transcript as a conversation. The query and
  the entries are the first reader. The page is a surface declaration
  later.
- A hook for a container that the provider reclaims in the middle of a
  turn. No Stop event comes, so the transcript stops at the last post
  before it, and the seal writes the gap.
- A guard that keeps a seat from its own transcripts, so that a
  transcript does not become a second memory beside `self`, `journal`,
  and `letter`. The owner's ruling, 2026-09-27: not necessary now.
- Transcripts of sessions that sit in no seat. The key comes from the
  sit, so a session with no sit has no transcript.

## 13a. Deviations in the build

- The two kinds live in `server/transcripts.clj`, not `seats.clj`
  (R-3.1). held_calls.clj is the precedent for a core kind with its
  own door and its own sweep.
- The transcript is born at the sit, not in the sitting's commit
  (R-3.6). A key is the only way a line arrives, and the sit is where
  the key is answered. A sitting born by the leash keeper or by hand
  has no hook that could send a line.
- The hook redacts by plain replacement inside each raw line (R-7.2).
  A line with nothing to redact keeps its exact bytes, so its chain is
  the same on every Stop, and a replaced secret keeps the line valid
  JSON because the replacement is placed where the secret's own JSON
  text stood.
- The engine adds the hook's redaction counts only from a post that
  starts at the held line. A resend that overlaps held lines carries
  counts for lines already counted.
- `transcript_entry` carries `n`, the line's place among all the
  transcript's lines, as its default sort (R-3.5 names two keys).

## 14. The owner's decisions, 2026-09-27

1. The trade in section 10 is accepted: detection now, and the proxy
   header later.
2. `transcript_days` defaults to 30.
3. `keep_transcripts` defaults to `fired`.
4. A seat can read its own transcripts. The guard is a punt
   (section 13).
