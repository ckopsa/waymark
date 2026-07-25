#!/usr/bin/env bash
# The family calendar's refresh token (waymark-6k5.1): a one-time
# consent flow that mints the ONE long-lived credential calendar10
# stores. Access tokens are derived from it at runtime and never
# stored (calendar10.oauth); this is the only secret that rests.
#
#   scripts/gcal-refresh-token.sh CLIENT_ID CLIENT_SECRET
#
# Before running, create the OAuth client once in Google Cloud Console:
#   APIs & Services → Enable "Google Calendar API"
#   → Credentials → Create credentials → OAuth client ID
#   → Application type: "Desktop app"
# Desktop clients are the right shape here: the loopback redirect below
# is what they authorize, and there is no browser-facing origin to
# register. Copy the client id and secret it hands back.
#
# The scope is read/write on purpose — a read-only scope is what the
# retired iCal feed already had, and the whole point of this stage is a
# calendar we can push to.
#
# Nothing is written to disk. The token is printed once; put it where
# the other house secrets live (the exact command is printed at the
# end) and it does not need minting again unless it is revoked, goes
# six months unused, or the account's password changes.
set -euo pipefail

SCOPE="https://www.googleapis.com/auth/calendar"
PORT="${PORT:-8765}"
REDIRECT="http://127.0.0.1:${PORT}"
SECRETS="${INFRA_SECRETS:-$HOME/dev/home-infrastructure/terraform/secrets.local.json}"

[ $# -eq 2 ] || {
  echo "usage: $(basename "$0") CLIENT_ID CLIENT_SECRET" >&2
  echo "       (create a 'Desktop app' OAuth client in Google Cloud Console)" >&2
  exit 2
}
client_id="$1"
client_secret="$2"

urlencode() {
  local s="$1" out="" c
  for ((i = 0; i < ${#s}; i++)); do
    c="${s:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_-]) out+="$c" ;;
      *) out+=$(printf '%%%02X' "'$c") ;;
    esac
  done
  printf '%s' "$out"
}

consent_url="https://accounts.google.com/o/oauth2/v2/auth"
consent_url+="?client_id=$(urlencode "$client_id")"
consent_url+="&redirect_uri=$(urlencode "$REDIRECT")"
consent_url+="&response_type=code"
consent_url+="&scope=$(urlencode "$SCOPE")"
# access_type=offline is what makes Google issue a refresh token at
# all; prompt=consent forces a NEW one even if this client was already
# approved (re-running otherwise returns an access token and no
# refresh token, which is the classic silent failure here).
consent_url+="&access_type=offline&prompt=consent"

echo "Open this in a browser signed in as the family calendar's owner:"
echo
echo "  $consent_url"
echo

code=""
if command -v nc >/dev/null 2>&1; then
  echo "Waiting for the redirect on ${REDIRECT} (Ctrl-C to paste manually) …"
  response=$'HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<h1>waymark: got it</h1><p>You can close this tab.</p>\n'
  # OpenBSD nc wants "-l host port"; GNU nc wants "-l -p port". Try
  # both rather than guessing the distro's netcat.
  request="$(printf '%s' "$response" | nc -l 127.0.0.1 "$PORT" 2>/dev/null | head -1 || true)"
  if [ -z "$request" ]; then
    request="$(printf '%s' "$response" | nc -l -p "$PORT" 2>/dev/null | head -1 || true)"
  fi
  # "GET /?code=4/0AX…&scope=… HTTP/1.1"
  if [[ "$request" =~ code=([^\&\ ]+) ]]; then
    code="${BASH_REMATCH[1]}"
    echo "Captured the code from the redirect."
  fi
fi

if [ -z "$code" ]; then
  echo
  echo "Paste the FULL redirect URL from the browser's address bar"
  echo "(it will look like ${REDIRECT}/?code=4/0AX…&scope=…):"
  read -r pasted
  if [[ "$pasted" =~ code=([^\&\ ]+) ]]; then
    code="${BASH_REMATCH[1]}"
  else
    echo "no ?code= in that URL — nothing to exchange." >&2
    exit 1
  fi
fi

# the authorization code is single-use and short-lived; exchanging it
# is the only thing left
resp="$(curl -sS -X POST https://oauth2.googleapis.com/token \
  -d "code=${code}" \
  -d "client_id=${client_id}" \
  -d "client_secret=${client_secret}" \
  -d "redirect_uri=${REDIRECT}" \
  -d "grant_type=authorization_code")"

refresh_token="$(printf '%s' "$resp" | jq -r '.refresh_token // empty')"
if [ -z "$refresh_token" ]; then
  echo "google returned no refresh_token:" >&2
  printf '%s\n' "$resp" >&2
  echo >&2
  echo "If it returned only an access_token, this client was already" >&2
  echo "approved — the prompt=consent above should have forced a new" >&2
  echo "refresh token, so check that the consent screen actually asked." >&2
  exit 1
fi

echo
echo "refresh token:"
echo
echo "  $refresh_token"
echo
echo "Store it with the other house secrets — it is a standing bearer for"
echo "the family calendar, so it belongs in secrets.local.json and the"
echo "nomad var, never in the repo:"
echo
echo "  jq '.calendar10_google_client_id = \"$client_id\""
echo "    | .calendar10_google_client_secret = \"$client_secret\""
echo "    | .calendar10_google_refresh_token = \"$refresh_token\"' \\"
echo "    $SECRETS > /tmp/s.json && mv /tmp/s.json $SECRETS"
echo
echo "Then prove the transport end to end:"
echo
echo "  CALENDAR10_GOOGLE_CLIENT_ID=$client_id \\"
echo "  CALENDAR10_GOOGLE_CLIENT_SECRET=… \\"
echo "  CALENDAR10_GOOGLE_REFRESH_TOKEN=… \\"
echo "  WRITE=1 make probe-calendar"
