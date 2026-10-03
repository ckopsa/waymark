(ns waymark10.server.routes.seats
  "What the house ANSWERS about a seat: the ledger route (spec-seat.md
  R-11.3, R-11.3a), the door a session's end reports its bill through
  (§ 12.1, R-12.17), and the little document `waymark_discover` shows
  a sitter under `doors.ask.seat` (R-7.4, R-12.3).

  THE SEVEN ANSWERS ARE ONE CALL. R-11.3 asks seven questions of a
  seat over a window — what did it cost, what did it do, where did it
  hit the law, what did it get wrong, what did each thing cost, which
  model did it, and which tool served the bytes — and R-11.3a says
  they must arrive together, because a person weighing a step down the
  ladder reads them together or not at all. Six of the seven are a
  read over the seat's closed sittings. The other, corrections, is the
  one question no row answers: nothing on a row records that a person
  undid an agent. It is a window over the transition log
  (`store/corrections-by-model`, LAG by (kind, resource_id)), and it
  is the one query this leg had to add.

  THE SEVENTH ANSWER IS A SUM OF MAPS (R-10.6a). Each closed sitting
  carries `served`: tool name → the calls and the bytes the MCP door
  answered. The ledger adds them tool by tool over the window and
  divides the total by the transitions, so a person can see which tool
  ate the bill before deciding which tool to make smaller. Bytes, not
  tokens — the engine does not run the model and will not estimate one.

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

  - THE BUDGET WINDOW IS READ, NEVER STORED, AND IT IS SAID ONCE.
    `spent` is one SUM over the seat's sittings of the last seven
    days at the moment somebody asks — no counter on the row, which
    would be one write per close and one more thing to be wrong. The
    wall itself (`grants/spent-this-week`, R-5.2 step 3) runs the
    SAME aggregate, and both it and this namespace spell the window
    through the one public reader `grants/seat-window-conds`. The
    wall and `spending-conds` count CLOSED AND OPEN both, since
    R-12.27 put a running cost on an open sitting; the LEDGER's
    `window-conds` asks the same reader for closed alone: a ledger
    line is a finished bill, and a sitting still going has not made
    one.
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
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.transcripts :as transcripts]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.webhooks :as webhooks]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.math RoundingMode)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
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
  seat's, started inside the window — the house's one window reader
  (`grants/seat-window-conds`) asked for closed alone (see the ns
  deviations)."
  [seat-id ^Instant since]
  (grants/seat-window-conds seat-id since ["closed"]))

(defn- spending-conds
  "The conds the WALL sums over (R-5.2 step 3, widened by R-12.27):
  this seat's sittings of the window, closed or open. `window-conds`
  above names the ledger's rows — the finished bills five of the six
  answers are computed from — and this names the fuel: an open sitting
  has spent what its last tally says, and a budget that could not see
  it would be a budget an interactive sitting walks through."
  [seat-id ^Instant since]
  (grants/seat-window-conds seat-id since ["closed" "open"]))

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

(defn served-of
  "R-11.3's seventh question: the bytes the MCP door served, by tool,
  added up over one bag of sittings. {tool → {:calls n :bytes b}}, and
  an empty map when no sitting in the window read anything.

  A sitting that never went through the MCP door carries no `served`
  at all, and it adds nothing here rather than adding a row of zeros."
  [rows]
  (reduce (fn [acc r]
            (reduce-kv (fn [a tool line]
                         (-> a
                             (update-in [tool :calls] (fnil + 0)
                                        (long (or (:calls line) 0)))
                             (update-in [tool :bytes] (fnil + 0)
                                        (long (or (:bytes line) 0)))))
                       acc
                       (or (get-in r [:data :served]) {})))
          {}
          rows))

(defn served-bytes
  "Every tool's bytes, added up — the numerator of
  `bytes-per-transition`."
  [served]
  (reduce (fn [n line] (+ (long n) (long (or (:bytes line) 0)))) 0 (vals served)))

(defn bytes-per-transition
  "Bytes served divided by transitions, rounded to a whole byte. The
  companion of `per-transition`, and nil under the same rule: a seat
  that moved nothing has no bytes per thing moved, and zero would be a
  lie in the cheap direction."
  [bytes n]
  (when (pos? (long n))
    (let [^java.math.BigDecimal total (bigdec (long bytes))]
      (.longValueExact (.divide total (bigdec (long n))
                                (int 0) RoundingMode/HALF_UP)))))

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

