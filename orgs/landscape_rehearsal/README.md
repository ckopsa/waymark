# landscape_rehearsal

The landscaping rehearsal org (plan bba84cf0), rebuilt as the minimal
kinds the calendar film needs (docs/spec-calendar-time.md §9, build
step 10). A `booking` and an approved `time_off` hold a `crew` for a
window through `:occupies`, so the engine writes the crew-is-free and
crew-is-working guards; a `contract` makes its `visit` rows on a
`:recurs` rule. The film's three steps are scenarios on `booking` and
`contract`.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps: waymark10 and factorykit by local root, and nothing else. `clojure -M:check` runs the declaration gate; `clojure -M:conform` the conformance suite. |
| `src/landscape_rehearsal/org.clj` | The module: `resources` lists the org's kinds and stamps them with the domain `:landscape_rehearsal`. |
| `src/landscape_rehearsal/kinds/` | One file per kind: `crew`, `booking`, `time_off`, `contract`, `visit`. |
| `resources/landscape_rehearsal/seed.edn` | What a fresh engine holds at boot: the domain, the owner, the org's mayor, and two crews. |
