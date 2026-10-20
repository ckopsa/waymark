# Stand up your own factory

This file is written for Claude Code to follow, step by step, on the
person's own machine. Paste its link into Claude Code and say "follow
this". When a step's check fails, stop at that step, do what its
"When it did not" says, and run the check again before going on.

What it ends with: Postgres, a factory-only engine and the bench in
docker on this machine (`selfhost/compose.yml`), localfire asking that
engine for its fires by pull on the person's own Claude token, the
repo kit in the person's repository, a mayor folder, and a first ticket
that draft-judge grooms and code-seat turns into a pull request. No
public URL, no Keycloak, nobody else's account.

Work in a clone of `ckopsa/waymark`:

    git clone https://github.com/ckopsa/waymark && cd waymark

## 1. Prerequisites

The person needs:

- docker with the compose plugin;
- Claude Code, signed in;
- a fine-grained GitHub token on their repository, with the scopes
  `docs/credential.md` in `ckopsa/waymark-bench` lists (contents,
  pull requests and actions, read and write). Ask them to make it and
  paste it; never print it back.

Check: `docker compose version` and `claude --version` both answer.

When it did not: install the missing one and open a new shell.

## 2. The engine, the database and the bench

Ask the person for their GitHub user (their member id here), their
repository as `owner/repo`, and the model their seats run on (the
default is `claude-opus-5`). Then:

    cp selfhost/.env.example selfhost/.env

and fill `selfhost/.env` in:

- `POSTGRES_PASSWORD` and `WAYMARK_PULL_TOKEN`: two fresh random
  strings, `openssl rand -base64 24 | tr '+/' '-_' | tr -d '='`;
- `WAYMARK_DATA_KEY`: a fresh key, `openssl rand -base64 32`, kept
  as long as the database is;
- `WAYMARK_OWNER`: their GitHub user;
- `WAYMARK_REPO`: their `owner/repo`;
- `WAYMARK_MODEL`: the model they named;
- `BENCH_GIT_TOKEN`: the token from step 1.

Leave the rest as it is. Then:

    docker compose -f selfhost/compose.yml up -d

and wait for the well-known door, at most three minutes:

    until curl -fsS http://127.0.0.1:8014/api/.well-known/waymark >/dev/null; do sleep 5; done

Check: the curl answers 200, and
`docker compose -f selfhost/compose.yml ps` shows `postgres`, `engine`
and `bench` running.

When it did not: read `docker compose -f selfhost/compose.yml logs engine`.
A `set X in .env` line names the variable left empty. A seed refusal
says the engine already has members: the factory seed loads on a fresh
database only, so `docker compose -f selfhost/compose.yml down -v`
(this deletes the database) and `up -d` again.

## 3. Connect Claude Code to the engine

The engine is solo: its dev headers are its only identity, and it is
published on 127.0.0.1 only. Add it as the person, with the owner from
`.env`:

    claude mcp add --transport http Waymark http://127.0.0.1:8014/api/-/mcp \
      --header "X-Waymark-Principal: <WAYMARK_OWNER>" \
      --header "X-Waymark-Actor-Type: human"

Restart Claude Code so the tools load.

Check: `waymark_discover` answers with the owner as its principal and
lists the kinds `ticket`, `seat` and `runner_link`.

When it did not: `claude mcp list` shows the server's status. A
principal that is not the owner means the header is misspelled; remove
it (`claude mcp remove Waymark`) and add it again.

## 4. localfire, on the person's own Claude token

Make a long-lived token for the person's Claude account:

    claude setup-token

and keep what it prints as `CLAUDE_CODE_OAUTH_TOKEN`. Find the pull
link the seed made: `waymark_query` kind `runner_link` with
`fields: ["delivery"]` and no filter (the published engine image may
predate the `delivery` filter), and keep the one row whose `delivery`
is `pull`; its id is the link id.

Write `selfhost/localfire.edn` (it holds no secret):

    {:port 8112
     :public-url "http://127.0.0.1:8112"
     :place "/srv/waymark-seat"
     :runs-dir "/data/runs"
     :mcp {:name "Waymark"
           :url "http://engine:8014/api/-/mcp"
           :headers {"X-Waymark-Principal"  "claude"
                     "X-Waymark-Actor-Type" "agent"
                     "X-Waymark-Acts-For"   "<WAYMARK_OWNER>"}}
     :routines {"seats" {:model "<WAYMARK_MODEL>" :max-concurrent 1}}
     :pull {:engine-url "http://engine:8014"
            :link-id "<the link id>"
            :routine "seats"}}

