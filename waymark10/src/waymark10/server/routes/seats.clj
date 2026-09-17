(ns waymark10.server.routes.seats
  "What the house ANSWERS about a seat: the ledger route (spec-seat.md
  R-11.3, R-11.3a), the door a session's end reports its bill through
  (§ 12.1, R-12.17), and the little document `waymark_discover` shows
  a sitter under `doors.ask.seat` (R-7.4, R-12.3).

  THE SIX ANSWERS ARE ONE CALL. R-11.3 asks six questions of a seat
  over a window — what did it cost, what did it do, where did it hit
  the law, what did it get wrong, what did each thing cost, which
  model did it — and R-11.3a says they must arrive together, because
  a person weighing a step down the ladder reads them together or not
  at all. Five of the six are a read over the seat's closed sittings.
  The sixth, corrections, is the one question no row answers: nothing
  on a row records that a person undid an agent. It is a window over
  the transition log (`store/corrections-by-model`, LAG by (kind,
  resource_id)), and it is the one query this leg had to add.

  THE ANSWERS ARE DATA, NOT A VERDICT (R-11.4). The audit is the
  truth; these numbers point a person at the sittings to read and
  prove nothing on their own. Nothing here decides anything, and
  nothing in the engine reads this route.

  ── who may read it ────────────────────────────────────────────────

  A person, unscoped, reads any seat's ledger. A sitter reads its own
  seat's, and nobody else sees the address at all — the seat is
  named, so the refusal is the concealment 404 every other door
  serves, never a 403 that would say the row exists.

  ── the discover half ──────────────────────────────────────────────

  `seat-door` lives here rather than in `server/mcp` because it and
  the ledger read the same seat, the same rolling week of sittings
  and the same arithmetic; two copies of a budget window would be two
  answers to R-5.2's third wall, correct on the day they were written.
  The MCP namespace calls it; this namespace mounts the route.

  Recorded deviations (each a sentence):

  - THE BUDGET WINDOW IS READ, NEVER STORED, AND IT IS SAID TWICE.
    `spent` is one SUM over the seat's sittings of the last seven
    days at the moment somebody asks — no counter on the row, which
    would be one write per close and one more thing to be wrong. The
    wall itself (`grants/spent-this-week`, R-5.2 step 3) runs the
    SAME aggregate over the SAME conds, and that function is private
    to a namespace this wave does not own, so `spending-conds` below
    is a second spelling of one arithmetic. They agree today because
    they are the same store call with the same three conds — CLOSED
    AND OPEN both, since R-12.27 put a running cost on an open
    sitting; the follow-up is one public reader both call, and it
    belongs beside the seat rather than beside either caller. The
    LEDGER keeps its own conds (`window-conds`, closed alone): a
    ledger line is a finished bill, and a sitting still going has not
    made one.
  - `by_model` KEYS ON THE MODEL ROW, AND A CORRECTION KEYS ON A
    CLAIM. A sitting carries a `model` ref; a transition's actor
    carries the session's model CLAIM, an API identifier
    (invoke/actor-map, R-9.5). So a correction's model is resolved
    through the `model` collection by name, exactly as a sitting's
    birth resolves it, and a claim naming no row on this engine lands
    in the `null` bucket rather than inventing one.
  - THE WINDOW IS FILTERED IN SQL AND CAPPED IN CODE. `sitting-cap`
    bounds a single answer; a seat whose cadence and window would
    exceed it is answering about more sittings than a person is going
    to read, and the honest fix is a shorter `since`, not a longer
    page."
  (:require [clojure.string :as str]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.math RoundingMode)
           (java.time Instant)
           (java.time.temporal ChronoUnit)))

(set! *warn-on-reflection* true)

(def window-days
  "The rolling week R-5.2 spends against and R-11.3's default window.
  Seven, and the same seven in both places: a ledger whose default
  disagreed with the wall would report a spend the wall did not see."
  7)

(def sitting-cap
  "The most sittings one answer reads. A seat waking hourly fills a
  week with 168; this is room for a month of that and a floor under
  the arithmetic either way."
  5000)

;; ── reading the rows ────────────────────────────────────────────────

(defn- now-of ^Instant [eng] ((:now-fn eng)))

(defn- week-ago ^Instant [^Instant now]
  (.minus now (long window-days) ChronoUnit/DAYS))

(defn- raw-row
  "A stored row of a kind this engine may not serve at all, or nil."
  [eng kind id]
  (when (and id (contains? (inv/resources eng) kind))
    (store/with-tx (:storage eng)
      (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))))

