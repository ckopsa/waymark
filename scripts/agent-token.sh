#!/usr/bin/env bash
# The agent's signed-url mint (waymark-b4y): a short-lived, engine-scoped
# bearer off the domestic-realm's waymark10-agent client. The token IS
# the capability — exp its lifetime (1h, the client's access-token
# lifespan), aud its scope (only the engines named here), actor_type
# "agent" stamped by the client's hardcoded-claim mapper. No standing
# agent credential rides any config; a session that wants access mints
# one and lets it die.
#
#   scripts/agent-token.sh work           # the one domestic engine
#
#   curl -H "Authorization: Bearer $(scripts/agent-token.sh work)" \
#     https://work.kopsa.info/api/.well-known/waymark
#
# Since the consolidation (waymark-bwu, 2026-07-24) there is ONE
# engine: rod/meals are 301s into work.kopsa.info, so every name
# mints the same scope — the aliases survive for muscle memory.
# The client secret lives in the infra repo's secrets.local.json
# (waymark10_agent_client_secret) — INFRA_SECRETS overrides the path.
# A token minted with NO scope carries no engine audience and the
# engine refuses it: naming the scope is the point.
set -euo pipefail

INFRA_SECRETS="${INFRA_SECRETS:-$HOME/dev/home-infrastructure/terraform/secrets.local.json}"
ISSUER="https://keycloak.kopsa.info/realms/domestic-realm"

[ $# -ge 1 ] || { echo "usage: $(basename "$0") work   (rod|meals accepted as aliases)" >&2; exit 2; }

scope=""
for target in "$@"; do
  case "$target" in
    work|workqueue10|rod|choreplan10|meals|mealplan10)
      scope="waymark-workqueue10" ;;
    *) echo "unknown engine: $target (want work; rod|meals alias to it)" >&2; exit 2 ;;
  esac
done

secret=$(jq -re .waymark10_agent_client_secret "$INFRA_SECRETS") \
  || { echo "no waymark10_agent_client_secret in $INFRA_SECRETS" >&2; exit 1; }

resp=$(curl -sS --max-time 15 -X POST "$ISSUER/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=waymark10-agent \
  --data-urlencode client_secret="$secret" \
  --data-urlencode scope="${scope# }")

jq -re .access_token <<<"$resp" \
  || { echo "mint refused: $(jq -r '.error_description // .error // .' <<<"$resp")" >&2; exit 1; }
