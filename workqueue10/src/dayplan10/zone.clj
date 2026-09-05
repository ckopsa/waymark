(ns dayplan10.zone
  "The household's one clock, read once (docs/spec-dayplan.md, *The
  day boundary*). A context says *nine to noon* — a local clock time —
  and a span is an instant, so turning a template into a day needs a
  zone, and the feed's *today* needs the SAME zone or the current block
  would answer tomorrow's plan at dinner (waymark-rptq).

  WORKQUEUE10_ZONE names it; WORKQUEUE10_HA_ZONE (the Home Assistant
  boundary main.clj already reads) is the fallback; UTC is the last
  resort, said out loud rather than guessed from the JVM. Read at first
  use through a delay, so a test that never sets either sees UTC and a
  misspelt region fails the first materialisation loudly instead of
  quietly landing every window six hours off."
  (:import (java.time Instant LocalDate LocalDateTime LocalTime ZoneId)
           (java.time.format DateTimeFormatter DateTimeParseException)))

(set! *warn-on-reflection* true)

(def ^:private env-names ["WORKQUEUE10_ZONE" "WORKQUEUE10_HA_ZONE"])

(def ^:private household
  (delay (ZoneId/of (or (some #(not-empty (System/getenv ^String %)) env-names)
                        "UTC"))))

(defn id
  "The household zone — the one place both the day plan's
  materialisation and the feed's day boundary read it from."
  ^ZoneId []
  @household)

(def ^:private hh-mm (DateTimeFormatter/ofPattern "HH:mm"))

(defn clock-time
  "\"09:00\" → a LocalTime, nil when the string is not a 24-hour HH:MM
  clock time. The template's own grammar, parsed once here so the
  guard on context and the materialisation agree on what a window is."
  [s]
  (when (and (string? s) (= 5 (count s)))
    (try (LocalTime/parse s hh-mm)
         (catch DateTimeParseException _ nil))))

(defn at
  "A local clock time on a date, in the household zone, as the
  instant a span stores."
  ^Instant [^LocalDate date ^LocalTime t]
  (.toInstant (.atZone (LocalDateTime/of date t) (id))))

(defn window->instants
  "One template window {:from \"09:00\" :to \"12:00\"} on a date →
  {:starts_at Instant :ends_at Instant}, nil when either side is not a
  clock time (the context's guard refuses those at the door; this is
  the belt)."
  [^LocalDate date {:keys [from to]}]
  (when-some [f (clock-time from)]
    (when-some [t (clock-time to)]
      {:starts_at (at date f) :ends_at (at date t)})))

(defn clock
  "An instant as the household reads it on a card — HH:MM in the
  household zone — for refusal sentences; nil in, \"—\" out."
  [^Instant i]
  (if i
    (.format hh-mm (.atZone i (id)))
    "—"))
