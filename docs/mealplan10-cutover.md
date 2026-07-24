# meals.kopsa.info: the mealplan9 → mealplan10 cutover

> **DONE 2026-07-23 — but not by this runbook's window.** The data
> check before the window found the truth had already moved: the
> family had been living in meals10 since the 2026-07-15 seed
> (v9's last human write was 2026-07-13; every v9 count was matched
> or exceeded in v10, and v9's final writes were all present there).
> Running the migration would have DESTROYED a week of family data
> in the name of copying stale data over it. So no migration ran:
> fresh dumps of both databases were taken (forced `mealplan-backup`
> run, 02:17 UTC), then the traefik router simply swapped —
> `meals.kopsa.info` now routes to the `mealplan10` job (alias
> `meals10` kept, `/` redirects to `/api/-/ui`), and the v9 job runs
> unrouted as the rollback anchor until 2026-07-30 (decommission
> tracked in the infra repo's beads). Lesson for the next cutover: a
> staging app the family can reach WILL become the system of record;
> re-verify which side is the truth before any window opens.

The runbook for moving the family app from Python waymark9 to Clojure
waymark10. Everything here is rehearsed except the two prod-only
steps (the backup and the real dry run); the rollback path is the
untouched mealplan9 database + the `mealplan9` branch.

## Before the window (no downtime)

1. **Backups exist and restore.** The cluster's Postgres has no
   automated backups (standing infra gap). At minimum, this window
   does not open without a fresh `pg_dump` of the `mealplan9`
   database, restored once somewhere to prove it. Fixing the standing
   gap (scheduled dumps) is strongly preferred before, not after.
2. **Build and push the image**: `make image10`
   (buildx arm64 → `docker.kopsa.info/mealplan10:<sha>`; qemu binfmt
   must be reinstalled if the build box rebooted:
   `docker run --privileged --rm tonistiigi/binfmt --install arm64`).
3. **Create the `mealplan10` Nomad job** (infra repo), alongside the
   running v9 job — not replacing it:
   - image `docker.kopsa.info/mealplan10:${image_tag}` from the
     `nomad/jobs/mealplan10/deploy` variable (`make deploy10` writes it);
   - env `MEALPLAN10_DSN` (the new `mealplan10` database),
     `MEALPLAN10_PORT=8010`, `MEALPLAN_GCAL_ICS_URL` (the same secret
     the v9 job holds);
   - `WAYMARK10_AUTO_MIGRATE` UNSET — production boots refuse on
     schema drift and name the plan;
   - health check `GET /api/.well-known/waymark`;
   - traefik router for a STAGING hostname first
     (e.g. meals10.kopsa.info), NOT meals.kopsa.info yet. The
     principal model is unchanged (trusted `x-waymark-principal`
     header, same as v9's header_principal) — whatever fronts v9
     fronts v10 identically.
4. **Create the `mealplan10` database** on the cluster Postgres, and
   apply the schema: `MEALPLAN10_DSN=<prod> APPLY=1 clojure -M:migrate`
   (from `mealplan10/`; the plan is additive CREATE TABLEs — same 22
   steps the dev apply ran).
5. **The real residue test** — dry-run the data migration against
   prod (reads only, writes nothing):
   `SOURCE_DSN=<prod mealplan9> MEALPLAN10_DSN=<prod mealplan10>
   clojure -M:migrate-v9`.
   The synthetic rehearsal was clean; this run is the one that sees
   the family's actual documents. Any violation names its row and
   field — extract or declare before the window (the residue rule).

## The window (writes stop, minutes not hours)

6. **Stop the v9 job** (`nomad job stop mealplan`) — the write
   freeze. The v9 database is now the frozen source of truth.
7. **Take the window's own `pg_dump`** of `mealplan9` (the rollback
   anchor: everything after this instant is reproducible).
8. **Run the migration**: step 5's command with `APPLY=1`. It refuses
   a non-empty target, verifies source→target counts per kind, and
   backfills aggregates + the clock index. Events are not copied —
   discovery re-mints the calendar from the ICS feed on first boot.
   One recorded transform rides the copy: v9 plans shed their embedded
   `:days`, and each day births a `plan_day` row whose STATE derives
   from the day's own facts (meal assigned → planned, eating out →
   eating_out, else undecided) — the promoted day machine arrives
   populated, and the plan's day counts backfill with the rest.
9. **Start the v10 job**; browse the staging hostname: the current
   plan, its days, the grocery list totals, a meal's cost line — the
   same numbers v9 was serving.
10. **Repoint meals.kopsa.info** at the v10 service (traefik router
    swap in the infra repo). Repoint the AI client at the same base —
    the tool surface is `GET /api/.well-known/waymark`, as before.

## Rollback (any point in the window)

- Point meals.kopsa.info back at the v9 job and start it (`nomad job
  run mealplan`). The v9 database was never written; nothing to
  restore. Drop and recreate the `mealplan10` database before
  retrying (the migration refuses a non-empty target on purpose).

## After

- Watch the first discovery pass mint the calendar (the event
  collection fills within one `discover-every`).
- Leave the v9 job definition and database in place for at least one
  full week cycle before decommissioning; the `mealplan9` branch
  stays as the code side of the rollback pair.
