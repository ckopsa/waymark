#!/usr/bin/env bash
# The fixture for WHAT THE HOUSE ALREADY SAYS (waymark-frv) — the two
# specimens the block was built from, run through
# `scripts/wm-keys.jq` + `scripts/house-says.jq` exactly as
# `sitting-run.sh` runs them. No database, no network, no house: the
# block is a pure function of the snapshot rows and a list of subjects.
#
#   bash scripts/house-says-fixture.sh          # green, or a diff
#   bash scripts/house-says-fixture.sh --show   # …and print the block
#
# ── THE SPECIMENS ─────────────────────────────────────────────────────
#
#   TC-842 (2026-08-29). The owner asked on the placard bundle "Can he
#   get this without a proper Utah ID yet?" The bundle cites the task
#   whose own DETAIL answers it — "No state ID required … accepts SSN
#   as the ID type, and it mails in" — and a published finding says the
#   same. Gemini, handed the thread alone, answered from the question
#   ("getting the ID would be a prerequisite") and indexed that answer
#   as a fact. THE BLOCK MUST SURFACE THE DETAIL, not just the title:
#   the title says "no state ID needed" in the household's shorthand,
#   and the sentence that settles it is three lines down in the detail.
#
#   CLARK (2026-08-31). A mailed form asked whether Clark's baptismal
#   interview had happened. The subject is a published finding whose
#   evidence names the Messages thread with Chris Archibald — where the
#   answer actually lives. The clerk wrote "still unknown" without
#   opening it. WHERE TO LOOK NEXT MUST NAME THAT THREAD, by address
#   and by title, and say how to read it.
#
# ── AND THE TWO WARNINGS `verify` PRINTS ──────────────────────────────
# SAYS-SO and UNREAD SOURCE are `scripts/answer-checks.jq`, run here
# exactly as `sitting-run.sh verify` runs them — both directions, since
# a warning that cannot be cleared is a nag and not a grade: a journal
# that says it looked clears UNREAD SOURCE, and so does a row of the
# run that cites the thread.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAIL=0

