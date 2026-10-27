# vanilla_plus

The household's Classic+: its private World of Warcraft 1.12.1 server
(CMaNGOS Classic with playerbots, Nomad job `wow` on big-colt) stays at
level 60 and keeps growing. Each new piece of the world -- a quest, an
NPC, an item, a loot change, an event, a tuning pass -- is one `content`
row, from an idea to the live world. The content itself is SQL in
`ckopsa/vanilla-plus` (private); the live server is reached through the
`wow` door (`ckopsa/waymark-doors`, `wow-mcp/`). The shape and its rules
are in `docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `org.edn` | The manifest. `:live true`: the house engine boots this org; `:world :none`, since every idea comes from inside the house. |
| `deps.edn` | The org's deps: waymark10 and factorykit by local root, and nothing else. `clojure -M:check` runs the declaration gate; `clojure -M:conform` the conformance suite. |
| `src/vanilla_plus/org.clj` | The module: `resources` lists the org's one kind and stamps it with the domain `:vanilla_plus`. |
| `src/vanilla_plus/kinds/content.clj` | `content`: idea → designed → building → in_review → live, or shelved. `live_mode` mirrors the content file's `-- Live:` header (reload or restart). |
| `resources/vanilla_plus/seed.edn` | The domain, the owner, the org's mayor (`vanilla_plus-mayor`, sat by Colton, $50 a week, every `content` door), three seats (designer, producer, world-keeper), one each per stage, and the first row, The Chronicler. |
| `README.md` | This file. |

## How a piece moves

| State | Who moves it | With |
| --- | --- | --- |
| idea | designer | `design` (pitch, type, zone, levels, live mode) or `shelve` |
| designed | producer | files a factory ticket for `ckopsa/vanilla-plus`, then `start_build` with its id |
| building | the code seat builds the content file; producer | `submit` (the merged file's path) once `tests` is green |
| in_review | world-keeper | `wow.plan` → `wow.apply` (your tap) → `go_live` with the commit; `wow.restart` (your tap) when the plan says restart; or `send_back` |

The release gate is the `wow.apply` power: it waits for your tap, and
the held call shows the commit and the plan's one-line summary. That
is why `go_live` itself holds no guard.

## In the house: the person's steps

The seed names only this org's kind. These rows belong to the house,
not the seed -- an org seed whose seat named `ticket`, `bench.*` or
`wow.*` would name kinds this org's engine does not serve, and
conformance refuses that (`scope/unknown-kind`). Do them in this order:

1. **The `wow` door's row.** Create an `mcp_server` row (a person's act,
   docs/spec-mcp-servers.md § 2) once the door answers:

   | field | value |
   | --- | --- |
   | name | `wow` |
   | transport | `http` |
   | url | `http://192.168.1.231:8116/mcp/` |
   | powers | the list below |

   ```json
   [
     {"power": "wow.read",    "tools": ["ping", "status", "who", "bots", "loot", "logs", "plan"], "approval": "none"},
     {"power": "wow.apply",   "tools": ["apply"],   "approval": "person", "shown": ["sha", "summary"]},
     {"power": "wow.restart", "tools": ["restart"], "approval": "person", "shown": ["reason"]},
     {"power": "wow.gm",      "tools": ["announce", "give_item", "give_gold"], "approval": "why", "shown": ["character", "item", "gold"]}
   ]
   ```

2. **The repository.** Wire `ckopsa/vanilla-plus` into the factory as
   docs/routines/code-seat.md § "Before the first firing" says: an active
   `repo_policy` row (`repository` `ckopsa/vanilla-plus`,
   `required_checks` `["tests"]`, `test` `{"workflow": "tests.yml",
   "input": "only"}`, `orientation` `docs/orientation.md`), then a seat
   from factorykit's `code-seat` template with `repo`
   `ckopsa/vanilla-plus`, its model and its schedule. The repository
   already holds the repo kit (`.github/workflows/tests.yml`,
   `docs/orientation.md`), and its `tests` check runs on the house's
   own runners.

3. **Widen two seats** once this org is live and steps 1-2 stand:
   - `producer`: add `{"kind": "ticket", "actions": ["create"], "filter": {"repo": "ckopsa/vanilla-plus"}}`.
   - `world-keeper`: add `{"kind": "wow.read", "actions": []}`,
     `{"kind": "wow.apply", "actions": []}`, `{"kind": "wow.restart", "actions": []}`
     and `{"kind": "wow.gm", "actions": []}`.
   - `vanilla_plus-mayor`: the same four `wow.*` entries, so the seat
     Colton sits in can read the world, plan and apply a release (his
     tap), restart (his tap) and act as a GM (with a why).

4. **Go live.** Flip `org.edn` to `:live true` in a pull request; the
   house engine boots live orgs from `WAYMARK10_ORGS_DIR` and runs this
   seed. Give each seat its model and schedule as the other house seats
   have.
