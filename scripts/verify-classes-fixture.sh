#!/usr/bin/env bash
# The fixture for EVERY ROW, CLASSIFIED (waymark-kfm) — one synthetic
# row of each class, run through `scripts/verify-classes.jq` exactly as
# `sitting-run.sh verify` runs it. No database, no network, no house:
# the classifier is a pure function of a manifest, the rows a principal
# wrote since the mark, and the remarks it said.
#
#   bash scripts/verify-classes-fixture.sh          # green, or a diff
#   bash scripts/verify-classes-fixture.sh --show   # …and print the lines
#
# ── THE MANIFEST ──────────────────────────────────────────────────────
#
#   work order        subject /api/tasks/order1, expected write: insight
#   letter form       subject /api/insights/form1, citing the turn
#                     /api/remarks/turn1
#   owed thread       /api/threads/owed1
#   arrivals          /api/threads/arr1
#   bare_tasks        /api/tasks/bare1
#   candidate_facts   /api/remarks/fact1
#
# ── THE ROWS, AND THE CLASS EACH MUST GET ─────────────────────────────
#
#   R1  insight cites tasks/order1            ORDER-ANSWER   (not printed:
#                                             the ORDER line above it says so)
#   R2  insight cites insights/form1          FORM-ANSWER
#   R3  insight cites remarks/fact1           FACT INDEXED
#   R4  insight cites threads/arr1            ARRIVAL ADVANCED
#   R5  insight cites tasks/bare1 AND         ENRICHED — a form is
#       verdict_reasons/vr1, which the form    answered by its SUBJECT;
#       also cites                             sharing one of its other
#                                              citations is not that
#                                              (the real /api/insights/
#                                              bde9b29d, 2026-08-31)
#   R6  outcome cites values/v1               EXTRA on a reading,
#                                             FILLER on a sitting
#   R7  insight cites threads/owed1           ORDER-ANSWER (the owed list
#                                             keeps its kindless rule)
#   R8  insight cites BOTH tasks/order1 and   ORDER-ANSWER — precedence:
#       threads/arr1                          first match wins
#   K1  remark in_reply_to turn1              FORM-ANSWER — a reply is a
#                                             write, and carries no evidence
#   K2  remark in_reply_to nothing named      not classified at all: a
#                                             remark is never filler here
#
# A row written before the mark, or by another principal, is not this
# run's row and never appears.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROG="$HERE/verify-classes.jq"
ME="me-principal"
SINCE="2026-08-31T00:00:00Z"
FAIL=0