(defn- instant-of ^Instant [v]
  (cond (instance? Instant v) v
        (some-> v str not-empty) (Instant/parse (str v))
        :else nil))

(defn lifts-at
  "When the week's fuel wall lifts on its own: the moment enough of the
  counted sittings have rolled out of the window that what is left is
  under the limit. `budget-of`'s `resumes_at` is the first moment the
  sum CAN drop; this is the moment it drops far enough, which is what a
  halted sit tells its session. The rows are the wall's own (closed and
  open, `spending-conds`), oldest first. nil when the seat has fuel
  left, and nil when no roll frees enough — a budget of zero, which
  only a person's restate lifts."
  [eng seat ^Instant now]
  (when-some [rdef (get (inv/resources eng) :sitting)]
    (let [st (:storage eng)
          limit (or (get-in seat [:data :budget_usd_per_week]) 0M)
          rows (mapv #(inv/decode-row rdef %)
                     (store/with-tx st
                       (fn [tx]
                         (store/search-rows st tx :sitting
                                            (spending-conds (:id seat)
                                                            (week-ago now))
                                            {:order-by :started_at
                                             :limit sitting-cap}))))
          spent (sum-of rows :cost_usd)]
      (when-not (neg? (compare spent limit))
        (loop [left spent
               [r & more] rows]
          (when r
            (let [left (- left (or (get-in r [:data :cost_usd]) 0M))]
              (if (neg? (compare left limit))
                (when-some [^Instant s (instant-of (get-in r [:data :started_at]))]
                  (.plus s (long window-days) ChronoUnit/DAYS))
                (recur left more)))))))))

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
  "The six summed answers over one bag of sittings plus its share of
  the corrections. `served` is a map rather than a number, because the
  question it answers is WHICH tool, and `bytes_per_transition` is the
  one number that ranks two windows against each other."
  [rows corrections]
  (let [cost (sum-of rows :cost_usd)
        n (count-of rows :transitions)
        served (served-of rows)]
    {:cost_usd cost
     :transitions n
     :refusals (count-of rows :refusals)
     :corrections (long corrections)
     :cost_per_transition (per-transition cost n)
     :served served
     :bytes_per_transition (bytes-per-transition (served-bytes served) n)}))

