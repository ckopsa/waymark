#!/usr/bin/env bash
# THE BEADS IMPORT (docs/spec-ticket.md § 5): every issue in a beads
# export becomes a `ticket` row, through the create door and the
# doors after it, with its parent, its blockers, what it was found
# in, its ending and its beads id kept.
#
# THIS SCRIPT WALKS DOORS, and only doors. Nothing here writes a row
# the engine would not let a person write by hand: a birth is
# POST /api/tickets, a blocker is the block door, an ending is the
# complete or drop door with the bead's own close reason as the
# sentence. So every ticket it makes is in the transition log under
# the hand that ran it, and a refusal is the engine's own 409 — printed
# and counted, never worked around.
#
#   scripts/beads-import.sh .beads/issues.jsonl              # do it
#   DRY=1 scripts/beads-import.sh .beads/issues.jsonl        # say what it would do
#
#   WAYMARK_BASE          the engine (default http://localhost:8014)
#   WAYMARK_AUTH_HEADER   the whole header, e.g. "Authorization: Bearer …"
#                         or "Cookie: waymark_session=…"
#   WAYMARK_REPO          the repository every ticket names
#                         (default ckopsa/waymark)
#   DRY                   set to print the plan and post nothing
#
# ── WHAT IT DOES, IN FOUR PASSES ──────────────────────────────────────
#
#   1  BIRTHS, parents first. Sorted by the depth of the beads id
#      (waymark-x, then waymark-x.y, then waymark-x.y.z), because a
#      child's `parent` is a ref the engine resolves at the door and
#      the parent must be a row by then. The map from beads id to row
#      id grows as it goes. A bead whose parent was never born (its
#      parent is not in the export) is born with no parent, and said.
#   2  BLOCKERS, on the tickets that are still open. `blocks` edges
#      whose blocker is still open in beads become one block door
#      call per ticket with the whole set. A blocker already closed
#      holds nothing, and the door would refuse it by name, so it is
#      left out here rather than refused there.
#   3  DEFERRALS. A deferred bead with a date takes the defer door
#      with that day; one with no date takes today plus ninety.
#   4  ENDINGS, children first. Closed beads take `complete`, with
#      the bead's own close_reason as the sentence (or "Closed in
#      beads." when it had none). Deepest ids first, because a parent
#      ends after its children and the door refuses otherwise. A
#      closed parent with an open child stays open, and the refusal
#      is printed: that is a fact about the record, not a fault of the
#      door.
#
# ── WHAT IT KEEPS, AND WHERE ──────────────────────────────────────────
#
#   title        the bead's title, cut to 200
#   detail       description, then design, acceptance and notes under
#                their own headings — one body, in the bead's words
#   type         bug → bug, feature → feature, chore → chore,
#                task → task, epic and molecule → feature (an epic is
#                a ticket with children; the type says what it asks)
#   priority     the bead's own 0–4
#   parent       the parent-child edge's depends_on_id, resolved
#   found_in     the discovered-from edge's depends_on_id, resolved
#   bead_id      the bead's id, so the record can be read both ways
#   repo         WAYMARK_REPO
#
# It needs jq and curl. It is idempotent by bead_id: a bead whose id is
# already on a ticket is skipped, so a run cut short is resumed by
# running it again.
set -euo pipefail

BASE="${WAYMARK_BASE:-http://localhost:8014}"
REPO="${WAYMARK_REPO:-ckopsa/waymark}"
AUTH="${WAYMARK_AUTH_HEADER:-}"
DRY="${DRY:-}"
IN="${1:?usage: beads-import.sh <issues.jsonl>}"

