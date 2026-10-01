(ns waymark10.server.clock-shift
  "The demo clock (docs/spec-agent-demo-walks.md § 7): `clock_shift`, the
  kind whose create moves a demo engine's clock forward, so a call
  scheduled for 08:30 tomorrow runs seconds later.

  THE DOOR IS A KIND. An agent reaches a kind with the tools it has, a
  create is audited like any write, and the row's history says who moved
  the clock and when. A row names `to`, an instant, or `by`, an ISO-8601
  duration, and exactly one. The engine stamps `offset_seconds`, the
  engine's total shift after the row.

  THE SHIFT IS FORWARD ONLY, AND AT MOST 14 DAYS IN ALL, which keeps it
  inside every retention the engine sweeps on.

  THE CLOCK IS A VALUE THE ASSEMBLY HOLDS (`clock`): the real clock and
  an offset. `with-clock` lays it over an engine's options: the kind, a
  `:now-fn` that adds the offset to the real clock, and two services,
  `:demo-clock` and `:recording-clock`. `install!` reads the offset back
  from the rows, so a restarted engine keeps its time, and hangs the
  pass below on the engine's post-commit hook.

  AFTER THE CREATE COMMITS THE CLOCK'S PASSES RUN ONCE,
  `scheduled/sweep-due!` and `invitations/sweep-expired!`, so nobody
  waits thirty seconds for the loop.

  THE WALL IS THREE WALLS, AND ANY ONE IS ENOUGH. The kind is in no
  module the inventory enrols: only a boot that calls `with-clock` serves
  it, and the one that does is the boot behind WAYMARK10_SEED. The create
  guard asks `seed/admit!`'s question again of the live engine. And an
  engine built without `with-clock` has the plain `:now-fn` and no offset
  to read, so a row that somehow existed would move nothing.

  A FRAME'S `t` IS RECORDING TIME. `:recording-clock` is the real clock,
  and the walks read it for a walk's start and for each frame, so a
  shift does not reorder a recording."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.invitations :as invitations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Duration Instant)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 clock: " parts))))

(def kind :clock_shift)

(def max-shift-seconds
  "The engine's total shift is at most fourteen days."
  (* 14 24 60 60))

(def ^:private restore-page
  "How many of the newest shifts `install!` reads the offset from."
  200)

;; ── the clock ───────────────────────────────────────────────────────

(defn clock
  "A demo clock: the real clock, and the offset the shifts add to it,
  zero until `install!` reads the rows. `:now-fn` is the real clock, the
  system's when absent; a test hands in its own."
  ([] (clock {}))
  ([{:keys [now-fn]}]
   {:real-now-fn (or now-fn (fn [] (Instant/now)))
    :offset (atom 0)
    :engine (atom nil)}))

(defn now-fn
  "The engine's `:now-fn` under this clock: the real clock plus the
  offset."
  [{:keys [real-now-fn offset]}]
  (fn [] (.plusSeconds ^Instant (real-now-fn) (long @offset))))

(defn offset-seconds
  "How far ahead of the real clock this engine is, in seconds; nil on an
  engine built without the demo clock."
  [eng]
  (some-> (get-in eng [:services :demo-clock :offset]) deref long))

(defn- instant-of ^Instant [v]
  (cond
    (instance? Instant v) v
    (string? v) (try (Instant/parse ^String v) (catch Exception _ nil))
    :else nil))

