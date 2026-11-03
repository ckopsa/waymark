# Stand up your own factory

Postgres, a factory-only engine, the bench and localfire, in docker on
your own machine, for one person: no public URL, no Keycloak, nobody
else's account. You need docker with the compose plugin and Claude
Code. Work in a clone of `ckopsa/waymark`:

    git clone https://github.com/ckopsa/waymark && cd waymark

## Three steps

1. Start it:

       docker compose -f selfhost/compose.yml up -d

2. Open http://127.0.0.1:8014 and enter the claim code the engine's
   log prints (`docker compose -f selfhost/compose.yml logs engine`
   shows one `claim code:` line), your name (your GitHub user) and your
   repository. Your GitHub repository, as `owner/repo`: the project the
   factory works on and opens pull requests in. It must already exist,
   and the GitHub token you paste next needs write access to it.

3. Clear the page. Each card says what it needs and has one door:
   paste a GitHub token, give localfire your Claude token at
   http://127.0.0.1:8112, run the mayor's one line in a new folder,
   and send the first ticket.

## What the page holds afterwards

When the page says "Nothing waits on you.", code-seat has the first
ticket: it adds the repo kit (`.github/workflows/tests.yml` and
`docs/orientation.md`) to your repository and opens that pull request.
From then on the page holds only what waits on you: a pull request to
merge, a call held for your tap, a draft to send. After the kit's
merge two moves stay yours: edit `tests.yml`'s test command for your
project, and make `tests` a required check on your default branch; a
card says so until it is.

## Back up the keys volume

The `keys` volume holds the data key, the database password, the pull
token, localfire's key and the owner you claimed as. Back it up once: a
database without its data key cannot be read, and
`docker compose -f selfhost/compose.yml down -v` deletes the key with
the database. `selfhost/.env.example` lists the variables you may still
override; none is needed.

The engine and localfire are published on 127.0.0.1 only. The claim
code keeps a second local user or process from becoming owner before
you do; once claimed, the engine trusts this machine as any solo engine
does, and the claim is no wider a boundary than that.

## When it did not

- The engine never answers: read
  `docker compose -f selfhost/compose.yml logs engine`. A seed refusal
  says the engine already has members: the seed loads on a fresh
  database only, so `down -v` (this deletes the database) and `up -d`.
- Five wrong claim codes end the code: restart the engine for a new one.
- Claude Code in the mayor's folder has no Waymark tools: the place's
  `.mcp.json` names the engine; approve its `Waymark` server when
  Claude Code asks (or in `/mcp`), and restart Claude Code. By hand it
  is `claude mcp add --transport http Waymark http://127.0.0.1:8014/api/-/mcp
  -H 'X-Waymark-Principal: claude-code' -H 'X-Waymark-Actor-Type: agent'
  -H 'X-Waymark-Acts-For: <your name>'`: an agent acting for you, as
  the mayor's seat admits, not your own person headers.
- No run after the first ticket's send: read
  `docker compose -f selfhost/compose.yml logs localfire` for a run and
  its refusal. A run that ends at once with a usage-limit line waits
  for the reset.
- A sitting with no pull request: read the change row beside the
  ticket; a stall says in one sentence why.
- A red `tests` is the test command, not the factory: fix it in your
  repository.

This repository's `selfhost` CI job walks these steps on a clean runner
(`selfhost/ci/walk.py`) and fails when they take more than 3 answers,
2 pasted secrets or any hand-written file.