command -v jq >/dev/null || { echo "this needs jq" >&2; exit 2; }
command -v curl >/dev/null || { echo "this needs curl" >&2; exit 2; }
[ -n "$AUTH" ] || [ -n "$DRY" ] || { echo "set WAYMARK_AUTH_HEADER (or DRY=1)" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
MAP="$WORK/map.tsv"          # bead_id <tab> row id
: > "$MAP"

# ── the engine, one call ─────────────────────────────────────────────

call() { # method path [json] → body on stdout, status in $STATUS
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$DRY" ]; then
    echo "  $method $path ${body:0:120}" >&2
    STATUS=201
    echo '{"id":"dry-'"$RANDOM$RANDOM"'"}'
    return 0
  fi
  local out
  out=$(curl -sS --max-time 30 -X "$method" "$BASE$path" \
        -H "$AUTH" -H "Content-Type: application/json" -H "Accept: application/json" \
        ${body:+--data-binary "$body"} -w $'\n%{http_code}')
  STATUS="${out##*$'\n'}"
  printf '%s' "${out%$'\n'*}"
}

row_of() { awk -F'\t' -v b="$1" '$1==b {print $2; exit}' "$MAP"; }

# ── 0 · what is already here ─────────────────────────────────────────
#
# Idempotence by bead_id: the tickets that carry one are read first,
# so a second run births nothing twice.

if [ -z "$DRY" ]; then
  # `state=` clears the default filter, `bead_id_set=true` keeps the
  # imported ones. One page: a run cut short past the first page births
  # a duplicate, which is why the plan is read before it is run.
  page=$(call GET "/api/tickets?state=&bead_id_set=true")
  if [ "$STATUS" = "200" ]; then
    jq -r '.items[]? // .rows[]? // empty
           | select(.data.bead_id != null)
           | [.data.bead_id, .id] | @tsv' <<<"$page" >> "$MAP" || true
  fi
fi
echo "already here: $(wc -l < "$MAP") ticket(s) with a bead_id" >&2

# ── the export, normalised ───────────────────────────────────────────

# one line per bead: id, depth, and the fields the doors take
jq -c '
  def dep(t): [ (.dependencies // [])[] | select(.type == t) | .depends_on_id ];
  def body:
    [ (.description // ""),
      (if (.design // "") != "" then "\n\n## Design\n\n" + .design else "" end),
      (if (.acceptance_criteria // "") != "" then "\n\n## Acceptance\n\n" + .acceptance_criteria else "" end),
      (if (.notes // "") != "" then "\n\n## Notes\n\n" + .notes else "" end)
    ] | join("") | .[0:20000];
  def kind:
    if .issue_type == "bug" then "bug"
    elif .issue_type == "chore" then "chore"
    elif .issue_type == "task" then "task"
    else "feature" end;
  {id: .id,
   depth: (.id | split(".") | length),
   title: (.title | .[0:200]),
   detail: body,
   type: kind,
   priority: ((.priority // 2) | if . < 0 then 0 elif . > 4 then 4 else . end),
   status: .status,
   close_reason: (.close_reason // ""),
   defer_until: (.defer_until // ""),
   parent: ((dep("parent-child") + dep("parent")) | first // ""),
   found_in: (dep("discovered-from") | first // ""),
   blocks: dep("blocks")}
' "$IN" > "$WORK/beads.jsonl"

total=$(wc -l < "$WORK/beads.jsonl")
echo "beads in the export: $total" >&2

# the open ids, for pass 2: a blocker that closed in beads holds nothing
jq -r 'select(.status != "closed") | .id' "$WORK/beads.jsonl" | sort > "$WORK/open.txt"

# ── 1 · births, parents first ────────────────────────────────────────

born=0; skipped=0; orphaned=0
while IFS= read -r line; do
  bid=$(jq -r .id <<<"$line")
  if [ -n "$(row_of "$bid")" ]; then skipped=$((skipped+1)); continue; fi
  parent_bid=$(jq -r .parent <<<"$line")
  parent_row=""
  if [ -n "$parent_bid" ]; then
    parent_row=$(row_of "$parent_bid")
    [ -n "$parent_row" ] || { echo "  $bid: parent $parent_bid is not in the export — born with no parent" >&2; orphaned=$((orphaned+1)); }
  fi
  found_bid=$(jq -r .found_in <<<"$line")
  found_row=""; [ -n "$found_bid" ] && found_row=$(row_of "$found_bid") || true
  body=$(jq -c --arg repo "$REPO" --arg parent "$parent_row" --arg found "$found_row" '
    {title, detail, type, priority, repo: $repo, bead_id: .id}
    + (if $parent != "" then {parent: $parent} else {} end)
    + (if $found != "" then {found_in: $found} else {} end)' <<<"$line")
  out=$(call POST "/api/tickets" "$body")
  if [ "$STATUS" != "201" ]; then
    echo "  $bid: the create door answered $STATUS — $(jq -r '.detail // .title // .' <<<"$out" 2>/dev/null | head -c 200)" >&2
    continue
  fi
  rid=$(jq -r '.id // empty' <<<"$out")
  [ -n "$rid" ] || { echo "  $bid: no id in the answer" >&2; continue; }
  printf '%s\t%s\n' "$bid" "$rid" >> "$MAP"
  born=$((born+1))
done < <(jq -c . "$WORK/beads.jsonl" | jq -c -s 'sort_by(.depth, .id) | .[]')
echo "pass 1 births: $born born, $skipped already here, $orphaned with a parent outside the export" >&2

# ── 2 · blockers, on the open tickets ────────────────────────────────

blocked=0
while IFS= read -r line; do
  bid=$(jq -r .id <<<"$line")
  rid=$(row_of "$bid"); [ -n "$rid" ] || continue
  blockers=$(jq -r '.blocks[]' <<<"$line" | sort | comm -12 - "$WORK/open.txt" \
             | while read -r b; do row_of "$b"; done | grep . || true)
  [ -n "$blockers" ] || continue
  body=$(jq -c -n --arg ids "$blockers" '{blocked_by: ($ids | split("\n") | map(select(. != "")))}')
  call POST "/api/tickets/$rid/-/block" "$body" >/dev/null
  if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then blocked=$((blocked+1))
  else echo "  $bid: the block door answered $STATUS" >&2; fi
done < <(jq -c 'select(.status != "closed" and (.blocks | length) > 0)' "$WORK/beads.jsonl")
echo "pass 2 blockers: $blocked ticket(s) blocked" >&2

# ── 3 · deferrals ────────────────────────────────────────────────────

deferred=0
default_day=$(date -u -d '+90 days' +%F 2>/dev/null || date -u -v+90d +%F)
while IFS= read -r line; do
  bid=$(jq -r .id <<<"$line")
  rid=$(row_of "$bid"); [ -n "$rid" ] || continue
  day=$(jq -r '.defer_until | .[0:10]' <<<"$line")
  [ -n "$day" ] || day="$default_day"
  call POST "/api/tickets/$rid/-/defer" "$(jq -c -n --arg d "$day" '{defer_until: $d}')" >/dev/null
  if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then deferred=$((deferred+1))
  else echo "  $bid: the defer door answered $STATUS" >&2; fi
done < <(jq -c 'select(.status == "deferred")' "$WORK/beads.jsonl")
echo "pass 3 deferrals: $deferred deferred" >&2

# ── 4 · endings, children first ──────────────────────────────────────

closed=0; held=0
while IFS= read -r line; do
  bid=$(jq -r .id <<<"$line")
  rid=$(row_of "$bid"); [ -n "$rid" ] || continue
  reason=$(jq -r '.close_reason | if . == "" then "Closed in beads." else .[0:480] end' <<<"$line")
  out=$(call POST "/api/tickets/$rid/-/complete" "$(jq -c -n --arg r "$reason" '{close_reason: $r}')")
  if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then closed=$((closed+1))
  else
    held=$((held+1))
    echo "  $bid stays open: $(jq -r '.detail // .title // .' <<<"$out" 2>/dev/null | head -c 200)" >&2
  fi
done < <(jq -c 'select(.status == "closed")' "$WORK/beads.jsonl" | jq -c -s 'sort_by(-.depth, .id) | .[]')
echo "pass 4 endings: $closed completed, $held held open by the door" >&2

echo "done: $(wc -l < "$MAP") ticket(s) carry a bead_id" >&2
