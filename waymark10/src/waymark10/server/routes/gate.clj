(ns waymark10.server.routes.gate
  "The Gate proxy's doors (waymark-q95) — a bespoke /-/ system door,
  sibling of /api/-/feed.

  `GET /api/-/gate` — the affordance document: Gate's live tools ∩
  the presented grant, reads as links and mutations as action forms.
  `POST /api/-/gate/{tool}` — the invoke: grant-checked in-process,
  then forwarded, Gate's payload answered verbatim.

  Both are three-or-more segments under /api with a literal second
  segment, so they ride the `:static` bucket for the reason
  `/api/-/feed` does: mounted after the plural grammar, GET
  /api/-/gate would be read as row \"gate\" of a collection named
  \"-\". Position is the router's only ordering rule.

  The gate-proxy split is mcp.clj/routes.mcp.clj's, verbatim: the
  map, the grant check and the Gate client live in
  `waymark10.server.gate-proxy`, so a second surface — the MCP
  projection the bead names — is that namespace with a different
  wrapper, and this file is the only thing it would not reuse.

  AUTH IS THE ROUTER'S, UNCHANGED. `wrap-identity` has already run:
  the principal is resolved and the presented X-Waymark-Grant has
  already become the visibility every affordance below is projected
  through. Nothing here authenticates anybody. It refuses the
  ANONYMOUS — the feed's own 404, because an unnamed caller has no
  grant to project a surface from — and it PROJECTS everyone else:
  a named caller whose grant admits nothing reads an empty document
  and the way to ask, which is the registry's standing vocabulary
  posture (capabilities are words).

  THE GATE CALLER IS BUILT ONCE, HERE — `gate-proxy/rpc-of` at route
  build, the modules.clj spelling for an engine opt read at the
  start site with its default ((:gate eng) {:url …}, defaulting to
  the deployment's LAN address) — so the MCP session to Gate is
  opened lazily and reused across requests rather than re-shaken per
  read. It is the ONLY thing kept: no rows, no cache of Gate's
  tools, no mirrored payloads. Every document is recomputed live."
  (:require [waymark10.server.gate-proxy :as gate]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- named!
  "The feed door's refusal, verbatim: a capability surface is
  somebody's leash, and there is no such thing as nobody's."
  [req]
  (let [principal (router/principal-of req)]
    (when (or (nil? principal)
              (= (:id principal) (:id t/anonymous)))
      (throw (p/problem :not-found 404 "Not found"
                        {:detail "No such route."})))))

(defn- affordance-doc [rpc]
  (fn [req]
    (named! req)
    (router/json-response
     200 (gate/affordances-for rpc (router/visibility-of req)))))

(defn- invoke-tool [rpc]
  (fn [req]
    (named! req)
    (let [tool (get-in req [:path-params :tool])
          args (router/read-body req)]
      (router/json-response
       200 (gate/invoke-for rpc (router/visibility-of req) tool args)))))

(defn routes [eng]
  (let [rpc (gate/rpc-of eng)]
    {:module :gate
     :static [["/api/-/gate" {:get (affordance-doc rpc)}]
              ["/api/-/gate/:tool" {:post (invoke-tool rpc)}]]}))
