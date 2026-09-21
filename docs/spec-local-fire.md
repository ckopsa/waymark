# Spec — local fire: a seat's Routine on a machine of the house

**Purpose.** This document gives the requirements for `localfire`, a
small server that runs on a machine the house owns and answers the
engine's fire the way a Claude Routine's fire endpoint does. The engine
fires a seat by one POST to a `fire_url` with a bearer token
(spec-seat.md R-12.18 to R-12.20). Today that URL is a Claude Routine
in the cloud, so a seat can run only there. With this server, the URL
is a machine on the LAN, and the same seat, the same key, the same
sit and the same Stop hook run a headless Claude Code on that machine.
The engine does not change.

This document is written in ASD-STE100 Simplified Technical English.
Technical names from the codebase keep their spelling: seat, model,
schedule, sitting, chair, link, fire, sit, walk. "Must" gives a
requirement. "Can" gives a permission. "Is" gives a fact.

Bead: waymark-fp62.19, under the epic waymark-fp62. Design: this
session, 2026-09-21, from the owner's statement: "We need to create a
server that runs locally that does the same thing as the claude server
for claude running locally."

## 1. The decision

The server impersonates the Routine's fire endpoint. It does not add a
`cron` adapter, and it does not add a provider to the schedule's enum.

The reason is in what the engine already does. Three things start a
sitting: the cadence, a person's `fire`, and a transition the seat asked
to be woken by (R-12.22). All three go out through one POST in
`schedules/fire!`, and the link they read is on the model row or the
schedule row. A cron adapter would carry the cadence alone and lose the
other two. A server that answers that one POST gets all three, and the
engine needs no new code, no new field and no new state.

A person therefore invokes `link` on a model row one time, with the
server's URL and the server's token, exactly as for a cloud Routine
(ci-classifier.md, "One Routine for each model"). Every seat whose
chair is that model then fires on the machine.

## 2. What the engine pins

The wire is recorded in `schedules.clj` (`RoutineFire`, `fire!`,
`provider-note`). The server answers this wire and no other.

| the engine sends | the server must answer |
|---|---|
| `POST {fire_url}` with `authorization: Bearer {token}`, `anthropic-beta`, `anthropic-version`, `content-type: application/json` | one of the statuses below, inside 20 seconds |
| body `{"text": "…"}` or `{}` | the text is the run's whole prompt payload, verbatim |
| — | 200 `{"type": "routine_fire", "claude_code_session_id": "…", "claude_code_session_url": "…"}` |

The engine reads four failures, and the schedule row records each one
(R-12.20, `provider-note`):

| status | the engine's row | the server answers it when |
|---|---|---|
| 401 | `broken`, `The Routine refused the token.` | the bearer does not match |
| 404 | `broken`, `No Routine answers the fire URL.` | the path names no routine |
| 400 | `paused` | a person paused the routine on the server |
| 429 with `Retry-After` | `broken`, `The Routine has no free run. Try again after {n}.` | the routine is at its run cap |

Any other status breaks the row with the exception's own text. The
server must not answer 5xx for a condition the table names.

The text is composed by the engine (R-12.35, R-12.37): the seat's
instructions, a `Seat:` line, a `Key:` line minted for this firing, and
the person's prose inside a `routine-fire-payload` block. The key is
spent by one `waymark_sit`. The server carries the text to the session
and reads nothing in it.

## 3. Definitions

| term | meaning |
|---|---|
| routine | one name on the server, bound to one CLI model. The local copy of "one Routine for each model" |
| run | one firing of a routine: one headless Claude Code process, one session id, one record on disk |
| the place | a clone of the seat's place, `ckopsa/waymark-seat`: `CLAUDE.md`, `.claude/settings.json`, `.claude/hooks/sitting-close.sh`, and nothing else |
| the run page | the server's page for one run, the URL the engine writes as `last_run_url` |
| the spawner | the seam that starts a process from an argument vector and a working directory. The tests replace it. Its handle waits through `await-exit`, not `wait`: a protocol method named `wait` collides with the three final `wait` overloads every object inherits, and the compiler refuses the call site |

