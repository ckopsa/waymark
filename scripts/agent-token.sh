#!/usr/bin/env bash
# The agent's signed-url mint (waymark-b4y): a short-lived, engine-scoped
# bearer off the domestic-realm's waymark10-agent client. The token IS
# the capability — exp its lifetime (1h, the client's access-token
# lifespan), aud its scope (only the engines named here), actor_type
# "agent" stamped by the client's hardcoded-claim mapper. No standing
# agent credential rides any config; a session that wants access mints
# one and lets it die.
#
#   scripts/agent-token.sh work                     # the shared agent
#   scripts/agent-token.sh --agent claude work      # a DISTINCT agent
#
#   curl -H "Authorization: Bearer $(scripts/agent-token.sh work)" \
#     https://work.kopsa.info/api/.well-known/waymark
#
# Since the consolidation (waymark-bwu, 2026-07-24) there is ONE
# engine: rod/meals are 301s into work.kopsa.info, so every name
# mints the same scope — the aliases survive for muscle memory.
# --agent NAME mints from the per-agent client waymark10-agent-NAME
# (scripts/agent-client.sh new NAME creates one): its own sub, so the
# engine's invite->bind machinery sees a distinct principal and
# member rows stay per-agent. Secrets live in the infra repo's
# secrets.local.json (.waymark10_agent_client_secret for the shared
# client, .waymark10_agent_clients.NAME per agent) — INFRA_SECRETS
# overrides the path. A token minted with NO scope carries no engine
# audience and the engine refuses it: naming the scope is the point.
set -euo pipefail

INFRA_SECRETS="${INFRA_SECRETS:-$HOME/dev/home-infrastructure/terraform/secrets.local.json}"
ISSUER="https://keycloak.kopsa.info/realms/domestic-realm"

[ $# -ge 1 ] || { echo "usage: $(basename "$0") [--agent NAME] work   (rod|meals accepted as aliases)" >&2; exit 2; }

agent=""
scope=""
while [ $# -gt 0 ]; do
  case "$1" in
    --agent) agent="${2:?--agent needs a name}"; shift 2 ;;
    work|workqueue10|rod|choreplan10|meals|mealplan10)
      scope="waymark-workqueue10"; shift ;;
    *) echo "unknown argument: $1 (want [--agent NAME] work; rod|meals alias to it)" >&2; exit 2 ;;
  esac
done
[ -n "$scope" ] || { echo "name an engine: work (rod|meals alias to it)" >&2; exit 2; }

if [ -n "$agent" ]; then
  client_id="waymark10-agent-$agent"
  secret=$(jq -re --arg n "$agent" '.waymark10_agent_clients[$n]' "$INFRA_SECRETS") \
    || { echo "no .waymark10_agent_clients.$agent in $INFRA_SECRETS — scripts/agent-client.sh new $agent" >&2; exit 1; }
else
  client_id="waymark10-agent"
  secret=$(jq -re .waymark10_agent_client_secret "$INFRA_SECRETS") \
    || { echo "no waymark10_agent_client_secret in $INFRA_SECRETS" >&2; exit 1; }
fi

resp=$(curl -sS --max-time 15 -X POST "$ISSUER/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id="$client_id" \
  --data-urlencode client_secret="$secret" \
  --data-urlencode scope="${scope# }")

jq -re .access_token <<<"$resp" \
  || { echo "mint refused: $(jq -r '.error_description // .error // .' <<<"$resp")" >&2; exit 1; }