(defn seat-row
  "The seat as STORED — the JSON document, not the decoded row.

  Deliberate, and the reason is the wire: `halt` carries an instant,
  and a decoded instant handed to `write-json` is a Java object the
  wire mapper has no writer for. The stored document is already
  exactly what an envelope would serve, decimals and all
  (`:encode/wire` is identity for a decimal, an ISO string for an
  instant), so the document IS the answer. The SITTINGS are decoded,
  because their `started_at` has to be compared and ordered, and ISO
  strings of differing precision do not sort."
  [eng seat-id]
  (raw-row eng :seat seat-id))

(defn seat-of
  "The seat this request is sitting in, or nil — read off the
  visibility, where `grants/visibility` already resolved it (R-5.2).

  `[:seat :id]` and not `((:row? vis) :seat id)`: at a wall the
  resolve empties the effective scope, and the sitter's own read of
  its seat row goes with it. The ledger is exactly what a walled
  sitter needs — it is how a firing says WHY it is stopping — so the
  door reads the seat the request cited, not the scope the wall left."
  [vis]
  (some-> (get-in vis [:seat :id]) str not-empty))

(defn- window-conds
  "The three conds that name a seat's counted sittings: closed, this
  seat's, started inside the window. `grants/spent-this-week` — the
  wall itself — sums over exactly these, and saying them once here is
  the closest this file can get to saying them once in the house (see
  the ns deviations)."
  [seat-id ^Instant since]
  [{:target :state :op := :value "closed"}
   {:target :data :field :seat :cast "text" :op := :value (str seat-id)}
   {:target :data :field :started_at :cast "timestamptz" :op :>=
    :value (str since)}])

(defn- spending-conds
  "The conds the WALL sums over (R-5.2 step 3, widened by R-12.27):
  this seat's sittings of the window, closed or open. `window-conds`
  above names the ledger's rows — the finished bills five of the six
  answers are computed from — and this names the fuel: an open sitting
  has spent what its last tally says, and a budget that could not see
  it would be a budget an interactive sitting walks through."
  [seat-id ^Instant since]
  (into [{:target :state :op :in :values ["closed" "open"]}]
        (rest (window-conds seat-id since))))

(defn closed-sittings
  "The seat's closed sittings started at or after `since`, decoded,
  oldest first — the rows five of the six answers are summed over.
  DECODED, because `started_at` has to be compared and ordered and
  ISO strings of differing precision do not sort."
  [eng seat-id ^Instant since]
  (if-some [rdef (get (inv/resources eng) :sitting)]
    (let [st (:storage eng)]
      (mapv #(inv/decode-row rdef %)
            (store/with-tx st
              (fn [tx]
                (store/search-rows st tx :sitting
                                   (window-conds seat-id since)
                                   {:order-by :started_at
                                    :limit sitting-cap})))))
    []))

;; ── the arithmetic ──────────────────────────────────────────────────

(defn- sum-of [rows field]
  (reduce (fn [acc r] (+ acc (or (get-in r [:data field]) 0M))) 0M rows))

(defn- count-of [rows field]
  (reduce (fn [acc r] (+ acc (long (or (get-in r [:data field]) 0)))) 0 rows))

(defn per-transition
  "Cost divided by transitions — R-11.3's fifth question. Nil when
  there were no transitions: a seat that moved nothing has no price
  per thing moved, and zero would be a lie in the cheap direction."
  [^java.math.BigDecimal cost n]
  (when (pos? (long n))
    (.divide cost (bigdec n) (int seats/cost-scale) RoundingMode/HALF_UP)))

(defn budget-of
  "R-5.2's third wall, read rather than stored: what this seat has
  spent in the rolling week, what it may spend, and when the wall
  lifts.

  `spent` is ONE aggregate over the same conds the resolve's own wall
  runs — CLOSED AND OPEN both, since R-12.27 (an open sitting carries
  a running cost from its last tally) — so discover cannot report a
  figure the wall disagrees with. `resumes_at` is when the OLDEST
  counted sitting falls out of the window, the first moment the sum
  can drop, and it is nil unless the seat is actually at its limit: a
  seat with fuel left is not waiting for anything."
  [eng seat ^Instant now]
  (let [st (:storage eng)
        since (week-ago now)
        conds (window-conds (:id seat) since)
        spent (or (when (contains? (inv/resources eng) :sitting)
                    (store/with-tx st
                      (fn [tx] (store/sum-matching st tx :sitting :cost_usd
                                                   (spending-conds (:id seat)
                                                                   since)))))
                  0M)
        limit (or (get-in seat [:data :budget_usd_per_week]) 0M)
        oldest (when (contains? (inv/resources eng) :sitting)
                 (some-> (first (store/with-tx st
                                  (fn [tx]
                                    (store/search-rows st tx :sitting conds
                                                       {:order-by :started_at
                                                        :limit 1}))))
                         :data :started_at str not-empty))]
    {:spent spent
     :limit limit
     :resumes_at (when (and oldest (not (neg? (compare spent limit))))
                   (.plus (Instant/parse oldest)
                          (long window-days) ChronoUnit/DAYS))}))

