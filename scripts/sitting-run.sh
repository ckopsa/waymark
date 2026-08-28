#!/usr/bin/env bash
# The sitting's MECHANICAL half (waymark-3z3's formula, waymark-53u's
# leash): everything one composer sitting does that needs no judgment.
# A model should arrive at the door already holding the house — so this
# script mints the credential, proves it at one address, pulls every
# read the formula's "read the house" step names into a snapshot of raw
# JSON, works out the lists the later steps are DEFINED in terms of
# (offered requests, unanswered turns, facts nothing has indexed,
# bundles carrying no judgment of ours, declines owed a diagnosis),
# runs a closed set of PROBES that turn the snapshot into two WORK
# ORDERS with their material already fetched (waymark-48a), and writes
# ONE manifest. What is left for the model is judgment and the door
# writes — which facts matter, what to compose, what to score.
#
#   scripts/sitting-run.sh            # read the house, write the manifest
#   scripts/sitting-run.sh verify     # what did the sitting actually write?
#
# It writes NOTHING to the engine but one thing: when the leash is
# nearly out it files the anchored extend-ask that
# standing-agent-tick.sh files, in the same words. That ask decides
# nothing — a human still taps it — and WAYMARK_NO_ASK=1 turns it off.
#
# CREDENTIALS, whichever shape the runner has:
#   WAYMARK_KC_CLIENT_ID + WAYMARK_KC_CLIENT_SECRET → agent-bearer.sh
#     (env-var runners: Jules and kin)
#   else WAYMARK_AGENT (default gemini)             → agent-token.sh
#     (a laptop that has the infra secrets file)
#   WAYMARK_BEARER                                  → a token you hold
# ALWAYS: WAYMARK_GRANT_ID — both headers ride every request, and a
# door that refuses says which one is wrong.
#
# Optional: WAYMARK_BASE_URL (https://work.kopsa.info),
# WAYMARK_SITTING_DIR (.sitting), WAYMARK_ASK_WINDOW_S (43200),
# WAYMARK_EXTEND_S (86400), WAYMARK_MAX_PAGES (5),
# WAYMARK_MAX_HYDRATE (120), WAYMARK_NO_GATE_PROBE (unset — set it to
# skip the one-read-per-rig Gate liveness probe AND the Gate material
# the work-order probes fetch), WAYMARK_WORK_ORDERS (2 — the ceiling on
# how many work orders the manifest presents).
#
# A refusal is the end of the run: this script never knocks, never
# spends re-entry, never invents another way in. It prints the door's
# own sentence and exits non-zero, because that sentence names the fix.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

BASE="${WAYMARK_BASE_URL:-https://work.kopsa.info}"; BASE="${BASE%/}"
GRANT="${WAYMARK_GRANT_ID:-}"
AGENT="${WAYMARK_AGENT:-gemini}"
SITDIR="${WAYMARK_SITTING_DIR:-$ROOT/.sitting}"
ASK_WINDOW="${WAYMARK_ASK_WINDOW_S:-43200}"
EXTEND="${WAYMARK_EXTEND_S:-86400}"
MAXPAGES="${WAYMARK_MAX_PAGES:-5}"
MAXHYDRATE="${WAYMARK_MAX_HYDRATE:-120}"

MODE="${1:-read}"
case "$MODE" in read|verify) ;; -h|--help|help)
  sed -n '2,45p' "${BASH_SOURCE[0]}"; exit 0 ;;
  *) echo "usage: $(basename "$0") [read|verify]" >&2; exit 2 ;; esac

