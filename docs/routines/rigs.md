# The rigs as their own rows: Gate's retirement

Gate is one MCP server in front of eight rigs. The engine reaches each
rig through the row named `gate`, with `passthrough` set, and the rigs
answer on addresses only Gate's node can reach. This runbook moves each
rig to its own `mcp_server` row and then retires Gate. It is the hand
half of docs/spec-mcp-servers.md section 7. Each step is one call on a
row and no deploy of the engine.

Written in ASD-STE100 Simplified Technical English.

## Who does the steps

A person does them, in the UI or through a tool the person is signed in
to. The guard `a-person-or-the-engine` on every door of the kind admits
a person, the engine, and an agent that acts for a person. A seat does
not add a server to the house it sits in.

## Before the first row

1. The rig answers on a static host port (waymark-fp62.10.1.1). The port
   table is below. A row names a URL, and a URL must not move between
   allocations.
2. The engine runs with `WORKQUEUE10_GATE_URL` set. That variable now
   means one thing: the rigs are real. The thread and inbox sources
   resolve each tool by its prefix to whichever row names it. Nothing
   else reads the variable, and nothing reads `default-gate-url` after
   the gate row exists.

## The port table

| rig | port | url |
|---|---|---|
| emila | 8102 | `http://192.168.1.40:8102/mcp/` |
| tgram | 8103 | `http://192.168.1.40:8103/mcp/` |
| messa | 8104 | `http://192.168.1.40:8104/mcp/` |
| gsd | 8105 | `http://192.168.1.40:8105/mcp/` |
| ynab | 8106 | `http://192.168.1.40:8106/mcp/` |
| amzn | 8107 | `http://192.168.1.40:8107/mcp/` |
| keep | 8108 | `http://192.168.1.40:8108/mcp/` |
| costco | 8109 | `http://192.168.1.40:8109/mcp/` |
| tgram-bot | 8110 | `http://192.168.1.40:8110/mcp/` |
| localfire | 8111 | `http://192.168.1.40:8111/` |

Gate holds 8100 and the bench holds 8101. The address is the node the
rigs are pinned to. 8111 is the local fire server
(docs/spec-local-fire.md), which is not a rig: it answers the engine's
fire and no MCP tool, so it has no `mcp_server` row.

## One rig, one row

Do these five steps for one rig. Then do them for the next rig. Do not
create every row first: a rig that does not answer refuses `create`, and
the gate row keeps serving the rigs that are not moved yet.

### Step 1: create the row

Invoke `create` on the kind `mcp_server`. The engine discovers the
rig's tools in the same call and refuses when the rig does not answer.
The row is born `live`.

The tools in a `powers` entry are the rig's own names, without a prefix.
The row's name is the prefix: a caller spells `emila__read` and the
engine forwards `read` to the row named `emila`. Gate's row lists the
prefixed names because Gate is a passthrough; a rig's row does not.

`why` is true on every tool Gate's policy marked `require_approval`.
When waymark-fp62.10.2 is live, restate those entries with
`"approval": "person"` so the engine holds the call for a person's tap,
as Gate did. Until then `why` demands the sentence and forwards at once.

The nine inputs follow. `note` is free text and optional.

emila:

```json
{"name": "emila", "transport": "http", "url": "http://192.168.1.40:8102/mcp/",
 "powers": [
  {"power": "email.read", "why": false,
   "tools": ["inbox", "list_messages", "search", "read", "read_batch",
             "download_attachment", "summary", "folders"]},
  {"power": "email.move", "why": true, "tools": ["move", "move_from_sender"]},
  {"power": "email.send", "why": true, "tools": ["send"]}]}
```

tgram:

```json
{"name": "tgram", "transport": "http", "url": "http://192.168.1.40:8103/mcp/",
 "powers": [
  {"power": "telegram.read", "why": false, "tools": ["get_messages", "list_chats"]},
  {"power": "telegram.send", "why": true, "tools": ["send_message"]}]}
```

The gate row's `telegram.read` names two search tools the rig does not
offer. Do not copy them.

