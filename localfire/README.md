# localfire

A server on a machine of the house that answers the engine's fire the
way a cloud Routine's fire endpoint does (`docs/spec-local-fire.md`).

## The image

`Dockerfile.localfire` at the repo root builds it, and
`.github/workflows/localfire.yml` builds it in CI for linux/amd64 and
linux/arm64 (one multi-arch manifest) on
every push that touches `localfire/**`, `seat/**`,
`Dockerfile.localfire` or the workflow. A pull request only builds it
and checks `claude --version` and the seat place inside it. A push to
`main` also pushes `ghcr.io/ckopsa/localfire:<short sha>` (and
`latest`) and writes `IMAGE_TAG=<short sha>` to the Nomad variable
`nomad/jobs/localfire/deploy`. CI restarts nothing.

The image holds:

- the server, run from source: `clojure -M:serve /etc/localfire/localfire.edn`
  from `/app/localfire`, on port 8112 (`http://192.168.1.231:8112` on
  the Nomad node big-colt);
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

## Run it on a laptop (pull)

A laptop behind a home router or a cafe's network cannot take the
engine's POST. With a pull link it opens one connection out to the
engine and asks for its fires (`docs/spec-local-fire.md` §13); a closed
lid leaves the fire waiting, not broken.

1. Make a pull link: `runner_link` `create` with `delivery: "pull"`,
   by MCP (`waymark_invoke` kind `runner_link`, action `create`) or by
   HTTP. Its answer shows the link's id, and its token (`fire_token`)
   one time: copy the token then.
2. Sign Claude Code in on the laptop (`claude`, or
   `claude setup-token` and `CLAUDE_CODE_OAUTH_TOKEN`), and add the
   engine's MCP door as `Waymark` (`claude mcp add`).
3. Run the one command, from `localfire/`:

        export LOCALFIRE_PULL_TOKEN=<the link's token>
        clojure -M:serve pull --engine https://work.kopsa.info --link <link id> \
          --place ~/waymark-seat

   or with the image, which holds the seat place:

        docker run --rm -it -v localfire:/data \
          -e LOCALFIRE_PULL_TOKEN -e CLAUDE_CODE_OAUTH_TOKEN \
          ghcr.io/ckopsa/localfire:latest \
          pull --engine https://work.kopsa.info --link <link id>

It needs no EDN file and runs in the foreground. It prints
`localfire pull connected to ...` when the engine answers, and
`localfire pull waiting: ...` when it stops answering; it keeps trying
and prints connected again when the engine is back.

The flags:

- `--engine` (required): the engine's base URL;
- `--link` (required): the pull runner link's id;
- `--place`: the seat place, a clone of `seat/` with the hook
  (default `/srv/waymark-seat`, the image's own);
- `--runs-dir`: where run records live (default `~/.localfire/runs`);
- `--port`: the run pages' port (default 8112);
- `--model`: the model every fire runs (default `opus`);
- `--mcp-url`: the engine's MCP door (default `<engine>/api/-/mcp`);
- `--claude`: the Claude Code binary (default `claude`).

The token is read from `LOCALFIRE_PULL_TOKEN` and from nowhere else.
An EDN config with a `:pull` block (see `localfire.example.edn`) still
works as before, for anything the flags do not reach.
