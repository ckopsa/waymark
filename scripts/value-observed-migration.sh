#!/usr/bin/env bash
# STEP 1 OF THE BELIEF MIGRATION (waymark-bug, docs/spec-hypotheses.md
# § 'THE MIGRATION, AND EXACTLY WHAT THE OWNER'S TAP IS'): every value
# standing in `observed` becomes an INTENT HYPOTHESIS, so the belief
# survives the state that carried it.
#
# THIS SCRIPT WRITES NOTHING. It reads the values collection and prints
# one `POST /api/hypotheses` body per observed row, on stdout, one JSON
# object per line. Reading a plan and running it deserve different
# amounts of friction — waymark-yqs's sentence, which is also why
# `make migrate-queue-prod` refuses APPLY — and a migration that mints
# rows about what a household believes is exactly the wrong place to
# be clever about that.
#
#   scripts/value-observed-migration.sh                  # the bodies
#   scripts/value-observed-migration.sh | jq -s length   # how many
#
# Feed the bodies to the door yourself, or hand them to a READING,
# which is the honest actor: joining a value's words to a claim about
# what this house means is the editor's work, not the clerk's
# (docs/spec-standing-agent.md § 'Two runs').
#
# ── WHAT IT PUTS IN EACH BODY, AND WHY ────────────────────────────────
#
#   claim   the value's own `says`, cut to the field's 240. The
#           household's words, unrewritten: a migration that reworded
#           what somebody noticed would be a migration having an
#           opinion. Where `says` is empty the `name` stands in.
#   shape   "intent" — always. § 'What merges' names this row exactly:
#           an observed value is a belief that somebody MEANS to hold
#           this, whether or not the week has shown it.
#   about   the value's own address, and nothing else. That is what
#           makes the belief followable and — since `about` IS the
#           link — what makes any typed finding citing the value feed
#           it, with no edit to a single finding.
#   prior   0.2, and it is a JUDGMENT stated here rather than hidden.
#           An observed value is not a hunch: an agent read this house
#           and wrote it down, which is a starting point the record
#           already leans toward. It is the top of the band's middle
#           and well under the coin toss the door refuses at. Change
#           it in one place if you disagree — that is the point of it
#           being one number in one line.
#
# ── AFTER THIS, AND ONLY AFTER ────────────────────────────────────────
#
# Step 2 is the destructive one and it is a person's: `value` declares
# :renames {:states {:observed :retired}}, `make migrate-queue-prod`
# prints the UPDATE it implies, and somebody runs that statement
# against the production database from the LAN. Do it AFTER this, so
# the state column still says which values were guesses while the
# hypotheses are being written. (An empty `affirmed_by` says it too,
# and the transition log always will — the order is for comfort, not
# for safety.)
set -euo pipefail

BASE="${WAYMARK_BASE:-${BASE:-http://localhost:8014}}"
PRIOR="${WAYMARK_MIGRATION_PRIOR:-0.2}"

command -v jq >/dev/null || { echo "this needs jq" >&2; exit 2; }
command -v curl >/dev/null || { echo "this needs curl" >&2; exit 2; }

# the same auth the driver uses: a bearer, a session cookie, or the
# dev header — whichever the environment carries. Nothing is minted
# here and nothing is stored.
AUTH=()
[ -n "${WAYMARK_BEARER:-}" ] && AUTH+=(-H "Authorization: Bearer $WAYMARK_BEARER")
[ -n "${WAYMARK_COOKIE:-}" ] && AUTH+=(-H "Cookie: $WAYMARK_COOKIE")
[ -n "${WAYMARK_PRINCIPAL:-}" ] && AUTH+=(-H "x-waymark-principal: $WAYMARK_PRINCIPAL")
[ -n "${WAYMARK_GRANT:-}" ] && AUTH+=(-H "x-waymark-grant: $WAYMARK_GRANT")

tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT

code="$(curl -sS -o "$tmp" -w '%{http_code}' \
        "${AUTH[@]+"${AUTH[@]}"}" \
        "$BASE/api/values?state=observed&page%5Bsize%5D=100" || echo 000)"

if [ "$code" != "200" ]; then
  echo "  ! /api/values?state=observed -> HTTP $code" >&2
  # A house that has ALREADY migrated answers 400 here, because the
  # kind no longer declares that state — which is the happy ending,
  # not a fault, and it is worth saying so rather than exiting cryptic.
  echo "  (a 400 here most likely means this house has already migrated:" >&2
  echo "   'observed' is not a state the value kind declares any more.)" >&2
  exit 1
fi

jq -c --arg prior "$PRIOR" '
  (.data.items // [])
  | map(. as $v
        | (($v.fields.says // $v.data.says // "") | tostring) as $says
        | (($v.fields.name // $v.data.name // "") | tostring) as $name
        | (if ($says | length) > 0 then $says else $name end) as $claim
        | select(($claim | length) > 0)
        | {claim: ($claim | .[0:240]),
           shape: "intent",
           about: [$v.self],
           prior: ($prior | tonumber)})
  | .[]' "$tmp"
