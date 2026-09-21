# MCP servers as rows

Written in ASD-STE100 Simplified Technical English. Bead waymark-fp62.10.

## 1. The decision

An MCP server is a row. The kind is `mcp_server`. A person creates one,
the engine discovers its tools, and a scope names its powers. The
connector's ten tools do not change. Sitters and sources keep the tool
names they use today.

Before this kind, a power reached the engine through Gate: a second
server, a second policy file, and a static map in `gate_proxy.clj` that
bound each tool name to a power token. That map is gone. The row's
`powers` list is the policy now.

## 2. The kind

`mcp_server` is core's kind, enrolled in every engine beside the seat
(`waymark10.modules`). It lives in `waymark10.server.mcp-servers`.

Fields a person states:

| Field | What it is |
| --- | --- |
| `name` | Unique. The prefix every tool of this server wears: a tool `read` on `emila` is `emila__read` to every caller. |
| `transport` | `http` or `stdio`. |
| `url` | Where an http server answers. |
| `command`, `args` | What a stdio server is started with. |
| `auth_env` | The NAME of an environment variable on the engine's host that holds the `Authorization` header value. Never the value. |
| `passthrough` | True only on the row named `gate`. Its tools already wear their prefixes. |
| `powers` | The policy. A list of `{power, tools, approval, shown, constraints}` (`why` is `approval`'s older spelling). |
| `note` | Free words for the next person. |

Fields the engine writes: `tools` (the mirror of `tools/list`: each
tool's name, description and input schema), `tools_hash`,
`discovered_at`, `last_error`.

States: `live`, `dark`, `retired`. Doors:

| Door | Who | What it does |
| --- | --- | --- |
| `create` | a person | Makes the row and discovers the server. A server that does not answer makes a row born `dark`. |
| `restate` | a person | States `url` or `command`, `args`, `auth_env`, `powers` or `note` again, then discovers. A server that does not answer refuses the restate. |
| `discover` | a person or the engine | Reads `tools/list` again and mirrors it onto the row. |
| `mark_dark` | the engine | Records that a call failed on the wire. Hidden. |
| `mark_live` | a person | Discovers first. Refuses when the server does not answer. |
| `retire` | a person | Closes the client. No power reaches the row. |
| `restore` | a person | Puts a retired row back `dark`. `mark_live` then proves it answers. |

## 3. The name is the prefix

The engine resolves a tool name by its prefix (`resolve-tool`). It splits
the name at the first `__`. The row that wears the prefix answers the
bare name to its server. When no row wears the prefix, the row named
`gate` with `passthrough` true answers the full name. This row is the
bridge of Gate's deprecation. Nothing else uses `passthrough`.

Example: `emila__read`. A row named `emila` answers it as `read`. No row
named `emila` yet: the `gate` row answers it as `emila__read`.

The power door must also take a power token as the tool name, when that
power names exactly one tool. `bench.read` names the one tool `read` on
the `bench` row, so the door reads `bench.read` as `bench__read` and
judges the grant, the filter and the `why` on that tool. A power that
names two tools is not a tool name: the door refuses it with a 404 that
lists both tool names. A power that names a glob names no single tool,
so it is not a tool name either. The door resolves the name before
every judgement, and the ledger counts the call under the tool the name
resolved to.

## 4. The powers list is the policy

One entry: `{"power": "email.read", "tools": ["read", "search"], "approval": "none"}`.

- `power` is the dotted token a grant names.
- `tools` are tool names or globs with `*` on this server.
- `approval` is what a call must pass before it goes out: `none`, `why`
  (one sentence of reason, and the call runs at once) or `person` (the
  sentence, and the call waits for a person's tap, which is R-14).
- `why` true is the older spelling of `approval why`, and the engine
  still reads it as one. An entry states one or the other; an entry
  whose two spellings disagree is refused at the write door.
- `shown` names the two or three tool input fields a held call's own
  line carries (R-14).
- `constraints` are the tool input fields a grant's filter may name for
  this power. An entry that lists none admits no filter.

A tool that no entry names does not exist through the power door,
whatever the server offers. The engine judges a call in this order: no
entry names the tool (404), the grant does not admit the power (403), the
entry demands a `why` and the call has none (422), the entry says
`approval person` and the call is HELD (R-14), then the forward.
Nothing before the forward touches a server.

`waymark_powers` answers the mirrored tools of every live row that the
caller's grant admits, with their schemas. `waymark_power` calls one by
its prefixed name. The bytes and the dropped bytes land on the sitting's
served line, as before. The engine's own calls past the grant (the
sources, the `:power` hook of a handler) resolve the same way through
`gate-proxy/rpc-of`.

### The powers list is also the vocabulary

A dotted token in a grant scope is real if a server names it. The engine
reads the `powers` of every `mcp_server` row that is not retired. If one
row names the token, the engine accepts the scope entry. If no row names
the token, the engine reads the capability registry. An active capability
row makes the token real too. Retire the server row and its tokens are no
longer real: a new scope that names one of them refuses.

Two capabilities stay in the registry. This engine enforces
`feed.preview_as` with its own feed route. This engine holds
`schedule.write` and grants it to nobody. No server enforces these two
powers, so no server row can hold them.

Write any dotted token you want in a `powers` entry. A capability row is
not necessary. The row is the policy and the row is the word.

The engine sweeps the registry one time at each boot
(`sweep-capabilities!`). The engine retires a capability row when a
server names its token AND the row's `enforced_by` names Gate. The engine
retires the row through the capability kind's own `retire` door, as the
engine principal. A second sweep makes no changes.

The discover answer shows one list. `doors.ask.powers` shows the servers'
tokens and the tokens of the active capability rows together. Read that
list to compose an ask.

### The narrow power

A `powers` entry can hold `constraints`: a list of tool input field
names. A grant filter may name these fields and no other field. The
bench row lists `["repo", "path"]` on its find, read and edit entries.
The bench row lists `["repo"]` on its pull entry, because a path
cannot narrow a whole checkout. The bench row lists `["repo"]` on its
feedback entry, because a feedback reads a whole branch. An entry that
lists no constraints admits no filter at all. Gate's entries list none.

The engine judges a filter at the ASK. A scope entry that names a
dotted power and carries a `filter` may name only the fields that
power's `constraints` list. The engine refuses the ask when the filter
names another field. The engine refuses the ask when the power lists
no constraints. The guard is `scope-filters-are-filterable`, which is
the guard that judges a filter on a kind as well. A dotted token that
only the capability registry names keeps its old judgment: the engine
does not narrow it, and its own enforcement point reads its filter.

One power can carry more than one filtered entry on one grant. Write
one entry for each repository you narrow to. The grant surface keeps
every entry. A kind keeps the one-entry rule, because a kind's filters
are a query.

The power door judges every call. The door reads the call's `repo`
value. The value must equal one value the filter names. A comma in a
filter value means "any of these". The door reads the call's `path`
value. The value must match one glob the filter names. The glob grammar is
the rig's deny grammar: a `*` matches any characters, slashes
included, and a `?` matches one character. A glob also matches the
last part of the path alone, so `*.pem` matches `keys/server.pem`. The door admits the call when ANY entry admits it.

The door adds one argument to a call that names no path, and to a call
whose path is `.`. The argument is `allow`. It holds the globs of the
entries that admitted the call. The rig then holds itself to those
globs for that one call.

The door refuses a call that no entry admits. The refusal is a 403.
The sentence names the power, the field, the call's value and the
filter. The door sends nothing to the server on a refusal.

The engine adds two more arguments to a bench call from a bound
sitting. `seat` is the seat's id. `sitting` is the sitting's id. The
engine reads both from the session's binding, in the MCP dispatch, so
the power door stays a function of the grant and the call. A session
with no bound sitting carries neither argument. The rig holds no seat
between calls, so the engine names the office on every call. The
engine's own hand carries no session, so its own calls name no office.

The discover answer publishes the fields. `doors.ask.constraints` maps
each power to its field names. A power that admits no filter is absent
from that map. `waymark_powers` shows `constraints` on each tool whose
entry lists them.

### The held call (R-14)

An entry that says `approval person` does not forward its call. The
engine mints one `held_call` row and answers the caller at once:

```json
{"held": true, "held_call": "<id>", "note": "waiting on a person's tap"}
```

That is an ANSWER and never a refusal. It counts as one served answer
of its own size on the sitting's `served` line, and as no refusal.

The row is both the notice and the record. It carries the server, the
tool as the door resolved it, the call's arguments as the caller gave
them, the `why`, the caller, the sitting the session sat in when it
sat, and `expires_at`. It carries a second, hidden map, `forward`:
what the server would actually receive, which the power door had
already prepared: the filter's `allow` globs added, and the `why`
translated for a passthrough row or removed for a server that never
asked for one.

Its own line names the caller, the tool and the fields the entry
marked as `shown`: `{approval: person, shown: [to, text]}` makes the
line read `to=… · text=…`. An entry that marks none leaves the line to
the `why`.

Two doors are a person's: `allow` and `refuse {reason}`. Two walls
stand on both, and they are the permission slip's, reused by name.
The first is `the-caller-does-not-decide`: `caller` is stamped at
birth, so nobody answers their own call. The second is a role,
`approver`. A person who is not the caller and holds no role meets the
second wall and its sentence.

The states are `held` (initial), `allowed`, `done`, `refused`,
`failed` and `expired`. A person's tap lands `allowed`. The engine
then forwards ONCE, at the wire boundary, under the row's own client
and with the row's own `forward` arguments, and lands the ending: the
answer on the row, cut to 16 KB with the bytes that went counted in
`answer_dropped`, and the row `done`; or the wire's sentence in
`reason`, and the row `failed`. A second allow is refused, because the
row is no longer held. A refusal carries its reason, and the caller
reads it on the row.

The caller learns the decision by a wake or a read. A seat's `wake_on`
may name `{kind: held_call, actions: [allow, refuse], filter: {caller:
<its own id>}}`, so the seat wakes when a person decides its own call
and not somebody else's. An interactive session reads the row: the
kind's own surface is `by: caller`, so a caller reads its own held
calls with no grant at all, and opens no door on them. The engine
never calls the caller back.

`expires_at` is stamped at birth, 24 hours out. The
`held-call-expiry` cadence walks the `expire` door over every held row
past it, so nothing runs late. A caller that reads an expired row
reads that word.

The engine's own calls never hold. `mcp-servers/call!`, `rpc-of` and
`gate-proxy/power-of` reach a server without passing `invoke-for`, so
a source, the `:power` hook of a handler and the bench helpers are not
callers and mint no row.

Approval and `safety.confirm` stay different and are meant to. A
confirm is the caller's own acknowledgement of a consequence, echoed
back at the door it stands on. An approval is another person's tap.
A door may want both, and the order is confirm first, then hold.

## 5. The clients

The engine holds one client per row (`client-for`). An http row holds
one session, reused, re-initialized when the server says it expired. A
stdio row holds the process and its pipes. The engine starts the
process on the first call, restarts it when it dies, and marks the row
dark after three deaths in one cadence window. Each client has its own
lock and its own timeout, so one hung server does not stop another's
calls.

A dark row answers every power with a 503 that names `mark_live` as the
remedy. The http client reads `auth_env` at call time. The engine never
writes the value to a row, a log, a transition or an answer. A value
that looks like a secret (a space, a colon, more than 64 characters)
refuses at create and at restate.

The seam for tests and for deployments is `(:services eng) :mcp-servers`:
`:client-fn` (a function of the row that answers a client, or nil),
`:gate-rpc` (the client of the row named `gate`, handed in whole),
`:call-timeout-ms` and `:discover-ms`.

## 6. The cadence

The `mcp-discover` hook (core's, elected) re-reads every live row's
`tools/list` every `discover-ms` (default 15 minutes). It walks the
`discover` door only when the hash moved, so an unchanged list costs no
transition. A failure the client calls fatal marks the row dark.

The pass tells the door WHICH LIST IT SAW: the `discover` door takes
one optional, hidden input, `seen_hash`, and the sweep passes the hash
it read. The door is otherwise inputless, and invoke's natural replay
answers a second inputless call on the same door with the first one's
outcome — so a second pass over a list that had moved again would have
been swallowed and the row would have kept the older mirror for good.

## 7. Gate's deprecation, in steps

1. This kind lands. At boot, when `WORKQUEUE10_GATE_URL` is set,
   `ensure-gate-row!` seeds one row named `gate`, passthrough, at that
   url, with the powers the static map used to hold
   (`gate-seed-powers`). Every tool keeps its name. Nothing changes for a
   sitter.
2. Create the bench as a row, `bench`, stdio, beside the engine.
3. Move one Gate rig at a time to its own row: `emila`, `tgram`, `messa`.
   When a row is live and discovered, restate the `gate` row without its
   entries.
4. When the `gate` row's powers are empty, retire it. Gate stops.

Each step is one restate by a person and no deploy of the engine.

## 8. Acceptance

1. A row created with a fake server discovers three tools onto the row
   with schemas and a hash.
2. A scope naming `email.read` on a row whose powers map it to two tools
   sees those two in `waymark_powers` and not the third.
3. `waymark_power` on a tool no entry names refuses; on a why-required
   tool without a why it refuses naming `why`.
4. A call on a dark row refuses with the remedy naming `mark_live`.
5. A restate whose `auth_env` looks like a value refuses.
6. The bytes and the dropped bytes of a power call land on the sitting's
   served line.
7. Two rows, one hung: the other's call answers inside its timeout.
8. The gate row with passthrough answers `emila__read` exactly as the
   static map did, and the static map is gone from `gate_proxy.clj`.
9. A stdio row started with the bench's own command discovers the
   bench's tools, the eight this engine knows among them (the rig also
   offers the enrolment's three, which no powers entry names and the
   engine's own hand calls). The test runs when python3 and the bench
   checkout are present
   and asserts its own skip otherwise, because a test that runs without
   an assertion is a failure and a machine without python3 is not a
   broken engine.
10. An ask that filters `bench.read` by `branch` refuses. The same ask
    that filters it by `repo` stands.
11. A grant that filters `bench.read` to one repository forwards a read
    on that repository. It refuses a read on another repository with a
    403 that names the filter, and the rig hears nothing.
12. A grant that filters `bench.read` by path forwards a read inside
    the globs and refuses a read outside them. A find that names no
    path forwards with the globs as `allow`.
13. Two filtered entries for `bench.edit` stand on one grant. The door
    admits a call either entry admits. The door refuses a call neither
    admits.
14. An ask that filters `telegram.send` refuses, because the gate row
    lists no constraints.
15. A bench call through `waymark_power` in a bound sitting carries
    `seat` and `sitting`. A call with no bound sitting carries neither.
16. The discover answer lists the constraints of the bench powers and
    lists none for Gate's.
17. An ask that filters `bench.feedback` by `path` refuses. A grant
    that filters `bench.feedback` to one repository forwards a
    feedback on that repository and refuses one on another. The rig
    receives no `allow`, because the filter narrows no path.

18. A call on a tool whose entry says `approval person` answers
    `held` and mints a row; the fake server records no call.
19. The caller's own `allow` is refused by the not-the-caller wall. A
    person who holds no role is refused by the role wall. A person
    with the role allows.
20. The allow forwards once, with the arguments the door prepared,
    stores the capped answer and its dropped count, and moves the row
    to `done`. A second allow is refused. A `refuse` carries its
    reason and the rig hears nothing.
21. A wire failure after the allow moves the row to `failed` with the
    reason, and the rig heard exactly one call.
22. A seat whose `wake_on` names `held_call`, the actions `allow` and
    `refuse`, and a filter on its own `caller` wakes on its own call
    and not on another caller's.
23. The expiry sweep expires a held call past `expires_at`, and the
    caller reads `expired`.
24. The engine's own call on a `person` tool runs without holding.
25. A held call counts one served answer and zero refusals on the
    sitting, and `waymark_powers` says `approval: person` on the
    tool.

Tests: `waymark10/test/waymark10/mcp_servers_test.clj` (1 to 7, 9),
`waymark10/test/waymark10/gate_proxy_test.clj` (8),
`waymark10/test/waymark10/narrow_power_test.clj` (10 to 17) and
`waymark10/test/waymark10/held_call_test.clj` (18 to 25).

## 9. Deviations on record

- A create cannot walk a door on a row that does not exist yet. The
  discover runs in the create's own hook, and a server that does not
  answer makes a row born `dark` with the reason in `last_error`.
- A power call is not a transition on any row, so a required `why` is
  demanded and forwarded to a passthrough server as `__why`, but it is
  not written to the transition log.
- A restate runs a discover too. A dark row that a person fixes by
  restating its address lands live in one tap.
- A tool that no entry names answers 404 (it does not exist), not 403.
- The `discover` door takes an input the requirements do not name:
  `seen_hash`, optional and hidden, the hash the cadence read before it
  walked the door. It is what keeps two passes over two different tool
  lists two calls rather than one replayed call (section 6).

### The held call (waymark-fp62.10.2)

- The bead's R-1 lists five states and the kind has six. `allowed`
  stands between the person's tap and the ending. A handler cannot
  choose its door's destination, and an allow that reaches a server
  has two honest endings: the answer, and the wire failure. It also
  keeps a call that may take the client's whole timeout OUT of the
  transaction that holds the row. The tap lands `allowed`; the wire
  boundary forwards and walks `land` or `fail`.
- R-3 names "the approver role of the engine's `approval_request`
  door". That door carries the four-eyes wall and no role, so there
  was no word to borrow: the kind spells `approver` itself, and a
  deployment mints the `role` row.
- R-1 names `input` as the call's arguments. The row carries a second,
  hidden map, `forward`: what the server would actually receive,
  prepared by the power door at call time. Without it the allow would
  have to re-judge a grant at a moment the call was never judged in.
- R-7 names the expiry of a HELD call. The sweep also expires a row a
  stopped engine left `allowed` past its moment, because a call
  nobody finished must not look like one somebody is about to.
- A call that reaches the power door with no principal on it cannot be
  held: a held call names its caller, and the first wall on answering
  one is "not the caller". The door refuses such a call with a 403
  rather than minting a row anybody could allow.
- R-11 (the held call as a card on the approver's feed) and R-12 (the
  corrections line reading a refused held call) are NOT built here.
  The kind's `default-filters` open on the held queue and its
  `sortable` is newest-first, so the collection is the queue; the feed
  population is owed.

## 10. What this does not do

- OAuth flows a server needs. A token is an environment variable here.
- MCP resources, prompts and sampling. Tools only.
- A server per person. One instance, one list of servers.
