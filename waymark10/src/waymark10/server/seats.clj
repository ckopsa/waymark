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

  ── the chair, and the one Routine a model holds ───────────────────

  A Routine stands for a MODEL, not for a seat. The model row is the
  CHAIR: it holds the key its sessions present (`sitter_key`), and the
  runner links whose pools name it carry the credential its firings go
  out on (ticket 9fa42626). A seat's chair is
  the FIRST model of its `held_for` — `chair-of` says so in one place
  — so a step down the ladder is one `restate` of that list and no
  second Routine. What the seat holds instead is `instructions`: the
  words the engine composes into the fire text, so a firing can no
  longer run on instructions nobody in this house can read.

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
  - `judgment` IS A TYPED REF FOR THE SAME REASON, AND CARRIES THE
    SAME DEBT. The seat's `judgment` (R-4 of waymark-fp62.11) names
    `:kind :judgment`, so this kind cannot boot on an engine that
    leaves the judgment kind out — the judgment module must enrol
    `:always`, exactly as `:schedules` does. Held as an opaque string
    it would have lost the picker and the dangling-ref check, and a
    seat citing a judgment no engine holds is precisely the drift
    this kind exists to end.
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
            [waymark10.time :refer [instant-of]]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.modules.hooks :as hooks]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.keys :as keys]
            ;; the key primitives: hash, compare, live entry, fire text
            ;; (ticket 7bdd1a37, carve 1 of 4)
            [waymark10.server.seats.keys :as seat-keys]
            ;; a fire's key, from mint to spend (ticket bb84d8d9,
            ;; carve 3 of 4)
            [waymark10.server.seats.fire :as fire]
            ;; the inbox, its keys, the feed tokens and the place, and
            ;; the mode and lease reads they share (ticket bbf88202,
            ;; carve 4 of 4)
            [waymark10.server.seats.inbox :as seat-inbox]
            ;; the wake and the inbox as subscriptions (ticket
            ;; bbf88202, carve 4 of 4)
            [waymark10.server.seats.wakes :as seat-wakes]
            ;; the rows a seat's sittings hold, the walk's left-out rows
            ;; and the claim (ticket 806dbaec, round two)
            [waymark10.server.seats.holds :as seat-holds]
            ;; a sitting's judgment, its counters and its seat's health
            ;; (ticket b44dbe85, round three)
            [waymark10.server.seats.health :as seat-health]
            ;; the engine's halt, stale and lapse writes on a seat row
            ;; (ticket b44dbe85, round three)
            [waymark10.server.seats.halts :as seat-halts]
            ;; the seat, model and sitting doors' guards, each referred
            ;; by the name its door lists (ticket 2019c930, round four)
            [waymark10.server.seats.guards
             :refer [a-person a-person-at-the-chair a-person-or-the-engine
                     author-can-take-it cast-only-on-a-clone folded-by-a-merge
                     followed-by-a-supersede held-for-active-models
                     inbox-cues-filter-declared-fields inbox-cues-filter-is-filterable
                     inbox-names-real-actions inbox-names-real-kinds
                     judgment-in-force judgment-not-superseded key-not-written-by-hand
                     lease-expired lease-still-running linked-for-fire
                     merge-target-is-active not-a-sitter not-halted not-interactive
                     not-parked one-model-spelling one-seat-spelling
                     step-carries-a-note the-engine-or-the-persons-tap
                     the-engines-own-hand ttl-within-standing
                     walk-judgment-not-superseded walk-leaves-its-filter
                     walk-matches-the-judgment walk-names-a-kind-in-scope
                     walk-not-hidden-by-its-default walk-promises-are-served]]
            ;; a sitting's report, close, floor and bill, each referred
            ;; by the name its door lists (ticket f190fba5, round five)
            [waymark10.server.seats.bill :as seat-bill
             :refer [a-bill-to-open abandon-sitting close-sitting expire-sitting
                     fire-entry-of floor-posts opening-posts refloor-sitting
                     renew-sitting report-posts report-usage reports-only-grow
                     tally-sitting]]
            ;; the seat and sitting pages' words (ticket f190fba5, round five)
            [waymark10.server.seats.page :refer [at-work-field reads-as-field]]
            ;; the crack a staffing change opens, the walk's drops and the
            ;; restate's replay (ticket 9b8c96ae, round six)
            [waymark10.server.seats.staffing :as staffing
             :refer [a-crack-waits-on-the-person a-restate-says-what-it-drops
                     cracks-opened cracks-told the-seat-writes-it? wake-drop-opened]]
            ;; the seat's field schemas and their help (ticket 9b8c96ae,
            ;; round six)
            [waymark10.server.seats.fields
             :refer [acts-as-cast-help charter-example delegates-field-help
                     delegates-schema delivers-help delivers-schema feed-url-field
                     idle-help inbox-field instructions-example keep-transcripts-field
                     max-open-sittings-help mode-choices mode-help serves-choices
                     transcript-days-field]]
            ;; who holds a walk row (docs/spec-leases.md §3): the claim
            ;; acquires, a sitting's ending releases
            [waymark10.server.leases :as leases]
            [waymark10.server.members :as members]
            [waymark10.server.roles :as roles]
            ;; a domain's budget and its wall (carve 2, ticket fdd28816),
            ;; a sitting's bill, the ledger read and the budget doors
            ;; (carve 2b, ticket 791cf8a1)
            [waymark10.server.seats.ledger :as ledger :refer [moved-by-a-domain]]
            [waymark10.server.seats.owners :refer [opening-person scope-inside-the-owners
                                                   a-restate-adds-no-copy]]
            [waymark10.server.seats.pools :as pools]
            [waymark10.server.seats.words :as words :refer [price-entry]]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

;; ── the engine's own hand ───────────────────────────────────────────

(def seats-actor "`fire/seats-actor`." fire/seats-actor)

;; Carve 4 of 4 (ticket bbf88202). One line for each var another
;; namespace or this one calls; the defns live in
;; `waymark10.server.seats.inbox` and `waymark10.server.seats.wakes`.
;; They stand ahead of every caller, so no `declare` is needed.

(def interactive-mode seat-inbox/interactive-mode)
(def interactive-seat? seat-inbox/interactive-seat?)
(def ^:private ->instant seat-inbox/->instant)
(def ^:private now-of seat-inbox/now-of)
(def lease-live? seat-inbox/lease-live?)
(def lease-seconds seat-inbox/lease-seconds)
(def place-origin seat-inbox/place-origin)
(def default-inbox seat-inbox/default-inbox)
(def own-held-calls? seat-inbox/own-held-calls?)
(def inbox-of seat-inbox/inbox-of)
(def cue-note seat-inbox/cue-note)
(def cue-note-holes? seat-inbox/cue-note-holes?)
(def inbox-keys-max seat-inbox/inbox-keys-max)
(def issue-inbox-key! seat-inbox/issue-inbox-key!)
(def inbox-url seat-inbox/inbox-url)
(def inbox-key-header seat-inbox/inbox-key-header)
(def inbox-wait-max seat-inbox/inbox-wait-max)
(def inbox-how seat-inbox/inbox-how)
(def inbox-client seat-inbox/inbox-client)
(def place-feed-paths seat-inbox/place-feed-paths)
(def place-feed seat-inbox/place-feed)
(def place-claude-md seat-inbox/place-claude-md)
(def place-mcp-json seat-inbox/place-mcp-json)
(def place-tar seat-inbox/place-tar)
(def inbox-sitting-by-key seat-inbox/inbox-sitting-by-key)
(def keep-inbox-lease! seat-inbox/keep-inbox-lease!)
(def feed-token-seconds seat-inbox/feed-token-seconds)
(def feed-tokens-max seat-inbox/feed-tokens-max)
(def issue-feed-token! seat-inbox/issue-feed-token!)
(def feed-seat-by-token seat-inbox/feed-seat-by-token)
(def renew-feed-token! seat-inbox/renew-feed-token!)

(def ^:private walk-entries seat-wakes/walk-entries)
(def ^:private walk-entry seat-wakes/walk-entry)
(def ^:private entry-filter seat-wakes/entry-filter)
(def walk-filter seat-wakes/walk-filter)
(def walk-create-action seat-wakes/walk-create-action)
(def judgment-reopen-kind seat-wakes/judgment-reopen-kind)
(def judgment-reopen-action seat-wakes/judgment-reopen-action)
(def effective-wake-on seat-wakes/effective-wake-on)
(def managed-wakes seat-wakes/managed-wakes)
(def wake-subscription seat-wakes/wake-subscription)
(def wake-entries seat-wakes/wake-entries)
(def door-entries seat-wakes/door-entries)
(def door-subscribed? seat-wakes/door-subscribed?)
(def wakes-of seat-wakes/wakes-of)
(def missing-wake-subscriptions seat-wakes/missing-wake-subscriptions)
(def keep-managed-wakes! seat-wakes/keep-managed-wakes!)
(def restate-managed-wakes! seat-wakes/restate-managed-wakes!)
(def wake-seat! seat-wakes/wake-seat!)
(def wake-seat-through! seat-wakes/wake-seat-through!)
(def pull-of-seat? seat-wakes/pull-of-seat?)
(def inbox-subscriptions seat-wakes/inbox-subscriptions)
(def unheard-cues seat-wakes/unheard-cues)
(def hears-own-calls? seat-wakes/hears-own-calls?)
(def missing-inbox-subscriptions seat-wakes/missing-inbox-subscriptions)
(def restate-managed-inbox! seat-wakes/restate-managed-inbox!)

(def closed-by-paths
  "The five ways a sitting ends closed: through the `close` DOOR, by
  the run's own stop HOOK, by the idle SWEEP (`sweep-sittings!`),
  born closed as a MISSED fire (`wakes/sweep-missed!`), or by a
  TAKEOVER, when a new sit of its seat found it silent (ticket
  e3add1a6; its field went with seat S5).
  Since leases 3b the last two are history and EXPIRY ends it."
  ["door" "hook" "sweep" "missed" "takeover" "expiry"])

(def ^:private closed-by-choices
  {"door" "Door — closed through the sitting's own close action"
   "hook" "Hook — closed by the run's stop hook"
   "sweep" "Sweep — closed by the engine after the sitting went idle"
   "missed" "Missed — a fire nobody sat in, born closed"
   "takeover" "Takeover — closed by a new sitting of its seat after it went silent"
   "expiry" "Expiry — closed by the engine after its lease ran out"})

;; Round three (ticket b44dbe85): the judge, its numbers and the seat
;; health rollup live in `waymark10.server.seats.health`.

(def outcomes seat-health/outcomes)

(def sitting-outcomes seat-health/sitting-outcomes)
(def ^:private outcome-choices @#'seat-health/outcome-choices)
(def health-thresholds seat-health/health-thresholds)
(def health-alert-defaults seat-health/health-alert-defaults)
(def ^:private outcome-help @#'seat-health/outcome-help)
(def ^:private flags-help @#'seat-health/flags-help)

;; the walls a seat halts for, and the halt's writes, live in
;; `waymark10.server.seats.halts` (ticket b44dbe85, round three)
(def halt-reasons seat-halts/halt-reasons)

(def halt-mark seat-halts/halt-mark)

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

(def chair-of pools/chair-of)

;; Carve 2b (ticket 791cf8a1): a sitting's bill, the ledger read and the
;; budget doors live in `waymark10.server.seats.ledger`. One line for
;; each, where its defn stood, so every caller below still finds it.

(def cost-scale ledger/cost-scale)

;; Round four (ticket 2019c930): the guards of the seat, model and
;; sitting doors live in `waymark10.server.seats.guards`, referred above.

(def default-idle-seconds "`fire/default-idle-seconds`." fire/default-idle-seconds)

;; ── the sitting's lease (docs/spec-leases.md §2, §3) ────────────────

(def lease-lapsed? "`seat-holds/lease-lapsed?`." seat-holds/lease-lapsed?)

(def ^:private lease-end seat-holds/lease-end)

;; ── the pool a fire reads (S1b of docs/spec-seat.md §22) ────────────

(def pooled-ids pools/pooled-ids)

(def pool-from pools/pool-from)

;; ── handlers ────────────────────────────────────────────────────────

(def ^:private restatable ledger/restatable)

(def ^:private lifts-the-line? ledger/lifts-the-line?)

(defhandler restate-seat [row inp ctx]
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
  (let [lift? (lifts-the-line? row inp)
        restated
        (cond-> (-> (reduce (fn [r f] (assoc-in r [:data f] (get inp f)))
                            row restatable)
                    (update :data dissoc :stale))
          ;; `domain` is not among `restatable` (epic aff24e84, piece 1): a
          ;; restate that leaves it out keeps the stored value, so no
          ;; caller written before the field has to name it
          (contains? inp :domain) (assoc-in [:data :domain] (:domain inp))
          ;; `serves` is restated the same way (epic aff24e84, piece 5)
          (contains? inp :serves) (assoc-in [:data :serves] (:serves inp))
          ;; and `walk_promises` (R-P.6.1, ticket f4598304)
          (contains? inp :walk_promises) (assoc-in [:data :walk_promises] (:walk_promises inp))
          lift? (update :data dissoc :halt))]
    ;; E-5.4: a walk seat's managed subscriptions follow its walk
    (restate-managed-wakes! ctx row restated)
    ;; E-5.5: and its managed pull subscriptions follow its inbox
    (restate-managed-inbox! ctx row restated)
    restated))

(def close-breaker ledger/close-breaker)

(defhandler unpark-seat [row _inp ctx]
  ;; R-2: the seat's state IS the input of the `seat_not_active` wall,
  ;; and `unpark` is the hand that moves it. The other three lines
  ;; stand — a park does not spend fuel and does not change the model
  ;; list — and the door that reads them judges them.
  ;;
  ;; INVARIANT 4: an authored seat's FIRST unpark is its person's
  ;; approval, and the row says who gave it and when. From then on its
  ;; author restates it within the ceiling with no new tap.
  (let [first-approval? (and (some-> (get-in row [:data :authored_by]) str
                                     not-empty)
                             (nil? (get-in row [:data :approved_by])))
        approver (when first-approval?
                   (or (some-> (delegation/allowed-hold ctx :seat (:id row))
                               (get-in [:data :decided_by]) str not-empty)
                       (str (get-in ctx [:principal :id]))))]
    (cond-> row
      (= "seat_not_active" (str (get-in row [:data :halt :reason])))
      (update :data dissoc :halt)
      first-approval?
      (update :data assoc :approved_by approver :approved_at (:now ctx)))))

(defhandler hand-seat-to [row inp ctx]
  ;; INVARIANT 3 and 4: the person's hand writes the author, and the
  ;; same tap IS the approval — the seat is live already, so there is
  ;; no first unpark to carry it. The person is the one who decided a
  ;; held call when this replays one, else the hand at the door.
  (let [person (or (some-> (delegation/allowed-hold ctx :seat (:id row))
                           (get-in [:data :decided_by]) str not-empty)
                   (delegation/owner-of ctx)
                   (str (get-in ctx [:principal :id])))]
    (update row :data assoc
            :authored_by (str (:author inp))
            :owner person
            :approved_by person
            :approved_at (:now ctx))))

(defhandler take-seat-back [row _inp _ctx]
  ;; hand_to undone: the author and the approval go, and the author's
  ;; restates are held again under invariant 4.
  (update row :data dissoc :authored_by :approved_by :approved_at))

(defn- parked-says
  "hand_to and take_back run from `active` alone, like restate; a
  parked seat is told the way back."
  [row _ctx]
  (when (= :parked (:state row))
    "The seat is parked. Unpark it first — unpark is the person's own lever."))

(defn seat-born
  "The seat's on-create (invariant 3 and 4 of server/delegation): a
  seat whose author is a DELEGATING seat is born parked, and carries
  `authored_by` and `owner` from its first moment. A seat a person
  opens — in person, or through a delegate that acts for them — is
  born live and carries that person as `owner`, so its later restates
  are judged against them (`scope-inside-the-owners`). Any other hand
  writes no owner.

  The author is read off the held call when this birth replays one,
  so a sitter whose grant lapsed between the ask and the tap still
  writes the author the person approved."
  [row ctx]
  (if-some [author (delegation/authoring-seat ctx :seat nil)]
    (let [owner (or (delegation/owner-of ctx)
                    (some-> (get-in author [:data :owner]) str not-empty))]
      (-> row
          (assoc :state :parked)
          (update :data assoc :authored_by (str (:id author)))
          (cond-> owner (assoc-in [:data :owner] owner))))
    (if-some [person (opening-person ctx)]
      (assoc-in row [:data :owner] person)
      row)))

(defhandler write-stale [row inp _ctx]
  (assoc-in row [:data :stale] (:stale inp)))

(defhandler drop-lapsed [row inp _ctx]
  (assoc-in row [:data :scope] (:scope inp)))

(defhandler write-halt [row inp ctx]
  (assoc-in row [:data :halt]
            (cond-> {:reason (:reason inp) :since (:now ctx)}
              (:detail inp) (assoc :detail (:detail inp)))))

(defhandler clear-halt-mark [row _inp _ctx]
  (update row :data dissoc :halt))

;; R-12.12: a new offer REPLACES the old one — at most one live sitter
;; key per seat, members' set-reentry overwrite exactly. The key the
;; person pasted into yesterday's Routine dies the moment a fresh one
;; lands. The row keeps the key's hash alone (R-22.6), as it keeps the
;; fire key's and the feed token's: no read can answer the key again.
(defhandler set-sitter-key [row inp _ctx]
  (-> row
      (update :data dissoc :sitter_key)
      (assoc-in [:data :sitter_key_hash] (keys/hash-b64 (:key inp)))))

(defhandler clear-sitter-key [row _inp _ctx]
  (update row :data dissoc :sitter_key :sitter_key_hash))

(defn- hash-standing-key
  "The seat's and the model's :data-migrate (R-22.6), members'
  `hash-standing-reentry` exactly: a row from before the hash holds its
  standing key raw. Hash it in place and drop the raw value, so the key
  a Routine already holds still opens the seat. Idempotent: a row with
  no raw key is left as it is."
  [data]
  (if (contains? data :sitter_key)
    (let [h (keys/hash-b64 (:sitter_key data))]
      (cond-> (dissoc data :sitter_key)
        h (assoc :sitter_key_hash h)))
    data))

(def ^:private dropped-fields
  "The seat fields seat S5 dropped (R-22.6) after the production count
  of 2026-10-24: each was read by nothing, or held its default on every
  seat. The doors no longer declare them, so a body that names one is
  refused as an unknown key."
  [:claim_silence_seconds :ignore_sitting_budget :substitute_drop
   :health_window :health_alerts :health_breaker])

(defn- migrate-seat-data
  "The seat's :data-migrate: `hash-standing-key`, and an old row leaves
  the `dropped-fields` behind, so it reads without them. Idempotent."
  [data]
  (apply dissoc (hash-standing-key data) dropped-fields))

;; the pool order alone: the links in the pool are each link's own
;; `pools`, written through the link's doors
(defhandler set-runner-order [row inp _ctx]
  (update row :data #(if-some [o (:runner_order inp)]
                       (assoc % :runner_order o)
                       (dissoc % :runner_order))))

;; the pool order (waymark ticket 529deb73): `least_used` spreads the
;; fires; `prefer` sends each to the first link that may fire, so a
;; later link takes only the overflow.
(def runner-orders ["least_used" "prefer"])

(def runner-order-choices
  {"least_used" "The least-used link that may fire; list order breaks a tie."
   "prefer" "The first link in list order that may fire; a later link takes only the overflow."})

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
      (assoc-in [:data :standing_ttl_seconds] (:standing_ttl_seconds inp))
      (assoc-in [:data :budget_usd_per_week] (:budget_usd_per_week inp))))

