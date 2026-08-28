#!/usr/bin/env bash
# The sitting's MECHANICAL half (waymark-3z3's formula, waymark-53u's
# leash): everything one composer sitting does that needs no judgment.
# A model should arrive at the door already holding the house — so this
# script mints the credential, proves it at one address, pulls every
# read the formula's "read the house" step names into a snapshot of raw
# JSON, works out the lists the later steps are DEFINED in terms of
# (offered requests, unanswered turns, facts nothing has indexed,
# bundles carrying no judgment of ours, declines owed a diagnosis), and
# writes ONE manifest. What is left for the model is judgment and the
# door writes — which facts matter, what to compose, what to score.
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
# WAYMARK_MAX_HYDRATE (120).
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
  if [ -z "$SINCE" ]; then
    prev="$(ls -1 "$SITDIR"/*/manifest.json 2>/dev/null | sort | tail -n 1)"
    [ -n "$prev" ] && SINCE="$(jq -r '.run.started_at' "$prev")"
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
    echo "NOTHING written. Under waymark-mho there is NO floor: if the manifest's arrivals were all handled (or there were none) and no bare task was worth enriching, a run that wrote nothing is a lawful no-op. It is only a FAILED run if an arrival was a person's unanswered remark, or a bare task plainly needed enriching, and it was left alone."
  else
    echo "$wrote row(s) written by this principal since $SINCE."
  fi
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
# admits none, and a diagnosis that offers one is dead on arrival)
for f in "$R"/*.json; do
  jq -c 'if type=="array" then .[] | select(.self? and .kind?) | {self, kind, state} else empty end' "$f" 2>/dev/null
done | jq -s 'unique_by(.self)' > "$D/all_rows.json"

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
                                | {self, kind, state, doors: ($res[.kind].actions // [])} ],
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

# NO FLOOR (waymark-mho, the owner's ruling 2026-08-27 that RETRACTS the
# floor): a run advances concrete ARRIVALS and enriches BARE tasks; it
# does not owe a manufactured outcome, and a quiet run is a lawful
# no-op. What follows computes the three lists that job is defined in
# terms of: arrivals (new since we last looked), bare tasks (the
# minimum — enrich one), and the cited set the compose path still needs.

# the previous run's start, so "new" means new since we last looked; on
# a first run look back one hour rather than flooding the arrival list
# with the whole house.
PREV_MANIFEST="$(ls -1 "$SITDIR"/*/manifest.json 2>/dev/null | grep -v "/$STAMP/manifest.json" | sort | tail -n 1)"
SINCE_ARR="$(jq -r '.run.started_at // empty' "$PREV_MANIFEST" 2>/dev/null)"
# On an EPHEMERAL runner (Jules clones fresh — no prior snapshot) there
# is no PREV_MANIFEST, so the self-diff below cannot fire and arrivals
# fall back to a timestamp. The best persisted watermark that survives
# a fresh clone is our own last JOURNAL — "new since I was last here" —
# read from the snapshot the driver already took; a truly first run
# with no journal looks back one hour.
if [ -z "${SINCE_ARR:-}" ]; then
  SINCE_ARR="$(jq -r '[.[].meta.updated_at] | max // empty' "$R/journals.full.json" 2>/dev/null)"
fi
[ -n "${SINCE_ARR:-}" ] || SINCE_ARR="$(iso "$(( $(now_s) - 3600 ))")"

# ARRIVALS: rows that are genuinely NEW — present now, absent from the
# last run's snapshot. A person's remark, a new or Gate-synced task, a
# new event: the concrete input this run reacts to. Keyed on self, NOT
# on updated_at, because a mirror sync bumps updated_at on every row and
# would drown a real arrival. When there is no prior snapshot (first
# run) fall back to "updated in the last hour" so the run is not blind.
ARR_ACC="$(mktemp)"; echo '[]' > "$ARR_ACC"
PREV_DIR="$([ -n "$PREV_MANIFEST" ] && dirname "$PREV_MANIFEST" || echo "")/rows"
for kf in tasks remarks.full events media chore_runs; do
  cur="$R/$kf.json"; [ -f "$cur" ] || continue
  prev="$PREV_DIR/$kf.json"
  if [ -n "$PREV_MANIFEST" ] && [ -f "$prev" ]; then
    jq -s '(.[0] | map(.self)) as $seen
           | .[1] | map(select((.self != null) and (($seen | index(.self)) == null)))' \
       "$prev" "$cur" > "$ARR_ACC.k" 2>/dev/null || echo '[]' > "$ARR_ACC.k"
  else
    jq --arg s "$SINCE_ARR" 'map(select((.self != null) and ((.meta.updated_at // "") > $s)))' \
       "$cur" > "$ARR_ACC.k" 2>/dev/null || echo '[]' > "$ARR_ACC.k"
  fi
  jq -s '.[0] + .[1]' "$ARR_ACC" "$ARR_ACC.k" > "$ARR_ACC.m" && mv "$ARR_ACC.m" "$ARR_ACC"
