# week_fixture

The fixture org `workqueue10.week-test` plays (ticket f084a59c): a
gardener's office whose homeowners ask for jobs, whose office quotes a
job once it says its budget, and whose owner's tap books it.

- `org.edn`: the manifest. Not live: no engine serves it but a test's.
- `src/week_fixture/org.clj`: the module.
- `src/week_fixture/kinds/job.clj`: the one kind.
- `resources/week_fixture/seed.edn`: the owner, the office's seat, and
  the rehearsal's homeowner.
- `resources/week_fixture/week.edn`: the first week as a script
  (docs/orgkit.md § The week): one call refused by a guard, one held
  for the owner and allowed the same day, and a job booked.

`cd workqueue10 && clojure -M -m workqueue10.week ../orgs/week_fixture`
prints its report.
