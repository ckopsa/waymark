(ns waymark10.server.routes.mcp
  "The MCP surface's Streamable HTTP transport: one route, two methods.

  POST /api/-/mcp carries a JSON-RPC 2.0 message and gets a JSON
  response — the simple half of MCP's Streamable HTTP transport, with
  no session id, because this server is stateless between calls.

  GET /api/-/mcp with `Accept: text/event-stream` is the other half,
  and it carries exactly ONE kind of server-initiated frame:
  notifications/tools/list_changed, pushed when a grant the caller
  wears (or an ask the caller filed) moves — the transition
  dispatcher's own feed, filtered to the caller's leash. It exists
  because the ask-then-approve loop the surface is built around was
  broken at the last step: an agent filed an approval_request from
  inside a session, the person tapped approve within the minute, the
  grant widened at once — and the connector's tool list stayed the
  seven fixed tools until it reconnected, because the client had been
  told the list never changes. Since waymark-912p the tool list IS
  static — the admitted Gate tools are read through waymark_powers,
  live, so an approval needs no re-list at all — and the notice
  remains as the nudge that the powers document is worth reading
  again. Row events stay the SSE doors' business
  (/api/-/events and /api/{plural}/{id}/-/events); a GET without the
  SSE accept still answers 405, saying where the streams are.

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
  (:require [clojure.string :as str]
            [org.httpkit.server :as http]
            [waymark10.server.events :as events]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.runtime :as runtime]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

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

(defn- no-stream
  "A GET that did not ask for the stream: 405, with the sentence that
  says what the door does push and where row events live."
  [_req]
  {:status 405
   :headers {"Content-Type" "application/problem+json" "Allow" "POST, GET"}
   :body (:body (p/->response
                 (p/problem :stream-unavailable 405 "Method not allowed"
                            {:detail (str "This engine speaks MCP over POST. "
                                          "GET with Accept: text/event-stream "
                                          "opens the notice stream, which "
                                          "carries only "
                                          "notifications/tools/list_changed; "
                                          "for row events use the SSE doors at "
                                          "/api/-/events and "
                                          "/api/{plural}/{id}/-/events.")})))})

(defn- wants-stream? [req]
  (some-> (get-in req [:headers "accept"]) str/lower-case
          (str/includes? "text/event-stream")))

(def ^:private stream-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "X-Accel-Buffering" "no"})

(def ^:private list-changed-frame
  "The one SSE frame this stream ever carries: MCP's spelling is an
  `event: message` whose data is the JSON-RPC message itself."
  (str "event: message\n"
       "data: " (wire/write-json mcp/list-changed) "\n\n"))

(defn- moved-for?
  "Did this transition move the caller's tool list? The log record
  carries the row's address, not the row, so the row is read once
  here — grant transitions are rare, and the read is the same
  load-row the envelope door makes."
  [eng t pid]
  (when (and (contains? #{:grant :approval_request} (:kind t))
             (:resource-id t))
    (let [st (:storage eng)
          row (try (store/with-tx st
                     (fn [tx] (store/load-row st tx (:kind t) (:resource-id t) {})))
                   (catch Exception _ nil))]
      (and row (mcp/grant-moved-for? (:kind t) row pid)))))

(defn- notice-stream
  "The GET stream: subscribe to grant and approval_request transitions
  on the engine's dispatcher, push list_changed for each one that is
  the caller's, heartbeat between. The dispatcher is a fact about a
  started process — a bare handler answers the same 503 the events
  doors do."
  [eng req principal]
  (let [d (or (runtime/surface eng :dispatcher)
              (throw (p/problem :events-unavailable 503 "Event stream unavailable"
                                {:detail (str "This engine is not started; the "
                                              "events dispatcher is not running.")})))
        pid (:id principal)
        hb-ms (:sse-heartbeat-ms eng 15000)
        sub (events/subscribe d {:kinds #{:grant :approval_request}})
        done (atom false)
        cleanup! (fn []
                   (when (compare-and-set! done false true)
                     (events/unsubscribe d sub)))]
    (http/as-channel
     req
     {:on-open
      (fn [ch]
        (http/send! ch {:status 200 :headers stream-headers
                        :body ": stream open\n\n"}
                    false)
        (let [t (Thread.
                 ^Runnable
                 (fn []
                   (try
                     (loop []
                       (let [evt (events/take-event sub hb-ms)]
                         (cond
                           (= ::events/closed evt) nil
                           (nil? evt)
                           (when (and (http/send! ch ": hb\n\n" false)
                                      (events/channel-alive? ch))
                             (recur))
                           (moved-for? eng evt pid)
                           (when (and (http/send! ch list-changed-frame false)
                                      (events/channel-alive? ch))
                             (recur))
                           :else (recur))))
                     (finally (cleanup!))))
                 (str "waymark10-mcp-notice-" (:id sub)))]
          (doto ^Thread t (.setDaemon true) (.start))))
      :on-close
      (fn [_ch _status]
        (cleanup!))})))

(defn- rpc-get
  "GET on the door: the notice stream for a client that asked for it,
  405 for one that did not — both only for a named principal, since
  a stream for nobody would announce nobody's leash."
  [eng]
  (fn [req]
    (let [principal (named-principal! eng req)]
      (if (wants-stream? req)
        (notice-stream eng req principal)
        (no-stream req)))))

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
                             :get (rpc-get eng)}]
              ;; RFC 9728, both spellings a client may derive: the root
              ;; document, and the path-inserted one for this door. Root
              ;; addresses are core-static's precedent (/agentInvite)
              ["/.well-known/oauth-protected-resource"
               {:get (protected-resource eng)}]
              ["/.well-known/oauth-protected-resource/api/-/mcp"
               {:get (protected-resource eng)}]]}))
