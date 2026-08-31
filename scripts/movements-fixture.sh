#!/usr/bin/env bash
# The fixture for WHAT MOVED THIS WEEK (waymark-2m2, corrected by
# waymark-bug) — seven synthetic typed atoms, two episodes, three
# about-rows, and the log-odds hand computed below so a reader can
# CHECK the program rather than trust it. No database, no network, no
# engine: `scripts/movements.jq` is a pure function of six JSON inputs
# and this runs it over six literal ones. CI runs this in the `quick`
# job, beside check-queue.
#
# The same three rules run in Clojure at `waymark10.belief`, which is
# where a STORED posterior comes from — and `waymark10.belief-test` is
# this file's twin over there, with its own hand arithmetic. The two
# must agree, and rule 2's key is where they did not until
# waymark-bug: see § RULE 2 below.
#
#   bash scripts/movements-fixture.sh            # green, or a diff
#   bash scripts/movements-fixture.sh --show     # …and print the block
#
# ── THE TABLE ─────────────────────────────────────────────────────────
#
# docs/spec-hypotheses.md's own numbers, which are `feed/default-
# evidence-lr`'s: costly_action 20 high / 5 low, unprompted_mention 8,
# statement_against_interest 6, specific_detail 4, question_asked 3,
# complaint_while_continuing 3, solicited_praise 1.05,
# minimal_response 0.9, declined_invite 0.2; solicited_discount 0.25;
# half-lives 540 / 365 / 365 / 180 / 180 / 180 / 90 / 60 / 60;
# episode_intensity 1.5; log_odds_clamp 6.
#
# ── THE ATOMS ─────────────────────────────────────────────────────────
#
# now = 2026-08-30T00:00:00Z (1788048000)
# episode A = "thread/7fda11c6 2026-08-24"  →  6 days old, THIS WEEK
# episode B = "thread/9c02af31 2026-05-02"  →  120 days old, not
#
#   A1  costly_action, cost high  ep A   cites people/iris, threads/7fda11c6
#   A2  unprompted_mention        ep A   cites people/iris
#   A3  question_asked            ep A   cites values/shop
#   A4  declined_invite           ep B   cites people/iris
#   A5  minimal_response          ep B   cites values/shop, threads/7fda11c6
#   A6  specific_detail, SOLICITED ep B  cites values/shop
#   A7  unprompted_mention        ep A   cites people/iris   ← A2's twin
#
# ── RULE 1: ln(LR), discounted where the house asked ──────────────────
#
#   A1  ln 20  =  2.995732           (cost high picks costly_action_high)
#   A2  ln 8   =  2.079442
#   A3  ln 3   =  1.098612
#   A4  ln 0.2 = -1.609438
#   A5  ln 0.9 = -0.105361
#   A6  ln 4 × 0.25 = 0.346574       ← the discount, because we asked
#   A7  ln 8   =  2.079442
#
# ── RULE 3: × 2^(−age ÷ half-life), at two clocks ─────────────────────
#
#           at now (age 6 / 120)        at now − 7d (age −1 / 113)
#   A1       2.972749                   not yet — it had not happened
#   A2       2.031947                   not yet
#   A3       1.049001                   not yet
#   A4      -1.281461                   -1.298610
#   A5      -0.026340                   -0.028559
#   A6       0.218328                    0.224293
#   A7       2.031947                   not yet
#
# ── RULE 2: one count per (WORD, occasion), ×1.5 where repeated ───────
#
# THE KEY IS THE WORD AND THE OCCASION TOGETHER, and it grew that half
# on 2026-08-31 (waymark-bug) to agree with docs/spec-hypotheses.md
# rule 2 and fork (m), which name `(type, episode)` twice. A1 and A2
# share occasion A and are DIFFERENT WORDS, so they both count — *he
# spent a Saturday on it* and *he brought it up* are two observations
# however close together they were said. A2 and A7 are the SAME WORD in
# the SAME evening, so they fold to one and take the intensity: that is
# one person being warm, not two independent facts. A7 exists so the
# intensity is exercised at all; before the key grew, every pair in
# this fixture folded and the fold said less than it meant.
#
#   people/iris        now: (costly_action, A)      {A1}     =  2.972749
#                           (unprompted_mention, A) {A2,A7}
#                                        2.031947 × 1.5      =  3.047921
#                           (declined_invite, B)    {A4}     = -1.281461
#                            standing =  4.739209
#                      −7d: (declined_invite, B) only        = -1.298610
#                            moved    =  6.037819
#   values/shop        now: (question_asked, A)     {A3}     =  1.049001
#                           (minimal_response, B)   {A5}     = -0.026340
#                           (specific_detail, B)    {A6}     =  0.218328
#                            standing =  1.240989
#                      −7d: the two B words                  =  0.195734
#                            moved    =  1.045255
#   threads/7fda11c6   now: (costly_action, A)      {A1}     =  2.972749
#                           (minimal_response, B)   {A5}     = -0.026340
#                            standing =  2.946409
#                      −7d: (minimal_response, B) only       = -0.028559
#                            moved    =  2.974968
#
# Ranked by |moved|: people/iris, threads/7fda11c6, values/shop.
#
# ── WHAT A5 AND A6 PROVE, and they are the two lines worth reading ────
#
# A5 is the only atom on two about-rows: on values/shop it stands
# beside A6, on threads/7fda11c6 it stands alone, and BOTH times it
# counts — one atom, two rows, two folds. Before the key grew it was
# folded away behind A6 on values/shop, which was the bug: a minimal
# response and a specific detail are two different observations of one
# evening, and treating them as one lost the weaker of them entirely.
#
# A6 is the discount doing its job: a specific detail is worth ln 4,
# but we ASKED, so it is worth a quarter of that. Take the discount out
# and the number changes; take it out silently and nobody would ever
# know.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROG="$HERE/movements.jq"
NOW=1788048000
FAIL=0

