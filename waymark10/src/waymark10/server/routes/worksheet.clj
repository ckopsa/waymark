(ns waymark10.server.routes.worksheet
  "The worksheet round-trip's two routes — and the reason the mounting
  seam has two buckets rather than one list.

  /api/{plural}/-/worksheet lives INSIDE the plural grammar: after
  /api/{plural}, before /api/{plural}/-/{action}. Mounted a line
  later, the bulk-action grammar matches it first and every worksheet
  download becomes an invocation of an action named \"worksheet\" —
  no error, no warning, just a surface that quietly stopped existing.
  That is what :plural buys (waymark10.server.router/assemble-routes)."
  (:require [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.worksheet :as worksheet]))

(set! *warn-on-reflection* true)

(defn- worksheet-get
  "GET /api/:plural/-/worksheet?<filters> — the filtered view as an
  xlsx download, for kinds declaring :worksheet. The same query
  grammar as the collection; pagination params are ignored (a
  worksheet is the whole subset)."
  [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (router/rdef-by-plural eng plural)]
      (router/check-kind! req rdef)
      ;; the export projects (waymark-ecq closed): the visibility
      ;; rides into the query and the columns exactly as it rides
      ;; the collection envelope
      (worksheet/export eng rdef (router/query-params req)
                        (router/visibility-of req)))))

(defn- worksheet-post
  "POST /api/:plural/-/worksheet — the edited workbook back, raw
  bytes in the body. The upload STAGES: it lands as a worksheet row
  (the engine's own kind) whose post-commit pass plans every line,
  so the 201 already carries the full report; revalidate / apply /
  discard are the row's own actions from there. ?filename= names the
  file for the record."
  [eng]
  (fn [{{:keys [plural]} :path-params :as req}]
    (let [rdef (router/rdef-by-plural eng plural)
          _ (router/check-kind! req rdef)
          ;; staging writes rows the scoped uploader then cannot see
          ;; (worksheet is outside every grant surface) — refuse at
          ;; the door instead of after the file has landed
          _ (when (router/visibility-of req)
              (throw (p/not-found "collection" plural)))
          result (worksheet/stage!
                  eng rdef (:body req)
                  {:principal (router/principal-of req)
                   :filename (get (router/query-params req) "filename")})
          row (:row result)
          ws-rdef (get (inv/resources eng) :worksheet)]
      (router/envelope-response eng ws-rdef row req 201
                                {"Location" (str "/api/worksheets/" (:id row))}))))

(defn routes
  "The one route set that MUST be in the :plural bucket."
  [eng]
  {:module :worksheet
   :plural [["/api/:plural/-/worksheet" {:get (worksheet-get eng)
                                         :post (worksheet-post eng)}]]})
