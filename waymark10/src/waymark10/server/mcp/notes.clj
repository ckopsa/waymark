(ns waymark10.server.mcp.notes
  "What the sit says around its walk, carved out of
  waymark10.server.mcp (ticket ed0a593e): the sit's refusals and the
  sitting it opens or reuses, the filter and judgment blocks of a walk,
  the walk's notes and the reasons it names withheld rows by, the
  bench's feedback, the submit sentence and the reference repositories,
  the app's own sections and an org mayor's first sit. A pure move —
  mcp keeps a stub for each var it still calls, and this namespace
  requires mcp.wire and mcp.bench rather than mcp back.

  `prepare-answer`, `bench-fetch-retry-pause-ms` and `bench-of` stay
  in mcp: a test redefines the pause there, and the prepare reads it."
  (:require [clojure.string :as str]
            [waymark10.modules.hooks :as hooks]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live-cracks]
            [waymark10.server.mcp.bench :as mcp-bench]
            [waymark10.server.mcp.wire :as mcp-wire]
            [waymark10.server.mcp-sessions :as sessions]
            [waymark10.server.routes.seats :as seat-routes]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.seats.guards :as seat-guards]
            [waymark10.server.store :as store]
            [waymark10.server.waiting-on :as waiting-on]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

;; mcp.wire's and mcp.bench's vars, one stub for each var this
;; namespace calls

(def ^:private ^Instant session-now mcp-wire/session-now)
(def ^:private ^Instant ttl-cutoff mcp-wire/ttl-cutoff)
(def ^:private bound-sitting mcp-wire/bound-sitting)
(def ^:private bound-elsewhere mcp-wire/bound-elsewhere)
(def ^:private live-sessions mcp-wire/live-sessions)
(def ^:private request mcp-wire/request)
(def ^:private query-string mcp-wire/query-string)
(def ^:private verbatim-json mcp-wire/verbatim-json)
(def ^:private collection-doc? mcp-wire/collection-doc?)

(def ^:private row-of mcp-bench/row-of)

;; ── the sit's refusals and its sitting ───────────────────────────────

(def sit-no-session
  (str "This client keeps no MCP session, so a key cannot bind it. "
       "Sit from a client that sends Mcp-Session-Id back."))

(def sit-not-a-delegate
  (str "A seat key binds a person's tool. Present it from a session "
       "signed in as a person through the connector."))

(defn sit-interactive
  "R-10.8's refusal, and it NAMES the seat where `sit-not-a-delegate`
  does not. The mode is the seat's, so the sentence is about the
  office rather than about the caller: a Routine's run — an agent with
  nobody behind it — has presented the right key to the wrong kind of
  seat, and the only thing it can do about it is stop. The seat is
  named because the key already proved the caller knows it."
  [named]
  (str "The seat `" named "` is an interactive seat. A person sits here."))

(def sit-no-seat
  "UNIFORM, and short on purpose: a key that matches nothing, a key
  the seat has since revoked, a key that is not a key at all and a
  seat that has been parked all answer this one sentence. Saying which
  would turn the door into an oracle over the house's offices, and
  `seat-by-key` compares in constant time for the same reason."
  "No seat answers this key.")

(defn sit-no-model
  "The refusal for a seat that would open no sitting (ticket 7e177f8d):
  it names no model, by its schedule or its `held_for`, so the sit has
  nothing to bill and binds nothing."
  [named]
  (str "The seat `" named "` names no model, so it cannot be sat; "
       "hold it for a model first."))

(defn sit-superseded-judgment
  "The refusal for a seat that still says a superseded judgment
  (ticket 86514746), or nil. A supersede re-points the seats that say
  it; this covers rows written before that, and any path that skipped
  it. It names the successor, which is what the restate should say."
  [eng seat]
  (when-some [j (row-of eng :judgment (get-in seat [:data :judgment]))]
    (when (= :superseded (:state j))
      (let [successor (some-> (get-in j [:data :successor]) str not-empty)]
        (str "The seat `" (get-in seat [:data :name]) "` says the judgment "
             (:id j) ", which is superseded"
             (if successor
               (str " by " successor ". Restate the seat to say " successor
                    ", then sit again.")
               (str " with no successor. Restate the seat to say a promoted"
                    " judgment, then sit again.")))))))

