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
  "$BD_BIN" dolt pull || echo "warning: bd dolt pull failed; database may be stale" >&2
else
  "$BD_BIN" bootstrap
fi

"$BD_BIN" ready --limit 3 || true