command -v jq >/dev/null || { echo "fixture needs jq"; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat "$HERE/wm-keys.jq" "$HERE/house-says.jq" > "$WORK/prog.jq"

# ── THE HOUSE, in the shapes the driver's snapshot writes ─────────────
cat > "$WORK/tasks.json" <<'JSON'
[{"self": "/api/tasks/tc842",
  "fields": {"title": "Mail TC-842 for Grandpa's handicap placard — no state ID needed, use SSN",
             "status": "open", "due_at": "2026-08-20T00:00:00Z",
             "detail": "No state ID required — TC-842 (Rev 7/26) accepts SSN as\nthe ID type, and it mails in.\n1) Section 1: Rod's info; check SSN as ID type."}},
 {"self": "/api/tasks/dld",
  "fields": {"title": "Call the DLD about Rod's state ID", "status": "open", "detail": null}}]
JSON

cat > "$WORK/events.json" <<'JSON'
[{"self": "/api/events/bday",
  "fields": {"title": "Clark's Birthday", "starts_at": "2026-09-19", "ends_at": "", "location": ""}}]
JSON

cat > "$WORK/people.json" <<'JSON'
[{"self": "/api/people/chris", "state": "observed",
  "data": {"name": "Chris Archibald", "relation": "somebody this house exchanges messages with"}},
 {"self": "/api/people/clark", "state": "current",
  "data": {"name": "Clark", "relation": "son — 7"}},
 {"self": "/api/people/rod", "state": "current",
  "data": {"name": "Rod", "relation": "grandfather"}}]
JSON

cat > "$WORK/insights.json" <<'JSON'
[{"self": "/api/insights/clarkq", "state": "published",
  "meta": {"updated_at": "2026-08-30T00:10:00Z"},
  "data": {"finding": "Chris Archibald asked whether Sunday 11:15 works for Clark's baptismal interview; no event holds it and no reply has gone.",
           "evidence": ["/api/threads/chris", "/api/people/chris", "/api/people/clark",
                        "/api/events/bday"]}},
 {"self": "/api/insights/ssn", "state": "published",
  "meta": {"updated_at": "2026-08-28T00:00:00Z"},
  "data": {"finding": "The DLD call exists to get Rod a state ID for the placard, and the placard form no longer needs one — TC-842 accepts SSN as the ID type and mails in.",
           "evidence": ["/api/tasks/tc842", "/api/tasks/dld"]}},
 {"self": "/api/insights/dismissed", "state": "dismissed",
  "meta": {"updated_at": "2026-08-29T00:00:00Z"},
  "data": {"finding": "A dismissed row is not what the house says.",
           "evidence": ["/api/tasks/tc842"]}}]
JSON

cat > "$WORK/outcomes.json" <<'JSON'
[{"self": "/api/outcomes/placard", "state": "offered",
  "data": {"goal": "Get Grandpa Rod's handicap placard in hand before the Sep 15 VA trip",
           "evidence": ["/api/tasks/tc842", "/api/people/rod"]}},
 {"self": "/api/outcomes/hearsay", "state": "offered",
  "data": {"goal": "Something somebody said, and nothing else",
           "evidence": ["/api/remarks/turn1"]}}]
JSON

cat > "$WORK/chats.json" <<'JSON'
[{"self": "/api/threads/chris",
  "data": {"title": "Chris Archibald", "source": "messa", "chat_kind": "direct",
           "status": "live", "participants": ["chris"],
           "participant_names": ["Chris Archibald"],
           "last_message_at": "2026-08-29T18:00:00Z"}},
 {"self": "/api/threads/group",
  "data": {"title": "Chris Archibald, Wellesley Kopsa", "source": "messa", "chat_kind": "group",
           "status": "live", "participants": ["chris", "welles"],
           "participant_names": ["Chris Archibald", "Wellesley Kopsa"],
           "last_message_at": "2026-08-22T18:00:00Z"}},
 {"self": "/api/threads/dead",
  "data": {"title": "An old group nobody is in", "source": "messa", "chat_kind": "group",
           "status": "dropped", "participants": ["clark"],
           "participant_names": ["Clark"],
           "last_message_at": "2021-02-26T19:00:00Z"}}]
JSON

block () { # block <subjects-json>
  jq -c --argjson subjects "$1" \
     --slurpfile tasks  "$WORK/tasks.json" \
     --slurpfile events "$WORK/events.json" \
     --slurpfile people "$WORK/people.json" \
     --slurpfile ins    "$WORK/insights.json" \
     --slurpfile out    "$WORK/outcomes.json" \
     --slurpfile chats  "$WORK/chats.json" \
     -f "$WORK/prog.jq" -n
}

has () { # has <label> <regex> <text>
  if printf '%s\n' "$3" | grep -qE "$2"; then printf '  ok   %s\n' "$1"
  else printf '  FAIL %s\n       nothing matching: %s\n       block:\n%s\n' "$1" "$2" "$3"; FAIL=1; fi
}
hasnt () { # hasnt <label> <regex> <text>
  if printf '%s\n' "$3" | grep -qE "$2"; then
    printf '  FAIL %s\n       matched and should not: %s\n       block:\n%s\n' "$1" "$2" "$3"; FAIL=1
  else printf '  ok   %s\n' "$1"; fi
}
check () { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then printf '  ok   %s\n' "$1"
  else printf '  FAIL %s\n       expected: %s\n       actual:   %s\n' "$1" "$2" "$3"; FAIL=1; fi
}

echo "house-says fixture — THE TC-842 SPECIMEN: a question whose cited task answers it"
TC="$(block '["/api/outcomes/placard"]')"
[ "${1:-}" = "--show" ] && { echo; printf '%s\n' "$TC" | jq .; echo; }

has "the bundle's own goal is the label" \
  '"label":"Get Grandpa Rod' "$TC"
has "the cited task is a row" '"self":"/api/tasks/tc842"' "$TC"
has "AND ITS DETAIL IS PRINTED — the sentence that answers the question" \
  '"detail":"No state ID required — TC-842 \(Rev 7/26\) accepts SSN as the ID type, and it mails in\.' "$TC"
has "the detail's newlines are collapsed to one line" \
  '"detail":"No state ID required[^"]*1\) Section 1' "$TC"
has "the published finding that names the task comes with it" \
  '"self":"/api/insights/ssn"' "$TC"
hasnt "a DISMISSED finding is not what the house says" '/api/insights/dismissed' "$TC"
has "the person the bundle cites is a row, with the relation" \
  '"says":"Rod — grandfather \[current\]"' "$TC"
check "and the block is one entry" 1 "$(printf '%s' "$TC" | jq 'length')"

echo
echo "house-says fixture — THE CLARK SPECIMEN: a form whose subject points at a thread"
CK="$(block '["/api/insights/clarkq"]')"
[ "${1:-}" = "--show" ] && { echo; printf '%s\n' "$CK" | jq .; echo; }

has "the finding's own evidence rows come with it" '"self":"/api/people/clark"' "$CK"
has "the cited thread is a row, and says out loud it carries no words here" \
  '"self":"/api/threads/chris","kind":"thread"' "$CK"
has "the thread row warns before anything is called unknown" \
  'read it before you call anything unknown' "$CK"
has "WHERE TO LOOK NEXT names that thread, by address and by title" \
  '"say":"read /api/threads/chris via tgram__get_messages, title \\"Chris Archibald\\"' "$CK"
has "and says the record CITES it" 'the record CITES this thread' "$CK"
has "the group thread the person is also in is offered, second" \
  '/api/threads/group[^}]*a person on these rows is in it' "$CK"
hasnt "a DROPPED thread is not somewhere to look" '/api/threads/dead' "$CK"
has "a Gate key rides along, in the household's own words" \
  '"kind":"gate","key":"Archibald"' "$CK"
check "the cited thread is offered FIRST, before the one that merely shares a person" \
  "/api/threads/chris" "$(printf '%s' "$CK" | jq -r '.[0].where[0].self')"

echo
echo "house-says fixture — the material the two verify warnings are read off"
# UNREAD SOURCE: the readable thing a run can be faulted for not opening
check "the Clark block carries a readable thread for UNREAD SOURCE to point at" \
  "/api/threads/chris" \
  "$(printf '%s' "$CK" | jq -r '[.[0].rows[], .[0].where[] | select(.self // "" | startswith("/api/threads/")) | .self] | first')"
# SAYS-SO: a bundle whose evidence is a remark and nothing else has no
# house row to render at all, which is the same absence verify names
SS="$(block '["/api/outcomes/hearsay"]')"
check "a bundle citing only a remark renders its own row and no house row beneath it" \
  1 "$(printf '%s' "$SS" | jq '.[0].rows | length')"
has "…and that one row is the bundle itself" '"kind":"bundle"' "$SS"
has "with nothing published behind it" '"findings":\[\]' "$SS"
check "and no thread to point at — nothing for UNREAD SOURCE to fault, which is exactly \
the row shape SAYS-SO is about" \
  0 "$(printf '%s' "$SS" | jq '[.[0].where[] | select(.kind == "thread")] | length')"
has "…while the search key still stands: a silent record is still searchable" \
  '"kind":"gate"' "$SS"

echo
echo "house-says fixture — SAYS-SO and UNREAD SOURCE, the two warnings themselves"
# The rules are scripts/answer-checks.jq, run here exactly as
# `sitting-run.sh verify` runs them.
CHK="$HERE/answer-checks.jq"
ME="me-principal"
SINCE="2026-08-31T00:00:00Z"

cat > "$WORK/rows.jsonl" <<JSON
{"self":"/api/insights/hearsay","kind":"insight","at":"2026-08-31T01:00:00Z","by":"$ME","evidence":["/api/remarks/turn1","/api/outcomes/placard"],"cites":["/api/remarks/turn1","/api/outcomes/placard"]}
{"self":"/api/insights/grounded","kind":"insight","at":"2026-08-31T01:00:00Z","by":"$ME","evidence":["/api/remarks/turn1","/api/tasks/tc842"],"cites":["/api/remarks/turn1","/api/tasks/tc842"]}
{"self":"/api/insights/theirs","kind":"insight","at":"2026-08-31T01:00:00Z","by":"another","evidence":["/api/remarks/turn1"],"cites":["/api/remarks/turn1"]}
{"self":"/api/insights/old","kind":"insight","at":"2026-08-30T01:00:00Z","by":"$ME","evidence":["/api/remarks/turn1"],"cites":["/api/remarks/turn1"]}
JSON

SAYSSO="$(jq -rs --arg s "$SINCE" --arg me "$ME" --arg which says-so \
             --argjson m '[]' --argjson tw '[]' --arg jt '' \
             -f "$CHK" "$WORK/rows.jsonl")"
has "a finding citing only a remark and a bundle is SAYS-SO, in those words" \
  '^SAYS-SO: /api/insights/hearsay — no house row behind it' "$SAYSSO"
hasnt "a finding that also cites a task is not" '/api/insights/grounded' "$SAYSSO"
hasnt "nor is another principal's row"          '/api/insights/theirs' "$SAYSSO"
hasnt "nor one from before the mark"            '/api/insights/old' "$SAYSSO"

# UNREAD SOURCE: a manifest whose block pointed at the Chris thread,
# and a run that replied under the question without opening it.
jq -n --slurpfile hs <(block '["/api/insights/clarkq"]') '
  {unanswered_threads:
     [{subject_kind:"insight", subject_id:"clarkq",
       owed_turns:[{shape:"question", said_by:"the owner",
                    self:"/api/remarks/q1",
                    says:"Did the baptismal interview for Clark happen?"}]}],
   house_says: $hs[0]}' > "$WORK/unread-manifest.json"
printf '{"subject":"insight/clarkq","mine":["/api/remarks/reply1"]}\n' > "$WORK/ans.jsonl"
printf '[{"self":"/api/remarks/reply1","kind":"remark","at":"2026-08-31T01:00:00Z","by":"%s","cites":[]}]\n' \
  "$ME" > "$WORK/tw.json"
printf 'the journal of this run, which never names the thread.\n' > "$WORK/j-silent.txt"
printf 'the journal: I opened /api/threads/chris and it had nothing in it.\n' > "$WORK/j-said.txt"

unread () { # unread <journal>
  jq -rs --slurpfile m "$WORK/unread-manifest.json" --slurpfile tw "$WORK/tw.json" \
         --arg s "$SINCE" --arg me "$ME" --arg which unread-source \
         --rawfile jt "$1" -f "$CHK" "$WORK/ans.jsonl"
}
UN="$(unread "$WORK/j-silent.txt")"
has "a reply under the question, with the cited thread never opened, is UNREAD SOURCE" \
  '^UNREAD SOURCE: the record pointed at /api/threads/chris and the answer never looked' "$UN"
has "and it names the question it was answering" 'asked “Did the baptismal' "$UN"
SAID="$(unread "$WORK/j-said.txt")"
check "a journal that says it looked clears the warning" "" "$SAID"

printf '[{"self":"/api/insights/answer","kind":"insight","at":"2026-08-31T01:00:00Z","by":"%s","cites":["/api/threads/chris"]}]\n' \
  "$ME" > "$WORK/tw-cited.json"
CITED="$(jq -rs --slurpfile m "$WORK/unread-manifest.json" --slurpfile tw "$WORK/tw-cited.json" \
             --arg s "$SINCE" --arg me "$ME" --arg which unread-source \
             --rawfile jt "$WORK/j-silent.txt" -f "$CHK" "$WORK/ans.jsonl")"
check "so does a row of this run that cites the thread" "" "$CITED"

NOREPLY="$(jq -rs --slurpfile m "$WORK/unread-manifest.json" --slurpfile tw "$WORK/tw.json" \
               --arg s "$SINCE" --arg me "$ME" --arg which unread-source \
               --rawfile jt "$WORK/j-silent.txt" -f "$CHK" /dev/null)"
check "and a run that answered nothing is not faulted for not looking" "" "$NOREPLY"

echo
echo "house-says fixture — a subject the snapshot does not carry"
GONE="$(block '["/api/tasks/no-such-row"]')"
check "is DROPPED, so the printer can say 'read it at its own address' \
rather than printing an empty block that reads as a silent house" \
  0 "$(printf '%s' "$GONE" | jq 'length')"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "house-says fixture: green."
else
  echo "house-says fixture: RED — the record moved, or the prose did."
  exit 1
fi
