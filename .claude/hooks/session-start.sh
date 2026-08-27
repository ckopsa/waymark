#!/bin/bash
# SessionStart hook: install bd (beads) and hydrate its database in
# Claude Code on the web sandboxes. Local sessions are untouched.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

BD_BIN="$HOME/go/bin/bd"

if [ ! -x "$BD_BIN" ]; then
  # beads needs ICU headers to build; the sandbox image does not carry them
  if ! dpkg -s libicu-dev >/dev/null 2>&1; then
    apt-get install -y libicu-dev >/dev/null
  fi
  # steveyegge, not gastownhall — the gastownhall path fails on a
  # module-path mismatch (see CLAUDE.md)
  go install github.com/steveyegge/beads/cmd/bd@v1.2.2
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo 'export PATH="$PATH:$HOME/go/bin"' >> "$CLAUDE_ENV_FILE"
fi

# Hydrate the Dolt database from refs/dolt/data; a fresh clone does not
# carry it. bootstrap refuses an existing database, so sync with a pull
# instead when one is already there (cached container state).
cd "$CLAUDE_PROJECT_DIR"
if "$BD_BIN" ready --limit 1 >/dev/null 2>&1; then
  if ! "$BD_BIN" dolt pull; then
    # The recurring divergence: the beads-sync workflow's import of
    # this session's own export commits the same change onto a second
    # Dolt history, and a row edited again after the export conflicts
    # on the next pull — with no operator resolution in embedded mode.
    # Recover by REPLACING local history, never merging: snapshot the
    # local database (strictly newer; --all keeps memories through the
    # round trip — the file stays local, never committed), re-clone
    # the remote, upsert the snapshot back on top. The same
    # bootstrap-then-import shape beads-sync.yml itself uses.
    echo "bd dolt pull refused — recovering by re-clone + re-import" >&2
    SNAP="$(mktemp)"
    "$BD_BIN" export --all -o "$SNAP"
    rm -rf "$CLAUDE_PROJECT_DIR/.beads/embeddeddolt"
    "$BD_BIN" bootstrap
    "$BD_BIN" import "$SNAP"
    rm -f "$SNAP"
  fi
else
  "$BD_BIN" bootstrap
fi

"$BD_BIN" ready --limit 3 || true