command -v jq >/dev/null || { echo "fixture needs jq"; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/table.json" <<'JSON'
{"source": "the fixture",
 "lr": {"costly_action_high": 20, "costly_action_low": 5,
        "unprompted_mention": 8, "statement_against_interest": 6,
        "specific_detail": 4, "question_asked": 3,
        "complaint_while_continuing": 3, "solicited_praise": 1.05,
        "minimal_response": 0.9, "declined_invite": 0.2,
        "solicited_discount": 0.25,
        "half_life_costly_action": 540,
        "half_life_statement_against_interest": 365,
        "half_life_declined_invite": 365,
        "half_life_specific_detail": 180,
        "half_life_unprompted_mention": 180,
        "half_life_complaint_while_continuing": 180,
        "half_life_question_asked": 90,
        "half_life_solicited_praise": 60,
        "half_life_minimal_response": 60,
        "episode_intensity": 1.5, "log_odds_clamp": 6}}
JSON

cat > "$WORK/people.json" <<'JSON'
[{"self": "/api/people/iris", "state": "current", "data": {"name": "Iris"}}]
JSON

cat > "$WORK/values.json" <<'JSON'
[{"self": "/api/values/shop", "state": "declared",
  "data": {"statement": "The shop gets opened"}}]
JSON

echo '[]' > "$WORK/empty.json"

atom () { # atom <id> <type> <episode> <updated> <evidence> <extra-json>
  printf '{"self":"/api/insights/%s","state":"published",' "$1"
  printf '"meta":{"updated_at":"%s"},' "$4"
  printf '"data":{"finding":"fixture atom %s","evidence_type":"%s",' "$1" "$2"
  printf '"episode":"%s","evidence":%s%s}}\n' "$3" "$5" "$6"
}

EPA="thread/7fda11c6 2026-08-24"
EPB="thread/9c02af31 2026-05-02"

{
  atom A1 costly_action        "$EPA" "2026-08-25T09:00:00Z" '["/api/people/iris","/api/threads/7fda11c6"]' ',"cost":"high"'
  atom A2 unprompted_mention   "$EPA" "2026-08-25T09:00:00Z" '["/api/people/iris"]' ''
  atom A3 question_asked       "$EPA" "2026-08-25T09:00:00Z" '["/api/values/shop"]' ''
  atom A4 declined_invite      "$EPB" "2026-05-03T09:00:00Z" '["/api/people/iris"]' ''
  atom A5 minimal_response     "$EPB" "2026-05-03T09:00:00Z" '["/api/values/shop","/api/threads/7fda11c6"]' ''
  atom A6 specific_detail      "$EPB" "2026-05-03T09:00:00Z" '["/api/values/shop"]' ',"solicited":true'
  atom A7 unprompted_mention   "$EPA" "2026-08-25T09:00:00Z" '["/api/people/iris"]' ''
} | jq -s '.' > "$WORK/insights.json"

run () { # run <insights-file>
  jq -n --slurpfile ins "$1" \
        --slurpfile tbl "$WORK/table.json" \
        --slurpfile people "$WORK/people.json" \
        --slurpfile values "$WORK/values.json" \
        --slurpfile chats "$WORK/empty.json" \
        --slurpfile lists "$WORK/empty.json" \
        --argjson nows "$NOW" -f "$PROG"
}

check () { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf '  ok   %s\n' "$1"
  else
    printf '  FAIL %s\n       expected: %s\n       actual:   %s\n' "$1" "$2" "$3"
    FAIL=1
  fi
}

echo "movements fixture — seven typed atoms, two episodes, three about-rows"
OUT="$(run "$WORK/insights.json")"

# `--show` prints the block exactly as a reading would read it in the
# brief. It is here so the section can be SEEN before any house has a
# single typed fact in it — which is every house, the day this lands.
if [ "${1:-}" = "--show" ]; then
  echo
  jq -r '.lines[]' <<<"$OUT"
  echo
fi

check "seven atoms typed" 7 "$(jq '.typed' <<<"$OUT")"
check "four of them new this week" 4 "$(jq '.new_this_week' <<<"$OUT")"
check "no atom is missing its episode" 0 "$(jq '.bare_episodes | length' <<<"$OUT")"
check "three movers" 3 "$(jq '.movers | length' <<<"$OUT")"
check "the clamp is read off the table" 6 "$(jq '.clamp' <<<"$OUT")"

# the order, and the six numbers, to four decimals — hand-computed above
check "movers in order of |moved|" \
  '["/api/people/iris","/api/threads/7fda11c6","/api/values/shop"]' \
  "$(jq -c '[.movers[].row]' <<<"$OUT")"

r4 () { jq -r --arg row "$1" --arg k "$2" \
          '.movers[] | select(.row == $row) | (.[$k] * 10000 | round) / 10000' \
          <<<"$OUT"; }

check "people/iris standing"      4.7392  "$(r4 /api/people/iris standing)"
check "people/iris moved"         6.0378  "$(r4 /api/people/iris moved)"
check "values/shop standing"      1.241   "$(r4 /api/values/shop standing)"
check "values/shop moved"         1.0453  "$(r4 /api/values/shop moved)"
check "threads/7fda11c6 standing" 2.9464  "$(r4 /api/threads/7fda11c6 standing)"
check "threads/7fda11c6 moved"    2.975   "$(r4 /api/threads/7fda11c6 moved)"

# rule 2, and the half it grew on 2026-08-31: iris's fresh occasion
# carried THREE facts in TWO words, so exactly one of those words is
# folded — and the label says which, in the household's own sentence.
check "iris — three facts in one occasion, two words, one of them repeated" 1 \
  "$(jq '[.movers[] | select(.row == "/api/people/iris") | .eps[]
          | select(.n == 3 and .repeated == 1)] | length' <<<"$OUT")"