done
jq 'map({self, kind, state, at:(.meta.updated_at // null),
         says:((.display.title // .summary // "") | .[0:160])})
    | sort_by(.at) | reverse' "$ARR_ACC" > "$D/arrivals.json" 2>/dev/null || echo '[]' > "$D/arrivals.json"
rm -f "$ARR_ACC" "$ARR_ACC.k"

# the CITED set — every row an outcome or insight already speaks for
jq -s '(.[0] + .[1]) | unique' \
   <(jq '[.[].data.evidence[]?]' "$R/outcomes.full.json") \
   <(jq '[.[].data.evidence[]?]' "$R/insights.full.json") \
   > "$D/cited.json" 2>/dev/null || echo '[]' > "$D/cited.json"

# BARE tasks — the minimum a quiet run still does: enrich ONE. A task
# is bare when it is actionable, carries no detail, and no outcome or
# insight yet speaks for it. Enrichment ANNOTATES it (an insight citing
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

# UNCOMPOSED evidence — no longer a floor, just context for when a real
# outcome IS warranted: rows nothing has composed or enriched yet.
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
# the rows it cites, so the floor's dig does not restage a bundle the
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
jq '[.[] | select(.state != "retired")
        | {id:(.self|split("/")|last), self, state, name:(.data.name // .summary),
           loved:(.data.loved // null)}]' "$R/values.full.json" > "$D/live_values.json"
jq '[.[] | select(.state == "current")
        | {id:(.self|split("/")|last), self, name:(.data.name // .summary),
           relation:(.data.relation // null)}]' "$R/people.full.json" > "$D/companions.json"

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
  --arg since_arr "$SINCE_ARR" \
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
    enrich_a_bare_task: ($bare[0] | length)
  },
  now: $started,
  arrivals_since: $since_arr,
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
  jq -r '[.declines[] | select(.diagnosis_stands|not)] | if length==0 then "  (none — every decline already carries one; DIAGNOSE NOTHING this sitting)" else (.[] | "- \(.self) reasons=\([.reasons[]|.reason]|tostring) — \(.goal[0:80])\n    cite: \(.cite|tostring)\n    offer one of: \(if (.offer_candidates|length)==0 then "(no standing row to offer a door on — cite the outcome and offer nothing forward; do not offer a door on the declined prior)" else ([.offer_candidates[] | "\(.self) [\(.kind) \(.state)] doors \(.doors|tostring)"] | join("; ")) end)") end' "$RUN/manifest.json"
  echo "  (quote the reason row you cite; reasons=[] means say \"no reason given\" and cite the outcome. The offered step is a door the offered row admits NOW — the declined prior admits none, so expire/retire on it is not a step, it is burial)"
  echo
  echo "## Declines already diagnosed — nothing to do"
  jq -r '[.declines[] | select(.diagnosis_stands)] | if length==0 then "  (none)" else (.[] | "- \(.self) reasons=\([.reasons[]|.reason]|tostring) — \(.goal[0:80])") end' "$RUN/manifest.json"
  echo
  echo "## Already standing — NEVER twin one of these"
  jq -r 'if (.standing_outcomes|length)==0 then "  (nothing stands yet)" else (.standing_outcomes[] | "- \(.self) [\(.state)] \(.goal[0:90])\n    cites: \(.evidence|tostring)") end' "$RUN/manifest.json"
  echo "  A candidate whose GOAL says the same thing, or that cites the SAME evidence row, as any of these is a twin — do not stage it. Compose only what is genuinely not here yet."
  echo
  echo "## What arrived since the last run — process these"
  jq -r '"  (new since " + .arrivals_since + ")"' "$RUN/manifest.json"
  jq -r 'if (.arrivals|length)==0 then "  (nothing new — a quiet run. If nothing below is owed either, journal nothing and leave: a no-op is a lawful run)" else (.arrivals[] | "- \(.self) [\(.kind) \(.state)] \(.says)") end' "$RUN/manifest.json"
  echo "  Each arrival is a concrete thing to ADVANCE as far as it honestly goes — no further. A person's remark is a work order (answer it). A new or synced task: read it in FULL, then either ENRICH it (below) or, only if it plus its situated graph implies a GOAL LARGER THAN ANY SINGLE ROW, COMPOSE an outcome. Do not manufacture an outcome to have done something — there is no floor."
  echo
  echo "## Bare tasks — the minimum a run does: ENRICH one (never mutate it)"
  jq -r 'if (.bare_tasks|length)==0 then "  (none bare — every actionable task already carries detail or is spoken for)" else (.bare_tasks[] | "- \(.self) [\(.state)] \(.title)\(if .due_at then " · due " + .due_at[0:10] else "" end)") end' "$RUN/manifest.json"
  echo "  To enrich a bare task: publish an INSIGHT (POST /api/insights) whose evidence cites the task AND the source you read (a Gate email/chat, a related row), whose finding is the context that makes the task actionable (what it is really for, where/when/with what, its real next physical step), and whose offer_kind/offer_id/offer_action names the task's own next door. This ANNOTATES the task beside it — it does not touch the task's fields; only the household edits its own rows. An enrichment that does not change whether the task is actionable is not worth writing."
  echo "  A finding must carry at least one FACT the task's row does not already state — what it is really for, who or where, when, or the next physical step — in a full sentence. \"This task needs action.\" is not an enrichment; verify flags as THIN any finding under 40 characters, or one reading 'needs action' / 'requires further action' / 'needs attention' / 'should be done'."
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