command -v jq >/dev/null || { echo "fixture needs jq"; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/manifest.json" <<'JSON'
{"run": {"mode": "sitting"},
 "work_orders": [
   {"probe": "bare-task-due-soon", "subject": "/api/tasks/order1",
    "write": {"kind": "insight", "cite": ["/api/tasks/order1"]}}],
 "letter_forms": [
   {"letter_id": "L1", "subject": "/api/insights/form1",
    "write": {"kind": "remark",
              "cite": ["/api/insights/form1", "/api/remarks/turn1",
                       "/api/verdict_reasons/vr1"]}}],
 "notes_for_sittings": [],
 "unanswered_threads": [
   {"subject_kind": "thread", "subject_id": "owed1",
    "last": {"self": "/api/remarks/owed-last"}, "person_turns": []}],
 "rework_orders": [], "offered_requests": [], "declines": [],
 "arrivals": [{"self": "/api/threads/arr1"}],
 "bare_tasks": [{"self": "/api/tasks/bare1"}],
 "candidate_facts": [{"self": "/api/remarks/fact1"}]}
JSON

row () { # row <self> <kind> <at> <by> <cites-json>
  printf '{"self":"%s","kind":"%s","at":"%s","by":"%s",' "$1" "$2" "$3" "$4"
  printf '"offer":["",""],"evidence":%s,"cites":%s}\n' "$5" "$5"
}

{
  row /api/insights/R1 insight 2026-08-31T01:00:00Z "$ME" '["/api/tasks/order1"]'
  row /api/insights/R2 insight 2026-08-31T01:00:00Z "$ME" '["/api/insights/form1"]'
  row /api/insights/R3 insight 2026-08-31T01:00:00Z "$ME" '["/api/remarks/fact1"]'
  row /api/insights/R4 insight 2026-08-31T01:00:00Z "$ME" '["/api/threads/arr1"]'
  row /api/insights/R5 insight 2026-08-31T01:00:00Z "$ME" '["/api/tasks/bare1","/api/verdict_reasons/vr1"]'
  row /api/outcomes/R6 outcome 2026-08-31T01:00:00Z "$ME" '["/api/values/v1"]'
  row /api/insights/R7 insight 2026-08-31T01:00:00Z "$ME" '["/api/threads/owed1"]'
  row /api/insights/R8 insight 2026-08-31T01:00:00Z "$ME" '["/api/tasks/order1","/api/threads/arr1"]'
  # not this run's rows: one from before the mark, one somebody else's
  row /api/insights/OLD insight 2026-08-30T01:00:00Z "$ME" '["/api/values/v9"]'
  row /api/insights/THEIRS insight 2026-08-31T01:00:00Z "another" '["/api/values/v9"]'
} > "$WORK/rows.jsonl"

cat > "$WORK/remarks.json" <<JSON
[{"self": "/api/remarks/K1", "kind": "remark", "at": "2026-08-31T01:00:00Z",
  "by": "$ME", "in_reply_to": "turn1", "subject": "/api/outcomes/o1"},
 {"self": "/api/remarks/K2", "kind": "remark", "at": "2026-08-31T01:00:00Z",
  "by": "$ME", "in_reply_to": "some-other-turn", "subject": "/api/threads/elsewhere"},
 {"self": "/api/remarks/K3", "kind": "remark", "at": "2026-08-30T01:00:00Z",
  "by": "$ME", "in_reply_to": "turn1", "subject": "/api/outcomes/o1"}]
JSON

echo '[]' > "$WORK/no-remarks.json"
printf 'the journal of this run. It says why /api/outcomes/R6 was worth a row.\n' > "$WORK/journal.txt"
printf 'the journal of this run, and it says nothing about the extra.\n' > "$WORK/silent.txt"

run () { # run <manifest> <mode> <remarks> <journal> [faults]
  jq -rs --slurpfile m "$1" --slurpfile rk "$3" \
         --arg s "$SINCE" --arg me "$ME" --arg mode "$2" \
         --rawfile jt "$4" --arg faults "${5:-}" \
         -f "$PROG" "$WORK/rows.jsonl"
}

check () { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf '  ok   %s\n' "$1"
  else
    printf '  FAIL %s\n       expected: %s\n       actual:   %s\n' "$1" "$2" "$3"
    FAIL=1
  fi
}

has () { # has <label> <regex> <output>
  if printf '%s\n' "$3" | grep -qE "$2"; then printf '  ok   %s\n' "$1"
  else printf '  FAIL %s\n       no line matching: %s\n       lines:\n%s\n' "$1" "$2" "$3"; FAIL=1; fi
}

hasnt () { # hasnt <label> <regex> <output>
  if printf '%s\n' "$3" | grep -qE "$2"; then
    printf '  FAIL %s\n       a line matched and should not: %s\n       lines:\n%s\n' "$1" "$2" "$3"; FAIL=1
  else printf '  ok   %s\n' "$1"; fi
}

echo "verify-classes fixture — one row of each class, on a SITTING"
SIT="$(run "$WORK/manifest.json" sitting "$WORK/remarks.json" "$WORK/journal.txt")"

if [ "${1:-}" = "--show" ]; then echo; printf '%s\n' "$SIT"; echo; fi

check "six lines: four classes, the reply, and the filler" 6 \
  "$(printf '%s\n' "$SIT" | grep -c .)"
has  "the form's own write is named"       '^FORM-ANSWER: /api/insights/R2 ' "$SIT"
has  "the uncited turn is indexed"         '^FACT INDEXED: /api/insights/R3 ' "$SIT"
has  "the arrival is advanced, not extra"  '^ARRIVAL ADVANCED: /api/insights/R4 ' "$SIT"
has  "the bare task is enriched"           '^ENRICHED: /api/insights/R5 ' "$SIT"
hasnt "sharing a form's other citation is not answering that form" \
  '^FORM-ANSWER: /api/insights/R5' "$SIT"
has  "the reply answers the form"          '^FORM-ANSWER: /api/remarks/K1 ' "$SIT"
has  "and the one true leftover is filler" '^FILLER: /api/outcomes/R6 ' "$SIT"

# the point of the bead: what the run was ASKED to do is never filler
hasnt "no arrival, fact or enrichment is called filler" \
  '^FILLER: /api/insights/(R3|R4|R5)' "$SIT"
# an order's answer is graded one line per order, above this block
hasnt "the order's answer is not repeated as a class" '/api/insights/R1' "$SIT"
hasnt "nor is the owed thread's"                      '/api/insights/R7' "$SIT"
# precedence: a row that answers an order is that answer, whatever else
# it cites
hasnt "precedence — the order wins over the arrival"  '/api/insights/R8' "$SIT"
# a remark is read by its reply and by nothing else
hasnt "a reply to nothing the manifest named is not graded" '/api/remarks/K2' "$SIT"
hasnt "and a reply from before the mark is not this run's"  '/api/remarks/K3' "$SIT"
hasnt "a row written before the mark is not this run's"     '/api/insights/OLD' "$SIT"
hasnt "nor is another principal's row"                      '/api/insights/THEIRS' "$SIT"
hasnt "every list is here, so nothing is unavailable" '^CLASSES UNAVAILABLE' "$SIT"

echo
echo "verify-classes fixture — the same rows, graded as a READING"
READ="$(run "$WORK/manifest.json" reading "$WORK/remarks.json" "$WORK/journal.txt")"
has "the one leftover is the reading's extra" \
  '^EXTRA: cited, distinct — /api/outcomes/R6$' "$READ"
# THE CAP COUNTS EXTRAS ONLY: four rows of assignment beside it, and the
# extra is still lawful
hasnt "the assignment does not spend the extra" '^FILLER' "$READ"
has "the arrival still reads as advanced"       '^ARRIVAL ADVANCED: ' "$READ"

echo
echo "verify-classes fixture — a reading whose journal never says why"
QUIET="$(run "$WORK/manifest.json" reading "$WORK/remarks.json" "$WORK/silent.txt")"
has "the extra stands, and the silence is named" \
  '^EXTRA: cited, distinct — /api/outcomes/R6 — but the journal never says why' "$QUIET"

echo
echo "verify-classes fixture — the extra is a twin"
TWIN="$(run "$WORK/manifest.json" reading "$WORK/remarks.json" "$WORK/journal.txt" \
            "TWIN: /api/outcomes/R6 shares /api/values/v1 with /api/outcomes/older")"
has "a twin is filler however well it is cited" \
  '^FILLER: /api/outcomes/R6 — the extra is a twin' "$TWIN"

echo
echo "verify-classes fixture — two rows beyond everything the manifest named"
{ cat "$WORK/rows.jsonl"
  row /api/outcomes/R9 outcome 2026-08-31T01:00:00Z "$ME" '["/api/values/v2"]'
} > "$WORK/rows-two.jsonl"
TWO="$(jq -rs --slurpfile m "$WORK/manifest.json" --slurpfile rk "$WORK/remarks.json" \
             --arg s "$SINCE" --arg me "$ME" --arg mode reading \
             --rawfile jt "$WORK/journal.txt" --arg faults "" \
             -f "$PROG" "$WORK/rows-two.jsonl")"
has "a reading gets ONE extra, or none" \
  '^FILLER: 2 rows beyond everything the manifest named' "$TWO"

echo
echo "verify-classes fixture — the last reading's notes are not this run's forms"
# `notes_for_sittings` is the block a reading left for the CLERK, read
# back on the next manifest. On a sitting its lines are already work
# orders; on a reading they are review material — and crediting a
# reading's own row against one is how the reading of 2026-08-31 read as
# answering two forms it had written for somebody else.
jq '.notes_for_sittings = [{"subject": "/api/values/v1",
                            "write": {"kind": "insight", "cite": []}}]' \
   "$WORK/manifest.json" > "$WORK/notes-manifest.json"