check "and the line says the same word was said twice" 1 \
  "$(jq '[.lines[] | select(test("the same word said more than once in one occasion — counted once and 50% again"))] | length' <<<"$OUT")"

# rule 1's discount, said out loud on the line so a reader can see it
check "the solicited detail is marked as asked for" 1 \
  "$(jq '[.lines[] | select(test("specific_detail \\(asked for\\)"))] | length' <<<"$OUT")"

# the prose the reading actually reads
check "the section names itself" 1 \
  "$(jq '[.lines[] | select(startswith("WHAT MOVED THIS WEEK"))] | length' <<<"$OUT")"
check "three claim-less movers printed" 3 \
  "$(jq '[.lines[] | select(test("CLAIM-LESS MOVER"))] | length' <<<"$OUT")"
check "iris's line quotes her name, her week and her standing total" 1 \
  "$(jq '[.lines[] | select(test("CLAIM-LESS MOVER: Iris  /api/people/iris moved \\+6\\.04 this week \\(standing at \\+4\\.74\\)"))] | length' <<<"$OUT")"
check "the value line quotes its statement" 1 \
  "$(jq '[.lines[] | select(test("CLAIM-LESS MOVER: The shop gets opened  /api/values/shop"))] | length' <<<"$OUT")"
check "the thread has no name and prints its address" 1 \
  "$(jq '[.lines[] | select(test("CLAIM-LESS MOVER: /api/threads/7fda11c6"))] | length' <<<"$OUT")"