(defn ledger
  "The seven answers for one seat over one window (R-11.3, R-11.3a).
  `by_model` carries the same per model, over the union of the
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

(def ^:private transcript-key-header
  "`Waymark-Transcript-Key`, read lowercased as ring hands it over. A
  header and not a bearer, for `seat-key-header`'s reason. The
  transcript door reads it, and so do the close and the tally when no
  seat key is presented: the key names one transcript, the transcript
  names its sitting, and a fired run's hook holds nothing else."
  "waymark-transcript-key")

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

(defn- hook-hand
  "The sitter, wearing `seats/hook-role` — so the close handler writes
  `closed_by` \"hook\" and not \"door\". Only the close route wears it."
  [seat]
  (update (sitter-of seat) :roles conj seats/hook-role))

(defn- close-doc
  "What the hook reads back: the row it closed, the counts as
  recorded, and the two numbers the engine counted and has now frozen
  (R-10.6). `cost_usd` is the close's own arithmetic over the model's
  prices at that moment (R-10.4) — the hook reports tokens and learns
  what they cost.

  `last_refusal` rides beside the count when the sitting was refused
  at least once: the problem type, the guard that refused when one
  did, and the moment. The count says how many; this says WHICH law
  the newest one was, so the close can tell whether the last invoke or
  bench write was refused. A sitting nothing refused carries no such
  key, so absence and silence read the same."
  [seat row]
  (let [d (:data row)]
    (cond-> {:waymark "10"
             :kind "sitting_close"
             :sitting (str (:id row))
             :seat (str (:id seat))
             :state (name (:state row))
             :closed_by (:closed_by d)
             :cost_usd (:cost_usd d)
             :input_tokens (:input_tokens d)
             :output_tokens (:output_tokens d)
             :cache_read_tokens (:cache_read_tokens d)
             :cache_write_tokens (:cache_write_tokens d)
             :turns (:turns d)
             :transitions (:transitions d)
             :refusals (:refusals d)}
      (:last_refusal d) (assoc :last_refusal (:last_refusal d)))))

(defn- tally-doc
  "What the Stop hook reads back: the row it tallied, the counts as
  recorded, the running cost at this moment (R-12.27) and the stamp
  the sweep measures idleness from. `state` is there and says `open`,
  because the one thing a hook must be able to tell from this answer
  is that the sitting is still going. `last_refusal` rides beside the
  count the same way the close's does — which law the newest refusal
  was, absent when there was none."
  [seat row]
  (let [d (:data row)]
    (cond-> {:waymark "10"
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
             :tallied_at (some-> (:tallied_at d) str)}
      (:last_refusal d) (assoc :last_refusal (:last_refusal d)))))

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
  (let [no-open #(throw (p/problem
                         :no-open-sitting 409 "No open sitting"
                         {:detail (str "The seat `"
                                       (get-in % [:data :name])
                                       "` has no open sitting.")}))
        tkey (when-not (get-in req [:headers seat-key-header])
               (some-> (get-in req [:headers transcript-key-header])
                       str not-empty))]
    (if tkey
      ;; the transcript key: it names ONE sitting, so there is no
      ;; pairing rule to run. A key that answers no open transcript
      ;; (wrong, dropped at the seal, or none) is the same sentence
      ;; a key no seat answers is.
      (let [transcript (transcripts/transcript-by-key eng tkey)
            seat (or (some->> (get-in transcript [:data :seat]) (seat-row eng))
                     (throw (p/problem :not-found 404 "Not found"
                                       {:detail no-seat})))
            report (report-of req)
            sitting (raw-row eng :sitting (get-in transcript [:data :sitting]))]
        (when-not (= :open (:state sitting))
          (no-open seat))
        [seat report sitting])
      (let [seat (seat-of-key eng req)
            report (report-of req)
            sitting (or (seats/open-sitting-for-seat eng (:id seat)
                                                     (:harness_session report))
                        (no-open seat))]
        [seat report sitting]))))

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
                                    report {:principal (hook-hand seat)}))]
      (router/json-response 200 (close-doc seat closed)))))

;; ── the transcript door (docs/spec-transcript.md § 5) ──────────────

(def ^:private transcript-path
  "The close's and the tally's sibling, one word over (R-5.1)."
  "/api/-/sittings/transcript")

(defn- transcript-body
  "The posted body, gunzipped when the hook says so (R-5.2), as JSON.
  Broken JSON is 422 here, `report-of`'s reason: to a hook there is
  one kind of mistake at this door, its own body."
  [req]
  (let [gzip? (some-> (get-in req [:headers "content-encoding"]) str/lower-case
                      (str/includes? "gzip"))
        b (:body req)
        text (try
               (cond
                 (nil? b) nil
                 (and gzip? (bytes? b))
                 (slurp (java.util.zip.GZIPInputStream.
                         (java.io.ByteArrayInputStream. ^bytes b)) :encoding "UTF-8")
                 (and gzip? (instance? java.io.InputStream b))
                 (slurp (java.util.zip.GZIPInputStream. ^java.io.InputStream b)
                        :encoding "UTF-8")
                 (string? b) b
                 :else (slurp b :encoding "UTF-8"))
               (catch Exception _
                 (invalid! :body "did not decompress; send gzip or plain JSON.")))]
    (try (router/read-body (assoc req :body text))
         (catch Exception _
           (invalid! :body "must be JSON; it did not parse.")))))

(defn- sitting-transcript
  "POST /api/-/sittings/transcript — lines of a sitting's transcript,
  appended (docs/spec-transcript.md R-5).

  ANONYMOUS ON PURPOSE, `sitting-close`'s reasoning verbatim: the key
  in the header is the whole credential, and it answers for one
  transcript and nothing else. The order of the refusals is the order
  of what they cost: the key, the body, then the row
  (`transcripts/upload!`)."
  [eng]
  (fn [req]
    (let [key (get-in req [:headers transcript-key-header])
          _ (when-not (transcripts/transcript-by-key eng key)
              (throw (p/problem :not-found 404 "Not found"
                                {:detail transcripts/no-transcript})))
          body (transcript-body req)]
      (router/json-response 200 (transcripts/upload! eng key body)))))