(def ^:private follow-successor-input
  [:map
   [:judgment {:kind :judgment
               :x-display
               {:label "The successor"
                :spelled-by-hand "The judgment the superseded one named as its successor, written by the supersede."}}
    :waymark/ref]
   [:superseded {:kind :judgment
                 :x-display
                 {:label "The judgment superseded"
                  :spelled-by-hand "The judgment this seat said until it was superseded, written by the supersede."}}
    :waymark/ref]])

(defhandler follow-successor [row inp _ctx]
  (assoc-in row [:data :judgment] (:judgment inp)))

(defhandler merge-seat [row inp ctx]
  ;; § 6, in order: fold the two scopes per kind (waymark-ycp's
  ;; merge — one entry per kind, never appended), keep the LARGER
  ;; ttl and budget, and write
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

(defhandler restate-model [row inp _ctx]
  (reduce (fn [r f] (if (contains? inp f) (assoc-in r [:data f] (get inp f)) r))
          row
          [:name :display :notes]))

;; Round five (ticket f190fba5): a sitting's report, close, floor and
;; bill live in `waymark10.server.seats.bill`, referred above. The
;; four expire_floor_test reads through this namespace stand here.

(def cost-of ledger/cost-of)

(def ^:private apply-report seat-bill/apply-report) ; expire_floor_test
(def ^:private floor-onto seat-bill/floor-onto) ; expire_floor_test
(def ^:private late-report seat-bill/late-report) ; expire_floor_test
(def ^:private refloor seat-bill/refloor) ; expire_floor_test

;; ── seat health: the outcome and the flags (ticket fad586b7) ────────
;; Pure over the sitting's document, the transitions made under it and
;; the earlier sittings of its seat. No model reads any of it.

;; The judge lives in `waymark10.server.seats.health` (ticket b44dbe85,
;; round three); the seat's restate reads it here.

(def default-delivers seat-health/default-delivers)

(def sitting-health seat-health/sitting-health)

(def ^:private leased-row-ids seat-holds/leased-row-ids)

(def ^:private release-grace-default seat-holds/release-grace-default) ; wakes_test derefs it

(def sit-deadline-seconds "`fire/sit-deadline-seconds`." fire/sit-deadline-seconds)

(def open-ledger ledger/open-ledger)

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

(def ^:private an-open-sitting
  {:seat "02eee915-354f-4010-a36f-7adfbd532395"
   :model "claude-sonnet"
   :started_at "2026-09-28T10:00:00Z"})

(defscenario another-hand-abandons-a-sitting-at-the-persons-tap
  "A seat whose grant admits `sitting.abandon` asks to end another
   seat's sitting, and the ask waits for the person's tap: a held
   call, never a not-found."
  {:kind    :sitting
   :attempt :abandon
   :row     {:state :open :data an-open-sitting}
   :as      {:id "mayor" :type :agent}
   :expect  {:refused :the-engine-or-the-persons-tap
             :because "held for the person's tap"}})

(defscenario the-sweep-abandons-a-lost-sitting
  "And the sweep still ends a lost sitting by its own hand."
  {:kind    :sitting
   :attempt :abandon
   :row     {:state :open :data an-open-sitting}
   :as      {:id "waymark10-seats" :type :system}
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
   :row     {:state :active :data (assoc an-open-seat :sitter_key_hash (keys/hash-b64 a-minted-key))}
   :as      {:id "inbox-clerk" :type :agent}
   :expect  {:refused :a-person
             :because "a person opens"}})

(defscenario the-person-revokes-the-seats-key
  "And the person takes it back, which is the whole of retiring a
   Routine's authority."
  {:kind    :seat
   :attempt :revoke_key
   :row     {:state :active :data (assoc an-open-seat :sitter_key_hash (keys/hash-b64 a-minted-key))}
   :as      {:id "colton" :type :human}
   :expect  {:allowed true}})

;; ── :seat ───────────────────────────────────────────────────────────

;; The seat's field schemas live in seats/fields.clj (ticket 9b8c96ae).


(def default-domain ledger/default-domain)

(def domain-name-of ledger/domain-name-of)

(defn- in-domain-field
  "The seat's :computed `in_domain`: `domain-name-of` over the read the
  render ctx lends."
  [row ctx]
  (domain-name-of (:read ctx) row))

(defn pull-wait
  "Why this seat waits rather than fails (LP2): while every live link of
  the pool its fire goes out through is a pull link whose localfire is
  away, the sentence naming the first (`runner-links/away-sentence`);
  nil for a seat with no pool, or a link connected.
  The schedule is read through `find'`, the chair and the links through
  `read'`. The pool is `pool-from`'s: this file cannot require
  runner-links, which requires it."
  [read' find' row now]
  (let [sched (first (find' :schedule {:seat (str (:id row))} {:limit 1}))
        pool (pool-from #(find' :runner_link {} {:limit 100000})
                        sched
                        (some->> (chair-of row) (read' :model)))]
    (when pool
      ((requiring-resolve 'waymark10.server.runner-links/away-sentence)
       (keep #(read' :runner_link %) pool) now))))

(defn- held-reason-field
  "The seat's :computed `held_reason`: `pull-wait` over the reads the
  render ctx lends, at its clock. waiting_on reads it as a hold field,
  so the seat's verdict says it waits."
  [row ctx]
  (when-some [find' (:find ctx)]
    (pull-wait (:read ctx) find' row (:now ctx))))

(defn spend-by-domain
  "`ledger/spend-by-domain` over this file's lease read: the dollars
  this seat's spend postings of the wall's window add, by domain name."
  [read' find' row now]
  (ledger/spend-by-domain leased-row-ids read' find' row now))

(defn fired-by
  "The subscriptions on the seat's fire door, active or paused, each
  with its cursor position (the `door` family's `door:<id>`,
  `consumers/member-cursor`; nil before its first drain or with no
  `cursor'`) and its last skip (docs/spec-events.md E-4.4): who wakes
  this seat, and why not."
  [find' cursor' row]
  (into []
        (comp (mapcat #(find' :subscription {:state %} {:limit 500}))
              (filter #(and (= "door" (some-> (get-in % [:data :receiver :type]) name))
                            (= (str (:id row)) (str (get-in % [:data :receiver :id])))))
              (map (fn [sub]
                     (let [d (:data sub)]
                       {:subscription (str (:id sub))
                        :state (str (some-> (:state sub) name))
                        :kinds (mapv name (:kinds d))
                        :actions (mapv name (:actions d))
                        :cursor (when cursor' (cursor' (str "door:" (:id sub))))
                        :skipped_count (:skipped_count d)
                        :last_skipped_at (some-> (:last_skipped_at d) str)
                        :last_skip_error (some-> (:last_skip_error d) str)}))))
        [:active :paused]))

(defn- fired-by-field
  "The seat's :computed `fired_by`: `fired-by` over the reads the render
  ctx lends."
  [row ctx]
  (when-some [find' (:find ctx)]
    (fired-by find' (:cursor ctx) row)))

(defn- lapsing-field
  "The seat's :computed `lapsing`: the scope entries whose `until` falls
  within seven days, soonest first, so a mayor asks to extend one
  before it goes (ticket b177a14b)."
  [row ctx]
  (mapv (fn [e]
          {:kind (str (:kind e))
           :actions (mapv str (:actions e))
           :until (str (:until e))
           :because (str (:because e))})
        (grants/lapsing-entries (or (some-> (:now ctx) ->instant)
                                    (java.time.Instant/now))
                                (* 7 86400)
                                (get-in row [:data :scope]))))

;; The seat and sitting pages' words live in
;; `waymark10.server.seats.page` (ticket f190fba5, round five).

(defn- spend-by-domain-field
  "The seat's :computed `spend_by_domain_7d`: `spend-by-domain` over the
  read and the find the render ctx lends."
  [row ctx]
  (when-some [find' (:find ctx)]
    (spend-by-domain (:read ctx) find' row
                     (or (:now ctx) (java.time.Instant/now)))))

;; ── a domain's budget and its wall: seats/ledger.clj ────────────────
;; One line each for the vars this file's resource and other namespaces
;; call; the defns live in `waymark10.server.seats.ledger`.

(def dollars ledger/dollars)
(def money-text ledger/money-text)
(def domain-row-of ledger/domain-row-of)
(def domain-under-budget ledger/domain-under-budget)
(def domain-allocated ledger/domain-allocated)
(def budget-fits-the-domain ledger/budget-fits-the-domain)

(def set-seat-budget ledger/set-seat-budget)

;; the mayor's no-tap budget restate (`mayor-moves-only-the-budget?`)
;; registers with delegation from seats/ledger.clj

;; ── the crack a staffing change opens: seats/staffing.clj ──────────
;; The audit, the walk's drops and the restate's replay live in
;; `waymark10.server.seats.staffing` (ticket 9b8c96ae, round six).
;; `opens-no-crack` and the two dropped-wake guards stay here, by the
;; names factory10's cracks_gate_test and webhooks read them.

(def ^:private would-submit staffing/would-submit) ; delegation_test
(def ^:private would-refuse staffing/would-refuse) ; delegation_test

(g/defguard opens-no-crack
  {:severity :warning
   :reads [:principal :now :grant :seat :held_call :within :services]
   :vars [:count :cracks]
   :open "The cracks are the audit's, read at the write from the law and the active seats; GET /api/-/cracks is the same reading. Acknowledge the warning by its name to accept them."
   :explain "This change to the seats would open cracks ({count}), where work stops with nobody told. {cracks} Change the seats so that the state is still left and woken, or acknowledge this warning to accept the crack on purpose."}
  [row inp ctx]
  ;; the create and restate doors': a person's own write, and the write
  ;; of a tool the person is signed in to. An authoring seat's restate is
  ;; held by the guard below, and the replay of that held call is the
  ;; person's yes.
  (if (or (delegation/author-seat ctx)
          (delegation/approved-hold? ctx :seat (:id row)))
    (t/allow)
    (if-some [cracks (cracks-opened row inp ctx)]
      (t/deny {:vars {:count (count cracks) :cracks (cracks-told cracks)}})
      (t/allow))))

(g/defguard a-dropped-wake-opens-no-crack
  {:severity :warning
   :reads [:principal :now :grant :seat :held_call :within :services]
   :vars [:count :cracks]
   :open "The cracks are the audit's, read at the write from the law, the active seats and their door subscriptions; GET /api/-/cracks is the same reading. Acknowledge the warning by its name to accept them."
   :explain "This change to what wakes a seat would open cracks ({count}), where work stops with nobody told. {cracks} Leave a seat woken for the state, or acknowledge this warning to accept the crack on purpose."}
  [row inp ctx]
  ;; a person's pause, revoke or restate. An authoring seat's is held by
  ;; the guard below, and the seat's own managed-row writes were judged
  ;; at its door.
  (if (or (the-seat-writes-it? ctx)
          (delegation/author-seat ctx)
          (delegation/approved-hold? ctx :subscription (:id row)))
    (t/allow)
    (if-some [cracks (wake-drop-opened row inp ctx)]
      (t/deny {:vars {:count (count cracks) :cracks (cracks-told cracks)}})
      (t/allow))))

(g/defguard a-dropped-wake-waits-on-the-person
  {:hold true
   :reads [:principal :now :grant :seat :held_call :within :services]
   :vars [:count :cracks]
   :open "No door clears this one. The call waits as a held_call for the person's tap, and an author that could open a crack alone would leave work with no seat and nobody told."
   :explain "Held for the person's tap. This change to what wakes a seat would open cracks ({count}), where work stops with nobody told. {cracks} The call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row inp ctx]
  (cond
    (the-seat-writes-it? ctx) (t/allow)
    (nil? (delegation/author-seat ctx)) (t/allow)
    (delegation/approved-hold? ctx :subscription (:id row)) (t/allow)
    :else
    (if-some [cracks (wake-drop-opened row inp ctx)]
      (t/deny {:vars {:count (count cracks) :cracks (cracks-told cracks)}})
      (t/allow))))

(defn- mayor-card
  "The mayor seat's setup card (docs/spec-first-run.md R-5.2): until it
  has sat once, the one line that fetches its place (R-5.6), carrying
  the solo identity headers."
  [row {:keys [owner-principal sat]}]
  (let [id (str (:id row))]
    (when (and (= "mayor" (some-> (get-in row [:data :name]) str))
               (not (contains? sat id)))
      (str "Set up your mayor: run this one line in a new folder, then open Claude Code there: "
           "curl -fsS -H 'X-Waymark-Principal: " owner-principal "'"
           " -H 'X-Waymark-Actor-Type: human' "
           place-origin "/api/seats/" id "/-/place | tar x"))))

(defresource seat
  {:kind :seat
   :plural "seats"
   :purpose words/seat
   ;; the mayor seat waits on the solo owner until it has sat (R-5.2)
   :owner-card mayor-card
   ;; the per-state copies, folded into one `:to :same` door each
   ;; (waymark ticket 620cf4dc); the old names answer for one release
   :aliases {:lapse_parked :lapse
             :follow_successor_parked :follow_successor}
   ;; wake_on is retired (docs/spec-events.md step 9): what wakes a seat
   ;; is its fire door's subscriptions, which every live seat already
   ;; carries, so `plan` strips the stored values in one reviewed UPDATE
   :renames {:fields {:wake_on nil}}
   :states [:active :parked :merged :retired]
   :initial :active
   :terminal #{:merged :retired}
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   ;; spend (docs/spec-spend.md §2): summed from its postings when read,
   ;; never stored, so a report's posting adds no version to the seat
   :accounts {:spend {:unit [:money "USD"] :on-read true}}
   :computed {:held_reason
              {:schema :string
               :x-display
               {:label "Waiting for its localfire"
                :help "While every live link this seat fires through is a pull link whose localfire is away, the sentence naming it and when it was last seen, worked out at read time. A wait, not a failure: the fire goes out once the localfire asks for it, and the seat's health does not count it."}
               ;; it reads the schedule and the links: with no :read it
               ;; is absent, never a false wait
               :reads? true
               :fn held-reason-field}
              :wake_failed fire/wake-failed-computed
              :fire_pool pools/fire-pool-computed
              :fired_by
              {:schema [:vector
                        [:map
                         [:subscription [:string {:max 64}]]
                         [:state [:string {:max 32}]]
                         [:kinds [:vector [:string {:max 64}]]]
                         [:actions [:vector [:string {:max 64}]]]
                         [:cursor [:maybe :int]]
                         [:skipped_count [:maybe :int]]
                         [:last_skipped_at [:maybe [:string {:max 40}]]]
                         [:last_skip_error [:maybe [:string {:max 200}]]]]]
               :x-display
               {:label "Fired by"
                :help "The subscriptions on this seat's fire door, worked out at read time: what each hears, where its cursor stands in the log, and its last skip with the reason, so who woke this seat, and why not, has an answer here."}
               ;; reads subscriptions: absent with no read lent
               :reads? true
               :fn fired-by-field}
              :in_domain
              {:schema :string
               :x-display
               {:label "In domain"
                :help "The name of the domain this seat is in, worked out at read time: the domain it names, or factory when it names none."}
               ;; it reads the domain row: with no :read it is absent,
               ;; never a false name
               :reads? true
               :fn in-domain-field}
              :spend_by_domain_7d
              {:schema [:map-of :keyword [:decimal {:min 0 :max 100000000}]]
               :x-display
               {:label "Spent in seven days, by domain, in dollars"
                :help "What this seat's sittings of the last seven days cost, by the domain each was worked for, worked out at read time. A sitting is charged to the domain that asked for the row it walked, and to the row's own domain when no other domain asked."}
               ;; it reads the sittings and the rows they walked: with
               ;; no read lent it is absent, never an empty map
               :reads? true
               :fn spend-by-domain-field}
              :lapsing
              {:schema [:vector
                        [:map
                         [:kind [:string {:max 64}]]
                         [:actions [:vector [:string {:max 64}]]]
                         [:until [:string {:max 40}]]
                         [:because [:string {:max 240}]]]]
               :x-display
               {:label "Ends within seven days"
                :help "The scope entries whose until falls within seven days, soonest first, worked out at read time. Restate the seat with a later until to keep one."}
               :fn lapsing-field}
              :reads_as
              {:schema [:map
                        [:charter {:optional true} :string]
                        [:wakes {:optional true} :string]
                        [:may {:optional true} :string]
                        [:works [:vector
                                 [:map
                                  [:kind :string]
                                  [:href :string]
                                  [:waiting :int]
                                  [:label :string]]]]
                        [:sitting {:optional true}
                         [:map [:href :string] [:label :string]]]]
               :x-display
               {:label "In words"
                :help "What this seat is for, what wakes it, what it may do and the queues it works, worked out at read time in the words of the org's law page. Each queue counts the rows waiting in a state this seat can move."}
               ;; it counts the queues: with no :read it is absent,
               ;; never a false zero
               :reads? true
               :fn reads-as-field}}
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
    ;; THE ROUTINE'S OWN WORDS (R-1 of waymark-fp62.7.23). One Routine
    ;; stands for one MODEL, and its prompt says one thing: read the
    ;; fire text and do what it says. This is what the engine composes
    ;; that text FROM, which is the whole point of the field being
    ;; here — instructions
    ;; pasted into a Routine by hand are instructions a seat can go
    ;; stale on with nobody the wiser, and these a person restates
    ;; like anything else about the office. R-12.10 still holds: the
    ;; pointer and the walk rule, and nothing else. No guard fences
    ;; the length past the schema's own 2000 — the cap is the priming
    ;; budget, and the refusal a schema writes already says so.
    [:instructions {:optional true
                    :examples [instructions-example]
                    :x-display
                    {:widget "prose"
                     :label "The instructions"
                     :help "What the Routine's session reads at the top of every firing: how to sit in this seat, and how to walk its queue. Not the judgment — that is the charter, and the session reads it off the row when it sits. Leave this empty and the seat fires the way it always did, on a Routine of its own."}}
     [:maybe [:string {:max 2000}]]]
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
    [:held_for {:default []
                :kind :model
                :ref-also-prefix grants/connector-token-prefix
                :x-display
                {:label "Models that may sit"
                 :help "The models allowed to hold this seat as its full sitter — the seat's place on the ladder. An empty list means any model may sit; name one and a session declaring another sees nothing."}}
     [:vector :waymark/ref]]
    [:substitute_for {:default []
                      :kind :model
                      :ref-also-prefix grants/connector-token-prefix
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
    keep-transcripts-field
    transcript-days-field
    inbox-field
    feed-url-field
    [:budget_usd_per_week {:examples [5M]
                           :x-display
                           {:label "Fuel for seven days, in dollars"
                            :help "What this seat may spend in a rolling week, summed over its closed sittings. Reaching it is a hard wall: the grant scopes to nothing until the window rolls, and the seat says so."}}
     [:decimal {:min 0 :max 100000}]]
    [:sitting_budget_tokens {:optional true
                             :examples [60000]
                             :x-display
                             {:label "One sitting's token ceiling"
                              :help "Passed to the harness as the cap on a single wake. Twenty thousand is the floor the engine accepts — below it a sitting cannot read its queue and write anything back. None is no ceiling: no sitting of the seat meets the token wall (R-22.4)."}}
     [:maybe [:int {:min 20000 :max 10000000}]]]
    [:walk {:optional true
            :not-a-ref "It holds the name of the kind this seat walks, never a row id."
            :x-options {:from :kinds}
            :x-display
            {:label "The queue it walks"
             :help "One kind this seat works through, a row at a time, in the order that kind's own default sort gives. The kind must be in the scope above, and the collection a firing opens IS the work waiting for it: give that kind's scope entry a filter, or walk a kind that filters its own queue, or name a judgment below. When the entry filters by state, that same entry must name a door that moves a row out of that state — no door this seat may take, no queue it can empty. Leave it empty for a seat that walks nothing."}}
     [:maybe [:string {:min 1 :max 64}]]]
    ;; R-P.6.1 (ticket f4598304): the breaches of the promises the seat
    ;; keeps join its walk, and `walk` keeps its meaning
    [:walk_promises {:optional true
                     :x-display
                     {:label "It walks the promises it keeps"
                      :help "On, a firing is also handed the open breaches of every enforced promise this seat owes or holds a remedy door of, of any kind, newest break first. Set by restate."}}
     [:maybe :boolean]]
    ;; THE JUDGMENT THIS SEAT SAYS (R-4 of waymark-fp62.11). The queue
    ;; is a query and nothing is copied: the walk is the judgment's
    ;; `subject_kind` under its `queue`, MINUS the subjects that
    ;; already carry a verdict under it. What the sitting produces is
    ;; the verdict row; whether the subject moves at all is the
    ;; judgment's `consequence`, and this seat never sees that door.
    [:judgment {:optional true
                :kind :judgment
                :x-display
                {:label "The judgment it says"
                 :help "A promoted judgment this seat walks the subjects of, one at a time, saying one of its verdicts on each. The walk must be the judgment's own subject kind, and the scope must open kind verdict with the action judge. Leave it empty for a seat that works a queue rather than judging one."}}
     [:maybe :waymark/ref]]
    [:rows_per_firing {:default 20
                       :x-display
                       {:label "Rows per firing"
                        :help "The most rows one wake moves to a leaf. The walk's cap, and the lever you pull before you pull the model: fewer rows is a shorter sitting at the same judgment."}}
     [:int {:min 1 :max 200}]]
    ;; WHOM THIS SEAT WORKS FOR (epic aff24e84, piece 5). NO DEFAULT IS
    ;; WRITTEN: a seat with nothing here serves its own domain.
    [:serves {:optional true
              :x-display
              {:label "Whom it serves"
               :choices serves-choices
               :help "own: this seat works for its own domain. any: it is a service, and the mayor of a domain that asked for a ticket in this seat's repository grooms and ranks that ticket. A person sets any: when a seat that opens seats for its person asks for any, the call waits for that person's tap. Leave it empty for own."}}
     [:maybe [:enum "own" "any"]]]
    ;; THE DOMAIN THIS SEAT IS IN (epic aff24e84, piece 1). NO DEFAULT
    ;; IS WRITTEN: a seat with nothing here reads as being in
    ;; `factory`, worked out at read time by `domain-name-of`.
    [:domain {:optional true
              :kind :domain
              :x-display
              {:label "Its domain"
               :help "The domain this seat works in. Leave it empty and the seat counts as being in factory."}}
     [:maybe :waymark/ref]]
    [:fire_interval_seconds {:default 300
                             :examples [300]
                             :x-display
                             {:label "Quietest gap between wakes, in seconds"
                              :help "The least time between two wakes the seat's own events start. A match inside the gap does not fire; it waits, and the first wake after the gap lifts walks the queue. Raise it for a busy queue: every wake costs one sitting's fuel."}}
     [:int {:min 1 :max 86400}]]
    ;; HOW MANY RUNS AT ONCE. One is the seat as it always was: an open
    ;; sitting holds every wake. Above one, a wake fires another run
    ;; while fewer sittings are open and a walk row is left that no
    ;; open sitting holds (`wakes/wake-seat!`), and each sit claims its
    ;; own row (`claim-rows-atomically!`).
    [:max_open_sittings {:default 1
                         :examples [1]
                         :x-display
                         {:label "Sittings at once"
                          :help max-open-sittings-help}}
     [:int {:min 1 :max 10}]]
    ;; HOW LONG A RELEASED ROW RESTS (ticket f6c8d5ce). A close can land
    ;; while the run that sat is still mid-call, so the rows it walked
    ;; are handed to no other sitting of the seat until this lifts
    ;; (`graced-rows`).
    [:release_grace_seconds {:default 120
                             :examples [120]
                             :x-display
                             {:label "Grace before a closed sitting's rows are handed on, in seconds"
                              :help "After a sitting closes, the rows it walked wait this long before another sitting of the seat is handed them, so a run whose close came early is not overtaken mid-call. Zero hands them on at once."}}
     [:int {:min 0 :max 3600}]]
    ;; SEAT HEALTH 2 (ticket 64a835b4). `health` is the rollup of the
    ;; seat's last `(:window health-thresholds)` closed sittings, written
    ;; again each time one of them closes (`roll-health!`). WRITTEN BY
    ;; NO DOOR, `fire_keys`' own way: neither the create door nor the
    ;; restate declares `health`, so a body that carries it is refused
    ;; as an unknown key.
    [:health {:optional true
              :x-display
              {:label "Health of its last sittings"
               :spelled-by-hand "Written by the engine each time a sitting of this seat closes: what its last sittings came to, what they cost and which flags they carried. Never typed."}}
     [:maybe [:map
              [:sittings {:x-display {:label "Sittings in the window"}} :int]
              [:outcomes {:x-display {:label "Sittings by outcome"}}
               [:map-of :keyword :int]]
              [:submit_rate {:optional true
                             :x-display {:label "Share that submitted"}}
               [:maybe [:decimal {:min 0}]]]
              [:cost_usd {:x-display {:label "What they cost, in dollars"}}
               [:decimal {:min 0}]]
              [:cost_per_submit {:optional true
                                 :x-display {:label "Dollars for each submit"}}
               [:maybe [:decimal {:min 0}]]]
              [:flags {:x-display {:label "Sittings by flag"}}
               [:map-of :keyword :int]]
              [:merged_prs {:x-display {:label "Changes merged since the window opened"}}
               :int]
              [:cost_per_merge {:optional true
                                :x-display {:label "Dollars for each merge"}}
               [:maybe [:decimal {:min 0}]]]
              [:last_submit_at {:optional true
                                :x-display {:label "Last submit"}}
               [:maybe :waymark/instant]]
              [:last_outcome {:optional true
                              :x-display {:label "What the newest came to"}}
               [:maybe [:string {:max 40}]]]
              [:computed_at {:x-display {:label "Counted at"}}
               :waymark/instant]
              ;; SEAT HEALTH 3: the alert rule the window breaks now,
              ;; written with the rollup and gone when the rule passes
              [:breach {:optional true
                        :x-display {:label "The alert it is breaking"}}
               [:maybe [:map
                        [:rule {:x-display {:label "Which rule"}}
                         [:string {:max 80}]]
                        [:at {:x-display {:label "Since"}} :waymark/instant]
                        [:ticket {:optional true
                                  :not-a-ref "It holds the id of the ticket the breach filed, in a kind this engine may not serve."
                                  :x-display {:label "The ticket filed for it"}}
                         [:maybe [:string {:max 128}]]]]]]]]]
    [:delegates {:optional true
                 :x-display
                 {:label "What it may author"
                  :help delegates-field-help}}
     [:maybe delegates-schema]]
    [:acts_as_cast {:optional true
                    :x-display
                    {:label "Acts as a seed's cast (clones only)"
                     :help acts-as-cast-help}}
     [:maybe :boolean]]
    [:delivers {:optional true
                :x-display
                {:label "What counts as its delivery"
                 :help delivers-help}}
     [:maybe delivers-schema]]
    ;; ── engine-written from here down (absent from the create door
    ;;    and from restate; see the ns docstring's write fence) ───────
    ;; WHO AUTHORED THIS SEAT, AND FOR WHOM (server/delegation,
    ;; invariant 3). Stamped by `seat-born` when a delegating seat's
    ;; sitter opens it — `owner` too when a person or their delegate
    ;; opens it — and absent from both input doors, so a closed map
    ;; refuses a hand that tries to write it.
    [:authored_by {:optional true
                   :kind :seat
                   :x-display
                   {:label "Authored by"
                    :spelled-by-hand "Written at birth when a delegating seat opens this one; never typed."}}
     [:maybe :waymark/ref]]
    [:owner {:optional true
             :x-ref {:principal true}
             :x-display
             {:raw true
              :label "For whom"
              :spelled-by-hand "The person who opened this seat, or the one its authoring seat acts for, written at birth; never typed."}}
     [:maybe [:string {:max 128}]]]
    ;; INVARIANT 4's record: the person's first unpark of an authored
    ;; seat, written by `unpark` and by nothing else
    [:approved_by {:optional true
                   :x-ref {:principal true}
                   :x-display
                   {:raw true
                    :label "Approved by"
                    :spelled-by-hand "Written by the first unpark of an authored seat; never typed."}}
     [:maybe [:string {:max 128}]]]
    [:approved_at {:optional true
                   :x-display {:label "Approved at"}}
     [:maybe :waymark/instant]]
    ;; SEAT HEALTH 3: the breaker a breach opened (`roll-health!`). The
    ;; wake path reads it (`wakes/max-open-of`) and `close_breaker`
    ;; drops it; `max_open_sittings` is never lowered.
    [:breaker_open {:optional true
                    :x-display
                    {:label "Breaker open"
                     :spelled-by-hand "Written by the engine when a health breach trips the breaker: the seat wakes one sitting at a time while it is set. close_breaker drops it; never typed."}}
     [:maybe :boolean]]
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
    ;; THE HASH ALONE (R-22.6): the row keeps the key's SHA-256,
    ;; base64url, as it keeps a firing's; the key itself is never kept.
    [:sitter_key_hash {:optional true :secret true
                       :x-display
                       {:hidden true
                        :label "Sitter key"
                        :spelled-by-hand "Written by offer_key as the key's hash and cleared by revoke_key; the key itself is never kept, never typed into a form, and never rendered back."}}
     [:maybe [:string {:min 43 :max 43}]]]
    ;; ── THE KEY OF ONE FIRING (R-12.37) ─────────────────────────────
    ;; The key above is standing, and a person pastes it into a
    ;; Routine by hand. A Routine made without that step fires a
    ;; session that has the seat's name and nothing to sit with, which
    ;; is what the meal planner's first firing showed. These keys are
    ;; the other half: the engine mints 128 bits for EACH fire of a
    ;; seat that has instructions, the fire text carries the key on one
    ;; line, and the row keeps the hash alone. One key opens one sit.
    ;;
    ;; WRITTEN BY NO DOOR, where `sitter_key` has two. A transition per
    ;; fire and per sit would double the log of a run and put a
    ;; credential's record in it twice, so the list is a maintenance
    ;; write (`hold-fire-key!` and `spend-fire-key!`, the sitting
    ;; counters' own spelling). The fence at the two doors a hand can
    ;; reach is OMISSION, the schedule's own way with its link:
    ;; neither the create door nor the restate declares the field, so
    ;; a body that carries it is refused as an unknown key. A guard
    ;; here would have had to say what the field wants, and a list of
    ;; hashes wants nothing from anybody. :secret, the `sitter_key`
    ;; posture: the list leaves the engine in no projection, scoped
    ;; or not.
    [:fire_keys {:optional true :secret true
                 :x-display
                 {:hidden true
                  :label "Keys of the firings"
                  :spelled-by-hand "Written by the fire and spent by the sit. The engine mints each key, keeps the hash alone, and never shows a key again."}}
     [:maybe fire/fire-keys-schema]]
    fire/unsat-fires-field fire/sit-refusal-field
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
   :no-own-surface "The sitter reads its seat through the grant that cites it (R-4.9); the owner is named on the row and does not hold it alone."
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
    [:instructions {:optional true
                    :examples [instructions-example]
                    :x-display
                    {:widget "prose"
                     :label "The instructions"
                     :help "What the Routine's session reads at the top of every firing: how to sit in this seat, and how to walk its queue. Not the judgment — the session reads that off the row when it sits. Leave it empty and the seat fires the way a seat with a Routine of its own always did."}}
     [:maybe [:string {:max 2000}]]]
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
    [:held_for {:default []
                :kind :model
                :ref-also-prefix grants/connector-token-prefix
                :x-display
                {:label "Models that may sit"
                 :help "The models allowed to hold this seat as its full sitter. An empty list means any model may sit."}}
     [:vector :waymark/ref]]
    [:substitute_for {:default []
                      :kind :model
                      :ref-also-prefix grants/connector-token-prefix
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
    keep-transcripts-field
    transcript-days-field
    inbox-field
    feed-url-field
    [:budget_usd_per_week {:examples [5M]
                           :x-display
                           {:label "Fuel for seven days, in dollars"
                            :help "What this seat may spend in a rolling week. Reaching it is a hard wall until the window rolls."}}
     [:decimal {:min 0 :max 100000}]]
    [:sitting_budget_tokens {:optional true
                             :examples [60000]
                             :x-display
                             {:label "One sitting's token ceiling"
                              :help "Passed to the harness as the cap on a single wake; twenty thousand is the floor, and none is no ceiling."}}
     [:maybe [:int {:min 20000 :max 10000000}]]]
    [:walk {:optional true
            :not-a-ref "It holds the name of the kind this seat walks, never a row id."
            :x-options {:from :kinds}
            :x-display
            {:label "The queue it walks"
             :help "One kind this seat works through, a row at a time. It must be in the scope above, and its scope entry must carry a filter — or the kind must filter its own queue, or the seat must name a judgment below. When the entry filters by state, that same entry must name a door that moves a row out of that state."}}
     [:maybe [:string {:min 1 :max 64}]]]
    [:judgment {:optional true
                :kind :judgment
                :x-display
                {:label "The judgment it says"
                 :help "A promoted judgment this seat walks the subjects of, saying one of its verdicts on each. The walk above must be that judgment's subject kind, and the scope must open kind verdict with the action judge."}}
     [:maybe :waymark/ref]]
    [:rows_per_firing {:default 20
                       :x-display
                       {:label "Rows per firing"
                        :help "The most rows one wake moves to a leaf — the lever you pull before you pull the model."}}
     [:int {:min 1 :max 200}]]
    [:domain {:optional true
              :kind :domain
              :x-display
              {:label "Its domain"
               :help "The domain this seat works in. Leave it empty and the seat counts as being in factory."}}
     [:maybe :waymark/ref]]
    [:serves {:optional true
              :x-display
              {:label "Whom it serves"
               :choices serves-choices
               :help "own: this seat works for its own domain. any: it is a service, and the mayor of a domain that asked for a ticket in this seat's repository grooms and ranks that ticket. A person sets any: when a seat that opens seats for its person asks for any, the call waits for that person's tap. Leave it empty for own."}}
     [:maybe [:enum "own" "any"]]]
    [:fire_interval_seconds {:default 300
                             :examples [300]
                             :x-display
                             {:label "Quietest gap between wakes, in seconds"
                              :help "The least time between two wakes the seat's own events start. A match inside the gap waits for it to lift. Five minutes is the default."}}
     [:int {:min 1 :max 86400}]]
    [:max_open_sittings {:default 1
                         :examples [1]
                         :x-display
                         {:label "Sittings at once"
                          :help max-open-sittings-help}}
     [:int {:min 1 :max 10}]]
    [:release_grace_seconds {:default 120
                             :examples [120]
                             :x-display
                             {:label "Grace before a closed sitting's rows are handed on, in seconds"
                              :help "After a sitting closes, the rows it walked wait this long before another sitting of the seat is handed them. Two minutes is the default."}}
     [:int {:min 0 :max 3600}]]
    [:delivers {:optional true
                :x-display
                {:label "What counts as its delivery"
                 :help delivers-help}}
     [:maybe delivers-schema]]
    [:delegates {:optional true
                 :x-display
                 {:label "What it may author"
                  :help delegates-field-help}}
     [:maybe delegates-schema]]
    [:acts_as_cast {:optional true
                    :x-display
                    {:label "Acts as a seed's cast (clones only)"
                     :help acts-as-cast-help}}
     [:maybe :boolean]]
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
     [:maybe [:string {:min 22 :max 128}]]]
    [:sitter_key_hash {:optional true :secret true
                       :x-display
                       {:hidden true
                        :label "Sitter key hash"
                        :spelled-by-hand "Refused here: the key is offer_key's to write."}}
     [:maybe [:string {:max 128}]]]]
   ;; `fire_keys` is NOT declared here (R-12.37): the create door is
   ;; a closed map, and a body that carries the field is refused as
   ;; an unknown key. That omission is the fence.
   ;; a raw standing key from before the hash is hashed at boot (R-22.6),
   ;; and an old row leaves the fields seat S5 dropped behind
   :data-migrate migrate-seat-data
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :domain #{:eq}}
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
                   grants/scope-ends-are-stated
                   scope-inside-the-owners
                   ttl-within-standing
                   held-for-active-models
                   walk-names-a-kind-in-scope
                   walk-leaves-its-filter
                   walk-not-hidden-by-its-default
                   walk-judgment-not-superseded
                   walk-matches-the-judgment
                   inbox-names-real-kinds
                   inbox-names-real-actions
                   inbox-cues-filter-declared-fields
                   inbox-cues-filter-is-filterable
                   cast-only-on-a-clone
                   budget-fits-the-domain
                   ;; LAST, so a hold is a call every other wall passed
                   delegation/authors-within-the-ceiling
                   opens-no-crack]
   :on-create seat-born
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
             [:instructions {:optional true
                             :examples [instructions-example]
                             :x-display
                             {:widget "prose"
                              :label "The instructions"
                              :help "The words every firing of this seat reads first, stated again. They reach the session through the fire text the engine composes, so a change here is live at the next wake — nothing is pasted anywhere and no Routine is touched."}}
              [:maybe [:string {:max 2000}]]]
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
             [:held_for {:default []
                         :kind :model
                         :ref-also-prefix grants/connector-token-prefix
                         :x-display
                         {:label "Models that may sit"
                          :help "The models allowed to hold this seat, stated again in full. Changing this is a STEP on the ladder and demands the note below."}}
              [:vector :waymark/ref]]
             [:substitute_for {:default []
                               :kind :model
                               :ref-also-prefix grants/connector-token-prefix
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
             keep-transcripts-field
             transcript-days-field
             inbox-field
             feed-url-field
             [:budget_usd_per_week {:examples [5M]
                                    :x-display
                                    {:label "Fuel for seven days, in dollars"
                                     :help "What this seat may spend in a rolling week."}}
              [:decimal {:min 0 :max 100000}]]
             [:sitting_budget_tokens {:optional true
                                      :examples [60000]
                                      :x-display
                                      {:label "One sitting's token ceiling"
                                       :help "The cap on a single wake, passed to the harness. Null clears it: the seat's sittings meet no token wall."}}
              [:maybe [:int {:min 20000 :max 10000000}]]]
             [:walk {:optional true
                     :not-a-ref "It holds the name of the kind this seat walks, never a row id."
                     :x-options {:from :kinds}
                     :x-display
                     {:label "The queue it walks"
                      :help "One kind this seat works through, in the scope above, filtering its own queue by state — or the subject kind of the judgment below."}}
              [:maybe [:string {:min 1 :max 64}]]]
             [:walk_promises {:optional true
                              :x-display
                              {:label "It walks the promises it keeps"
                               :help "On, a firing is also handed the open breaches of every enforced promise this seat owes or holds a remedy door of, newest break first. Left out, it stays as it was."}}
              [:maybe :boolean]]
             [:judgment {:optional true
                         :kind :judgment
                         :x-display
                         {:label "The judgment it says"
                          :help "The judgment this seat says, stated again. Clear it and the seat goes back to working its queue rather than judging it; the verdicts already said stand, because a verdict is a row and not a mark on the subject."}}
              [:maybe :waymark/ref]]
             [:rows_per_firing {:default 20
                                :x-display
                                {:label "Rows per firing"
                                 :help "The most rows one wake moves to a leaf."}}
              [:int {:min 1 :max 200}]]
             [:domain {:optional true
                       :kind :domain
                       :x-display
                       {:label "Its domain"
                        :help "The domain this seat works in. Leave it out and the seat stays in the domain it is in; clear it and the seat counts as being in factory."}}
              [:maybe :waymark/ref]]
             [:serves {:optional true
                       :x-display
                       {:label "Whom it serves"
                        :choices serves-choices
                        :help "own or any, stated again. Leave it out and the seat serves whom it serves today; clear it and the seat serves its own domain. A person sets any: from a seat that opens seats for its person, a change to any waits for that person's tap."}}
              [:maybe [:enum "own" "any"]]]
             [:fire_interval_seconds {:default 300
                                      :examples [300]
                                      :x-display
                                      {:label "Quietest gap between wakes, in seconds"
                                       :help "The least time between two wakes the seat's own events start. Raise it when a busy queue is waking this seat more often than the work deserves."}}
              [:int {:min 1 :max 86400}]]
             [:max_open_sittings {:default 1
                                  :examples [1]
                                  :x-display
                                  {:label "Sittings at once"
                                   :help max-open-sittings-help}}
              [:int {:min 1 :max 10}]]
             [:release_grace_seconds {:default 120
                                      :examples [120]
                                      :x-display
                                      {:label "Grace before a closed sitting's rows are handed on, in seconds"
                                       :help "After a sitting closes, the rows it walked wait this long before another sitting of the seat is handed them. Raise it when a run's close can come before its last call does."}}
              [:int {:min 0 :max 3600}]]
             [:delivers {:optional true
                         :x-display
                         {:label "What counts as its delivery"
                          :help delivers-help}}
              [:maybe delivers-schema]]
             [:delegates {:optional true
                          :x-display
                          {:label "What it may author"
                           :help delegates-field-help}}
              [:maybe delegates-schema]]
             [:acts_as_cast {:optional true
                             :x-display
                             {:label "Acts as a seed's cast (clones only)"
                              :help acts-as-cast-help}}
              [:maybe :boolean]]
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
              [:maybe [:string {:min 22 :max 128}]]]
             [:sitter_key_hash {:optional true :secret true
                                :x-display
                                {:hidden true
                                 :label "Sitter key hash"
                                 :spelled-by-hand "Refused here: the key is offer_key's to write."}}
              [:maybe [:string {:max 128}]]]]
     ;; `fire_keys` is not declared here either (R-12.37): the
     ;; closed map refuses it, and `restate-seat` writes only the
     ;; restatable fields, so the keys a firing holds survive a
     ;; restate the way `sitter_key` does
     :record true
     ;; sitter_key is NOT prefilled and cannot be: the draft view
     ;; serves prefill from the raw row, and resource/check-secret!
     ;; refuses a :secret field there at the declaration.
     :edit {:prefill [:charter :instructions :mode :scope
                      :held_for
                      :substitute_for :standing_ttl_seconds :cadence_seconds
                      :sitting_idle_seconds
                      :keep_transcripts :transcript_days :inbox :feed_url
                      :budget_usd_per_week :sitting_budget_tokens
                      :walk :walk_promises
                      :judgment :rows_per_firing
                      :fire_interval_seconds :max_open_sittings
                      :release_grace_seconds
                      :delivers :delegates
                      :acts_as_cast :domain :serves]
            :draft {:shared true :live true}}
     :guards [a-person
              not-a-sitter
              key-not-written-by-hand
              grants/scope-names-real-kinds
              grants/scope-names-real-actions
              grants/scope-filters-are-filterable
              grants/scope-omits-private-kinds
              grants/scope-ends-are-stated
              scope-inside-the-owners
              ttl-within-standing
              held-for-active-models
              walk-names-a-kind-in-scope
              walk-leaves-its-filter
              walk-not-hidden-by-its-default
              walk-judgment-not-superseded
              walk-matches-the-judgment
              walk-promises-are-served
              inbox-names-real-kinds
              inbox-names-real-actions
              inbox-cues-filter-declared-fields
              inbox-cues-filter-is-filterable
              step-carries-a-note
              cast-only-on-a-clone
              budget-fits-the-domain
              delegation/authors-within-the-ceiling
              opens-no-crack
              a-crack-waits-on-the-person
              a-restate-says-what-it-drops
              a-restate-adds-no-copy]
     :safety {:idempotent true :reversible true :confirm false}
     :handler restate-seat
     :display {:label "Restate" :style :primary :order 1
               :description "Send \"patch\": true and only what changes — a list such as scope takes {\"add\": [...], \"remove\": [...]}, so a one-entry change reads as that entry. Without patch, state the office again, whole. Every live grant moves with it at the next request. What wakes it is not here: restate the subscriptions on its fire door"}}

    ;; THE CHEAP LEVER (R-4.5): no confirm, reversible, and it costs
    ;; nothing to pull. Grants stay where they are; the seat simply
    ;; serves nothing until somebody unparks it.
    :park
    {:from #{:active} :to :parked
     ;; no delegation guard: a park only takes authority away, and a
     ;; wall that read rows here would move the two scenarios below
     ;; out of the check tier (law_scenarios_test) for nothing
     :guards [a-person]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Park" :order 2
               :description "The seat serves nothing and costs nothing; its grants stay, and unpark is one tap"}}

    :unpark
    {:from #{:parked} :to :active
     :guards [a-person judgment-not-superseded judgment-in-force
              delegation/the-persons-lever]
     :safety {:idempotent true :reversible true :confirm false}
     :handler unpark-seat
     :display {:label "Unpark" :style :primary :order 3
               :description "The seat serves again, on the scope it had"}}

    ;; SEAT HEALTH 3 (ticket 698f6818): the breaker a breach opened is
    ;; closed by hand. No delegation guard, park's own reason: the door
    ;; gives back only what the seat's `max_open_sittings` already
    ;; states, so a person's tool or the authoring seat may pull it.
    :close_breaker
    {:from #{:active} :to :active
     :guards [a-person]
     :safety {:idempotent true :reversible true :confirm false}
     :handler close-breaker
     :display {:label "Close the breaker"
               :description "The seat wakes as many sittings at once as it states again; only a new breach opens the breaker"}}

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
              domain-under-budget linked-for-fire]
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
     :guards [a-person not-a-sitter merge-target-is-active
              delegation/the-persons-lever
              opens-no-crack
              a-crack-waits-on-the-person]
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
     :guards [a-person delegation/the-persons-lever
              opens-no-crack
              a-crack-waits-on-the-person]
     :safety {:idempotent true :reversible false :confirm true
              :consequence "The office closes for good. Its sittings and its whole history stay on record; its grants scope to nothing and expire on their own clocks. Opening the work again is a new seat."}
     :display {:label "Retire" :style :danger :order 9}}

    ;; ── the person's hand-off (invariant 3 and 4) ───────────────────
    ;; A person gives a live seat to a delegating seat to author, and
    ;; takes it back. Both are the person's levers: from an author they
    ;; are held, like unpark, merge and retire.
    :hand_to
    {:from #{:active} :to :active
     :input [:map
             [:author {:kind :seat
                       :x-display
                       {:label "Hand to which seat"
                        :help "A seat that delegates. It restates this seat within its ceiling from now on, with no new tap; this tap is the approval."}}
              :waymark/ref]]
     :record true
     :guards [a-person not-a-sitter author-can-take-it
              delegation/the-persons-lever]
     :safety {:idempotent true :reversible true :confirm false}
     :out-of-state-says parked-says
     :handler hand-seat-to
     :display {:label "Hand to" :order 14
               :description "A delegating seat authors this one within its ceiling; take_back undoes it"}}

    :take_back
    {:from #{:active} :to :active
     :guards [a-person not-a-sitter delegation/the-persons-lever]
     :safety {:idempotent true :reversible true :confirm false}
     :out-of-state-says parked-says
     :handler take-seat-back
     :display {:label "Take back" :order 15
               :description "The author and its approval go; its restates wait on you again"}}

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

    ;; ── a walked row left without its delivery (ticket 00bad58a) ────
    ;; `wakes/rewake-undelivered!` wakes a fired seat once more for a
    ;; row its sitting walked and left without the delivery the seat
    ;; owes. When the seat's sitting before on that row missed too, the
    ;; second miss is recorded here instead and nothing fires. Concealed
    ;; like the three above; the input is the record, and the row is not
    ;; written.
    :undelivered
    {:from #{:active} :to :active
     :input [:map
             [:sitting {:kind :sitting
                        :x-display {:label "The sitting that missed"}}
              :waymark/ref]
             [:row {:x-display {:label "The row it walked"}}
              [:string {:min 1 :max 128}]]
             [:delivery {:x-display {:label "What it owed"}}
              [:string {:min 1 :max 240}]]
             [:last_refusal {:optional true
                             :x-display {:label "Its last refusal"}}
              [:maybe [:string {:max 200}]]]]
     :record true
     ;; :ref-unjudged — the engine's own hand names the sitting that
     ;; missed, read off the sweep, and no person reaches this door
     :waives #{:ref-unjudged}
     :guards [the-engines-own-hand]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Record undelivered" :order 20}}

    ;; ── break glass runs out (ticket b177a14b) ──────────────────────
    ;; The clock sweep restates the scope without the entries whose
    ;; `until` has passed. The input names each one and its `because`,
    ;; and the door records it, so the seat's history says what went
    ;; and why. Concealed like the three above. A parked seat lapses
    ;; too and stays parked (`:to :same`, ticket 50843da5): its row is
    ;; what a person reads before they unpark it, and it must say what
    ;; the seat holds today.
    :lapse
    {:from #{:active :parked} :to :same
     :input [:map
             [:scope {:x-display
                      {:label "The scope that stays"
                       :spelled-by-hand "The seat's scope without the entries that ended, written by the clock sweep."}}
              grants/scope-schema]
             [:lapsed {:x-display
                       {:label "The entries that ended"
                        :spelled-by-hand "The scope entries whose until has passed, written by the clock sweep."}}
              grants/scope-schema]
             [:note {:x-display {:widget "prose"
                                 :label "Why each was added"}}
              [:string {:min 1 :max 2000}]]]
     :record true
     ;; :edit-shape — the scope is worked out from the clock, and there
     ;; is no form to prefill.
     :waives #{:edit-shape}
     :guards [the-engines-own-hand]
     :safety {:idempotent true :reversible false :confirm false}
     :handler drop-lapsed
     :display {:label "Lapse" :order 18}}

    ;; ── the merge's landing (§ 6) ───────────────────────────────────
    :absorb
    {:from #{:active} :to :active
     :input [:map
             [:scope {:x-display
                      {:label "The folded scope"
                       :spelled-by-hand "The two seats' scopes folded to one entry per kind, written by the merge."}}
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
              grants/scope-omits-private-kinds
              grants/scope-ends-are-stated]
     :safety {:idempotent true :reversible false :confirm false}
     :handler absorb-fold
     :display {:label "Absorb" :order 13}}

    ;; ── the domain move's landing (epic aff24e84, piece 2) ──────────
    ;; A domain's `move_budget` writes both seats through this door, in
    ;; its own transaction, so each seat's history says its budget moved.
    :set_budget
    {:from #{:active} :to :active
     :input [:map
             [:budget_usd_per_week {:x-display {:label "Fuel for seven days, in dollars"}}
              [:decimal {:min 0 :max 100000}]]]
     :record true
     ;; :edit-shape — the value is worked out from the move's amount,
     ;; and there is no form to prefill.
     :waives #{:edit-shape}
     :guards [moved-by-a-domain]
     :safety {:idempotent true :reversible false :confirm false}
     :handler set-seat-budget
     :display {:label "Set the budget" :order 29}}

    ;; ── the supersede's landing (ticket 86514746) ───────────────────
    ;; A judgment's `supersede` re-points every seat that says it to the
    ;; successor through these doors, in its own transaction, so the
    ;; seat's history says it moved and why. A parked seat follows too
    ;; and stays parked (`:to :same`).
    :follow_successor
    {:from #{:active :parked} :to :same
     :input follow-successor-input
     :record true
     ;; :ref-unjudged — only the judgment's own supersede reaches this
     ;; door, and it names itself and the successor its own guard judged
     :waives #{:edit-shape :ref-unjudged}
     :guards [followed-by-a-supersede]
     :safety {:idempotent true :reversible false :confirm false}
     :handler follow-successor
     :display {:label "Follow the successor" :order 16}}}
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
    "`fire_keys` is written by NO door (R-12.37), where `sitter_key` has two. The fire mints one key and the sit spends it, and both are maintenance writes (`hold-fire-key!`, `spend-fire-key!`) rather than transitions. Two reasons, and the second is the stronger. A transition for each fire and each sit would double the log of one run, beside the `fire` the log already holds. And a recorded transition persists its raw inputs (R-12.19 keeps the person's prose alone), so a door here would put a credential's own record in the log twice per run. The field is `:secret`, and it is declared on the row ALONE, not at the create door and not at the restate: a closed map refuses an unknown key, which is the schedule's own fence for its link. A guard was tried first and refused at declaration time, for a reason worth keeping: a guard that judges a field must tell the client what the field wants, and one that cannot says so with `:open`, which the usability policy reads as a vocabulary the engine is hiding. A list of hashes is no vocabulary. Nobody may write it, so no door names it."
    "`fire` declares `:idempotent false`, so every call must carry an Idempotency-Key (invoke's phase 2). That is the truthful spelling: a second fire starts a second run. It is also the safe one: an idempotent door is subject to invoke's natural replay, which compares only the row's LATEST transition, so a textless fire following a textless fire with nothing else on the seat would have been answered as a replay and never gone out — the wake's release fire (R-12.22) and a person's second press, both lost. The key costs nobody anything: the MCP door signs every invoke, and the wake consumer keys each fire by the transition it heard, which doubles as its own dedupe. The consumer's replay of the POST is deduped separately, where it happens: `schedules/already-fired?` compares `last_fired_at` against the transition's own instant."
    "`fire` runs from `parked` as well as `active`, and the `not-parked` guard refuses it there. R-12.20 asks for the sentence \"The seat is parked. Unpark it first.\", and a door absent from a parked seat's envelope could only answer 409 with the machine's own words."
    "THE HALT LINE IS A RECORD, AND THREE DOORS LIFT IT (waymark-fp62.7.13). R-7.7 gives the line one writer and one lifter — the router, at a sitter's request. A FIRED seat makes no request of its own, so a line that outlived its wall left the seat stuck until a person ran the Routine by hand. Now: a `restate` that changes the wall's own input drops the line in the same transaction (`stale`'s precedent), `unpark` drops a `seat_not_active` line, and the `fire` door judges the week's fuel again for itself and lets the fire out when the wall no longer holds — the schedules consumer then lifts the line through `clear_halt`, so every lift is one audited door. The router's own path is untouched."
    "`mark_stale`, `mark_halted` and `clear_halt` are declared `active → active` only. Covering `parked` would take only a `:to :same`, but a parked seat is already scoped to nothing by the person's own hand, so neither a stale entry nor a halt on it tells anybody anything they did not choose."]})

;; ── :model ──────────────────────────────────────────────────────────

(defresource model
  {:kind :model
   :plural "models"
   :purpose words/model
   :states [:active :retired]
   :initial :active
   :terminal #{}                       ; retirement is reversible, deliberately
   :nav :system
   ;; a person retires a model and brings it back; no seat does
   :left-by [{:in #{:active} :by :person :via :retire :to :retired}
             {:in #{:retired} :by :person :via :reactivate :to :active}]
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
                            :not-money "a USD rate per million tokens, not an amount; cost-of turns it into money"
                            :x-display
                            {:label "Input, $ per million tokens"
                             :help "What a million input tokens costs. Every price here is read at the moment a sitting closes and written down beside its cost, so a later reprice never moves a closed bill."}}
     price-entry]
    [:price_output_per_mtok {:examples [15M]
                             :not-money "a USD rate per million tokens, not an amount; cost-of turns it into money"
                             :x-display
                             {:label "Output, $ per million tokens"
                              :help "What a million output tokens costs."}}
     price-entry]
    [:price_cache_read_per_mtok {:examples [0.3M]
                                 :not-money "a USD rate per million tokens, not an amount; cost-of turns it into money"
                                 :x-display
                                 {:label "Cache read, $ per million tokens"
                                  :help "What a million cache-read tokens costs — usually the cheapest line, and the reason a charter that fits in the cache is nearly free to carry."}}
     price-entry]
    [:price_cache_write_per_mtok {:examples [3.75M]
                                  :not-money "a USD rate per million tokens, not an amount; cost-of turns it into money"
                                  :x-display
                                  {:label "Cache write, $ per million tokens"
                                   :help "What a million cache-write tokens costs."}}
     price-entry]
    [:notes {:optional true
             :x-display
             {:label "Anything else worth knowing"
              :help "A line for whoever reads the ladder later — a context window, a deprecation date, why this rung exists."}}
     [:maybe [:string {:max 240}]]]
    ;; ── THE CHAIR (waymark-fp62.7.23) ───────────────────────────────
    ;; ONE ROUTINE FOR EACH MODEL, so the credentials that Routine
    ;; needs live on the model. Both fields are the seat's and the
    ;; schedule's own, worn here without a change of meaning: a
    ;; session presenting `sitter_key` is a sitter of any seat this
    ;; model is the chair of (`waymark_sit`, R-4), and a seat with no
    ;; pool of its own fires through the runner links in this model's
    ;; (R-5). A step down the ladder is then one restate of the
    ;; seat's `held_for` — the seat moves to another chair, and
    ;; nobody makes a second Routine.
    ;; THE CHAIR'S KEY IS STANDING TOO (R-22.6), so the row keeps its
    ;; hash alone, as the seat does. `sitter_key` stays declared only
    ;; as the fence `key-not-written-by-hand` judges: this schema is
    ;; the create door, and a create carrying it is refused by name.
    [:sitter_key {:optional true :secret true
                  :x-display
                  {:hidden true
                   :label "Chair key"
                   :spelled-by-hand "Refused here: the key is offer_key's to write."}}
     [:maybe [:string {:min 22 :max 128}]]]
    [:sitter_key_hash {:optional true :secret true
                       :x-display
                       {:hidden true
                        :label "Chair key hash"
                        :spelled-by-hand "Written by offer_key as the key's hash and cleared by revoke_key; the key itself is never kept, never typed into a form, and never rendered back."}}
     [:maybe [:string {:max 128}]]]
    ;; the runner pool (waymark ticket d16b71bf): the order the links
    ;; every seat this model is the chair of fires through are tried
    ;; in, unless its schedule has a pool of its own.
    [:runner_order {:optional true
                    :x-display
                    {:label "Pool order"
                     :help "How a fire picks among the runner links that may fire. Empty is least used."
                     :choices runner-order-choices}}
     [:maybe (into [:enum] runner-orders)]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :tier #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   ;; ONE ROW PER IDENTIFIER, enforced by an index and not by a
   ;; sentence: a second row for one model would split its prices, and
   ;; a sitting costed against the wrong half would be wrong forever.
   :unique [[:name]]
   ;; the write fence of R-2, and the seat's own note about why a
   ;; fence must have something to name: a guard judges a field of the
   ;; door it stands on. This kind declares no create-schema, so its
   ;; row schema IS that door and the field is already there to be
   ;; refused — which is the whole of what the seat had to declare it
   ;; for.
   :create-guards [one-model-spelling
                   key-not-written-by-hand]
   ;; a raw chair key from before the hash is hashed at boot (R-22.6)
   :data-migrate hash-standing-key
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
                        :description "The vendor moved its prices — record the new four; nothing already closed changes"}}
    ;; The Routine behind a row can move to another model; the row
    ;; follows it here and keeps its id, its history and its seats.
    ;; Prices stay with `reprice`. An omitted field keeps its value.
    :restate {:from #{:active} :to :active
              :input [:map
                      [:name {:optional true
                              :examples ["claude-sonnet-5"]
                              :x-display {:raw true
                                          :label "API identifier"
                                          :help "The identifier the Routine's model now answers to, spelled exactly."}}
                       [:string {:min 1 :max 64}]]
                      [:display {:optional true
                                 :examples ["Sonnet 5"]
                                 :x-display {:label "What a person reads"
                                             :help "The short name for a card, a ledger line and a ladder step."}}
                       [:string {:min 1 :max 120}]]
                      [:notes {:optional true
                               :x-display {:label "Anything else worth knowing"
                                           :help "A line for whoever reads the ladder later."}}
                       [:maybe [:string {:max 240}]]]]
              :guards [one-model-spelling]
              :record true
              :edit {:prefill [:name :display :notes]}
              :safety {:idempotent true :reversible true :confirm false}
              :handler restate-model
              :display {:label "Restate" :order 3
                        :description "The Routine now runs another model — rename this row; its seats, sittings and prices stay"}}

    ;; ── the chair's four doors (waymark-fp62.7.23) ──────────────────
    ;; The seat's `offer_key`/`revoke_key` and the schedule's
    ;; `link`/`unlink`, on the row that is the chair. None of the four
    ;; records, and it is the seat's `offer_key` reason verbatim: a
    ;; recorded action persists its RAW inputs into the transition
    ;; log, and two of these inputs ARE credentials (R-12.11). The
    ;; transition row — actor, input digest, summary — is still the
    ;; audit that each was written, by whom, when.
    :offer_key
    {:from #{:active} :to :active
     :input [:map
             [:key {:x-display
                    {:raw true
                     :label "Chair key"
                     :help "The secret this model's Routine hands back to the engine when it sits, minted by YOU — at least 22 characters of real randomness, which is 128 bits a machine made and no hand typed. The engine never generates it and never shows it again. A new offer replaces the old one."}}
              [:string {:min 22 :max 128}]]]
     :guards [a-person-at-the-chair]
     :safety {:idempotent true :reversible true :confirm false}
     :handler set-sitter-key
     :display {:label "Offer key" :order 4
               :description "Hand this model a secret to paste into its Routine — a session presenting it may sit in any seat this model is the chair of"}}

    :revoke_key
    {:from #{:active} :to :active
     :guards [a-person-at-the-chair]
     :safety {:idempotent true :reversible true :confirm false}
     :handler clear-sitter-key
     :display {:label "Revoke key" :order 5
               :description "The key answers for nothing; a session presenting it is told no seat answers, and the seats themselves are untouched"}}

    ;; the pool order (waymark ticket 529deb73): a person's hand only.
    ;; Which links are in the pool is each link's own `pools`, through
    ;; its join_pool and leave_pool; the engine never sets an order.
    :set_runner_order
    {:from #{:active} :to :active
     :input [:map
             [:runner_order {:optional true
                             :x-display
                             {:label "Pool order"
                              :help "How a fire picks among the links that may fire. Empty is least used."
                              :choices runner-order-choices}}
              [:maybe (into [:enum] runner-orders)]]]
     :writes #{:runner_order}
     :record true
     :guards [a-person-at-the-chair]
     :edit {:prefill [:runner_order] :fence false
            :unfenced-reason
            "The order is restated whole; a restate replaces what stands rather than editing it."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The new order replaces the one this model held; another restate puts it back."}
     :handler set-runner-order
     :display {:label "Pool order" :order 8
               :description "How a fire picks among the links in the pool of this model's seats. A runner link joins the pool from its own page, through Add to a pool"}}}
   :deviations
   ["THE CHAIR'S WRITE FENCE IS A GUARD. `sitter_key` is declared on this kind's ONE schema, which is its create door as well — this kind has no create-schema — so a create could carry it. `key-not-written-by-hand` is what refuses it, and its refusal names the door that writes the field instead. The secret stays `{:secret true}`, so the advertised create body drops it, no form asks, and the usability policies skip it; what a caller gains over silent omission is the sentence."
    "R-9.2 calls `name` unique and R-4.7's precedent (roles.clj's `one-spelling`) judges only ACTIVE rows. The two disagree about a retired model, so this kind takes the index's reading: `one_model_spelling` refuses any spelling already on record, active or retired, and its sentence sends the reader to `reactivate`. A second row for one identifier would split its prices, and a closed sitting costed against the wrong half would be wrong forever."]})

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

(defn- seat-at-birth
  "The seat this sitting is opening in, read at birth, or nil when this
  ctx cannot read: the probe ctx carries no hooks."
  [inp ctx]
  (when-some [read' (:read ctx)]
    (some->> (:seat inp) str not-empty (read' :seat))))

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

(defn- seat-domain-of
  "The name of the domain the seat this sitting is opening in stores,
  read at birth, or nil: the seat stores none, or this ctx cannot read.
  Nothing is stamped then, and the kind's :absent-as filters the row as
  `default-domain`."
  [inp ctx]
  (when-some [read' (:read ctx)]
    (when-some [id (some-> (read' :seat (str (:seat inp)))
                           (get-in [:data :domain]) str not-empty)]
      (some-> (read' :domain id) (get-in [:data :name]) str not-empty))))

(defn- seat-domain-id-of
  "The id of the domain row the seat this sitting is opening in stores,
  read at birth (spend step 3a), or nil: the seat stores none, its
  domain row is gone, or this ctx cannot read. A seat with no domain is
  NOT read as factory here: its sitting carries none."
  [inp ctx]
  (when-some [read' (:read ctx)]
    (when-some [id (some-> (read' :seat (str (:seat inp)))
                           (get-in [:data :domain]) str not-empty)]
      (some-> (domain-row-of ctx id) :id str not-empty))))

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

(def ^:private usage-input
  "`report_usage`'s input (docs/spec-spend.md §3): one session's whole
  total so far. The session is required, since the delta is its own."
  [:map
   [:harness_session {:x-display {:raw true
                                  :label "The harness session"
                                  :help "The session these counts are the whole total of. Two sessions on one sitting each post their own growth."}}
    [:string {:min 1 :max 128}]]
   [:input_tokens {:x-display {:label "Input tokens"
                               :help "The session's whole count so far."}}
    [:int {:min 0}]]
   [:output_tokens {:x-display {:label "Output tokens"
                                :help "The session's whole count so far."}}
    [:int {:min 0}]]
   [:cache_read_tokens {:x-display {:label "Cache-read tokens"
                                    :help "The session's whole count so far."}}
    [:int {:min 0}]]
   [:cache_write_tokens {:x-display {:label "Cache-write tokens"
                                     :help "The session's whole count so far."}}
    [:int {:min 0}]]
   [:turns {:optional true
            :x-display {:label "Model turns"
                        :help "How many turns the model has taken in this session so far."}}
    [:maybe [:int {:min 0 :max 100000}]]]
   [:model {:optional true
            :x-display {:raw true
                        :label "The model"
                        :help "The model row whose prices cost these tokens, when it is not the sitting's own."}}
    [:maybe [:string {:max 200}]]]])

(def ^:private usage-entry
  "One session's line in `usage_by_session`: its last reported counts,
  and what the sitting has posted for it."
  [:map
   [:input_tokens {:x-display {:label "Input tokens"}} [:int {:min 0}]]
   [:output_tokens {:x-display {:label "Output tokens"}} [:int {:min 0}]]
   [:cache_read_tokens {:x-display {:label "Cache-read tokens"}} [:int {:min 0}]]
   [:cache_write_tokens {:x-display {:label "Cache-write tokens"}} [:int {:min 0}]]
   [:turns {:optional true :x-display {:label "Model turns"}} [:maybe [:int {:min 0}]]]
   [:posted_usd {:x-display {:label "Posted, in dollars"}} [:decimal {:min 0}]]])

(def ^:private opening-input
  "`open_ledger`'s input (docs/spec-spend.md §8): what its log row says
  the bill rests on — the opening, and the prices the sitting was billed
  at, where it has them."
  [:map
   [:basis {:x-display {:label "Basis"
                        :help "What the postings rest on: always opening."}}
    [:enum "opening"]]
   [:prices {:optional true
             :x-display {:raw true
                         :label "The prices it was billed at"
                         :help "The sitting's own prices map, copied onto the log row; the bill is not repriced."}}
    [:maybe [:map-of :keyword price-entry]]]])

(defn- sitting-person
  "The member the sitter acts for, or nil. `:acts-for` is the identity
  gate's own mark on a delegate principal (spec-connector-door § 3) —
  a person signed in through a tool — and it is nil for a Routine's
  run, which is the honest answer for a session with nobody in the
  chair."
  [ctx]
  (some-> (get-in ctx [:principal :acts-for]) str not-empty))

(def ^:private served-entry @#'seat-health/served-entry)

;; ── the sitting's summary line (ticket ea061a48) ────────────────────
;; A collection shows a row's summary line and nothing else, so the
;; line names the SEAT, not its id, and says how the sitting is going.
;; It is composed at each render (`:summary-line`), so its times are
;; relative to that render's clock.

(def ^:private line-budget
  "The most characters a sitting's summary line runs to. Past it the
  least useful parts leave whole — the moves, then the refusals."
  100)

(defn- span
  "A length of time in its largest whole unit: 45s, 11m, 3h, 2d."
  [seconds]
  (let [s (max 0 (long seconds))]
    (cond (< s 60) (str s "s")
          (< s 3600) (str (quot s 60) "m")
          (< s 86400) (str (quot s 3600) "h")
          :else (str (quot s 86400) "d"))))

(defn- clock-utc
  "An instant as its UTC time of day: 17:49Z."
  [^java.time.Instant at]
  (str (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm")
                (.atOffset at java.time.ZoneOffset/UTC))
       "Z"))

(defn- counted [n word]
  (let [n (long (or n 0))]
    (when (pos? n) (str n " " word (when (not= 1 n) "s")))))

(defn sitting-line
  "A sitting's summary line, from its row, its seat's document (nil
  when this render could not read it: the line then names the seat by
  its id) and the render's clock (nil: the times are absolute UTC).

    rigs-code-seat · fired · started 11m ago · last call 1m ago · 2 refusals

  An open sitting whose last call is older than half its seat's
  `sitting_idle_seconds` leads with `idle 14m`, in place of the last
  call. A sitting that has ended says its outcome and its state. nil
  when the row names no seat this reader may see."
  [row seat now]
  (let [data (:data row)
        join #(clojure.string/join " · " (remove nil? %))
        label #(some-> % name (clojure.string/replace "_" " ") not-empty)
        mode (label (:mode data))]
    (when-some [who (or (some-> (:name seat) str not-empty)
                        (some-> (:seat data) str not-empty))]
      (if-not (= "open" (some-> (:state row) name))
        (join [who mode (label (:outcome data))
               (some-> (label (:state row)) clojure.string/capitalize)])
        (let [^java.time.Instant now (some-> now ->instant)
              since (fn [at]
                      (.getSeconds (java.time.Duration/between
                                    ^java.time.Instant at now)))
              at (fn [what v]
                   (when-some [at (->instant v)]
                     (if now
                       (str what " " (span (since at)) " ago")
                       (str what " " (clock-utc at)))))
              quiet (when now (some-> (->instant (:last_call_at data)) since))
              idle (when (and quiet
                              (> (long quiet)
                                 (quot (long (or (:sitting_idle_seconds seat)
                                                 default-idle-seconds))
                                       2)))
                     (str "idle " (span quiet)))
              head [idle who mode (at "started" (:started_at data))
                    (when-not idle (at "last call" (:last_call_at data)))]
              refusals (counted (:refusals data) "refusal")
              moves (counted (:transitions data) "move")]
          (or (some #(when (<= (count %) line-budget) %)
                    [(join (conj head refusals moves))
                     (join (conj head refusals))])
              (join head)))))))

(defresource sitting
  {:kind :sitting
   :plural "sittings"
   :purpose words/sitting
   :states [:open :closed :abandoned]
   :initial :open
   :terminal #{:closed :abandoned}
   :nav :system
   ;; no seat leaves an open sitting: the session's hook closes it and
   ;; the engine expires it once its lease runs out
   :left-by [{:in #{:open} :by :engine :via :close :to :closed}
             {:in #{:open} :by :engine :via :expire :to :closed}
             {:in #{:open} :by :engine :via :abandon :to :abandoned}]
   ;; the template is what a render with no clock and no reads says (a
   ;; transition's log line, another row's ref label); the envelope asks
   ;; `sitting-line`, which reads the seat for its name and its idle bar
   :summary "Sitting of {data.seat} · {state}"
   :summary-line
   (fn [row ctx]
     (sitting-line row
                   (when-some [read' (:read ctx)]
                     (some->> (get-in row [:data :seat]) str not-empty
                              (read' :seat) :data))
                   (:now ctx)))
   ;; A SITTING IS ITS MEMBER'S (R-10.3): the session opens one before
   ;; it reads its queue and the harness's hook closes it when the
   ;; session ends, and neither of those moments is a good one to
   ;; discover that the row needs a scope entry. The guards still judge
   ;; every invoke; this only decides which doors are visible enough to
   ;; be knocked on — the grant's own posture, one kind over.
   :own-surface {:by :member :actions #{"create" "close" "tally" "report_usage" "renew"}}
   :computed {:at_work
              {:schema [:map
                        [:lead :string]
                        [:working :boolean]
                        [:calls [:vector
                                 [:map
                                  [:n :int]
                                  [:outcome :string]
                                  [:text :string]
                                  [:href {:optional true} :string]]]]]
               :x-display
               {:label "At work"
                :help "What the seat did in this sitting, call by call, newest last: what it read, what it moved and why, and what it asked a person to decide. Worked out at read time from the sitting's record of calls."}
               :reads? true
               :fn at-work-field}}
   :schema
   [:map
    [:seat {:kind :seat
            :x-display
            {:label "The seat woken"
             :help "The office this wake belongs to. Its budget is what this sitting spends against, and its ledger is where the cost lands."}}
     :waymark/ref]
    ;; THE PROMISE THAT WOKE IT (R-P.10.1 of docs/spec-promise.md):
    ;; stamped by the fire, so spend per promise is a sum over sittings
    [:promise {:optional true
               :x-display
               {:raw true
                :label "The promise that woke it"
                :help "The id of the promise whose break fired this wake, when one did. Stamped by the engine from the fire's text, never written by hand."}}
     [:maybe [:string {:max 128}]]]
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
    ;; CANCELLED TEST RUNS (ticket 39b2c934). A bench.test run the rig
    ;; answers `cancelled` is a run the sitter started and threw away —
    ;; the seat-health `test_thrash` signal. The door counts each run
    ;; once, by its run id, however many times the sitter polls it.
    [:cancelled_runs {:default 0
                      :x-display {:label "Test runs cancelled"
                                  :help "bench.test runs the rig answered cancelled while this sitting was open, each counted once by its run id. Counted by the engine and frozen at the close."}}
     [:int {:min 0}]]
    [:cancelled_run_ids {:default []
                         :x-display {:raw true
                                     :label "Cancelled run ids"
                                     :help "The run ids already counted in cancelled_runs, so a second poll of one run does not count it again."}}
     [:vector :string]]
    ;; IDENTICAL CALLS (ticket c785073c). The power door counts each
    ;; sitting's calls by a hash of the tool and its arguments, and
    ;; keeps here the most any one of them was made: seat health's
    ;; `poll_loop` signal.
    [:repeat_calls {:default 0
                    :x-display {:label "Most identical calls"
                                :help "The most times this sitting made one call through the power door, the same tool with the same arguments. Counted by the engine and frozen at the close."}}
     [:int {:min 0}]]
    ;; THE CORRECTIONS LINE. Written by the router AFTER the close: a
    ;; person's transition on a row whose previous transition was this
    ;; sitting's (found by that transition's grant) adds one here and
    ;; the row's id to `corrected_rows`. Data beside the audit, not a
    ;; verdict (R-11.4) — the rows say what to read. Absent reads zero.
    [:corrections {:optional true
                   :x-display {:label "Verdicts a person reversed"
                               :help "Counted by the engine after the close: each person's transition on a row whose last transition was this sitting's."}}
     [:maybe [:int {:min 0}]]]
    [:corrected_rows {:optional true
                      :x-display
                      {:raw true
                       :label "The rows a person reversed"
                       :spelled-by-hand "The ids of the rows whose verdict from this sitting a person later changed. The engine writes it beside `corrections`."}}
     [:maybe [:vector [:string {:max 128}]]]]
    ;; WHICH LAW THE LAST REFUSAL WAS. The count says how many; this
    ;; says what the newest one was, so the close can tell whether the
    ;; last invoke or bench write was refused, and on which guard.
    [:last_refusal {:optional true
                    :x-display
                    {:raw true
                     :label "The last refusal"
                     :help "The newest 409 served under this sitting's grant: its problem type, the guard that refused when one did, and when. Stamped by the engine beside the count, frozen at the close."}}
     [:maybe [:map
              [:type [:maybe [:string {:max 200}]]]
              [:guard {:optional true} [:maybe [:string {:max 200}]]]
              [:at [:string {:max 64}]]]]]
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
                            :help "The sum of what this sitting's usage reports have posted to its seat's spend, each priced when it was reported; a reprice afterwards does not move it. On an OPEN sitting it is the running cost (R-12.27), so the week's wall can see a sitting that has not ended."}}
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
    ;; SPEND STEP 3c: the report's own record. Each session's last
    ;; counts, the delta the last report posted, and what the postings
    ;; rest on — all written by the reporting doors, none by hand.
    [:usage_by_session {:optional true
                        :x-display
                        {:raw true
                         :label "Usage, by harness session"
                         :spelled-by-hand "Each session's last reported counts and what it has posted, written by every usage report."}}
     [:maybe [:map-of :keyword usage-entry]]]
    [:last_report {:optional true
                   :x-display
                   {:raw true
                    :label "The last usage report"
                    :spelled-by-hand "The session, the delta it posted, the prices it was costed at and when; the posted amount is delta_usd."}}
     [:maybe [:map
              [:harness_session [:string {:max 128}]]
              [:delta_usd [:decimal {:min 0}]]
              [:estimate_reversed_usd {:optional true} [:decimal {:min 0}]]
              [:reversed_basis {:optional true} [:enum "estimated_from_transcript" "estimated_from_calls"]]
              [:estimate_usd {:optional true} [:decimal {:min 0}]]
              [:estimate_basis {:optional true} [:enum "estimated_from_transcript" "estimated_from_calls"]]
              [:prices [:map-of :keyword price-entry]]
              [:at :waymark/instant]]]]
    [:bill_basis {:optional true
                  :x-display
                  {:label "What the bill rests on"
                   :spelled-by-hand "estimated_from_transcript, or estimated_from_calls when no line or report came, while the floor's net estimate stands, else the basis of the latest posting, none when nothing posted."}}
     [:maybe (into [:enum] ["reported" "opening" "estimated_from_transcript" "estimated_from_calls" "none"])]]
    ;; SPEND STEP 6: the engine's floor (docs/spec-spend.md §4), written
    ;; by `expire` and re-run by a late report, what `expire` posts.
    [:floor {:optional true
             :x-display
             {:raw true
              :label "The engine's floor"
              :spelled-by-hand "What the transcript showed beyond every session's report when the engine last ran the floor, the models it was priced at, and when."}}
     [:maybe [:map
              [:estimate_usd [:decimal {:min 0}]]
              [:models [:vector {:max 64} [:string {:max 128}]]]
              [:basis {:optional true} [:enum "estimated_from_transcript" "estimated_from_calls"]]
              [:at :waymark/instant]]]]
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
              :not-a-ref "A member's principal id, not a person row's: the member kind owns it."
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
     [:maybe :waymark/instant]]
    ;; THE LAST CALL (ticket 086307f2). Stamped by the sit at birth and
    ;; by every call the doors count against the sitting — a tool
    ;; answered, a transition, a refusal, a tally — so a FIRED sitting
    ;; whose run was lost reads as silent, and the sweep ends it after
    ;; the seat's `sitting_idle_seconds`.
    [:last_call_at {:optional true
                    :x-display
                    {:label "Last call"
                     :spelled-by-hand "Stamped by the engine on every call counted against an open sitting; the sweep ends a fired sitting silent past its seat's idle limit."}}
     [:maybe :waymark/instant]]
    ;; THE LEASE (docs/spec-leases.md §3). Stamped at birth to
    ;; `started_at` plus the seat's `sitting_idle_seconds` and moved by
    ;; `renew` once less than half of it is left. Past it the sitting is
    ;; no longer counted open, and its tokens and keys stop answering.
    [:expires_at {:optional true
                  :x-display
                  {:label "Lease ends"
                   :spelled-by-hand "Stamped at birth and moved by each renewal: the instant after which this sitting is no longer counted open and its tokens and keys stop answering."}}
     [:maybe :waymark/instant]]
    ;; THE LAST ANSWER (ticket 28a5d117). Stamped by the MCP door on a
    ;; tool call that answered and was not a refusal, so the close can
    ;; tell a sitting its last refusal stopped from one that called
    ;; again and was answered.
    [:last_answer_at {:optional true
                      :x-display
                      {:label "Last answer"
                       :spelled-by-hand "Stamped by the engine on each tool call that answered without an error; the close reads it against the last refusal."}}
     [:maybe :waymark/instant]]
    ;; THE TRACE OF THE FIRING'S KEY (R-12.37). The sit that spends a
    ;; firing's key keeps its hash here, on the sitting it opened and
    ;; not on the seat, so a run that loses its bind to a restart may
    ;; sit again in THIS sitting, while it is open, and nowhere else.
    [:fire_key_hash {:optional true :secret true
                     :x-display {:hidden true
                                 :label "The firing key's hash"
                                 :spelled-by-hand "The SHA-256 of the firing key that opened this sitting. The sit writes it; it answers only while the sitting is open; the engine never shows a key."}}
     [:maybe [:string {:max 64}]]]
    ;; THE CONNECTOR SESSION'S CLAIM (ticket 7496403e). The sit that
    ;; picks this sitting stamps the hash of its connector session and
    ;; the moment, in the same transaction that holds the row, so a
    ;; session-less re-sit after a drop can tell a claim whose session
    ;; is gone from one a live run still holds (mcp/reusable-sitting).
    [:connector_session {:optional true
                         :x-display
                         {:raw true
                          :label "The claiming connector session"
                          :help "The SHA-256 of the connector session that last sat in this sitting. The sit writes it; the engine never shows a session id."}}
     [:maybe [:string {:max 64}]]]
    [:claimed_at {:optional true
                  :x-display
                  {:label "Claimed"
                   :spelled-by-hand "Stamped by the sit that last claimed this sitting for its connector session."}}
     [:maybe :waymark/instant]]
    ;; WHAT THE CLIENT DECLARED (ticket b9f90987). The session table
    ;; keeps what an `initialize` said and no kind serves it, so the sit
    ;; copies it here at the bind and again at each re-sit
    ;; (`stamp-client!`). `app_tools` is the listing's own verdict at
    ;; that moment, so one read answers why a host got no waymark_show.
    ;; The booleans are plain :boolean, `missed`'s spelling.
    [:client_name {:optional true
                   :x-display
                   {:label "The MCP client"
                    :spelled-by-hand "The clientInfo name the connector session's initialize declared. The sit copies it from the session; absent when the client named none."}}
     [:maybe [:string {:max 200}]]]
    [:client_version {:optional true
                      :x-display
                      {:label "The MCP client's version"
                       :spelled-by-hand "The clientInfo version the connector session's initialize declared. The sit copies it from the session; absent when the client named none."}}
     [:maybe [:string {:max 200}]]]
    [:app_ui {:optional true
              :x-display
              {:label "Declared MCP Apps"
               :spelled-by-hand "Written by the sit: whether the connector session's initialize declared the MCP Apps extension with the app page's MIME type."}}
     :boolean]
    [:app_tools {:optional true
                 :x-display
                 {:label "Listed the app tools"
                  :spelled-by-hand "Written by the sit: whether the session's tool listing carried the app tools at that moment. The client declared the extension, its bearer is a delegate of a client listed for the app tools, and the ticket's signing key is set."}}
     :boolean]
    ;; THE INBOX'S KEY. A seat that has an inbox (`inbox-of`) is answered
    ;; a fresh key at each sit (`issue-inbox-key!`), and its hash is kept
    ;; here, on the sitting, so the key answers only while the sitting
    ;; is open (`inbox-sitting-by-key`).
    [:inbox_key_hash {:optional true :secret true
                      :x-display {:hidden true
                                  :label "The inbox key's hash"
                                  :spelled-by-hand "The SHA-256 of the inbox key the last sit answered. The sit writes it; it answers only while the sitting is open; the engine never shows a key."}}
     [:maybe [:string {:max 64}]]]
    ;; THE KEYS BEFORE THE NEWEST. A sit that reuses this sitting for the
    ;; same harness session mints a key and stops none, so the key a
    ;; stream is using answers until the sitting closes. Their hashes
    ;; are kept here, newest first.
    [:inbox_keys_earlier {:optional true :secret true
                          :x-display {:hidden true
                                      :label "The earlier inbox keys' hashes"
                                      :spelled-by-hand "The SHA-256 of each inbox key a sit of the same session answered before the newest. The sit writes it; they answer only while the sitting is open; the engine never shows a key."}}
     [:maybe [:vector [:string {:min 1 :max 128}]]]]
    ;; THE FEED'S TOKEN. A seat that declares a `feed_url` is answered
    ;; a fresh token at each sit (`issue-feed-token!`). Its hash and its
    ;; end are kept here, on the sitting, so the token answers only
    ;; while the sitting is open and that end has not come
    ;; (`feed-seat-by-token`).
    [:feed_token_hash {:optional true :secret true
                       :x-display {:hidden true
                                   :label "The feed token's hash"
                                   :spelled-by-hand "The SHA-256 of the feed token the last sit answered. The sit writes it; it answers only while the sitting is open; the engine never shows a token."}}
     [:maybe [:string {:max 64}]]]
    [:feed_token_expires_at {:optional true
                             :x-display
                             {:label "The feed token's end"
                              :spelled-by-hand "Written by the sit: the instant after which the feed token the last sit answered is refused, when the sitting has not closed before it."}}
     [:maybe [:string {:max 40}]]]
    ;; THE TOKENS BEFORE THE NEWEST. A re-sit and a renewal at the key
    ;; check door each mint a token and stop none (`with-feed-token`),
    ;; so the token a stream is using answers until its own end. Their
    ;; hashes and ends are kept here, newest first.
    [:feed_tokens_earlier {:optional true :secret true
                           :x-display {:hidden true
                                       :label "The earlier feed tokens' hashes"
                                       :spelled-by-hand "The SHA-256 and the end of each feed token minted before the newest that has not reached its end. The sit and the key check door write it; the engine never shows a token."}}
     [:maybe [:vector
              [:map
               [:hash {:x-display
                       {:label "The hash of one token"
                        :help "The SHA-256 of a feed token minted before the newest one."}}
                [:string {:min 1 :max 128}]]
               [:expires_at {:x-display
                             {:label "When it stops answering"
                              :help "The instant after which this token is refused, when the sitting has not closed before it."}}
                [:string {:max 40}]]]]]]
    ;; A WAKE THAT WALKED NOTHING. The sit stamps this when the walk it
    ;; hands has no rows at all — an empty queue, a queue whose every
    ;; row another open sitting or a stuck change holds, a seat at a
    ;; wall — so seat health can count an idle wake. A sitting with no
    ;; lease cannot say it: one the claim never reached has none
    ;; either. Plain :boolean, `missed`'s spelling, so the
    ;; field promotes to a column and filters.
    [:walked_nothing {:optional true
                      :x-display
                      {:label "Walked nothing"
                       :spelled-by-hand "Written by the sit when the walk it handed had no rows at all: the queue was empty, or every row in it was held. A sitting that was handed a row does not carry it, and a seat at a wall carries `halted` instead."}}
     :boolean]
    ;; WHY IT WAS EMPTY (ticket ae64b57c). The rows the walk left out,
    ;; each with the rule that left it out — the sit's own `withheld` —
    ;; or that the queue itself was empty, so an empty walk over a
    ;; queue that is not empty is not read as a walk bug.
    [:walked_nothing_why {:optional true
                          :x-display
                          {:label "Why it walked nothing"
                           :spelled-by-hand "Written by the sit beside `walked_nothing`: the rows the walk left out and the rule that left out each one (another open sitting holds it, the release grace holds it, its change waits for a person), or that the queue had no rows under the walk's filter."}}
     [:maybe [:string {:max 480}]]]
    ;; A WAKE THE WALL HELD (ticket ae64b57c). The sit's `halted`
    ;; block, kept: a halted sit was not let walk, so it is not stamped
    ;; `walked_nothing`, and this says which wall and when it lifts.
    [:halted {:optional true
              :x-display
              {:raw true
               :label "Halted at a wall"
               :spelled-by-hand "Written by the sit when the seat was against a wall: which wall, the sentence that says why, and when it lifts."}}
     [:maybe halt-mark]]
    ;; A FIRE NOBODY SAT IN. The clock sweep writes this row, already
    ;; closed, when a firing's key is still unspent past the sit
    ;; deadline (`wakes/sweep-missed!`), so an audit that reads the
    ;; sittings sees the run that died before it sat. Plain :boolean
    ;; and not :maybe, so the field promotes to a column and filters.
    [:missed {:optional true
              :x-display
              {:label "Fired, and nobody sat"
               :spelled-by-hand "Written by the engine's sweep when a firing's key went unspent past the sit deadline. The sitting is born closed, with every count at zero."}}
     :boolean]
    ;; HOW IT WAS CLOSED. Written at every path that ends a sitting
    ;; closed — the close handler for the door, the hook and the idle
    ;; sweep, and `wakes/record-missed!` for a missed fire — so seat
    ;; health reads a cut-short or never-sat run without parsing the
    ;; note. Absent while open; not :maybe, so it promotes and filters.
    [:closed_by {:optional true
                 :x-display
                 {:label "How it was closed"
                  :choices closed-by-choices
                  :spelled-by-hand "Written by the engine at the close: door, hook, sweep, or missed for a fire nobody sat in."}}
     (into [:enum] closed-by-paths)]
    ;; WHAT IT CAME TO, AND WHY (seat health 1, ticket fad586b7). Both
    ;; are written at every path that ends a sitting closed, beside
    ;; `closed_by`, by `sitting-health`: one outcome, and the flags
    ;; that explain it. No model judges either. `outcome` is an enum
    ;; and not :maybe, so it promotes and filters; `flags` is a
    ;; vocabulary array, so it filters by membership.
    [:outcome {:optional true
               :x-display
               {:label "What it came to"
                :choices outcome-choices
                :help outcome-help}}
     (into [:enum] sitting-outcomes)]
    [:flags {:optional true
             :x-display
             {:label "Health flags"
              :help flags-help}}
     [:vector [:waymark/vocab {:open true}]]]
    ;; THE SEAT'S DOMAIN, BY NAME (epic aff24e84, piece 3): stamped at
    ;; birth from the seat's stored domain. A seat that stores none
    ;; stamps nothing, and :absent-as filters the row as factory. Not
    ;; :maybe, so it promotes and filters.
    [:domain {:optional true
              :not-a-ref "The domain's NAME, as the seat's domain row spells it: never a row id."
              :x-display
              {:label "The seat's domain"
               :help "The name of the domain the seat was in when this sitting opened. The engine stamps it at birth. A sitting with none filters as factory."}}
     [:string {:min 1 :max 120}]]
    ;; THE SEAT'S DOMAIN, BY ID (spend step 3a): stamped once at birth
    ;; from the seat's stored domain, so a seat that moves domain
    ;; mid-sitting keeps this sitting's stamp. A seat that stores none
    ;; stamps nothing; `backfill-domain-ids!` fills the rows born before.
    [:domain_id {:optional true
                 :kind :domain
                 :x-display
                 {:label "The seat's domain row"
                  :help "The domain the seat was in when this sitting opened, by id. The engine stamps it at birth. Absent when the seat named no domain."}}
     ;; not :maybe, so it promotes and filters: the report's domain
     ;; posting reads the row it names (spend step 3c)
     :waymark/ref]]
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
                      [:last_call_at (:now ctx)]
                      ;; the lease (docs/spec-leases.md §3): started_at
                      ;; plus the seat's idle limit, in both modes
                      [:expires_at (lease-end (:now ctx) (seat-at-birth (:data row) ctx))]
                      [:input_tokens 0] [:output_tokens 0]
                      [:cache_read_tokens 0] [:cache_write_tokens 0]
                      [:turns 0] [:transitions 0] [:refusals 0]
                      ;; R-10.6a: an open sitting that has read
                      ;; nothing yet says so with an empty map, not
                      ;; with a hole
                      [:served {}]]
               ;; the delegate's own person, when there is one — the
               ;; identity gate's mark, never a claim in the request
               (sitting-person ctx) (conj [:person (sitting-person ctx)])
               ;; the seat's stored domain, by name; a seat that stores
               ;; none stamps nothing
               (seat-domain-of (:data row) ctx)
               (conj [:domain (seat-domain-of (:data row) ctx)])
               (seat-domain-id-of (:data row) ctx)
               (conj [:domain_id (seat-domain-id-of (:data row) ctx)]))))
   :filterable {:state #{:eq :in}
                :domain #{:eq}
                :domain_id #{:eq}
                :seat #{:eq}
                :member #{:eq}
                :model #{:eq}
                :grant #{:eq}
                :missed #{:eq}
                :closed_by #{:eq :in}
                :outcome #{:eq :in}
                :started_at #{:after :before :range}
                :ended_at #{:after :before :range}}
   :absent-as {:domain default-domain}
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
     :guards [reports-only-grow]
     :input report-input
     :record true
     ;; :edit-shape — a close welds the first counts onto a row that
     ;; has none; there is no earlier value to prefill from and no
     ;; second close, so the fence an :edit implies would be an etag
     ;; demanded of a session-end hook for fields nobody has written.
     :waives #{:edit-shape}
     :posts report-posts
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
     :guards [reports-only-grow]
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
     :posts report-posts
     :handler tally-sitting
     :display {:label "Tally" :order 2
               :description "Write what this sitting has spent so far — the counts, the running cost at today's prices, and the stamp the sweep reads"}}

    ;; ── the usage report (docs/spec-spend.md §3, spend step 3c) ─────
    ;; Cumulative in, delta out: the price of what a session grew since
    ;; its last report, posted to the seat's and the domain's spend. It
    ;; runs on an ended sitting too, under the licence, and leaves it
    ;; ended: a late hook still reports.
    :report_usage
    {:from #{:open :closed :abandoned} :to :same
     :allow-after-end true
     :writes #{:usage_by_session :last_report :cost_usd :bill_basis :floor}
     :guards [reports-only-grow]
     :input usage-input
     :record true
     :waives #{:edit-shape}
     :posts report-posts
     :safety {:idempotent true :reversible false :confirm false}
     :handler report-usage
     :display {:label "Report usage" :order 3
               :description "Report a session's whole token counts so far — the price of what grew since its last report is posted to the seat's and the domain's spend"}}

    ;; ── the lease's renewal (docs/spec-leases.md §2, §3) ────────────
    ;; A call or a report that finds less than half the lease left
    ;; makes this once its own write has committed, so a steady run logs
    ;; about two renewals per lease length. A lapsed lease is refused,
    ;; and the session sits afresh.
    :renew
    {:from #{:open} :to :open
     :guards [lease-expired]
     :safety {:idempotent true :reversible false :confirm false}
     ;; :replay false — a renew whose latest transition is a renew (a
     ;; session that only reads its inbox writes nothing between) would
     ;; otherwise replay the first and never move `expires_at` again
     :replay false
     :handler renew-sitting
     :display {:label "Renew" :order 5
               :description "Move this sitting's lease end to now plus its seat's idle limit"}}

    ;; ── the opening (docs/spec-spend.md §8, spend step 4) ───────────
    ;; The engine's own: a bill from before the ledger, posted unchanged
    ;; and dated when it was spent, on any sitting, ended or not.
    :open_ledger
    {:from #{:open :closed :abandoned} :to :same
     :allow-after-end true
     :writes #{:usage_by_session :last_report :bill_basis}
     :guards [the-engines-own-hand a-bill-to-open]
     :input opening-input
     :record true
     :waives #{:edit-shape}
     :posts opening-posts
     :safety {:idempotent true :reversible false :confirm false}
     :handler open-ledger
     :display {:label "Open the ledger" :order 4
               :description "Post the cost this sitting was billed before the ledger to its seat's and its domain's spend, unchanged, dated when it was spent"}}

    ;; the floor again, when transcript lines land on a sitting the
    ;; engine expired (docs/spec-spend.md §4): the upload door's
    :refloor
    {:from #{:closed} :to :same
     :allow-after-end true
     :writes #{:last_report :cost_usd :bill_basis :floor}
     :guards [the-engines-own-hand]
     :posts report-posts
     ;; :replay false — a second batch of lines must re-run the floor,
     ;; not replay the first refloor
     :replay false
     :safety {:idempotent true :reversible false :confirm false}
     :handler refloor-sitting
     :display {:label "Re-price the floor" :order 9}}

    ;; the engine's end of a sitting whose lease ran out: the sweep's,
    ;; or a claim's (docs/spec-leases.md §3, leases 3b)
    :expire
    {:from #{:open} :to :closed
     :guards [the-engines-own-hand lease-still-running]
     ;; the floor (docs/spec-spend.md §4, spend step 6)
     :posts floor-posts
     :handler expire-sitting
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The sitting's lease ran out and it is over: its leases end with it, and the session that held it sits afresh."}
     :display {:label "Expire" :order 8}}

    ;; R-7.6: a sitting left open past two cadences is the boot
    ;; sweep's, and it ends with NO tokens — the absence of a bill,
    ;; not a zero one. Since leases 3b only a person's tap abandons.
    :abandon
    {:from #{:open} :to :abandoned
     :guards [the-engine-or-the-persons-tap]
     ;; nothing is written on the row. The ending of a sitting
     ;; nobody closed is the ABSENCE of a bill, not a zero one, and
     ;; the counts it already carries are what it did before it was
     ;; lost. Its handler only lets go of its leases, with no grace.
     :handler abandon-sitting
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A sitting nobody closed is over with no cost recorded; the session that would have reported its tokens is gone."
              ;; the door is visible now (ticket be2c2c16), so the
              ;; cheap-reverse battery reads it: the cost is the world's,
              ;; not the row's, and :final is where that is spelled
              :final "The session that sat is gone and nothing will report its tokens; its seat's next firing opens a new sitting."}
     :display {:label "Abandon" :order 9}}}
   :deviations
   ["R-10.2 lets a sitting's `model` be null (R-9.4: a token with no claim has model null), and R-10.7 wants the collection filterable by model. A promoted column is generated only for a non-`:maybe` entry, so those two cannot both be had: `model` is required at the create door, and the session's claim wins over it when there is one. A harness with nothing to declare names the row it is running as."
    "R-10.6 has the engine count transitions and refusals. `bump-counter!` is a maintenance write (`store/update-data!`, jobs.clj's progress precedent) rather than a transition: a logged transition per counted transition would double the log — the counter would cost more log than the thing it counts. The `close` is a real transition and freezes both numbers."
    "`tally` writes a `cost_usd` and NO `prices` map, where the close writes both. The prices are copied down beside a bill a reprice must not be able to move, and the only bill is the close's; a tally's cost is a reading of the row at that moment, re-read at the next turn, and a prices map beside it would say a running figure was final. A sitting closed by the sweep from its last tally is costed by the close, at the close's prices, like every other."
    "`close` and `tally` share ONE input (`report-input`) rather than declaring the five counts twice. The two doors take the same report from the same hook — one ends the sitting, the other writes the running total — and two spellings would be two shapes for a harness to keep in step, which is exactly the drift § 3 of the spec is about."
    "`harness_session` is on the BIRTH door as well as the close's (R-12.15, R-12.17), which no other count-bearing field is. The reason is that it is not a count: it is the only fact a session knows at the sit that the engine cannot derive, and the pairing it makes is what lets two overlapping wakes of one seat each end their own sitting. It is `:maybe`, so it is not filterable and the pairing reads one page of the seat's open sittings rather than querying — `model`'s recorded wall, one field over. The close writes it only onto a row that carries none: a report naming another run's id must not move a bill."]
   :scenarios [another-hand-abandons-a-sitting-at-the-persons-tap
               the-sweep-abandons-a-lost-sitting]})

;; ── the seam wave two calls ─────────────────────────────────────────

(def ^:private open-sitting-page seat-holds/open-sitting-page)

(def open-sitting-for-grant seat-holds/open-sitting-for-grant)
(def open-sittings-for-grant seat-holds/open-sittings-for-grant)
(def open-sitting-for-seat seat-holds/open-sitting-for-seat)

;; The sitting's counters and stamps live in
;; `waymark10.server.seats.health` (ticket b44dbe85, round three).

(def bump-counter! seat-health/bump-counter!)
(def stamp-call! seat-health/stamp-call!)
(def stamp-client! seat-health/stamp-client!)
(def add-cancelled-run! seat-health/add-cancelled-run!)

;; The claim stays here: raw_writes.clj lists its raw write under this
;; file. It rides the stamps' renewal through their vars.

(def ^:private with-renewal @#'seat-health/with-renewal)
(def ^:private judge-renewal! @#'seat-health/judge-renewal!)
(def ^:private call-stamp @#'seat-health/call-stamp)
(def ^:private seat-of-sitting seat-holds/seat-of-sitting)

(declare claim-sitting*)

(defn claim-sitting!
  "Claim an open sitting for the connector session whose hash is
  `claimant` (ticket 7496403e): `connector_session`, `claimed_at` and
  `last_call_at` move together, when `free?` — handed the row's data
  as this transaction holds it for update — says the row may be taken.
  The same MAINTENANCE write as `stamp-call!`. Two sits racing for one
  row serialise on its lock, and the second reads the first's claim.
  A nil `claimant` stamps the call alone. → the row as claimed, or
  nil: no id, an unknown id, a closed sitting or a lapsed lease (the
  sit then births a fresh one, docs/spec-leases.md §3), or a claim
  `free?` refuses. A claim is a call, so it renews at half-life as
  `stamp-call!` does.

  With `relet?` a lapsed lease the sweep has not ended yet is claimed
  too, and let again from now (ticket e2b55a0c): the sit that passes it
  is THIS run's own re-sit — bound to the row, or stamped with its
  harness session — and the sweep must not end it under the caller it
  was just handed to.

  `stamps` is the sit's take of the sitting its fire opened (ticket
  9100c7b9): `person` and, when given, `harness_session`, written in the
  claim's own write, and the lease let again from now to the idle limit,
  since the fire leased it to the sit deadline alone."
  ([eng sitting-id claimant free?]
   (claim-sitting! eng sitting-id claimant free? false))
  ([eng sitting-id claimant free? relet?]
   (claim-sitting! eng sitting-id claimant free? relet? nil))
  ([eng sitting-id claimant free? relet? stamps]
   (with-renewal eng sitting-id
     #(claim-sitting* eng sitting-id claimant free? relet? stamps))))

(defn- claim-sitting*
  "`claim-sitting!`'s write, in its own transaction."
  [eng sitting-id claimant free? relet? stamps]
  (when (and sitting-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :sitting
                                        (str sitting-id) {:for-update true})]
          (when (and (or (lease-live? row (now-of eng))
                         (and relet? (= "open" (some-> (:state row) name))))
                     (free? (:data row)))
            (judge-renewal! eng tx row)
            (let [at (call-stamp eng)
                  lapsed? (not (lease-live? row (now-of eng)))
                  data (cond-> (assoc (:data row) :last_call_at at)
                         claimant (assoc :connector_session (str claimant)
                                         :claimed_at at)
                         (or lapsed? (seq stamps))
                         (assoc :expires_at
                                (lease-end (now-of eng)
                                           (seat-of-sitting eng tx row)))
                         (seq stamps) (merge stamps))]
              (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                  data nil)
              (assoc row :data data))))))))

(def sitting-transitions seat-holds/sitting-transitions)

(def backfill-health! seat-health/backfill-health!)

(def backfill-expiries! seat-holds/backfill-expiries!)

(def backfill-domain-ids! ledger/backfill-domain-ids!)

(def open-ledger! ledger/open-ledger!)

(def ledger-agreement ledger/ledger-agreement)

;; Seat health 2 to 4 (the rollup, its breach tickets and the rows that
;; wait outside the grant), the correction count and the served ledger
;; live in `waymark10.server.seats.health` (ticket b44dbe85, round
;; three).

(def seat-health seat-health/seat-health)
(def submits-change? seat-health/submits-change?)
(def submitting-seat? seat-health/submitting-seat?)
(def health-breach seat-health/health-breach)
(def health-default-key seat-health/health-default-key)
(def roll-health! seat-health/roll-health!)
(def health-promise seat-health/health-promise)
(def health-promises seat-health/health-promises)
(def within-window seat-health/within-window)
(def rule-breach seat-health/rule-breach)
(def roll-all-health! seat-health/roll-all-health!)
(def after-write seat-health/after-write)
(def count-correction! seat-health/count-correction!)
(def add-served! seat-health/add-served!)

(def seat-halt! seat-halts/seat-halt!)
(def seat-clear-halt! seat-halts/seat-clear-halt!)
(def seat-halt-at-the-wall! seat-halts/seat-halt-at-the-wall!)
(def mark-stale! seat-halts/mark-stale!)
(def lapse! seat-halts/lapse!)

;; ── the keyed sitter session's seam (R-12.13) ───────────────────────

(def ^:private key-matches? seat-keys/key-matches?)

(def seat-by-key "`seat-keys/seat-by-key`." seat-keys/seat-by-key)

(def seat-named seat-keys/seat-named)
(def standing-key? seat-keys/standing-key?)

;; ── the key of one firing (R-12.37) ─────────────────────────────────
;;
;; The two keys above are STANDING. A person mints one, offers it at a
;; door, and pastes it into a Routine. That puts a secret in a prompt a
;; person writes by hand, and a missed step stays silent until the
;; first firing: the meal planner's first run (2026-09-21) had the
;; seat's id and name from the fire text, and nothing to sit with.
;;
;; A FIRE KEY IS THE OTHER KIND. The engine mints one for each fire of
;; a seat that has instructions. The fire text carries it on one line,
;; and the seat row keeps the hash alone. One key opens one sit. A key
;; no session spent stops answering after the seat's
;; `sitting_idle_seconds`, which is the same limit the sweep measures
;; an idle sitting by. The Routine's prompt then holds no secret, so
;; there is no step for a person to miss.

(def key-hash "`seat-keys/key-hash`." seat-keys/key-hash)

(def ^:private fire-key-entry seat-keys/fire-key-entry)

;; ── a fire's key, from mint to spend: seats/fire.clj ──────────────────
;; Carve 3 of 4 (ticket bb84d8d9). One line for each var another
;; namespace or this one calls; the defns live in
;; `waymark10.server.seats.fire`.

(def ^:private named-row fire/named-row)
(def hold-fire-key! fire/hold-fire-key!)
(def fire-key-held? fire/fire-key-held?)
(def fire-key-row fire/fire-key-row)
(def spend-fire-key! fire/spend-fire-key!)

;; ── the key of a fire a pull link holds (ticket a25950b4) ────────────

(def fire-key-of-text "`seat-keys/fire-key-of-text`." seat-keys/fire-key-of-text)

(def ^:private rewrite-fire-key! fire/rewrite-fire-key!)
(def queue-fire-key! fire/queue-fire-key!)
(def take-fire-key! fire/take-fire-key!)
(def revoke-fire-key! fire/revoke-fire-key!)
(def keep-fire-key! fire/keep-fire-key!)

;; ── the rows a seat's sittings hold: seats/holds.clj ───────────────
;; Round two (ticket 806dbaec). One line for each var another
;; namespace calls; the defns live in `waymark10.server.seats.holds`.

(def held-row-ids seat-holds/held-row-ids)
(def handed-row-ids seat-holds/handed-row-ids)
(def handed-by seat-holds/handed-by)
(def grace-lifts-at seat-holds/grace-lifts-at)
(def elsewhere-lifts-at seat-holds/elsewhere-lifts-at)
(def other-seats-row-ids seat-holds/other-seats-row-ids)
(def claimed-rows seat-holds/claimed-rows)
(def holding-sittings seat-holds/holding-sittings)
(def graced-row-ids seat-holds/graced-row-ids)
(def groom-after-stall seat-holds/groom-after-stall)
(def ask-after-close seat-holds/ask-after-close)
(def stuck-walk-reasons seat-holds/stuck-walk-reasons)
(def stuck-walk-rows seat-holds/stuck-walk-rows)
(def named-walk-row seat-holds/named-walk-row)
(def failing-change-mark seat-holds/failing-change-mark)
(def fire-deferred-until seat-holds/fire-deferred-until)
(def fire-names seat-holds/fire-names)
(def open-walked-rows seat-holds/open-walked-rows)
(def sitting-down-count seat-holds/sitting-down-count)
(def named-beside-a-live-change? seat-holds/named-beside-a-live-change?)
(def named-beside-a-submitted-change? seat-holds/named-beside-a-submitted-change?)
(def named-open-beside-a-submitted-change? seat-holds/named-open-beside-a-submitted-change?)
(def unwalkable-rows seat-holds/unwalkable-rows)
(def claim-rows! seat-holds/claim-rows!)
(def claim-rows-atomically! seat-holds/claim-rows-atomically!)
(def stamp-walked-nothing! seat-holds/stamp-walked-nothing!)
(def stamp-halted-schedule! seat-holds/stamp-halted-schedule!)
(def open-sitting-count seat-holds/open-sitting-count)
(def busy-sitting-count seat-holds/busy-sitting-count)

(defn resit-sitting
  "The OPEN sitting of the seat `named` names that this spent firing's
  key opened, for THIS harness session, or nil (R-12.16, R-12.37).

  A run whose bind died with the engine presents its firing's key a
  second time. The key opens no new sitting; it re-sits only the one it
  opened, while that sitting is open, and only for the harness session
  stamped on it at birth. A call that names no session matches nothing.
  → [seat sitting], or nil."
  [eng named key harness-session]
  (let [harness (some-> harness-session str str/trim not-empty)]
    (when-some [wanted (key-hash key)]
      (when (and harness (get (inv/resources eng) :sitting))
        (when-some [seat (seat-named eng named)]
          (let [rows (store/with-tx (:storage eng)
                       (fn [tx]
                         (store/query-rows (:storage eng) tx :sitting
                                           {:seat (str (:id seat)) :state :open}
                                           {:limit open-sitting-page
                                            :newest-first true})))
                hit (first
                     (filter (fn [r]
                               (and (= harness (some-> (get-in r [:data :harness_session])
                                                       str not-empty))
                                    (keys/equal? wanted (get-in r [:data :fire_key_hash]))))
                             rows))]
            (when hit [seat hit])))))))

(defn seat-for-key
  "The seat `named` names, when this key may sit in it. Nil for
  everything else (R-4 of waymark-fp62.7.23, R-12.37).

  THREE KEYS OPEN ONE SEAT. The seat's own and its chair's are the two
  standing ones (`standing-key?`). The third is the key of one firing:
  the engine minted it when the seat fired, the fire text carried it,
  and it answers for this seat alone until a sit spends it or its
  moment passes.

  Nil is the only other answer, and the caller says one sentence for
  all of it: a name nobody answers to, a seat that is not active, a
  key the seat and its chair both refuse, a firing's key already
  spent. Saying which would turn the door into an oracle over the
  house's offices."
  [eng named key]
  (when-some [key (some-> key str not-empty)]
    (when-some [seat (seat-named eng named)]
      (when (or (standing-key? eng seat key)
                (fire-key-held? eng seat key))
        seat))))

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

;; ── the fire's own sitting (ticket 9100c7b9) ────────────────────────
;;
;; The fire opens the sitting its run will sit in, and the run's sit
;; takes it. Before, the sit opened it, so a run that died before it sat
;; left nothing open and the missed sweep wrote a sitting born closed.
;; The model and the grant are read here, one copy for the fire and the
;; sit (`waymark10.server.mcp`).

(defn- decoded-row
  "One decoded row, or nil."
  [eng kind id]
  (when-some [rdef (get (inv/resources eng) kind)]
    (when-some [id (some-> id str not-empty)]
      (some->> (store/with-tx (:storage eng)
                 (fn [tx] (store/load-row (:storage eng) tx kind id {})))
               (inv/decode-row rdef)))))

(defn seat-model
  "The model row the sitter claims (R-12.8). For a schedule this engine
  pushes, the schedule's `model` is the declaration and the copy
  mirrors it. For a schedule it does NOT push, that `model` is the
  value at mint or at link time and drifts the moment the seat steps
  down; the seat's first `held_for` — the CHAIR — is the declaration
  then, and the schedule's copy is only the fallback when the seat
  names no model at all. With neither, nil. The row, not the name,
  because the sitting's birth wants the ref and the sitter's claim
  wants the identifier.

  A SCHEDULE THAT FIRES THROUGH A RUNNER POOL IS NEVER PUSHED, the
  schedule's pool or the chair's (waymark ticket 962e0aeb). The chair's
  row is read here anyway, so asking it costs nothing. The reading is
  `schedules/fires-out?`'s, spelled here rather than required: seats
  do not depend on the consumer."
  [eng seat]
  (let [schedule (decoded-row eng :schedule (get-in seat [:data :schedule]))
        held (decoded-row eng :model (chair-of seat))
        st (:storage eng)
        by-hand? (boolean (when (contains? (inv/resources eng) :runner_link)
                            (pool-from #(store/with-tx st
                                          (fn [tx] (store/query-rows st tx :runner_link {}
                                                                     {:limit 100000})))
                                       schedule held)))
        copy (decoded-row eng :model (get-in schedule [:data :model]))]
    (if by-hand?
      (or held copy)
      (or copy held))))

(defn standing-seat-grant
  "The grant this sitter already holds FOR THIS SEAT, or nil.
  `standing-grant-for` answers the newest that still confers or could
  — accepted first, then a still-offered one, unexpired by the live
  clock — and a grant citing a different seat is not this seat's, so
  a sitter moved between offices mints again rather than sitting in
  the old one.

  A GRANT NEAR ITS END IS NOT STANDING (ticket acc89add): one whose
  `expires_at` is less than half the seat's `standing_ttl_seconds`
  away would expire mid-run and take every power with it, so the sit
  mints afresh. The old grant is left to expire on its own clock: an
  open sitting of another run may still wear it. A seat with no ttl
  reuses as before. The near-end test is put INSIDE the pick, not after
  it: the accepted old grant is otherwise always picked over the fresh
  offered one, and every sit after it would mint again."
  [eng sitter-id seat ^Instant now]
  (let [ttl (long (or (get-in seat [:data :standing_ttl_seconds]) 0))
        long-enough? (fn [g]
                       (leases/half-left? now (get-in g [:data :expires_at]) ttl))]
    (when-some [g (grants/standing-grant-for eng sitter-id long-enough?)]
      (when (= (str (:id seat)) (str (get-in g [:data :seat])))
        g))))

(defn- retire-seat-grants!
  "Revoke this sitter's older full grants FOR THIS SEAT that no open
  sitting wears, once `fresh` is minted beside them (ticket b72a4943):
  a near-end grant left to its own clock made every seat collect a new
  standing grant every few days. One an open sitting of another run
  still wears is left alone, and the next mint retires it. Only a grant
  `standing-seat-grant` would no longer pick (less than half its ttl
  left) is retired: a fresh one is a simultaneous sit's, minted before
  its sitting opens, and revoking it left that sit walking nothing. →
  the count revoked."
  [eng sitter-id seat fresh ^Instant now]
  (let [st (:storage eng)
        seat-id (str (:id seat))
        ttl (long (or (get-in seat [:data :standing_ttl_seconds]) 0))
        [mine worn] (store/with-tx st
                      (fn [tx]
                        [(store/query-rows st tx :grant {:audience (str sitter-id)}
                                           {:limit 100})
                         (when (get (inv/resources eng) :sitting)
                           (into #{} (map #(str (get-in % [:data :grant])))
                                 (store/query-rows st tx :sitting
                                                   {:seat seat-id :state :open}
                                                   {:limit 100})))]))
        stale (filter #(and (not= (str (:id fresh)) (str (:id %)))
                            (contains? #{"offered" "accepted"} (name (:state %)))
                            (= seat-id (str (get-in % [:data :seat])))
                            (not (true? (get-in % [:data :substitute])))
                            (pos? ttl)
                            (not (leases/half-left? now (get-in % [:data :expires_at]) ttl))
                            (not (contains? worn (str (:id %)))))
                      mine)]
    (count
     (filter (fn [g]
               (try (inv/invoke! eng :grant (str (:id g)) :revoke {}
                                 {:principal grants/approvals-actor})
                    true
                    (catch clojure.lang.ExceptionInfo _ false)))
             stale))))

(defn mint-seat-grant!
  "The seat grant, minted the way an approved seat ask mints one
  (grants/approval-effects!, R-5.4): the approvals actor, the sitter
  as audience, the seat as a ref, no scope at all, and the seat's own
  `standing_ttl_seconds` as the expiry. The sitter's older grants for
  the seat that no open sitting wears are revoked beside it
  (`retire-seat-grants!`), so a seat holds one standing grant.

  It is left OFFERED, deliberately: accepting is the audience's own
  act (the accept guard's rule), and `worn-visibility` performs it on
  the sitter's first request the way the guest door does. The key was
  the person's consent to the seat; the acceptance is the sitter's."
  [eng sitter-id seat ^Instant now]
  (let [ttl (long (or (get-in seat [:data :standing_ttl_seconds]) 0))
        fresh (:row (inv/create! eng :grant
                                 (cond-> {:audience (str sitter-id)
                                          :seat (str (:id seat))
                                          :substitute false}
                                   (pos? ttl)
                                   (assoc :expires_at (str (.plusSeconds now ttl))))
                                 {:principal grants/approvals-actor}))]
    (retire-seat-grants! eng sitter-id seat fresh now)
    fresh))

(defn fire-promise
  "The id of the promise a fire's text names, or nil: a promise's wake
  is JSON that carries `promise` (R-P.10.1). Prose names none."
  [text]
  (when-some [s (some-> text str str/trim not-empty)]
    (when (str/starts-with? s "{")
      (let [m (try (wire/read-json s) (catch Exception _ nil))]
        (when (map? m) (some-> (:promise m) str not-empty))))))

(defn open-fire-sitting!
  "The sitting ONE fire opens before any session sits in it, or nil when
  this fire opens none and its sit opens the sitting as before: a seat
  with no instructions mints no key, a seat that names no model opens
  no sitting, and a seat whose sitter row does not exist yet is left to
  its first sit, which mints the sitter acting for the person behind it.

  It is born as the sit's would be: the sitter as its principal, with
  no person behind it yet, under the seat grant (`standing-seat-grant`,
  else `mint-seat-grant!`). Its lease is the sit deadline and not the
  idle limit: `expires_at` is the fire plus `sit-deadline-seconds`, so a
  fire nobody sits on lapses exactly when it is missed, and `expire`
  ends it (`missed-onto`). The sit that spends the fire's key takes it
  (`claim-sitting!` with stamps), and its claim lets the lease again.

  `text` is the fire's own. A promise's wake names its promise there,
  and the sitting keeps it as `promise` (R-P.10.1, `fire-promise`)."
  [eng seat-row at & [text]]
  (when (and seat-row
             (some-> (get-in seat-row [:data :instructions]) str not-empty)
             (get (inv/resources eng) :seat)
             (get (inv/resources eng) :sitting))
    (when-some [model (seat-model eng seat-row)]
      (let [sid (sitter-id seat-row)]
        ;; a first-ever fire mints no sitter with nobody behind it
        (when (decoded-row eng :member sid)
          (let [st (:storage eng)
                ^Instant at (or (instant-of at) (now-of eng))
                now (now-of eng)
                sitter (t/principal {:id sid :type :agent
                                     :display (sitter-display seat-row)
                                     :model (some-> (get-in model [:data :name])
                                                    str not-empty)
                                     :roles (members/held-roles eng sid)})
                grant (or (standing-seat-grant eng sid seat-row now)
                          (mint-seat-grant! eng sid seat-row now))
                row (:row (inv/create! eng :sitting
                                       {:seat (str (:id seat-row))
                                        :model (str (:id model))
                                        :grant (str (:id grant))}
                                       {:principal sitter}))
                ends (str (.plusSeconds at (long sit-deadline-seconds)))
                promise-id (fire-promise text)]
            ;; the birth stamped the idle limit; the fire's lease is the
            ;; sit deadline, and a promise's wake stamps its promise
            (store/with-tx st
              (fn [tx]
                (when-some [r (store/load-row st tx :sitting (str (:id row))
                                              {:for-update true})]
                  (store/maintain! :stamp st tx :sitting (str (:id row))
                                   (cond-> (assoc (:data r) :expires_at ends)
                                     promise-id (assoc :promise promise-id))
                                   nil))))
            (cond-> (assoc-in row [:data :expires_at] ends)
              promise-id (assoc-in [:data :promise] promise-id))))))))

(defn fire-key-sitting
  "The id of the sitting the fire that minted `key` opened, or nil
  (`open-fire-sitting!`). A READ, like `fire-key-row`: the sit asks it
  BEFORE it spends the key."
  [eng seat-row key]
  (when seat-row
    (some-> (fire-key-entry seat-row key ((:now-fn eng))) :sitting str not-empty)))

(defn fire-held-sittings
  "The ids of the sittings this seat's unspent fire keys opened. Each is
  its own fire's run's, and no other sit reuses it."
  [seat-row]
  (into #{} (keep #(some-> (:sitting %) str not-empty))
        (get-in seat-row [:data :fire_keys])))

(defn mark-not-started-fire-key!
  "Write on the key `held` ({:seat :hash}) names that its localfire
  stopped before it knew whether the run started, and why. The sitting
  the fire opened ends at the sit deadline through `expire`, which reads
  it (`missed-onto`). → true when the entry was there."
  [eng held reason]
  (let [s (or (some-> reason str not-empty) "no reason given")]
    (rewrite-fire-key! eng held
                       #(assoc % :not_started (subs s 0 (min 240 (count s)))))))

(defn drop-fire-key-of-sitting!
  "Take off the seat row the unspent key entry that names `sitting-id`
  as its fire's sitting, in one transaction FOR UPDATE, so a late sit
  with its key is refused (`wakes/missed-expired!`). → the entry, or nil."
  [eng seat-id sitting-id]
  (when (get (inv/resources eng) :seat)
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :seat (str seat-id)
                                        {:for-update true})]
          (when-some [entry (fire-entry-of row sitting-id)]
            (store/maintain! :credential (:storage eng) tx :seat (str seat-id)
                                (assoc (:data row) :fire_keys
                                       (into [] (remove #(= entry %))
                                             (get-in row [:data :fire_keys])))
                                (:next-flip-at row))
            entry))))))