NOTES="$(run "$WORK/notes-manifest.json" reading "$WORK/remarks.json" "$WORK/journal.txt")"
has "the row citing that subject is still the reading's extra" \
  '^EXTRA: cited, distinct — /api/outcomes/R6$' "$NOTES"
hasnt "and it is not called a form's answer" '^FORM-ANSWER: /api/outcomes/R6' "$NOTES"

echo
echo "verify-classes fixture — an older manifest, carrying none of the lists"
jq 'del(.arrivals, .bare_tasks, .candidate_facts)' "$WORK/manifest.json" > "$WORK/old-manifest.json"
OLDM="$(run "$WORK/old-manifest.json" sitting "$WORK/remarks.json" "$WORK/journal.txt")"
has "the missing lists are named, all three, in one line" \
  '^CLASSES UNAVAILABLE: this manifest carries no arrivals, bare_tasks, candidate_facts list' "$OLDM"
has "and its rows fall back to the older grade" '^FILLER: /api/insights/R4 ' "$OLDM"
has "the form still answers, list or no list" '^FORM-ANSWER: /api/insights/R2 ' "$OLDM"

echo
echo "verify-classes fixture — a run with no remarks at all"
NOR="$(run "$WORK/manifest.json" sitting "$WORK/no-remarks.json" "$WORK/journal.txt")"
hasnt "no reply, no FORM-ANSWER off a remark" '^FORM-ANSWER: /api/remarks/' "$NOR"
has   "and the row classes are untouched"     '^ENRICHED: /api/insights/R5 ' "$NOR"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "verify-classes fixture: green."
else
  echo "verify-classes fixture: RED — a class moved, or the prose did."
  exit 1
fi
