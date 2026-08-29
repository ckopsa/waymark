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
# ONE DRIVER, TWO RUNS (waymark-nl0). WAYMARK_RUN=sitting (default) or
# reading. Same snapshot, same walls, same leash, same journal; what
# differs is which orders the run owns. Every order the probes and the
# owed lists produce is LABELED clerk or editor by one rule: an order
# is `editor` when its expected write is an outcome, an unmarked
# rework, an answer to a person's question, an extra, or a
# contradiction between rows; `clerk` when the write is one row at one
# door with the material inline. A SITTING (the clerk's run, on the
# timer) takes the clerk orders and prints the editor orders under
# "Waiting for a reading", not counted as owed. A READING (the editor's
# run, morning/evening or on demand) takes both, opens with THE HOUSE
# BRIEF, carries the REVIEW of the sittings since the last reading
# (their verify grade lines, stored per run dir by `verify`), and
# closes with the one-extra paragraph. `verify` grades against the
# run's own formula: a sitting is never faulted for an editor order; a
# reading is faulted (printed, never blocked) for an editor order it
# neither answered nor skipped out loud, and for an extra that is
# uncited or a twin.
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
# how many CLERK work orders the manifest presents), WAYMARK_EDITOR_ORDERS
# (3 — the same ceiling on editor orders), WAYMARK_THREADS (4 — how
# many of the household's conversations a sitting reads; the newest
# four with activity in the window, groups included),
# WAYMARK_BRIEF_LINES (80 — the cap on the house brief a reading opens
# with), WAYMARK_REVIEW_SITTINGS (6 — how many graded sittings a
# reading reviews), WAYMARK_RUN (sitting|reading — see above).
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
# the household zone, hoisted here because BOTH halves need it: the
# read half converts every instant it prepares, and verify reads the
# wall clock back off what was written (waymark-thn).
HOUSE_TZ="${WAYMARK_HOUSE_TZ:-America/Denver}"

MODE="${1:-read}"
case "$MODE" in read|verify) ;; -h|--help|help)
  sed -n '2,65p' "${BASH_SOURCE[0]}"; exit 0 ;;
  *) echo "usage: $(basename "$0") [read|verify]" >&2; exit 2 ;; esac
# WHICH RUN THIS IS (waymark-nl0): the clerk's sitting or the editor's
# reading. One driver, one snapshot; the label on every order and the
# rendering differ, and verify grades against the run's own formula.
RUN_MODE="${WAYMARK_RUN:-sitting}"
case "$RUN_MODE" in sitting|reading) ;;
  *) echo "WAYMARK_RUN is '$RUN_MODE' — a run is a sitting or a reading, nothing else" >&2; exit 2 ;; esac
