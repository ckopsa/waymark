(ns waymark10.server.routes.feed
  "The feed's one door (waymark-iqa.2).

  `GET /api/-/feed` — three segments under /api, which is
  `/api/{plural}/{id}`'s own shape, so it rides the `:static` bucket
  for the reason `/api/-/mcp` does: mounted after the plural grammar
  this address would be read as row \"feed\" of a collection named
  \"-\". Position is the router's only ordering rule.

  The mcp.clj / routes.mcp.clj split, verbatim: the mixer, the recipe
  and the populations live in `waymark10.server.feed`, so a second
  transport — a stdio agent, a server-rendered screen — is that
  namespace with a different wrapper, and this file is the only thing
  it would not reuse.

  THE RECIPE IS READ ONCE, HERE. `(:feed eng feed/default-recipe)` is
  the modules.clj spelling — opts are read off the engine at the start
  site with their defaults, one engine, one opts map — and
  `check-recipe!` runs at BUILD time, so a recipe that names an unknown
  population or forgets its seam refuses the boot rather than the
  request. A definition error at assembly is the same refusal
  `modules/selected` gives an unknown module label.

  AUTH IS THE ROUTER'S, UNCHANGED. `wrap-identity` has already run:
  the principal is resolved and the presented X-Waymark-Grant has
  already become the visibility every card below is projected through.
  Nothing here authenticates anybody. It refuses the ANONYMOUS — the
  same 404 `/api/-/seasons` answers, because a feed is somebody's own
  order and there is no such thing as nobody's — and it PROJECTS
  everyone else. That last half is the departure `routes/law_sweep.clj`
  declines to make and says so: a sweep refuses a scoped caller
  outright, and the feed may not, because per-member worlds is the
  point of the surface rather than a complication of it."
  (:require [waymark10.server.feed :as feed]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- feed-doc
  "GET /api/-/feed[?cursor=…] — the day's mixed feed, or the archive
  page a cursor names.

  The cursor's day is judged BEFORE a single row is read: a cursor
  minted yesterday is a 409 whose sentence says the feed rolled, never
  a page served under yesterday's seed. The seed is re-derived from
  (salt, this principal, today) rather than trusted from the token, so
  a forged cursor buys an offset into the reader's OWN feed and
  nothing else."
  [eng recipe]
  (fn [req]
    (let [principal (router/principal-of req)]
      (when (or (nil? principal)
                (= (:id principal) (:id t/anonymous)))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "No such route."})))
      (let [cursor (some-> (get (router/query-params req) "cursor")
                           feed/decode-cursor)
            today (feed/today eng recipe)]
        (when (and cursor (not= today (:day cursor)))
          (throw (feed/rolled (:day cursor) today)))
        (router/json-response
         200
         (feed/document eng recipe
                        {:principal principal
                         :visibility (router/visibility-of req)
                         :offset (:offset cursor)}))))))

(defn routes [eng]
  (let [recipe (feed/check-recipe! (:feed eng feed/default-recipe))]
    {:module :feed
     :static [["/api/-/feed" {:get (feed-doc eng recipe)}]]}))
