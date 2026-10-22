# landscape_rehearsal

The landscaping rehearsal org (plan bba84cf0), rebuilt as the minimal
kinds the calendar film needs (docs/spec-calendar-time.md §9, build
step 10), with its crews as the engine's `team` rows (docs/spec-teams.md
step 11): a booking names its `crew` team and a `lead` who must be on
it, each crew has a crew-lead seat that reads only its own crew's
rows (`:team-of`), and the paving-stone supplier is an outside member.
A `booking` and an approved `time_off` hold a `crew` for a
window through `:occupies`, so the engine writes the crew-is-free and
crew-is-working guards; a `contract` makes its `visit` rows on a
`:recurs` rule. The film's three steps are scenarios on `booking` and
`contract`. A booking's `rain_day` door is its `:occupies` entry's
`:bumps` door: one atomic bulk call, filtered with `where` by
`crew` and a `starts_at` range for the day, moves each of that
crew's scheduled bookings to its next free window on a later business
day, skipping holidays and held slots.

| File | What it is |
| --- | --- |
| `deps.edn` | The org's deps: waymark10 and factorykit by local root, and nothing else. `clojure -M:check` runs the declaration gate; `clojure -M:conform` the conformance suite. |
| `src/landscape_rehearsal/org.clj` | The module: `resources` lists the org's kinds and stamps them with the domain `:landscape_rehearsal`. |
| `src/landscape_rehearsal/kinds/` | One file per kind: `booking`, `time_off`, `contract`, `visit`, `materials_order` (its `supplier` names the outside supplier, whose grant admits only the orders that name them). A crew is a `team` row, which the engine serves. |
| `resources/landscape_rehearsal/seed.edn` | What a fresh engine holds at boot: the domain, the owner, the org's mayor, the crews' people, two crews as teams and a crew-lead seat for each; on a clone, the customer, the bookkeeper and the outside supplier. |
