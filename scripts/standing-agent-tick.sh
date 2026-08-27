#!/usr/bin/env bash
# The standing agent's pulse (waymark-53u): one tick keeps a leashed
# agent alive without a human hand, inside the leash philosophy —
# every credential still expires, every renewal is audited, and the
# human's tap moves from "re-invite the agent daily" to "approve the
# extend-ask it filed".
#
# What one tick does, in order:
#   1. RENEW the session: POST /auth/agent/renew with the live cookie.
#      The engine slides the session and, past half-life, rotates the
#      standing re-entry credential in the same response.
#   2. On a lapsed session (401), COME HOME: POST /auth/agent with the
#      stored re-entry token in the JSON body. One shot — the spend
#      nulls it and the response carries the rotated replacement.
#   3. On both failing, STOP and say so: the way back in is a human's
#      fresh invitation (or a recovery-admin's offer_reentry). This
#      script never knocks on its own.
#   4. Watch the GRANT: when its expiry is inside the ask window and
#      no extend-ask of ours is still open, file an ANCHORED ask
#      (grant_id names the grant; approval extends it in place —
#      grants.clj's negotiation machine). The human approves from the
#      feed; nothing here widens scope.
#   5. Rewrite the MCP config's Cookie header so the next sitting
#      rides the fresh session.
#
# State rides one 0600 JSON file (WAYMARK_AGENT_STATE), seeded once by
# hand from a bind response:
#   {"base_url":      "https://work.kopsa.info",
#    "cookie_name":   "waymark_session",
#    "session_token": "…",
#    "reentry_token": "…",           # optional until first rotation
#    "grant_id":      "…",           # optional; enables step 4
#    "mcp_config":    "~/.claude.json",   # optional; enables step 5
#    "mcp_server":    "waymark",
#    "ask_window_s":  43200,         # file the extend-ask this close to expiry
#    "extend_s":      86400,         # ask for this much more leash
#    "open_ask_id":   "…"}           # script-managed
#
# Run it from cron or a systemd timer, well inside the session TTL:
#   */30 * * * * WAYMARK_AGENT_STATE=~/.waymark-agent.json standing-agent-tick.sh
set -euo pipefail

