# Agent Instructions — engineering this project

This file is for an agent **working on the waymark software**: reading
and changing the code in this repository. The authoritative build,
test, architecture and issue-tracking guide is **`CLAUDE.md`** — read
it first; this file only adds the shell hygiene that keeps a
non-interactive session from hanging.

> **Running a composer *sitting* instead?** That is not software
> engineering and none of this file applies. A sitting never edits the
> repository — it acts on the live house at `https://work.kopsa.info`
> over HTTP. Its whole instruction is **`SITTING.md`**, and the prompt
> that queued you says so. Do not treat this file or `CLAUDE.md` as
> your task.

## Build & Test

See `CLAUDE.md` for the full picture and the `Makefile` for the
targets. The essentials:

```bash
make test10        # waymark10 framework tests
make test-queue    # the household suite (queue + chores + meals + evenings)
make check-queue   # declaration-time checks + usability warnings (no database)
```

Everything runs against one dockerized Postgres on `:5433`
(`make db10` bootstraps it).

## Issue tracking

This project uses **bd (beads)**. Run `bd prime` for the full workflow
and the session-close protocol. Quick reference:

```bash
bd ready              # find available work
bd show <id>          # view an issue
bd update <id> --claim  # claim work
bd close <id>         # complete work
```

Do not commit or push, or run Dolt remote sync, unless the active task
or the user explicitly asks — see the Agent Context Profiles in
`CLAUDE.md`.

## Non-interactive shell hygiene

`cp`/`mv`/`rm` may be aliased to `-i` on some systems and hang waiting
for a y/n. Always force:

```bash
cp -f source dest      # not: cp source dest
mv -f source dest      # not: mv source dest
rm -rf directory       # not: rm -r directory
```

Other prompt-prone commands: `ssh`/`scp` with `-o BatchMode=yes`,
`apt-get -y`, `HOMEBREW_NO_AUTO_UPDATE=1` for `brew`.
