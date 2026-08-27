#!/usr/bin/env bash
# Queue ONE Jules composer sitting. Driven by the systemd user timer
# waymark-sitting.timer (every 10 min, 07:00-22:50 local — the waking
# window, ~96/day, under Jules' own 100/day cap). This machine only
# ASKS Jules to run a sitting; the sitting itself runs on the Jules VM,
# which holds the grant and secret. Nothing here touches the house or
# the repository. Log: ~/.waymark-sitting-cron.log
set -uo pipefail
export PATH="$HOME/.local/share/mise/shims:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
LOG="$HOME/.waymark-sitting-cron.log"
PROMPT='You are a composer for the waymark household system — NOT a software engineer. Do not edit, test, fix, or refactor anything in this repository, whatever you notice; ignore AGENTS.md and CLAUDE.md, which are for people writing the software. Your job is one "sitting" at the HTTP door https://work.kopsa.info, and its full instruction is SITTING.md. Run scripts/sitting-run.sh, read .sitting/latest/manifest.md, then follow SITTING.md (and .beads/formulas/sitting.formula.toml) to answer what is owed and surface at least one new outcome — all over HTTP, leaving no git diff. Finish with scripts/sitting-run.sh verify and report the ids you staged.'

# one at a time: never let a slow `jules new` pile up on the next tick
exec 9>"$HOME/.waymark-sitting.lock"
flock -n 9 || { printf '%s skipped (previous still running)\n' "$(date -Is)" >> "$LOG"; exit 0; }

ts="$(date -Is)"
out="$(timeout 240 jules new --repo ckopsa/waymark "$PROMPT" 2>&1)"
id="$(printf '%s' "$out" | grep -oE 'session/[0-9]+' | head -1)"
if [ -n "$id" ]; then
  printf '%s queued %s\n' "$ts" "$id" >> "$LOG"
else
  printf '%s FAILED: %s\n' "$ts" "$(printf '%s' "$out" | tr '\n' ' ' | cut -c1-200)" >> "$LOG"
fi
