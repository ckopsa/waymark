#!/usr/bin/env bash
# The connector door's ceremony, step 1 (docs/spec-connector-door.md
# § The ceremony; waymark-kkx.3): ONE confidential Keycloak client per
# connector tool. The tool logs the PERSON in through it; the engine,
# seeing the client in WAYMARK10_OIDC_DELEGATE_CLIENTS as the token's
# azp, resolves the token to a delegate — an agent acting for that
# person, wearing the grant off its own member row.
#
#   scripts/connector-client.sh new claude                      # client waymark10-connector-claude
#   scripts/connector-client.sh new gemini <redirect-uri> Gemini # a second tool, its own client
#   scripts/connector-client.sh show claude                     # id + secret, for the tool's form
#   scripts/connector-client.sh list
#
# ONE CLIENT PER TOOL, never shared: the delegate's id is
# <client>:<sub>, so two tools on one client would be one agent
# wearing one grant, and the roster could not say which tool acted.
# The redirect URI is the tool's own callback, exactly — claude.ai's
# is the default; Gemini's is the oauth-redirect.googleusercontent.com
# address its connector form shows in the failed login's URL.
#
# Shape of each client, and why it differs from agent-client.sh's:
# standard (authorization-code) flow ON and client-credentials OFF —
# there is a person at the other end, and no service account may act
# in their name; PKCE S256; consent REQUIRED, so the login page states
# what Claude is being given; the redirect URI is exactly the
# connector's callback; the engine's audience scope is attached as a
# DEFAULT scope, because the connector cannot be relied on to request
# it and a token without the audience is refused at the door; and the
# client's own session idle/max is a week — how often the owner logs
# in again inside claude.ai. The secret lands in secrets.local.json
# under .waymark10_connector_clients.<name>; admin credentials come
# from the same file (keycloak_admin_username/password).
set -euo pipefail

INFRA_SECRETS="${INFRA_SECRETS:-$HOME/dev/home-infrastructure/terraform/secrets.local.json}"
KC="https://keycloak.kopsa.info"
REALM="domestic-realm"
SCOPE="waymark-workqueue10"
CLAUDE_CALLBACK="https://claude.ai/api/mcp/auth_callback"
WEEK=604800

admin_token() {
  curl -sS --max-time 15 -X POST "$KC/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d username="$(jq -re .keycloak_admin_username "$INFRA_SECRETS")" \
    --data-urlencode password="$(jq -re .keycloak_admin_password "$INFRA_SECRETS")" \
    | jq -re .access_token
}

client_uuid() {  # $1 token, $2 clientId
  curl -sS -H "Authorization: Bearer $1" "$KC/admin/realms/$REALM/clients?clientId=$2" | jq -re '.[0].id'
}

case "${1:-}" in
  new)
    NAME="${2:?usage: connector-client.sh new <name> [redirect-uri] [display]}"
    [[ "$NAME" =~ ^[a-z][a-z0-9-]*$ ]] || { echo "name must be kebab-case" >&2; exit 2; }
    CALLBACK="${3:-$CLAUDE_CALLBACK}"
    DISPLAY_NAME="${4:-Claude}"
    [[ "$CALLBACK" =~ ^https:// ]] || { echo "redirect uri must be https" >&2; exit 2; }
    CID="waymark10-connector-$NAME"
    TOK=$(admin_token)
    curl -sS -o /dev/null -w "client $CID: %{http_code}\n" -X POST "$KC/admin/realms/$REALM/clients" \
      -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" -d "{
      \"clientId\": \"$CID\", \"protocol\": \"openid-connect\",
      \"name\": \"$DISPLAY_NAME (connector)\",
      \"description\": \"connector door (spec-connector-door): $NAME logs the person in; the engine resolves the token to a delegate\",
      \"publicClient\": false, \"standardFlowEnabled\": true,
      \"implicitFlowEnabled\": false, \"directAccessGrantsEnabled\": false,
      \"serviceAccountsEnabled\": false, \"consentRequired\": true,
      \"redirectUris\": [\"$CALLBACK\"], \"webOrigins\": [],
      \"attributes\": {\"pkce.code.challenge.method\": \"S256\",
                       \"use.refresh.tokens\": \"true\",
                       \"client.session.idle.timeout\": \"$WEEK\",
                       \"client.session.max.lifespan\": \"$WEEK\"}}"
    U=$(client_uuid "$TOK" "$CID")
    SCID=$(curl -sS -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/client-scopes" \
      | jq -re --arg s "$SCOPE" '.[] | select(.name==$s) | .id')
    curl -sS -o /dev/null -w "scope $SCOPE attached as DEFAULT: %{http_code}\n" -X PUT \
      -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/clients/$U/default-client-scopes/$SCID"
    SECRET=$(curl -sS -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/clients/$U/client-secret" | jq -re .value)
    TMP=$(mktemp)
    jq --arg n "$NAME" --arg s "$SECRET" '
      .waymark10_connector_clients = ((.waymark10_connector_clients // {})
                                      | if type == "string" then fromjson else . end)
      | .waymark10_connector_clients[$n] = $s' "$INFRA_SECRETS" > "$TMP"
    chmod --reference="$INFRA_SECRETS" "$TMP"
    mv "$TMP" "$INFRA_SECRETS"
    echo "secret stored: .waymark10_connector_clients.$NAME"
    echo "engine env:    WAYMARK10_OIDC_DELEGATE_CLIENTS=…,$CID=$DISPLAY_NAME"
    echo "show with:     scripts/connector-client.sh show $NAME"
    ;;
  show)
    NAME="${2:?usage: connector-client.sh show <name>}"
    echo "connector URL: https://work.kopsa.info/api/-/mcp"
    echo "client id:     waymark10-connector-$NAME"
    echo "client secret: $(jq -re --arg n "$NAME" '.waymark10_connector_clients[$n]' "$INFRA_SECRETS")"
    ;;
  list)
    TOK=$(admin_token)
    curl -sS -H "Authorization: Bearer $TOK" "$KC/admin/realms/$REALM/clients?max=200" \
      | jq -r '.[] | select(.clientId | startswith("waymark10-connector")) | .clientId'
    ;;
  *)
    echo "usage: $(basename "$0") new <name> | show <name> | list" >&2; exit 2 ;;
esac
