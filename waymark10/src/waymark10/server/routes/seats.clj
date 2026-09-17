(ns waymark10.server.routes.seats
  "What the house ANSWERS about a seat: the ledger route (spec-seat.md
  R-11.3, R-11.3a) and the little document `waymark_discover` shows a
  sitter under `doors.ask.seat` (R-7.4, R-12.3).

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
    `spent` is one SUM over the seat's closed sittings of the last
    seven days at the moment somebody asks — no counter on the row,
    which would be one write per close and one more thing to be
    wrong. The wall itself (`grants/spent-this-week`, R-5.2 step 3)
    runs the SAME aggregate over the SAME conds, and that function is
    private to a namespace this wave does not own, so `window-conds`
    below is a second spelling of one arithmetic. They agree today
    because they are the same store call with the same three conds;
    the follow-up is one public reader both call, and it belongs
    beside the seat rather than beside either caller.
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
            [waymark10.server.store :as store])
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

  `spent` is ONE aggregate — the same SUM over the same conds the
  resolve's own wall runs, so discover cannot report a figure the wall
  disagrees with. `resumes_at` is when the OLDEST counted sitting
  falls out of the window, the first moment the sum can drop, and it
  is nil unless the seat is actually at its limit: a seat with fuel
  left is not waiting for anything."
  [eng seat ^Instant now]
  (let [st (:storage eng)
        since (week-ago now)
        conds (window-conds (:id seat) since)
        spent (or (when (contains? (inv/resources eng) :sitting)
                    (store/with-tx st
                      (fn [tx] (store/sum-matching st tx :sitting :cost_usd conds))))
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

(defn routes [eng]
  {:module :seats
   :static [["/api/seats/:id/ledger" {:get (ledger-doc eng)}]]})

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
