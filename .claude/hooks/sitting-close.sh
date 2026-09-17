#!/bin/bash
# Stop hook: close the seat's open sitting with the session's exact
# token counts. A Routine's firing is one prompt, so its one Stop
# event is where the bill is known. The engine never estimates; this
# is the report it records (docs/spec-seat.md R-12.17).
set -u

# The hook rides in every waymark cloud session. Only a seat's
# environment carries the door's URL; everywhere else, be silent.
[ -n "${WAYMARK_SEAT_URL:-}" ] || exit 0

# The hook's JSON arrives on stdin. The heredoc holds the program, so
# stdin stays the hook's own and python3 reads it.
SUM=$(cat <<'PY'
import glob, json, os, sys
FIELDS = ("input_tokens", "output_tokens",
          "cache_read_input_tokens", "cache_creation_input_tokens")
try:
    hook = json.loads(sys.stdin.read() or "{}")
except Exception:
    hook = {}
session, main = str(hook.get("session_id") or ""), str(hook.get("transcript_path") or "")
# A subagent's transcript sits beside the main one, with the same
# shape. Its tokens are the sitting's too.
paths = [main] if main else []
if main and session:
    paths += sorted(glob.glob(os.path.join(
        os.path.dirname(main), session, "subagents", "agent-*.jsonl")))
totals, turns = dict.fromkeys(FIELDS, 0), 0
for index, path in enumerate(paths):
    seen = set()
    try:
        handle = open(path, "r", errors="replace")
    except OSError:
        continue
    with handle:
        for line in handle:
            try:
                record = json.loads(line)
            except Exception:
                continue  # a partial or non-JSON line is not a bill
            if not isinstance(record, dict) or record.get("type") != "assistant":
                continue
            # One API response is several lines, one for each content
            # block, all with one requestId. Count it once.
            request = record.get("requestId") or record.get("uuid") or ""
            if request in seen:
                continue
            seen.add(request)
            usage = (record.get("message") or {}).get("usage") or {}
            for field in FIELDS:
                if isinstance(usage.get(field), int):
                    totals[field] += usage[field]
    if index == 0:
        turns = len(seen)
json.dump({"input_tokens": totals["input_tokens"],
           "output_tokens": totals["output_tokens"],
           "cache_read_tokens": totals["cache_read_input_tokens"],
           "cache_write_tokens": totals["cache_creation_input_tokens"],
           "turns": turns, "harness_session": session,
           "note": ("Closed by the Stop hook after %d turns." % turns)[:240]},
          sys.stdout)
PY
)
BODY=$(python3 -c "$SUM") || exit 0
[ -n "$BODY" ] || exit 0

# The key goes in a header, not a bearer: the identity layer reads a
# bearer as an OIDC token. When the environment holds the key as a
# stored credential, the proxy adds the header outside the container
# and the variable is absent. Send it only when it is set.
KEY=()
[ -n "${WAYMARK_SEAT_KEY:-}" ] && KEY=(-H "Waymark-Seat-Key: ${WAYMARK_SEAT_KEY}")

REPLY=$(curl -sS --max-time 20 -X POST -H 'Content-Type: application/json' \
  ${KEY[@]+"${KEY[@]}"} -d "$BODY" -w '\n%{http_code}' "$WAYMARK_SEAT_URL" 2>&1)
STATUS=${REPLY##*$'\n'}

# A door that refuses must not fail the session. Say one line and go.
case "$STATUS" in
  2??) exit 0 ;;
  [1-5][0-9][0-9])
    DETAIL=$(printf '%s' "${REPLY%$'\n'*}" | python3 -c 'import json,sys
try: print(json.loads(sys.stdin.read()).get("detail") or "")
except Exception: pass' 2>/dev/null)
    echo "waymark: the sitting close was answered ${STATUS}. ${DETAIL}" >&2 ;;
  *) echo "waymark: the sitting close did not reach the door: $(printf '%s' \
       "$REPLY" | tr '\n' ' ')" >&2 ;;
esac
exit 0