;; ── corrections ─────────────────────────────────────────────────────

(defn- model-id-of-claim
  "A session's model claim (an API identifier) → the `model` row that
  prices it, or nil. The sitting's own birth resolves it exactly this
  way (`seats/resolve-model`), so a correction and a sitting agree
  about which rung of the ladder they belong to."
  [eng claim]
  (when-some [claim (some-> claim str not-empty)]
    (when (contains? (inv/resources eng) :model)
      (let [st (:storage eng)]
        (some-> (first (store/with-tx st
                         (fn [tx]
                           (store/query-rows st tx :model {:name claim}
                                             {:limit 1}))))
                :id str)))))

(defn corrections-of
  "R-11.3's fourth question, by model row: {model-id-or-nil → count}.
  A correction is a person's transition on a row whose immediately
  previous transition was written by one of the members that sat this
  seat in the window — the store's LAG walk — attributed to the model
  that written transition declared."
  [eng rows ^Instant since]
  (let [members (into #{} (keep #(some-> (get-in % [:data :member]) str)) rows)
        ;; the framework's own kinds are never corrections: a person's
        ;; approve after a sitter's ask, or a close after a sitter's
        ;; sitting create, is bookkeeping, not a reversal of a verdict
        excluded (into [] (keep (fn [[k rdef]] (when (= :system (:nav rdef)) (name k))))
                       (inv/resources eng))
        st (:storage eng)]
    (if (empty? members)
      {}
      ;; the walk finishes before the claims are resolved: each
      ;; resolution is its own short read, and nesting one inside the
      ;; window function's transaction would hold a connection for the
      ;; length of the whole answer
      (let [found (store/with-tx st
                    (fn [tx]
                      (store/corrections-by-model st tx (vec members) since excluded)))]
        (reduce (fn [acc {:keys [model n]}]
                  (update acc (model-id-of-claim eng model) (fnil + 0) (long n)))
                {}
                found)))))

;; ── the ledger document ─────────────────────────────────────────────

(defn ledger-path
  "The address R-11.3a pins, and the one `doors.ask.seat` names."
  [seat-id]
  (str "/api/seats/" seat-id "/ledger"))

(defn- answers
  "The five summed answers over one bag of sittings plus its share of
  the corrections."
  [rows corrections]
  (let [cost (sum-of rows :cost_usd)
        n (count-of rows :transitions)]
    {:cost_usd cost
     :transitions n
     :refusals (count-of rows :refusals)
     :corrections (long corrections)
     :cost_per_transition (per-transition cost n)}))

(defn ledger
  "The six answers for one seat over one window (R-11.3, R-11.3a).
  `by_model` carries the same four per model, over the union of the
  models that sat and the models that were corrected — a model that
  sat and was never corrected still has a row, and so does one that
  was corrected after its last sitting fell out of the window."
  [eng seat ^Instant since ^Instant until]
  (let [rows (closed-sittings eng (:id seat) since)
        corrections (corrections-of eng rows since)
        by-model (group-by #(some-> (get-in % [:data :model]) str) rows)
        models (sort-by str (distinct (concat (keys by-model) (keys corrections))))]
    (merge {:waymark "10"
            :kind "seat_ledger"
            :seat (str (:id seat))
            :self (str (ledger-path (:id seat)) "?since=" since)
            :window {:since (str since) :until (str until)}}
           (answers rows (reduce + 0 (vals corrections)))
           {:by_model (mapv (fn [m]
                              (assoc (answers (get by-model m []) (get corrections m 0))
                                     :model m))
                            models)})))

;; ── the route ───────────────────────────────────────────────────────

(defn- since-of
  "`?since=<instant>`, defaulting to a week back. An unreadable value
  is refused rather than quietly rounded to the default — a person
  reading a ledger must know which window they are reading."
  ^Instant [req ^Instant now]
  (if-some [raw (some-> (get (router/query-params req) "since")
                        str/trim not-empty)]
    (try (Instant/parse raw)
         (catch Exception _
           (throw (p/problem :invalid-params 422 "Invalid parameters"
                             {:detail (str "since must be an RFC 3339 instant, "
                                           "such as " (str (week-ago now))
                                           "; got " (pr-str raw) ".")}))))
    (week-ago now)))

(defn- may-read?
  "A person (unscoped) reads any seat's ledger; a sitter reads the
  seat its presented grant cites. Anybody else is told the address
  does not exist, which is what every other concealed door says."
  [req seat-id]
  (if-some [vis (router/visibility-of req)]
    (= (str seat-id) (seat-of vis))
    true))

(defn- ledger-doc [eng]
  (fn [{{:keys [id]} :path-params :as req}]
    (let [seat (seat-row eng id)]
      (when-not (and seat (may-read? req id))
        (throw (p/not-found :seat id)))
      (let [now (now-of eng)]
        (router/json-response 200 (ledger eng seat (since-of req now) now))))))

;; ── the session-end door (spec-seat.md § 12.1, R-12.17) ─────────────
;;
;; A keyed sitter session opens a sitting when it sits (R-12.15) and
;; the router counts against it — and until this door existed nothing
;; ever closed it. The boot sweep abandoned it two cadences later and
;; its cost went unrecorded, which is the one thing a seat is for.
;;
;; What ends a run is the HARNESS, not the engine: the session stops,
;; its hook sums the transcript's usage and posts it here. So the door
;; is shaped for a hook and nothing else — one POST, one credential,
;; five counts, no bearer, no etag, no idempotency key.

(def ^:private close-path
  "The address the hook posts to — the engine's own `/api/-/…` shape
  (/api/-/mcp, /api/-/feed), one segment longer. The literal \"-\"
  second segment is what keeps it out of the plural grammar
  altogether, and the static bucket is where the engine's doors about
  itself are mounted."
  "/api/-/sittings/close")

(def ^:private tally-path
  "The close's sibling (R-12.25), one word over. An interactive
  session raises a Stop event every turn, so its hook posts HERE on
  each one and posts the close once, at the end — same credential,
  same body, same pairing rule."
  "/api/-/sittings/tally")

(def ^:private seat-key-header
  "MCP's Mcp-Session-Id precedent: ring lowercases what a client
  sends, so the READ is this and the contract's spelling is
  `Waymark-Seat-Key`.

  NOT an Authorization bearer, deliberately: the identity layer would
  try to parse one as a token and refuse the request before this
  handler saw it. The key is not an identity — it is the seat's, and
  the request's resolved principal is ignored."
  "waymark-seat-key")

(def ^:private no-seat
  "UNIFORM, and `sit-no-seat`'s sentence verbatim (server/mcp): a key
  that matches nothing, a key the seat has since revoked, a key that
  is not a key at all and a seat that has been parked all answer this
  one sentence, and a missing header answers it too. Saying which
  would turn the door into an oracle over the house's offices."
  "No seat answers this key.")

(def ^:private count-fields
  "The five R-10.5 numbers a report carries. The engine never
  estimates a token: every one of these is required, and a report that
  omits one is a report the door cannot cost."
  [:input_tokens :output_tokens :cache_read_tokens :cache_write_tokens :turns])

(defn- invalid!
  "The 422 `since-of` serves, one field over — a hook reading its own
  refusal has to know WHICH number it got wrong."
  [field detail]
  (throw (p/problem :invalid-params 422 "Invalid parameters"
                    {:detail (str (name field) " " detail)})))

(defn- report-of
  "The posted body, judged before a row is read.

  The counts are judged HERE as well as by the close's own input
  schema, and the order is the reason: a malformed report must answer
  422 whether or not the seat happens to have an open sitting, and the
  409 below is decided by a read. The declaration remains the
  authority — it refuses the same values at the invoke — and this is
  the same law said early enough to be said first.

  Broken JSON is 422 here rather than `read-body`'s 400 for the same
  reason the counts are: to a hook there is one kind of mistake at
  this door, its own body, and one status for it."
  [req]
  (let [body (try (router/read-body req)
                  (catch Exception _
                    (invalid! :body "must be JSON; it did not parse.")))]
    (when-not (map? body)
      (invalid! :body (str "must be a JSON object carrying "
                           (str/join ", " (map name count-fields)) ".")))
    (doseq [f count-fields]
      (let [v (get body f)]
        (when (nil? v)
          (invalid! f "is required: the harness's exact count, a whole number."))
        (when-not (and (int? v) (not (neg? v)))
          (invalid! f (str "must be a whole number of zero or more; got "
                           (pr-str v) ".")))))
    (doseq [[f limit] [[:note 240] [:harness_session 128]]]
      (when-some [v (get body f)]
        (when-not (and (string? v) (<= (count v) limit))
          (invalid! f (str "must be a string of at most " limit
                           " characters.")))))
    ;; closed, like the action's own input: a misspelled count is a
    ;; count nobody reported, and a report that swallowed it would
    ;; cost a wake at zero and say nothing
    (when-some [extra (seq (sort (map name (keys (apply dissoc body
                                                        :note :harness_session
                                                        count-fields)))))]
      (invalid! (first extra)
                (str "is not a field of this report, which carries "
                     (str/join ", " (map name count-fields))
                     ", and optionally note and harness_session.")))
    body))

(defn- seat-of-key
  "The active seat whose sitter key this request presents, or the one
  sentence. `seats/seat-by-key` compares in constant time, so a caller
  cannot walk the key off the clock; a request with no header never
  reaches storage at all, and answers the same way."
  [eng req]
  (or (seats/seat-by-key eng (get-in req [:headers seat-key-header]))
      (throw (p/problem :not-found 404 "Not found" {:detail no-seat}))))

(defn- sitter-of
  "The principal the close is written as: the seat's sitter, exactly
  as `mcp/sit` builds it (`seats/sitter-id` + `sitter-display`).

  THE HOOK ACTS FOR THE SITTER, so the log reads the sitter. The bill
  is the office's, the transitions it froze were the office's, and a
  ledger whose closes were written by a system actor would name the
  engine as the one thing in the seat's history that was not the seat.
  Nothing refuses it: `close` declares no guards, and the kind's
  `:own-surface {:by :member}` is a projection rule rather than a
  gate — this call is `inv/invoke!` with a principal and no presented
  leash, the `seat-halt!` posture."
  [seat]
  (t/principal {:id (seats/sitter-id seat)
                :type :agent
                :display (seats/sitter-display seat)}))

(defn- close-doc
  "What the hook reads back: the row it closed, the counts as
  recorded, and the two numbers the engine counted and has now frozen
  (R-10.6). `cost_usd` is the close's own arithmetic over the model's
  prices at that moment (R-10.4) — the hook reports tokens and learns
  what they cost."
  [seat row]
  (let [d (:data row)]
    {:waymark "10"
     :kind "sitting_close"
     :sitting (str (:id row))
     :seat (str (:id seat))
     :state (name (:state row))
     :cost_usd (:cost_usd d)
     :input_tokens (:input_tokens d)
     :output_tokens (:output_tokens d)
     :cache_read_tokens (:cache_read_tokens d)
     :cache_write_tokens (:cache_write_tokens d)
     :turns (:turns d)
     :transitions (:transitions d)
     :refusals (:refusals d)}))

(defn- tally-doc
  "What the Stop hook reads back: the row it tallied, the counts as
  recorded, the running cost at this moment (R-12.27) and the stamp
  the sweep measures idleness from. `state` is there and says `open`,
  because the one thing a hook must be able to tell from this answer
  is that the sitting is still going."
  [seat row]
  (let [d (:data row)]
    {:waymark "10"
     :kind "sitting_tally"
     :sitting (str (:id row))
     :seat (str (:id seat))
     :state (name (:state row))
     :cost_usd (:cost_usd d)
     :input_tokens (:input_tokens d)
     :output_tokens (:output_tokens d)
     :cache_read_tokens (:cache_read_tokens d)
     :cache_write_tokens (:cache_write_tokens d)
     :turns (:turns d)
     :transitions (:transitions d)
     :refusals (:refusals d)
     :tallied_at (some-> (:tallied_at d) str)}))

(defn- paired
  "The three refusals in the order of what they cost, and the row they
  land on: `[seat report sitting]`.

  The key first (a request that answers for no seat learns nothing
  else), the body next (a malformed report is wrong whatever the seat
  holds), the open sitting last, because finding it is a read. Both
  doors of § 12.1 ask exactly this and pair exactly this way
  (R-12.17), so it is asked once: a tally that found its sitting by a
  different rule than the close would tally one run and close
  another."
  [eng req]
  (let [seat (seat-of-key eng req)
        report (report-of req)
        sitting (or (seats/open-sitting-for-seat eng (:id seat)
                                                 (:harness_session report))
                    (throw (p/problem
                            :no-open-sitting 409 "No open sitting"
                            {:detail (str "The seat `"
                                          (get-in seat [:data :name])
                                          "` has no open sitting.")})))]
    [seat report sitting]))

(defn- sitting-tally
  "POST /api/-/sittings/tally — what this sitting has spent so far
  (R-12.25, R-12.27).

  The close's twin in every way but the ending. A fired run raises one
  Stop event and its hook closes; an interactive session raises one
  per turn, and a hook that closed there would end the sitting after
  the person's first message. So it tallies: the same counts onto the
  same row, the sitting still open, and a running cost the week's wall
  can see.

  CUMULATIVE, so a replay is harmless — the hook sums the whole
  transcript every turn, and the newest numbers replace the last. The
  answer carries them back with the stamp, which is what the sweep
  reads when nobody ever posts the close."
  [eng]
  (fn [req]
    (let [[seat report sitting] (paired eng req)
          tallied (:row (inv/invoke! eng :sitting (str (:id sitting)) :tally
                                     report {:principal (sitter-of seat)}))]
      (router/json-response 200 (tally-doc seat tallied)))))

(defn- sitting-close
  "POST /api/-/sittings/close — the end of a wake, reported (R-12.17).

  ANONYMOUS ON PURPOSE, and nothing in the chain has to bend for it: a
  request with no bearer resolves to `t/anonymous` (router's
  `dev-principal`), `members/gate!` passes an anonymous principal
  untouched because a system or unnamed actor is not a member, and
  `grants/unscoped-visibility` answers nil for anybody who is not an
  agent — so `router/visibility-of` is nil, none of the concealment
  checks bite, and the static bucket wraps no auth of its own around
  what it mounts. The key in the header is the whole credential, and
  it is the seat's rather than a person's, which is exactly what a
  hook running after its session has ended can still present.

  The order of the three refusals is the order of what they cost: the
  key first (a request that answers for no seat learns nothing else),
  the body next (a malformed report is wrong whatever the seat holds),
  the open sitting last, because finding it is a read. A second report
  after a close lands on that last one — 409, by design: the ending is
  written once and the hook that retries is told the bill is in.

  A WALLED SEAT STILL CLOSES. R-5.2's three walls scope a seat grant
  to nothing; this door presents no grant, so a seat that reached its
  budget mid-wake can still record what that wake cost — which is the
  number the wall was about."
  [eng]
  (fn [req]
    (let [[seat report sitting] (paired eng req)
          closed (:row (inv/invoke! eng :sitting (str (:id sitting)) :close
                                    report {:principal (sitter-of seat)}))]
      (router/json-response 200 (close-doc seat closed)))))

(defn routes [eng]
  {:module :seats
   :static [["/api/seats/:id/ledger" {:get (ledger-doc eng)}]
            [close-path {:post (sitting-close eng)}]
            [tally-path {:post (sitting-tally eng)}]]})

;; ── what discover shows a sitter (R-7.4, R-12.3) ────────────────────

(defn seat-door
  "`doors.ask.seat` for a principal whose request resolved a seat, or
  nil for every other caller (R-7.4).

  A firing reads this FIRST (R-12.4), so the two things that stop a
  session — the halt and a parked state — are in it, beside the fuel
  it has left, the scope entries the boot sweep refused, whatever the
  read-back found drifting in the provider's copy of the schedule
  (R-12.3), and the address of the ledger that says what the seat has
  been costing. Every key is present whatever its value: a sitter
  reading `halt` must be able to tell 'not halted' from 'this
  document does not say'."
  [eng vis]
  (when-some [seat-id (seat-of vis)]
    (when-some [seat (seat-row eng seat-id)]
      (let [b (budget-of eng seat (now-of eng))]
        {:name (get-in seat [:data :name])
         :state (name (:state seat))
         :standing_ttl_seconds (get-in seat [:data :standing_ttl_seconds])
         :stale (get-in seat [:data :stale])
         :halt (get-in seat [:data :halt])
         :budget {:spent (:spent b)
                  :limit (:limit b)
                  :resumes_at (some-> (:resumes_at b) str)}
         :drift (some-> (schedules/schedule-for-seat eng seat-id)
                        :data :drift)
         :ledger (ledger-path seat-id)}))))