STATE="${WAYMARK_AGENT_STATE:-$HOME/.waymark-agent.json}"
[ -f "$STATE" ] || { echo "no state file at $STATE — seed it from a bind response" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

sget() { jq -r ".$1 // empty" "$STATE"; }
sput() { # sput key value — writes state back at 0600
  local tmp; tmp="$(mktemp)"
  jq --arg v "$2" ".$1 = \$v" "$STATE" > "$tmp" && mv "$tmp" "$STATE"
  chmod 600 "$STATE"
}
sdel() {
  local tmp; tmp="$(mktemp)"
  jq "del(.$1)" "$STATE" > "$tmp" && mv "$tmp" "$STATE"
  chmod 600 "$STATE"
}

BASE="$(sget base_url)"; COOKIE_NAME="$(sget cookie_name)"
COOKIE_NAME="${COOKIE_NAME:-waymark_session}"
SESSION="$(sget session_token)"; REENTRY="$(sget reentry_token)"

# ── 1+2: a live session slides; a lapsed one comes home ─────────────
take_session() { # take_session <response-json> — store what the door handed back
  local body="$1"
  sput session_token "$(jq -r '.session.token' <<<"$body")"
  local rt; rt="$(jq -r '.reentry.token // empty' <<<"$body")"
  [ -n "$rt" ] && sput reentry_token "$rt"
}

body="$(curl -sf -X POST "$BASE/auth/agent/renew" \
             -H "Cookie: $COOKIE_NAME=$SESSION" 2>/dev/null)" || body=""
if [ -n "$body" ]; then
  take_session "$body"
elif [ -n "$REENTRY" ]; then
  body="$(curl -sf -X POST "$BASE/auth/agent" \
               -H "Content-Type: application/json" \
               -d "{\"invite\": \"$REENTRY\"}" 2>/dev/null)" || body=""
  if [ -n "$body" ]; then
    take_session "$body"
  else
    # the spent-or-dead token must not be retried into the pacing wall
    sdel reentry_token
    echo "session lapsed and the re-entry token did not spend — a human must re-invite (knock) or offer_reentry" >&2
    exit 1
  fi
else
  echo "session lapsed and no re-entry token stands — a human must re-invite" >&2
  exit 1
fi
SESSION="$(sget session_token)"

# ── 4: the anchored extend-ask, filed before the leash runs out ─────
GRANT="$(sget grant_id)"
if [ -n "$GRANT" ]; then
  ASK_WINDOW="$(sget ask_window_s)"; ASK_WINDOW="${ASK_WINDOW:-43200}"
  EXTEND="$(sget extend_s)"; EXTEND="${EXTEND:-86400}"
  grant_row="$(curl -sf "$BASE/api/grants/$GRANT" \
                    -H "Cookie: $COOKIE_NAME=$SESSION" \
                    -H "Accept: application/json" \
                    -H "X-Waymark-Grant: $GRANT" 2>/dev/null)" || grant_row=""
  exp="$(jq -r '.data.expires_at // .expires_at // empty' <<<"$grant_row")"
  if [ -n "$exp" ]; then
    exp_s="$(date -u -d "$exp" +%s 2>/dev/null || date -u -j -f "%Y-%m-%dT%H:%M:%SZ" "$exp" +%s)"
    now_s="$(date -u +%s)"
    if [ $((exp_s - now_s)) -lt "$ASK_WINDOW" ]; then
      open_ask="$(sget open_ask_id)"
      ask_state=""
      if [ -n "$open_ask" ]; then
        ask_state="$(curl -sf "$BASE/api/approval_requests/$open_ask" \
                          -H "Cookie: $COOKIE_NAME=$SESSION" \
                          -H "Accept: application/json" 2>/dev/null \
                     | jq -r '.state // empty')" || ask_state=""
      fi
      if [ "$ask_state" != "offered" ]; then
        scope="$(jq -c '.data.scope // .scope' <<<"$grant_row")"
        ask_exp="$(date -u -d "@$((now_s + EXTEND))" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
                   || date -u -r $((now_s + EXTEND)) +%Y-%m-%dT%H:%M:%SZ)"
        hours=$((EXTEND / 3600))
        ask="$(curl -sf -X POST "$BASE/api/approval_requests" \
                    -H "Cookie: $COOKIE_NAME=$SESSION" \
                    -H "Content-Type: application/json" \
                    -d "{\"grant_id\": \"$GRANT\",
                         \"task\": \"Keep my standing leash: the same scope, another $hours hours.\",
                         \"scope\": $scope,
                         \"expires_at\": \"$ask_exp\"}" 2>/dev/null)" || ask=""
        ask_id="$(jq -r '.id // empty' <<<"$ask")"
        [ -n "$ask_id" ] && sput open_ask_id "$ask_id" \
          && echo "extend-ask $ask_id filed — awaiting a human verdict"
      fi
    fi
  fi
fi

# ── 5: the MCP config wears the fresh cookie ────────────────────────
MCP="$(sget mcp_config)"
if [ -n "$MCP" ]; then
  MCP="${MCP/#\~/$HOME}"
  SERVER="$(sget mcp_server)"; SERVER="${SERVER:-waymark}"
  if [ -f "$MCP" ]; then
    tmp="$(mktemp)"
    jq --arg s "$SERVER" --arg c "$COOKIE_NAME=$SESSION" \
       '(.mcpServers[$s].headers.Cookie) = $c' "$MCP" > "$tmp" && mv "$tmp" "$MCP"
  fi
fi

echo "tick ok: session live$( [ -n "$(sget reentry_token)" ] && echo ", way home standing" )"
