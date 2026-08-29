#!/usr/bin/env bash
# Run ONE reading — the editor's run beside the clerk's sitting
# (waymark-nl0, READING.md). NOT a Jules queue: a reading runs a strong
# model LOCALLY, where the repo, the credential and the prior sittings'
# grade lines are. This is the cron target for the morning/evening
# lines docs/spec-standing-agent.md § "Two runs" gives, and the thing
# to run by hand when a sitting has printed a bundle under "Waiting for
# a reading":
#
#   0 6,18 * * *  /path/to/waymark/scripts/queue-reading.sh
#
# It sets WAYMARK_RUN=reading and hands the /reading skill to `claude
# -p`; the skill runs the driver, reads the manifest, follows
# READING.md, and reports with `scripts/sitting-run.sh verify`. One at
# a time (a reading is longer than a sitting), logged to
# ~/.waymark-reading-cron.log. Nothing here touches the house itself.
set -uo pipefail
export PATH="$HOME/.local/share/mise/shims:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:$HOME/go/bin:$PATH"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
LOG="$HOME/.waymark-reading-cron.log"
export WAYMARK_RUN=reading

exec 9>"$HOME/.waymark-reading.lock"
flock -n 9 || { printf '%s skipped (previous reading still running)\n' "$(date -Is)" >> "$LOG"; exit 0; }

ts="$(date -Is)"
printf '%s reading started\n' "$ts" >> "$LOG"
cd "$ROOT" || exit 1
if timeout 3600 claude -p "/reading" >> "$LOG" 2>&1; then
  printf '%s reading finished\n' "$(date -Is)" >> "$LOG"
else
  printf '%s reading FAILED (exit %s)\n' "$(date -Is)" "$?" >> "$LOG"
fi