## 4. Requirements: the server

**R-4.1** The server must be a JVM Clojure program in its own module,
`localfire/`, with its own `deps.edn`. It depends on `http-kit` and
`jsonista` at the versions `waymark10/deps.edn` pins, and on nothing of
the engine at run time. Its `:test` alias adds `waymark10` as a local
root, so the round trip of section 9 runs the engine's own adapter
against the server. `clojure -M:serve` starts it.

**R-4.2** The server must read one EDN config file. The path is the
first argument, or the variable `LOCALFIRE_CONFIG`, or `localfire.edn`
in the working directory. The file holds no secret.

| key | type | meaning |
|---|---|---|
| `:port` | integer | the port. The rig table (docs/routines/rigs.md) gives 8111 |
| `:public-url` | string | the URL the engine and a person reach the server at, for run pages. Example `http://192.168.1.40:8111` |
| `:place` | path | the place. The server copies it for each run and never writes in it |
| `:runs-dir` | path | where run records live |
| `:claude` | string | the Claude Code binary. Default `claude` |
| `:mcp` | map `{:name :url}` | the engine's MCP door for the session. Default name `waymark` |
| `:allowed-tools` | list of strings | passed to `--allowedTools`. Default `["mcp__waymark__*" "Bash(echo *)"]` |
| `:routines` | map name → routine | the routines below |

A routine has `:model` (required, the CLI model id), `:max-concurrent`
(default 1), `:max-run-seconds` (default 3600), and `:prompt` (default
the fixed Routine prompt of R-6.1).

**R-4.3** The server must read its bearer token from the variable
`LOCALFIRE_TOKEN`, and from nowhere else. It refuses to start when the
variable is empty, with one sentence on the error stream. It compares a
presented bearer to the token in constant time. It never writes the
token in a log line, a run record or a page.

**R-4.4** The server must serve these routes.

| route | auth | answer |
|---|---|---|
| `POST /fire/{routine}` | bearer | section 5 |
| `POST /routines/{routine}/pause` | bearer | 200 `{"routine": …, "paused": true}`. 404 for an unknown routine |
| `POST /routines/{routine}/resume` | bearer | 200 `{"routine": …, "paused": false}` |
| `GET /runs/{id}` | none | the run page, section 7 |
| `GET /runs` | none | the run list, newest first, at most 100 |
| `GET /healthz` | none | 200 `{"ok": true, "routines": [names]}` |

The pause is a file, `{runs-dir}/paused/{routine}`, so it survives a
restart. The run pages carry no bearer check because they hold no
secret (R-7.3) and the engine's row links to them for a person's
browser.

**R-4.5** The server must not demand the `anthropic-beta` or
`anthropic-version` headers. The engine sends them, and the server
ignores them. A body with no bytes is read as `{}`, because a fire with
no text is a fire. A body that parses to anything but a JSON object
answers 422 with one sentence, and the engine breaks the row with that
sentence. The body is judged after the four judgments of R-5.1, so a
caller with the wrong bearer learns nothing from its body, and a run
slot the body refuses is given back.

## 5. Requirements: the fire

**R-5.1** `POST /fire/{routine}` must judge in this order and stop at
the first that holds: no bearer or a wrong bearer, 401; no routine of
that name, 404; the routine paused, 400; running processes of the
routine at or above `:max-concurrent`, 429 with `Retry-After: 60`.
Each refusal carries a JSON body with one sentence in `detail`. The
order is a decision, not an accident: a paused routine at its cap
answers 400 and not 429, so the engine's row says `paused` and not
`broken`. The slot is taken in one atomic step, so two fires that
arrive together cannot both read a free slot.

**R-5.2** The server must answer 200 before the run starts. It mints a
UUID, answers `{"type": "routine_fire", "claude_code_session_id":
"{uuid}", "claude_code_session_url": "{public-url}/runs/{uuid}"}`, and
starts the run on a thread of its own. The engine's fire has a 20
second timeout, and a run takes minutes.

