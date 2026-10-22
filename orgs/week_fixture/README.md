# week_fixture

The fixture org `workqueue10.week-test` plays (ticket f084a59c): a
gardener's office whose homeowners ask for jobs, whose office quotes a
job once it says its budget, and whose owner's tap books it.

- `org.edn`: the manifest. Not live: no engine serves it but a test's.
- `src/week_fixture/org.clj`: the module.
- `src/week_fixture/kinds/job.clj`: a homeowner's job.
- `src/week_fixture/kinds/supply.clj`: a supply order one staff member
  packs and another checks (`:stamp` and `:not-by`).
- `resources/week_fixture/seed.edn`: the owner, the office's seat, and
  the rehearsal's homeowner and its signing secret, a `[:param ...]`
  the week fills with a stand-in, and its two staff and the two supply
  orders a week names by their `:ref`.
- `resources/week_fixture/week.edn`: the first week as a script
  (docs/orgkit.md § The week): one call refused by a guard, one held
  for the owner and allowed the same day, and a job booked.

`cd workqueue10 && clojure -M -m workqueue10.week ../orgs/week_fixture`
prints its report.
