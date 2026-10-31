# dress_rehearsal

An org: a thin directory of declarations the engine boots. A boutique
firm that prepares federal and state R&D tax credit studies for small
technology companies. The shape and its rules are in `docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps: waymark10 and factorykit by local root, and nothing else. `clojure -M:check` runs the declaration gate. |
| `src/dress_rehearsal/org.clj` | The module: `resources` lists the org's kinds and stamps them with the domain `:dress_rehearsal`. |
| `src/dress_rehearsal/kinds/` | One file per kind: `prospect`, `study`, `invoice`. |
| `resources/dress_rehearsal/seed.edn` | What a fresh engine holds at boot: the domain, the owner, the org's mayor, and the starting rows. |
| `README.md` | This file: what each file is, and the org's assumptions. |

## Assumptions

What the principal has said is settled, and what is still a best guess
for the principal to confirm or change.

| Assumption | Status |
| --- | --- |
| The AI drafts each project's narrative as its engineering records arrive, while the study is still collecting. | Settled by the principal. |
| The specialist, not the AI, decides when a study's cost records are complete; that moves the study to drafting (`records_in` takes `records_complete_on`, a day not in the future, and waits for the person's tap, which records who confirmed it). | Settled by the principal. |
| A missing document is chased twice (`chases_sent`); after the second chase the principal is told (`principal_told_on`). | Settled by the principal. |
| The org's mayor runs day to day on $8 a week. | Settled by the principal. |
| The fee is 20% of the credit, with a $5,000 minimum. | Best guess. |
| The team is the principal and one tax specialist. | Best guess. |
| The tools the firm uses. | Best guess. |
| An invoice is due in 30 days; a reminder goes at 15 days late and the principal calls at 30. | Best guess. |