command -v jq   >/dev/null || { echo "jq is required — apt-get install -y jq" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
[ -n "$GRANT" ] || { echo "set WAYMARK_GRANT_ID — every request wears X-Waymark-Grant, and outside it the resources answer 404" >&2; exit 1; }

now_s() { date -u +%s; }
iso()   { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; }
to_s()  { date -u -d "$1" +%s 2>/dev/null || date -u -j -f "%Y-%m-%dT%H:%M:%SZ" "${1%%.*}Z" +%s 2>/dev/null || echo 0; }

# ── the credential: minted at run start, used, and left to die ───────
mint() {
  if [ -n "${WAYMARK_BEARER:-}" ]; then printf '%s' "$WAYMARK_BEARER"; return; fi
  if [ -n "${WAYMARK_KC_CLIENT_ID:-}" ] && [ -n "${WAYMARK_KC_CLIENT_SECRET:-}" ]; then
    "$HERE/agent-bearer.sh"
  else
    "$HERE/agent-token.sh" --agent "$AGENT" work
  fi
}
TOKEN="$(mint)" || { echo "the mint refused — no bearer, no sitting. Fix the credential the message above names; never invent another way in." >&2; exit 1; }

api() { # api <outfile> <path> — GET, echoes the status code, NEVER aborts.
  # The reason it must not abort: this script reads the house in ~40
  # sequential GETs, and on a cloud runner one of them WILL time out
  # sometimes (Jules, 2026-08-27: curl 28 on read three killed the run,
  # left no manifest, and the model — handed a repo and no work order —
  # "improved" the repo instead of sitting). So a read that fails is
  # retried, and if it still fails it degrades to its HTTP code (000
  # when no response came); the caller writes an empty snapshot for
  # that kind and the sitting goes on. The ONE read that must be fatal
  # — the credential proof at the top — is fatal because it checks for
  # 200, which 000 is not, not because this function aborts.
  local code i
  for i in 1 2 3; do
    code="$(curl -sS --max-time 25 -o "$1" -w '%{http_code}' \
      -H "Authorization: Bearer $TOKEN" \
      -H "X-Waymark-Grant: $GRANT" \
      -H "Accept: application/json" \
      "$BASE$2" 2>/dev/null)" && [ -n "$code" ] && [ "$code" != "000" ] \
      && { printf '%s' "$code"; return 0; }
    [ "$i" -lt 3 ] && sleep 2
  done
  printf '%s' "${code:-000}"
  return 0
}
refusal() { jq -r '(.detail // .title // .error // "no sentence came back") | tostring' "$1" 2>/dev/null || cat "$1"; }

# ── two GETs, because a bearer and a grant are two separate things ───
# /api/-/welcome answers to the BEARER and shows the agent its standing
# grant whatever header it sent — so it proves the credential and
# nothing else. A grant-gated collection is what proves the header:
# outside the selected scope the resources answer 404, which is the
# hole a run must never fall through quietly.
PROBE="$(mktemp)"; trap 'rm -f "$PROBE" "${WELCOME:-}"' EXIT
code="$(api "$PROBE" "/api/-/welcome")"
if [ "$code" != "200" ]; then
  echo "the door refused the credential at $BASE/api/-/welcome (HTTP $code):" >&2
  echo "  $(refusal "$PROBE")" >&2
  echo "STOP. Check that the mint's client is the one this house knows; the sentence above names the fix." >&2
  exit 1
fi
WELCOME="$(mktemp)"; cp "$PROBE" "$WELCOME"   # $PROBE is reused for the grant check below
GRANT_STATE="$(jq -r '.home.grant.state // "?"' "$PROBE")"
GRANT_EXP="$(jq -r '.home.grant.expires_at // empty' "$PROBE")"
GRANT_HOME="$(jq -r '.home.grant.id // empty' "$PROBE")"
OWNER="$(jq -r '[.home.grant.scope[]? | select(.kind=="feed.preview_as") | .filter.member] | first // empty' "$PROBE")"

code="$(api "$PROBE" "/api/composition_requests?state=offered")"
if [ "$code" != "200" ]; then
  echo "the credential is good but the GRANT is not: $BASE/api/composition_requests answered HTTP $code" >&2
  echo "  $(refusal "$PROBE")" >&2
  if [ -n "$GRANT_HOME" ] && [ "$GRANT_HOME" != "$GRANT" ]; then
    echo "STOP. WAYMARK_GRANT_ID is $GRANT, but the grant this house holds for you is $GRANT_HOME. Set WAYMARK_GRANT_ID to that one." >&2
  else
    echo "STOP. Outside the selected scope every resource answers 404 — the grant named by WAYMARK_GRANT_ID is expired, revoked, or written for a different agent." >&2
  fi
  exit 1
fi
if [ -n "$GRANT_HOME" ] && [ "$GRANT_HOME" != "$GRANT" ]; then
  echo "note: you are wearing $GRANT while the house also holds $GRANT_HOME for you" >&2
fi

WHO="$(mktemp)"; api "$WHO" "/api/.well-known/waymark" >/dev/null
PRINCIPAL="$(jq -r '.principal.id' "$WHO")"
DISPLAY="$(jq -r '.principal.display' "$WHO")"

STARTED="$(iso "$(now_s)")"

# ══════════════════════════════════════════════════════════════════
# verify — what did this principal actually write?
# ══════════════════════════════════════════════════════════════════
# It leaves no snapshot of its own: a verify that minted a run
# directory would move the very "since" mark it reads.
if [ "$MODE" = "verify" ]; then
  rm -f "$WHO"
  SINCE="${WAYMARK_SINCE:-}"
  # the previous manifest is read for TWO things now — the "since" mark
  # and the work orders this run was handed — so it is found whether or
  # not WAYMARK_SINCE overrode the mark.
  prev="$(ls -1 "$SITDIR"/*/manifest.json 2>/dev/null | sort | tail -n 1 || true)"
  if [ -z "$SINCE" ] && [ -n "$prev" ]; then
    SINCE="$(jq -r '.run.started_at' "$prev")"
  fi
  [ -n "$SINCE" ] || SINCE="$(iso "$(( $(now_s) - 7200 ))")"
  echo "what $DISPLAY ($PRINCIPAL) wrote at $BASE since $SINCE"
  echo
  wrote=0
  check() { # check <label> <path> <states...>
    local label="$1" path="$2"; shift 2
    local st out n
    for st in "$@"; do
      out="$(mktemp)"
      if [ "$(api "$out" "${path}&state=${st}")" = "200" ]; then
        n="$(jq --arg s "$SINCE" '[.data.items[]? | select(.meta.updated_at >= $s)] | length' "$out")"
        if [ "$n" -gt 0 ]; then
          wrote=$((wrote + n))
          jq -r --arg s "$SINCE" --arg l "$label" \
            '.data.items[]? | select(.meta.updated_at >= $s) | "  \($l)  \(.self)  \(.state)  \(.summary[0:110])"' "$out"
        fi
      fi
      rm -f "$out"
    done
  }
  check outcome       "/api/outcomes?composed_by=$PRINCIPAL"      offered accepted declined expired
  check outcome_piece "/api/outcome_pieces?composed_by=$PRINCIPAL" offered taken declined moot
  check insight       "/api/insights?authored_by=$PRINCIPAL"      published taken dismissed
  check ranking_note  "/api/ranking_notes?judged_by=$PRINCIPAL"   live dismissed
  check remark        "/api/remarks?said_by=$PRINCIPAL"           noted
  check journal       "/api/journals?owner=$PRINCIPAL"            written amended
  echo
  # THIN — a finding that says nothing (waymark-46j). Not a door: a
  # door cannot judge prose, and one that tried would be a door
  # rewriting the house's sentences. So it is a HEURISTIC, printed
  # beside what this run wrote, while the run that wrote it is still
  # here to fix it: a finding too short to carry a fact, or one of the
  # generic sentences that fit any task ever written.
  thin="$(mktemp)"
  if [ "$(api "$thin" "/api/insights?authored_by=$PRINCIPAL&state=published")" = "200" ]; then
    jq -r --arg s "$SINCE" '
      .data.items[]? | select(.meta.updated_at >= $s)
      | ((.fields.finding // .summary) // "") as $f
      | select(($f | length) < 40
               or ($f | ascii_downcase
                      | test("needs action|requires further action|needs attention|should be done")))
      | "THIN: \(.self) — \($f)"' "$thin"
  fi
  rm -f "$thin"
  if [ "$wrote" -eq 0 ]; then
    echo "NOTHING written. Under waymark-mho a run writes only what its work orders and the owed lists name: if the manifest's arrivals were all handled (or there were none) and no bare task was worth enriching, a run that wrote nothing is a lawful no-op. It is only a FAILED run if an arrival was a person's unanswered remark, or a bare task plainly needed enriching, and it was left alone."
  else
    echo "$wrote row(s) written by this principal since $SINCE."
  fi

  # ── TWINS (waymark-8gc, waymark-1ag) ──────────────────────────────
  # A run that duplicated what the house already holds is a FAILED
  # run, and the report has to say so in its own voice rather than
  # leave it to whoever reads the ids. BOTH arms are doors now —
  # `outcome/not-a-twin` and `insight/one-live-finding-per-offer` —
  # so a fault line here is a hole worth knowing about rather than
  # the only check: a row written before the walls landed, or a
  # standing row past the window they read.
  #
  # The insight test below is UNCHANGED by 1ag landing, and that is
  # the point: it already wore shared evidence AND the same
  # {offer_kind, offer_id, offer_action}, and that is exactly the key
  # the door settled on — two findings citing one task are two
  # findings until they answer the same question off the same reading.
  # (The door needs the evidence half for a reason this report never
  # had to care about: without it, two diagnoses owed on one value
  # would collide and no composer could discharge the second.)
  #
  # Evidence lives only at a row's OWN address (a collection answers a
  # projection), so every candidate and every standing row is read
  # there — `hydrate`'s idiom, inlined, because verify mints no run
  # directory to keep a snapshot in.
  twin_rows() { # twin_rows <path> <kind> <state...> — one JSON line per row
    local path="$1" kind="$2"; shift 2
    local st out full href at
    for st in "$@"; do
      out="$(mktemp)"
      if [ "$(api "$out" "${path}?state=${st}&page%5Bsize%5D=100")" = "200" ]; then
        # the stamp comes off the COLLECTION item, which carries meta;
        # only the row's own address carries data.evidence
        while IFS="$(printf '\t')" read -r href at; do
          [ -n "$href" ] || continue
          full="$(mktemp)"
          if [ "$(api "$full" "$href")" = "200" ]; then
            # a row's OWN value is what it serves, not something it
            # read — the same subtraction `not-a-twin` makes, so this
            # report and that door disagree about nothing
            jq -c --arg k "$kind" --arg at "$at" '
              (if (.data.value_id // "") == "" then ""
               else "/api/values/" + (.data.value_id | tostring) end) as $own
              | {self, kind:$k, at:$at,
                 by:((.data.composed_by // .data.authored_by // "") | tostring),
                 offer:[(.data.offer_kind // ""), (.data.offer_id // ""),
                        (.data.offer_action // "")],
                 evidence:[(.data.evidence // [])[] | select(. != $own)],
                 # the UNSUBTRACTED list, for the work-order grading
                 # below: a bundle that serves a value drops that value
                 # from `evidence`, and an order whose subject IS a
                 # value would then read UNANSWERED however well it was
                 # answered
                 cites:((.data.evidence // []) + [$own] | map(select(. != "")) | unique)}' \
              "$full"
          fi
          rm -f "$full"
        done < <(jq -r '.data.items[]? | "\(.self)\t\(.meta.updated_at // "")"' \
                    "$out" | head -n "$MAXHYDRATE")
      fi
      rm -f "$out"
    done
  }
  twins="$(mktemp)"; : > "$twins"
  twin_rows "/api/outcomes" outcome offered accepted >> "$twins"
  twin_rows "/api/insights" insight published >> "$twins"
  faults="$(jq -rs --arg s "$SINCE" '
    . as $rows
    | [ $rows[] | select(.at >= $s) ] as $mine
    | [ $mine[] as $a
        | $rows[] as $b
        | select($b.self != $a.self and $b.kind == $a.kind)
        | select($a.kind != "insight" or $a.offer == $b.offer)
        | ($a.evidence - ($a.evidence - $b.evidence)) as $shared
        | select(($shared | length) > 0)
        | select($b.at < $a.at or ($b.at == $a.at and $b.self < $a.self))
        | "TWIN: \($a.self) shares \($shared | join(", ")) with \($b.self)" ]
    | unique | .[]' "$twins" 2>/dev/null)"
  if [ -n "$faults" ]; then
    echo
    echo "$faults"
    echo "  A twin is a FAILED run: the rank cannot tell two rows saying the same thing apart, so the second one is pure noise on the household's fridge. Read the standing row named above, and retire (or leave unstaged) the duplicate."
  fi

  # ── THE WORK ORDERS, GRADED (waymark-48a) ─────────────────────────
  # The row listing above says WHAT was written; this says whether the
  # run did WHAT IT WAS ASKED. An order is ANSWERED when a row this
  # principal wrote since the mark CITES the order's subject address —
  # not when a row of any shape exists, and not when the prose sounds
  # related. Nothing else grades it: the manifest named the subject,
  # the write and the citation, so answering is a fact about evidence,
  # which a script can check and a report cannot fake.
  if [ -n "$prev" ] && [ "$(jq '(.work_orders // []) | length' "$prev" 2>/dev/null || echo 0)" -gt 0 ]; then
    echo
    jq -rs --slurpfile m "$prev" --arg s "$SINCE" --arg me "$PRINCIPAL" '
      . as $rows
      | [ $rows[] | select(.at >= $s) | select(.by == $me) ] as $mine
      | (($m[0].work_orders) // [])[]
      | . as $o
      | [ $mine[] | select((.cites | index($o.subject)) != null) | .self ] as $hits
      | if (($o.write.kind // "") == "journal")
        then "ORDER \($o.probe) \($o.subject): JOURNAL-ONLY — no door write was asked (no live value carried it); it is answered in the journal or not at all"
        elif ($hits | length) > 0
        then "ORDER \($o.probe) \($o.subject): answered by \($hits | join(", "))"
        else "ORDER \($o.probe) \($o.subject): UNANSWERED"
        end' "$twins" 2>/dev/null
    jq -r 'if .crowd_out then "  CROWD-OUT, as the manifest said it: " + .crowd_out.says else empty end' "$prev"
    echo "  An order left UNANSWERED is only a failure if it could honestly have been answered — a Gate rig that refused, an event that needed nothing, a value with no goal in it are all lawful skips. The journal is where a skip is said out loud; an UNANSWERED order and a silent journal is a run that ignored its assignment."
  fi
  rm -f "$twins"

  # A SITTING LEAVES NO DIFF. The wisp appends to .beads/interactions.jsonl
  # (a tracked file) and a runner that diffs its tree would carry that
  # residue home as a patch; the snapshot is already gitignored.
  if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "$ROOT" checkout -q -- .beads/ 2>/dev/null || true
    left="$(git -C "$ROOT" status --porcelain 2>/dev/null | grep -v '^?? \.sitting' || true)"
    if [ -n "$left" ]; then echo; echo "tree not clean after the sitting — a sitting leaves no diff:"; echo "$left"; fi
  fi
  exit 0
fi

# ══════════════════════════════════════════════════════════════════
# read — the snapshot
# ══════════════════════════════════════════════════════════════════
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN="$SITDIR/$STAMP"
mkdir -p "$RUN/rows" "$RUN/doors"
ln -sfn "$STAMP" "$SITDIR/latest"
cp "$WHO" "$RUN/rows/well-known.json"; rm -f "$WHO"
cp "$WELCOME" "$RUN/rows/welcome.json"; rm -f "$WELCOME"

# The bearer, 0600, so the model's own door writes ride this run's
# credential instead of minting a second one. It dies in an hour.
umask 077; printf '%s' "$TOKEN" > "$RUN/bearer"

READS="$RUN/reads.jsonl"; : > "$READS"

note_read() { # note_read <name> <path> <http> <count>
  jq -nc --arg n "$1" --arg p "$2" --arg h "$3" --argjson c "$4" \
    '{name:$n, path:$p, http:$h, count:$c}' >> "$READS"
}

doc() { # doc <name> <path> — a document, no items
  local out="$RUN/rows/$1.json" c
  c="$(api "$out" "$2")"
  note_read "$1" "$2" "$c" 0
  [ "$c" = "200" ] || echo "  ! $1 -> HTTP $c: $(refusal "$out")" >&2
}

collection() { # collection <name> <path> — every page, merged to an items array
  local name="$1" path="$2"
  local out="$RUN/rows/$name.json"
  local sep page=1 n c tmp acc
  case "$path" in *\?*) sep='&';; *) sep='?';; esac
  acc="$(mktemp)"; : > "$acc"
  tmp="$(mktemp)"
  while :; do
    c="$(api "$tmp" "${path}${sep}page%5Bsize%5D=100&page%5Bnumber%5D=${page}")"
    if [ "$c" != "200" ]; then
      note_read "$name" "$path" "$c" 0
      echo "  ! $name -> HTTP $c: $(refusal "$tmp")" >&2
      echo '[]' > "$out"; rm -f "$acc" "$tmp"; return 0
    fi
    jq -c '.data.items // []' "$tmp" >> "$acc"
    n="$(jq '(.data.items // []) | length' "$tmp")"
    [ "$page" -eq 1 ] && jq '.actions.create // {}' "$tmp" > "$RUN/doors/$name.json"
    [ "$n" -lt 100 ] && break
    page=$((page + 1)); [ "$page" -gt "$MAXPAGES" ] && break
  done
  jq -s 'add // []' "$acc" > "$out"
  note_read "$name" "$path" 200 "$(jq 'length' "$out")"
  rm -f "$acc" "$tmp"
}

states() { # states <name> <path> <state...> — one collection per state, merged
  local name="$1" path="$2"; shift 2
  local sep st parts=() out="$RUN/rows/$name.json"
  case "$path" in *\?*) sep='&';; *) sep='?';; esac
  for st in "$@"; do
    collection "${name}-${st}" "${path}${sep}state=${st}"
    parts+=("$RUN/rows/${name}-${st}.json")
  done
  jq -s 'add // []' "${parts[@]}" > "$out"
  note_read "$name" "$path (every state)" 200 "$(jq 'length' "$out")"
}

hydrate() { # hydrate <name> — replace the projection with each row's OWN GET
  # A collection answers `fields`, a PROJECTION; only the row's own
  # address answers `data`, where evidence, routing and the rest live.
  # Every derived list below reads `data`, so this is not optional.
  local name="$1"
  local src="$RUN/rows/$name.json" out="$RUN/rows/$name.full.json"
  local href tmp acc
  acc="$(mktemp)"; : > "$acc"; tmp="$(mktemp)"
  while IFS= read -r href; do
    [ -n "$href" ] || continue
    if [ "$(api "$tmp" "$href")" = "200" ]; then cat "$tmp" >> "$acc"; echo >> "$acc"; fi
  done < <(jq -r '.[].self' "$src" | head -n "$MAXHYDRATE")
  jq -s '.' "$acc" > "$out"
  rm -f "$acc" "$tmp"
}

echo "reading the house as $DISPLAY ($PRINCIPAL) at $BASE"

# 1-2. the house's own orientation and the composer's tally
doc diagnosis "/api/-/diagnosis"
# 4. the feed through the owner's eyes — a preview writes nothing
if [ -n "$OWNER" ]; then doc feed-preview "/api/-/feed?preview_as=$OWNER"; fi

# 3. the standing pulls
states composition_requests "/api/composition_requests" offered answered expired
# 5. the evidence the grant admits
states values      "/api/values"  observed declared retired
states people      "/api/people"  observed current past
collection tasks       "/api/tasks"
# the NAMED lists a task belongs to (waymark-dgh). A task carries its
# list as an id, and an id is a handle rather than a name: a session
# goal reading "one task list (c07c9da8)" named nothing the household
# would recognise. This read is what turns that id back into the
# household own word for the list. A grant that does not admit the
# `task_list` kind answers the concealment 404, the read degrades to
# [], and the session probe then never ships a same-list cluster at
# all — the honest outcome, not a fault.
collection task_lists  "/api/task_lists"
collection chore_runs  "/api/chore_runs"
collection events      "/api/events"
collection media       "/api/media"
collection verdict_reasons "/api/verdict_reasons"
collection feed_recipes    "/api/feed_recipes"
# 6. the threads — one whole-kind read beats a read per subject
collection remarks "/api/remarks"
# 7. what already stands, in EVERY state
states outcomes       "/api/outcomes"       offered accepted declined expired
states outcome_pieces "/api/outcome_pieces" offered taken declined moot
# 8. what is already written down, and what this composer has judged
states insights      "/api/insights"      published taken dismissed
states ranking_notes "/api/ranking_notes" live dismissed
states journals      "/api/journals?owner=$PRINCIPAL" written amended
collection approval_requests "/api/approval_requests?grant_id=$GRANT"
doc grant "/api/grants/$GRANT"

for k in composition_requests values people outcomes outcome_pieces insights ranking_notes remarks verdict_reasons journals; do
  hydrate "$k"
done

# ── the derived lists: what the later steps are defined in terms of ──
D="$RUN/derived"; mkdir -p "$D"
R="$RUN/rows"

# every address any row says it read — and, kept apart, the addresses
# INSIGHTS cite. The distinction is the law's: a fact counts as indexed
# and a decline counts as diagnosed only when an INSIGHT names it; an
# outcome citing a prior decline is a recomposition, not a diagnosis.
jq -s '[.[][] | .data.evidence // [] | .[]] | unique' \
  "$R/insights.full.json" "$R/outcomes.full.json" "$R/ranking_notes.full.json" > "$D/cited.json"
jq '[.[] | .data.evidence // [] | .[]] | unique' "$R/insights.full.json" > "$D/insight_cited.json"

# the pulls owed an answer — offered means unanswered by definition
jq '[.[] | select(.state=="offered") | {id: (.self|split("/")|last), self, requested_by: .data.requested_by,
      value_id: (.data.value_id // null), good_until: .data.good_until, says: (.data.says // .summary)}]' \
  "$R/composition_requests.full.json" > "$D/offered_requests.json"

# the threads, oldest turn first, and whose word is last
jq --arg me "$PRINCIPAL" '
  [.[] | {id:(.self|split("/")|last), self, at:.meta.updated_at,
          subject_kind:.data.subject_kind, subject_id:.data.subject_id,
          said_by:(.data.said_by // "?"), says:.data.says,
          in_reply_to:(.data.in_reply_to // null)}]
  | group_by(.subject_kind + "/" + .subject_id)
  | map(sort_by(.at))
  | map({subject_kind: .[0].subject_kind, subject_id: .[0].subject_id,
         turns: ., last: .[-1],
         last_is_mine: (.[-1].said_by == $me)})
' "$R/remarks.full.json" > "$D/threads.json"

jq '[.[] | select(.last_is_mine|not)]' "$D/threads.json" > "$D/unanswered_threads.json"

# a turn nobody has indexed: a remark not ours that no insight cites
jq --arg me "$PRINCIPAL" --slurpfile cited "$D/insight_cited.json" '
  ($cited[0] // []) as $c
  | [.[] | select((.data.said_by // "") != $me)
         | .self as $sf
         | {id:($sf|split("/")|last), self:$sf, said_by:(.data.said_by // "?"),
            subject_kind:.data.subject_kind, subject_id:.data.subject_id,
            says:.data.says, indexed: ($c | index($sf) != null)}]
  | map(select(.indexed | not))
' "$R/remarks.full.json" > "$D/candidate_facts.json"

# the bundles carrying no live judgment of ours — never our own rows.
# Each carries its CITATION PACK: the addresses a judgment is read
# from — the bundle, the value it names, its pieces, the house's
# verdict words on it, and any insight that cites it. A note that
# cites only the bundle was judged from a headline; the pack is what
# the score step is defined in terms of, and it is computed here so
# the model copies addresses rather than guessing them.
jq --arg me "$PRINCIPAL" --slurpfile notes "$R/ranking_notes.full.json" \
   --slurpfile pieces "$R/outcome_pieces.full.json" \
   --slurpfile reasons "$R/verdict_reasons.full.json" \
   --slurpfile insights "$R/insights.full.json" '
  ([ ($notes[0] // [])[] | select(.state=="live")
                         | select((.data.judged_by // "") == $me)
                         | .data.subject_id ]) as $mine
  | ($pieces[0] // []) as $pc | ($reasons[0] // []) as $vr | ($insights[0] // []) as $in
  | [.[] | select((.data.composed_by // "") != $me)
         | (.self|split("/")|last) as $id | .self as $sf
         | {id:$id, self:$sf, state, goal:.data.goal,
            value_name:(.data.value_name // null), composed_by:(.data.composed_by // "?"),
            at:.meta.updated_at,
            evidence:(.data.evidence // []),
            pieces:[ $pc[] | select(.data.outcome_id == $id) | {self, state, says:.data.says, target_kind:.data.target_kind} ],
            reasons:[ $vr[] | select(.data.subject_id == $id) | {self, verdict:.data.verdict, reason:.data.reason, words:(.data.words // null)} ],
            insights:[ $in[] | select((.data.evidence // []) | index($sf) != null) | {self, finding:.data.finding} ],
            scored_by_me: ($mine | index($id) != null)}
         | .cite = ([.self]
                    + (if .value_name != null and (.self|length) > 0 then [] else [] end)
                    + [ .pieces[].self ] + [ .reasons[].self ] + [ .insights[].self ]) ]
  | map(select(.scored_by_me | not)) | sort_by(.at) | reverse
' "$R/outcomes.full.json" > "$D/unscored_bundles.json"
# the value's address rides the row's own link, read off the projection
jq --slurpfile full "$R/outcomes.full.json" '
  ($full[0] // []) as $f
  | map(. as $b | ($f[] | select(.self == $b.self) | .links.value.href // null) as $v
        | if $v then .value = $v | .cite = [.self, $v] + (.cite[1:]) else . end)
' "$D/unscored_bundles.json" > "$D/unscored_bundles.tmp" && mv "$D/unscored_bundles.tmp" "$D/unscored_bundles.json"

# every row the snapshot holds, by address — the lookup an offered step
# needs (a step is a door on a row that STANDS; a declined outcome
# admits none, and a diagnosis that offers one is dead on arrival).
# light_doors is the SHORTLIST an insight's offer may name (waymark-42m):
# offers-something-light admits nothing heavier than a selection, so a
# door whose rendered effort is assent or selection is offerable and
# every other door is a form. Read off the row's own envelope, because
# effort is the engine's word, not ours.
for f in "$R"/*.json; do
  jq -c 'if type=="array" then .[] | select(.self? and .kind?)
         | {self, kind, state,
            light_doors: [ (.actions // {}) | to_entries[]
                           | select((.value.effort // "recall")
                                    | . == "assent" or . == "selection")
                           | .key ]}
         else empty end' "$f" 2>/dev/null
done | jq -s 'group_by(.self) | map(reduce .[] as $r ({};
                (.light_doors // []) as $seen
                | . * $r
                | .light_doors = ($seen + ($r.light_doors // []) | unique)))' > "$D/all_rows.json"

# the declines, with the house's own words and whether a diagnosis stands
jq --slurpfile reasons "$R/verdict_reasons.full.json" --slurpfile cited "$D/insight_cited.json" \
   --slurpfile pieces "$R/outcome_pieces.full.json" --slurpfile rows "$D/all_rows.json" \
   --slurpfile wk "$R/well-known.json" '
  ($reasons[0] // []) as $vr | ($cited[0] // []) as $c | ($pieces[0] // []) as $pc
  | ($rows[0] // []) as $all | (($wk[0].resources) // {}) as $res
  | ["done","closed","declined","expired","retired","discarded","moot","taken","dismissed","accepted","finished","abandoned"] as $terminal
  | [.[] | select(.state=="declined")
         | (.self|split("/")|last) as $id
         | .self as $sf
         | {id:$id, self:$sf, goal:.data.goal, composed_by:(.data.composed_by // "?"),
            declined_count:(.data.declined_count // 0),
            value:(.links.value.href // null),
            reasons: [ $vr[] | select(.data.subject_id == $id)
                             | {self, verdict:.data.verdict, reason:.data.reason, words:(.data.words // null)} ],
            pieces: [ $pc[] | select(.data.outcome_id == $id) | {self, state, says:.data.says} ],
            offer_candidates: [ (.data.evidence // [])[] as $e
                                | $all[] | select(.self == $e)
                                | select(.kind != "outcome" and .kind != "journal" and .kind != "insight")
                                | .state as $st | select(($terminal | index($st)) == null)
                                | {self, kind, state, doors: ($res[.kind].actions // []),
                                   light_doors: (.light_doors // [])} ],
            diagnosis_stands: ($c | index($sf) != null)}
         | .cite = ([.self] + (if .value then [.value] else [] end)
                    + [ .reasons[].self ] + [ .pieces[].self ])]
' "$R/outcomes.full.json" > "$D/declines.json"

# SOURCES BEYOND THE HOUSE (the owner's ruling, 2026-08-27, cont.):
# email, Telegram and texts are CAPABILITIES, reached through the Gate
# door — GET /api/-/gate is Gate's live tools ∩ this grant, and nothing
# behind them is ever stored here. The dig goes through this door when
# the grant admits it; when it admits nothing, the manifest says which
# ask would open it.
gate_out="$(mktemp)"
if [ "$(api "$gate_out" "/api/-/gate")" = "200" ]; then cp "$gate_out" "$R/gate.json"; else echo '{}' > "$R/gate.json"; fi
rm -f "$gate_out"
jq -c '{tools: ((.links // {}) | to_entries | map({name:.key, href:.value.href, summary:(.value.description // .value.summary // null),
                                                     args: ((.value.input.properties // {}) | to_entries | map(select(.key != "why")) | map(.key + (if .value.default != null then "=" + (.value.default|tostring) else "" end)) | join(", "))})),
        mutations: ((.actions // {}) | keys),
        ask: (.ask // null)}' "$R/gate.json" > "$D/gate.json"

# … AND WHETHER THE RIG BEHIND EACH NAME STILL ANSWERS (waymark-idw).
# Gate's tool list is its AGGREGATION, not a promise: on 2026-08-27
# emila listed eight email tools and refused every one of them ("no
# such user" — the mail bridge had lost its account), and the sitting
# found that out mid-dig, one filler enrichment at a time. So probe
# ONE cheap read per rig HERE, at manifest time, and let the manifest
# NAME a dead rig before the model plans a dig around it. Reads only:
# nothing is ever written to Gate, and a probe that refuses costs the
# run nothing but the refusal's own sentence, which is what the model
# needs to say instead of filler. WAYMARK_NO_GATE_PROBE=1 turns it off.
gate_probe() { # gate_probe <outfile> <tool> <json-args> — POST, echoes the code
  # `api`'s posture, one direction over: never abort, degrade to the
  # HTTP code (000 when no answer came). The window is wider than a
  # read's because a rig that is merely SICK answers slowly — messa
  # waits 30s on its own page before it says so, and that sentence is
  # worth more to the manifest than a timeout of ours.
  local code
  code="$(curl -sS --max-time 40 -o "$1" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Waymark-Grant: $GRANT" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "$3" "$BASE/api/-/gate/$2" 2>/dev/null)" || true
  printf '%s' "${code:-000}"
}
: > "$D/gate_rigs.jsonl"
if [ -z "${WAYMARK_NO_GATE_PROBE:-}" ]; then
  # one tool per rig — an ARGUMENT-FREE read, the listing tools first
  # by name, so the probe is the cheapest call that rig serves; a
  # `limit`/`count` argument is pinned to 1 where the schema has one
  while IFS="$(printf '\t')" read -r rig tool args; do
    [ -n "$rig" ] || continue
    probe_out="$(mktemp)"
    code="$(gate_probe "$probe_out" "$tool" "$args")"
    jq -c --arg rig "$rig" --arg tool "$tool" --arg code "$code" '
      (($code == "200") and (.isError != true)) as $ok
      | {rig: $rig, probe: $tool, answered: $ok,
         refusal: (if $ok then null
                   else (((.content // []) | map(.text? // empty) | join(" ")) as $t
                         | (if ($t | length) > 0 then $t
                            else ((.detail // .title // ("HTTP " + $code)) | tostring) end)
                         | gsub("\\s+"; " ") | .[0:300])
                   end)}' "$probe_out" 2>/dev/null \
      || printf '{"rig":"%s","probe":"%s","answered":false,"refusal":"no answer came back (HTTP %s)"}\n' "$rig" "$tool" "$code"
    rm -f "$probe_out"
  done < <(jq -r '
    (.links // {}) | to_entries
    | map({rig: (.key | split("__") | .[0]), name: .key,
           short: (.key | split("__") | .[1] // ""),
           props: ((.value.input.properties // {}) | keys),
           required: ((.value.input.required // []) | map(select(. != "why")))})
    | map(select((.required | length) == 0))
    | map(. as $t | $t + {rank: ((["folders","threads","list_chats","chats",
                                   "accounts","categories","summary","inbox"]
                                  | index($t.short)) // 99)})
    | group_by(.rig) | map(sort_by(.rank, .name) | .[0])
    | .[] | [.rig, .name,
             (if (.props | index("limit")) then "{\"limit\":1}"
              elif (.props | index("count")) then "{\"count\":1}"
              else "{}" end)] | @tsv' "$R/gate.json") >> "$D/gate_rigs.jsonl"
fi
jq -s '.' "$D/gate_rigs.jsonl" > "$D/gate_rigs.json"
jq -c --slurpfile rigs "$D/gate_rigs.json" '. + {rigs: ($rigs[0] // [])}' \
  "$D/gate.json" > "$D/gate.tmp" && mv "$D/gate.tmp" "$D/gate.json"

# WHAT A RUN OWES (waymark-mho, the owner's ruling 2026-08-27 that
# RETRACTED the old requirement): a run advances concrete ARRIVALS and
# enriches BARE tasks; it never owes a manufactured outcome, and a
# quiet house is a complete run. What follows computes the three lists
# that job is defined in terms of: arrivals (new since we last
# looked), bare tasks (the lightest write — enrich one), and the cited
# set the compose path still needs.

# the previous run's start, so "new" means new since we last looked; on
# a first run look back one hour rather than flooding the arrival list
# with the whole house.
# (the `|| true` is load-bearing under `set -o pipefail`: with no prior
# manifest at all — the ephemeral runner the next comment describes —
# both `ls` and `grep -v` exit non-zero, and the assignment took the
# whole run down before it ever reached the fallback written for it.)
PREV_MANIFEST="$(ls -1 "$SITDIR"/*/manifest.json 2>/dev/null | grep -v "/$STAMP/manifest.json" | sort | tail -n 1 || true)"
# (and the read of it is GUARDED for the same reason: handed an empty
# path, jq reads stdin and exits 2, which under `set -e` killed the run
# — silently, because the stderr this hides is jq's. The two guards
# together are what make the fallback below reachable at all.)
SINCE_ARR=""
if [ -n "$PREV_MANIFEST" ]; then
  SINCE_ARR="$(jq -r '.run.started_at // empty' "$PREV_MANIFEST" 2>/dev/null || true)"
fi
# On an EPHEMERAL runner (Jules clones fresh — no prior snapshot) there
# is no PREV_MANIFEST, so the self-diff below cannot fire and arrivals
# fall back to a timestamp. The best persisted watermark that survives
# a fresh clone is our own last JOURNAL — "new since I was last here" —
# read from the snapshot the driver already took; a truly first run
# with no journal looks back one hour.
if [ -z "${SINCE_ARR:-}" ]; then
  SINCE_ARR="$(jq -r '[.[].meta.updated_at] | max // empty' "$R/journals.full.json" 2>/dev/null || true)"
fi
[ -n "${SINCE_ARR:-}" ] || SINCE_ARR="$(iso "$(( $(now_s) - 3600 ))")"

# ARRIVALS ARE CREATIONS (waymark-dgh). A row is an arrival when it did
# not exist at the watermark — a new or Gate-synced task, a new event, a
# person's remark — plus one thing a clock cannot see: a person's turn
# stays an arrival for as long as it is the last word of its thread,
# whenever it was said, because it is still owed an answer.
#
# WHAT "CREATED" IS READ FROM. The engine's own log: a collection asked
# with `?as-of=<instant>` answers the rows that EXISTED then (the
# router's time-travel tier 1), so `now − then`, keyed on self, is
# exactly the set created since. It needs no prior snapshot, which is
# what makes it right on the ephemeral runner, and a Gate-synced task
# that is new to the house counts by construction: the mirror's create
# IS when the house first saw it, and the row is simply absent from the
# as-of read.
#
# WHY NOT A TIMESTAMP. `meta` carries no created_at to compare, and
# `version` cannot stand in for one — a mirrored row is born at version
# 2 (the create, then the observation), so every synced arrival would be
# missed. Until 2026-08-28 the no-snapshot arm keyed on
# `meta.updated_at`, and a mirror resync bumps every row it touches: the
# evening the owner swept the fridge, this list read 291 arrivals on a
# house where nothing at all had been created since the last journal.
#
# An as-of read takes no other parameter (not even page[size] — the log
# carries state, not data), so it is one GET per kind. A refusal falls
# back to the prior snapshot's self-diff, and then to nothing for that
# kind — and which basis each kind got is written down rather than
# guessed at, because "no arrivals" and "we could not tell" are
# different sentences.
ARR_ACC="$(mktemp)"; echo '[]' > "$ARR_ACC"
PREV_DIR="$([ -n "$PREV_MANIFEST" ] && dirname "$PREV_MANIFEST" || echo "")/rows"
mkdir -p "$R/asof"
: > "$D/arrivals_basis.jsonl"
for kf in tasks remarks.full events media chore_runs; do
  cur="$R/$kf.json"; [ -f "$cur" ] || continue
  plural="${kf%.full}"
  then_f="$R/asof/$plural.json"
  if [ "$(api "$then_f" "/api/${plural}?as-of=${SINCE_ARR}")" = "200" ]; then
    jq '[.data.items[]?.self]' "$then_f" > "$then_f.selves" 2>/dev/null || echo '[]' > "$then_f.selves"
    # the row is bound BEFORE index() is asked: `$seen | index(.self)`
    # evaluates .self against $seen, which is an array — jq errors, the
    # 2>/dev/null swallows it, and the whole diff silently reads empty
    jq -s '(.[0] // []) as $seen
           | .[1] | map(. as $r | select(($r.self != null)
                                         and (($seen | index($r.self)) == null)))' \
       "$then_f.selves" "$cur" > "$ARR_ACC.k" 2>/dev/null || echo '[]' > "$ARR_ACC.k"
    arr_basis="the engine log (as-of)"
  elif [ -n "$PREV_MANIFEST" ] && [ -f "$PREV_DIR/$kf.json" ]; then
    jq -s '(.[0] | map(.self)) as $seen
           | .[1] | map(. as $r | select(($r.self != null)
                                         and (($seen | index($r.self)) == null)))' \
       "$PREV_DIR/$kf.json" "$cur" > "$ARR_ACC.k" 2>/dev/null || echo '[]' > "$ARR_ACC.k"
    arr_basis="the last run's snapshot"
  else
    echo '[]' > "$ARR_ACC.k"
    arr_basis="nothing — the log refused and there is no prior snapshot, so a creation of this kind cannot be seen from here"
  fi
  jq -nc --arg k "$plural" --arg b "$arr_basis" \
         --argjson n "$(jq 'length' "$ARR_ACC.k" 2>/dev/null || echo 0)" \
     '{kind:$k, read_from:$b, created_since:$n}' >> "$D/arrivals_basis.jsonl"
  jq -s '.[0] + .[1]' "$ARR_ACC" "$ARR_ACC.k" > "$ARR_ACC.m" && mv "$ARR_ACC.m" "$ARR_ACC"
done
# and the turns still holding the end of a thread — owed on every run
# until we answer them, however old the words are
# (an `A && mv` here would be a whole compound command returning
# non-zero when jq stumbles, and `set -e` would take the run down with
# it — the failure has to leave the accumulator alone, not the run)
if jq --slurpfile th "$D/unanswered_threads.json" --slurpfile full "$R/remarks.full.json" '
     ([ ($th[0] // [])[] | .last.self ]) as $ends
     | . + [ ($full[0] // [])[] | .self as $sf | select(($ends | index($sf)) != null) ]
   ' "$ARR_ACC" > "$ARR_ACC.m" 2>/dev/null; then mv "$ARR_ACC.m" "$ARR_ACC"; fi
jq 'unique_by(.self)
    | map({self, kind, state, at:(.meta.updated_at // null),
           says:((.display.title // .summary // "") | .[0:160])})
    | sort_by(.at) | reverse' "$ARR_ACC" > "$D/arrivals.json" 2>/dev/null || echo '[]' > "$D/arrivals.json"
jq -s '.' "$D/arrivals_basis.jsonl" > "$D/arrivals_basis.json" 2>/dev/null || echo '[]' > "$D/arrivals_basis.json"
rm -f "$ARR_ACC" "$ARR_ACC.k"

# the CITED set — every row a STANDING outcome or a LIVE insight speaks
# for. Standing means offered or accepted; live means published. A
# declined, expired or dismissed row speaks for nothing — that is what
# a verdict frees (not-a-twin reads the same two states). Until
# 2026-08-28 this read every outcome in every state, so a swept fridge
# (28 declines in one evening) still claimed all 16 open tasks and the
# session probe could never find a free cluster.
jq -s '(.[0] + .[1]) | unique' \
   <(jq '[.[] | select(.state=="offered" or .state=="accepted") | .data.evidence[]?]' "$R/outcomes.full.json") \
   <(jq '[.[] | select(.state=="published") | .data.evidence[]?]' "$R/insights.full.json") \
   > "$D/cited.json" 2>/dev/null || echo '[]' > "$D/cited.json"

# BARE tasks — the lightest write, and what a quiet run does: enrich
# ONE. A task is bare when it is actionable, carries no detail, and no
# outcome or insight yet speaks for it. Enrichment ANNOTATES it (an insight citing
# the source and offering the task's own next step); it NEVER edits the
# task — only people decide what their own rows say.
#
# ACTIONABLE is the mirrored :status (open|done|dropped — the source's
# own word, task.clj), NOT the waymark state: every mirror sits at
# state=fresh whether its source says open or done, so a filter on
# state alone handed a composer 24 finished July chores and dropped
# gtasks as "bare" (the Fable sitting of 2026-08-27 read them out).
jq --slurpfile cited "$D/cited.json" '
  ($cited[0] // []) as $c
  | [.[] | (.state // "") as $st
         | select($st=="fresh" or $st=="open" or $st=="active")
         | select((.fields.status // "open") == "open")
         | select(((.fields.title // .display.title // "") | tostring | length) > 0)
         | select(((.fields.detail // "") | tostring | length) == 0)
         | .self as $sf | select(($c | index($sf)) == null)
         | {self, state, title:(.display.title // .fields.title // .summary // ""),
            due_at:(.fields.due_at // null), source:(.fields.source // null)}]
' "$R/tasks.json" > "$D/bare_tasks.json" 2>/dev/null || echo '[]' > "$D/bare_tasks.json"

# UNCOMPOSED evidence — nothing a run owes, just context for when a
# real outcome IS warranted: rows nothing has composed or enriched yet.
jq -s '
  (.[0] // []) as $cited
  | .[1:] | add
  | map(.self as $sf | select($sf != null and (($cited | index($sf)) == null)))
  | map({self, kind, state, at:(.meta.updated_at // null),
         says:((.display.title // .summary // "") | .[0:140])})
  | sort_by(.at) | reverse
' "$D/cited.json" \
   "$R/tasks.json" "$R/events.json" "$R/media.json" "$R/chore_runs.json" \
   > "$D/uncomposed.json" 2>/dev/null || echo '[]' > "$D/uncomposed.json"
jq -c 'group_by(.kind) | map({kind:.[0].kind, count:length})' "$D/uncomposed.json" > "$D/uncomposed_census.json"

# what ALREADY STANDS — every offered/accepted outcome with its goal and
# the rows it cites, so a compose does not restage a bundle the
# house already holds (sitting 7 twinned the realtor list; the rank
# cannot tell twins apart, so a duplicate is pure noise).
#
# iterate_open (waymark-9j2): a person tapped "iterate" on this outcome
# — the goal is right, the plan is wrong, rework it in place — more
# recently than the composer last reworked it. A standing work order to
# revise the pieces (withdraw with outcome_pieces/-/rework, stage the
# replacements, commit with outcomes/-/rework); the note itself is a
# remark on the outcome's thread, so it also rides unanswered_threads.
jq '[.[] | select(.state=="offered" or .state=="accepted")
       | {self, state, goal:.data.goal, value:(.data.value_name // null),
          evidence:(.data.evidence // []),
          plan_revision:(.data.plan_revision // 0),
          iterate_open:(((.data.iterate_requested_at // "") != "")
                        and ((.data.iterate_requested_at // "")
                             > (.data.reworked_at // "")))}]' \
   "$R/outcomes.full.json" > "$D/standing_outcomes.json"

# a value must be LIVE to be named; a companion must be current
# (`says` rides along because value-fit is tested on a value's OWN
# words — its name, what it says, and the activities it loves)
jq '[.[] | select(.state != "retired")
        | {id:(.self|split("/")|last), self, state, name:(.data.name // .summary),
           says:(.data.says // null), loved:(.data.loved // null)}]' \
   "$R/values.full.json" > "$D/live_values.json"
jq '[.[] | select(.state == "current")
        | {id:(.self|split("/")|last), self, name:(.data.name // .summary),
           relation:(.data.relation // null)}]' "$R/people.full.json" > "$D/companions.json"

# ── THE WORK ORDERS (waymark-48a) ────────────────────────────────────
# The owner's ruling, 2026-08-27: THE WEAKER THE MODEL, THE MORE
# DIRECTION IT NEEDS. Handed the same manifest, Fable read a noisy
# candidate list and picked well; Gemini wrote filler. A candidate set
# asks the model to CHOOSE; a work order asks it to EXECUTE. So the line
# moves one notch further toward the machine: below is a CLOSED SET OF
# PROBES — the feed's `populations` shape (waymark10.server.feed: a
# closed map a reviewer reads on one screen, never a scan) — each a
# deterministic query over the snapshot, each emitting AT MOST ONE work
# order carrying its own pre-fetched material and the write it expects.
#
# A work order is: a subject ADDRESS, the material to read, the EXPECTED
# WRITE (kind, the fields to fill, the addresses to cite, the light door
# to offer), and one line saying why it is worth a row. Nothing in it is
# a judgment call except the sentence the model has to write.
#
# THE CEILING. The manifest presents the top
# WAYMARK_WORK_ORDERS (default 2), ordered by urgency — soonest due or
# soonest starting first, then the probe order below. Those two ARE the
# run's assignment. Everything else in the manifest is optional
# material, and a run with no work orders and nothing owed writes
# nothing at all: still a lawful run (waymark-mho).
#
# THE PROBES, in order (the order is also the tie-break):
#   1. session-of-like-tasks       — two or more free open tasks of one
#                                    shape, gathered into one held block
#   2. commitments-in-messages     — a task or an event that exists only
#                                    in a text or an email
#   3. bare-task-due-soon          — an open, detail-less, unspoken-for
#                                    task, nearest due_at first
#   4. event-without-prep          — an event inside 10 days that no
#                                    outcome or insight speaks for
#   5. person-mentioned-unrecorded — a roster companion named in the
#                                    last 7 days of Gate traffic whom no
#                                    insight has written down since
#   6. value-with-no-live-outcome  — a live value nothing standing serves
#
# WHY THE TWO NEW ONES GO FIRST. Rank only breaks a tie — a date always
# outranks it, so an event today still ships ahead of a session next
# Saturday. What the order settles is what wins when two probes carry
# the same clock, and there the answer is the one that clears more of
# the house: a session finishes four tasks where bare-task-due-soon
# annotates one, and a commitment nobody wrote down is the only kind of
# subject that is invisible to every other probe — it has no row yet to
# be found by. (A person's pull and a person's turn still outrank all
# six: those are owed lists, not probes, and SITTING.md puts them
# first.)
#
# Three things every probe leans on (waymark-jux, from the Fable
# grading of the first real order; waymark-q23/is7 for the third): Gate
# is searched with the SHORT KEY derived from the subject line rather
# than the line itself; any order that would demand an outcome first
# tests VALUE-FIT — when no live value owns a word the subject says,
# the order becomes journal-only instead of asking for a bundle that
# would have to invent the value it serves; and every instant a probe
# PREPARES is a wall clock in the household's zone, rendered UTC and
# ahead of this run.
# Adding a seventh means adding it HERE and in the render below,
# together, the way a feed population is added —
# docs/spec-standing-agent.md § The composer probes says it in full.
#
# MACHINE DEDUPE COMES FIRST. Every probe drops any subject the cited
# set already names — a row an outcome or insight already speaks for.
# The two dedupe doors (waymark-8gc's not-a-twin, waymark-1ag's
# one-live-finding-per-offer) would refuse a duplicate outcome or a
# duplicate finding at staging, but a refusal costs the run a round
# trip and teaches the model nothing: the list it is handed is clean
# before it reads it.
WORK_ORDERS_N="${WAYMARK_WORK_ORDERS:-2}"
# a ceiling that is not a number is not a ceiling — and jq would abort
# the whole run on it, which is the one thing this block must never do
case "$WORK_ORDERS_N" in ''|*[!0-9]*) WORK_ORDERS_N=2 ;; esac
NOW_S="$(now_s)"
SINCE_7D="$(iso "$(( NOW_S - 604800 ))")"
IMAP_SINCE="SINCE $(date -u -d "@$(( NOW_S - 604800 ))" +%d-%b-%Y 2>/dev/null || date -u -r "$(( NOW_S - 604800 ))" +%d-%b-%Y) "

# ── the household's own clock (waymark-q23, waymark-is7) ─────────────
# Every instant a probe PREPARES is a wall clock in the household's
# zone, rendered as UTC. "Saturday morning" is 09:00 in Denver, which
# is 15:00Z in August and 16:00Z in November; a probe that shipped
# 09:00Z would be holding a block at three in the morning, and a model
# handed a bare hour would have to do the arithmetic itself. So the
# conversion is asked of `date` with the ZONE INSIDE THE STRING, which
# is DST-correct on both sides of the change rather than carrying
# today's offset a week forward.
HOUSE_TZ="${WAYMARK_HOUSE_TZ:-America/Denver}"
den_utc() { # den_utc <YYYY-MM-DD> <HH:MM> — a local wall clock, as UTC
  date -u -d "TZ=\"$HOUSE_TZ\" $1 $2" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
    || iso "$(to_s "$1T$2:00$(TZ="$HOUSE_TZ" date +%z 2>/dev/null || echo +0000)")"
}
DEN_NOW="$(TZ="$HOUSE_TZ" date +'%Y-%m-%d %H:%M %Z (%A)' 2>/dev/null || echo "unknown")"
# THE DAY TABLE: the next seven local days, each already converted at a
# 9am and a 7pm local start, so a small model picks a row instead of
# doing timezone arithmetic in its head. It starts TOMORROW, because a
# wall clock earlier today is already past and every prepared instant
# has to be in the future of the run time at the top of the manifest.
DAY_TABLE="$(
  i=1
  printf '['
  while [ "$i" -le 7 ]; do
    d="$(TZ="$HOUSE_TZ" date -d "+$i day" +%Y-%m-%d 2>/dev/null || true)"
    [ -n "$d" ] || break
    if [ "$i" -gt 1 ]; then printf ','; fi
    jq -nc --arg d "$d" \
       --arg wd "$(TZ="$HOUSE_TZ" date -d "$d" +%A 2>/dev/null || echo '?')" \
       --arg m "$(den_utc "$d" 09:00)" --arg e "$(den_utc "$d" 19:00)" \
       '{date:$d, weekday:$wd, at_0900_local:$m, at_1900_local:$e}'
    i=$((i + 1))
  done
  printf ']'
)"
jq -e 'type == "array"' <<< "$DAY_TABLE" >/dev/null 2>&1 || DAY_TABLE='[]'
# THE SESSION BLOCK: the next Saturday morning in the household's zone,
# 09:00–11:00 local. `next Saturday` read from a Saturday is the one
# after, so the hold is always ahead of the run; and if the conversion
# degrades anyway (a `date` without the zone syntax), a block that
# landed in the past is replaced rather than shipped.
SESSION_DATE="$(TZ="$HOUSE_TZ" date -d 'next Saturday' +%Y-%m-%d 2>/dev/null || true)"
[ -n "$SESSION_DATE" ] || SESSION_DATE="$(TZ="$HOUSE_TZ" date -d '+3 day' +%Y-%m-%d 2>/dev/null || date -u +%Y-%m-%d)"
SESSION_START="$(den_utc "$SESSION_DATE" 09:00)"
SESSION_END="$(den_utc "$SESSION_DATE" 11:00)"
if [ "$(to_s "$SESSION_START")" -le "$NOW_S" ]; then
  SESSION_DATE="$(TZ="$HOUSE_TZ" date -d '+3 day' +%Y-%m-%d 2>/dev/null || date -u +%Y-%m-%d)"
  SESSION_START="$(den_utc "$SESSION_DATE" 09:00)"
  SESSION_END="$(den_utc "$SESSION_DATE" 11:00)"
fi
SESSION_WD="$(TZ="$HOUSE_TZ" date -d "$SESSION_DATE" +%A 2>/dev/null || echo "that morning")"

# A PROBE NEVER FAILS THE RUN — but a probe that fails SILENTLY is worse
# than one that fails loudly: the first cut of this block swallowed a jq
# scoping bug behind 2>/dev/null and printed a confident manifest with
# zero work orders on a house that had two. So every probe keeps its
# refusal to abort, and its stderr goes somewhere a person can read.
PROBE_ERRS="$D/probe_errors.log"; : > "$PROBE_ERRS"
probe_stumbled() { echo "  ! probe $1 stumbled — see $PROBE_ERRS" >&2; }

# ── the words: search keys, and whether a value touches a subject ────
# THE SHORT KEY (waymark-jux). The first real work order queried both
# rigs with the whole event title — "Breakfast with Kev Gallagher" —
# and got nothing from either; "Gallagher" alone found the friend who
# moved here in 2024 and the breakfast before this one. A title is a
# household's own sentence, not a search term, so the keys are DERIVED:
# the capitalized tokens that survive the stopword list and the generic
# calendar words (Breakfast, Meeting, Call…), a SURNAME first — the
# second of two adjacent capitalized tokens — then the rest
# longest-first, and when a line carries no name at all, the line minus
# stopwords. Two keys at most, tried in that order.
JQ_KEYS='
  def wm_stop: ["a","an","and","are","as","at","be","but","by","did","do",
                "does","for","from","get","got","had","has","have","he",
                "her","him","his","how","i","if","in","into","is","it",
                "its","me","my","no","not","of","off","on","or","our",
                "out","over","per","she","so","that","the","their","them",
                "then","they","this","to","up","us","via","was","we",
                "were","what","when","where","which","who","why","will",
                "with","would","you","your"];
  def wm_generic: ["breakfast","brunch","lunch","dinner","supper","coffee",
                   "tea","drinks","meeting","meet","call","zoom",
                   "appointment","appt","party","birthday","anniversary",
                   "visit","trip","event","reminder","pickup","pick","drop",
                   "dropoff","task","todo","time","date","day","days","week",
                   "weekend","today","tomorrow","tonight","morning","evening",
                   "afternoon","night","game","practice","class","session",
                   "review","check","schedule","plan","planning","sync",
                   "standup","catchup","catch","followup","follow","update",
                   "note","notes"];
  def wm_tokens: (tostring | [ splits("[^A-Za-z0-9]+") ] | map(select(length > 0)));
  def wm_norm: (ascii_downcase | gsub("[^a-z0-9]"; ""));
  def wm_dedupe: reduce .[] as $x
    ([]; . as $acc | ($x | wm_norm) as $n
         | if ([ $acc[] | wm_norm ] | index($n)) then $acc else $acc + [$x] end);
  def wm_keys:
    wm_tokens as $t
    | (wm_stop + wm_generic) as $drop
    | ([ range(0; ($t | length))
         | {i:., w:$t[.], lc:($t[.] | wm_norm)}
         | select(.w | test("^[A-Z][A-Za-z]{2,}$"))
         | . as $c | select(($drop | index($c.lc)) == null) ]) as $caps
    | ([ $caps[] | .i ]) as $ci
    | ([ $caps[] | . as $c | select(($ci | index($c.i - 1)) != null) | .w ]) as $surnames
    | (([ $caps[] | .w ] - $surnames) | sort_by(-(length))) as $rest
    | ($surnames + $rest) as $all
    # a key of three letters or fewer is a SUBSTRING, and IMAP TEXT
    # search is substring search: "Kev" answered 248 messages — the
    # whole mailbox, dressed as material. Short keys are used only when
    # the line offers nothing longer.
    | ([ $all[] | select(length >= 4) ]) as $long
    | (if ($long | length) > 0 then $long else $all end) as $names
    | (if ($names | length) > 0 then $names
       else ([ $t[] | . as $w | select(($drop | index($w | wm_norm)) == null) ]
             | join(" ") | if (length > 0) then [.] else [] end)
       end)
    | map(select(length > 0)) | wm_dedupe | .[0:2];
  # the words a value and a subject can be compared on: normalized, four
  # letters or longer, stopwords out — "the" is not a match
  def wm_words: wm_tokens | map(wm_norm) | map(select(length >= 4))
                | [ .[] as $w | select((wm_stop | index($w)) == null) | $w ]
                | unique;
  # A value OWNS the words of its name, of every activity it loves, and
  # the LONG words of what it says. Six letters or more from `says` is
  # not fussiness: a value whose prose reads "a long healthy life … the
  # God of War game" otherwise owns "long" and "game", and matched a
  # woodworking task on "long" — prose filler is short, and the words
  # that actually name a value (woodworking, appointments, caregivers,
  # grandchildren) are long.
  def wm_value_words($v):
    (([ ($v.name // ""), (($v.loved // []) | join(" ")) ] | join(" ") | wm_words)
     + (($v.says // "") | wm_words | map(select(length >= 6))))
    | unique;
  # VALUE-FIT (waymark-jux): does any live value own a word this subject
  # says? Nothing fits is a legitimate answer, and the caller turns it
  # into a journal-only order rather than demanding an outcome that
  # would have to invent the value it serves.
  def wm_value_fit($subject; $values):
    ($subject | wm_words) as $sw
    | [ $values[] | . as $v
        | (wm_value_words($v)) as $vw
        | ([ $vw[] as $w | select(($sw | index($w)) != null) | $w ]) as $shared
        | select(($shared | length) > 0)
        | {self:$v.self, id:$v.id, name:$v.name,
           state:($v.state // "declared"),
           matched:($shared | sort_by(-(length)) | .[0])} ]
    | .[0:1];
  # A value the house has only been OBSERVED to hold is a lawful thing
  # to compose against: `outcome/names-a-value` holds observed and
  # declared alike and refuses only retired, and the crown ranks an
  # observed value LOWER rather than turning the bundle away. What it
  # must not do is pass silently as something the household said in so
  # many words — so every order that lands on one says which it is, in
  # the same sentence that names it.
  def wm_value_note($f):
    "value: " + $f.self + " (" + $f.name + ") — matched on \"" + $f.matched + "\"."
    + (if (($f.state // "declared") == "observed")
       then " That value is OBSERVED, not affirmed: the house has not said it in so many words, only its record has. Name it anyway — `names-a-value` holds an observed value, and the crown ranks it lower rather than refusing it — and say in the goal that it serves a reading of this household rather than a word the household gave."
       else "" end);
'
# One rig answers with a JSON array, one with objects side by side, one
# with an envelope around them, one with plain lines — and `fromjson`
# over the concatenation of two objects fails, which is what once
# printed JSON fragments at the model. Each content part is parsed on
# its own; `headers` is in the envelope list because emila answers a
# fruitless search with {"total":0,"headers":[]}, which is NOT a hit.
JQ_ROWS='
  def wm_rows:
    ((.content // []) | map(.text? // empty)) as $texts
    | [ $texts[]
        | (try fromjson catch null) as $j
        | if   ($j | type) == "array"  then $j[]
          elif ($j | type) == "object"
            then (($j.results // $j.messages // $j.hits // $j.items
                   // $j.threads // $j.chats // $j.headers) as $arr
                  | if ($arr | type) == "array" then $arr[] else $j end)
          else (split("\n")[] | select((gsub("\\s"; "") | length) > 0))
          end ];
  def wm_one:
    if type == "object"
    then ([ (.date // .last_message_date // .Date // .received // empty),
            (.from // .sender_name // .sender // .chat_title // .title
             // .From // .thread_hash // empty),
            (.subject // .Subject // .text // .snippet // .preview
             // .last_message_preview // .body // empty) ]
          | map(tostring) | map(select(length > 0)) | join(" | ")) as $l
         | (if ($l | length) > 0 then $l else tostring end)
    else tostring end;
  def wm_trim: gsub("\\s+"; " ") | .[0:200];
'

# The GATE SEARCH tool, one per rig that ANSWERED the liveness probe
# above (waymark-idw): the tool whose schema takes a free-text `query`
# and requires nothing else — `emila__search` and
# `tgram__search_all_chats` today; `messa` lists threads and searches
# nothing, so it contributes no material, and that is not a fault. The
# schemas are read off gate.json rather than named here, because Gate's
# tool list is its aggregation and it changes without telling us.
: > "$D/gate_search_tools.tsv"
if [ -z "${WAYMARK_NO_GATE_PROBE:-}" ]; then
  jq -r --slurpfile rigs "$D/gate_rigs.json" '
    ([ ($rigs[0] // [])[] | select(.answered) | .rig ]) as $live
    | (.links // {}) | to_entries
    | map({name:.key, rig:(.key|split("__")|.[0]), short:((.key|split("__")|.[1]) // ""),
           props:((.value.input.properties // {})|keys),
           required:(((.value.input.required // []))|map(select(. != "why")))})
    | map(. as $t | select(($live | index($t.rig)) != null))
    | map(select((.props | index("query")) != null))
    | map(select(((.required - ["query"]) | length) == 0))
    | map(. as $t | $t + {rank: (if ($t.short | test("search")) then 0 else 1 end)})
    | group_by(.rig) | map(sort_by(.rank, (.name|length), .name) | .[0])
    | .[] | [.rig, .name, (.props|join(","))] | @tsv' \
    "$R/gate.json" >> "$D/gate_search_tools.tsv" 2>>"$PROBE_ERRS" || probe_stumbled gate-search-tools
fi

gate_call() { # gate_call <outfile> <tool> <json-args> — POST, echoes the code
  local code
  code="$(curl -sS --max-time 30 -o "$1" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Waymark-Grant: $GRANT" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "$3" "$BASE/api/-/gate/$2" 2>/dev/null)" || true
  printf '%s' "${code:-000}"
}

gate_search() { # gate_search <outfile.json> <imap-prefix> <key> [key]
  # READ ONLY, and never fatal: a rig that refuses contributes its
  # SENTENCE instead of its hits, which is exactly what the model must
  # say in place of filler. At most three hits per rig, each flattened
  # to one line and cut at 200 characters — a manifest is not a mailbox,
  # and a message body copied into a row is a body in the house's
  # record. WAYMARK_NO_GATE_PROBE=1 skips the material entirely.
  #
  # UP TO TWO KEYS per rig (waymark-jux), tried shortest-distinctive
  # first and stopping at the first key that finds anything: a second
  # key costs a call only where the first came back empty, which keeps
  # the three-hits-per-rig cap exactly where it was. A rig that REFUSED
  # is not asked again — it will refuse the second key too.
  local out="$1" pre="$2"; shift 2
  local acc; acc="$(mktemp)"; : > "$acc"
  if [ -z "${WAYMARK_NO_GATE_PROBE:-}" ] && [ -s "$D/gate_search_tools.tsv" ] && [ "$#" -gt 0 ]; then
    local rig tool props args hit code k last tried
    while IFS="$(printf '\t')" read -r rig tool props; do
      [ -n "$tool" ] || continue
      last=""; tried=""
      for k in "$@"; do
        [ -n "$k" ] || continue
        tried="${tried:+$tried, }$k"
        # emila speaks IMAP, where a bare phrase is a syntax error; every
        # other rig takes the phrase as it stands
        args="$(jq -nc --arg q "$k" --arg pre "$pre" --arg rig "$rig" --arg props "$props" '
          ($props | split(",")) as $p
          | {query: (if $rig == "emila" then ($pre + "TEXT \"" + $q + "\"") else $q end),
             why: "waymark sitting: material for one work order"}
          + (if ($p|index("count"))            then {count:3}            else {} end)
          + (if ($p|index("results_per_chat")) then {results_per_chat:3} else {} end)
          + (if ($p|index("dialog_limit"))     then {dialog_limit:10}    else {} end)
          + (if ($p|index("limit"))            then {limit:3}            else {} end)')"
        hit="$(mktemp)"
        code="$(gate_call "$hit" "$tool" "$args")"
        last="$(jq -c --arg rig "$rig" --arg tool "$tool" --arg code "$code" --arg q "$k" \
          "$JQ_ROWS"'
          (($code == "200") and (.isError != true)) as $ok
          | (((.content // []) | map(.text? // empty)) | join("\n")) as $t
          | (wm_rows) as $rows
          | {rig:$rig, tool:$tool, query:$q, answered:$ok,
             hits: (if $ok then ($rows[0:3] | map(wm_one | wm_trim)) else [] end),
             refusal: (if $ok then null
                       else ((if ($t | length) > 0 then $t
                              else ((.detail // .title // ("HTTP " + $code)) | tostring) end)
                             | gsub("\\s+"; " ") | .[0:300]) end)}' "$hit" 2>/dev/null)" \
          || last=""
        [ -n "$last" ] || last="$(jq -nc --arg rig "$rig" --arg tool "$tool" --arg q "$k" \
             '{rig:$rig, tool:$tool, query:$q, answered:false, hits:[],
               refusal:"no answer came back from the rig"}')"
        rm -f "$hit"
        if [ "$(printf '%s' "$last" | jq '(.hits // []) | length')" -gt 0 ]; then break; fi
        if [ "$(printf '%s' "$last" | jq -r '.answered')" != "true" ]; then break; fi
      done
      if [ -n "$last" ]; then
        printf '%s' "$last" | jq -c --arg t "$tried" '. + {keys_tried: ($t | split(", "))}' >> "$acc"
      fi
    done < "$D/gate_search_tools.tsv"
  fi
  jq -s '.' "$acc" > "$out"
  rm -f "$acc"
}

# ── ONE named household thread, read once (waymark-jux, waymark-is7) ─
# What an event needs beforehand is said where a household actually
# says it — the spouse/family chat — and a keyword search over all
# chats never sees a line that names the event obliquely ("what time
# tomorrow?"). Two probes read that thread now: an EVENT order wants
# the week AROUND its subject, and a COMMITMENTS order wants everything
# said in it. So the picking and the history read are one function, and
# the callers differ only in how much they ask for and what they keep.
#
# Nothing is hardcoded: the per-chat HISTORY tool is the one whose only
# required argument is a chat id, its listing partner is the
# argument-free tool on the same rig that lists chats, and the chat is
# a roster companion's thread when the roster names one, else the most
# recently active thread that is not a bot. No such pair in gate.json
# means no thread material, which is not a fault.
gate_chat_history() { # gate_chat_history <raw-outfile> <limit> — echoes "rig\ttool\ttitle"
  local raw="$1" limit="$2"
  : > "$raw"
  [ -z "${WAYMARK_NO_GATE_PROBE:-}" ] || return 0
  local pair rig list_tool hist_tool
  pair="$(jq -r --slurpfile rigs "$D/gate_rigs.json" '
    ([ ($rigs[0] // [])[] | select(.answered) | .rig ]) as $live
    | ((.links // {}) | to_entries
       | map({name:.key, rig:(.key|split("__")|.[0]), short:((.key|split("__")|.[1]) // ""),
              props:((.value.input.properties // {})|keys),
              required:(((.value.input.required // []))|map(select(. != "why")))})
       | map(. as $x | select(([ $live[] ] | index($x.rig)) != null))) as $t
    | ([ $t[] | select((.required | length) == 0)
              | select(.short | test("chats|dialogs|threads"))
              | . + {rank: (if (.short | test("chats|dialogs")) then 0 else 1 end)} ]
       | sort_by(.rank, .name)) as $lists
    | ([ $t[] | select((.required | length) == 1)
              | select(.required[0] | test("chat|dialog|thread"))
              | select((.props | index("query")) == null) ]) as $hists
    | [ $lists[] | . as $l | ($hists[] | select(.rig == $l.rig)) as $h
        | [$l.rig, $l.name, $h.name] ] | .[0] // [] | @tsv' \
    "$R/gate.json" 2>>"$PROBE_ERRS" || true)"
  [ -n "$pair" ] || return 0
  IFS="$(printf '\t')" read -r rig list_tool hist_tool <<< "$pair"
  [ -n "${hist_tool:-}" ] || return 0

  local lf code chat
  lf="$(mktemp)"
  code="$(gate_call "$lf" "$list_tool" '{"limit":20,"why":"waymark sitting: which household thread to read"}')"
  chat="$(jq -r --slurpfile people "$D/companions.json" --arg code "$code" "$JQ_ROWS"'
    (($code == "200") and (.isError != true)) as $ok
    | if ($ok | not) then "" else
      ([ ($people[0] // [])[] | (.name // "") | ascii_downcase | select(length > 0) ]) as $names
      | [ wm_rows[] | select(type == "object")
          | select(((.title // .name // .chat_title // "") | tostring | length) > 0)
          | {title:((.title // .name // .chat_title) | tostring),
             id:((.id // .chat_id // .thread_hash // "") | tostring),
             hash:((.thread_hash // "") | tostring),
             username:((.username // "") | tostring),
             at:((.last_message_date // .date // "") | tostring)}
          | select((.username | ascii_downcase | test("bot$")) | not)
          | select((.title   | ascii_downcase | test("bot$")) | not)
          | . as $c
          | . + {known: ([ $names[] as $n
                           | select(($c.title | ascii_downcase) | contains($n)) ] | length)} ]
      | ([ .[] | select(.known > 0) ]) as $known
      | ((if ($known | length) > 0 then $known else . end) | max_by(.at))
      | if . == null then "" else ([.title, .id, .hash] | @tsv) end
      end' "$lf" 2>>"$PROBE_ERRS" || true)"
  rm -f "$lf"
  [ -n "$chat" ] || return 0
  local title cid chash cand hf key
  IFS="$(printf '\t')" read -r title cid chash <<< "$chat"
  key="$(jq -r --arg h "$hist_tool" '((.links[$h].input.required // []) | map(select(. != "why")))[0] // "chat_id"' "$R/gate.json")"
  # the listing answers a title, a hash and an id and the history tool
  # takes exactly one of them — which one is the rig's business, so all
  # three are tried and the first that comes back with MESSAGES wins. A
  # 200 carrying nothing is a wrong handle, not an empty week.
  for cand in "$title" "$chash" "$cid"; do
    [ -n "$cand" ] || continue
    hf="$(mktemp)"
    code="$(gate_call "$hf" "$hist_tool" "$(jq -nc --arg id "$cand" --arg key "$key" --argjson lim "$limit" \
      '{($key): $id, limit: $lim, why: "waymark sitting: the last week of the household thread"}')")"
    if [ "$code" = "200" ] \
       && [ "$(jq "$JQ_ROWS"'if (.isError // false) then 0 else (wm_rows | length) end' "$hf" 2>/dev/null || echo 0)" -gt 0 ]; then
      cp "$hf" "$raw"; rm -f "$hf"
      printf '%s\t%s\t%s' "$rig" "$hist_tool" "$title"
      return 0
    fi
    rm -f "$hf"
  done
  return 0
}

# the EVENT order's slice of that thread: the three messages that say
# one of the order's keys, or the three most recent when none does — a
# week of a household thread is ambient context either way.
gate_thread() { # gate_thread <outfile.json> [key...]
  local out="$1"; shift
  local keys_json; keys_json="$(printf '%s\n' "$@" | jq -R '.' | jq -sc 'map(select(length > 0))')"
  echo '[]' > "$out"
  local raw meta rig tool title
  raw="$(mktemp)"
  meta="$(gate_chat_history "$raw" 20)"
  if [ -n "$meta" ]; then
    IFS="$(printf '\t')" read -r rig tool title <<< "$meta"
    jq -c --arg rig "$rig" --arg tool "$tool" \
      --arg q "the $title thread, last 7 days" --arg since "$SINCE_7D" \
      --argjson keys "$keys_json" "$JQ_ROWS"'
      ([ $keys[] | ascii_downcase ]) as $k
      | ([ wm_rows[] | select(type == "object")
           | ((.date // .last_message_date // "") | tostring) as $d
           | select($d == "" or (($d | sub(" "; "T") | .[0:19]) >= ($since | .[0:19])))
           | . as $m
           | (($m | wm_one) | ascii_downcase) as $hay
           # the three the ORDER is about, when the thread says them;
           # otherwise the three most recent — a week of a household
           # thread is ambient context either way
           | {line:($m | wm_one | wm_trim), at:$d,
              on_key: ([ $k[] as $w | select($hay | contains($w)) ] | length)} ]
         | sort_by([(0 - .on_key)])) as $rows
      | if ($rows | length) == 0 then empty
        else {rig:$rig, tool:$tool, query:$q, answered:true,
              keys_tried:[$q],
              hits: ($rows[0:3] | map(.line)), refusal:null}
        end' "$raw" 2>/dev/null | jq -s '.' > "$out" || echo '[]' > "$out"
  fi
  rm -f "$raw"
  return 0
}

# ── COMMITMENT LANGUAGE (waymark-is7) ────────────────────────────────
# A line counts as a candidate only when it says BOTH a WHEN and an
# ASK. Either alone is noise: "thanks!" carries a time and promises
# nothing, and "can you" with no day attached is a conversation rather
# than something the calendar or the list is missing. The pair is what
# makes a line a commitment somebody is holding in their head.
JQ_COMMIT='
  def wm_when_re: "(mon|tues|wednes|thurs|fri|satur|sun)day|tomorrow|tonight|next week|this (week|weekend)|\\b[0-9]{1,2}(:[0-9]{2})? ?(am|pm)\\b|\\b[0-9]{1,2}/[0-9]{1,2}\\b|\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? ?[0-9]{1,2}\\b|\\bat [0-9]{1,2}\\b";
  def wm_ask_re: "can you|could you|would you|will you|we need|i need|you need|don.t forget|remember to|remind|appointment|\\bdue\\b|deadline|rsvp|sign ?up|drop ?off|pick ?up|bring |we have|scheduled|reschedul|meeting|confirm|\\bbook(ed|ing)?\\b|\\brenew";
  def wm_commits($l): ($l | ascii_downcase) as $x
    | ($x | test(wm_when_re)) and ($x | test(wm_ask_re));
'

# The two places a household commits: the thread it actually talks in,
# read whole for the last seven days, and the inbox, asked for
# everything SINCE that date. The inbox arm speaks IMAP — SINCE is a
# search KEY, not a phrase — so it goes to the same rig `gate_search`
# already speaks IMAP to; a rig that only takes a phrase has no way to
# ask for "everything since Friday" and contributes nothing here, which
# is not a fault. READ TOOLS ONLY, and every line is trimmed to the
# same 200 characters every other Gate hit is: the manifest carries it
# as MATERIAL, and what goes into a row is the paraphrase.
gate_commitments() { # gate_commitments <outfile.json>
  local out="$1"
  local acc; acc="$(mktemp)"; : > "$acc"
  if [ -z "${WAYMARK_NO_GATE_PROBE:-}" ]; then
    local raw meta rig tool title
    raw="$(mktemp)"
    meta="$(gate_chat_history "$raw" 100)"
    if [ -n "$meta" ]; then
      IFS="$(printf '\t')" read -r rig tool title <<< "$meta"
      jq -c --arg src "the $title thread" --arg since "$SINCE_7D" "$JQ_ROWS$JQ_COMMIT"'
        [ wm_rows[] | select(type == "object")
          | ((.date // .last_message_date // "") | tostring) as $d
          | select($d == "" or (($d | sub(" "; "T") | .[0:19]) >= ($since | .[0:19])))
          | {line:(wm_one | wm_trim),
             text:(((.text // .body // .snippet // .preview // .subject // "") | tostring) | wm_trim),
             at:$d, source:$src}
          | select(wm_commits(.line)) ] | .[]' "$raw" >> "$acc" 2>>"$PROBE_ERRS" || true
    fi
    rm -f "$raw"
    local irig itool iprops iargs ihit code
    while IFS="$(printf '\t')" read -r irig itool iprops; do
      [ "$irig" = "emila" ] || continue
      iargs="$(jq -nc --arg q "${IMAP_SINCE% }" --arg props "$iprops" '
        ($props | split(",")) as $p
        | {query: $q, why: "waymark sitting: commitments in the last seven days of mail"}
        + (if ($p | index("count")) then {count: 10} else {} end)')"
      ihit="$(mktemp)"
      code="$(gate_call "$ihit" "$itool" "$iargs")"
      if [ "$code" = "200" ]; then
        jq -c --arg src "the inbox" "$JQ_ROWS$JQ_COMMIT"'
          if (.isError // false) then empty
          else [ wm_rows[]
                 | {line: (wm_one | wm_trim),
                    text: (((if type == "object"
                             then (.subject // .Subject // .snippet // .preview // .text // "")
                             else . end) | tostring) | wm_trim),
                    at: ((if type == "object" then (.date // .Date // "") else "" end) | tostring),
                    source: $src}
                 | select(wm_commits(.line)) ] | .[] end' "$ihit" >> "$acc" 2>>"$PROBE_ERRS" || true
      fi
      rm -f "$ihit"
    done < "$D/gate_search_tools.tsv"
  fi
  jq -s 'unique_by(.line) | sort_by(.at) | reverse | .[0:12]' "$acc" > "$out" 2>/dev/null \
    || echo '[]' > "$out"
  rm -f "$acc"
}

CAND="$D/work_order_candidates.jsonl"; : > "$CAND"

# the date arithmetic every probe needs, written once — a mirror writes
# dates in three shapes (a day, an instant, an instant with an offset)
JQ_DATES='
  def to_epoch:
    if . == null or . == "" then null
    else (tostring
          | (if (length == 10) then (. + "T00:00:00Z") else . end)
          | sub("\\.[0-9]+"; "")
          | sub("[+-][0-9]{2}:?[0-9]{2}$"; "Z")
          | (if test("Z$") then . else (. + "Z") end)
          | (try (strptime("%Y-%m-%dT%H:%M:%SZ") | mktime) catch null))
    end;
  def in_days($now): to_epoch as $t
    | if $t == null then null else ((($t - $now) / 86400) | floor) end;
  def when($now): (in_days($now)) as $d
    | if $d == null then "no date"
      elif $d < 0 then ((-$d|tostring) + " days ago")
      elif $d == 0 then "today"
      elif $d == 1 then "tomorrow"
      else ("in " + ($d|tostring) + " days") end;
'

# ── probe 1: session-of-like-tasks ───────────────────────────────────
# The owner's ideal outcome, 2026-08-28: "group like tasks into a work
# session and suggest I schedule it." Every other probe is
# single-subject and the ceiling text forbids extras, so nothing here
# ever batched. This one clusters the OPEN tasks by SHAPE — the four
# batches the household's own values name (pomodoro break phone calls,
# one errand loop, a paperwork hour, the shop) — and orders ONE outcome
# whose goal is the session itself, with the block already held on the
# calendar.
#
# SHAPE FIRST, THE LIST ONLY AS A LAST RESORT (waymark-dgh). A shared
# task list used to compete with the four shapes on size alone, and on
# 2026-08-28 it won: the order that shipped was "one task list
# (c07c9da8)" — a raw id for a goal, over a mixed bag of a realtor
# list, a lapsed life-insurance policy, a brake booster, a steering
# pump and a 401k. That is not one hour of one shape, it is a database
# handle wearing an hour as a costume. So the shapes are RANKED AHEAD:
# a same-list cluster ships only when no shape cluster has two free
# tasks, and it ships under the household's own NAME for the list, read
# from the task_lists rows. A list this grant cannot name is not a
# cluster at all — an hour nobody can say out loud is not an hour
# anyone holds.
#
# THE GARAGE IS NOT AN HOUR EITHER (waymark-dgh). A car repair —
# replace / repair / install against a vehicle word — is a parts-and-lift
# job of unknown length that ends when the part arrives, not at 11:00.
# It could have been given a shape of its own, a Saturday block in the
# driveway; it is not, because the block is the whole promise of this
# probe and a two-hour hold on "replace the brake booster" is a promise
# the household would decline. Worse, folded in beside three phone
# calls it poisons the hour those three would have said yes to. So car
# repairs are taken OUT of the clustering entirely and the order SAYS
# so; they stay visible to every other probe as the bare tasks they
# are.
#
# CLUSTERS ARE SEEN WHOLE; ORDERS ARE BUILT FROM WHAT IS FREE. A task a
# standing outcome already cites is not free: `not-a-twin` refuses a
# bundle that shares an evidence row with one that stands, so citing it
# would cost a round trip and teach nothing. But dropping it BEFORE the
# clustering would also hide the cluster — the house would look like it
# had no phone calls in it at all — so the cluster is formed over every
# open task and then split. The free ones become the evidence and the
# pieces; the cited ones ride the order as EXCLUDED, each beside the
# bundle that already speaks for it, so the model can see the shape it
# is only allowed to work half of. A cluster needs two FREE tasks to be
# worth an hour; when none has two, no order ships and the crowd-out
# line below says why.
#
# FIVE PIECES IS THE CEILING (waymark-dgh). The outcome wall takes 2–5
# pieces, and the session order used to prescribe one hold plus one
# raise per free task — six pieces on a cluster of five, refused at the
# door. So the block holds the calendar hold plus the FOUR nearest-due
# free tasks, cites exactly those, and names the rest for the next
# session in its NOTE rather than staging them.
jq -c --slurpfile cited "$D/cited.json" --slurpfile standing "$D/standing_outcomes.json" \
      --slurpfile values "$D/live_values.json" --slurpfile lists "$R/task_lists.json" \
      --argjson nows "$NOW_S" \
      --arg bstart "$SESSION_START" --arg bend "$SESSION_END" \
      --arg bday "$SESSION_WD" --arg bdate "$SESSION_DATE" --arg tz "$HOUSE_TZ" \
      "$JQ_DATES$JQ_KEYS"'
  ($cited[0] // []) as $c | ($standing[0] // []) as $st | ($values[0] // []) as $vals
  # the household name for each list; an id is a handle, not a name
  | ([ ($lists[0] // [])[]
       | {id:((.self // "") | split("/") | last),
          name:(((.display.title // .fields.title // .summary // "") | tostring)
                | sub("^\\s+"; "") | sub("\\s+$"; ""))}
       | select(.name != "") ]) as $listnames
  | [ .[] | (.fields // {}) as $f
          | select(($f.status // "open") == "open")
          | ((.display.title // $f.title // "") | tostring) as $t
          | select($t != "")
          | .self as $sf
          | {self:$sf, title:$t, due_at:($f.due_at // null),
             task_list:($f.task_list // null),
             cited: (($c | index($sf)) != null)} ] as $all_open
  # the garage, lifted out before anything is clustered
  | ([ $all_open[] | . as $x | ($x.title | ascii_downcase) as $l
       | select(($l | test("\\b(replace|repair|install|fix|swap|bleed|rotate|change)\\b"))
                and ($l | test("\\b(edge|odyssey|car|van|truck|brakes?|booster|pumps?|tires?|tyres?|oil|alternator|radiator|transmission)\\b"))) ]) as $garage
  | ([ $garage[] | .self ]) as $garage_ids
  | ([ $all_open[] | . as $x | select(($garage_ids | index($x.self)) == null) ]) as $open
  # The four shapes are the household own words for its batches, taken
  # off what its values say they love. The fifth is the task list, and
  # it is a fallback rather than a peer: a list is a set the household
  # already grouped, but the grouping can be a junk drawer, so it only
  # gets an hour when no shape has two free tasks in it.
  | [ $open[] | . as $x | ($x.title | ascii_downcase) as $l
      | ( (if ($l | test("^(call|contact|phone|ring)\\b"))
              or ($l | test("[0-9]{3}[-. ][0-9]{3}[-. ][0-9]{4}"))
           then "the phone calls" else empty end),
          (if ($l | test("^(pick up|pickup|drop off|dropoff|mail|buy|purchase|return|deliver|go to)\\b"))
           then "the errand loop" else empty end),
          # "board" is the shop word that leaks: a Trello board is not
          # lumber, and with shapes ranked ahead of lists it decided a
          # whole order on 2026-08-28. The software boards are named
          # and excluded rather than the word being dropped, because
          # every other board in this house is a piece of wood.
          (if ($l | test("woodwork|\\bshop\\b|\\bbuild|\\bsand\\b|glue|lumber|dowel|\\bboards?\\b|\\bsaw\\b|workbench"))
              and (($l | test("trello|kanban|\\bjira\\b|scrum|sprint")) | not)
           then "the shop" else empty end),
          (if ($l | test("\\bforms?\\b|application|insurance|401k|address|paperwork|policy|placard|\\btax\\b|account"))
           then "the paperwork hour" else empty end) ) as $shape
      | $x + {cluster:$shape, is_shape:true} ]
    + [ $open[] | select(.task_list != null) | . as $x
        | ([ $listnames[] | select(.id == ($x.task_list | tostring)) | .name ][0]) as $nm
        | select($nm != null and $nm != "")
        | $x + {cluster:("the " + $nm + " list"), is_shape:false} ]
  | group_by(.cluster)
  | map({cluster:.[0].cluster, is_shape:.[0].is_shape,
         free:[ .[] | select(.cited | not) ],
         excluded:[ .[] | select(.cited) ]})
  | map(select((.free | length) >= 2))
  | map(.free = (.free | sort_by([(if .due_at == null then 1 else 0 end), (.due_at // "")])))
  # a shape before a list, then most tasks, then the nearest due — the
  # biggest hour of one shape the house can actually have, soonest
  | sort_by([ (if .is_shape then 0 else 1 end),
              (0 - (.free | length)),
              (if .free[0].due_at == null then 1 else 0 end),
              (.free[0].due_at // "") ])
  | .[0:1]
  | map(. as $k
        | ($k.free[0:4]) as $held | ($k.free[4:]) as $later
        | ($held[0]) as $lead
        | (($held | length) | tostring) as $n
        | (if $k.is_shape then "shape" else "list" end) as $kindword
        | (wm_value_fit(([$k.cluster] + [ $held[] | .title ] | join(" ")); $vals)) as $fit
        | {probe:"session-of-like-tasks", rank:1,
           subject:$lead.self,
           subject_says:($k.cluster + " — " + $n + " open tasks of one " + $kindword),
           urgency_at:$lead.due_at,
           urgency_says:(if $lead.due_at
                         then ("the nearest of them is due " + ($lead.due_at|.[0:10])
                               + " (" + ($lead.due_at|when($nows)) + ")")
                         else "no due date on any of them" end),
           why:("These " + $n + " open tasks are one " + $kindword + " — " + $k.cluster
                + " — and the house is holding them as " + $n
                + " separate evenings. Gathered into one held block they are one hour, and an hour on the calendar is the thing the household would actually say yes to."),
           gate_keys:[],
           value_fit:$fit,
           material:{
             row:{self:$lead.self, title:$lead.title, due_at:$lead.due_at,
                  task_list:$lead.task_list, cluster:$k.cluster},
             siblings:[ $held[] | select(.self != $lead.self)
                        | {self, title, due_at, shares:("the same session: " + $k.cluster)} ],
             excluded:[ $k.excluded[] | . as $m
                        | {self:$m.self, title:$m.title,
                           cited_by:[ $st[] | select(((.evidence // []) | index($m.self)) != null) | .self ]} ],
             gate:null},
           write:(if ($fit|length) > 0
                  then {kind:"outcome", door:"POST /api/outcomes",
                        fields:["goal", "value_id", "evidence", "2-5 outcome_pieces"],
                        finding:("One held block in which " + $k.cluster
                                 + " are finished together — the session as the end-state, said the way the household would say it out loud (\"" + $bday + " morning: " + $k.cluster + ", done\"). Not any one of the tasks restated, and not a heading over the list."),
                        cite:([ $held[] | .self ]),
                        offer:null,
                        value_id:$fit[0].id,
                        pieces:([ {form:"create", target_kind:"event", target_id:null, target_action:null,
                                   says:("Hold " + $bday + " " + $bdate + ", 9:00 to 11:00, for " + $k.cluster),
                                   prepared:{title:($bday + " morning: " + $k.cluster),
                                             starts_at:$bstart, ends_at:$bend, calendar:"family"}} ]
                                 + [ $held[] | {form:"invoke", target_kind:"task",
                                                target_id:(.self|split("/")|last),
                                                target_action:"prioritize",
                                                says:("Raise \"" + .title + "\" into that block"),
                                                prepared:{priority:1}} ]),
                        note:(wm_value_note($fit[0])
                              + " The block is already computed: " + $bstart + " to " + $bend
                              + " — 9:00 to 11:00 on " + $bday + " " + $bdate + " in " + $tz
                              + ", rendered UTC and ahead of the run time at the top of this manifest. Move it only if the house plainly says otherwise, and keep it in that zone and in the future."
                              + " FIVE PIECES IS THE CEILING: the outcome wall takes 2 to 5, so this block is the hold plus the "
                              + $n + " nearest-due tasks, and a sixth piece would be refused at the door. Cite those "
                              + $n + " and no others."
                              + (if (($later|length) > 0)
                                 then (" NEXT SESSION, not this one: " + (($later|length)|tostring)
                                       + " more free tasks of this " + $kindword + " did not fit under that ceiling — "
                                       + ([ $later[] | "\"" + .title + "\" (" + .self + ")" ] | join(", "))
                                       + ". Leave them free and say in the goal that this hour covers the ones it holds; the next sitting can hold a second block for them.")
                                 else "" end)
                              + (if (($garage|length) > 0)
                                 then (" LEFT OUT OF EVERY SESSION: " + (($garage|length)|tostring)
                                       + " car-repair " + (if ($garage|length) == 1 then "task" else "tasks" end)
                                       + " — " + ([ $garage[] | "\"" + .title + "\"" ] | join(", "))
                                       + ". A brake booster or a steering pump is a parts-and-lift job that ends when the part arrives, not at 11:00, so it is not an hour anyone holds, and folded in here it would poison the hour these tasks would have said yes to. Do not cite them, do not stage pieces for them, and do not name them in the goal.")
                                 else "" end)
                              + (if (($k.excluded|length) > 0)
                                 then (" " + (($k.excluded|length)|tostring)
                                       + (if ($k.excluded|length) == 1
                                          then (" more task of this " + $kindword + " is listed")
                                          else (" more tasks of this " + $kindword + " are listed") end)
                                       + " above as EXCLUDED: a standing outcome already cites each of them, and `not-a-twin` refuses a bundle that shares an evidence row with one that stands. Do not cite them and do not stage pieces for them — say in the goal that this hour covers the ones that are free.")
                                 else "" end))}
                  else {kind:"journal", door:"POST /api/journals",
                        fields:["title", "body"],
                        finding:("No live value carries this. Write nothing at the outcome door; in the journal say what value an hour of " + $k.cluster + " would serve, in one sentence — that skip IS the answer."),
                        cite:([ $held[] | .self ]),
                        offer:null, value_id:null, pieces:[],
                        note:("No live value owns a word these tasks say, so a session composed here would have to invent the value it serves — the wrapper the last grading caught.")}
                  end)})
  | .[]' "$R/tasks.json" >> "$CAND" 2>>"$PROBE_ERRS" || probe_stumbled session-of-like-tasks

# ── probe 2: commitments-in-messages ─────────────────────────────────
# The owner's ideal outcome, 2026-08-28: "digging through old messages
# to find tasks/calendar events." A commitment said only in a text has
# no house address, and `cites-what-it-claims` means an insight cannot
# carry it — so it has exactly ONE lawful landing: a PIECE inside an
# outcome whose evidence cites house rows. The person who said it IS
# such a row (only companion_id needs affirmation; a person row is a
# lawful evidence address), and the source is named in the prose —
# "from Wellesley's text of Aug 27" — never copied into a row.
#
# Like the person probe, its CANDIDACY needs the outside world, so its
# reads happen here rather than after the ceiling; and like that probe
# it fills material.gate itself, so the ceiling pays for nothing twice.
# READ TOOLS ONLY. At most ONE order per run.
COMMIT_HITS="$D/commitment_candidates.json"; echo '[]' > "$COMMIT_HITS"
COMMIT_ORDER="$D/work_order_commitments.json"; echo 'null' > "$COMMIT_ORDER"
gate_commitments "$COMMIT_HITS"
if [ "$(jq 'length' "$COMMIT_HITS" 2>/dev/null || echo 0)" -gt 0 ]; then
  jq -n --slurpfile hits "$COMMIT_HITS" --slurpfile tasks "$R/tasks.json" \
        --slurpfile events "$R/events.json" --slurpfile people "$D/companions.json" \
        --slurpfile cited "$D/cited.json" --slurpfile values "$D/live_values.json" \
        --argjson nows "$NOW_S" --argjson days "$DAY_TABLE" --arg tz "$HOUSE_TZ" \
        "$JQ_DATES$JQ_KEYS"'
    ($hits[0] // []) as $h | ($tasks[0] // []) as $tk | ($events[0] // []) as $ev
    | ($people[0] // []) as $pp | ($cited[0] // []) as $c | ($values[0] // []) as $vals
    # DEDUPE AGAINST WHAT THE HOUSE ALREADY HOLDS, by key word and by
    # date. A line sharing a distinctive word with an open task or with
    # an event in the window around now is a line ABOUT a row that
    # exists — the household talking over its own calendar, not a
    # commitment nobody wrote down. So is a line that names a day an
    # event already sits on. Both are dropped, and what survives is
    # only what the house has no row for.
    # The RAW title on both sides, and never a bare figure: a mirror
    # appends the date to display.title, so "2026" is a word every row
    # in the house shares, and matching on it would drop every candidate
    # ever written. The words that identify a subject have letters in
    # them.
    | ([ $tk[] | select((.fields.status // "open") == "open")
               | ((.fields.title // .display.title // "") | tostring) ]) as $ttl
    | ([ $ev[] | (.fields.starts_at // .fields.date // null) as $s
               | select($s != null)
               | (($s | in_days($nows)) // -999) as $d
               | select($d >= -7 and $d <= 21)
               | {t:((.fields.title // .display.title // "") | tostring), day:($s | .[0:10])} ]) as $evs
    | ([ ($ttl[] | wm_words), ($evs[] | .t | wm_words) ] | flatten
       | map(select(test("[a-z]"))) | unique) as $house_words
    | ([ $evs[] | .day ]) as $house_days
    | [ $h[] | . as $m
        # the message BODY decides the match: the rendered line carries
        # whatever timestamp the rig printed in front of it, and a date
        # is not a subject
        | ((if (($m.text // "") | length) > 0 then $m.text else $m.line end)
           | wm_words | map(select(test("[a-z]")))) as $mw
        | select([ $mw[] as $w | select(($house_words | index($w)) != null) ] | length == 0)
        | select([ $house_days[]
                   | ((. | .[5:7] | sub("^0"; "")) + "/" + (. | .[8:10] | sub("^0"; ""))) as $md
                   | select($m.line | test($md)) ] | length == 0)
        | $m ] as $fresh
    | select(($fresh | length) > 0)
    # THE EVIDENCE ADDRESS. The order is anchored on the companion the
    # traffic names — the person row is what makes the write lawful,
    # and machine dedupe drops one a standing row already speaks for.
    # The SOURCE is read as well as the line, and that is not belt and
    # braces: a per-chat history tool answers a date and a body and no
    # sender at all, so in the household thread the only place the name
    # appears is the thread title. In the inbox it is the other way
    # round — the source is just "the inbox" and the sender rides the
    # line. When neither says a companion there is no house address to
    # cite and so no order: the honest answer, not a fault.
    | ([ $pp[] | . as $p | select(($c | index($p.self)) == null)
               | select([ $fresh[]
                          | ((.source + " " + .line) | ascii_downcase) as $hay
                          | select($hay | contains($p.name | ascii_downcase)) ] | length > 0) ]) as $named
    | select(($named | length) > 0)
    | $named[0] as $who
    | (($fresh | length) | tostring) as $n
    | (wm_value_fit(([ $fresh[] | .line ] | join(" ")); $vals)) as $fit
    | {probe:"commitments-in-messages", rank:2,
       subject:$who.self,
       subject_says:($who.name + " — " + $n + (if $n == "1" then " commitment" else " commitments" end)
                     + " living only in a message"),
       urgency_at:null,
       urgency_says:("said in the last seven days of " + $fresh[0].source + "; no row in the house holds it"),
       why:("The last seven days of the household thread and of the inbox carry " + $n
            + (if $n == "1" then " line that names" else " lines that name" end)
            + " a day AND an obligation, and no open task and no coming event says any of their words. A commitment that lives only in a message is invisible to the rank and to whoever is not holding the phone."),
       gate_keys:[],
       value_fit:$fit,
       material:{
         row:$who,
         siblings:[],
         excluded:[],
         gate:[{rig:"the household thread and the inbox",
                tool:"read tools only",
                query:"the last seven days", answered:true,
                keys_tried:["a day AND an obligation, said in one line"],
                hits:[ $fresh[] | (.at | .[0:16]) + " · " + .source + " · "
                                  + (if ((.text // "") | length) > 0 then .text else .line end) ],
                refusal:null}],
         # the clock rides along only when an instant is going to be
         # prepared: a journal-only order writes no date, and seven
         # lines of conversion under it are noise
         day_table:(if ($fit | length) > 0 then $days else [] end)},
       write:(if ($fit|length) > 0
              then {kind:"outcome", door:"POST /api/outcomes",
                    fields:["goal", "value_id", "evidence", "1-3 outcome_pieces"],
                    finding:("The commitment above KEPT — one goal in the household own words, naming what has to happen and by when. PARAPHRASE it: never the message sentence, and name the source in the prose (\"from " + $who.name + ", " + ($fresh[0].at | .[0:10]) + "\")."),
                    cite:[$who.self],
                    offer:null,
                    value_id:$fit[0].id,
                    pieces:[ {form:"create", target_kind:"task", target_id:null, target_action:null,
                              says:"The thing that has to be done, in the household own words",
                              prepared:{title:"<your paraphrase of the commitment — never the message own sentence>"}},
                             {form:"create", target_kind:"event", target_id:null, target_action:null,
                              says:"The hold on the calendar, when the line names a day",
                              prepared:{title:"<your paraphrase>",
                                        starts_at:"<a row from the household clock above>",
                                        ends_at:"<the same row, an hour later>",
                                        calendar:"family"}} ],
                    note:(wm_value_note($fit[0])
                          + " ONE outcome, and one piece per commitment, at most three. A piece title PARAPHRASES — a message body is never copied into a row, and a Gate line is never an address: cite " + $who.self
                          + " (a person row is a lawful evidence address) plus any house row you actually read, and name the source in prose. Every prepared instant is a wall clock in " + $tz
                          + " rendered UTC and after the run time at the top of this manifest — the household clock above has the next seven days already converted, so pick a row rather than doing the arithmetic.")}
              else {kind:"journal", door:"POST /api/journals",
                    fields:["title", "body"],
                    finding:("No live value carries these. Write nothing at the outcome door; in the journal say, in one sentence, what value a commitment like this would serve — that skip IS the answer."),
                    cite:[$who.self],
                    offer:null, value_id:null, pieces:[],
                    note:("No live value owns a word these lines say, so an outcome here would have to invent the value it serves. Say what value it would need; never copy the message into the journal either.")}
              end)}' > "$COMMIT_ORDER" 2>>"$PROBE_ERRS" || probe_stumbled commitments-in-messages
fi
jq -c 'select(type == "object")' "$COMMIT_ORDER" >> "$CAND" 2>>"$PROBE_ERRS" || probe_stumbled commitments-in-messages

# ── probe 1: bare-task-due-soon ──────────────────────────────────────
# The bare list is already the open, detail-less, unspoken-for tasks
# (the mirror sits at state=fresh whether its source says open or done,
# so :status is the word that decides — the Fable sitting of 2026-08-27
# read out 24 finished July chores when state alone was the filter).
# Nearest due_at wins; a task carrying no due date sorts last.
jq -c --slurpfile bare "$D/bare_tasks.json" --slurpfile cited "$D/cited.json" \
      --argjson nows "$NOW_S" "$JQ_DATES$JQ_KEYS"'
  ($cited[0] // []) as $c
  | . as $tasks
  | [ ($bare[0] // [])[] | . as $x | select(($c | index($x.self)) == null) ]
  | sort_by([(if .due_at == null then 1 else 0 end), (.due_at // "")])
  | .[0:1]
  | map(. as $t
        | ((first($tasks[] | select(.self == $t.self))) // null) as $row
        | ($row.fields // {}) as $f
        | {probe:"bare-task-due-soon", rank:3,
           subject:$t.self, subject_says:$t.title,
           urgency_at:$t.due_at,
           urgency_says:(if $t.due_at
                         then ("due " + ($t.due_at|.[0:10]) + " (" + ($t.due_at|when($nows)) + ")")
                         else "no due date" end),
           why:("An open task with no detail that nothing in the house speaks for"
                + (if $t.due_at then (", due " + ($t.due_at|when($nows))) else ", with no due date" end)
                + " — until a row says what it is for, the household holds a title and no next step."),
           gate_keys:($t.title | wm_keys),
           material:{
             row:{self:$t.self, state:$t.state, status:($f.status // "open"),
                  title:$t.title, due_at:($f.due_at // null),
                  source:($f.source // null), task_list:($f.task_list // null),
                  assignee_name:($f.assignee_name // null),
                  priority:($f.priority // null),
                  source_ui_href:($f.source_ui_href // null)},
             siblings:[ $tasks[]
                        | select(.self != $t.self)
                        | select((.fields.status // "open") == "open")
                        | select((($f.task_list != null) and (.fields.task_list == $f.task_list))
                                 or (($f.assignee != null) and (.fields.assignee == $f.assignee)))
                        | {self, title:((.display.title // .fields.title // "")|tostring),
                           due_at:(.fields.due_at // null),
                           shares:(if (($f.task_list != null) and (.fields.task_list == $f.task_list))
                                   then ("task_list " + ($f.task_list|tostring))
                                   else ("assignee " + (($f.assignee_name // $f.assignee)|tostring)) end)} ][0:6],
             gate:null},
           write:{kind:"insight", door:"POST /api/insights",
                  fields:["finding", "evidence", "offer_kind", "offer_id", "offer_action"],
                  finding:"One sentence carrying a FACT this task row does not already state — what it is really for, where/when/with what, or its real next physical step.",
                  cite:[$t.self],
                  offer:{offer_kind:"task", offer_id:($t.self|split("/")|last),
                         offer_action:"complete"}}})
  | .[]' "$R/tasks.json" >> "$CAND" 2>>"$PROBE_ERRS" || probe_stumbled bare-task-due-soon

# ── probe 2: event-without-prep ──────────────────────────────────────
# An event inside ten days that no outcome and no insight cites. A TASK
# cannot cite an address at all, so a task whose title carries a word of
# the event is not a citation — it is MATERIAL, and it is also the only
# light door in sight: an event declares no door a card can tap, so the
# order offers `complete` on that task when one exists, and when none
# does it says plainly that an insight here would be refused and names
# the two lawful answers instead.
jq -c --slurpfile cited "$D/cited.json" --slurpfile tasks "$R/tasks.json" \
      --slurpfile values "$D/live_values.json" \
      --argjson nows "$NOW_S" "$JQ_DATES$JQ_KEYS"'
  ($cited[0] // []) as $c | ($tasks[0] // []) as $tk | ($values[0] // []) as $vals
  | [ .[] | . as $x
          | (.fields.starts_at // .fields.date // null) as $start
          | select($start != null)
          | ($start | in_days($nows)) as $d
          | select($d != null and $d >= 0 and $d <= 10)
          | select(($c | index($x.self)) == null)
          # the RAW title, not display.title: a mirror appends the date
          # to the display line, and " — 2026-08-28" in a Gate query is
          # a search for nothing
          | {self, state, start:$start, days:$d,
             title:((.fields.title // .display.title // "") | tostring),
             location:(.fields.location // null),
             calendar:(.fields.calendar // null),
             all_day:(.fields.all_day // false),
             ends_at:(.fields.ends_at // .fields.end_date // null)} ]
  | map(select(.title != ""))
  | sort_by(.start) | .[0:1]
  | map(. as $e
        | ([ ($e.title | ascii_downcase | gsub("[^a-z0-9 ]"; " ") | split(" ")[])
             | select(length >= 5) ]) as $words
        | ([ $tk[] | select((.fields.status // "open") == "open")
                   | ((.display.title // .fields.title // "") | tostring) as $tt
                   | select($tt != "")
                   | select([ $words[] as $w | select($tt | ascii_downcase | contains($w)) ] | length > 0)
                   | {self, title:$tt, due_at:(.fields.due_at // null),
                      shares:"a word of the event title"} ][0:4]) as $near
        # VALUE-FIT (waymark-jux), tested BEFORE any outcome is ordered:
        # the event says its title, its location and the titles of the
        # rows beside it, and a value fits only if it owns one of those
        # words. (The CALENDAR name is left out on purpose: every row on
        # a calendar called "family" would otherwise match a value about
        # family.) A breakfast with a friend served none of the four
        # values this house holds, and the order that demanded one
        # anyway was asking for a wrapper.
        | (wm_value_fit(([$e.title, ($e.location // "")]
                         + [ $near[] | .title ] | join(" ")); $vals)) as $fit
        | {probe:"event-without-prep", rank:4,
           subject:$e.self, subject_says:$e.title,
           urgency_at:$e.start,
           urgency_says:("starts " + ($e.start|.[0:10]) + " (" + ($e.start|when($nows)) + ")"),
           why:("An event " + ($e.start|when($nows))
                + " that no outcome and no insight speaks for — nothing in the house says what it needs beforehand."),
           gate_keys:($e.title | wm_keys),
           # an event also gets the last week of the household thread
           gate_thread:true,
           value_fit:$fit,
           material:{
             row:{self:$e.self, state:$e.state, title:$e.title, starts_at:$e.start,
                  ends_at:$e.ends_at, all_day:$e.all_day,
                  location:$e.location, calendar:$e.calendar},
             siblings:$near,
             gate:null},
           # WHICH DOOR depends on whether an open task names the event,
           # and then on whether any live value carries it — neither is
           # the model to judge. An event declares no door a card can
           # tap, so an insight about an event with no task beside it
           # would have nothing LIGHT to offer and offers-something-light
           # would refuse it. With a task in hand the write is an insight
           # offering `complete` on that task. Without one: an OUTCOME
           # whose pieces create the prep WHEN A VALUE FITS, and when
           # none does, the honest write is no door write at all — the
           # journal sentence saying which value this would need.
           write:(if ($near|length) > 0
                  then {kind:"insight", door:"POST /api/insights",
                        fields:["finding", "evidence", "offer_kind", "offer_id", "offer_action"],
                        finding:("One sentence naming what " + $e.title + " needs prepared beforehand and by when — a fact, not its title restated."),
                        cite:([$e.self] + [ $near[] | .self ]),
                        offer:{offer_kind:"task", offer_id:($near[0].self|split("/")|last),
                               offer_action:"complete"},
                        note:null}
                  elif ($fit|length) > 0
                  then {kind:"outcome", door:"POST /api/outcomes",
                        fields:["goal", "value_id", "evidence", "2-5 outcome_pieces"],
                        finding:($e.title + " happens prepared — the end-state, not the event restated. Each piece OPENS a create door with its input already filled (the thing bought, booked, written, brought), and every date in it falls after the run time at the top of this manifest."),
                        cite:[$e.self],
                        offer:null,
                        value_id:$fit[0].id,
                        note:("No open task names this event, and an event admits no door a card can tap — so an INSIGHT here would have nothing light to offer and offers-something-light would refuse it. Write the OUTCOME instead, and name THIS value. "
                              + wm_value_note($fit[0])
                              + " If the event honestly needs nothing prepared, write nothing and say so in the journal: a skipped order is lawful, and a refused write is a wasted round trip.")}
                  else {kind:"journal", door:"POST /api/journals",
                        fields:["title", "body"],
                        finding:("No live value carries this. Write nothing at the outcome door; in the journal say what value this would need, in one sentence — that skip IS the answer."),
                        cite:[$e.self],
                        offer:null,
                        value_id:null,
                        note:("No open task names this event, so an insight here has nothing light to offer; and no live value owns a word of \"" + $e.title + "\" or where it happens, so an outcome would have to invent the value it serves — which is the wrapper the last grading caught. If the event honestly needs nothing prepared, that is the same answer: write nothing at the outcome door and say so in the journal. A skipped order is lawful, and a refused write is a wasted round trip.")}
                  end)})
  | .[]' "$R/events.json" >> "$CAND" 2>>"$PROBE_ERRS" || probe_stumbled event-without-prep

# ── probe 3: person-mentioned-unrecorded ─────────────────────────────
# A companion the roster affirms (state=current — an observed person is
# not a usable companion, so a house that has affirmed nobody yields
# nothing here, which is honest) whom the last seven days of Gate
# traffic names and no insight has written down in that window. This is
# the one probe whose CANDIDACY needs the outside world, so its searches
# run here rather than after the ceiling; three names at most, and the
# first name that comes back with hits wins.
PERSON_ORDER="$D/work_order_person.json"; echo 'null' > "$PERSON_ORDER"
people_seen="$(jq -r --slurpfile ins "$R/insights.full.json" --arg s "$SINCE_7D" '
  ([ ($ins[0] // [])[] | select((.meta.updated_at // "") >= $s) | (.data.evidence // [])[] ]) as $recent
  | [ .[] | . as $x | select(($recent | index($x.self)) == null) ] | .[0:3]
  | .[] | [.self, .name] | @tsv' "$D/companions.json" 2>/dev/null || true)"
if [ -n "$people_seen" ]; then
  # the loop reads from fd 3, not stdin, and the jq below is `-n`:
  # a jq with no input FILE reads stdin, and inside a `read` loop that
  # stdin is the here-string — so the first person who came back with
  # hits swallowed the rest of the roster as JSON, hit "Invalid numeric
  # literal", and `set -e` took the whole run down before any manifest
  # was written. The filter is a constructor and wants no input at all.
  while IFS="$(printf '\t')" read -r p_self p_name <&3; do
    [ -n "$p_name" ] || continue
    [ "$(jq -r 'type' "$PERSON_ORDER")" = "null" ] || break
    pg="$(mktemp)"
    # the short key here too: a roster name searched whole misses the
    # traffic that only ever writes the surname
    pk="$(jq -r -n --arg n "$p_name" "$JQ_KEYS"'($n | wm_keys) as $k
            | [ ($k[0] // $n), ($k[1] // "") ] | @tsv')"
    IFS="$(printf '\t')" read -r pk1 pk2 <<< "$pk"
    gate_search "$pg" "$IMAP_SINCE" "${pk1:-$p_name}" "${pk2:-}"
    if [ "$(jq '[.[] | select(.answered) | .hits[]] | length' "$pg")" -gt 0 ]; then
      jq -nc --slurpfile g "$pg" --arg self "$p_self" --arg name "$p_name" \
            --arg since "$SINCE_7D" --slurpfile people "$D/companions.json" '
        {probe:"person-mentioned-unrecorded", rank:5,
         subject:$self, subject_says:$name,
         urgency_at:null,
         urgency_says:("named in Gate traffic since " + ($since|.[0:10])),
         why:("The roster carries " + $name
              + ", the last seven days of mail and chat name them, and no insight has written any of it into the house."),
         gate_keys:[],
         material:{
           row:((first(($people[0] // [])[] | select(.self == $self))) // {self:$self, name:$name}),
           siblings:[],
           gate:$g[0]},
         write:{kind:"insight", door:"POST /api/insights",
                fields:["finding", "evidence", "offer_kind", "offer_id", "offer_action"],
                finding:("One sentence stating, as the house record, the FACT about " + $name
                         + " the traffic below carries — what changed, when, and for whom. State it; never judge it."),
                cite:[$self],
                offer:{offer_kind:"person", offer_id:($self|split("/")|last),
                       offer_action:"still_with_us"}}}' \
        > "$PERSON_ORDER"
    fi
    rm -f "$pg"
  done 3<<< "$people_seen"
fi
jq -c 'select(type == "object")' "$PERSON_ORDER" >> "$CAND" 2>>"$PROBE_ERRS" || probe_stumbled person-mentioned-unrecorded

# ── probe 4: value-with-no-live-outcome ──────────────────────────────
# A live value no offered or accepted outcome serves. Its material is
# the value, the activities it says the household loves, and the open
# tasks and coming events that name one of them — exactly the graph a
# real goal would have to come out of. It is the only probe whose
# expected write is an OUTCOME, and it is last in probe order on
# purpose: it carries no clock, so anything with a date outranks it.
jq -c --slurpfile out "$R/outcomes.full.json" --slurpfile cited "$D/cited.json" \
      --slurpfile tasks "$R/tasks.json" --slurpfile events "$R/events.json" \
      --argjson nows "$NOW_S" "$JQ_DATES$JQ_KEYS"'
  ($cited[0] // []) as $c
  | ([ ($out[0] // [])[] | select(.state=="offered" or .state=="accepted")
       | ((.data.value_id // "") | tostring) ]) as $served
  | ($tasks[0] // []) as $tk | ($events[0] // []) as $ev
  | [ .[] | . as $x
          | select(($c | index($x.self)) == null)
          | select(($served | index($x.id)) == null)
          | {self, state, name, id, says:(.says // null), loved:(.loved // [])} ]
  | .[0:1]
  # the value-fit test (waymark-jux) runs in this direction too: a row
  # belongs to this value when the row says one of the words the value
  # OWNS — a loved activity by phrase, or any word of its name, of its
  # loved list, or a long word of what it says.
  | map(. as $v
        | ([ ($v.loved // [])[] | ascii_downcase ]) as $love
        | (wm_value_words($v)) as $vw
        | (([ $tk[] | select((.fields.status // "open") == "open")
                    | ((.display.title // .fields.title // "")|tostring) as $t
                    | select($t != "")
                    # the TITLE decides the match and the detail only
                    # widens the phrase arm: a paragraph of detail
                    # shares a word with everything
                    | (($t + " " + ((.fields.detail // "")|tostring)) | ascii_downcase) as $hay
                    | ($t | wm_words) as $hw
                    | ([ $love[] as $w | select($hay | contains($w)) | $w ]
                       + [ $vw[] as $w | select(($hw | index($w)) != null) | $w ]) as $m
                    | select(($m|length) > 0)
                    | {self, kind:"task", title:$t, due_at:(.fields.due_at // null),
                       shares:("the value word \"" + $m[0] + "\""), matched:$m[0]} ]
            + [ $ev[] | ((.display.title // .fields.title // "")|tostring) as $t
                      | ((.fields.starts_at // .fields.date // null)) as $s
                      | select($t != "" and $s != null)
                      | select((($s|in_days($nows)) // -999) >= 0)
                      | ($t | ascii_downcase) as $hay
                      | ($hay | wm_words) as $hw
                      | ([ $love[] as $w | select($hay | contains($w)) | $w ]
                         + [ $vw[] as $w | select(($hw | index($w)) != null) | $w ]) as $m
                      | select(($m|length) > 0)
                      | {self, kind:"event", title:$t, starts_at:$s,
                         shares:("the value word \"" + $m[0] + "\""), matched:$m[0]} ])[0:6]) as $sib
        | {probe:"value-with-no-live-outcome", rank:6,
           subject:$v.self, subject_says:$v.name,
           urgency_at:null,
           urgency_says:"no clock — nothing standing serves this value",
           why:"The house declares this value and no offered or accepted outcome serves it, so the rank has nothing to raise for it.",
           gate_keys:[],
           value_fit:(if ($sib|length) > 0
                      then [{self:$v.self, id:$v.id, name:$v.name, matched:$sib[0].matched}]
                      else [] end),
           material:{
             row:{self:$v.self, state:$v.state, name:$v.name, loved:$v.loved},
             siblings:$sib,
             gate:null},
           # VALUE-FIT (waymark-jux) decides the door here too. A value
           # with nothing live under it — no open task, no coming event
           # carrying one of its own words — has no goal to compose from,
           # and an order that asked for one anyway would be asking for
           # an outcome invented out of the value name. The answer then
           # is the journal sentence, and the skip IS the answer.
           write:(if ($sib|length) > 0
                  then {kind:"outcome", door:"POST /api/outcomes",
                        fields:["goal", "value_id", "evidence", "2-5 outcome_pieces"],
                        finding:("A goal larger than any single row below, serving " + $v.name
                                 + " — an end-state the household would want, not a task restated. If the rows below do not imply one, this order is answered by writing NOTHING and saying so in the journal: that skip IS the answer."),
                        cite:([$v.self] + [ $sib[] | .self ]),
                        offer:null,
                        value_id:$v.id,
                        note:(wm_value_note({self:$v.self, id:$v.id, name:$v.name,
                                             state:$v.state, matched:$sib[0].matched})
                              + " That match is why the rows above are its material.")}
                  else {kind:"journal", door:"POST /api/journals",
                        fields:["title", "body"],
                        finding:("No live value carries this. Write nothing at the outcome door; in the journal say what value this would need, in one sentence — that skip IS the answer."),
                        cite:[$v.self],
                        offer:null,
                        value_id:null,
                        note:("Nothing live carries " + $v.name
                              + ": no open task and no coming event says one of its own words, so a goal composed here would come out of the value name and nothing else. Say in the journal what this value would need to become composable — one sentence. A skipped order is lawful; an invented outcome is not.")}
                  end)})
  | .[]' "$D/live_values.json" >> "$CAND" 2>>"$PROBE_ERRS" || probe_stumbled value-with-no-live-outcome

# ── the crowd-out warning (waymark-q23) ──────────────────────────────
# Machine dedupe is right and it can also leave nothing to do. On
# 2026-08-28 thirty-one standing offered bundles cited all sixteen open
# tasks: every cluster the session probe could see was already spoken
# for, so no session could ship and no new bundle could cite anything.
# That is not a fault in a probe, it is a fact about the fridge — and a
# manifest that only said "no work orders" would leave a run unable to
# tell a quiet house from a jammed one. So it is SAID, with the count,
# whenever standing offers speak for half the open tasks or more. The
# fix is not ours: a person's verdicts on those offers are what free
# the tasks again.
jq -n --slurpfile tasks "$R/tasks.json" --slurpfile standing "$D/standing_outcomes.json" '
  ([ ($tasks[0] // [])[] | select(((.fields.status // "open")) == "open") | .self ]) as $open
  | ([ ($standing[0] // [])[] | select(.state == "offered") ]) as $offered
  | ([ $offered[] | (.evidence // [])[] ] | unique) as $spoken
  | ([ $open[] | . as $t | select(($spoken | index($t)) != null) ]) as $held
  | if (($open | length) > 0) and ((($held | length) * 2) >= ($open | length))
    then {open_tasks:($open | length), held:($held | length), offered:($offered | length),
          says:("the fridge is crowding out composition: " + (($offered | length) | tostring)
                + " standing offered outcomes cite " + (($held | length) | tostring) + "/"
                + (($open | length) | tostring)
                + " open tasks, so machine dedupe frees nothing — no session and no new bundle can cite a task without twinning one that stands. The person verdicts on those offers are what free them; nothing this run writes can.")}
    else null end' > "$D/crowd_out.json" 2>>"$PROBE_ERRS" || echo 'null' > "$D/crowd_out.json"

# ── the ceiling: rank by urgency, keep the top N, and only THEN pay for
# the Gate material. A probe whose order never ships costs no outside
# read at all.
jq -s 'sort_by([(if .urgency_at == null then 1 else 0 end), (.urgency_at // ""), .rank])' \
   "$CAND" > "$D/work_orders_all.json" 2>/dev/null || echo '[]' > "$D/work_orders_all.json"
jq --argjson n "$WORK_ORDERS_N" '.[0:$n]' "$D/work_orders_all.json" > "$D/work_orders.json"

WO_ACC="$D/work_orders.jsonl"; : > "$WO_ACC"
wo_n="$(jq 'length' "$D/work_orders.json")"
wo_i=0
while [ "$wo_i" -lt "$wo_n" ]; do
  ord="$(jq -c --argjson k "$wo_i" '.[$k]' "$D/work_orders.json")"
  gacc="$(mktemp)"; echo '[]' > "$gacc"
  if [ "$(printf '%s' "$ord" | jq -r '.material.gate | type')" = "null" ]; then
    k1="$(printf '%s' "$ord" | jq -r '(.gate_keys // [])[0] // ""')"
    k2="$(printf '%s' "$ord" | jq -r '(.gate_keys // [])[1] // ""')"
    if [ -n "$k1" ]; then
      gf="$(mktemp)"
      gate_search "$gf" "" "$k1" "$k2"
      jq -s '.[0] + .[1]' "$gacc" "$gf" > "$gacc.m" && mv "$gacc.m" "$gacc"
      rm -f "$gf"
    fi
    # an EVENT order also carries the last week of the household thread
    if [ "$(printf '%s' "$ord" | jq -r '.gate_thread // false')" = "true" ]; then
      tf="$(mktemp)"
      gate_thread "$tf" "$k1" "$k2"
      jq -s '.[0] + .[1]' "$gacc" "$tf" > "$gacc.m" && mv "$gacc.m" "$gacc"
      rm -f "$tf"
    fi
  fi
  if [ "$(jq 'length' "$gacc" 2>/dev/null || echo 0)" -gt 0 ]; then
    printf '%s' "$ord" | jq -c --slurpfile g "$gacc" '.material.gate = $g[0]' >> "$WO_ACC"
  else
    printf '%s\n' "$ord" >> "$WO_ACC"
  fi
  rm -f "$gacc"
  wo_i=$((wo_i + 1))
done
jq -s '.' "$WO_ACC" > "$D/work_orders.json"

# ── the leash: file the anchored extend-ask before it runs out ───────
ASK='{"filed": false, "why": "the grant is not inside the ask window"}'
if [ -n "$GRANT_EXP" ] && [ -z "${WAYMARK_NO_ASK:-}" ]; then
  left=$(( $(to_s "$GRANT_EXP") - $(now_s) ))
  if [ "$left" -lt "$ASK_WINDOW" ]; then
    open_n="$(jq '[.[] | select(.state=="offered")] | length' "$R/approval_requests.json")"
    if [ "$open_n" -gt 0 ]; then
      ASK="$(jq -nc --arg id "$(jq -r '[.[]|select(.state=="offered")][0].self' "$R/approval_requests.json")" \
        '{filed:false, why:"an extend-ask of ours is already offered and waiting for a human tap", ask:$id}')"
    else
      hours=$(( EXTEND / 3600 ))
      body="$(jq -nc --arg g "$GRANT" --arg t "Keep my standing leash: the same scope, another $hours hours." \
                     --argjson s "$(jq -c '.data.scope // .scope' "$R/grant.json")" \
                     --arg e "$(iso "$(( $(now_s) + EXTEND ))")" \
              '{grant_id:$g, task:$t, scope:$s, expires_at:$e}')"
      out="$(mktemp)"
      c="$(curl -sS --max-time 30 -o "$out" -w '%{http_code}' -X POST "$BASE/api/approval_requests" \
             -H "Authorization: Bearer $TOKEN" -H "X-Waymark-Grant: $GRANT" \
             -H "Content-Type: application/json" -d "$body")"
      if [ "$c" = "201" ] || [ "$c" = "200" ]; then
        ASK="$(jq -c '{filed:true, ask:.self, state:.state,
                       why:"the leash is inside the ask window; a human taps this to extend it in place"}' "$out")"
      else
        ASK="$(jq -nc --arg c "$c" --arg s "$(refusal "$out")" \
               '{filed:false, why:("the extend-ask was refused (HTTP " + $c + "): " + $s)}')"
      fi
      rm -f "$out"
    fi
  fi
fi
echo "$ASK" > "$D/extend_ask.json"

# ── the manifest: the one file the model reads ───────────────────────
jq -n \
  --arg started "$STARTED" --arg base "$BASE" --arg run "$RUN" \
  --arg principal "$PRINCIPAL" --arg display "$DISPLAY" \
  --arg grant "$GRANT" --arg gstate "$GRANT_STATE" --arg gexp "$GRANT_EXP" \
  --arg owner "$OWNER" \
  --argjson left "$(( $(to_s "${GRANT_EXP:-1970-01-01T00:00:00Z}") - $(now_s) ))" \
  --slurpfile reads <(jq -s '.' "$READS") \
  --slurpfile requests "$D/offered_requests.json" \
  --slurpfile threads "$D/unanswered_threads.json" \
  --slurpfile facts "$D/candidate_facts.json" \
  --slurpfile unscored "$D/unscored_bundles.json" \
  --slurpfile declines "$D/declines.json" \
  --slurpfile values "$D/live_values.json" \
  --slurpfile companions "$D/companions.json" \
  --slurpfile arrivals "$D/arrivals.json" \
  --slurpfile bare "$D/bare_tasks.json" \
  --slurpfile orders "$D/work_orders.json" \
  --slurpfile orders_all "$D/work_orders_all.json" \
  --slurpfile crowd "$D/crowd_out.json" \
  --arg since_arr "$SINCE_ARR" \
  --slurpfile arr_basis "$D/arrivals_basis.json" \
  --slurpfile uncomposed "$D/uncomposed.json" \
  --slurpfile census "$D/uncomposed_census.json" \
  --slurpfile standing "$D/standing_outcomes.json" \
  --slurpfile ask "$D/extend_ask.json" \
  --slurpfile gate "$D/gate.json" \
  --slurpfile diag "$RUN/rows/diagnosis.json" \
  --slurpfile journals "$RUN/rows/journals.full.json" \
'{
  run: {started_at:$started, base_url:$base, snapshot:$run, principal:$principal,
        display:$display, owner_member:$owner,
        grant:{id:$grant, state:$gstate, expires_at:$gexp, seconds_left:$left},
        bearer_file: ($run + "/bearer"),
        note: "Every request wears BOTH Authorization: Bearer <bearer_file> and X-Waymark-Grant: <grant.id>. A collection answers a PROJECTION; a row read at its own address answers `data` in full — the snapshot has both (rows/<kind>.json and rows/<kind>.full.json)."},
  grant_watch: $ask[0],
  reads: $reads[0],
  duties: {
    answer_requests: ($requests[0] | length),
    answer_threads:  ($threads[0]  | length),
    index_facts:     ($facts[0]    | length),
    score_bundles:   ($unscored[0] | length),
    declines_owed_a_diagnosis: ([$declines[0][] | select(.diagnosis_stands|not)] | length),
    advance_arrivals: ($arrivals[0] | length),
    enrich_a_bare_task: ($bare[0] | length),
    work_orders: ($orders[0] | length)
  },
  work_orders: $orders[0],
  work_orders_found: ($orders_all[0] | length),
  crowd_out: ($crowd[0] // null),
  now: $started,
  arrivals_since: $since_arr,
  arrivals_basis: ($arr_basis[0] // []),
  arrivals: ($arrivals[0] | .[0:40]),
  bare_tasks: ($bare[0] | .[0:40]),
  gate: $gate[0],
  standing_outcomes: $standing[0],
  uncomposed_census: $census[0],
  uncomposed: ($uncomposed[0] | .[0:60]),
  offered_requests: $requests[0],
  unanswered_threads: $threads[0],
  candidate_facts: $facts[0],
  unscored_bundles: $unscored[0],
  declines: $declines[0],
  live_values: $values[0],
  companions: $companions[0],
  prior_journals: [$journals[0][] | {self, at:.meta.updated_at, title:.data.title, body:.data.body}],
  tally: ($diag[0].tally // null),
  doors: {
    outcome:       {create:"POST /api/outcomes",           schema:"doors/outcomes-offered.json"},
    outcome_piece: {create:"POST /api/outcome_pieces",     schema:"doors/outcome_pieces-offered.json"},
    insight:       {create:"POST /api/insights",           schema:"doors/insights-published.json"},
    remark:        {create:"POST /api/remarks",            schema:"doors/remarks.json"},
    ranking_note:  {create:"POST /api/ranking_notes",      schema:"doors/ranking_notes-live.json"},
    journal:       {create:"POST /api/journals",           schema:"doors/journals-written.json"}
  }
}' > "$RUN/manifest.json"

# the same thing in the sitting's own words, for the model to read first
{
  echo "# The house, read at $STARTED"
  echo
  echo "You are $DISPLAY ($PRINCIPAL) at $BASE."
  echo "Grant $GRANT is $GRANT_STATE and expires $GRANT_EXP."
  jq -r '.grant_watch | "Leash: " + .why + (if .filed then " — filed " + .ask else "" end)' "$RUN/manifest.json"
  echo "Bearer for this run: $RUN/bearer (0600, one hour)."
  echo "Snapshot: $RUN/rows/*.json — <kind>.json is the collection projection, <kind>.full.json each row read at its own address (evidence and routing live only there)."
  echo
  echo "## What is owed"
  jq -r '.duties | to_entries[] | "- \(.key): \(.value)"' "$RUN/manifest.json"
  echo
  echo "## YOUR WORK ORDERS — this run's assignment (waymark-48a)"
  jq -r '"  (\(.work_orders|length) presented of \(.work_orders_found) the probes found — a CEILING, set by WAYMARK_WORK_ORDERS)"' "$RUN/manifest.json"
  jq -r 'if .crowd_out then "  CROWD-OUT: " + .crowd_out.says else empty end' "$RUN/manifest.json"
  jq -r '
    if (.work_orders|length) == 0 then
      "  (no order this run — every probe came up empty)"
    else
      (. as $root
       | .work_orders | to_entries[]
       | (.key + 1) as $n | .value as $o
       | ([ "",
            "ORDER \($n) · \($o.probe) · \($o.subject)",
            "    subject: \($o.subject_says)",
            "    urgency: \($o.urgency_says)",
            "    why:     \($o.why)",
            "    MATERIAL — read all of it; only house addresses may be cited:",
            "      row: \($o.material.row | tojson)" ]
          + [ $o.material.siblings[]?
              | "      related: \(.self) — \(.title)"
                + (if .due_at then " · due " + (.due_at | .[0:10]) else "" end)
                + (if .starts_at then " · starts " + (.starts_at | .[0:10]) else "" end)
                + (if .shares then " (shares \(.shares))" else "" end) ]
          + [ $o.material.excluded[]?
              | "      EXCLUDED by machine dedupe: \(.self) — \(.title)"
                + (if (.cited_by | length) > 0
                   then " — already cited by \(.cited_by | join(", ")); citing it too would be the twin `not-a-twin` refuses"
                   else " — a standing row already speaks for it" end) ]
          + (if $o.material.gate == null
             then [ "      gate: (no Gate material — the probe was turned off or no rig answered)" ]
             else (([ $o.material.gate[]
                      | if .answered
                        then ([ "      gate \(.rig) via \(.tool) — searched \((.keys_tried // [.query]) | map("\"\(.)\"") | join(" then ")) — MATERIAL, NOT AN ADDRESS (never cite it, never copy a body; name the source in prose):" ]
                               + (if (.hits|length) == 0
                                  then [ "        (nothing came back)" ]
                                  else [ .hits[] | "        · \(.)" ] end))
                        else [ "      gate \(.rig): REFUSED — \(.refusal)",
                               "        (say the source was unreachable and quote that sentence — a refusal is never a licence to write filler)" ]
                        end ] | add) // [ "      gate: (no rig this grant admits could be searched)" ])
             end)
          + [ (($root.gate.rigs // [])[] | select(.answered | not))
              | "      gate \(.rig): NOT SEARCHED — it refused the liveness probe: \(.refusal)" ]
          + (if (($o.material.day_table // []) | length) > 0
             then [ "      the household clock — every instant you prepare is one of these (a local wall clock, already rendered UTC, all of them ahead of this run):" ]
                  + [ $o.material.day_table[]
                      | "        · \(.weekday) \(.date) — 9:00 local = \(.at_0900_local) · 19:00 local = \(.at_1900_local)" ]
             else [] end)
          + [ "    WRITE — one \($o.write.kind | ascii_upcase) at \($o.write.door), and that is the whole order:",
              "      fields: \($o.write.fields | join(", "))",
              "      \(if $o.write.kind == "outcome" then "goal" elif $o.write.kind == "journal" then "say" else "finding" end): \($o.write.finding)" ]
          + (if $o.write.kind == "journal"
             then [ "      the row this sentence is about: \($o.write.cite | join(", "))  — a journal cites nothing; naming it in the sentence is what makes the skip readable" ]
             else [ "      evidence: \($o.write.cite | tojson)  — these, plus any other HOUSE row you actually read" ] end)
          + (if $o.write.value_id then [ "      value_id: \($o.write.value_id)" ] else [] end)
          + (if $o.write.offer
             then [ "      offer_kind: \($o.write.offer.offer_kind)  offer_id: \($o.write.offer.offer_id)  offer_action: \($o.write.offer.offer_action)",
                    "      (that door is LIGHT — it asks for nothing. Never send offer_href: the engine derives the address from the kind and the id.)" ]
             else [] end)
          + (if (($o.write.pieces // []) | length) > 0
             then [ "      pieces — one POST /api/outcome_pieces each, outcome_id = the outcome you just wrote:" ]
                  + [ $o.write.pieces[]
                      | "        · \(.says)\n          \({form, target_kind, target_id, target_action, prepared} | tojson)" ]
             else [] end)
          + (if $o.write.note then [ "      NOTE: \($o.write.note)" ] else [] end)
          | .[]))
    end' "$RUN/manifest.json"
  echo
  echo "  These orders ARE the assignment, and they are its ceiling. Do what is owed above first (a person's pull and a person's turn outrank any probe), then execute these in the order given — each is one row at one door, and the material to write it is already here. Everything else in this manifest is OPTIONAL material: read it if an order needs it, and stage nothing extra to look busy. An order you cannot answer honestly is skipped and said so in the journal. A run with no work orders and nothing owed writes NOTHING AT ALL, and that is a lawful, complete run (waymark-mho)."
  echo
  echo "## Offered requests — a person's pull is never capped"
  jq -r 'if (.offered_requests|length)==0 then "  (none)" else (.offered_requests[] | "- \(.self) by \(.requested_by) good until \(.good_until)") end' "$RUN/manifest.json"
  echo
  echo "## Threads whose last turn is not yours"
  jq -r 'if (.unanswered_threads|length)==0 then "  (none)" else (.unanswered_threads[] | "- \(.subject_kind)/\(.subject_id): \(.last.said_by) said \"\(.last.says[0:120])\" (\(.last.self))") end' "$RUN/manifest.json"
  echo "  (a turn by another AGENT is not a work order — only a person's is; judge who said it)"
  echo
  echo "## Turns no insight cites yet — index the FACTS among them"
  jq -r 'if (.candidate_facts|length)==0 then "  (none)" else (.candidate_facts[] | "- \(.self) on \(.subject_kind)/\(.subject_id): \"\(.says[0:120])\"") end' "$RUN/manifest.json"
  echo "  (a question, a thanks or a preference indexes nothing)"
  echo
  echo "## Bundles carrying no live judgment of yours — newest first"
  jq -r 'if (.unscored_bundles|length)==0 then "  (none)" else (.unscored_bundles[] | "- \(.self) [\(.state)] \(.goal[0:100])\n    value: \(.value_name // "?")  pieces: \(.pieces|length)  decline words: \([.reasons[]|.reason]|tostring)\n    cite: \(.cite|tostring)") end' "$RUN/manifest.json"
  echo "  (a note's evidence is the whole cite list — a score read off the headline alone is not a judgment)"
  echo
  echo "## Declines OWED a diagnosis — publish one each, then nothing more"
  jq -r '[.declines[] | select(.diagnosis_stands|not)] | if length==0 then "  (none — every decline already carries one; DIAGNOSE NOTHING this sitting)" else (.[] | "- \(.self) reasons=\([.reasons[]|.reason]|tostring) — \(.goal[0:80])\n    cite: \(.cite|tostring)\n    offer one of: \(if (.offer_candidates|length)==0 then "(no standing row to offer a door on — cite the outcome and offer nothing forward; do not offer a door on the declined prior)" else ([.offer_candidates[] | "\(.self) [\(.kind) \(.state)] tappable doors \((if (.light_doors|length)>0 then .light_doors else ["(none seen — read the row)"] end)|tostring) (all: \(.doors|tostring))"] | join("; ")) end)") end' "$RUN/manifest.json"
  echo "  (quote the reason row you cite; reasons=[] means say \"no reason given\" and cite the outcome. The offered step is a TAPPABLE door the offered row admits NOW — offers-something-light refuses anything that takes input, prioritize included, so a rank goes in an outcome piece. The declined prior admits none, so expire/retire on it is not a step, it is burial)"
  echo
  echo "## Declines already diagnosed — nothing to do"
  jq -r '[.declines[] | select(.diagnosis_stands)] | if length==0 then "  (none)" else (.[] | "- \(.self) reasons=\([.reasons[]|.reason]|tostring) — \(.goal[0:80])") end' "$RUN/manifest.json"
  echo
  echo "## Already standing — NEVER twin one of these"
  jq -r 'if (.standing_outcomes|length)==0 then "  (nothing stands yet)" else (.standing_outcomes[] | "- \(.self) [\(.state)] \(.goal[0:90])\n    cites: \(.evidence|tostring)") end' "$RUN/manifest.json"
  echo "  A candidate whose GOAL says the same thing, or that cites the SAME evidence row, as any of these is a twin — do not stage it. Compose only what is genuinely not here yet."
  echo
  echo "## Beyond the house — the Gate rigs, each probed just now"
  jq -r 'if ((.gate.rigs // []) | length) == 0 then "  (nothing probed — this grant admits no Gate tool, or the probe was turned off)" else (.gate.rigs[] | if .answered then "- \(.rig): ANSWERS (probed \(.probe))" else "- \(.rig): REFUSING — \(.refusal)  (probed \(.probe))" end) end' "$RUN/manifest.json"
  echo "  A rig marked REFUSING will refuse every tool it lists — do not plan a dig through it, and never let its refusal become filler: say the source was unreachable, quote the sentence, and enrich from what the house itself holds."
  echo
  echo "## What arrived since the last run — process these"
  jq -r '"  (rows CREATED since " + .arrivals_since
           + ", read from the engine log; plus any turn of a person still holding the end of its thread)"' "$RUN/manifest.json"
  jq -r '[(.arrivals_basis // [])[] | select(.read_from | startswith("the engine log") | not)]
          | if length == 0 then empty
            else "  (NOT read from the engine log: "
                 + (map("\(.kind) — \(.read_from)") | join("; "))
                 + " — for those kinds a row created since the mark may be missing from this list)" end' "$RUN/manifest.json"
  jq -r 'if (.arrivals|length)==0 then "  (nothing new — a quiet run. If nothing below is owed either, journal nothing and leave: a no-op is a lawful run)" else (.arrivals[] | "- \(.self) [\(.kind) \(.state)] \(.says)") end' "$RUN/manifest.json"
  echo "  Each arrival is a concrete thing to ADVANCE as far as it honestly goes — no further. A person's remark is a work order (answer it). A new or synced task: read it in FULL, then either ENRICH it (below) or, only if it plus its situated graph implies a GOAL LARGER THAN ANY SINGLE ROW, COMPOSE an outcome. Do not manufacture an outcome to have done something: a run writes only what its work orders and the owed lists name, and skipping an order out loud in the journal is the right answer when the order does not hold."
  echo
  echo "## Bare tasks — the lightest write a run makes: ENRICH one (never mutate it)"
  jq -r 'if (.bare_tasks|length)==0 then "  (none bare — every actionable task already carries detail or is spoken for)" else (.bare_tasks[] | "- \(.self) [\(.state)] \(.title)\(if .due_at then " · due " + .due_at[0:10] else "" end)") end' "$RUN/manifest.json"
  echo "  To enrich a bare task: publish an INSIGHT (POST /api/insights) whose evidence cites the task AND the source you read (a Gate email/chat, a related row), whose finding is the context that makes the task actionable (what it is really for, where/when/with what, its real next physical step), and whose offer_kind/offer_id/offer_action names the task's own next door. This ANNOTATES the task beside it — it does not touch the task's fields; only the household edits its own rows. An enrichment that does not change whether the task is actionable is not worth writing."
  echo "  A finding must carry a FACT the task's row does not already state — what it is really for, who or where, when, or the next physical step — in a full sentence. \"This task needs action.\" is not an enrichment; verify flags as THIN any finding under 40 characters, or one reading 'needs action' / 'requires further action' / 'needs attention' / 'should be done'."
  echo
  echo "## When to COMPOSE instead of enrich"
  echo "  Compose an outcome only when the arrival + its situated graph (the same person / project / value / time-window rows) imply a GOAL that is larger than any single evidence row — an end-state the household would want, not a task restated. A bundle whose goal equals one task, or whose only work is re-prioritizing an existing task, is a wrapper, not an outcome: enrich instead. When a real goal is there, the uncomposed rows below and standing_outcomes above are the material and the twin-guard."
  echo "## Live values you may name"
  jq -r 'if (.live_values|length)==0 then "  (none — a plan citing no live value is refused)" else (.live_values[] | "- \(.self) [\(.state)] \(.name)") end' "$RUN/manifest.json"
  echo
  echo "## Usable companions (affirmed/current only)"
  jq -r 'if (.companions|length)==0 then "  (none — name no companion_id)" else (.companions[] | "- \(.self) \(.name)") end' "$RUN/manifest.json"
  echo
  echo "## Prior sittings"
  jq -r 'if (.prior_journals|length)==0 then "  (none — this is the first)" else (.prior_journals[] | "- \(.at) \(.title)") end' "$RUN/manifest.json"
} > "$RUN/manifest.md"

echo
cat "$RUN/manifest.md"
echo
echo "manifest: $RUN/manifest.json"
echo "read it, then do the judgment: $ROOT/.beads/formulas/sitting.formula.toml is the work order."
