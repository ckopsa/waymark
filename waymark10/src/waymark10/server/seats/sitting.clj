(ns waymark10.server.seats.sitting
  "The sitting: one wake of a seat, what it did and what it cost
  (docs/spec-seat.md § 10). Its resource, its summary line, its doors'
  inputs, and the reads its birth makes of the seat it opens in.

  Moved out of `waymark10.server.seats` whole (ticket fb2a205e, round
  seven). The seam a session calls on a sitting — the open-sitting
  reads, the counters, the claim — stays there; this namespace is the
  kind, and it reads the seat's modes and the closing paths from there."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.resource :refer [defresource]]
            ;; the modes the seat's own fields read too, and the paths a
            ;; sitting ends closed by, which wakes.clj writes
            [waymark10.server.seats :refer [closed-by-paths default-mode seat-modes]]
            [waymark10.server.seats.bill
             :refer [a-bill-to-open abandon-sitting close-sitting expire-sitting
                     floor-posts opening-posts refloor-sitting renew-sitting
                     report-posts report-usage reports-only-grow tally-sitting]]
            [waymark10.server.seats.fields :refer [mode-choices]]
            [waymark10.server.seats.fire :refer [default-idle-seconds]]
            [waymark10.server.seats.guards
             :refer [lease-expired lease-still-running
                     the-engine-or-the-persons-tap the-engines-own-hand]]
            [waymark10.server.seats.halts :refer [halt-mark]]
            [waymark10.server.seats.health :as seat-health :refer [sitting-outcomes]]
            [waymark10.server.seats.holds :refer [lease-end]]
            [waymark10.server.seats.inbox :refer [->instant]]
            [waymark10.server.seats.ledger :refer [default-domain domain-row-of open-ledger]]
            [waymark10.server.seats.page :refer [at-work-field]]
            [waymark10.server.seats.words :as words :refer [price-entry]]))

(set! *warn-on-reflection* true)

(def ^:private closed-by-choices
  {"door" "Door — closed through the sitting's own close action"
   "hook" "Hook — closed by the run's stop hook"
   "sweep" "Sweep — closed by the engine after the sitting went idle"
   "missed" "Missed — a fire nobody sat in, born closed"
   "takeover" "Takeover — closed by a new sitting of its seat after it went silent"
   "expiry" "Expiry — closed by the engine after its lease ran out"})

