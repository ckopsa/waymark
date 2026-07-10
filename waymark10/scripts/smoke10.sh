#!/usr/bin/env bash
# smoke10.sh — poke the waymark10 dev server by hand.
#
# 1. Ensure the dev database exists (the waymark-test-pg container):
#      docker exec waymark-test-pg psql -U ckopsa -d postgres \
#        -c "CREATE DATABASE mealplan10_dev" 2>/dev/null || true
# 2. Start the dev server (fixtures live on the test path):
#      cd waymark10 && clojure -Sdeps '{:aliases {:fx {:extra-paths ["test"]}}}' -M:fx \
#        -e "((requiring-resolve 'waymark10.server.engine/start-dev!)) @(promise)"
# 3. Run this script:  scripts/smoke10.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8010}"
P=(-H "X-Waymark-Principal: colton")

say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
body() { sed -e 's/^/  /'; }

say "discovery"
curl -sf "$BASE/api/.well-known/waymark" | body

say "the plan schema"
curl -sf "$BASE/api/schemas/plan" | body

say "create a plan (201 + Location)"
LOC=$(curl -sf -D - -o /tmp/smoke10-plan.json "${P[@]}" \
  -X POST "$BASE/api/plans" \
  -d '{"start_date":"2026-07-14","weeks":1,
       "days":[{"date":"2026-07-14"},{"date":"2026-07-15"}]}' \
  | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
PID="${LOC##*/}"
echo "  plan: $PID"
cat /tmp/smoke10-plan.json | body

say "the envelope: finalize is unavailable with a reason + remedies"
curl -sf "${P[@]}" "$BASE/api/plans/$PID" | body

say "acceptance folding refuses a date outside the plan (409)"
curl -s "${P[@]}" -X POST "$BASE/api/plans/$PID/-/assign_meal" \
  -d '{"date":"2026-07-19","meal_id":"m-x"}' | body

say "an unknown field refuses (422, problem+json)"
curl -s "${P[@]}" -X POST "$BASE/api/plans/$PID/-/assign_meal" \
  -d '{"date":"2026-07-14","meal_id":"m-x","evil":1}' | body

say "dry-run validates without effect"
curl -sf "${P[@]}" -X POST "$BASE/api/plans/$PID/-/assign_meal?dry_run=1" \
  -d '{"date":"2026-07-14","meal_id":"m-tacos"}' | body

say "cover every day, then finalize"
curl -sf "${P[@]}" -X POST "$BASE/api/plans/$PID/-/assign_meal" \
  -d '{"date":"2026-07-14","meal_id":"m-tacos"}' >/dev/null
curl -sf "${P[@]}" -X POST "$BASE/api/plans/$PID/-/assign_meal" \
  -d '{"date":"2026-07-15","meal_id":"m-soup"}' >/dev/null
curl -sf "${P[@]}" -X POST "$BASE/api/plans/$PID/-/finalize" | body

say "the fence: a meal edit without If-Match is 412"
MLOC=$(curl -sf -D - -o /dev/null "${P[@]}" -X POST "$BASE/api/meals" \
  -d '{"name":"Tacos","themes":["mexican"]}' \
  | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
MID="${MLOC##*/}"
curl -sf "${P[@]}" -X POST "$BASE/api/meals/$MID/-/accept" >/dev/null
curl -s "${P[@]}" -X POST "$BASE/api/meals/$MID/-/update_recipe" \
  -d '{"recipe":"carne asada, 900g"}' | body

say "…and with the current ETag it lands"
ETAG=$(curl -sf -D - -o /dev/null "${P[@]}" "$BASE/api/meals/$MID" \
  | awk 'tolower($1)=="etag:"{$1="";print}' | tr -d '\r' | sed 's/^ //')
curl -sf "${P[@]}" -H "If-Match: $ETAG" \
  -X POST "$BASE/api/meals/$MID/-/update_recipe" \
  -d '{"recipe":"carne asada, 900g"}' | body

say "the collection"
curl -sf "${P[@]}" "$BASE/api/plans" | body

say "the generic UI serves (phase 10) — open $BASE/api/-/ui in a browser"
curl -sf -o /dev/null -w '  %{http_code} %{content_type}\n' "$BASE/api/-/ui"

say "the CLI walks the same wire (phase 10)"
echo "  clojure -M:cli $BASE index"
echo "  clojure -M:cli $BASE act /api/plans/$PID finalize --yes"

printf '\nsmoke10: all calls answered.\n'
