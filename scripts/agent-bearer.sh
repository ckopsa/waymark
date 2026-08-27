#!/usr/bin/env bash
# The env-var mint for headless runners (Jules and kin): a fresh
# 1-hour engine-scoped bearer from the per-agent Keycloak client,
# with the credentials arriving as ENVIRONMENT VARIABLES rather than
# the infra repo's secrets file (agent-token.sh's path — that one is
# for laptops that have the file; this one is for VMs that have only
# an env). The token IS the credential: mint at run start, use it in
# `Authorization: Bearer <token>`, let it die. No session cookie, no
# re-entry token, nothing stored.
#
#   WAYMARK_KC_CLIENT_ID      waymark10-agent-<name> (agent-client.sh new <name>)
#   WAYMARK_KC_CLIENT_SECRET  its secret
#   WAYMARK_KC_ISSUER         optional; the domestic realm by default
#
#   TOKEN="$(scripts/agent-bearer.sh)"
#   curl -H "Authorization: Bearer $TOKEN" -H "X-Waymark-Grant: $WAYMARK_GRANT_ID" ...
set -euo pipefail

ISSUER="${WAYMARK_KC_ISSUER:-https://keycloak.kopsa.info/realms/domestic-realm}"
: "${WAYMARK_KC_CLIENT_ID:?set WAYMARK_KC_CLIENT_ID (waymark10-agent-<name>)}"
: "${WAYMARK_KC_CLIENT_SECRET:?set WAYMARK_KC_CLIENT_SECRET}"

resp=$(curl -sS --max-time 15 -X POST "$ISSUER/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id="$WAYMARK_KC_CLIENT_ID" \
  --data-urlencode client_secret="$WAYMARK_KC_CLIENT_SECRET" \
  --data-urlencode scope=waymark-workqueue10)

jq -re .access_token <<<"$resp" \
  || { echo "mint refused: $(jq -r '.error_description // .error // .' <<<"$resp")" >&2; exit 1; }
