#!/usr/bin/env bash
# feed-smoke.sh — the feed screen's hand verification, as one command
# (waymark-iqa.7). This repo verifies UI screens by hand
# (docs/waymark10-design.md §10); this is that walk written down so the
# next person takes the same one.
#
#   make dev-queue                 # :8014 against the dockerized pg :5433
#   waymark10/scripts/feed-smoke.sh
#
# It starts headless chromium if nothing is listening on the CDP port,
# runs the `feed` drive (which seeds its own day through the API and
# then reads the screen), and finishes with the half a browser cannot
# see: the Idempotency-Key a card's verb left on the transition row.
# That last query IS the epic's success metric — actions-from-the-feed,
# one prefix away, per day, per section, per kind.
#
# BASE / CDP_PORT / PG_CONTAINER / PG_DB override the defaults.
set -euo pipefail

BASE="${BASE:-http://localhost:8014}"
CDP_PORT="${CDP_PORT:-9223}"
PG_CONTAINER="${PG_CONTAINER:-waymark-test-pg}"
PG_DB="${PG_DB:-workqueue10_dev}"
PG_USER="${PG_USER:-ckopsa}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! curl -sf -o /dev/null "$BASE/api/-/ui"; then
  echo "no engine at $BASE — start one first:  make dev-queue" >&2
  exit 1
fi
if ! curl -sf -o /dev/null "http://127.0.0.1:$CDP_PORT/json"; then
  echo "· starting headless chromium on $CDP_PORT"
  CHROME="$(command -v chromium || command -v chromium-browser \
            || command -v google-chrome-stable)"
  "$CHROME" --headless=new --remote-debugging-port="$CDP_PORT" \
    --no-sandbox --user-data-dir=/tmp/wm10-chrome about:blank \
    >/tmp/wm10-chrome.log 2>&1 &
  trap 'kill %1 2>/dev/null || true' EXIT
  until curl -sf -o /dev/null "http://127.0.0.1:$CDP_PORT/json"; do sleep 0.5; done
fi

BASE="$BASE" CDP_PORT="$CDP_PORT" node "$HERE/ui-drive.mjs" feed

echo
echo "· actions from the feed, off the audit trail:"
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c \
  "SELECT idempotency_key, kind, action, at
     FROM waymark10_transitions
    WHERE idempotency_key LIKE 'feed/%'
    ORDER BY at DESC LIMIT 5"
