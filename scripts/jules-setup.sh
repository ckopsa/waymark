#!/usr/bin/env bash
# Jules environment setup (run once at snapshot build): everything a
# sitting run needs. The queued job's own instructions live in
# AGENTS.md — this script only makes the tools real.
set -euo pipefail

sudo() { command sudo "$@" 2>/dev/null || "$@"; }

# curl + jq: the door and its wire
command -v jq >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq jq curl; }

# bd (beads): the formula/molecule runner. A CGO build from source —
# slow once, cached in the snapshot forever. steveyegge, not
# gastownhall: the module declares itself steveyegge and go refuses
# the mismatch.
if ! command -v bd >/dev/null && [ ! -x "$HOME/go/bin/bd" ]; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq libicu-dev golang-go
  go install github.com/steveyegge/beads/cmd/bd@v1.2.2
fi
export PATH="$PATH:$HOME/go/bin"

# A local beads database for wisps (vapor-phase, never synced): the
# remote clone when the credential allows it, a fresh init when not —
# a sitting's molecule needs any database, not this repo's history.
if ! bd ready >/dev/null 2>&1; then
  bd bootstrap || bd init || true
fi

bd cook sitting --dry-run >/dev/null 2>&1 \
  && echo "setup ok: bd ready, sitting formula cooks" \
  || echo "setup warning: bd or the formula is unavailable — a run can still follow .beads/formulas/sitting.formula.toml by reading it directly"
