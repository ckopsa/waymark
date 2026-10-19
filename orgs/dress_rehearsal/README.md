# dress_rehearsal

An R&D tax credit study firm, from the orgkit template. The shape and
its rules are in `docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps. `clojure -M:check` runs the declaration gate. |
| `org.edn` | The org's name and whether it is live. |
| `src/dress_rehearsal/org.clj` | The module: the org's kinds, stamped with the domain `:dress_rehearsal`. |
| `src/dress_rehearsal/kinds/` | referrer, engagement, study, document_request, invoice, assumption. |
| `resources/dress_rehearsal/seed.edn` | The cast, the open assumptions and a first client. |
