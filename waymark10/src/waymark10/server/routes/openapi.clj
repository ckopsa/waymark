(ns waymark10.server.routes.openapi
  "The OpenAPI overlay's one route.

  The cheapest module there is — no kind, no state, no runtime, a
  derived reading of the registry the engine already holds — which is
  exactly why the spec picked it as the mounting seam's first proof
  (docs/spec-modularization.md, the module inventory)."
  (:require [waymark10.server.openapi :as openapi]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]))

(set! *warn-on-reflection* true)

(defn- openapi-doc [eng]
  (fn [req]
    ;; the document names every kind; a scoped request gets the
    ;; concealment answer
    (when (router/visibility-of req)
      (throw (p/problem :not-found 404 "Not found" {:detail "No such route."})))
    (router/json-response 200 (openapi/document eng))))

(defn routes
  "/api/openapi.json — static, and it must stay static: two segments
  under /api is /api/{plural}'s own shape, so mounted after the plural
  grammar this address would be read as a collection named
  \"openapi.json\"."
  [eng]
  {:module :openapi
   :static [["/api/openapi.json" {:get (openapi-doc eng)}]]})
