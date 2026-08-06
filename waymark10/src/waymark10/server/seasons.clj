(ns waymark10.server.seasons
  "GET /api/-/seasons: the transition log read as a SHAPE — weekly
  rhythm buckets over what moved (created / completed / other, per
  kind) plus an aging read over what quietly waits (non-terminal rows
  past two weeks old). The house can only show its current state, and
  tending on a snapshot degrades into nagging; the log already
  records the rhythm — this door is the first thing that reads it
  whole.

  The projection seam: the firehose 404s every grant-scoped caller
  as a recorded punt (router/firehose-events); seasons is the first
  history surface that PROJECTS instead. A scoped caller gets exactly
  the kinds its grant sees WHOLE (whole-kind-sight? — a scope entry
  naming the kind with no :ids and no :filters narrowing); every
  other kind — ids-narrowed, filter-narrowed, own-surface or
  ungranted — is byte-level absent from weeks and aging alike.

  Deliberate scope limit, load-bearing: seasons reads the WORK,
  never the people. No per-human activity profiles, no gaze history,
  no actor breakdown on the wire beyond the system-exclusion filter
  (mirror-sync's bookkeeping beat would otherwise dominate every
  count — include_system=1 invites it back in, still un-broken-down).
  That same line excludes the presence curtain's own touches
  (member draw_curtain/open_curtain, waymark-tti.4): when someone
  steps behind their curtain and back out is PERSON-rhythm, not work
  rhythm, and a weekly bar chart of it would be exactly the gaze
  history this surface refuses — the transition rows stay the audit
  they always were, they just never become a shape here."
  (:require [waymark10.server.curtain :as curtain]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store])
  (:import (java.time Duration Instant LocalDate ZoneOffset)
           (java.time.temporal ChronoUnit IsoFields)))

(set! *warn-on-reflection* true)

(def ^:private aging-cutoff-days 14)

(def ^:private aging-page
  "Per-state page for the aging read — cheap by construction (one
  index-ordered LIMIT per open state). A kind with more open rows
  than this reports the page's count: a floor, never a lie."
  500)

(defn clamp-weeks
  "The weeks query param: default 4, clamped 1..12; anything
  unparseable is the default."
  [s]
  (-> (or (some-> s parse-long) 4) (max 1) (min 12)))

(defn whole-kind-sight?
  "The projection seam's honest question: does this visibility see
  the kind WHOLE? ONE definition answers it — grants/visibility's
  :whole-kind? closure (a scope ENTRY with neither :ids nor :filter
  narrowing; waymark-tti.5 folded this door's private duplicate into
  it, and the fix that made it per-entry lands on both consumers at
  once). The own-kind fallback — grant, approval_request, the
  dwelling kinds — is per-owner sight, never whole, so it stays false
  here. A visibility without the closure (an older or hand-rolled
  one) conceals: fail toward concealment, never toward sight. Never
  approximate this with :row? sampling: a sample cannot tell a wide
  ids list from the whole kind."
  [vis kind]
  (boolean (when-some [whole-kind? (:whole-kind? vis)]
             (whole-kind? kind))))

(defn- person-rhythm?
  "Is this (kind, action) a PERSON's rhythm rather than the house's
  work? The presence curtain's two touches are (curtain/curtain-actions,
  the one spelling): drawing and opening a curtain is when someone
  chose to be unwatched, and bucketing that weekly would rebuild the
  gaze history this surface exists to refuse. The transitions
  themselves stay legible on the member row's log — deliberate
  posture, waymark-tti.4 — they simply never become a bar here."
  [kind action]
  (and (= "member" kind) (contains? curtain/curtain-actions action)))

(defn- week-label
  "\"2026-W29\" for a UTC week-start instant — the ISO week-based
  year, which near January 1 is not the calendar year."
  [^Instant start]
  (let [d (LocalDate/ofInstant start ZoneOffset/UTC)]
    (format "%d-W%02d"
            (.get d IsoFields/WEEK_BASED_YEAR)
            (.get d IsoFields/WEEK_OF_WEEK_BASED_YEAR))))

