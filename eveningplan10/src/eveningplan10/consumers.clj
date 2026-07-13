(ns eveningplan10.consumers
  "Populating a plan's evenings is client work — the engine's
  :on-create hook is read-only across kinds (:read/:find, no writer),
  so one resource's create can't cascade into another's. This is the
  durable version of that client: a named, at-least-once consumer
  (waymark10.server.consumers) of the transition log itself, so a
  plan's sessions appear no matter who created the plan — this
  process, curl, or the generic UI's own create form, none of which
  know about each other.

  At-least-once means a crash can redeliver the same event, so
  spawn-sessions-for-plan! has to tolerate seeing one plan's create
  twice: it skips dates that already have a session rather than
  always creating.

  Reads go through waymark10.dev (silent — dev/row and dev/rows print
  nothing); the write goes through waymark10.server.invoke/create!
  directly, not waymark10.dev/create!, so it runs as a named system
  actor and never prints a REPL-style transition line for what is,
  here, a background process."
  (:require [waymark10.dev :as dev]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.invoke :as inv]
            [waymark10.types :as t]))

(def ^:private system-principal
  (t/principal {:id "eveningplan10-consumer" :type :system
                :display "Evening-plan sessions consumer"}))

(defn- date-range [^java.time.LocalDate start ^java.time.LocalDate end]
  (take-while #(not (.isAfter ^java.time.LocalDate % end))
              (iterate #(.plusDays ^java.time.LocalDate % 1) start)))

(defn spawn-sessions-for-plan!
  "One transition record in, evening_session rows out when it's an
  evening_plan create: the plan's whole date range, minus whatever
  dates already have a session (the replay guard)."
  [eng t]
  (when (and (= :evening_plan (:kind t)) (nil? (:from-state t)))
    (let [plan (dev/row eng :evening_plan (:resource-id t))
          existing (into #{} (map #(get-in % [:data :date]))
                         (filter #(= (:plan_id (:data %)) (:id plan))
                                 (dev/rows eng :evening_session)))]
      (doseq [d (date-range (get-in plan [:data :start_date])
                             (get-in plan [:data :end_date]))
              :when (not (contains? existing d))]
        (inv/create! eng :evening_session {:plan_id (:id plan) :date (str d)}
                     {:principal system-principal})))))

(defn register!
  "Register the durable consumer against a RUNNING engine (one with
  engine/start!'s dispatcher — dev/serve! or eveningplan10.main's own
  start!). :from-origin? true: a first registration hears the whole
  transition log, so it backfills any plan already sitting there with
  no sessions, not just future ones. Returns the running consumer;
  stop! ends it."
  [eng]
  (consumers/register-consumer! eng :plan-sessions
                                (partial spawn-sessions-for-plan! eng)
                                {:from-origin? true}))

(defn stop! [running]
  (consumers/stop-consumer! running))