(def ^:private outcome-choices @#'seat-health/outcome-choices)
(def ^:private outcome-help @#'seat-health/outcome-help)
(def ^:private flags-help @#'seat-health/flags-help)

(def ^:private an-open-sitting
  {:seat "02eee915-354f-4010-a36f-7adfbd532395"
   :model "claude-sonnet"
   :started_at "2026-09-28T10:00:00Z"})

(defscenario another-hand-abandons-a-sitting-at-the-persons-tap
  "A seat whose grant admits `sitting.abandon` asks to end another
   seat's sitting, and the ask waits for the person's tap: a held
   call, never a not-found."
  {:kind    :sitting
   :attempt :abandon
   :row     {:state :open :data an-open-sitting}
   :as      {:id "mayor" :type :agent}
   :expect  {:refused :the-engine-or-the-persons-tap
             :because "held for the person's tap"}})

(defscenario the-sweep-abandons-a-lost-sitting
  "And the sweep still ends a lost sitting by its own hand."
  {:kind    :sitting
   :attempt :abandon
   :row     {:state :open :data an-open-sitting}
   :as      {:id "waymark10-seats" :type :system}
   :expect  {:allowed true}})

;; ── :sitting ────────────────────────────────────────────────────────

(defn- resolve-model
  "The model row id a sitting should carry: the session's own claim
  when the principal made one (R-9.5 — resolved from the API
  identifier to the row that prices it), else what the caller named.
  A claim naming no row on this engine falls back to the input rather
  than emptying the field: the harness's word is not the engine's
  registry, and an unknown identifier is a models row somebody has
  not added yet."
  [inp ctx]
  (or (when-some [claimed (some-> (get-in ctx [:principal :model]) str
                                  str/trim not-empty)]
        (when-some [find' (:find ctx)]
          (some-> (first (find' :model {:name claimed} {:limit 1})) :id str)))
      (some-> (:model inp) str)))

(defn- seat-at-birth
  "The seat this sitting is opening in, read at birth, or nil when this
  ctx cannot read: the probe ctx carries no hooks."
  [inp ctx]
  (when-some [read' (:read ctx)]
    (some->> (:seat inp) str not-empty (read' :seat))))

(defn- seat-mode-of
  "The mode of the seat this sitting is opening in (R-10.8), read at
  birth. A seat this engine cannot read here — the probe ctx carries
  no hooks — and a seat row written before the field existed are both
  `fired`, which is what every seat was."
  [inp ctx]
  (or (when-some [read' (:read ctx)]
        (some-> (read' :seat (str (:seat inp))) (get-in [:data :mode])
                str not-empty))
      default-mode))

(defn- seat-domain-of
  "The name of the domain the seat this sitting is opening in stores,
  read at birth, or nil: the seat stores none, or this ctx cannot read.
  Nothing is stamped then, and the kind's :absent-as filters the row as
  `default-domain`."
  [inp ctx]
  (when-some [read' (:read ctx)]
    (when-some [id (some-> (read' :seat (str (:seat inp)))
                           (get-in [:data :domain]) str not-empty)]
      (some-> (read' :domain id) (get-in [:data :name]) str not-empty))))

(defn- seat-domain-id-of
  "The id of the domain row the seat this sitting is opening in stores,
  read at birth (spend step 3a), or nil: the seat stores none, its
  domain row is gone, or this ctx cannot read. A seat with no domain is
  NOT read as factory here: its sitting carries none."
  [inp ctx]
  (when-some [read' (:read ctx)]
    (when-some [id (some-> (read' :seat (str (:seat inp)))
                           (get-in [:data :domain]) str not-empty)]
      (some-> (domain-row-of ctx id) :id str not-empty))))

(def ^:private report-input
  "The report a session makes about itself: the five counts of R-10.5,
  the sentence, and the run that spent them.

  ONE MAP, TWO DOORS. `close` ends the sitting with it and `tally`
  writes it onto a sitting still open (R-12.25) — a hook that can
  fill one can fill the other, and two spellings of one report would
  be two shapes for the harness to keep in step."
  [:map
   [:input_tokens {:x-display {:label "Input tokens"
                               :help "The harness's exact count for this sitting."}}
    [:int {:min 0}]]
   [:output_tokens {:x-display {:label "Output tokens"
                                :help "The harness's exact count for this sitting."}}
    [:int {:min 0}]]
   [:cache_read_tokens {:x-display {:label "Cache-read tokens"
                                    :help "The harness's exact count for this sitting."}}
    [:int {:min 0}]]
   [:cache_write_tokens {:x-display {:label "Cache-write tokens"
                                     :help "The harness's exact count for this sitting."}}
    [:int {:min 0}]]
   [:turns {:x-display {:label "Model turns"
                        :help "How many turns the model took."}}
    [:int {:min 0 :max 100000}]]
   [:note {:optional true
           :examples ["Walked nine messages; two became tasks and seven were receipts."]
           :x-display {:label "What the sitting did"
                       :help "One sentence on what this wake actually moved."}}
    [:maybe [:string {:max 240}]]]
   ;; what the report is FOR beyond the counts: which run spent them.
   ;; Written only onto a row that carries no stamp already
   ;; (close-sitting, tally-sitting) — see R-12.17.
   [:harness_session {:optional true
                      :x-display
                      {:raw true
                       :label "The harness session"
                       :help "The harness session id this report came from, so the bill can be traced to the run that made it."}}
    [:maybe [:string {:max 128}]]]])

(def ^:private usage-input
  "`report_usage`'s input (docs/spec-spend.md §3): one session's whole
  total so far. The session is required, since the delta is its own."
  [:map
   [:harness_session {:x-display {:raw true
                                  :label "The harness session"
                                  :help "The session these counts are the whole total of. Two sessions on one sitting each post their own growth."}}
    [:string {:min 1 :max 128}]]
   [:input_tokens {:x-display {:label "Input tokens"
                               :help "The session's whole count so far."}}
    [:int {:min 0}]]
   [:output_tokens {:x-display {:label "Output tokens"
                                :help "The session's whole count so far."}}
    [:int {:min 0}]]
   [:cache_read_tokens {:x-display {:label "Cache-read tokens"
                                    :help "The session's whole count so far."}}
    [:int {:min 0}]]
   [:cache_write_tokens {:x-display {:label "Cache-write tokens"
                                     :help "The session's whole count so far."}}
    [:int {:min 0}]]
   [:turns {:optional true
            :x-display {:label "Model turns"
                        :help "How many turns the model has taken in this session so far."}}
    [:maybe [:int {:min 0 :max 100000}]]]
   [:model {:optional true
            :x-display {:raw true
                        :label "The model"
                        :help "The model row whose prices cost these tokens, when it is not the sitting's own."}}
    [:maybe [:string {:max 200}]]]])

(def ^:private usage-entry
  "One session's line in `usage_by_session`: its last reported counts,
  and what the sitting has posted for it."
  [:map
   [:input_tokens {:x-display {:label "Input tokens"}} [:int {:min 0}]]
   [:output_tokens {:x-display {:label "Output tokens"}} [:int {:min 0}]]
   [:cache_read_tokens {:x-display {:label "Cache-read tokens"}} [:int {:min 0}]]
   [:cache_write_tokens {:x-display {:label "Cache-write tokens"}} [:int {:min 0}]]
   [:turns {:optional true :x-display {:label "Model turns"}} [:maybe [:int {:min 0}]]]
   [:posted_usd {:x-display {:label "Posted, in dollars"}} [:decimal {:min 0}]]])

(def ^:private opening-input
  "`open_ledger`'s input (docs/spec-spend.md §8): what its log row says
  the bill rests on — the opening, and the prices the sitting was billed
  at, where it has them."
  [:map
   [:basis {:x-display {:label "Basis"
                        :help "What the postings rest on: always opening."}}
    [:enum "opening"]]
   [:prices {:optional true
             :x-display {:raw true
                         :label "The prices it was billed at"
                         :help "The sitting's own prices map, copied onto the log row; the bill is not repriced."}}
    [:maybe [:map-of :keyword price-entry]]]])

(defn- sitting-person
  "The member the sitter acts for, or nil. `:acts-for` is the identity
  gate's own mark on a delegate principal (spec-connector-door § 3) —
  a person signed in through a tool — and it is nil for a Routine's
  run, which is the honest answer for a session with nobody in the
  chair."
  [ctx]
  (some-> (get-in ctx [:principal :acts-for]) str not-empty))

(def ^:private served-entry @#'seat-health/served-entry)

;; ── the sitting's summary line (ticket ea061a48) ────────────────────
;; A collection shows a row's summary line and nothing else, so the
;; line names the SEAT, not its id, and says how the sitting is going.
;; It is composed at each render (`:summary-line`), so its times are
;; relative to that render's clock.

(def ^:private line-budget
  "The most characters a sitting's summary line runs to. Past it the
  least useful parts leave whole — the moves, then the refusals."
  100)

(defn- span
  "A length of time in its largest whole unit: 45s, 11m, 3h, 2d."
  [seconds]
  (let [s (max 0 (long seconds))]
    (cond (< s 60) (str s "s")
          (< s 3600) (str (quot s 60) "m")
          (< s 86400) (str (quot s 3600) "h")
          :else (str (quot s 86400) "d"))))

(defn- clock-utc
  "An instant as its UTC time of day: 17:49Z."
  [^java.time.Instant at]
  (str (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm")
                (.atOffset at java.time.ZoneOffset/UTC))
       "Z"))

(defn- counted [n word]
  (let [n (long (or n 0))]
    (when (pos? n) (str n " " word (when (not= 1 n) "s")))))

(defn sitting-line
  "A sitting's summary line, from its row, its seat's document (nil
  when this render could not read it: the line then names the seat by
  its id) and the render's clock (nil: the times are absolute UTC).

    rigs-code-seat · fired · started 11m ago · last call 1m ago · 2 refusals

  An open sitting whose last call is older than half its seat's
  `sitting_idle_seconds` leads with `idle 14m`, in place of the last
  call. A sitting that has ended says its outcome and its state. nil
  when the row names no seat this reader may see."
  [row seat now]
  (let [data (:data row)
        join #(clojure.string/join " · " (remove nil? %))
        label #(some-> % name (clojure.string/replace "_" " ") not-empty)
        mode (label (:mode data))]
    (when-some [who (or (some-> (:name seat) str not-empty)
                        (some-> (:seat data) str not-empty))]
      (if-not (= "open" (some-> (:state row) name))
        (join [who mode (label (:outcome data))
               (some-> (label (:state row)) clojure.string/capitalize)])
        (let [^java.time.Instant now (some-> now ->instant)
              since (fn [at]
                      (.getSeconds (java.time.Duration/between
                                    ^java.time.Instant at now)))
              at (fn [what v]
                   (when-some [at (->instant v)]
                     (if now
                       (str what " " (span (since at)) " ago")
                       (str what " " (clock-utc at)))))
              quiet (when now (some-> (->instant (:last_call_at data)) since))
              idle (when (and quiet
                              (> (long quiet)
                                 (quot (long (or (:sitting_idle_seconds seat)
                                                 default-idle-seconds))
                                       2)))
                     (str "idle " (span quiet)))
              head [idle who mode (at "started" (:started_at data))
                    (when-not idle (at "last call" (:last_call_at data)))]
              refusals (counted (:refusals data) "refusal")
              moves (counted (:transitions data) "move")]
          (or (some #(when (<= (count %) line-budget) %)
                    [(join (conj head refusals moves))
                     (join (conj head refusals))])
              (join head)))))))

(defresource sitting
  {:kind :sitting
   :plural "sittings"
   :purpose words/sitting
   :states [:open :closed :abandoned]
   :initial :open
   :terminal #{:closed :abandoned}
   :nav :system
   ;; no seat leaves an open sitting: the session's hook closes it and
   ;; the engine expires it once its lease runs out
   :left-by [{:in #{:open} :by :engine :via :close :to :closed}
             {:in #{:open} :by :engine :via :expire :to :closed}
             {:in #{:open} :by :engine :via :abandon :to :abandoned}]
   ;; the template is what a render with no clock and no reads says (a
   ;; transition's log line, another row's ref label); the envelope asks
   ;; `sitting-line`, which reads the seat for its name and its idle bar
   :summary "Sitting of {data.seat} · {state}"
   :summary-line
   (fn [row ctx]
     (sitting-line row
                   (when-some [read' (:read ctx)]
                     (some->> (get-in row [:data :seat]) str not-empty
                              (read' :seat) :data))
                   (:now ctx)))
   ;; A SITTING IS ITS MEMBER'S (R-10.3): the session opens one before
   ;; it reads its queue and the harness's hook closes it when the
   ;; session ends, and neither of those moments is a good one to
   ;; discover that the row needs a scope entry. The guards still judge
   ;; every invoke; this only decides which doors are visible enough to
   ;; be knocked on — the grant's own posture, one kind over.
   :own-surface {:by :member :actions #{"create" "close" "tally" "report_usage" "renew"}}
   :computed {:at_work
              {:schema [:map
                        [:lead :string]
                        [:working :boolean]
                        [:calls [:vector
                                 [:map
                                  [:n :int]
                                  [:outcome :string]
                                  [:text :string]
                                  [:href {:optional true} :string]]]]]
               :x-display
               {:label "At work"
                :help "What the seat did in this sitting, call by call, newest last: what it read, what it moved and why, and what it asked a person to decide. Worked out at read time from the sitting's record of calls."}
               :reads? true
               :fn at-work-field}}
   :schema
   [:map
    [:seat {:kind :seat
            :x-display
            {:label "The seat woken"
             :help "The office this wake belongs to. Its budget is what this sitting spends against, and its ledger is where the cost lands."}}
     :waymark/ref]
    ;; THE PROMISE THAT WOKE IT (R-P.10.1 of docs/spec-promise.md):
    ;; stamped by the fire, so spend per promise is a sum over sittings
    [:promise {:optional true
               :x-display
               {:raw true
                :label "The promise that woke it"
                :help "The id of the promise whose break fired this wake, when one did. Stamped by the engine from the fire's text, never written by hand."}}
     [:maybe [:string {:max 128}]]]
    [:member {:kind :member
              :x-display
              {:raw true
               :label "Who sat"
               :help "The member whose session this was — stamped from the principal at birth, never written by hand."}}
     :waymark/ref]
    [:model {:kind :model
             :x-display
             {:label "Which model sat"
              :help "The model this session ran on. When the session declared one, that claim wins over anything the caller names; the engine cannot verify it and says so."}}
     :waymark/ref]
    [:grant {:kind :grant
             :x-display
             {:raw true
              :label "The grant worn"
              :help "The leash this sitting acted under. The router finds the open sitting by it, which is how a transition and a 409 land on the right row."}}
     :waymark/ref]
    [:started_at {:x-display
                  {:label "When the model started"
                   :help "Stamped at birth. The week a sitting counts toward is measured from here."}}
     :waymark/instant]
    [:ended_at {:optional true
                :x-display
                {:label "When the model stopped"
                 :help "Stamped by the close; absent while the sitting is open."}}
     [:maybe :waymark/instant]]
    [:input_tokens {:default 0
                    :x-display {:label "Input tokens"
                                :help "The harness's exact count, summed over the sitting. The engine never estimates a token."}}
     [:int {:min 0}]]
    [:output_tokens {:default 0
                     :x-display {:label "Output tokens"
                                 :help "The harness's exact count, summed over the sitting."}}
     [:int {:min 0}]]
    [:cache_read_tokens {:default 0
                         :x-display {:label "Cache-read tokens"
                                     :help "The harness's exact count, summed over the sitting."}}
     [:int {:min 0}]]
    [:cache_write_tokens {:default 0
                          :x-display {:label "Cache-write tokens"
                                      :help "The harness's exact count, summed over the sitting."}}
     [:int {:min 0}]]
    [:turns {:default 0
             :x-display {:label "Model turns"
                         :help "How many turns the model took. Cost divided by turns is the number that says whether a cadence is too short."}}
     [:int {:min 0 :max 100000}]]
    ;; THE TWO THE ENGINE COUNTS (R-10.6). The harness does not report
    ;; these: the router adds one to `transitions` on each committed
    ;; transition under this sitting's grant and one to `refusals` on
    ;; each 409 it serves. A refusal is fuel spent on law the model did
    ;; not know, and this counter is the first record of one.
    [:transitions {:default 0
                   :x-display {:label "Transitions committed"
                               :help "Counted by the engine while this sitting was open, and frozen at the close."}}
     [:int {:min 0}]]
    [:refusals {:default 0
                :x-display {:label "Refusals served"
                            :help "409s served under this sitting's grant — fuel spent on law the model did not know ahead of time. Counted by the engine, frozen at the close, and read as waymark's own backlog rather than as the model's fault."}}
     [:int {:min 0}]]
    ;; CANCELLED TEST RUNS (ticket 39b2c934). A bench.test run the rig
    ;; answers `cancelled` is a run the sitter started and threw away —
    ;; the seat-health `test_thrash` signal. The door counts each run
    ;; once, by its run id, however many times the sitter polls it.
    [:cancelled_runs {:default 0
                      :x-display {:label "Test runs cancelled"
                                  :help "bench.test runs the rig answered cancelled while this sitting was open, each counted once by its run id. Counted by the engine and frozen at the close."}}
     [:int {:min 0}]]
    [:cancelled_run_ids {:default []
                         :x-display {:raw true
                                     :label "Cancelled run ids"
                                     :help "The run ids already counted in cancelled_runs, so a second poll of one run does not count it again."}}
     [:vector :string]]
    ;; IDENTICAL CALLS (ticket c785073c). The power door counts each
    ;; sitting's calls by a hash of the tool and its arguments, and
    ;; keeps here the most any one of them was made: seat health's
    ;; `poll_loop` signal.
    [:repeat_calls {:default 0
                    :x-display {:label "Most identical calls"
                                :help "The most times this sitting made one call through the power door, the same tool with the same arguments. Counted by the engine and frozen at the close."}}
     [:int {:min 0}]]
    ;; THE CORRECTIONS LINE. Written by the router AFTER the close: a
    ;; person's transition on a row whose previous transition was this
    ;; sitting's (found by that transition's grant) adds one here and
    ;; the row's id to `corrected_rows`. Data beside the audit, not a
    ;; verdict (R-11.4) — the rows say what to read. Absent reads zero.
    [:corrections {:optional true
                   :x-display {:label "Verdicts a person reversed"
                               :help "Counted by the engine after the close: each person's transition on a row whose last transition was this sitting's."}}
     [:maybe [:int {:min 0}]]]
    [:corrected_rows {:optional true
                      :x-display
                      {:raw true
                       :label "The rows a person reversed"
                       :spelled-by-hand "The ids of the rows whose verdict from this sitting a person later changed. The engine writes it beside `corrections`."}}
     [:maybe [:vector [:string {:max 128}]]]]
    ;; WHICH LAW THE LAST REFUSAL WAS. The count says how many; this
    ;; says what the newest one was, so the close can tell whether the
    ;; last invoke or bench write was refused, and on which guard.
    [:last_refusal {:optional true
                    :x-display
                    {:raw true
                     :label "The last refusal"
                     :help "The newest 409 served under this sitting's grant: its problem type, the guard that refused when one did, and when. Stamped by the engine beside the count, frozen at the close."}}
     [:maybe [:map
              [:type [:maybe [:string {:max 200}]]]
              [:guard {:optional true} [:maybe [:string {:max 200}]]]
              [:at [:string {:max 64}]]]]]
    ;; THE THIRD THE ENGINE COUNTS (R-10.6a). The transcript is the
    ;; larger part of the bill, and the transcript is what the MCP
    ;; door answered. `served` is the record of it: tool name → the
    ;; calls and the bytes. Keys are OPEN on purpose — the tool list
    ;; moves, and a closed map here would make each new tool a schema
    ;; change. Nothing writes it by hand: it is on no door, as the two
    ;; counters above are on no door.
    [:served {:default {}
              :x-display
              {:raw true
               :label "Bytes served, by tool"
               :help "What the MCP door answered this sitting, tool by tool: the number of calls and the UTF-8 length of the text. The engine counts bytes and not tokens, because it does not run the model; a reader divides by four. No price is attached — the bytes are the engine's own truth and the cost is the harness's."}}
     [:map-of :keyword served-entry]]
    [:cost_usd {:optional true
                :x-display {:label "What it cost, in dollars"
                            :help "The sum of what this sitting's usage reports have posted to its seat's spend, each priced when it was reported; a reprice afterwards does not move it. On an OPEN sitting it is the running cost (R-12.27), so the week's wall can see a sitting that has not ended."}}
     [:maybe [:decimal {:min 0}]]]
    [:prices {:optional true
              :x-display
              {:label "The prices it was costed at"
               :spelled-by-hand "The four prices the model carried at the moment this sitting closed, copied down by the close so a later reprice cannot rewrite a bill."}}
     [:maybe [:map
              [:input {:x-display {:label "Input, $ per million tokens"}} price-entry]
              [:output {:x-display {:label "Output, $ per million tokens"}} price-entry]
              [:cache_read {:x-display {:label "Cache read, $ per million tokens"}} price-entry]
              [:cache_write {:x-display {:label "Cache write, $ per million tokens"}} price-entry]]]]
    ;; SPEND STEP 3c: the report's own record. Each session's last
    ;; counts, the delta the last report posted, and what the postings
    ;; rest on — all written by the reporting doors, none by hand.
    [:usage_by_session {:optional true
                        :x-display
                        {:raw true
                         :label "Usage, by harness session"
                         :spelled-by-hand "Each session's last reported counts and what it has posted, written by every usage report."}}
     [:maybe [:map-of :keyword usage-entry]]]
    [:last_report {:optional true
                   :x-display
                   {:raw true
                    :label "The last usage report"
                    :spelled-by-hand "The session, the delta it posted, the prices it was costed at and when; the posted amount is delta_usd."}}
     [:maybe [:map
              [:harness_session [:string {:max 128}]]
              [:delta_usd [:decimal {:min 0}]]
              [:estimate_reversed_usd {:optional true} [:decimal {:min 0}]]
              [:reversed_basis {:optional true} [:enum "estimated_from_transcript" "estimated_from_calls"]]
              [:estimate_usd {:optional true} [:decimal {:min 0}]]
              [:estimate_basis {:optional true} [:enum "estimated_from_transcript" "estimated_from_calls"]]
              [:prices [:map-of :keyword price-entry]]
              [:at :waymark/instant]]]]
    [:bill_basis {:optional true
                  :x-display
                  {:label "What the bill rests on"
                   :spelled-by-hand "estimated_from_transcript, or estimated_from_calls when no line or report came, while the floor's net estimate stands, else the basis of the latest posting, none when nothing posted."}}
     [:maybe (into [:enum] ["reported" "opening" "estimated_from_transcript" "estimated_from_calls" "none"])]]
    ;; SPEND STEP 6: the engine's floor (docs/spec-spend.md §4), written
    ;; by `expire` and re-run by a late report, what `expire` posts.
    [:floor {:optional true
             :x-display
             {:raw true
              :label "The engine's floor"
              :spelled-by-hand "What the transcript showed beyond every session's report when the engine last ran the floor, the models it was priced at, and when."}}
     [:maybe [:map
              [:estimate_usd [:decimal {:min 0}]]
              [:models [:vector {:max 64} [:string {:max 128}]]]
              [:basis {:optional true} [:enum "estimated_from_transcript" "estimated_from_calls"]]
              [:at :waymark/instant]]]]
    [:note {:optional true
            :x-display
            {:label "What the sitting did"
             :help "One sentence, written at the close — what this wake actually moved. Read beside the counts when a step down the ladder is being judged."}}
     [:maybe [:string {:max 240}]]]
    ;; THE RUN THIS BILL CAME FROM (R-12.15, R-12.17). The harness
    ;; knows its own session id; the engine cannot derive one. A
    ;; session that declares it at the sit is paired with this row
    ;; from birth, and the close's report names the same id — which is
    ;; what lets two overlapping wakes of one seat each close their
    ;; own sitting rather than the newest.
    [:harness_session {:optional true
                       :x-display
                       {:raw true
                        :label "The harness session"
                        :help "The harness session id the hook reported, so a bill can be traced back to the run that made it. Absent until the close, unless the session named it when it sat."}}
     [:maybe [:string {:max 128}]]]
    ;; ── WHO SAT, AND HOW (R-10.8) ───────────────────────────────────
    ;; Both are stamped at birth and neither is on the create door: a
    ;; sitting does not choose its mode, it INHERITS the seat's, and
    ;; the person behind a delegate is the identity gate's mark rather
    ;; than a claim a session may make about itself. The ledger reads
    ;; the two modes in two columns, and a step down the ladder
    ;; compares fired with fired.
    [:mode {:optional true
            :x-display
            {:label "How it was sat"
             :choices mode-choices
             :spelled-by-hand "Copied from the seat at birth; a sitting's mode is the seat's, and no hand writes it."}}
     [:maybe (into [:enum] seat-modes)]]
    [:person {:optional true
              :not-a-ref "A member's principal id, not a person row's: the member kind owns it."
              :x-display
              {:raw true
               :label "The person in the chair"
               :spelled-by-hand "The member the sitter acts for, stamped at birth when a person sat through their own tool; absent for a Routine's run, which has nobody behind it."}}
     [:maybe [:string {:max 200}]]]
    ;; THE SAFETY NET UNDER THE WAIT (R-12.25). An interactive sitting
    ;; waits between turns, so nothing bounds it but this: the Stop
    ;; hook tallies each turn, the stamp moves, and a stamp that stops
    ;; moving is how the sweep tells a person who walked away from a
    ;; person who is thinking.
    [:tallied_at {:optional true
                  :x-display
                  {:label "Last tallied"
                   :spelled-by-hand "Stamped by each tally of an open sitting; absent on a sitting nobody has tallied."}}
     [:maybe :waymark/instant]]
    ;; THE LAST CALL (ticket 086307f2). Stamped by the sit at birth and
    ;; by every call the doors count against the sitting — a tool
    ;; answered, a transition, a refusal, a tally — so a FIRED sitting
    ;; whose run was lost reads as silent, and the sweep ends it after
    ;; the seat's `sitting_idle_seconds`.
    [:last_call_at {:optional true
                    :x-display
                    {:label "Last call"
                     :spelled-by-hand "Stamped by the engine on every call counted against an open sitting; the sweep ends a fired sitting silent past its seat's idle limit."}}
     [:maybe :waymark/instant]]
    ;; THE LEASE (docs/spec-leases.md §3). Stamped at birth to
    ;; `started_at` plus the seat's `sitting_idle_seconds` and moved by
    ;; `renew` once less than half of it is left. Past it the sitting is
    ;; no longer counted open, and its tokens and keys stop answering.
    [:expires_at {:optional true
                  :x-display
                  {:label "Lease ends"
                   :spelled-by-hand "Stamped at birth and moved by each renewal: the instant after which this sitting is no longer counted open and its tokens and keys stop answering."}}
     [:maybe :waymark/instant]]
    ;; THE LAST ANSWER (ticket 28a5d117). Stamped by the MCP door on a
    ;; tool call that answered and was not a refusal, so the close can
    ;; tell a sitting its last refusal stopped from one that called
    ;; again and was answered.
    [:last_answer_at {:optional true
                      :x-display
                      {:label "Last answer"
                       :spelled-by-hand "Stamped by the engine on each tool call that answered without an error; the close reads it against the last refusal."}}
     [:maybe :waymark/instant]]
    ;; THE TRACE OF THE FIRING'S KEY (R-12.37). The sit that spends a
    ;; firing's key keeps its hash here, on the sitting it opened and
    ;; not on the seat, so a run that loses its bind to a restart may
    ;; sit again in THIS sitting, while it is open, and nowhere else.
    [:fire_key_hash {:optional true :secret true
                     :x-display {:hidden true
                                 :label "The firing key's hash"
                                 :spelled-by-hand "The SHA-256 of the firing key that opened this sitting. The sit writes it; it answers only while the sitting is open; the engine never shows a key."}}
     [:maybe [:string {:max 64}]]]
    ;; THE CONNECTOR SESSION'S CLAIM (ticket 7496403e). The sit that
    ;; picks this sitting stamps the hash of its connector session and
    ;; the moment, in the same transaction that holds the row, so a
    ;; session-less re-sit after a drop can tell a claim whose session
    ;; is gone from one a live run still holds (mcp/reusable-sitting).
    [:connector_session {:optional true
                         :x-display
                         {:raw true
                          :label "The claiming connector session"
                          :help "The SHA-256 of the connector session that last sat in this sitting. The sit writes it; the engine never shows a session id."}}
     [:maybe [:string {:max 64}]]]
    [:claimed_at {:optional true
                  :x-display
                  {:label "Claimed"
                   :spelled-by-hand "Stamped by the sit that last claimed this sitting for its connector session."}}
     [:maybe :waymark/instant]]
    ;; WHAT THE CLIENT DECLARED (ticket b9f90987). The session table
    ;; keeps what an `initialize` said and no kind serves it, so the sit
    ;; copies it here at the bind and again at each re-sit
    ;; (`stamp-client!`). `app_tools` is the listing's own verdict at
    ;; that moment, so one read answers why a host got no waymark_show.
    ;; The booleans are plain :boolean, `missed`'s spelling.
    [:client_name {:optional true
                   :x-display
                   {:label "The MCP client"
                    :spelled-by-hand "The clientInfo name the connector session's initialize declared. The sit copies it from the session; absent when the client named none."}}
     [:maybe [:string {:max 200}]]]
    [:client_version {:optional true
                      :x-display
                      {:label "The MCP client's version"
                       :spelled-by-hand "The clientInfo version the connector session's initialize declared. The sit copies it from the session; absent when the client named none."}}
     [:maybe [:string {:max 200}]]]
    [:app_ui {:optional true
              :x-display
              {:label "Declared MCP Apps"
               :spelled-by-hand "Written by the sit: whether the connector session's initialize declared the MCP Apps extension with the app page's MIME type."}}
     :boolean]
    [:app_tools {:optional true
                 :x-display
                 {:label "Listed the app tools"
                  :spelled-by-hand "Written by the sit: whether the session's tool listing carried the app tools at that moment. The client declared the extension, its bearer is a delegate of a client listed for the app tools, and the ticket's signing key is set."}}
     :boolean]
    ;; THE INBOX'S KEY. A seat that has an inbox (`inbox-of`) is answered
    ;; a fresh key at each sit (`issue-inbox-key!`), and its hash is kept
    ;; here, on the sitting, so the key answers only while the sitting
    ;; is open (`inbox-sitting-by-key`).
    [:inbox_key_hash {:optional true :secret true
                      :x-display {:hidden true
                                  :label "The inbox key's hash"
                                  :spelled-by-hand "The SHA-256 of the inbox key the last sit answered. The sit writes it; it answers only while the sitting is open; the engine never shows a key."}}
     [:maybe [:string {:max 64}]]]
    ;; THE KEYS BEFORE THE NEWEST. A sit that reuses this sitting for the
    ;; same harness session mints a key and stops none, so the key a
    ;; stream is using answers until the sitting closes. Their hashes
    ;; are kept here, newest first.
    [:inbox_keys_earlier {:optional true :secret true
                          :x-display {:hidden true
                                      :label "The earlier inbox keys' hashes"
                                      :spelled-by-hand "The SHA-256 of each inbox key a sit of the same session answered before the newest. The sit writes it; they answer only while the sitting is open; the engine never shows a key."}}
     [:maybe [:vector [:string {:min 1 :max 128}]]]]
    ;; THE FEED'S TOKEN. A seat that declares a `feed_url` is answered
    ;; a fresh token at each sit (`issue-feed-token!`). Its hash and its
    ;; end are kept here, on the sitting, so the token answers only
    ;; while the sitting is open and that end has not come
    ;; (`feed-seat-by-token`).
    [:feed_token_hash {:optional true :secret true
                       :x-display {:hidden true
                                   :label "The feed token's hash"
                                   :spelled-by-hand "The SHA-256 of the feed token the last sit answered. The sit writes it; it answers only while the sitting is open; the engine never shows a token."}}
     [:maybe [:string {:max 64}]]]
    [:feed_token_expires_at {:optional true
                             :x-display
                             {:label "The feed token's end"
                              :spelled-by-hand "Written by the sit: the instant after which the feed token the last sit answered is refused, when the sitting has not closed before it."}}
     [:maybe [:string {:max 40}]]]
    ;; THE TOKENS BEFORE THE NEWEST. A re-sit and a renewal at the key
    ;; check door each mint a token and stop none (`with-feed-token`),
    ;; so the token a stream is using answers until its own end. Their
    ;; hashes and ends are kept here, newest first.
    [:feed_tokens_earlier {:optional true :secret true
                           :x-display {:hidden true
                                       :label "The earlier feed tokens' hashes"
                                       :spelled-by-hand "The SHA-256 and the end of each feed token minted before the newest that has not reached its end. The sit and the key check door write it; the engine never shows a token."}}
     [:maybe [:vector
              [:map
               [:hash {:x-display
                       {:label "The hash of one token"
                        :help "The SHA-256 of a feed token minted before the newest one."}}
                [:string {:min 1 :max 128}]]
               [:expires_at {:x-display
                             {:label "When it stops answering"
                              :help "The instant after which this token is refused, when the sitting has not closed before it."}}
                [:string {:max 40}]]]]]]
    ;; A WAKE THAT WALKED NOTHING. The sit stamps this when the walk it
    ;; hands has no rows at all — an empty queue, a queue whose every
    ;; row another open sitting or a stuck change holds, a seat at a
    ;; wall — so seat health can count an idle wake. A sitting with no
    ;; lease cannot say it: one the claim never reached has none
    ;; either. Plain :boolean, `missed`'s spelling, so the
    ;; field promotes to a column and filters.
    [:walked_nothing {:optional true
                      :x-display
                      {:label "Walked nothing"
                       :spelled-by-hand "Written by the sit when the walk it handed had no rows at all: the queue was empty, or every row in it was held. A sitting that was handed a row does not carry it, and a seat at a wall carries `halted` instead."}}
     :boolean]
    ;; WHY IT WAS EMPTY (ticket ae64b57c). The rows the walk left out,
    ;; each with the rule that left it out — the sit's own `withheld` —
    ;; or that the queue itself was empty, so an empty walk over a
    ;; queue that is not empty is not read as a walk bug.
    [:walked_nothing_why {:optional true
                          :x-display
                          {:label "Why it walked nothing"
                           :spelled-by-hand "Written by the sit beside `walked_nothing`: the rows the walk left out and the rule that left out each one (another open sitting holds it, the release grace holds it, its change waits for a person), or that the queue had no rows under the walk's filter."}}
     [:maybe [:string {:max 480}]]]
    ;; A WAKE THE WALL HELD (ticket ae64b57c). The sit's `halted`
    ;; block, kept: a halted sit was not let walk, so it is not stamped
    ;; `walked_nothing`, and this says which wall and when it lifts.
    [:halted {:optional true
              :x-display
              {:raw true
               :label "Halted at a wall"
               :spelled-by-hand "Written by the sit when the seat was against a wall: which wall, the sentence that says why, and when it lifts."}}
     [:maybe halt-mark]]
    ;; A FIRE NOBODY SAT IN. The clock sweep writes this row, already
    ;; closed, when a firing's key is still unspent past the sit
    ;; deadline (`wakes/sweep-missed!`), so an audit that reads the
    ;; sittings sees the run that died before it sat. Plain :boolean
    ;; and not :maybe, so the field promotes to a column and filters.
    [:missed {:optional true
              :x-display
              {:label "Fired, and nobody sat"
               :spelled-by-hand "Written by the engine's sweep when a firing's key went unspent past the sit deadline. The sitting is born closed, with every count at zero."}}
     :boolean]
    ;; HOW IT WAS CLOSED. Written at every path that ends a sitting
    ;; closed — the close handler for the door, the hook and the idle
    ;; sweep, and `wakes/record-missed!` for a missed fire — so seat
    ;; health reads a cut-short or never-sat run without parsing the
    ;; note. Absent while open; not :maybe, so it promotes and filters.
    [:closed_by {:optional true
                 :x-display
                 {:label "How it was closed"
                  :choices closed-by-choices
                  :spelled-by-hand "Written by the engine at the close: door, hook, sweep, or missed for a fire nobody sat in."}}
     (into [:enum] closed-by-paths)]
    ;; WHAT IT CAME TO, AND WHY (seat health 1, ticket fad586b7). Both
    ;; are written at every path that ends a sitting closed, beside
    ;; `closed_by`, by `sitting-health`: one outcome, and the flags
    ;; that explain it. No model judges either. `outcome` is an enum
    ;; and not :maybe, so it promotes and filters; `flags` is a
    ;; vocabulary array, so it filters by membership.
    [:outcome {:optional true
               :x-display
               {:label "What it came to"
                :choices outcome-choices
                :help outcome-help}}
     (into [:enum] sitting-outcomes)]
    [:flags {:optional true
             :x-display
             {:label "Health flags"
              :help flags-help}}
     [:vector [:waymark/vocab {:open true}]]]
    ;; THE SEAT'S DOMAIN, BY NAME (epic aff24e84, piece 3): stamped at
    ;; birth from the seat's stored domain. A seat that stores none
    ;; stamps nothing, and :absent-as filters the row as factory. Not
    ;; :maybe, so it promotes and filters.
    [:domain {:optional true
              :not-a-ref "The domain's NAME, as the seat's domain row spells it: never a row id."
              :x-display
              {:label "The seat's domain"
               :help "The name of the domain the seat was in when this sitting opened. The engine stamps it at birth. A sitting with none filters as factory."}}
     [:string {:min 1 :max 120}]]
    ;; THE SEAT'S DOMAIN, BY ID (spend step 3a): stamped once at birth
    ;; from the seat's stored domain, so a seat that moves domain
    ;; mid-sitting keeps this sitting's stamp. A seat that stores none
    ;; stamps nothing; `backfill-domain-ids!` fills the rows born before.
    [:domain_id {:optional true
                 :kind :domain
                 :x-display
                 {:label "The seat's domain row"
                  :help "The domain the seat was in when this sitting opened, by id. The engine stamps it at birth. Absent when the seat named no domain."}}
     ;; not :maybe, so it promotes and filters: the report's domain
     ;; posting reads the row it names (spend step 3c)
     :waymark/ref]]
   ;; the birth door is the SESSION'S, and it carries nothing a close
   ;; or a counter owns: member and started_at are stamped, the token
   ;; counts and the cost are the close's, and the three counters — the
   ;; two of R-10.6 and the `served` map of R-10.6a — are the engine's.
   :create-schema
   [:map
    [:seat {:kind :seat
            :x-display
            {:label "The seat woken"
             :help "The office this wake belongs to."}}
     :waymark/ref]
    [:model {:kind :model
             :x-display
             {:label "Which model is sitting"
              :help "The model this session runs on. If the session's own token declares one, that claim wins over this."}}
     :waymark/ref]
    [:grant {:kind :grant
             :x-display
             {:raw true
              :label "The grant worn"
              :help "The leash this sitting will act under — the grant the session is presenting."}}
     :waymark/ref]
    ;; the ONE thing a session knows at the sit that nothing else can
    ;; tell the engine (R-12.15): which run this is. Optional, because
    ;; a harness that reports no id still opens a sitting; when it
    ;; does report one, the pairing is made here rather than guessed
    ;; at the close.
    [:harness_session {:optional true
                       :x-display
                       {:raw true
                        :label "The harness session"
                        :help "The session id of the run that is sitting, if the harness knows one. The close's report names the same id, and that is how two overlapping wakes of one seat each end their own sitting."}}
     [:maybe [:string {:max 128}]]]]
   ;; THE BIRTH STAMPS WHAT THE CALLER MAY NOT WRITE and what the
   ;; document owes a reader: whose session this is, when it started,
   ;; which model the session itself claims — and zeroes for every
   ;; count, so an open sitting reads as a wake that has spent nothing
   ;; yet rather than as a row with holes in it.
   :on-create
   (fn [row ctx]
     (reduce (fn [r [f v]] (assoc-in r [:data f] v))
             row
             (cond-> [[:member (str (get-in ctx [:principal :id]))]
                      [:model (resolve-model (:data row) ctx)]
                      ;; R-10.8: the mode is the seat's, read once at
                      ;; birth, so a seat restated afterwards never
                      ;; rewrites a sitting already under way
                      [:mode (seat-mode-of (:data row) ctx)]
                      [:started_at (:now ctx)]
                      [:last_call_at (:now ctx)]
                      ;; the lease (docs/spec-leases.md §3): started_at
                      ;; plus the seat's idle limit, in both modes
                      [:expires_at (lease-end (:now ctx) (seat-at-birth (:data row) ctx))]
                      [:input_tokens 0] [:output_tokens 0]
                      [:cache_read_tokens 0] [:cache_write_tokens 0]
                      [:turns 0] [:transitions 0] [:refusals 0]
                      ;; R-10.6a: an open sitting that has read
                      ;; nothing yet says so with an empty map, not
                      ;; with a hole
                      [:served {}]]
               ;; the delegate's own person, when there is one — the
               ;; identity gate's mark, never a claim in the request
               (sitting-person ctx) (conj [:person (sitting-person ctx)])
               ;; the seat's stored domain, by name; a seat that stores
               ;; none stamps nothing
               (seat-domain-of (:data row) ctx)
               (conj [:domain (seat-domain-of (:data row) ctx)])
               (seat-domain-id-of (:data row) ctx)
               (conj [:domain_id (seat-domain-id-of (:data row) ctx)]))))
   :filterable {:state #{:eq :in}
                :domain #{:eq}
                :domain_id #{:eq}
                :seat #{:eq}
                :member #{:eq}
                :model #{:eq}
                :grant #{:eq}
                :missed #{:eq}
                :closed_by #{:eq :in}
                :outcome #{:eq :in}
                :started_at #{:after :before :range}
                :ended_at #{:after :before :range}}
   :absent-as {:domain default-domain}
   :sortable {:fields [:started_at] :default "-started_at"}
   :links [{:rel "seat" :kind :seat
            :href "/api/seats/{data.seat}"
            :summary "The seat this wake belongs to"}
           {:rel "model" :kind :model
            :href "/api/models/{data.model}"
            :summary "The model that sat"}]
   :actions
   {:close
    {:from #{:open} :to :closed
     :guards [reports-only-grow]
     :input report-input
     :record true
     ;; :edit-shape — a close welds the first counts onto a row that
     ;; has none; there is no earlier value to prefill from and no
     ;; second close, so the fence an :edit implies would be an etag
     ;; demanded of a session-end hook for fields nobody has written.
     :waives #{:edit-shape}
     :posts report-posts
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The counts and the cost are written down and the sitting is over; there is no second close, and reopening a wake that has ended would be recording a bill twice."}
     :handler close-sitting
     :display {:label "Close" :style :primary :order 1
               :description "Report the token counts and the turns — the cost is computed from the model's prices right now and written down beside them"}}

    ;; ── the tally (R-12.25, R-12.27) ────────────────────────────────
    ;; A fired run raises ONE Stop event, so its hook closes. An
    ;; interactive session raises one for every turn, so its hook must
    ;; not close and must not block — it tallies. The counts are
    ;; CUMULATIVE, which is what makes a replay harmless: a second
    ;; tally of the same numbers writes the same numbers.
    ;;
    ;; The door is the sitter's, exactly as `close` is (`:own-surface`
    ;; carries all three), and the route invokes it as the sitter.
    ;; The running cost it writes is what lets the week's wall see a
    ;; sitting that has not ended — one turn late at most.
    :tally
    {:from #{:open} :to :open
     :guards [reports-only-grow]
     :input report-input
     :record true
     ;; :edit-shape — the same reason the close waives it, and one
     ;; more: a tally arrives from a Stop hook with no etag to carry
     ;; and no form to prefill, and it REPLACES what the last one
     ;; wrote by design.
     :waives #{:edit-shape}
     ;; a self-loop: re-doing is its own undo, so no :one-way is owed
     ;; and :reversible would have nowhere to point
     :safety {:idempotent true :reversible false :confirm false}
     :posts report-posts
     :handler tally-sitting
     :display {:label "Tally" :order 2
               :description "Write what this sitting has spent so far — the counts, the running cost at today's prices, and the stamp the sweep reads"}}

    ;; ── the usage report (docs/spec-spend.md §3, spend step 3c) ─────
    ;; Cumulative in, delta out: the price of what a session grew since
    ;; its last report, posted to the seat's and the domain's spend. It
    ;; runs on an ended sitting too, under the licence, and leaves it
    ;; ended: a late hook still reports.
    :report_usage
    {:from #{:open :closed :abandoned} :to :same
     :allow-after-end true
     :writes #{:usage_by_session :last_report :cost_usd :bill_basis :floor}
     :guards [reports-only-grow]
     :input usage-input
     :record true
     :waives #{:edit-shape}
     :posts report-posts
     :safety {:idempotent true :reversible false :confirm false}
     :handler report-usage
     :display {:label "Report usage" :order 3
               :description "Report a session's whole token counts so far — the price of what grew since its last report is posted to the seat's and the domain's spend"}}

    ;; ── the lease's renewal (docs/spec-leases.md §2, §3) ────────────
    ;; A call or a report that finds less than half the lease left
    ;; makes this once its own write has committed, so a steady run logs
    ;; about two renewals per lease length. A lapsed lease is refused,
    ;; and the session sits afresh.
    :renew
    {:from #{:open} :to :open
     :guards [lease-expired]
     :safety {:idempotent true :reversible false :confirm false}
     ;; :replay false — a renew whose latest transition is a renew (a
     ;; session that only reads its inbox writes nothing between) would
     ;; otherwise replay the first and never move `expires_at` again
     :replay false
     :handler renew-sitting
     :display {:label "Renew" :order 5
               :description "Move this sitting's lease end to now plus its seat's idle limit"}}

    ;; ── the opening (docs/spec-spend.md §8, spend step 4) ───────────
    ;; The engine's own: a bill from before the ledger, posted unchanged
    ;; and dated when it was spent, on any sitting, ended or not.
    :open_ledger
    {:from #{:open :closed :abandoned} :to :same
     :allow-after-end true
     :writes #{:usage_by_session :last_report :bill_basis}
     :guards [the-engines-own-hand a-bill-to-open]
     :input opening-input
     :record true
     :waives #{:edit-shape}
     :posts opening-posts
     :safety {:idempotent true :reversible false :confirm false}
     :handler open-ledger
     :display {:label "Open the ledger" :order 4
               :description "Post the cost this sitting was billed before the ledger to its seat's and its domain's spend, unchanged, dated when it was spent"}}

    ;; the floor again, when transcript lines land on a sitting the
    ;; engine expired (docs/spec-spend.md §4): the upload door's
    :refloor
    {:from #{:closed} :to :same
     :allow-after-end true
     :writes #{:last_report :cost_usd :bill_basis :floor}
     :guards [the-engines-own-hand]
     :posts report-posts
     ;; :replay false — a second batch of lines must re-run the floor,
     ;; not replay the first refloor
     :replay false
     :safety {:idempotent true :reversible false :confirm false}
     :handler refloor-sitting
     :display {:label "Re-price the floor" :order 9}}

    ;; the engine's end of a sitting whose lease ran out: the sweep's,
    ;; or a claim's (docs/spec-leases.md §3, leases 3b)
    :expire
    {:from #{:open} :to :closed
     :guards [the-engines-own-hand lease-still-running]
     ;; the floor (docs/spec-spend.md §4, spend step 6)
     :posts floor-posts
     :handler expire-sitting
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The sitting's lease ran out and it is over: its leases end with it, and the session that held it sits afresh."}
     :display {:label "Expire" :order 8}}

    ;; R-7.6: a sitting left open past two cadences is the boot
    ;; sweep's, and it ends with NO tokens — the absence of a bill,
    ;; not a zero one. Since leases 3b only a person's tap abandons.
    :abandon
    {:from #{:open} :to :abandoned
     :guards [the-engine-or-the-persons-tap]
     ;; nothing is written on the row. The ending of a sitting
     ;; nobody closed is the ABSENCE of a bill, not a zero one, and
     ;; the counts it already carries are what it did before it was
     ;; lost. Its handler only lets go of its leases, with no grace.
     :handler abandon-sitting
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A sitting nobody closed is over with no cost recorded; the session that would have reported its tokens is gone."
              ;; the door is visible now (ticket be2c2c16), so the
              ;; cheap-reverse battery reads it: the cost is the world's,
              ;; not the row's, and :final is where that is spelled
              :final "The session that sat is gone and nothing will report its tokens; its seat's next firing opens a new sitting."}
     :display {:label "Abandon" :order 9}}}
   :deviations
   ["R-10.2 lets a sitting's `model` be null (R-9.4: a token with no claim has model null), and R-10.7 wants the collection filterable by model. A promoted column is generated only for a non-`:maybe` entry, so those two cannot both be had: `model` is required at the create door, and the session's claim wins over it when there is one. A harness with nothing to declare names the row it is running as."
    "R-10.6 has the engine count transitions and refusals. `bump-counter!` is a maintenance write (`store/update-data!`, jobs.clj's progress precedent) rather than a transition: a logged transition per counted transition would double the log — the counter would cost more log than the thing it counts. The `close` is a real transition and freezes both numbers."
    "`tally` writes a `cost_usd` and NO `prices` map, where the close writes both. The prices are copied down beside a bill a reprice must not be able to move, and the only bill is the close's; a tally's cost is a reading of the row at that moment, re-read at the next turn, and a prices map beside it would say a running figure was final. A sitting closed by the sweep from its last tally is costed by the close, at the close's prices, like every other."
    "`close` and `tally` share ONE input (`report-input`) rather than declaring the five counts twice. The two doors take the same report from the same hook — one ends the sitting, the other writes the running total — and two spellings would be two shapes for a harness to keep in step, which is exactly the drift § 3 of the spec is about."
    "`harness_session` is on the BIRTH door as well as the close's (R-12.15, R-12.17), which no other count-bearing field is. The reason is that it is not a count: it is the only fact a session knows at the sit that the engine cannot derive, and the pairing it makes is what lets two overlapping wakes of one seat each end their own sitting. It is `:maybe`, so it is not filterable and the pairing reads one page of the seat's open sittings rather than querying — `model`'s recorded wall, one field over. The close writes it only onto a row that carries none: a report naming another run's id must not move a bill."]
   :scenarios [another-hand-abandons-a-sitting-at-the-persons-tap
               the-sweep-abandons-a-lost-sitting]})