Then start it on compose's network, so it reaches the engine by name:

    set -a; . selfhost/.env; set +a
    docker run -d --name waymark-localfire --restart unless-stopped \
      --network waymark-factory_default \
      -p 127.0.0.1:8112:8112 \
      -v waymark-localfire:/data \
      -v "$PWD/selfhost/localfire.edn:/etc/localfire/localfire.edn:ro" \
      -e LOCALFIRE_PULL_TOKEN="$WAYMARK_PULL_TOKEN" \
      -e CLAUDE_CODE_OAUTH_TOKEN="<the setup-token>" \
      ghcr.io/ckopsa/localfire:latest

The pull token is the same `WAYMARK_PULL_TOKEN` the seed gave the link.

Check: `docker logs waymark-localfire` shows the server started and no
config refusal, and `waymark_get` on the runner link shows it is not
broken.

When it did not: a config refusal is one sentence naming the key to
fix in `localfire.edn`; fix it and `docker restart waymark-localfire`.
A 401 from the engine means the pull token differs from `.env`. A
network error naming `engine` means the network name is wrong:
`docker network ls` shows compose's.

### localfire on another machine

A laptop, or any machine off compose's network, runs localfire with
one command and no `localfire.edn`: make a pull link for it, export its
token as `LOCALFIRE_PULL_TOKEN`, and run
`localfire pull --engine <engine URL> --link <link id>`. The steps and
the flags are in `localfire/README.md`, "Run it on a laptop (pull)".

## 5. The repo kit

The factory tests and orients in the person's repository through what
`selfhost/repo-kit` holds: `.github/workflows/tests.yml` (one job named
`tests`, dispatched with an `only` input) and `docs/orientation.md`.
Clone their repository beside this one, copy the kit in, and open a
pull request:

    git clone https://github.com/<WAYMARK_REPO> ../my-repo
    cp -r selfhost/repo-kit/. ../my-repo/
    cd ../my-repo && git checkout -b waymark-repo-kit
    git add .github/workflows/tests.yml docs/orientation.md
    git commit -m "Add the waymark repo kit" && git push -u origin waymark-repo-kit
    gh pr create --fill

Ask the person to edit `tests.yml`'s test command for their project
and `docs/orientation.md` to say what the repository is, then to merge
it, and to make `tests` a required check on their default branch: it
is what the merge waits on.

Check: the pull request is merged and the `tests` workflow ran green
on `main`.

When it did not: a red `tests` is the test command, not the factory;
fix it in their repository. A missing `gh` is fine: open the pull
request from the URL `git push` prints.

This repository's own selfhost walk, before it reads the fixture
repository, prints the fixture token's kind (from its prefix), its
length, whether whitespace surrounded it, and the HTTP status GitHub's
API answers it for the repository and its README, never the token; a
status other than 200 there fails the step naming that status.

## 6. The mayor folder

The mayor is the person's own seat: it tells them what waits on them.
Make a folder outside both repositories, in the shape of
`ckopsa/waymark-mayor`:

    mkdir -p ../my-mayor
    cp seat/inbox-feed.sh ../my-mayor/ && chmod +x ../my-mayor/inbox-feed.sh

Read the mayor seat (`waymark_query` kind `seat` with
`{name: "mayor"}`, then `waymark_get` on it) for its id and its
`sitter_key`, and write `../my-mayor/CLAUDE.md`:

    # Mayor

    Call waymark_sit with key `<sitter_key>`, seat `mayor`, and this
    session's id. Then follow the charter the sit answers. Watch the
    sit's `inbox` with a Monitor running
    `./inbox-feed.sh <inbox url> <inbox key>`.

The key is a secret: this folder is not a repository and is not
pushed.

Check: `claude` in `../my-mayor` sits in the mayor seat and answers
its charter.

When it did not: a refused key is a stale one; read the seat again.

## 7. The first ticket

From the mayor's session, or this one, file a small ticket on the
person's repository: `waymark_invoke` kind `ticket`, action `create`,
input `{title, detail, type: "feature", priority: 3, repo:
"<WAYMARK_REPO>"}`. A one-line change to their README is enough.

Then watch, with `waymark_in_flight`:

1. draft-judge grooms it: the ticket moves from `draft` to `open`;
2. that fires code-seat through the pull link: `docker logs
   waymark-localfire` shows a run, and a sitting opens;
3. code-seat submits: a pull request opens on the person's
   repository, and the mayor says it is waiting on their merge.

Check: the pull request exists on their repository.

When it did not: a ticket still in `draft` after the judge's cadence
means draft-judge did not fire; read `docker logs waymark-localfire`
for a run and its refusal. A run that ends at once with a usage-limit
line waits for the reset. A sitting with no pull request: read the
change row beside the ticket; a stall says in one sentence why.
