# dress_rehearsal

An org: a thin directory of declarations the engine boots. A boutique
firm that prepares federal and state R&D tax credit studies for small
technology companies. The shape and its rules are in `docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps: waymark10 and factorykit by local root, and nothing else. `clojure -M:check` runs the declaration gate. |
| `src/dress_rehearsal/org.clj` | The module: `resources` lists the org's kinds and stamps them with the domain `:dress_rehearsal`. |
| `src/dress_rehearsal/kinds/` | One file per kind: `prospect`, `study`, `invoice`. |
| `resources/dress_rehearsal/seed.edn` | What a fresh engine holds at boot: the domain, the owner, and the starting rows. |
| `README.md` | This file: what each file is. |
