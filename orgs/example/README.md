# example

An org: a thin directory of declarations the engine boots. This is
the org kit's example, a filled copy of `orgkit/template/`: a tiny
landscaping office whose `request` becomes a `work_order`, with one
seat. The shape and its rules are in `docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps: waymark10 and factorykit by local root, and nothing else. `clojure -M:check` runs the declaration gate. |
| `src/example/org.clj` | The module: `resources` lists the org's kinds and stamps them with the domain `:example`. |
| `src/example/kinds/` | One file per kind: one `defresource`, its guards as `defguard` sentences. |
| `resources/example/seed.edn` | What a fresh engine holds at boot: domains, the cast, its one seat, judgments and the starting rows. |
| `README.md` | This file: what each file is. |
