(ns waymark10.server.routes.seasons
  "The rhythm door's one route (waymark-tti.2).

  One route, one module, nothing else: the spec's smallest honest
  entry in the inventory, and the one the acceptance test drops to
  prove the seam — an engine assembled without :seasons answers
  /api/-/seasons with the not-found the router gives any address
  nobody mounted."
  (:require [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.seasons :as seasons]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- seasons-doc
  "GET /api/-/seasons?weeks=4&include_system=0 — the transition log
  as weekly rhythm buckets plus the aging read over current rows
  (waymark10.server.seasons). The core firehose 404s every
  grant-scoped caller as a recorded punt; seasons supersedes that
  posture for history: it PROJECTS — a scoped caller gets exactly the
  kinds its grant sees whole, everything else byte-level absent.
  Anonymous gets the same concealment 404 as the other doors."
  [eng]
  (fn [req]
    (let [principal (router/principal-of req)]
      (when (or (nil? principal)
                (= (:id principal) (:id t/anonymous)))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "No such route."})))
      (let [q (router/query-params req)]
        (router/json-response
         200
         (seasons/report eng (router/visibility-of req)
                         {:weeks (seasons/clamp-weeks (get q "weeks"))
                          :include-system? (contains? #{"1" "true"}
                                                      (get q "include_system"))}))))))

(defn routes [eng]
  {:module :seasons
   :static [["/api/-/seasons" {:get (seasons-doc eng)}]]})
