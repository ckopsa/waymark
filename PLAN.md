# Off-laptop conformance runs (waymark-conformance Nomad job)

Runs the full `pytest` + conformance suite on the home cluster's ARM64 node
instead of the dev laptop. Two repos:

- **waymark** (`~/dev/waymark`) — source, tests, `Dockerfile.test`, the
  `test-image` Make target.
- **home-infrastructure** (`~/dev/home-infrastructure`) — the Nomad job
  `terraform/nomad-jobs/waymark-conformance.hcl`, registered by Terraform.

## Files this adds

| File | Repo | Purpose |
|------|------|---------|
| `Dockerfile.test` | waymark | Test image: full source + `tests/` + dev/test deps. |
| `Dockerfile.test.dockerignore` | waymark | Keeps `tests/`+`app/` in the build context (prod `.dockerignore` drops them). |
| `Makefile` `test-image` target | waymark | Build+push the arm64 test image, set the job's image-tag nomad var. |
| `terraform/nomad-jobs/waymark-conformance.hcl` | home-infrastructure | The batch+periodic job. |

## 1. Build + push the test image

```bash
cd ~/dev/waymark
make test-image
```

This runs (tag = `git rev-parse --short HEAD`, `-dirty` if the tree is dirty):

```bash
docker buildx build --platform linux/arm64 -f Dockerfile.test \
  -t docker.kopsa.info/waymark-test:<tag> --push .
nomad var put -force nomad/jobs/waymark-conformance/deploy image_tag=<tag>
```

Cross-arch build needs qemu binfmt once:
`docker run --privileged --rm tonistiigi/binfmt --install arm64`.
`NOMAD_ADDR`/`NOMAD_TOKEN` come from
`~/dev/home-infrastructure/terraform/secrets.local.json` (same as `make deploy`).

## 2. Register the job (Terraform)

`terraform/main.tf` auto-discovers every `nomad-jobs/*.hcl` via `fileset` and
registers it as a `nomad_job`. So registration is just:

```bash
cd ~/dev/home-infrastructure/terraform
terraform plan     # should show one new resource: nomad_job.jobs["waymark-conformance.hcl"]
terraform apply
```

No new `nomad_variable` is needed — the job uses trust-auth Postgres (no
secret) and reads its image tag from `nomad/jobs/waymark-conformance/deploy`,
which is owned by `nomad var put` (via `make test-image`), not Terraform,
exactly like `nomad/jobs/mealplan/deploy`.

> Order: run `make test-image` **before** the first scheduled run so the
> deploy var exists; otherwise the image ref renders as
> `docker.kopsa.info/waymark-test:` and the pull fails.

## 3. Run it

- **Nightly**: the `periodic { crons = ["0 4 * * *"] }` stanza fires
  automatically; `prohibit_overlap = true` prevents a slow night stacking two.
- **On demand**:

  ```bash
  nomad job periodic force waymark-conformance
  ```

## 4. Where results land

- Per-suite pass/fail is in the `pytest` task's **alloc logs**:

  ```bash
  nomad job status waymark-conformance          # find the latest/child alloc
  nomad alloc logs -job waymark-conformance pytest
  nomad alloc logs <alloc-id> pytest
  ```

- The job **exits non-zero if any suite failed**, so a failed alloc = a red
  run in `nomad job status`. The `run.sh` entrypoint runs every suite even
  after one fails (so you see all results) and returns 1 at the end.

## Design notes

- **Serial suites / one xdist run at a time.** `run.sh` runs the framework
  suite then `--waymark`, `--waymark3`…`--waymark7` sequentially (mirrors the
  Makefile targets). Concurrent xdist runs collide on per-worker DB names
  (`waymark_test_gwN`), so they must never overlap — hence serial + periodic
  `prohibit_overlap`.
- **Ephemeral Postgres.** A `postgres:16` prestart **sidecar** in the same
  group, bridge-networked so the pytest task reaches it at `127.0.0.1:5432`.
  `POSTGRES_HOST_AUTH_METHOD=trust`, user `ckopsa`, db `waymark_test`; the
  suite's `per_worker_dsn` creates `waymark_test_gwN` per worker. Nothing is
  bind-mounted — the DB dies with the alloc. It is **never** the prod mealplan
  Postgres.
- **arm64.** `constraint ${attr.cpu.arch} == arm64`; image built
  `--platform linux/arm64`; `postgres:16` is multi-arch.
- **Failure surfaces** via the task's non-zero exit + `restart/reschedule
  attempts = 0` (a failed run stays failed).

## Open questions / flagged assumptions

- **Registry auth.** `mealplan.hcl` pulls `docker.kopsa.info/...` with no
  `auth` block, so the node already has registry creds (or the registry is
  open in-cluster). This job mirrors that. If pulls fail with 401, add an
  `auth` block or docker `config.json` the same way mealplan would need it.
- **Postgres user `ckopsa`.** Chosen to match the dev DSN shape in the memory
  notes; the value is arbitrary for a throwaway trust-auth DB (only needs
  CREATEDB, which the superuser role has).
- **Resources.** `pytest` task gets 2 CPU / 2 GB; `-n auto` will fan out to
  the node's core count. Tune `cpu`/`memory` if the orangepi5plus is tight or
  the run OOMs.
- **`--platform linux/arm64` build not validated on this host** (can't build
  arm64 here reliably). The Dockerfile mirrors the working prod Dockerfile's
  base and idioms. `nomad job validate` on the HCL passed.

## Real-run adjustments (first live run, 2026-07-09)

Five fixes were needed once the job actually ran on the cluster:

1. **Memory oversubscription.** The node is reservation-bound (~30/31 GiB
   reserved, but ~22 GiB actually free). Enabled cluster-wide with
   `nomad operator scheduler set-config -memory-oversubscription=true`
   (safe: jobs without `memory_max` are unaffected), and the tasks now
   reserve small / burst large via `memory` + `memory_max`.
2. **ACL for the deploy var.** A workload identity can't read
   `nomad/jobs/<self>/deploy` by default (the implicit policy only covers
   group/task paths), so `nomad_acl_policies.tf` gained a
   `waymark-conf-deploy` entry — same as `mealplan-deploy-vars`.
3. **Postgres readiness via asyncpg.** The slim test image has no
   `pg_isready`; `run.sh` probes with a real asyncpg connect instead.
4. **`COPY tests/ tests/`** — was missing from `Dockerfile.test`, so
   pytest collected 0 items ("No files were found in testpaths").
5. **`force_pull = true`** on the pytest task — the git-short image tag is
   mutable, so a re-pushed fix must be pulled, not served from cache.

First green run: framework suite `1636 passed, 1 skipped in 702.85s`.
