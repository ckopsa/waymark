#!/usr/bin/env bash
# The inbox feed of a seat's sitting, for a Claude Code Monitor.
#
#   inbox-feed.sh <inbox url> <inbox key> [--batch N] [--every SECONDS]
#                 [--urgent kind[:action],...]
#
# WHY THIS EXISTS. Every seat repository carried its own copy of a feed
# script. This is the one copy: it tails the inbox door of
# docs/spec-seat.md R-12.38 with the `inbox {url, key}` a sit answers.
#
# ONE LINE IS ONE BATCH. The door holds a request 25 seconds at most,
# so the batching over minutes is here and not in the door. A batch
# prints when it holds N events, or SECONDS after its first event,
# whichever comes first. An urgent event prints the batch at once.
# Each event reads `kind id8 action from->to by actor_name: summary`;
# `by actor_name` is there when the door's line carries the name.
#
# THE CURSOR IS A FILE, so a restart resumes. It is `.inbox-feed.after`
# beside this script, or the path in WAYMARK_INBOX_CURSOR. It names the
# last event that was PRINTED, and never an event that waits in a
# batch: a feed killed with a batch in hand reads that batch again.
#
# NO CURSOR BEGINS AT NOW. A sitting can be open for days, so a feed
# with no cursor asks the door for `after=now` one time and tails from
# the event that answer names. A seat that wants the history writes the
# event to read from (0 for the whole log) in the cursor file first.
#
# ANY STATUS BUT 200 ENDS IT, with one line that says the key lapsed.
# A request that got no answer at all (a deploy, a dropped network) is
# not a status: the feed waits five seconds and asks again.

set -u

usage() {
  echo "usage: inbox-feed.sh <inbox url> <inbox key> [--batch N] [--every SECONDS] [--urgent kind[:action],...]" >&2
  exit 2
}

[ $# -ge 2 ] || usage
URL=$1
KEY=$2
shift 2

BATCH=10
EVERY=60
URGENT='held_call,approval_request,change:fail,change:stall'
while [ $# -gt 0 ]; do
  [ $# -ge 2 ] || usage
  case "$1" in
    --batch) BATCH=$2 ;;
    --every) EVERY=$2 ;;
    --urgent) URGENT=$2 ;;
    *) usage ;;
  esac
  shift 2
done
case "$BATCH" in '' | *[!0-9]* | 0) usage ;; esac
case "$EVERY" in '' | *[!0-9]*) usage ;; esac

CURSOR=${WAYMARK_INBOX_CURSOR:-"$(cd "$(dirname "$0")" && pwd)/.inbox-feed.after"}

# `after` is where the next request reads from. `mark` is what the
# cursor file holds: the last event printed.
after=''
if [ -r "$CURSOR" ]; then
  after=$(tr -cd '0-9' <"$CURSOR")
fi
mark=$after
saved=$after

save() {
  [ "$mark" != "$saved" ] || return 0
  printf '%s\n' "$mark" >"$CURSOR.tmp" && mv "$CURSOR.tmp" "$CURSOR" && saved=$mark
}

# One event a line: `!` for an urgent one or `.`, the event's log id
# (`-` when it has none), and the event as the batch prints it.
FORMAT='
import json, re, sys
urgent = {u.strip() for u in sys.argv[1].split(",") if u.strip()}
for raw in sys.stdin:
    try:
        e = json.loads(raw)
    except ValueError:
        continue
    if not isinstance(e, dict):
        continue
    kind = str(e.get("kind") or "?")
    action = str(e.get("action") or "?")
    text = "%s %s %s %s->%s" % (kind, str(e.get("id") or "")[:8], action,
                                e.get("from") or "-", e.get("to") or "-")
    if e.get("actor_name"):
        text += " by %s" % e["actor_name"]
    text += ": %s" % (e.get("summary") or "")
    text = re.sub(r"\s+", " ", text).strip()
    event = e.get("event")
    flag = "!" if kind in urgent or kind + ":" + action in urgent else "."
    print("%s\t%s\t%s" % (flag, event if isinstance(event, int) else "-", text))
'

batch=()
last=''
first=0

flush() {
  [ ${#batch[@]} -gt 0 ] || return 0
  local line="${#batch[@]} events" sep=': ' e
  [ ${#batch[@]} -gt 1 ] || line='1 event'
  for e in "${batch[@]}"; do
    line+="$sep$e"
    sep=' | '
  done
  printf '%s\n' "$line"
  batch=()
  if [ -n "$last" ]; then
    mark=$last
    save
  fi
}

HEAD=$(mktemp)
BODY=$(mktemp)
trap 'rm -f "$HEAD" "$BODY"' EXIT

ask='?'
case "$URL" in *\?*) ask='&' ;; esac

lapsed() {
  echo "The inbox key lapsed (the door answered $1): sit again, with the same session, and start this feed with the inbox the sit answers."
  exit 1
}

# The door's cursor, from the headers of its last answer.
newest() {
  tr -d '\r' <"$HEAD" | awk 'tolower($1) == "waymark-inbox-after:" { v = $2 } END { print v }'
}

# No cursor: begin at the present. A door that names no cursor is left
# to begin where it likes.
while [ -z "$after" ]; do
  status=$(curl -sS -o "$BODY" -D "$HEAD" -w '%{http_code}' --max-time 15 \
    -H "Waymark-Inbox-Key: $KEY" "$URL${ask}after=now" 2>/dev/null) || status=000
  if [ "$status" = 000 ]; then
    sleep 5
    continue
  fi
  [ "$status" = 200 ] || lapsed "$status"
  next=$(newest)
  case "$next" in '' | *[!0-9]*) break ;; esac
  after=$next
  mark=$after
  save
done

while :; do
  wait=25
  if [ ${#batch[@]} -gt 0 ]; then
    left=$((first + EVERY - SECONDS))
    if [ "$left" -le 0 ]; then
      flush
      continue
    fi
    if [ "$left" -lt 25 ]; then wait=$left; fi
  fi

  status=$(curl -sS -o "$BODY" -D "$HEAD" -w '%{http_code}' --max-time $((wait + 15)) \
    -H "Waymark-Inbox-Key: $KEY" "$URL${ask}wait=$wait${after:+&after=$after}" 2>/dev/null) || status=000
  if [ "$status" = 000 ]; then
    sleep 5
    continue
  fi
  if [ "$status" != 200 ]; then
    flush
    lapsed "$status"
  fi

  while IFS=$'\t' read -r flag event text; do
    if [ ${#batch[@]} -eq 0 ]; then first=$SECONDS; fi
    batch+=("$text")
    if [ "$event" != - ]; then last=$event; fi
    if [ "$flag" = '!' ] || [ ${#batch[@]} -ge "$BATCH" ]; then flush; fi
  done < <(python3 -c "$FORMAT" "$URGENT" <"$BODY")

  next=$(newest)
  case "$next" in '' | *[!0-9]*) ;; *) after=$next ;; esac
  if [ ${#batch[@]} -eq 0 ]; then
    mark=$after
    save
  fi
done
