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
  point of the surface rather than a complication of it.

  …AND THIS FILE IS AN ENFORCEMENT POINT (waymark-iqa.23). One
  parameter, `?preview_as=<member>`, answers somebody ELSE's feed, and
  it is judged here because the capability granted is this route.
  server/capabilities.clj's docstring says waymark holds the law about
  access and never the credential, and names the trade that follows
  from it — enforcement is cooperative, own the enforcement point. We
  own this one. So the ask, the four-eyes approval, the leash, the
  expiry and the revoke door are all the ordinary capability
  machinery, unchanged and un-special-cased, and the only thing that
  is different is that the system standing in front of the data is
  the same process reading the grant."
  (:require [clojure.string :as str]
            [waymark10.server.capabilities :as cap]
            [waymark10.server.feed :as feed]
            [waymark10.server.grants :as grants]
            [waymark10.server.members :as members]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the preview (waymark-iqa.23) ────────────────────────────────────

(defn- refuse-preview
  "One 403, spelled so the next move is obvious. It is a 403 and not
  the feed's usual concealing 404 on purpose, and the reason is the
  vocabulary posture the registry already took: capabilities are
  WORDS, readable by every named principal without a grant, so naming
  `feed.preview_as` in a refusal discloses nothing that a GET
  /api/capabilities would not. What it buys is the thing concealment
  costs — an agent that reads this sentence knows how to ASK, and the
  asking door is the one surface this engine never hides."
  [detail]
  (throw (p/problem :preview-not-granted 403 "Preview not granted"
                    {:detail detail
                     :remedies
                     [(str "POST /api/approval_requests with scope [{\"kind\":"
                           " \"" cap/feed-preview-as-token "\", \"actions\":"
                           " [], \"filter\": {\"member\": \"<member-id>\"}}]"
                           " — a human in the house approves it, and the"
                           " grant it mints is what this door reads.")]})))

(def ^:private filter-field
  "The one key the constraint may carry. `:filter` on a scope entry is
  a map of field→exact value that waymark validates the SHAPE of and
  carries without meaning; this door is the reader that gives it one,
  and it reads exactly one field, because a preview that accepted an
  unrecognised constraint would be honouring a leash it had not
  understood."
  :member)

(defn- preview-target
  "The member whose feed this request may be answered with, or nil
  when the request is an ordinary one for the caller's own feed.

  Four refusals, in this order, and the order is the security
  property. The CAPABILITY first — a caller who does not hold it
  learns nothing about which member ids exist, because it never gets
  to a lookup. Then the FILTER's presence: an unfiltered
  `feed.preview_as` grant is refused at this door rather than read as
  'any member'. That is a decision and it could have gone the other
  way; it did not, because absent-means-any is exactly the grant a
  tired human approves without reading, and the whole value of a
  capability over a role is that the approval names the thing.
  (Consequence, recorded and small: one grant previews one member. A
  second member is a second ask — `scope-filters-are-filterable`
  already refuses two filtered entries of one kind, so the shape is
  the guard's, not this door's.) Then the filter's SPELLING, then the
  member it names against the one asked for.

  The target is resolved through `members/principal-for` and its
  sight through `grants/unscoped-visibility` — the member gate and
  wrap-identity's own else-branch, called rather than imitated. A
  preview must see EXACTLY what the member sees; anything re-derived
  here would be a second, quietly divergent definition of a member's
  world."
  [eng req]
  (when-some [who (some-> (get (router/query-params req) "preview_as")
                          str/trim not-empty)]
    (let [vis (router/visibility-of req)
          caller (router/principal-of req)
          entry (grants/capability-entry vis cap/feed-preview-as-token)
          named (some-> (first (:filters entry)) (get filter-field) str)]
      (cond
        (nil? entry)
        (refuse-preview
         (str "Reading another member's feed is the "
              cap/feed-preview-as-token " capability, and this request wears"
              " no live grant that names it. Present an accepted grant as"
              " X-Waymark-Grant, or file the ask."))

        (nil? (:filters entry))
        (refuse-preview
         (str "This grant names " cap/feed-preview-as-token
              " with no filter, and an unfiltered preview grant is refused"
              " here: the filter is WHOSE feed may be read ({\"member\":"
              " \"<member-id>\"}), and a grant that names nobody would name"
              " everybody. Ask again with the member spelled out."))

        (str/blank? (str named))
        (refuse-preview
         (str "This grant's " cap/feed-preview-as-token " filter carries "
              (pr-str (vec (sort (map name (keys (first (:filters entry)))))))
              " and this door reads exactly one constraint, "
              (pr-str (name filter-field)) " — a preview that honoured a"
              " constraint it had not understood would be honouring nothing."))

        (not= named who)
        (refuse-preview
         (str "This grant admits a preview of member " (pr-str named)
              ", and " (pr-str who) " is not that member. One grant, one"
              " member; a second member is a second ask."))

        :else
        (let [target (or (members/principal-for eng who)
                         (throw (p/not-found :member who)))]
          {:principal target
           :visibility (grants/unscoped-visibility eng target)
           ;; the stamp, assembled where the two identities are both
           ;; in hand. :grant is the DURABLE half of this reading —
           ;; see the recorded audit punt in docs/spec-feed.md
           :preview {:of {:id (:id target)
                          :display (let [d (str (:display target))]
                                     (if (str/blank? d) (:id target) d))}
                     :by {:id (:id caller)
                          :display (let [d (str (:display caller))]
                                     (if (str/blank? d) (:id caller) d))}
                     :grant (:grant-id vis)}})))))

(defn- feed-doc
  "GET /api/-/feed[?preview_as=…][&cursor=…] — the day's mixed feed, or
  the archive page a cursor names.

  The cursor's day is judged BEFORE a single row is read: a cursor
  minted yesterday is a 409 whose sentence says the feed rolled, never
  a page served under yesterday's seed. The seed is re-derived from
  (salt, this principal, today) rather than trusted from the token, so
  a forged cursor buys an offset into the reader's OWN feed and
  nothing else — and under a preview, 'the reader' is the previewed
  member, so the same sentence holds one identity over.

  THE VERBS RENDER, and they are the member's. A previewed card
  carries the actions the MEMBER holds, because `document` is called
  with the member's visibility and `card` is the same function it
  always was; the alternative — stripping them — was weighed and
  refused twice over. Once for honesty: previewing 'what my audience
  will actually see' with the affordances removed is a preview of a
  different surface. Once for arithmetic: the do-now section keeps
  only cards that `offers-something?`, so stripping before the mixer
  would CHANGE which cards appear and stripping after would leave a
  section whose whole rule is 'a verb under the thumb' full of rows
  with none. So they render, disabled by truth rather than by a flag:
  every href is the member's own door, the router judges the ACTUAL
  caller at it, and a previewer who POSTs one is judged as themselves
  and refused. The note says so in the document; the pack proves it."
  [eng recipe]
  (fn [req]
    (let [principal (router/principal-of req)]
      (when (or (nil? principal)
                (= (:id principal) (:id t/anonymous)))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "No such route."})))
      (let [preview (preview-target eng req)
            cursor (some-> (get (router/query-params req) "cursor")
                           feed/decode-cursor)
            today (feed/today eng recipe)]
        (when (and cursor (not= today (:day cursor)))
          (throw (feed/rolled (:day cursor) today)))
        (router/json-response
         200
         (feed/document eng recipe
                        (merge {:principal principal
                                :visibility (router/visibility-of req)
                                :offset (:offset cursor)}
                               preview)))))))

(defn routes [eng]
  (let [recipe (feed/check-recipe! (:feed eng feed/default-recipe))]
    {:module :feed
     :static [["/api/-/feed" {:get (feed-doc eng recipe)}]]}))