(defn- classify
  "action-name string → :created | :completed | :other for one kind,
  from its declaration: create births; an action whose declared :to
  lands in a terminal state — or one wearing a conventional closing
  name — completes; everything else (unknown names included: the log
  outlives declarations) is :other."
  [rdef]
  (let [terminal (set (:terminal rdef))
        closing (into #{"complete" "close" "accept"}
                      (comp (filter #(contains? terminal (:to (val %))))
                            (map (comp name key)))
                      (:actions rdef))]
    (fn [action]
      (cond
        (= "create" action) :created
        (contains? closing action) :completed
        :else :other))))

(defn- weekly-shape
  "The :weeks vector: exactly `weeks` buckets oldest-first, each
  {:week label :start iso :kinds {kind {:created n :completed n
  :other n}}} — empty buckets ride as {} so the panel's bars always
  have the full window to scale against."
  [kinds stats from weeks]
  (let [classifiers (into {}
                          (map (fn [[k rdef]] [(name k) (classify rdef)]))
                          kinds)
        tallies (reduce
                 (fn [m {:keys [week-start kind action n]}]
                   ;; a kind outside the projection (or the registry —
                   ;; the log outlives declarations) contributes nothing
                   (if-some [cf (when-not (person-rhythm? kind action)
                                  (get classifiers kind))]
                     (update-in m [week-start (keyword kind) (cf action)]
                                (fnil + 0) n)
                     m))
                 {} stats)]
    (vec
     (for [i (range weeks)
           :let [start (.plus ^Instant from (* 7 (long i)) ChronoUnit/DAYS)]]
       {:week (week-label start)
        :start (str start)
        :kinds (into {}
                     (map (fn [[k counts]]
                            [k (merge {:created 0 :completed 0 :other 0}
                                      counts)]))
                     (get tallies start {}))}))))

(defn- aging-shape
  "The :aging vector: per projected kind, among its non-terminal
  rows (the declaration's own reading of open — a kind declaring no
  terminal states ages everything), how many were created more than
  aging-cutoff-days ago and the age of the oldest. Kinds with
  nothing aging are absent — compact, and zero is not attention."
  [st kinds ^Instant now]
  (let [cutoff (.minus now (long aging-cutoff-days) ChronoUnit/DAYS)]
    (into []
          (keep
           (fn [[kind rdef]]
             (let [terminal (set (:terminal rdef))
                   open-states (remove terminal (:states rdef))
                   rows (store/with-tx st
                          (fn [tx]
                            (into []
                                  (mapcat
                                   #(store/query-rows st tx kind {:state %}
                                                      {:limit aging-page
                                                       :order-by :created_at}))
                                  open-states)))
                   old (filterv #(some-> ^Instant (:created-at %)
                                         (.isBefore cutoff))
                                rows)]
               (when (seq old)
                 (let [oldest (reduce (fn [^Instant a ^Instant b]
                                        (if (.isBefore a b) a b))
                                      (map :created-at old))]
                   {:kind (name kind)
                    :open_older_than_14d (count old)
                    :oldest_days (.toDays (Duration/between oldest now))})))))
          kinds)))

(defn report
  "The seasons document — {:waymark :window :weeks :aging}. vis nil
  (an unscoped human or system caller) sees every registered kind;
  a grant visibility projects per whole-kind-sight?, weeks and aging
  alike. include-system? false (the default posture) drops
  system-actor transitions at the store."
  [eng vis {:keys [weeks include-system?]}]
  (let [st (:storage eng)
        now ^Instant ((:now-fn eng))
        from (.minus (store/utc-week-start now)
                     (* 7 (long (dec weeks))) ChronoUnit/DAYS)
        visible? (if vis
                   (fn [k] (whole-kind-sight? vis k))
                   (constantly true))
        kinds (into (sorted-map)
                    (filter (comp visible? key))
                    (inv/resources eng))
        stats (store/with-tx st
                #(store/transition-stats st % from include-system?))]
    {:waymark "10"
     :window {:weeks weeks :from (str from) :to (str now)}
     :weeks (weekly-shape kinds stats from weeks)
     :aging (aging-shape st kinds now)}))