**R-5.3** The run must be one process, started by the spawner in a
directory of its own, `{runs-dir}/{uuid}/place`, which is a copy of the
place. The server writes one file beside the copy, `{runs-dir}/{uuid}/
mcp.json`, naming the engine's MCP door under `:mcp`. The place itself
stays three files, so the seat-place workflow's count still holds.

**R-5.4** The argument vector must be this, with the config's values in
it:

```
{claude} -p
  --session-id {uuid}
  --model {routine's model}
  --output-format json
  --strict-mcp-config --mcp-config {runs-dir}/{uuid}/mcp.json
  --allowedTools {each allowed tool, one argument each}
  {the prompt of R-6.2}
```

`--session-id` makes the session's id the one the engine already
holds, and `CLAUDE_CODE_SESSION_ID` in the session is that id, which
the Routine prompt asks the session to echo and pass to the sit.

**R-5.4a** The server must drain the process's two pipes on threads of
their own before it waits on the process. `claude -p` writes its whole
result JSON at the end, and a process whose pipe fills and is not read
stops and never ends.

**R-5.5** The server must end a run that outlives the routine's
`:max-run-seconds`. It signals the process to end, waits ten seconds,
then destroys it. The record says `killed`. The sitting is then the
sweep's, as a reclaimed cloud container's sitting is (R-7.6 of the seat
spec).

**R-5.6** A restart of the server must not start a run again. The
engine's `already-fired?` dedupes replays on its side, and the server
holds no queue. Runs in flight at a restart are recorded as `lost` at
the next start, from the records that say `running` with no end.

## 6. Requirements: the prompt

**R-6.1** The default prompt of a routine is the fixed Routine prompt
of ci-classifier.md, "One Routine for each model", verbatim. It holds no
key and names no seat. A routine's `:prompt` replaces it.

**R-6.2** The prompt the process gets must be the routine's prompt,
one blank line, then the engine's text inside a `routine-fire-payload`
block:

```
{the routine's prompt}

<routine-fire-payload>
{the text the engine sent, verbatim}
</routine-fire-payload>
```

R-12.21 of the seat spec records that the cloud provider puts the
fire's text into the session in that block, and the seat's instructions
and the Routine prompt were written against that shape. A fire with no
text gets the prompt and an empty block. The server never cuts the
text.

**Pin before the first real firing.** The exact way the cloud provider
wraps the text is visible in the transcript of one real firing and
nowhere else in this repository. Read one, and make R-6.2 match it
word for word. A difference here is the one thing that would make a
seat behave differently on the two providers.

## 7. Requirements: the record and the page

**R-7.1** Each run must leave these files under `{runs-dir}/{uuid}/`:
`run.edn` with `:routine`, `:model`, `:status` (`running`, `done`,
`failed`, `killed`, `lost`), `:started-at`, `:ended-at`, `:exit`;
`fire.txt`, the text with the `Key:` line's value replaced by
`<withheld>`; `stdout.json`, the process's stdout, which is Claude
Code's result JSON; `stderr.log`.

**R-7.2** The run page must show: the routine, the model, the status,
the instants and the duration, the exit code, the session id, and from
`stdout.json` when it parses: `num_turns`, `total_cost_usd`, the four
token counts under `usage`, and `modelUsage`. Then `fire.txt`, then the
tail of `stderr.log`. It is one HTML page with no script.

**R-7.3** The server must never write the firing key. The `Key:` line
is withheld in `fire.txt` (R-7.1), and the text is not written in any
log line. The argument vector is not logged. The session's own
transcript, under Claude Code's project directory on that machine,
holds the prompt as it holds every prompt, and the key in it is spent
by the first sit.

**R-7.4** The token counts on the page are Claude Code's own report of
the API's `usage`, the same figures the Stop hook sums from the
transcript. `total_cost_usd` is list price applied to those counts, not
the plan's bill. The engine's `close` handler prices the sitting from
the model row, and that figure is the ledger's.

## 8. Requirements: the machine

**R-8.1** The place must be a clone of `ckopsa/waymark-seat`, pulled
by a person or a timer. The server copies it and does not pull it.

