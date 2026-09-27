(ns waymark10.holds
  "Holds: the guards whose refusal is not a 409 but a call that waits
  for a person's tap (server/delegation's held seat call, generalised).

  A HOLD IS DECLARED ON THE GUARD. A guard written with `:hold true`
  is registered here when its module loads (`guards/guard` does it),
  and the router asks `hold?` of every refusal: a registered guard's
  refusal becomes a `held_call` row the person's Allow replays, every
  other refusal stays the 409 it always was. No list of names lives
  anywhere else, so a module that writes a new wall of this kind needs
  nothing but the option.

  THE REPLAY IS RECOGNISED BY `:within`. The engine's own replay of an
  allowed held call (held-calls/forward!) invokes the door as the
  caller, with `:within` naming the held row. `allowed-hold` reads that
  row back through the write's own transaction and admits the call
  only while it is `allowed` and names this kind, this door, this row
  and this caller. A hand at the wire cannot set `:within`, so it
  cannot forge the person's yes. A guard that holds calls it before it
  denies, so the person's Allow is the one thing that opens it.

  Pure: no server namespace is required here, so a module's guards
  (factory10, workqueue10) may call it."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; ── the registry ────────────────────────────────────────────────────

(defonce ^:private registry (atom #{}))

(defn register!
  "Record that this guard's refusal is a hold. Idempotent: a guard
  rebuilt from its declaration registers the same name again."
  [guard-name]
  (swap! registry conj (keyword (name guard-name)))
  guard-name)

(defn registered
  "The names of every guard a loaded module declared `:hold true`."
  []
  @registry)

(defn hold?
  "Is this refusal's guard a hold? The problem document carries the
  guard's name as a keyword or its string, depending on which side of
  the wire read it."
  [guard]
  (contains? @registry (some-> guard name keyword)))

;; ── the replay check ────────────────────────────────────────────────

(defn- nonblank [x] (some-> x str str/trim not-empty))

(defn allowed-hold
  "The held call this write replays, when it is the engine's replay of
  one the person allowed, or nil. `:within` names the held row, and
  the row is read back through the write's own transaction: it must be
  `allowed`, name this kind and this row, name this caller, and name
  this door when `action` is given. `target` is the row id, nil at a
  create door. A ctx with no `:read` hook (the render probe, a
  scenario) is answered nil: no replay is ever rehearsed."
  ([ctx kind target] (allowed-hold ctx kind nil target))
  ([ctx kind action target]
   (let [{wkind :kind waction :action hid :id} (:within ctx)
         read' (:read ctx)]
     (when (and (= :held_call wkind) (= :allow waction) hid read')
       (when-some [h (read' :held_call (str hid))]
         (let [door (get-in h [:data :door])]
           (when (and (= :allowed (some-> (:state h) name keyword))
                      (= (name kind) (str (:kind door)))
                      (or (nil? action) (= (name action) (str (:action door))))
                      (= (some-> target str) (nonblank (:id door)))
                      (= (str (get-in ctx [:principal :id]))
                         (str (get-in h [:data :caller]))))
             h)))))))

(defn approved-hold?
  "`allowed-hold`, as the yes-or-no a guard asks."
  ([ctx kind target] (some? (allowed-hold ctx kind target)))
  ([ctx kind action target] (some? (allowed-hold ctx kind action target))))
