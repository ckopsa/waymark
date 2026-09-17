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

  - THE BUDGET WINDOW IS READ, NEVER STORED. `spent` is summed over
    the seat's closed sittings of the last seven days at the moment
    somebody asks. A counter on the seat row would be one write per
    close and one more thing to be wrong; the sum is one indexed read
    over a seat-week of rows, and the wall it feeds (R-5.2) is the
    router's own, not this file's.
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
  "The seat, decoded — its decimals and instants as values rather than
  as the JSON they were stored in."
  [eng seat-id]
  (when-some [rdef (get (inv/resources eng) :seat)]
    (some->> (raw-row eng :seat seat-id) (inv/decode-row rdef))))

(defn grant-seat
  "The seat a presented grant cites (`grant.seat`, R-5.1), or nil when
  it cites none. Read off the STORED row rather than through the
  visibility map: a seat grant's authority is the seat row the router
  resolves at request time, and this is the id that names it."
  [eng grant-id]
  (some-> (raw-row eng :grant grant-id) :data :seat str not-empty))

(defn closed-sittings
  "The seat's closed sittings started at or after `since`, decoded,
  oldest first — the rows five of the six answers are summed over."
  [eng seat-id ^Instant since]
  (if-some [rdef (get (inv/resources eng) :sitting)]
    (let [st (:storage eng)]
      (mapv #(inv/decode-row rdef %)
            (store/with-tx st
              (fn [tx]
                (store/search-rows
                 st tx :sitting
                 [{:target :data :field :seat :cast "text"
                   :op := :value (str seat-id)}
                  {:target :state :op := :value "closed"}
                  {:target :data :field :started_at :cast "timestamptz"
                   :op :>= :value (str since)}]
                 {:order-by :started_at :limit sitting-cap})))))
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
  lifts. `resumes_at` is when the OLDEST counted sitting falls out of
  the window — the first moment the sum can drop — and it is nil
  unless the seat is actually at its limit, because a seat with fuel
  left is not waiting for anything."
  [eng seat ^Instant now]
  (let [rows (closed-sittings eng (:id seat) (week-ago now))
        spent (sum-of rows :cost_usd)
        limit (get-in seat [:data :budget_usd_per_week])
        oldest (first (sort (keep #(get-in % [:data :started_at]) rows)))]
    {:spent spent
     :limit limit
     :resumes_at (when (and limit oldest (not (neg? (compare spent limit))))
                   (.plus ^Instant oldest (long window-days) ChronoUnit/DAYS))}))

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
        st (:storage eng)]
    (if (empty? members)
      {}
      (reduce (fn [acc {:keys [model n]}]
                (update acc (model-id-of-claim eng model) (fnil + 0) (long n)))
              {}
              (store/with-tx st
                (fn [tx]
                  (store/corrections-by-model st tx (vec members) since)))))))

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
  [eng req seat-id]
  (if-some [vis (router/visibility-of req)]
    (= (str seat-id) (grant-seat eng (:grant-id vis)))
    true))

(defn- ledger-doc [eng]
  (fn [{{:keys [id]} :path-params :as req}]
    (let [seat (seat-row eng id)]
      (when-not (and seat (may-read? eng req id))
        (throw (p/not-found :seat id)))
      (let [now (now-of eng)]
        (router/json-response 200 (ledger eng seat (since-of req now) now))))))

(defn routes [eng]
  {:module :seats
   :static [["/api/seats/:id/ledger" {:get (ledger-doc eng)}]]})

;; ── what discover shows a sitter (R-7.4, R-12.3) ────────────────────

(defn seat-door
  "`doors.ask.seat` for a principal wearing a grant that cites a seat,
  or nil for every other caller (R-7.4).

  A firing reads this FIRST (R-12.4), so the two things that stop a
  session — the halt and a parked state — are in it, beside the fuel
  it has left, the scope entries the boot sweep refused, whatever the
  read-back found drifting in the provider's copy of the schedule
  (R-12.3), and the address of the ledger that says what the seat has
  been costing. Every key is present whatever its value: a sitter
  reading `halt` must be able to tell 'not halted' from 'this
  document does not say'."
  [eng grant-id]
  (when-some [seat-id (grant-seat eng grant-id)]
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
