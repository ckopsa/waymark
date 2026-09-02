(ns waymark10.server.routes.mcp
  "The MCP surface's Streamable HTTP transport: one route, one method.

  POST /api/-/mcp carries a JSON-RPC 2.0 message and gets a JSON
  response — the simple half of MCP's Streamable HTTP transport, with
  no SSE stream and no session id, because this server is stateless
  between calls and the streaming half is the spec's recorded punt
  (docs/spec-mcp-surface.md). A client that opens the stream half gets
  405 and a sentence saying so, which is the transport's own way of
  answering 'does this server push?'.

  Everything else about the exchange is waymark10.server.mcp: the six
  tools and the JSON-RPC message layer both live there, so a stdio
  server for a local agent is that namespace with a read-line loop,
  and this file is the only thing it would not reuse.

  AUTH IS THE ROUTER'S, UNCHANGED. This is a route inside the router's
  own assembly, so `wrap-identity` has already run: the bearer (or the
  RP session, or the invite the agent door minted) resolved a
  principal, the members gate has had its say, and the presented
  X-Waymark-Grant became the visibility that every tool below will
  wear. Nothing here authenticates anybody. It only refuses the
  anonymous — the same 401 `/api/-/grant-check` answers, for the same
  reason: an unnamed caller has no grant to project a surface from,
  and a tool list assembled for nobody is the one thing this surface
  must never serve.

  THE DOOR SAYS WHERE TO LOG IN (docs/spec-connector-door.md). That
  anonymous 401 carries the engine's one WWW-Authenticate challenge
  (oidc/challenge), whose resource_metadata parameter points at the
  RFC 9728 protected-resource document this module also serves — at
  the root well-known address and at the path-inserted spelling for
  this door — naming the authorization server a client should log in
  at. The document is the MCP door's own metadata, which is why it
  lives in this module's static routes: a deployment assembled
  without :mcp has no resource to advertise. Without an external base
  URL (:app-url) there is nothing to name, and the address 404s."
  (:require [waymark10.server.gate-proxy :as gate]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- named-principal!
  [eng req]
  (let [principal (router/principal-of req)]
    (when (or (nil? principal) (= (:id principal) (:id t/anonymous)))
      (throw (p/problem :unauthenticated 401 "Unauthenticated"
                        {:detail (str "The MCP surface is for named principals "
                                      "— present a credential. An agent with "
                                      "none knocks at /agentInvite and reads "
                                      "/api/-/welcome.")
                         ;; the challenge, so an OAuth-capable client
                         ;; starts discovery from the refusal itself
                         :waymark10/headers
                         {"WWW-Authenticate" (oidc/challenge (:oidc eng))}})))
    principal))

(defn- protected-resource [eng]
  (fn [_req]
    (if-some [doc (oidc/protected-resource (:oidc eng))]
      (router/json-response 200 doc)
      (throw (p/problem :not-found 404 "Not found"
                        {:detail (str "This engine advertises no OAuth "
                                      "protected resource: it runs without an "
                                      "identity provider, or knows no external "
                                      "base URL (WAYMARK10_OIDC_APP_URL).")})))))

(defn- rpc-post [eng call gate-rpc]
  (fn [req]
    (let [principal (named-principal! eng req)
          session {:principal principal
                   :visibility (router/visibility-of req)}
          body (router/read-body req)]
      (cond
        ;; JSON-RPC batching left MCP with the 2025-06-18 revision, and
        ;; supporting it here would be inventing a compatibility
        ;; surface nobody asked for.
        (vector? body)
        (router/json-response
         200 {:jsonrpc "2.0" :id nil
              :error {:code -32600
                      :message (str "Batched JSON-RPC is not supported — MCP "
                                    "removed it in " mcp/protocol-version
                                    "; send one message per request.")}})

        (not (map? body))
        (router/json-response
         200 {:jsonrpc "2.0" :id nil
              :error {:code -32600
                      :message "Expected one JSON-RPC 2.0 object."}})

        :else
        (if-some [answer (mcp/message eng call gate-rpc session body)]
          (router/json-response 200 answer)
          ;; a notification: nothing to say, and the transport says so
          ;; with a status rather than an empty body pretending to be one
          {:status 202 :headers {} :body ""})))))

(defn- no-stream [eng]
  (fn [req]
    (named-principal! eng req)
    {:status 405
     :headers {"Content-Type" "application/problem+json" "Allow" "POST"}
     :body (:body (p/->response
                   (p/problem :stream-unavailable 405 "Method not allowed"
                              {:detail (str "This engine speaks MCP over POST "
                                            "only. Server-initiated frames are "
                                            "a recorded punt; for live events "
                                            "use the SSE doors at "
                                            "/api/-/events and "
                                            "/api/{plural}/{id}/-/events.")})))}))

(defn routes
  "/api/-/mcp — static, and it has to be: three segments under /api is
  /api/{plural}/{id}'s own shape, so mounted after the plural grammar
  this address would be read as row \"mcp\" of a collection named
  \"-\".

  The door is built ONCE per engine, here, rather than per request:
  it is a reitit router over core's routes, and assembling one per
  tool call would make every agent read pay for the routing table."
  [eng]
  (let [call (mcp/door eng)
        ;; the Gate caller, built ONCE here exactly as routes/gate.clj
        ;; builds its own — gate-proxy/rpc-of over the (:gate eng)
        ;; engine opt (the tests' :rpc seam, the deployment's :url) —
        ;; so the MCP session to Gate is opened lazily and reused
        ;; across requests rather than re-shaken per message.
        gate-rpc (gate/rpc-of eng)]
    {:module :mcp
     :static [["/api/-/mcp" {:post (rpc-post eng call gate-rpc)
                             :get (no-stream eng)}]
              ;; RFC 9728, both spellings a client may derive: the root
              ;; document, and the path-inserted one for this door. Root
              ;; addresses are core-static's precedent (/agentInvite)
              ["/.well-known/oauth-protected-resource"
               {:get (protected-resource eng)}]
              ["/.well-known/oauth-protected-resource/api/-/mcp"
               {:get (protected-resource eng)}]]}))
