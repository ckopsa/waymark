# myorg

An org: a thin directory of declarations the engine boots. This is
the org kit's template. Copy it to `orgs/<org>/` and replace every
`myorg` (directory names, namespaces, the domain, the seed) with your
org's name. The shape and its rules are in `docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps: waymark10 and factorykit by local root, and nothing else. `clojure -M:check` runs the declaration gate. |
| `src/myorg/org.clj` | The module: `resources` lists the org's kinds and stamps them with the domain `:myorg`. |
| `src/myorg/kinds/` | One file per kind: one `defresource`, its guards as `defguard` sentences. |
| `resources/myorg/seed.edn` | What a fresh engine holds at boot: domains, the cast, seats from factorykit templates, judgments and the starting rows. |
| `README.md` | This file: what each file is. |
