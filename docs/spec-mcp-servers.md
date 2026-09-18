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
| `powers` | The policy. A list of `{power, tools, why}`. |
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
9. A stdio row started with the bench's own command discovers eight
   tools. The test runs when python3 is present and skips otherwise.

Tests: `waymark10/test/waymark10/mcp_servers_test.clj` (1 to 7, 9) and
`waymark10/test/waymark10/gate_proxy_test.clj` (8).

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

## 10. What this does not do

- OAuth flows a server needs. A token is an environment variable here.
- MCP resources, prompts and sampling. Tools only.
- A server per person. One instance, one list of servers.
