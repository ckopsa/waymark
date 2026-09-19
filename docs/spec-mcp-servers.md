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
| `powers` | The policy. A list of `{power, tools, why, constraints}`. |
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

## 4. The powers list is the policy

One entry: `{"power": "email.read", "tools": ["read", "search"], "why": false}`.

- `power` is the dotted token a grant names.
- `tools` are tool names or globs with `*` on this server.
- `why` true says each call must carry one sentence of reason.
- `constraints` are the tool input fields a grant's filter may name for
  this power. An entry that lists none admits no filter.

A tool that no entry names does not exist through the power door,
whatever the server offers. The engine judges a call in this order: no
entry names the tool (404), the grant does not admit the power (403), the
entry demands a `why` and the call has none (422), then the forward.
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

Tests: `waymark10/test/waymark10/mcp_servers_test.clj` (1 to 7, 9),
`waymark10/test/waymark10/gate_proxy_test.clj` (8) and
`waymark10/test/waymark10/narrow_power_test.clj` (10 to 17).

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

## 10. What this does not do

- OAuth flows a server needs. A token is an environment variable here.
- MCP resources, prompts and sampling. Tools only.
- A server per person. One instance, one list of servers.
