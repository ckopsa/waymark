(ns waymark10.server.seats
  "Seats, models and sittings: the office, the price list, and the
  wake (docs/spec-seat.md §§ 4, 9, 10).

  A SEAT IS THE UNIT OF THE BILL. It groups work that needs one level
  of judgment, it names the cheapest model that can hold that work,
  and it records what the work cost. The charter holds the residual —
  the judgment the engine cannot say at a door — and nothing else,
  because every sentence a model must pre-load is fuel and every rule
  written in prose is a fence the engine is not yet speaking (R-4.10).
  The seat measures; waymark lowers.

  A SEAT IS FLUID AND ITS SCOPE IS NOT A COPY. The drift this kind
  exists to end is on record three times over (spec § 3): a grant's
  scope was a copy of an ask, each extend copied it forward again, and
  the copies rotted — 74 entries for 20 kinds (waymark-ycp), a stored
  scope naming a retired action (waymark-enx), a default expiry copied
  onto a grant nobody meant to shorten (waymark-h6y). A seat grant
  carries NO scope: the router resolves the seat row at each request
  (R-5.2), so a `restate` moves every sitter's leash with no new grant
  and nothing to copy. What this file owns is the row that resolution
  reads.

  THE THREE KINDS, AND WHY THEY ARE ONE FILE. A seat's `held_for` is a
  list of models, a sitting's `model` is one, and the sitting's close
  reads the model's prices AT THAT MOMENT and writes them down beside
  the cost — so a reprice tomorrow cannot rewrite what last week cost
  (R-10.4). Three kinds, one law about money, and splitting them would
  put that law in three places.

  ── what the ENGINE writes, and what a person writes ────────────────

  `stale`, `halt`, `schedule` and `merged_into` are engine-written and
  absent from the create door and from `restate` — the members.clj
  write-fence posture (`reentry-not-written-by-hand`), applied by
  omission rather than by a guard: an action's input is a closed map
  and the create model simply does not declare them, so no hand can
  smuggle one in. They are written by three CONCEALED transitions —
  `mark_stale`, `mark_halted`, `clear_halt` — each system-actor,
  logged, and hidden from every envelope the way members' `bind` and
  `stamp_subject` are. `absorb` is the fourth, and it is the one door
  a merge opens on the seat it folds INTO (§ 6); it reads `:within`
  rather than the principal, the composition_request precedent, so it
  is reachable from `merge`'s own handler and from nowhere else.

  ── what wave two calls ─────────────────────────────────────────────

  Six public fns, and they are the whole of this namespace's seam
  (the second is the session-end door's, R-12.17):

      (open-sitting-for-grant eng grant-id) → the open sitting or nil
      (open-sitting-for-seat eng seat-id [harness-session])
      (bump-counter! eng sitting-id :transitions|:refusals)
      (add-served! eng sitting-id tool bytes [dropped])
      (seat-halt! eng seat-id reason detail)
      (seat-clear-halt! eng seat-id)

  plus `mark-stale!` for the boot sweep (R-7.2), which this file does
  not own, and three the KEYED SITTER SESSION calls (R-12.13):

      (seat-by-key eng key)       → the active seat holding that key
      (sitter-id seat-row)        → seat:<id>, the sitter's member id
      (sitter-display seat-row)   → what a person reads beside it

  ── the sitter key, and what it is for ─────────────────────────────

  Every session of a person's connector is the SAME delegate on the
  SAME bearer, so a Routine's session and the person's own chat are
  one credential and cannot be told apart by it. `sitter_key` is what
  tells them apart: the person mints 128 bits, offers them to the
  seat, and pastes them into the Routine's instructions. The session
  that presents the key once, at its start, is that seat's sitter for
  the rest of its life — and every other session of the same person is
  untouched. The key is :secret and minter-supplied (the
  `reentry_token` posture), written by `offer_key` and cleared by
  `revoke_key`, and `key-not-written-by-hand` refuses it at every
  other door.

  Recorded deviations and named punts (each a sentence, per the
  discipline; the per-kind `:deviations` carry the ones that belong to
  a declaration):

  - `schedule` IS A TYPED REF, AND THAT COSTS A COUPLING. A
    `:waymark/ref` names its target kind at the declaration and
    checks-assembly refuses one whose kind is not registered, so this
    kind cannot boot on an engine assembled without the `:schedules`
    module. The coupling is already mutual and unavoidable —
    `schedule` holds typed refs to `seat` AND `model` — and both
    modules enrol `:always`, so the alternative (an opaque id string)
    would have bought nothing and lost the picker, the navigable
    reference and the dangling-ref check.
  - THE SITTER'S OWN-SURFACE IS NOT A DECLARATION. R-4.9 wants the
    seat row readable by its sitters with no scope entry, and a sitter
    is identified THROUGH THE GRANT (`grant.seat`). `:own-surface :by`
    names a field of the row being READ, and the seat row carries no
    sitter field and must not grow one — a seat with a sitter column
    would be a second copy of the grant. Wave two spelled the courtesy
    where the sitter is identified instead: grants.clj's seat resolve
    adds `{kind \"seat\", ids [<the cited seat>], actions []}` to the
    scope it computes, so the read rides the ordinary admission
    algebra and no second one exists.
  - `one-spelling` IS SPELLED TWICE. roles.clj's guard is the
    precedent and the name; two kinds in one namespace cannot both be
    `one-spelling`, so the vars are `one-seat-spelling` and
    `one-model-spelling`. The law is roles.clj's verbatim, once per
    registry.
  - THE COUNTERS ARE A MAINTENANCE WRITE. R-10.6 has the router add
    one to `transitions` on every committed transition and one to
    `refusals` on every 409. A logged transition per count would
    DOUBLE the transition log — the counter would cost more log than
    the thing it counts — so `bump-counter!` is `store/update-data!`,
    jobs.clj's progress precedent: document only, version untouched,
    no transition. The sitting's own `close` is a real transition and
    freezes the counts.
  - `standing_ttl_seconds` IS FENCED TWICE. The schema's `:max` is a
    year, which is what gives the field a published constraint; the
    real ceiling is `members/reentry-standing-ttl-seconds` and it
    lives in the guard, where the refusal can say what to ask for
    instead."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.math RoundingMode)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(set! *warn-on-reflection* true)

;; ── the engine's own hand ───────────────────────────────────────────

(def seats-actor
  "The system actor the sweep and the router write seats as — the
  definitions `deploy` and members `registrar` precedent."
  (t/principal {:id "waymark10-seats" :type :system :display "Seats"}))

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

(def seat-modes
  "The two ways a seat is sat in (R-10.8). A FIRED seat is the seat's
  work day: a cadence, a wake or a person's `fire` starts a run, and
  the run's own hook closes the sitting. An INTERACTIVE seat is its
  training day: a person sits in it from their own machine, across
  turns and hours, and nothing fires it."
  ["fired" "interactive"])

(def default-mode
  "What every seat was before the field existed, and what a seat is
  when nobody says otherwise."
  "fired")

(def interactive-mode "interactive")

(defn interactive-seat?
  "Is this seat one a person sits in (R-10.8)? Reads a STORED row as
  happily as a decoded one — an enum crosses the wire as its own
  string either way — so the schedules consumer, the wake consumer
  and the `fire` door all ask the question the same way. A row
  written before the field existed is `fired`, which is what it was."
  [seat-row]
  (= interactive-mode (some-> (get-in seat-row [:data :mode]) str)))

(def ^:private million (bigdec 1000000))

(def cost-scale
  "Decimal places a sitting's cost is written to. Six, because a turn
  on an economy model costs a fraction of a cent and a ledger that
  rounded it to the penny would report zero for a week of work."
  6)

;; ── guards: who may touch a seat ────────────────────────────────────

(g/defguard a-person
  {:reads [:principal]
   :explain "A seat is an office a person opens, restates, parks and closes — in person, or through a tool the person is signed in to. An agent does not open its own office: ask for a grant that cites a seat somebody already opened, and the sitting is yours."}
  [_row _inp ctx]
  ;; :human, or a DELEGATE — an :agent principal the identity gate
  ;; marked :acts-for, which is a person signed in through a tool
  ;; (spec-connector-door § 3): the members gate admits it only while
  ;; that person is an active member, and the mark is the gate's own,
  ;; never a request's. Not a bare :agent (the whole point) and not
  ;; :system either: the engine's own actors reach every handler
  ;; through one ctx :invoke, and a seat is the one row whose
  ;; authority a system path must not be able to widen. The concealed
  ;; transitions below are where the engine writes, and they say so
  ;; out loud. Ruled 2026-09-17 (spec-seat.md § 17): the owner's
  ;; connector opens the first seat, and the person behind the
  ;; delegate is the person the rule always meant.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

;; THE SITTER KEY'S WRITE FENCE (R-12.12), members.clj's
;; `reentry-not-written-by-hand` made real for this kind: sitter_key is
;; a schema field and a :secret one, so a create or a restate that
;; could carry it would stamp a LIVE credential with no mint door, no
;; audit and nothing a reader could ever see again. The one writer is
;; `set-sitter-key`, under `offer_key`'s own guards. The field IS
;; declared on both inputs, deliberately — a guard may only judge a
;; field of the door it stands on (checks/check-create-guards), and a
;; fence nobody can name is a fence nobody can read. Both spellings
;; are :secret, so no form asks and no advertised input names it.
(g/defguard key-not-written-by-hand
  {:judges [:sitter_key]
   :explain "The sitter key is written by offer_key alone, never by hand — a create or a restate may not carry sitter_key. Open the seat first, then offer it a key."}
  [_row inp _ctx]
  (if (contains? inp :sitter_key)
    (t/deny)
    (t/allow)))

(g/defguard one-seat-spelling
  {:judges [:name]
   :reads [:seat]
   :open "The taken spellings are the seats collection, one query away; enumerating them into the create form would offer exactly the tokens the guard is about to refuse."
   :explain "A seat named {name} is already open — one spelling per seat, or an ask and a grant name different offices with the same word."}
  [_row inp ctx]
  (if-some [find' (:find ctx)]
    (if (and (some? (:name inp))
             (seq (find' :seat {:name (str (:name inp)) :state "active"}
                        {:limit 1})))
      (t/deny {:vars {:name (str (:name inp))}})
      (t/allow))
    (t/allow)))

(g/defguard not-a-sitter
  {:reads [:principal :now :grant]
   :explain "A sitter does not widen its own office. The authority of a seat is decided by the person who opened it, and a hand that holds a grant citing this seat is the hand that would be widening itself. Ask a person, or file an approval_request naming what the seat is missing."}
  [row inp ctx]
  ;; A grant CITES a seat through `grant.seat` — the field wave two
  ;; adds (R-5.1). Until it exists this check reads nil off every
  ;; grant and finds nobody, which is the honest answer for an engine
  ;; where no grant can cite a seat yet.
  (let [p (:principal ctx)
        find' (:find ctx)]
    (if (or (= :system (:type p)) (nil? find'))
      (t/allow)
      (let [now (:now ctx)
            live? (fn [gr]
                    (and (= :accepted (:state gr))
                         (let [e (get-in gr [:data :expires_at])]
                           (or (nil? e) (neg? (compare now e))))))
            cited (into #{}
                        (comp (filter live?)
                              (keep #(some-> (get-in % [:data :seat]) str)))
                        (find' :grant {:audience (:id p)} {:limit 100}))]
        (cond
          (empty? cited) (t/allow)
          ;; the create door: there is no seat yet to compare against,
          ;; and R-4.4 still says the actor is "a person, not a
          ;; sitter" — a sitter that could open a fresh office with a
          ;; scope of its choosing would be widening itself sideways
          (nil? (:id row)) (t/deny)
          :else (if (some cited
                          (remove nil? [(str (:id row))
                                        (some-> (:into inp) str)]))
                  (t/deny)
                  (t/allow)))))))

(g/defguard drop-inside-scope
  {:judges [:substitute_drop :scope]
   :vars [:kind]
   :open "A drop list narrows the seat's own scope, entry by entry; the vocabulary is the scope above it in the same form, not a registry this form could enumerate."
   :explain "A substitute gets the seat's scope MINUS this list, so every entry must be inside it: the {kind} entry names a kind, or an action on one, that the scope above does not admit. Add it to the scope, or drop it from the drop list."}
  [_row inp _ctx]
  (let [by-kind (into {} (map (fn [e] [(str (:kind e))
                                       (into #{} (map str) (:actions e))]))
                      (:scope inp))
        bad (some (fn [e]
                    (let [k (str (:kind e))
                          admitted (get by-kind k)]
                      (when (or (nil? admitted)
                                (seq (remove admitted (map str (:actions e)))))
                        k)))
                  (:substitute_drop inp))]
    (if bad (t/deny {:vars {:kind bad}}) (t/allow))))

(g/defguard ttl-within-standing
  {:judges [:standing_ttl_seconds]
   :vars [:max_days :asked_days]
   :explain "A seat's longest leash is {max_days} days — the standing rotation's own window — and this one asks for {asked_days}. Ask for less; a sitter whose grant lapses files a fresh ask, which costs one tap and leaves a record."}
  [_row inp _ctx]
  (let [asked (long (or (:standing_ttl_seconds inp) 0))]
    (if (<= asked (long members/reentry-standing-ttl-seconds))
      (t/allow)
      (t/deny {:vars {:max_days (quot (long members/reentry-standing-ttl-seconds)
                                      86400)
                      :asked_days (quot asked 86400)}}))))

(g/defguard held-for-active-models
  {:judges [:held_for :substitute_for]
   :reads [:model]
   :vars [:model]
   :open "The models are the models collection, one query away — and the list a seat names is a handful of rows, not a vocabulary a create form could recite."
   :explain "A seat is held for models that can still sit, and {model} is retired or unknown. Reactivate it, or name a model that is active; an empty list means any model may sit."}
  [_row inp ctx]
  (if-some [read' (:read ctx)]
    (let [bad (some (fn [id]
                      (let [r (read' :model (str id))]
                        (when-not (and r (= :active (:state r))) (str id))))
                    (concat (:held_for inp) (:substitute_for inp)))]
      (if bad (t/deny {:vars {:model bad}}) (t/allow)))
    ;; the pure render probe carries no read hooks — advertise
    ;; optimistically there (members' reentry-minters precedent); the
    ;; real invoke, which DOES carry them, is the wall
    (t/allow)))

(g/defguard walk-names-a-kind-in-scope
  ;; :judges names :walk alone, though the check reads the scope beside
  ;; it: the refusal is ABOUT the walk, and a judged field needs either
  ;; a published constraint or an :open acknowledgment (check-closure).
  ;; :walk has a maxLength and the scope is a list of maps that has
  ;; neither — declaring both would buy an :open this guard does not
  ;; need and a picker warning nobody could clear.
  {:judges [:walk]
   :reads [:services]
   :vars [:walk :problem]
   :explain "A seat walks a queue it can already see, one row at a time, in the order the queue's own default sort gives: {walk} {problem}."}
  [_row inp ctx]
  (if-some [walk (some-> (:walk inp) str str/trim not-empty)]
    (let [rdef-of (:rdef-of ctx)
          in-scope? (boolean (some #(= walk (str (:kind %))) (:scope inp)))
          rdef (when rdef-of (rdef-of walk))]
      (cond
        (not in-scope?)
        (t/deny {:vars {:walk walk
                        :problem (str "is not a kind this seat's scope opens"
                                      " — add an entry for it, or leave walk"
                                      " empty and the seat walks nothing")}})
        ;; the probe ctx carries no registry — decline to guess
        (nil? rdef-of) (t/allow)
        (nil? rdef)
        (t/deny {:vars {:walk walk
                        :problem "is not a kind this engine serves"}})
        (nil? (get (:default-filters rdef) :state))
        (t/deny {:vars {:walk walk
                        :problem (str "declares no default filter over state,"
                                      " so the collection a firing opens is"
                                      " every row of it rather than the work"
                                      " waiting — walk a kind whose queue"
                                      " filters itself")}})
        :else (t/allow)))
    (t/allow)))

;; ── what wakes a seat is named the way its scope is (R-12.22) ───────
;;
;; A `wake_on` entry is a scope entry's shape, so it is judged the way
;; a scope entry is: the kind must be one this engine serves, and each
;; action must be one that kind actually has. The scope guards next
;; door cannot be borrowed for it — a guard judges ONE named field
;; (`:judges`), and theirs is `:scope` — so the law is stated again
;; here over `:wake_on`, in their own two sentences. An entry nobody
;; can match is a seat that never wakes and never says why.

(defn- wake-on-unknown-kind
  "The first `wake_on` entry naming something this engine does not
  serve, or nil. `names-of` is the ctx's action-name lookup: nil for a
  kind the registry has never heard of."
  [names-of entries]
  (first (for [e entries
               :let [k (str (:kind e))]
               :when (nil? (names-of k))]
           k)))

(defn- wake-on-unknown-action
  "The first `wake_on` entry naming an action its own kind does not
  have, as the refusal's vars, or nil."
  [names-of entries]
  (first (for [e entries
               :let [known (names-of (str (:kind e)))]
               :when known
               a (:actions e)
               :when (not (contains? known (str a)))]
           {:kind (str (:kind e)) :action (str a)
            :actions (str/join ", " (sort known))})))

(g/defguard wake-on-names-real-kinds
  {:judges [:wake_on]
   :reads [:services]
   :vars [:kind]
   :open "The legal kind names are well-known's resources, one GET away; enumerating the registry into this form would duplicate it."
   :explain "A seat is woken by a kind this surface serves; there is no kind {kind}."}
  [_row inp ctx]
  (if-some [names-of (:action-names ctx)]
    (if-some [bad (wake-on-unknown-kind names-of (:wake_on inp))]
      (t/deny {:vars {:kind bad}})
      (t/allow))
    ;; the pure render probe carries no registry — decline to guess,
    ;; exactly as the scope guards do
    (t/allow)))

(g/defguard wake-on-names-real-actions
  {:judges [:wake_on]
   :reads [:services]
   :vars [:kind :action :actions]
   :open "Each kind's action vocabulary is well-known's actions list, one GET away; the refusal spells the kind's real actions when an entry misses."
   :explain "There is no action {action} on {kind}; its actions are: {actions}. A wake_on entry with no actions wakes this seat for nothing."}
  [_row inp ctx]
  (if-some [names-of (:action-names ctx)]
    (if-some [bad (wake-on-unknown-action names-of (:wake_on inp))]
      (t/deny {:vars bad})
      (t/allow))
    (t/allow)))

(defn- field-moved?
  "Did a restate change this field? One spelling, because two doors
  ask it: the note guard below, and the halt line the restate lifts
  (R-1 of waymark-fp62.7.13).

  A LIST OF REFS compares as strings — a ref is an id, whatever type
  carried it here — and a DECIMAL compares by value and not by scale,
  because 12 and 12.00 are the same dollars and a line lifted by a
  scale is a record dropped by nothing."
  [row inp f]
  (let [was (get-in row [:data f])
        asked (get inp f)]
    (cond
      (or (vector? was) (vector? asked))
      (not= (mapv str asked) (mapv str was))

      (and (decimal? was) (decimal? asked))
      (not (zero? (compare was asked)))

      :else (not= was asked))))

(g/defguard step-carries-a-note
  {:judges [:note]
   :explain "A step up or down the ladder is a record: a restate that changes held_for or substitute_for carries a note saying which model it was, which it is now, and why. Everything else about a seat may move silently; the model it is held for may not."}
  [row inp _ctx]
  (if (and (or (field-moved? row inp :held_for)
               (field-moved? row inp :substitute_for))
           (str/blank? (str (:note inp))))
    (t/deny)
    (t/allow)))

(g/defguard merge-target-is-active
  {:judges [:into]
   :reads [:seat]
   :vars [:into]
   :explain "A merge folds this seat into a LIVE one: {into} is not an active seat, or it is this seat. Name the office the work is moving to."}
  [row inp ctx]
  (let [into-id (some-> (:into inp) str)]
    (cond
      (nil? into-id) (t/allow)          ; the schema refuses the blank
      (= into-id (str (:id row))) (t/deny {:vars {:into into-id}})
      (nil? (:read ctx)) (t/allow)      ; probe ctx — decline to guess
      :else (let [target ((:read ctx) :seat into-id)]
              (if (and target (= :active (:state target)))
                (t/allow)
                (t/deny {:vars {:into into-id}}))))))

(g/defguard the-engines-own-hand
  {:reads [:principal]
   :hide true
   :explain "The sweep and the router write this, never a hand at the wire."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(g/defguard folded-by-a-merge
  {:reads [:within]
   :hide true
   :explain "A seat's scope grows by a merge and by nothing else: POST merge on the seat being folded in, and this fold happens in the same transaction."}
  [_row _inp ctx]
  ;; THE :within READ (waymark-jfv.20, composition_request's
  ;; precedent). At the wire and on the render probe it is nil and the
  ;; door is concealed, which is the truth: no client taps it. Inside
  ;; `merge`'s own handler it names that write, and the door opens.
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :seat kind) (= :merge action))
      (t/allow)
      (t/deny))))

;; ── the fire door's four guards (R-12.19, R-12.20) ──────────────────
;;
;; Each refuses with ONE sentence, and each says what to do next. A
;; fire is fuel: the engine never fires a seat behind a wall, and
;; never fires one whose Routine nobody has linked.

(g/defguard a-person-or-the-engine
  {:reads [:principal]
   :explain "A fire is a person's act or the engine's. An agent with no person behind it does not fire a seat. Ask the person who opened this seat, or file an approval_request."}
  [_row _inp ctx]
  ;; `a-person` plus the engine. The engine is here because the wake
  ;; consumer fires through this same door (R-12.22) and a second,
  ;; concealed door would be the same law written twice; a BARE agent
  ;; is refused exactly as it is at every other seat door.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (= :system type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

(g/defguard not-parked
  {:explain "The seat is parked. Unpark it first."}
  [row _inp _ctx]
  ;; `parked` is in the door's from-set on purpose: a refusal that
  ;; names the park and says the way back is worth more than a 409
  ;; saying the door is not there.
  (if (= :parked (:state row))
    (t/deny)
    (t/allow)))

(g/defguard not-interactive
  {:explain "The seat is an interactive seat. A person sits here; nothing fires it."}
  [row _inp _ctx]
  ;; R-10.8. The mode is the SEAT'S, so the wall is on the seat's own
  ;; door rather than on the principal: a person's fire, a wake and
  ;; the schedules consumer's cadence all arrive here, and an
  ;; interactive seat is woken by none of the three.
  (if (interactive-seat? row)
    (t/deny)
    (t/allow)))

(def ^:private wall-sentences
  "One sentence for each wall, for the rare halt that carries no
  detail — a row written before the router had a sentence, or one
  written by hand in a test. The router's own detail wins where it is
  there, which is why these are short."
  {"seat_not_active" "The seat serves nothing until somebody opens it again."
   "model_not_held" "The session's model is not one this seat is held for."
   "budget_reached" "The week's fuel is spent. The wall lifts as the window rolls."
   "sitting_budget_reached" "This sitting's fuel is spent. Close the sitting; a new one opens fresh."})

(def ^:private wall-exits
  "What lifts each wall, said at the fire door (R-4 of
  waymark-fp62.7.13). A halt line is a RECORD of a wall, not a lock:
  the door judges the week's fuel again for itself, so the two walls
  it cannot judge with no sitter in the room must say what a person
  does about them."
  {"seat_not_active" "Unpark the seat and the line lifts."
   "model_not_held" "Restate the seat to hold the model that sits here, and the line lifts."
   "budget_reached" "The wall lifts on its own as the window rolls."
   "sitting_budget_reached" "The wall lifts when that sitting closes."})

(defn- fuel-left?
  "Is there fuel left in this seat's week? The reading is the WALL'S
  own — `grants/spent-with` over `grants/week-spend-conds`, through
  the ctx `:sum` hook — so the door and R-5.2 step 3 cannot disagree
  about one number. nil when there is no hook to ask with: a ctx
  without one cannot judge the week, and the caller then reads the
  line as it stands."
  [row ctx]
  (when (and (:sum ctx) (:now ctx))
    (grants/under-budget? (grants/spent-with (:sum ctx) (:id row) (:now ctx))
                          (get-in row [:data :budget_usd_per_week]))))

(g/defguard not-halted
  {:reads [:storage]
   :vars [:wall :exit]
   :explain "The seat is against a wall. {wall} {exit}"}
  [row _inp ctx]
  ;; R-3: the halt line is a record of a wall that HELD, and the
  ;; rolling window lifts the fuel wall with no hand. A fired seat
  ;; makes no request of its own that could clear the line, so the
  ;; door that reads the line judges that one wall again. The other
  ;; three it cannot: `model_not_held` needs the sitter's claim, a
  ;; parked seat is a person's choice, and a sitting past its ceiling
  ;; is an interactive seat's, which `not-interactive` already refuses.
  (if-some [halt (get-in row [:data :halt])]
    (let [reason (str (:reason halt))]
      (if (and (= "budget_reached" reason) (true? (fuel-left? row ctx)))
        (t/allow)
        (t/deny {:vars {:wall (or (some-> (:detail halt) str not-empty)
                                  (get wall-sentences reason)
                                  "Wait for the wall to lift.")
                        :exit (get wall-exits reason
                                   "The wall lifts when the condition clears.")}})))
    (t/allow)))

(g/defguard linked-for-fire
  {:reads [:schedule]
   :explain "Link the Routine's fire URL and token to the schedule first."}
  [row _inp ctx]
  ;; The schedule is read through the ctx `:find` hook — the write's
  ;; own transaction, `schedules/one-per-seat?`'s spelling exactly. A
  ;; ctx without the hook (the pure render probe) advertises
  ;; optimistically, as every cross-row guard here does.
  (if-some [find' (:find ctx)]
    (let [sched (first (find' :schedule {:seat (str (:id row))} {:limit 1}))]
      (if (some-> (get-in sched [:data :fire_url]) str not-empty)
        (t/allow)
        (t/deny)))
    (t/allow)))

(g/defguard one-model-spelling
  {:judges [:name]
   :reads [:model]
   :open "The registered identifiers are the models collection, one query away; enumerating them into the create form would offer exactly the tokens the guard is about to refuse."
   :explain "A model named {name} is already on record — one row per API identifier. If it was retired, reactivate that row rather than minting a second one: its prices are the history a closed sitting was costed against."}
  [_row inp ctx]
  (if-some [find' (:find ctx)]
    (if (and (some? (:name inp))
             (seq (find' :model {:name (str (:name inp))} {:limit 1})))
      (t/deny {:vars {:name (str (:name inp))}})
      (t/allow))
    (t/allow)))

;; ── handlers ────────────────────────────────────────────────────────

(def ^:private restatable
  "The fields a `restate` states again. `name` is not among them (one
  spelling per seat) and neither is anything the engine writes."
  [:charter :mode :scope :substitute_drop :held_for :substitute_for
   :standing_ttl_seconds :cadence_seconds :sitting_idle_seconds
   :budget_usd_per_week
   :sitting_budget_tokens :walk :rows_per_firing
   :wake_on :fire_interval_seconds])

(def ^:private wall-inputs
  "The seat field each wall is judged against, for the walls a person
  states again (R-1 of waymark-fp62.7.13). `seat_not_active` is not
  here: its input is the seat's STATE, and `unpark` is the door that
  moves it. `sitting_budget_reached` is not here either: its input is
  a sitting's running count, which no restate touches."
  {"budget_reached" [:budget_usd_per_week]
   "model_not_held" [:held_for :substitute_for]})

(defn- lifts-the-line?
  "Does this restate change the input of the wall the line records?
  PURE, row against input: a restate is a person changing the law,
  and the next request judges the new law and writes the line again
  if the wall still holds. The handler never sums the week."
  [row inp]
  (boolean (some #(field-moved? row inp %)
                 (get wall-inputs (str (get-in row [:data :halt :reason]))))))

(defhandler restate-seat [row inp _ctx]
  ;; R-7.5: a restate whose scope passes the four guards CLEARS stale.
  ;; The guards ran before this handler, so arriving here IS the pass
  ;; — and a scope that still names a stale entry never gets here,
  ;; because the guard that marked it stale is the guard that refuses
  ;; it, with the entry named.
  ;;
  ;; R-1 of waymark-fp62.7.13, and `stale` is its precedent: a restate
  ;; that changes the wall's own input drops the halt line in the same
  ;; transaction. The line is a record, and a record of a law that
  ;; moved is stale on the row the restate answers.
  (let [lift? (lifts-the-line? row inp)]
    (cond-> (-> (reduce (fn [r f] (assoc-in r [:data f] (get inp f)))
                        row restatable)
                (update :data dissoc :stale))
      lift? (update :data dissoc :halt))))

(defhandler unpark-seat [row _inp _ctx]
  ;; R-2: the seat's state IS the input of the `seat_not_active` wall,
  ;; and `unpark` is the hand that moves it. The other three lines
  ;; stand — a park does not spend fuel and does not change the model
  ;; list — and the door that reads them judges them.
  (if (= "seat_not_active" (str (get-in row [:data :halt :reason])))
    (update row :data dissoc :halt)
    row))

(defhandler write-stale [row inp _ctx]
  (assoc-in row [:data :stale] (:stale inp)))

(defhandler write-halt [row inp ctx]
  (assoc-in row [:data :halt]
            (cond-> {:reason (:reason inp) :since (:now ctx)}
              (:detail inp) (assoc :detail (:detail inp)))))

(defhandler clear-halt-mark [row _inp _ctx]
  (update row :data dissoc :halt))

;; R-12.12: a new offer REPLACES the old one — at most one live sitter
;; key per seat, members' set-reentry overwrite exactly. The key the
;; person pasted into yesterday's Routine dies the moment a fresh one
;; lands.
(defhandler set-sitter-key [row inp _ctx]
  (assoc-in row [:data :sitter_key] (:key inp)))

(defhandler clear-sitter-key [row _inp _ctx]
  (update row :data dissoc :sitter_key))

;; R-12.19: a fire moves nothing on the seat. The row is returned as
;; it stands, and the transition IS the record — `:record true` puts
;; the text in the log's inputs, the ledger counts the move, and the
;; schedules consumer hears it and starts the run.
(defhandler fire-seat [row _inp _ctx]
  row)

(defn- larger
  "The larger of two comparables, either of which may be absent."
  [a b]
  (cond (nil? a) b (nil? b) a (neg? (compare a b)) b :else a))

(defhandler absorb-fold [row inp _ctx]
  (-> row
      (assoc-in [:data :scope] (:scope inp))
      (assoc-in [:data :substitute_drop] (:substitute_drop inp))
      (assoc-in [:data :standing_ttl_seconds] (:standing_ttl_seconds inp))
      (assoc-in [:data :budget_usd_per_week] (:budget_usd_per_week inp))))

(defhandler merge-seat [row inp ctx]
  ;; § 6, in order: fold the two scopes per kind (waymark-ycp's
  ;; merge — one entry per kind, never appended), fold the two drop
  ;; lists the same way, keep the LARGER ttl and budget, and write
  ;; merged_into. The fold lands on `into` through its OWN door, so
  ;; the four scope guards judge it there (R-6.1) and `into`'s history
  ;; says its authority grew, by whose hand, in the same transaction
  ;; as the close. Nothing revokes and nothing mints (R-6.2): a grant
  ;; citing a merged seat scopes to nothing by R-5.2 and dies on its
  ;; own clock, and each moved sitter files a bootstrap ask for `into`.
  (let [into-id (str (:into inp))
        mine (:data row)
        target (when (:read ctx) ((:read ctx) :seat into-id))
        theirs (:data target)]
    (when (and target (:invoke ctx))
      ((:invoke ctx) :seat into-id :absorb
       {:scope (grants/merge-scope (:scope theirs) (:scope mine))
        :substitute_drop (grants/merge-scope (:substitute_drop theirs)
                                             (:substitute_drop mine))
        :standing_ttl_seconds (max (long (or (:standing_ttl_seconds theirs) 0))
                                   (long (or (:standing_ttl_seconds mine) 0)))
        :budget_usd_per_week (larger (:budget_usd_per_week theirs)
                                     (:budget_usd_per_week mine))}))
    (assoc-in row [:data :merged_into] into-id)))

(defhandler reprice-model [row inp _ctx]
  (reduce (fn [r f] (assoc-in r [:data f] (get inp f)))
          row
          [:price_input_per_mtok :price_output_per_mtok
           :price_cache_read_per_mtok :price_cache_write_per_mtok]))

(def ^:private cost-pairs
  "Which token count is priced by which field — the four halves of a
  sitting's bill, named once so the close and the stored `prices` map
  cannot drift apart."
  [[:input_tokens :price_input_per_mtok :input]
   [:output_tokens :price_output_per_mtok :output]
   [:cache_read_tokens :price_cache_read_per_mtok :cache_read]
   [:cache_write_tokens :price_cache_write_per_mtok :cache_write]])

(defn cost-of
  "The dollars a sitting's token counts cost at these prices —
  sum(tokens ÷ 1e6 × price), exact decimals throughout (never floats,
  batch H's own rule) and rounded once at the end. Public because the
  ledger route and the tests both want the arithmetic named rather
  than repeated."
  [counts prices]
  (let [^java.math.BigDecimal total
        (reduce (fn [acc [tokens-field _ price-key]]
                  (+ acc (* (bigdec (or (get counts tokens-field) 0))
                            (bigdec (or (get prices price-key) 0)))))
                0M
                cost-pairs)]
    (.divide total ^java.math.BigDecimal million (int cost-scale)
             RoundingMode/HALF_UP)))

(def ^:private counted-fields
  "The five a report writes onto the row, in the order the schema
  declares them. Named once: the close and the tally write the same
  five, and a list spelled twice would drift the day a sixth arrives."
  [:input_tokens :output_tokens :cache_read_tokens :cache_write_tokens
   :turns])

(defn- prices-now
  "The four prices this sitting's model carries AT THIS MOMENT
  (R-10.4). Four decimals, always — a model row that went missing
  between the open and the close costs zero rather than writing nulls
  into a map the schema says holds prices."
  [row ctx]
  (let [model (when (:read ctx)
                ((:read ctx) :model (str (get-in row [:data :model]))))]
    (into {}
          (map (fn [[_ price-field price-key]]
                 [price-key (or (get-in model [:data price-field]) 0M)]))
          cost-pairs)))

(defn- write-counts
  "The five counts of a report, onto the row."
  [row inp]
  (reduce (fn [r f] (assoc-in r [:data f] (get inp f))) row counted-fields))

(defn- token-counts
  "The four priced counts of a report, as `cost-of` takes them."
  [inp]
  (select-keys inp [:input_tokens :output_tokens
                    :cache_read_tokens :cache_write_tokens]))

(defhandler close-sitting [row inp ctx]
  ;; R-10.4: the model's prices are read AT THIS MOMENT, the cost is
  ;; computed from them, and the prices used are written beside it —
  ;; so a reprice tomorrow moves the model row and does not move one
  ;; byte of what last week cost. R-10.5: the engine never estimates
  ;; a token; these are the harness's counts, recorded.
  (let [prices (prices-now row ctx)
        counts (token-counts inp)]
    (-> (write-counts row inp)
        (assoc-in [:data :note] (:note inp))
        ;; THE BIRTH STAMP WINS (R-12.15, R-12.17). `waymark_sit` may
        ;; already have paired this row with the harness session that
        ;; opened it, and two runs of one seat can overlap — so a
        ;; close writes the id only onto a row that carries none. A
        ;; report naming a different session is the door's fallback
        ;; case, and moving the stamp would move the bill to a run
        ;; that did not spend it.
        (cond-> (and (some? (:harness_session inp))
                     (nil? (get-in row [:data :harness_session])))
          (assoc-in [:data :harness_session] (:harness_session inp)))
        (assoc-in [:data :ended_at] (:now ctx))
        (assoc-in [:data :prices] prices)
        (assoc-in [:data :cost_usd] (cost-of counts prices)))))

(defhandler tally-sitting [row inp ctx]
  ;; R-12.25, R-12.27: the same five counts as a close, written onto a
  ;; sitting that is STILL OPEN, priced at this moment so the week's
  ;; wall can see what an unfinished sitting has spent.
  ;;
  ;; CUMULATIVE, never additive: the hook sums the whole transcript
  ;; every turn, so the newest tally REPLACES the last and a replayed
  ;; one writes what is already there.
  ;;
  ;; No `prices` map is copied down. The prices belong beside the bill
  ;; that a reprice must not be able to move, and the only bill is the
  ;; close's; a running cost is a reading of the row right now, and it
  ;; is re-read at the next turn.
  (-> (write-counts row inp)
      ;; a tally with nothing to say leaves the last sentence standing
      ;; — the close is where a note is owed, and a per-turn wipe would
      ;; lose it
      (cond-> (some? (:note inp))
        (assoc-in [:data :note] (:note inp)))
      ;; THE BIRTH STAMP WINS, the close's rule verbatim (R-12.17)
      (cond-> (and (some? (:harness_session inp))
                   (nil? (get-in row [:data :harness_session])))
        (assoc-in [:data :harness_session] (:harness_session inp)))
      (assoc-in [:data :tallied_at] (:now ctx))
      (assoc-in [:data :cost_usd] (cost-of (token-counts inp)
                                           (prices-now row ctx)))))

;; ── the law, written down ───────────────────────────────────────────
;;
;; Both are CHECK-TIER — no :given rows, and `park`'s one guard reads
;; :principal and nothing else — so `make check-queue` judges them
;; with no database, in the same breath as the usability warnings.

(def ^:private an-open-seat
  {:name "inbox-clerk"
   :charter "Decide whether a message asks something of this house."
   :scope [{:kind "inbox_item" :actions ["research" "yes" "no"]}]
   :standing_ttl_seconds 604800
   :cadence_seconds 3600
   :budget_usd_per_week 5M
   :sitting_budget_tokens 60000
   :rows_per_firing 20})

(defscenario an-agent-does-not-park-its-own-seat
  "The levers on an office are the person's: an agent that could park
   its own seat could take itself off the books between two audits."
  {:kind    :seat
   :attempt :park
   :row     {:state :active :data an-open-seat}
   :as      {:id "inbox-clerk" :type :agent}
   :expect  {:refused :a-person
             :because "a person opens"}})

(defscenario the-person-parks-the-seat
  "And the lever really is there for the person whose fuel it is —
   one tap, no confirm, and the way back is one more."
  {:kind    :seat
   :attempt :park
   :row     {:state :active :data an-open-seat}
   ;; :human, not the scenario vocabulary's default :person —
   ;; `a-person` reads the RUNTIME type (t/actor-types is
   ;; #{:human :agent :system}), and a wall that also answered to a
   ;; word only scenarios spell would be two vocabularies for one fact
   :as      {:id "colton" :type :human}
   :expect  {:allowed true}})

(def ^:private a-minted-key
  "Twenty-six base64url characters — what a machine mints for 128 bits,
  and never what a hand types."
  "Zm9vYmFyYmF6cXV4c2l0dGVy")

(defscenario an-agent-does-not-hand-itself-the-seats-key
  "The key is how a person tells one of its tool's sessions from the
   rest. An agent that could offer itself one could walk into any
   office it liked."
  {:kind    :seat
   :attempt :offer_key
   :row     {:state :active :data an-open-seat}
   :input   {:key a-minted-key}
   :as      {:id "inbox-clerk" :type :agent}
   :expect  {:refused :a-person
             :because "a person opens"}})

(defscenario the-person-offers-the-seat-a-key
  "And the person whose fuel it is mints one and pastes it into the
   Routine, in one tap, with revoke one tap behind it."
  {:kind    :seat
   :attempt :offer_key
   :row     {:state :active :data an-open-seat}
   :input   {:key a-minted-key}
   :as      {:id "colton" :type :human}
   :expect  {:allowed true}})

(defscenario an-agent-does-not-revoke-the-seats-key
  "The same wall on the way back: a sitter that could revoke the key
   could lock its person out of its own office."
  {:kind    :seat
   :attempt :revoke_key
   :row     {:state :active :data (assoc an-open-seat :sitter_key a-minted-key)}
   :as      {:id "inbox-clerk" :type :agent}
   :expect  {:refused :a-person
             :because "a person opens"}})

(defscenario the-person-revokes-the-seats-key
  "And the person takes it back, which is the whole of retiring a
   Routine's authority."
  {:kind    :seat
   :attempt :revoke_key
   :row     {:state :active :data (assoc an-open-seat :sitter_key a-minted-key)}
   :as      {:id "colton" :type :human}
   :expect  {:allowed true}})

;; ── :seat ───────────────────────────────────────────────────────────

(def ^:private mode-choices
  "R-10.8's two words, in the person's own terms. Said once and shown
  at all three doors, because a seat whose mode reads one way on the
  create form and another on the restate is a seat nobody can move."
  {"fired" "Fired — a cadence, a wake or your own fire starts the run, and the run's own hook closes the sitting"
   "interactive" "Interactive — you sit here yourself, from your own machine, across as many turns as the work takes; nothing fires it"})

(def ^:private mode-help
  "The seat's OWN reading of R-10.8, and the reason the field is on the
  seat rather than on the principal."
  "Who sits here. A fired seat is the seat's work day: it wakes on its cadence, on a wake or on your fire, and a Routine's run does the work. An interactive seat is its training day: you sit in it yourself, the corrections you make are the record a step down the ladder reads, and nothing fires it — a Routine's run that tries is refused.")

(def ^:private idle-help
  "R-12.25's safety net under the wait, said where a person sets it."
  "How long an interactive sitting may go untallied before the engine closes it. The Stop hook tallies after every turn, so this is the gap that says somebody shut the laptop — the sweep then closes the sitting with the last tally's counts rather than leaving it open forever. It means nothing to a fired seat.")

;; ── what wakes a seat, entry by entry (R-12.22, R-12.24) ───────────
;;
;; A `wake_on` entry wore the scope entry's schema while a wake was
;; one thing: a transition the seat asked to be woken by. R-12.24
;; gives it a second thing to be — a COUNT wake, which says how many
;; rows must be waiting before the seat is worth waking — so the
;; entry has a schema of its own here. The scope's `kind` and
;; `actions` are the same two fields, judged by the same two guards
;; (`wake-on-names-real-kinds`, `wake-on-names-real-actions`), and two
;; fields a leash has no use for join them:
;;
;;   filter     which rows are counted, in the shape of that kind's
;;              query where clause — `grants/filter-map-schema`, the
;;              scope entry's own filter shape, spelled once and worn
;;              twice. Absent, the kind's default filter counts: the
;;              queue a walk works through.
;;   at_least   the size that wakes the seat. Absent, the entry is a
;;              transition wake and behaves exactly as it always did.
;;
;; The rest of a scope entry — ids, fields, hashed, args — is a
;; leash's vocabulary and not a wake's: WHAT a woken session may see
;; is decided by the seat's `scope`, one field up, and a wake entry
;; that repeated it would be a second leash nobody is holding.
(def wake-entry-schema
  [:map
   [:kind {:x-options {:from :kinds}
           :x-display {:label "Kind"
                       :help "The collection whose transitions wake this seat — one kind name this engine serves."}}
    [:string {:min 1 :max 64}]]
   [:actions {:x-options {:from :actions :of :kind :each true}
              :x-display {:label "Actions"
                          :help "Which transitions of that kind count, by name. A transition wake with an empty list wakes this seat for nothing; a count wake with an empty list counts on every action of the kind."}}
    [:vector [:string {:min 1 :max 64}]]]
   ;; no :x-options, for the scope filter's reason verbatim: the
   ;; vocabulary here is the legal KEYS of an object, and the recipe's
   ;; composition words both describe a value BUILT from tokens
   [:filter {:optional true
             :x-display {:label "Only rows matching"
                         :spelled-by-hand grants/filter-spelled-by-hand
                         :help "Which rows a count wake counts: field=value pairs in the shape of that kind's own query, the collection grammar's eq. Omit it and the kind's own default filter counts — the queue a walk works through."}}
    [:maybe grants/filter-map-schema]]
   [:at_least {:optional true
               :examples [20]
               :x-display {:label "Rows waiting before it wakes"
                           :help "The size that wakes this seat. The engine counts the rows matching this entry when one of its actions commits, and fires once the count is at or above this number; the fire names no row, so the session walks the queue. Omit it and every matching transition wakes the seat, one row at a time."}}
    [:int {:min 1}]]])

(def wake-on-schema
  "What a seat may write in `wake_on`: a list of wake entries."
  [:vector wake-entry-schema])

(def ^:private wake-on-example
  "The scope example, and one count entry beside it: the two kinds of
  wake in one textarea, so the shape of the second is not a thing a
  person has to be told about to find."
  (conj grants/scope-example
        {:kind "task" :actions ["create"]
         :filter {:state "open"} :at_least 20}))

(def ^:private charter-example
  "Decide whether a message asks something of this house, and say what it asks in one line. A receipt for something already bought asks nothing. A person waiting on an answer asks something, even when they are polite about it.")

(defresource seat
  {:kind :seat
   :plural "seats"
   :states [:active :parked :merged :retired]
   :initial :active
   :terminal #{:merged :retired}
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   ;; No :x-options on :name, and roles.clj's reason verbatim: the list
   ;; the engine could publish here is the list of names already TAKEN,
   ;; and a chip row of it would offer exactly the tokens the guard is
   ;; about to refuse. What the field owes is the convention, said out
   ;; loud, and the refusal names the collision when one happens.
   :schema
   [:map
    [:name {:examples ["inbox-clerk"]
            :x-display
            {:raw true
             :label "Seat name"
             :help "The token a grant and an ask will spell — lowercase, hyphenated, one word for one office (\"inbox-clerk\", \"composer\"). One spelling per seat: a name already open is refused, because two spellings of one office split its grants silently."}}
     [:string {:min 1 :max 40}]]
    ;; THE RESIDUAL (R-4.10), and its cap is the priming budget: 1200
    ;; characters is near 300 tokens, and a cache read of it costs a
    ;; fraction of a cent per turn. A sentence naming a door the scope
    ;; does not open is redundant — the door is absent from the
    ;; envelope, and absence is the rule. A sentence repeating a
    ;; correction is a fence not yet written.
    [:charter {:examples [charter-example]
               :x-display
               {:widget "prose"
                :label "The judgment, in your words"
                :help "What this seat has to DECIDE that the engine cannot say at a door — and nothing else. Leave out which door comes next (the envelope offers only the open ones) and leave out what is forbidden (a door the scope does not open is not there). If you find yourself writing the same correction twice, that sentence belongs in a guard, a filter or a reason string, not here."}}
     [:string {:min 1 :max 1200}]]
    ;; ── WHO SITS HERE (R-10.8) ──────────────────────────────────────
    ;; The mode is the SEAT'S, not the principal's: one office is
    ;; fired and another is sat in, and the ledger compares seat with
    ;; seat. The audit chair of the ladder (§ 11) is a second seat
    ;; with the same charter and this field set the other way.
    [:mode {:default default-mode
            :x-display
            {:label "How it is sat in"
             :choices mode-choices
             :help mode-help}}
     (into [:enum] seat-modes)]
    [:scope {:examples [grants/scope-example]
             :x-display
             {:label "What the seat opens"
              :help "The office's authority, entry by entry: a kind, the actions allowed on it, and optionally the rows, fields and filter that narrow it. Every sitter of this seat sees exactly this and nothing else — and a restate moves every live grant with it, with no new grant minted."}}
     grants/scope-schema]
    [:substitute_drop {:default []
                       :examples [grants/scope-example]
                       :x-display
                       {:label "What a substitute does NOT get"
                        :help "The entries a stand-in sitter is refused — the parts of this office you would not hand a model covering for the one that usually sits here. Every entry must be inside the scope above; an empty list means a substitute sees the whole seat."}}
     grants/scope-schema]
    [:held_for {:default []
                :kind :model
                :x-display
                {:label "Models that may sit"
                 :help "The models allowed to hold this seat as its full sitter — the seat's place on the ladder. An empty list means any model may sit; name one and a session declaring another sees nothing."}}
     [:vector :waymark/ref]]
    [:substitute_for {:default []
                      :kind :model
                      :x-display
                      {:label "Models that may stand in"
                       :help "The models allowed to sit as a SUBSTITUTE — they read the seat's memory and do not write it. An empty list means any model may stand in."}}
     [:vector :waymark/ref]]
    [:standing_ttl_seconds {:examples [604800]
                            :x-display
                            {:label "Longest leash, in seconds"
                             :help "The longest grant a sitter of this seat may ask for. A week (604800) is the standing rotation's own window and the ceiling the engine enforces; ask for less where the work is shorter."}}
     [:int {:min 60 :max 31536000}]]
    [:cadence_seconds {:examples [3600]
                       :x-display
                       {:label "How often it wakes, in seconds"
                        :help "The interval the schedule fires this seat at. This is the seat's FIXED COST: a wake costs money whether or not there was work, so a quiet seat wants a longer cadence before it wants a cheaper model. An interactive seat has no cadence; nothing fires it."}}
     [:int {:min 300 :max 2592000}]]
    [:sitting_idle_seconds {:default 3600
                            :examples [3600]
                            :x-display
                            {:label "How long a sitting may idle, in seconds"
                             :help idle-help}}
     [:int {:min 60 :max 86400}]]
    [:budget_usd_per_week {:examples [5M]
                           :x-display
                           {:label "Fuel for seven days, in dollars"
                            :help "What this seat may spend in a rolling week, summed over its closed sittings. Reaching it is a hard wall: the grant scopes to nothing until the window rolls, and the seat says so."}}
     [:decimal {:min 0 :max 100000}]]
    [:sitting_budget_tokens {:examples [60000]
                             :x-display
                             {:label "One sitting's token ceiling"
                              :help "Passed to the harness as the cap on a single wake. Twenty thousand is the floor the engine accepts — below it a sitting cannot read its queue and write anything back."}}
     [:int {:min 20000 :max 10000000}]]
    [:walk {:optional true
            :x-options {:from :kinds}
            :x-display
            {:label "The queue it walks"
             :help "One kind this seat works through, a row at a time, in the order that kind's own default sort gives. The kind must be in the scope above and must filter its own queue by state — the collection a firing opens IS the work waiting for it. Leave it empty for a seat that walks nothing."}}
     [:maybe [:string {:min 1 :max 64}]]]
    [:rows_per_firing {:default 20
                       :x-display
                       {:label "Rows per firing"
                        :help "The most rows one wake moves to a leaf. The walk's cap, and the lever you pull before you pull the model: fewer rows is a shorter sitting at the same judgment."}}
     [:int {:min 1 :max 200}]]
    ;; ── the third way a sitting begins (R-12.22) ────────────────────
    ;; The cadence is the first and a person's fire is the second.
    ;; This is the third: the transitions this seat asked to be woken
    ;; by, named the way its scope is. NO DEFAULT IS WRITTEN — a walk
    ;; seat with nothing here behaves as one entry, its walk's kind
    ;; with the action `create`, computed at read time by
    ;; `effective-wake-on`. A default written into the row would be a
    ;; value a person never chose, and a later restate of `walk` would
    ;; leave it pointing at the queue the seat no longer walks.
    [:wake_on {:optional true
               :examples [wake-on-example]
               :x-display
               {:label "What wakes it"
                :help "The transitions that wake this seat, entry by entry: a kind, and the actions on it that count. An entry that names at_least is a count wake: it wakes the seat when that many rows are waiting, and not one row at a time. A seat that walks a queue and names nothing here wakes when a row of that queue is created. Leave it empty for a seat that wakes on its cadence alone."}}
     [:maybe wake-on-schema]]
    [:fire_interval_seconds {:default 300
                             :examples [300]
                             :x-display
                             {:label "Quietest gap between wakes, in seconds"
                              :help "The least time between two wakes the seat's own events start. A match inside the gap does not fire; it waits, and the first wake after the gap lifts walks the queue. Raise it for a busy queue: every wake costs one sitting's fuel."}}
     [:int {:min 1 :max 86400}]]
    ;; ── engine-written from here down (absent from the create door
    ;;    and from restate; see the ns docstring's write fence) ───────
    [:stale {:optional true
             :x-display
             {:label "Entries the boot sweep refused"
              :spelled-by-hand "Written by the boot sweep when a scope entry stops naming something this engine serves; a person never writes it, and a restate whose new scope passes the four guards clears it."}}
     [:maybe grants/scope-schema]]
    [:halt {:optional true
            :x-display
            {:label "The wall this seat is against"
             :spelled-by-hand "Written by the router the first time a request under this seat's grant meets a wall, and cleared the first time one passes; a halt is not a state, so the seat stays active and the wall lifts on its own."}}
     [:maybe [:map
              [:reason {:x-display {:label "Which wall"}}
               (into [:enum] (sort halt-reasons))]
              [:since {:x-display {:label "Since"}} :waymark/instant]
              [:detail {:optional true :x-display {:label "What it said"}}
               [:maybe [:string {:max 240}]]]]]]
    ;; THE KEYED SITTER SESSION'S HALF OF THE HANDSHAKE (R-12.12).
    ;; Every session of a person's connector is the SAME delegate on
    ;; the SAME bearer, so a Routine's session cannot be told from the
    ;; person's chats by credential. This is what tells them apart: a
    ;; machine-minted secret the person pastes into the Routine's
    ;; instructions, presented once through waymark_sit. :secret, the
    ;; :reentry_token posture — the value never leaves the engine in
    ;; any projection, scoped or not — and minter-supplied, because an
    ;; engine that generated it would have to show it back.
    [:sitter_key {:optional true :secret true
                  :x-display
                  {:hidden true
                   :label "Sitter key"
                   :spelled-by-hand "Written by offer_key and cleared by revoke_key; never typed into a form, and never rendered back."}}
     [:maybe [:string {:min 22 :max 128}]]]
    ;; THE MEANS BY WHICH A SITTING IS CREATED (R-12.0). The seat is
    ;; the only thing a person manages; the schedule is the engine's
    ;; own record of how this seat wakes, and it is written when the
    ;; seat is opened. Hidden, because a form offering it would be a
    ;; form inviting a person to manage the thing R-12.0 took off them.
    [:schedule {:optional true
                :kind :schedule
                :x-display
                {:hidden true
                 :label "Schedule row"
                 :help "The engine's own link to the thing that wakes this seat. Written by the engine; a person manages the seat, never the schedule."}}
     [:maybe :waymark/ref]]
    [:merged_into {:optional true
                   :kind :seat
                   :x-display
                   {:hidden true
                    :label "Folded into"
                    :help "The seat this one's work moved to. Written by the merge."}}
     [:maybe :waymark/ref]]]
   ;; THE CREATE DOOR IS THE PERSON'S, and it carries nothing a verdict
   ;; or a sweep owns: stale, halt, schedule and merged_into are absent
   ;; on purpose — a create that could stamp a halt would be a seat
   ;; born against a wall nobody hit.
   :create-schema
   [:map
    [:name {:examples ["inbox-clerk"]
            :x-display
            {:raw true
             :label "Seat name"
             :help "The token a grant and an ask will spell — lowercase, hyphenated, one word for one office. One spelling per seat."}}
     [:string {:min 1 :max 40}]]
    [:charter {:examples [charter-example]
               :x-display
               {:widget "prose"
                :label "The judgment, in your words"
                :help "What this seat has to DECIDE that the engine cannot say at a door — and nothing else. A new seat's judgment is not known yet; open it on a model you trust and let the first weeks find out what the judgment actually is."}}
     [:string {:min 1 :max 1200}]]
    [:mode {:default default-mode
            :x-display
            {:label "How it is sat in"
             :choices mode-choices
             :help mode-help}}
     (into [:enum] seat-modes)]
    [:scope {:examples [grants/scope-example]
             :x-display
             {:label "What the seat opens"
              :help "The office's authority, entry by entry. Ask for the least that does the job — every sitter of this seat will see exactly this."}}
     grants/scope-schema]
    [:substitute_drop {:default []
                       :examples [grants/scope-example]
                       :x-display
                       {:label "What a substitute does NOT get"
                        :help "The entries a stand-in sitter is refused. Every entry must be inside the scope above; an empty list means a substitute sees the whole seat."}}
     grants/scope-schema]
    [:held_for {:default []
                :kind :model
                :x-display
                {:label "Models that may sit"
                 :help "The models allowed to hold this seat as its full sitter. An empty list means any model may sit."}}
     [:vector :waymark/ref]]
    [:substitute_for {:default []
                      :kind :model
                      :x-display
                      {:label "Models that may stand in"
                       :help "The models allowed to sit as a substitute — they read the seat's memory and do not write it."}}
     [:vector :waymark/ref]]
    [:standing_ttl_seconds {:examples [604800]
                            :x-display
                            {:label "Longest leash, in seconds"
                             :help "The longest grant a sitter of this seat may ask for. A week is the ceiling the engine enforces."}}
     [:int {:min 60 :max 31536000}]]
    [:cadence_seconds {:examples [3600]
                       :x-display
                       {:label "How often it wakes, in seconds"
                        :help "The interval the schedule fires this seat at — the seat's fixed cost, paid whether or not there was work. An interactive seat has no cadence; nothing fires it."}}
     [:int {:min 300 :max 2592000}]]
    [:sitting_idle_seconds {:default 3600
                            :examples [3600]
                            :x-display
                            {:label "How long a sitting may idle, in seconds"
                             :help idle-help}}
     [:int {:min 60 :max 86400}]]
    [:budget_usd_per_week {:examples [5M]
                           :x-display
                           {:label "Fuel for seven days, in dollars"
                            :help "What this seat may spend in a rolling week. Reaching it is a hard wall until the window rolls."}}
     [:decimal {:min 0 :max 100000}]]
    [:sitting_budget_tokens {:examples [60000]
                             :x-display
                             {:label "One sitting's token ceiling"
                              :help "Passed to the harness as the cap on a single wake; twenty thousand is the floor."}}
     [:int {:min 20000 :max 10000000}]]
    [:walk {:optional true
            :x-options {:from :kinds}
            :x-display
            {:label "The queue it walks"
             :help "One kind this seat works through, a row at a time. It must be in the scope above and must filter its own queue by state."}}
     [:maybe [:string {:min 1 :max 64}]]]
    [:rows_per_firing {:default 20
                       :x-display
                       {:label "Rows per firing"
                        :help "The most rows one wake moves to a leaf — the lever you pull before you pull the model."}}
     [:int {:min 1 :max 200}]]
    [:wake_on {:optional true
               :examples [wake-on-example]
               :x-display
               {:label "What wakes it"
                :help "The transitions that wake this seat, entry by entry: a kind, and the actions on it that count. An entry that names at_least is a count wake: it wakes the seat when that many rows are waiting. Leave it empty and the seat wakes on its cadence; a seat that walks a queue wakes when a row of that queue is created."}}
     [:maybe wake-on-schema]]
    [:fire_interval_seconds {:default 300
                             :examples [300]
                             :x-display
                             {:label "Quietest gap between wakes, in seconds"
                              :help "The least time between two wakes the seat's own events start. A match inside the gap waits for it to lift. Five minutes is the default."}}
     [:int {:min 1 :max 86400}]]
    ;; the write fence, named so it can be refused (R-12.12): the
    ;; create door and the restate DECLARE sitter_key only so
    ;; `key-not-written-by-hand` may judge it — a guard judges a field
    ;; of its own door or nothing. :secret keeps it out of every
    ;; advertised create body and out of every form.
    [:sitter_key {:optional true :secret true
                  :x-display
                  {:hidden true
                   :label "Sitter key"
                   :spelled-by-hand "Refused here: the key is offer_key's to write."}}
     [:maybe [:string {:min 22 :max 128}]]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :links [{:rel "merged_into" :kind :seat
            :href "/api/seats/{data.merged_into}"
            :summary "The seat this one folded into"}
           ;; the office's own records, one hop from the row (owner's
           ;; ask, 2026-09-17): the wakes it has sat, newest first, and
           ;; the schedule that fires it
           {:rel "sittings" :kind :sitting
            :href "/api/sittings?seat={id}&sort=-started_at"
            :summary "The sittings this seat has held, newest first"}
           {:rel "schedule" :kind :schedule
            :href "/api/schedules/{data.schedule}"
            :summary "The schedule that fires this seat"}]
   :create-guards [a-person
                   not-a-sitter
                   key-not-written-by-hand
                   one-seat-spelling
                   grants/scope-names-real-kinds
                   grants/scope-names-real-actions
                   grants/scope-filters-are-filterable
                   grants/scope-omits-private-kinds
                   drop-inside-scope
                   ttl-within-standing
                   held-for-active-models
                   walk-names-a-kind-in-scope
                   wake-on-names-real-kinds
                   wake-on-names-real-actions]
   :actions
   {:restate
    {:from #{:active} :to :active
     :input [:map
             [:charter {:examples [charter-example]
                        :x-display
                        {:widget "prose"
                         :label "The judgment, in your words"
                         :help "State the seat's judgment again, whole. If a sentence here is one you have written because the model kept getting something wrong, the fix is a guard, a filter, a door or a reason string — and then the sentence leaves."}}
              [:string {:min 1 :max 1200}]]
             [:mode {:default default-mode
                     :x-display
                     {:label "How it is sat in"
                      :choices mode-choices
                      :help mode-help}}
              (into [:enum] seat-modes)]
             [:scope {:examples [grants/scope-example]
                      :x-display
                      {:label "What the seat opens"
                       :help "The whole authority, restated. Every live grant citing this seat moves with it at the next request — no new grant, nothing copied."}}
              grants/scope-schema]
             [:substitute_drop {:default []
                                :examples [grants/scope-example]
                                :x-display
                                {:label "What a substitute does NOT get"
                                 :help "The entries a stand-in is refused; every one must be inside the scope above. An empty list means a substitute sees the whole seat."}}
              grants/scope-schema]
             [:held_for {:default []
                         :kind :model
                         :x-display
                         {:label "Models that may sit"
                          :help "The models allowed to hold this seat, stated again in full. Changing this is a STEP on the ladder and demands the note below."}}
              [:vector :waymark/ref]]
             [:substitute_for {:default []
                               :kind :model
                               :x-display
                               {:label "Models that may stand in"
                                :help "The models allowed to sit as a substitute, stated again in full. Changing this is a step too, and demands the note."}}
              [:vector :waymark/ref]]
             [:standing_ttl_seconds {:examples [604800]
                                     :x-display
                                     {:label "Longest leash, in seconds"
                                      :help "The longest grant a sitter may ask for; a week is the ceiling."}}
              [:int {:min 60 :max 31536000}]]
             [:cadence_seconds {:examples [3600]
                                :x-display
                                {:label "How often it wakes, in seconds"
                                 :help "A longer cadence is the cheapest lever there is: it removes wakes that cost money and found nothing. An interactive seat has no cadence; nothing fires it."}}
              [:int {:min 300 :max 2592000}]]
             [:sitting_idle_seconds {:default 3600
                                     :examples [3600]
                                     :x-display
                                     {:label "How long a sitting may idle, in seconds"
                                      :help idle-help}}
              [:int {:min 60 :max 86400}]]
             [:budget_usd_per_week {:examples [5M]
                                    :x-display
                                    {:label "Fuel for seven days, in dollars"
                                     :help "What this seat may spend in a rolling week."}}
              [:decimal {:min 0 :max 100000}]]
             [:sitting_budget_tokens {:examples [60000]
                                      :x-display
                                      {:label "One sitting's token ceiling"
                                       :help "The cap on a single wake, passed to the harness."}}
              [:int {:min 20000 :max 10000000}]]
             [:walk {:optional true
                     :x-options {:from :kinds}
                     :x-display
                     {:label "The queue it walks"
                      :help "One kind this seat works through, in the scope above, filtering its own queue by state."}}
              [:maybe [:string {:min 1 :max 64}]]]
             [:rows_per_firing {:default 20
                                :x-display
                                {:label "Rows per firing"
                                 :help "The most rows one wake moves to a leaf."}}
              [:int {:min 1 :max 200}]]
             [:wake_on {:optional true
                        :examples [wake-on-example]
                        :x-display
                        {:label "What wakes it"
                         :help "The transitions that wake this seat, stated again in full, count entries and all. An entry naming a kind the scope above does not open still wakes the seat; the sitting then sees only what the scope opens."}}
              [:maybe wake-on-schema]]
             [:fire_interval_seconds {:default 300
                                      :examples [300]
                                      :x-display
                                      {:label "Quietest gap between wakes, in seconds"
                                       :help "The least time between two wakes the seat's own events start. Raise it when a busy queue is waking this seat more often than the work deserves."}}
              [:int {:min 1 :max 86400}]]
             ;; THE STEP'S RECORD (R-11.2). A transition input, so the
             ;; log's `inputs` column holds it and no column is added.
             [:note {:optional true
                     :examples ["Down from the frontier tier: five sittings read, the dismissals held, and the two corrections were both mine changing my mind."]
                     :x-display
                     {:label "Why, if the models changed"
                      :help "Required when held_for or substitute_for moves: which model it was, which it is now, and what you read that says the step holds. Read five sittings before you write it; a step that does not hold is reversed by one more restate."}}
              [:maybe [:string {:min 1 :max 240}]]]
             ;; the write fence, named so it can be refused (R-12.12)
             ;; — declared here only so `key-not-written-by-hand` may
             ;; judge it; :secret, so no form and no prefill sees it.
             [:sitter_key {:optional true :secret true
                           :x-display
                           {:hidden true
                            :label "Sitter key"
                            :spelled-by-hand "Refused here: the key is offer_key's to write."}}
              [:maybe [:string {:min 22 :max 128}]]]]
     :record true
     ;; sitter_key is NOT prefilled and cannot be: the draft view
     ;; serves prefill from the raw row, and resource/check-secret!
     ;; refuses a :secret field there at the declaration.
     :edit {:prefill [:charter :mode :scope :substitute_drop :held_for
                      :substitute_for :standing_ttl_seconds :cadence_seconds
                      :sitting_idle_seconds
                      :budget_usd_per_week :sitting_budget_tokens :walk
                      :rows_per_firing :wake_on :fire_interval_seconds]
            :draft {:shared true :live true}}
     :guards [a-person
              not-a-sitter
              key-not-written-by-hand
              grants/scope-names-real-kinds
              grants/scope-names-real-actions
              grants/scope-filters-are-filterable
              grants/scope-omits-private-kinds
              drop-inside-scope
              ttl-within-standing
              held-for-active-models
              walk-names-a-kind-in-scope
              wake-on-names-real-kinds
              wake-on-names-real-actions
              step-carries-a-note]
     :safety {:idempotent true :reversible true :confirm false}
     :handler restate-seat
     :display {:label "Restate" :style :primary :order 1
               :description "State the office again, whole — every live grant moves with it at the next request"}}

    ;; THE CHEAP LEVER (R-4.5): no confirm, reversible, and it costs
    ;; nothing to pull. Grants stay where they are; the seat simply
    ;; serves nothing until somebody unparks it.
    :park
    {:from #{:active} :to :parked
     :guards [a-person]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Park" :order 2
               :description "The seat serves nothing and costs nothing; its grants stay, and unpark is one tap"}}

    :unpark
    {:from #{:parked} :to :active
     :guards [a-person]
     :safety {:idempotent true :reversible true :confirm false}
     :handler unpark-seat
     :display {:label "Unpark" :style :primary :order 3
               :description "The seat serves again, on the scope it had"}}

    ;; ── the keyed sitter session (R-12.12 to R-12.16) ───────────────
    ;; The person mints the secret, pastes it into the Routine's
    ;; instructions, and that Routine's session — one of many wearing
    ;; the same bearer — presents it once through waymark_sit and is
    ;; this seat's sitter from then on. Guarded by `a-person`, which
    ;; admits the person AND the person's own tool: minting the key is
    ;; setting up a Routine, and setting up a Routine is a thing a
    ;; person does from a chat.
    :offer_key
    {:from #{:active} :to :active
     :input [:map
             [:key {:x-display
                    {:raw true
                     :label "Sitter key"
                     :help "The secret you are about to paste into the Routine's instructions, minted by YOU — at least 22 characters of real randomness, which is 128 bits a machine made and no hand typed. The engine never generates it and never shows it again. A new offer replaces the old one."}}
              [:string {:min 22 :max 128}]]]
     ;; NOT :record, and members' offer_reentry's reason verbatim: a
     ;; recorded action persists its RAW inputs into the transition
     ;; log, and this input IS the credential. The transition row
     ;; (actor, key digest, summary) is still the audit that a key was
     ;; offered, by whom, when.
     :guards [a-person]
     :safety {:idempotent true :reversible true :confirm false}
     :handler set-sitter-key
     :display {:label "Offer key" :order 4
               :description "Hand this seat a secret to paste into a Routine — the session that presents it sits here, and a new offer replaces the old one"}}

    :revoke_key
    {:from #{:active} :to :active
     :guards [a-person]
     :safety {:idempotent true :reversible true :confirm false}
     :handler clear-sitter-key
     :display {:label "Revoke key" :order 5
               :description "The key answers for nothing; a session presenting it is told no seat answers, and the seat's own sittings are untouched"}}

    ;; ── the fire door (R-12.19, R-12.20) ────────────────────────────
    ;; A seat wakes three ways: its cadence, a person's fire, and a
    ;; transition it asked to be woken by. This is the second, and the
    ;; third comes through it too. The door itself only records: the
    ;; POST goes out after the commit, from the schedules consumer,
    ;; which reads this transition's text out of the log — and which
    ;; lifts the halt line this door judged stale (waymark-fp62.7.13).
    :fire
    {:from #{:active :parked} :to :active
     :input [:map
             [:text {:optional true
                     :examples ["Look at the three messages that arrived this morning."]
                     :x-display
                     {:widget "prose"
                      :label "What this run is about"
                      :help "What the session should do with this wake, in your words. Name one row and the session walks that row alone; leave it empty and the session walks the queue, as a cadence wake does."}}
              [:maybe [:string {:min 1 :max 2000}]]]]
     :record true
     :guards [a-person-or-the-engine not-interactive not-parked not-halted
              linked-for-fire]
     ;; NOT idempotent, and honestly so: a second fire starts a second
     ;; run. Every caller already carries the key the door demands —
     ;; the MCP door signs each invoke with `origin-key`, and the wake
     ;; consumer keys its fire by the transition it heard, which is
     ;; also its own replay dedupe. An idempotent spelling would have
     ;; let invoke's natural replay swallow a textless fire whenever the
     ;; seat's latest transition was the last textless fire — the
     ;; wake's release fire and a person's second press, both lost.
     :safety {:idempotent false :reversible false :confirm false
              :one-way "The run starts at the provider and cannot be called back; it costs one sitting's fuel."}
     :handler fire-seat
     :display {:label "Fire" :order 6
               :description "Wake the seat now, without waiting for its cadence — one run, counted in the ledger like any other"}}

    :merge
    {:from #{:active :parked} :to :merged
     :input [:map
             [:into {:kind :seat
                     :x-display
                     {:label "Fold into which seat"
                      :help "The office this seat's work is moving to. Its scope grows to cover both, its ttl and budget take the larger of the two, and this seat closes."}}
              :waymark/ref]]
     :record true
     :guards [a-person not-a-sitter merge-target-is-active]
     ;; the fold lands on `into` through its own concealed door, in
     ;; this transaction — declared so a reader can see it coming and
     ;; checks-assembly/check-touches can verify the pair at assembly
     :touches [{:kind :seat :action :absorb}]
     :safety {:idempotent true :reversible false :confirm true
              :consequence "This seat closes. Its scope folds into {into}. Each sitter of this seat loses its grant and must ask to sit in {into}."}
     :handler merge-seat
     :display {:label "Merge" :style :danger :order 8}}

    :retire
    {:from #{:active :parked} :to :retired
     :guards [a-person]
     :safety {:idempotent true :reversible false :confirm true
              :consequence "The office closes for good. Its sittings and its whole history stay on record; its grants scope to nothing and expire on their own clocks. Opening the work again is a new seat."}
     :display {:label "Retire" :style :danger :order 9}}

    ;; ── the concealed three (R-7.2, R-7.7) ──────────────────────────
    ;; System actor, logged, hidden from every envelope — members'
    ;; :bind and :stamp_subject posture exactly: a human, an agent and
    ;; a recovery-admin all get 404 from a POST by hand.
    :mark_stale
    {:from #{:active} :to :active
     :input [:map
             [:stale {:x-display
                      {:label "The entries that stopped resolving"
                       :spelled-by-hand "The failing scope entries, written by the boot sweep."}}
              grants/scope-schema]
             [:note {:x-display {:label "What the guard said"}}
              [:string {:min 1 :max 240}]]]
     :record true
     ;; :edit-shape — the sweep welds a first stale list onto a seat
     ;; that has none, and there is no earlier value to prefill from;
     ;; the fence an :edit implies would be an etag demanded of a boot.
     :waives #{:edit-shape}
     :guards [the-engines-own-hand]
     ;; NOT :reversible — a self-loop's reverse is itself, and a hidden
     ;; door is no usable reverse (checks/check-reversible). A self-loop
     ;; owes no :one-way either: re-doing is its own undo.
     :safety {:idempotent true :reversible false :confirm false}
     :handler write-stale
     :display {:label "Mark stale" :order 10}}

    :mark_halted
    {:from #{:active} :to :active
     :input [:map
             [:reason {:x-display
                       {:label "Which wall"
                        :choices {"seat_not_active" "The seat is parked, merged or retired, so it serves nothing"
                                  "model_not_held" "The session's model is not one this seat is held for"
                                  "budget_reached" "The week's fuel is spent; the wall lifts when the window rolls"
                                  "sitting_budget_reached" "This sitting is past the seat's token ceiling; the wall lifts when it closes"}}}
              (into [:enum] (sort halt-reasons))]
             [:detail {:optional true
                       :x-display {:label "What it said"}}
              [:maybe [:string {:max 240}]]]]
     :record true
     :guards [the-engines-own-hand]
     :safety {:idempotent true :reversible false :confirm false}
     :handler write-halt
     :display {:label "Mark halted" :order 11}}

    :clear_halt
    {:from #{:active} :to :active
     :guards [the-engines-own-hand]
     :safety {:idempotent true :reversible false :confirm false}
     :handler clear-halt-mark
     :display {:label "Clear halt" :order 12}}

    ;; ── the merge's landing (§ 6) ───────────────────────────────────
    :absorb
    {:from #{:active} :to :active
     :input [:map
             [:scope {:x-display
                      {:label "The folded scope"
                       :spelled-by-hand "The two seats' scopes folded to one entry per kind, written by the merge."}}
              grants/scope-schema]
             [:substitute_drop {:x-display
                                {:label "The folded drop list"
                                 :spelled-by-hand "The two seats' drop lists folded the same way, written by the merge."}}
              grants/scope-schema]
             [:standing_ttl_seconds {:x-display {:label "Longest leash, in seconds"}}
              [:int {:min 60 :max 31536000}]]
             [:budget_usd_per_week {:x-display {:label "Fuel for seven days, in dollars"}}
              [:decimal {:min 0 :max 100000}]]]
     :record true
     ;; :edit-shape — a fold is not an edit of the fields it lands on;
     ;; it is computed from two rows, and there is no form to prefill.
     :waives #{:edit-shape}
     :guards [folded-by-a-merge
              grants/scope-names-real-kinds
              grants/scope-names-real-actions
              grants/scope-filters-are-filterable
              grants/scope-omits-private-kinds]
     :safety {:idempotent true :reversible false :confirm false}
     :handler absorb-fold
     :display {:label "Absorb" :order 13}}}
   :scenarios [an-agent-does-not-park-its-own-seat
               the-person-parks-the-seat
               an-agent-does-not-hand-itself-the-seats-key
               the-person-offers-the-seat-a-key
               an-agent-does-not-revoke-the-seats-key
               the-person-revokes-the-seats-key]
   :deviations
   ["R-10.8's `mode` is a field of the SEAT and not of the sitting's create door, and `sitting_idle_seconds` sits beside `cadence_seconds` rather than on the sitting. Both are the office's settings: the mode decides who may sit at all (the `fire` door's `not-interactive` guard, the schedules consumer's silence, the wake consumer's skip), and the idle limit is what the sweep measures a sitting of this seat against. A sitting inherits the mode at birth and never chooses it."
    "R-4.9's own-surface for sitters is NOT declared here, and wave two settled why: `:own-surface :by` names a field of the row being read, and a sitter is identified through `grant.seat` — a field of the GRANT. A seat with a sitter column would be a second copy of the grant, so the courtesy is spelled where the sitter is actually identified: the seat resolve adds the citing seat's row as a synthetic, unstored scope entry (`{kind \"seat\", ids [<this seat>], actions []}`), and `:kind?`, `:row?`, `:field?` and `:ids-of` then answer for it exactly as they answer for anything granted. One admission algebra, read-only, one row — and `:whole-kind?` stays false, because one row is not the collection."
    "R-4.6's consequence sentence is kept verbatim, `{into}` included. The framework does not interpolate a consequence (render substitutes only a per-origin map, never a template), so the brace renders literally. The alternative was rewording the one sentence the spec pins, and a spec-pinned string is worth more than a tidy dialog."
    "`sitter_key` IS DECLARED on the create door and on `restate`, which reads at first like the opposite of this file's write fence. It is the fence: a guard may judge only a field of the door it stands on (checks/check-create-guards and check-guard-declarations are definition ERRORS otherwise), so a `key-not-written-by-hand` that could be READ had to have something to name — members.clj's `reentry-not-written-by-hand` has it for free, because that kind has no separate create-schema. Both spellings carry `{:secret true}`, so the advertised create body drops the field (collections.clj unions the row schema's secret set with the create model's for exactly this), no form asks for it, and the usability policies skip it. What the caller gains over silent omission is the refusal's own sentence, which names the door that writes the key instead."
    "`fire` declares `:idempotent false`, so every call must carry an Idempotency-Key (invoke's phase 2). That is the truthful spelling: a second fire starts a second run. It is also the safe one: an idempotent door is subject to invoke's natural replay, which compares only the row's LATEST transition, so a textless fire following a textless fire with nothing else on the seat would have been answered as a replay and never gone out — the wake's release fire (R-12.22) and a person's second press, both lost. The key costs nobody anything: the MCP door signs every invoke, and the wake consumer keys each fire by the transition it heard, which doubles as its own dedupe. The consumer's replay of the POST is deduped separately, where it happens: `schedules/already-fired?` compares `last_fired_at` against the transition's own instant."
    "R-12.22's `wake_on` is judged by its OWN two guards, `wake-on-names-real-kinds` and `wake-on-names-real-actions`, which say what the scope guards next door already say. A guard grades the fields it names in `:judges` (checks/check-guard-declarations refuses anything else), and the scope guards name `:scope`; borrowing one for `wake_on` would have had it refuse a scope the caller never sent. The duplication is two short bodies over a shared helper, against a wake entry nobody can match — a seat that never wakes and never says why."
    "`wake_on` has NO default in the row and `walk` is not copied into it. R-12.22 asks for exactly that: the walk seat's one entry is computed at read time by `effective-wake-on`. A default written at the create door would be a value a person never chose, and the first restate of `walk` would leave it naming the queue the seat no longer walks."
    "`fire` runs from `parked` as well as `active`, and the `not-parked` guard refuses it there. R-12.20 asks for the sentence \"The seat is parked. Unpark it first.\", and a door absent from a parked seat's envelope could only answer 409 with the machine's own words."
    "THE HALT LINE IS A RECORD, AND THREE DOORS LIFT IT (waymark-fp62.7.13). R-7.7 gives the line one writer and one lifter — the router, at a sitter's request. A FIRED seat makes no request of its own, so a line that outlived its wall left the seat stuck until a person ran the Routine by hand. Now: a `restate` that changes the wall's own input drops the line in the same transaction (`stale`'s precedent), `unpark` drops a `seat_not_active` line, and the `fire` door judges the week's fuel again for itself and lets the fire out when the wall no longer holds — the schedules consumer then lifts the line through `clear_halt`, so every lift is one audited door. The router's own path is untouched."
    "`mark_stale`, `mark_halted` and `clear_halt` are declared `active → active` only. A v10 action declares ONE `:to` (definitions.clj records the same wart for `measure`/`measure_pilot`), so covering `parked` would mean six doors instead of three — and a parked seat is already scoped to nothing by the person's own hand, so neither a stale entry nor a halt on it tells anybody anything they did not choose."]})

;; ── :model ──────────────────────────────────────────────────────────

(def ^:private price-entry
  "One price field's shape — dollars per million tokens, exact
  decimals (never a float: the E.lit(\"0.02\") lesson, batch H)."
  [:decimal {:min 0 :max 10000}])

(defresource model
  {:kind :model
   :plural "models"
   :states [:active :retired]
   :initial :active
   :terminal #{}                       ; retirement is reversible, deliberately
   :nav :system
   :summary "{data.name} · {data.tier} · {state}"
   :label-template "{data.display}"
   :schema
   [:map
    [:name {:examples ["claude-sonnet-5"]
            :x-display
            {:raw true
             :label "API identifier"
             :help "The identifier the harness declares and the vendor answers to, spelled exactly. This is what a session's claim is matched against, so a near-miss is a seat that serves nothing."}}
     [:string {:min 1 :max 64}]]
    [:display {:examples ["Sonnet 5"]
               :x-display
               {:label "What a person reads"
                :help "The short name for a card, a ledger line and a ladder step — what you would say out loud."}}
     [:string {:min 1 :max 120}]]
    [:vendor {:examples ["anthropic"]
              :x-display
              {:raw true
               :label "Who serves it"
               :help "The vendor this identifier belongs to, lowercase."}}
     [:string {:min 1 :max 60}]]
    [:tier {:x-display
            {:label "Rung on the ladder"
             :choices {"frontier" "Frontier — the newest and dearest; where a seat opens before its judgment is known"
                       "strong" "Strong — most of the work, most of the time"
                       "economy" "Economy — the floor a seat reaches when the law is spoken at the door"}}}
     [:enum "frontier" "strong" "economy"]]
    [:price_input_per_mtok {:examples [3M]
                            :x-display
                            {:label "Input, $ per million tokens"
                             :help "What a million input tokens costs. Every price here is read at the moment a sitting closes and written down beside its cost, so a later reprice never moves a closed bill."}}
     price-entry]
    [:price_output_per_mtok {:examples [15M]
                             :x-display
                             {:label "Output, $ per million tokens"
                              :help "What a million output tokens costs."}}
     price-entry]
    [:price_cache_read_per_mtok {:examples [0.3M]
                                 :x-display
                                 {:label "Cache read, $ per million tokens"
                                  :help "What a million cache-read tokens costs — usually the cheapest line, and the reason a charter that fits in the cache is nearly free to carry."}}
     price-entry]
    [:price_cache_write_per_mtok {:examples [3.75M]
                                  :x-display
                                  {:label "Cache write, $ per million tokens"
                                   :help "What a million cache-write tokens costs."}}
     price-entry]
    [:notes {:optional true
             :x-display
             {:label "Anything else worth knowing"
              :help "A line for whoever reads the ladder later — a context window, a deprecation date, why this rung exists."}}
     [:maybe [:string {:max 240}]]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :tier #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   ;; ONE ROW PER IDENTIFIER, enforced by an index and not by a
   ;; sentence: a second row for one model would split its prices, and
   ;; a sitting costed against the wrong half would be wrong forever.
   :unique [[:name]]
   :create-guards [one-model-spelling]
   :actions
   {:retire {:from #{:active} :to :retired
             :safety {:idempotent true :reversible true :confirm true
                      :consequence "Seats may no longer be held for this model, and a restate naming it is refused; sittings already costed against its prices are untouched."}
             :display {:label "Retire" :style :danger :order 9}}
    :reactivate {:from #{:retired} :to :active
                 :safety {:idempotent true :reversible true :confirm false}
                 :display {:label "Reactivate" :order 1}}
    ;; A TRANSITION, so the history of prices is on record (R-9.3): the
    ;; log says what the prices were, when they moved, and by whose
    ;; hand — and a closed sitting keeps the copy it was costed
    ;; against, so the record and the bills never disagree.
    :reprice {:from #{:active} :to :active
              :input [:map
                      [:price_input_per_mtok {:examples [3M]
                                              :x-display {:label "Input, $ per million tokens"
                                                          :help "The new price for a million input tokens."}}
                       price-entry]
                      [:price_output_per_mtok {:examples [15M]
                                               :x-display {:label "Output, $ per million tokens"
                                                           :help "The new price for a million output tokens."}}
                       price-entry]
                      [:price_cache_read_per_mtok {:examples [0.3M]
                                                   :x-display {:label "Cache read, $ per million tokens"
                                                               :help "The new price for a million cache-read tokens."}}
                       price-entry]
                      [:price_cache_write_per_mtok {:examples [3.75M]
                                                    :x-display {:label "Cache write, $ per million tokens"
                                                                :help "The new price for a million cache-write tokens."}}
                       price-entry]]
              :record true
              :edit {:prefill [:price_input_per_mtok :price_output_per_mtok
                               :price_cache_read_per_mtok
                               :price_cache_write_per_mtok]}
              :safety {:idempotent true :reversible true :confirm false}
              :handler reprice-model
              :display {:label "Reprice" :order 2
                        :description "The vendor moved its prices — record the new four; nothing already closed changes"}}}
   :deviations
   ["R-9.2 calls `name` unique and R-4.7's precedent (roles.clj's `one-spelling`) judges only ACTIVE rows. The two disagree about a retired model, so this kind takes the index's reading: `one_model_spelling` refuses any spelling already on record, active or retired, and its sentence sends the reader to `reactivate`. A second row for one identifier would split its prices, and a closed sitting costed against the wrong half would be wrong forever."]})

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

(defn- sitting-person
  "The member the sitter acts for, or nil. `:acts-for` is the identity
  gate's own mark on a delegate principal (spec-connector-door § 3) —
  a person signed in through a tool — and it is nil for a Routine's
  run, which is the honest answer for a session with nobody in the
  chair."
  [ctx]
  (some-> (get-in ctx [:principal :acts-for]) str not-empty))

(def ^:private served-entry
  "One tool's line in `served` (R-10.6a): how many times the MCP door
  answered that tool, and how many bytes of text those answers
  carried. No price, because a price is the harness's and a byte is
  the engine's.

  A third count joins them when, and only when, a caller asked this
  door to make an answer smaller (waymark-fp62.7.16): `dropped`, the
  bytes the shape removed on the way through. `served` plus `dropped`
  is then what the power answered, which is the one arithmetic that
  says what the shape was worth. A tool nobody shaped carries no
  `dropped` key at all."
  [:map
   [:calls {:x-display {:label "Calls"
                        :help "How many times the door answered this tool."}}
    [:int {:min 0}]]
   [:bytes {:x-display {:label "Bytes"
                        :help "The UTF-8 length of the text those answers carried."}}
    [:int {:min 0}]]
   [:dropped {:optional true
              :x-display
              {:label "Bytes dropped"
               :help "The bytes this door removed because the caller asked for a smaller answer — text only, or a cap on the characters. Add it to the bytes above to see what the power itself answered. Absent when nothing was shaped."}}
    [:maybe [:int {:min 0}]]]])

(defresource sitting
  {:kind :sitting
   :plural "sittings"
   :states [:open :closed :abandoned]
   :initial :open
   :terminal #{:closed :abandoned}
   :nav :system
   :summary "Sitting of {data.seat} · {state}"
   ;; A SITTING IS ITS MEMBER'S (R-10.3): the session opens one before
   ;; it reads its queue and the harness's hook closes it when the
   ;; session ends, and neither of those moments is a good one to
   ;; discover that the row needs a scope entry. The guards still judge
   ;; every invoke; this only decides which doors are visible enough to
   ;; be knocked on — the grant's own posture, one kind over.
   :own-surface {:by :member :actions #{"create" "close" "tally"}}
   :schema
   [:map
    [:seat {:kind :seat
            :x-display
            {:label "The seat woken"
             :help "The office this wake belongs to. Its budget is what this sitting spends against, and its ledger is where the cost lands."}}
     :waymark/ref]
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
                            :help "Written at the close from the model's prices at that moment; a reprice afterwards does not move it. On an OPEN sitting it is the running cost (R-12.27): what the tallies so far have spent, so the week's wall can see a sitting that has not ended."}}
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
     [:maybe :waymark/instant]]]
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
                      [:input_tokens 0] [:output_tokens 0]
                      [:cache_read_tokens 0] [:cache_write_tokens 0]
                      [:turns 0] [:transitions 0] [:refusals 0]
                      ;; R-10.6a: an open sitting that has read
                      ;; nothing yet says so with an empty map, not
                      ;; with a hole
                      [:served {}]]
               ;; the delegate's own person, when there is one — the
               ;; identity gate's mark, never a claim in the request
               (sitting-person ctx) (conj [:person (sitting-person ctx)]))))
   :filterable {:state #{:eq :in}
                :seat #{:eq}
                :member #{:eq}
                :model #{:eq}
                :grant #{:eq}
                :started_at #{:after :before :range}}
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
     :input report-input
     :record true
     ;; :edit-shape — a close welds the first counts onto a row that
     ;; has none; there is no earlier value to prefill from and no
     ;; second close, so the fence an :edit implies would be an etag
     ;; demanded of a session-end hook for fields nobody has written.
     :waives #{:edit-shape}
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
     :handler tally-sitting
     :display {:label "Tally" :order 2
               :description "Write what this sitting has spent so far — the counts, the running cost at today's prices, and the stamp the sweep reads"}}

    ;; R-7.6: a sitting left open past two cadences is the boot
    ;; sweep's, and it ends with NO tokens — the absence of a bill,
    ;; not a zero one.
    :abandon
    {:from #{:open} :to :abandoned
     :guards [the-engines-own-hand]
     ;; no handler: nothing is written. The ending of a sitting
     ;; nobody closed is the ABSENCE of a bill, not a zero one, and
     ;; the counts it already carries are what it did before it was
     ;; lost.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A sitting nobody closed is over with no cost recorded; the session that would have reported its tokens is gone."}
     :display {:label "Abandon" :order 9}}}
   :deviations
   ["R-10.2 lets a sitting's `model` be null (R-9.4: a token with no claim has model null), and R-10.7 wants the collection filterable by model. A promoted column is generated only for a non-`:maybe` entry, so those two cannot both be had: `model` is required at the create door, and the session's claim wins over it when there is one. A harness with nothing to declare names the row it is running as."
    "R-10.6 has the engine count transitions and refusals. `bump-counter!` is a maintenance write (`store/update-data!`, jobs.clj's progress precedent) rather than a transition: a logged transition per counted transition would double the log — the counter would cost more log than the thing it counts. The `close` is a real transition and freezes both numbers."
    "`tally` writes a `cost_usd` and NO `prices` map, where the close writes both. The prices are copied down beside a bill a reprice must not be able to move, and the only bill is the close's; a tally's cost is a reading of the row at that moment, re-read at the next turn, and a prices map beside it would say a running figure was final. A sitting closed by the sweep from its last tally is costed by the close, at the close's prices, like every other."
    "`close` and `tally` share ONE input (`report-input`) rather than declaring the five counts twice. The two doors take the same report from the same hook — one ends the sitting, the other writes the running total — and two spellings would be two shapes for a harness to keep in step, which is exactly the drift § 3 of the spec is about."
    "`harness_session` is on the BIRTH door as well as the close's (R-12.15, R-12.17), which no other count-bearing field is. The reason is that it is not a count: it is the only fact a session knows at the sit that the engine cannot derive, and the pairing it makes is what lets two overlapping wakes of one seat each end their own sitting. It is `:maybe`, so it is not filterable and the pairing reads one page of the seat's open sittings rather than querying — `model`'s recorded wall, one field over. The close writes it only onto a row that carries none: a report naming another run's id must not move a bill."]})

;; ── the seam wave two calls ─────────────────────────────────────────

(def walk-create-action
  "The action a walk seat's default `wake_on` entry names: a row
  arriving in the queue is the work this seat exists to do."
  "create")

(defn effective-wake-on
  "What actually wakes this seat (R-12.22), as `wake-entry-schema`
  entries.

  The seat's own `wake_on` when it wrote one. A seat that walks a
  queue and wrote none behaves as ONE entry — the walk's kind with
  the action `create` — and that default is computed HERE, at read
  time, because the spec says the engine writes nothing for it: a
  default in the row would be a value nobody chose, and a restate of
  `walk` would leave it naming the queue the seat no longer walks. A
  seat with neither wakes on its cadence and a person's fire alone,
  which is the empty vector.

  The count wake (R-12.24) asks nothing of the default: the computed
  entry carries no `at_least`, so it stays the transition wake it has
  always been — a walk seat wakes on the row that arrived, and a seat
  that wants a batch says how big a batch is."
  [seat-row]
  (let [written (get-in seat-row [:data :wake_on])
        walk (some-> (get-in seat-row [:data :walk]) str not-empty)]
    (cond
      (seq written) (vec written)
      walk [{:kind walk :actions [walk-create-action]}]
      :else [])))

(defn open-sitting-for-grant
  "The open sitting a request under `grant-id` is counted against, or
  nil. ONE query, by the promoted `grant` column and state — the
  router runs it on every write and every refusal, so it must stay one
  lookup (R-10.6). A request with no open sitting counts nothing,
  which is what nil means here."
  [eng grant-id]
  (when (and grant-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (first (store/query-rows (:storage eng) tx :sitting
                                 {:grant (str grant-id) :state :open}
                                 {:limit 1 :newest-first true}))))))

(def ^:private open-sitting-page
  "The most open sittings one seat is read for at a close. A seat
  holds one open sitting in the ordinary case and a handful when runs
  overlap; anything past this is a seat whose sweep is overdue, and
  the honest fix is the sweep, not a longer page."
  50)

(defn open-sitting-for-seat
  "The open sitting a SESSION-END REPORT belongs to (R-12.17), or nil.

  `open-sitting-for-grant`'s shape, one field over and one reading
  past it. The hook at the end of a run presents the seat's key and
  knows nothing about which grant the firing wore, so the lookup is by
  the promoted `seat` column and state — one query, newest first.

  WHICH of the seat's open sittings is the reading. `harness_session`
  is `:maybe`, so it can never be a promoted column and never a
  filter (the kind's own recorded deviation about `model` is the same
  wall); the pairing is therefore done in code over one page:

    1. the newest open sitting stamped with the reported session — the
       run that is ending, named by its own id;
    2. failing that, the newest open sitting carrying NO stamp — a
       wake nobody's hook will ever name, which is the one an unnamed
       report is most likely about;
    3. failing that, the newest open sitting.

  A report with no session id starts at 2, which is why a stamped
  sitting is not closed by somebody else's report while its own run
  is still going."
  ([eng seat-id] (open-sitting-for-seat eng seat-id nil))
  ([eng seat-id harness-session]
   (when (and seat-id (get (inv/resources eng) :sitting))
     (let [rows (store/with-tx (:storage eng)
                  (fn [tx]
                    (store/query-rows (:storage eng) tx :sitting
                                      {:seat (str seat-id) :state :open}
                                      {:limit open-sitting-page
                                       :newest-first true})))
           stamp-of #(some-> (get-in % [:data :harness_session]) str not-empty)
           wanted (some-> harness-session str not-empty)]
       (or (when wanted (first (filter #(= wanted (stamp-of %)) rows)))
           (first (remove stamp-of rows))
           (first rows))))))

(defn bump-counter!
  "Add one to an open sitting's `:transitions` or `:refusals`. A
  MAINTENANCE write — document only, version untouched, no transition
  (see the ns docstring). → the new count, or nil when there was
  nothing to count: an unknown id, a sitting already closed, or a
  counter this kind does not keep."
  [eng sitting-id counter]
  (when (and sitting-id
             (contains? #{:transitions :refusals} counter)
             (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :sitting
                                        (str sitting-id) {:for-update true})]
          (when (= :open (:state row))
            (let [n (inc (long (or (get (:data row) counter) 0)))]
              (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                  (assoc (:data row) counter n) nil)
              n)))))))

(defn add-served!
  "Add one call and `bytes` bytes to an open sitting's `served`, under
  the tool that answered them (R-10.6a). `bump-counter!`'s write, one
  field wider: THE DOCUMENT ONLY — version untouched, no transition —
  because a log line per tool answer would cost more than the thing it
  records.

  The key is the tool's own name. Keys are open, so a tool this engine
  gains tomorrow needs no schema change, and a name is keywordized on
  the way in because the store hands every key back as a keyword.

  `dropped` is the fourth argument and it is optional
  (waymark-fp62.7.16): the bytes the door removed from this answer
  because the caller asked for a smaller one. It is added to the line
  only when it is more than nothing, so a tool nobody shaped keeps the
  two counts it always had.

  → the tool's new line, {:calls n :bytes b} and `:dropped` when
  there is one, or nil when there was nothing to count: an unknown id,
  a sitting already closed, a call with no tool name, or a kind this
  engine does not serve."
  ([eng sitting-id tool bytes] (add-served! eng sitting-id tool bytes 0))
  ([eng sitting-id tool bytes dropped]
   (let [tool (some-> tool str not-empty)
         bytes (long (or bytes 0))
         dropped (long (or dropped 0))]
     (when (and sitting-id tool (not (neg? bytes)) (not (neg? dropped))
                (get (inv/resources eng) :sitting))
       (store/with-tx (:storage eng)
         (fn [tx]
           (when-some [row (store/load-row (:storage eng) tx :sitting
                                           (str sitting-id) {:for-update true})]
             (when (= :open (:state row))
               (let [k (keyword tool)
                     prior (get-in (:data row) [:served k])
                     total (+ (long (or (:dropped prior) 0)) dropped)
                     line (cond-> {:calls (inc (long (or (:calls prior) 0)))
                                   :bytes (+ (long (or (:bytes prior) 0)) bytes)}
                            (pos? total) (assoc :dropped total))]
                 (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                     (assoc-in (:data row) [:served k] line) nil)
                 line)))))))))

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

;; ── the keyed sitter session's seam (R-12.13) ───────────────────────

(defn seat-by-key
  "The ACTIVE seat whose `sitter_key` is exactly this key, or nil.

  The compare is `MessageDigest/isEqual` over UTF-8 bytes — constant
  time in the length of the two arrays, so a caller cannot walk the
  key one character at a time off the clock. Every active seat is
  read, which is honest arithmetic here: seats are an office per kind
  of judgment and a house has a handful, `sitter_key` is :secret and
  therefore may never be :filterable (resource/check-secret!: a filter
  is a value oracle over what the projection conceals), and the read
  is one query behind a door the MCP surface calls once per session.

  A blank key answers nil without touching storage: a seat that was
  never offered a key holds nil, and nil must never match nil."
  [eng key]
  (when-some [key (some-> key str not-empty)]
    (when (get (inv/resources eng) :seat)
      (let [wanted (.getBytes key StandardCharsets/UTF_8)]
        (store/with-tx (:storage eng)
          (fn [tx]
            (->> (store/query-rows (:storage eng) tx :seat {:state :active}
                                   {:limit 500})
                 (filter (fn [row]
                           (when-some [held (some-> (get-in row [:data :sitter_key])
                                                    str not-empty)]
                             (MessageDigest/isEqual
                              wanted (.getBytes held StandardCharsets/UTF_8)))))
                 first)))))))

(defn sitter-id
  "The member id of the seat's sitter — `seat:<the seat's id>`.

  DERIVED, never stored: the sitter is the office, not a person, so
  there is exactly one of it per seat and its id must be computable
  from the seat row alone. Two sessions that sit in the same seat are
  the same sitter, which is the whole point — the ledger reads one
  actor per office."
  [seat-row]
  (str "seat:" (:id seat-row)))

(defn sitter-display
  "What a person reads beside a transition the sitter made: the seat's
  own name, marked as the office rather than the person."
  [seat-row]
  (str (get-in seat-row [:data :name]) " (seat)"))