EDITOR_ORDERS_N="${WAYMARK_EDITOR_ORDERS:-3}"
case "$EDITOR_ORDERS_N" in ''|*[!0-9]*) EDITOR_ORDERS_N=3 ;; esac
BRIEF_LINES="${WAYMARK_BRIEF_LINES:-80}"
case "$BRIEF_LINES" in ''|*[!0-9]*) BRIEF_LINES=80 ;; esac
REVIEW_N="${WAYMARK_REVIEW_SITTINGS:-6}"
case "$REVIEW_N" in ''|*[!0-9]*) REVIEW_N=6 ;; esac

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
verify_run() {
  # THE RUN'S OWN FORMULA (waymark-nl0): the manifest recorded which
  # run it was, and every grade below reads against that. A sitting is
  # never faulted for an editor order — it was printed under "Waiting
  # for a reading" and never counted as owed — and a reading is faulted
  # for one it neither answered nor skipped out loud.
  local PREV_MODE
  PREV_MODE="$([ -n "$prev" ] && jq -r '.run.mode // "sitting"' "$prev" 2>/dev/null || echo sitting)"
  echo "what $DISPLAY ($PRINCIPAL) wrote at $BASE since $SINCE — graded as a $PREV_MODE"
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
  check outcome       "/api/outcomes?composed_by=$PRINCIPAL"      offered iterating accepted declined expired
  check outcome_piece "/api/outcome_pieces?composed_by=$PRINCIPAL" offered taken declined moot reworked
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
    # ── DIAGNOSIS FLOOD (waymark-me9) ────────────────────────────────
    # The failure a swept fridge invites: the composer reads N declines
    # owed a diagnosis, publishes N findings that are the manifest line
    # copied out, and every one of them is lawful and none of them says
    # anything. Graded like THIN — a heuristic printed while the run
    # that wrote it is still here — and by the PROSE, because that is
    # where this fault lives: the boilerplate sentence, or one finding
    # repeated across rows.
    jq -r --arg s "$SINCE" '
      [ .data.items[]? | select(.meta.updated_at >= $s)
        | ((.fields.finding // .summary) // "") ] as $f
      | ([ $f[] | select(test("^Declined(,| with)? no reason"; "i")) ] | length) as $boiler
      | ([ $f[] | ascii_downcase | gsub("\\s+"; " ") ]
         | group_by(.) | map(length) | max // 0) as $same
      | (if $boiler > $same then $boiler else $same end) as $n
      | if $n > 3
        then "DIAGNOSIS FLOOD: \($n) identical findings — the duty is owed at recomposition, not per decline"
        else empty end' "$thin"
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
  twin_rows "/api/outcomes" outcome offered iterating accepted >> "$twins"
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
  # WHAT THE JOURNAL SAID OUT LOUD. A skip is lawful only when the
  # journal names it, so the bodies written since the mark are read
  # once here and every grade that asks "was it said" asks this text.
  jtext="$(mktemp)"; : > "$jtext"
  for st in written amended; do
    jout="$(mktemp)"
    if [ "$(api "$jout" "/api/journals?owner=$PRINCIPAL&state=${st}&page%5Bsize%5D=100")" = "200" ]; then
      jq -r --arg s "$SINCE" '.data.items[]? | select((.meta.updated_at // "") >= $s)
                               | ((.fields.title // "") + "\n" + (.fields.body // ""))' "$jout" >> "$jtext" 2>/dev/null || true
    fi
    rm -f "$jout"
  done
  if [ -n "$prev" ] && [ "$(jq '(.work_orders // []) | length' "$prev" 2>/dev/null || echo 0)" -gt 0 ]; then
    echo
    jq -rs --slurpfile m "$prev" --arg s "$SINCE" --arg me "$PRINCIPAL" \
           --arg mode "$PREV_MODE" --rawfile jt "$jtext" '
      . as $rows
      | [ $rows[] | select(.at >= $s) | select(.by == $me) ] as $mine
      | (($m[0].work_orders) // [])[]
      | . as $o
      | [ $mine[] | select((.cites | index($o.subject)) != null) | .self ] as $hits
      | (($o.label // "clerk") == "editor") as $ed
      | (($jt | contains($o.subject)) or ($jt | contains($o.subject | split("/") | last))) as $said
      | (if $ed then "EDITOR ORDER" else "ORDER" end) as $word
      | if $ed and $mode == "sitting"
        then "\($word) \($o.probe) \($o.subject): WAITING FOR A READING — not for a sitting to answer, and never a fault against one"
        elif (($o.write.kind // "") == "journal")
        then "\($word) \($o.probe) \($o.subject): JOURNAL-ONLY — no door write was asked (no live value carried it); it is answered in the journal or not at all"
        elif ($hits | length) > 0
        then "\($word) \($o.probe) \($o.subject): answered by \($hits | join(", "))"
        elif $said
        then "\($word) \($o.probe) \($o.subject): SKIPPED OUT LOUD — the journal names it"
        elif $ed
        then "\($word) \($o.probe) \($o.subject): UNANSWERED AND UNSAID — a reading answers an editor order or skips it out loud in the journal; this one did neither"
        else "\($word) \($o.probe) \($o.subject): UNANSWERED"
        end' "$twins" 2>/dev/null
    jq -r 'if .crowd_out then "  CROWD-OUT, as the manifest said it: " + .crowd_out.says else empty end' "$prev"
    echo "  An order left UNANSWERED is only a failure if it could honestly have been answered — a Gate rig that refused, an event that needed nothing, a value with no goal in it are all lawful skips. The journal is where a skip is said out loud; an UNANSWERED order and a silent journal is a run that ignored its assignment."
  fi

  # ── SAYS-SO (waymark-frv) ─────────────────────────────────────────
  # A finding whose evidence is only a remark and an outcome has no
  # house row behind it: it indexed what somebody SAID, which is the
  # exact shape of the false fact a weak model publishes when it
  # answers a question from the question. The contradiction itself
  # (a finding against a task's detail) cannot be checked mechanically;
  # this half can.
  jq -rs --arg s "$SINCE" --arg me "$PRINCIPAL" '
    . as $rows
    | [ $rows[] | select(.kind == "insight") | select(.at >= $s) | select(.by == $me) ][]
    | . as $i
    | ([ $i.evidence[] | select(test("^/api/(tasks|events|people|threads|values|chore_runs|media|task_lists)/")) ] | length) as $house
    | select($house == 0 and (($i.evidence | length) > 0))
    | "SAYS-SO: \($i.self) — cites \($i.evidence | join(", ")) and no task, event, person, thread or value row: a fact with nothing in the house behind it. Read the rows the thread is about before it stands as the record."' \
    "$twins" 2>/dev/null || true

  # ── THE EXTRA (waymark-mqo), a reading's freedom, graded ──────────
  # A reading may write ONE row the manifest did not order: cited to
  # the rows it read, distinct from what stands, with a sentence in the
  # journal on why it was worth a row. A row that answers nothing in
  # the manifest is an extra by definition — it cites no order subject,
  # no thread subject, no handed-back bundle, no offered request, no
  # owed decline. One and cited and not a twin is EXTRA; anything else
  # is FILLER, and a sitting has no extra at all, so on a sitting every
  # such row is filler by the ceiling.
  if [ -n "$prev" ]; then
    extras="$(jq -rs --slurpfile m "$prev" --arg s "$SINCE" --arg me "$PRINCIPAL" \
                     --arg mode "$PREV_MODE" --rawfile jt "$jtext" --arg faults "$faults" '
      . as $rows
      | ($m[0]) as $mf
      | ([ ($mf.work_orders // [])[] | .subject ]
         + [ ($mf.work_orders // [])[] | (.write.cite // [])[] ]
         + [ ($mf.unanswered_threads // [])[] | "/api/" + .subject_kind + "s/" + .subject_id ]
         + [ ($mf.unanswered_threads // [])[] | .last.self ]
         + [ ($mf.rework_orders // [])[] | .self ]
         + [ ($mf.offered_requests // [])[] | .self ]
         + [ ($mf.declines // [])[] | select(.owed_a_diagnosis) | .cite[] ]
         + [ ($mf.candidate_facts // [])[] | .self ]
         | map(select(. != null)) | unique) as $owed
      | [ $rows[] | select(.kind == "outcome" or .kind == "insight")
                  | select(.at >= $s) | select(.by == $me)
                  | select(([ .cites[] | . as $c | select(($owed | index($c)) != null) ] | length) == 0) ] as $ex
      | if ($ex | length) == 0
        then (if $mode == "reading" then "EXTRA: none — lawful, and the journal says why or it says nothing" else empty end)
        elif $mode != "reading"
        then ($ex[] | "FILLER: \(.self) answers nothing the manifest ordered or owed — a sitting has no extra; the orders are the ceiling")
        elif ($ex | length) > 1
        then ("FILLER: \($ex | length) rows beyond the orders — \([ $ex[] | .self ] | join(", ")) — and a reading gets ONE extra, or none")
        else ($ex[0] | . as $x
              | if (($x.evidence | length) == 0) then "FILLER: \($x.self) — the extra cites nothing"
                elif ($faults | contains($x.self)) then "FILLER: \($x.self) — the extra is a twin (see TWIN above)"
                elif (($jt | contains($x.self)) or ($jt | contains($x.self | split("/") | last)) | not)
                then "EXTRA: cited, distinct — \($x.self) — but the journal never says why it was worth a row"
                else "EXTRA: cited, distinct — \($x.self)" end)
        end' "$twins" 2>/dev/null || true)"
    if [ -n "$extras" ]; then echo; echo "$extras"; fi
  fi

  # ── NOTES FOR THE NEXT SITTINGS (waymark-nl0), a reading's last duty ─
  if [ "$PREV_MODE" = "reading" ]; then
    echo
    nn="$(grep -ciE '^\s*[-*]\s*(do|write|reply|stage|score|index|publish|post)\b' <(awk 'BEGIN{p=0} /^#+[[:space:]]*notes_for_sittings|^notes_for_sittings[[:space:]]*:?[[:space:]]*$/{p=1; next} /^#/{p=0} p' "$jtext") 2>/dev/null || echo 0)"
    if grep -qiE 'notes_for_sittings' "$jtext"; then
      echo "NOTES FOR SITTINGS: $nn form(s) left under notes_for_sittings — the next sitting's manifest prints each as a clerk order"
    else
      echo "NOTES FOR SITTINGS: none — the journal carries no notes_for_sittings block, so the next sittings work from probes alone. Lawful, and worth saying on purpose."
    fi
  fi
  rm -f "$twins" "$jtext"

  # ── HANDED BACK, NOT REWORKED (waymark-vf8) ───────────────────────
  # The one work order a run can no longer answer in words, graded the
  # same way the others are: by the row rather than by the prose. Every
  # bundle the last manifest listed as ITERATING AND MINE is read back
  # at its own address, and one still standing at the plan version it
  # had is one this run left where it found it — a promise on the
  # thread ("I will rework this to include the party") now refuses at
  # the remark door, so a bundle sitting here means nothing was
  # committed at all. Revision, not state: a bundle reworked and handed
  # back again is ANSWERED, and its state would lie about that.
  if [ -n "$prev" ]; then
    unreworked="$(mktemp)"; : > "$unreworked"
    while IFS="$(printf '\t')" read -r href rev label; do
      [ -n "$href" ] || continue
      cur="$(mktemp)"
      if [ "$(api "$cur" "$href")" = "200" ]; then
        # an UNMARKED rework with no clock time in the note is the
        # editor's (waymark-nl0): a sitting leaves it for a reading and
        # is not faulted for that — it is said, so nobody reads the
        # silence as nothing being wrong
        jq -r --arg r "$rev" --arg h "$href" --arg l "${label:-clerk}" --arg mode "$PREV_MODE" '
          if (((.data.plan_revision // 0) | tostring) == $r)
          then (if ($l == "editor" and $mode == "sitting")
                then "HANDED BACK, WAITING FOR A READING: \($h) — the note is unmarked and names no hour, so reading it against the pieces is the duty of a reading, not of a sitting"
                else "HANDED BACK, NOT REWORKED: \($h)" end)
          else empty end' "$cur" >> "$unreworked"
      fi
      rm -f "$cur"
    done < <(jq -r '. as $m
                    | [(.standing_outcomes // [])[] | select(.iterate_open and .mine)][]
                    | . as $s
                    | (([ ($m.rework_orders // [])[] | select(.self == $s.self) | .label ] | first) // "clerk") as $l
                    | "\($s.self)\t\(($s.plan_revision // 0) | tostring)\t\($l)"' "$prev")
    if [ -s "$unreworked" ]; then
      echo
      cat "$unreworked"
      echo "  A bundle the household handed back and this run did not rework is a FAILED work order: it is off their feed until a composer commits, and words on the thread are not a commit (the remark door refuses that hand by name). Read the note, withdraw and stage what changes, and POST /api/outcomes/<id>/-/rework {says} — or, if the plan stands, commit a round that changes nothing and say so. WAITING FOR A READING is the one lawful exception, and it is lawful only until the next reading."
    fi
    rm -f "$unreworked"
  fi


  # ── CLAIMED, NOT STAGED (waymark-o04) ─────────────────────────────
  # The other half of vf8, and the one the rows can still see. A
  # no-change rework is LAWFUL where nothing was marked — but a
  # no-change rework whose `says` announces a change is a PROMISE
  # standing in for the work: on 2026-08-29 a composer committed
  # "I've added the Payson inspection and Howie's party, and kept
  # Wilfred's party." on a round that staged nothing and withdrew
  # nothing, and the bundle went back on the household feed looking
  # answered while every hour in it was still wrong.
  #
  # Graded by the ROWS, like everything else here: the revision moved
  # (a round was committed), no piece was staged and none withdrawn
  # inside it, and the words posted with it claim otherwise. The words
  # are read off the THREAD, because that is where the rework door
  # puts them — `says` is not a field on the bundle, it is the turn.
  if [ -n "$prev" ]; then
    claims="$(mktemp)"; : > "$claims"
    while IFS="$(printf '\t')" read -r href rev asked; do
      [ -n "$href" ] || continue
      cur="$(mktemp)"
      if [ "$(api "$cur" "$href")" = "200" ] \
         && [ "$(jq -r --arg r "$rev" '(((.data.plan_revision // 0) | tostring) != $r)' "$cur")" = "true" ]; then
        oid="${href##*/}"
        pcs="$(mktemp)"; rem="$(mktemp)"
        api "$pcs" "/api/outcome_pieces?outcome_id=${oid}&page%5Bsize%5D=100" >/dev/null
        api "$rem" "/api/remarks?subject_kind=outcome&subject_id=${oid}&page%5Bsize%5D=100" >/dev/null
        jq -r --slurpfile r "$rem" --arg me "$PRINCIPAL" --arg b "$asked" --arg h "$href" '
          ([ .data.items[]? | select(.state == "offered")
                            | select((.meta.updated_at // "") > $b) ] | length) as $staged
          | ([ .data.items[]? | select(.state == "reworked")
                             | select((.meta.updated_at // "") > $b) ] | length) as $withdrawn
          | ([ (($r[0].data.items) // [])[]
               | select(((.fields.said_by) // "") == $me)
               | select((.meta.updated_at // "") > $b) ]
             | sort_by(.meta.updated_at) | last) as $turn
          | (($turn.fields.says) // "") as $w
          | if $staged == 0 and $withdrawn == 0
               and ($w | test("\\b(added|moved|re-?timed|changed|updated|rescheduled)\\b"; "i"))
            then "CLAIMED, NOT STAGED: \($h) — says “\($w)” but no piece changed"
            else empty end' "$pcs" >> "$claims"
        rm -f "$pcs" "$rem"
      fi
      rm -f "$cur"
    done < <(jq -r '[(.standing_outcomes // [])[] | select(.iterate_open and .mine)][]
                     | "\(.self)\t\((.plan_revision // 0) | tostring)\t\(((.iterate_requested_at // .reworked_at) // ""))"' "$prev")
    if [ -s "$claims" ]; then
      echo
      cat "$claims"
      echo "  A round that changes nothing is lawful; a round that changes nothing while SAYING it changed something is not an answer, it is a claim. The bundle is back on the household feed reading as done, and nobody there can see that the hour they asked for was never staged. Withdraw and stage what the note asks, commit again, and let says describe the pieces that actually moved."
    fi
    rm -f "$claims"
  fi

  # ── DID THE NOTE OWN HOUR LAND? (waymark-o04) ─────────────────────
  # The manifest suggested a RE-TIME off a clock time the household
  # wrote into the note. This reads back whether any piece on that
  # bundle now starts at that hour — the same local hour, which on a
  # given day is the same UTC hour, so it is one string comparison and
  # no zone arithmetic. It grades the SUGGESTION, not the composer:
  # IGNORED is a fact, and standing by the plan is a lawful answer to
  # it as long as the journal says so.
  if [ -n "$prev" ] \
     && [ "$(jq '[((.note_times // [])[] | .suggestions[] | select(.kind == "RE-TIME"))] | length' "$prev" 2>/dev/null || echo 0)" -gt 0 ]; then
    honored="$(mktemp)"; : > "$honored"
    while IFS= read -r osf; do
      [ -n "$osf" ] || continue
      pcs="$(mktemp)"
      if [ "$(api "$pcs" "/api/outcome_pieces?outcome_id=${osf##*/}&page%5Bsize%5D=100")" = "200" ]; then
        jq -r --slurpfile m "$prev" --arg self "$osf" '
          ([ .data.items[]? | select(.state == "offered" or .state == "taken")
             | (((.fields.prepared.starts_at) // "")) ] | map(select(. != ""))) as $starts
          | (($m[0].note_times) // [])[] | select(.self == $self)
          | .suggestions[] | select(.kind == "RE-TIME") | . as $g
          | if ([ $starts[] | select(.[0:13] == ($g.note_utc | .[0:13])) ] | length) > 0
            then "NOTE TIME HONORED: \($g.piece) — a piece on \($self) now starts \($g.note_local) local (\($g.note_utc))"
            else "NOTE TIME IGNORED: \($g.piece) still \($g.piece_local) — the note said \($g.note_local) local (\($g.note_utc))"
            end' "$pcs" >> "$honored"
      fi
      rm -f "$pcs"
    done < <(jq -r '[(.note_times // [])[]
                      | select(([.suggestions[] | select(.kind == "RE-TIME")] | length) > 0)
                      | .self] | .[]' "$prev")
    if [ -s "$honored" ]; then
      echo
      sort -u "$honored"
      echo "  A time in the note is a re-time even where nobody tapped Wrong time. IGNORED is not automatically a failure — the plan may still stand — but it is only an answer if the journal says out loud why the hour the household named was not taken."
    fi
    rm -f "$honored"
  fi

  # ── ODD HOUR (waymark-thn) ────────────────────────────────────────
  # The fault that started it: an 11 AM party written as 11:00:00Z,
  # which is five in the morning here. Nothing can tell a deliberate
  # dawn start from a zone mistake, so this is a HEURISTIC printed
  # beside what the run wrote while the run is still here to fix it —
  # a prepared start before six in the morning or after ten at night,
  # local, read off the wall clock rather than off the string.
  odd="$(mktemp)"; : > "$odd"
  for st in offered taken; do
    out="$(mktemp)"
    if [ "$(api "$out" "/api/outcome_pieces?composed_by=$PRINCIPAL&state=${st}&page%5Bsize%5D=100")" = "200" ]; then
      while IFS="$(printf '\t')" read -r psf pstart; do
        [ -n "$psf" ] || continue
        loc="$(TZ="$HOUSE_TZ" date -d "$pstart" +'%Y-%m-%d %H:%M' 2>/dev/null || echo "")"
        [ -n "$loc" ] || continue
        mins=$(( 10#${loc:11:2} * 60 + 10#${loc:14:2} ))
        if [ "$mins" -lt 360 ] || [ "$mins" -gt 1320 ]; then
          echo "ODD HOUR: $psf starts $loc ($pstart) — check the zone" >> "$odd"
        fi
      done < <(jq -r --arg s "$SINCE" '.data.items[]?
                  | select((.meta.updated_at // "") >= $s)
                  | select(((.fields.form) // "") == "create")
                  | select((((.fields.prepared.starts_at)) // "") != "")
                  | "\(.self)\t\(.fields.prepared.starts_at)"' "$out")
    fi
    rm -f "$out"
  done
  if [ -s "$odd" ]; then
    echo
    sort -u "$odd"
    echo "  Times in this house are $HOUSE_TZ. A clock time a person says is LOCAL and the row wants UTC, so 11 AM is 17:00Z in August and 18:00Z in December — the manifest rework section prints the whole table already converted. If the hour above really is what the household asked for, leave it; if it is six hours off, it is the zone."
  fi
  rm -f "$odd"

  # ── THE MARKS, GRADED (waymark-wxk) ───────────────────────
  # The rework orders the last manifest handed over, read back one
  # bundle at a time: which of the household's marks this run answered
  # and which it left standing. It grades by the ROWS, like every
  # other check here — a DROP is answered by the decline that made it
  # and needs nothing; a RE-TIME and a REPLACE each want one new piece
  # staged inside the round, counted from the boundary the MANIFEST
  # recorded — the bundle's `iterate_requested_at` as it stood at read
  # time, which is the same side of the round the door counts the
  # composer's answer from — so a commit that has since moved
  # `reworked_at` does not erase the evidence. `mbnd` (the household's
  # own boundary) is read alongside it and left unused here: the marks
  # themselves were already classified against it when the manifest was
  # written. Nothing pairs a replacement to the piece it replaces —
  # no row says so and this report will not guess — so the arithmetic
  # is a COUNT, said as a count, beside the marks themselves.
  if [ -n "$prev" ] && \
     [ "$(jq '((.rework_orders // []) | length)' "$prev" 2>/dev/null || echo 0)" -gt 0 ]; then
    marks="$(mktemp)"; : > "$marks"
    while IFS="$(printf '\t')" read -r msf mbnd masked; do
      [ -n "$msf" ] || continue
      pcs="$(mktemp)"
      if [ "$(api "$pcs" "/api/outcome_pieces?outcome_id=${msf##*/}&page%5Bsize%5D=100")" = "200" ]; then
        jq -r --arg self "$msf" --arg b "$masked" --slurpfile m "$prev" '
          ([ $m[0].rework_orders[] | select(.self == $self) ] | first) as $o
          | [ .data.items[]? ] as $now
          | ([ $now[] | select(.state == "offered")
                      | select((.meta.updated_at // "") > $b) ] | length) as $staged
          | [ $o.marked[] | select(.list != "DROP") ] as $owed
          | [ $o.keep[].self ] as $kept
          | [ $now[] | select(.state == "reworked")
                     | select(.self as $s | ($kept | index($s)) != null) ] as $lost
          | "MARKS on \($self) \u2014 \($o.marked | length) marked, \($o.keep | length) kept",
            ($o.marked[]
             | if .list == "DROP"
               then "  \(.list) \(.self) \u2014 answered by the decline itself"
               else "  \(.list) \(.self) \u2014 \(.says[0:70])" end),
            (if ($owed | length) == 0
             then "  nothing here was owed a new piece"
             elif $staged >= ($owed | length)
             then "  ADDRESSED: \($owed | length) owed a new piece, \($staged) staged this round"
             else "  NOT ADDRESSED: \($owed | length) owed a new piece, only \($staged) staged this round"
             end),
            ($lost[] | "  WITHDREW A KEEP: \(.self) \u2014 the household left that one standing")
        ' "$pcs" >> "$marks"
      fi
      rm -f "$pcs"
    done < <(jq -r '(.rework_orders // [])[]
                     | "\(.self)\t\(.boundary // "")\t\(.asked // .boundary // "")"' "$prev")
    if [ -s "$marks" ]; then
      echo
      cat "$marks"
      echo "  A mark is the household picking the piece that needs revision, and the rework door refuses a commit that leaves one unanswered or withdraws a piece they kept (the-marks-are-the-work-order). NOT ADDRESSED here means the round is still owed a new piece: POST /api/outcome_pieces citing the same bundle — the SAME step at a new hour for a RE-TIME, a DIFFERENT step toward the same goal for a REPLACE — and then commit."
    fi
    rm -f "$marks"
  fi

  # A SITTING LEAVES NO DIFF. The wisp appends to .beads/interactions.jsonl
  # (a tracked file) and a runner that diffs its tree would carry that
  # residue home as a patch; the snapshot is already gitignored.
  if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "$ROOT" checkout -q -- .beads/ 2>/dev/null || true
    left="$(git -C "$ROOT" status --porcelain 2>/dev/null | grep -v '^?? \.sitting' || true)"
    if [ -n "$left" ]; then echo; echo "tree not clean after the sitting — a sitting leaves no diff:"; echo "$left"; fi
  fi
  return 0
}

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
  # THE GRADE LINES ARE KEPT (waymark-nl0): verify's whole report goes
  # into the run dir it graded, and the lines that grade — ORDER, THIN,
  # TWIN, HANDED BACK, CLAIMED, NOTE TIME, ODD HOUR, MARKS, EXTRA,
  # FILLER, SAYS-SO — are filed beside it as grades.txt, which is what
  # the next READING reads under REVIEW. On an ephemeral runner the dir
  # does not survive, and the reading's manifest says so rather than
  # pretending the sittings went ungraded.
  if [ -n "$prev" ]; then
    vdir="$(dirname "$prev")"
    verify_run | tee "$vdir/verify.txt"
    grep -E '^(ORDER|EDITOR ORDER|THIN|TWIN|DIAGNOSIS FLOOD|HANDED BACK|CLAIMED|NOTE TIME|ODD HOUR|MARKS|EXTRA|FILLER|SAYS-SO|NOTES FOR SITTINGS|NOTHING written|[0-9]+ row)|^  (ADDRESSED|NOT ADDRESSED|WITHDREW)' \
      "$vdir/verify.txt" | sed "s/^\(NOTHING written\.\).*/\1/" > "$vdir/grades.txt" 2>/dev/null || true
    printf '%s\n' "$(iso "$(now_s)")" > "$vdir/verified_at"
  else
    verify_run
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
# the household's CONVERSATIONS, as rows (waymark-36s). This read is
# what turns thread selection from a guess into a query: every chat is
# an address, so a fact said in one can be CITED instead of described.
# It is named chat_threads because `threads` is already the derived
# list of remark turns, two concepts one word apart.
# A house that has not deployed the kind yet answers the concealment
# 404, this degrades to [], and every thread-shaped step below says so
# rather than pretending — "we could not tell" and "there was nothing"
# are different sentences.
collection chat_threads "/api/threads"
collection verdict_reasons "/api/verdict_reasons"
collection feed_recipes    "/api/feed_recipes"
# 6. the threads — one whole-kind read beats a read per subject
collection remarks "/api/remarks"
# 7. what already stands, in EVERY state
# `iterating` is a state the snapshot must read (waymark-9xn): it is
# where the household's own rework orders sit, and a driver that
# listed only the four old states would have handed a composer a
# manifest with its work orders invisible. `reworked` on the piece is
# the same omission one kind down (waymark-9j2 added the state and
# left this list at four).
states outcomes       "/api/outcomes"       offered iterating accepted declined expired
states outcome_pieces "/api/outcome_pieces" offered taken declined moot reworked
# 8. what is already written down, and what this composer has judged
states insights      "/api/insights"      published taken dismissed
states ranking_notes "/api/ranking_notes" live dismissed
states journals      "/api/journals?owner=$PRINCIPAL" written amended
collection approval_requests "/api/approval_requests?grant_id=$GRANT"
doc grant "/api/grants/$GRANT"

for k in composition_requests values people outcomes outcome_pieces insights ranking_notes remarks verdict_reasons journals chat_threads; do
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

# ── WHOSE TURN IT IS TO ANSWER, AND FROM WHAT (waymark-nl0, waymark-frv) ─
# A person's turn is one of two shapes. A FACT ("the wood arrived",
# "we are at the gym 8:30 to 10:00") is a clerk's form: index it as an
# insight citing the remark and the rows it is about, reply in words.
# A QUESTION ("can he get this without a state ID?") is the editor's:
# the answer has to come from the RECORD, and on 2026-08-29 a weak
# model handed the thread alone answered from the question instead —
# "getting the ID would be a prerequisite" — and published that as a
# fact, while the TC-842 task's own detail said SSN suffices. So every
# unanswered thread is LABELED by its shape, and under each one the
# manifest prints WHAT THE HOUSE ALREADY SAYS: the rows the subject
# bundle cites (title + detail for a task, starts/ends for an event,
# name + relation for a person) and every published insight whose
# evidence names one of them, each with its address, newest first,
# capped. A sitting answers the facts; a question waits for a reading,
# and either run answers FROM these rows or says which row contradicts
# the person.
jq --arg owner "$OWNER" '
  def is_question: (.last.says // "")
    | (test("\\?")
       or test("^\\s*(can|could|should|would|will|is|are|do|does|did|what|when|where|who|how|why|which)\\b"; "i"));
  map(. + {said_by_owner: (.last.said_by == $owner),
           shape: (if is_question then "question" else "fact" end),
           label: (if is_question then "editor" else "clerk" end)})
' "$D/unanswered_threads.json" > "$D/unanswered_threads.tmp" \
  && mv "$D/unanswered_threads.tmp" "$D/unanswered_threads.json"

jq -n --slurpfile out "$R/outcomes.full.json" --slurpfile tasks "$R/tasks.json" \
      --slurpfile events "$R/events.json" --slurpfile people "$R/people.full.json" \
      --slurpfile ins "$R/insights.full.json" --slurpfile th "$D/unanswered_threads.json" '
  ($out[0] // []) as $o | ($tasks[0] // []) as $tk | ($events[0] // []) as $ev
  | ($people[0] // []) as $pp | ($ins[0] // []) as $in
  | ([ ($th[0] // [])[] | select(.subject_kind == "outcome") | .subject_id ]
     + [ $o[] | select(.state == "iterating") | (.self | split("/") | last) ]
     | unique) as $ids
  | [ $ids[] | . as $id
      | (([ $o[] | select((.self | split("/") | last) == $id) ] | first) // null) as $b
      | select($b != null)
      | ([$b.self] + ($b.data.evidence // [])) as $rows
      | {outcome: $b.self,
         goal: ($b.data.goal // ""),
         rows: ([ $rows[] | . as $r
                  | (if ($r | test("^/api/tasks/"))
                     then (([ $tk[] | select(.self == $r) ] | first) // null) as $t
                          | select($t != null)
                          | {self:$r, kind:"task", says:(($t.fields.title // $t.display.title // "") | tostring)
                                          + (if (($t.fields.status // "open") != "open") then " [" + $t.fields.status + "]" else "" end)
                                          + (if ($t.fields.due_at // "") != "" then " · due " + ($t.fields.due_at | .[0:10]) else "" end),
                             detail:(($t.fields.detail // "") | tostring | gsub("\\s+"; " ") | .[0:400])}
                     elif ($r | test("^/api/events/"))
                     then (([ $ev[] | select(.self == $r) ] | first) // null) as $e
                          | select($e != null)
                          | {self:$r, kind:"event", says:(($e.fields.title // $e.display.title // "") | tostring)
                                          + " · " + (($e.fields.starts_at // $e.fields.date // "?") | tostring)
                                          + (if ($e.fields.ends_at // "") != "" then " to " + ($e.fields.ends_at | tostring) else "" end)
                                          + (if ($e.fields.location // "") != "" then " · " + $e.fields.location else "" end),
                             detail:""}
                     elif ($r | test("^/api/people/"))
                     then (([ $pp[] | select(.self == $r) ] | first) // null) as $p
                          | select($p != null)
                          | {self:$r, kind:"person", says:(($p.data.name // "") + " — " + ($p.data.relation // "?") + " [" + $p.state + "]"), detail:""}
                     else empty end) ]),
         findings: ([ $in[] | select(.state == "published")
                     | . as $i | select(([ ($i.data.evidence // [])[] | . as $e | select(($rows | index($e)) != null) ] | length) > 0)
                     | {self, at:(.meta.updated_at // ""), finding:(.data.finding // "" | .[0:300])} ]
                    | sort_by(.at) | reverse | .[0:8])} ]
' > "$D/house_says.json" 2>/dev/null || echo '[]' > "$D/house_says.json"

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
            evidence:(.data.evidence // []),
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
# …and the CONVERSATIONS THAT MOVED (waymark-36s). A thread row is
# created once and then it moves, so the creation diff above can never
# see one: what makes a thread an arrival is its last_message_at
# crossing the watermark. It is declared here beside the unanswered turn
# for the same reason that one is — the second thing a clock CAN see,
# and still owed an answer. The row's own timestamp replaces meta's for
# ordering (a resync bumps updated_at; only last_message_at means
# somebody spoke), and the title becomes the sentence.
if jq --slurpfile th "$R/chat_threads.full.json" --arg since "$SINCE_ARR" '
     . + [ ($th[0] // [])[] | . as $t | (($t.data.last_message_at) // "") as $at
           | select($at != "" and (($at | .[0:19]) > ($since | .[0:19])))
           | $t
             + {meta: (($t.meta // {}) + {updated_at: $at})}
             + {display: (($t.display // {})
                          + {title: ("something was said in "
                                     + ($t.data.title // "a conversation"))})} ]
   ' "$ARR_ACC" > "$ARR_ACC.m" 2>/dev/null; then mv "$ARR_ACC.m" "$ARR_ACC"; fi
# which basis THAT arm got, said out loud like every other kind's: a
# rig that answers no timestamp for its threads (the phone's texts do
# not) can contribute no arrival, and that is not the same as quiet.
jq -nc --slurpfile th "$R/chat_threads.full.json" \
   --argjson dated "$(jq '[.[] | select((.data.last_message_at // "") != "")] | length' \
                        "$R/chat_threads.full.json" 2>/dev/null || echo 0)" \
   --argjson n "$(jq --arg since "$SINCE_ARR" \
                     '[.[] | select(((.data.last_message_at // "") != "")
                                    and ((.data.last_message_at | .[0:19]) > ($since | .[0:19])))] | length' \
                     "$R/chat_threads.full.json" 2>/dev/null || echo 0)" '
  (($th[0] // []) | length) as $rows
  | {kind:"chat_threads",
     read_from:(if $rows == 0
                then "nothing — this house serves no /api/threads yet, so a conversation that moved cannot be seen from here"
                else ("the thread row own last_message_at (" + ($dated|tostring)
                      + " of " + ($rows|tostring)
                      + " rows carry one; a rig that answers no time contributes none)") end),
     created_since:$n}' >> "$D/arrivals_basis.jsonl" 2>/dev/null || true
jq 'unique_by(.self)
    | map({self, kind, state, at:(.meta.updated_at // null),
           says:((.display.title // .summary // "") | .[0:160])})
    | sort_by(.at) | reverse' "$ARR_ACC" > "$D/arrivals.json" 2>/dev/null || echo '[]' > "$D/arrivals.json"
jq -s '.' "$D/arrivals_basis.jsonl" > "$D/arrivals_basis.json" 2>/dev/null || echo '[]' > "$D/arrivals_basis.json"
rm -f "$ARR_ACC" "$ARR_ACC.k"

# the CITED set — every row a STANDING outcome or a LIVE insight speaks
# for. Standing means offered or accepted; live means published. A
# declined, expired or dismissed row speaks for nothing — that is what
# a verdict frees (not-a-twin reads the same three states, `iterating`
# among them since waymark-9xn: a bundle handed back for a re-plan is
# the loudest kind of standing, and twinning it would be composing
# against the very rework you were asked for). Until
# 2026-08-28 this read every outcome in every state, so a swept fridge
# (28 declines in one evening) still claimed all 16 open tasks and the
# session probe could never find a free cluster.
jq -s '(.[0] + .[1]) | unique' \
   <(jq '[.[] | select(.state=="offered" or .state=="iterating" or .state=="accepted")
          | .data.evidence[]?]' "$R/outcomes.full.json") \
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
# iterate_open (waymark-9j2, waymark-9xn): a person tapped "iterate" on
# this outcome — the goal is right, the plan is wrong, rework it in
# place — and the bundle is OFF THEIR FEED until you answer. It is READ
# OFF THE STATE now rather than compared out of two stamps: `iterating`
# IS the open request, one fact instead of two that could disagree. A
# standing work order to revise the pieces (withdraw with
# outcome_pieces/-/rework, stage the replacements, commit with
# outcomes/-/rework, which is the door back to `offered`); the note
# itself is a remark on the outcome's thread, so it also rides
# unanswered_threads.
jq --arg me "$PRINCIPAL" '[.[] | select(.state=="offered" or .state=="iterating"
                                        or .state=="accepted")
       | {self, state, goal:.data.goal, value:(.data.value_name // null),
          evidence:(.data.evidence // []),
          composed_by:(.data.composed_by // null),
          plan_revision:(.data.plan_revision // 0),
          # THE BOUNDARY OF THE ROUND (waymark-wxk): the last commit.
          # Every mark and every replacement since it belongs to the
          # round being asked for, which is the same reading the rework
          # door makes — one fact, not two, so the manifest and the
          # wall cannot disagree about what counts.
          reworked_at:(.data.reworked_at // null),
          iterate_requested_at:(.data.iterate_requested_at // null),
          mine:((.data.composed_by // "") == $me),
          iterate_open:(.state=="iterating")}]' \
   "$R/outcomes.full.json" > "$D/standing_outcomes.json"

# ITERATING, NOT YOURS TO REWORK (waymark-9xn). A bundle in `iterating`
# whose composer is somebody else is a work order this run cannot
# execute: `only-its-composer-reworks` admits the author unasked and
# anybody else only under a grant that names the row. It is listed all
# the same, with the composer named, because a person is waiting on it
# and a silent omission would read as nothing being wrong — if that
# composer is gone, the fix is a grant the owner approves, not a twin.
jq '[.[] | select(.iterate_open and (.mine | not))
       | {self, goal, composed_by, plan_revision}]' \
   "$D/standing_outcomes.json" > "$D/iterating_not_mine.json"

# ── THE MARKS, AS THE LISTS THEY ARE (waymark-wxk) ───────────────────
# The owner's ruling: *part of my revising should be picking the pieces
# that need revision so that the AI can focus its attention.* A note
# about a whole bundle made this manifest hand over a paragraph and a
# guess; the marks make it hand over a WORK ORDER PER PIECE, in the
# five lists the rework door itself is written against.
#
# Nothing new is read to build them. A mark IS the piece's own
# `not_this` plus the quick word filed against it in `verdict_reason`
# — both already in the snapshot — and the lists are that word:
#
#   wrong_time              → RE-TIME  withdraw nothing; stage a NEW
#                                      piece, same step, new hour
#   wrong_piece / wrong_way → REPLACE  stage a NEW piece, a different
#                                      step toward the same goal
#   never_this              → DROP     nothing to write; the decline
#                                      already took it out
#   declined with no word   → DROP     the household said no order
#                                      beyond "not this one"
#   still offered           → KEEP     write nothing, and DO NOT
#                                      withdraw it
#   what the note asks and no list covers → ADD
#
# THE ROUND is everything since the last commit (`reworked_at`), which
# is the same boundary the door reads: a piece declined BEFORE the
# iterate was tapped is still this round's mark, because marking and
# then handing the plan back is the same gesture in the other order.
#
# THE COMPOSER'S OWN SIDE RUNS FROM THE ASK (`iterate_requested_at`),
# and the asymmetry is the door's too: a piece staged before the
# person tapped iterate is part of the plan they were LOOKING at when
# they marked it, so it cannot also be the answer to those marks. One
# boundary for both counted every original piece of a first round as a
# replacement, which is what CI caught.
#
# `updated_at` on a piece IS the moment it was answered — a declined
# piece is terminal and nothing writes it again — and on an OFFERED
# piece it is the moment it was staged, which is how `staged_this_round`
# counts the composer's own answer back.
jq --slurpfile pieces "$R/outcome_pieces.full.json" \
   --slurpfile reasons "$R/verdict_reasons.full.json" \
   --slurpfile threads "$D/threads.json" \
   --arg me "$PRINCIPAL" '
  ($pieces[0] // []) as $pc | ($reasons[0] // []) as $vr
  | ($threads[0] // []) as $th
  | {wrong_time:"RE-TIME", wrong_piece:"REPLACE",
     wrong_way:"REPLACE", never_this:"DROP"} as $lists
  | [.[] | select(.iterate_open and .mine)
      | (.self|split("/")|last) as $oid
      | ((.reworked_at // "")) as $b
      | ((.iterate_requested_at // .reworked_at // "")) as $ask
      | [ $pc[] | select(.data.outcome_id == $oid) ] as $mine
      | {self, goal, plan_revision,
         boundary: $b, asked: $ask,
         note: ([ $th[] | select(.subject_kind=="outcome" and .subject_id==$oid)
                        | .turns[] | select(.said_by != $me) ] | last // null),
         thread: ("/api/remarks?subject_kind=outcome&subject_id=" + $oid),
         keep: [ $mine[] | select(.state=="offered")
                         | select((.meta.updated_at // "") <= $ask)
                 | {self, says:.data.says, write:"nothing — leave it exactly as it stands"} ],
         staged_this_round: [ $mine[] | select(.state=="offered")
                                      | select((.meta.updated_at // "") > $ask)
                              | {self, says:.data.says} ],
         marked: [ $mine[] | select(.state=="declined")
                           | select((.meta.updated_at // "") > $b)
                   | . as $p | ($p.self|split("/")|last) as $pid
                   | ([ $vr[] | select(.data.subject_kind=="outcome_piece"
                                       and .data.subject_id==$pid
                                       and .data.verdict=="not_this") ] | first) as $r
                   | ($r.data.reason // null) as $w
                   | {self:$p.self, says:$p.data.says, word:$w,
                      words:($r.data.words // null),
                      list:($lists[$w // ""] // "DROP"),
                      write:(if ($lists[$w // ""] // "DROP") == "RE-TIME"
                             then "POST /api/outcome_pieces — the SAME step at a new hour or on a new day, citing this bundle"
                             elif ($lists[$w // ""] // "DROP") == "REPLACE"
                             then "POST /api/outcome_pieces — a DIFFERENT step toward the same goal, citing this bundle"
                             else "nothing — the decline already took it out of the bundle" end)} ],
         withdrawn_this_round: [ $mine[] | select(.state=="reworked")
                                         | select((.meta.updated_at // "") > $b)
                                 | {self, says:.data.says} ]}
      | .owed = ([.marked[] | select(.list != "DROP")] | length)
      | .unanswered = (.owed - (.staged_this_round | length))
      | .marked_round = ((.marked | length) > 0) ]
' "$D/standing_outcomes.json" > "$D/rework_orders.json"

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

# ── WHICH CONVERSATIONS THIS SITTING READS (waymark-36s) ─────────────
# Until the :thread kind existed this was a heuristic, and the
# heuristic was wrong in a way nobody could see from the manifest: take
# the most recently active 1:1 chat whose title matched a roster
# companion, else the most recent non-bot chat. ONE thread out of ten,
# and never a group — which is how the Utah Kopsas group carried an
# unanswered birthday invitation straight past a sitting.
#
# Now it is a READ, and the rule is one sentence: every thread ROW
# whose last_message_at falls inside the window, ranked newest first,
# capped at WAYMARK_THREADS, GROUPS INCLUDED. Nothing is matched
# against the roster, because a conversation does not need a companion
# to be worth reading — that was the bug.
#
# The confluence's source TAG is the Gate rig's own prefix ("tgram",
# "messa"), so a row says which rig to ask without anything here
# mapping between the two vocabularies.
#
# A rig that answers no timestamp for its threads (the phone's texts do
# not — verified) contributes no candidate, and that is not a fault:
# its rows are still addresses a work order can cite.
THREADS_CAP="${WAYMARK_THREADS:-4}"
case "$THREADS_CAP" in ''|*[!0-9]*) THREADS_CAP=4 ;; esac
jq --arg since "$SINCE_7D" --argjson cap "$THREADS_CAP" '
  [ .[] | . as $t | ($t.data // {}) as $d
    | select((($d.last_message_at) // "") != "")
    | select(($d.last_message_at | .[0:19]) >= ($since | .[0:19]))
    | {self:$t.self, title:($d.title // ""),
       source:($d.source // ""),
       chat_kind:($d.chat_kind // null),
       at:$d.last_message_at,
       # the rig own handle, unwrapped from the confluence namespacing
       handle:((($d.external_id) // "") | split(":") | .[1:] | join(":")),
       names:($d.participant_names // []),
       # the PEOPLE this conversation names, as house addresses — the
       # evidence a commitment found in it is cited with
       people:[ ($d.participants // [])[] | "/api/people/" + . ]} ]
  | sort_by(.at) | reverse | .[0:$cap]
' "$R/chat_threads.full.json" > "$D/chat_selection.json" 2>>"$PROBE_ERRS" \
  || echo '[]' > "$D/chat_selection.json"

# THE SENDER DIRECTORY, which the rows buy for nothing. tgram answers
# sender IDS and no names at all, so a group's week used to render as
# numbers. But a DIRECT chat's external id IS the peer's sender id —
# so the mirrored rows are the only place that question is answerable,
# and no name appears here that the rig did not already hand us as a
# chat title.
jq '[ .[] | (.data // {}) as $d
      | select(($d.chat_kind // "") == "direct")
      | {id: ((($d.external_id) // "") | split(":") | .[1:] | join(":")),
         name: ($d.title // "")}
      | select((.id | length) > 0 and (.name | length) > 0
               and (.id | test("^[0-9]+$"))) ]' \
   "$R/chat_threads.full.json" > "$D/chat_senders.json" 2>>"$PROBE_ERRS" \
  || echo '[]' > "$D/chat_senders.json"

# what the manifest says about HOW the threads were chosen — the
# degradation is announced, never silent
if [ "$(jq 'length' "$R/chat_threads.full.json" 2>/dev/null || echo 0)" -gt 0 ]; then
  THREAD_BASIS="rows — every /api/threads row with activity in the last seven days, newest first, groups included, capped at $THREADS_CAP"
else
  THREAD_BASIS="heuristic (the thread kind is not served yet) — one chat picked from Gate's own listing: a roster companion's 1:1 thread if the listing names one, else the most recently active non-bot chat"
fi

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

# ── THE HOUSEHOLD CLOCK A REWORK ORDER CARRIES (waymark-thn) ─────────
# Jules answered the order "add Howie's 11AM birthday party" with an
# event at 11:00Z — five in the morning, Mountain. The manifest said
# "times are America/Denver" in prose and left the arithmetic to a
# small model, which is the one thing this driver has learned not to
# do: the commitments probe already hands its order a table of
# pre-converted rows so the model PICKS one. A rework order writes
# instants too, so it gets the same table, built by the same `den_utc`
# — the zone is inside the date string, so it is DST-correct on both
# sides of the change rather than carrying today's offset a week on.
#
# It starts TODAY rather than tomorrow (the probes' table starts
# tomorrow because every instant they PREPARE has to be ahead of the
# run): a rework is usually about a day the household is already
# standing in, and the specimen's whole argument was about a Saturday
# that had already begun.
#
# It is printed ONCE, at the top of the rework section, and every
# bundle below it points at it. Per-bundle would be eight lines of
# identical arithmetic repeated under every order, and the marks lists
# are what has to stay legible per bundle.
REWORK_HOURS="08:00 09:30 11:00 13:00 14:00 17:00 19:00"
REWORK_CLOCK="$(
  acc="$(mktemp)"; : > "$acc"
  i=0
  while [ "$i" -le 7 ]; do
    d="$(TZ="$HOUSE_TZ" date -d "+$i day" +%Y-%m-%d 2>/dev/null || true)"
    [ -n "$d" ] || break
    hacc="$(mktemp)"; : > "$hacc"
    for hh in $REWORK_HOURS; do
      jq -nc --arg l "$hh" --arg u "$(den_utc "$d" "$hh")" '{local:$l, utc:$u}' >> "$hacc"
    done
    jq -sc --arg d "$d" \
       --arg wd "$(TZ="$HOUSE_TZ" date -d "$d" +%A 2>/dev/null || echo '?')" \
       --arg z "$(TZ="$HOUSE_TZ" date -d "$d 12:00" +'%Z, UTC%:z' 2>/dev/null || echo '?')" \
       '{date:$d, weekday:$wd, zone:$z, hours:.}' "$hacc" >> "$acc"
    rm -f "$hacc"
    i=$((i + 1))
  done
  jq -sc '.' "$acc"; rm -f "$acc"
)"
jq -e 'type == "array"' <<< "$REWORK_CLOCK" >/dev/null 2>&1 || REWORK_CLOCK='[]'

# ── A TIME IN THE NOTE IS A RE-TIME (waymark-o04) ────────────────────
# The failure the marks wall cannot see. On an iterating bundle where
# the household marked NOTHING and wrote three clock times into the
# note — "Howie's Party at 11AM in Spanish Fork / Wilfred's Party at
# 1PM in Provo at our home. / Payson inspection maybe 9:30-10:30?" —
# the composer committed a no-change rework saying "I've added the
# Payson inspection and Howie's party, and kept Wilfred's party." and
# nothing moved. That commit is LAWFUL (waymark-vf8: a no-change round
# is an answer where nothing was marked), the wall could not fire
# because nothing was marked, and the bundle went back on the feed
# looking answered while every hour in it was still wrong.
#
# So the note is READ. Every turn on the bundle's thread that is not
# ours is split into sentences, each sentence is scanned for CLOCK
# ATOMS, and each atom's sentence is matched to a piece by SHARED
# NOUNS — case-folded, four letters or longer, stopwords and when-words
# out. What comes out is three lists, and they are SUGGESTIONS rather
# than marks: the marks wall (waymark-wxk) is the enforced path, and
# nothing here refuses anything.
#
#   the sentence matches a piece whose hour differs   → RE-TIME
#   …and that piece is already TAKEN (its row exists) → INVOKE
#   the sentence matches no piece at all              → ADD
#
# A phrase whose piece ALREADY holds the hour prints nothing: the list
# is what is still unheld, so it empties itself as the rework lands.
#
# The subjects are the bundles handed back to us AND our own OFFERED
# bundles, because the specimen's shape is exactly the second one: a
# no-change rework puts a bundle back on the feed still carrying the
# note's unanswered hour, and a driver reading only `iterating` would
# go quiet at the moment the fault appears.
jq '[.[] | select(.mine) | select(.iterate_open or .state == "offered")
      | {self, state, goal, plan_revision, iterate_open,
         boundary:(.reworked_at // ""),
         asked:(.iterate_requested_at // .reworked_at // "")}]' \
   "$D/standing_outcomes.json" > "$D/note_time_subjects.json" 2>>"$PROBE_ERRS" \
  || echo '[]' > "$D/note_time_subjects.json"

# WHAT LOCAL TIME EACH PIECE ALREADY HOLDS. `date` does this and jq
# cannot, so it is done here, once per piece, and what the manifest
# prints is the reading a person would take off a wall.
: > "$D/piece_local.jsonl"
while IFS="$(printf '\t')" read -r psf pstart pend; do
  [ -n "$psf" ] || continue
  lstart="$(TZ="$HOUSE_TZ" date -d "$pstart" +'%Y-%m-%d %H:%M' 2>/dev/null || echo "")"
  lend=""
  [ -n "$pend" ] && lend="$(TZ="$HOUSE_TZ" date -d "$pend" +'%H:%M' 2>/dev/null || echo "")"
  jq -nc --arg s "$psf" --arg u "$pstart" --arg l "$lstart" --arg e "$lend" \
     '{self:$s, starts_at:$u, local:$l,
       local_date:($l | .[0:10]), local_hm:($l | .[11:16]), local_end:$e}' \
     >> "$D/piece_local.jsonl" 2>/dev/null || true
done < <(jq -r --slurpfile subj "$D/note_time_subjects.json" '
    ([ ($subj[0] // [])[] | (.self | split("/") | last) ]) as $ids
    | .[] | . as $p | select(($ids | index($p.data.outcome_id)) != null)
    | select((($p.data.prepared.starts_at) // "") != "")
    | [.self, .data.prepared.starts_at, ((.data.prepared.ends_at) // "")] | @tsv' \
    "$R/outcome_pieces.full.json" 2>>"$PROBE_ERRS")
jq -s '.' "$D/piece_local.jsonl" > "$D/piece_local.json" 2>/dev/null \
  || echo '[]' > "$D/piece_local.json"

# THE ZONE, AS A NUMBER PER DAY. A local wall clock becomes UTC by
# subtracting that day's offset, and the offset is asked of `date` once
# per day rather than assumed — which is the whole of the DST
# correctness here. Yesterday through a fortnight out covers every day
# a note is likely to name; a date outside it falls back to the first
# offset in the table.
: > "$D/tz_offsets.jsonl"
{ i=-1; while [ "$i" -le 14 ]; do
    TZ="$HOUSE_TZ" date -d "$i day" +%Y-%m-%d 2>/dev/null || true; i=$((i + 1)); done
  jq -r '.[] | .local_date' "$D/piece_local.json" 2>/dev/null || true; } \
  | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' | sort -u | while read -r d; do
    z="$(TZ="$HOUSE_TZ" date -d "$d 12:00" +%z 2>/dev/null || echo +0000)"
    sgn=1; case "$z" in -*) sgn=-1 ;; esac
    jq -nc --arg d "$d" \
       --argjson s "$(( sgn * (10#${z:1:2} * 3600 + 10#${z:3:2} * 60) ))" \
       '{date:$d, offset:$s}'
  done >> "$D/tz_offsets.jsonl" 2>>"$PROBE_ERRS" || true
jq -s 'map({key:.date, value:.offset}) | from_entries' "$D/tz_offsets.jsonl" \
   > "$D/tz_offsets.json" 2>/dev/null || echo '{}' > "$D/tz_offsets.json"

# THE PARSER. It lives beside wm_keys because it is the same vocabulary
# one question over: wm_keys asks what a line is ABOUT, this asks WHEN
# it says a thing happens.
JQ_NOTETIMES='
  # the words that say WHEN rather than WHAT — they must never be the
  # noun a sentence is matched to a piece on
  def wm_when_stop: ["today","tonight","tomorrow","yesterday","morning",
                     "afternoon","evening","night","noon","midnight",
                     "monday","tuesday","wednesday","thursday","friday",
                     "saturday","sunday","january","february","march",
                     "april","june","july","august","september","october",
                     "november","december","sept","maybe","about","around",
                     "also","just","then","than","some","time","times",
                     "hour","hours","early","late","said","please","think",
                     "want","wants","wanting","need","needs","make","fixes",
                     "consider","comments","still","again"];
  # the NOUNS of a sentence or of a piece. Letters only, four or longer:
  # a clock time is never a noun ("11AM" would otherwise fold to "am",
  # and "9:30" to nothing at all, which is the honest answer).
  def wm_nouns:
    (wm_stop + wm_when_stop) as $drop
    | wm_tokens | map(ascii_downcase | gsub("[^a-z]"; ""))
    | map(select(length >= 4))
    | [ .[] as $w | select(($drop | index($w)) == null) | $w ]
    | unique;
  # a note is SENTENCES. "p.m." is one word and a full stop is an end,
  # so the dots come out first; a slash, a semicolon and a newline all
  # end a sentence too, because a household writes its list either way.
  def wm_sentences:
    (tostring
     | gsub("(?<x>[aApP])\\.\\s*[mM]\\.?"; "\(.x)m")
     | gsub("(?<x>[.?!])\\s"; "\n")
     | gsub("[.?!]+$"; "")
     | [ splits("[\\n;/]+") ]
     | map(gsub("^\\s+"; "") | gsub("\\s+$"; ""))
     | map(select(length > 0)));
  def wm_cap($m; $n): ([ $m.captures[] | select(.name == $n) | .string ]
                       | map(select(. != null)) | first // null);
  # 11AM is eleven; 1PM is thirteen; a bare 9:30 is morning and a bare
  # 1:30 is afternoon — the reading a household would give it out loud.
  def wm_h24($h; $ap):
    ($h | tonumber) as $n
    | if $ap == null
      then (if $n >= 13 then $n elif $n == 0 then 0
            elif $n >= 7 and $n <= 12 then $n else $n + 12 end)
      elif ($ap | ascii_downcase | startswith("p"))
      then (if $n == 12 then 12 else $n + 12 end)
      else (if $n == 12 then 0 else $n end) end;
  # A BARE NUMBER IS NEVER A TIME. A phrase counts only when it carries
  # a colon, an am/pm, or the word noon or midnight — otherwise "Aug
  # 29" reads as half past eight and every note in the house grows a
  # clock it never said.
  def wm_atoms:
    [ match("(?<a>[0-9]{1,2}):(?<b>[0-9]{2})\\s*(?<c>[ap]m)?|(?<d>[0-9]{1,2})\\s*(?<e>[ap]m)|(?<n>noon)|(?<m>midnight)"; "gi")
      | . as $mt
      | (wm_cap($mt; "a")) as $ha | (wm_cap($mt; "d")) as $hd
      | (wm_cap($mt; "n")) as $nn | (wm_cap($mt; "m")) as $mn
      | {offset:$mt.offset, length:$mt.length, text:$mt.string,
         h:(if $ha != null then $ha elif $hd != null then $hd
            elif $nn != null then "12" elif $mn != null then "0" else null end),
         mi:((wm_cap($mt; "b")) // "00"),
         ap:(if $nn != null then "pm" elif $mn != null then "am"
             else ((wm_cap($mt; "c")) // (wm_cap($mt; "e"))) end)}
      | select(.h != null)
      | select((.h | tonumber) <= 23 and (.mi | tonumber) <= 59) ];
  # two atoms with nothing but a dash or the word "to" between them are
  # ONE phrase, a start and an end — and the end lends the start its
  # am/pm ("9 to 11am"), which is how a household actually writes a
  # window down
  def wm_phrases:
    . as $s
    | (wm_atoms) as $at
    | [ range(0; ($at | length)) as $i
        | $at[$i] as $a
        | (if ($i + 1) < ($at | length)
           then ($s[($a.offset + $a.length):($at[$i + 1].offset)]) else null end) as $gap
        | {i:$i, a:$a,
           b:(if ($gap != null
                  and ($gap | test("^\\s*(-|–|—|to|until|till|thru|through)\\s*$"; "i")))
              then $at[$i + 1] else null end)} ] as $pairs
    | ([ $pairs[] | select(.b != null) | .i + 1 ]) as $ends
    | [ $pairs[] | . as $q | select(($ends | index($q.i)) == null) ]
    | map(. as $p
          | (($p.a.ap) // ($p.b.ap)) as $ap
          | {text:(($p.a.text | sub("\\s+$"; ""))
                   + (if $p.b then " to " + ($p.b.text | sub("\\s+$"; "")) else "" end)),
             h24: wm_h24($p.a.h; $ap), mi: ($p.a.mi | tonumber),
             end_h24: (if $p.b then wm_h24($p.b.h; (($p.b.ap) // $ap)) else null end),
             end_mi: (if $p.b then ($p.b.mi | tonumber) else null end)});
  def wm_pad2($n): (($n | tostring) | if length == 1 then "0" + . else . end);
  def wm_hhmm($h; $m): wm_pad2($h) + ":" + wm_pad2($m);
  def wm_months: {"jan":1,"feb":2,"mar":3,"apr":4,"may":5,"jun":6,"jul":7,
                  "aug":8,"sep":9,"oct":10,"nov":11,"dec":12};
  # WHICH DAY the phrase lands on: the weekday or the month-day the
  # sentence names, else the day the matched piece already sits on. A
  # re-time keeps the day unless the note says otherwise — that is what
  # makes "at 11AM" an answer rather than a question.
  def wm_note_date($sentence; $days; $default):
    ($sentence | ascii_downcase) as $t
    | (wm_months) as $mons
    | ([ $days[] | . as $d
         | select($t | test("\\b" + ($d.weekday | ascii_downcase) + "\\b")) ] | first) as $wd
    | (($t | match("\\b(?<mo>january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec)\\.?\\s+(?<dy>[0-9]{1,2})\\b"; "i")) // null) as $md
    | if $md != null
      then ((($days[0].date) // $default) | .[0:4]) + "-"
           + wm_pad2($mons[(wm_cap($md; "mo") | ascii_downcase)])
           + "-" + wm_pad2((wm_cap($md; "dy") | tonumber))
      elif $wd != null then $wd.date
      elif ($t | test("\\btomorrow\\b")) then ((($days[1]).date) // $default)
      elif ($t | test("\\b(today|tonight)\\b")) then ((($days[0]).date) // $default)
      else $default end;
  # a local wall clock, as UTC — that day own offset subtracted, which
  # is the same arithmetic the clock table above prints
  def wm_utc($date; $h; $m; $offs):
    ((($offs[$date]) // ($offs | to_entries | map(.value) | first) // 0)) as $o
    | ((($date + "T" + wm_hhmm($h; $m) + ":00Z")
        | strptime("%Y-%m-%dT%H:%M:%SZ") | mktime) - $o) | todate;
  # the piece a sentence is ABOUT: most shared nouns wins, the longest
  # shared noun breaks the tie, and a lone GENERIC word ("party",
  # "birthday") is not a match at all — every party in the note would
  # otherwise land on the first party in the plan.
  def wm_match_piece($sentence; $pieces):
    ($sentence | wm_nouns) as $sn
    | [ $pieces[] | . as $p
        | ([ ($p.words[]) as $w | select(($sn | index($w)) != null) | $w ]) as $shared
        | select(($shared | length) > 0)
        | select((($shared | length) > 1)
                 or ((wm_generic | index($shared[0])) == null))
        | {piece:$p, shared:$shared,
           score:[($shared | length), ($shared | map(length) | add)]} ]
    | sort_by(.score) | last // null;
'

jq --slurpfile pieces "$R/outcome_pieces.full.json" \
   --slurpfile threads "$D/threads.json" \
   --slurpfile local "$D/piece_local.json" \
   --slurpfile rows "$D/all_rows.json" \
   --slurpfile offs "$D/tz_offsets.json" \
   --argjson days "$REWORK_CLOCK" \
   --arg me "$PRINCIPAL" \
   "$JQ_KEYS$JQ_NOTETIMES"'
  ($pieces[0] // []) as $pc | ($threads[0] // []) as $th
  | ($local[0] // []) as $lc | ($rows[0] // []) as $all
  | ($offs[0] // {}) as $offsets
  | [ .[] | . as $o | ($o.self | split("/") | last) as $oid
      | ([ $pc[] | select(.data.outcome_id == $oid)
           | . as $p | (([ $lc[] | select(.self == $p.self) ] | first) // null) as $l
           | {self:$p.self, state:$p.state, says:($p.data.says // ""),
              form:($p.data.form // null),
              target_kind:($p.data.target_kind // null),
              materialized:($p.data.materialized // null),
              starts_at:(($p.data.prepared.starts_at) // null),
              local:($l.local // null), local_date:($l.local_date // null),
              local_hm:($l.local_hm // null), local_end:($l.local_end // null),
              words:(([($p.data.says // ""),
                       (($p.data.prepared.title) // ""),
                       (($p.data.prepared.location) // "")] | join(" ")) | wm_nouns)} ]) as $plist
      | ([ $th[] | select(.subject_kind == "outcome" and .subject_id == $oid)
                 | .turns[] | select(.said_by != $me) ]) as $turns
      | ([ $turns[] | . as $turn
           | (($turn.says // "") | wm_sentences | .[]) as $sentence
           | ($sentence | wm_phrases | .[]) as $ph
           | (wm_match_piece($sentence; $plist)) as $hit
           | ($hit.piece) as $p
           | (wm_note_date($sentence; $days; (($p.local_date) // (($days[0]).date) // "")))
             as $ndate
           | select($ndate != "")
           | (wm_hhmm($ph.h24; $ph.mi)) as $nhm
           | (if $ph.end_h24 != null then wm_hhmm($ph.end_h24; $ph.end_mi) else null end) as $nend
           # the piece already holds it — nothing to suggest, and the
           # list empties itself as the rework lands
           | select($p == null
                    or ($p.local_date != $ndate) or ($p.local_hm != $nhm))
           # AN ADD IS ONLY AN ORDER ON A BUNDLE HANDED BACK. On an
           # offered bundle nobody asked for a re-plan, and a time said
           # in passing ("we are at the gym from 8:30 to 10:00") is a
           # constraint rather than a request — so there only a time
           # that CONTRADICTS a piece already staged is worth printing.
           | select($p != null or $o.iterate_open)
           | {kind: (if $p == null then "ADD"
                     elif $p.state == "taken" then "INVOKE"
                     else "RE-TIME" end),
              said_by:$turn.said_by, remark:$turn.self, at:$turn.at,
              sentence:$sentence, phrase:$ph.text,
              piece:($p.self // null), piece_says:(($p.says // "") | .[0:90]),
              piece_state:($p.state // null),
              piece_local:($p.local // null), piece_local_end:($p.local_end // null),
              piece_target_kind:($p.target_kind // null),
              materialized:($p.materialized // null),
              light_doors:([ $all[] | select(.self == (($p.materialized) // " "))
                                    | (.light_doors // [])[] ] | unique),
              matched_on:($hit.shared // []),
              note_date:$ndate, note_local:$nhm, note_local_end:$nend,
              note_utc:(wm_utc($ndate; $ph.h24; $ph.mi; $offsets)),
              note_utc_end:(if $ph.end_h24 != null
                            then wm_utc($ndate; $ph.end_h24; $ph.end_mi; $offsets)
                            else null end)} ]
         # a household says a thing three times over three turns and it
         # is still ONE suggestion; the LATEST turn is the one cited
         | group_by([.kind, (.piece // .sentence), .note_utc, (.note_utc_end // "")])
         | map(max_by(.at)) | sort_by(.kind, .note_utc)) as $sugg
      | select(($sugg | length) > 0)
      | {self:$o.self, state:$o.state, iterate_open:$o.iterate_open,
         goal:$o.goal, suggestions:$sugg} ]
' "$D/note_time_subjects.json" > "$D/note_times.json" 2>>"$PROBE_ERRS" \
  || echo '[]' > "$D/note_times.json"

# ── WHOSE REWORK IT IS (waymark-nl0) ─────────────────────────────────
# A MARKED round is a clerk's form: the marks say which piece and the
# lists say what to write. So is an unmarked round whose note carries a
# clock time — the suggestions above are the form. An unmarked round
# with no hour in it is the editor's: the note has to be read against
# the pieces, and that is judgment. A sitting prints those under
# "Waiting for a reading" and is not faulted for leaving them; a
# reading takes them.
jq --slurpfile nt "$D/note_times.json" '
  ($nt[0] // []) as $times
  | map(. as $o
        | ([ $times[] | select(.self == $o.self) | .suggestions[] ] | length) as $ns
        | . + {suggested: $ns,
               label: (if .marked_round or ($ns > 0) then "clerk" else "editor" end),
               why_label: (if .marked_round then "the household marked pieces — the marks are the form"
                           elif $ns > 0 then "the note names a clock time — the suggested re-time is the form"
                           else "unmarked, and no hour in the note: the note must be read against the pieces" end)})
' "$D/rework_orders.json" > "$D/rework_orders.tmp" 2>>"$PROBE_ERRS" \
  && mv "$D/rework_orders.tmp" "$D/rework_orders.json" || true
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
             // .From // .thread_hash
             # tgram answers sender IDS and no names at all; the id is
             # a handle the sender directory turns back into a name
             # (wm_names, below), and a bare number beats no sender
             // (if (.sender_id != null) then (.sender_id | tostring) else null end)
             // empty),
            ((.subject // .Subject // .text // .snippet // .preview
              // .last_message_preview // .body // empty)
             | tostring
             # A MEDIA-ONLY MESSAGE (waymark-36s). Both rigs answer an
             # EMPTY text for a picture, so a photo used to render as a
             # bare timestamp and read as nothing at all — a week of a
             # family thread came back looking half empty. "[picture]"
             # is not a body: it is the FACT that one was sent, which
             # is the part a sitting needs. Only an object that HAS one
             # of those keys reaches this, so a chat listing entry
             # never grows a picture it does not have.
             | if length == 0 then "[picture]" else . end) ]
          | map(tostring) | map(select(length > 0)) | join(" | ")) as $l
         | (if ($l | length) > 0 then $l else tostring end)
    else tostring end;
  # a rendered line with its sender ids read back as names, from the
  # directory the mirrored direct chats are (waymark-36s). No opinion
  # when the house has no row for the id: the number stands, which is
  # still better than the nothing it used to say.
  def wm_names($senders):
    reduce ($senders[] | select((.id | length) > 3)) as $s
      (.; gsub($s.id; $s.name));
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

# ── THE HOUSEHOLD THREADS, read once (waymark-jux, is7, 36s) ────────
# What an event needs beforehand is said where a household actually
# says it — the spouse/family chat — and a keyword search over all
# chats never sees a line that names the event obliquely ("what time
# tomorrow?"). Two probes read those threads: an EVENT order wants the
# week AROUND its subject, and a COMMITMENTS order wants everything
# said in them. So the picking and the history read are one function,
# and the callers differ only in how much they ask for and what they
# keep.
#
# WHICH threads is now a READ (waymark-36s): gate_chat_history_rows,
# below, works from /api/threads. What follows it is the FALLBACK for a
# house that does not serve the kind yet — the old heuristic, kept
# whole and announced in the manifest rather than silently standing in.
#
# Nothing is hardcoded in either arm: the per-chat HISTORY tool is the
# one whose only required argument is a chat id, and its listing
# partner (the guess arm's) is the argument-free tool on the same rig
# that lists chats. No such pair in gate.json means no thread material,
# which is not a fault.
gate_chat_history_guess() { # <raw-dir> <limit> — echoes "rig\ttool\ttitle\t"
  local raw="$1/1.json" limit="$2"
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
      printf '%s\t%s\t%s\t\n' "$rig" "$hist_tool" "$title"
      return 0
    fi
    rm -f "$hf"
  done
  return 0
}

# the per-rig HISTORY tool, read off gate.json rather than named here —
# Gate's tool list is its aggregation and it changes without telling us
gate_hist_tool() { # gate_hist_tool <rig> — echoes "tool\tkey"
  jq -r --arg rig "$1" '
    ((.links // {}) | to_entries
     | map({name:.key, rig:(.key|split("__")|.[0]),
            props:((.value.input.properties // {})|keys),
            required:(((.value.input.required // []))|map(select(. != "why")))})
     | map(select(.rig == $rig))
     | map(select((.required | length) == 1))
     | map(select(.required[0] | test("chat|dialog|thread")))
     | map(select((.props | index("query")) == null))
     | sort_by(.name) | .[0] // empty)
    | [.name, .required[0]] | @tsv' "$R/gate.json" 2>>"$PROBE_ERRS" || true
}

# THE READ (waymark-36s): the threads the ROWS chose, each read once.
# One raw file per thread that answered, numbered in the order the
# lines are echoed, so a caller can pair them without a second lookup.
#
# The confluence's source tag IS the Gate rig's prefix, so the row says
# which rig to ask and nothing here maps between two vocabularies. The
# HANDLE is the rig's business: the row carries the title and the rig's
# own external id, and both are tried in that order — telegram answers
# to a title and refuses its own numeric id ("Cannot find any entity"),
# the phone answers to its hash. A 200 carrying nothing is a wrong
# handle, not an empty week.
gate_chat_history_rows() { # <raw-dir> <limit> — one "rig\ttool\ttitle\tself" line per thread read
  local dir="$1" limit="$2"
  local n=0 rig title self handle pair tool key cand hf code
  while IFS="$(printf '\t')" read -r rig title self handle; do
    [ -n "$rig" ] || continue
    pair="$(gate_hist_tool "$rig")"
    [ -n "$pair" ] || continue
    IFS="$(printf '\t')" read -r tool key <<< "$pair"
    [ -n "${tool:-}" ] || continue
    for cand in "$title" "$handle"; do
      [ -n "$cand" ] || continue
      hf="$(mktemp)"
      code="$(gate_call "$hf" "$tool" "$(jq -nc --arg id "$cand" \
        --arg key "${key:-chat_id}" --argjson lim "$limit" \
        '{($key): $id, limit: $lim,
          why: "waymark sitting: the last week of a conversation this house holds a row for"}')")"
      if [ "$code" = "200" ] \
         && [ "$(jq "$JQ_ROWS"'if (.isError // false) then 0 else (wm_rows | length) end' "$hf" 2>/dev/null || echo 0)" -gt 0 ]; then
        n=$((n + 1))
        cp "$hf" "$dir/$n.json"; rm -f "$hf"
        printf '%s\t%s\t%s\t%s\n' "$rig" "$tool" "$title" "$self"
        break
      fi
      rm -f "$hf"
    done
  done < <(jq -r '.[] | [.source, .title, .self, .handle] | @tsv' \
             "$D/chat_selection.json" 2>>"$PROBE_ERRS" || true)
  return 0
}

gate_chat_history() { # gate_chat_history <raw-dir> <limit> — one line per thread read
  local dir="$1" limit="$2"
  mkdir -p "$dir"
  [ -z "${WAYMARK_NO_GATE_PROBE:-}" ] || return 0
  if [ "$(jq 'length' "$D/chat_selection.json" 2>/dev/null || echo 0)" -gt 0 ]; then
    gate_chat_history_rows "$dir" "$limit"
  else
    # no rows chose anything — either the kind is not served here, or
    # nothing was said in the window. Either way the guess is the only
    # material available, and THREAD_BASIS says which of the two it is.
    gate_chat_history_guess "$dir" "$limit"
  fi
}

# the EVENT order's slice of that thread: the three messages that say
# one of the order's keys, or the three most recent when none does — a
# week of a household thread is ambient context either way.
gate_thread() { # gate_thread <outfile.json> [key...]
  local out="$1"; shift
  local keys_json; keys_json="$(printf '%s\n' "$@" | jq -R '.' | jq -sc 'map(select(length > 0))')"
  echo '[]' > "$out"
  # EVERY chosen thread, not one: the picking moved to the rows, and
  # the material follows it. Each answered thread contributes its own
  # entry, so a group's week sits beside a spouse's rather than
  # replacing it.
  local dir acc rig tool title self i=0
  dir="$(mktemp -d)"; acc="$(mktemp)"; : > "$acc"
  while IFS="$(printf '\t')" read -r rig tool title self; do
    [ -n "$rig" ] || continue
    i=$((i + 1))
    [ -f "$dir/$i.json" ] || continue
    jq -c --arg rig "$rig" --arg tool "$tool" --arg self "$self" \
      --arg q "the $title thread, last 7 days" --arg since "$SINCE_7D" \
      --argjson keys "$keys_json" \
      --slurpfile senders "$D/chat_senders.json" "$JQ_ROWS"'
      ([ $keys[] | ascii_downcase ]) as $k
      | ($senders[0] // []) as $sn
      | ([ wm_rows[] | select(type == "object")
           | ((.date // .last_message_date // "") | tostring) as $d
           | select($d == "" or (($d | sub(" "; "T") | .[0:19]) >= ($since | .[0:19])))
           | . as $m
           | (($m | wm_one) | ascii_downcase) as $hay
           # the three the ORDER is about, when the thread says them;
           # otherwise the three most recent — a week of a household
           # thread is ambient context either way
           | {line:($m | wm_one | wm_names($sn) | wm_trim), at:$d,
              on_key: ([ $k[] as $w | select($hay | contains($w)) ] | length)} ]
         | sort_by([(0 - .on_key)])) as $rows
      | if ($rows | length) == 0 then empty
        else {rig:$rig, tool:$tool, query:$q, answered:true,
              keys_tried:[$q],
              # the house address this material came from, when the
              # house has one — a Gate line is never an address, and
              # this is the row that is
              subject:(if ($self | length) > 0 then $self else null end),
              hits: ($rows[0:3] | map(.line)), refusal:null}
        end' "$dir/$i.json" >> "$acc" 2>/dev/null || true
  done < <(gate_chat_history "$dir" 20)
  jq -s '.' "$acc" > "$out" 2>/dev/null || echo '[]' > "$out"
  rm -rf "$dir"; rm -f "$acc"
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
    # EVERY chosen thread (waymark-36s), and each line carries the
    # ADDRESS of the conversation it was said in — which is the whole
    # point of the thread kind: a commitment found in a chat used to
    # have no row to cite, so the order anchored on a person and the
    # source lived in prose. Now the chat itself is the subject.
    local dir rig tool title self i=0
    dir="$(mktemp -d)"
    while IFS="$(printf '\t')" read -r rig tool title self; do
      [ -n "$rig" ] || continue
      i=$((i + 1))
      [ -f "$dir/$i.json" ] || continue
      jq -c --arg src "the $title thread" --arg self "$self" \
        --arg title "$title" --arg since "$SINCE_7D" \
        --slurpfile senders "$D/chat_senders.json" "$JQ_ROWS$JQ_COMMIT"'
        ($senders[0] // []) as $sn
        | [ wm_rows[] | select(type == "object")
          | ((.date // .last_message_date // "") | tostring) as $d
          | select($d == "" or (($d | sub(" "; "T") | .[0:19]) >= ($since | .[0:19])))
          | {line:(wm_one | wm_names($sn) | wm_trim),
             text:(((.text // .body // .snippet // .preview // .subject // "") | tostring) | wm_trim),
             at:$d, source:$src,
             thread_self:(if ($self | length) > 0 then $self else null end),
             thread_title:$title}
          | select(wm_commits(.line)) ] | .[]' \
        "$dir/$i.json" >> "$acc" 2>>"$PROBE_ERRS" || true
    done < <(gate_chat_history "$dir" 100)
    rm -rf "$dir"
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
        --slurpfile selection "$D/chat_selection.json" \
        --argjson nows "$NOW_S" --argjson days "$DAY_TABLE" --arg tz "$HOUSE_TZ" \
        "$JQ_DATES$JQ_KEYS"'
    ($hits[0] // []) as $h | ($tasks[0] // []) as $tk | ($events[0] // []) as $ev
    | ($people[0] // []) as $pp | ($cited[0] // []) as $c | ($values[0] // []) as $vals
    | ($selection[0] // []) as $sel
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
    # THE EVIDENCE ADDRESS (waymark-36s). Until the :thread kind
    # existed, a commitment said in a chat had NO row to cite: this
    # order anchored on whichever companion the traffic happened to
    # name, the chat itself lived in prose, and when no companion was
    # named there was no order at all — the fact was simply dropped.
    # Now the conversation IS a row. The order anchors on the THREAD
    # the lines were said in, and cites it together with the person
    # rows that thread names.
    #
    # The inbox arm carries no thread — mail is not a conversation this
    # house holds a row for — so those lines still fall to the person
    # anchor, the old law kept exactly where it is still the only one.
    | ([ $fresh[] | select(.thread_self != null) ]
       | group_by(.thread_self) | sort_by(0 - length) | (.[0] // [])) as $in_thread
    | ([ $pp[] | . as $p | select(($c | index($p.self)) == null)
               | select([ $fresh[]
                          | ((.source + " " + .line) | ascii_downcase) as $hay
                          | select($hay | contains($p.name | ascii_downcase)) ] | length > 0) ]) as $named
    | (($named | first) // null) as $who
    | (($in_thread | first) // null) as $thread
    | ($thread.thread_self // null) as $thread_self
    # the thread ROW, and the people it names — read off the selection
    # the rows already made, so the citation is exactly the set the
    # conversation itself names rather than a guess from the prose
    | (([ $sel[] | select(.self == $thread_self) ]) | first) as $trow
    | ([ $trow.people[]? ]) as $thread_people
    | select($thread_self != null or $who != null)
    # when a thread anchors it, the lines that count are that one thread
    | (if $thread_self != null then $in_thread else $fresh end) as $fresh
    | ([$thread_self] + $thread_people + [$who.self]
       | map(select(. != null)) | unique) as $cite
    | (if $thread != null then $thread.thread_title
       else ($who.name // "the inbox") end) as $where
    | (($fresh | length) | tostring) as $n
    | (wm_value_fit(([ $fresh[] | .line ] | join(" ")); $vals)) as $fit
    | {probe:"commitments-in-messages", rank:2,
       subject:($thread_self // $who.self),
       subject_says:($where + " — " + $n + (if $n == "1" then " commitment" else " commitments" end)
                     + " living only in a message"),
       urgency_at:null,
       urgency_says:("said in the last seven days of " + $fresh[0].source + "; no row in the house holds it"),
       why:("The last seven days of the conversations of this house and of the inbox carry " + $n
            + (if $n == "1" then " line that names" else " lines that name" end)
            + " a day AND an obligation, and no open task and no coming event says any of their words. A commitment that lives only in a message is invisible to the rank and to whoever is not holding the phone."),
       gate_keys:[],
       value_fit:$fit,
       material:{
         row:($trow // $who),
         # the people this conversation names — the roster rows the
         # citation carries beside the thread itself
         people:[ $sel[] | select(.self == $thread_self)
                         | {names, people} ],
         siblings:[],
         excluded:[],
         gate:[{rig:"the conversations of this house and the inbox",
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
                    finding:("The commitment above KEPT — one goal in the household own words, naming what has to happen and by when. PARAPHRASE it: never the message sentence, and name the source in the prose (\"from " + $where + ", " + ($fresh[0].at | .[0:10]) + "\")."),
                    cite:$cite,
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
                          + " ONE outcome, and one piece per commitment, at most three. A piece title PARAPHRASES — a message body is never copied into a row, and a Gate line is never an address: cite " + ($cite | join(", "))
                          + (if $thread_self != null then " (the conversation itself is a row now, and the people it names are rows too — that is what a chat-borne fact cites)" else " (a person row is a lawful evidence address)" end)
                          + " plus any house row you actually read, and name the source in prose. Every prepared instant is a wall clock in " + $tz
                          + " rendered UTC and after the run time at the top of this manifest — the household clock above has the next seven days already converted, so pick a row rather than doing the arithmetic.")}
              else {kind:"journal", door:"POST /api/journals",
                    fields:["title", "body"],
                    finding:("No live value carries these. Write nothing at the outcome door; in the journal say, in one sentence, what value a commitment like this would serve — that skip IS the answer."),
                    cite:$cite,
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
  | ([ ($out[0] // [])[] | select(.state=="offered" or .state=="iterating"
                                  or .state=="accepted")
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

# ── probe 7: stale-relative-date (waymark-63s, an EDITOR order) ──────
# Contradictions live BETWEEN two rows and no single-subject probe
# reaches them. This one is the cheapest of the four 63s named: a task
# whose detail says "Monday" or "tomorrow" or "next week" and whose
# due date is already past — prose that was true when it was written
# and is now a lie nothing flags ("Realtor meeting is Monday" on a task
# overdue since Aug 18). The write is judgment, so the order is the
# editor's: an insight offering `complete` when the day has plainly
# come and gone, or a rework note when the task is still live — which
# of the two is exactly what a reading is for.
jq -c --slurpfile cited "$D/cited.json" --argjson nows "$NOW_S" "$JQ_DATES$JQ_KEYS"'
  ($cited[0] // []) as $c
  | [ .[] | (.fields // {}) as $f
          | select(($f.status // "open") == "open")
          | ((.display.title // $f.title // "") | tostring) as $t
          | (($f.detail // "") | tostring) as $d
          | select($d != "")
          | select($d | test("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow|tonight|next week|this week(end)?)\\b"; "i"))
          | (($f.due_at // null) | in_days($nows)) as $days
          | select($days != null and $days < 0)
          | {self, title:$t, detail:($d | gsub("\\s+"; " ") | .[0:300]), due_at:$f.due_at, days:$days,
             words:([ $d | match("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow|tonight|next week|this week(end)?)\\b"; "gi") | .string ] | unique)} ]
  | sort_by(.due_at) | .[0:1]
  | map(. as $t
        | {probe:"stale-relative-date", rank:7, label:"editor",
           subject:$t.self, subject_says:$t.title,
           urgency_at:$t.due_at,
           urgency_says:("due " + ($t.due_at | .[0:10]) + " (" + ((0 - $t.days) | tostring) + " days ago) and the detail still says \"" + ($t.words | join("\", \"")) + "\""),
           why:("The task is overdue and its detail names a relative day — " + ($t.words | join(", ")) + " — that has already come and gone. Stale prose is a contradiction between the row and the calendar, and nothing in the house flags it. Read the detail against the date and say which it is: a day that passed and a task that is done, or a task still live whose detail is simply wrong."),
           gate_keys:[],
           material:{row:{self:$t.self, title:$t.title, due_at:$t.due_at, detail:$t.detail},
                     siblings:[], gate:null},
           write:{kind:"insight", door:"POST /api/insights",
                  fields:["finding", "evidence", "offer_kind", "offer_id", "offer_action"],
                  finding:"One sentence saying what the stale day in the detail actually refers to now — which date it named, whether that day passed, and what the task therefore is (done, or still owed on a real date).",
                  cite:[$t.self],
                  offer:{offer_kind:"task", offer_id:($t.self|split("/")|last), offer_action:"complete"},
                  note:"Offer `complete` only if the day plainly passed and the work with it; if the task is still live, the finding says the real date and the offer still names the task own light door — it is the household that taps, never you."}})
  | .[]' "$R/tasks.json" >> "$CAND" 2>>"$PROBE_ERRS" || probe_stumbled stale-relative-date

# ── probe 8: far-event-names-a-task (waymark-63s, an EDITOR order) ───
# The event probe looks ten days out and the placard task's real
# deadline was the Sep 15 VA appointment — outside the window, but its
# title shares a word with the open task's purpose. So: events 10 to 45
# days out, token-matched against open task titles and details on words
# of five letters or more, the cited set dropped. The write is an
# insight giving the task the event's date as its deadline; whether the
# match is real is the editor's call, so the order is labeled so.
jq -c --slurpfile cited "$D/cited.json" --slurpfile tasks "$R/tasks.json" \
      --argjson nows "$NOW_S" "$JQ_DATES$JQ_KEYS"'
  ($cited[0] // []) as $c | ($tasks[0] // []) as $tk
  | [ .[] | . as $x
          | (.fields.starts_at // .fields.date // null) as $start
          | select($start != null)
          | ($start | in_days($nows)) as $d
          | select($d != null and $d > 10 and $d <= 45)
          | {self, start:$start, days:$d,
             title:((.fields.title // .display.title // "") | tostring),
             location:(.fields.location // null)} ]
  | map(select(.title != ""))
  | map(. as $e
        | ([ ($e.title + " " + ($e.location // "")) | wm_words[] | select(length >= 5) ]) as $words
        | ([ $tk[] | select((.fields.status // "open") == "open")
                   | ((.display.title // .fields.title // "") | tostring) as $tt
                   | select($tt != "")
                   | .self as $ts | select(($c | index($ts)) == null)
                   | (($tt + " " + ((.fields.detail // "") | tostring)) | wm_words) as $tw
                   | ([ $words[] as $w | select(($tw | index($w)) != null) | $w ]) as $shared
                   | select(($shared | length) > 0)
                   | {self:$ts, title:$tt, due_at:(.fields.due_at // null),
                      detail:(((.fields.detail // "") | tostring) | gsub("\\s+"; " ") | .[0:240]),
                      shares:("the word \"" + $shared[0] + "\""), matched:$shared[0]} ]) as $near
        | select(($near | length) > 0)
        | . + {near:$near})
  | sort_by(.start) | .[0:1]
  | map(. as $e
        | {probe:"far-event-names-a-task", rank:8, label:"editor",
           subject:$e.near[0].self, subject_says:($e.near[0].title + " — named by " + $e.title + " on " + ($e.start | .[0:10])),
           urgency_at:$e.start,
           urgency_says:("the event is " + ($e.start | when($nows)) + " (" + ($e.start | .[0:10]) + "); the task " + (if $e.near[0].due_at then "is due " + ($e.near[0].due_at | .[0:10]) else "carries no due date" end)),
           why:("An event " + ($e.start | when($nows)) + " — outside the ten-day prep window — shares " + $e.near[0].shares + " with an open task. If the event is what the task is FOR, the task real deadline is the event date, not whatever the row says, and nothing in the house connects the two."),
           gate_keys:[],
           material:{row:{self:$e.self, title:$e.title, starts_at:$e.start, location:$e.location},
                     siblings:($e.near | .[0:3] | map({self, title, due_at, detail, shares})),
                     gate:null},
           write:{kind:"insight", door:"POST /api/insights",
                  fields:["finding", "evidence", "offer_kind", "offer_id", "offer_action"],
                  finding:("One sentence saying that " + $e.title + " on " + ($e.start | .[0:10]) + " is what the task is for and therefore its real deadline — with the lead time the task detail implies (a mail-in that takes weeks is due weeks before). If the match is a coincidence of words, write nothing and say so in the journal."),
                  cite:([$e.self] + [ $e.near[0].self ]),
                  offer:{offer_kind:"task", offer_id:($e.near[0].self|split("/")|last), offer_action:"complete"},
                  note:"The match is on a shared word, which is a reading and not a fact: judge it. A finding that connects the two cites both rows."}})
  | .[]' "$R/events.json" >> "$CAND" 2>>"$PROBE_ERRS" || probe_stumbled far-event-names-a-task

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
  | ([ ($standing[0] // [])[]
       | select(.state == "offered" or .state == "iterating") ]) as $offered
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
# ── THE LABEL (waymark-nl0), one rule, applied here to every order ──
# An order is EDITOR when its expected write is an outcome, an unmarked
# rework, an answer to a person's question, an extra, or a
# contradiction between rows; CLERK when the write is one row at one
# door with the material inline. Over the probes that is: an `outcome`
# write is the editor's (a goal larger than any row is judgment), a
# contradiction probe is the editor's whatever it writes (it said so
# in its own label), and an insight or a journal-only skip is a form.
# The ceiling is then applied PER LABEL — WAYMARK_WORK_ORDERS clerk
# orders and WAYMARK_EDITOR_ORDERS editor orders — so an editor's order
# never crowds a clerk's form off the sitting's manifest, and a reading
# sees both.
jq -s 'map(. + {label: (if (.label // "") == "editor" then "editor"
                        elif ((.write.kind // "") == "outcome") then "editor"
                        else "clerk" end)})
       | map(. + {why_label: (if .label == "editor"
                              then (if ((.write.kind // "") == "outcome") then "the write is an outcome — a goal larger than any row is judgment"
                                    else "a contradiction between rows — which row is right is judgment" end)
                              else "one row at one door, the material inline" end)})
       | sort_by([(if .urgency_at == null then 1 else 0 end), (.urgency_at // ""), .rank])' \
   "$CAND" > "$D/work_orders_all.json" 2>/dev/null || echo '[]' > "$D/work_orders_all.json"
jq --argjson n "$WORK_ORDERS_N" --argjson m "$EDITOR_ORDERS_N" '
  ([ .[] | select(.label == "clerk") ] | .[0:$n])
  + ([ .[] | select(.label == "editor") ] | .[0:$m])' \
   "$D/work_orders_all.json" > "$D/work_orders.json"

WO_ACC="$D/work_orders.jsonl"; : > "$WO_ACC"
wo_n="$(jq 'length' "$D/work_orders.json")"
wo_i=0
while [ "$wo_i" -lt "$wo_n" ]; do
  ord="$(jq -c --argjson k "$wo_i" '.[$k]' "$D/work_orders.json")"
  gacc="$(mktemp)"; echo '[]' > "$gacc"
  # a sitting pays for no Gate material on an order it will not take:
  # the editor's orders are printed under "Waiting for a reading" and
  # the reading runs this driver again with its own reads
  if [ "$(printf '%s' "$ord" | jq -r '.material.gate | type')" = "null" ] \
     && { [ "$RUN_MODE" = "reading" ] || [ "$(printf '%s' "$ord" | jq -r '.label')" = "clerk" ]; }; then
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

# ── WHICH DECLINES ACTUALLY OWE A DIAGNOSIS (waymark-me9) ────────────
# The duty is owed at RECOMPOSITION, not at every decline. Read the
# wall it serves: `outcome/no-burial-without-a-diagnosis` fires on
# `supersedes` — it is the gate a composer walks through on its way to
# RE-PROPOSING a line the house was shown and turned down, and it says
# nothing at all about a decline nobody is re-proposing. So an evening
# where the owner sweeps thirty stale wrappers must not become a
# morning where the composer publishes thirty one-line diagnoses about
# wrappers: that is not the law being honored, it is the feed being
# flooded, and the insight rank counts every one of them.
#
# A decline owes a diagnosis when all three hold:
#   (a) it still stands as a prior the house was SHOWN and turned down
#       — the `declines` list above, which is exactly that;
#   (b) no published INSIGHT cites it yet (`diagnosis_stands`), because
#       the duty is discharged once and never twice; and
#   (c) something this run is about to write WOULD RECOMPOSE IT — a
#       work order or an offered request whose own rows overlap the
#       prior's evidence, or that names the prior's address outright
#       (the `supersedes` the composer is about to write).
# Everything else is listed, with the house's own word, so the composer
# can read the household's mind without writing a row about it.
#
# The overlap is computed over the prior's evidence PLUS the value it
# served, because a bundle's own value is the one row a recomposition
# is guaranteed to name again.
jq --slurpfile orders "$D/work_orders.json" \
   --slurpfile reqs "$D/offered_requests.json" '
  ($orders[0] // []) as $ords | ($reqs[0] // []) as $rqs
  | map(. as $d
        | (((.evidence // []) + (if .value then [.value] else [] end)) | unique) as $ev
        | [ $ords[] | . as $o
            | (([ $o.subject, ($o.material.row.self? // null) ]
                + [ ($o.material.siblings // [])[].self ]
                + ($o.write.cite? // [])
                + [ ($o.write.supersedes? // null) ]
                + [ ($o.write.offer.offer_id? // null) ])
               | map(select(. != null)) | unique) as $touch
            | (($ev - ($ev - $touch)) | unique) as $shared
            | (($touch | index($d.self)) != null) as $names_prior
            | select(($shared | length) > 0 or $names_prior)
            | {kind:"work order", who:$o.probe, at:$o.subject,
               why:(if $names_prior
                    then "the order names the declined prior itself — writing it supersedes it"
                    else "it works the same rows the prior cited: " + ($shared | join(", ")) end)} ] as $byorder
        | [ $rqs[] | . as $r
            | (((($r.says // "") | tostring) | contains($d.self))) as $names_prior
            | select($names_prior
                     or ($d.value != null and $r.value_id != null
                         and ($d.value == ("/api/values/" + ($r.value_id | tostring)))))
            | {kind:"offered request", who:($r.requested_by // "?"), at:$r.self,
               why:(if $names_prior
                    then "the pull names the declined prior by address"
                    else "the pull stands on the same value the prior served (" + ($d.value | tostring) + ")" end)} ] as $byreq
        | .recomposed_by = ($byorder + $byreq)
        | .verdict_word = ([ .reasons[]? | .reason | select(. != null and . != "") ] | first)
        | .note = ([ .reasons[]? | .words | select(. != null and . != "") ] | first)
        | .house_says = (if .note then "\u201c" + .note + "\u201d"
                         elif .verdict_word then .verdict_word
                         else "no reason given" end)
        | .owed_a_diagnosis = ((.diagnosis_stands | not) and ((.recomposed_by | length) > 0)))
' "$D/declines.json" > "$D/declines.owed.json" \
  && mv "$D/declines.owed.json" "$D/declines.json"

# ── NOTES FOR THE NEXT SITTINGS (waymark-nl0) ────────────────────────
# A reading's last duty is to leave FORMS: a `notes_for_sittings` block
# in its journal, one line per form — "do X at door Y citing Z". The
# journal is an own-surface kind (private to its principal, never
# grantable), so the notes cross from a reading to a sitting only when
# the two runs wear the SAME principal — which is what "same journal"
# in READING.md means literally. The newest journal carrying the block
# is the one read; each line becomes a clerk order on a sitting's
# manifest, and a line whose subject a standing row already speaks for
# is dropped as answered.
jq --slurpfile cited "$D/cited.json" --arg me "$PRINCIPAL" '
  ($cited[0] // []) as $c
  | def notes_block:
      ((.data.body // "") | split("\n"))
      | reduce .[] as $l ({on:false, out:[]};
          if ($l | test("^#+\\s*notes_for_sittings|^notes_for_sittings\\s*:?\\s*$"; "i")) then .on = true
          elif ($l | test("^#")) then .on = false
          elif .on and ($l | test("^\\s*[-*]\\s+")) then .out += [($l | sub("^\\s*[-*]\\s+"; "") | gsub("\\s+$"; ""))]
          else . end)
      | .out;
  def addrs: [ match("/api/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+"; "g") | .string ] | unique;
  def door_of:
      ascii_downcase
      | if test("ranking_note|\\bscore\\b") then {kind:"ranking_note", door:"POST /api/ranking_notes"}
        elif test("outcome_piece|\\bpiece\\b") then {kind:"outcome_piece", door:"POST /api/outcome_pieces"}
        elif test("\\brework\\b") then {kind:"outcome", door:"POST /api/outcomes/<id>/-/rework"}
        elif test("\\bremark\\b|\\breply\\b|\\banswer\\b") then {kind:"remark", door:"POST /api/remarks"}
        elif test("\\binsight\\b|\\bfinding\\b|\\bindex\\b|\\benrich\\b") then {kind:"insight", door:"POST /api/insights"}
        elif test("\\boutcome\\b|\\bcompose\\b") then {kind:"outcome", door:"POST /api/outcomes"}
        else {kind:"journal", door:"the door the note names"} end;
  ([ .[] | select((.data.body // "") | test("notes_for_sittings"; "i")) ]
   | sort_by(.meta.updated_at) | last) as $j
  | if $j == null then [] else
    [ ($j | notes_block)[] | . as $line
      | ($line | addrs) as $a
      | ($line | door_of) as $d
      | {probe:"notes-for-sittings", rank:0, label:"clerk",
         why_label:"a reading left this form; the note is the whole order",
         subject:($a[0] // null), subject_says:($line | .[0:140]),
         urgency_at:null,
         urgency_says:("left by the reading of " + (($j.meta.updated_at // "") | .[0:10]) + " (" + $j.self + ")"),
         why:"A reading read across the rows and wrote this form for a sitting: one row, one door, the material in the sentence.",
         gate_keys:[], material:{row:null, siblings:[], gate:null},
         write:{kind:$d.kind, door:$d.door, fields:[],
                finding:$line, cite:$a, offer:null,
                note:"Do exactly what the line says and nothing beside it. If a row it names is gone, or a door it names is shut, skip it and say so in the journal — the note was written before this run read the house."},
         from_journal:$j.self, left_at:($j.meta.updated_at // null),
         answered: (if $a[0] != null then (($c | index($a[0])) != null) else false end)} ]
    end
' "$R/journals.full.json" > "$D/notes_for_sittings.json" 2>>"$PROBE_ERRS" \
  || echo '[]' > "$D/notes_for_sittings.json"
# on a SITTING the open notes are orders, ahead of the probes' — a
# reading already chose them; on a READING they are printed under the
# review as what the last reading left and whether it was taken
if [ "$RUN_MODE" = "sitting" ]; then
  jq -s '([ .[0][] | select(.answered | not) ] | .[0:5]) + .[1]' \
     "$D/notes_for_sittings.json" "$D/work_orders.json" > "$D/work_orders.tmp" 2>>"$PROBE_ERRS" \
    && mv "$D/work_orders.tmp" "$D/work_orders.json" || true
fi

# ── THE HOUSE BRIEF (waymark-xnf, built here under waymark-nl0) ──────
# A run starts cold with a manifest and the rows; the NARRATIVE — who
# is one year old, who left this summer, which appointment the placard
# is for — lives in journals and in whoever was in the room. Most of
# the cross-row findings a strong model surfaces in chat come from that
# story, not from the rows. So the driver composes it MECHANICALLY,
# from rows the house already holds, no model summarization: the
# standing facts (every published insight's finding, newest first,
# grouped by the person, value or list its evidence names), the last
# five journals' notes, the people with relation and age from `born`,
# the values with the words they love, the open threads the owner
# spoke in with the last turn quoted, and the next thirty days of the
# calendar as one list. Capped at WAYMARK_BRIEF_LINES, and what was
# cut is said. A reading reads it first; a sitting has it in the
# manifest JSON and the rows.
jq -n --slurpfile ins "$R/insights.full.json" --slurpfile people "$R/people.full.json" \
      --slurpfile values "$R/values.full.json" --slurpfile lists "$R/task_lists.json" \
      --slurpfile events "$R/events.json" --slurpfile journals "$R/journals.full.json" \
      --slurpfile threads "$D/unanswered_threads.json" \
      --argjson nows "$NOW_S" --arg owner "$OWNER" --arg tz "$HOUSE_TZ" "$JQ_DATES"'
  ($ins[0] // []) as $in | ($people[0] // []) as $pp | ($values[0] // []) as $vv
  | ($lists[0] // []) as $ll | ($events[0] // []) as $ev | ($journals[0] // []) as $jj
  | ($threads[0] // []) as $th
  | def name_of($addr):
      (if ($addr | test("^/api/people/")) then (([ $pp[] | select(.self == $addr) | .data.name ] | first) // null)
       elif ($addr | test("^/api/values/")) then (([ $vv[] | select(.self == $addr) | .data.name ] | first) // null)
       elif ($addr | test("^/api/task_lists/")) then (([ $ll[] | select(.self == $addr) | ((.display.title // .fields.title // "") | tostring) ] | first) // null)
       else null end);
  def age_of($born): ($born | to_epoch) as $b
      | if $b == null then null else ((($nows - $b) / 31557600) | floor) end;
  def notes_of:
      ((.data.body // "") | split("\n"))
      | reduce .[] as $l ({on:false, out:[]};
          if ($l | test("^#+\\s*notes_for_sittings|^notes_for_sittings\\s*:?\\s*$"; "i")) then .on = true
          elif ($l | test("^#")) then .on = false
          elif .on and ($l | test("^\\s*[-*]\\s+")) then .out += [($l | sub("^\\s*[-*]\\s+"; ""))]
          else . end)
      | .out;
  # (c) the people, current first, with relation and age
  ([ "## THE HOUSE BRIEF — mechanical, from rows the house holds; read it before any order",
     "People (current):" ]
   + ([ $pp[] | select(.state == "current")
        | "  - \(.data.name) — \(.data.relation // "?")\(if (.data.born // null) != null then " · age \(age_of(.data.born) // "?")" else "" end)  \(.self)" ]
      | if length == 0 then ["  (nobody affirmed — every person row is observed)"] else . end)
   + [ "Values (with the words they love):" ]
   + ([ $vv[] | select(.state != "retired")
        | "  - \(.data.name) [\(.state)]\(if ((.data.loved // []) | length) > 0 then " — loves " + ((.data.loved) | join(", ")) else "" end)  \(.self)" ]
      | if length == 0 then ["  (none)"] else . end)
   + [ "Standing facts (published findings, newest first, grouped by who or what they name):" ]
   + ([ $in[] | select(.state == "published")
        | . as $i
        | (([ ($i.data.evidence // [])[] | select(test("^/api/(people|values|task_lists)/")) ] | first) // null) as $key
        | {group: (if $key then (name_of($key) // $key) else "the house" end),
           at: (($i.meta.updated_at // "") | .[0:10]),
           line: ("    \(($i.meta.updated_at // "") | .[0:10]) \((.data.finding // "") | .[0:170])  \(.self)")} ]
      | group_by(.group) | sort_by(.[0].group == "the house", .[0].group)
      | map(([ "  [\(.[0].group)]" ] + (sort_by(.at) | reverse | map(.line))))
      | add // ["  (no published finding stands)"])
   + [ "The next 30 days:" ]
   + ([ $ev[] | (.fields.starts_at // .fields.date // null) as $s
        | select($s != null)
        | ($s | in_days($nows)) as $d
        | select($d != null and $d >= 0 and $d <= 30)
        | {s:$s, line:("  - \($s | .[0:10]) \(if ($s | length) > 10 then ($s | .[11:16]) + "Z" else "all day" end) · \((.fields.title // .display.title // "") | tostring)\(if (.fields.location // "") != "" then " · " + .fields.location else "" end)  \(.self)")} ]
      | sort_by(.s) | map(.line) | if length == 0 then ["  (nothing on the calendar)"] else . end)
   + [ "Open threads the owner spoke in (last turn quoted):" ]
   + ([ $th[] | select(.said_by_owner) | "  - \(.subject_kind)/\(.subject_id): “\(.last.says | .[0:160])”  (\(.last.self))" ]
      | if length == 0 then ["  (none)"] else . end)
   + [ "The last 5 journals, and the notes they left:" ]
   + ([ $jj[] ] | sort_by(.meta.updated_at) | reverse | .[0:5]
      | map(. as $j | (notes_of) as $n
            | [ "  - \(($j.meta.updated_at // "") | .[0:10]) \($j.data.title // "")  \($j.self)" ]
              + (if ($n | length) > 0 then ($n | map("      · " + .[0:150])) else [ "      " + (($j.data.body // "") | gsub("\\s+"; " ") | .[0:150]) ] end))
      | add // ["  (no journal yet)"])
  ) as $lines
  | {lines: $lines, total: ($lines | length)}
' > "$D/brief.json" 2>>"$PROBE_ERRS" || echo '{"lines":[],"total":0}' > "$D/brief.json"
jq --argjson cap "$BRIEF_LINES" '
  . + {shown: (.lines | .[0:$cap]),
       cut: (if (.lines | length) > $cap then (.lines | length) - $cap else 0 end)}' \
   "$D/brief.json" > "$D/brief.tmp" && mv "$D/brief.tmp" "$D/brief.json"

# ── THE REVIEW (waymark-nl0): the sittings since the last reading ────
# A reading REVIEWS the clerk's runs: it reads their verify grade lines
# (filed per run dir by `verify`), scores the standing bundles it did
# not write, dismisses thin or false rows where its grant admits the
# door, and leaves notes. The list here is the run directories on this
# machine whose manifest says `sitting`, newer than the last one that
# says `reading`, capped at WAYMARK_REVIEW_SITTINGS, each with its
# grades.txt when verify was run after it. On an ephemeral runner there
# are none, and the manifest says so rather than reading silence as a
# clean record.
LAST_READING=""
for mf in $(ls -1 "$SITDIR"/*/manifest.json 2>/dev/null | sort || true); do
  case "$mf" in */"$STAMP"/manifest.json) continue ;; esac
  if [ "$(jq -r '.run.mode // "sitting"' "$mf" 2>/dev/null)" = "reading" ]; then LAST_READING="$(basename "$(dirname "$mf")")"; fi
done
: > "$D/review.jsonl"
for mf in $(ls -1 "$SITDIR"/*/manifest.json 2>/dev/null | sort || true); do
  rd="$(dirname "$mf")"; st="$(basename "$rd")"
  [ "$st" = "$STAMP" ] && continue
  [ "$(jq -r '.run.mode // "sitting"' "$mf" 2>/dev/null)" = "sitting" ] || continue
  if [ -n "$LAST_READING" ] && ! [ "$st" \> "$LAST_READING" ]; then continue; fi
  jq -nc --arg st "$st" --arg at "$(jq -r '.run.started_at // ""' "$mf")" \
         --argjson orders "$(jq '(.work_orders // []) | length' "$mf" 2>/dev/null || echo 0)" \
         --argjson graded "$([ -f "$rd/grades.txt" ] && echo true || echo false)" \
         --rawfile g "$([ -f "$rd/grades.txt" ] && echo "$rd/grades.txt" || echo /dev/null)" \
         '{stamp:$st, started_at:$at, orders:$orders, graded:$graded,
           grades:($g | split("\n") | map(select(length > 0)))}' >> "$D/review.jsonl"
done
jq -s --argjson n "$REVIEW_N" --arg last "$LAST_READING" --slurpfile notes "$D/notes_for_sittings.json" '
  {since: (if $last == "" then null else $last end),
   sittings: (.[-$n:] // []),
   found: length,
   notes_left: ($notes[0] // [])}' "$D/review.jsonl" > "$D/review.json" 2>>"$PROBE_ERRS" \
  || echo '{"since":null,"sittings":[],"found":0,"notes_left":[]}' > "$D/review.json"

# ── WHAT A REVIEW NEEDS FROM THE LEASH, AND THE ASK THAT OPENS IT ────
# Dismissing a thin finding, a wrong ranking note, an observed person
# that is nobody, or declining a bundle in the owner's name are all
# doors a person opens for an agent by approving an anchored ask
# (waymark-sfe): insight.dismiss, person.dismiss, ranking_note.dismiss,
# outcome.not_this_week — on rows by id, never the kind whole. The
# driver reads which of those the grant already admits, names the thin
# findings the review can already see as the ids an insight.dismiss
# entry would carry, and builds the ask body. Inside the ask window the
# one extend-ask a reading files carries this scope with it (the same
# file-once law: never a second ask while one stands); outside it, the
# body is printed for the reading to file when it holds a row to act on.
# Four eyes hold whatever the grant says: a row THIS principal wrote is
# never its own to dismiss, and both runs may wear one principal.
jq -n --slurpfile grant "$R/grant.json" --slurpfile ins "$R/insights.full.json" \
      --arg me "$PRINCIPAL" --arg gid "$GRANT" '
  (($grant[0].data.scope // $grant[0].scope) // []) as $scope
  | [ {kind:"insight", action:"dismiss"}, {kind:"person", action:"dismiss"},
      {kind:"ranking_note", action:"dismiss"}, {kind:"outcome", action:"not_this_week"} ] as $doors
  | ([ ($ins[0] // [])[] | select(.state == "published")
       | select((.data.authored_by // "") != $me)
       | ((.data.finding // "") ) as $f
       | select(($f | length) < 40
                or ($f | ascii_downcase | test("needs action|requires further action|needs attention|should be done")))
       | {self, id:(.self | split("/") | last), finding:($f | .[0:120])} ]) as $thin
  | [ $doors[] | . as $d
      | (([ $scope[] | select(.kind == $d.kind) | select((.actions // []) | index($d.action) != null) ] | length) > 0) as $has
      | $d + {admitted:$has} ] as $checked
  | ([ $checked[] | select(.admitted | not) ]) as $missing
  | {doors:$checked,
     thin_findings:$thin,
     scope_add: ([ $missing[] | {kind:.kind, actions:[.action]}
                   + (if .kind == "insight" and ($thin | length) > 0 then {ids:[ $thin[] | .id ]} else {} end) ]),
     body: {grant_id:$gid,
            task:("Let the reading answer thin and false rows: " + ([ $missing[] | .kind + "." + .action ] | join(", ")) + " on the rows named, the same leash otherwise."),
            scope: ($scope + [ $missing[] | {kind:.kind, actions:[.action]}
                                + (if .kind == "insight" and ($thin | length) > 0 then {ids:[ $thin[] | .id ]} else {} end) ])}}
' > "$D/review_ask.json" 2>>"$PROBE_ERRS" \
  || echo '{"doors":[],"thin_findings":[],"scope_add":[],"body":null}' > "$D/review_ask.json"

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
      # a READING's extend-ask carries the review doors with it
      # (waymark-nl0): the same one-ask-at-a-time law, one body, and
      # the task names exactly what widens so the person's tap is an
      # informed one. A sitting's ask is the scope copied, as ever.
      if [ "$RUN_MODE" = "reading" ] && [ "$(jq '(.scope_add // []) | length' "$D/review_ask.json" 2>/dev/null || echo 0)" -gt 0 ]; then
        body="$(jq -nc --arg g "$GRANT" --argjson hours "$hours" \
                       --slurpfile ra "$D/review_ask.json" \
                       --arg e "$(iso "$(( $(now_s) + EXTEND ))")" '
                 {grant_id:$g,
                  task:("Keep my standing leash another " + ($hours|tostring) + " hours, and let the reading answer thin and false rows: "
                        + ([ $ra[0].scope_add[] | .kind + "." + (.actions | join("/")) + (if .ids then " on " + ((.ids|length)|tostring) + " named rows" else "" end) ] | join(", "))
                        + ". Everything else the same."),
                  scope:$ra[0].body.scope, expires_at:$e}')"
      else
        body="$(jq -nc --arg g "$GRANT" --arg t "Keep my standing leash: the same scope, another $hours hours." \
                       --argjson s "$(jq -c '.data.scope // .scope' "$R/grant.json")" \
                       --arg e "$(iso "$(( $(now_s) + EXTEND ))")" \
                '{grant_id:$g, task:$t, scope:$s, expires_at:$e}')"
      fi
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
  --slurpfile notmine "$D/iterating_not_mine.json" \
  --slurpfile reworkorders "$D/rework_orders.json" \
  --slurpfile notetimes "$D/note_times.json" \
  --argjson rework_clock "$REWORK_CLOCK" \
  --slurpfile ask "$D/extend_ask.json" \
  --slurpfile gate "$D/gate.json" \
  --slurpfile chatsel "$D/chat_selection.json" \
  --slurpfile chatrows "$R/chat_threads.json" \
  --arg thread_basis "$THREAD_BASIS" \
  --slurpfile diag "$RUN/rows/diagnosis.json" \
  --slurpfile journals "$RUN/rows/journals.full.json" \
  --arg mode "$RUN_MODE" \
  --slurpfile brief "$D/brief.json" \
  --slurpfile review "$D/review.json" \
  --slurpfile review_ask "$D/review_ask.json" \
  --slurpfile house_says "$D/house_says.json" \
  --slurpfile notes "$D/notes_for_sittings.json" \
'
  # WHAT THIS RUN OWNS (waymark-nl0). A sitting owns the clerk orders,
  # the fact-shaped turns and the marked (or clocked) reworks; the
  # editor half is listed under waiting_for_a_reading and not counted.
  # A reading owns both halves.
  ($mode == "sitting") as $sit
  | ([ $orders[0][] | select(.label == "clerk") ]) as $clerk_orders
  | ([ $orders[0][] | select(.label == "editor") ]) as $editor_orders
  | ([ $threads[0][] | select(.label == "clerk") ]) as $clerk_threads
  | ([ $threads[0][] | select(.label == "editor") ]) as $editor_threads
  | ([ $reworkorders[0][] | select(.label == "clerk") ]) as $clerk_reworks
  | ([ $reworkorders[0][] | select(.label == "editor") ]) as $editor_reworks
  | {
  run: {started_at:$started, base_url:$base, snapshot:$run, principal:$principal,
        display:$display, owner_member:$owner,
        mode: $mode,
        formula: (if $sit then "sitting" else "reading" end),
        grant:{id:$grant, state:$gstate, expires_at:$gexp, seconds_left:$left},
        bearer_file: ($run + "/bearer"),
        note: "Every request wears BOTH Authorization: Bearer <bearer_file> and X-Waymark-Grant: <grant.id>. A collection answers a PROJECTION; a row read at its own address answers `data` in full — the snapshot has both (rows/<kind>.json and rows/<kind>.full.json)."},
  grant_watch: $ask[0],
  reads: $reads[0],
  duties: {
    answer_requests: ($requests[0] | length),
    answer_threads:  (if $sit then ($clerk_threads | length) else ($threads[0] | length) end),
    index_facts:     ($facts[0]    | length),
    score_bundles:   ($unscored[0] | length),
    declines_owed_a_diagnosis: ([$declines[0][] | select(.owed_a_diagnosis)] | length),
    advance_arrivals: ($arrivals[0] | length),
    enrich_a_bare_task: ($bare[0] | length),
    # the rework orders the household handed back (waymark-9xn):
    # bundles off its feed until this composer answers
    rework_iterating: (if $sit then ($clerk_reworks | length) else ([$standing[0][] | select(.iterate_open and .mine)] | length) end),
    work_orders: (if $sit then ($clerk_orders | length) else ($orders[0] | length) end),
    # what a sitting leaves for the editor, counted apart and never owed
    waiting_for_a_reading: (if $sit then (($editor_orders | length) + ($editor_threads | length) + ($editor_reworks | length)) else 0 end)
  },
  work_orders: $orders[0],
  waiting_for_a_reading: (if $sit then {orders:$editor_orders, threads:$editor_threads, reworks:$editor_reworks} else null end),
  brief: $brief[0],
  review: (if $sit then null else $review[0] end),
  review_ask: (if $sit then null else $review_ask[0] end),
  house_says: $house_says[0],
  notes_for_sittings: $notes[0],
  work_orders_found: ($orders_all[0] | length),
  crowd_out: ($crowd[0] // null),
  now: $started,
  arrivals_since: $since_arr,
  arrivals_basis: ($arr_basis[0] // []),
  arrivals: ($arrivals[0] | .[0:40]),
  bare_tasks: ($bare[0] | .[0:40]),
  gate: $gate[0],
  # WHICH CONVERSATIONS WERE READ, AND HOW THEY WERE CHOSEN
  # (waymark-36s). `basis` is the honest half: "rows" means the house
  # serves /api/threads and the selection below is a query over it;
  # "heuristic" means the kind is not deployed here yet and the rig own
  # listing was guessed at instead. A reader must never have to infer
  # which of the two produced the material.
  conversations: {
    basis: $thread_basis,
    rows_in_house: ($chatrows[0] | length),
    read_this_run: $chatsel[0]
  },
  standing_outcomes: $standing[0],
  # the marks read as the five lists, one entry per bundle handed back
  # to THIS composer (waymark-wxk) — the work order the rework door
  # itself is written against
  rework_orders: $reworkorders[0],
  # the clock a rework order writes its instants from (waymark-thn),
  # and the clock times the note itself already named (waymark-o04) —
  # suggestions beside the marks, never marks
  rework_clock: $rework_clock,
  note_times: $notetimes[0],
  iterating_not_mine: $notmine[0],
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
  if [ "$RUN_MODE" = "reading" ]; then
    echo "# The house, read at $STARTED — a READING (the editor's run; the law is READING.md)"
  else
    echo "# The house, read at $STARTED — a SITTING (the clerk's run; the law is SITTING.md)"
  fi
  echo
  echo "You are $DISPLAY ($PRINCIPAL) at $BASE."
  echo "Grant $GRANT is $GRANT_STATE and expires $GRANT_EXP."
  jq -r '.grant_watch | "Leash: " + .why + (if .filed then " — filed " + .ask else "" end)' "$RUN/manifest.json"
  echo "Bearer for this run: $RUN/bearer (0600, one hour)."
  echo "Snapshot: $RUN/rows/*.json — <kind>.json is the collection projection, <kind>.full.json each row read at its own address (evidence and routing live only there)."
  echo
  if [ "$RUN_MODE" = "reading" ]; then
    # THE HOUSE BRIEF comes first on a reading (waymark-xnf): the story
    # the rows already tell, before any order asks for judgment
    jq -r '.brief.shown[]' "$RUN/manifest.json"
    jq -r 'if (.brief.cut // 0) > 0
           then "  (… \(.brief.cut) more lines cut by WAYMARK_BRIEF_LINES=\(.brief.shown | length); the whole brief is \(.run.snapshot)/derived/brief.json)"
           else "  (the whole brief, nothing cut)" end' "$RUN/manifest.json"
    echo
  fi
  echo "## What is owed"
  jq -r '.duties | to_entries[] | "- \(.key): \(.value)"' "$RUN/manifest.json"
  if [ "$RUN_MODE" = "sitting" ]; then
    echo "  (waiting_for_a_reading is counted apart and is NOT owed to this run — see that section below)"
  fi
  echo
  if [ "$RUN_MODE" = "reading" ]; then
    echo "## YOUR ORDERS — the clerk's forms AND the editor's orders, labeled (waymark-48a, waymark-nl0)"
    jq -r '"  (\(.work_orders|length) presented of \(.work_orders_found) the probes found — a CEILING per label: WAYMARK_WORK_ORDERS clerk, WAYMARK_EDITOR_ORDERS editor)"' "$RUN/manifest.json"
  else
    echo "## YOUR WORK ORDERS — this run's assignment (waymark-48a)"
    jq -r '"  (\([.work_orders[] | select(.label == "clerk")]|length) clerk orders presented of \(.work_orders_found) the probes found — a CEILING, set by WAYMARK_WORK_ORDERS; the editor orders are under Waiting for a reading)"' "$RUN/manifest.json"
  fi
  jq -r 'if .crowd_out then "  CROWD-OUT: " + .crowd_out.says else empty end' "$RUN/manifest.json"
  jq -r --arg mode "$RUN_MODE" '
    ([ .work_orders[] | select($mode == "reading" or .label == "clerk") ]) as $mine
    | if ($mine|length) == 0 then
      (if $mode == "reading" then "  (no order this run — every probe came up empty)"
       else "  (no clerk order this run — every probe came up empty or handed its order to the editor)" end)
    else
      (. as $root
       | $mine | to_entries[]
       | (.key + 1) as $n | .value as $o
       | ([ "",
            "ORDER \($n) · \($o.probe) · \($o.subject)  [\($o.label | ascii_upcase) — \($o.why_label // "")]",
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
  if [ "$RUN_MODE" = "reading" ]; then
    echo "  These orders are the assignment. Do what is owed first (a person's pull and a person's turn outrank any probe), then the clerk forms as written, then the EDITOR orders — each of those asks for a reading of the rows, and the lawful answers are the write it names, or a skip said out loud in the journal; next run verify prints UNANSWERED AND UNSAID against an editor order that got neither. A goal larger than any row is composed only when the rows imply one."
    echo
    echo "  ONE EXTRA, OR NONE (waymark-mqo). Beyond the orders, a reading may write ONE finding of its own: a row this manifest did not order, cited to the rows you actually read, distinct from everything standing, with one sentence in the journal on why it was worth a row. Not two. A sitting has none. verify grades it EXTRA (cited, distinct) or FILLER (uncited, a twin, or a second one) — so the freedom stays observable. If nothing you read deserves it, write none and say so; that is the usual answer."
  else
    echo "  These orders ARE the assignment, and they are its ceiling. Do what is owed above first (a person's pull and a person's turn outrank any probe), then execute these in the order given — each is one row at one door, and the material to write it is already here. Everything else in this manifest is OPTIONAL material: read it if an order needs it, and stage nothing extra to look busy. An order you cannot answer honestly is skipped and said so in the journal. A run with no work orders and nothing owed writes NOTHING AT ALL, and that is a lawful, complete run (waymark-mho). A sitting fills forms; it has no extra."
    echo
    echo "## Waiting for a reading — the editor's orders; NOT yours, and never owed to this run (waymark-nl0)"
    jq -r '
      .waiting_for_a_reading as $w
      | if (($w.orders|length) + ($w.threads|length) + ($w.reworks|length)) == 0
        then "  (nothing — every order this run found is a clerk form)"
        else
          ($w.orders[] | "- ORDER \(.probe) · \(.subject) — \(.subject_says[0:100])\n    editor because: \(.why_label // "")\n    would write: one \(.write.kind | ascii_upcase) at \(.write.door)"),
          ($w.threads[] | "- QUESTION on \(.subject_kind)/\(.subject_id): \(.last.said_by) asked “\(.last.says[0:140])” (\(.last.self))\n    editor because: the answer has to come from the record, not from the question — a reading answers it FROM what the house already says"),
          ($w.reworks[] | "- REWORK \(.self) [iterating, plan v\(.plan_revision)] \(.goal[0:80])\n    editor because: \(.why_label // "")\n    THE NOTE: " + (if .note then "\(.note.said_by) said “\(.note.says[0:160])”" else "(no turn on the thread)" end))
        end' "$RUN/manifest.json"
    echo "  Leave every one of these exactly as it stands: no reply in words, no piece, no insight, no rework commit. A reading (READING.md — a strong model, morning and evening or on demand) takes them with the whole house in view. verify never faults a sitting for a line on this list."
  fi
  echo
  echo "## Offered requests — a person's pull is never capped"
  jq -r 'if (.offered_requests|length)==0 then "  (none)" else (.offered_requests[] | "- \(.self) by \(.requested_by) good until \(.good_until)") end' "$RUN/manifest.json"
  echo
  if [ "$RUN_MODE" = "reading" ]; then
    echo "## Threads whose last turn is not yours — facts and questions, and WHAT THE HOUSE ALREADY SAYS under each"
  else
    echo "## Threads whose last turn is not yours — the FACTS are yours; a QUESTION waits for a reading"
  fi
  jq -r --arg mode "$RUN_MODE" '
    . as $root
    | [ .unanswered_threads[] | select($mode == "reading" or .label == "clerk") ] as $mine
    | if ($mine|length)==0 then "  (none)" else
      ($mine[]
       | . as $t
       | "- \(.subject_kind)/\(.subject_id): \(.last.said_by)\(if .said_by_owner then " (the owner)" else "" end) said \"\(.last.says[0:160])\" (\(.last.self))  [\(.shape | ascii_upcase) — \(.label)]",
         (if .shape == "fact"
          then "    a fact: index it as an insight citing the remark and the rows it is about, then reply in words (in_reply_to naming their turn)"
          else "    a question: the answer comes FROM the rows below — cite the row that answers it; if the rows contradict the person, say which and quote it; never index a question as a fact" end),
         (([ ($root.house_says // [])[] | select(.outcome == ("/api/outcomes/" + $t.subject_id)) ] | first) as $h
          | if $h == null then "    WHAT THE HOUSE ALREADY SAYS: (the subject is not an outcome in the snapshot — read the row at its own address)"
            else "    WHAT THE HOUSE ALREADY SAYS about \($h.outcome) — \($h.goal[0:80]):",
                 ($h.rows[] | "      · \(.kind) \(.self): \(.says)" + (if .detail != "" then "\n          detail: \(.detail)" else "" end)),
                 (if ($h.findings|length) > 0 then ($h.findings[] | "      · finding \(.at[0:10]) \(.self): \(.finding)") else "      · (no published finding names these rows)" end)
            end))
      end' "$RUN/manifest.json"
  echo "  (a turn by another AGENT is not a work order — only a person's is; judge who said it. Answer FROM the rows printed: if they answer the question, cite them and say so; if they contradict the person, say which row and quote it. Never index a person's question as a fact — verify prints SAYS-SO against a finding with no house row behind it.)"
  echo
  echo "## Turns no insight cites yet — index the FACTS among them"
  jq -r 'if (.candidate_facts|length)==0 then "  (none)" else (.candidate_facts[] | "- \(.self) on \(.subject_kind)/\(.subject_id): \"\(.says[0:120])\"") end' "$RUN/manifest.json"
  echo "  (a question, a thanks or a preference indexes nothing)"
  echo
  if [ "$RUN_MODE" = "reading" ]; then
    echo "## REVIEW — the sittings since the last reading (waymark-nl0)"
    jq -r '
      .review as $r
      | (if $r.since then "  since the reading of \($r.since)" else "  (no earlier reading on this machine — every sitting snapshot here is in scope)" end),
        (if ($r.sittings | length) == 0
         then "  NO GRADED SITTINGS TO REVIEW: no sitting snapshot with a manifest is on this machine since then. verify files each sitting grade lines into its run dir (grades.txt); on an ephemeral runner those do not survive, so this list is empty by construction there, not because the sittings were clean. Review from the rows instead: the bundles below, the published findings in the brief, the journals."
         else ($r.sittings[]
               | "  ─ sitting \(.stamp) (\(.orders) orders)" + (if .graded then ":" else " — NOT GRADED (verify was never run after it, or its dir was lost)" end),
                 (if .graded then (if (.grades|length) == 0 then "      (verify wrote no grade line)" else (.grades[] | "      \(.)") end) else empty end))
         end),
        (if ($r.notes_left | length) > 0
         then "  THE FORMS THE LAST READING LEFT (notes_for_sittings), and whether a row now speaks for each:",
              ($r.notes_left[] | "      · \(if .answered then "ANSWERED" else "STILL OPEN" end) — \(.subject_says)")
         else "  (the last reading left no notes_for_sittings block)" end)' "$RUN/manifest.json"
    echo "  What the review WRITES: a ranking_note on every standing bundle you did not write (the list below), a dismissal on a thin or false row where your grant admits the door, and the journal sentence on what the sittings got wrong and why. What it never does: rewrite a sitting row, score its own, or dismiss a row this principal wrote — four eyes, always."
    echo
    echo "  THE DOORS A REVIEW NEEDS, and whether the leash admits them:"
    jq -r '.review_ask.doors[] | "    · \(.kind).\(.action): \(if .admitted then "admitted" else "ABSENT from the grant" end)"' "$RUN/manifest.json"
    jq -r '
      .review_ask as $a
      | (if ($a.thin_findings | length) > 0
         then "  THIN published findings by other hands (the mechanical candidates for insight.dismiss):",
              ($a.thin_findings[] | "    · \(.self) — \(.finding)")
         else "  (no thin published finding by another hand — nothing mechanical to dismiss)" end),
        (if ($a.scope_add | length) > 0
         then "  THE ASK that opens the absent doors — anchored to this grant, on rows by id where the review names them, never the kind whole. Inside the ask window the driver files it as the one extend-ask (grant_watch above says whether it did); otherwise, when you hold a row to act on and no ask of yours stands, POST /api/approval_requests with this body (add expires_at = the grant own expiry or later) and REPORT THE ASK ID:",
              "    \($a.body | tojson)"
         else "  (every review door is already in the leash)" end)' "$RUN/manifest.json"
    echo "  An ask decides nothing — a person taps it in the feed. Never a second ask while one stands; never a door the ask did not open."
    echo
  fi
  echo "## Bundles carrying no live judgment of yours — newest first"
  jq -r 'if (.unscored_bundles|length)==0 then "  (none)" else (.unscored_bundles[] | "- \(.self) [\(.state)] \(.goal[0:100])\n    value: \(.value_name // "?")  pieces: \(.pieces|length)  decline words: \([.reasons[]|.reason]|tostring)\n    cite: \(.cite|tostring)") end' "$RUN/manifest.json"
  echo "  (a note's evidence is the whole cite list — a score read off the headline alone is not a judgment)"
  echo
  echo "## Declines OWED a diagnosis — only a prior something here is about to RECOMPOSE"
  jq -r '[.declines[] | select(.owed_a_diagnosis)] | if length==0 then "  (none — nothing this run would write recomposes a declined prior, so DIAGNOSE NOTHING this sitting. The duty is the gate in front of a recomposition, not a tax on every verdict — waymark-me9)" else (.[] | "- \(.self) — \(.goal[0:80])\n    the house said: \(.house_says)\(if .verdict_word and .note then " (word: \(.verdict_word))" else "" end)\(if .note then "  — the diagnosis is ALREADY ON THE RECORD: cite the note, do not restate it" else "" end)\n    WOULD BE RECOMPOSED BY: \([.recomposed_by[] | "\(.kind) \(.who) (\(.at)) — \(.why)"] | join("; "))\n    cite: \(.cite|tostring)\(if (.reasons|length)>0 then "  — the verdict_reason row is in that list; quote its word" else "  — no verdict_reason row stands, so the citation is the outcome itself" end)\n    offer one of: \(if (.offer_candidates|length)==0 then "(no standing row to offer a door on — cite the outcome and offer nothing forward; do not offer a door on the declined prior)" else ([.offer_candidates[] | "\(.self) [\(.kind) \(.state)] tappable doors \((if (.light_doors|length)>0 then .light_doors else ["(none seen — read the row)"] end)|tostring) (all: \(.doors|tostring))"] | join("; ")) end)") end' "$RUN/manifest.json"
  echo "  ONE light door on ONE standing row, off the offer_candidates above — the declined prior is terminal and admits none, so expire/retire on it is not a step, it is burial; offers-something-light refuses anything that takes input, prioritize included, so a rank goes in an outcome piece."
  echo "  THE FINDING SAYS WHAT THE RECOMPOSITION CHANGES BECAUSE OF THE DECLINE. \"Declined with no reason given.\" is not a finding — it is the manifest line copied out, it fits every row above equally, and a run that publishes it N times has published nothing N times (verify calls that a DIAGNOSIS FLOOD). Where the house left a note, the diagnosis is already on the record: cite it and say what you are doing differently."
  echo
  echo "## Declined, not being recomposed — no diagnosis owed"
  jq -r '[.declines[] | select(.owed_a_diagnosis|not) | select((.recomposed_by|length)==0)] | if length==0 then "  (none)" else (.[] | "- \(.self) · \(.goal[0:60]) · \(.house_says)\(if .diagnosis_stands then " · already diagnosed" else "" end)") end' "$RUN/manifest.json"
  echo "  This is the household's mind, read without writing a row. Nothing on this list is owed anything: no insight, no score, no remark. If one of them ever comes back as a recomposition, it moves up to the list above and gets its diagnosis then."
  echo
  echo "## Declines already diagnosed, and under recomposition pressure — nothing to do"
  jq -r '[.declines[] | select(.diagnosis_stands) | select((.recomposed_by|length)>0)] | if length==0 then "  (none)" else (.[] | "- \(.self) · \(.goal[0:60]) · \(.house_says) — its diagnosis stands; recompose freely") end' "$RUN/manifest.json"
  echo
  if [ "$RUN_MODE" = "reading" ]; then
    echo "## Handed back for a rework — YOUR work orders, marked and unmarked alike, and they are off the household's feed"
  else
    echo "## Handed back for a rework — YOUR work orders (the MARKED and the CLOCKED ones), and they are off the household's feed"
  fi
  jq -r --arg mode "$RUN_MODE" '
    [ (.rework_orders // [])[] | select($mode == "reading" or .label == "clerk") ]
    | if length==0 then "  (none — nothing of yours is being reworked" + (if $mode == "sitting" then " by a form; an unmarked, unclocked note is under Waiting for a reading" else "" end) + ")"
      else (.[] | "- \(.self) [iterating, plan v\(.plan_revision)] \(.goal[0:90])  [\(.label | ascii_upcase) — \(.why_label // "")]") end' "$RUN/manifest.json"
  echo "  An ITERATING bundle is one a person kept and sent back: the goal is right, the plan is wrong, and it has LEFT THEIR FEED until you answer. Read its thread for the note. Withdraw the pieces that were wrong (POST /api/outcome_pieces/<id>/-/rework — the piece goes reworked, never declined), stage the replacements, then commit with POST /api/outcomes/<id>/-/rework {says}. That commit is the only door back to offered; until it lands, nobody in the house can see the bundle at all. Do not stage a twin, and do not wait for a decline."
  jq -r '
    if (((.rework_orders // []) | length) == 0
        and ((.note_times // []) | length) == 0) then empty
    else ("",
      "  THE HOUSEHOLD CLOCK — a rework writes instants, so here they are already converted. Every hour below is a WALL CLOCK in America/Denver rendered UTC; the day own offset is asked of the zone, so it is right on both sides of the change.",
      (((.rework_clock // [])[]) | . as $row
       | "    · \(.weekday) \(.date) (\(.zone))  "
         + ([ $row.hours[]
              | "\(.local)→\(.utc[11:16])Z"
                + (if (.utc[0:10]) != $row.date then "(+1d)" else "" end) ]
            | join("  "))),
      "  A clock time a person says is LOCAL. Write the UTC beside it from the rows above and NEVER write the local hour with a Z: an 11 AM party posted as 11:00:00Z is five in the morning, Mountain, which is how waymark-thn was found. A day this table does not carry is a day you convert from the nearest row on it, and say so.")
    end' "$RUN/manifest.json"
  jq -r --arg mode "$RUN_MODE" '
    . as $root
    | [ (.rework_orders // [])[] | select($mode == "reading" or .label == "clerk") ] as $mine
    | if ($mine | length) == 0 then empty else
    ($mine[]
     | . as $o
     | "",
       "  \u2500 \(.self) \u2014 \(.goal[0:90])  [\(.label | ascii_upcase)]",
       "    THE NOTE: " + (if .note then "\(.note.said_by) said \u201c\(.note.says)\u201d  (\(.note.self))" else "(no turn on the thread \u2014 read \(.thread))" end),
       (([ ($root.house_says // [])[] | select(.outcome == $o.self) ] | first) as $h
        | if $h == null then empty
          else "    WHAT THE HOUSE ALREADY SAYS (read the note against these before you touch a piece):",
               ($h.rows[] | "      \u00b7 \(.kind) \(.self): \(.says)" + (if .detail != "" then "\n          detail: \(.detail)" else "" end)),
               (if ($h.findings|length) > 0 then ($h.findings[] | "      \u00b7 finding \(.at[0:10]) \(.self): \(.finding)") else empty end)
          end),
       "    KEEP \u2014 write nothing, and withdrawing one of these is REFUSED:",
       (if (.keep|length)==0 then "      (none still standing)"
        else (.keep[] | "      \u00b7 \(.self) \u2014 \(.says[0:100])") end),
       (if (.marked|length)==0
        then "    MARKED: nothing. The note is then the WHOLE order and what changes is your reading of it \u2014 add what it asks, or stand by the plan and say so in says. Both are lawful; the wall stands down."
        else "    MARKED \u2014 each is an order, and the commit is refused until it is answered:" end),
       (.marked[] | "      \u00b7 \(.list)  \(.self) \u2014 \(.says[0:90])\n          said \(.word // "no word at all, so it is a DROP")\(if .words then " \u2014 \u201c\(.words)\u201d" else "" end)\n          write: \(.write)"),
       "    ADD \u2014 whatever the note asks that no list above covers: a NEW piece citing this bundle. Nothing else in the note is an order.",
       "    THIS ROUND SO FAR: \(.staged_this_round|length) staged, \(.owed) owed" + (if .unanswered > 0 then " \u2014 \(.unanswered) STILL UNANSWERED" else " \u2014 every mark answered" end) + (if (.withdrawn_this_round|length) > 0 then ", \(.withdrawn_this_round|length) withdrawn this round" else "" end),
       ( [ ($root.note_times // [])[] | select(.self == $o.self) | .suggestions[] ] as $sg
         | if ($sg | length) == 0 then empty
           else "    THE NOTE OWN CLOCK TIMES — SUGGESTIONS, not marks; no door refuses any of them:",
                "      A note that names a time for a piece is a RE-TIME even when nobody tapped Wrong time — write the UTC beside the local hour from the household clock above; never write the local hour with a Z.",
                ($sg[]
                 | "      · \(.kind)  \(.piece // "(no piece in this plan matches)") — the note says \(.note_local)\(if .note_local_end then " to " + .note_local_end else "" end) local on \(.note_date)"
                   + "\n          from “\(.sentence)” — \(.said_by) said it in \(.remark)"
                   + (if .piece then "\n          the piece holds \(.piece_local) local\(if .piece_local_end then " to " + .piece_local_end else "" end) — \(.piece_says)" else "" end)
                   + (if ((.matched_on // []) | length) > 0 then "\n          matched on \((.matched_on) | join(", "))" else "" end)
                   + "\n          the UTC for that hour: starts_at \(.note_utc)\(if .note_utc_end then ", ends_at " + .note_utc_end else "" end)"
                   + "\n          write: "
                   + (if .kind == "RE-TIME"
                      then "POST /api/outcome_pieces — the SAME step at that hour (form create, target_kind \(.piece_target_kind // "event"), prepared.starts_at the UTC above), citing this bundle. Do NOT withdraw \(.piece): a piece nobody marked is a KEEP."
                      elif .kind == "INVOKE"
                      then (if ((.light_doors // []) | length) > 0
                            then "that piece is already TAKEN and its row exists — \(.materialized). Offer that row own light door (\((.light_doors) | join(", "))) rather than staging a second one."
                            else "that piece is already TAKEN and its row exists — \(.materialized // "the row it made"). That row exposes no light door to you, so THE EVENT EXISTS AND A PERSON MOVES IT: say so in `says`, and do NOT stage a second event at the new hour." end)
                      else "POST /api/outcome_pieces — a NEW piece at that hour, citing this bundle. No piece in the plan shares a word with this sentence." end)) end ))
    end' "$RUN/manifest.json"
  echo "  THE MARKS ARE THE ORDER (waymark-wxk). A piece the household declined is a work order and its quick word says which: WRONG TIME is a RE-TIME (the same step at a new hour), WRONG PIECE or NOT THIS WAY is a REPLACE (a different step toward the same goal), NEVER THIS \u2014 or a decline carrying no word at all \u2014 is a DROP that needs nothing more, and a piece still standing is a KEEP you may not withdraw. A RE-TIME and a REPLACE are each a NEW piece staged under the same bundle; you never withdraw the marked piece itself, because a declined piece is already out. POST /api/outcomes/<id>/-/rework is REFUSED by name while a mark is unanswered or a KEEP has been withdrawn, and the refusal lists the offenders with their lists. Where the household marked NOTHING, none of that applies and the note is the whole order."
  echo "  YOU CANNOT PROMISE THIS ONE, YOU CAN ONLY DO IT (waymark-vf8). The REPLY DOOR IS CLOSED on a bundle you could rework: POST /api/remarks on it is refused by name (words-do-not-answer) and the refusal names this door. Your words ride the rework itself — says, required, at most 240 characters, posted as your turn on the thread. And a rework that changes NO piece is a LAWFUL answer: if you read the note and the plan still stands, or you cannot stage what was asked for, commit anyway and say that — it counts the round, puts the bundle back on their feed, and they may then decline it. Leaving it in iterating is the one wrong answer, and next run verify prints HANDED BACK, NOT REWORKED against your name."
  jq -r '
    [ (.note_times // [])[] | select(.iterate_open | not) ] as $off
    | if ($off | length) == 0 then empty
      else "",
           "## Offered, and a time the household named is STILL UNHELD (waymark-o04)",
           "  Nobody handed these back, so there is no rework door to walk on them — and that is the point: a NO-CHANGE rework is what usually leaves a bundle here. The round was counted, the bundle went back on the feed looking answered, and the hour the note asked for was never staged. Say it in the journal in one sentence; if the household hands the bundle back, the lines below ARE the order.",
           ($off[]
            | "", "  ─ \(.self) — \(.goal[0:90])",
              (.suggestions[]
               | "      · \(.kind)  \(.piece // "(no piece in this plan matches)") — the note says \(.note_local)\(if .note_local_end then " to " + .note_local_end else "" end) local on \(.note_date) = \(.note_utc)"
                 + (if .piece then ", while the piece holds \(.piece_local) local" else "" end)
                 + "\n          from “\(.sentence)” — \(.said_by) said it in \(.remark)"))
      end' "$RUN/manifest.json"
  echo
  echo "## Iterating, not yours to rework"
  jq -r 'if (.iterating_not_mine|length)==0 then "  (none)" else (.iterating_not_mine[] | "- \(.self) [iterating, plan v\(.plan_revision)] composed by \(.composed_by // "?") — \(.goal[0:80])") end' "$RUN/manifest.json"
  echo "  Somebody else staged these and only its own composer walks its rework door unasked. Leave them alone: no twin, no verdict, no piece. If a composer here is gone and the household is waiting, say so in the journal — the way through is a grant the owner approves naming outcome.rework on that row, not a second bundle."
  echo
  echo "## Already standing — NEVER twin one of these"
  jq -r 'if (.standing_outcomes|length)==0 then "  (nothing stands yet)" else (.standing_outcomes[] | "- \(.self) [\(.state)] \(.goal[0:90])\n    cites: \(.evidence|tostring)") end' "$RUN/manifest.json"
  echo "  Standing is three states now (waymark-9xn): offered, iterating — handed back for a re-plan — and accepted. A bundle being reworked speaks for the rows it cites exactly as loudly as one on the fridge."
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
  echo "## Conversations read this sitting"
  jq -r '"  chosen by: " + .conversations.basis' "$RUN/manifest.json"
  jq -r 'if (.conversations.read_this_run|length)==0
         then "  (none — nothing was said in the window, or this house serves no thread rows yet; a chat named in prose is not an address, so cite nothing you did not read)"
         else (.conversations.read_this_run[]
               | "- \(.self) [\(.source) \(.chat_kind // "?")] \(.title) — last word \(.at[0:16])"
                 + (if (.people|length) > 0 then " · names \(.people|join(", "))" else "" end)) end' \
    "$RUN/manifest.json"
  echo "  A fact said in one of these cites the THREAD's address plus the person rows it names — never the Gate line, and never the message's own sentence."
  echo
  echo "## Prior sittings"
  jq -r 'if (.prior_journals|length)==0 then "  (none — this is the first)" else (.prior_journals[] | "- \(.at) \(.title)") end' "$RUN/manifest.json"
} > "$RUN/manifest.md"

echo
cat "$RUN/manifest.md"
echo
echo "manifest: $RUN/manifest.json"
if [ "$RUN_MODE" = "reading" ]; then
  echo "read it, then do the judgment: $ROOT/.beads/formulas/reading.formula.toml is the work order, and READING.md the law. End the journal with a notes_for_sittings block — one line per form: '- do <write> at <door> citing </api/…> — <the sentence>'."
else
  echo "read it, then do the judgment: $ROOT/.beads/formulas/sitting.formula.toml is the work order."
fi