# ── the clamp ────────────────────────────────────────────────────────
# Rule 1's wall: no finite pile of atoms becomes certainty. Twelve
# costly actions on twelve separate days would fold to about 35 in
# log-odds — call it 99.99999% — and the whole posture of this house is
# that it proposes and never believes.
echo
echo "movements fixture — a pile of atoms does not become certainty"
: > "$WORK/pile.jsonl"
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  d="2026-08-$(printf '%02d' "$i")"
  atom "C$i" costly_action "thread/pile $d" "${d}T09:00:00Z" \
    '["/api/people/iris"]' ',"cost":"high"' >> "$WORK/pile.jsonl"
done
jq -s '.' "$WORK/pile.jsonl" > "$WORK/pile.json"
PILE="$(run "$WORK/pile.json")"
check "twelve atoms" 12 "$(jq '.typed' <<<"$PILE")"
check "and the fold stops at the clamp" 6 \
  "$(jq '.all_movers[] | select(.row == "/api/people/iris") | .standing' <<<"$PILE")"

# ── the honest empty section ─────────────────────────────────────────
# A house whose clerk has typed nothing gets a section that says so and
# stops. This is the state EVERY house is in the day this lands, so it
# is the case that must read well.
echo
echo "movements fixture — a house with no typed facts at all"
printf '%s' '[{"self":"/api/insights/Z1","state":"published","meta":{"updated_at":"2026-08-29T09:00:00Z"},"data":{"finding":"an untyped finding, entirely lawful","evidence":["/api/people/iris"]}}]' > "$WORK/untyped.json"
BARE="$(run "$WORK/untyped.json")"
check "no atoms" 0 "$(jq '.typed' <<<"$BARE")"
check "no movers" 0 "$(jq '.movers | length' <<<"$BARE")"
check "two lines, and they say why" 2 "$(jq '.lines | length' <<<"$BARE")"
check "the empty section says nothing yet" 1 \
  "$(jq '[.lines[] | select(test("WHAT MOVED THIS WEEK — nothing yet"))] | length' <<<"$BARE")"

# ── an atom with no episode ──────────────────────────────────────────
echo
echo "movements fixture — a typed fact with no occasion on it"
printf '%s' '[{"self":"/api/insights/Y1","state":"published","meta":{"updated_at":"2026-08-28T09:00:00Z"},"data":{"finding":"typed, no episode","evidence_type":"question_asked","evidence":["/api/people/iris"]}}]' > "$WORK/bare.json"
NOEP="$(run "$WORK/bare.json")"
check "it still counts" 1 "$(jq '.typed' <<<"$NOEP")"
check "and it is named as bare" 1 "$(jq '.bare_episodes | length' <<<"$NOEP")"
check "the brief says so out loud" 1 \
  "$(jq '[.lines[] | select(test("carry no episode"))] | length' <<<"$NOEP")"

# ── nothing new, and the record forgetting ───────────────────────────
# Rule 3, on its own. With no atom newer than a week every movement is
# the record FADING, which is a real movement — a house whose clerk
# stopped typing is told what it is looking at rather than shown a
# blank.
echo
echo "movements fixture — nothing new this week, only forgetting"
jq '[ .[] | select(.self != "/api/insights/A1" and .self != "/api/insights/A2"
                   and .self != "/api/insights/A3" and .self != "/api/insights/A7") ]' \
   "$WORK/insights.json" > "$WORK/old.json"
OLD="$(run "$WORK/old.json")"
check "nothing new" 0 "$(jq '.new_this_week' <<<"$OLD")"
check "and the section says what it is showing" 1 \
  "$(jq '[.lines[] | select(test("the record FORGETTING"))] | length' <<<"$OLD")"
# the only atom left on iris is a NO, and a no that fades makes the
# record less negative — so the movement is upward, and it is the
# record forgetting rather than anybody changing their mind
check "iris still moves — upward, as the old no fades" true \
  "$(jq '[.movers[] | select(.row == "/api/people/iris") | .moved > 0] | first' <<<"$OLD")"
check "and the newest occasion is named first on the line" 1 \
  "$(jq '[.lines[] | select(test("atoms: thread/7fda11c6 2026-08-24 — costly_action, unprompted_mention"))] | length' <<<"$OUT")"

# ── a dismissed finding is not evidence ──────────────────────────────
echo
echo "movements fixture — the house said no to the claim"
jq '[ .[] | .state = "dismissed" ]' "$WORK/insights.json" > "$WORK/dismissed.json"
DIS="$(run "$WORK/dismissed.json")"
check "every atom is left out" 0 "$(jq '.typed' <<<"$DIS")"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "movements fixture: green."
else
  echo "movements fixture: RED — the arithmetic moved, or the prose did."
  exit 1
fi