;; ── the inbox door (docs/spec-seat.md R-12.38) ──────────────────────

(def ^:private inbox-path
  "Where `seats/inbox-url` points: the transcript door's sibling."
  "/api/-/sittings/inbox")

(def ^:private inbox-key-header
  "`Waymark-Inbox-Key`, read lowercased as ring hands it over."
  "waymark-inbox-key")

(def ^:private no-inbox
  "The one sentence every refused key is answered with: a wrong key, a
  key a later sit replaced and a key whose sitting ended look alike."
  "No open sitting answers this inbox key.")

(def ^:private inbox-wait-max
  "The longest `wait`, in seconds: under the idle timeout of the
  proxies a cloud session reaches the engine through."
  25)

(def ^:private inbox-page
  "The log rows one read takes."
  200)

(def ^:private inbox-pages
  "The most pages one answer reads. A tail far behind the log catches
  up across answers, through `Waymark-Inbox-After`, and never holds
  one request for the whole log."
  25)

(def ^:private inbox-tick-ms
  "How often a waiting request reads the log again."
  250)

(def ^:private transcript-kinds
  "The kinds that hold a sitting's own words. The door never serves
  them: a session tailing its inbox must not be fed its transcript."
  #{"transcript" "transcript_entry"})

(defn- whole-param
  "A whole-number query parameter from `lo` to `hi` (nil `hi` for no
  top), nil when absent. Anything else is 422, `since-of`'s reason."
  [req param lo hi]
  (when-some [raw (some-> (get (router/query-params req) param) str str/trim not-empty)]
    (let [n (try (Long/parseLong raw) (catch NumberFormatException _ nil))]
      (when-not (and n (<= (long lo) (long n)) (or (nil? hi) (<= (long n) (long hi))))
        (invalid! param (str "must be a whole number "
                             (if hi (str "from " lo " to " hi) (str "of at least " lo))
                             "; got " (pr-str raw) ".")))
      n)))

(defn- log-after
  "One page of the log after event `after`, oldest first."
  [eng after]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (store/transitions st tx {:since after} {:limit inbox-page})))))

(defn- sitting-start
  "Where a tail with no `after` begins: just before the sitting's first
  event, so it reads from the sitting's start. A sitting the log does
  not know begins at the log's newest event, never at its first."
  [eng sitting]
  (let [st (:storage eng)
        [own newest] (store/with-tx st
                       (fn [tx]
                         [(first (store/transitions st tx {:kind :sitting
                                                           :resource-id (str (:id sitting))}
                                                    {:limit 1}))
                          (first (store/transitions st tx {} {:newest-first true :limit 1}))]))]
    (cond own (dec (long (:id own)))
          newest (long (:id newest))
          :else 0)))

(defn- inbox-match
  "A predicate on a log row. The seat's `inbox.only` names its kind
  and its action (an empty list is every action), the seat's scope
  reads the whole kind, and it is neither a transcript nor this
  sitting's own row.

  THE SCOPE IS READ OFF THE SEAT, not off a visibility resolved for
  the sitting's grant: a seat's grant reads exactly its seat's scope
  (R-5.2), and the door holds no principal whose grant that is. An
  entry narrowed by ids or a filter reads only some rows of its kind,
  so it admits none of that kind's events here."
  [seat sitting]
  (let [only (into {} (map (fn [[k acts]] [(name k) (set (map name acts))]))
                   (get-in seat [:data :inbox :only]))
        readable (into #{}
                       (keep (fn [e]
                               (when (and (nil? (:ids e)) (nil? (:filter e)))
                                 (some-> (:kind e) name))))
                       (get-in seat [:data :scope]))
        sitting-id (str (:id sitting))]
    (fn [t]
      (let [k (name (:kind t))
            acts (get only k)]
        (boolean
         (and acts
              (or (empty? acts) (contains? acts (name (:action t))))
              (contains? readable k)
              (not (contains? transcript-kinds k))
              (not (and (= "sitting" k) (= sitting-id (str (:resource-id t)))))))))))

