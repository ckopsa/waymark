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

  THE BUILT-IN RECIPE IS READ ONCE, HERE. `(:feed eng
  feed/default-recipe)` is the modules.clj spelling — opts are read off
  the engine at the start site with their defaults, one engine, one
  opts map — and `check-recipe!` runs at BUILD time, so a recipe that
  names an unknown population or forgets its seam refuses the boot
  rather than the request. A definition error at assembly is the same
  refusal `modules/selected` gives an unknown module label.

  …AND THE STORED RECIPE IS READ PER REQUEST (waymark-4yn). The engine
  opt is now the FALLBACK rather than the whole answer: a household
  tunes its own order at runtime through the `feed_recipe` kind, and
  `feed-recipe/for-reader` resolves member row → household row →
  built-in, once per read, uncached on purpose. The build-time check
  stays exactly where it was, because the built-in is still a
  DECLARATION and a broken one should still refuse the boot; a stored
  row is judged at its own doors instead, by the same four checks.

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
  the same process reading the grant.

  …AND ONE READ FLAG, `?explain=1` (waymark-iqa.29). It changes no
  card, no order and no seed — only what each card says about itself,
  which is why it is a parameter rather than a second document and why
  a client may fetch it LATE and line the answer up by `card_id`. The
  narrated recipe rides every answer either way: it is one narration
  per LINE, and the prose that would have repeated once per card is
  the half this flag buys."
  (:require [clojure.string :as str]
            [waymark10.feed-recipe :as recipe]
            [waymark10.server.capabilities :as cap]
            [waymark10.server.diagnosis :as diagnosis]
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
  "GET /api/-/feed[?preview_as=…][&draw=…][&cursor=…] — the day's mixed
  feed, the draw a person's tap asked for, or the archive page a
  cursor names.

  The cursor's day is judged BEFORE a single row is read: a cursor
  minted yesterday is a 409 whose sentence says the feed rolled, never
  a page served under yesterday's seed. The seed is re-derived from
  (salt, this principal, today, draw) rather than trusted from the
  token, so a forged cursor buys an offset into the reader's OWN feed
  and nothing else — and under a preview, 'the reader' is the previewed
  member, so the same sentence holds one identity over.

  …AND `?draw=<nonce>` IS THE PERSON SPINNING (waymark-8um.2, law 6).
  The nonce is the CLIENT's — it taps, it mints, the server hashes it
  into the seed and hands back a fresh order with the draw named in
  the document, in `self`, and in `links.next`. Absent means the day's
  own order, which is what every reader who never taps keeps getting,
  to the byte. Three rules live at this door and nowhere else:

  - The draw is read through `feed/parse-draw`, so a mangled nonce is
    a 422 naming the parameter rather than a silent daily order.
  - **The cursor's draw wins**, because the cursor is the page's own
    memory of which order it is walking; a client that drops the query
    parameter while following `links.next` still gets its own draw.
  - Both halves speaking and disagreeing is refused (`draw-mismatch`)
    rather than guessed at. Every link this engine mints carries them
    agreeing, so the disagreement is always hand-composed.

  A rolled DAY still 409s under a draw, unchanged and for the
  unchanged reason: the day is an ingredient of the seed either way,
  and yesterday's draw is yesterday's order.

  And a previewer may deal again, because dealing again is a READ. The
  draw rides the preview exactly as `?explain=1` does — it changes
  which order the previewed member's own cards come back in, it is
  stamped in that member's own `self` link, and it writes nothing at
  all. There is nothing here for a preview to make dangerous: no row
  moves, no view is counted (`views.recording` is false on every
  preview), and the member whose feed it is will never know it
  happened, exactly as with any other preview read.

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
  and refused. The note says so in the document; the pack proves it.

  AND THE RECIPE IS THE READER'S (waymark-4yn). `for-reader` is asked
  about the member whose feed this is — the PREVIEWED member under a
  preview, not the previewer — for the same reason the visibility is
  theirs: a preview computed through the reader's own order would be a
  preview of a feed nobody has. The stamp it answers with rides the
  document, so a previewer can see which order the member reads in."
  [eng built-in]
  (fn [req]
    (let [principal (router/principal-of req)]
      (when (or (nil? principal)
                (= (:id principal) (:id t/anonymous)))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "No such route."})))
      (let [params (router/query-params req)
            preview (preview-target eng req)
            reader (or (:principal preview) principal)
            {:keys [recipe source]} (recipe/for-reader eng built-in reader)
            cursor (some-> (get params "cursor") feed/decode-cursor)
            today (feed/today eng recipe)
            ;; the draw (waymark-8um.2): the person's tap, spelled on
            ;; the wire. The CURSOR's draw wins when there is one, so a
            ;; page continues the draw it came from even if the client
            ;; dropped the parameter — and when both halves speak and
            ;; disagree, neither is guessed at.
            asked (feed/parse-draw (get params "draw"))
            draw (if cursor (:draw cursor) asked)]
        (when (and cursor (not= today (:day cursor)))
          (throw (feed/rolled (:day cursor) today)))
        (when (and cursor (get params "draw") (not= (:draw cursor) asked))
          (throw (feed/draw-mismatch (:draw cursor) asked)))
        (router/json-response
         200
         (feed/document eng recipe
                        (merge {:principal principal
                                :recipe-source source
                                :visibility (router/visibility-of req)
                                :offset (:offset cursor)
                                ;; ?draw=<nonce> — the person dealt
                                ;; again (waymark-8um.2). Absent is
                                ;; the day's own order, and the seed
                                ;; it hashes is byte-identical to the
                                ;; one every reader has always read.
                                :draw draw
                                ;; ?explain=1 — the citation spelled out
                                ;; on every card (waymark-iqa.29). It is
                                ;; a READ FLAG and nothing more: the
                                ;; day's order, the seed and the cards
                                ;; are identical with it and without it,
                                ;; which is why the UI may fetch it late
                                ;; and line the answer up by card_id.
                                ;; An unrecognised value is simply not
                                ;; an explained read — there is nothing
                                ;; here to get wrong and no reason to
                                ;; refuse a reader their own feed over
                                ;; a query string.
                                :explain? (contains? #{"1" "true" "yes"}
                                                     (str (get params "explain")))}
                               preview)))))))

