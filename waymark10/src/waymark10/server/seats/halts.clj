(ns waymark10.server.seats.halts
  "The engine's own writes on a seat row, carved out of
  `waymark10.server.seats` (ticket b44dbe85, round three): the halt a
  wall marks and lifts (`seat-halt!`, `seat-clear-halt!`,
  `seat-halt-at-the-wall!`, with `halt-reasons`, the walls a seat halts
  for), the scope entries that stopped resolving (`mark-stale!`) and
  the ones whose `until` passed (`lapse!`). Each goes through one of the
  seat's concealed doors as the system actor, so the log reads it.

  seats.clj keeps a one-line def for each var another namespace or its
  own resource calls, so every `seats/` alias still resolves. This file
  requires nothing of seats: seats requires it."
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats.fire :refer [seats-actor]]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def halt-reasons
  "The walls of R-5.2, and the only reasons a seat halts. Each is HARD
  (the grant scopes to nothing) and each must reach a person, which is
  what `halt` is for — a halt is not a state, so the wall lifts on its
  own when the condition clears.

  The fourth is R-12.27's, and it is the running cost's own wall: a
  sitting past the seat's `sitting_budget_tokens` is spent while the
  seat's week is not, so it lifts the moment that sitting closes."
  #{"seat_not_active" "model_not_held" "budget_reached"
    "sitting_budget_reached"})

(def halt-mark
  "The wall a sit found its seat against, as the sit answered it:
  `wall`, `detail`, and `lifts_at` (absent when no roll of the window
  lifts it). Stamped on the sitting and on the seat's schedule (ticket
  ae64b57c), so a halted wake does not read as one that walked nothing."
  [:map
   [:wall [:string {:max 64}]]
   [:detail {:optional true} [:maybe [:string {:max 480}]]]
   [:lifts_at {:optional true} [:maybe [:string {:max 64}]]]])

(defn- seat-row [eng seat-id]
  (when (and seat-id (get (inv/resources eng) :seat))
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/load-row (:storage eng) tx :seat (str seat-id) {})))))

(defn seat-halt!
  "Write the wall this seat is against, through the concealed
  `mark_halted` — system actor, logged (R-7.7). IDEMPOTENT BY
  INTENT, not merely by the machine: a seat already halted for this
  reason is not written again, because the router meets the same wall
  on every request behind it and a feed item per request would be the
  alert shouting instead of speaking. → true when the halt was
  written, false when there was nothing to write."
  [eng seat-id reason detail]
  (let [row (seat-row eng seat-id)
        reason (str reason)]
    (if (and row
             (= :active (:state row))
             (contains? halt-reasons reason)
             (not= reason (str (get-in row [:data :halt :reason]))))
      (do (inv/invoke! eng :seat (:id row) :mark_halted
                       (cond-> {:reason reason}
                         (some-> detail str not-empty) (assoc :detail (str detail)))
                       {:principal seats-actor})
          true)
      false)))

(defn seat-clear-halt!
  "Lift the halt, through the concealed `clear_halt` — system actor,
  logged. Idempotent the same way: a seat with no halt is not written,
  so the router may call this on every request that passes. → true
  when a halt was cleared."
  [eng seat-id]
  (let [row (seat-row eng seat-id)]
    (if (and row
             (= :active (:state row))
             (some? (get-in row [:data :halt])))
      (do (inv/invoke! eng :seat (:id row) :clear_halt nil
                       {:principal seats-actor})
          true)
      false)))

(defn seat-halt-at-the-wall!
  "Mark the seat halted `budget_reached` when the week's fuel wall
  held it — a wake held back (`wakes/hold-at-the-wall!`) or a halted
  sit. A seat whose wakes are all held never makes the request the
  router halts on, so without this its row never said it. `detail` is
  the wall's sentence, cut to 240 characters. Best effort, the
  router's posture: a seat that moved under us is not an error here.
  Idempotent because `seat-halt!` is: one `mark_halted` across every
  hold. → true when the halt was written."
  [eng seat-id detail]
  (try
    (seat-halt! eng seat-id "budget_reached"
                (some-> detail str (as-> s (subs s 0 (min 240 (count s))))))
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 seats: seat" seat-id
                 "could not record its halt at the wall -" (ex-message e)))
      false)))

(defn mark-stale!
  "Write the scope entries that stopped resolving, through the
  concealed `mark_stale` — system actor, logged, the guard's own
  sentence as the note (R-7.2). The BOOT SWEEP that decides WHICH
  entries these are is not this file's; this is the door it writes
  through. Idempotent by intent: an unchanged stale list is not
  written again. → true when the list moved."
  [eng seat-id entries note]
  (let [row (seat-row eng seat-id)
        wire (schema/encode grants/scope-schema (vec entries))]
    (if (and row
             (= :active (:state row))
             (not= wire (get-in row [:data :stale])))
      (do (inv/invoke! eng :seat (:id row) :mark_stale
                       {:stale (vec entries)
                        :note (str note)}
                       {:principal seats-actor})
          true)
      false)))

(defn lapse!
  "Take the scope entries whose `until` has passed off one seat, through
  the concealed `lapse`: system actor, logged, and the input names each
  entry and its `because` (ticket b177a14b). The resolve judged them
  absent from their instant; this makes the row say so. A parked seat
  lapses through the same door and stays parked (ticket 50843da5). →
  how many entries went: 0 when none has ended, or the seat is neither
  active nor parked."
  [eng seat-id]
  (let [row (seat-row eng seat-id)
        now ((:now-fn eng))
        scope (vec (get-in row [:data :scope]))
        gone (grants/lapsed-entries now scope)
        note (str/join "; " (map (fn [e]
                                   (str (:kind e) " ["
                                        (str/join ", " (map str (:actions e)))
                                        "] ended " (:until e) ": "
                                        (or (:because e) "no reason given")))
                                 gone))]
    (if (and row (#{:active :parked} (:state row)) (seq gone))
      (do (inv/invoke! eng :seat (:id row) :lapse
                       {:scope (grants/live-entries now scope)
                        :lapsed gone
                        :note (if (< 2000 (count note)) (subs note 0 2000) note)}
                       {:principal seats-actor})
          (count gone))
      0)))
