(ns waymark10.server.seats.pools
  "The runner pool ONE fire of a schedule goes out through (S1b of
  docs/spec-seat.md §22), carved out of `waymark10.server.seats` and
  `waymark10.server.schedules` (ticket 7286d5b4): a seat's chair, the
  links whose `pools` name a schedule or a model, what a schedule fires
  through, and the checklist a schedule and its seat show while there is
  no pool to fire through.

  seats.clj and schedules.clj keep a one-line def for each var another
  namespace calls, so every `seats/` and `sch/` alias still resolves.
  This file requires nothing of either: both require it."
  (:require [waymark10.server.grants :as grants]))

(defn chair-of
  "The seat's CHAIR — the first model of its `held_for` (R-2 of
  waymark-fp62.7.23), or nil.

  FIRST, and said here so it is said once: the chair is the model
  whose Routine fires this seat and whose key its sessions present, so
  a step down the ladder is one `restate` of that list and no second
  Routine. A seat held for nothing has no chair — any model may sit in
  it and none of them answers for it. Reads a STORED row as happily as
  a decoded one, `interactive-seat?`'s posture and its reason: a ref
  is a string in the document either way. A connector token in the
  list (`connector:<client>`, R-5.7) names a client, not a model, and
  is skipped: the chair is the first entry that is a model."
  [seat-row]
  (some->> (get-in seat-row [:data :held_for])
           (map str)
           (remove grants/connector-token?)
           first
           not-empty))

(defn pooled-ids
  "The ids of the runner links among `links` whose `pools` names the
  schedule or model `kind` `id`, by that entry's rank, or nil. A retired
  link is dropped; a broken one is kept, so a pool whose every link
  broke is held, not empty."
  [links kind id]
  (let [kind (name kind)
        id (str id)]
    (some->> links
             (remove #(= "retired" (some-> (:state %) name)))
             (mapcat (fn [link]
                       (for [e (get-in link [:data :pools])
                             :when (and (= kind (some-> (:kind e) name))
                                        (= id (some-> (:id e) str)))]
                         [(or (some-> (:rank e) long) 0) (str (:id link))])))
             (sort-by first)
             (mapv second)
             not-empty)))

(defn pool-from
  "The runner link ids ONE fire of the schedule `sched` goes out
  through, in order, or nil: `schedules/pool-of`'s reading, spelled
  here so a door, a render and a wake read one pool (seats.clj cannot
  require schedules, which requires it). The schedule's pool wins when
  it has links; else the chair's. A pool is the links whose `pools`
  name the row — `links` is a thunk answering every runner link row."
  [links sched chair]
  (let [ls (links)]
    (or (when sched (pooled-ids ls :schedule (:id sched)))
        (when chair (pooled-ids ls :model (:id chair))))))

(defn- chair-row
  "The model row of the chair of the seat `schedule-row` fires, through
  `read'`, or nil."
  [read' schedule-row]
  (some->> (get-in schedule-row [:data :seat]) str not-empty
           (read' :seat)
           chair-of
           (read' :model)))

(defn fires-through
  "One line saying what ONE fire of this schedule goes out through
  (waymark ticket 360d207d), so a person sees a mislink before it
  fires: a runner pool, or none. `read'` is a render ctx's `:read`,
  (read' kind id); with none, the chair is not consulted. With the
  ctx's `:find` too, the pool is `pool-of`'s reading: the links whose
  `pools` name the row or its chair (S1b of docs/spec-seat.md §22)."
  [read' schedule-row & [ctx]]
  (let [chair (when read' (chair-row read' schedule-row))
        find' (:find ctx)
        pool (when find'
               (pool-from #(find' :runner_link {} {:limit 100000})
                          schedule-row chair))]
    (if pool
      (str "runner pool (" (count pool) " links)")
      "no link")))

(defn fires-through-field
  "The schedule's :computed `fires_through`: `fires-through` over the
  reads the render ctx lends."
  [row ctx]
  (fires-through (:read ctx) row ctx))

;; ── a schedule with no pool says so (ticket 7286d5b4) ────────────────
;; An empty held_for means any model may sit, so a seat may have no
;; chair, and with no link joined to its schedule's pool it never fires.
;; The schedule's checklist and its seat's name the remedy, so the
;; schedule's waiting_on while pending, and the seat's while active (an
;; unpark's answer among them), say what would make it fire.

(def no-pool-remedy
  "The cross-row remedy a schedule with no runner pool waits on."
  "join_pool a runner_link to this schedule's pool, or name a model in the seat's held_for so its chair's pool is used")

(defn pool-checklist
  "What ONE schedule waits on before a fire can go out: a runner pool,
  as `pool-from` reads it. Not ok, saying `no-pool-remedy`, while there
  is none."
  [links sched chair]
  (let [pool (pool-from links sched chair)]
    [{:item "a runner pool"
      :ok (boolean (seq pool))
      :says (if (seq pool)
              (str "fires through " (count pool) " runner links")
              no-pool-remedy)}]))

(defn pool-waits
  "The schedule's `waits_on_pool` checklist: `pool-checklist` over its
  pool and its seat's chair. `read'` and `find'` are a render ctx's."
  [read' find' schedule-row]
  (pool-checklist #(find' :runner_link {} {:limit 100000})
                  schedule-row
                  (chair-row read' schedule-row)))

(defn fire-pool
  "The seat's `fire_pool` checklist: `pool-checklist` over the schedule
  that fires it; nil for a seat no schedule fires, or whose schedule
  ended. The schedule is read through `find'`, the chair through
  `read'`."
  [read' find' seat-row]
  (when-some [sched (first (find' :schedule {:seat (str (:id seat-row))} {:limit 1}))]
    (when-not (= "ended" (some-> (:state sched) name))
      (pool-checklist #(find' :runner_link {} {:limit 100000})
                      sched
                      (some->> (chair-of seat-row) (read' :model))))))

(def ^:private checklist-schema
  [:maybe [:vector [:map [:item :string] [:ok :boolean] [:says :string]]]])

(def waits-on-pool-computed
  "The schedule's :computed `waits_on_pool`. It reads the seat, the
  chair and the links: with no read lent it is absent."
  {:schema checklist-schema
   :x-display
   {:label "Waits on a pool"
    :widget "checklist"
    :help "Whether this schedule has a runner pool to fire through, worked out at read time: its own pool, or its seat's chair's. Not ok while neither has a link; while the row is pending, its waiting_on names the remedy."}
   :reads? true
   :checklist #{:pending}
   :fn (fn [row ctx]
         (when (and (:read ctx) (:find ctx))
           (pool-waits (:read ctx) (:find ctx) row)))})

(def fire-pool-computed
  "The seat's :computed `fire_pool`. It reads the schedule, the chair
  and the links: with no read lent it is absent."
  {:schema checklist-schema
   :x-display
   {:label "Fires through a pool"
    :widget "checklist"
    :help "Whether a fire of this seat has a runner pool to go out through, worked out at read time. An empty held_for means any model may sit, so the seat has no chair; with no link joined to its schedule's pool either, it does not fire, and while it is active its waiting_on names the remedy."}
   :reads? true
   :checklist #{:active}
   :fn (fn [row ctx]
         (when (and (:read ctx) (:find ctx))
           (fire-pool (:read ctx) (:find ctx) row)))})