(defn- move-of
  "What a body asks for, read against `now`: `{:seconds n}`, or
  `{:problem sentence}` for a body that does not name one move. A `to`
  between two seconds is rounded up, so the clock is never left short
  of it."
  [body ^Instant now]
  (let [to (:to body)
        to (if (instance? Instant to) to (some-> to str str/trim not-empty))
        by (some-> (:by body) str str/trim not-empty)]
    (cond
      (and to by)
      {:problem "it names both `to` and `by`. Write one of them."}

      (and (nil? to) (nil? by))
      {:problem "it names neither `to` nor `by`. Write one of them."}

      to
      (if-some [at (instant-of to)]
        {:seconds (long (Math/ceil (/ (.toMillis (Duration/between now at)) 1000.0)))}
        {:problem (str "`to` is `" to "`, which is not an instant. Write it as 2026-10-02T14:30:00Z.")})

      :else
      (if-some [^Duration d (try (Duration/parse ^String by)
                                 (catch DateTimeParseException _ nil))]
        {:seconds (.getSeconds d)}
        {:problem (str "`by` is `" by "`, which is not an ISO-8601 duration of days, hours, minutes and seconds. Write it as PT18H or P1DT2H.")}))))

;; ── guards ──────────────────────────────────────────────────────────

(defn- wall
  "Why this engine's clock does not move, or nil on a demo engine:
  `seed/admit!`'s question, asked of the engine `install!` was given."
  [c]
  (if-some [eng (some-> c :engine deref)]
    (try
      (seed/admit! eng)
      nil
      (catch clojure.lang.ExceptionInfo _
        (if (str/starts-with? (str (:name eng)) "demo-")
          "this engine has an identity provider, and a demo engine has none."
          (str "this engine is named `" (:name eng)
               "`, and a demo engine's name begins with `demo-`."))))
    "this engine was built without the demo clock, so it has no offset to move."))

(g/defguard the-engine-is-a-demo
  {:reads [:services]
   :vars [:problem]
   :explain "The clock moves only on a demo engine: {problem}"
   :open "No door opens this on a working engine. The clock is moved in a seeded clone: an engine whose name begins with `demo-`, with no identity provider, booted with the demo clock."}
  [_row _inp ctx]
  (if-some [problem (wall (get-in ctx [:services :demo-clock]))]
    (t/deny {:vars {:problem problem}})
    (t/allow)))

(g/defguard the-shift-names-one-move
  {:reads [:now]
   :vars [:problem]
   :explain "A clock shift names one move, and this one does not: {problem}"
   :open "The way out is in this same form: write `to`, an instant, or `by`, a duration, and leave the other empty."}
  [_row inp ctx]
  (if-some [problem (:problem (move-of inp (or (:now ctx) (Instant/now))))]
    (t/deny {:vars {:problem problem}})
    (t/allow)))

(g/defguard the-shift-is-forward-and-short
  {:reads [:now :services]
   :vars [:problem]
   :explain "The clock moves forward only, and at most 14 days in all: {problem}"
   :open "The way out is in this same form: a later `to`, or a shorter `by`. The 14 days are fixed in code, and no door moves the clock back."}
  [_row inp ctx]
  (let [seconds (:seconds (move-of inp (or (:now ctx) (Instant/now))))
        ahead (long (or (some-> (get-in ctx [:services :demo-clock :offset]) deref) 0))]
    (cond
      ;; a body that names no move is the-shift-names-one-move's
      (nil? seconds) (t/allow)

      (not (pos? (long seconds)))
      (t/deny {:vars {:problem (str "this shift is " seconds " seconds, which is not forward.")}})

      (< (long max-shift-seconds) (+ ahead (long seconds)))
      (t/deny {:vars {:problem (str "the clock is " ahead " seconds ahead already, and this shift would make it "
                                    (+ ahead (long seconds)) ", past " max-shift-seconds ".")}})

      :else (t/allow))))

;; ── the kind ────────────────────────────────────────────────────────

(defn- born
  "The birth stamp: the engine's total shift after this row."
  [row ctx]
  (let [ahead (long (or (some-> (get-in ctx [:services :demo-clock :offset]) deref) 0))
        seconds (:seconds (move-of (:data row) (or (:now ctx) (Instant/now))))]
    (assoc-in row [:data :offset_seconds] (+ ahead (long (or seconds 0))))))

