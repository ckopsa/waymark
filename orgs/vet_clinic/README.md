# vet_clinic

A veterinary clinic that answers business inquiries arriving by email.
The shape and its rules are in `docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `deps.edn` | waymark10 and factorykit by local root. `clojure -M:check` runs the declaration gate; `clojure -M:conform` the conformance suite. |
| `org.edn` | The manifest. |
| `src/vet_clinic/org.clj` | The module: `resources` lists the org's kinds under the domain `:vet_clinic`. |
| `src/vet_clinic/kinds/inquiry.clj` | One emailed inquiry: answered on its own, held for the owner's tap, handed to staff, or set aside. |
| `src/vet_clinic/kinds/clinic_fact.clj` | One fact the replies may state: hours, address, directions, services, booking, prices. |
| `resources/vet_clinic/seed.edn` | Domains, the cast, the replies seat, the outside world and the starting facts. |
