#!/usr/bin/env bash
# Jules environment setup (run once at snapshot build): everything a
# sitting run needs, and a loud check that the credential the owner
# pasted into the web UI actually mints. The queued job's own
# instructions live in SITTING.md — this script only makes the tools
# real and tells the truth about what is missing.
#
# NOTHING here is allowed to fail the build. A Jules VM that cannot
# install bd can still run a sitting (scripts/sitting-run.sh does the
# mechanical half with jq and curl alone, and the formula TOML is
# readable prose); a VM whose credentials are wrong CANNOT, and that
# is the one line worth shouting about — two sittings before this
# check existed reached the door and left no footprint, because the
# mint was refused and nothing downstream said so.
#
# What a fresh Ubuntu Jules VM already has, measured 2026-08-27:
# jq, curl, bash 5.2, go (/usr/local/go/bin/go). What it does NOT
# have: bd.
set -uo pipefail   # NOT -e: every rung below degrades rather than dies

say() { printf '%s\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }
maybe_sudo() { if have sudo; then sudo "$@" || return 1; else "$@" || return 1; fi; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$PATH:$HOME/go/bin:/usr/local/go/bin"

# ── the wire: curl and jq are the only hard needs ────────────────────
if ! have jq || ! have curl; then
  maybe_sudo apt-get update -qq
  maybe_sudo apt-get install -y -qq jq curl
fi
have jq && have curl \
  && say "setup: jq and curl ready" \
  || say "SETUP FAILURE: jq/curl are missing and apt could not install them — scripts/sitting-run.sh cannot run without them"

# ── bd (beads): the formula runner, and strictly optional ────────────
# A CGO build from source — slow once, cached in the snapshot forever.
# steveyegge, not gastownhall: the module declares itself steveyegge
# and go refuses the mismatch.
if ! have bd; then
  maybe_sudo apt-get update -qq
  maybe_sudo apt-get install -y -qq libicu-dev
  if have go; then
    say "setup: building bd from source (a few minutes, once)"
    go install github.com/steveyegge/beads/cmd/bd@v1.2.2 \
      || say "setup: bd did not build — a run follows .beads/formulas/sitting.formula.toml by reading it instead"
  else
    say "setup: no go toolchain, so no bd — a run reads the formula TOML instead"
  fi
fi

# A local beads database for wisps (vapor-phase, never synced): the
# remote clone when the credential allows it, a fresh init when not —
# a sitting's molecule needs any database, not this repo's history.
if have bd && ! bd ready >/dev/null 2>&1; then
  bd bootstrap >/dev/null 2>&1 || bd init >/dev/null 2>&1 || true
fi

if have bd && bd cook sitting --dry-run >/dev/null 2>&1; then
  say "setup: bd ready, the sitting formula cooks"
else
  say "setup: bd or the formula is unavailable — this is FINE. A run still calls scripts/sitting-run.sh for the mechanical half and reads .beads/formulas/sitting.formula.toml for the walk."
fi

# ── the credential, checked HERE so a bad paste is loud ──────────────
# This is the check whose absence cost two silent sittings. It writes
# nothing to the engine and never prints a secret.
say ""
say "── credential check ──"
missing=""
for v in WAYMARK_KC_CLIENT_ID WAYMARK_KC_CLIENT_SECRET WAYMARK_GRANT_ID; do
  [ -n "${!v:-}" ] || missing="$missing $v"
done
if [ -n "$missing" ]; then
  say "SETUP FAILURE: unset in this environment:$missing"
  say "  Set them in the Jules web UI (Environment > Variables). Without them a sitting mints nothing, reaches nothing, and leaves no footprint — exactly the silence to avoid."
else
  say "env: WAYMARK_KC_CLIENT_ID=${WAYMARK_KC_CLIENT_ID} WAYMARK_GRANT_ID=${WAYMARK_GRANT_ID} (secret present, ${#WAYMARK_KC_CLIENT_SECRET} chars)"
  if TOKEN="$("$HERE/agent-bearer.sh" 2>&1)" && [ "${TOKEN:0:2}" = "ey" ]; then
    BASE="${WAYMARK_BASE_URL:-https://work.kopsa.info}"
    code="$(curl -sS --max-time 20 -o /tmp/waymark-setup-welcome.json -w '%{http_code}' \
              -H "Authorization: Bearer $TOKEN" \
              -H "X-Waymark-Grant: $WAYMARK_GRANT_ID" \
              "${BASE%/}/api/-/welcome")"
    if [ "$code" = "200" ]; then
      say "setup ok: the mint works and the door opened as $(jq -r '.welcome' /tmp/waymark-setup-welcome.json), grant $(jq -r '.home.grant.state' /tmp/waymark-setup-welcome.json) until $(jq -r '.home.grant.expires_at' /tmp/waymark-setup-welcome.json)"
    else
      say "SETUP FAILURE: the bearer minted but the door refused it (HTTP $code):"
      say "  $(jq -r '.detail // .title // .' /tmp/waymark-setup-welcome.json 2>/dev/null)"
      say "  Usually WAYMARK_GRANT_ID names a grant that is expired, revoked, or written for a different agent."
    fi
    rm -f /tmp/waymark-setup-welcome.json
  else
    say "SETUP FAILURE: the mint was refused — $TOKEN"
    say "  'Invalid client or Invalid client credentials' means WAYMARK_KC_CLIENT_SECRET in the Jules UI does not match the Keycloak client named by WAYMARK_KC_CLIENT_ID. Re-paste it (no quotes, no trailing newline) — the secret lives at .waymark10_agent_clients.<agent> in the infra repo's secrets.local.json."
  fi
fi
say "──────────────────────"
exit 0