(defn- diagnosis-doc
  "GET /api/-/diagnosis[?outcome=<id>] — the composer's work order
  (waymark-8um.4, law 4): every outcome the CALLER composed, each with
  how many mornings it was shown, how it was answered, which reasons
  the house gave, and what the floor says — and the lesson each one
  teaches, shown-and-declined apart from never-shown.

  The feed door's own posture, inherited whole: `wrap-identity` has
  already run, the anonymous are refused with the same 404 the feed
  gives, and everyone else is PROJECTED — the outcomes are the
  caller's own, and the two records other members wrote are read only
  where the presented leash admits the whole kind. Nothing here
  authenticates anybody and nothing here writes. It is a second door
  beside the feed rather than a key on the feed document because it
  is somebody else's page: the feed is the household's morning, and
  this is the composer's.

  THE RECIPE IS THE HOUSEHOLD'S (waymark-1uv.4). The document reads
  the crown's numbers to say what the rank made of each outcome, and
  `for-reader` is asked with NO member — the household's row or the
  built-in, never a member's own — because the composer has no feed
  and the numbers its never-shown bundles lost under are the house's.
  A member who reads under a recipe of their own is one screen among
  the measured, and the tally says whose numbers it quoted."
  [eng built-in]
  (fn [req]
    (let [principal (router/principal-of req)]
      (when (or (nil? principal)
                (= (:id principal) (:id t/anonymous)))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "No such route."})))
      (let [{:keys [recipe source]} (recipe/for-reader eng built-in nil)]
        (router/json-response
         200
         (diagnosis/document eng {:principal principal
                                  :visibility (router/visibility-of req)
                                  :recipe recipe
                                  :recipe-source source
                                  :outcome (some-> (get (router/query-params req)
                                                        "outcome")
                                                   str/trim not-empty)}))))))

(defn routes [eng]
  (let [built-in (feed/check-recipe! (:feed eng feed/default-recipe))]
    {:module :feed
     :static [["/api/-/feed" {:get (feed-doc eng built-in)}]
              ;; the composer's diagnosis (waymark-8um.4) — the feed's
              ;; own three-segment shape, for the feed's own reason
              ["/api/-/diagnosis" {:get (diagnosis-doc eng built-in)}]]}))
