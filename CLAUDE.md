# Project Instructions for AI Agents

This file provides instructions and context for AI coding agents working on this project.

## Build & Test

Everything runs against one dockerized Postgres on `:5433`
(`make db10` bootstraps the container and databases).

```bash
make test10                # waymark10 framework tests
make test-queue            # the household suite: queue + chores + meals + evenings
make test-calendar         # calendar transport tests
make check-queue           # declaration-time checks + usability warnings (no database)
```

## Architecture Overview

`waymark10/` is the framework: declarative resource definitions (state
machines with guarded transitions), from which routing, serialization,
validation, authorization, live events, documentation, and the conformance
suite are mechanically projected. The application directories
(`mealplan10/`, `eveningplan10/`, `choreplan10/`, `workqueue10/`,
`calendar10/`) are declarations driving that engine. Start with
`README.md`, then `docs/waymark10-design.md` and
`docs/waymark10-vocabulary.md`.

## Conventions & Patterns

- REPL entry point: `waymark10.dev/scratch!`
- Declarations are data; prefer growing the framework over app-local
  workarounds.


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:970c3bf2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

### If `bd dolt push` is refused (restricted agent sessions)

A sandboxed session's git credential may write `refs/heads/*` but not
`refs/dolt/data`; `bd dolt push` then fails with HTTP 403 on the
receive-pack POST while ordinary branch pushes succeed. Do not try to
route around it. Commit the export instead — `.github/workflows/
beads-sync.yml` carries the last hop on a push to `main`:

```bash
bd dolt pull                          # MANDATORY, and immediately before the export
bd export -o .beads/issues.jsonl      # default flags: no --all, no --include-memories
git add .beads/issues.jsonl && git commit && git push
```

**Pull immediately before exporting, not at session start.** `bd import`
upserts, so a stale export does not merely fail to add — it writes old
field values back over newer ones. The pull is what bounds that window
to seconds.

Two standing cautions: the export is a full dump, so keep memories and
infra beads out of a public repo by using the default flags; and a
tracked `issues.jsonl` drifts and merge-conflicts, so treat it as a
transport, not a source of truth.

### If `bd dolt pull` refuses with a merge conflict

Expected in any session that merges an export mid-session and keeps
editing: the beads-sync workflow's import commits your own change onto
a second Dolt history, and a row edited again after the export
conflicts on the next pull — embedded mode has no operator resolution.
Recover by replacing local history, never by merging (your local copy
is the strictly newer one):

```bash
bd export --all -o /tmp/beads-snapshot.jsonl   # keep memories; stays local
rm -rf .beads/embeddeddolt
bd bootstrap                                    # fresh clone of the remote tip
bd import /tmp/beads-snapshot.jsonl && rm /tmp/beads-snapshot.jsonl
```

The session-start hook runs this automatically when its pull refuses.
Never commit the `--all` snapshot — the transport export keeps its
default flags.

`bd` itself is not installed in a fresh sandbox and publishes no release
binaries. Build it with `apt-get install -y libicu-dev` then
`go install github.com/steveyegge/beads/cmd/bd@v1.2.2` — note
`steveyegge`, not the `gastownhall` path linked above, which fails on a
module-path mismatch. Then `bd bootstrap` to hydrate the database, which
a fresh clone does not carry.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->
