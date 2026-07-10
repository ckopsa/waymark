(ns mealplan10.resources.event
  "The family calendar Event: a read-only Mirror of the Google
  Calendar feed (design 6.0 §1–§2's driving story, revised).

  Every earlier mealplan kept the calendar out of the system because
  the plan couldn't own it — a recital doesn't belong to a meal plan,
  it merely OVERLAPS one. The plan cites event through a
  date-containment predicate, so an event needs nothing but its own
  honest fields. Those fields are not authored here: the calendar is
  the family's, kept in Google Calendar, so event is a pure
  whole-resource Mirror — pulled, never written. The declared
  discovery sweep mints a resource per feed occurrence on the engine's
  cadence; fields arrive with the mint's eager batch pull (or the
  row's first pull-through read).

  kind still exists so the plan's overlap predicate has something to
  filter on, but every pulled event is minted note (non-blocking) for
  now — the feed carries no signal for \"this evening is spoken for\"
  (design note: revisit if that distinction turns out to matter)."
  (:require [waymark10.resource :as r]
            [waymark10.server.mirror :as mirror]))

;; a personal calendar changes rarely enough that an hour between
;; pull-throughs is plenty; the hourly discovery sweep picks up
;; newly-added events
(def ttl-seconds 3600)
(def discover-every 3600)

(defn event-resource
  "The event kind over one adapter — FakeEvents for tests/offline dev,
  GoogleCalendarEvents when MEALPLAN_GCAL_ICS_URL is set (see
  mealplan10.main). An event enters by its feed identity alone: the
  display fields are the calendar's, filled by the sync path."
  [adapter]
  (r/resource
   (mirror/declaration
    {:kind :event
     :summary "{data.title} · {data.date}"
     :schema [:map
              [:title {:optional true}
               [:maybe [:string {:max 120}]]]
              [:date {:optional true} [:maybe :waymark/date]]
              [:kind {:optional true}
               [:maybe [:enum "blocking" "note"]]]]
     :filterable {:date #{:eq :range}
                  :kind #{:eq :in}
                  :state #{:eq :in}}
     :sortable {:fields [:date] :default "date"}
     :display {:title "{data.title} — {data.date}"}}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every})))
