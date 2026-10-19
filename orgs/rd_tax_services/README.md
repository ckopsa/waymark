# rd_tax_services

A boutique R&D tax credit firm, as an org. Built from the org kit's
template; the shape and its rules are in `docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps. `clojure -M:check` runs the declaration gate. |
| `src/rd_tax_services/org.clj` | The module: the org's kinds, stamped with the domain `:rd_tax_services`. |
| `src/rd_tax_services/kinds/prospect.clj` | A prospect: new, qualified, engaged (letter signed) or declined. |
| `src/rd_tax_services/kinds/study.clj` | One study per client per tax year: collecting, drafting, review, signed_off, delivered. |
| `src/rd_tax_services/kinds/invoice.clj` | The fee for a delivered study: open, paid or written_off. |
| `src/rd_tax_services/kinds/referring_cpa.clj` | A referring CPA on file, the fee owed them; they receive the study. |
| `src/rd_tax_services/kinds/assumption.clj` | A best guess (fee, team, tools, reminders) the principal confirms. |
| `resources/rd_tax_services/seed.edn` | Domain, cast, seats and the starting rows. |
