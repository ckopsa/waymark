#!/bin/bash
# Stop hook: close the seat's open sitting with the session's exact
# token counts. A Routine's firing is one prompt, so its one Stop
# event is where the bill is known. The engine never estimates; this
# is the report it records (docs/spec-seat.md R-12.17).
#
# Two paths. When the environment carries the door's URL, the hook
# posts the counts. When it carries nothing — a cloud environment that
# takes a repository and no settings — the hook holds the stop one
# time and gives the counts to the session, which closes its own
# sitting through the connector.
set -u

HOOK=$(cat)   # the hook's JSON, on stdin. Both paths read it.

# The program sums the transcript. Its argument is the path: "post"
# prints the door's body; "block" prints the answer to the harness, or
# nothing when the session must be left alone.
SUM=$(cat <<'PY'
import glob, json, os, re, sys
FIELDS = ("input_tokens", "output_tokens",
          "cache_read_input_tokens", "cache_creation_input_tokens")
MODE = sys.argv[1] if len(sys.argv) > 1 else "post"
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

def text_of(block):  # a tool result is a string, or blocks of text
    body = block.get("content")
    if isinstance(body, list):
        return " ".join(p.get("text") or "" for p in body if isinstance(p, dict))
    return body if isinstance(body, str) else ""

totals, turns = dict.fromkeys(FIELDS, 0), 0
sat, sitting, closed = set(), "", False
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
            if not isinstance(record, dict):
                continue
            content = (record.get("message") or {}).get("content")
            # The sit answers the sitting's id. A close in the main
            # transcript means the bill is already in.
            for block in (content if index == 0 and isinstance(content, list) else []):
                if not isinstance(block, dict):
                    continue
                name, kind = str(block.get("name") or ""), block.get("type")
                if kind == "tool_use" and name.endswith("waymark_sit"):
                    sat.add(block.get("id"))
                elif kind == "tool_use" and name.endswith("waymark_invoke"):
                    arg = block.get("input") or {}
                    closed = closed or (arg.get("kind") == "sitting"
                                        and arg.get("action") == "close")
                elif kind == "tool_result" and block.get("tool_use_id") in sat:
                    named = re.findall(r'"sitting"\s*:\s*"([^"]+)"', text_of(block))
                    if named:
                        sitting = named[-1]  # the last sit is the open one
            if record.get("type") != "assistant":
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

count = tuple(totals[field] for field in FIELDS)
if MODE == "post":
    json.dump({"input_tokens": count[0], "output_tokens": count[1],
               "cache_read_tokens": count[2], "cache_write_tokens": count[3],
               "turns": turns, "harness_session": session,
               "note": ("Closed by the Stop hook after %d turns." % turns)[:240]},
              sys.stdout)
    sys.exit(0)

# Say nothing unless a sitting is open, no close is in the transcript,
# and the harness is not already going on because this hook blocked.
if not sitting or closed or hook.get("stop_hook_active"):
    sys.exit(0)
json.dump({"decision": "block", "reason": (
    'Before you stop, close your sitting with its exact usage. Call '
    'waymark_invoke with kind "sitting", id "%s", action "close", and input '
    '{"input_tokens": %d, "output_tokens": %d, "cache_read_tokens": %d, '
    '"cache_write_tokens": %d, "turns": %d, "harness_session": "%s", "note": '
    '"<one sentence: what this wake moved, at most 240 characters>"}. Copy the '
    'numbers exactly as written; they are the harness\'s count, not yours. '
    'Then stop.') % (sitting, count[0], count[1], count[2], count[3],
                     turns, session)}, sys.stdout)
PY
)

# Path one: the environment carries the door. Post, and never block.
if [ -n "${WAYMARK_SEAT_URL:-}" ]; then
  BODY=$(printf '%s' "$HOOK" | python3 -c "$SUM" post 2>/dev/null) || {
    echo "waymark: the sitting hook could not read the transcript." >&2; exit 0; }
  [ -n "$BODY" ] || exit 0
  # The key goes in a header, not a bearer: the identity layer reads a
  # bearer as an OIDC token. A stored credential adds the header
  # outside the container, so send the variable only when it is set.
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
fi

# Path two: no door in the environment. Hold the stop one time and
# hand the session the id and the counts. A session that never sat
# gets nothing: the hook rides in every waymark cloud session.
ANSWER=$(printf '%s' "$HOOK" | python3 -c "$SUM" block 2>/dev/null) || {
  echo "waymark: the sitting hook could not read the transcript." >&2; exit 0; }
[ -n "$ANSWER" ] && printf '%s\n' "$ANSWER"
exit 0
