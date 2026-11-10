# The server core's layers

Six namespaces carry most of the engine: `waymark10.server.invoke`,
`grants`, `seats`, `held-calls`, `router` and `mcp`. This page draws how
they require one another, names the direction they are meant to go, and
says how CI holds them to it.

## The graph today

The direct requires among the six, read from their `ns` forms:

```
invoke      -> (none of the six)
grants      -> invoke
seats       -> grants, invoke
held-calls  -> grants, invoke
router      -> invoke, grants, held-calls, seats
mcp         -> invoke, grants, seats, router
```

Read by their `ns` forms alone the six make no cycle. The cycle is in
the bodies: a `requiring-resolve` loads a namespace at call time, and
through the namespaces it reaches it comes back up into the core.

## The intended direction

Bottom first; each layer may require only the ones below it:

```
store -> invoke -> grants -> seats -> held-calls -> router -> mcp
```

`seats` and `held-calls` do not require each other today; `seats` sits
below because `router` and `mcp` read both and nothing in `held-calls`
needs to be beneath a seat.

## Back-edges that stand today

A back-edge is a reach from one layer to a layer above it: directly, by
a `requiring-resolve` or `require` in a body, or through any other
`waymark10` namespace. Two stand. They are not fixed here; the split
tickets remove them.

| From | To | Caused by | Path |
|---|---|---|---|
| `invoke` | `grants` | `make-ctx`'s `(requiring-resolve 'waymark10.server.replay/history)`, with `replay/window` and `replay/*tx*` | invoke -> replay -> measure -> grants |
| `seats` | `held-calls` | two `(requiring-resolve 'waymark10.server.replay-walks/replay)` calls | seats -> replay-walks -> waiting-on -> cracks -> held-calls |

## How CI holds it

`waymark10.server-layers-test` (in `waymark10/test`, so `discover` puts
it in a `test10` shard and `gate` reads it) reads the `ns` form and the
body requires of every namespace under `waymark10/src/waymark10`, finds
every back-edge among the six layers, and fails on:

- a back-edge that `waymark10/test/waymark10/server_layers_allowlist.edn`
  does not name, and
- an entry in that allowlist that is no longer a back-edge.

So the allowlist only shrinks: a change that removes a back-edge
removes its line, and a change that adds one turns `gate` red.
