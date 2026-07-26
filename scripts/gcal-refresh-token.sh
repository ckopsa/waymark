#!/usr/bin/env bash
# The household's Google refresh token (waymark-6k5.1, widened by
# waymark-78x): a one-time consent flow that mints the ONE long-lived
# credential the Google-backed sources store. Access tokens are derived
# from it at runtime and never stored (calendar10.oauth, which is a
# general refresh-token grant despite its namespace); this is the only
# secret that rests.
#
#   scripts/gcal-refresh-token.sh [CLIENT_ID CLIENT_SECRET] [SCOPE …]
#
# Omit the client pair and the stored one is used (secrets.local.json,
# or $INFRA_SECRETS) — the common case, and the one that cannot be
# fat-fingered into Google's 401 invalid_client.
#
# With no SCOPE the calendar alone is requested, which is what this
# script meant before it learned the argument. Naming several mints ONE
# token covering all of them — Google's consent screen asks once, and a
# single credential is a single rotation point:
#
#   scripts/gcal-refresh-token.sh \
#     https://www.googleapis.com/auth/calendar \
#     https://www.googleapis.com/auth/tasks
#
# Before running, create the OAuth client once in Google Cloud Console:
#   APIs & Services → Enable the API for EVERY scope you request
#                     ("Google Calendar API", "Tasks API", …)
#   → Credentials → Create credentials → OAuth client ID
#   → Application type: "Desktop app"
# Desktop clients are the right shape here: the loopback redirect below
# is what they authorize, and there is no browser-facing origin to
# register. Copy the client id and secret it hands back.
#
# Enabling the API is a SEPARATE act from requesting its scope, and
# skipping it fails late: consent succeeds, the token mints, and the
# first real call answers 403 "… API has not been used in project …".
#
# The scopes are read/write on purpose — a read-only scope is what the
# retired iCal feed already had, and the whole point is a calendar we
# can push to (and, since waymark-78x, tasks we can complete).
#
# Nothing is written to disk. The token is printed once; put it where
# the other house secrets live (the exact command is printed at the
# end) and it does not need minting again unless it is revoked, goes
# six months unused, or the account's password changes.
set -euo pipefail

PORT="${PORT:-8765}"
REDIRECT="http://127.0.0.1:${PORT}"
SECRETS="${INFRA_SECRETS:-$HOME/dev/home-infrastructure/terraform/secrets.local.json}"

usage() {
  echo "usage: $(basename "$0") [CLIENT_ID CLIENT_SECRET] [SCOPE …]" >&2
  echo "       (create a 'Desktop app' OAuth client in Google Cloud Console)" >&2
  echo "       omit the client pair to use the one in $SECRETS" >&2
  echo "       default scope: the calendar alone" >&2
  exit 2
}

# The client pair: explicit arguments win, the stored pair is the
# fallback. A leading https:// disambiguates "these are scopes, not a
# client" — a client id is never a URL. Typing the pair by hand is how
# you get Google's 401 invalid_client, which names nothing useful and
# reads like a scope problem.
if [ $# -ge 2 ] && [[ "$1" != https://* ]]; then
  client_id="$1"
  client_secret="$2"
  shift 2
elif [ $# -eq 1 ] && [[ "$1" != https://* ]]; then
  echo "a client id with no secret — pass both, or neither" >&2
  usage
else
  [ -r "$SECRETS" ] || { echo "no client given and $SECRETS is unreadable" >&2; usage; }
  client_id="$(jq -r '.calendar10_google_client_id // empty' "$SECRETS")"
  client_secret="$(jq -r '.calendar10_google_client_secret // empty' "$SECRETS")"
  [ -n "$client_id" ] && [ -n "$client_secret" ] || {
    echo "$SECRETS holds no calendar10_google_client_id/_secret pair" >&2
    usage
  }
  echo "using the stored OAuth client (…${client_id: -28})"
fi
# space-separated is Google's spelling for "one token, several scopes";
# urlencode below turns the spaces into %20
SCOPE="${*:-https://www.googleapis.com/auth/calendar}"

echo "requesting scope(s):"
for s in $SCOPE; do echo "  $s"; done
echo

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
echo "every scope above, so it belongs in secrets.local.json and the"
echo "nomad var, never in the repo:"
echo
echo "  jq '.calendar10_google_client_id = \"$client_id\""
echo "    | .calendar10_google_client_secret = \"$client_secret\""
echo "    | .calendar10_google_refresh_token = \"$refresh_token\"' \\"
echo "    $SECRETS > /tmp/s.json && mv /tmp/s.json $SECRETS"
echo
echo "The key names still say calendar10 because that is what terraform"
echo "declares (nomad_variables.tf) and what the job templates. A token"
echo "covering more than the calendar is a MISNOMER, not a second"
echo "secret: point the other sources' env at these same keys rather"
echo "than storing the same bearer twice."
echo
echo "Then prove the transport end to end:"
echo
echo "  CALENDAR10_GOOGLE_CLIENT_ID=$client_id \\"
echo "  CALENDAR10_GOOGLE_CLIENT_SECRET=… \\"
echo "  CALENDAR10_GOOGLE_REFRESH_TOKEN=… \\"
echo "  WRITE=1 make probe-calendar"
