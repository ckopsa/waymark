(ns waymark10.age
  "When a dated `:expected-age` falls due (docs/spec-calendar-time.md
  §2, §3): the one function the live cracks pass, waiting_on and a law
  scenario's `:age` all read (ticket 4b20e9e1), so a deadline is stated
  once and judged one way. Pure: no storage, and no clock but the `now`
  it is handed."
  (:require [waymark10.expr :as expr])
  (:import (java.time Instant LocalDate LocalTime ZoneId ZonedDateTime)))

(set! *warn-on-reflection* true)

(defn- text [v] (if (keyword? v) (name v) (str v)))

(defn data-value
  "A row's stored value as the law reads it: an ISO date or an RFC 3339
  instant spelled as a string becomes the date or the instant."
  [v]
  (if (string? v)
    (or (try (LocalDate/parse v) (catch Exception _ nil))
        (try (Instant/parse v) (catch Exception _ nil))
        v)
    v))

(defn- law-data
  "A row's data as the law reads it: keyword keys, `data-value` values."
  [data]
  (into {} (map (fn [[k v]] [(keyword (text k)) (data-value v)])) data))

(defn deadline
  "The instant past which a row is overdue under an age `entry` that is
  a map (docs/spec-calendar-time.md §2, §3), or nil when the entry is
  seconds, its answer is missing (an unset date) or the calendar it
  needs is. `{:business-days n}` counts from `at`, the row's entry to
  the state: the same local time n business days later on the
  governing calendar. `{:until form}` is evaluated over the row's
  data: an instant is the deadline, and a date is overdue once that
  day ends in the calendar's zone. `calendar` is the :calendar hook. A
  `(follow (data :f) :g)` in the form reads the linked row through the
  `:read` hook the calendar hook carries as its metadata
  (`invoke/render-hooks`); with none, or an unset ref or date, there is
  no deadline."
  [entry row ^Instant at ^Instant now calendar]
  (let [cal (when calendar (calendar nil))
        zone (:zone cal)
        read (:read (meta calendar))
        scope (cond-> {:now now}
                calendar (assoc :calendar calendar)
                read (assoc :read (fn [kind id]
                                    (some-> (read kind id) (update :data law-data)))))]
    (cond
      (not (map? entry)) nil

      (pos-int? (:business-days entry))
      (when (and (instance? ZoneId zone) at)
        (let [z (.atZone at ^ZoneId zone)
              d (expr/evaluate (list '+ '(var :entered) (list 'business-days (:business-days entry)))
                               (assoc scope :vars {:entered (.toLocalDate z)}))]
          (when (instance? LocalDate d)
            (.toInstant (ZonedDateTime/of ^LocalDate d ^LocalTime (.toLocalTime z) ^ZoneId zone)))))

      (contains? entry :until)
      (let [v (expr/evaluate (expr/normalize (:until entry))
                             (cond-> (assoc scope :data (law-data (:data row)))
                               (some? (:state row)) (assoc :state (expr/state-of row))))]
        (cond (instance? Instant v) v
              (and (instance? LocalDate v) (instance? ZoneId zone))
              (.toInstant (.atStartOfDay (.plusDays ^LocalDate v 1) ^ZoneId zone)))))))
