(ns waymark10.server.routes.law-sweep
  "The sweep's one door (waymark-442.3).

  `/api/definitions/:id/sweep` — four segments, so it collides with
  nothing in the plural grammar, and it rides the `:static` bucket for
  the same reason `/api/attachments/{id}/bytes` does: an address whose
  second segment is a literal has to be mounted before
  `/api/{plural}` swallows it.

  A GET, not a POST, and that is the decision the spec's original
  `POST /api/law_sweeps` invites a second look at. A sweep commits
  nothing — it loads rows and runs two probes over each — so a POST
  would be a write verb over a read, and the collection query grammar
  it scopes by (`?state=active`, the ref filters, everything) already
  lives in a query string. The report is idempotent, cacheable and
  linkable; a proposer can paste one into the conversation about the
  promote.

  Concealment, twice. A grant-scoped caller is refused at the door
  rather than projected: a sweep names the ids and summaries of every
  row of a kind, and narrowing that to a grant would be a second
  visibility surface with its own bugs, on a door whose whole subject
  is a law nobody scoped has business promoting. Anonymous gets the
  same 404 `/api/-/seasons` gives. spec-time-travel's one security
  clause — an as-of read must project through the same grant
  visibility — is the shape .4 will have to solve properly; this door
  sidesteps it by refusing, and says so."
  (:require [waymark10.server.invoke :as inv]
            [waymark10.server.law-sweep :as law-sweep]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- load-definition
  [eng id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) :definition)
        raw (store/with-tx st #(store/load-row st % :definition id {}))]
    (when-not raw (throw (p/not-found :definition id)))
    (inv/decode-row rdef raw)))

(defn- sweep-doc
  "GET /api/definitions/:id/sweep?<the target kind's filters> — which
  live rows this proposal re-judges differently, before anyone
  promotes it."
  [eng]
  (fn [req]
    (let [principal (router/principal-of req)]
      (when (or (nil? principal)
                (= (:id principal) (:id t/anonymous))
                (some? (router/visibility-of req)))
        (throw (p/not-found "collection" "definitions")))
      (let [row (load-definition eng (get-in req [:path-params :id]))]
        (router/json-response
         200
         (p/wire-value (law-sweep/report eng row principal
                                         (router/query-params req))))))))

(defn routes [eng]
  {:module :law-sweep
   :static [["/api/definitions/:id/sweep" {:get (sweep-doc eng)}]]})
