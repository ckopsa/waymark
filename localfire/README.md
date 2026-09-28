# localfire

A server on a machine of the house that answers the engine's fire the
way a cloud Routine's fire endpoint does (`docs/spec-local-fire.md`).

## The image

`Dockerfile.localfire` at the repo root builds it, and
`.github/workflows/localfire.yml` builds it in CI for linux/arm64 on
every push that touches `localfire/**`, `seat/**`,
`Dockerfile.localfire` or the workflow. A pull request only builds it
and checks `claude --version` and the seat place inside it. A push to
`main` also pushes `ghcr.io/ckopsa/localfire:<short sha>` (and
`latest`) and writes `IMAGE_TAG=<short sha>` to the Nomad variable
`nomad/jobs/localfire/deploy`. CI restarts nothing.

The image holds:

- the server, run from source: `clojure -M:serve /etc/localfire/localfire.edn`
  from `/app/localfire`, on port 8111;
- the Claude Code CLI, pinned by `CLAUDE_CODE_VERSION` in the Dockerfile;
- the seat place, copied from the repo's `seat/` to `/srv/waymark-seat`.

## Runtime

It runs as the user `localfire`, uid 1500, whose HOME is `/data`. The
job mounts a host volume there, owned by uid 1500:

- `/data/.claude*`: the Claude login, made once with `claude` inside
  the container;
- `/data/runs`: the runs directory.

The config, `/etc/localfire/localfire.edn`, comes from the Nomad job's
template (see `localfire.example.edn` for its shape), and the bearer
from `LOCALFIRE_TOKEN` in the job's environment.
