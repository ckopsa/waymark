# clinic_rehearsal

An org: a small outpatient physical therapy clinic. A physician's
`referral` (the prescription) opens a `patient` chart at intake; a
therapist writes a `plan_of_care`; each `visit` is booked from the
plan, noted and signed; each visit becomes an insurer `claim`.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps: waymark10 and factorykit by local root. `clojure -M:check` runs the declaration gate; `clojure -M:conform` the conformance suite. |
| `src/clinic_rehearsal/org.clj` | The module: `resources` lists the org's kinds and stamps them with the domain `:clinic_rehearsal`. |
| `src/clinic_rehearsal/kinds/` | One file per kind: one `defresource`, its guards as `defguard` sentences. |
| `resources/clinic_rehearsal/seed.edn` | What a fresh engine holds at boot: the domain, the clinic's team, the mayor, and the outside world (physicians, patients, insurers) a clone plays. |
| `README.md` | This file. |