(defn- inbox-line
  "One event as the door answers it (R-12.38)."
  [t]
  {:kind (name (:kind t))
   :id (str (:resource-id t))
   :action (name (:action t))
   :from (some-> (:from-state t) name)
   :to (some-> (:to-state t) name)
   :summary (:summary t)
   :at (str (:at t))
   :event (:id t)})

(defn- inbox-scan
  "The matching events after `cursor`, read a page at a time until the
  log ends, a page matched, or `inbox-pages` ran out. → [events, the
  last event read]."
  [eng match? cursor]
  (loop [cursor cursor pages 1]
    (let [rows (log-after eng cursor)
          hits (filterv match? rows)
          cursor (if-some [r (last rows)] (:id r) cursor)]
      (if (or (seq hits) (< (count rows) inbox-page) (>= pages inbox-pages))
        [hits cursor]
        (recur cursor (inc pages))))))

(defn- sitting-inbox
  "GET /api/-/sittings/inbox — the events a seat's inbox names, after
  `after` or from the sitting's start, as newline-delimited JSON
  (docs/spec-seat.md R-12.38).

  ANONYMOUS ON PURPOSE, the transcript door's reasoning: the key in the
  header is the whole credential, it answers for one open sitting, and
  it reads only what that sitting's grant reads. `wait` holds the
  request until an event lands or the seconds run out, reading the log
  again each quarter second: a handful of open sittings can afford it,
  and it needs no dispatcher, so it answers the same on every engine."
  [eng]
  (fn [req]
    (let [sitting (seats/inbox-sitting-by-key eng (get-in req [:headers inbox-key-header]))
          seat (some->> (get-in sitting [:data :seat]) (seat-row eng))
          _ (when-not (and sitting seat)
              (throw (p/problem :unauthorized 401 "Unauthorized" {:detail no-inbox})))
          after (whole-param req "after" 0 nil)
          wait (or (whole-param req "wait" 0 inbox-wait-max) 0)
          match? (inbox-match seat sitting)
          deadline (+ (System/nanoTime) (* (long wait) 1000000000))]
      (loop [cursor (or after (sitting-start eng sitting))]
        (let [[hits cursor] (inbox-scan eng match? cursor)]
          (if (and (empty? hits) (< (System/nanoTime) (long deadline)))
            (do (Thread/sleep (long inbox-tick-ms))
                (recur cursor))
            {:status 200
             :headers {"Content-Type" "application/x-ndjson"
                       "Waymark-Inbox-After" (str cursor)}
             :body (apply str (map #(str (wire/write-json (inbox-line %)) "\n") hits))}))))))

;; ── the key check door (docs/spec-seat.md § 16) ─────────────────────

(def ^:private verify-path
  "A question about a seat's key, asked by a service. It is under the
  seats' own word and not the sittings': it opens no sitting."
  "/api/-/seats/verify")

(def ^:private service-secret-header
  "`Waymark-Subscription-Secret`, read lowercased as ring hands it
  over. A header and not a bearer, for `seat-key-header`'s reason."
  "waymark-subscription-secret")

(def ^:private no-service
  "The one sentence every refused caller is answered with: no header,
  a secret no subscription holds and a subscription that is paused,
  failed or revoked look alike."
  "No active subscription answers this secret.")

(def verify-per-minute
  "How many keys one subscription may ask about in one clock minute. A
  feed checks a key once per connection, so this is far above an
  honest caller and far below what walking 128 bits would take."
  120)

(def ^:private verify-key-max
  "The longest key the door reads. A seat key is 22 characters."
  200)

(defonce ^:private
  ^{:doc "Subscription id → [clock minute, asks in it]. One entry per
  caller, written over each minute, so it is bounded by the
  subscriptions. It is this process's count: two engines each allow
  the limit."}
  verify-counts
  (atom {}))

(defn- service-of
  "The ACTIVE subscription whose signing secret is exactly the one this
  request presents, or nil. `seats/seat-by-key`'s read and its reason:
  every active subscription is read and compared in constant time, and
  a request with no header never reaches storage. A subscription that
  declares no secret matches nothing. The secret is the key the
  deliverer signs with (`webhooks/signing-key`): for a subscription
  whose `secret` is the id of a secret row, that is the row's value
  now and never the id, and a row that holds no value matches nothing."
  [eng req]
  (when-some [secret (some-> (get-in req [:headers service-secret-header])
                             str not-empty)]
    (when (get (inv/resources eng) :subscription)
      (let [wanted (.getBytes (str secret) StandardCharsets/UTF_8)
            ;; the rows leave the transaction whole: a referenced
            ;; secret is read by its own
            active (store/with-tx (:storage eng)
                     (fn [tx]
                       (vec (store/query-rows (:storage eng) tx :subscription
                                              {:state :active} {:limit 500}))))]
        (->> active
             (filter (fn [row]
                       ;; a waiting reference answers a keyword, and an
                       ;; unsigned subscription nil: neither is a key
                       (let [held (webhooks/signing-key eng row)]
                         (when (and (string? held) (seq held))
                           (MessageDigest/isEqual
                            wanted
                            (.getBytes ^String held StandardCharsets/UTF_8))))))
             first)))))

(defn- verify-asks
  "This caller's asks in the clock minute `minute`, this one counted."
  [sub minute]
  (let [id (str (:id sub))
        counts (swap! verify-counts update id
                      (fn [[m n]]
                        (if (= m minute) [m (inc (long n))] [minute 1])))]
    (long (second (get counts id)))))

(defn- seat-verify
  "POST /api/-/seats/verify — is this seat key live, and which seat
  does it name (docs/spec-seat.md § 16).

  THE CALLER IS A SERVICE, AND IT PROVES ITSELF FIRST. The secret of
  an active subscription rides `Waymark-Subscription-Secret`; without
  one the door answers 401 and reads no seat, so it is not a key
  oracle for the public. Each subscription has `verify-per-minute`
  asks a minute and is answered 429 past them.

  THE ANSWER IS TWO FIELDS. `live` and the seat's `name`, and nothing
  else of the row. A key that matches nothing, a key the seat revoked
  and a parked seat's key all answer `{live: false, seat: null}` off
  the same read: `seats/seat-by-key` compares every active seat in
  constant time whichever it is.

  A FEED TOKEN ANSWERS AS THE SEAT'S KEY DOES (R-16.7). The token a sit
  answered for the seat's `feed_url` is live while its sitting is open
  and its end has not come (`seats/feed-seat-by-token`); after that it
  answers the same `{live: false, seat: null}`. No other door reads it.

  IT OPENS NOTHING. No sitting is born, no grant is minted and no row
  is written: the door is two reads."
  [eng]
  (fn [req]
    (let [sub (or (service-of eng req)
                  (throw (p/problem :unauthorized 401 "Unauthorized"
                                    {:detail no-service})))
          epoch (.getEpochSecond (now-of eng))]
      (if (> (long (verify-asks sub (quot epoch 60)))
             (long verify-per-minute))
        (router/json-response
         429
         {:detail (str "This subscription asked about more than "
                       verify-per-minute " keys in one minute.")}
         "application/json"
         {"Retry-After" (str (- 60 (mod epoch 60)))})
        (let [body (try (router/read-body req)
                        (catch Exception _
                          (invalid! :body "must be JSON; it did not parse.")))
              key (when (map? body) (:key body))
              _ (when-not (and (string? key)
                               (<= 1 (count key) (long verify-key-max)))
                  (invalid! :key (str "is required: the seat key to check, a "
                                      "string of at most " verify-key-max
                                      " characters.")))
              seat (or (seats/seat-by-key eng key)
                       (seats/feed-seat-by-token eng key))]
          (router/json-response
           200 {:live (some? seat)
                :seat (some-> seat (get-in [:data :name]))}))))))

(defn routes [eng]
  {:module :seats
   :static [["/api/seats/:id/ledger" {:get (ledger-doc eng)}]
            [close-path {:post (sitting-close eng)}]
            [tally-path {:post (sitting-tally eng)}]
            [transcript-path {:post (sitting-transcript eng)}]
            [inbox-path {:get (sitting-inbox eng)}]
            [verify-path {:post (seat-verify eng)}]]})

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
