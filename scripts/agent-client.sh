#!/usr/bin/env bash
# Per-agent identity minting (the waymark-b4y design, distinct-identity
# form): each agent gets its OWN Keycloak client — its own sub, so the
# engine's invite->bind machinery sees a genuinely new principal and
# member rows stay per-agent. The client is the durable identity; the
# 1h tokens agent-token.sh mints from it are the sessions.
#
#   scripts/agent-client.sh new claude     # client waymark10-agent-claude
#   scripts/agent-client.sh list
#
# Shape of each client: client-credentials only, 1h access tokens,
# actor_type=agent stamped, the one engine scope (waymark-workqueue10)
# attached as optional — a mint still names its scope or opens
# nothing. The secret lands in secrets.local.json under
# .waymark10_agent_clients.<name>; admin credentials come from the
# same file (keycloak_admin_username/password).
set -euo pipefail

INFRA_SECRETS="${INFRA_SECRETS:-$HOME/dev/home-infrastructure/terraform/secrets.local.json}"
KC="https://keycloak.kopsa.info"
REALM="domestic-realm"

admin_token() {
  curl -sS --max-time 15 -X POST "$KC/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d username="$(jq -re .keycloak_admin_username "$INFRA_SECRETS")" \
    --data-urlencode password="$(jq -re .keycloak_admin_password "$INFRA_SECRETS")" \
    | jq -re .access_token
}

case "${1:-}" in
  new)
    NAME="${2:?usage: agent-client.sh new <name>}"
    [[ "$NAME" =~ ^[a-z][a-z0-9-]*$ ]] || { echo "name must be kebab-case" >&2; exit 2; }
    CID="waymark10-agent-$NAME"
    TOK=$(admin_token)
    curl -sS -o /dev/null -w "client $CID: %{http_code}\n" -X POST "$KC/admin/realms/$REALM/clients" \
      -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" -d "{
      \"clientId\": \"$CID\", \"protocol\": \"openid-connect\",
      \"description\": \"per-agent capability minting: $NAME\",
      \"publicClient\": false, \"standardFlowEnabled\": false,
      \"directAccessGrantsEnabled\": false, \"serviceAccountsEnabled\": true,
      \"attributes\": {\"access.token.lifespan\": \"3600\"},
      \"protocolMappers\": [{
        \"name\": \"actor-type-agent\", \"protocol\": \"openid-connect\",
        \"protocolMapper\": \"oidc-hardcoded-claim-mapper\",
        \"config\": {\"claim.name\": \"actor_type\", \"claim.value\": \"agent\",
                     \"jsonType.label\": \"String\", \"access.token.claim\": \"true\",
                     \"id.token.claim\": \"false\", \"userinfo.token.claim\": \"false\"}}]}"
    U=$(curl -sS -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/clients?clientId=$CID" | jq -re '.[0].id')
    SCID=$(curl -sS -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/client-scopes" \
      | jq -re '.[] | select(.name=="waymark-workqueue10") | .id')
    curl -sS -o /dev/null -w "scope attached: %{http_code}\n" -X PUT \
      -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/clients/$U/optional-client-scopes/$SCID"
    SA=$(curl -sS -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/clients/$U/service-account-user" | jq -re .id)
    curl -sS -o /dev/null -w "service account named: %{http_code}\n" -X PUT "$KC/admin/realms/$REALM/users/$SA" \
      -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
      -d "{\"firstName\": \"$NAME\", \"lastName\": \"agent\"}"
    SECRET=$(curl -sS -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/clients/$U/client-secret" | jq -re .value)
    # The map has been found double-encoded as a JSON string once; decode
    # that shape rather than fail on it. Not an && chain: set -e must see
    # a jq failure, or "secret stored" lies.
    TMP=$(mktemp)
    jq --arg n "$NAME" --arg s "$SECRET" '
      .waymark10_agent_clients = ((.waymark10_agent_clients // {})
                                  | if type == "string" then fromjson else . end)
      | .waymark10_agent_clients[$n] = $s' "$INFRA_SECRETS" > "$TMP"
    chmod --reference="$INFRA_SECRETS" "$TMP"
    mv "$TMP" "$INFRA_SECRETS"
    echo "secret stored: .waymark10_agent_clients.$NAME"
    echo "mint with:     scripts/agent-token.sh --agent $NAME work"
    ;;
  list)
    TOK=$(admin_token)
    curl -sS -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/clients?max=100" \
      | jq -r '.[] | select(.clientId | startswith("waymark10-agent")) | .clientId'
    ;;
  *)
    echo "usage: $(basename "$0") new <name> | list" >&2; exit 2 ;;
esac