**R-8.2** The session's bearer at the engine's MCP door is the person's
own, as the connector's is (spec-connector-door.md § 3). A person
authenticates Claude Code to the door one time on that machine, through
the OAuth flow the door advertises. Headless runs reuse the stored
credential. The sitter the sit makes is then the same delegate the cloud
connector resolves to, and the firing key works unchanged.

**R-8.3** The Stop hook takes its second path on that machine: no
`WAYMARK_SEAT_URL` is set, the hook holds the stop one time, and the
session closes its own sitting through the connector (seat spec
R-12.17). That is what a cloud run with no environment does. The server
sets no seat variable.

**R-8.4** The hook must parse under the machine's bash. macOS ships
bash 3.2, which could not parse the hook until 2026-09-21; the fix is
in `sitting-close.sh` and reaches the place through the seat-place
workflow.

## 9. Requirements: the tests

**R-9.1** The suite must run with no database and no network beyond
the loopback. The spawner is a seam; the tests give a fake that records
the argument vector and the directory, writes a canned `stdout.json`,
and exits as told.

**R-9.2** One test must be the round trip: the server on an ephemeral
port, and `schedules/fire-routine` on the engine's own `RoutineFire`
adapter fired at it. The right token answers `:session-id` and
`:session-url`. A wrong token throws with `:status 401`. A paused
routine throws with `:status 400`. A routine at its cap throws with
`:status 429` and `:retry-after`. This pins the wire from both sides.

**R-9.3** One test must start a real process through the real spawner,
`sh -c` with a short script, and show: a run that exits 0 is `done`
with its stdout parsed; a run that outlives a one second
`:max-run-seconds` is `killed`.

**R-9.4** One test must show the prompt of R-6.2 with and without a
text, and the `fire.txt` of R-7.1 with the key withheld and every other
line kept.

**R-9.5** CI must run the suite in the `quick` job of `tests.yml`, and
`localfire/*` must be a code path in the `changes` job. The Makefile
gets `check-localfire`, which runs the suite locally because it needs no
database, and `serve-localfire`.

## 10. Hand steps

1. On the machine: install Claude Code, sign in, clone
   `ckopsa/waymark-seat` to the place, and authenticate the MCP server
   `waymark` at `https://work.kopsa.info/api/-/mcp` one time.
2. Write `localfire.edn` with the port, the public URL, the place, the
   runs directory and one routine per model. Mint a token of 128 bits,
   base64url, and export it as `LOCALFIRE_TOKEN` in the service's
   environment, not in the file.
3. Start the server under systemd on the rig node, or launchd on a Mac,
   with `clojure -M:serve` in `localfire/`. The JVM must be on the
   service's path. A Mac with Homebrew's JDK and no system Java needs
   `JAVA_HOME` set to that JDK's home in the service's environment.
   Check `GET /healthz`.
4. Invoke `link` on the model row with `{public-url}/fire/{routine}`
   and the token. Leave the seat's schedule with no link, so it fires
   through the chair.
5. Fire the seat once by hand, open `last_run_url` on the schedule row,
   and read the run page and the sitting row. Read the transcript of
   that run and pin R-6.2.

## 11. Recorded punts

- **A queue for a 429.** The engine's punt list already records that a
  fire inside the run cap is lost and a person fires again. The server
  holds no queue for the same reason.
- **Nomad packaging.** The rigs are nomad jobs. The server needs the
  `claude` binary and a person's stored credential, so it starts as a
  plain service on the node. A container with the credential mounted is
  a later step.
- **A tunnel.** When the engine is not on the LAN, the public URL is a
  Tailscale or Cloudflare address and the server is unchanged.
- **The stdio transport of waymark-iwi.** A session on the LAN could
  speak MCP to the engine in-process. The HTTP door with the person's
  bearer is what the cloud uses, so parity says HTTP first.
- **Closing the sitting from the result JSON.** The server sees the
  exact counts on stdout and could post the close itself. It holds no
  key, because the firing's key is spent by the sit, so the hook's
  second path stays the close.
- **A hook trust prompt in headless mode.** Project hooks under the
  place's `.claude/settings.json` may need a one-time trust on that
  machine. The first hand firing shows whether they run.