messa:

```json
{"name": "messa", "transport": "http", "url": "http://192.168.1.40:8104/mcp/",
 "powers": [
  {"power": "messages.read", "why": false, "tools": ["threads", "read_messages", "reset"]}]}
```

keep:

```json
{"name": "keep", "transport": "http", "url": "http://192.168.1.40:8108/mcp/",
 "powers": [
  {"power": "notes.read", "why": false, "tools": ["list_notes", "search", "read"]}]}
```

ynab:

```json
{"name": "ynab", "transport": "http", "url": "http://192.168.1.40:8106/mcp/",
 "powers": [
  {"power": "ynab.read", "why": false,
   "tools": ["accounts", "transactions", "budget_month", "categories"]},
  {"power": "ynab.write", "why": true,
   "tools": ["update_transaction", "split_transaction", "bulk_approve", "create_transaction"]}]}
```

amzn:

```json
{"name": "amzn", "transport": "http", "url": "http://192.168.1.40:8107/mcp/",
 "powers": [
  {"power": "amazon.read", "why": false,
   "tools": ["orders", "search", "product_details", "view_cart", "reset"]},
  {"power": "amazon.cart", "why": true, "tools": ["add_to_cart"]}]}
```

costco:

```json
{"name": "costco", "transport": "http", "url": "http://192.168.1.40:8109/mcp/",
 "powers": [
  {"power": "costco.read", "why": false,
   "tools": ["receipts", "receipt", "captured", "login", "reset"]}]}
```

gsd has no row. The engine owns tasks and the calendar natively, and the
gate row's powers never named a gsd tool. Stop the gsd job with Gate, or
keep it for the CLI. That is the owner's call.

tgram-bot (waymark-fp62.18.1):

```json
{"name": "tgrambot", "transport": "http", "url": "http://192.168.1.40:8110/mcp/",
 "powers": [
  {"power": "telegram_bot.read", "why": false, "tools": ["list_chats", "get_messages", "me"]},
  {"power": "telegram_bot.send", "why": true, "tools": ["send_message"]}]}
```

### Step 2: read the row

Invoke `waymark_get` on the row. `tools` lists what the rig answered
and `tools_hash` is set. A tool a powers entry names and the rig does
not list is a mistake in the entry. Restate the entry.

### Step 3: call one tool through the row

From a session that holds the power, invoke one read tool with
`waymark_power`, for example `emila__folders`. The answer comes from
the rig through the new row. `waymark_powers` shows the tool once,
under the new row, because the gate row still names it too and the
engine lists each name one time.

### Step 4: take the entries off the gate row

Invoke `restate` on the gate row with its `powers` list minus the
entries the new row carries. The gate row keeps the other rigs. A
caller's tool name resolves to the one row that names it now.

### Step 5: watch one day

The discover sweep re-reads the row on its own cadence. A rig that
stops answering marks its own row `dark` and nothing else. The gate row
stays live for the rigs not yet moved.

## When the gate row's powers are empty

1. Invoke `retire` on the gate row. A retired row answers no tool, and
   `restore` brings it back to `dark` if a rig must go back behind Gate.
2. Stop the gate job. In ckopsa/home-infrastructure remove
   `terraform/nomad-jobs/gate.hcl` and `nomad_variable.gate` in
   `terraform/nomad_variables.tf`, apply, and merge the same day. The
   gate's bot token is free after that. It is the approval channel of
   Gate and nothing else uses it.
3. Leave `WORKQUEUE10_GATE_URL` set in `workqueue10.hcl`. The comment
   beside it says what it means now. Renaming it is a deploy of the
   engine and buys nothing.

## The sweep of the capability rows

The boot sweep retires a capability row whose token a live server row
names. After the move, every Gate token is named by a rig's row, so the
capability rows stay retired. The two the engine enforces itself,
`feed.preview_as` and `schedule.write`, stay active. Nothing to do.

## To go back

Invoke `retire` on the rig's row and `restate` the gate row with the
entries back on it. The gate row is the fallback until it is retired.