(defn- reusable-sitting
  "The open sitting under this seat grant that THIS run may go on
  using, or nil.

  A second `waymark_sit` on the same session must not mint a second
  bill, which is why the grant's open sitting is reused at all. But
  two runs of one seat can overlap — a person taps Run now during the
  scheduled hour — and the second run reusing the first's sitting
  would put both wakes' tokens on one row and leave the first run's
  hook with nothing to close. So the reuse holds only when the row is
  THIS run's, and every sitting of a seat shares the seat's grant, so
  the grant's NEWEST open sitting is not the answer (tickets 8c72fd3a,
  5d4bbab6). Read in this order:

    1. the open sitting this connector session is already bound to,
       under this grant — unless it is stamped with another run's
       harness session than the one just declared;
    2. when a harness session is declared, the grant's newest open
       sitting stamped with it — the pairing `open-sitting-for-seat`
       makes at the close;
    3. only then the grant's newest UNCLAIMED open sitting: no stamp,
       and — when the seat lets more than one sitting run at once — a
       connector-session claim that is this session's own, or whose
       session is gone: closed, or unheard for longer than the seat's
       `sitting_idle_seconds` (ticket 7496403e). A row no sit has
       claimed yet is free when no other live session is bound to it.
       At one, an open sitting holds every wake
       (`max-open-sittings-help`), so a second session's sit re-keys it
       rather than opening another.

  The row answered is CLAIMED for this session (`seats/claim-sitting!`)
  in the transaction that judges it, so two session-less sits racing
  for one sibling serialise on its lock and exactly one takes it."
  [eng sid grant seat harness-session]
  (let [gid (str (:id grant))
        many? (< 1 (long (or (get-in seat [:data :max_open_sittings]) 1)))
        stamp-of #(some-> (get-in % [:data :harness_session]) str not-empty)
        claim-of #(some-> (:connector_session %) str not-empty)
        wanted (some-> harness-session str not-empty)
        mine (some-> sid str not-empty sessions/id-hash)
        ;; THIS run's own row (bound, or stamped with its session) is
        ;; re-let when its lease lapsed unswept; an unclaimed one is not
        claim! (fn [row free? & [relet?]]
                 (seats/claim-sitting! eng (:id row) mine free? (boolean relet?)))
        bound (some->> (bound-sitting eng sid) str not-empty (row-of eng :sitting))]
    (or (when (and bound
                   (= "open" (some-> (:state bound) name))
                   (= gid (str (get-in bound [:data :grant])))
                   (let [held (stamp-of bound)]
                     (or (nil? held) (nil? wanted) (= held wanted))))
          (claim! bound (constantly true) true))
        (let [rows (seats/open-sittings-for-grant eng gid)]
          (or (when wanted
                (some-> (first (filter #(= wanted (stamp-of %))
                                       (seats/open-sittings-for-grant eng gid true)))
                        (claim! (constantly true) true)))
              (let [;; a sitting a fire opened is its run's own: only the
                    ;; sit that spends that fire's key takes it (ticket
                    ;; 9100c7b9)
                    fired (seats/fire-held-sittings seat)
                    free (remove #(or (stamp-of %) (contains? fired (str (:id %))))
                                 rows)
                    ;; compared with sessions' `touched`: transport time
                    now (session-now eng)
                    idle (long (or (get-in seat [:data :sitting_idle_seconds])
                                   seats/default-idle-seconds))
                    since (let [quiet (.minusSeconds now idle)
                                ttl (ttl-cutoff now)]
                            (if (.isAfter quiet ttl) quiet ttl))
                    live (if many? (disj (live-sessions eng since) mine) #{})
                    taken (if many?
                            (bound-elsewhere eng sid
                                             (map (comp str :id)
                                                  (remove (comp claim-of :data) free)))
                            #{})
                    free? (fn [row]
                            (fn [data]
                              (let [held (claim-of data)]
                                (cond
                                  (not many?) true
                                  (nil? held) (not (contains? taken (str (:id row))))
                                  (= held mine) true
                                  :else (not (contains? live held))))))]
                (some #(claim! % (free? %)) free)))))))

(defn open-sitting!
  "The sitting this bound session is counted against (R-12.15): the
  one already open under the seat grant that belongs to this run, or a
  fresh one born now as the sitter, with the seat, the model, the
  grant and — when the session declared one — the harness session id.
  nil when the seat names no model at all, because a sitting's birth
  needs one — the seat then has no claim to make and nothing to cost.

  The router counts transitions and refusals only against an OPEN
  sitting, and nothing else opens one for a keyed session: no leash
  keeper stands behind a Routine's firing, so the bind is where the
  sitting begins. Reused rather than re-minted on a second sit, the
  way the grant is — see `reusable-sitting` for when that reuse stops.

  THE ID IS STAMPED AT BIRTH, not guessed at the close: the engine
  cannot derive which run this is, the harness can, and a pairing made
  here is one the session-end door (R-12.17) reads rather than
  reconstructs.

  A REUSED SITTING IS STAMPED as it is handed back (ticket e2b55a0c):
  the sit is a call, and a sitting idle past `sitting_idle_seconds`
  would otherwise be abandoned by the next sweep under the caller it
  was just given to. The stamp is the claim (ticket 7496403e), and a
  candidate the claim finds closed is no answer; a fresh sitting is
  born instead, and claimed for this session at birth."
  [eng sid sitter grant seat model harness-session]
  (when model
    (or (reusable-sitting eng sid grant seat harness-session)
        (let [row (:row (inv/create! eng :sitting
                                     (cond-> {:seat (str (:id seat))
                                              :model (str (:id model))
                                              :grant (str (:id grant))}
                                       harness-session
                                       (assoc :harness_session harness-session))
                                     {:principal sitter}))]
          (or (seats/claim-sitting! eng (:id row)
                                    (some-> sid str not-empty sessions/id-hash)
                                    (constantly true))
              row)))))

;; ── the filter and the judgment of a walk ────────────────────────────

(defn filter-params
  "One filter map as query parameters — a plain-equality filter in the
  collection's own vocabulary. `name` reads a stored key whether it
  came back a keyword or a string.

  Two filters ride this one spelling: a judgment's `queue`
  (`queue-params` below) and the walk's own scope entry (R-1 of bead
  waymark-fp62.12). Both are `grants/filter-map-schema` maps, and a
  second spelling would be a second answer to what a filter means."
  [filter-map]
  (into {} (map (fn [[k v]] [(name k) (str v)])) filter-map))

(defn queue-params
  "The judgment's `queue` as query parameters — a plain-equality
  filter on the subject kind."
  [judgment]
  (filter-params (get-in judgment [:data :queue])))

(defn judgment-block
  "What the sitter is told about the law it is saying: which judgment,
  what its subjects are, the verdicts it may choose between with the
  sentence each one means, and how long a remedy may be. The sitter
  never reads the judgment row — this IS the read."
  [judgment]
  {"id" (str (:id judgment))
   "name" (str (get-in judgment [:data :name]))
   "subject_kind" (str (get-in judgment [:data :subject_kind]))
   "verdicts" (mapv (fn [v] {"name" (str (:name v))
                             "sentence" (str (:sentence v))})
                    (get-in judgment [:data :verdicts]))
   "remedy_max" (get-in judgment [:data :remedy_max])})

(def own-held-reason
  "Why a walk withholds a row another open sitting of the SAME seat
  holds: the one reason a sit that finds no row free defers on."
  "another open sitting of this seat holds it")

;; ── the walk's notes and the reasons it withholds by ─────────────────

(def walk-note
  "What a sitter holding its rows does next — and what it must not do.
  Each of the four named tools was a turn of the opening on the
  measured sitting, and the sit's own answer now carries what they
  went for: the charter, the rows, the doors and their inputs."
  (str "Your rows are below, each with its doors. For each row, invoke "
       "the door the charter chooses. Do not call discover, schema, "
       "query or powers; a refusal names its own remedy."))

(def judgment-walk-note
  "The one sentence a seat that says a judgment gets on top of the
  walk note. It names the door, the kind and every field the create
  takes, because the sitter reads the verdict names and the remedy
  cap out of the `judgment` block beside its rows and must not spend
  a turn on waymark_schema to learn where to put them."
  (str " For each row, invoke judge on kind verdict with judgment, "
       "subject_kind, subject_id, the verdict name and one remedy "
       "sentence under remedy_max characters."))

(def no-walk-note
  "The seat that walks nothing still has a charter, and the seat row
  is where it reads it."
  "Read the seat row with waymark_get and do what its charter says.")

(def halted-note
  "The seat against the week's fuel wall. Its grant scopes to nothing
  until the window rolls, so the session is told that in words rather
  than left to find a seat row it may not read and call it broken."
  (str "The seat is halted: its week's fuel is spent, so it walks "
       "nothing now. `halted` says why and when the wall lifts. Say why "
       "and stop."))

(defn halted-of
  "The sit's `halted` block when the seat is against the week's fuel
  wall, or nil. The wall is the router's own arithmetic (R-5.2 step
  3): `grants/spent-this-week` against the seat's budget, through
  `grants/under-budget?`, so the sit and the wall cannot disagree
  about one number. `detail` is the sentence the `fire` door's
  `not-halted` says, and `lifts_at` is when the window has rolled far
  enough (`seat-routes/lifts-at`), or null when no roll will."
  [eng seat named now]
  (let [spent (grants/spent-this-week eng (:id seat) now)
        budget (or (get-in seat [:data :budget_usd_per_week]) 0M)]
    (when-not (grants/under-budget? spent budget)
      {"wall" "budget"
       "detail" (str "The seat is against a wall. The week's fuel is spent: "
                     spent " of " budget " over " named "'s sittings of"
                     " the last seven days. The wall lifts on its own as"
                     " the window rolls.")
       "lifts_at" (some-> (seat-routes/lifts-at eng seat now) str)})))

(defn walk-filter-left-out
  "What the walk's filter left out of an empty walk (ticket 6ea8f277):
  the walked kind read once more AS THE SITTER with the filter taken
  off — the judgment's `queue` for a seat that says a judgment, the
  scope entry's filter for every other — so the count is the kind's
  own queue under the seat's own grant.
  → {\"kind\" \"filter\" \"count\"}, or nil.

  nil when the walk was not empty under its filter, when rows were
  withheld (they are the reason then), when the walk has no filter,
  and when the read without it counts no row. A row the grant does
  not admit is not on that page either, so it is never counted and
  never named: what a grant conceals stays absent here as at every
  other door."
  [eng call session seat walk]
  (when (and walk
             (empty? (get walk "withheld"))
             (not (some-> (get walk "total") pos?)))
    (when-some [rdef (get (inv/resources eng) (keyword (get walk "kind")))]
      (let [judgment (row-of eng :judgment (get-in seat [:data :judgment]))
            params (not-empty
                    (if judgment
                      (queue-params judgment)
                      (some-> (seats/walk-filter seat) filter-params)))]
        (when params
          (let [resp (call (request session :get (str "/api/" (:plural rdef))
                                    {:query (query-string {"page[size]" "1"})}))
                doc (when (<= 200 (:status resp 500) 299) (verbatim-json resp))
                total (when (collection-doc? doc) (get-in doc ["data" "total"]))]
            (when (and (number? total) (pos? total))
              {"kind" (get walk "kind")
               "filter" (str/join ", " (map (fn [[k v]] (str k "=" v))
                                           (sort-by key params)))
               "count" total})))))))

(defn walked-nothing-why
  "One sentence for a sitting its walk handed no rows (ticket
  ae64b57c): the queue rows the walk left out, each with its reason —
  the `withheld` the sit already answers — or that the queue had no
  rows under the walk's filter, or that no queue was read at all. The
  sit keeps it on the sitting as `walked_nothing_why`.

  `left-out` is `walk-filter-left-out`'s answer: when the filter, and
  not an empty queue, left the walk empty, the sentence names the
  filter and counts the rows the seat can see past it (ticket
  6ea8f277)."
  [walk left-out]
  (let [withheld (get walk "withheld")
        total (get walk "total")
        s (cond
            (nil? walk)
            "No queue was read: the seat walks no kind, or its grant does not admit the one it names."

            (seq withheld)
            (str "Every row the queue offered was left out: "
                 (str/join "; " (map #(str (get % "id") " (" (get % "reason") ")")
                                     withheld))
                 ".")

            ;; a judgment's queue keeps the subjects it has judged, so
            ;; an empty walk over it is the judge having caught up
            (and (number? total) (pos? total) (get walk "judgment"))
            (str "The queue held " total " rows under the walk's filter, and "
                 "each one the walk read already carries a standing verdict "
                 "of this judgment.")

            (and (number? total) (pos? total))
            (str "The queue held " total " rows under the walk's filter, and "
                 "none was free to hand.")

            left-out
            (let [n (get left-out "count")]
              (str "Your walk filter (" (get left-out "filter") ") leaves out "
                   n " " (get left-out "kind") (if (= 1 n) " row" " rows")
                   " this seat can see."))

            :else
            "The queue held no rows under the walk's filter and the seat's grant.")]
    (subs s 0 (min 480 (count s)))))

(defn may-create-first-note
  "The sentence an interactive seat on an empty walk is told when its
  scope holds the walked kind's create door (ticket c09893b1): a
  person's first sitting starts before any row exists, and `walk-note`
  over no rows read as nothing to do. nil when the walk handed or held
  a row, when its filter left rows out, and for any other seat."
  [eng call session seat walk]
  (let [kind (some-> (get walk "kind") str not-empty)
        rdef (when kind (get (inv/resources eng) (keyword kind)))
        creates (set (map name (or (:create-action-names rdef) #{:create})))]
    (when (and rdef
               (= "interactive" (some-> (get-in seat [:data :mode]) str))
               (empty? (get walk "rows"))
               (empty? (get walk "withheld"))
               (not (some-> (get walk "total") pos?))
               (some creates (seat-guards/walk-doors seat))
               (nil? (walk-filter-left-out eng call session seat walk)))
      (str " Nothing to walk yet: this seat may create the first " kind "."))))

(def graced-reason
  "a sitting of this seat closed within the seat's release grace (release_grace_seconds) and still holds it")

(defn with-lease-ends
  "The walk, its `withheld` rows another open sitting of this seat
  holds naming that sitting and its lease's end, `sitting` and `until`,
  read from the row's live lease (leases 6, docs/spec-leases.md §5).
  The reason stays `own-held-reason`, which `held-by-own-sittings`
  matches."
  [eng walk]
  (let [own? #(= own-held-reason (get % "reason"))
        ids (into [] (comp (filter own?) (map #(get % "id"))) (get walk "withheld"))]
    (if (empty? ids)
      walk
      (let [held (store/with-tx (:storage eng)
                   #(waiting-on/leases-in eng % nil ids))
            kind (str (get walk "kind"))]
        (update walk "withheld"
                (partial mapv #(let [l (get held [kind (get % "id")])]
                                 (cond-> %
                                   (and (own? %) (:sitting l))
                                   (assoc "sitting" (:sitting l)
                                          "until" (:expires_at l))))))))))

(defn with-graced-reasons
  "The walk, its `withheld` rows that a closed sitting's release grace
  holds named for the grace rather than for an open sitting (ticket
  ae64b57c): both are `claimed`, and only one is another run."
  [walk graced]
  (cond-> walk
    (and (seq graced) (seq (get walk "withheld")))
    (update "withheld"
            (partial mapv #(cond-> %
                             (contains? graced (get % "id"))
                             (assoc "reason" graced-reason))))))

(def other-seat-reason
  "an open sitting of another seat that walks this kind holds it")

(defn defer-past-other-seats!
  "A walk that handed nothing and withheld rows for an open sitting of
  ANOTHER seat (`other-seat-reason`) leaves the seat's schedule pending,
  due when that hold lifts (`schedules/defer-until-elsewhere-lifts!`,
  ticket bf17ce48), rather than only the reason in words on the
  sitting. The wake names the row the fire named when that row is one
  the hold keeps, and walks the queue otherwise."
  [eng seat walk named]
  (let [elsewhere (into #{}
                        (keep #(when (= other-seat-reason (get % "reason"))
                                 (some-> (get % "id") str not-empty)))
                        (get walk "withheld"))]
    (when (seq elsewhere)
      (let [kind (some-> (get-in seat [:data :walk]) str not-empty)
            named (some->> named (seats/named-walk-row eng kind) str)]
        (schedules/defer-until-elsewhere-lifts!
         eng seat elsewhere
         (when (and kind (contains? elsewhere named))
           (wire/write-json {:kind kind :id named})))))))

(defn with-other-seat-reasons
  "The walk, its `withheld` rows that an open sitting of ANOTHER seat
  holds named for that seat's sitting rather than for this seat's own
  (ticket 40d8f04c)."
  [walk elsewhere]
  (cond-> walk
    (and (seq elsewhere) (seq (get walk "withheld")))
    (update "withheld"
            (partial mapv #(cond-> %
                             (contains? elsewhere (get % "id"))
                             (assoc "reason" other-seat-reason))))))

(def change-beside-the-walk-note
  "What a seat whose rows are ASKS does with the change beside them
  (R-12.32). The row it works is the ask; the door that ends the
  round is on the change, and a sitter told only \"invoke the door
  the charter chooses\" would look for a submit on the ask."
  (str " The `change` beside your rows is the change this firing "
       "submits: work the row above, and take submit or stall on the "
       "change."))

(def bench-filled-note
  "What a seat whose bench opened is told about the two arguments every
  bench tool's schema calls required: the engine fills them from the
  worktree it prepared (`bench-stamped`) when the call reaches it on a
  bound session. The note still asks for both on every call: three
  onboarding rehearsals (ticket 083cd105) were refused 403 for leaving
  them out, and a call that names them is judged the same either way."
  (str " Pass the bench's repo and branch, as `bench` above names them, "
       "on every bench call."))

(def every-row-held-note
  "What a sit is told when every open row of its walk is held by
  another open sitting of the seat: the run has nothing to do, and
  stopping at once is the cheap end of it."
  (str "Every open row of your walk is held by another open sitting, "
       "so there is nothing for you to walk. Say so and stop."))

(defn named-row-withheld-note
  "What a sit is told when the row its fire text named is not in its
  walk at all - judged already, ended, or out of the queue - so the
  queue's next rows stand in for it. Said, so a firing that missed its
  row is seen to have missed it."
  [row-id]
  (str " The row your fire text names, " row-id ", is not in your walk"
       " (already judged, ended, or outside the queue), so the rows below"
       " are the queue's instead. Do not walk it; say so if it mattered."))

(defn default-filter-note
  "What a sit is told beside `named-row-withheld-note` when the walk
  puts no filter of its own and so runs under its kind's default
  filter, and the named row's state is outside that default (ticket
  086dcbfd): the walk is the default's queue, not the whole kind, and
  that, not a judgment or an end, is why the row is not in it. nil for
  a seat that names a judgment, a walk an entry filters, a kind with no
  default state, and a row inside the default."
  [eng seat walk row-id]
  (let [kind (some-> (get walk "kind") str not-empty)
        rdef (when kind (get (inv/resources eng) (keyword kind)))
        dflt (some-> (get-in rdef [:default-filters :state]) str not-empty)]
    (when (and dflt
               (nil? (seats/walk-filter seat))
               (nil? (seat-guards/walk-filters seat))
               (not (some-> (get-in seat [:data :judgment]) str not-empty)))
      (when-some [state (some-> (row-of eng (keyword kind) row-id) :state name)]
        (when-not (contains? (into #{} (map str/trim) (str/split dflt #",")) state)
          (str " Your " kind " walk has no filter, so it runs under " kind
               "'s default state=" dflt "; this row is " state
               ". A filter on the scope entry naming the states you work"
               " would put it in your walk."))))))

(defn named-row-held-note
  "What a sit is told when the row its fire text named is held by
  another open sitting of the seat: it is handed the next free row
  instead, and must not walk the one the text names."
  [row-id]
  (str " The row your fire text names, " row-id ", is held by another "
       "open sitting. Walk the row below instead of it."))

(defn with-failing-change
  "The walk with the named ticket's row marked (`seats/failing-change-mark`,
  ticket c91b3af9): the fire named the seat's own failing change, so
  the row says so, and which change, pull request and head it is."
  [walk mark ticket-id]
  (if (and mark ticket-id (seq (get walk "rows")))
    (update walk "rows"
            (fn [rows]
              (mapv #(if (= (str ticket-id) (str (get % "id"))) (merge % mark) %)
                    rows)))
    walk))

(defn held-by-note
  "What a fire that opened no sitting is told: every row is held, by
  the sittings named, and the run stops at once."
  [holders]
  (str "Every open row of your walk is held by another open sitting ("
       (str/join ", " holders) "), so no row was free and no sitting "
       "was opened for you. Say so and stop; there is no sitting to close."))

(defn held-by-sat-note
  "What a fire is told when every row is held and it took the sitting
  its fire opened (ticket 9100c7b9): the run stops at once, and its stop
  hook closes that sitting, so the run's spend still reaches the ledger."
  [holders]
  (str "Every open row of your walk is held by another open sitting ("
       (str/join ", " holders) "), so no row was free. Say so and stop "
       "now; your stop hook closes this sitting."))

(defn held-by-why
  "The `walked_nothing_why` of a fire's sitting whose every row another
  open sitting of the seat holds."
  [holders]
  (str "Every open row of the walk was held by another open sitting of "
       "this seat: " (str/join ", " holders) "."))

;; ── the bench's feedback, the submit sentence and the references ─────

(defn orientation-there?
  "Is the orientation document in the worktree? ONE `read` of the rig,
  with the engine's own hand and through the same caller the prepare
  rides (R-6). An answer means the file is there; a refusal, a dark
  Gate and a rig that faults each mean it is not, because a sit that
  cannot see a document cannot send a seat to read it."
  [gate-rpc repo branch path]
  (boolean
   (try
     (mcp-bench/bench-payload (gate-rpc "tools/call"
                              {:name (gate/bench-tool :read)
                               :arguments {:repo repo :branch branch
                                           :path path}}))
     (catch Exception _ nil))))

(def ^:private feedback-findings-ceiling
  "How many of the rig's findings the sit carries (R-12.31). The rig
  already orders them and already caps its own answer; this is the
  last bound, so one red pipeline with a thousand comments cannot
  fill a seat's context before it has read a row."
  40)

(def ^:private feedback-log-bytes
  "How much of a failed step's log the rig tails for each finding
  (R-12.31). The tail is where the error is, and 2 KiB of it is a
  sentence a seat can act on."
  2048)

(def ^:private finding-remedy
  "The words a finding of the rig's closes with when it names a tool to
  read more with: `read more with bench__log {job: \"gate\"}`. The one
  group is the tool's name, as the power door spells it."
  #"(?s)[\s;,:]*\bread more with (\w+)(?:\s*\{[^{}]*\})?\.?\s*\z")

(defn- message-the-seat-can-follow
  "A finding's message, without its closing remedy when that remedy
  names a tool the seat's grant does not hold (ticket 9b16e766). The
  rig writes the remedy for whoever holds its log read; a seat that
  does not met `No power` when it followed it. `held` is the set of
  tool names in the sit's `bench.tools`, so a seat that is granted the
  tool reads the remedy again with no change here."
  [message held]
  (when-some [said (some-> message str)]
    (let [[remedy tool] (re-find finding-remedy said)]
      (if (and remedy (not (contains? held tool)))
        (subs said 0 (- (count said) (count remedy)))
        said))))

(defn- feedback-said
  "One finding of the rig's, as the sit carries it: the source, the
  severity, the message, and the locations when the rig named any.
  THE ENGINE ADDS NOTHING AND JUDGES NOTHING — a finding is the rig's
  reading of what the submit caused, and the order is the rig's too.
  The one thing it takes away is a closing remedy that names a tool
  outside `held` (`message-the-seat-can-follow`)."
  [finding held]
  (cond-> {"source" (some-> (:source finding) str)
           "severity" (some-> (:severity finding) str)
           "message" (message-the-seat-can-follow (:message finding) held)}
    (seq (:locations finding)) (assoc "locations" (:locations finding))))

(defn- interrupted-finding?
  "Did the rig read this finding as DEAD CI rather than a verdict: a
  run whose jobs were cancelled, timed out or stopped in setup? The rig
  says so twice (severity `interrupted`, and a message that starts
  `ci: interrupted`), and either one is enough."
  [finding]
  (or (= "interrupted" (some-> (:severity finding) str))
      (str/starts-with? (str (:message finding)) "ci: interrupted")))

(def ^:private interrupted-note
  "What the sit says beside feedback that holds an interrupted finding.
  Dead CI is not the code's fault and not the ticket's: the seat asks
  the rig to run the checks again and ends its sitting, and the next
  sitting reads what the new run found."
  (str "The checks did not run to a verdict (ci: interrupted): a job was "
       "cancelled, timed out or stopped in setup. Call the bench's rerun "
       "tool (bench.rerun in bench.tools) with this repo and branch, then "
       "stop the sitting. Do not stall the ticket on dead CI, and do not "
       "change code for it."))

(defn- train-red-finding
  "The finding a merge train's red makes, or nil (ticket 238f45b3). A
  change whose own checks are green and that a train found red carries
  `train_red` (the train's branch and run url) and the train's red
  check names in `failing_checks`; the rig's feedback reads only the
  change's own branch, which is green, so without this the seat sees
  nothing that says why it was sent back."
  [change]
  (when-some [said (some-> (get-in change [:data :train_red]) str not-empty)]
    (let [checks (seq (map str (get-in change [:data :failing_checks])))]
      {"source" "merge-train"
       "severity" "failure"
       "message" (str "merge train red: " said
                      (when checks
                        (str "; failing checks: " (str/join ", " checks))))})))

(defn with-train-red
  "The sit's `feedback` with the change's own train red first among its
  findings; the train's red alone when the rig answered nothing."
  [feedback change]
  (if-some [finding (train-red-finding change)]
    (if feedback
      (update feedback "findings" #(into [finding] %))
      {"findings" [finding] "unavailable" []})
    feedback))

(def ^:private base-red-note
  "What the sit says beside feedback the ops seat read as `base_red`:
  the base branch is red, and this change did not make it so."
  (str "The base branch is red (base_red): this change did not cause it. "
       "Call the bench's pull tool (bench.pull in bench.tools) once the "
       "base is green. Do not stall the ticket on a red base, and do not "
       "change code for it."))

(defn- ops-verdict-finding
  "One classified run as a finding: the check, the ops seat's verdict
  and its remedy."
  [run]
  {"source" "ops-seat"
   "severity" "failure"
   "message" (str (get-in run [:data :check_name]) ": "
                  (get-in run [:data :verdict]) " — "
                  (get-in run [:data :remedy]))})

(defn with-ops-verdicts
  "The sit's `feedback` with the ops seat's verdicts first among its
  findings (ticket 954e9d25): one per `ci_run` of `runs` that is
  classified or reclassified on the change's current head. A run with
  no verdict yet is left out, and with none left the feedback is as it
  was. Every run `infra` carries the rerun note; a `base_red` the pull
  note."
  [feedback change runs]
  (let [head (some-> (get-in change [:data :head_sha]) str not-empty)
        judged (when head
                 (filterv #(and (contains? #{"classified" "reclassified"}
                                           (some-> (:state %) name))
                                (= head (some-> (get-in % [:data :head_sha]) str))
                                (some-> (get-in % [:data :verdict]) str not-empty))
                          runs))
        verdicts (set (map #(str (get-in % [:data :verdict])) judged))]
    (if (empty? judged)
      feedback
      (cond-> (update (or feedback {"findings" [] "unavailable" []})
                      "findings" #(into (mapv ops-verdict-finding judged) %))
        (= #{"infra"} verdicts) (assoc "note" interrupted-note)
        (contains? verdicts "base_red") (assoc "note" base-red-note)))))

(defn ci-runs-of
  "Every `ci_run` row that points at `change`, decoded; nil on an
  engine that serves no ci_run."
  [eng change]
  (when-some [rdef (get (inv/resources eng) :ci_run)]
    (when-some [id (some-> (:id change) str not-empty)]
      (let [st (:storage eng)]
        (->> (store/with-tx st
               (fn [tx]
                 (hooks/hook :factory-ci-runs eng tx id
                             mcp-bench/change-scan-limit)))
             (mapv #(inv/decode-row rdef %)))))))

(defn feedback-of
  "What the submit caused, or nil. ONE `feedback` of the rig, with the
  engine's own hand and through the same caller the prepare rides
  (R-12.31): the pull request the branch opened, the findings the rig
  made of the pipelines, the statuses and the review comments, and the
  parts of the forge it could not reach. A refusal, a dark Gate and a
  rig that faults each mean nil, and the sit then answers no
  `feedback` at all — a seat that cannot see the checks is not a sit
  that refuses. `tools` is the sit's `bench.tools`: a finding names no
  tool that map does not hold."
  [gate-rpc repo branch tools]
  (try
    (when-some [got (mcp-bench/bench-payload
                     (gate-rpc "tools/call"
                               {:name (gate/bench-tool :feedback)
                                :arguments {:repo repo :branch branch
                                            :log_bytes feedback-log-bytes}}))]
      (let [findings (take feedback-findings-ceiling (:findings got))
            held (set (vals tools))]
        (cond-> {"pull_request"
                 (when-some [pr (:pull_request got)]
                   {"number" (:number pr)
                    "state" (some-> (:state pr) str)
                    "url" (some-> (:url pr) str)})
                 "findings" (mapv #(feedback-said % held) findings)
                 "unavailable" (mapv str (:unavailable got))}
          ;; dead CI gets the one instruction that answers it
          (some interrupted-finding? findings)
          (assoc "note" interrupted-note))))
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 bench feedback failed -" (ex-message e)))
      nil)))

(defn submit-means
  "What `submit` does on this seat, in one sentence built from the
  policy. The model reads it and asks for nothing: the sentence says
  whether a push opens a pull request, who merges, how large the
  change may be, and how many rounds it gets. A change whose lane a
  `lane_max_lines` prefix names is told that lane's ceiling (ticket
  bacb84f2), as factory10's `bench/lane-max-lines-of` judges it."
  [policy change]
  (if (nil? policy)
    ;; the same sentence the submit door's first guard says, said
    ;; early: a repository nobody has stated a policy for is a
    ;; repository the bench does not submit to.
    (str "This repository has no policy, so submit refuses. A person "
         "states what submit means here — the branches, the base, the "
         "size ceiling and the rounds — and then the bench works it.")
    (let [d (:data policy)
          opens? (not (false? (:opens_pr d)))
          merges? (not (false? (:auto_merge d)))
          lane (some-> (get-in change [:data :lane]) str not-empty)
          lane-lines (when lane
                       (some (fn [[k n]]
                               (let [p (str/replace (if (keyword? k) (subs (str k) 1) (str k))
                                                    #"/+$" "")]
                                 (when (and (integer? n) (not-empty p)
                                            (or (= lane p)
                                                (str/starts-with? lane (str p "/"))))
                                   n)))
                             (sort-by #(- (count (str (key %))))
                                      (:lane_max_lines d))))
          lines (long (or lane-lines (:max_lines d) 400))
          rounds (long (or (:rounds_per_change d) 3))]
      (str "Submit writes your changes to the branch with your sentence,"
           " then pushes them: "
           (if opens? "the push opens a pull request"
               "the push moves the branch and opens no pull request")
           ", and "
           (if merges? "the gate merges the change when the checks are green"
               "a person merges the change")
           ". The change must have not more than " lines
           " changed lines"
           (when lane-lines
             (str " (the " lane " lane's ceiling, while every path it"
                  " touches stands in that lane)"))
           ", and this change gets " rounds
           (if (= 1 rounds) " round." " rounds.")))))

(def bench-dark-note
  "What the sit says when the rig did not answer. It is a sentence
  about the BENCH and not about the row: the rows are still below, and
  the seat can still read them and write a finding."
  (str "The bench did not answer, so there is no worktree in this "
       "answer. Do not try to read or edit files; work from the rows, "
       "and say what you could not do."))

(defn bench-refused-note
  "What the sit says when the rig REFUSED the prepare (bead
  waymark-fp62.6.3.11). A refusal is an answer, and its reason is the
  cause: the seat reads what the bench would not do and why, stalls
  the change with it, and a person reads the cause on the row."
  [refusal]
  (let [command (or (some-> (:command refusal) str not-empty)
                    (str (:refused refusal)))
        reason (or (some-> (:reason refusal) str not-empty)
                   "the rig gave no reason")]
    (str "The bench refused to open the worktree: " command " — " reason
         (when-not (str/ends-with? reason ".") ".")
         " Work from the rows, and stall the change with this reason.")))

(defn reference-of
  "The repositories this seat may only READ, beside the bench it edits
  (R-12.32) → {\"reference_repos\" […] \"reference_note\" sentence}, or
  nil when its reading entries name none but its own. They are the
  seat's references only when the bench opened on the seat's own
  repository. A reference with no active `repo_policy` has no base
  branch to name, so it is left out of the list and the note says so."
  [eng seat repo]
  (let [named (when-some [rs (mcp-bench/seat-repositories seat)]
                (when (= repo (:repo rs)) (:reference rs)))
        based (keep (fn [r]
                      (when-some [p (mcp-bench/repo-policy-of eng r)]
                        [r (or (some-> (get-in p [:data :base]) str not-empty)
                               mcp-bench/default-base)]))
                    named)
        kept (mapv first based)
        left (vec (remove (set kept) named))]
    (when (seq named)
      {"reference_repos" kept
       "reference_note"
       (str (if (seq kept)
              (str "The repositories in reference_repos are for reading "
                   "only: a bench.find or bench.read call reaches one when "
                   "it names it with `repo` and `branch` ("
                   (str/join ", " (map (fn [[r b]] (str r " on " b)) based))
                   "). Nothing you edit or submit goes there.")
              "This seat names no reference repository you can read.")
            (when (seq left)
              (str " " (str/join ", " left)
                   (if (= 1 (count left)) " has" " have")
                   " no active repo_policy, so "
                   (if (= 1 (count left)) "it is" "they are")
                   " left out.")))})))

;; ── the app's own sections ───────────────────────────────────────────

(def sit-sections-key
  "Where an app hands the sit its own sections, beside the bench: the
  engine opts' `[:services :sit-sections]`, a seq of
  `(fn [ctx seat walk-rows] → nil | {section-key value})`. `ctx` is the
  engine, `seat` the seat row and `walk-rows` the rows the walk handed
  this sitting. Each runs after the walk is chosen, and each map it
  answers is merged into the sit's answer under its own keys — the
  way `bench-of` hands a code seat its worktree, an app can hand a
  seat whatever else it prepared. A key the engine's answer already
  carries stays the engine's.

  A fn that THROWS costs its section and never the sit: it adds
  `{key {:error message}}`, the key being the fn's `:sit-section`
  metadata or, lacking it, `sit_section_<n>` by its place in the seq."
  [:services :sit-sections])

(defn- section-key [k]
  (if (keyword? k) (name k) (str k)))

(defn app-sections
  "Every app section's answer, merged in order (see `sit-sections-key`)."
  [eng seat walk]
  (let [rows (get walk "rows")]
    (reduce
     (fn [acc [n f]]
       (try
         (let [out (f eng seat rows)]
           (cond-> acc
             (map? out) (into (map (fn [[k v]] [(section-key k) v])) out)))
         (catch Exception e
           (binding [*out* *err*]
             (println "waymark10 sit section failed -" (ex-message e)))
           (assoc acc
                  (section-key (or (:sit-section (meta f))
                                   (str "sit_section_" (inc n))))
                  {"error" (or (ex-message e) (str (class e)))}))))
     {}
     (map-indexed vector (get-in eng sit-sections-key)))))

;; ── an org mayor's first sit ─────────────────────────────────────────

(def ^:private org-live-page
  "The most rows of one kind and state an org mayor's first sit reads."
  500)

(def ^:private org-live-whys
  "The held calls whose why an org mayor's first sit names."
  5)

(defn- stored-rows
  "The stored rows of `kind` under `where`, or [] for a kind this
  engine does not serve."
  [eng kind where]
  (if (contains? (inv/resources eng) kind)
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx] (vec (store/query-rows st tx kind where {:limit org-live-page})))))
    []))

(defn- decoded-rows [eng kind where]
  (let [rdef (get (inv/resources eng) kind)]
    (mapv #(inv/decode-row rdef %) (stored-rows eng kind where))))

(defn- row-reader
  "`(fn [kind id] → row)`, the read `seats/domain-name-of` takes."
  [eng]
  (fn [kind id]
    (when-some [rdef (get (inv/resources eng) (keyword (name kind)))]
      (let [st (:storage eng)]
        (some->> (store/with-tx st
                   (fn [tx] (store/load-row st tx (:kind rdef) (str id) {})))
                 (inv/decode-row rdef))))))

(defn org-live-of
  "What an org mayor's FIRST sit leads with (ticket 9b16b765), or nil.
  The seat is an org's when the domain it is in is named by an org_plan
  that is not retired — read by the kind's name, so an engine serving
  no org_plan answers nil — and the sitting is its first when it is the
  seat's only one. The org's held calls are those still held whose
  stamped `domain` is the org's, or, for a call that stores none (held
  before the stamp, ticket afcce783), whose caller or door's author is
  the sitter of a seat in the org's domain; its aged rows are `live-cracks/aged-rows` over the rows whose
  domain is the org's."
  [eng seat sitting now]
  (let [read' (row-reader eng)
        org (seats/domain-name-of read' seat)
        plan (some #(when-not (= "retired" (name (:state %))) %)
                   (decoded-rows eng :org_plan {:name org}))
        mine (when plan (stored-rows eng :sitting {:seat (str (:id seat))}))]
    (when (and plan sitting
               (= [(str (:id sitting))] (mapv (comp str :id) mine)))
      (let [seat-rows (let [active (decoded-rows eng :seat {:state :active})]
                        (store/with-tx (:storage eng)
                          #(cracks/fired-in eng % active)))
            callers (into #{}
                          (comp (filter #(= org (seats/domain-name-of read' %)))
                                (map seats/sitter-id))
                          seat-rows)
            held (filterv (fn [row]
                            (let [d (:data row)
                                  door (:door d)]
                              (if-some [dom (some-> (:domain d) str not-empty)]
                                (= org dom)
                                (or (contains? callers (str (:caller d)))
                                    (contains? callers
                                               (str (or (get door :author)
                                                        (get door "author"))))))))
                          (decoded-rows eng :held_call {:state :held}))
            rdefs (vec (vals (inv/resources eng)))
            rows (into {}
                       (keep (fn [rdef]
                               (let [rs (into []
                                              (comp (filter #(live-cracks/expected-age rdef %))
                                                    (mapcat #(stored-rows eng (:kind rdef)
                                                                          {:state (keyword (name %))}))
                                                    (filter #(= org (live-cracks/row-domain rdef %))))
                                              (:states rdef))]
                                 (when (seq rs) [(keyword (name (:kind rdef))) rs]))))
                       rdefs)
            aged (vec (live-cracks/aged-rows rdefs seat-rows rows now
                                             (:calendar (inv/render-hooks eng))))
            plural (into {} (map (juxt (comp name :kind) :plural)) rdefs)
            href (fn [kind id] (str "/api/" (get plural (str kind)) "/" id))]
        {"org" org
         "line" (str org " is live. Here is what is waiting on you:")
         "held_calls"
         {"count" (count held)
          "first" (mapv (fn [row]
                          {"id" (str (:id row))
                           "why" (str (get-in row [:data :why]))
                           "href" (href "held_call" (:id row))})
                        (take org-live-whys held))}
         "aged"
         {"count" (count aged)
          "by" (mapv (fn [[[kind state] cs]]
                       {"kind" kind "state" state "count" (count cs)
                        "href" (str "/api/" (get plural kind) "?state=" state)})
                     (sort-by key (group-by (juxt :kind :state) aged)))
          "rows" (mapv (fn [c]
                         {"kind" (:kind c) "id" (:id c) "state" (:state c)
                          "since" (:since c) "href" (href (:kind c) (:id c))})
                       aged)}}))))

(defn org-live-note
  "The live line and what waits, in words, ahead of the sit's note."
  [live]
  (let [held (get live "held_calls")
        aged (get live "aged")]
    (str (get live "line") " "
         (get held "count") " held calls"
         (when-some [ws (seq (map #(get % "why") (get held "first")))]
           (str " (" (str/join "; " ws) ")"))
         ", " (get aged "count") " aged rows"
         (when-some [bs (seq (get aged "by"))]
           (str " (" (str/join ", " (map #(str (get % "kind") " in " (get % "state")
                                               ": " (get % "count"))
                                          bs))
                ")"))
         "; `org_live` links each one. ")))