(def ^:private move-fields
  "The move as its author writes it: exactly one of the two."
  [[:to {:optional true
         :examples ["2026-10-02T14:30:00Z"]
         :x-display {:raw true :label "To"
                     :help "The instant the engine's clock moves to: RFC 3339 in UTC (2026-10-02T14:30:00Z). It is later than the engine's clock is now. Leave it empty when you write `by`."}}
    [:maybe [:string {:min 1 :max 40}]]]
   [:by {:optional true
         :examples ["PT18H"]
         :x-display {:raw true :label "By"
                     :help "How far the engine's clock moves forward: an ISO-8601 duration of days, hours, minutes and seconds (PT18H, P1DT2H). Leave it empty when you write `to`."}}
    [:maybe [:string {:min 1 :max 40}]]]])

(defresource clock-shift
  {:kind :clock_shift
   :plural "clock_shifts"
   :nav :system
   :states [:applied]
   :initial :applied
   :terminal #{:applied}
   :summary "{data.offset_seconds} s ahead · {state}"
   :schema
   (into [:map]
         (conj move-fields
               [:offset_seconds {:x-display {:label "Ahead (seconds)"
                                             :help "The engine's total shift after this row: how many seconds its clock is ahead of the real one. Stamped by the engine."}}
                [:int {:min 0}]]))
   ;; the total is the engine's to write
   :create-schema (into [:map] move-fields)
   :create-guards [the-engine-is-a-demo the-shift-names-one-move
                   the-shift-is-forward-and-short]
   :on-create born
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "-created_at"}})

;; ── the assembly ────────────────────────────────────────────────────

(defn with-clock
  "Engine options with the demo clock laid over them: the `clock_shift`
  kind beside the application's own, a `:now-fn` that adds the offset to
  the real clock, and the services the guard, the birth and the walks
  read. The options' own `:now-fn` is taken as the real clock. Build the
  engine from the answer, then `install!` it."
  ([opts] (with-clock opts (clock (select-keys opts [:now-fn]))))
  ([opts c]
   (-> opts
       (update :resources #(conj (vec %) clock-shift))
       (assoc :now-fn (now-fn c))
       (update :services assoc
               :demo-clock c
               :recording-clock (:real-now-fn c)))))

(defn- stored-offset
  "The offset the rows say: the largest `offset_seconds` among the
  newest shifts, and zero on an engine nobody shifted."
  [eng]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (if-not rdef
      0
      (store/with-tx st
        (fn [tx]
          (transduce (map #(long (or (get-in (inv/decode-row rdef %)
                                             [:data :offset_seconds])
                                     0)))
                     max 0
                     (store/query-rows st tx kind {}
                                       {:limit restore-page :newest-first true})))))))

(defn- after-shift!
  "The post-commit pass of one shift: the clock takes the row's total,
  then the clock's passes run once. Never throws: the shift is
  committed, and the loops make the same passes within thirty seconds."
  [eng res]
  (when-some [c (get-in eng [:services :demo-clock])]
    (swap! (:offset c) max (long (or (get-in res [:row :data :offset_seconds]) 0)))
    (doseq [[what pass] [["scheduled actions" scheduled/sweep-due!]
                         ["invitations" invitations/sweep-expired!]]]
      (try
        (pass eng)
        (catch Exception e
          (warn! "the pass over " what " after a shift failed — " (ex-message e)))))))

(defn install!
  "Finish an engine built from `with-clock`'s options: the clock learns
  the engine its guard asks about, takes the offset the rows say, and
  `after-shift!` is woven into the engine's post-commit `:maintain` hook
  (after any installed maintainer). The engine is a map — build the
  handler / start! from the RETURNED engine. An engine built without
  `with-clock` is answered as it is."
  [eng]
  (if-some [c (get-in eng [:services :demo-clock])]
    (let [prior (:maintain eng)
          eng (assoc eng :maintain
                     (fn [engine k action-name res]
                       (let [res (if prior
                                   (or (prior engine k action-name res) res)
                                   res)]
                         (when (= (name kind) (some-> k name))
                           (after-shift! engine res))
                         res)))]
      (reset! (:engine c) eng)
      (swap! (:offset c) max (long (stored-offset eng)))
      eng)
    eng))
